// GoogleTest-free host tests for the voice effects (audio/voice_fx.h): pitch accuracy, formant and
// whisper behaviour, ring-mod spectrum, band/drive, echo and reverb impulse responses, chunk
// invariance, alignment, bounded tails, no NaN/denormals and the parameter checks.
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "audio/voice_fx.h"

using namespace uv::audio;

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
constexpr int kRate = 48000;

static std::vector<float> stereoSine(double freq, double amp, int frames) {
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        const float s = static_cast<float>(amp * std::sin(2.0 * kPi * freq * i / kRate));
        v[static_cast<size_t>(2 * i)] = s;
        v[static_cast<size_t>(2 * i + 1)] = s;
    }
    return v;
}

static uint32_t g_noise = 12345;
static float noiseSample() {
    g_noise = g_noise * 1664525u + 1013904223u;
    return static_cast<float>(static_cast<double>(g_noise >> 8) / 8388608.0 - 1.0);
}

static std::vector<float> noiseStereo(int frames, float amp, uint32_t seed) {
    g_noise = seed;
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    for (float& x : v) x = noiseSample() * amp;
    return v;
}

// Feeds `in` in chunks of `chunk` frames, then flushes with up to `tail` frames of echo/reverb tail.
static std::vector<float> run(const VoiceParams& p, const std::vector<float>& in, size_t chunk, int64_t tail = 0, bool force = false) {
    VoiceProcessor proc(p, kRate, force);
    std::vector<float> out;
    const size_t frames = in.size() / 2;
    for (size_t at = 0; at < frames; at += chunk) {
        const size_t n = std::min(chunk, frames - at);
        proc.process(in.data() + at * 2, n, &out);
    }
    proc.flush(&out, tail);
    return out;
}

static double rms(const std::vector<float>& v, size_t from, size_t to) {
    double s = 0;
    for (size_t i = from; i < to; ++i) s += static_cast<double>(v[2 * i]) * v[2 * i];
    return std::sqrt(s / static_cast<double>(to - from));
}

static double peakOf(const std::vector<float>& v) {
    double p = 0;
    for (float x : v) p = std::max(p, static_cast<double>(std::fabs(x)));
    return p;
}

static double toneAmplitude(const std::vector<float>& v, double freq, size_t from, size_t to) {
    double re = 0, im = 0;
    for (size_t i = from; i < to; ++i) {
        const double w = 2.0 * kPi * freq * static_cast<double>(i) / kRate;
        re += v[2 * i] * std::cos(w);
        im += v[2 * i] * std::sin(w);
    }
    return 2.0 * std::sqrt(re * re + im * im) / static_cast<double>(to - from);
}

// Frequency from rising zero crossings (linearly interpolated) in the left channel.
static double frequencyOf(const std::vector<float>& v, size_t from, size_t to) {
    double first = -1, last = -1;
    int count = 0;
    for (size_t i = from + 1; i < to; ++i) {
        const double a = v[2 * (i - 1)], b = v[2 * i];
        if (a < 0 && b >= 0) {
            const double t = static_cast<double>(i - 1) + (-a) / (b - a);
            if (first < 0) first = t;
            last = t;
            ++count;
        }
    }
    return count < 2 ? 0.0 : (count - 1) * kRate / (last - first);
}

static double cents(double f, double ref) { return 1200.0 * std::log2(f / ref); }

// ------------------------------------------------------------------ parameters

static void testParams() {
    VoiceParams neutral;
    CHECK(neutral.isNeutral() && neutral.hash() == 0 && voiceParamsValid(neutral));
    VoiceParams p;
    p.pitchSemitones = 5.0f;
    CHECK(!p.isNeutral() && p.needsSpectral() && p.hash() != 0 && p.hash() != neutral.hash());
    VoiceParams q = p;
    q.pitchSemitones = 5.5f;
    CHECK(p.hash() != q.hash());
    CHECK(voiceParamsValid(p));

    float f[kVoiceParamFloats];
    voiceParamsToFloats(p, f);
    VoiceParams back;
    voiceParamsFromFloats(f, &back);
    CHECK(back.pitchSemitones == 5.0f && back.hash() == p.hash());

    auto bad = [](auto edit) {
        VoiceParams v;
        edit(v);
        return !voiceParamsValid(v);
    };
    CHECK(bad([](VoiceParams& v) { v.pitchSemitones = 13.0f; }));
    CHECK(bad([](VoiceParams& v) { v.formantSemitones = std::nanf(""); }));
    CHECK(bad([](VoiceParams& v) { v.whisperMix = 1.5f; }));
    CHECK(bad([](VoiceParams& v) { v.ringHz = 5.0f; }));
    CHECK(bad([](VoiceParams& v) { v.echoFeedback = 0.99f; }));
    CHECK(bad([](VoiceParams& v) { v.echoMs = 5000.0f; }));
    CHECK(bad([](VoiceParams& v) { v.bandLowHz = 3000.0f; v.bandHighHz = 2000.0f; }));
    CHECK(bad([](VoiceParams& v) { v.driveDb = 40.0f; }));
    CHECK(bad([](VoiceParams& v) { v.reverbMix = -0.1f; }));

    // Tails: none without echo/reverb, bounded with them.
    CHECK(neutral.tailFrames(kRate) == 0);
    VoiceParams e;
    e.echoMs = 300.0f;
    e.echoFeedback = 0.5f;
    e.echoMix = 0.5f;
    const int64_t echoTail = e.tailFrames(kRate);
    CHECK(echoTail > kRate / 2 && echoTail <= static_cast<int64_t>(kVoiceMaxTailSeconds * kRate));
    VoiceParams r;
    r.reverbMix = 0.5f;
    r.reverbSize = 1.0f;
    CHECK(r.tailFrames(kRate) > kRate && r.tailFrames(kRate) <= static_cast<int64_t>(kVoiceMaxTailSeconds * kRate));
    VoiceParams small = r;
    small.reverbSize = 0.0f;
    CHECK(small.tailFrames(kRate) < r.tailFrames(kRate));
}

// ------------------------------------------------------------------ spectral stage

static void testNeutralIsPassthrough() {
    VoiceParams neutral;
    std::vector<float> in = noiseStereo(5000, 0.3f, 3);
    std::vector<float> out = run(neutral, in, 777);
    CHECK(out == in);
}

static void testAlignmentAndGainOfThePhaseVocoder() {
    // Pitch ratio 1 (forced through the vocoder): the output is the input, aligned and at the same level.
    VoiceParams identity;
    std::vector<float> in(2 * 24000);
    for (int i = 0; i < 24000; ++i) {
        const float s = 0.3f * static_cast<float>(std::sin(2.0 * kPi * 300.0 * i / kRate) + 0.5 * std::sin(2.0 * kPi * 1234.0 * i / kRate));
        in[static_cast<size_t>(2 * i)] = s;
        in[static_cast<size_t>(2 * i + 1)] = -s;
    }
    std::vector<float> out = run(identity, in, 1000, 0, true);
    CHECK(out.size() == in.size());  // exactly as many frames as were fed
    double err = 0, sig = 0;
    for (size_t i = 6000; i < 20000; ++i) {
        const double d = static_cast<double>(out[2 * i]) - in[2 * i];
        err += d * d;
        sig += static_cast<double>(in[2 * i]) * in[2 * i];
        CHECK(std::fabs(out[2 * i + 1] + out[2 * i]) < 1e-6);  // channels stay independent and symmetric
    }
    const double snr = 10.0 * std::log10(sig / err);
    std::printf("phase vocoder identity SNR %.1f dB\n", snr);
    CHECK(snr > 30.0);
}

static void testPitchAccuracy() {
    const double base = 440.0;
    std::vector<float> in = stereoSine(base, 0.4, 3 * kRate);
    for (float semis : {7.0f, 3.0f, -5.0f, 12.0f, -12.0f}) {
        VoiceParams p;
        p.pitchSemitones = semis;
        p.formantSemitones = semis;  // linked: plain bin shifting, a pure sine stays a pure sine
        std::vector<float> out = run(p, in, 4096);
        const double expected = base * std::pow(2.0, semis / 12.0);
        const double f = frequencyOf(out, kRate / 2, 5 * kRate / 2);
        std::printf("pitch %+.0f st: %.2f Hz (expected %.2f, %+.1f cents)\n", semis, f, expected, cents(f, expected));
        CHECK(std::fabs(cents(f, expected)) < 8.0);
        const double level = rms(out, kRate / 2, 5 * kRate / 2) / (0.4 * 0.7071);
        CHECK(level > 0.85 && level < 1.1);  // the level of a shifted sinusoid is kept (within 1.5 dB)
    }
}

// A vowel-like test signal: a glottal pulse train at f0 through two resonators (formants).
static std::vector<float> vowel(double f0, int frames, double f1 = 700.0, double f2 = 1700.0) {
    auto resonator = [](double fc, double r) { return std::pair<double, double>{2.0 * r * std::cos(2.0 * kPi * fc / kRate), -r * r}; };
    const auto a = resonator(f1, 0.985), b = resonator(f2, 0.985);
    std::vector<float> v(static_cast<size_t>(frames) * 2);
    double y1[2] = {0, 0}, y2[2] = {0, 0};
    const int period = static_cast<int>(std::lround(kRate / f0));
    for (int i = 0; i < frames; ++i) {
        const double x = (i % period == 0) ? 1.0 : 0.0;
        double s = 0;
        const double c1 = a.first * y1[0] + a.second * y2[0] + x;
        y2[0] = y1[0];
        y1[0] = c1;
        const double c2 = b.first * y1[1] + b.second * y2[1] + x;
        y2[1] = y1[1];
        y1[1] = c2;
        s = 0.02 * (c1 + 0.8 * c2);
        v[static_cast<size_t>(2 * i)] = static_cast<float>(s);
        v[static_cast<size_t>(2 * i + 1)] = static_cast<float>(s);
    }
    return v;
}

// Power-weighted mean frequency 200..5000 Hz of the left channel of `v` over [from, to).
static double centroid(const std::vector<float>& v, size_t from, size_t to) {
    double num = 0, den = 0;
    for (double f = 200.0; f <= 5000.0; f += 25.0) {
        const double a = toneAmplitude(v, f, from, to);
        num += f * a * a;
        den += a * a;
    }
    return num / den;
}

static void testFormantControlsTheEnvelope() {
    std::vector<float> in = vowel(120.0, 3 * kRate);
    const double original = centroid(in, kRate, 2 * kRate);

    VoiceParams linked;  // pitch and formants move together: the voice just gets higher
    linked.pitchSemitones = 4.0f;
    linked.formantSemitones = 4.0f;
    VoiceParams preserved;  // pitch moves, the timbre stays
    preserved.pitchSemitones = 4.0f;
    VoiceParams formantUp;  // pitch moves and the vocal tract gets smaller
    formantUp.pitchSemitones = 4.0f;
    formantUp.formantSemitones = 8.0f;

    const double cLinked = centroid(run(linked, in, 4096), kRate, 2 * kRate);
    const double cPreserved = centroid(run(preserved, in, 4096), kRate, 2 * kRate);
    const double cUp = centroid(run(formantUp, in, 4096), kRate, 2 * kRate);
    std::printf("centroid: original %.0f, linked %.0f, preserved %.0f, formant +8 %.0f Hz\n", original, cLinked, cPreserved, cUp);
    CHECK(std::fabs(cPreserved / original - 1.0) < 0.2);  // the envelope stayed where it was
    CHECK(cLinked / original > 1.15);                     // plain shifting moved it up
    CHECK(cUp / cPreserved > 1.2);                        // the formant control moved it independently
}

static void testWhisperRemovesThePitch() {
    std::vector<float> in = vowel(150.0, 3 * kRate);
    VoiceParams w;
    w.whisperMix = 1.0f;
    std::vector<float> out = run(w, in, 4096);
    const double a = rms(in, kRate, 2 * kRate), b = rms(out, kRate, 2 * kRate);
    CHECK(b > a * 0.5 && b < a * 2.0);  // roughly the same loudness
    // Periodicity: normalised autocorrelation at the pitch lag.
    auto periodicity = [](const std::vector<float>& v) {
        const int lag = kRate / 150;
        double num = 0, d0 = 0, d1 = 0;
        for (size_t i = kRate; i < 2 * static_cast<size_t>(kRate); ++i) {
            const double x = v[2 * i], y = v[2 * (i + static_cast<size_t>(lag))];
            num += x * y;
            d0 += x * x;
            d1 += y * y;
        }
        return num / std::sqrt(d0 * d1);
    };
    const double pIn = periodicity(in), pOut = periodicity(out);
    std::printf("periodicity input %.2f, whisper %.2f\n", pIn, pOut);
    CHECK(pIn > 0.8 && pOut < 0.4);
}

// ------------------------------------------------------------------ classical blocks

static void testRingModulation() {
    VoiceParams p;
    p.ringHz = 100.0f;
    p.ringMix = 1.0f;
    std::vector<float> out = run(p, stereoSine(1000.0, 0.5, kRate), 2048);
    const double carrier = toneAmplitude(out, 1000.0, 4800, 43200);
    const double lower = toneAmplitude(out, 900.0, 4800, 43200), upper = toneAmplitude(out, 1100.0, 4800, 43200);
    CHECK_NEAR(lower, 0.25, 0.02);
    CHECK_NEAR(upper, 0.25, 0.02);
    CHECK(carrier < 0.02);
    p.ringMix = 0.5f;
    out = run(p, stereoSine(1000.0, 0.5, kRate), 2048);
    CHECK_NEAR(toneAmplitude(out, 1000.0, 4800, 43200), 0.25, 0.02);  // half of the dry level remains
}

static void testBandAndDrive() {
    VoiceParams p;
    p.bandLowHz = 400.0f;
    p.bandHighHz = 3400.0f;
    auto level = [&](double f) { return toneAmplitude(run(p, stereoSine(f, 0.2, kRate), 1024), f, 9600, 43200); };
    CHECK(level(1000.0) > 0.18);
    CHECK(level(60.0) < 0.2 * 0.05);
    CHECK(level(12000.0) < 0.2 * 0.05);

    VoiceParams drive;
    drive.driveDb = 20.0f;
    std::vector<float> out = run(drive, stereoSine(500.0, 0.3, kRate), 1024);
    CHECK(toneAmplitude(out, 1500.0, 4800, 43200) > 0.03);  // odd harmonics appear
    CHECK(peakOf(out) <= 1.0);
}

static void testEchoImpulseResponse() {
    VoiceParams p;
    p.echoMs = 100.0f;
    p.echoFeedback = 0.5f;
    p.echoMix = 0.8f;
    std::vector<float> in(2 * 3 * kRate, 0.0f);
    in[2 * 1000] = 1.0f;
    in[2 * 1000 + 1] = 1.0f;
    std::vector<float> out = run(p, in, 4096);
    const size_t d = 4800;
    CHECK_NEAR(out[2 * 1000], 1.0, 1e-6);
    CHECK_NEAR(out[2 * (1000 + d)], 0.8, 1e-6);
    CHECK_NEAR(out[2 * (1000 + 2 * d)], 0.4, 1e-6);
    CHECK_NEAR(out[2 * (1000 + 3 * d)], 0.2, 1e-6);
    CHECK_NEAR(out[2 * (1000 + d) + 1], 0.8, 1e-6);  // both channels
    CHECK_NEAR(out[2 * (1000 + d / 2)], 0.0, 1e-6);  // nothing in between
}

static void testReverbImpulseResponse() {
    std::vector<float> in(2 * 6 * kRate, 0.0f);
    in[2 * 100] = 1.0f;
    in[2 * 100 + 1] = 1.0f;
    auto decayTime = [&](float size) {
        VoiceParams p;
        p.reverbMix = 1.0f;
        p.reverbSize = size;
        std::vector<float> out = run(p, in, 4096);
        // Time at which the short-term energy last exceeds -40 dB of its early peak.
        double peak = 0;
        for (size_t at = 0; at + 512 < out.size() / 2; at += 512) peak = std::max(peak, rms(out, at, at + 512));
        size_t last = 0;
        for (size_t at = 0; at + 512 < out.size() / 2; at += 512) {
            if (rms(out, at, at + 512) > peak * 0.01) last = at;
        }
        return static_cast<double>(last) / kRate;
    };
    const double small = decayTime(0.1f), large = decayTime(1.0f);
    std::printf("reverb -40 dB at %.2f s (small) and %.2f s (large)\n", small, large);
    CHECK(small > 0.1 && small < large);
    CHECK(large > 1.0 && large < 7.5);

    VoiceParams p;
    p.reverbMix = 0.5f;
    p.reverbSize = 0.5f;
    std::vector<float> out = run(p, in, 4096);
    double diff = 0;
    for (size_t i = 0; i < out.size() / 2; ++i) diff += std::fabs(out[2 * i] - out[2 * i + 1]);
    CHECK(diff > 0.01);  // the two channels are decorrelated

    // Mix 0 leaves the signal alone.
    VoiceParams off;
    off.reverbSize = 0.8f;
    CHECK(run(off, in, 4096) == in);
}

static void testReverbLevelAgainstNoise() {
    VoiceParams p;
    p.reverbMix = 1.0f;
    p.reverbSize = 0.5f;
    std::vector<float> in = noiseStereo(2 * kRate, 0.2f, 9);
    std::vector<float> out = run(p, in, 4096);
    std::vector<float> wet(out.size());
    for (size_t i = 0; i < out.size(); ++i) wet[i] = out[i] - in[i];
    const double ratio = rms(wet, kRate / 2, 3 * kRate / 2) / rms(in, kRate / 2, 3 * kRate / 2);
    std::printf("reverb wet/dry level for noise: %.2f\n", ratio);
    CHECK(ratio > 0.3 && ratio < 2.0);
}

// ------------------------------------------------------------------ stream behaviour

static VoiceParams fullChain() {
    VoiceParams p;
    p.pitchSemitones = 3.0f;
    p.formantSemitones = -2.0f;
    p.whisperMix = 0.3f;
    p.ringHz = 70.0f;
    p.ringMix = 0.3f;
    p.bandLowHz = 200.0f;
    p.bandHighHz = 7000.0f;
    p.driveDb = 6.0f;
    p.echoMs = 180.0f;
    p.echoFeedback = 0.4f;
    p.echoMix = 0.3f;
    p.reverbSize = 0.6f;
    p.reverbMix = 0.3f;
    return p;
}

static void testChunkInvariance() {
    const VoiceParams p = fullChain();
    std::vector<float> in = vowel(130.0, 2 * kRate);
    const int64_t tail = 20000;
    std::vector<float> reference = run(p, in, in.size() / 2, tail);
    for (size_t chunk : {size_t{1}, size_t{77}, size_t{512}, size_t{1000}, size_t{4096}}) {
        std::vector<float> got = run(p, in, chunk, tail);
        CHECK(got.size() == reference.size());
        CHECK(std::memcmp(got.data(), reference.data(), std::min(got.size(), reference.size()) * sizeof(float)) == 0);
    }
    // Input frames fed + the requested tail come back, no more and no less.
    CHECK(reference.size() / 2 == in.size() / 2 + static_cast<size_t>(std::min<int64_t>(tail, p.tailFrames(kRate))));
}

static void testLengthAndTail() {
    VoiceParams p;
    p.pitchSemitones = 4.0f;
    p.formantSemitones = 4.0f;
    std::vector<float> in = stereoSine(300.0, 0.3, 20000);
    CHECK(run(p, in, 999).size() == in.size());           // no echo or reverb: exactly the input length
    CHECK(run(p, in, 999, 50000).size() == in.size());    // and a tail request changes nothing

    VoiceParams e;
    e.echoMs = 200.0f;
    e.echoFeedback = 0.5f;
    e.echoMix = 0.5f;
    std::vector<float> clip = stereoSine(300.0, 0.3, 10000);
    const size_t withTail = run(e, clip, 999, 30000).size() / 2;
    CHECK(withTail == clip.size() / 2 + 30000);           // the requested tail
    CHECK(run(e, clip, 999, 0).size() == clip.size());    // none requested
    CHECK(run(e, clip, 999, 10000000).size() / 2 <= clip.size() / 2 + static_cast<size_t>(e.tailFrames(kRate)));  // never beyond its own

    // A short input and an empty one.
    std::vector<float> few = stereoSine(300.0, 0.3, 100);
    CHECK(run(p, few, 7).size() == few.size());
    VoiceProcessor empty(p, kRate);
    std::vector<float> nothing;
    empty.flush(&nothing, 1000);
    CHECK(nothing.empty());
}

static void testResetRestartsCleanly() {
    const VoiceParams p = fullChain();
    std::vector<float> in = vowel(130.0, kRate);
    VoiceProcessor proc(p, kRate);
    std::vector<float> first, second;
    proc.process(in.data(), in.size() / 2, &first);
    proc.reset();
    proc.process(in.data(), in.size() / 2, &second);
    CHECK(first == second);  // a seek starts exactly like the first play
}

static void testFiniteAndNoDenormals() {
    VoiceParams extreme = fullChain();
    extreme.pitchSemitones = 12.0f;
    extreme.formantSemitones = -12.0f;
    extreme.whisperMix = 0.7f;
    extreme.echoFeedback = 0.95f;
    extreme.reverbSize = 1.0f;
    extreme.reverbMix = 1.0f;
    extreme.driveDb = 36.0f;
    // Loud noise, then silence long enough for every feedback loop to die out.
    std::vector<float> in = noiseStereo(kRate, 0.9f, 17);
    in.resize(in.size() + 2 * 6 * kRate, 0.0f);
    std::vector<float> out = run(extreme, in, 3000, 6 * kRate);
    size_t bad = 0, subnormal = 0;
    for (float v : out) {
        if (!std::isfinite(v)) ++bad;
        if (std::fpclassify(v) == FP_SUBNORMAL) ++subnormal;
    }
    CHECK(bad == 0);
    CHECK(subnormal == 0);

    // Silence in, exact silence out (no noise from the vocoder's random phases or the feedback loops).
    std::vector<float> quiet(2 * kRate, 0.0f);
    std::vector<float> nothing = run(extreme, quiet, 1000, 3 * kRate);
    double peak = 0;
    for (float v : nothing) peak = std::max(peak, static_cast<double>(std::fabs(v)));
    CHECK(peak < 1e-9);
}

static void testSpeed() {
    const VoiceParams p = fullChain();
    std::vector<float> in = vowel(130.0, 10 * kRate);
    const auto t0 = std::chrono::steady_clock::now();
    std::vector<float> out = run(p, in, 4096, 10000);
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    std::printf("full chain: 10 s of stereo in %.2f s (%.1fx real time)\n", seconds, 10.0 / seconds);
    CHECK(out.size() >= in.size());
    CHECK(seconds < 10.0);  // far above what is expected, only guards against an accidental quadratic cost
}

int main() {
    testParams();
    testNeutralIsPassthrough();
    testAlignmentAndGainOfThePhaseVocoder();
    testPitchAccuracy();
    testFormantControlsTheEnvelope();
    testWhisperRemovesThePitch();
    testRingModulation();
    testBandAndDrive();
    testEchoImpulseResponse();
    testReverbImpulseResponse();
    testReverbLevelAgainstNoise();
    testChunkInvariance();
    testLengthAndTail();
    testResetRestartsCleanly();
    testFiniteAndNoDenormals();
    testSpeed();
    if (g_failures == 0) std::printf("voice host tests passed\n");
    return g_failures == 0 ? 0 : 1;
}
