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

#include "audio/audio_core.h"
#include "audio/audio_mixer.h"
#include "audio/audio_snapshot.h"
#include "audio/audio_time.h"
#include "audio/clip_buffer.h"
#include "audio/clock_mapper.h"
#include "audio/resampler.h"
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

static Buf makeAudioSnapshot(int32_t fpsNum, int32_t fpsDen, const std::vector<AudioClipDesc>& clips) {
    Buf w;
    w.put<uint32_t>(kAudioSnapshotMagic);
    w.put<uint32_t>(kAudioSnapshotVersion);
    w.put<int32_t>(fpsNum);
    w.put<int32_t>(fpsDen);
    w.put<uint32_t>(static_cast<uint32_t>(clips.size()));
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
        w.put<int32_t>(0);
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

struct FakeSpec {
    int32_t rate = 48000;
    int64_t totalFrames = 48000 * 600;  // source frames available
    float constant = -1.0f;             // >= 0: constant signal instead of the index ramp
    Status openStatus = Status::Ok;     // != Ok: factory fails
};
static FakeSpec g_spec;

// Source sample i has a unique, reproducible value so placement and seeking can be verified.
static float valueAt(int64_t i) { return static_cast<float>((i % 20000) + 1) / 40000.0f; }

class FakeDecoder : public PcmDecoder {
public:
    explicit FakeDecoder(FakeSpec s) : spec_(s) {
        ++g_liveDecoders;
        ++g_createdDecoders;
    }
    ~FakeDecoder() override { --g_liveDecoders; }
    int32_t sampleRate() const override { return spec_.rate; }
    Status seekToMicros(int64_t us) override {
        pos_ = microsToSamples(us, spec_.rate);
        return Status::Ok;
    }
    PcmReadResult read(float* dst, int32_t maxFrames) override {
        PcmReadResult r;
        if (pos_ >= spec_.totalFrames) {
            r.eof = true;
            return r;
        }
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(maxFrames, spec_.totalFrames - pos_));
        for (int32_t i = 0; i < n; ++i) {
            const float v = spec_.constant >= 0 ? spec_.constant : valueAt(pos_ + i);
            dst[2 * i] = dst[2 * i + 1] = v;
        }
        pos_ += n;
        r.frames = n;
        r.eof = pos_ >= spec_.totalFrames;
        return r;
    }

private:
    FakeSpec spec_;
    int64_t pos_ = 0;
};

static DecoderFactory fakeFactory() {
    return [](int64_t, Status* st) -> std::unique_ptr<PcmDecoder> {
        if (g_spec.openStatus != Status::Ok) {
            *st = g_spec.openStatus;
            return nullptr;
        }
        return std::make_unique<FakeDecoder>(g_spec);
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
    // Two simultaneous 0.75 sources sum to 1.5 and must be hard-limited to 1.0.
    CHECK(core.setSnapshot(snap30({clipDesc(1, 0, 30), clipDesc(2, 0, 30)})) == Status::Ok);
    core.play();
    std::vector<float> out;
    CHECK(renderUntilPlaying(core, &out));
    for (int i = 0; i < 20; ++i) step(core, &out);
    bool allOne = true;
    for (float v : out) allOne = allOne && v == 1.0f;
    CHECK(allOne);
    core.streamStopped();
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
    if (g_failures == 0) std::puts("audio host tests: all passed");
    return g_failures == 0 ? 0 : 1;
}
