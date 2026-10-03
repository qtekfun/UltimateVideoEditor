#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace uv::captions {

constexpr int32_t kWhisperRate = 16000;

// Streaming converter from interleaved stereo float at `inRate` to the mono 16 kHz float stream
// speech models expect. Each output sample is the box average of the input span it covers, which
// low-passes before decimating (point sampling would alias sibilants into the speech band). The
// span edges are computed with exact integer arithmetic, so the output length and phase never
// drift however long the input is, and the result does not depend on how the input is chunked.
class Mono16kConverter {
public:
    explicit Mono16kConverter(int32_t inRate);

    // Consumes `frames` stereo frames and appends the finished mono samples to `out`.
    void process(const float* stereo, size_t frames, std::vector<float>* out);

    int64_t inputFrames() const { return inputFrames_; }
    int64_t outputSamples() const { return nextOut_; }

private:
    int64_t in_;
    int64_t out_;
    std::vector<float> mono_;  // mono_[0] is input sample monoStart_
    int64_t monoStart_ = 0;
    int64_t inputFrames_ = 0;
    int64_t nextOut_ = 0;
};

}  // namespace uv::captions
