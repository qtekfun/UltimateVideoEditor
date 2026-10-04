// Host tests for the stabiliser (SPECS.md 9.6): similarity estimation, corner detection, Lucas-Kanade and
// box tracking, the motion analyser, path smoothing and cropping, the analysis cache, the table registry
// and the effect wire format. Pure C++; no Android dependencies.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "core/layer_fx.h"
#include "stabilise/gray.h"
#include "stabilise/luma.h"
#include "stabilise/motion_analyser.h"
#include "stabilise/path.h"
#include "stabilise/similarity.h"
#include "stabilise/stab_cache.h"
#include "stabilise/stab_registry.h"
#include "stabilise/stab_warp.h"
#include "stabilise/tracker.h"

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

using namespace uv::stab;
using uv::core::Status;
using uv::decode::Rational;

// ---- synthetic scenes ---------------------------------------------------------------------------------

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

// Frame of size fw x fh seen through the camera `cam`, which maps centred frame coordinates (pixels) to world
// coordinates relative to the world's centre.
Gray renderFrame(const Gray& world, int fw, int fh, const Similarity& cam) {
    Gray f(fw, fh);
    const float cx = 0.5f * static_cast<float>(fw - 1), cy = 0.5f * static_cast<float>(fh - 1);
    const float wx = 0.5f * static_cast<float>(world.w - 1), wy = 0.5f * static_cast<float>(world.h - 1);
    for (int y = 0; y < fh; ++y) {
        for (int x = 0; x < fw; ++x) {
            const Vec2 p = cam.apply({static_cast<float>(x) - cx, static_cast<float>(y) - cy});
            f.ref(x, y) = world.sample(p.x + wx, p.y + wy);
        }
    }
    return f;
}

// ---- similarity ---------------------------------------------------------------------------------------

void similarityComposeAndInverse() {
    const Similarity a = Similarity::fromParams(1.2, 0.3, 4.0, -2.0);
    const Similarity b = Similarity::fromParams(0.9, -0.5, -1.0, 3.0);
    const Vec2 p{5.0f, 7.0f};
    const Vec2 viaCompose = compose(a, b).apply(p);
    const Vec2 viaSteps = a.apply(b.apply(p));
    CHECK_NEAR(viaCompose.x, viaSteps.x, 1e-4);
    CHECK_NEAR(viaCompose.y, viaSteps.y, 1e-4);
    const Vec2 round = a.inverse().apply(a.apply(p));
    CHECK_NEAR(round.x, p.x, 1e-4);
    CHECK_NEAR(round.y, p.y, 1e-4);
    CHECK_NEAR(a.scale(), 1.2, 1e-9);
    CHECK_NEAR(a.angle(), 0.3, 1e-9);
}

void similarityFitIsExactForCleanPoints() {
    const Similarity truth = Similarity::fromParams(1.1, 0.2, 3.0, -4.0);
    std::vector<Vec2> src, dst;
    for (int i = 0; i < 10; ++i) {
        const Vec2 p{static_cast<float>(i * 7 % 23), static_cast<float>(i * 11 % 19)};
        src.push_back(p);
        dst.push_back(truth.apply(p));
    }
    Similarity fit;
    CHECK(fitSimilarity(src.data(), dst.data(), src.size(), &fit));
    CHECK_NEAR(fit.a, truth.a, 1e-4);
    CHECK_NEAR(fit.b, truth.b, 1e-4);
    CHECK_NEAR(fit.tx, truth.tx, 1e-3);
    CHECK_NEAR(fit.ty, truth.ty, 1e-3);
    CHECK(!fitSimilarity(src.data(), dst.data(), 1, &fit));  // one point is not enough
}

void ransacIgnoresOutliers() {
    const Similarity truth = Similarity::fromParams(1.0, 0.05, 6.0, -3.0);
    std::vector<Vec2> src, dst;
    rng = 4242;
    for (int i = 0; i < 100; ++i) {
        const Vec2 p{static_cast<float>(rand01() * 200 - 100), static_cast<float>(rand01() * 120 - 60)};
        src.push_back(p);
        Vec2 q = truth.apply(p);
        if (i % 10 < 3) {  // 30% of the matches are wrong
            q.x += static_cast<float>(rand01() * 40 - 20);
            q.y += static_cast<float>(rand01() * 40 - 20);
        }
        dst.push_back(q);
    }
    RansacResult result;
    CHECK(estimateSimilarityRansac(src, dst, {}, &result));
    CHECK_NEAR(result.model.tx, truth.tx, 0.1);
    CHECK_NEAR(result.model.ty, truth.ty, 0.1);
    CHECK_NEAR(result.model.angle(), 0.05, 0.002);
    CHECK(result.inliers >= 65);
    // Too few points: refuses instead of guessing.
    std::vector<Vec2> few(src.begin(), src.begin() + 4), fewDst(dst.begin(), dst.begin() + 4);
    CHECK(!estimateSimilarityRansac(few, fewDst, {}, &result));
}

void ransacIsDeterministic() {
    std::vector<Vec2> src, dst;
    rng = 77;
    const Similarity truth = Similarity::fromParams(1.0, 0.0, 2.0, 1.0);
    for (int i = 0; i < 40; ++i) {
        const Vec2 p{static_cast<float>(rand01() * 100), static_cast<float>(rand01() * 100)};
        src.push_back(p);
        dst.push_back(truth.apply(p));
    }
    RansacResult a, b;
    CHECK(estimateSimilarityRansac(src, dst, {}, &a));
    CHECK(estimateSimilarityRansac(src, dst, {}, &b));
    CHECK(a.model.tx == b.model.tx && a.model.ty == b.model.ty && a.inliers == b.inliers);
}

// ---- corners, Lucas-Kanade and the box tracker ---------------------------------------------------------

void cornersAreFoundOnTexture() {
    const Gray world = makeWorld(320, 240);
    CornerOptions options;
    options.maxCorners = 200;
    const std::vector<Vec2> corners = detectCorners(world, options);
    CHECK(corners.size() >= 60);
    CHECK(corners.size() <= 200);
    for (const Vec2& c : corners) {
        CHECK(c.x >= 0 && c.x < 320 && c.y >= 0 && c.y < 240);
    }
    // Spread: at least three quarters of the grid cells hold a corner.
    std::vector<int> cells(8 * 6, 0);
    for (const Vec2& c : corners) ++cells[static_cast<size_t>(std::min(5, static_cast<int>(c.y) * 6 / 240) * 8 + std::min(7, static_cast<int>(c.x) * 8 / 320))];
    int filled = 0;
    for (int n : cells) filled += n > 0 ? 1 : 0;
    CHECK(filled >= 36);
    // A flat image has none.
    CHECK(detectCorners(Gray(64, 64), options).empty());
    // A region of interest keeps them inside it.
    const IntRect roi{100, 80, 200, 160};
    for (const Vec2& c : detectCorners(world, options, &roi)) {
        CHECK(c.x >= 100 && c.x < 200 && c.y >= 80 && c.y < 160);
    }
}

void lucasKanadeRecoversAShift() {
    const Gray world = makeWorld(400, 300);
    const Gray a = renderFrame(world, 240, 135, Similarity::fromParams(1.0, 0.0, 0.0, 0.0));
    const Gray b = renderFrame(world, 240, 135, Similarity::fromParams(1.0, 0.0, 3.2, -1.7));
    // The camera moved by (3.2, -1.7) in world space, so picture content moves by (-3.2, +1.7).
    const std::vector<Gray> pa = buildPyramid(a, 3), pb = buildPyramid(b, 3);
    const std::vector<Vec2> corners = detectCorners(a, {});
    std::vector<Vec2> tracked;
    std::vector<uint8_t> ok;
    trackLk(pa, pb, corners, &tracked, &ok);
    int good = 0;
    double sx = 0, sy = 0;
    for (size_t i = 0; i < corners.size(); ++i) {
        if (!ok[i]) continue;
        ++good;
        sx += tracked[i].x - corners[i].x;
        sy += tracked[i].y - corners[i].y;
    }
    CHECK(good >= 40);
    CHECK_NEAR(sx / good, -3.2, 0.1);
    CHECK_NEAR(sy / good, 1.7, 0.1);
    Vec2 one;
    CHECK(trackPoint(pa, pb, corners[corners.size() / 2], &one));
    CHECK_NEAR(one.x - corners[corners.size() / 2].x, -3.2, 0.2);
}

void lucasKanadeRejectsFlatPoints() {
    const Gray flat(64, 64);
    const std::vector<Gray> p = buildPyramid(flat, 3);
    Vec2 out;
    CHECK(!trackPoint(p, p, {32.0f, 32.0f}, &out));
}

void boxTrackerFollowsAPatch() {
    const Gray world = makeWorld(500, 300);
    // The camera pans right by 2 px per frame, so the picture (and the box on it) moves left by 2 px.
    Gray first = renderFrame(world, 240, 135, Similarity::fromParams(1.0, 0.0, 0.0, 0.0));
    BoxTracker tracker(first, {120.0f, 67.0f, 60.0f, 40.0f, 0.0f});
    float confidence = 0.0f;
    for (int t = 1; t <= 20; ++t) {
        const Gray f = renderFrame(world, 240, 135, Similarity::fromParams(1.0, 0.0, 2.0 * t, 0.0));
        confidence = tracker.update(f);
        CHECK(confidence > 0.3f);
    }
    CHECK_NEAR(tracker.box().cx, 120.0 - 40.0, 1.5);
    CHECK_NEAR(tracker.box().cy, 67.0, 1.5);
    CHECK_NEAR(tracker.box().w, 60.0, 3.0);
    // A frame of another size is ignored.
    CHECK(tracker.update(Gray(10, 10)) == 0.0f);
}

// ---- motion analyser ----------------------------------------------------------------------------------

// The camera path used by the analyser tests: a slow drift plus jitter, with a little roll.
Similarity cameraAt(int t) {
    const double jx = 2.5 * std::sin(t * 1.7) + 1.5 * std::sin(t * 0.9 + 1.0);
    const double jy = 2.0 * std::sin(t * 2.3 + 0.4);
    const double roll = 0.012 * std::sin(t * 1.3);
    return Similarity::fromParams(1.0, roll, 0.8 * t + jx, jy);
}

void analyserRecoversCameraMotion() {
    const int fw = 240, fh = 135;
    const Gray world = makeWorld(700, 360);
    MotionAnalyser analyser;
    Similarity previous;
    for (int t = 0; t < 14; ++t) {
        const Similarity cam = cameraAt(t);
        const FrameMotion m = analyser.feed(renderFrame(world, fw, fh, cam));
        CHECK(m.valid);
        if (t == 0) {
            CHECK(m.tx == 0.0 && m.ty == 0.0 && m.theta == 0.0);
            previous = cam;
            continue;
        }
        // Picture motion from t-1 to t is cam_t^-1 o cam_{t-1}, in height units.
        const Similarity expected = compose(cam.inverse(), previous);
        CHECK_NEAR(m.tx, expected.tx / fh, 0.004);
        CHECK_NEAR(m.ty, expected.ty / fh, 0.004);
        CHECK_NEAR(m.theta, expected.angle(), 0.004);
        CHECK(m.quality > 0.15f);
        previous = cam;
    }
}

void analyserFollowsLargeJumps() {
    // A violent shake: the picture jumps by about 20 px (8% of the width) between frames on a 240 px wide frame.
    const int fw = 240, fh = 135;
    const Gray world = makeWorld(900, 420);
    const auto cam = [](int t) { return Similarity::fromParams(1.0, 0.0, (t % 2 == 0 ? 1.0 : -1.0) * 9.0 + 3.0 * t, (t % 3 == 0 ? 4.0 : -4.0)); };
    MotionAnalyser analyser;
    Similarity previous;
    int valid = 0, frames = 0;
    for (int t = 0; t < 10; ++t) {
        const Similarity c = cam(t);
        const FrameMotion m = analyser.feed(renderFrame(world, fw, fh, c));
        if (t > 0) {
            ++frames;
            const Similarity expected = compose(c.inverse(), previous);
            if (m.valid && m.quality > 0.0f) {
                ++valid;
                CHECK_NEAR(m.tx, expected.tx / fh, 0.01);
                CHECK_NEAR(m.ty, expected.ty / fh, 0.01);
            }
        }
        previous = c;
    }
    CHECK(valid >= frames - 1);  // the deeper retry finds the motion in (nearly) every frame
}

void analyserCopesWithAMovingObject() {
    const int fw = 240, fh = 135;
    const Gray world = makeWorld(700, 360);
    MotionAnalyser analyser;
    for (int t = 0; t < 8; ++t) {
        const Similarity cam = cameraAt(t);
        Gray frame = renderFrame(world, fw, fh, cam);
        // A bright square crossing the frame: about 8% of the picture, moving on its own.
        const int ox = 20 + t * 9, oy = 40;
        for (int y = oy; y < oy + 34; ++y) {
            for (int x = ox; x < ox + 34 && x < fw; ++x) frame.ref(x, y) = 235.0f;
        }
        const FrameMotion m = analyser.feed(frame);
        if (t == 0) continue;
        const Similarity expected = compose(cam.inverse(), cameraAt(t - 1));
        CHECK_NEAR(m.tx, expected.tx / fh, 0.01);
        CHECK_NEAR(m.ty, expected.ty / fh, 0.01);
    }
}

void analyserDoesNotLoseMotionAcrossAFailedFrame() {
    // The picture slides 3 px per frame; frame 3 is blank (nothing to track). The motion across the gap must still
    // be counted once, in the next frame, so the sum over the clip equals the real total.
    const int fw = 240, fh = 135;
    const Gray world = makeWorld(700, 360);
    MotionAnalyser analyser;
    double sumTx = 0;
    int invalid = 0;
    for (int t = 0; t < 8; ++t) {
        Gray frame = t == 3 ? Gray(fw, fh) : renderFrame(world, fw, fh, Similarity::fromParams(1.0, 0.0, 3.0 * t, 0.0));
        const FrameMotion m = analyser.feed(frame);
        if (!m.valid) ++invalid;
        sumTx += m.tx * fh;
    }
    CHECK(invalid >= 1);
    CHECK_NEAR(sumTx, -21.0, 0.6);  // 7 frames x 3 px, the picture moving left
}

void analyserOnATexturelessFrameReportsNoMotion() {
    MotionAnalyser analyser;
    analyser.feed(Gray(160, 90));
    const FrameMotion m = analyser.feed(Gray(160, 90));
    CHECK(!m.valid);
    CHECK(m.tx == 0.0 && m.ty == 0.0 && m.theta == 0.0 && m.logScale == 0.0);
    // A change of frame size restarts instead of comparing unlike frames.
    CHECK(analyser.feed(Gray(80, 45)).valid);
}

// ---- path, smoothing, crop -----------------------------------------------------------------------------

// Samples (at `samplesPerSecond`) of a camera whose view jitters around a slow pan, in height units.
std::vector<MotionSample> jitteryCamera(int frames, double fps, Similarity* truthAt0 = nullptr) {
    (void)truthAt0;
    std::vector<MotionSample> samples;
    Similarity previous;
    for (int t = 0; t < frames; ++t) {
        const double jx = 0.012 * std::sin(t * 1.9) + 0.008 * std::sin(t * 0.7);
        const double jy = 0.010 * std::sin(t * 2.4 + 0.3);
        const double roll = 0.01 * std::sin(t * 1.5);
        const Similarity cam = Similarity::fromParams(1.0, roll, 0.002 * t + jx, jy);
        MotionSample s;
        s.ptsUs = static_cast<int64_t>(std::llround(t * 1e6 / fps));
        s.motion.valid = true;
        s.motion.quality = 1.0f;
        if (t > 0) {
            const Similarity m = compose(cam.inverse(), previous);
            s.motion.tx = m.tx;
            s.motion.ty = m.ty;
            s.motion.theta = m.angle();
            s.motion.logScale = std::log(m.scale());
        }
        samples.push_back(s);
        previous = cam;
    }
    return samples;
}

// Sum of squared second differences of a position sequence: a measure of jitter that ignores steady motion.
double jitter(const std::vector<Vec2>& positions) {
    double total = 0.0;
    for (size_t i = 1; i + 1 < positions.size(); ++i) {
        const double ddx = positions[i + 1].x - 2.0 * positions[i].x + positions[i - 1].x;
        const double ddy = positions[i + 1].y - 2.0 * positions[i].y + positions[i - 1].y;
        total += ddx * ddx + ddy * ddy;
    }
    return total;
}

void smoothingReducesJitterByAFactor() {
    const Rational fps{30, 1};
    const std::vector<MotionSample> samples = jitteryCamera(240, 30.0);
    const std::vector<Similarity> path = cumulativePath(samples);
    const StabTable table = buildTable(samples, fps, 16.0 / 9.0, 0.002f, CropLevel::Full);  // the lightest smoothing
    CHECK(table.frames() == samples.size());
    CHECK(table.firstFrame == 0);

    // Follow a point 0.25 height units right of the centre, as the viewer sees it with and without the correction.
    std::vector<Vec2> raw, stabilised;
    for (size_t t = 0; t < samples.size(); ++t) {
        const Vec2 r = path[t].apply({0.25f, 0.1f});
        raw.push_back(r);
        float c[4];
        CHECK(table.lookup(static_cast<int64_t>(t), c));
        stabilised.push_back(uv::stab::stabilisedPosition(c, r));
    }
    const double before = jitter(raw), after = jitter(stabilised);
    CHECK(before > 0.0);
    CHECK(after < 0.01 * before);  // two orders of magnitude steadier even at the lightest strength
}

void strengthZeroAndShortInputGiveIdentity() {
    const Rational fps{30, 1};
    const std::vector<MotionSample> samples = jitteryCamera(60, 30.0);
    const StabTable off = buildTable(samples, fps, 16.0 / 9.0, 0.0f, CropLevel::Tight);
    CHECK(off.frames() == 60);
    for (size_t i = 0; i < off.data.size(); i += 4) {
        CHECK(off.data[i] == 0.0f && off.data[i + 1] == 0.0f && off.data[i + 2] == 0.0f && off.data[i + 3] == 1.0f);
    }
    CHECK(buildTable({}, fps, 16.0 / 9.0, 0.5f, CropLevel::Tight).frames() == 0);
    float c[4];
    CHECK(!StabTable{}.lookup(0, c));
    // A single sample has nothing to smooth.
    const StabTable one = buildTable({samples[0]}, fps, 16.0 / 9.0, 0.5f, CropLevel::Tight);
    CHECK(one.frames() == 1);
    CHECK(one.lookup(5, c) && c[3] == 1.0f);
}

void strongerSmoothingIsSteadier() {
    const Rational fps{30, 1};
    const std::vector<MotionSample> samples = jitteryCamera(240, 30.0);
    const std::vector<Similarity> path = cumulativePath(samples);
    double previous = 1e30;
    for (float strength : {0.002f, 0.006f, 0.012f}) {
        const StabTable table = buildTable(samples, fps, 16.0 / 9.0, strength, CropLevel::Full);
        std::vector<Vec2> out;
        for (size_t t = 0; t < samples.size(); ++t) {
            float c[4];
            table.lookup(static_cast<int64_t>(t), c);
            out.push_back(uv::stab::stabilisedPosition(c, path[t].apply({0.2f, 0.0f})));
        }
        const double j = jitter(out);
        CHECK(j < 0.8 * previous);  // a clear step each time, not rounding noise
        previous = j;
    }
}

void cropLevelsOrderTheZoom() {
    const Rational fps{30, 1};
    const std::vector<MotionSample> samples = jitteryCamera(240, 30.0);
    const double aspect = 16.0 / 9.0;
    const auto scaleOf = [&](CropLevel level) {
        const StabTable t = buildTable(samples, fps, aspect, 0.6f, level);
        double sum = 0.0;
        for (size_t i = 3; i < t.data.size(); i += 4) sum += t.data[i];
        return sum / static_cast<double>(t.frames());
    };
    const double full = scaleOf(CropLevel::Full), medium = scaleOf(CropLevel::Medium), tight = scaleOf(CropLevel::Tight);
    CHECK(full < medium && medium < tight);
    CHECK_NEAR(full, 1.0, 0.02);  // no zoom beyond the path's own scale wobble
    CHECK(tight > 1.02);
    CHECK(tight <= 2.05);
}

void tightCropCoversTheViewport() {
    const Rational fps{30, 1};
    const std::vector<MotionSample> samples = jitteryCamera(240, 30.0);
    const double aspect = 16.0 / 9.0;
    const StabTable table = buildTable(samples, fps, aspect, 0.6f, CropLevel::Tight);
    // Every viewport corner must read inside the raw frame (a tiny tolerance for float rounding).
    int outside = 0;
    for (size_t t = 0; t < table.frames(); ++t) {
        float c[4];
        table.lookup(static_cast<int64_t>(t), c);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                const Vec2 src = uv::stab::stabilisedSourcePosition(c, {static_cast<float>(sx * aspect * 0.5), static_cast<float>(sy * 0.5)});
                if (std::fabs(src.x) > aspect * 0.5 + 1e-3 || std::fabs(src.y) > 0.5 + 1e-3) ++outside;
            }
        }
    }
    CHECK(outside == 0);
}

void requiredZoomMatchesTheClosedForm() {
    const double aspect = 16.0 / 9.0;
    CHECK_NEAR(requiredZoom(Similarity{}, aspect), 1.0, 1e-9);
    // A pure shift of dx (height units): u = 1 - 2 dx / aspect, zoom = 1 / u.
    CHECK_NEAR(requiredZoom(Similarity::fromParams(1.0, 0.0, 0.2, 0.0), aspect), 1.0 / (1.0 - 2.0 * 0.2 / aspect), 1e-6);
    CHECK_NEAR(requiredZoom(Similarity::fromParams(1.0, 0.0, 0.0, -0.1), aspect), 1.0 / (1.0 - 2.0 * 0.1 / 1.0), 1e-6);
    // The correction already scales the picture up: no extra zoom.
    CHECK_NEAR(requiredZoom(Similarity::fromParams(1.5, 0.0, 0.0, 0.0), aspect), 1.0, 1e-9);
    // A huge shift cannot be hidden by any zoom: capped.
    CHECK_NEAR(requiredZoom(Similarity::fromParams(1.0, 0.0, 5.0, 0.0), aspect), 2.0, 1e-9);
    // Rotation needs zoom even with no shift.
    CHECK(requiredZoom(Similarity::fromParams(1.0, 0.1, 0.0, 0.0), aspect) > 1.05);
}

void framesAreNumberedAtTheProjectRate() {
    // Samples every 1/60 s resampled to a 30 fps project: every other sample's time is a frame.
    const std::vector<MotionSample> samples = jitteryCamera(121, 60.0);
    const StabTable table = buildTable(samples, Rational{30, 1}, 16.0 / 9.0, 0.5f, CropLevel::Full);
    CHECK(table.firstFrame == 0);
    CHECK(table.frames() == 61);
    // A first sample that does not sit at time zero shifts the table.
    std::vector<MotionSample> shifted = jitteryCamera(30, 30.0);
    for (MotionSample& s : shifted) s.ptsUs += 2000000;
    CHECK(buildTable(shifted, Rational{30, 1}, 16.0 / 9.0, 0.5f, CropLevel::Full).firstFrame == 60);
    // Lookups outside the range clamp to the ends.
    const StabTable t = buildTable(shifted, Rational{30, 1}, 16.0 / 9.0, 0.5f, CropLevel::Full);
    float a[4], b[4], c[4];
    CHECK(t.lookup(0, a) && t.lookup(60, b) && t.lookup(-5, c));
    CHECK(a[0] == b[0] && a[2] == b[2] && c[0] == b[0]);
}

void rotationIsUnwrapped() {
    // A camera that rolls steadily past +-pi must not produce a jump in the smoothed path.
    std::vector<MotionSample> samples;
    for (int t = 0; t < 120; ++t) {
        MotionSample s;
        s.ptsUs = t * 33333;
        s.motion.valid = true;
        s.motion.theta = t == 0 ? 0.0 : 0.05;  // 6 radians over the clip
        samples.push_back(s);
    }
    const StabTable table = buildTable(samples, Rational{30, 1}, 16.0 / 9.0, 0.5f, CropLevel::Full);
    for (size_t i = 0; i < table.frames(); ++i) {
        CHECK(std::fabs(table.data[i * 4 + 2]) < 0.2);  // a steady roll is a smooth path: tiny correction
    }
}

void mirroredEndsDoNotPullThePath() {
    // A steady drift smoothed with a long filter stays steady up to the last frame (no lag at the ends).
    std::vector<PathParams> path(100);
    for (size_t i = 0; i < path.size(); ++i) path[i].tx = 0.01 * static_cast<double>(i);
    const std::vector<PathParams> smooth = smoothParams(path, 12.0);
    CHECK_NEAR(smooth.front().tx, path.front().tx, 1e-6);
    CHECK_NEAR(smooth.back().tx, path.back().tx, 1e-6);
    CHECK_NEAR(smooth[50].tx, path[50].tx, 1e-6);
    CHECK(smoothParams(path, 0.5).front().tx == path.front().tx);
}

void sigmaGrowsWithStrength() {
    const Rational fps{30, 1};
    CHECK(sigmaFramesFor(0.0f, fps) == 0.0);
    CHECK(sigmaFramesFor(0.2f, fps) < sigmaFramesFor(0.5f, fps));
    CHECK(sigmaFramesFor(0.5f, fps) < sigmaFramesFor(1.0f, fps));
    CHECK(sigmaFramesFor(2.0f, fps) == sigmaFramesFor(1.0f, fps));  // clamped
    CHECK(sigmaFramesFor(0.5f, Rational{60, 1}) > sigmaFramesFor(0.5f, fps));  // the same time span, more frames
}

void warpMappingsAreInverse() {
    const float c[4] = {0.03f, -0.02f, 0.05f, 1.1f};
    for (const Vec2 raw : {Vec2{0.0f, 0.0f}, Vec2{0.4f, 0.2f}, Vec2{-0.7f, 0.5f}}) {
        const Vec2 out = uv::stab::stabilisedPosition(c, raw);
        const Vec2 back = uv::stab::stabilisedSourcePosition(c, out);
        CHECK_NEAR(back.x, raw.x, 1e-5);
        CHECK_NEAR(back.y, raw.y, 1e-5);
    }
    // Identity correction maps points to themselves.
    const float id[4] = {0, 0, 0, 1};
    const Vec2 p = uv::stab::stabilisedSourcePosition(id, {0.3f, -0.2f});
    CHECK_NEAR(p.x, 0.3, 1e-6);
    CHECK_NEAR(p.y, -0.2, 1e-6);
}

// ---- cache ----------------------------------------------------------------------------------------------

std::string tempPath(const char* name) { return std::string("/tmp/uv_stab_test_") + name; }

void cacheRoundTrips() {
    const std::vector<MotionSample> samples = jitteryCamera(50, 30.0);
    CacheHeader header;
    header.aspect = 1.7777f;
    header.analysisWidth = 480;
    header.analysisHeight = 270;
    header.rangeStartUs = 0;
    header.rangeEndUs = samples.back().ptsUs;
    const std::string path = tempPath("roundtrip.bin");
    CHECK(writeCache(path, header, samples) == Status::Ok);

    CacheHeader read;
    std::vector<MotionSample> back;
    CHECK(readCache(path, &read, &back) == Status::Ok);
    CHECK(read.analysisVersion == kAnalysisVersion);
    CHECK_NEAR(read.aspect, 1.7777, 1e-6);
    CHECK(read.analysisWidth == 480 && read.analysisHeight == 270);
    CHECK(read.rangeStartUs == 0 && read.rangeEndUs == samples.back().ptsUs);
    CHECK(back.size() == samples.size());
    for (size_t i = 0; i < back.size(); ++i) {
        CHECK(back[i].ptsUs == samples[i].ptsUs);
        CHECK_NEAR(back[i].motion.tx, samples[i].motion.tx, 1e-6);
        CHECK_NEAR(back[i].motion.theta, samples[i].motion.theta, 1e-6);
    }
    std::remove(path.c_str());
    CHECK(readCache(path, &read, &back) == Status::IoError);  // gone
}

void cacheRejectsDamage() {
    const std::vector<MotionSample> samples = jitteryCamera(20, 30.0);
    CacheHeader header;
    header.rangeEndUs = samples.back().ptsUs;
    const std::string path = tempPath("damage.bin");
    CHECK(writeCache(path, header, samples) == Status::Ok);

    std::vector<uint8_t> bytes;
    {
        std::FILE* f = std::fopen(path.c_str(), "rb");
        CHECK(f != nullptr);
        uint8_t chunk[4096];
        size_t n;
        while ((n = std::fread(chunk, 1, sizeof(chunk), f)) > 0) bytes.insert(bytes.end(), chunk, chunk + n);
        std::fclose(f);
    }
    CHECK(bytes.size() == kCacheHeaderBytes + samples.size() * kCacheSampleBytes + 4);
    const auto writeBytes = [&](const std::vector<uint8_t>& data) {
        std::FILE* f = std::fopen(path.c_str(), "wb");
        std::fwrite(data.data(), 1, data.size(), f);
        std::fclose(f);
    };
    CacheHeader read;
    std::vector<MotionSample> back;

    std::vector<uint8_t> flipped = bytes;
    flipped[100] ^= 0x40;  // a payload bit: the checksum catches it
    writeBytes(flipped);
    CHECK(readCache(path, &read, &back) == Status::UnsupportedFormat);

    std::vector<uint8_t> truncated(bytes.begin(), bytes.end() - 10);
    writeBytes(truncated);
    CHECK(readCache(path, &read, &back) == Status::UnsupportedFormat);

    std::vector<uint8_t> badMagic = bytes;
    badMagic[0] = 'X';
    writeBytes(badMagic);
    CHECK(readCache(path, &read, &back) == Status::UnsupportedFormat);

    writeBytes({});
    CHECK(readCache(path, &read, &back) == Status::UnsupportedFormat);
    std::remove(path.c_str());
}

void cacheKeyChangesWithTheFile() {
    // The CRC differs for different content, which is what makes a replaced analysis detectable.
    const uint8_t a[] = {1, 2, 3, 4}, b[] = {1, 2, 3, 5};
    CHECK(crc32(a, 4) != crc32(b, 4));
    CHECK(crc32(a, 4) == crc32(a, 4));
    const uint8_t check[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
    CHECK(crc32(check, 9) == 0xCBF43926u);  // the standard CRC-32 check value
}

// ---- registry and effect wire -----------------------------------------------------------------------------

void registryResolvesPerFrameValues() {
    StabRegistry& registry = StabRegistry::instance();
    registry.clear();
    const uint64_t before = registry.revision();
    StabTable table;
    table.firstFrame = 10;
    for (int i = 0; i < 5; ++i) {
        table.data.insert(table.data.end(), {0.01f * static_cast<float>(i), -0.02f, 0.03f, 1.0f + 0.1f * static_cast<float>(i)});
    }
    registry.put(7, table);
    CHECK(registry.revision() > before);
    CHECK(registry.has(7) && !registry.has(8));

    uv::core::LayerFx fx;
    uv::core::EffectOp stab;
    stab.type = uv::core::EffectType::Stabilise;
    stab.v[0] = 7;
    stab.v[1] = 1;
    uv::core::EffectOp other;
    other.type = uv::core::EffectType::Brightness;
    other.v[0] = 0.2f;
    fx.effects = {stab, other};
    resolveStabilisation(&fx, 12);
    CHECK(fx.effects.size() == 2);
    CHECK_NEAR(fx.effects[0].v[2], 0.02, 1e-6);
    CHECK_NEAR(fx.effects[0].v[3], -0.02, 1e-6);
    CHECK_NEAR(fx.effects[0].v[4], 0.03, 1e-6);
    CHECK_NEAR(fx.effects[0].v[5], 1.2, 1e-6);
    CHECK(fx.effects[1].v[0] == 0.2f);  // other effects are untouched
    // Outside the table the ends hold.
    uv::core::LayerFx early;
    early.effects = {stab};
    resolveStabilisation(&early, 0);
    CHECK_NEAR(early.effects[0].v[5], 1.0, 1e-6);
    uv::core::LayerFx late;
    late.effects = {stab};
    resolveStabilisation(&late, 500);
    CHECK_NEAR(late.effects[0].v[5], 1.4, 1e-6);

    // An unregistered key drops the effect so the layer draws as it is.
    uv::core::LayerFx missing;
    missing.effects = {other, stab};
    missing.effects[1].v[0] = 99;
    resolveStabilisation(&missing, 12);
    CHECK(missing.effects.size() == 1 && missing.effects[0].type == uv::core::EffectType::Brightness);

    registry.remove(7);
    CHECK(!registry.has(7));
    registry.clear();
}

void registryBuildsFromACacheFile() {
    StabRegistry::instance().clear();
    const std::vector<MotionSample> samples = jitteryCamera(90, 30.0);
    CacheHeader header;
    header.rangeEndUs = samples.back().ptsUs;
    const std::string path = tempPath("registry.bin");
    CHECK(writeCache(path, header, samples) == Status::Ok);
    CHECK(registerFromCache(5, path, Rational{30, 1}, 0.5f, CropLevel::Medium) == Status::Ok);
    float c[4];
    CHECK(StabRegistry::instance().lookup(5, 40, c));
    CHECK(c[3] > 0.9f);
    CHECK(registerFromCache(0, path, Rational{30, 1}, 0.5f, CropLevel::Medium) == Status::InvalidArgument);  // key 0 is reserved
    CHECK(registerFromCache(6, path, Rational{0, 1}, 0.5f, CropLevel::Medium) == Status::InvalidArgument);
    CHECK(registerFromCache(6, tempPath("missing.bin"), Rational{30, 1}, 0.5f, CropLevel::Medium) == Status::IoError);
    CHECK(!StabRegistry::instance().has(6));
    std::remove(path.c_str());
    StabRegistry::instance().clear();
}

void effectWireAcceptsTheStabiliseEffect() {
    using namespace uv::core;
    // A layer with the maximum user effects plus the stabiliser: header(9) then (type, count, values) per effect.
    std::vector<double> wire = {0, 0, 0, 0, 1, 1, 0, 0, static_cast<double>(kMaxEffectsPerLayer + 1)};
    wire.insert(wire.end(), {15, 6, 7, 1, 0, 0, 0, 1});  // stabilise: key 7, edge 1, placeholders
    for (int i = 0; i < kMaxEffectsPerLayer; ++i) wire.insert(wire.end(), {1, 1, 0.1});
    LayerFx fx;
    size_t offset = 0;
    CHECK(parseLayerFx(wire.data(), wire.size(), &offset, &fx));
    CHECK(offset == wire.size());
    CHECK(fx.effects.size() == static_cast<size_t>(kMaxEffectsPerLayer + 1));
    CHECK(fx.effects[0].type == EffectType::Stabilise);
    CHECK(fx.effects[0].v[0] == 7.0f && fx.effects[0].v[1] == 1.0f && fx.effects[0].v[5] == 1.0f);
    CHECK(!fx.neutral());
    // One more than that is still rejected.
    std::vector<double> tooMany = {0, 0, 0, 0, 1, 1, 0, 0, static_cast<double>(kMaxWireEffectsPerLayer + 1)};
    for (int i = 0; i < kMaxWireEffectsPerLayer + 1; ++i) tooMany.insert(tooMany.end(), {1, 1, 0.1});
    CHECK(!parseLayerFx(tooMany.data(), tooMany.size(), &offset, &fx));
    // Unknown type 18 is rejected (16 and 17 are the repair effects).
    std::vector<double> unknown = {0, 0, 0, 0, 1, 1, 0, 0, 1, 18, 1, 0};
    offset = 0;
    CHECK(!parseLayerFx(unknown.data(), unknown.size(), &offset, &fx));
}

// ---- luma extraction ---------------------------------------------------------------------------------------

void lumaIsUprightCroppedAndScaled() {
    using uv::thumb::YuvFrame;
    // A 64x48 plane with 16 bytes of row padding, visible area 60x40 at (2, 4); one bright pixel at plane (10, 9).
    const int stride = 80;
    std::vector<uint8_t> plane(static_cast<size_t>(stride) * 48, 20);
    plane[static_cast<size_t>(9) * stride + 10] = 200;
    YuvFrame f;
    f.y = plane.data();
    f.yRowStride = stride;
    f.cropLeft = 2;
    f.cropTop = 4;
    f.cropWidth = 60;
    f.cropHeight = 40;
    // Visible coordinates of the bright pixel: (8, 5). No scaling is needed at this size: the output is 60x40.
    Gray g;
    CHECK(lumaToGray(f, 0, 480, &g));
    CHECK(g.w == 60 && g.h == 40);
    CHECK_NEAR(g.at(8, 5), 200.0, 1e-3);
    CHECK_NEAR(g.at(9, 5), 20.0, 1e-3);
    // 90 degrees clockwise: the picture is 40x60 and (x, y) goes to (h-1-y, x) = (34, 8).
    CHECK(lumaToGray(f, 90, 480, &g));
    CHECK(g.w == 40 && g.h == 60);
    CHECK_NEAR(g.at(34, 8), 200.0, 1e-3);
    CHECK(lumaToGray(f, 180, 480, &g));
    CHECK(g.w == 60 && g.h == 40);
    CHECK_NEAR(g.at(51, 34), 200.0, 1e-3);
    CHECK(lumaToGray(f, 270, 480, &g));
    CHECK(g.w == 40 && g.h == 60);
    CHECK_NEAR(g.at(5, 51), 200.0, 1e-3);
    CHECK(lumaToGray(f, -90, 480, &g) && g.w == 40);  // negative angles wrap
    CHECK(!lumaToGray(f, 45, 480, &g));                // only quarter turns
    CHECK(!lumaToGray(f, 0, 8, &g));                   // a target too small to analyse

    // Scaling down averages: a uniform 400x300 frame becomes 100x75 and stays uniform.
    std::vector<uint8_t> big(static_cast<size_t>(400) * 300, 90);
    YuvFrame b;
    b.y = big.data();
    b.yRowStride = 400;
    b.cropWidth = 400;
    b.cropHeight = 300;
    CHECK(lumaToGray(b, 0, 100, &g));
    CHECK(g.w == 100 && g.h == 75);
    CHECK_NEAR(g.at(50, 40), 90.0, 1e-3);
    // The same frame is never enlarged.
    CHECK(lumaToGray(b, 0, 2000, &g) && g.w == 400 && g.h == 300);

    // 10-bit samples (P010): the high byte of each little-endian word is the luma.
    std::vector<uint8_t> p010(static_cast<size_t>(32) * 2 * 24, 0);
    for (size_t i = 0; i < p010.size(); i += 2) p010[i + 1] = 77;
    YuvFrame w;
    w.y = p010.data();
    w.yRowStride = 64;
    w.sampleBytes = 2;
    w.cropWidth = 32;
    w.cropHeight = 24;
    CHECK(lumaToGray(w, 0, 480, &g));
    CHECK_NEAR(g.at(5, 5), 77.0, 1e-3);
    // Unusable frames are refused.
    YuvFrame none;
    CHECK(!lumaToGray(none, 0, 480, &g));
}

}  // namespace

int main() {
    similarityComposeAndInverse();
    similarityFitIsExactForCleanPoints();
    ransacIgnoresOutliers();
    ransacIsDeterministic();
    cornersAreFoundOnTexture();
    lucasKanadeRecoversAShift();
    lucasKanadeRejectsFlatPoints();
    boxTrackerFollowsAPatch();
    analyserRecoversCameraMotion();
    analyserFollowsLargeJumps();
    analyserCopesWithAMovingObject();
    analyserDoesNotLoseMotionAcrossAFailedFrame();
    analyserOnATexturelessFrameReportsNoMotion();
    smoothingReducesJitterByAFactor();
    strengthZeroAndShortInputGiveIdentity();
    strongerSmoothingIsSteadier();
    cropLevelsOrderTheZoom();
    tightCropCoversTheViewport();
    requiredZoomMatchesTheClosedForm();
    framesAreNumberedAtTheProjectRate();
    rotationIsUnwrapped();
    mirroredEndsDoNotPullThePath();
    sigmaGrowsWithStrength();
    warpMappingsAreInverse();
    cacheRoundTrips();
    cacheRejectsDamage();
    cacheKeyChangesWithTheFile();
    registryResolvesPerFrameValues();
    registryBuildsFromACacheFile();
    effectWireAcceptsTheStabiliseEffect();
    lumaIsUprightCroppedAndScaled();
    if (failures == 0) std::printf("stabilise host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
