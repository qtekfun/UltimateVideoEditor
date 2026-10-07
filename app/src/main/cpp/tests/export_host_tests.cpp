// Host tests for encode/export_math.h (no Android dependencies).
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <map>
#include <unordered_set>
#include <vector>

#include "encode/export_math.h"
#include "encode/frame_signature.h"
#include "encode/occlusion_math.h"
#include "encode/picture_residency.h"
#include "encode/still_math.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                                                         \
    do {                                                                                                    \
        if (!(cond)) {                                                                                      \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                                      \
            ++failures;                                                                                     \
        }                                                                                                   \
    } while (0)

#define CHECK_EQ(actual, expected)                                                                          \
    do {                                                                                                    \
        const long long a_ = static_cast<long long>(actual);                                                \
        const long long e_ = static_cast<long long>(expected);                                              \
        if (a_ != e_) {                                                                                     \
            std::printf("FAIL %s:%d: %s = %lld, expected %lld\n", __FILE__, __LINE__, #actual, a_, e_);    \
            ++failures;                                                                                     \
        }                                                                                                   \
    } while (0)

using namespace uv::encode;

void ptsIsExactForNtscRates() {
    const Fps f5994{60000, 1001};
    CHECK_EQ(frameToNs(0, f5994), 0);
    // 60000 frames at 59.94 fps are exactly 1001 seconds.
    CHECK_EQ(frameToNs(60000, f5994), 1001000000000LL);
    CHECK_EQ(frameToUs(60000, f5994), 1001000000LL);
    // One frame is 16.683333... ms: rounded to the nearest nanosecond.
    CHECK_EQ(frameToNs(1, f5994), 16683333);
    CHECK_EQ(frameToNs(3, f5994), 50050000);
    const Fps f30{30, 1};
    CHECK_EQ(frameToNs(1, f30), 33333333);
    CHECK_EQ(frameToNs(30, f30), 1000000000LL);
}

void ptsDoesNotDriftOverLongTimelines() {
    // 30000 frames at 29.97 fps take exactly 1001 s, so 30030000 frames take 1001 * 1001 s:
    // the 128-bit path must not overflow and must stay exact.
    const Fps f{30000, 1001};
    CHECK_EQ(frameToNs(30000LL * 1001, f), 1001LL * 1001 * 1000000000LL);
    int64_t previous = -1;
    for (int64_t frame = 0; frame < 200000; frame += 7) {
        const int64_t ns = frameToNs(frame, f);
        if (ns <= previous) {
            std::printf("FAIL: pts not strictly increasing at frame %lld\n", static_cast<long long>(frame));
            ++failures;
            break;
        }
        previous = ns;
    }
    // Ten million frames still fits: no overflow in the intermediate product.
    CHECK_EQ(frameToNs(10000000, Fps{25, 1}), 400000000000000LL);
}

void audioSamplesTileWithVideoFrames() {
    const Fps f5994{60000, 1001};
    CHECK_EQ(framesToSamples(60000, f5994, 48000), 48048000LL);
    const Fps f30{30, 1};
    CHECK_EQ(framesToSamples(1, f30, 48000), 1600);
    // Boundaries computed per frame never overlap or leave gaps: the sum of per-frame lengths
    // equals the total, whatever the rounding.
    int64_t sum = 0;
    for (int64_t frame = 0; frame < 5000; ++frame) {
        sum += framesToSamples(frame + 1, f5994, 48000) - framesToSamples(frame, f5994, 48000);
    }
    CHECK_EQ(sum, framesToSamples(5000, f5994, 48000));
    CHECK_EQ(samplesToUs(48000, 48000), 1000000);
    CHECK_EQ(samplesToUs(1, 48000), 21);
}

void progressIsClampedAndMonotonic() {
    CHECK_EQ(progressPermille(0, 100), 0);
    CHECK_EQ(progressPermille(50, 100), 500);
    CHECK_EQ(progressPermille(100, 100), 1000);
    CHECK_EQ(progressPermille(250, 100), 1000);
    CHECK_EQ(progressPermille(-3, 100), 0);
    CHECK_EQ(progressPermille(5, 0), 0);
    int32_t previous = 0;
    for (int64_t done = 0; done <= 997; ++done) {
        const int32_t p = progressPermille(done, 997);
        if (p < previous) {
            std::printf("FAIL: progress went backwards at %lld\n", static_cast<long long>(done));
            ++failures;
            break;
        }
        previous = p;
    }
    CHECK_EQ(previous, 1000);
}

VideoClip makeClip(int64_t start, int64_t duration, int64_t sourceIn, int64_t asset, int32_t layer) {
    VideoClip c;
    c.startFrame = start;
    c.durationFrames = duration;
    c.sourceInFrame = sourceIn;
    c.assetKey = asset;
    c.layer = layer;
    return c;
}

void layersAreListedBottomFirst() {
    std::vector<VideoClip> clips;
    clips.push_back(makeClip(0, 100, 0, 1, 0));    // top track
    clips.push_back(makeClip(0, 100, 0, 2, 2));    // bottom track
    clips.push_back(makeClip(50, 100, 0, 3, 1));   // middle track, starts later
    const auto at10 = layersAt(clips, 10);
    CHECK_EQ(at10.size(), 2);
    CHECK_EQ(at10[0]->assetKey, 2);  // drawn first, so it ends up underneath
    CHECK_EQ(at10[1]->assetKey, 1);
    const auto at60 = layersAt(clips, 60);
    CHECK_EQ(at60.size(), 3);
    CHECK_EQ(at60[0]->assetKey, 2);
    CHECK_EQ(at60[1]->assetKey, 3);
    CHECK_EQ(at60[2]->assetKey, 1);
    CHECK_EQ(layersAt(clips, 150).size(), 0);  // ends are exclusive and the gap is empty
    CHECK_EQ(layersAt(clips, 149).size(), 1);
}

void topLayerWinsAndGapsAreEmpty() {
    std::vector<VideoClip> clips;
    clips.push_back(makeClip(0, 100, 0, 1, 1));    // lower track
    clips.push_back(makeClip(50, 100, 10, 2, 0));  // top track, overlaps frames 50..149
    const VideoClip* a = clipAt(clips, 10);
    const VideoClip* b = clipAt(clips, 60);
    const VideoClip* c = clipAt(clips, 120);
    const VideoClip* d = clipAt(clips, 150);
    CHECK_EQ(a != nullptr ? a->assetKey : -1, 1);
    CHECK_EQ(b != nullptr ? b->assetKey : -1, 2);  // the top track covers the lower one
    CHECK_EQ(c != nullptr ? c->assetKey : -1, 2);
    CHECK_EQ(d == nullptr ? 1 : 0, 1);             // clip end is exclusive
    CHECK_EQ(clipAt(clips, -1) == nullptr ? 1 : 0, 1);
}

void keyframedEffectsPickTheFrameOfTheClip() {
    VideoClip c = makeClip(40, 5, 0, 1, 0);
    uv::core::LayerFx fixed;
    fixed.blend = uv::core::BlendMode::Add;
    c.fx = fixed;
    // No table: the fixed effects hold for every frame, even outside the clip.
    CHECK_EQ(fxAt(c, 42).blend == uv::core::BlendMode::Add ? 1 : 0, 1);
    for (int i = 0; i < 5; ++i) {
        uv::core::LayerFx f;
        f.blend = i == 3 ? uv::core::BlendMode::Screen : uv::core::BlendMode::Multiply;
        c.fxFrames.push_back(f);
    }
    // Index = frame - startFrame, clamped to the table at both ends (a transition's window can ask outside).
    CHECK_EQ(fxAt(c, 40).blend == uv::core::BlendMode::Multiply ? 1 : 0, 1);
    CHECK_EQ(fxAt(c, 43).blend == uv::core::BlendMode::Screen ? 1 : 0, 1);
    CHECK_EQ(fxAt(c, 44).blend == uv::core::BlendMode::Multiply ? 1 : 0, 1);
    CHECK_EQ(fxAt(c, 10).blend == uv::core::BlendMode::Multiply ? 1 : 0, 1);   // before the clip: first entry
    CHECK_EQ(fxAt(c, 900).blend == uv::core::BlendMode::Multiply ? 1 : 0, 1);  // after: last entry
}

void outputFramesMapToProjectFrames() {
    const Fps p30{30, 1};
    const Fps p60{60, 1};
    CHECK_EQ(outputToProjectFrame(17, p30, p30), 17);
    CHECK_EQ(outputToProjectFrame(10, p30, p60), 20);  // exporting a 60 fps project at 30 fps: every second frame
    CHECK_EQ(outputToProjectFrame(10, p60, p30), 5);   // exporting a 30 fps project at 60 fps: frames repeat
    CHECK_EQ(outputToProjectFrame(11, p60, p30), 5);
    CHECK_EQ(outputToProjectFrame(12, p60, p30), 6);
    CHECK_EQ(outputToProjectFrame(60000, Fps{60000, 1001}, Fps{30000, 1001}), 30000);
}

void sourceFrameMapsAndClamps() {
    const VideoClip clip = makeClip(100, 50, 20, 1, 0);
    CHECK_EQ(sourceFrameFor(clip, 100, 1000), 20);
    CHECK_EQ(sourceFrameFor(clip, 149, 1000), 69);
    CHECK_EQ(sourceFrameFor(clip, 149, 60), 59);   // media shorter than the clip range: hold the last frame
    CHECK_EQ(sourceFrameFor(clip, 149, 0), 69);    // unknown length: no clamp
    CHECK_EQ(totalFramesOf({clip}, 0), 150);
    CHECK_EQ(totalFramesOf({clip}, 400), 400);
}

void retimedClipsReadTheirSourceTable() {
    // A 2x clip: the Kotlin plan (domain/Retime.kt) lists source frames 20, 22, 24, ... for project frames 100...
    VideoClip clip = makeClip(100, 5, 0, 1, 0);
    clip.sourceTable = {20, 22, 24, 26, 28};
    CHECK_EQ(sourceFrameFor(clip, 100, 1000), 20);
    CHECK_EQ(sourceFrameFor(clip, 103, 1000), 26);
    CHECK_EQ(sourceFrameFor(clip, 104, 27), 26);   // media ends: hold its last frame
    CHECK_EQ(sourceFrameFor(clip, 120, 1000), 28);  // past the table (transition tail): the last entry holds
    CHECK_EQ(sourceFrameFor(clip, 90, 1000), 20);   // before it: the first entry
    // A reversed clip's table runs downwards and the same lookup applies.
    VideoClip back = makeClip(0, 4, 0, 1, 0);
    back.sourceTable = {99, 98, 97, 96};
    back.reverse = true;
    CHECK_EQ(sourceFrameFor(back, 0, 1000), 99);
    CHECK_EQ(sourceFrameFor(back, 3, 1000), 96);
    // A freeze frame is a table of one repeated frame.
    VideoClip freeze = makeClip(0, 3, 0, 1, 0);
    freeze.sourceTable = {42, 42, 42};
    CHECK_EQ(sourceFrameFor(freeze, 2, 1000), 42);
}

void smoothSlowMotionEntriesCarryTheirMix() {
    // Entries as ui/export/ExportPlan.kt packSource writes them: frame | (permille << 44) when interpolated.
    const int64_t shift = static_cast<int64_t>(1) << kSourceMixShift;
    VideoClip clip = makeClip(0, 4, 0, 1, 0);
    clip.sourceTable = {5, 5 + 250 * shift, 5 + 500 * shift, 5 + 750 * shift};
    CHECK_EQ(sourceFrameFor(clip, 0, 1000), 5);
    CHECK_EQ(sourceFrameFor(clip, 2, 1000), 5);  // the mix bits never leak into the frame
    CHECK_EQ(sourceMixFor(clip, 0), 0);
    CHECK_EQ(sourceMixFor(clip, 1), 250);
    CHECK_EQ(sourceMixFor(clip, 3), 750);
    CHECK_EQ(sourceMixFor(clip, 99), 750);  // past the table: the last entry holds
    CHECK_EQ(blendFrameFor(clip, 5, 1000), 6);  // forward: the next frame
    CHECK_EQ(blendFrameFor(clip, 999, 1000), -1);  // nothing after the last frame of the media
    clip.reverse = true;
    CHECK_EQ(blendFrameFor(clip, 5, 1000), 4);  // reversed: the frame before
    CHECK_EQ(blendFrameFor(clip, 0, 1000), -1);
    // Negative frames (a transition's pre-roll before the media) are stored plain and have no mix.
    VideoClip early = makeClip(0, 2, 0, 1, 0);
    early.sourceTable = {-4, -3};
    CHECK_EQ(sourceFrameFor(early, 0, 1000), 0);  // clamped to the media
    CHECK_EQ(sourceMixFor(early, 0), 0);
}

void reverseWindowIsBoundedByMemory() {
    constexpr int64_t k4k = 3840LL * 2160 * 4;
    constexpr int64_t k1080 = 1920LL * 1080 * 4;
    CHECK_EQ(reverseWindowFrames(k4k), 8);     // 256 MiB / 33.2 MB
    CHECK_EQ(reverseWindowFrames(k1080), 32);  // 256 MiB / 8.3 MB
    CHECK_EQ(reverseWindowFrames(640LL * 360 * 4), 48);  // small frames: capped
    CHECK_EQ(reverseWindowFrames(1LL << 40), 4);          // huge frames: never below the minimum
    CHECK_EQ(reverseWindowFrames(0), 4);
}

// Mirrors domain/RenderPlanTest.kt: the same clips, frames and expected values, so the preview
// (Kotlin) and the exporter (here) agree on how a transition looks.
void transitionOverlapMatchesTheKotlinPlan() {
    VideoClip outgoing = makeClip(0, 105, 0, 7, 0);         // A extended 5 frames past its cut at 100
    VideoClip incoming = makeClip(95, 105, 45, 7, 0);       // B starts 5 frames early, reading earlier media
    incoming.fadeInFrames = 10;
    incoming.lane = 1;
    const std::vector<VideoClip> clips{outgoing, incoming};

    CHECK_EQ(layersAt(clips, 90).size(), 1);
    const auto during = layersAt(clips, 100);
    CHECK_EQ(during.size(), 2);
    CHECK_EQ(during[0]->startFrame, 0);   // outgoing underneath
    CHECK_EQ(during[1]->startFrame, 95);  // incoming on top
    CHECK_EQ(layersAt(clips, 105).size(), 1);

    auto micro = [](double v) { return static_cast<long long>(std::llround(v * 1e6)); };
    CHECK_EQ(micro(opacityAt(outgoing, 100)), 1000000);
    CHECK_EQ(micro(opacityAt(incoming, 95)), 50000);
    CHECK_EQ(micro(opacityAt(incoming, 100)), 550000);
    CHECK_EQ(micro(opacityAt(incoming, 104)), 950000);
    CHECK_EQ(micro(opacityAt(incoming, 105)), 1000000);
    CHECK_EQ(micro(opacityAt(incoming, 150)), 1000000);
    incoming.opacity = 0.5;
    CHECK_EQ(micro(opacityAt(incoming, 100)), 275000);

    // The picture at the cut is the one without the transition.
    CHECK_EQ(sourceFrameFor(outgoing, 100, 1000), 100);
    CHECK_EQ(sourceFrameFor(incoming, 100, 1000), 50);
    CHECK_EQ(sourceFrameFor(incoming, 95, 1000), 45);
    // Before the media starts the first frame is held.
    VideoClip early = makeClip(0, 20, 0, 7, 0);
    early.startFrame = -5;
    CHECK_EQ(sourceFrameFor(early, 0, 1000), 5);
    CHECK_EQ(sourceFrameFor(makeClip(10, 20, -5, 7, 0), 10, 1000), 0);
}

void sameLayerClipsStackByStartFrame() {
    // Several clips of one layer ordered by start whatever order they come in.
    std::vector<VideoClip> clips;
    clips.push_back(makeClip(40, 100, 0, 3, 1));
    clips.push_back(makeClip(0, 100, 0, 2, 1));
    clips.push_back(makeClip(20, 100, 0, 4, 1));
    const auto out = layersAt(clips, 50);
    CHECK_EQ(out.size(), 3);
    CHECK_EQ(out[0]->assetKey, 2);
    CHECK_EQ(out[1]->assetKey, 4);
    CHECK_EQ(out[2]->assetKey, 3);
}

void titleClipsCarryTheirKey() {
    VideoClip title = makeClip(10, 30, 0, 0, 0);
    title.titleKey = 5;
    CHECK_EQ(layersAt({title}, 20).size(), 1);
    CHECK_EQ(layersAt({title}, 20)[0]->titleKey, 5);
}

#define CHECK_NEAR(actual, expected)                                                                           \
    do {                                                                                                       \
        const double a_ = static_cast<double>(actual);                                                         \
        const double e_ = static_cast<double>(expected);                                                       \
        if (std::fabs(a_ - e_) > 1e-9) {                                                                       \
            std::printf("FAIL %s:%d: %s = %.12f, expected %.12f\n", __FILE__, __LINE__, #actual, a_, e_);      \
            ++failures;                                                                                        \
        }                                                                                                      \
    } while (0)

// The same vectors as KeyframesTest.kotlinAndNativeShareVectors in the JVM tests.
std::vector<uv::core::Keyframe> sharedKeyframes() {
    using uv::core::Interpolation;
    using uv::core::Keyframe;
    return {
        Keyframe{0, {0, 0, 1, 1, 0, 1.0}, Interpolation::Linear},
        Keyframe{10, {100, -50, 2, 3, 90, 0.5}, Interpolation::Ease},
        Keyframe{20, {300, 50, 1, 1, 180, 0.0}, Interpolation::Hold},
        Keyframe{30, {0, 0, 1, 1, 0, 1.0}, Interpolation::Linear},
    };
}

void keyframesMatchTheKotlinVectors() {
    const auto keys = sharedKeyframes();
    const uv::core::Pose base{7, 8, 1, 1, 0, 1};
    CHECK_NEAR(uv::core::evaluateKeyframes({}, 5, base).posX, 7);  // no keyframes: the fixed pose
    CHECK_NEAR(uv::core::evaluateKeyframes(keys, -3, base).posX, 0);
    CHECK_NEAR(uv::core::evaluateKeyframes(keys, 0, base).opacity, 1.0);
    const auto linear = uv::core::evaluateKeyframes(keys, 5, base);
    CHECK_NEAR(linear.posX, 50);
    CHECK_NEAR(linear.posY, -25);
    CHECK_NEAR(linear.scaleX, 1.5);
    CHECK_NEAR(linear.scaleY, 2.0);
    CHECK_NEAR(linear.rotationDeg, 45);
    CHECK_NEAR(linear.opacity, 0.75);
    CHECK_NEAR(uv::core::evaluateKeyframes(keys, 10, base).posX, 100);
    const auto mid = uv::core::evaluateKeyframes(keys, 15, base);  // ease, halfway: weight 0.5
    CHECK_NEAR(mid.posX, 200);
    CHECK_NEAR(mid.posY, 0);
    CHECK_NEAR(mid.scaleX, 1.5);
    CHECK_NEAR(mid.rotationDeg, 135);
    CHECK_NEAR(mid.opacity, 0.25);
    CHECK_NEAR(uv::core::evaluateKeyframes(keys, 12, base).posX, 120.8);  // ease, t = 0.2: weight 0.104
    const auto held = uv::core::evaluateKeyframes(keys, 25, base);        // hold: the earlier pose until the next key
    CHECK_NEAR(held.posX, 300);
    CHECK_NEAR(held.rotationDeg, 180);
    CHECK_NEAR(uv::core::evaluateKeyframes(keys, 30, base).posX, 0);
    CHECK_NEAR(uv::core::evaluateKeyframes(keys, 1000, base).posX, 0);
}

void poseCountsFromTheClipOriginNotTheTransitionStart() {
    // A transition makes the clip start 10 frames early; keyframes still count from its own first frame.
    VideoClip clip = makeClip(90, 60, 0, 0, 0);
    clip.keyOriginFrame = 100;
    clip.keyframes = sharedKeyframes();
    CHECK_NEAR(poseAt(clip, 105).posX, 50);  // clip frame 5
    CHECK_NEAR(poseAt(clip, 95).posX, 0);    // before the clip's first frame: the first pose holds
    clip.fadeInFrames = 10;
    // Opacity is the animated one times the crossfade ramp ((k + 0.5) / d at k = 5 over 10 frames).
    CHECK_NEAR(opacityAt(clip, 95), 1.0 * 0.55);
    clip.keyframes.clear();
    clip.opacity = 0.5;
    CHECK_NEAR(opacityAt(clip, 95), 0.5 * 0.55);
}

}  // namespace

// A late frame is waited for, never replaced by the one before it (that showed a picture twice and shifted
// the rest of the clip by a frame); a stand-in is only for frames the stream really lacks.
void lateFramesAreWaitedForAndMissingOnesSubstituted() {
    std::map<int64_t, int> cached{{10, 0}, {12, 0}, {13, 0}};
    // 11 is not decoded yet but 12 and 13 are: wait, do not show 10.
    CHECK_EQ(static_cast<int>(pickSourceFrame(cached, 11, false).kind), static_cast<int>(FramePick::Kind::Wait));
    CHECK_EQ(static_cast<int>(pickSourceFrame(cached, 12, false).kind), static_cast<int>(FramePick::Kind::Exact));
    CHECK_EQ(pickSourceFrame(cached, 12, false).frame, 12);
    // The stream never produces 11: the nearest earlier frame stands in.
    const FramePick sub = pickSourceFrame(cached, 11, true);
    CHECK_EQ(static_cast<int>(sub.kind), static_cast<int>(FramePick::Kind::Substitute));
    CHECK_EQ(sub.frame, 10);
    // Past the end of the stream: the last frame.
    CHECK_EQ(pickSourceFrame(cached, 20, true).frame, 13);
    // Nothing earlier than the missing frame: the nearest later one; an empty cache keeps waiting.
    CHECK_EQ(pickSourceFrame(cached, 5, true).frame, 10);
    CHECK_EQ(static_cast<int>(pickSourceFrame(std::map<int64_t, int>{}, 5, true).kind), static_cast<int>(FramePick::Kind::Wait));
    // An exact frame wins even when the stream is flagged as lacking it.
    CHECK_EQ(static_cast<int>(pickSourceFrame(cached, 13, true).kind), static_cast<int>(FramePick::Kind::Exact));
}

// ---- picture residency: an export holds only the stills it needs, within a byte budget ----

static void residencyKeepsWithinBudgetAndEvictsLeastRecentlyUsed() {
    using uv::encode::PictureResidency;
    PictureResidency r(1000);
    const std::unordered_set<uint32_t> none;
    CHECK(r.admit(1, 400, none).empty());
    CHECK(r.admit(2, 400, none).empty());
    r.touch(1);  // 2 is now the oldest
    const auto gone = r.admit(3, 400, none);  // 1200 > 1000
    CHECK_EQ(gone.size(), 1u);
    CHECK_EQ(gone[0], 2u);
    CHECK(r.contains(1) && r.contains(3) && !r.contains(2));
    CHECK_EQ(r.usedBytes(), 800);
    CHECK_EQ(r.size(), 2u);
    // Touching a key that is not resident does nothing.
    r.touch(99);
    CHECK_EQ(r.size(), 2u);
}

static void residencyNeverEvictsTheFrameInUseNorTheNewPicture() {
    using uv::encode::PictureResidency;
    PictureResidency r(1000);
    const std::unordered_set<uint32_t> none;
    r.admit(1, 600, none);
    // Picture 1 is drawn by the current frame: protected, so the budget is exceeded rather than losing it.
    const auto gone = r.admit(2, 600, std::unordered_set<uint32_t>{1});
    CHECK(gone.empty());
    CHECK(r.contains(1) && r.contains(2));
    CHECK_EQ(r.usedBytes(), 1200);
    // The next frame no longer needs 1: it goes as soon as something is admitted.
    const auto later = r.admit(3, 100, std::unordered_set<uint32_t>{2});
    CHECK_EQ(later.size(), 1u);
    CHECK_EQ(later[0], 1u);
    // A single picture larger than the budget stays.
    PictureResidency tiny(10);
    CHECK(tiny.admit(7, 5000, none).empty());
    CHECK(tiny.contains(7));
    // Admitting a key again replaces its size.
    PictureResidency again(1000);
    again.admit(1, 300, none);
    again.admit(1, 500, none);
    CHECK_EQ(again.usedBytes(), 500);
    CHECK_EQ(again.size(), 1u);
}

static void sequentialExportOfAHugeAnimationStaysWithinBudget() {
    using uv::encode::PictureResidency;
    // 600 distinct frames of a 480x270 picture (0.5 MB each) shown one per output frame, 128 MB budget.
    const int64_t frameBytes = 480LL * 270 * 4;
    const int64_t budget = 128LL * 1024 * 1024;
    PictureResidency r(budget);
    int loads = 0;
    int64_t peak = 0;
    for (uint32_t frame = 0; frame < 600; ++frame) {
        const uint32_t key = 1000 + frame;
        std::unordered_set<uint32_t> inUse{key};
        if (!r.contains(key)) {
            ++loads;
            r.admit(key, frameBytes, inUse);
        } else {
            r.touch(key);
        }
        if (r.usedBytes() > peak) peak = r.usedBytes();
    }
    CHECK_EQ(loads, 600);  // every picture is loaded exactly once going forward
    CHECK(peak <= budget);
    CHECK(r.size() >= 250 && r.size() <= 260);
    // Replaying the same animation from the start (a loop) reloads what was evicted but never exceeds the budget.
    int reloads = 0;
    for (uint32_t frame = 0; frame < 600; ++frame) {
        const uint32_t key = 1000 + frame;
        std::unordered_set<uint32_t> inUse{key};
        if (!r.contains(key)) {
            ++reloads;
            r.admit(key, frameBytes, inUse);
        } else {
            r.touch(key);
        }
        CHECK(r.usedBytes() <= budget);
    }
    CHECK(reloads > 0);
}

void stillCropIsValidatedAgainstTheSurface() {
    CHECK(validStillCrop({0, 0, 1280, 720}, 1280, 720));
    CHECK(validStillCrop({1166, 0, 1080, 1920}, 4246, 1920));
    CHECK(!validStillCrop({0, 0, 0, 720}, 1280, 720));               // empty
    CHECK(!validStillCrop({-1, 0, 100, 100}, 1280, 720));            // negative origin
    CHECK(!validStillCrop({1, 0, 1280, 720}, 1280, 720));            // sticks out on the right
    CHECK(!validStillCrop({0, 1, 1280, 720}, 1280, 720));            // sticks out at the bottom
    CHECK(!validStillCrop({0, 0, 10, 10}, kMaxStillSide + 1, 100));  // surface too large
    CHECK(!validStillCrop({0, 0, 10, 10}, 0, 100));
    CHECK(!validStillCrop({2147483647, 0, 2, 2}, 100, 100));         // no overflow
}

void stillReadbackUsesTheLowerLeftOrigin() {
    const StillCrop square{420, 0, 1080, 1080};  // the centred square of a 1920 x 1080 surface
    CHECK_EQ(glReadY(square, 1080), 0);
    const StillCrop top{0, 0, 10, 4};
    CHECK_EQ(glReadY(top, 100), 96);  // the top rows are the last ones GL stores
    const StillCrop bottom{0, 96, 10, 4};
    CHECK_EQ(glReadY(bottom, 100), 0);
    CHECK_EQ(stillBytes({0, 0, 1920, 1080}), 1920LL * 1080 * 4);
}

void stillRowsAreFlippedTopFirst() {
    // Three rows of two RGBA pixels; the first byte of every row tells which row it is.
    uint8_t gl[3 * 8];
    for (int r = 0; r < 3; ++r) {
        for (int i = 0; i < 8; ++i) gl[r * 8 + i] = static_cast<uint8_t>(r * 10 + i);
    }
    uint8_t out[3 * 8] = {};
    flipRows(gl, 8, 3, out);
    CHECK_EQ(out[0], 20);  // the picture's top row is the last row GL returned
    CHECK_EQ(out[8], 10);
    CHECK_EQ(out[16], 0);
    CHECK_EQ(out[23], 7);  // rows stay intact
    uint8_t one[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    uint8_t copy[8] = {};
    flipRows(one, 8, 1, copy);
    CHECK_EQ(copy[7], 8);
}

void stillAlphaIsForcedOpaque() {
    uint8_t px[8] = {10, 20, 30, 0, 40, 50, 60, 128};
    forceOpaque(px, 2);
    CHECK_EQ(px[3], 255);
    CHECK_EQ(px[7], 255);
    CHECK_EQ(px[0], 10);  // colour untouched
    CHECK_EQ(px[6], 60);
}

void stillGuardRefusesFlatPicturesWithLayers() {
    uint8_t flat[16] = {0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255};
    CHECK(isUniformRgba(flat, 4));
    uint8_t one[4] = {1, 2, 3, 4};
    CHECK(isUniformRgba(one, 1));
    flat[15] = 254;  // the last pixel differs in alpha only
    CHECK(!isUniformRgba(flat, 4));
    uint8_t lit[8] = {0, 0, 0, 255, 0, 1, 0, 255};
    CHECK(!isUniformRgba(lit, 2));
    CHECK(stillLooksEmpty(1, true));    // video layer, nothing came out: refuse
    CHECK(!stillLooksEmpty(0, true));   // a gap is legitimately black
    CHECK(!stillLooksEmpty(3, false));  // real picture
}

// ---- post-export verification: signatures (encode/frame_signature.h), mirrored by FrameSignature.kt ----

std::vector<uint16_t> solidProbe(int r, int g, int b) {
    std::vector<uint16_t> rgb(static_cast<size_t>(kProbeW) * kProbeH * 3);
    for (size_t i = 0; i < rgb.size(); i += 3) {
        rgb[i] = static_cast<uint16_t>(r);
        rgb[i + 1] = static_cast<uint16_t>(g);
        rgb[i + 2] = static_cast<uint16_t>(b);
    }
    return rgb;
}

void signatureOfSolidColoursMatchesTheMatrices() {
    auto black = reduceProbe(solidProbe(0, 0, 0).data(), 255, SigMatrix::Bt709, 3, 100);
    CHECK_EQ(black.frame, 3);
    CHECK_EQ(black.ptsUs, 100);
    CHECK_EQ(black.y.size(), kSigCells);
    CHECK_EQ(black.y[0], 0);
    CHECK_EQ(black.cb[0], 32768);  // chroma of a neutral colour is one half
    CHECK(isFlatSignature(black, 2));
    auto white = reduceProbe(solidProbe(255, 255, 255).data(), 255, SigMatrix::Bt709, 0, 0);
    CHECK_EQ(white.y[17], kSigScale);
    CHECK(std::abs(static_cast<int>(white.cb[17]) - 32768) <= 1);
    // Pure red: Y = Kr, Cr = +0.5 (clamped to the top of the stored range), Cb = -Kr / (2 (1 - Kb)).
    auto red709 = reduceProbe(solidProbe(255, 0, 0).data(), 255, SigMatrix::Bt709, 0, 0);
    CHECK(std::abs(red709.y[5] - 0.2126 * kSigScale) < 2);
    CHECK_EQ(red709.cr[5], kSigScale);
    CHECK(std::abs(red709.cb[5] - (0.5 - 0.2126 / (2 * (1 - 0.0722))) * kSigScale) < 2);
    auto red2020 = reduceProbe(solidProbe(1023, 0, 0).data(), 1023, SigMatrix::Bt2020, 0, 0);
    CHECK(std::abs(red2020.y[5] - 0.2627 * kSigScale) < 2);  // ten-bit values keep their precision
}

void signatureCellsAverageTwoByTwoPixels() {
    auto rgb = solidProbe(0, 0, 0);
    // The top-left cell covers probe pixels (0..1, 0..1): make two of its four pixels white.
    for (int c = 0; c < 3; ++c) {
        rgb[(0 * kProbeW + 0) * 3 + static_cast<size_t>(c)] = 255;
        rgb[(1 * kProbeW + 1) * 3 + static_cast<size_t>(c)] = 255;
    }
    auto sig = reduceProbe(rgb.data(), 255, SigMatrix::Bt709, 0, 0);
    CHECK(std::abs(sig.y[0] - kSigScale / 2) <= 1);
    CHECK_EQ(sig.y[1], 0);
    CHECK_EQ(sig.y[kSigW], 0);  // the row below
    CHECK(!isFlatSignature(sig, 2));
}

void signatureRowsRunTopToBottom() {
    auto rgb = solidProbe(0, 0, 0);
    for (int y = 0; y < kProbeH / 2; ++y) {  // the top half is white
        for (int x = 0; x < kProbeW; ++x) {
            for (int c = 0; c < 3; ++c) rgb[(static_cast<size_t>(y) * kProbeW + static_cast<size_t>(x)) * 3 + static_cast<size_t>(c)] = 255;
        }
    }
    auto sig = reduceProbe(rgb.data(), 255, SigMatrix::Bt709, 0, 0);
    CHECK_EQ(sig.y[0], kSigScale);
    CHECK_EQ(sig.y[kSigCells - 1], 0);
}

void probeFramesAreTheFirstAndTheLast() {
    auto frames = probeFrames(18000, Fps{30, 1});
    CHECK_EQ(frames.size(), 2);
    CHECK_EQ(frames.front(), 0);
    CHECK_EQ(frames.back(), 17999);
}

void probeFramesOfShortMoviesAreWithinRange() {
    auto one = probeFrames(1, Fps{30, 1});
    CHECK_EQ(one.size(), 1);
    CHECK_EQ(one[0], 0);
    auto five = probeFrames(5, Fps{30, 1});
    CHECK_EQ(five.size(), 2);
    CHECK_EQ(five.front(), 0);
    CHECK_EQ(five.back(), 4);
    for (int64_t f : five) CHECK(f >= 0 && f < 5);
    CHECK(probeFrames(0, Fps{30, 1}).empty());
    auto ntsc = probeFrames(100000, Fps{60000, 1001});
    CHECK_EQ(ntsc.back(), 99999);
}

void occlusionCoverageIsConservative() {
    using uv::render::LayerTransform;
    const int cw = 3840, ch = 2160;
    LayerTransform full;  // identity: scale 1, centred, opaque
    CHECK(coversCanvas(cw, ch, 3840, 2160, full));
    CHECK(coversCanvas(cw, ch, 1920, 1080, full));            // same aspect, any size: contain fit reaches the edges
    CHECK(coversCanvas(cw, ch, 2160, 3840, LayerTransform{}) == false);  // portrait frame is letterboxed
    CHECK(coversCanvas(cw, ch, 1920, 1088, full) == false);    // 16:9.06: pillar of a few pixels stays uncovered
    LayerTransform half = full;
    half.scaleX = half.scaleY = 0.472f;
    CHECK(!coversCanvas(cw, ch, 3840, 2160, half));            // split screen
    LayerTransform zoom = full;
    zoom.scaleX = zoom.scaleY = 1.2f;
    CHECK(coversCanvas(cw, ch, 3840, 2160, zoom));
    zoom.posX = 300.0f;  // moved less than the overscan (384 px each side)
    CHECK(coversCanvas(cw, ch, 3840, 2160, zoom));
    zoom.posX = 400.0f;  // moved past it: a sliver of what is beneath shows
    CHECK(!coversCanvas(cw, ch, 3840, 2160, zoom));
    LayerTransform fading = full;
    fading.opacity = 0.999f;
    CHECK(!coversCanvas(cw, ch, 3840, 2160, fading));
    LayerTransform turned = full;
    turned.rotationDeg = 90.0f;
    CHECK(!coversCanvas(cw, ch, 3840, 2160, turned));
    LayerTransform narrow = full;
    narrow.scaleX = 0.9999f;  // 0.4 px short on a 4K canvas is not provable
    CHECK(!coversCanvas(cw, ch, 3840, 2160, narrow));
    CHECK(!coversCanvas(0, ch, 3840, 2160, full));
    CHECK(!coversCanvas(cw, ch, 0, 2160, full));
    // The size the Kotlin side hands over for a 4K canvas and an HLG iPhone clip, with rounding in the transform.
    LayerTransform rounded = full;
    rounded.scaleX = rounded.scaleY = 1.0000001f;
    CHECK(coversCanvas(cw, ch, 3840, 2160, rounded));
}

void occlusionLookAndDuration() {
    uv::core::LayerFx fx;
    CHECK(opaqueLook(fx));
    fx.blend = uv::core::BlendMode::Screen;
    CHECK(!opaqueLook(fx));
    fx = {};
    fx.mask.shape = 2;
    CHECK(!opaqueLook(fx));
    fx = {};
    fx.effects.push_back(uv::core::EffectOp{});
    CHECK(!opaqueLook(fx));
    CHECK_EQ(minCullFrames(60, 1), 120);
    CHECK_EQ(minCullFrames(30000, 1001), 59);
    CHECK_EQ(minCullFrames(0, 1), 120);
}

int main() {
    signatureOfSolidColoursMatchesTheMatrices();
    signatureCellsAverageTwoByTwoPixels();
    signatureRowsRunTopToBottom();
    probeFramesAreTheFirstAndTheLast();
    probeFramesOfShortMoviesAreWithinRange();
    stillGuardRefusesFlatPicturesWithLayers();
    occlusionCoverageIsConservative();
    occlusionLookAndDuration();
    stillAlphaIsForcedOpaque();
    stillCropIsValidatedAgainstTheSurface();
    stillReadbackUsesTheLowerLeftOrigin();
    stillRowsAreFlippedTopFirst();
    lateFramesAreWaitedForAndMissingOnesSubstituted();
    keyframesMatchTheKotlinVectors();
    poseCountsFromTheClipOriginNotTheTransitionStart();
    ptsIsExactForNtscRates();
    ptsDoesNotDriftOverLongTimelines();
    audioSamplesTileWithVideoFrames();
    progressIsClampedAndMonotonic();
    layersAreListedBottomFirst();
    keyframedEffectsPickTheFrameOfTheClip();
    topLayerWinsAndGapsAreEmpty();
    outputFramesMapToProjectFrames();
    sourceFrameMapsAndClamps();
    retimedClipsReadTheirSourceTable();
    smoothSlowMotionEntriesCarryTheirMix();
    reverseWindowIsBoundedByMemory();
    transitionOverlapMatchesTheKotlinPlan();
    sameLayerClipsStackByStartFrame();
    titleClipsCarryTheirKey();
    residencyKeepsWithinBudgetAndEvictsLeastRecentlyUsed();
    residencyNeverEvictsTheFrameInUseNorTheNewPicture();
    sequentialExportOfAHugeAnimationStaysWithinBudget();
    if (failures == 0) std::printf("export host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
