#include "audio/spectral_denoise.h"

#include <algorithm>
#include <cmath>
#include <complex>

namespace uv::audio {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int N = kDenoiseFft;
constexpr int H = kDenoiseHop;
constexpr int kLatency = N - H;
using Cx = std::complex<float>;

// In-place iterative radix-2 FFT. `inverse` uses the conjugate trick and scales by 1/N.
void fft(Cx* a, bool inverse) {
    static const std::vector<Cx> twiddle = [] {
        std::vector<Cx> t(N / 2);
        for (int k = 0; k < N / 2; ++k) t[k] = Cx(static_cast<float>(std::cos(-2.0 * kPi * k / N)), static_cast<float>(std::sin(-2.0 * kPi * k / N)));
        return t;
    }();
    for (int i = 1, j = 0; i < N; ++i) {
        int bit = N >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    if (inverse) {
        for (int i = 0; i < N; ++i) a[i] = std::conj(a[i]);
    }
    for (int len = 2; len <= N; len <<= 1) {
        const int step = N / len;
        for (int i = 0; i < N; i += len) {
            for (int k = 0; k < len / 2; ++k) {
                const Cx u = a[i + k];
                const Cx v = a[i + k + len / 2] * twiddle[k * step];
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
            }
        }
    }
    if (inverse) {
        for (int i = 0; i < N; ++i) a[i] = std::conj(a[i]) / static_cast<float>(N);
    }
}

const std::vector<float>& sqrtHann() {
    static const std::vector<float> w = [] {
        std::vector<float> v(N);
        for (int i = 0; i < N; ++i) v[i] = static_cast<float>(std::sqrt(0.5 - 0.5 * std::cos(2.0 * kPi * i / N)));
        return v;
    }();
    return w;
}

}  // namespace

core::Status computeNoiseProfile(const float* mono, size_t frames, float* magnitudeOut) {
    if (mono == nullptr || magnitudeOut == nullptr || frames < static_cast<size_t>(2 * N)) return core::Status::InvalidArgument;
    const std::vector<float>& w = sqrtHann();
    std::vector<double> power(kDenoiseBins, 0.0);
    std::vector<Cx> buf(N);
    size_t count = 0;
    for (size_t start = 0; start + N <= frames; start += H) {
        for (int i = 0; i < N; ++i) buf[i] = Cx(mono[start + i] * w[i], 0.0f);
        fft(buf.data(), false);
        for (int k = 0; k < kDenoiseBins; ++k) power[k] += static_cast<double>(std::norm(buf[k]));
        ++count;
    }
    for (int k = 0; k < kDenoiseBins; ++k) magnitudeOut[k] = static_cast<float>(std::sqrt(power[k] / static_cast<double>(count)));
    return core::Status::Ok;
}

SpectralDenoiser::SpectralDenoiser(const float* profile, float strength) : window_(sqrtHann()) {
    const float s = std::clamp(strength, 0.0f, 1.0f);
    alpha_ = 1.0f + 5.0f * s;                                          // over-subtraction 1x .. 6x
    floor_ = static_cast<float>(std::pow(10.0, -(10.0 + 20.0 * s) / 20.0));  // gain floor -10 .. -30 dB
    noisePower_.resize(kDenoiseBins);
    for (int k = 0; k < kDenoiseBins; ++k) noisePower_[k] = profile[k] * profile[k];
    reset();
}

void SpectralDenoiser::prime() {
    for (Channel& c : ch_) {
        c.pending.assign(kLatency, 0.0f);
        c.acc.assign(N, 0.0f);
        c.gainPrev.assign(kDenoiseBins, 1.0f);
    }
    skip_ = kLatency;
}

void SpectralDenoiser::reset() {
    in_ = 0;
    out_ = 0;
    prime();
}

void SpectralDenoiser::runFrame(Channel& c, std::vector<float>* emit) {
    std::vector<Cx> buf(N);
    for (int i = 0; i < N; ++i) buf[i] = Cx(c.pending[i] * window_[i], 0.0f);
    fft(buf.data(), false);

    std::vector<float> gain(kDenoiseBins);
    for (int k = 0; k < kDenoiseBins; ++k) {
        const float mag2 = std::norm(buf[k]);
        const float noise2 = noisePower_[k];
        // Power spectral subtraction: keep what exceeds the (over-estimated) noise.
        float g = std::sqrt(std::max(floor_ * floor_, 1.0f - alpha_ * noise2 / (mag2 + 1e-12f)));
        // Fast attack (let a rising signal through at once), slow release (avoid musical noise).
        g = g > c.gainPrev[k] ? g : 0.6f * c.gainPrev[k] + 0.4f * g;
        gain[k] = g;
    }
    // Light smoothing across frequency, then remember it for the next frame.
    std::vector<float> smooth(kDenoiseBins);
    for (int k = 0; k < kDenoiseBins; ++k) {
        const float a = gain[std::max(k - 1, 0)], b = gain[k], d = gain[std::min(k + 1, kDenoiseBins - 1)];
        smooth[k] = 0.25f * a + 0.5f * b + 0.25f * d;
    }
    c.gainPrev = smooth;
    for (int k = 0; k < kDenoiseBins; ++k) {
        buf[k] *= smooth[k];
        if (k > 0 && k < N / 2) buf[N - k] = std::conj(buf[k]);
    }
    fft(buf.data(), true);
    // sqrt-Hann squared is Hann, whose overlap-add at hop N/4 sums to 2.
    for (int i = 0; i < N; ++i) c.acc[i] += buf[i].real() * window_[i] * 0.5f;

    emit->insert(emit->end(), c.acc.begin(), c.acc.begin() + H);
    c.acc.erase(c.acc.begin(), c.acc.begin() + H);
    c.acc.resize(N, 0.0f);
    c.pending.erase(c.pending.begin(), c.pending.begin() + H);
}

void SpectralDenoiser::process(const float* stereo, size_t frames, std::vector<float>* out) {
    for (int ch = 0; ch < 2; ++ch) {
        Channel& c = ch_[ch];
        for (size_t i = 0; i < frames; ++i) c.pending.push_back(stereo[2 * i + ch]);
        emit_[ch].clear();
        while (c.pending.size() >= static_cast<size_t>(N)) runFrame(c, &emit_[ch]);
    }
    in_ += static_cast<int64_t>(frames);
    const size_t produced = emit_[0].size();  // both channels advance in lockstep
    size_t from = 0;
    if (skip_ > 0) {
        from = static_cast<size_t>(std::min<int64_t>(skip_, static_cast<int64_t>(produced)));
        skip_ -= static_cast<int64_t>(from);
    }
    for (size_t i = from; i < produced; ++i) {
        out->push_back(emit_[0][i]);
        out->push_back(emit_[1][i]);
        ++out_;
    }
}

void SpectralDenoiser::flush(std::vector<float>* out) {
    // Feed silence until every real frame has come out, then drop the surplus and start over.
    const int64_t missing = in_ - out_;  // real frames whose output is still inside the pipeline
    std::vector<float> tail;
    std::vector<float> zeros(static_cast<size_t>(H) * 2, 0.0f);
    for (int guard = 0; guard < 8 && static_cast<int64_t>(tail.size() / 2) < missing; ++guard) {
        process(zeros.data(), H, &tail);
    }
    if (missing > 0) {
        const size_t take = std::min<size_t>(static_cast<size_t>(missing) * 2, tail.size());
        out->insert(out->end(), tail.begin(), tail.begin() + static_cast<std::ptrdiff_t>(take));
    }
    reset();
}

}  // namespace uv::audio
