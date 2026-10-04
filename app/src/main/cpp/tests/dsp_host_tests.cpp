// GoogleTest-free host tests for the classical audio DSP: biquad responses, EQ, pan law, bus
// compressor, ducker, limiter, BS.1770 loudness and the spectral noise suppressor. Build/run: see
// tests/CMakeLists.txt.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "audio/dsp.h"
#include "audio/loudness.h"
#include "audio/spectral_denoise.h"

using namespace uv::audio;
using namespace uv::audio::dsp;

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)
#define CHECK_NEAR(a, b, eps) CHECK(std::fabs(static_cast<double>(a) - static_cast<double>(b)) <= (eps))

constexpr double kPi = 3.14159265358979323846;
constexpr double kRate = 48000.0;

static std::vector<float> stereoSine(double freq, double amp, int frames, double rate = kRate, double phase = 0.0) {
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        const float s = static_cast<float>(amp * std::sin(2.0 * kPi * freq * i / rate + phase));
        v[2 * i] = s;
        v[2 * i + 1] = s;
    }
    return v;
}

static double peakOf(const std::vector<float>& v, size_t fromFrame = 0) {
    double p = 0;
    for (size_t i = fromFrame * 2; i < v.size(); ++i) p = std::max(p, static_cast<double>(std::fabs(v[i])));
    return p;
}

static double rmsOf(const std::vector<float>& v, size_t fromFrame, size_t toFrame) {
    double s = 0;
    size_t n = 0;
    for (size_t i = fromFrame; i < toFrame; ++i) {
        s += static_cast<double>(v[2 * i]) * v[2 * i];
        ++n;
    }
    return std::sqrt(s / static_cast<double>(n));
}

// Amplitude of the `freq` component of the left channel in [from, to).
static double toneAmplitude(const std::vector<float>& v, double freq, size_t from, size_t to) {
    double re = 0, im = 0;
    for (size_t i = from; i < to; ++i) {
        const double w = 2.0 * kPi * freq * static_cast<double>(i) / kRate;
        re += v[2 * i] * std::cos(w);
        im += v[2 * i] * std::sin(w);
    }
    return 2.0 * std::sqrt(re * re + im * im) / static_cast<double>(to - from);
}

static double maxDiff(const std::vector<float>& a, const std::vector<float>& b) {
    if (a.size() != b.size()) return 1e9;
    double m = 0;
    for (size_t i = 0; i < a.size(); ++i) m = std::max(m, static_cast<double>(std::fabs(a[i] - b[i])));
    return m;
}

// ------------------------------------------------------------------ biquads and EQ

static void testBiquadResponses() {
    const BiquadCoeffs lp = designBiquad(FilterType::LowPass, kRate, 1000, 0, 0.70710678);
    CHECK_NEAR(biquadMagnitude(lp, kRate, 1000), 0.70710678, 0.01);  // -3 dB at the corner
    CHECK_NEAR(biquadMagnitude(lp, kRate, 20), 1.0, 0.01);
    CHECK(biquadMagnitude(lp, kRate, 8000) < 0.02);                  // 12 dB/oct: -36 dB three octaves up

    const BiquadCoeffs hp = designBiquad(FilterType::HighPass, kRate, 1000, 0, 0.70710678);
    CHECK_NEAR(biquadMagnitude(hp, kRate, 1000), 0.70710678, 0.01);
    CHECK_NEAR(biquadMagnitude(hp, kRate, 15000), 1.0, 0.02);
    CHECK(biquadMagnitude(hp, kRate, 100) < 0.02);

    const BiquadCoeffs peak = designBiquad(FilterType::Peaking, kRate, 1000, 6.0, 1.0);
    CHECK_NEAR(biquadMagnitude(peak, kRate, 1000), dbToLin(6.0), 0.01);
    CHECK_NEAR(biquadMagnitude(peak, kRate, 30), 1.0, 0.02);
    CHECK_NEAR(biquadMagnitude(peak, kRate, 18000), 1.0, 0.03);
    const BiquadCoeffs cut = designBiquad(FilterType::Peaking, kRate, 1000, -9.0, 2.0);
    CHECK_NEAR(biquadMagnitude(cut, kRate, 1000), dbToLin(-9.0), 0.01);

    const BiquadCoeffs low = designBiquad(FilterType::LowShelf, kRate, 200, 6.0, 0.7071);
    CHECK_NEAR(biquadMagnitude(low, kRate, 20), dbToLin(6.0), 0.03);
    CHECK_NEAR(biquadMagnitude(low, kRate, 10000), 1.0, 0.02);
    const BiquadCoeffs high = designBiquad(FilterType::HighShelf, kRate, 4000, -6.0, 0.7071);
    CHECK_NEAR(biquadMagnitude(high, kRate, 15000), dbToLin(-6.0), 0.03);
    CHECK_NEAR(biquadMagnitude(high, kRate, 100), 1.0, 0.02);
}

static void testEqChain() {
    EqParams flat;
    CHECK(flat.isFlat());
    CHECK(EqChain::design(flat, kRate).stages == 0);

    EqParams p;
    p.highPassHz = 80;
    p.bands[1].freqHz = 1000;
    p.bands[1].gainDb = 12;
    p.bands[1].q = 1.0f;
    CHECK(!p.isFlat());
    const EqChain chain = EqChain::design(p, kRate);
    CHECK(chain.stages == 2);
    CHECK_NEAR(chain.magnitude(kRate, 1000), dbToLin(12.0), 0.05);
    CHECK(chain.magnitude(kRate, 30) < 0.2);

    // Processing a 1 kHz sine really applies the response.
    std::vector<float> x = stereoSine(1000, 0.1, 48000);
    EqState st;
    eqProcess(chain, st, x.data(), 48000);
    CHECK_NEAR(toneAmplitude(x, 1000, 24000, 48000), 0.1 * dbToLin(12.0), 0.02);

    // Block size does not matter.
    std::vector<float> a = stereoSine(440, 0.3, 4800), b = a;
    EqState sa, sb;
    eqProcess(chain, sa, a.data(), 4800);
    for (int off = 0; off < 4800; off += 7) eqProcess(chain, sb, b.data() + off * 2, std::min(7, 4800 - off));
    CHECK(maxDiff(a, b) < 1e-7);
}

static void testPanLaw() {
    float l, r;
    panGains(0.0f, &l, &r);
    CHECK(l == 1.0f && r == 1.0f);
    panGains(1.0f, &l, &r);
    CHECK_NEAR(l, 0.0, 1e-6);
    CHECK(r == 1.0f);
    panGains(-1.0f, &l, &r);
    CHECK(l == 1.0f);
    CHECK_NEAR(r, 0.0, 1e-6);
    panGains(0.5f, &l, &r);
    CHECK_NEAR(l, std::cos(kPi / 4), 1e-6);
    panGains(7.0f, &l, &r);  // out of range clamps
    CHECK_NEAR(l, 0.0, 1e-6);
    // The far channel falls monotonically as the pan moves away from the centre.
    float prev = 1.0f;
    for (float p = 0.0f; p <= 1.0f; p += 0.05f) {
        panGains(p, &l, &r);
        CHECK(l <= prev + 1e-6f);
        prev = l;
    }
}

// ------------------------------------------------------------------ dynamics

static void testCompressor() {
    Compressor c;
    CompressorParams p;
    p.enabled = true;
    p.thresholdDb = -20;
    p.ratio = 4;
    p.attackMs = 5;
    p.releaseMs = 50;
    c.configure(p, kRate);
    // A constant-magnitude signal (alternating +-0.5, -6 dBFS) settles at the static curve:
    // (-6 - -20) * (1 - 1/4) = 10.5 dB of reduction.
    std::vector<float> x(static_cast<size_t>(48000) * 2);
    for (int i = 0; i < 48000; ++i) x[2 * i] = x[2 * i + 1] = (i & 1) ? -0.5f : 0.5f;
    c.process(x.data(), 48000);
    CHECK_NEAR(peakOf(x, 24000), 0.5 * dbToLin(-10.5), 0.003);
    // A sine settles a little shallower (the detector releases between the peaks) but still compresses.
    Compressor sine;
    sine.configure(p, kRate);
    std::vector<float> s1 = stereoSine(1000, 0.5, 48000);
    sine.process(s1.data(), 48000);
    CHECK(peakOf(s1, 24000) < 0.2 && peakOf(s1, 24000) > 0.12);

    // Below the threshold nothing changes; disabled does nothing at all.
    Compressor quiet;
    quiet.configure(p, kRate);
    std::vector<float> q = stereoSine(1000, 0.05, 4800), q0 = q;
    quiet.process(q.data(), 4800);
    CHECK(maxDiff(q, q0) < 1e-6);
    Compressor off;
    off.configure(CompressorParams{}, kRate);
    std::vector<float> o = stereoSine(1000, 0.9, 4800), o0 = o;
    off.process(o.data(), 4800);
    CHECK(maxDiff(o, o0) == 0.0);

    // Block independence.
    std::vector<float> a = stereoSine(300, 0.6, 9600), b = a;
    Compressor ca, cb;
    ca.configure(p, kRate);
    cb.configure(p, kRate);
    ca.process(a.data(), 9600);
    for (int off2 = 0; off2 < 9600; off2 += 13) cb.process(b.data() + off2 * 2, std::min(13, 9600 - off2));
    CHECK(maxDiff(a, b) < 1e-6);
}

static void testDucker() {
    Ducker d;
    DuckerParams p;
    p.amountDb = 12;
    p.thresholdDb = -35;
    p.attackMs = 20;
    p.releaseMs = 400;
    d.configure(p, kRate);

    std::vector<float> voice = stereoSine(300, 0.3, 48000);
    std::vector<float> gains(48000);
    d.process(voice.data(), 48000, gains.data());
    CHECK_NEAR(gains[47999], dbToLin(-12.0), 0.01);  // fully ducked after a second of speech
    CHECK(gains[0] > 0.9);                           // it takes the attack time to get there

    std::vector<float> silence(static_cast<size_t>(48000) * 3 * 2, 0.0f);
    std::vector<float> g2(48000 * 3);
    d.process(silence.data(), 48000 * 3, g2.data());
    CHECK(g2.back() > 0.98);  // recovers after the voice stops
    for (float g : g2) CHECK(g <= 1.0f && g > 0.0f);

    // Quiet "voice" below the threshold does not duck.
    Ducker q;
    q.configure(p, kRate);
    std::vector<float> hiss = stereoSine(300, 0.001, 24000);
    std::vector<float> gq(24000);
    q.process(hiss.data(), 24000, gq.data());
    CHECK(gq.back() > 0.999);

    // Amount 0 never ducks; block independence.
    Ducker z;
    DuckerParams zero = p;
    zero.amountDb = 0;
    z.configure(zero, kRate);
    std::vector<float> gz(48000);
    z.process(voice.data(), 48000, gz.data());
    CHECK(gz.back() > 0.9999f);
    Ducker a, b;
    a.configure(p, kRate);
    b.configure(p, kRate);
    std::vector<float> ga(9600), gb(9600);
    a.process(voice.data(), 9600, ga.data());
    for (int off = 0; off < 9600; off += 11) b.process(voice.data() + off * 2, std::min(11, 9600 - off), gb.data() + off);
    double m = 0;
    for (int i = 0; i < 9600; ++i) m = std::max(m, static_cast<double>(std::fabs(ga[i] - gb[i])));
    CHECK(m < 1e-6);
}

static void testLimiter() {
    Limiter lim;
    lim.configure(-1.0f, kRate);
    std::vector<float> x = stereoSine(1000, 4.0, 48000);
    lim.process(x.data(), 48000);
    CHECK(peakOf(x) <= lim.ceiling + 1e-6);
    CHECK(peakOf(x) > lim.ceiling * 0.95);  // it limits, it does not mute

    Limiter small;
    small.configure(-1.0f, kRate);
    std::vector<float> y = stereoSine(1000, 0.1, 4800), y0 = y;
    small.process(y.data(), 4800);
    CHECK(maxDiff(y, y0) < 1e-6);

    // A loud burst followed by quiet material: gain recovers.
    Limiter burst;
    burst.configure(-1.0f, kRate);
    std::vector<float> z(static_cast<size_t>(48000) * 2, 0.0f);
    for (int i = 0; i < 100; ++i) z[2 * i] = z[2 * i + 1] = 3.0f;
    for (int i = 24000; i < 48000; ++i) z[2 * i] = z[2 * i + 1] = 0.1f;
    burst.process(z.data(), 48000);
    CHECK(peakOf(z) <= burst.ceiling + 1e-6);
    CHECK_NEAR(z[2 * 47999], 0.1, 0.002);

    // Block independence.
    std::vector<float> a = stereoSine(200, 2.0, 9600), b = a;
    Limiter la, lb;
    la.configure(-1.0f, kRate);
    lb.configure(-1.0f, kRate);
    la.process(a.data(), 9600);
    for (int off = 0; off < 9600; off += 17) lb.process(b.data() + off * 2, std::min(17, 9600 - off));
    CHECK(maxDiff(a, b) < 1e-7);
}

static void testPeakMeter() {
    PeakMeter m;
    std::vector<float> x(8, 0.0f);
    x[2] = 0.5f;
    x[5] = -0.8f;
    m.publish(x.data(), 4);
    std::vector<float> y(8, 0.0f);
    y[0] = 0.2f;
    m.publish(y.data(), 4);  // a smaller later block does not hide the earlier peak
    float l, r;
    m.take(&l, &r);
    CHECK_NEAR(l, 0.5, 1e-6);
    CHECK_NEAR(r, 0.8, 1e-6);
    m.take(&l, &r);
    CHECK(l == 0.0f && r == 0.0f);
}

// ------------------------------------------------------------------ loudness

static double lufsOfSine(double amp, double seconds, int rate = 48000) {
    LoudnessMeter m(rate);
    const std::vector<float> x = stereoSine(1000, amp, static_cast<int>(seconds * rate), rate);
    m.addFrames(x.data(), static_cast<int64_t>(x.size() / 2));
    return m.integratedLufs();
}

static void testLoudness() {
    // A 1 kHz stereo sine: K-weighting is ~0 dB there and the -0.691 offset cancels it, so the mean
    // square of two channels of amplitude A gives 10 log10(A^2) LUFS (EBU Tech 3341 style check).
    CHECK_NEAR(lufsOfSine(0.1, 5.0), -20.0, 0.1);
    CHECK_NEAR(lufsOfSine(0.01, 5.0), -40.0, 0.1);
    CHECK_NEAR(lufsOfSine(0.1, 5.0, 44100), -20.0, 0.1);
    CHECK_NEAR(lufsOfSine(0.1, 0.3), -20.0, 0.3);  // shorter than one 400 ms block still reads
    // 6 dB louder reads 6 LU higher.
    CHECK_NEAR(lufsOfSine(0.2, 5.0) - lufsOfSine(0.1, 5.0), 6.02, 0.05);

    // Silence is reported as silence.
    LoudnessMeter quiet(48000);
    std::vector<float> zeros(48000 * 2 * 3, 0.0f);
    quiet.addFrames(zeros.data(), 48000 * 3);
    CHECK(quiet.integratedLufs() == LoudnessMeter::kSilence);

    // Gating: digital silence after the tone does not pull the result down.
    LoudnessMeter gated(48000);
    const std::vector<float> tone = stereoSine(1000, 0.1, 48000 * 5);
    gated.addFrames(tone.data(), 48000 * 5);
    gated.addFrames(zeros.data(), 48000 * 3);
    CHECK_NEAR(gated.integratedLufs(), -20.0, 0.15);

    // Relative gate: a section 30 LU quieter than the rest is ignored.
    LoudnessMeter rel(48000);
    rel.addFrames(tone.data(), 48000 * 5);
    const std::vector<float> faint = stereoSine(1000, 0.1 * std::pow(10.0, -30.0 / 20.0), 48000 * 5);
    rel.addFrames(faint.data(), 48000 * 5);
    CHECK_NEAR(rel.integratedLufs(), -20.0, 0.2);
    CHECK_NEAR(rel.samplePeak(), 0.1, 1e-4);

    // Low frequencies weigh less than 1 kHz at the same level (the RLB high-pass).
    LoudnessMeter bass(48000);
    const std::vector<float> lf = stereoSine(40, 0.1, 48000 * 5);
    bass.addFrames(lf.data(), 48000 * 5);
    CHECK(bass.integratedLufs() < -20.5);
}

// ------------------------------------------------------------------ noise suppression

struct Lcg {
    uint32_t s;
    float next() {  // uniform in [-1, 1)
        s = s * 1664525u + 1013904223u;
        return static_cast<float>((s >> 8) & 0xFFFF) / 32768.0f - 1.0f;
    }
};

static std::vector<float> noiseStereo(int frames, float amp, uint32_t seedL, uint32_t seedR) {
    Lcg l{seedL}, r{seedR};
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        v[2 * i] = amp * l.next();
        v[2 * i + 1] = amp * r.next();
    }
    return v;
}

static void testNoiseProfileValidation() {
    std::vector<float> mono(100, 0.1f);
    float mag[kDenoiseBins];
    CHECK(computeNoiseProfile(mono.data(), mono.size(), mag) == uv::core::Status::InvalidArgument);
    CHECK(computeNoiseProfile(nullptr, 10000, mag) == uv::core::Status::InvalidArgument);
    std::vector<float> ok(4096, 0.0f);
    CHECK(computeNoiseProfile(ok.data(), ok.size(), mag) == uv::core::Status::Ok);
}

static void testSpectralDenoise() {
    const int kSecond = 48000;
    // Profile from a second of noise.
    const std::vector<float> noiseOnly = noiseStereo(kSecond, 0.05f, 11, 12);
    std::vector<float> mono(kSecond);
    for (int i = 0; i < kSecond; ++i) mono[i] = 0.5f * (noiseOnly[2 * i] + noiseOnly[2 * i + 1]);
    float profile[kDenoiseBins];
    CHECK(computeNoiseProfile(mono.data(), mono.size(), profile) == uv::core::Status::Ok);

    // Material: 1 s of the same kind of noise (other seeds), then noise plus a 1 kHz tone.
    std::vector<float> input = noiseStereo(2 * kSecond, 0.05f, 21, 22);
    for (int i = kSecond; i < 2 * kSecond; ++i) {
        const float t = 0.3f * std::sin(2.0f * static_cast<float>(kPi) * 1000.0f * static_cast<float>(i) / 48000.0f);
        input[2 * i] += t;
        input[2 * i + 1] += t;
    }

    auto run = [&](size_t chunk) {
        SpectralDenoiser d(profile, 0.8f);
        std::vector<float> out;
        for (size_t off = 0; off < input.size() / 2; off += chunk) {
            const size_t n = std::min(chunk, input.size() / 2 - off);
            d.process(input.data() + off * 2, n, &out);
        }
        d.flush(&out);
        return out;
    };
    const std::vector<float> out = run(1000);
    CHECK(out.size() == input.size());  // aligned and complete

    // Noise only region (skip the first 100 ms of adaptation): at least 12 dB quieter.
    const double before = rmsOf(input, 4800, kSecond - 4800), after = rmsOf(out, 4800, kSecond - 4800);
    CHECK(20.0 * std::log10(after / before) < -12.0);
    // The tone survives with little loss and in phase with the input (no latency).
    const double toneIn = toneAmplitude(input, 1000, kSecond + 9600, 2 * kSecond - 9600);
    const double toneOut = toneAmplitude(out, 1000, kSecond + 9600, 2 * kSecond - 9600);
    CHECK(toneOut / toneIn > 0.88 && toneOut / toneIn < 1.08);
    // Alignment: the output tone correlates with the input sine at the same phase.
    double dot = 0, ref = 0;
    for (int i = kSecond + 9600; i < 2 * kSecond - 9600; ++i) {
        const double s = std::sin(2.0 * kPi * 1000.0 * i / 48000.0);
        dot += out[2 * i] * s;
        ref += input[2 * i] * s;
    }
    CHECK(dot / ref > 0.85);

    // The result does not depend on how the audio is chunked.
    CHECK(maxDiff(run(4096), out) < 1e-5);
    CHECK(maxDiff(run(97), out) < 1e-5);

    // A short input still returns exactly its length; empty input returns nothing.
    SpectralDenoiser shortRun(profile, 0.5f);
    std::vector<float> few = noiseStereo(300, 0.05f, 5, 6), got;
    shortRun.process(few.data(), 300, &got);
    shortRun.flush(&got);
    CHECK(got.size() == few.size());
    SpectralDenoiser none(profile, 0.5f);
    std::vector<float> nothing;
    none.flush(&nothing);
    CHECK(nothing.empty());
}

int main() {
    testBiquadResponses();
    testEqChain();
    testPanLaw();
    testCompressor();
    testDucker();
    testLimiter();
    testPeakMeter();
    testLoudness();
    testNoiseProfileValidation();
    testSpectralDenoise();
    if (g_failures == 0) std::printf("dsp host tests passed\n");
    return g_failures == 0 ? 0 : 1;
}
