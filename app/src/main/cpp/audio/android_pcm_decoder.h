#pragma once

// AMediaExtractor + AMediaCodec implementation of PcmDecoder: any audio track the platform can
// decode, delivered as interleaved stereo float. Mono is duplicated; layouts with more than two
// channels use the first two (no real downmix yet). Streams whose codec output rate differs from
// the container rate (implicit-SBR HE-AAC) are rejected with UnsupportedFormat.

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <chrono>
#include <memory>
#include <vector>

#include "audio/pcm_decoder.h"
#include "core/file_lock.h"

namespace uv::audio {

class AndroidPcmDecoder final : public PcmDecoder {
public:
    // Duplicates `fd` (the caller keeps ownership of its own). Returns nullptr and sets *status
    // on failure.
    static std::unique_ptr<AndroidPcmDecoder> open(int fd, core::Status* status);
    ~AndroidPcmDecoder() override;

    int32_t sampleRate() const override { return sampleRate_; }
    core::Status seekToMicros(int64_t micros) override;
    PcmReadResult read(float* dstStereo, int32_t maxFrames) override;

private:
    AndroidPcmDecoder() = default;
    core::Status init(int fd);
    // One feed + drain step; appends decoded stereo frames to pending_.
    core::Status pump();
    core::Status consumeOutput(const uint8_t* data, int32_t bytes, int64_t ptsUs);
    size_t pendingFrames() const { return (pending_.size() - pendingPos_) / 2; }

    int fd_ = -1;
    core::FileLock fileLock_;  // held around extractor calls that read the file (core/file_lock.h)
    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    int32_t sampleRate_ = 0;
    int32_t channels_ = 0;
    int32_t encoding_ = 2;  // AudioFormat.ENCODING_PCM_16BIT

    std::vector<float> pending_;
    size_t pendingPos_ = 0;
    bool inputDone_ = false;
    bool outputDone_ = false;

    // The raw PCM codec (WAV files, so every voice-over take) does not copy: its output buffer is the memory of the input buffer it came
    // from. Queuing the next input before the previous output was read overwrote that memory, and the audio came out in the wrong order
    // with stretches repeated. For it, one input is in flight at a time; other codecs copy and are fed as fast as they take input.
    bool rawPassthrough_ = false;
    int32_t inFlight_ = 0;  // inputs queued whose output has not been read (raw PCM only)

    // Exact positioning after a seek: the codec restarts at a sync point before the target.
    bool awaitFirstOutput_ = false;
    int64_t targetSample_ = 0;
    int64_t skipFrames_ = 0;
    std::chrono::steady_clock::time_point lastProgress_{};
};

}  // namespace uv::audio
