#pragma once

// ITU-R BS.1770-4 / EBU R128 integrated loudness of a stereo signal: K-weighting (shelf plus
// RLB high-pass), 400 ms blocks with 75 % overlap, an absolute gate at -70 LUFS and a relative
// gate 10 LU below the gated mean. Pure C++, offline use (the app measures a clip once, caches the
// number and turns it into a gain).

#include <cstdint>
#include <vector>

#include "audio/dsp.h"

namespace uv::audio {

class LoudnessMeter {
public:
    explicit LoudnessMeter(int sampleRate);

    // Interleaved stereo frames.
    void addFrames(const float* stereo, int64_t frames);

    // Integrated loudness in LUFS, or kSilence when nothing above the absolute gate was heard.
    double integratedLufs() const;
    // Highest sample magnitude seen (linear), for the report.
    double samplePeak() const { return peak_; }

    static constexpr double kSilence = -999.0;

private:
    struct Chan {
        dsp::BiquadState shelf, highpass;
    };

    int rate_;
    dsp::BiquadCoeffs shelf_, highpass_;
    Chan chan_[2];
    int64_t subLen_;                 // 100 ms in samples
    int64_t subCount_ = 0;           // samples accumulated in the current 100 ms sub-block
    double subEnergy_ = 0;           // sum over both channels of squared K-weighted samples
    std::vector<double> sub_;        // mean-square (summed over channels) of each finished 100 ms sub-block
    double peak_ = 0;
};

}  // namespace uv::audio
