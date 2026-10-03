#include "audio/waveform_extractor.h"

#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <optional>
#include <vector>

#define LOG_TAG "uv_waveform"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace uv::audio {

namespace {

using core::Status;

constexpr int32_t kPcmEncodingFloat = 4;  // AudioFormat.ENCODING_PCM_FLOAT
constexpr int kMaxIdleDequeues = 300;      // ~3 s of no output after input is drained

struct ExtractorDeleter { void operator()(AMediaExtractor* e) const { AMediaExtractor_delete(e); } };
struct CodecDeleter {
    void operator()(AMediaCodec* c) const {
        AMediaCodec_stop(c);
        AMediaCodec_delete(c);
    }
};
struct FormatDeleter { void operator()(AMediaFormat* f) const { AMediaFormat_delete(f); } };

}  // namespace

core::Status extractWaveform(int fd, const std::atomic<bool>& cancel, PeakPyramid* out) {
    if (out == nullptr || fd < 0) return Status::InvalidArgument;

    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        LOGE("fstat failed or empty file");
        return Status::IoError;
    }

    std::unique_ptr<AMediaExtractor, ExtractorDeleter> extractor(AMediaExtractor_new());
    if (!extractor) return Status::CodecError;
    if (AMediaExtractor_setDataSourceFd(extractor.get(), fd, 0, static_cast<off64_t>(st.st_size)) != AMEDIA_OK) {
        LOGE("setDataSourceFd failed");
        return Status::IoError;
    }

    const size_t trackCount = AMediaExtractor_getTrackCount(extractor.get());
    std::unique_ptr<AMediaFormat, FormatDeleter> trackFormat;
    const char* mime = nullptr;
    for (size_t i = 0; i < trackCount; ++i) {
        std::unique_ptr<AMediaFormat, FormatDeleter> f(AMediaExtractor_getTrackFormat(extractor.get(), i));
        const char* m = nullptr;
        if (f && AMediaFormat_getString(f.get(), AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr &&
            std::strncmp(m, "audio/", 6) == 0) {
            AMediaExtractor_selectTrack(extractor.get(), i);
            trackFormat = std::move(f);
            mime = m;
            break;
        }
    }
    if (!trackFormat) {
        LOGE("no audio track");
        return Status::UnsupportedFormat;
    }

    std::unique_ptr<AMediaCodec, CodecDeleter> codec(AMediaCodec_createDecoderByType(mime));
    if (!codec) {
        LOGE("no decoder for %s", mime);
        return Status::CodecError;
    }
    if (AMediaCodec_configure(codec.get(), trackFormat.get(), nullptr, nullptr, 0) != AMEDIA_OK ||
        AMediaCodec_start(codec.get()) != AMEDIA_OK) {
        LOGE("codec configure/start failed for %s", mime);
        return Status::CodecError;
    }

    int32_t sampleRate = 0, channels = 0, encoding = 2;
    AMediaFormat_getInt32(trackFormat.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
    AMediaFormat_getInt32(trackFormat.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels);

    std::optional<PeakBuilder> builder;
    std::vector<int16_t> converted;
    bool inputDone = false, outputDone = false;
    int idle = 0;

    while (!outputDone) {
        if (cancel.load(std::memory_order_relaxed)) return Status::Cancelled;

        if (!inputDone) {
            const ssize_t idx = AMediaCodec_dequeueInputBuffer(codec.get(), 10000);
            if (idx >= 0) {
                size_t cap = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(codec.get(), static_cast<size_t>(idx), &cap);
                const ssize_t n = buf ? AMediaExtractor_readSampleData(extractor.get(), buf, cap) : -1;
                if (n < 0) {
                    AMediaCodec_queueInputBuffer(codec.get(), static_cast<size_t>(idx), 0, 0, 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    AMediaCodec_queueInputBuffer(codec.get(), static_cast<size_t>(idx), 0, static_cast<size_t>(n),
                                                 static_cast<uint64_t>(AMediaExtractor_getSampleTime(extractor.get())), 0);
                    AMediaExtractor_advance(extractor.get());
                }
            }
        }

        AMediaCodecBufferInfo info{};
        const ssize_t oidx = AMediaCodec_dequeueOutputBuffer(codec.get(), &info, 10000);
        if (oidx >= 0) {
            idle = 0;
            if (info.size > 0) {
                if (!builder) {
                    if (sampleRate <= 0 || channels <= 0) {
                        LOGE("unknown pcm format (rate=%d ch=%d)", sampleRate, channels);
                        return Status::UnsupportedFormat;
                    }
                    builder.emplace(static_cast<uint32_t>(sampleRate), channels);
                }
                size_t outSize = 0;
                const uint8_t* data = AMediaCodec_getOutputBuffer(codec.get(), static_cast<size_t>(oidx), &outSize);
                if (data == nullptr || static_cast<size_t>(info.offset) + static_cast<size_t>(info.size) > outSize) {
                    return Status::CodecError;
                }
                data += info.offset;
                if (encoding == kPcmEncodingFloat) {
                    const size_t samples = static_cast<size_t>(info.size) / sizeof(float);
                    converted.resize(samples);
                    for (size_t i = 0; i < samples; ++i) {
                        float v;
                        std::memcpy(&v, data + i * sizeof(float), sizeof(float));
                        v = std::clamp(v, -1.0f, 1.0f);
                        converted[i] = static_cast<int16_t>(v * 32767.0f);
                    }
                    builder->addInterleaved(converted.data(), samples / static_cast<size_t>(channels));
                } else {
                    const size_t samples = static_cast<size_t>(info.size) / sizeof(int16_t);
                    converted.resize(samples);
                    std::memcpy(converted.data(), data, samples * sizeof(int16_t));
                    builder->addInterleaved(converted.data(), samples / static_cast<size_t>(channels));
                }
            }
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outputDone = true;
            AMediaCodec_releaseOutputBuffer(codec.get(), static_cast<size_t>(oidx), false);
        } else if (oidx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            std::unique_ptr<AMediaFormat, FormatDeleter> f(AMediaCodec_getOutputFormat(codec.get()));
            int32_t newRate = sampleRate, newCh = channels, newEnc = 2;
            if (f) {
                AMediaFormat_getInt32(f.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, &newRate);
                AMediaFormat_getInt32(f.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, &newCh);
                AMediaFormat_getInt32(f.get(), AMEDIAFORMAT_KEY_PCM_ENCODING, &newEnc);
            }
            if (builder && (newRate != sampleRate || newCh != channels)) {
                LOGE("pcm format changed mid-stream");
                return Status::UnsupportedFormat;
            }
            sampleRate = newRate;
            channels = newCh;
            encoding = newEnc;
        } else if (oidx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            if (inputDone && ++idle > kMaxIdleDequeues) {
                LOGE("decoder produced no output after end of input");
                return Status::CodecError;
            }
        } else if (oidx != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            LOGE("dequeueOutputBuffer error %zd", oidx);
            return Status::CodecError;
        }
    }

    if (!builder) return Status::UnsupportedFormat;  // audio track with no decodable samples
    *out = builder->finish();
    return Status::Ok;
}

}  // namespace uv::audio
