#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace uv::audio {

// Streaming linear-interpolation resampler for interleaved stereo float. Output frame k maps
// to input position k * inRate / outRate computed with exact integer phase, so there is no
// drift however long the stream runs. Equal rates are a straight copy.
class LinearResampler {
public:
    LinearResampler(int32_t inRate, int32_t outRate);

    void reset();
    // Consumes `inFrames` stereo frames and appends the produced stereo frames to `out`.
    void process(const float* in, size_t inFrames, std::vector<float>* out);

private:
    int32_t inRate_;
    int32_t outRate_;
    std::vector<float> buf_;    // stereo history, first frame has input index bufStart_
    int64_t bufStart_ = 0;
    int64_t nextOut_ = 0;
};

}  // namespace uv::audio
