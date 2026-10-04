// Host tests for motion tracking (SPECS.md 9.15): the pure runner (forward, backward, merge, loss and recovery),
// the packed frames the backward run uses, and the analysis cache format. Pure C++; no Android dependencies.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <utility>
#include <vector>

#include "stabilise/gray.h"
#include "track/track_path.h"
#include "track/track_runner.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                      \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                  \
        }                                                                \
    } while (0)

#define CHECK_NEAR(actual, expected, eps)                                                           \
    do {                                                                                            \
        const double a_ = static_cast<double>(actual);                                              \
        const double e_ = static_cast<double>(expected);                                            \
        if (!(std::fabs(a_ - e_) <= (eps))) {                                                       \
            std::printf("FAIL %s:%d: %s = %f, expected %f\n", __FILE__, __LINE__, #actual, a_, e_); \
            ++failures;                                                                             \
        }                                                                                           \
    } while (0)

using namespace uv::track;
using uv::core::Status;
using uv::stab::Gray;

// ---- synthetic scene -----------------------------------------------------------------------------------

uint64_t rng = 12345;
double rand01() {
    rng ^= rng >> 12;
    rng ^= rng << 25;
    rng ^= rng >> 27;
    return static_cast<double>((rng * 2685821657736338717ULL) >> 11) / 9007199254740992.0;
}

// A textured "world": soft blobs and small squares, lots of corners at every scale.
Gray makeWorld(int w, int h) {
    rng = 987654321;
    Gray g(w, h);
    for (float& v : g.px) v = 110.0f;
    for (int i = 0; i < 260; ++i) {
        const double cx = rand01() * w, cy = rand01() * h, r = 3.0 + rand01() * 9.0, amp = (rand01() - 0.5) * 220.0;
        const int x0 = std::max(0, static_cast<int>(cx - 2 * r)), x1 = std::min(w - 1, static_cast<int>(cx + 2 * r));
        const int y0 = std::max(0, static_cast<int>(cy - 2 * r)), y1 = std::min(h - 1, static_cast<int>(cy + 2 * r));
        for (int y = y0; y <= y1; ++y) {
            for (int x = x0; x <= x1; ++x) {
                const double d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (r * r);
                g.ref(x, y) += static_cast<float>(amp * std::exp(-d2));
            }
        }
    }
    for (int i = 0; i < 180; ++i) {
        const int sz = 3 + static_cast<int>(rand01() * 8.0);
        const int x0 = static_cast<int>(rand01() * (w - sz)), y0 = static_cast<int>(rand01() * (h - sz));
        const float amp = static_cast<float>((rand01() - 0.5) * 200.0);
        for (int y = y0; y < y0 + sz; ++y) {
            for (int x = x0; x < x0 + sz; ++x) g.ref(x, y) += amp;
        }
    }
    for (float& v : g.px) v = std::clamp(v, 0.0f, 255.0f);
    return g;
}

constexpr int kFw = 240;
constexpr int kFh = 135;
constexpr int64_t kStepUs = 33333;

// The frame whose top-left corner sees the world at (ox, oy): a camera that pans, so a world point at (wx, wy)
// is at (wx - ox, wy - oy) in the frame.
Gray frameAt(const Gray& world, double ox, double oy) {
    Gray f(kFw, kFh);
    for (int y = 0; y < kFh; ++y) {
        for (int x = 0; x < kFw; ++x) f.ref(x, y) = world.sample(static_cast<float>(x + ox), static_cast<float>(y + oy));
    }
    return f;
}

double camX(int i) { return 20.0 + 1.5 * i; }
double camY(int i) { return 30.0 + 8.0 * std::sin(i * 0.15); }

std::vector<std::pair<int64_t, Gray>> sequence(const Gray& world, int count) {
    std::vector<std::pair<int64_t, Gray>> frames;
    for (int i = 0; i < count; ++i) frames.emplace_back(i * kStepUs, frameAt(world, camX(i), camY(i)));
    return frames;
}

// Where the world point (wx, wy) is, as fractions of the frame, in frame i.
void truth(int i, double wx, double wy, double* u, double* v) {
    *u = (wx - camX(i) + 0.5) / kFw;
    *v = (wy - camY(i) + 0.5) / kFh;
}

double pixelError(const TrackSample& s, int i, double wx, double wy) {
    double u, v;
    truth(i, wx, wy, &u, &v);
    return std::hypot((s.cx - u) * kFw, (s.cy - v) * kFh);
}

// ---- tests ---------------------------------------------------------------------------------------------

void packedFramesRoundTrip() {
    Gray g(16, 8);
    for (size_t i = 0; i < g.px.size(); ++i) g.px[i] = static_cast<float>((i * 7) % 256);
    const PackedFrame p = packFrame(42, g);
    const Gray back = unpackFrame(p);
    CHECK(p.ptsUs == 42);
    CHECK(back.w == 16 && back.h == 8);
    for (size_t i = 0; i < g.px.size(); ++i) CHECK_NEAR(back.px[i], g.px[i], 0.51);
}

void forwardTrackingFollowsThePan() {
    const Gray world = makeWorld(700, 360);
    const auto frames = sequence(world, 40);
    // The target is the world point seen at the middle of frame 0.
    const double wx = camX(0) + kFw * 0.5, wy = camY(0) + kFh * 0.5;
    NormBox box;
    box.cx = 0.5f;
    box.cy = 0.5f;
    box.w = 0.18f;
    box.h = 0.25f;
    TrackRunner runner;
    runner.begin(frames[0].first, frames[0].second, box);
    for (size_t i = 1; i < frames.size(); ++i) runner.step(frames[i].first, frames[i].second);
    const auto& s = runner.samples();
    CHECK(s.size() == frames.size());
    for (size_t i = 0; i < s.size(); ++i) CHECK(!s[i].lost);
    double worst = 0.0;
    for (size_t i = 0; i < s.size(); ++i) worst = std::max(worst, pixelError(s[i], static_cast<int>(i), wx, wy));
    std::printf("forward: worst error %.2f px over %zu frames\n", worst, s.size());
    CHECK(worst < 3.0);
}

void seedInTheMiddleTracksBothWaysInOrder() {
    const Gray world = makeWorld(700, 360);
    const auto frames = sequence(world, 41);
    const size_t seed = 20;
    const double wx = camX(20) + kFw * 0.5, wy = camY(20) + kFh * 0.5;
    NormBox box;
    box.cx = 0.5f;
    box.cy = 0.5f;
    box.w = 0.18f;
    box.h = 0.25f;
    const auto path = trackFrames(frames, seed, box);
    CHECK(path.size() == frames.size());
    for (size_t i = 1; i < path.size(); ++i) CHECK(path[i].ptsUs > path[i - 1].ptsUs);  // chronological, the seed once
    CHECK(path[seed].ptsUs == frames[seed].first);
    CHECK_NEAR(path[seed].confidence, 1.0, 1e-6);
    double worst = 0.0;
    for (size_t i = 0; i < path.size(); ++i) worst = std::max(worst, pixelError(path[i], static_cast<int>(i), wx, wy));
    std::printf("both ways: worst error %.2f px over %zu frames\n", worst, path.size());
    CHECK(worst < 3.0);
}

void lossIsMarkedAndTrackingRecovers() {
    const Gray world = makeWorld(700, 360);
    auto frames = sequence(world, 40);
    // Five blank frames in the middle: nothing to follow.
    for (int i = 18; i < 23; ++i) {
        for (float& v : frames[static_cast<size_t>(i)].second.px) v = 128.0f;
    }
    const double wx = camX(0) + kFw * 0.5, wy = camY(0) + kFh * 0.5;
    NormBox box;
    box.cx = 0.5f;
    box.cy = 0.5f;
    box.w = 0.18f;
    box.h = 0.25f;
    TrackRunner runner;
    runner.begin(frames[0].first, frames[0].second, box);
    for (size_t i = 1; i < frames.size(); ++i) runner.step(frames[i].first, frames[i].second);
    const auto& s = runner.samples();
    int lost = 0;
    for (int i = 18; i < 23; ++i) lost += s[static_cast<size_t>(i)].lost ? 1 : 0;
    std::printf("loss: %d of 5 blank frames marked lost; error after the gap %.2f px\n", lost, pixelError(s[39], 39, wx, wy));
    CHECK(lost >= 4);
    for (int i = 1; i < 17; ++i) CHECK(!s[static_cast<size_t>(i)].lost);
    // The box is held while lost, so it never jumps to a garbage position.
    for (int i = 19; i < 23; ++i) {
        CHECK_NEAR(s[static_cast<size_t>(i)].cx, s[18].cx, 0.02);
        CHECK_NEAR(s[static_cast<size_t>(i)].cy, s[18].cy, 0.02);
    }
    // Afterwards it finds the target again.
    CHECK(pixelError(s[39], 39, wx, wy) < 12.0);
}

void tooSmallBoxIsGrown() {
    const Gray world = makeWorld(700, 360);
    const auto frames = sequence(world, 3);
    NormBox box;
    box.cx = 0.5f;
    box.cy = 0.5f;
    box.w = 0.001f;
    box.h = 0.001f;
    TrackRunner runner;
    runner.begin(frames[0].first, frames[0].second, box);
    CHECK(runner.samples()[0].w * kFw >= kMinBoxPixels - 0.01f);
    CHECK(runner.samples()[0].h * kFh >= kMinBoxPixels - 0.01f);
}

void mergeRunsKeepsTheSeedOnce() {
    std::vector<TrackSample> back(3), fwd(3);
    for (int i = 0; i < 3; ++i) {
        back[static_cast<size_t>(i)].ptsUs = 100 - i * 10;  // 100 (seed), 90, 80
        fwd[static_cast<size_t>(i)].ptsUs = 100 + i * 10;   // 100 (seed), 110, 120
    }
    const auto merged = mergeRuns(back, fwd);
    CHECK(merged.size() == 5);
    const int64_t expected[5] = {80, 90, 100, 110, 120};
    for (size_t i = 0; i < 5 && i < merged.size(); ++i) CHECK(merged[i].ptsUs == expected[i]);
}

void cacheRoundTripAndCorruption() {
    const std::string path = "/tmp/uv_track_test.cache";
    TrackHeader header;
    header.aspect = 1.7777f;
    header.seedUs = 1234567;
    header.rangeStartUs = 1000;
    header.rangeEndUs = 9000000;
    std::vector<TrackSample> samples(5);
    for (size_t i = 0; i < samples.size(); ++i) {
        samples[i].ptsUs = 1000 + static_cast<int64_t>(i) * kStepUs;
        samples[i].cx = 0.1f + 0.1f * static_cast<float>(i);
        samples[i].cy = 0.9f - 0.1f * static_cast<float>(i);
        samples[i].w = 0.2f;
        samples[i].h = 0.3f;
        samples[i].rotation = 0.05f * static_cast<float>(i);
        samples[i].confidence = 0.5f + 0.1f * static_cast<float>(i);
        samples[i].lost = i == 3;
    }
    CHECK(writeTrackCache(path, header, samples) == Status::Ok);
    TrackHeader readHeader;
    std::vector<TrackSample> read;
    CHECK(readTrackCache(path, &readHeader, &read) == Status::Ok);
    CHECK(readHeader.analysisVersion == kTrackAnalysisVersion);
    CHECK_NEAR(readHeader.aspect, 1.7777, 1e-4);
    CHECK(readHeader.seedUs == 1234567);
    CHECK(readHeader.rangeStartUs == 1000 && readHeader.rangeEndUs == 9000000);
    CHECK(read.size() == samples.size());
    for (size_t i = 0; i < read.size() && i < samples.size(); ++i) {
        CHECK(read[i].ptsUs == samples[i].ptsUs);
        CHECK_NEAR(read[i].cx, samples[i].cx, 1e-6);
        CHECK_NEAR(read[i].cy, samples[i].cy, 1e-6);
        CHECK_NEAR(read[i].w, samples[i].w, 1e-6);
        CHECK_NEAR(read[i].confidence, samples[i].confidence, 1e-6);
        CHECK(read[i].lost == samples[i].lost);
    }

    // A flipped byte, a truncated file and a foreign file are all rejected.
    std::FILE* f = std::fopen(path.c_str(), "r+b");
    if (f != nullptr) {
        std::fseek(f, 60, SEEK_SET);
        std::fputc(0x7F, f);
        std::fclose(f);
    }
    CHECK(readTrackCache(path, nullptr, nullptr) == Status::UnsupportedFormat);
    CHECK(writeTrackCache(path, header, samples) == Status::Ok);
    f = std::fopen(path.c_str(), "r+b");
    long size = 0;
    if (f != nullptr) {
        std::fseek(f, 0, SEEK_END);
        size = std::ftell(f);
        std::fclose(f);
    }
    CHECK(size == static_cast<long>(kTrackHeaderBytes + samples.size() * kTrackSampleBytes + 4));
    CHECK(readTrackCache("/tmp/uv_track_does_not_exist", nullptr, nullptr) == Status::IoError);
    std::remove(path.c_str());
}

}  // namespace

int main() {
    packedFramesRoundTrip();
    forwardTrackingFollowsThePan();
    seedInTheMiddleTracksBothWaysInOrder();
    lossIsMarkedAndTrackingRecovers();
    tooSmallBoxIsGrown();
    mergeRunsKeepsTheSeedOnce();
    cacheRoundTripAndCorruption();
    if (failures == 0) std::printf("track host tests passed\n");
    return failures == 0 ? 0 : 1;
}
