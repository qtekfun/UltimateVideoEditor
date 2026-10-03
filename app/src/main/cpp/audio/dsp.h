#pragma once

// Classical DSP building blocks of the audio tools (no ML, no third-party code): biquad filters
// (RBJ cookbook), per-clip EQ, balance pan law, a feed-forward bus compressor, a sidechain ducker,
// a brickwall master limiter and a peak meter. Everything is plain C++ with no allocation in the
// processing calls, so it runs on the audio thread and in the host tests alike. All processing is
// per sample, so the result does not depend on how the stream is cut into blocks (the offline
// export and the realtime stream give the same samples).

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

namespace uv::audio::dsp {

constexpr int kEqBands = 5;          // low shelf, three peaking bands, high shelf
constexpr int kMaxEqStages = 7;      // high-pass, low-pass and the five bands
constexpr float kMinEqGainDb = -18.0f;
constexpr float kMaxEqGainDb = 18.0f;
constexpr float kMinFilterHz = 20.0f;

inline double dbToLin(double db) { return std::pow(10.0, db / 20.0); }
inline double linToDb(double lin) { return 20.0 * std::log10(std::max(lin, 1e-12)); }

// ---------------------------------------------------------------- biquads

enum class FilterType { LowPass, HighPass, LowShelf, HighShelf, Peaking };

struct BiquadCoeffs {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;  // a0 normalised to 1
    bool isIdentity() const { return b0 == 1 && b1 == 0 && b2 == 0 && a1 == 0 && a2 == 0; }
};

struct BiquadState {
    double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
};

// Audio EQ cookbook designs. `q` is the quality factor (shelves use it as the shelf slope Q).
BiquadCoeffs designBiquad(FilterType type, double sampleRate, double freqHz, double gainDb, double q);

// |H(e^jw)| at `freqHz`; used by the tests and the UI curve.
double biquadMagnitude(const BiquadCoeffs& c, double sampleRate, double freqHz);

inline double biquadProcess(const BiquadCoeffs& c, BiquadState& s, double x) {
    const double y = c.b0 * x + c.b1 * s.x1 + c.b2 * s.x2 - c.a1 * s.y1 - c.a2 * s.y2;
    s.x2 = s.x1;
    s.x1 = x;
    s.y2 = s.y1;
    s.y1 = y;
    return y;
}

// ---------------------------------------------------------------- per-clip EQ

struct EqBandParams {
    float freqHz = 1000.0f;
    float gainDb = 0.0f;  // 0 = band off
    float q = 1.0f;
};

struct EqParams {
    float highPassHz = 0.0f;  // 0 = off
    float lowPassHz = 0.0f;   // 0 = off
    EqBandParams bands[kEqBands];

    bool isFlat() const {
        if (highPassHz > 0.0f || lowPassHz > 0.0f) return false;
        for (const EqBandParams& b : bands) {
            if (b.gainDb != 0.0f) return false;
        }
        return true;
    }
};

// The filter chain for one clip: coefficients computed once (control thread), state per channel.
struct EqChain {
    BiquadCoeffs coeffs[kMaxEqStages];
    int stages = 0;

    static EqChain design(const EqParams& p, double sampleRate);
    // Frequency response of the whole chain at `freqHz` (linear).
    double magnitude(double sampleRate, double freqHz) const;
};

struct EqState {
    BiquadState ch[2][kMaxEqStages];
    void reset() { *this = EqState{}; }
};

// Applies the chain to interleaved stereo in place.
void eqProcess(const EqChain& chain, EqState& state, float* stereo, int frames);

// ---------------------------------------------------------------- pan

// Balance law with constant centre level: pan -1 is hard left, +1 hard right, 0 leaves the signal
// untouched; the far channel falls off as a quarter cosine so the power moves smoothly across.
inline void panGains(float pan, float* left, float* right) {
    const float p = std::clamp(pan, -1.0f, 1.0f);
    const float halfPi = 1.57079632679f;
    *left = p > 0.0f ? std::cos(p * halfPi) : 1.0f;
    *right = p < 0.0f ? std::cos(-p * halfPi) : 1.0f;
}

// ---------------------------------------------------------------- dynamics

// Per-sample smoothing coefficient for a time constant in milliseconds.
inline double timeConstantCoef(double ms, double sampleRate) {
    if (ms <= 0.0) return 0.0;
    return std::exp(-1.0 / (ms * 0.001 * sampleRate));
}

struct CompressorParams {
    bool enabled = false;
    float thresholdDb = -18.0f;
    float ratio = 3.0f;       // >= 1
    float attackMs = 10.0f;
    float releaseMs = 120.0f;
    float makeupDb = 0.0f;
};

// Feed-forward peak compressor on a stereo bus (linked channels).
struct Compressor {
    CompressorParams p;
    double attack = 0, release = 0;
    double reductionDb = 0;  // current gain reduction, >= 0

    void configure(const CompressorParams& params, double sampleRate);
    void reset() { reductionDb = 0; }
    void process(float* stereo, int frames);
};

struct DuckerParams {
    float amountDb = 0.0f;      // 0 = off; the reduction applied to music while the voice speaks
    float thresholdDb = -35.0f; // voice level that triggers it
    float attackMs = 20.0f;
    float releaseMs = 400.0f;
};

// Sidechain ducker: follows the level of the voice bus and returns the gain for the music buses,
// per sample. Non-destructive gain automation derived from the voice itself.
struct Ducker {
    DuckerParams p;
    double envAttack = 0, envRelease = 0, gainAttack = 0, gainRelease = 0;
    double env = 0;   // voice level (linear peak follower)
    double gain = 1;  // current music gain (linear)

    void configure(const DuckerParams& params, double sampleRate);
    void reset() { env = 0; gain = 1; }
    // `voiceStereo` is the summed voice bus; fills `gains` (frames values) for the music buses.
    void process(const float* voiceStereo, int frames, float* gains);
};

// Brickwall limiter at a fixed ceiling: instantaneous attack, exponential release.
struct Limiter {
    float ceiling = 0.8912509f;  // -1 dBFS
    double release = 0;
    double gain = 1;

    void configure(float ceilingDb, double sampleRate, double releaseMs = 60.0);
    void reset() { gain = 1; }
    void process(float* stereo, int frames);
};

// Peak levels for the UI, readable from any thread. The audio thread publishes the maximum of each
// block; the reader decays/holds them.
struct PeakMeter {
    std::atomic<float> left{0.0f};
    std::atomic<float> right{0.0f};

    void publish(const float* stereo, int frames) {
        float l = 0.0f, r = 0.0f;
        for (int i = 0; i < frames; ++i) {
            l = std::max(l, std::fabs(stereo[2 * i]));
            r = std::max(r, std::fabs(stereo[2 * i + 1]));
        }
        // Keep the larger of the new block and the unread previous value so a reader polling slower
        // than the block rate does not miss peaks.
        for (float cur = left.load(std::memory_order_relaxed); l > cur && !left.compare_exchange_weak(cur, l, std::memory_order_relaxed);) {
        }
        for (float cur = right.load(std::memory_order_relaxed); r > cur && !right.compare_exchange_weak(cur, r, std::memory_order_relaxed);) {
        }
    }
    // Returns the peaks since the previous call and clears them.
    void take(float* l, float* r) {
        *l = left.exchange(0.0f, std::memory_order_relaxed);
        *r = right.exchange(0.0f, std::memory_order_relaxed);
    }
};

}  // namespace uv::audio::dsp
