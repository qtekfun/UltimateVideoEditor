// Host-built unit tests (no Android deps). Run with scripts/run-native-tests.sh.
#include <cmath>
#include <cstdio>
#include <string>

#include "cache/lru_cache.h"
#include "decode/frame_rate.h"
#include "render/color_math.h"
#include "render/layout_math.h"

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)
#define CHECK_NEAR(a, b, eps) CHECK(std::fabs((a) - (b)) <= (eps))

using uv::cache::LruCache;

static void lruEvictsLeastRecentlyUsed() {
    LruCache<int, std::string> c(300);
    c.put(1, "a", 100);
    c.put(2, "b", 100);
    c.put(3, "c", 100);
    std::string out;
    CHECK(c.get(1, &out));  // 1 becomes most recent; 2 is now the oldest
    auto evicted = c.put(4, "d", 100);
    CHECK(evicted.size() == 1 && evicted[0] == "b");
    CHECK(!c.contains(2));
    CHECK(c.contains(1) && c.contains(3) && c.contains(4));
    CHECK(c.usedBytes() == 300);
}

static void lruNeverExceedsBudget() {
    LruCache<int, int> c(1000);
    for (int i = 0; i < 500; ++i) {
        c.put(i, i, 37 + (i % 11));
        CHECK(c.usedBytes() <= 1000);
    }
}

static void lruRejectsOversizedAndReplaces() {
    LruCache<int, std::string> c(100);
    auto rejected = c.put(1, "big", 101);
    CHECK(rejected.size() == 1 && !c.contains(1) && c.usedBytes() == 0);
    c.put(2, "x", 60);
    auto replaced = c.put(2, "y", 40);
    CHECK(replaced.size() == 1 && replaced[0] == "x");
    CHECK(c.usedBytes() == 40 && c.size() == 1);
}

static void lruBudgetShrinkAndClear() {
    LruCache<int, int> c(400);
    for (int i = 0; i < 4; ++i) c.put(i, i, 100);
    auto ev = c.setBudget(200);
    CHECK(ev.size() == 2 && c.usedBytes() == 200);
    CHECK(c.contains(2) && c.contains(3));
    CHECK(c.clear().size() == 2 && c.size() == 0 && c.usedBytes() == 0);
}

static void lruContainsDoesNotTouchRecency() {
    LruCache<int, int> c(200);
    c.put(1, 1, 100);
    c.put(2, 2, 100);
    CHECK(c.contains(1));  // must not refresh 1
    auto ev = c.put(3, 3, 100);
    CHECK(ev.size() == 1 && ev[0] == 1);
}

static void lruEraseIf() {
    LruCache<int, int> c(1000);
    for (int i = 0; i < 6; ++i) c.put(i, i * 10, 100);
    auto removed = c.eraseIf([](int k) { return k % 2 == 0; });
    CHECK(removed.size() == 3);
    CHECK(c.size() == 3 && c.usedBytes() == 300);
    CHECK(!c.contains(0) && c.contains(1) && !c.contains(2) && c.contains(5));
}

static void lruProtectedEvictionKeepsWindow() {
    LruCache<int, int> c(300);
    c.put(10, 10, 100);  // oldest by recency, but inside the protected window
    c.put(11, 11, 100);
    c.put(1, 1, 100);
    const auto inWindow = [](int k) { return k >= 10 && k <= 12; };
    auto ev = c.put(12, 12, 100, inWindow);
    CHECK(ev.size() == 1 && ev[0] == 1);  // the unprotected entry goes, not the older protected 10
    CHECK(c.contains(10) && c.contains(11) && c.contains(12));
    // With only protected entries left, plain LRU order decides.
    ev = c.put(11, 11, 100, inWindow);
    CHECK(c.size() == 3 && c.usedBytes() == 300);
    auto ev2 = c.put(13, 13, 100, [](int k) { return k >= 10; });
    CHECK(ev2.size() == 1 && ev2[0] == 10);
    // The entry just inserted may be evicted when it is outside the window, instead of a protected one.
    LruCache<int, int> d(200);
    d.put(10, 10, 100, inWindow);
    d.put(11, 11, 100, inWindow);
    auto ev3 = d.put(99, 99, 100, inWindow);
    CHECK(ev3.size() == 1 && ev3[0] == 99 && d.contains(10) && d.contains(11));
}

static void frameRateSnapAndConversions() {
    using namespace uv::decode;
    Rational r = snapFrameRate(59.94005);
    CHECK(r.num == 60000 && r.den == 1001);
    r = snapFrameRate(29.97);
    CHECK(r.num == 30000 && r.den == 1001);
    r = snapFrameRate(30.0);
    CHECK(r.num == 30 && r.den == 1);
    const Rational ntsc{60000, 1001};
    for (int64_t f : {0LL, 1LL, 59LL, 1800LL, 107892LL}) {
        CHECK(ptsUsToFrame(frameToPtsUs(f, ntsc), ntsc) == f);  // lossless round trip
    }
    CHECK(ptsUsToFrame(0, {30, 1}) == 0);
    CHECK(ptsUsToFrame(33333, {30, 1}) == 1);
    CHECK(ptsUsToFrame(-1000, {30, 1}) == 0);
    CHECK(frameToPtsUs(30, {30, 1}) == 1000000);
}

static void hlgTransferCurves() {
    using namespace uv::render;
    CHECK_NEAR(hlgInverseOetf(0.0f), 0.0f, 1e-6f);
    CHECK_NEAR(hlgInverseOetf(1.0f), 1.0f, 1e-3f);
    CHECK_NEAR(hlgInverseOetf(0.5f), 1.0f / 12.0f, 1e-5f);
    CHECK_NEAR(hlgInverseOetf(0.5001f), hlgInverseOetf(0.4999f), 1e-3f);  // continuity at the knee
    float prev = -1.0f;
    for (int i = 0; i <= 100; ++i) {  // monotonic
        float v = hlgInverseOetf(static_cast<float>(i) / 100.0f);
        CHECK(v >= prev);
        prev = v;
    }
}

static void gamutMatrixPreservesWhite() {
    using namespace uv::render;
    for (size_t row = 0; row < 3; ++row) {
        float sum = kRec2020ToRec709[row * 3] + kRec2020ToRec709[row * 3 + 1] + kRec2020ToRec709[row * 3 + 2];
        CHECK_NEAR(sum, 1.0f, 2e-3f);
    }
}

static void toneMapProperties() {
    using namespace uv::render;
    CHECK_NEAR(toneMapLuma(0.0f), 0.0f, 1e-6f);
    CHECK_NEAR(toneMapLuma(0.5f), 0.5f, 1e-6f);  // linear below the knee
    CHECK_NEAR(toneMapLuma(kPeakNits / kDiffuseWhiteNits), 1.0f, 1e-4f);  // peak maps to 1
    CHECK(toneMapLuma(100.0f) <= 1.0f);
    float prev = -1.0f;
    for (int i = 0; i <= 500; ++i) {
        float v = toneMapLuma(static_cast<float>(i) / 100.0f);
        CHECK(v >= prev - 1e-6f);
        prev = v;
    }
}

static void hlgToSdrEndToEnd() {
    using namespace uv::render;
    Vec3 black = hlg2020ToSdr709({0, 0, 0});
    CHECK_NEAR(black.r, 0.0f, 1e-5f);
    Vec3 white = hlg2020ToSdr709({1, 1, 1});
    CHECK_NEAR(white.r, 1.0f, 2e-3f);
    CHECK_NEAR(white.g, 1.0f, 2e-3f);
    CHECK_NEAR(white.b, 1.0f, 2e-3f);
    // HLG 75% grey is the 203 nit reference white: must land close to SDR white, still below it.
    Vec3 ref = hlg2020ToSdr709({0.75f, 0.75f, 0.75f});
    CHECK(ref.g > 0.93f && ref.g <= 1.0f);
    // Neutral stays neutral.
    Vec3 mid = hlg2020ToSdr709({0.4f, 0.4f, 0.4f});
    CHECK_NEAR(mid.r, mid.g, 5e-3f);
    CHECK_NEAR(mid.g, mid.b, 5e-3f);
    // Output always in range even for saturated 2020 colours.
    Vec3 sat = hlg2020ToSdr709({0.1f, 0.9f, 0.1f});
    CHECK(sat.r >= 0.0f && sat.r <= 1.0f && sat.g <= 1.0f && sat.b >= 0.0f);
}

static void quarterTurnsNormalise() {
    using uv::render::quarterTurns;
    CHECK(quarterTurns(0) == 0 && quarterTurns(90) == 1 && quarterTurns(180) == 2 && quarterTurns(270) == 3);
    CHECK(quarterTurns(360) == 0 && quarterTurns(-90) == 3 && quarterTurns(450) == 1);
    CHECK(quarterTurns(45) == 0);  // off-grid metadata is ignored
}

static void rotateUvCornersAndComposition() {
    using uv::render::rotateUv;
    using uv::render::Uv;
    // Rotating 90 clockwise: the source top-left corner ends up top-right in the output, so the
    // output top-right samples the source top-left.
    Uv a = rotateUv({1.0f, 0.0f}, 1);
    CHECK_NEAR(a.u, 0.0f, 1e-6f);
    CHECK_NEAR(a.v, 0.0f, 1e-6f);
    // Output bottom-left shows the source bottom-right after 270 clockwise.
    Uv b = rotateUv({0.0f, 1.0f}, 3);
    CHECK_NEAR(b.u, 0.0f, 1e-6f);
    CHECK_NEAR(b.v, 0.0f, 1e-6f);
    Uv c = rotateUv({0.25f, 0.75f}, 2);
    CHECK_NEAR(c.u, 0.75f, 1e-6f);
    CHECK_NEAR(c.v, 0.25f, 1e-6f);
    // Mapping a point through the source-to-output rotation and back is the identity: a 90 and a
    // 270 mapping are inverse, 180 is its own inverse.
    for (int t = 0; t < 4; ++t) {
        Uv p{0.3f, 0.8f};
        Uv back = rotateUv(rotateUv(p, t), (4 - t) & 3);
        CHECK_NEAR(back.u, p.u, 1e-6f);
        CHECK_NEAR(back.v, p.v, 1e-6f);
    }
}

static void displaySizeAndLetterbox() {
    using namespace uv::render;
    int w = 0;
    int h = 0;
    displaySize(3840, 2160, 1, &w, &h);
    CHECK(w == 2160 && h == 3840);
    displaySize(3840, 2160, 2, &w, &h);
    CHECK(w == 3840 && h == 2160);
    // 16:9 into a tall phone surface: full width, bars above and below.
    Viewport v = letterbox(3840, 2160, 1440, 3168);
    CHECK(v.w == 1440 && v.h == 810 && v.x == 0 && v.y == (3168 - 810) / 2);
    // Portrait frame (rotated 90) into the same surface: full width again, taller image.
    displaySize(3840, 2160, 1, &w, &h);
    v = letterbox(w, h, 1440, 3168);
    CHECK(v.h <= 3168 && v.w <= 1440 && (v.w == 1440 || v.h == 3168));
    // Same aspect as the surface fills it exactly.
    v = letterbox(1920, 1080, 960, 540);
    CHECK(v == (Viewport{0, 0, 960, 540}));
    // Wide surface: pillarbox.
    v = letterbox(1080, 1920, 1920, 1080);
    CHECK(v.h == 1080 && v.w == 607 && v.y == 0 && v.x == (1920 - 607) / 2);
    // Degenerate inputs do not divide by zero.
    v = letterbox(0, 0, 100, 100);
    CHECK(v.w == 100 && v.h == 100);
}

// Corners of the quad in canvas pixels (+y down), via the same map the shader uses.
static uv::render::Uv canvasPoint(const uv::render::QuadMap& m, int cw, int ch, float px, float py) {
    const uv::render::Uv ndc = uv::render::applyQuadMap(m, {px, py});
    return {ndc.u * static_cast<float>(cw) / 2.0f, -ndc.v * static_cast<float>(ch) / 2.0f};
}

static void layerIdentityFillsCanvasWhenAspectMatches() {
    using namespace uv::render;
    const QuadMap m = layerQuadMap(1920, 1080, 1920, 1080, LayerTransform{});
    CHECK_NEAR(m.a, 1.0f, 1e-6f);
    CHECK_NEAR(m.d, 1.0f, 1e-6f);
    CHECK_NEAR(m.b, 0.0f, 1e-6f);
    CHECK_NEAR(m.c, 0.0f, 1e-6f);
    CHECK_NEAR(m.tx, 0.0f, 1e-6f);
    CHECK_NEAR(m.ty, 0.0f, 1e-6f);
    // A 1080p clip in a 4K canvas is fitted, so it still fills it.
    const QuadMap big = layerQuadMap(3840, 2160, 1920, 1080, LayerTransform{});
    CHECK_NEAR(big.a, 1.0f, 1e-6f);
    CHECK_NEAR(big.d, 1.0f, 1e-6f);
}

static void layerFitLetterboxesMismatchedAspect() {
    using namespace uv::render;
    // A portrait 1080x1920 clip in a 1920x1080 canvas: full height, narrow width.
    const QuadMap m = layerQuadMap(1920, 1080, 1080, 1920, LayerTransform{});
    CHECK_NEAR(m.d, 1.0f, 1e-6f);
    CHECK_NEAR(m.a, (1080.0f * 1080.0f / 1920.0f) / 1920.0f, 1e-5f);
    // A square clip in a wide canvas.
    const QuadMap sq = layerQuadMap(1920, 1080, 1000, 1000, LayerTransform{});
    CHECK_NEAR(sq.d, 1.0f, 1e-6f);
    CHECK_NEAR(sq.a, 1080.0f / 1920.0f, 1e-6f);
}

static void layerScaleAndPosition() {
    using namespace uv::render;
    LayerTransform t;
    t.scaleX = 0.5f;
    t.scaleY = 0.25f;
    t.posX = 480.0f;   // a quarter of the canvas width to the right
    t.posY = 270.0f;   // a quarter of the canvas height down
    const QuadMap m = layerQuadMap(1920, 1080, 1920, 1080, t);
    CHECK_NEAR(m.a, 0.5f, 1e-6f);
    CHECK_NEAR(m.d, 0.25f, 1e-6f);
    CHECK_NEAR(m.tx, 0.5f, 1e-6f);    // clip space spans 2, so a quarter of the width is 0.5
    CHECK_NEAR(m.ty, -0.5f, 1e-6f);   // down in canvas pixels is negative in clip space
    // Top-left image corner (p = (-1, +1)) lands at canvas px (480 - 480, 270 - 135) from the centre.
    const Uv c = canvasPoint(m, 1920, 1080, -1.0f, 1.0f);
    CHECK_NEAR(c.u, 0.0f, 1e-3f);
    CHECK_NEAR(c.v, 135.0f, 1e-3f);
}

static void layerRotationIsClockwiseOnScreen() {
    using namespace uv::render;
    LayerTransform t;
    t.rotationDeg = 90.0f;
    const QuadMap m = layerQuadMap(1000, 1000, 1000, 1000, t);
    // The top-centre of the image (p = (0, 1)) moves to the right-centre of the canvas.
    const Uv top = canvasPoint(m, 1000, 1000, 0.0f, 1.0f);
    CHECK_NEAR(top.u, 500.0f, 1e-2f);
    CHECK_NEAR(top.v, 0.0f, 1e-2f);
    // The right-centre (p = (1, 0)) moves to the bottom-centre.
    const Uv right = canvasPoint(m, 1000, 1000, 1.0f, 0.0f);
    CHECK_NEAR(right.u, 0.0f, 1e-2f);
    CHECK_NEAR(right.v, 500.0f, 1e-2f);
    // 360 degrees is the identity.
    t.rotationDeg = 360.0f;
    const QuadMap full = layerQuadMap(1000, 1000, 1000, 1000, t);
    CHECK_NEAR(full.a, 1.0f, 1e-5f);
    CHECK_NEAR(full.b, 0.0f, 1e-5f);
}

static void layerRotationPreservesAreaAndAboutCentre() {
    using namespace uv::render;
    LayerTransform t;
    t.rotationDeg = 33.0f;
    t.scaleX = 0.6f;
    t.scaleY = 0.4f;
    const QuadMap m = layerQuadMap(1920, 1080, 1280, 720, t);
    // The centre does not move when there is no translation.
    const Uv centre = canvasPoint(m, 1920, 1080, 0.0f, 0.0f);
    CHECK_NEAR(centre.u, 0.0f, 1e-3f);
    CHECK_NEAR(centre.v, 0.0f, 1e-3f);
    // Rotation keeps lengths: the distance centre-to-right-edge is half the scaled width.
    const Uv edge = canvasPoint(m, 1920, 1080, 1.0f, 0.0f);
    CHECK_NEAR(std::sqrt(edge.u * edge.u + edge.v * edge.v), 1920.0f * 0.6f / 2.0f, 1e-2f);
    // The determinant of the 2x2 part (in canvas pixels) equals the scaled, fitted area / 4.
    const float det = (m.a * 1920.0f / 2.0f) * (m.d * 1080.0f / 2.0f) - (m.b * 1920.0f / 2.0f) * (m.c * 1080.0f / 2.0f);
    CHECK_NEAR(det, 1920.0f * 0.6f / 2.0f * 1080.0f * 0.4f / 2.0f, 1.0f);
}

static void layerDegenerateInputsGiveIdentity() {
    using namespace uv::render;
    const QuadMap m = layerQuadMap(0, 1080, 1920, 1080, LayerTransform{});
    CHECK(m.a == 1.0f && m.d == 1.0f && m.tx == 0.0f && m.ty == 0.0f);
    const QuadMap z = layerQuadMap(1920, 1080, 0, 0, LayerTransform{});
    CHECK(z.a == 1.0f && z.d == 1.0f);
}

static void layerMat3IsColumnMajor() {
    using namespace uv::render;
    const QuadMap m{2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f};
    float out[9];
    quadMapToMat3(m, out);
    // (x, y, 1) -> (a x + b y + tx, c x + d y + ty, 1)
    const float x = 0.5f;
    const float y = -2.0f;
    const float rx = out[0] * x + out[3] * y + out[6];
    const float ry = out[1] * x + out[4] * y + out[7];
    CHECK_NEAR(rx, 2.0f * x + 3.0f * y + 4.0f, 1e-6f);
    CHECK_NEAR(ry, 5.0f * x + 6.0f * y + 7.0f, 1e-6f);
    CHECK(out[8] == 1.0f && out[2] == 0.0f && out[5] == 0.0f);
}

static void opacityIsClamped() {
    using uv::render::clampOpacity;
    CHECK(clampOpacity(-1.0f) == 0.0f && clampOpacity(0.0f) == 0.0f);
    CHECK(clampOpacity(0.4f) == 0.4f && clampOpacity(1.0f) == 1.0f && clampOpacity(7.0f) == 1.0f);
    CHECK(clampOpacity(std::nanf("")) == 0.0f);
}

int main() {
    layerIdentityFillsCanvasWhenAspectMatches();
    layerFitLetterboxesMismatchedAspect();
    layerScaleAndPosition();
    layerRotationIsClockwiseOnScreen();
    layerRotationPreservesAreaAndAboutCentre();
    layerDegenerateInputsGiveIdentity();
    layerMat3IsColumnMajor();
    opacityIsClamped();
    lruEvictsLeastRecentlyUsed();
    lruNeverExceedsBudget();
    lruRejectsOversizedAndReplaces();
    lruBudgetShrinkAndClear();
    lruContainsDoesNotTouchRecency();
    lruEraseIf();
    lruProtectedEvictionKeepsWindow();
    frameRateSnapAndConversions();
    hlgTransferCurves();
    gamutMatrixPreservesWhite();
    toneMapProperties();
    hlgToSdrEndToEnd();
    quarterTurnsNormalise();
    rotateUvCornersAndComposition();
    displaySizeAndLetterbox();
    if (g_failures == 0) std::puts("all native tests passed");
    return g_failures == 0 ? 0 : 1;
}
