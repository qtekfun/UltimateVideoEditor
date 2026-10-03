#include "thumbnail/thumb_decoder.h"

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <sys/stat.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "thumbnail/yuv_tile.h"

#define LOG_TAG "uv_thumb"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace uv::thumb {

using core::Status;
using Clock = std::chrono::steady_clock;

namespace {

constexpr int64_t kContinueWindowUs = 1500000;  // decode forward instead of re-seeking within this
constexpr auto kTileDeadline = std::chrono::seconds(5);

// MediaCodecInfo.CodecCapabilities colour formats a CPU-readable decoder output can use.
constexpr int32_t kColorFormatYuv420Planar = 19;       // I420
constexpr int32_t kColorFormatYuv420SemiPlanar = 21;   // NV12
constexpr int32_t kColorFormatYuvP010 = 54;            // 10-bit semi-planar in 16-bit words
constexpr int32_t kColorFormatQcomNv12 = 0x7FA30C00;   // QTI linear NV12 variants
constexpr int32_t kColorFormatQcomNv12Venus = 0x7FA30C04;

// MediaFormat keys without an NDK constant on every API level.
constexpr const char* kKeyStride = "stride";
constexpr const char* kKeySliceHeight = "slice-height";
constexpr const char* kKeyColorFormat = "color-format";
constexpr const char* kKeyCropLeft = "crop-left";
constexpr const char* kKeyCropTop = "crop-top";
constexpr const char* kKeyCropRight = "crop-right";
constexpr const char* kKeyCropBottom = "crop-bottom";

int32_t intOr(AMediaFormat* f, const char* key, int32_t fallback) {
    int32_t v = 0;
    return AMediaFormat_getInt32(f, key, &v) ? v : fallback;
}

}  // namespace

struct ThumbDecoder::Impl {
    AMediaExtractor* extractor = nullptr;
    AMediaCodec* codec = nullptr;
    int rotation = 0;
    int64_t durationUs = 0;

    bool positionValid = false;  // decoder is running forward from a known place
    bool inputEos = false;
    int64_t positionUs = -1;
    bool loggedFormat = false;

    ~Impl() {
        if (codec != nullptr) {
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
        }
        if (extractor != nullptr) AMediaExtractor_delete(extractor);
    }

    void seek(int64_t timeUs) {
        AMediaExtractor_seekTo(extractor, timeUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        AMediaCodec_flush(codec);
        positionValid = true;
        positionUs = -1;
        inputEos = false;
    }

    void feedInput() {
        if (inputEos) return;
        const ssize_t idx = AMediaCodec_dequeueInputBuffer(codec, 0);
        if (idx < 0) return;
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(codec, static_cast<size_t>(idx), &cap);
        const ssize_t n = buf != nullptr ? AMediaExtractor_readSampleData(extractor, buf, cap) : -1;
        if (n < 0) {
            AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(idx), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            inputEos = true;
        } else {
            AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(idx), 0, static_cast<size_t>(n),
                                         static_cast<uint64_t>(AMediaExtractor_getSampleTime(extractor)), 0);
            AMediaExtractor_advance(extractor);
        }
    }

    // Turns one decoder output buffer (linear YUV in CPU memory) into a tile.
    Status convert(size_t index, const AMediaCodecBufferInfo& info, uint16_t* out) {
        size_t capacity = 0;
        const uint8_t* data = AMediaCodec_getOutputBuffer(codec, index, &capacity);
        AMediaFormat* format = AMediaCodec_getOutputFormat(codec);
        if (data == nullptr || format == nullptr) {
            if (format != nullptr) AMediaFormat_delete(format);
            return Status::CodecError;
        }
        data += info.offset;
        capacity = capacity > static_cast<size_t>(info.offset) ? capacity - static_cast<size_t>(info.offset) : 0;

        const int32_t colour = intOr(format, kKeyColorFormat, 0);
        const int32_t width = intOr(format, AMEDIAFORMAT_KEY_WIDTH, 0);
        const int32_t height = intOr(format, AMEDIAFORMAT_KEY_HEIGHT, 0);
        const int32_t stride = std::max(intOr(format, kKeyStride, width), width);
        const int32_t sliceHeight = std::max(intOr(format, kKeySliceHeight, height), height);
        const int32_t cropLeft = intOr(format, kKeyCropLeft, 0);
        const int32_t cropTop = intOr(format, kKeyCropTop, 0);
        // MediaFormat crop edges are inclusive.
        const int32_t cropRight = intOr(format, kKeyCropRight, width - 1);
        const int32_t cropBottom = intOr(format, kKeyCropBottom, height - 1);
        AMediaFormat_delete(format);

        if (!loggedFormat) {
            loggedFormat = true;
            LOGI("decoder output: colour 0x%x %dx%d stride %d slice %d crop [%d,%d,%d,%d] rot %d", colour, width, height,
                 stride, sliceHeight, cropLeft, cropTop, cropRight, cropBottom, rotation);
        }

        YuvFrame f;
        f.cropLeft = cropLeft;
        f.cropTop = cropTop;
        f.cropWidth = cropRight - cropLeft + 1;
        f.cropHeight = cropBottom - cropTop + 1;
        f.y = data;
        f.yRowStride = stride;

        size_t needed = 0;
        switch (colour) {
            case kColorFormatYuv420SemiPlanar:
            case kColorFormatQcomNv12:
            case kColorFormatQcomNv12Venus: {
                const uint8_t* uv = data + static_cast<size_t>(stride) * sliceHeight;
                f.u = uv;
                f.v = uv + 1;
                f.uvRowStride = stride;
                f.uvPixelStride = 2;
                needed = static_cast<size_t>(stride) * sliceHeight * 3 / 2;
                break;
            }
            case kColorFormatYuv420Planar: {
                const uint8_t* u = data + static_cast<size_t>(stride) * sliceHeight;
                const size_t chromaPlane = static_cast<size_t>(stride / 2) * (sliceHeight / 2);
                f.u = u;
                f.v = u + chromaPlane;
                f.uvRowStride = stride / 2;
                f.uvPixelStride = 1;
                needed = static_cast<size_t>(stride) * sliceHeight * 3 / 2;
                break;
            }
            case kColorFormatYuvP010: {
                // 10-bit samples in the high bits of little-endian 16-bit words; stride is in bytes.
                const uint8_t* uv = data + static_cast<size_t>(stride) * sliceHeight;
                f.u = uv;
                f.v = uv + 2;
                f.uvRowStride = stride;
                f.uvPixelStride = 4;
                f.sampleBytes = 2;
                needed = static_cast<size_t>(stride) * sliceHeight * 3 / 2;
                break;
            }
            default:
                LOGE("unsupported decoder output colour format 0x%x", colour);
                return Status::UnsupportedFormat;
        }
        if (capacity < needed || width <= 0 || height <= 0) {
            LOGE("decoder buffer too small: %zu < %zu (%dx%d stride %d slice %d)", capacity, needed, width, height,
                 stride, sliceHeight);
            return Status::CodecError;
        }
        if (!yuvToTile(f, rotation, out)) {
            LOGE("unusable frame %dx%d crop %dx%d", width, height, f.cropWidth, f.cropHeight);
            return Status::CodecError;
        }
        return Status::Ok;
    }

    Status decodeAt(int64_t target, const std::atomic<bool>& cancel, uint16_t* out) {
        for (int attempt = 0; attempt < 2; ++attempt) {
            const bool continueForward = positionValid && !inputEos && positionUs >= 0 && positionUs <= target &&
                                         target - positionUs < kContinueWindowUs;
            if (!continueForward) seek(target);

            const auto deadline = Clock::now() + kTileDeadline;
            int64_t lastPts = -1;
            bool reachedEos = false;
            while (!reachedEos) {
                if (cancel.load(std::memory_order_relaxed)) return Status::Cancelled;
                if (Clock::now() > deadline) {
                    LOGE("tile decode timed out at %lld us", static_cast<long long>(target));
                    positionValid = false;
                    return Status::CodecError;
                }
                feedInput();
                AMediaCodecBufferInfo info{};
                const ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec, &info, 5000);
                if (idx < 0) continue;  // timeouts and format/buffer change notifications
                const bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                const int64_t pts = info.presentationTimeUs;
                const bool want = info.size > 0 && pts >= target;
                if (info.size > 0) {
                    lastPts = pts;
                    positionUs = pts;
                }
                // Only the wanted frame is ever read: every other output buffer is released untouched.
                const Status st = want ? convert(static_cast<size_t>(idx), info, out) : Status::Ok;
                AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(idx), false);
                if (want) return st;
                if (eos) reachedEos = true;
            }
            // The target lies past the last frame: show the last one instead.
            positionValid = false;
            if (lastPts < 0) break;
            target = lastPts;
        }
        return Status::CodecError;
    }
};

ThumbDecoder::ThumbDecoder(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
ThumbDecoder::~ThumbDecoder() = default;

int64_t ThumbDecoder::durationUs() const { return impl_->durationUs; }

std::unique_ptr<ThumbDecoder> ThumbDecoder::open(int fd, Status* status) {
    const auto fail = [&](Status s) {
        if (status != nullptr) *status = s;
        return std::unique_ptr<ThumbDecoder>();
    };
    struct stat st {};
    if (fd < 0 || ::fstat(fd, &st) != 0 || st.st_size <= 0) return fail(Status::IoError);

    auto impl = std::make_unique<Impl>();
    impl->extractor = AMediaExtractor_new();
    if (impl->extractor == nullptr) return fail(Status::CodecError);
    if (AMediaExtractor_setDataSourceFd(impl->extractor, fd, 0, static_cast<off64_t>(st.st_size)) != AMEDIA_OK) {
        return fail(Status::IoError);
    }

    AMediaFormat* format = nullptr;
    const char* mime = nullptr;
    const size_t tracks = AMediaExtractor_getTrackCount(impl->extractor);
    for (size_t i = 0; i < tracks; ++i) {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(impl->extractor, i);
        const char* m = nullptr;
        if (f != nullptr && AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr &&
            std::strncmp(m, "video/", 6) == 0) {
            AMediaExtractor_selectTrack(impl->extractor, i);
            format = f;
            mime = m;
            break;
        }
        if (f != nullptr) AMediaFormat_delete(f);
    }
    if (format == nullptr) return fail(Status::UnsupportedFormat);

    int32_t rot = 0;
    int64_t durationUs = 0;
    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_ROTATION, &rot);
    AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &durationUs);
    impl->rotation = rot;
    impl->durationUs = durationUs;
    {
        int32_t w = 0, h = 0;
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &w);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &h);
        LOGI("video track %s %dx%d rotation %d duration %lld ms", mime, w, h, rot, static_cast<long long>(durationUs / 1000));
    }

    // No output surface: frames come back as linear YUV in CPU memory, which also avoids the
    // vendor-compressed buffer formats a surface path can hand out.
    Status result = Status::Ok;
    impl->codec = AMediaCodec_createDecoderByType(mime);
    if (impl->codec == nullptr) {
        LOGE("no decoder for %s", mime);
        result = Status::CodecError;
    } else if (AMediaCodec_configure(impl->codec, format, nullptr, nullptr, 0) != AMEDIA_OK ||
               AMediaCodec_start(impl->codec) != AMEDIA_OK) {
        LOGE("decoder configure/start failed for %s", mime);
        AMediaCodec_delete(impl->codec);
        impl->codec = nullptr;  // never started: skip stop() in the destructor
        result = Status::CodecError;
    }
    AMediaFormat_delete(format);
    if (result != Status::Ok) return fail(result);

    if (status != nullptr) *status = Status::Ok;
    return std::unique_ptr<ThumbDecoder>(new ThumbDecoder(std::move(impl)));
}

Status ThumbDecoder::decodeTile(int64_t timeUs, const std::atomic<bool>& cancel, uint16_t* out) {
    if (out == nullptr) return Status::InvalidArgument;
    int64_t target = std::max<int64_t>(0, timeUs);
    if (impl_->durationUs > 0) target = std::min(target, std::max<int64_t>(0, impl_->durationUs - 1));
    return impl_->decodeAt(target, cancel, out);
}

}  // namespace uv::thumb
