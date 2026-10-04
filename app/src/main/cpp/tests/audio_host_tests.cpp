// GoogleTest-free host tests for the audio playback core: time math, clock mapping, resampler,
// clip buffer, snapshot parsing, mixer and the AudioCore state machine driven by a fake decoder.
// Build/run: see tests/CMakeLists.txt (documented in CLAUDE.md).
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

#include "audio/analysis.h"
#include "audio/audio_core.h"
#include "audio/audio_mixer.h"
#include "audio/loudness.h"
#include "audio/spectral_denoise.h"
#include "audio/audio_snapshot.h"
#include "audio/audio_time.h"
#include "audio/clip_buffer.h"
#include "audio/clock_mapper.h"
#include "audio/resampler.h"
#include "audio/retime_source.h"
#include "core/crossfade_math.h"

using namespace uv;
using namespace uv::audio;
using core::Status;

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)
#define CHECK_NEAR(a, b, eps) CHECK(std::fabs(static_cast<double>(a) - static_cast<double>(b)) <= (eps))

// ------------------------------------------------------------------ time

static void testTimeMath() {
    const Rational ntsc{30000, 1001};
    // One hour of 29.97 fps is 107892 frames; check exact sample tiling at 48 kHz.
    const int64_t s0 = framesToSamples(0, ntsc, 48000);
    const int64_t s1 = framesToSamples(1, ntsc, 48000);
    CHECK(s0 == 0);
    CHECK(s1 == 1602);  // 48000 * 1001 / 30000 = 1601.6, rounded half up
    int64_t prevEnd = 0;
    for (int64_t f = 0; f < 5000; ++f) {  // contiguous clips of one frame never gap or overlap
        const int64_t a = framesToSamples(f, ntsc, 48000);
        const int64_t b = framesToSamples(f + 1, ntsc, 48000);
        CHECK(a == prevEnd);
        CHECK(b > a);
        prevEnd = b;
    }
    // No drift: 24 hours of frames lands on the exact rational sample.
    const int64_t day = 24LL * 3600 * 30000 / 1001;
    CHECK(framesToSamples(day, ntsc, 48000) == (2LL * day * 48000 * 1001 + 30000) / (2LL * 30000));
    // sample -> frame is the floor inverse.
    CHECK(samplesToFrames(1601, ntsc, 48000) == 0);
    CHECK(samplesToFrames(1602, ntsc, 48000) == 1);
    for (int64_t f = 0; f < 2000; ++f) CHECK(samplesToFrames(framesToSamples(f, ntsc, 48000), ntsc, 48000) == f);

    CHECK(floorDiv(-1, 3) == -1 && floorDiv(-3, 3) == -1 && floorDiv(-4, 3) == -2 && floorDiv(7, 3) == 2);
    CHECK(sourceFramesToMicros(60, Rational{30, 1}) == 2000000);
    CHECK(sourceFramesToMicros(1, Rational{30000, 1001}) == 33367);  // 33366.67 us
    CHECK(samplesToMicros(48000, 48000) == 1000000);
    CHECK(microsToSamples(1000000, 44100) == 44100);
    CHECK(microsToSamples(samplesToMicros(123456, 48000), 48000) == 123456);

    // Extrapolating a hardware timestamp: 10 ms after frame 1000 was presented at 48 kHz.
    CHECK(presentedStreamFrame(1000, 5'000'000'000LL, 5'010'000'000LL, 48000) == 1480);
    CHECK(presentedStreamFrame(1000, 5'000'000'000LL, 5'000'000'000LL, 48000) == 1000);
}

static void testClockMapper() {
    ClockAnchor a{1000, 50000, true};
    CHECK(timelineSampleAt(a, 1000, 5000) == 50000);
    CHECK(timelineSampleAt(a, 1500, 5000) == 50500);
    CHECK(timelineSampleAt(a, 400, 5000) == 50000);    // still hearing pre-anchor audio
    CHECK(timelineSampleAt(a, 9000, 5000) == 54000);   // cannot run ahead of what was rendered
    ClockAnchor frozen{1000, 50000, false};
    CHECK(timelineSampleAt(frozen, 99999, 99999) == 50000);
}

// ------------------------------------------------------------------ resampler

static void testResampler() {
    std::vector<float> in(2 * 1000);
    for (int i = 0; i < 1000; ++i) in[2 * i] = in[2 * i + 1] = static_cast<float>(i);

    LinearResampler same(48000, 48000);
    std::vector<float> out;
    same.process(in.data(), 1000, &out);
    CHECK(out == in);

    // 44.1k -> 48k of a ramp stays on the ramp (linear interpolation is exact for lines).
    LinearResampler up(44100, 48000);
    out.clear();
    up.process(in.data(), 1000, &out);
    CHECK(out.size() / 2 >= 1085 && out.size() / 2 <= 1088);
    for (size_t k = 0; k < out.size() / 2; ++k) {
        CHECK_NEAR(out[2 * k], static_cast<double>(k) * 44100.0 / 48000.0, 1e-3);
    }

    // Chunking must not change the result (no state leaks between process() calls).
    LinearResampler chunked(44100, 48000);
    std::vector<float> out2;
    for (int i = 0; i < 1000; i += 37) chunked.process(in.data() + 2 * i, static_cast<size_t>(std::min(37, 1000 - i)), &out2);
    CHECK(out2 == out);

    // Down-conversion and an exact-multiple ratio.
    LinearResampler down(48000, 24000);
    out.clear();
    down.process(in.data(), 1000, &out);
    CHECK(out.size() / 2 == 500);
    CHECK_NEAR(out[2 * 10], 20.0, 1e-4);

    up.reset();
    out.clear();
    up.process(in.data(), 100, &out);
    CHECK_NEAR(out[0], 0.0, 1e-6);  // phase restarts at zero after reset
}

// ------------------------------------------------------------------ clip buffer

static void testClipBuffer() {
    ClipBuffer b(8);
    CHECK(!b.allocated());
    float tmp[16];
    CHECK(!b.read(0, 1, tmp));  // nothing allocated yet
    b.allocate();
    CHECK(!b.read(0, 1, tmp));  // allocated but empty

    float frames[2 * 6];
    for (int i = 0; i < 6; ++i) frames[2 * i] = frames[2 * i + 1] = static_cast<float>(i + 1);
    b.append(frames, 6);
    CHECK(b.windowStart() == 0 && b.writeEnd() == 6);
    CHECK(b.read(0, 6, tmp) && tmp[0] == 1.0f && tmp[10] == 6.0f);
    CHECK(!b.read(0, 7, tmp));  // beyond what was written
    CHECK(b.covers(2, 4) && !b.covers(2, 5));

    // Wrapping append slides the window; old frames are no longer trusted.
    for (int i = 0; i < 6; ++i) frames[2 * i] = frames[2 * i + 1] = static_cast<float>(i + 7);
    b.append(frames, 6);
    CHECK(b.writeEnd() == 12 && b.windowStart() == 4);
    CHECK(!b.read(3, 1, tmp));
    CHECK(b.read(4, 8, tmp) && tmp[0] == 5.0f && tmp[2] == 6.0f && tmp[4] == 7.0f && tmp[14] == 12.0f);

    b.reset(100);
    CHECK(b.windowStart() == 100 && b.writeEnd() == 100);
    CHECK(!b.read(4, 1, tmp));
    b.append(frames, 3);
    CHECK(b.read(100, 3, tmp) && tmp[0] == 7.0f);

    float* old = b.detach();
    CHECK(old != nullptr && !b.allocated() && !b.read(100, 1, tmp));
    ClipBuffer::release(old);
}

// ------------------------------------------------------------------ snapshot parsing

struct Buf {
    std::vector<uint8_t> b;
    template <typename T>
    void put(T v) {
        const auto* p = reinterpret_cast<const uint8_t*>(&v);
        b.insert(b.end(), p, p + sizeof(T));
    }
};

// Tracks, ducking and master flags that go with a snapshot (defaults: no tracks listed, which the
// parser turns into one default track, no ducking, limiter on).
struct SnapExtras {
    std::vector<AudioTrackDesc> tracks;
    dsp::DuckerParams ducking;
    bool limiterOff = false;
};

static Buf makeAudioSnapshot(int32_t fpsNum, int32_t fpsDen, const std::vector<AudioClipDesc>& clips,
                             const SnapExtras& extra = {}) {
    Buf w;
    w.put<uint32_t>(kAudioSnapshotMagic);
    w.put<uint32_t>(kAudioSnapshotVersion);
    w.put<int32_t>(fpsNum);
    w.put<int32_t>(fpsDen);
    w.put<uint32_t>(static_cast<uint32_t>(clips.size()));
    w.put<uint32_t>(static_cast<uint32_t>(extra.tracks.size()));
    w.put<uint32_t>(extra.limiterOff ? 1u : 0u);
    for (const AudioTrackDesc& t : extra.tracks) {
        w.put<int64_t>(t.trackKey);
        w.put<float>(t.gainDb);
        w.put<uint32_t>((t.muted ? 1u : 0u) | (t.comp.enabled ? 2u : 0u));
        w.put<uint32_t>(static_cast<uint32_t>(t.role));
        w.put<float>(t.comp.thresholdDb);
        w.put<float>(t.comp.ratio);
        w.put<float>(t.comp.attackMs);
        w.put<float>(t.comp.releaseMs);
        w.put<float>(t.comp.makeupDb);
    }
    w.put<float>(extra.ducking.amountDb);
    w.put<float>(extra.ducking.thresholdDb);
    w.put<float>(extra.ducking.attackMs);
    w.put<float>(extra.ducking.releaseMs);
    for (const auto& c : clips) {
        w.put<int64_t>(c.clipKey);
        w.put<int64_t>(c.assetKey);
        w.put<int64_t>(c.startFrame);
        w.put<int64_t>(c.durationFrames);
        w.put<int64_t>(c.sourceInFrame);
        w.put<int32_t>(c.sourceFps.num);
        w.put<int32_t>(c.sourceFps.den);
        w.put<float>(c.gainDb);
        w.put<int32_t>(static_cast<int32_t>(c.fadeInFrames));
        w.put<int32_t>(static_cast<int32_t>(c.fadeOutFrames));
        w.put<int32_t>(static_cast<int32_t>(c.knots.size()));
        // audio block
        w.put<int32_t>(c.trackIndex);
        w.put<float>(c.pan);
        w.put<int32_t>(static_cast<int32_t>(c.userFadeInFrames));
        w.put<int32_t>(static_cast<int32_t>(c.userFadeOutFrames));
        w.put<float>(c.eq.highPassHz);
        w.put<float>(c.eq.lowPassHz);
        for (const dsp::EqBandParams& b : c.eq.bands) {
            w.put<float>(b.freqHz);
            w.put<float>(b.gainDb);
            w.put<float>(b.q);
        }
        w.put<float>(c.denoiseStrength);
        w.put<int32_t>(static_cast<int32_t>(c.noiseProfile.size()));
        w.put<uint32_t>(static_cast<uint32_t>(c.lanes.size()));
    }
    for (const auto& c : clips) {
        for (const RetimeKnot& k : c.knots) {
            w.put<int64_t>(k.frame);
            w.put<double>(k.sourceFrame);
        }
    }
    for (const auto& c : clips) {
        for (float v : c.noiseProfile) w.put<float>(v);
    }
    for (const auto& c : clips) {
        for (const AutoLane& lane : c.lanes) {
            w.put<int32_t>(static_cast<int32_t>(lane.param));
            w.put<uint32_t>(static_cast<uint32_t>(lane.points.size()));
            w.put<uint32_t>(0);
            w.put<uint32_t>(0);
            for (const AutoPoint& p : lane.points) {
                w.put<int64_t>(p.frame);
                w.put<float>(p.value);
                w.put<uint32_t>(0);
            }
        }
    }
    return w;
}

static AudioClipDesc clipDesc(int64_t key, int64_t start, int64_t dur, int64_t srcIn = 0, float gainDb = 0.0f) {
    AudioClipDesc c;
    c.clipKey = key;
    c.assetKey = 1;
    c.startFrame = start;
    c.durationFrames = dur;
    c.sourceInFrame = srcIn;
    c.sourceFps = Rational{30, 1};
    c.gainDb = gainDb;
    return c;
}

static void testSnapshotParsing() {
    AudioSnapshotData out;
    Buf ok = makeAudioSnapshot(30000, 1001, {clipDesc(7, 10, 20, 5, -6.0f), clipDesc(8, 30, 1)});
    CHECK(parseAudioSnapshot(ok.b.data(), ok.b.size(), &out) == Status::Ok);
    CHECK(out.fps.num == 30000 && out.fps.den == 1001 && out.clips.size() == 2);
    CHECK(out.clips[0].clipKey == 7 && out.clips[0].startFrame == 10 && out.clips[0].durationFrames == 20);
    CHECK(out.clips[0].sourceInFrame == 5 && out.clips[0].gainDb == -6.0f && out.clips[1].clipKey == 8);

    Buf empty = makeAudioSnapshot(30, 1, {});
    CHECK(parseAudioSnapshot(empty.b.data(), empty.b.size(), &out) == Status::Ok && out.clips.empty());

    Buf bad = ok;
    bad.b[0] ^= 0xFF;  // magic
    CHECK(parseAudioSnapshot(bad.b.data(), bad.b.size(), &out) == Status::BadSnapshot);
    CHECK(parseAudioSnapshot(ok.b.data(), ok.b.size() - 1, &out) == Status::BadSnapshot);  // truncated
    CHECK(parseAudioSnapshot(ok.b.data(), 5, &out) == Status::BadSnapshot);
    CHECK(parseAudioSnapshot(nullptr, 0, &out) == Status::BadSnapshot);

    CHECK(parseAudioSnapshot(makeAudioSnapshot(0, 1, {}).b.data(), kAudioSnapshotHeaderBytes, &out) == Status::BadSnapshot);
    Buf zeroDur = makeAudioSnapshot(30, 1, {clipDesc(1, 0, 0)});
    CHECK(parseAudioSnapshot(zeroDur.b.data(), zeroDur.b.size(), &out) == Status::BadSnapshot);
    Buf negStart = makeAudioSnapshot(30, 1, {clipDesc(1, -1, 5)});
    CHECK(parseAudioSnapshot(negStart.b.data(), negStart.b.size(), &out) == Status::BadSnapshot);
    Buf loud = makeAudioSnapshot(30, 1, {clipDesc(1, 0, 5, 0, 60.0f)});
    CHECK(parseAudioSnapshot(loud.b.data(), loud.b.size(), &out) == Status::BadSnapshot);
    Buf nan = makeAudioSnapshot(30, 1, {clipDesc(1, 0, 5, 0, std::nanf(""))});
    CHECK(parseAudioSnapshot(nan.b.data(), nan.b.size(), &out) == Status::BadSnapshot);

    // Crossfade lengths: carried through, and they must fit inside the clip.
    AudioClipDesc faded = clipDesc(1, 0, 20);
    faded.fadeInFrames = 10;
    faded.fadeOutFrames = 20;
    Buf fades = makeAudioSnapshot(30, 1, {faded});
    CHECK(parseAudioSnapshot(fades.b.data(), fades.b.size(), &out) == Status::Ok);
    CHECK(out.clips[0].fadeInFrames == 10 && out.clips[0].fadeOutFrames == 20);
    faded.fadeInFrames = 21;
    Buf tooLong = makeAudioSnapshot(30, 1, {faded});
    CHECK(parseAudioSnapshot(tooLong.b.data(), tooLong.b.size(), &out) == Status::BadSnapshot);
    faded.fadeInFrames = -1;
    Buf negative = makeAudioSnapshot(30, 1, {faded});
    CHECK(parseAudioSnapshot(negative.b.data(), negative.b.size(), &out) == Status::BadSnapshot);
    // A version 1 snapshot (56-byte clips) is rejected rather than misread.
    Buf old = makeAudioSnapshot(30, 1, {});
    old.b[4] = 1;
    CHECK(parseAudioSnapshot(old.b.data(), old.b.size(), &out) == Status::BadSnapshot);
}

// ------------------------------------------------------------------ fake decoder + core

static std::atomic<int> g_liveDecoders{0};
static std::atomic<int> g_createdDecoders{0};
static std::atomic<int> g_seeks{0};

struct FakeSpec {
    int32_t rate = 48000;
    int64_t totalFrames = 48000 * 600;  // source frames available
    float constant = -1.0f;             // >= 0: constant signal instead of the index ramp
    Status openStatus = Status::Ok;     // != Ok: factory fails
    int failReads = 0;                  // the first N read() calls (across decoders) fail with CodecError
    // Synthetic signals for the audio tools: a sine and/or deterministic uniform noise instead of the ramp.
    double sineHz = 0.0;
    float sineAmp = 0.0f;
    float noiseAmp = 0.0f;
    uint32_t noiseSeed = 1;
    // A different tone for one asset (the "voice") while every other asset plays the base signal.
    int64_t voiceAsset = -1;
    double voiceHz = 0.0;
    float voiceAmp = 0.0f;
};
static std::atomic<int> g_readsToFail{0};
static FakeSpec g_spec;

// Source sample i has a unique, reproducible value so placement and seeking can be verified.
static float valueAt(int64_t i) { return static_cast<float>((i % 20000) + 1) / 40000.0f; }

// Deterministic white noise in [-1, 1) that depends only on the sample index and the seed.
static float noiseAt(int64_t i, uint32_t seed) {
    uint32_t x = static_cast<uint32_t>(i) * 2654435761u + seed * 40503u;
    x ^= x >> 15;
    x *= 2246822519u;
    x ^= x >> 13;
    x *= 3266489917u;
    x ^= x >> 16;
    return static_cast<float>((x >> 8) & 0xFFFF) / 32768.0f - 1.0f;
}

static float signalAt(const FakeSpec& s, int64_t asset, int64_t i) {
    if (asset == s.voiceAsset) return s.voiceAmp * static_cast<float>(std::sin(2.0 * 3.14159265358979323846 * s.voiceHz * static_cast<double>(i) / s.rate));
    if (s.sineHz > 0.0 || s.noiseAmp > 0.0f) {
        float v = 0.0f;
        if (s.sineHz > 0.0) v += s.sineAmp * static_cast<float>(std::sin(2.0 * 3.14159265358979323846 * s.sineHz * static_cast<double>(i) / s.rate));
        if (s.noiseAmp > 0.0f) v += s.noiseAmp * noiseAt(i, s.noiseSeed);
        return v;
    }
    return s.constant >= 0 ? s.constant : valueAt(i);
}

class FakeDecoder : public PcmDecoder {
public:
    explicit FakeDecoder(FakeSpec s, int64_t asset = -2) : spec_(s), asset_(asset) {
        ++g_liveDecoders;
        ++g_createdDecoders;
    }
    ~FakeDecoder() override { --g_liveDecoders; }
    int32_t sampleRate() const override { return spec_.rate; }
    Status seekToMicros(int64_t us) override {
        ++g_seeks;
        pos_ = microsToSamples(us, spec_.rate);
        return Status::Ok;
    }
    PcmReadResult read(float* dst, int32_t maxFrames) override {
        PcmReadResult r;
        if (g_readsToFail.load() > 0) {
            --g_readsToFail;
            r.status = Status::CodecError;
            return r;
        }
        if (pos_ >= spec_.totalFrames) {
            r.eof = true;
            return r;
        }
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(maxFrames, spec_.totalFrames - pos_));
        for (int32_t i = 0; i < n; ++i) {
            const float v = signalAt(spec_, asset_, pos_ + i);
            dst[2 * i] = dst[2 * i + 1] = v;
        }
        pos_ += n;
        r.frames = n;
        r.eof = pos_ >= spec_.totalFrames;
        return r;
    }

private:
    FakeSpec spec_;
    int64_t asset_;
    int64_t pos_ = 0;
};

static DecoderFactory fakeFactory() {
    return [](int64_t asset, Status* st) -> std::unique_ptr<PcmDecoder> {
        if (g_spec.openStatus != Status::Ok) {
            *st = g_spec.openStatus;
            return nullptr;
        }
        return std::make_unique<FakeDecoder>(g_spec, asset);
    };
}

static AudioSnapshotData snap30(std::vector<AudioClipDesc> clips) {
    AudioSnapshotData d;
    d.fps = Rational{30, 1};  // 1 frame = 1600 samples at 48 kHz
    d.clips = std::move(clips);
    return d;
}

constexpr int kBlock = 480;

// Renders one block, running the worker pass first when `service` is set (deterministic mode).
static void step(AudioCore& core, std::vector<float>* out, bool service = true) {
    if (service) core.serviceOnce();
    const size_t at = out->size();
    out->resize(at + kBlock * 2);
    core.render(out->data() + at, kBlock);
}

// Renders until playback leaves its warm-up hold; `out` receives only audible blocks.
static bool renderUntilPlaying(AudioCore& core, std::vector<float>* out, int maxBlocks = 200) {
    std::vector<float> scratch;
    for (int i = 0; i < maxBlocks; ++i) {
        core.serviceOnce();
        scratch.assign(kBlock * 2, 0.0f);
        core.render(scratch.data(), kBlock);
        if (core.anchor().playing) {
            out->insert(out->end(), scratch.begin(), scratch.end());
            return true;
        }
    }
    return false;
}

static void testMixerPlacementAndGain() {
    g_spec = FakeSpec{};
    AudioCore core(fakeFactory());
    CHECK(core.configure(48000) == Status::Ok);
    core.streamStarting();
    // clip 1: frames 0-30 (1 s) from source 0; clip 2: frames 30-60 from source second 2, -6.0206 dB.
    CHECK(core.setSnapshot(snap30({clipDesc(1, 0, 30, 0), clipDesc(2, 30, 30, 60, -6.0206f)})) == Status::Ok);
    core.play();

    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    CHECK(core.renderPosSamples() == kBlock);  // holding did not advance the playhead
    while (out.size() / 2 < 96000) step(core, &out);

    int bad = 0;
    for (int64_t k = 0; k < 96000; ++k) {
        const float expect = k < 48000 ? valueAt(k) : valueAt(96000 + (k - 48000)) * 0.5f;
        if (std::fabs(out[2 * k] - expect) > 1e-4f || std::fabs(out[2 * k + 1] - expect) > 1e-4f) ++bad;
    }
    CHECK(bad == 0);
    CHECK(core.underrunBlocks() == 0);
    std::vector<AudioFault> faults;
    core.pollFaults(&faults);
    CHECK(faults.empty());
    core.streamStopped();
}

static void testOverlapSumsAndClips() {
    g_spec = FakeSpec{};
    g_spec.constant = 0.75f;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    // Two simultaneous 0.75 sources sum to 1.5: the master limiter holds them at its -1 dBFS ceiling.
    CHECK(core.setSnapshot(snap30({clipDesc(1, 0, 30), clipDesc(2, 0, 30)})) == Status::Ok);
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    for (int i = 0; i < 20; ++i) step(core, &out);
    bool atCeiling = true;
    for (float v : out) atCeiling = atCeiling && std::fabs(v - 0.8912509f) < 1e-4f;
    CHECK(atCeiling);
    core.streamStopped();

    // With the limiter off the old behaviour remains: a hard clamp to [-1, 1].
    AudioCore plain(fakeFactory());
    plain.configure(48000);
    plain.streamStarting();
    AudioSnapshotData off = snap30({clipDesc(1, 0, 30), clipDesc(2, 0, 30)});
    off.limiterOff = true;
    CHECK(plain.setSnapshot(off) == Status::Ok);
    plain.play();
    std::vector<float> out2;
    CHECK(renderUntilPlaying(plain, &out2));
    for (int i = 0; i < 20; ++i) step(plain, &out2);
    bool allOne = true;
    for (float v : out2) allOne = allOne && v == 1.0f;
    CHECK(allOne);
    plain.streamStopped();
}

// Outgoing clip fades out over its last 20 frames while the incoming one fades in over its first
// 20 (the same samples): equal-power gains, untouched outside the overlap.
static void testCrossfadeGains() {
    g_spec = FakeSpec{};
    g_spec.constant = 0.5f;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    AudioClipDesc a = clipDesc(1, 0, 60);
    a.fadeOutFrames = 20;
    AudioClipDesc b = clipDesc(2, 40, 60);
    b.fadeInFrames = 20;
    CHECK(core.setSnapshot(snap30({a, b})) == Status::Ok);
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    while (out.size() / 2 < 160000) step(core, &out);

    const int64_t regionStart = 40 * 1600;
    const int64_t regionLen = 20 * 1600;
    int bad = 0;
    for (int64_t k = 0; k < 160000; ++k) {
        float expect = 0.5f;
        if (k >= regionStart && k < regionStart + regionLen) {
            const int64_t i = k - regionStart;
            expect = 0.5f * (uv::core::crossfadeFadeOutGain(i, regionLen) + uv::core::crossfadeFadeInGain(i, regionLen));
        }
        if (std::fabs(out[2 * k] - expect) > 1e-4f) ++bad;
    }
    CHECK(bad == 0);
    // Power is preserved for uncorrelated material: gains squared sum to one everywhere in the fade.
    for (int64_t i = 0; i < regionLen; i += 997) {
        const float go = uv::core::crossfadeFadeOutGain(i, regionLen);
        const float gi = uv::core::crossfadeFadeInGain(i, regionLen);
        CHECK_NEAR(go * go + gi * gi, 1.0, 1e-5);
    }
    CHECK(core.underrunBlocks() == 0);
    core.streamStopped();
}

static void testSilenceOutsideClipsAndEof() {
    g_spec = FakeSpec{};
    g_spec.totalFrames = 24000;  // media is only half a second but the clip claims 1 s
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    CHECK(core.setSnapshot(snap30({clipDesc(1, 15, 30)})) == Status::Ok);  // starts at 0.5 s
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    while (out.size() / 2 < 96000) step(core, &out);

    bool gapSilent = true, mediaOk = true, tailSilent = true;
    for (int64_t k = 0; k < 24000; ++k) gapSilent = gapSilent && out[2 * k] == 0.0f;
    for (int64_t k = 24000; k < 48000; ++k) mediaOk = mediaOk && std::fabs(out[2 * k] - valueAt(k - 24000)) < 1e-4f;
    for (int64_t k = 48000; k < 96000; ++k) tailSilent = tailSilent && out[2 * k] == 0.0f;
    CHECK(gapSilent && mediaOk && tailSilent);
    CHECK(core.underrunBlocks() == 0);  // running out of media is not an underrun
    core.streamStopped();
}

static void testResamplingPath() {
    g_spec = FakeSpec{};
    g_spec.rate = 44100;
    g_spec.constant = 0.25f;
    g_spec.totalFrames = 44100 * 10;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    CHECK(core.setSnapshot(snap30({clipDesc(1, 0, 60)})) == Status::Ok);  // 2 s clip
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    while (out.size() / 2 < 96000 + kBlock) step(core, &out);
    bool level = true;
    for (int64_t k = 1; k < 96000 - 2; ++k) level = level && std::fabs(out[2 * k] - 0.25f) < 1e-5f;
    CHECK(level);
    // The clip ends on its exact output-sample boundary.
    CHECK(out[2 * 95999] != 0.0f || out[2 * 95998] != 0.0f);
    bool endsClean = true;
    for (int64_t k = 96000; k < 96000 + kBlock; ++k) endsClean = endsClean && out[2 * k] == 0.0f;
    CHECK(endsClean);
    core.streamStopped();
}

static void testSeekPauseAndClock() {
    g_spec = FakeSpec{};
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    CHECK(core.setSnapshot(snap30({clipDesc(1, 0, 600, 0)})) == Status::Ok);  // 20 s clip

    // Paused seek prefetches, so play() starts without waiting long.
    core.seekSamples(48000 * 5);
    std::vector<float> out;
    core.serviceOnce();
    core.render(std::vector<float>(kBlock * 2).data(), kBlock);
    CHECK(!core.anchor().playing && core.anchor().timelineSample == 48000 * 5);
    core.play();
    CHECK(renderUntilPlaying(core, &out, 4));
    CHECK(std::fabs(out[0] - valueAt(48000 * 5)) < 1e-4f);
    CHECK(std::fabs(out[2 * 100] - valueAt(48000 * 5 + 100)) < 1e-4f);

    // Seek while playing: warm-up hold, then audio from the new position.
    core.seekSamples(48000 * 12 + 77);
    out.clear();
    CHECK(renderUntilPlaying(core, &out));
    CHECK(std::fabs(out[0] - valueAt(48000 * 12 + 77)) < 1e-4f);
    CHECK(core.anchor().timelineSample == 48000 * 12 + 77);

    // Master clock: audio rendered ahead of the speaker is not yet "heard".
    for (int i = 0; i < 50; ++i) step(core, &out);
    const ClockAnchor a = core.anchor();
    CHECK(a.playing);
    const int64_t rendered = core.streamFrames();
    const int64_t heardFrame = rendered - 2400;  // 50 ms of buffered output
    const int64_t heard = timelineSampleAt(a, heardFrame, rendered);
    CHECK(heard == core.renderPosSamples() - 2400);
    CHECK(core.samplesToTimelineFrames(heard) == heard / 1600);

    // Pause rewinds to the heard position; resuming continues from exactly there.
    core.pause(heard);
    std::vector<float> silent(kBlock * 2, 1.0f);
    core.render(silent.data(), kBlock);
    core.render(silent.data(), kBlock);
    CHECK(!core.anchor().playing && core.anchor().timelineSample == heard);
    bool allZero = true;
    for (float v : silent) allZero = allZero && v == 0.0f;
    CHECK(allZero);
    core.play();
    out.clear();
    CHECK(renderUntilPlaying(core, &out));
    CHECK(std::fabs(out[0] - valueAt(heard)) < 1e-4f);
    core.streamStopped();
}

static void testFailuresAreReported() {
    // Decoder cannot be opened: silence, one typed Decode fault, playback does not stall.
    g_spec = FakeSpec{};
    g_spec.openStatus = Status::CodecError;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    CHECK(core.setSnapshot(snap30({clipDesc(42, 0, 30)})) == Status::Ok);
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out, 10));  // failed clips never block the hold
    for (int i = 0; i < 10; ++i) step(core, &out);
    std::vector<AudioFault> faults;
    core.pollFaults(&faults);
    CHECK(faults.size() == 1);
    if (!faults.empty()) {
        CHECK(faults[0].kind == AudioFault::Kind::Decode && faults[0].clipKey == 42 &&
              faults[0].status == Status::CodecError);
    }
    CHECK(core.underrunBlocks() == 0);
    faults.clear();
    core.pollFaults(&faults);
    CHECK(faults.empty());  // reported once
    core.streamStopped();

    // Without a worker the buffers never fill: after the hold times out, underruns are counted
    // and reported as one aggregated fault.
    g_spec = FakeSpec{};
    AudioCore starved(fakeFactory());
    starved.configure(48000);
    starved.streamStarting();
    starved.setSnapshot(snap30({clipDesc(5, 0, 30)}));
    starved.play();
    std::vector<float> o2;
    for (int i = 0; i < 80; ++i) step(starved, &o2, /*service=*/false);
    CHECK(starved.underrunBlocks() > 0);
    faults.clear();
    starved.pollFaults(&faults);
    CHECK(faults.size() == 1 && faults[0].kind == AudioFault::Kind::Underrun && faults[0].clipKey == 5 &&
          faults[0].count == starved.underrunBlocks());
    starved.streamStopped();

    // A bad snapshot is rejected up front.
    AudioCore c3(fakeFactory());
    c3.configure(48000);
    CHECK(c3.setSnapshot(snap30({clipDesc(1, 0, 5), clipDesc(1, 5, 5)})) == Status::BadSnapshot);  // duplicate key
    CHECK(c3.configure(100) == Status::InvalidArgument);
}

static void testSnapshotEditsReuseDecoders() {
    g_spec = FakeSpec{};
    g_createdDecoders = 0;
    {
        AudioCore core(fakeFactory());
        core.configure(48000);
        core.streamStarting();
        core.setSnapshot(snap30({clipDesc(1, 0, 600)}));
        core.play();
        std::vector<float> out;
        CHECK(renderUntilPlaying(core, &out));
        for (int i = 0; i < 20; ++i) step(core, &out);
        CHECK(g_createdDecoders == 1);

        // Re-gain and trim the end: same source, so the decoder keeps running uninterrupted.
        core.setSnapshot(snap30({clipDesc(1, 0, 300, 0, -3.0f)}));
        for (int i = 0; i < 20; ++i) step(core, &out);
        CHECK(g_createdDecoders == 1);
        core.streamStopped();
    }
    CHECK(g_liveDecoders == 0);
}

static void testFarClipsHoldNoDecoder() {
    g_spec = FakeSpec{};
    g_createdDecoders = 0;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    core.setSnapshot(snap30({clipDesc(1, 3000, 30)}));  // 100 s away
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    for (int i = 0; i < 10; ++i) step(core, &out);
    CHECK(g_createdDecoders == 0 && g_liveDecoders == 0);

    core.seekSamples(framesToSamples(3000, Rational{30, 1}, 48000) - 24000);  // 0.5 s before the clip
    out.clear();
    CHECK(renderUntilPlaying(core, &out));
    for (int i = 0; i < 5; ++i) step(core, &out);  // the clip is 0.5 s ahead: inside the look-ahead window
    CHECK(g_createdDecoders == 1);
    core.seekSamples(0);  // jump far away again: the decoder and buffer are given back
    for (int i = 0; i < 10; ++i) step(core, &out);
    CHECK(g_liveDecoders == 0);
    core.streamStopped();
}

static void testThreadedWorker() {
    g_spec = FakeSpec{};
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    core.setSnapshot(snap30({clipDesc(1, 0, 90, 0)}));
    core.startWorker();
    core.play();

    // Pace the "audio thread" at ~10x real time while the worker decodes concurrently.
    std::vector<float> out;
    int64_t start = -1;
    for (int i = 0; i < 700 && out.size() / 2 < 3 * 48000; ++i) {
        std::vector<float> block(kBlock * 2);
        core.render(block.data(), kBlock);
        if (start < 0 && core.anchor().playing) start = core.renderPosSamples() - kBlock;
        if (start >= 0) out.insert(out.end(), block.begin(), block.end());
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    core.stopWorker();
    CHECK(start == 0);
    CHECK(out.size() / 2 >= 3 * 48000 - kBlock);
    int bad = 0;
    for (int64_t k = 0; k < static_cast<int64_t>(out.size() / 2) && k < 3 * 48000; ++k) {
        if (std::fabs(out[2 * k] - valueAt(k)) > 1e-4f) ++bad;
    }
    CHECK(bad == 0);
    CHECK(core.underrunBlocks() == 0);
    core.streamStopped();
}

static void testOfflineRenderWaitsForData() {
    g_spec = FakeSpec{};
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.setOfflineMode(true);
    core.streamStarting();
    core.setSnapshot(snap30({clipDesc(1, 0, 600, 0)}));
    core.startWorker();
    core.play();

    // Pull as fast as possible: no pacing, no hold. Every block must wait for its data instead of
    // underrunning, so the output is exact.
    std::vector<float> out(static_cast<size_t>(5 * 48000) * 2);
    for (int64_t done = 0; done < 5 * 48000; done += kBlock) core.render(out.data() + done * 2, kBlock);
    int bad = 0;
    for (int64_t k = 0; k < 5 * 48000; ++k) {
        if (std::fabs(out[2 * k] - valueAt(k)) > 1e-4f) ++bad;
    }
    CHECK(bad == 0);
    CHECK(core.underrunBlocks() == 0);

    // A seek mid-render is honoured immediately, again without underruns.
    core.seekSamples(48000 * 15 + 13);
    std::vector<float> after(static_cast<size_t>(kBlock) * 2);
    core.render(after.data(), kBlock);
    CHECK(std::fabs(after[0] - valueAt(48000 * 15 + 13)) < 1e-4f);
    CHECK(std::fabs(after[2 * 400] - valueAt(48000 * 15 + 13 + 400)) < 1e-4f);
    CHECK(core.renderPosSamples() == 48000 * 15 + 13 + kBlock);
    CHECK(core.underrunBlocks() == 0);
    core.stopWorker();
    core.streamStopped();
}

// A clip whose decoder hiccups (a reclaimed codec, a failed seek) is retried by an offline render,
// which waits for it: the output stays exact, with no silent hole and no fault.
static void testOfflineRenderSurvivesTransientDecodeFailures() {
    g_spec = FakeSpec{};
    g_readsToFail = 2;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.setOfflineMode(true);
    core.streamStarting();
    core.setSnapshot(snap30({clipDesc(1, 0, 600, 0)}));
    core.startWorker();
    core.play();
    std::vector<float> out(static_cast<size_t>(3 * 48000) * 2);
    for (int64_t done = 0; done < 3 * 48000; done += kBlock) core.render(out.data() + done * 2, kBlock);
    int bad = 0;
    for (int64_t k = 0; k < 3 * 48000; ++k) {
        if (std::fabs(out[2 * k] - valueAt(k)) > 1e-4f) ++bad;
    }
    CHECK(bad == 0);
    CHECK(core.underrunBlocks() == 0);
    std::vector<AudioFault> faults;
    core.pollFaults(&faults);
    CHECK(faults.empty());
    core.stopWorker();
    core.streamStopped();
    g_readsToFail = 0;
}

// A clip that never decodes is reported once its retries run out, as a typed Decode fault, instead of
// rendering silence in its place.
static void testOfflineRenderReportsAPermanentlyFailingClip() {
    g_spec = FakeSpec{};
    g_readsToFail = 1000000;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.setOfflineMode(true);
    core.streamStarting();
    core.setSnapshot(snap30({clipDesc(7, 0, 600, 0)}));
    core.startWorker();
    core.play();
    std::vector<float> out(static_cast<size_t>(kBlock) * 2);
    core.render(out.data(), kBlock);
    std::vector<AudioFault> faults;
    core.pollFaults(&faults);
    CHECK(faults.size() == 1 && faults[0].kind == AudioFault::Kind::Decode && faults[0].clipKey == 7 &&
          faults[0].status == Status::CodecError);
    core.stopWorker();
    core.streamStopped();
    g_readsToFail = 0;
}

// ------------------------------------------------------------------ retimed clips

static std::vector<RetimeKnot> knotsOf(std::initializer_list<std::pair<int64_t, double>> list) {
    std::vector<RetimeKnot> knots;
    for (const auto& [frame, source] : list) knots.push_back(RetimeKnot{frame, source});
    return knots;
}

// Source sample q read the way the reader does: linear interpolation of the fake decoder's ramp.
static float interpolatedValue(double q) {
    if (q < 0) return 0.0f;
    const int64_t i0 = static_cast<int64_t>(std::floor(q));
    const float frac = static_cast<float>(q - static_cast<double>(i0));
    const float a = valueAt(i0);
    const float b = valueAt(i0 + 1);
    return a + (b - a) * frac;
}

static void testRetimeSnapshotParsing() {
    AudioSnapshotData out;
    AudioClipDesc fast = clipDesc(1, 0, 30);
    fast.knots = knotsOf({{0, 0.0}, {30, 60.0}});
    AudioClipDesc plain = clipDesc(2, 40, 10);
    AudioClipDesc ramped = clipDesc(3, 60, 20);
    ramped.knots = knotsOf({{0, 100.0}, {10, 80.5}, {20, 60.0}});  // reversed, with a middle knot
    Buf ok = makeAudioSnapshot(30, 1, {fast, plain, ramped});
    CHECK(parseAudioSnapshot(ok.b.data(), ok.b.size(), &out) == Status::Ok);
    CHECK(out.clips.size() == 3);
    CHECK(out.clips[0].knots.size() == 2 && out.clips[1].knots.empty() && out.clips[2].knots.size() == 3);
    CHECK(out.clips[0].knots[1].frame == 30 && out.clips[0].knots[1].sourceFrame == 60.0);
    CHECK(out.clips[2].knots[1].sourceFrame == 80.5);

    const auto bad = [&](std::vector<RetimeKnot> knots) {
        AudioClipDesc c = clipDesc(1, 0, 30);
        c.knots = std::move(knots);
        Buf b = makeAudioSnapshot(30, 1, {c});
        return parseAudioSnapshot(b.b.data(), b.b.size(), &out) == Status::BadSnapshot;
    };
    CHECK(bad(knotsOf({{0, 0.0}})));                                   // one knot is not a mapping
    CHECK(bad(knotsOf({{1, 0.0}, {30, 60.0}})));                       // must start at the clip's start
    CHECK(bad(knotsOf({{0, 0.0}, {20, 60.0}})));                       // must end at the clip's end
    CHECK(bad(knotsOf({{0, 0.0}, {15, 5.0}, {15, 6.0}, {30, 9.0}})));  // strictly increasing
    CHECK(bad(knotsOf({{0, 0.0}, {40, 9.0}})));                        // past the clip
    CHECK(bad(knotsOf({{0, 0.0}, {30, std::nan("")}})));               // finite positions only
    Buf truncated = ok;
    truncated.b.resize(truncated.b.size() - 16);
    CHECK(parseAudioSnapshot(truncated.b.data(), truncated.b.size(), &out) == Status::BadSnapshot);
    Buf extra = ok;
    for (int i = 0; i < 16; ++i) extra.b.push_back(0);  // a knot nobody asked for
    CHECK(parseAudioSnapshot(extra.b.data(), extra.b.size(), &out) == Status::BadSnapshot);
}

static void testRetimeMap() {
    const Rational fps{30, 1};
    // 2x: 30 frames of clip play 60 frames of source; at equal rates sample j reads source 2j.
    RetimeMap fast(knotsOf({{0, 0.0}, {30, 60.0}}), fps, 48000, 48000);
    CHECK_NEAR(fast.sourceSample(0), 0.0, 1e-9);
    CHECK_NEAR(fast.sourceSample(1000), 2000.0, 1e-6);
    CHECK_NEAR(fast.sourceSample(48000), 96000.0, 1e-6);
    CHECK_NEAR(fast.sourceSample(60000), 120000.0, 1e-6);  // past the last knot the speed continues
    CHECK_NEAR(fast.sourceSample(-100), -200.0, 1e-6);
    CHECK(!fast.reversed());
    // A source at 44.1 kHz read onto a 48 kHz output at 1x.
    RetimeMap resampled(knotsOf({{0, 0.0}, {30, 30.0}}), fps, 48000, 44100);
    CHECK_NEAR(resampled.sourceSample(48000), 44100.0, 1e-6);
    // Reversed: positions fall.
    RetimeMap back(knotsOf({{0, 100.0}, {30, 70.0}}), fps, 48000, 48000);
    CHECK(back.reversed());
    CHECK_NEAR(back.sourceSample(0), 100.0 * 1600, 1e-6);
    CHECK_NEAR(back.sourceSample(48000), 70.0 * 1600, 1e-6);
    // A middle knot changes the speed: 1x for 10 frames then 4x.
    RetimeMap ramp(knotsOf({{0, 0.0}, {10, 10.0}, {20, 50.0}}), fps, 48000, 48000);
    CHECK_NEAR(ramp.sourceSample(16000), 16000.0, 1e-6);
    CHECK_NEAR(ramp.sourceSample(24000), 16000.0 + 8000.0 * 4, 1e-6);
}

static void renderAll(RetimedReader& reader, int64_t total, int32_t block, std::vector<float>* out) {
    out->resize(static_cast<size_t>(total) * 2);
    for (int64_t at = 0; at < total; at += block) {
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(block, total - at));
        CHECK(reader.render(at, n, out->data() + at * 2) == RetimedReader::Result::Ok);
    }
}

static int countWrong(const RetimeMap& map, const std::vector<float>& out, int64_t total) {
    int wrong = 0;
    for (int64_t j = 0; j < total; ++j) {
        const float expect = interpolatedValue(map.sourceSample(j));
        if (std::fabs(out[2 * j] - expect) > 1e-3f || std::fabs(out[2 * j + 1] - expect) > 1e-3f) ++wrong;
    }
    return wrong;
}

static void testRetimedReaderForward() {
    g_spec = FakeSpec{};
    g_seeks = 0;
    FakeDecoder decoder(g_spec);
    const RetimeMap map(knotsOf({{0, 0.0}, {30, 60.0}}), Rational{30, 1}, 48000, 48000);  // 2x
    RetimedReader reader(&decoder, map);
    std::vector<float> out;
    renderAll(reader, 48000, 1024, &out);
    CHECK(countWrong(map, out, 48000) == 0);
    CHECK(g_seeks == 1);  // streamed: one positioning seek, none while it played through
    // Half speed reads each source sample twice over (linear interpolation in between).
    g_seeks = 0;
    FakeDecoder slowDecoder(g_spec);
    const RetimeMap slowMap(knotsOf({{0, 0.0}, {60, 30.0}}), Rational{30, 1}, 48000, 48000);
    RetimedReader slow(&slowDecoder, slowMap);
    renderAll(slow, 60000, 777, &out);
    CHECK(countWrong(slowMap, out, 60000) == 0);
    CHECK(g_seeks == 1);
}

static void testRetimedReaderReverse() {
    g_spec = FakeSpec{};
    g_seeks = 0;
    FakeDecoder decoder(g_spec);
    // 1 s of output reading source frames 60 down to 0 at 2x backwards (2 s of source).
    const RetimeMap map(knotsOf({{0, 60.0}, {30, 0.0}}), Rational{30, 1}, 48000, 48000);
    RetimedReader reader(&decoder, map);
    std::vector<float> out;
    renderAll(reader, 48000, 1024, &out);
    CHECK(countWrong(map, out, 48000) == 0);
    // The first read decodes a block below the start (96000 source samples): a single seek serves all of it.
    CHECK(g_seeks <= 2);
    // Longer than a block: seeks once per block, not once per read.
    g_seeks = 0;
    FakeDecoder longDecoder(g_spec);
    const RetimeMap longMap(knotsOf({{0, 300.0}, {150, 0.0}}), Rational{30, 1}, 48000, 48000);  // 5 s output, 10 s source
    RetimedReader longReader(&longDecoder, longMap);
    renderAll(longReader, 5 * 48000, 1024, &out);
    CHECK(countWrong(longMap, out, 5 * 48000) == 0);
    CHECK(g_seeks >= 2 && g_seeks <= 8);
}

static void testRetimedReaderRampAndEdges() {
    g_spec = FakeSpec{};
    FakeDecoder decoder(g_spec);
    const RetimeMap ramp(knotsOf({{0, 0.0}, {10, 10.0}, {20, 50.0}}), Rational{30, 1}, 48000, 48000);
    RetimedReader reader(&decoder, ramp);
    std::vector<float> out;
    renderAll(reader, 32000, 1000, &out);
    CHECK(countWrong(ramp, out, 32000) == 0);

    // Media shorter than the clip wants: the rest is silence, not garbage.
    g_spec.totalFrames = 10000;
    FakeDecoder shortDecoder(g_spec);
    const RetimeMap fast(knotsOf({{0, 0.0}, {30, 60.0}}), Rational{30, 1}, 48000, 48000);
    RetimedReader shortReader(&shortDecoder, fast);
    renderAll(shortReader, 12000, 1024, &out);  // source positions 0..24000, media ends at 10000
    for (int64_t j = 6000; j < 12000; ++j) CHECK(out[2 * j] == 0.0f);
    CHECK(std::fabs(out[2 * 1000] - valueAt(2000)) < 1e-3f);

    // Before the start of the source (a transition's lead-in reaching below zero) is silence too.
    g_spec = FakeSpec{};
    FakeDecoder startDecoder(g_spec);
    const RetimeMap early(knotsOf({{0, -2.0}, {30, 28.0}}), Rational{30, 1}, 48000, 48000);  // starts 2 frames before the media
    RetimedReader startReader(&startDecoder, early);
    renderAll(startReader, 8000, 1024, &out);
    CHECK(out[0] == 0.0f && out[2 * 3000] == 0.0f);
    CHECK(std::fabs(out[2 * 4000] - interpolatedValue(early.sourceSample(4000))) < 1e-3f);
}

static void testRetimedReaderReportsDecoderFailures() {
    g_spec = FakeSpec{};
    class Failing : public PcmDecoder {
    public:
        int32_t sampleRate() const override { return 48000; }
        Status seekToMicros(int64_t) override { return Status::IoError; }
        PcmReadResult read(float*, int32_t) override { return {}; }
    } failing;
    RetimedReader reader(&failing, RetimeMap(knotsOf({{0, 0.0}, {30, 30.0}}), Rational{30, 1}, 48000, 48000));
    std::vector<float> out(2048);
    CHECK(reader.render(0, 1024, out.data()) == RetimedReader::Result::Error);
    CHECK(reader.lastStatus() == Status::IoError);
    // A decoder with nothing ready yet is "not ready", not an error.
    class Slow : public PcmDecoder {
    public:
        int32_t sampleRate() const override { return 48000; }
        Status seekToMicros(int64_t) override { return Status::Ok; }
        PcmReadResult read(float*, int32_t) override { return {}; }
    } slow;
    RetimedReader waiting(&slow, RetimeMap(knotsOf({{0, 0.0}, {30, 30.0}}), Rational{30, 1}, 48000, 48000));
    CHECK(waiting.render(0, 1024, out.data()) == RetimedReader::Result::NotReady);
}

// A 2x clip and a reversed clip through the whole core, as the editor sends them.
static void testRetimedClipsPlayThroughTheCore() {
    g_spec = FakeSpec{};
    AudioCore core(fakeFactory());
    CHECK(core.configure(48000) == Status::Ok);
    core.streamStarting();
    AudioClipDesc fast = clipDesc(1, 0, 30);
    fast.knots = knotsOf({{0, 0.0}, {30, 60.0}});  // 1 s of output, source seconds 0..2
    AudioClipDesc back = clipDesc(2, 30, 30);
    back.knots = knotsOf({{0, 90.0}, {30, 60.0}});  // 1 s of output, source seconds 3 down to 2
    CHECK(core.setSnapshot(snap30({fast, back})) == Status::Ok);
    core.play();

    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    while (out.size() / 2 < 96000) step(core, &out);
    const RetimeMap fastMap(fast.knots, Rational{30, 1}, 48000, 48000);
    const RetimeMap backMap(back.knots, Rational{30, 1}, 48000, 48000);
    int bad = 0;
    for (int64_t k = 0; k < 96000; ++k) {
        const float expect = k < 48000 ? interpolatedValue(fastMap.sourceSample(k)) : interpolatedValue(backMap.sourceSample(k - 48000));
        if (std::fabs(out[2 * k] - expect) > 1e-3f) ++bad;
    }
    CHECK(bad == 0);
    CHECK(core.underrunBlocks() == 0);
    std::vector<AudioFault> faults;
    core.pollFaults(&faults);
    CHECK(faults.empty());
    core.streamStopped();
}

// Changing only a clip's retime makes a new source: the old buffer is not reused for the new mapping.
static void testRetimeChangeStartsANewSource() {
    g_spec = FakeSpec{};
    g_createdDecoders = 0;
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.streamStarting();
    AudioClipDesc a = clipDesc(1, 0, 30);
    a.knots = knotsOf({{0, 0.0}, {30, 60.0}});
    core.setSnapshot(snap30({a}));
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    for (int i = 0; i < 20; ++i) step(core, &out);
    CHECK(g_createdDecoders == 1);
    // Same retime, clip moved: the decoder survives (the buffer is clip local).
    AudioClipDesc moved = a;
    moved.startFrame = 3;
    core.setSnapshot(snap30({moved}));
    for (int i = 0; i < 5; ++i) step(core, &out);
    CHECK(g_createdDecoders == 1);
    // A different speed: a new source and decoder.
    AudioClipDesc slower = a;
    slower.knots = knotsOf({{0, 0.0}, {30, 45.0}});
    core.setSnapshot(snap30({slower}));
    for (int i = 0; i < 5; ++i) step(core, &out);
    CHECK(g_createdDecoders == 2);
    core.streamStopped();
}


// ------------------------------------------------------------------ audio tools (pan, EQ, fades, tracks, ducking, denoise)

static AudioClipDesc toolClip(int64_t key, int64_t start, int64_t dur, int32_t track = 0) {
    AudioClipDesc c = clipDesc(key, start, dur);
    c.trackIndex = track;
    return c;
}

static AudioTrackDesc trackDesc(int64_t key, float gainDb = 0.0f, bool muted = false, TrackRole role = TrackRole::Normal) {
    AudioTrackDesc t;
    t.trackKey = key;
    t.gainDb = gainDb;
    t.muted = muted;
    t.role = role;
    return t;
}

// Plays `data` from the start (deterministic worker, 480-frame blocks) and returns `seconds` of audio.
static std::vector<float> playFor(const AudioSnapshotData& data, double seconds, AudioCore** keep = nullptr) {
    static AudioCore* last = nullptr;
    delete last;
    last = new AudioCore(fakeFactory());
    AudioCore& core = *last;
    core.configure(48000);
    core.streamStarting();
    CHECK(core.setSnapshot(data) == Status::Ok);
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    while (static_cast<double>(out.size() / 2) < seconds * 48000) step(core, &out);
    if (keep != nullptr) *keep = last;
    return out;
}

static double meanOf(const std::vector<float>& v, size_t chan, int64_t from, int64_t to) {
    double s = 0;
    for (int64_t i = from; i < to; ++i) s += v[2 * static_cast<size_t>(i) + chan];
    return s / static_cast<double>(to - from);
}

static void testSnapshotV4Parsing() {
    AudioSnapshotData out;
    SnapExtras extra;
    extra.tracks = {trackDesc(10, -3.0f), trackDesc(11, 0.0f, true, TrackRole::Voice), trackDesc(12, 0.0f, false, TrackRole::Music)};
    extra.tracks[0].comp.enabled = true;
    extra.tracks[0].comp.thresholdDb = -24.0f;
    extra.tracks[0].comp.ratio = 4.0f;
    extra.ducking.amountDb = 9.0f;
    extra.limiterOff = true;
    AudioClipDesc c = toolClip(1, 0, 30, 2);
    c.pan = -0.25f;
    c.userFadeInFrames = 5;
    c.userFadeOutFrames = 30;
    c.eq.highPassHz = 80.0f;
    c.eq.bands[2].gainDb = 4.5f;
    c.eq.bands[2].freqHz = 3000.0f;
    c.denoiseStrength = 0.6f;
    c.noiseProfile.assign(kDenoiseBins, 0.01f);
    AudioClipDesc plain = toolClip(2, 40, 10, 1);
    plain.knots = knotsOf({{0, 0.0}, {10, 20.0}});
    Buf ok = makeAudioSnapshot(30, 1, {c, plain}, extra);
    CHECK(parseAudioSnapshot(ok.b.data(), ok.b.size(), &out) == Status::Ok);
    CHECK(out.tracks.size() == 3 && out.tracks[0].gainDb == -3.0f && out.tracks[0].comp.enabled && out.tracks[0].comp.ratio == 4.0f);
    CHECK(out.tracks[1].muted && out.tracks[1].role == TrackRole::Voice && out.tracks[2].role == TrackRole::Music);
    CHECK(out.ducking.amountDb == 9.0f && out.limiterOff);
    CHECK(out.clips.size() == 2 && out.clips[0].trackIndex == 2 && out.clips[0].pan == -0.25f);
    CHECK(out.clips[0].userFadeInFrames == 5 && out.clips[0].userFadeOutFrames == 30);
    CHECK(out.clips[0].eq.highPassHz == 80.0f && out.clips[0].eq.bands[2].gainDb == 4.5f && out.clips[0].eq.bands[2].freqHz == 3000.0f);
    CHECK(out.clips[0].denoiseStrength == 0.6f && out.clips[0].noiseProfile.size() == static_cast<size_t>(kDenoiseBins));
    CHECK(out.clips[1].denoiseStrength == 0.0f && out.clips[1].noiseProfile.empty() && out.clips[1].knots.size() == 2);

    // No tracks listed: one default track appears.
    Buf bare = makeAudioSnapshot(30, 1, {toolClip(1, 0, 5)});
    CHECK(parseAudioSnapshot(bare.b.data(), bare.b.size(), &out) == Status::Ok && out.tracks.size() == 1);

    const auto bad = [&](auto mutate) {
        SnapExtras e = extra;
        AudioClipDesc k = c;
        mutate(e, k);
        Buf b = makeAudioSnapshot(30, 1, {k}, e);
        return parseAudioSnapshot(b.b.data(), b.b.size(), &out) == Status::BadSnapshot;
    };
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.trackIndex = 3; }));  // no such track
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.trackIndex = -1; }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.pan = 1.5f; }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.pan = std::nanf(""); }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.userFadeInFrames = 31; }));  // longer than the clip
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.eq.highPassHz = 5.0f; }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.eq.bands[0].freqHz = 30000.0f; }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.eq.bands[1].gainDb = 30.0f; }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.eq.bands[1].q = 0.0f; }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.denoiseStrength = 1.5f; }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.noiseProfile.clear(); }));    // strength without a profile
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.denoiseStrength = 0.0f; }));  // profile without strength
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) {
        k.noiseProfile.assign(100, 0.1f);
        k.denoiseStrength = 0.5f;
    }));
    CHECK(bad([](SnapExtras&, AudioClipDesc& k) { k.noiseProfile[7] = -1.0f; }));
    CHECK(bad([](SnapExtras& e, AudioClipDesc&) { e.tracks[0].comp.ratio = 0.5f; }));
    CHECK(bad([](SnapExtras& e, AudioClipDesc&) { e.tracks[0].gainDb = 60.0f; }));
    CHECK(bad([](SnapExtras& e, AudioClipDesc&) { e.ducking.amountDb = 60.0f; }));
    CHECK(bad([](SnapExtras& e, AudioClipDesc&) { e.ducking.attackMs = 0.0f; }));
    Buf role = ok;
    role.b[kAudioSnapshotHeaderBytes + 16] = 3;  // a role that does not exist
    CHECK(parseAudioSnapshot(role.b.data(), role.b.size(), &out) == Status::BadSnapshot);
    Buf truncated = ok;
    truncated.b.resize(truncated.b.size() - 4);  // a profile cut short
    CHECK(parseAudioSnapshot(truncated.b.data(), truncated.b.size(), &out) == Status::BadSnapshot);
}

static void testPanFadesAndGainInTheMixer() {
    g_spec = FakeSpec{};
    g_spec.constant = 0.5f;
    // Hard right: the left channel is silent, the right untouched.
    AudioClipDesc right = toolClip(1, 0, 60);
    right.pan = 1.0f;
    std::vector<float> out = playFor(snap30({right}), 1.0);
    CHECK_NEAR(meanOf(out, 0, 24000, 48000), 0.0, 1e-5);
    CHECK_NEAR(meanOf(out, 1, 24000, 48000), 0.5, 1e-5);
    // Centre: both channels equal.
    out = playFor(snap30({toolClip(1, 0, 60)}), 1.0);
    CHECK_NEAR(meanOf(out, 0, 24000, 48000), 0.5, 1e-5);
    CHECK_NEAR(meanOf(out, 1, 24000, 48000), 0.5, 1e-5);

    // The clip's own fades: equal-power ramps over the first 10 and last 10 frames of a 30-frame clip.
    AudioClipDesc faded = toolClip(1, 0, 30);
    faded.userFadeInFrames = 10;
    faded.userFadeOutFrames = 10;
    out = playFor(snap30({faded}), 1.0);
    const int64_t len = 30 * 1600, fade = 10 * 1600;
    CHECK_NEAR(out[0], 0.0, 1e-4);  // starts from (almost) silence
    for (int64_t k : {int64_t{1000}, int64_t{8000}, int64_t{15000}}) {
        CHECK_NEAR(out[2 * k], 0.5 * uv::core::crossfadeFadeInGain(k, fade), 1e-4);
    }
    CHECK_NEAR(out[2 * 24000], 0.5, 1e-5);  // flat in the middle
    for (int64_t k : {len - 12000, len - 5000, len - 100}) {
        CHECK_NEAR(out[2 * k], 0.5 * uv::core::crossfadeFadeOutGain(k - (len - fade), fade), 1e-4);
    }
    CHECK(out[2 * (len - 1)] < 0.01f);
    // Both fades together on a clip shorter than their sum never exceed the level.
    AudioClipDesc tiny = toolClip(1, 0, 10);
    tiny.userFadeInFrames = 10;
    tiny.userFadeOutFrames = 10;
    out = playFor(snap30({tiny}), 0.4);
    for (size_t i = 0; i < 10 * 1600; ++i) CHECK(out[2 * i] <= 0.5f + 1e-5f);

    // Clip gain on top of pan and fades (-6.0206 dB halves it).
    AudioClipDesc quiet = toolClip(1, 0, 60);
    quiet.gainDb = -6.0206f;
    out = playFor(snap30({quiet}), 1.0);
    CHECK_NEAR(meanOf(out, 0, 24000, 48000), 0.25, 1e-4);
    g_spec = FakeSpec{};
}

static void testEqInTheMixer() {
    g_spec = FakeSpec{};
    g_spec.sineHz = 1000.0;
    g_spec.sineAmp = 0.1f;
    AudioClipDesc c = toolClip(1, 0, 60);
    c.eq.bands[1].freqHz = 1000.0f;
    c.eq.bands[1].gainDb = 12.0f;
    c.eq.bands[1].q = 1.0f;
    std::vector<float> out = playFor(snap30({c}), 1.0);
    double peak = 0;
    for (size_t i = 24000; i < 48000; ++i) peak = std::max(peak, static_cast<double>(std::fabs(out[2 * i])));
    CHECK_NEAR(peak, 0.1 * std::pow(10.0, 12.0 / 20.0), 0.01);  // +12 dB at the band centre

    // A 4 kHz high-pass removes a 100 Hz tone almost entirely.
    g_spec.sineHz = 100.0;
    AudioClipDesc hp = toolClip(1, 0, 60);
    hp.eq.highPassHz = 4000.0f;
    out = playFor(snap30({hp}), 1.0);
    double low = 0;
    for (size_t i = 24000; i < 48000; ++i) low = std::max(low, static_cast<double>(std::fabs(out[2 * i])));
    CHECK(low < 0.005);
    g_spec = FakeSpec{};
}

static void testTrackVolumeMuteAndCompressor() {
    g_spec = FakeSpec{};
    g_spec.constant = 0.2f;
    AudioSnapshotData d = snap30({toolClip(1, 0, 60, 0), toolClip(2, 0, 60, 1)});
    d.tracks = {trackDesc(10, -6.0206f), trackDesc(11, 0.0f, true)};
    std::vector<float> out = playFor(d, 1.0);
    CHECK_NEAR(meanOf(out, 0, 24000, 48000), 0.1, 1e-4);  // track 0 at -6 dB, track 1 muted
    CHECK_NEAR(out[0], 0.1, 0.02);                         // the volume is already there at the start (no ramp from 0)

    // A bus compressor on a constant 0.5 signal: -6 dBFS, threshold -20 dB, 4:1 -> 10.5 dB of reduction.
    g_spec.constant = 0.5f;
    AudioSnapshotData comp = snap30({toolClip(1, 0, 60, 0)});
    AudioTrackDesc t = trackDesc(10);
    t.comp.enabled = true;
    t.comp.thresholdDb = -20.0f;
    t.comp.ratio = 4.0f;
    t.comp.attackMs = 5.0f;
    t.comp.releaseMs = 50.0f;
    comp.tracks = {t};
    out = playFor(comp, 1.5);
    CHECK_NEAR(meanOf(out, 0, 48000, 72000), 0.5 * std::pow(10.0, -10.5 / 20.0), 0.003);
    g_spec = FakeSpec{};
}

static void testDuckingInTheMixer() {
    g_spec = FakeSpec{};
    // Asset 1 is the "music" (a DC level) and asset 2 the "voice" (a 300 Hz tone): the mean of the
    // output is the music level, whatever the voice does.
    g_spec.constant = 0.3f;
    g_spec.voiceAsset = 2;
    g_spec.voiceHz = 300.0;
    g_spec.voiceAmp = 0.3f;
    AudioClipDesc music = toolClip(1, 0, 150, 1);
    music.assetKey = 1;
    AudioClipDesc voice = toolClip(2, 30, 60, 0);  // speaks from 1 s to 3 s
    voice.assetKey = 2;
    AudioSnapshotData d = snap30({music, voice});
    d.tracks = {trackDesc(20, 0.0f, false, TrackRole::Voice), trackDesc(21, 0.0f, false, TrackRole::Music)};
    d.ducking.amountDb = 12.0f;
    d.ducking.thresholdDb = -35.0f;
    d.ducking.attackMs = 20.0f;
    d.ducking.releaseMs = 300.0f;
    std::vector<float> out = playFor(d, 5.0);
    CHECK_NEAR(meanOf(out, 0, 0, 24000), 0.3, 0.002);                                              // before anyone speaks
    CHECK_NEAR(meanOf(out, 0, 2 * 48000, 3 * 48000), 0.3 * std::pow(10.0, -12.0 / 20.0), 0.003);  // ducked
    CHECK_NEAR(meanOf(out, 0, 4 * 48000 + 24000, 5 * 48000), 0.3, 0.003);                          // recovered
    // An amount of zero leaves the music alone.
    d.ducking.amountDb = 0.0f;
    out = playFor(d, 5.0);
    CHECK_NEAR(meanOf(out, 0, 2 * 48000, 3 * 48000), 0.3, 0.003);
    g_spec = FakeSpec{};
}

// The same project rendered offline with different block sizes gives the same samples: all the DSP is per sample.
static std::vector<float> renderOffline(const AudioSnapshotData& data, int64_t frames, int block) {
    AudioCore core(fakeFactory());
    core.configure(48000);
    core.setOfflineMode(true);
    core.streamStarting();
    CHECK(core.setSnapshot(data) == Status::Ok);
    core.startWorker();
    core.play();
    std::vector<float> out(static_cast<size_t>(frames) * 2);
    for (int64_t done = 0; done < frames;) {
        const int n = static_cast<int>(std::min<int64_t>(block, frames - done));
        core.render(out.data() + done * 2, n);
        done += n;
    }
    core.stopWorker();
    core.streamStopped();
    return out;
}

static void testOfflineMatchesRealtimeAndBlockSizes() {
    g_spec = FakeSpec{};
    g_spec.sineHz = 440.0;
    g_spec.sineAmp = 0.4f;
    g_spec.voiceAsset = 2;
    g_spec.voiceHz = 300.0;
    g_spec.voiceAmp = 0.3f;
    AudioClipDesc a = toolClip(1, 0, 90, 1);
    a.assetKey = 1;
    a.pan = 0.3f;
    a.eq.highPassHz = 120.0f;
    a.eq.bands[3].gainDb = -5.0f;
    a.eq.bands[3].freqHz = 2500.0f;
    a.userFadeInFrames = 8;
    AudioClipDesc b = toolClip(2, 20, 50, 0);
    b.assetKey = 2;
    b.userFadeOutFrames = 12;
    AudioSnapshotData d = snap30({a, b});
    AudioTrackDesc voice = trackDesc(20, -2.0f, false, TrackRole::Voice);
    voice.comp.enabled = true;
    voice.comp.thresholdDb = -22.0f;
    voice.comp.ratio = 3.0f;
    voice.comp.attackMs = 4.0f;
    voice.comp.releaseMs = 90.0f;
    d.tracks = {voice, trackDesc(21, 0.0f, false, TrackRole::Music)};
    d.ducking.amountDb = 8.0f;
    const int64_t frames = 3 * 48000;
    const std::vector<float> ref = renderOffline(d, frames, 480);
    for (int block : {64, 1000, 4096, 17}) {
        const std::vector<float> other = renderOffline(d, frames, block);
        double diff = 0;
        for (size_t i = 0; i < ref.size(); ++i) diff = std::max(diff, static_cast<double>(std::fabs(ref[i] - other[i])));
        CHECK(diff < 1e-5);
    }
    // The realtime path (deterministic worker, hold then play) renders the same samples.
    const std::vector<float> live = playFor(d, 3.0);
    double diff = 0;
    for (int64_t i = 0; i < frames * 2 && i < static_cast<int64_t>(live.size()); ++i) {
        diff = std::max(diff, static_cast<double>(std::fabs(ref[static_cast<size_t>(i)] - live[static_cast<size_t>(i)])));
    }
    CHECK(diff < 1e-5);
    // And the output is not silent.
    double peak = 0;
    for (float v : ref) peak = std::max(peak, static_cast<double>(std::fabs(v)));
    CHECK(peak > 0.1);
    g_spec = FakeSpec{};
}

// Re-sending an identical snapshot mid-playback (what any edit does) must not restart filters or envelopes.
static void testEditsKeepDspStateRunning() {
    g_spec = FakeSpec{};
    g_spec.sineHz = 700.0;
    g_spec.sineAmp = 0.3f;
    AudioClipDesc c = toolClip(1, 0, 150);
    c.eq.highPassHz = 300.0f;
    c.eq.bands[2].gainDb = 6.0f;
    c.eq.bands[2].freqHz = 700.0f;
    AudioSnapshotData d = snap30({c});
    AudioTrackDesc t = trackDesc(5);
    t.comp.enabled = true;
    t.comp.thresholdDb = -30.0f;
    t.comp.ratio = 4.0f;
    d.tracks = {t};

    auto run = [&](bool edit) {
        AudioCore core(fakeFactory());
        core.configure(48000);
        core.streamStarting();
        core.setSnapshot(d);
        core.play();
        std::vector<float> out;
        renderUntilPlaying(core, &out);
        for (int i = 0; i < 100; ++i) {
            if (edit && i == 40) core.setSnapshot(d);
            step(core, &out);
        }
        return out;
    };
    const std::vector<float> plain = run(false), edited = run(true);
    double diff = 0;
    for (size_t i = 0; i < plain.size(); ++i) diff = std::max(diff, static_cast<double>(std::fabs(plain[i] - edited[i])));
    CHECK(diff < 1e-6);
    g_spec = FakeSpec{};
}

// ------------------------------------------------------------------ keyframed parameters (automation lanes)

static AutoLane laneOf(AutoParam param, std::initializer_list<std::pair<int64_t, float>> points) {
    AutoLane lane;
    lane.param = param;
    for (const auto& p : points) lane.points.push_back(AutoPoint{p.first, p.second});
    return lane;
}

static void testAutomationSnapshotParsing() {
    AudioSnapshotData out;
    AudioClipDesc c = clipDesc(1, 0, 60);
    c.lanes = {laneOf(AutoParam::GainDb, {{0, 0.0f}, {30, -12.0f}}), laneOf(AutoParam::Pan, {{10, -1.0f}, {50, 1.0f}}),
               laneOf(AutoParam::EqGain2, {{0, 3.0f}})};
    Buf ok = makeAudioSnapshot(30, 1, {c, clipDesc(2, 70, 10)});
    CHECK(parseAudioSnapshot(ok.b.data(), ok.b.size(), &out) == Status::Ok);
    CHECK(out.clips.size() == 2);
    CHECK(out.clips[0].lanes.size() == 3);
    CHECK(out.clips[0].lanes[0].param == AutoParam::GainDb);
    CHECK(out.clips[0].lanes[0].points.size() == 2);
    CHECK_NEAR(out.clips[0].lanes[0].points[1].value, -12.0f, 0);
    CHECK(out.clips[0].lanes[1].points[0].frame == 10);
    CHECK(out.clips[0].lanes[2].param == AutoParam::EqGain2);
    CHECK(out.clips[1].lanes.empty());

    // Version 4 buffers (no lanes, reserved field 0) still parse.
    AudioClipDesc plain = clipDesc(3, 0, 20);
    Buf v4 = makeAudioSnapshot(30, 1, {plain});
    const uint32_t four = 4;
    std::memcpy(v4.b.data() + 4, &four, sizeof(four));
    CHECK(parseAudioSnapshot(v4.b.data(), v4.b.size(), &out) == Status::Ok);
    const uint32_t six = 6;
    std::memcpy(v4.b.data() + 4, &six, sizeof(six));
    CHECK(parseAudioSnapshot(v4.b.data(), v4.b.size(), &out) == Status::BadSnapshot);

    auto bad = [&](AudioClipDesc clip) {
        Buf b = makeAudioSnapshot(30, 1, {clip});
        return parseAudioSnapshot(b.b.data(), b.b.size(), &out) == Status::BadSnapshot;
    };
    AudioClipDesc dup = clipDesc(1, 0, 60);
    dup.lanes = {laneOf(AutoParam::Pan, {{0, 0.0f}}), laneOf(AutoParam::Pan, {{5, 0.5f}})};
    CHECK(bad(dup));  // one lane per parameter
    AudioClipDesc unordered = clipDesc(1, 0, 60);
    unordered.lanes = {laneOf(AutoParam::GainDb, {{10, 0.0f}, {10, 1.0f}})};
    CHECK(bad(unordered));
    AudioClipDesc outside = clipDesc(1, 0, 60);
    outside.lanes = {laneOf(AutoParam::GainDb, {{0, 0.0f}, {61, 1.0f}})};
    CHECK(bad(outside));
    AudioClipDesc loud = clipDesc(1, 0, 60);
    loud.lanes = {laneOf(AutoParam::GainDb, {{0, 99.0f}})};
    CHECK(bad(loud));
    AudioClipDesc panned = clipDesc(1, 0, 60);
    panned.lanes = {laneOf(AutoParam::Pan, {{0, 1.5f}})};
    CHECK(bad(panned));
    AudioClipDesc eq = clipDesc(1, 0, 60);
    eq.lanes = {laneOf(AutoParam::EqGain0, {{0, 30.0f}})};
    CHECK(bad(eq));
    AudioClipDesc nan = clipDesc(1, 0, 60);
    nan.lanes = {laneOf(AutoParam::GainDb, {{0, std::nanf("")}})};
    CHECK(bad(nan));

    // A truncated lane region and trailing bytes are both rejected.
    Buf whole = makeAudioSnapshot(30, 1, {c});
    CHECK(parseAudioSnapshot(whole.b.data(), whole.b.size() - 4, &out) == Status::BadSnapshot);
    Buf extra = whole;
    extra.put<uint32_t>(0);
    CHECK(parseAudioSnapshot(extra.b.data(), extra.b.size(), &out) == Status::BadSnapshot);
    // A lane count above the limit is rejected before anything is allocated.
    Buf many = makeAudioSnapshot(30, 1, {clipDesc(1, 0, 60)});
    const uint32_t huge = 1000;
    const size_t laneField = many.b.size() - 4;  // the clip record is the last block: its last u32 is the lane count
    std::memcpy(many.b.data() + laneField, &huge, sizeof(huge));
    CHECK(parseAudioSnapshot(many.b.data(), many.b.size(), &out) == Status::BadSnapshot);
}

static void testAutomationInTheMixer() {
    g_spec = FakeSpec{};
    g_spec.constant = 0.5f;
    const int64_t frame = 1600;  // samples per frame at 30 fps and 48 kHz

    // Volume: linear gain from 1.0 at frame 0 to 0.1 at frame 30 (-20 dB), held afterwards.
    AudioClipDesc g = toolClip(1, 0, 60);
    g.lanes = {laneOf(AutoParam::GainDb, {{0, 0.0f}, {30, -20.0f}})};
    std::vector<float> out = playFor(snap30({g}), 2.0);
    for (int64_t k : {int64_t{500}, int64_t{12000}, int64_t{24000}, int64_t{40000}}) {
        const double t = static_cast<double>(k) / (30.0 * static_cast<double>(frame));
        CHECK_NEAR(out[2 * static_cast<size_t>(k)], 0.5 * (1.0 + (0.1 - 1.0) * t), 2e-3);
    }
    CHECK_NEAR(out[2 * 60000], 0.05, 1e-4);  // held at the last point

    // Pan: hard left at frame 10, hard right at frame 50 (centre in the middle); before and after it holds.
    AudioClipDesc p = toolClip(1, 0, 60);
    p.lanes = {laneOf(AutoParam::Pan, {{10, -1.0f}, {50, 1.0f}})};
    out = playFor(snap30({p}), 2.0);
    CHECK_NEAR(out[2 * 1000 + 1], 0.0, 1e-3);          // before the first point: hard left, right silent
    CHECK(out[2 * 1000] > 0.49f);
    CHECK_NEAR(out[2 * 30 * frame], out[2 * 30 * frame + 1], 5e-3);  // centre at frame 30
    CHECK_NEAR(out[2 * 55 * frame], 0.0, 1e-3);        // after the last point: hard right, left silent

    // EQ band gain: a +12 dB lane on band 1 behaves like the static +12 dB band at the band centre.
    g_spec.constant = 0.0f;
    g_spec.sineHz = 1000.0;
    g_spec.sineAmp = 0.1f;
    AudioClipDesc e = toolClip(1, 0, 60);
    e.eq.bands[1].freqHz = 1000.0f;
    e.eq.bands[1].gainDb = 0.0f;
    e.eq.bands[1].q = 1.0f;
    e.lanes = {laneOf(AutoParam::EqGain1, {{0, 12.0f}})};
    out = playFor(snap30({e}), 1.0);
    double peak = 0;
    for (size_t i = 24000; i < 48000; ++i) peak = std::max(peak, static_cast<double>(std::fabs(out[2 * i])));
    CHECK_NEAR(peak, 0.1 * std::pow(10.0, 12.0 / 20.0), 0.012);
    // The same band animated from 0 to 12 dB grows over time instead of jumping.
    e.lanes = {laneOf(AutoParam::EqGain1, {{0, 0.0f}, {40, 12.0f}})};
    out = playFor(snap30({e}), 1.6);
    auto peakOf = [&](size_t from, size_t to) {
        double m = 0;
        for (size_t i = from; i < to; ++i) m = std::max(m, static_cast<double>(std::fabs(out[2 * i])));
        return m;
    };
    CHECK(peakOf(3200, 6400) < peakOf(30000, 33000));
    CHECK(peakOf(30000, 33000) < peakOf(70000, 76000));
    g_spec = FakeSpec{};
}

// The result must not depend on how the stream is cut into blocks: lanes are evaluated per absolute chunk.
static void testAutomationIsBlockSizeIndependent() {
    g_spec = FakeSpec{};
    g_spec.sineHz = 500.0;
    g_spec.sineAmp = 0.3f;
    AudioClipDesc c = toolClip(1, 0, 90);
    c.pan = 0.2f;
    c.userFadeInFrames = 6;
    c.eq.bands[2].freqHz = 800.0f;
    c.eq.bands[2].q = 2.0f;
    c.lanes = {laneOf(AutoParam::GainDb, {{0, -6.0f}, {20, 0.0f}, {44, -3.0f}, {89, -18.0f}}),
               laneOf(AutoParam::Pan, {{0, -0.5f}, {60, 0.8f}}), laneOf(AutoParam::EqGain2, {{0, 0.0f}, {50, 9.0f}})};
    AudioSnapshotData d = snap30({c});
    const int64_t frames = 3 * 48000;
    const std::vector<float> ref = renderOffline(d, frames, 480);
    for (int block : {17, 64, 1000, 4096}) {
        const std::vector<float> other = renderOffline(d, frames, block);
        double diff = 0;
        for (size_t i = 0; i < ref.size(); ++i) diff = std::max(diff, static_cast<double>(std::fabs(ref[i] - other[i])));
        CHECK(diff < 1e-6);
    }
    const std::vector<float> live = playFor(d, 3.0);
    double diff = 0;
    for (int64_t i = 0; i < frames * 2 && i < static_cast<int64_t>(live.size()); ++i) {
        diff = std::max(diff, static_cast<double>(std::fabs(ref[static_cast<size_t>(i)] - live[static_cast<size_t>(i)])));
    }
    CHECK(diff < 1e-5);
    g_spec = FakeSpec{};
}

static double rmsRange(const std::vector<float>& v, int64_t from, int64_t to) {
    double s = 0;
    for (int64_t i = from; i < to; ++i) s += static_cast<double>(v[2 * static_cast<size_t>(i)]) * v[2 * static_cast<size_t>(i)];
    return std::sqrt(s / static_cast<double>(to - from));
}

static void testNoiseSuppressionThroughTheCore() {
    g_spec = FakeSpec{};
    g_spec.noiseAmp = 0.05f;
    g_spec.noiseSeed = 77;
    // The profile comes from a different stretch of the same kind of noise (what a user's quiet region is).
    std::vector<float> mono(48000);
    for (size_t i = 0; i < mono.size(); ++i) mono[i] = noiseAt(static_cast<int64_t>(i) + 500000, 77) * 0.05f;
    std::vector<float> profile(kDenoiseBins);
    CHECK(computeNoiseProfile(mono.data(), mono.size(), profile.data()) == Status::Ok);

    AudioClipDesc off = toolClip(1, 0, 90);
    std::vector<float> clean = playFor(snap30({off}), 3.0);
    AudioClipDesc on = off;
    on.denoiseStrength = 0.8f;
    on.noiseProfile = profile;
    std::vector<float> denoised = playFor(snap30({on}), 3.0);
    const double before = rmsRange(clean, 9600, 130000), after = rmsRange(denoised, 9600, 130000);
    CHECK(20.0 * std::log10(after / before) < -12.0);

    // A retimed clip (1x through the knots) takes the same path through the suppressor.
    AudioClipDesc retimed = on;
    retimed.knots = knotsOf({{0, 0.0}, {90, 90.0}});
    std::vector<float> viaKnots = playFor(snap30({retimed}), 3.0);
    const double afterKnots = rmsRange(viaKnots, 9600, 130000);
    CHECK(20.0 * std::log10(afterKnots / before) < -12.0);

    // Changing the strength makes a new source: the stronger setting leaves less noise behind.
    AudioClipDesc stronger = on;
    stronger.denoiseStrength = 1.0f;
    std::vector<float> strongest = playFor(snap30({stronger}), 3.0);
    CHECK(rmsRange(strongest, 9600, 130000) < after);

    // A clip that ends before the audio does still gets its tail (no short or silent end).
    AudioClipDesc shortClip = on;
    shortClip.durationFrames = 15;  // half a second
    std::vector<float> clipped = playFor(snap30({shortClip}), 1.0);
    CHECK(rmsRange(clipped, 20000, 23900) > 0.0);  // the last samples of the clip are present
    CHECK(rmsRange(clipped, 24100, 40000) == 0.0);  // and silence follows it
    g_spec = FakeSpec{};
}

static void testMeterReportsPeaks() {
    g_spec = FakeSpec{};
    g_spec.constant = 0.5f;
    AudioCore* core = nullptr;
    std::vector<float> out = playFor(snap30({toolClip(1, 0, 60)}), 0.5, &core);
    float l = 0, r = 0;
    core->takePeaks(&l, &r);
    CHECK_NEAR(l, 0.5, 1e-5);
    CHECK_NEAR(r, 0.5, 1e-5);
    core->takePeaks(&l, &r);
    CHECK(l == 0.0f && r == 0.0f);  // cleared by the read
    g_spec = FakeSpec{};
}

// ---- offline analysis on a fake decoder

static void testAnalysis() {
    // Loudness of a 1 kHz sine at amplitude 0.1 (stereo) is -20 LUFS, also through the 44.1 -> 48 kHz resampler.
    g_spec = FakeSpec{};
    g_spec.rate = 44100;
    g_spec.sineHz = 1000.0;
    g_spec.sineAmp = 0.1f;
    g_spec.totalFrames = 44100 * 20;
    {
        FakeDecoder dec(g_spec);
        double lufs = 0, peak = 0;
        CHECK(measureLoudness(dec, 0, -1, &lufs, &peak) == Status::Ok);
        CHECK_NEAR(lufs, -20.0, 0.15);
        CHECK_NEAR(peak, 0.1, 0.005);
    }
    {
        FakeDecoder dec(g_spec);
        double lufs = 0;
        CHECK(measureLoudness(dec, 2'000'000, 6'000'000, &lufs) == Status::Ok);  // a 4 s range
        CHECK_NEAR(lufs, -20.0, 0.15);
    }
    {
        FakeDecoder dec(g_spec);
        double lufs = 0;
        CHECK(measureLoudness(dec, 5'000'000, 5'000'000, &lufs) == Status::InvalidArgument);
        CHECK(measureLoudness(dec, -1, 1000, &lufs) == Status::InvalidArgument);
        CHECK(measureLoudness(dec, 0, 1000, nullptr) == Status::InvalidArgument);
        int calls = 0;
        CHECK(measureLoudness(dec, 0, -1, &lufs, nullptr, [&calls] { return ++calls > 3; }) == Status::Cancelled);
    }
    {
        // Silence has no loudness.
        FakeSpec quiet = g_spec;
        quiet.sineAmp = 0.0f;
        FakeDecoder dec(quiet);
        double lufs = 0;
        CHECK(measureLoudness(dec, 0, 3'000'000, &lufs) == Status::Ok);
        CHECK(lufs == LoudnessMeter::kSilence);
    }
    // Noise profile of uniform noise at amplitude 0.05: sigma = 0.05/sqrt(3), |X| ~ sigma * sqrt(N * 0.5).
    g_spec = FakeSpec{};
    g_spec.noiseAmp = 0.05f;
    g_spec.noiseSeed = 9;
    {
        FakeDecoder dec(g_spec);
        float mag[kDenoiseBins];
        CHECK(measureNoiseProfile(dec, 0, 2'000'000, mag) == Status::Ok);
        const double expected = (0.05 / std::sqrt(3.0)) * std::sqrt(kDenoiseFft * 0.5);
        CHECK_NEAR(mag[100], expected, expected * 0.15);
        CHECK_NEAR(mag[400], expected, expected * 0.15);
        FakeDecoder shortDec(g_spec);
        CHECK(measureNoiseProfile(shortDec, 0, 20'000, mag) == Status::InvalidArgument);  // 20 ms is too short
    }
    g_spec = FakeSpec{};
}
int main() {
    testTimeMath();
    testClockMapper();
    testResampler();
    testClipBuffer();
    testSnapshotParsing();
    testMixerPlacementAndGain();
    testOverlapSumsAndClips();
    testCrossfadeGains();
    testSilenceOutsideClipsAndEof();
    testResamplingPath();
    testSeekPauseAndClock();
    testFailuresAreReported();
    testSnapshotEditsReuseDecoders();
    testFarClipsHoldNoDecoder();
    testThreadedWorker();
    testOfflineRenderWaitsForData();
    testOfflineRenderSurvivesTransientDecodeFailures();
    testOfflineRenderReportsAPermanentlyFailingClip();
    testRetimeSnapshotParsing();
    testRetimeMap();
    testRetimedReaderForward();
    testRetimedReaderReverse();
    testRetimedReaderRampAndEdges();
    testRetimedReaderReportsDecoderFailures();
    testRetimedClipsPlayThroughTheCore();
    testRetimeChangeStartsANewSource();
    testSnapshotV4Parsing();
    testPanFadesAndGainInTheMixer();
    testEqInTheMixer();
    testTrackVolumeMuteAndCompressor();
    testDuckingInTheMixer();
    testOfflineMatchesRealtimeAndBlockSizes();
    testEditsKeepDspStateRunning();
    testAutomationSnapshotParsing();
    testAutomationInTheMixer();
    testAutomationIsBlockSizeIndependent();
    testNoiseSuppressionThroughTheCore();
    testMeterReportsPeaks();
    testAnalysis();
    if (g_failures == 0) std::puts("audio host tests: all passed");
    return g_failures == 0 ? 0 : 1;
}
