#include "audio/loudness.h"

#include <algorithm>
#include <cmath>

namespace uv::audio {

namespace {

constexpr double kPi = 3.14159265358979323846;

// K-weighting stage 1: a high shelf (+4 dB above ~1.7 kHz) that models the head. Same formulas as
// the BS.1770 reference implementations, valid at any sample rate.
dsp::BiquadCoeffs shelfCoeffs(double rate) {
    const double f0 = 1681.974450955533;
    const double G = 3.999843853973347;
    const double Q = 0.7071752369554196;
    const double K = std::tan(kPi * f0 / rate);
    const double Vh = std::pow(10.0, G / 20.0);
    const double Vb = std::pow(Vh, 0.4996667741545416);
    const double a0 = 1.0 + K / Q + K * K;
    dsp::BiquadCoeffs c;
    c.b0 = (Vh + Vb * K / Q + K * K) / a0;
    c.b1 = 2.0 * (K * K - Vh) / a0;
    c.b2 = (Vh - Vb * K / Q + K * K) / a0;
    c.a1 = 2.0 * (K * K - 1.0) / a0;
    c.a2 = (1.0 - K / Q + K * K) / a0;
    return c;
}

// Stage 2: the RLB weighting, a 2nd order high-pass near 38 Hz.
dsp::BiquadCoeffs highpassCoeffs(double rate) {
    const double f0 = 38.13547087602444;
    const double Q = 0.5003270373238773;
    const double K = std::tan(kPi * f0 / rate);
    const double a0 = 1.0 + K / Q + K * K;
    dsp::BiquadCoeffs c;
    c.b0 = 1.0;
    c.b1 = -2.0;
    c.b2 = 1.0;
    c.a1 = 2.0 * (K * K - 1.0) / a0;
    c.a2 = (1.0 - K / Q + K * K) / a0;
    return c;
}

double lufsOf(double meanEnergy) { return -0.691 + 10.0 * std::log10(std::max(meanEnergy, 1e-30)); }

}  // namespace

LoudnessMeter::LoudnessMeter(int sampleRate)
    : shelf_(shelfCoeffs(sampleRate)),
      highpass_(highpassCoeffs(sampleRate)),
      subLen_(std::max(1, sampleRate / 10)) {}

void LoudnessMeter::addFrames(const float* stereo, int64_t frames) {
    for (int64_t i = 0; i < frames; ++i) {
        for (int ch = 0; ch < 2; ++ch) {
            const double x = stereo[2 * i + ch];
            peak_ = std::max(peak_, std::fabs(x));
            const double y = dsp::biquadProcess(highpass_, chan_[ch].highpass, dsp::biquadProcess(shelf_, chan_[ch].shelf, x));
            subEnergy_ += y * y;
        }
        if (++subCount_ == subLen_) {
            sub_.push_back(subEnergy_ / static_cast<double>(subLen_));  // channel weights are 1.0 for L and R
            subEnergy_ = 0;
            subCount_ = 0;
        }
    }
}

double LoudnessMeter::integratedLufs() const {
    // 400 ms blocks every 100 ms: the mean of four consecutive sub-blocks.
    std::vector<double> blocks;
    if (sub_.size() >= 4) {
        for (size_t i = 0; i + 4 <= sub_.size(); ++i) blocks.push_back((sub_[i] + sub_[i + 1] + sub_[i + 2] + sub_[i + 3]) / 4.0);
    } else if (!sub_.empty()) {
        double sum = 0;
        for (double e : sub_) sum += e;
        blocks.push_back(sum / static_cast<double>(sub_.size()));  // a clip shorter than 400 ms: one short block
    } else if (subCount_ > 0) {
        blocks.push_back(subEnergy_ / static_cast<double>(subCount_));
    }
    if (blocks.empty()) return kSilence;

    // Absolute gate.
    double sum = 0;
    size_t n = 0;
    for (double e : blocks) {
        if (lufsOf(e) > -70.0) {
            sum += e;
            ++n;
        }
    }
    if (n == 0) return kSilence;
    // Relative gate: 10 LU below the loudness of the absolute-gated blocks.
    const double relative = lufsOf(sum / static_cast<double>(n)) - 10.0;
    double sum2 = 0;
    size_t n2 = 0;
    for (double e : blocks) {
        if (lufsOf(e) > -70.0 && lufsOf(e) > relative) {
            sum2 += e;
            ++n2;
        }
    }
    return n2 == 0 ? kSilence : lufsOf(sum2 / static_cast<double>(n2));
}

}  // namespace uv::audio
