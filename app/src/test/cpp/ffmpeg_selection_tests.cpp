// Host tests of the pure logic of the FFmpeg software-decoding fallback: which decoder opens a file, the CPU
// budget, exact timestamp <-> frame mapping, and the worker's seek/decode planning driven by a fake reader.
// No libav and no Android headers are needed (the logic lives in decode/decoder_selection.h,
// decode/ffmpeg/ts_math.h and decode/ffmpeg/software_policy.h).
//
// The real decoding is tested against libav by ffmpeg_reader_tests.cpp (scripts/run-ffmpeg-host-tests.sh, CI).
//
// Build: scripts/run-native-tests.sh (also the CMake host test uv_ffmpeg_selection_host_tests).
#include <cstdint>
#include <cstdio>
#include <set>
#include <string>

#include "decode/decoder_selection.h"
#include "decode/ffmpeg/software_policy.h"
#include "decode/ffmpeg/ts_math.h"

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

using namespace uv::decode;
using namespace uv::decode::ffmpeg;

namespace {

// ---- which decoder opens the file -------------------------------------------------------------------------------

void mediaCodecWinsWhenItOpens() {
    CHECK(chooseRoute(Status::Ok, true) == Route::MediaCodec);
    CHECK(chooseRoute(Status::Ok, false) == Route::MediaCodec);
}

void softwareIsTriedOnlyForFixableFailuresAndOnlyWhenBuiltIn() {
    for (Status s : {Status::UnsupportedFormat, Status::CodecError, Status::NotFound, Status::IoError}) {
        CHECK(softwareMayHelp(s));
        CHECK(chooseRoute(s, true) == Route::Software);
        CHECK(chooseRoute(s, false) == Route::Failed);  // flag off: the typed MediaCodec error is reported
    }
    for (Status s : {Status::InvalidArgument, Status::OutOfMemory, Status::InvalidState, Status::EglError, Status::GlError}) {
        CHECK(!softwareMayHelp(s));
        CHECK(chooseRoute(s, true) == Route::Failed);
    }
}

void errorsKeepTheMediaCodecCodeAndSayWhyThereIsNoFallback() {
    const Error hw{Status::UnsupportedFormat, "only H.264 and HEVC video are supported"};
    const Error notBuilt = combineOpenErrors(hw, nullptr, false);
    CHECK(notBuilt.code == Status::UnsupportedFormat);
    CHECK(notBuilt.message.find("only H.264 and HEVC") != std::string::npos);
    CHECK(notBuilt.message.find("not included in this build") != std::string::npos);

    const Error plain = combineOpenErrors(Error{Status::OutOfMemory, "oom"}, nullptr, false);
    CHECK(plain.message == "oom");  // nothing software could do: no hint

    const Error sw{Status::CodecError, "no software decoder for av1"};
    const Error both = combineOpenErrors(hw, &sw, true);
    CHECK(both.code == Status::UnsupportedFormat);
    CHECK(both.message.find("software decoding also failed: no software decoder for av1") != std::string::npos);
}

// ---- CPU budget --------------------------------------------------------------------------------------------------

void budgetUsesHalfTheCoresWithinLimits() {
    CHECK(softwareBudget(1).threads == 1);
    CHECK(softwareBudget(2).threads == 1);
    CHECK(softwareBudget(8).threads == 4);
    CHECK(softwareBudget(16).threads == 4);
    CHECK(softwareBudget(0).threads == 1);
}

void bigOrFastStreamsAreFlaggedSlow() {
    const SoftwareBudget b = softwareBudget(8);
    CHECK(softwareSpeed(1920, 1080, {30, 1}, b) == SoftwareSpeed::Realtime);
    CHECK(softwareSpeed(1280, 720, {60, 1}, b) == SoftwareSpeed::Realtime);  // 55 Mpx/s < 1080p30 (62 Mpx/s)
    CHECK(softwareSpeed(1920, 1080, {60, 1}, b) == SoftwareSpeed::Slow);
    CHECK(softwareSpeed(3840, 2160, {24, 1}, b) == SoftwareSpeed::Slow);
    CHECK(softwareSpeed(1920, 1080, {30000, 1001}, b) == SoftwareSpeed::Realtime);
    CHECK(softwareLookAhead(60, SoftwareSpeed::Realtime) == 60);
    CHECK(softwareLookAhead(60, SoftwareSpeed::Slow) == 12);
    CHECK(softwareLookAhead(4, SoftwareSpeed::Slow) == 4);
}

// ---- exact timestamp mapping -------------------------------------------------------------------------------------

void ptsMapsToIntegerFramesWithoutDrift() {
    const TimeBase mpegts{1, 90000};
    const Rational ntsc{30000, 1001};
    CHECK(ptsToFrame(0, mpegts, 0, ntsc) == 0);
    CHECK(ptsToFrame(3003, mpegts, 0, ntsc) == 1);
    CHECK(ptsToFrame(3003 * 1000, mpegts, 0, ntsc) == 1000);
    // A pts one tick off a frame boundary still lands on that frame (round half up).
    CHECK(ptsToFrame(3003 * 1000 + 1, mpegts, 0, ntsc) == 1000);
    CHECK(ptsToFrame(3003 * 1000 - 1, mpegts, 0, ntsc) == 1000);
    // A start offset is removed.
    CHECK(ptsToFrame(90000 + 3003, mpegts, 90000, ntsc) == 1);
    // Hours do not drift: frame 1,800,000 of 29.97 fps is exactly 1,800,000 * 3003 ticks.
    CHECK(ptsToFrame(1800000LL * 3003, mpegts, 0, ntsc) == 1800000);

    const TimeBase ms{1, 1000};
    CHECK(ptsToFrame(40, ms, 0, {25, 1}) == 1);
    CHECK(ptsToFrame(20, ms, 0, {25, 1}) == 1);  // half a frame rounds up
    CHECK(ptsToFrame(19, ms, 0, {25, 1}) == 0);
    CHECK(ptsToFrame(-40, ms, 0, {25, 1}) == -1);
}

void seekTargetsNeverLieAheadOfTheFrame() {
    const TimeBase mpegts{1, 90000};
    const Rational ntsc{30000, 1001};
    for (int64_t f : {0LL, 1LL, 2LL, 29LL, 100LL, 12345LL}) {
        const int64_t pts = frameToPtsFloor(f, mpegts, 0, ntsc);
        CHECK(pts <= f * 3003);
        CHECK(ptsToFrame(pts, mpegts, 0, ntsc) == f);
    }
    // A timebase that does not divide the frame period: floor keeps the seek at or before the frame.
    const TimeBase odd{1, 15360};
    const int64_t pts = frameToPtsFloor(7, odd, 100, {24000, 1001});
    CHECK(pts >= 100);
    CHECK(ptsToFrame(pts, odd, 100, {24000, 1001}) == 7);
    CHECK(microsToPtsFloor(1000000, {1, 48000}, 0) == 48000);
    CHECK(microsToPtsFloor(999999, {1, 48000}, 0) == 47999);
}

void durationConvertsToFrames() {
    CHECK(durationToFrames(0, {1, 1000}, {25, 1}) == 0);
    CHECK(durationToFrames(4000, {1, 1000}, {25, 1}) == 100);
    CHECK(durationToFrames(10 * 1000000LL, {1, 1000000}, {30000, 1001}) == 300);  // 10 s of 29.97 fps
}

void frameRateIsExactAndSnapped() {
    CHECK(chooseFrameRate({60, 1}, {25, 1}, {0, 0}).num == 60);  // override wins
    const Rational ntsc = chooseFrameRate({0, 0}, {30000, 1001}, {0, 0});
    CHECK(ntsc.num == 30000 && ntsc.den == 1001);
    const Rational near = chooseFrameRate({0, 0}, {2997, 100}, {0, 0});  // 29.97 as a decimal fraction
    CHECK(near.num == 30000 && near.den == 1001);
    const Rational nominal = chooseFrameRate({0, 0}, {0, 0}, {24, 1});  // no average rate: the codec's
    CHECK(nominal.num == 24 && nominal.den == 1);
    const Rational unknown = chooseFrameRate({0, 0}, {0, 0}, {0, 0});
    CHECK(unknown.num == 30 && unknown.den == 1);
    const Rational odd = chooseFrameRate({0, 0}, {15000, 1000}, {0, 0});  // 15 fps: not in the table, kept exact
    CHECK(odd.num == 15 && odd.den == 1);
}

void colourAndRotationMetadataMapToMediaFormatValues() {
    CHECK(mediaTransferFromAv(16) == 6);  // SMPTE ST 2084 (PQ)
    CHECK(mediaTransferFromAv(18) == 7);  // ARIB STD-B67 (HLG)
    CHECK(mediaTransferFromAv(1) == 3);   // BT.709
    CHECK(mediaTransferFromAv(8) == 1);   // linear
    CHECK(mediaTransferFromAv(2) == 0);   // unspecified
    // libav reports counter-clockwise degrees; players rotate clockwise.
    CHECK(clockwiseRotationFromDisplayMatrix(0.0) == 0);
    CHECK(clockwiseRotationFromDisplayMatrix(-90.0) == 90);
    CHECK(clockwiseRotationFromDisplayMatrix(90.0) == 270);
    CHECK(clockwiseRotationFromDisplayMatrix(180.0) == 180);
    CHECK(clockwiseRotationFromDisplayMatrix(-180.0) == 180);
    CHECK(clockwiseRotationFromDisplayMatrix(-89.7) == 90);
}

// ---- worker planning against a fake reader -----------------------------------------------------------------------

// A stream of `frames` pictures with a key frame every `gop`. Like a demuxer: seek lands on the previous key
// frame; decode() returns the next picture in order.
struct FakeReader {
    int64_t frames = 600;
    int64_t gop = 240;
    int64_t pos = 0;  // next picture decode() returns
    int64_t seeks = 0;
    int64_t decoded = 0;
    void seek(int64_t frame) {
        pos = (frame / gop) * gop;
        ++seeks;
    }
    bool decode(int64_t* frame) {
        if (pos >= frames) return false;
        *frame = pos++;
        ++decoded;
        return true;
    }
};

// Mirrors FfmpegDecoder::step() without threads or buffers: every picture inside the window is "cached"
// immediately. The playhead moves with `advance`.
struct Driver {
    FakeReader reader;
    PlanState plan;
    std::set<int64_t> cache;
    int32_t behind = 0;
    int32_t ahead = 4;
    int64_t target = 0;

    // Returns false when nothing was missing.
    bool step() {
        const Window w = windowFor(target, behind, ahead, reader.frames - 1);
        const Plan p = planStep(plan, w, [this](int64_t f) { return cache.count(f) != 0 || f >= reader.frames; });
        if (p.step == Step::Idle) return false;
        if (p.step == Step::Seek) {
            reader.seek(p.missing);
            plan.forced = false;
            plan.primed = true;
            plan.awaitingFirstOutput = true;
            plan.seekGoal = p.missing;
        }
        for (int i = 0; i < 8; ++i) {
            int64_t f = 0;
            if (!reader.decode(&f)) {
                plan.awaitingFirstOutput = false;
                plan.seekGoal = -1;
                return true;
            }
            plan.awaitingFirstOutput = false;
            plan.decodePos = f + 1;
            if (plan.seekGoal >= 0 && f >= plan.seekGoal) plan.seekGoal = -1;
            const Window now = windowFor(target, behind, ahead, reader.frames - 1);
            if (f >= now.lo && f <= now.hi) cache.insert(f);
            if (f >= p.missing) break;
        }
        return true;
    }
    void settle() {
        for (int guard = 0; guard < 100000 && step(); ++guard) {
        }
    }
};

void sequentialPlaybackOverALongGopSeeksOnce() {
    Driver d;
    d.reader.frames = 600;
    d.reader.gop = 240;
    d.target = 0;
    d.settle();
    for (int64_t t = 1; t < 600; ++t) {
        d.target = t;
        d.settle();
        CHECK(d.cache.count(t) != 0);
    }
    CHECK(d.reader.seeks == 1);
    CHECK(d.reader.decoded == 600);  // every picture decoded exactly once
}

void startingMidGopDecodesForwardFromTheKeyFrame() {
    Driver d;
    d.reader.gop = 240;
    d.target = 300;  // the previous key frame is 240
    d.settle();
    CHECK(d.reader.seeks == 1);
    CHECK(d.cache.count(300) != 0);
    CHECK(d.reader.decoded == 300 - 240 + 1 + d.ahead);  // 240..304
}

void aJumpFarAheadSeeksOnceAndABackwardJumpSeeksOnce() {
    Driver d;
    d.reader.gop = 30;
    d.target = 0;
    d.settle();
    d.target = 500;  // far beyond the forward-skip limit: one seek
    d.settle();
    CHECK(d.reader.seeks == 2);
    CHECK(d.cache.count(500) != 0);
    d.cache.clear();
    d.target = 100;  // behind the decoder: one seek
    d.settle();
    CHECK(d.reader.seeks == 3);
    CHECK(d.cache.count(100) != 0);
}

void aShortForwardJumpDecodesThroughInsteadOfSeeking() {
    Driver d;
    d.reader.gop = 600;
    d.target = 0;
    d.settle();
    d.cache.clear();
    d.target = 80;  // within kMaxForwardSkipFrames of the decoder
    d.settle();
    CHECK(d.reader.seeks == 1);
    CHECK(d.cache.count(80) != 0);
}

void recoverForcesOneFreshSeek() {
    Driver d;
    d.reader.gop = 240;
    d.target = 10;
    d.settle();
    CHECK(d.reader.seeks == 1);
    d.cache.clear();
    d.plan.forced = true;  // FfmpegDecoder::recover()
    d.settle();
    CHECK(d.reader.seeks == 2);
    CHECK(d.cache.count(10) != 0);
}

void theWindowStaysInsideTheStreamAndFramesPastTheEndAreNotAsked() {
    const Window w = windowFor(598, 2, 10, 599);
    CHECK(w.lo == 596 && w.hi == 599);
    const Window start = windowFor(1, 5, 3, 599);
    CHECK(start.lo == 0 && start.hi == 4);
    const Window past = windowFor(700, 0, 2, 599);  // a playhead beyond the last frame still asks for itself
    CHECK(past.hi == 700);
    Driver d;
    d.reader.frames = 20;
    d.reader.gop = 10;
    d.target = 19;
    d.settle();
    CHECK(d.cache.count(19) != 0);
}

}  // namespace

int main() {
    mediaCodecWinsWhenItOpens();
    softwareIsTriedOnlyForFixableFailuresAndOnlyWhenBuiltIn();
    errorsKeepTheMediaCodecCodeAndSayWhyThereIsNoFallback();
    budgetUsesHalfTheCoresWithinLimits();
    bigOrFastStreamsAreFlaggedSlow();
    ptsMapsToIntegerFramesWithoutDrift();
    seekTargetsNeverLieAheadOfTheFrame();
    durationConvertsToFrames();
    frameRateIsExactAndSnapped();
    colourAndRotationMetadataMapToMediaFormatValues();
    sequentialPlaybackOverALongGopSeeksOnce();
    startingMidGopDecodesForwardFromTheKeyFrame();
    aJumpFarAheadSeeksOnceAndABackwardJumpSeeksOnce();
    aShortForwardJumpDecodesThroughInsteadOfSeeking();
    recoverForcesOneFreshSeek();
    theWindowStaysInsideTheStreamAndFramesPastTheEndAreNotAsked();
    if (g_failures == 0) std::puts("all FFmpeg fallback selection tests passed");
    return g_failures == 0 ? 0 : 1;
}
