// Host tests for encode/export_math.h (no Android dependencies).
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

void topLayerWinsAndGapsAreEmpty() {
    std::vector<VideoClip> clips;
    clips.push_back({0, 100, 0, 1, 1, 0});    // lower track
    clips.push_back({50, 100, 10, 2, 0, 0});  // top track, overlaps frames 50..149
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
    const VideoClip clip{100, 50, 20, 1, 0, 0};
    CHECK_EQ(sourceFrameFor(clip, 100, 1000), 20);
    CHECK_EQ(sourceFrameFor(clip, 149, 1000), 69);
    CHECK_EQ(sourceFrameFor(clip, 149, 60), 59);   // media shorter than the clip range: hold the last frame
    CHECK_EQ(sourceFrameFor(clip, 149, 0), 69);    // unknown length: no clamp
    CHECK_EQ(totalFramesOf({clip}, 0), 150);
    CHECK_EQ(totalFramesOf({clip}, 400), 400);
}

}  // namespace

int main() {
    ptsIsExactForNtscRates();
    ptsDoesNotDriftOverLongTimelines();
    audioSamplesTileWithVideoFrames();
    progressIsClampedAndMonotonic();
    topLayerWinsAndGapsAreEmpty();
    outputFramesMapToProjectFrames();
    sourceFrameMapsAndClamps();
    if (failures == 0) std::printf("export host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
