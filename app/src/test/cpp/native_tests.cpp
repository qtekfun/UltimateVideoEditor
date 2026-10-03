// Host-built unit tests (no Android deps). Run with scripts/run-native-tests.sh.
#include <cmath>
#include <cstdio>
#include <string>

#include "cache/lru_cache.h"
#include "decode/frame_rate.h"
#include "render/color_math.h"

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

int main() {
    lruEvictsLeastRecentlyUsed();
    lruNeverExceedsBudget();
    lruRejectsOversizedAndReplaces();
    lruBudgetShrinkAndClear();
    lruContainsDoesNotTouchRecency();
    lruEraseIf();
    frameRateSnapAndConversions();
    hlgTransferCurves();
    gamutMatrixPreservesWhite();
    toneMapProperties();
    hlgToSdrEndToEnd();
    if (g_failures == 0) std::puts("all native tests passed");
    return g_failures == 0 ? 0 : 1;
}
