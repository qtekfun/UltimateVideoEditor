#pragma once

// Noise suppression by spectral subtraction with a noise profile the user takes from a quiet
// region of the recording: classical DSP only (STFT, over-subtraction with a gain floor and
// temporal/frequency smoothing), no learned model. The same code runs in the decode worker for
// playback and for the export, and in the host tests.
//
// STFT: 1024-sample frames, hop 256, sqrt-Hann analysis and synthesis windows (they sum to a
// constant at 75 % overlap). The processor is aligned with its input: process() returns samples as
// they complete, flush() returns the tail, and together they return exactly as many frames as were
// fed (the 768-sample algorithmic latency is hidden by priming the history with zeros).

#include <cstddef>
#include <cstdint>
#include <vector>

#include "core/error.h"

namespace uv::audio {

constexpr int kDenoiseFft = 1024;
constexpr int kDenoiseHop = 256;
constexpr int kDenoiseBins = kDenoiseFft / 2 + 1;  // 513

// Average magnitude spectrum of `frames` mono samples (>= two STFT frames), written as
// kDenoiseBins magnitudes. InvalidArgument when the region is too short to profile.
core::Status computeNoiseProfile(const float* mono, size_t frames, float* magnitudeOut);

class SpectralDenoiser {
public:
    // `profile` is kDenoiseBins magnitudes from computeNoiseProfile. `strength` in (0, 1].
    SpectralDenoiser(const float* profile, float strength);

    void reset();
    // Interleaved stereo in; appends the finished, input-aligned frames (stereo) to `out`.
    void process(const float* stereo, size_t frames, std::vector<float>* out);
    // Completes the tail so that the total output equals the total input, then re-primes.
    void flush(std::vector<float>* out);

    int64_t framesIn() const { return in_; }

private:
    struct Channel {
        std::vector<float> pending;  // samples from the start of the next frame
        std::vector<float> acc;      // overlap-add accumulator, kDenoiseFft long
        std::vector<float> gainPrev; // smoothed gain of the previous frame, per bin
    };

    void prime();
    void runFrame(Channel& c, std::vector<float>* emit);

    std::vector<float> noisePower_;  // profile squared
    float alpha_, floor_;
    Channel ch_[2];
    int64_t in_ = 0;       // real frames fed
    int64_t out_ = 0;      // frames returned
    int64_t skip_ = 0;     // primed samples still to discard from the front
    std::vector<float> window_;
    std::vector<float> emit_[2];
};

}  // namespace uv::audio
