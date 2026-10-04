#include "stabilise/luma_decoder.h"

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "stabilise/luma.h"
#include "thumbnail/yuv_tile.h"

#define LOG_TAG "uv_stab"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace uv::stab {

using core::Status;
using Clock = std::chrono::steady_clock;

namespace {

constexpr auto kFrameDeadline = std::chrono::seconds(8);  // no output for this long: the codec is stuck

// MediaCodecInfo.CodecCapabilities colour formats a CPU-readable decoder output can use (as in the thumbnail decoder).
constexpr int32_t kColorFormatYuv420Planar = 19;
constexpr int32_t kColorFormatYuv420SemiPlanar = 21;
constexpr int32_t kColorFormatYuvP010 = 54;
constexpr int32_t kColorFormatQcomNv12 = 0x7FA30C00;
constexpr int32_t kColorFormatQcomNv12Venus = 0x7FA30C04;

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

struct LumaDecoder::Impl {
    AMediaExtractor* extractor = nullptr;
    AMediaCodec* codec = nullptr;
    int rotation = 0;
    int64_t durationUs = 0;
    int64_t firstPtsUs = 0;
    bool inputEos = false;
    bool loggedFormat = false;

    ~Impl() {
        if (codec != nullptr) {
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
        }
        if (extractor != nullptr) AMediaExtractor_delete(extractor);
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

    // Reads the luma of one output buffer into `out`.
    Status convert(size_t index, const AMediaCodecBufferInfo& info, int maxDimension, Gray* out) {
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
        const int32_t cropRight = intOr(format, kKeyCropRight, width - 1);  // MediaFormat crop edges are inclusive
        const int32_t cropBottom = intOr(format, kKeyCropBottom, height - 1);
        AMediaFormat_delete(format);
        if (!loggedFormat) {
            loggedFormat = true;
            LOGI("analysis decoder output: colour 0x%x %dx%d stride %d slice %d rot %d", colour, width, height, stride, sliceHeight, rotation);
        }

        thumb::YuvFrame f;
        f.cropLeft = cropLeft;
        f.cropTop = cropTop;
        f.cropWidth = cropRight - cropLeft + 1;
        f.cropHeight = cropBottom - cropTop + 1;
        f.y = data;
        f.yRowStride = stride;
        // Only the luma plane is read; the chroma pointers merely have to exist for a well-formed frame, and the
        // buffer must hold at least the luma plane.
        size_t lumaNeeded = static_cast<size_t>(stride) * static_cast<size_t>(sliceHeight);
        switch (colour) {
            case kColorFormatYuv420SemiPlanar:
            case kColorFormatQcomNv12:
            case kColorFormatQcomNv12Venus:
            case kColorFormatYuv420Planar:
                break;
            case kColorFormatYuvP010:
                f.sampleBytes = 2;  // 10-bit samples in the high bits of little-endian 16-bit words; stride is in bytes
                break;
            default:
                LOGE("unsupported decoder output colour format 0x%x", colour);
                return Status::UnsupportedFormat;
        }
        if (capacity < lumaNeeded || width <= 0 || height <= 0) {
            LOGE("decoder buffer too small: %zu < %zu (%dx%d)", capacity, lumaNeeded, width, height);
            return Status::CodecError;
        }
        if (!lumaToGray(f, rotation, maxDimension, out)) return Status::CodecError;
        return Status::Ok;
    }
};

LumaDecoder::LumaDecoder(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
LumaDecoder::~LumaDecoder() = default;

int64_t LumaDecoder::durationUs() const { return impl_->durationUs; }

std::unique_ptr<LumaDecoder> LumaDecoder::open(int fd, Status* status) {
    const auto fail = [&](Status s) {
        if (status != nullptr) *status = s;
        return std::unique_ptr<LumaDecoder>();
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
        if (f != nullptr && AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr && std::strncmp(m, "video/", 6) == 0) {
            AMediaExtractor_selectTrack(impl->extractor, i);
            format = f;
            mime = m;
            break;
        }
        if (f != nullptr) AMediaFormat_delete(f);
    }
    if (format == nullptr) return fail(Status::UnsupportedFormat);  // no picture to analyse

    int32_t rot = 0;
    int64_t durationUs = 0;
    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_ROTATION, &rot);
    AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &durationUs);
    impl->rotation = rot;
    impl->durationUs = durationUs;
    // The preview decoder numbers frames from the first sample's time, so time zero here is the same instant.
    impl->firstPtsUs = std::max<int64_t>(AMediaExtractor_getSampleTime(impl->extractor), 0);

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
    return std::unique_ptr<LumaDecoder>(new LumaDecoder(std::move(impl)));
}

Status LumaDecoder::run(int64_t startUs, int64_t endUs, int maxDimension, const std::atomic<bool>& cancel, const FrameFn& onFrame) {
    Impl& d = *impl_;
    startUs = std::max<int64_t>(0, startUs);
    AMediaExtractor_seekTo(d.extractor, d.firstPtsUs + startUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
    AMediaCodec_flush(d.codec);
    d.inputEos = false;

    auto lastOutput = Clock::now();
    Gray gray;
    while (true) {
        if (cancel.load(std::memory_order_relaxed)) return Status::Cancelled;
        if (Clock::now() - lastOutput > kFrameDeadline) {
            LOGE("analysis decode timed out at %lld us", static_cast<long long>(startUs));
            return Status::CodecError;
        }
        d.feedInput();
        AMediaCodecBufferInfo info{};
        const ssize_t idx = AMediaCodec_dequeueOutputBuffer(d.codec, &info, 5000);
        if (idx < 0) continue;  // timeouts and format/buffer change notifications
        lastOutput = Clock::now();
        const bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
        const int64_t rel = info.presentationTimeUs - d.firstPtsUs;
        bool stop = false;
        Status st = Status::Ok;
        if (info.size > 0 && rel >= startUs) {
            if (endUs > 0 && rel > endUs) {
                stop = true;
            } else {
                st = d.convert(static_cast<size_t>(idx), info, maxDimension, &gray);
                if (st == Status::Ok && !onFrame(rel, gray)) stop = true;
            }
        }
        AMediaCodec_releaseOutputBuffer(d.codec, static_cast<size_t>(idx), false);
        if (st != Status::Ok) return st;
        if (stop || eos) return Status::Ok;
    }
}

}  // namespace uv::stab
