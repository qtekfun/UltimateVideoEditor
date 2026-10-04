#include "audio/dsp.h"

namespace uv::audio::dsp {

namespace {
constexpr double kPi = 3.14159265358979323846;
}

BiquadCoeffs designBiquad(FilterType type, double sampleRate, double freqHz, double gainDb, double q) {
    // Keep the design inside the stable range (below Nyquist, positive Q).
    const double f = std::clamp(freqHz, 1.0, sampleRate * 0.499);
    const double qq = std::max(q, 0.05);
    const double w0 = 2.0 * kPi * f / sampleRate;
    const double cw = std::cos(w0);
    const double sw = std::sin(w0);
    const double alpha = sw / (2.0 * qq);
    const double A = std::pow(10.0, gainDb / 40.0);

    double b0 = 1, b1 = 0, b2 = 0, a0 = 1, a1 = 0, a2 = 0;
    switch (type) {
        case FilterType::LowPass:
            b0 = (1 - cw) / 2;
            b1 = 1 - cw;
            b2 = (1 - cw) / 2;
            a0 = 1 + alpha;
            a1 = -2 * cw;
            a2 = 1 - alpha;
            break;
        case FilterType::HighPass:
            b0 = (1 + cw) / 2;
            b1 = -(1 + cw);
            b2 = (1 + cw) / 2;
            a0 = 1 + alpha;
            a1 = -2 * cw;
            a2 = 1 - alpha;
            break;
        case FilterType::Peaking:
            b0 = 1 + alpha * A;
            b1 = -2 * cw;
            b2 = 1 - alpha * A;
            a0 = 1 + alpha / A;
            a1 = -2 * cw;
            a2 = 1 - alpha / A;
            break;
        case FilterType::LowShelf: {
            const double s = 2.0 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) - (A - 1) * cw + s);
            b1 = 2 * A * ((A - 1) - (A + 1) * cw);
            b2 = A * ((A + 1) - (A - 1) * cw - s);
            a0 = (A + 1) + (A - 1) * cw + s;
            a1 = -2 * ((A - 1) + (A + 1) * cw);
            a2 = (A + 1) + (A - 1) * cw - s;
            break;
        }
        case FilterType::HighShelf: {
            const double s = 2.0 * std::sqrt(A) * alpha;
            b0 = A * ((A + 1) + (A - 1) * cw + s);
            b1 = -2 * A * ((A - 1) + (A + 1) * cw);
            b2 = A * ((A + 1) + (A - 1) * cw - s);
            a0 = (A + 1) - (A - 1) * cw + s;
            a1 = 2 * ((A - 1) - (A + 1) * cw);
            a2 = (A + 1) - (A - 1) * cw - s;
            break;
        }
    }
    BiquadCoeffs c;
    c.b0 = b0 / a0;
    c.b1 = b1 / a0;
    c.b2 = b2 / a0;
    c.a1 = a1 / a0;
    c.a2 = a2 / a0;
    return c;
}

double biquadMagnitude(const BiquadCoeffs& c, double sampleRate, double freqHz) {
    const double w = 2.0 * kPi * freqHz / sampleRate;
    const double cw = std::cos(w), sw = std::sin(w);
    const double c2 = std::cos(2 * w), s2 = std::sin(2 * w);
    // H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2), z = e^{jw}
    const double nr = c.b0 + c.b1 * cw + c.b2 * c2;
    const double ni = -(c.b1 * sw + c.b2 * s2);
    const double dr = 1 + c.a1 * cw + c.a2 * c2;
    const double di = -(c.a1 * sw + c.a2 * s2);
    return std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
}

EqChain EqChain::design(const EqParams& p, double sampleRate) {
    EqChain chain;
    auto add = [&](const BiquadCoeffs& c) {
        if (chain.stages < kMaxEqStages) chain.coeffs[chain.stages++] = c;
    };
    constexpr double kButterworthQ = 0.70710678;
    if (p.highPassHz >= kMinFilterHz) add(designBiquad(FilterType::HighPass, sampleRate, p.highPassHz, 0, kButterworthQ));
    if (p.lowPassHz >= kMinFilterHz) add(designBiquad(FilterType::LowPass, sampleRate, p.lowPassHz, 0, kButterworthQ));
    static constexpr FilterType kTypes[kEqBands] = {FilterType::LowShelf, FilterType::Peaking, FilterType::Peaking,
                                                    FilterType::Peaking, FilterType::HighShelf};
    for (int i = 0; i < kEqBands; ++i) {
        const EqBandParams& b = p.bands[i];
        if (b.gainDb == 0.0f) continue;
        const double gain = std::clamp<double>(b.gainDb, kMinEqGainDb, kMaxEqGainDb);
        add(designBiquad(kTypes[i], sampleRate, b.freqHz, gain, b.q));
    }
    return chain;
}

BiquadCoeffs EqChain::designBand(int band, const EqBandParams& b, double sampleRate) {
    static constexpr FilterType kTypes[kEqBands] = {FilterType::LowShelf, FilterType::Peaking, FilterType::Peaking,
                                                    FilterType::Peaking, FilterType::HighShelf};
    if (b.gainDb == 0.0f) return BiquadCoeffs{};
    const double gain = std::clamp<double>(b.gainDb, kMinEqGainDb, kMaxEqGainDb);
    return designBiquad(kTypes[band], sampleRate, b.freqHz, gain, b.q);
}

EqChain EqChain::designAll(const EqParams& p, double sampleRate) {
    EqChain chain;
    constexpr double kButterworthQ = 0.70710678;
    if (p.highPassHz >= kMinFilterHz) chain.coeffs[chain.stages++] = designBiquad(FilterType::HighPass, sampleRate, p.highPassHz, 0, kButterworthQ);
    if (p.lowPassHz >= kMinFilterHz) chain.coeffs[chain.stages++] = designBiquad(FilterType::LowPass, sampleRate, p.lowPassHz, 0, kButterworthQ);
    chain.firstBandStage = chain.stages;
    for (int i = 0; i < kEqBands; ++i) chain.coeffs[chain.stages++] = designBand(i, p.bands[i], sampleRate);
    return chain;
}

double EqChain::magnitude(double sampleRate, double freqHz) const {
    double m = 1.0;
    for (int i = 0; i < stages; ++i) m *= biquadMagnitude(coeffs[i], sampleRate, freqHz);
    return m;
}

void eqProcess(const EqChain& chain, EqState& state, float* stereo, int frames) {
    if (chain.stages == 0) return;
    for (int i = 0; i < frames; ++i) {
        for (int ch = 0; ch < 2; ++ch) {
            double x = stereo[2 * i + ch];
            for (int s = 0; s < chain.stages; ++s) x = biquadProcess(chain.coeffs[s], state.ch[ch][s], x);
            stereo[2 * i + ch] = static_cast<float>(x);
        }
    }
}

// ---------------------------------------------------------------- dynamics

void Compressor::configure(const CompressorParams& params, double sampleRate) {
    p = params;
    p.ratio = std::max(p.ratio, 1.0f);
    attack = timeConstantCoef(p.attackMs, sampleRate);
    release = timeConstantCoef(p.releaseMs, sampleRate);
}

void Compressor::process(float* stereo, int frames) {
    if (!p.enabled) return;
    const double slope = 1.0 - 1.0 / p.ratio;
    const double makeup = dbToLin(p.makeupDb);
    for (int i = 0; i < frames; ++i) {
        const double level = std::max(std::fabs(static_cast<double>(stereo[2 * i])), std::fabs(static_cast<double>(stereo[2 * i + 1])));
        const double levelDb = linToDb(level);
        const double target = levelDb > p.thresholdDb ? (levelDb - p.thresholdDb) * slope : 0.0;
        const double coef = target > reductionDb ? attack : release;
        reductionDb = coef * reductionDb + (1.0 - coef) * target;
        const float g = static_cast<float>(dbToLin(-reductionDb) * makeup);
        stereo[2 * i] *= g;
        stereo[2 * i + 1] *= g;
    }
}

void Ducker::configure(const DuckerParams& params, double sampleRate) {
    p = params;
    envAttack = timeConstantCoef(2.0, sampleRate);          // the level detector reacts fast
    envRelease = timeConstantCoef(80.0, sampleRate);
    gainAttack = timeConstantCoef(std::max(p.attackMs, 1.0f), sampleRate);
    gainRelease = timeConstantCoef(std::max(p.releaseMs, 1.0f), sampleRate);
}

void Ducker::process(const float* voiceStereo, int frames, float* gains) {
    const double thresholdLin = dbToLin(p.thresholdDb);
    const double duckedLin = dbToLin(-std::max(p.amountDb, 0.0f));
    for (int i = 0; i < frames; ++i) {
        const double level = std::max(std::fabs(static_cast<double>(voiceStereo[2 * i])), std::fabs(static_cast<double>(voiceStereo[2 * i + 1])));
        const double coef = level > env ? envAttack : envRelease;
        env = coef * env + (1.0 - coef) * level;
        const double target = env > thresholdLin ? duckedLin : 1.0;
        const double gc = target < gain ? gainAttack : gainRelease;
        gain = gc * gain + (1.0 - gc) * target;
        gains[i] = static_cast<float>(gain);
    }
}

void Limiter::configure(float ceilingDb, double sampleRate, double releaseMs) {
    ceiling = static_cast<float>(dbToLin(ceilingDb));
    release = timeConstantCoef(releaseMs, sampleRate);
}

void Limiter::process(float* stereo, int frames) {
    for (int i = 0; i < frames; ++i) {
        const double peak = std::max(std::fabs(static_cast<double>(stereo[2 * i])), std::fabs(static_cast<double>(stereo[2 * i + 1])));
        const double needed = peak > ceiling ? ceiling / peak : 1.0;
        // Instant attack: never let a sample through above the ceiling. Release back to unity.
        gain = needed < gain ? needed : release * gain + (1.0 - release) * 1.0;
        if (gain > needed) gain = needed;  // release must not overshoot what this sample needs
        stereo[2 * i] = static_cast<float>(stereo[2 * i] * gain);
        stereo[2 * i + 1] = static_cast<float>(stereo[2 * i + 1] * gain);
    }
}

}  // namespace uv::audio::dsp
