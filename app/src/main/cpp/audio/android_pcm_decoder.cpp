#include "audio/android_pcm_decoder.h"
#include "core/fd_util.h"
#include "core/file_lock.h"

#include <android/log.h>
#include <media/NdkMediaFormat.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>

#include "audio/audio_time.h"

#define LOG_TAG "uv_audio_decoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace uv::audio {

namespace {

using core::Status;
using Clock = std::chrono::steady_clock;

constexpr int32_t kPcmEncoding16 = 2;
constexpr int32_t kPcmEncodingFloat = 4;
constexpr auto kReadBudget = std::chrono::milliseconds(40);  // max time one read() may block
constexpr auto kStallTimeout = std::chrono::seconds(3);       // codec silent after EOS was queued
constexpr int64_t kMaxPadSeconds = 2;
constexpr size_t kMaxPendingFrames = 16384;  // stop draining once this much PCM is waiting

}  // namespace

std::unique_ptr<AndroidPcmDecoder> AndroidPcmDecoder::open(int fd, Status* status) {
    std::unique_ptr<AndroidPcmDecoder> d(new AndroidPcmDecoder());
    const Status st = d->init(fd);
    if (st != Status::Ok) {
        if (status != nullptr) *status = st;
        return nullptr;
    }
    return d;
}

AndroidPcmDecoder::~AndroidPcmDecoder() {
    if (codec_ != nullptr) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
    }
    if (extractor_ != nullptr) AMediaExtractor_delete(extractor_);
    if (fd_ >= 0) close(fd_);
}

Status AndroidPcmDecoder::init(int fd) {
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        LOGE("fstat failed or empty file");
        return Status::IoError;
    }
    fd_ = core::openIndependent(fd);
    if (fd_ < 0) return Status::IoError;
    // Readers of one file that share a file offset must not interleave (core/file_lock.h); held while opening.
    fileLock_ = core::fileLockFor(fd_);
    std::unique_lock<std::mutex> ioLock(*fileLock_);

    extractor_ = AMediaExtractor_new();
    if (extractor_ == nullptr) return Status::CodecError;
    if (AMediaExtractor_setDataSourceFd(extractor_, fd_, 0, static_cast<off64_t>(st.st_size)) != AMEDIA_OK) {
        LOGE("setDataSourceFd failed");
        return Status::IoError;
    }

    AMediaFormat* format = nullptr;
    const char* mime = nullptr;
    const size_t tracks = AMediaExtractor_getTrackCount(extractor_);
    for (size_t i = 0; i < tracks && format == nullptr; ++i) {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(extractor_, i);
        const char* m = nullptr;
        if (f != nullptr && AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr &&
            std::strncmp(m, "audio/", 6) == 0) {
            AMediaExtractor_selectTrack(extractor_, i);
            format = f;
            mime = m;
        } else if (f != nullptr) {
            AMediaFormat_delete(f);
        }
    }
    if (format == nullptr) {
        LOGE("no audio track");
        return Status::UnsupportedFormat;
    }

    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate_);
    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channels_);
    Status result = Status::Ok;
    if (sampleRate_ <= 0 || channels_ <= 0) {
        LOGE("unknown pcm format (rate=%d ch=%d)", sampleRate_, channels_);
        result = Status::UnsupportedFormat;
    } else if ((codec_ = AMediaCodec_createDecoderByType(mime)) == nullptr) {
        LOGE("no decoder for %s", mime);
        result = Status::CodecError;
    } else if (AMediaCodec_configure(codec_, format, nullptr, nullptr, 0) != AMEDIA_OK ||
               AMediaCodec_start(codec_) != AMEDIA_OK) {
        LOGE("codec configure/start failed for %s", mime);
        result = Status::CodecError;
    }
    AMediaFormat_delete(format);
    lastProgress_ = Clock::now();
    return result;
}

Status AndroidPcmDecoder::seekToMicros(int64_t micros) {
    media_status_t sought;
    {
        core::FileGuard io(*fileLock_);
        sought = AMediaExtractor_seekTo(extractor_, micros, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
    }
    if (sought != AMEDIA_OK) {
        LOGE("extractor seek failed");
        return Status::IoError;
    }
    if (AMediaCodec_flush(codec_) != AMEDIA_OK) {
        LOGE("codec flush failed");
        return Status::CodecError;
    }
    pending_.clear();
    pendingPos_ = 0;
    inputDone_ = false;
    outputDone_ = false;
    awaitFirstOutput_ = true;
    targetSample_ = microsToSamples(micros, sampleRate_);
    skipFrames_ = 0;
    lastProgress_ = Clock::now();
    return Status::Ok;
}

PcmReadResult AndroidPcmDecoder::read(float* dst, int32_t maxFrames) {
    PcmReadResult r;
    const auto deadline = Clock::now() + kReadBudget;
    while (pendingFrames() == 0) {
        if (outputDone_) {
            r.eof = true;
            return r;
        }
        r.status = pump();
        if (r.status != Status::Ok) return r;
        if (Clock::now() > deadline) return r;  // nothing ready yet; caller retries
    }
    const int32_t n = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(maxFrames), pendingFrames()));
    std::memcpy(dst, pending_.data() + pendingPos_, static_cast<size_t>(n) * 2 * sizeof(float));
    pendingPos_ += static_cast<size_t>(n) * 2;
    if (pendingPos_ == pending_.size()) {
        pending_.clear();
        pendingPos_ = 0;
    }
    r.frames = n;
    r.eof = outputDone_ && pendingFrames() == 0;
    return r;
}

Status AndroidPcmDecoder::pump() {
    // Keep the codec's input queue full: one buffer per step would leave the decoder mostly idle
    // and cap throughput at a few times real time.
    bool queued = false;
    while (!inputDone_) {
        const ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_, 0);
        if (idx < 0) break;
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(idx), &cap);
        ssize_t n = -1;
        int64_t sampleTime = 0;
        if (buf != nullptr) {
            core::FileGuard io(*fileLock_);
            n = AMediaExtractor_readSampleData(extractor_, buf, cap);
            if (n >= 0) {
                sampleTime = AMediaExtractor_getSampleTime(extractor_);
                AMediaExtractor_advance(extractor_);
            }
        }
        if (n < 0) {
            AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(idx), 0, 0, 0,
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            inputDone_ = true;
        } else {
            AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(idx), 0, static_cast<size_t>(n),
                                         static_cast<uint64_t>(sampleTime), 0);
        }
        queued = true;
    }

    // Drain every output that is ready. Only the first dequeue may wait, and only briefly.
    bool first = true;
    for (;;) {
        AMediaCodecBufferInfo info{};
        const int64_t timeoutUs = first ? (queued ? 2000 : 5000) : 0;
        first = false;
        const ssize_t oidx = AMediaCodec_dequeueOutputBuffer(codec_, &info, timeoutUs);
        if (oidx >= 0) {
            lastProgress_ = Clock::now();
            Status st = Status::Ok;
            if (info.size > 0) {
                size_t outSize = 0;
                const uint8_t* data = AMediaCodec_getOutputBuffer(codec_, static_cast<size_t>(oidx), &outSize);
                if (data == nullptr || static_cast<size_t>(info.offset) + static_cast<size_t>(info.size) > outSize) {
                    st = Status::CodecError;
                } else {
                    st = consumeOutput(data + info.offset, info.size, info.presentationTimeUs);
                }
            }
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outputDone_ = true;
            AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(oidx), false);
            if (st != Status::Ok) return st;
            if (outputDone_ || pendingFrames() >= kMaxPendingFrames) return Status::Ok;
            continue;
        }
        if (oidx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat* f = AMediaCodec_getOutputFormat(codec_);
            int32_t rate = sampleRate_, ch = channels_, enc = kPcmEncoding16;
            if (f != nullptr) {
                AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
                AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
                AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_PCM_ENCODING, &enc);
                AMediaFormat_delete(f);
            }
            if (rate != sampleRate_) {
                LOGE("codec output rate %d differs from container rate %d", rate, sampleRate_);
                return Status::UnsupportedFormat;
            }
            channels_ = ch;
            encoding_ = enc;
            continue;
        }
        if (oidx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (oidx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            if (inputDone_ && Clock::now() - lastProgress_ > kStallTimeout) {
                LOGE("decoder produced no output after end of input");
                return Status::CodecError;
            }
            return Status::Ok;
        }
        LOGE("dequeueOutputBuffer error %zd", oidx);
        return Status::CodecError;
    }
}

Status AndroidPcmDecoder::consumeOutput(const uint8_t* data, int32_t bytes, int64_t ptsUs) {
    if (encoding_ != kPcmEncoding16 && encoding_ != kPcmEncodingFloat) {
        LOGE("unsupported pcm encoding %d", encoding_);
        return Status::UnsupportedFormat;
    }
    if (channels_ <= 0) return Status::UnsupportedFormat;
    const size_t bytesPerSample = encoding_ == kPcmEncodingFloat ? sizeof(float) : sizeof(int16_t);
    const size_t frames = static_cast<size_t>(bytes) / (bytesPerSample * static_cast<size_t>(channels_));
    const size_t stride = bytesPerSample * static_cast<size_t>(channels_);

    if (awaitFirstOutput_) {
        awaitFirstOutput_ = false;
        const int64_t first = microsToSamples(ptsUs, sampleRate_);
        if (first < targetSample_) {
            skipFrames_ = targetSample_ - first;  // decoder restarted at a sync point before the target
        } else if (first > targetSample_) {
            // Stream starts later than requested (e.g. seek before the first sample): keep timing
            // by emitting silence for the gap, bounded so a bad timestamp cannot balloon memory.
            const int64_t gap = std::min<int64_t>(first - targetSample_, kMaxPadSeconds * sampleRate_);
            pending_.insert(pending_.end(), static_cast<size_t>(gap) * 2, 0.0f);
        }
    }

    auto sampleAt = [&](size_t frame, int32_t ch) -> float {
        const uint8_t* p = data + frame * stride + static_cast<size_t>(ch) * bytesPerSample;
        if (encoding_ == kPcmEncodingFloat) {
            float v;
            std::memcpy(&v, p, sizeof(float));
            return v;
        }
        int16_t v;
        std::memcpy(&v, p, sizeof(int16_t));
        return static_cast<float>(v) * (1.0f / 32768.0f);
    };

    size_t first = 0;
    if (skipFrames_ > 0) {
        first = static_cast<size_t>(std::min<int64_t>(skipFrames_, static_cast<int64_t>(frames)));
        skipFrames_ -= static_cast<int64_t>(first);
    }
    pending_.reserve(pending_.size() + (frames - first) * 2);
    for (size_t f = first; f < frames; ++f) {
        const float l = sampleAt(f, 0);
        pending_.push_back(l);
        pending_.push_back(channels_ > 1 ? sampleAt(f, 1) : l);
    }
    return Status::Ok;
}

}  // namespace uv::audio
