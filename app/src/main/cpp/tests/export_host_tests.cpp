// Host tests for encode/export_math.h (no Android dependencies).
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "encode/export_math.h"

namespace {

int failures = 0;

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

int main() {
    keyframesMatchTheKotlinVectors();
    poseCountsFromTheClipOriginNotTheTransitionStart();
    ptsIsExactForNtscRates();
    ptsDoesNotDriftOverLongTimelines();
    audioSamplesTileWithVideoFrames();
    progressIsClampedAndMonotonic();
    layersAreListedBottomFirst();
    topLayerWinsAndGapsAreEmpty();
    outputFramesMapToProjectFrames();
    sourceFrameMapsAndClamps();
    transitionOverlapMatchesTheKotlinPlan();
    sameLayerClipsStackByStartFrame();
    titleClipsCarryTheirKey();
    if (failures == 0) std::printf("export host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
