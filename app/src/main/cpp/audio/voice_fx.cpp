#include "audio/voice_fx.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstring>

namespace uv::audio {

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int N = kVoiceFft;
constexpr int H = kVoiceHop;
constexpr int K = N / 2 + 1;
constexpr double kOsamp = static_cast<double>(N) / H;
constexpr double kExpct = 2.0 * kPi * H / N;
// Hann analysis and synthesis windows overlap-added at 75 %: the squared window sums to 1.5.
constexpr float kSynthScale = 1.0f / 1.5f;
// Random-phase frames add incoherently, which is quieter than the coherent sum; this restores the level.
constexpr float kWhisperGain = 1.43f;
constexpr double kEpsilon = 1e-9;
using Cx = std::complex<double>;

// In-place iterative radix-2 FFT; `inverse` scales by 1/N.
void fft(Cx* a, bool inverse) {
    static const std::vector<Cx> twiddle = [] {
        std::vector<Cx> t(N / 2);
        for (int k = 0; k < N / 2; ++k) t[static_cast<size_t>(k)] = Cx(std::cos(-2.0 * kPi * k / N), std::sin(-2.0 * kPi * k / N));
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
                const Cx v = a[i + k + len / 2] * twiddle[static_cast<size_t>(k * step)];
                a[i + k] = u + v;
                a[i + k + len / 2] = u - v;
            }
        }
    }
    if (inverse) {
        for (int i = 0; i < N; ++i) a[i] = std::conj(a[i]) / static_cast<double>(N);
    }
}

const std::vector<float>& hann() {
    static const std::vector<float> w = [] {
        std::vector<float> v(N);
        for (int i = 0; i < N; ++i) v[static_cast<size_t>(i)] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * i / N));
        return v;
    }();
    return w;
}

inline float flushDenormal(float v) { return std::fabs(v) < 1e-20f ? 0.0f : v; }

bool finiteIn(float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; }
bool zeroOrIn(float v, float lo, float hi) { return v == 0.0f || finiteIn(v, lo, hi); }

// Reverb tuning (samples at 44.1 kHz, the classic Schroeder/Freeverb lengths).
constexpr int kCombTuning[4] = {1116, 1188, 1277, 1356};
constexpr int kAllpassTuning[2] = {556, 441};
constexpr int kStereoSpread = 23;
constexpr float kReverbInputGain = 0.25f;
constexpr float kReverbWetGain = 0.62f;

double reverbSizeScale(float size) { return 0.6 + 1.1 * static_cast<double>(size); }
double reverbFeedback(float size) { return 0.72 + 0.21 * static_cast<double>(size); }

// Everything after the spectral stage: ring modulation, band limit + drive, echo, reverb. Plain
// per-sample code, so the result never depends on how the stream is cut. With a schedule the settings are read
// every kBlock samples at the sample's clip-local position (origin + n), so animation does not depend on the
// cut either; without one they are read once.
struct PostChain {
    static constexpr int kBlock = 16;

    VoiceParams base, p;  // p: the settings in force for the current block
    const VoiceSchedule* sched = nullptr;
    double sr = 48000.0;
    bool ring = false, band = false, drive = false, echo = false, reverb = false;  // stages that can ever sound
    bool echoMsAnimated = false;
    int64_t n = 0;       // frames since reset
    int64_t origin = 0;  // clip-local position of frame 0

    dsp::BiquadCoeffs hp, lp;
    dsp::BiquadState hpState[2][2];
    dsp::BiquadState lpState[2][2];
    float lastLow = -1.0f, lastHigh = -1.0f, lastDrive = -1.0f;
    double driveGain = 1.0, driveNorm = 1.0;
    double ringPhase = 0.0;

    // The delay line holds the longest delay the settings ask for; the read position trails the write position by
    // `echoNow` samples (fractional, ramped over a block towards `echoTarget` so a moving delay glides).
    std::vector<float> delay[2];
    int delayCap = 0, delayPos = 0;
    double echoNow = 1.0, echoTarget = 1.0, echoStep = 0.0;

    struct Comb {
        std::vector<float> buf;
        int pos = 0;
        float filt = 0.0f;
    };
    struct Allpass {
        std::vector<float> buf;
        int pos = 0;
    };
    Comb comb[2][4];
    Allpass allpass[2][2];
    float combFeedback = 0.0f, combDamp = 0.0f;

    // `env` is `params` widened by the schedule (see VoiceSchedule::envelope).
    void configure(const VoiceParams& params, const VoiceParams& env, const VoiceSchedule* schedule, double rate) {
        base = params;
        p = params;
        sched = schedule;
        sr = rate;
        ring = env.hasRing();
        band = env.hasBand();
        drive = env.driveDb > 0.0f;
        echo = env.hasEcho();
        reverb = env.hasReverb();
        echoMsAnimated = sched != nullptr && sched->animates(8);
        if (echo) {
            delayCap = std::max(4, static_cast<int>(std::ceil(env.echoMs * 0.001 * sr)) + 2);
            for (auto& d : delay) d.assign(static_cast<size_t>(delayCap), 0.0f);
        }
        if (reverb) {
            // The comb lengths are fixed for the whole clip; an animated size moves the decay (the feedback), not the
            // room, and the room is the middle of the range the keys cover.
            double size = base.reverbSize;
            if (const VoiceLane* lane = sched != nullptr ? sched->find(11) : nullptr) {
                float lo = base.reverbSize, hi = lo;
                for (float v : lane->values) {
                    lo = std::min(lo, v);
                    hi = std::max(hi, v);
                }
                size = 0.5 * (static_cast<double>(lo) + hi);
            }
            const double scale = reverbSizeScale(static_cast<float>(size)) * sr / 44100.0;
            for (int c = 0; c < 2; ++c) {
                const int spread = c == 0 ? 0 : kStereoSpread;
                for (int i = 0; i < 4; ++i) comb[c][i].buf.assign(static_cast<size_t>(std::max(1, static_cast<int>(std::lround((kCombTuning[i] + spread) * scale)))), 0.0f);
                for (int i = 0; i < 2; ++i) allpass[c][i].buf.assign(static_cast<size_t>(std::max(1, static_cast<int>(std::lround((kAllpassTuning[i] + spread) * sr / 44100.0)))), 0.0f);
            }
        }
        reset(0);
    }

    void reset(int64_t position) {
        origin = position;
        n = 0;
        ringPhase = 0.0;
        lastLow = lastHigh = lastDrive = -1.0f;
        for (auto& row : hpState) for (auto& s : row) s = dsp::BiquadState{};
        for (auto& row : lpState) for (auto& s : row) s = dsp::BiquadState{};
        for (auto& d : delay) std::fill(d.begin(), d.end(), 0.0f);
        delayPos = 0;
        for (auto& row : comb) for (Comb& c : row) {
            std::fill(c.buf.begin(), c.buf.end(), 0.0f);
            c.pos = 0;
            c.filt = 0.0f;
        }
        for (auto& row : allpass) for (Allpass& a : row) {
            std::fill(a.buf.begin(), a.buf.end(), 0.0f);
            a.pos = 0;
        }
    }

    // Takes the settings at clip-local sample `pos` for the block that starts there.
    void updateBlock(int64_t pos, bool first) {
        if (sched != nullptr) p = sched->at(base, pos);
        if (band) {
            if (p.bandLowHz != lastLow) {
                if (p.bandLowHz > 0.0f) {
                    hp = dsp::designBiquad(dsp::FilterType::HighPass, sr, p.bandLowHz, 0.0, 0.7071);
                    if (lastLow <= 0.0f) for (auto& s : hpState) for (auto& st : s) st = dsp::BiquadState{};
                }
                lastLow = p.bandLowHz;
            }
            if (p.bandHighHz != lastHigh) {
                if (p.bandHighHz > 0.0f) {
                    lp = dsp::designBiquad(dsp::FilterType::LowPass, sr, std::min<double>(p.bandHighHz, sr * 0.45), 0.0, 0.7071);
                    if (lastHigh <= 0.0f) for (auto& s : lpState) for (auto& st : s) st = dsp::BiquadState{};
                }
                lastHigh = p.bandHighHz;
            }
        }
        if (drive && p.driveDb != lastDrive) {
            driveGain = dsp::dbToLin(p.driveDb);
            driveNorm = 1.0 / std::tanh(driveGain);
            lastDrive = p.driveDb;
        }
        if (echo) {
            double target = p.echoMs * 0.001 * sr;
            // A fixed delay is a whole number of samples, as it always was; only a moving one is fractional.
            if (!echoMsAnimated) target = std::round(target);
            target = std::clamp(target, 1.0, static_cast<double>(delayCap - 2));
            if (first) {
                echoNow = echoTarget = target;
                echoStep = 0.0;
            } else {
                echoNow = echoTarget;
                echoTarget = target;
                echoStep = (echoTarget - echoNow) / kBlock;
            }
        }
        if (reverb) {
            combFeedback = static_cast<float>(reverbFeedback(p.reverbSize));
            combDamp = std::clamp(p.reverbDamping, 0.0f, 1.0f) * 0.4f;
        }
    }

    void tick(float* lr) {
        if (n == 0 || (sched != nullptr && n % kBlock == 0)) updateBlock(origin + n, n == 0);
        float x[2] = {lr[0], lr[1]};
        if (ring && p.ringHz > 0.0f && p.ringMix > 0.0f) {
            const double carrier = std::sin(ringPhase);
            ringPhase += 2.0 * kPi * static_cast<double>(p.ringHz) / sr;
            if (ringPhase > 2.0 * kPi) ringPhase -= 2.0 * kPi;
            const float m = p.ringMix;
            for (float& v : x) v = static_cast<float>(v * (1.0 - m) + m * v * carrier);
        }
        ++n;
        if (band) {
            for (int c = 0; c < 2; ++c) {
                double v = x[c];
                if (p.bandLowHz > 0.0f) {
                    v = dsp::biquadProcess(hp, hpState[c][0], v);
                    v = dsp::biquadProcess(hp, hpState[c][1], v);
                }
                if (p.bandHighHz > 0.0f) {
                    v = dsp::biquadProcess(lp, lpState[c][0], v);
                    v = dsp::biquadProcess(lp, lpState[c][1], v);
                }
                x[c] = static_cast<float>(v);
            }
        }
        if (drive && p.driveDb > 0.0f) {
            for (float& v : x) v = static_cast<float>(std::tanh(driveGain * v) * driveNorm);
        }
        if (echo) {
            const bool on = p.echoMs > 0.0f;
            const float feedback = on ? p.echoFeedback : 0.0f;
            const float mix = on ? p.echoMix : 0.0f;
            double read = static_cast<double>(delayPos) - echoNow;
            if (read < 0.0) read += delayCap;
            int i0 = static_cast<int>(read);
            const float frac = static_cast<float>(read - i0);
            if (i0 >= delayCap) i0 -= delayCap;
            const int i1 = i0 + 1 >= delayCap ? 0 : i0 + 1;
            echoNow += echoStep;
            for (int c = 0; c < 2; ++c) {
                std::vector<float>& line = delay[c];
                const float d = frac == 0.0f ? line[static_cast<size_t>(i0)] : line[static_cast<size_t>(i0)] * (1.0f - frac) + line[static_cast<size_t>(i1)] * frac;
                line[static_cast<size_t>(delayPos)] = flushDenormal(x[c] + feedback * d);
                x[c] += mix * d;
            }
            if (++delayPos >= delayCap) delayPos = 0;
        }
        if (reverb) {
            for (int c = 0; c < 2; ++c) {
                const float in = x[c] * kReverbInputGain;
                float sum = 0.0f;
                for (Comb& cb : comb[c]) {
                    const float y = cb.buf[static_cast<size_t>(cb.pos)];
                    cb.filt = flushDenormal(y * (1.0f - combDamp) + cb.filt * combDamp);
                    cb.buf[static_cast<size_t>(cb.pos)] = flushDenormal(in + cb.filt * combFeedback);
                    if (++cb.pos >= static_cast<int>(cb.buf.size())) cb.pos = 0;
                    sum += y;
                }
                for (Allpass& ap : allpass[c]) {
                    const float buffered = ap.buf[static_cast<size_t>(ap.pos)];
                    const float out = -sum + buffered;
                    ap.buf[static_cast<size_t>(ap.pos)] = flushDenormal(sum + buffered * 0.5f);
                    if (++ap.pos >= static_cast<int>(ap.buf.size())) ap.pos = 0;
                    sum = out;
                }
                x[c] += p.reverbMix * kReverbWetGain * sum;
            }
        }
        lr[0] = x[0];
        lr[1] = x[1];
    }
};

}  // namespace

// ------------------------------------------------------------------------------ VoiceParams

int64_t VoiceParams::tailFrames(double sampleRate) const {
    double seconds = 0.0;
    if (hasEcho()) {
        const double delay = static_cast<double>(echoMs) * 0.001;
        const double repeats = echoFeedback > 0.0f ? std::min(60.0, std::ceil(std::log(1e-3) / std::log(static_cast<double>(echoFeedback)))) : 1.0;
        seconds = std::max(seconds, delay * (repeats + 1.0));
    }
    if (hasReverb()) {
        const double comb = 0.0307 * reverbSizeScale(reverbSize);
        seconds = std::max(seconds, comb * std::log(1e-3) / std::log(reverbFeedback(reverbSize)) + 0.1);
    }
    return static_cast<int64_t>(std::min(seconds, kVoiceMaxTailSeconds) * sampleRate);
}

uint64_t VoiceParams::hash() const {
    if (isNeutral()) return 0;
    float f[kVoiceParamFloats];
    voiceParamsToFloats(*this, f);
    uint64_t h = 1469598103934665603ull;
    for (float v : f) {
        uint32_t bits = 0;
        std::memcpy(&bits, &v, sizeof(bits));
        h ^= bits;
        h *= 1099511628211ull;
    }
    return h | 1ull;
}

VoiceFieldRange voiceFieldRange(int field) {
    static constexpr VoiceFieldRange kRanges[kVoiceFieldCount] = {
        {-kVoiceMaxShiftSemitones, kVoiceMaxShiftSemitones, false},  // pitchSemitones
        {-kVoiceMaxShiftSemitones, kVoiceMaxShiftSemitones, false},  // formantSemitones
        {0.0f, 1.0f, false},                                         // whisperMix
        {10.0f, 2000.0f, true},                                      // ringHz
        {0.0f, 1.0f, false},                                         // ringMix
        {20.0f, 8000.0f, true},                                      // bandLowHz
        {200.0f, 20000.0f, true},                                    // bandHighHz
        {0.0f, 36.0f, false},                                        // driveDb
        {1.0f, 2000.0f, true},                                       // echoMs
        {0.0f, 0.95f, false},                                        // echoFeedback
        {0.0f, 1.0f, false},                                         // echoMix
        {0.0f, 1.0f, false},                                         // reverbSize
        {0.0f, 1.0f, false},                                         // reverbDamping
        {0.0f, 1.0f, false},                                         // reverbMix
    };
    return kRanges[field];
}

bool voiceParamsValid(const VoiceParams& p) {
    float f[kVoiceParamFloats];
    voiceParamsToFloats(p, f);
    for (int i = 0; i < kVoiceFieldCount; ++i) {
        const VoiceFieldRange r = voiceFieldRange(i);
        if (!(r.zeroOk ? zeroOrIn(f[i], r.lo, r.hi) : finiteIn(f[i], r.lo, r.hi))) return false;
    }
    return !(p.bandLowHz > 0.0f && p.bandHighHz > 0.0f && p.bandLowHz >= p.bandHighHz);
}

// ------------------------------------------------------------------------------ VoiceSchedule

float VoiceLane::at(int64_t sample) const {
    if (sample <= samples.front()) return values.front();
    if (sample >= samples.back()) return values.back();
    const auto it = std::upper_bound(samples.begin(), samples.end(), sample);
    const size_t hi = static_cast<size_t>(it - samples.begin());
    const size_t lo = hi - 1;
    const double t = static_cast<double>(sample - samples[lo]) / static_cast<double>(samples[hi] - samples[lo]);
    return static_cast<float>(values[lo] + (values[hi] - values[lo]) * t);
}

const VoiceLane* VoiceSchedule::find(int field) const {
    for (const VoiceLane& lane : lanes) {
        if (lane.field == field) return &lane;
    }
    return nullptr;
}

bool VoiceSchedule::animates(int field) const { return find(field) != nullptr; }

VoiceParams VoiceSchedule::at(const VoiceParams& base, int64_t sample) const {
    float f[kVoiceParamFloats];
    voiceParamsToFloats(base, f);
    for (const VoiceLane& lane : lanes) f[lane.field] = lane.at(sample);
    VoiceParams out;
    voiceParamsFromFloats(f, &out);
    return out;
}

VoiceParams VoiceSchedule::envelope(const VoiceParams& base) const {
    float f[kVoiceParamFloats];
    voiceParamsToFloats(base, f);
    for (const VoiceLane& lane : lanes) {
        for (float v : lane.values) {
            if (std::fabs(v) > std::fabs(f[lane.field])) f[lane.field] = v;
        }
    }
    VoiceParams out;
    voiceParamsFromFloats(f, &out);
    return out;
}

uint64_t VoiceSchedule::identity(const VoiceParams& base) const {
    if (lanes.empty()) return base.hash();
    uint64_t h = 1469598103934665603ull;
    auto mix = [&h](uint64_t v) {
        h ^= v;
        h *= 1099511628211ull;
    };
    float f[kVoiceParamFloats];
    voiceParamsToFloats(base, f);
    for (float v : f) {
        uint32_t bits = 0;
        std::memcpy(&bits, &v, sizeof(bits));
        mix(bits);
    }
    for (const VoiceLane& lane : lanes) {
        mix(static_cast<uint64_t>(lane.field) + 0x100u);
        for (size_t i = 0; i < lane.samples.size(); ++i) {
            uint32_t bits = 0;
            std::memcpy(&bits, &lane.values[i], sizeof(bits));
            mix(static_cast<uint64_t>(lane.samples[i]));
            mix(bits);
        }
    }
    return h | 1ull;
}

void voiceParamsFromFloats(const float* f, VoiceParams* p) {
    p->pitchSemitones = f[0];
    p->formantSemitones = f[1];
    p->whisperMix = f[2];
    p->ringHz = f[3];
    p->ringMix = f[4];
    p->bandLowHz = f[5];
    p->bandHighHz = f[6];
    p->driveDb = f[7];
    p->echoMs = f[8];
    p->echoFeedback = f[9];
    p->echoMix = f[10];
    p->reverbSize = f[11];
    p->reverbDamping = f[12];
    p->reverbMix = f[13];
}

void voiceParamsToFloats(const VoiceParams& p, float* f) {
    f[0] = p.pitchSemitones;
    f[1] = p.formantSemitones;
    f[2] = p.whisperMix;
    f[3] = p.ringHz;
    f[4] = p.ringMix;
    f[5] = p.bandLowHz;
    f[6] = p.bandHighHz;
    f[7] = p.driveDb;
    f[8] = p.echoMs;
    f[9] = p.echoFeedback;
    f[10] = p.echoMix;
    f[11] = p.reverbSize;
    f[12] = p.reverbDamping;
    f[13] = p.reverbMix;
    f[14] = 0.0f;
    f[15] = 0.0f;
}

// ------------------------------------------------------------------------------ VoiceProcessor

struct VoiceProcessor::Impl {
    struct Channel {
        std::vector<float> ring, acc;
        std::vector<double> lastPhase, sumPhase;
        std::vector<uint8_t> tracked;  // bins that hosted a region last frame (their sumPhase is live)
        int pos = 0, count = 0;
        uint32_t rng = 1;

        void reset(uint32_t seed) {
            ring.assign(N, 0.0f);
            acc.assign(N, 0.0f);
            lastPhase.assign(K, 0.0);
            sumPhase.assign(K, 0.0);
            tracked.assign(K, 0);
            pos = 0;
            count = 0;
            rng = seed;
        }
        double rand01() {
            rng = rng * 1664525u + 1013904223u;
            return static_cast<double>(rng >> 8) * (1.0 / 16777216.0);
        }
    };

    // Spectral settings.
    double pitchRatio = 1.0, formantRatio = 1.0;
    bool envelopeMode = false;  // formants move differently from the pitch: split excitation and envelope
    float whisper = 0.0f;
    int lifter = 64;
    Channel ch[2];
    PostChain post;
    int64_t skip = 0;  // primed frames still to discard from the front

    // Scratch, allocated once.
    std::vector<Cx> buf, cep, synSpec;
    std::vector<double> magn, phase, trueBin, env, flat, nextPhase;
    std::vector<int> peaks;
    std::vector<float> emit[2];

    // The spectral settings of the next frame.
    void setSpectral(const VoiceParams& p) {
        pitchRatio = std::pow(2.0, static_cast<double>(p.pitchSemitones) / 12.0);
        formantRatio = std::pow(2.0, static_cast<double>(p.formantSemitones) / 12.0);
        envelopeMode = std::fabs(formantRatio - pitchRatio) > 1e-6;
        whisper = p.whisperMix;
    }

    void init(const VoiceParams& p, const VoiceParams& widest, const VoiceSchedule* schedule, int32_t rate) {
        setSpectral(p);
        lifter = std::max(8, static_cast<int>(std::lround(0.0016 * rate)));
        buf.assign(N, Cx());
        cep.assign(N, Cx());
        synSpec.assign(K, Cx());
        magn.assign(K, 0.0);
        phase.assign(K, 0.0);
        trueBin.assign(K, 0.0);
        env.assign(K, 1.0);
        flat.assign(K, 0.0);
        nextPhase.assign(K, 0.0);
        peaks.reserve(K);
        post.configure(p, widest, schedule, rate);
    }

    void resetState(bool spectralOn, int64_t position) {
        ch[0].reset(0x1234567u);
        ch[1].reset(0x7654321u);
        post.reset(position);
        skip = spectralOn ? kVoiceLatency : 0;
    }

    // Cepstrally smoothed magnitude envelope of magn[0..K).
    void envelope() {
        for (int k = 0; k < K; ++k) cep[static_cast<size_t>(k)] = Cx(std::log(magn[static_cast<size_t>(k)] + kEpsilon), 0.0);
        for (int k = 1; k < N / 2; ++k) cep[static_cast<size_t>(N - k)] = cep[static_cast<size_t>(k)];
        fft(cep.data(), true);
        for (int n = lifter + 1; n < N - lifter; ++n) cep[static_cast<size_t>(n)] = Cx();
        fft(cep.data(), false);
        for (int k = 0; k < K; ++k) env[static_cast<size_t>(k)] = std::exp(cep[static_cast<size_t>(k)].real());
    }

    double envelopeAt(double bin) const {
        if (bin <= 0.0) return env[0];
        if (bin >= K - 1) return env[K - 1];
        const int i = static_cast<int>(bin);
        const double t = bin - i;
        return env[static_cast<size_t>(i)] * (1.0 - t) + env[static_cast<size_t>(i + 1)] * t;
    }

    // Hermitian-completes buf[0..K), inverse transforms and overlap-adds the real result scaled by `gain`.
    void synthesiseInto(std::vector<float>& acc, float gain) {
        const std::vector<float>& win = hann();
        buf[0] = Cx(buf[0].real(), 0.0);
        buf[N / 2] = Cx(buf[N / 2].real(), 0.0);
        for (int j = 1; j < N / 2; ++j) buf[static_cast<size_t>(N - j)] = std::conj(buf[static_cast<size_t>(j)]);
        fft(buf.data(), true);
        for (int i = 0; i < N; ++i) acc[static_cast<size_t>(i)] += win[static_cast<size_t>(i)] * static_cast<float>(buf[static_cast<size_t>(i)].real()) * gain;
    }

    // The voiced path: every spectral peak (with its region of influence) moves rigidly to the shifted pitch and
    // keeps its analysis phase relations (identity phase locking), so a shifted sinusoid keeps its level.
    void shiftPeaks(Channel& c, const double* src) {
        std::fill(synSpec.begin(), synSpec.end(), Cx());
        double maxMagn = 0.0;
        for (int k = 0; k < K; ++k) maxMagn = std::max(maxMagn, magn[static_cast<size_t>(k)]);
        const double threshold = std::max(maxMagn * 1e-3, 1e-7);
        peaks.clear();
        for (int k = 2; k < K - 2; ++k) {
            const double m = magn[static_cast<size_t>(k)];
            if (m >= threshold && m > magn[static_cast<size_t>(k - 1)] && m >= magn[static_cast<size_t>(k + 1)] &&
                m > magn[static_cast<size_t>(k - 2)] && m >= magn[static_cast<size_t>(k + 2)]) {
                peaks.push_back(k);
            }
        }
        // Bins that host no region just keep running at their centre frequency.
        for (int j = 0; j < K; ++j) nextPhase[static_cast<size_t>(j)] = c.sumPhase[static_cast<size_t>(j)] + j * kExpct;
        std::vector<uint8_t>& nowTracked = trackedNext;
        std::fill(nowTracked.begin(), nowTracked.end(), 0);
        for (size_t i = 0; i < peaks.size(); ++i) {
            const int p = peaks[i];
            const int a = i == 0 ? 0 : (peaks[i - 1] + p) / 2 + 1;
            const int b = i + 1 == peaks.size() ? K - 1 : (p + peaks[i + 1]) / 2;
            const int delta = static_cast<int>(std::lround(p * pitchRatio)) - p;
            const int jp = p + delta;
            if (jp < 0 || jp >= K) continue;
            // The peak's synthesis phase: where this bin was last frame plus the shifted true frequency's advance;
            // a peak that was not tracked starts from its analysis phase.
            const double dev = trueBin[static_cast<size_t>(p)] * pitchRatio - jp;
            double peakPhase = c.tracked[static_cast<size_t>(jp)]
                                   ? c.sumPhase[static_cast<size_t>(jp)] + dev * 2.0 * kPi / kOsamp + jp * kExpct
                                   : phase[static_cast<size_t>(p)];
            if (peakPhase > 1e6 || peakPhase < -1e6) peakPhase -= 2.0 * kPi * std::floor(peakPhase / (2.0 * kPi));
            for (int k = a; k <= b; ++k) {
                const int j = k + delta;
                if (j < 0 || j >= K) continue;
                const double ph = peakPhase + (phase[static_cast<size_t>(k)] - phase[static_cast<size_t>(p)]);
                synSpec[static_cast<size_t>(j)] += std::polar(src[k], ph);
                nextPhase[static_cast<size_t>(j)] = ph;
                nowTracked[static_cast<size_t>(j)] = 1;
            }
        }
        c.sumPhase = nextPhase;
        c.tracked = nowTracked;
    }

    std::vector<uint8_t> trackedNext = std::vector<uint8_t>(K, 0);

    // One analysis/synthesis frame of channel `c`; appends H finished samples to `emitted`.
    void runFrame(Channel& c, std::vector<float>& emitted) {
        const std::vector<float>& win = hann();
        for (int i = 0; i < N; ++i) buf[static_cast<size_t>(i)] = Cx(static_cast<double>(c.ring[static_cast<size_t>((c.pos + i) % N)]) * win[static_cast<size_t>(i)], 0.0);
        fft(buf.data(), false);
        double inputEnergy = 0.0;
        for (int k = 0; k < K; ++k) {
            magn[static_cast<size_t>(k)] = std::abs(buf[static_cast<size_t>(k)]);
            phase[static_cast<size_t>(k)] = std::arg(buf[static_cast<size_t>(k)]);
            inputEnergy += magn[static_cast<size_t>(k)] * magn[static_cast<size_t>(k)];
        }

        const bool voiced = whisper < 1.0f;
        if (voiced) {
            for (int k = 0; k < K; ++k) {
                double d = phase[static_cast<size_t>(k)] - c.lastPhase[static_cast<size_t>(k)];
                c.lastPhase[static_cast<size_t>(k)] = phase[static_cast<size_t>(k)];
                d -= k * kExpct;
                d -= 2.0 * kPi * std::round(d / (2.0 * kPi));
                trueBin[static_cast<size_t>(k)] = k + d * kOsamp / (2.0 * kPi);
            }
        }
        if (envelopeMode || whisper > 0.0f) envelope();

        std::vector<float>& acc = c.acc;
        if (voiced) {
            const double* src = magn.data();
            if (envelopeMode) {
                for (int k = 0; k < K; ++k) flat[static_cast<size_t>(k)] = magn[static_cast<size_t>(k)] / std::max(env[static_cast<size_t>(k)], kEpsilon);
                src = flat.data();
            }
            shiftPeaks(c, src);
            if (envelopeMode) {
                for (int j = 0; j < K; ++j) synSpec[static_cast<size_t>(j)] *= envelopeAt(j / formantRatio);
            }
            for (int j = 0; j < K; ++j) buf[static_cast<size_t>(j)] = synSpec[static_cast<size_t>(j)];
            synthesiseInto(acc, (1.0f - whisper) * kSynthScale);
        }
        // Whispered path: the smooth spectral envelope (no harmonics) with a fresh random phase every frame,
        // scaled to the energy of the input so the level matches.
        if (whisper > 0.0f) {
            double wetEnergy = 0.0;
            for (int j = 0; j < K; ++j) {
                const double m = envelopeAt(j / formantRatio);
                flat[static_cast<size_t>(j)] = m;
                wetEnergy += m * m;
            }
            const double norm = wetEnergy > 0.0 ? std::sqrt(inputEnergy / wetEnergy) : 0.0;
            for (int j = 0; j < K; ++j) buf[static_cast<size_t>(j)] = std::polar(flat[static_cast<size_t>(j)] * norm, 2.0 * kPi * c.rand01());
            synthesiseInto(acc, whisper * kWhisperGain * kSynthScale);
        }

        emitted.insert(emitted.end(), acc.begin(), acc.begin() + H);
        std::memmove(acc.data(), acc.data() + H, static_cast<size_t>(N - H) * sizeof(float));
        std::fill(acc.begin() + (N - H), acc.end(), 0.0f);
    }
};

VoiceProcessor::VoiceProcessor(const VoiceParams& params, int32_t sampleRate, bool forceSpectral,
                               std::shared_ptr<const VoiceSchedule> schedule)
    : impl_(new Impl()),
      params_(params),
      envelope_(schedule && !schedule->empty() ? schedule->envelope(params) : params),
      schedule_(schedule && !schedule->empty() ? std::move(schedule) : nullptr),
      rate_(sampleRate),
      spectral_(forceSpectral || envelope_.needsSpectral()) {
    impl_->init(params_, envelope_, schedule_.get(), rate_);
    reset();
}

VoiceProcessor::~VoiceProcessor() { delete impl_; }

void VoiceProcessor::reset(int64_t position) {
    origin_ = position;
    in_ = 0;
    out_ = 0;
    impl_->resetState(spectral_, position);
}

void VoiceProcessor::process(const float* stereo, size_t frames, std::vector<float>* out) {
    Impl& s = *impl_;
    out->reserve(out->size() + frames * 2);
    if (!spectral_) {
        for (size_t i = 0; i < frames; ++i) {
            float lr[2] = {stereo[2 * i], stereo[2 * i + 1]};
            s.post.tick(lr);
            out->push_back(lr[0]);
            out->push_back(lr[1]);
        }
        in_ += static_cast<int64_t>(frames);
        out_ += static_cast<int64_t>(frames);
        return;
    }
    for (size_t i = 0; i < frames; ++i) {
        bool hop = false;
        for (int c = 0; c < 2; ++c) {
            Impl::Channel& ch = s.ch[c];
            ch.ring[static_cast<size_t>(ch.pos)] = stereo[2 * i + static_cast<size_t>(c)];
            ch.pos = (ch.pos + 1) % N;
            if (++ch.count == H) {
                ch.count = 0;
                if (schedule_) {
                    // The frame spans the last N input samples; its settings are those at the middle of that span.
                    const int64_t consumed = in_ + static_cast<int64_t>(i) + 1;
                    s.setSpectral(schedule_->at(params_, origin_ + std::max<int64_t>(0, consumed - N / 2)));
                }
                s.emit[c].clear();
                s.runFrame(ch, s.emit[c]);
                hop = true;
            }
        }
        if (!hop) continue;
        for (int k = 0; k < H; ++k) {
            if (s.skip > 0) {
                --s.skip;
                continue;
            }
            float lr[2] = {s.emit[0][static_cast<size_t>(k)], s.emit[1][static_cast<size_t>(k)]};
            s.post.tick(lr);
            out->push_back(lr[0]);
            out->push_back(lr[1]);
            ++out_;
        }
    }
    in_ += static_cast<int64_t>(frames);
}

void VoiceProcessor::flush(std::vector<float>* out, int64_t maxTailFrames) {
    if (in_ == 0) {
        reset();
        return;
    }
    const int64_t missing = in_ - out_;
    const int64_t tail = std::max<int64_t>(0, std::min(maxTailFrames, envelope_.tailFrames(rate_)));
    const int64_t want = missing + tail;
    std::vector<float> produced;
    std::vector<float> zeros(static_cast<size_t>(H) * 2, 0.0f);
    const int guardLimit = static_cast<int>(want / H) + 16;
    for (int guard = 0; guard < guardLimit && static_cast<int64_t>(produced.size() / 2) < want; ++guard) {
        process(zeros.data(), H, &produced);
    }
    const size_t take = std::min<size_t>(static_cast<size_t>(want) * 2, produced.size());
    out->insert(out->end(), produced.begin(), produced.begin() + static_cast<std::ptrdiff_t>(take));
    reset();
}

}  // namespace uv::audio
