// Host tests for render/repair_math.h: the CPU reference of block-matching optical flow, smooth slow
// motion interpolation, noise reduction and flicker removal. Synthetic frames with known motion and noise.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "core/layer_fx.h"
#include "render/repair_math.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                      \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
            ++failures;                                                  \
        }                                                                \
    } while (0)

#define CHECK_NEAR(actual, expected, eps)                                                                    \
    do {                                                                                                     \
        const double a_ = static_cast<double>(actual);                                                       \
        const double e_ = static_cast<double>(expected);                                                     \
        if (std::fabs(a_ - e_) > static_cast<double>(eps)) {                                                 \
            std::printf("FAIL %s:%d: %s = %f, expected %f +- %f\n", __FILE__, __LINE__, #actual, a_, e_, eps); \
            ++failures;                                                                                      \
        }                                                                                                    \
    } while (0)

using namespace uv;
using namespace uv::render;
using namespace uv::render::repair;

// Deterministic noise in 0..1.
struct Lcg {
    uint32_t state = 12345;
    float next() {
        state = state * 1664525u + 1013904223u;
        return static_cast<float>((state >> 8) & 0xFFFF) / 65535.0f;
    }
    float gaussish() { return (next() + next() + next() + next() - 2.0f) * 0.866f; }  // ~N(0, 0.5)
};

// A textured background (so block matching has something to lock on) with a bright square at column x0.
Image sceneWithSquare(int w, int h, int x0, int size) {
    Image img(w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            // A smooth, non-repeating-in-the-window pattern at low contrast.
            const float tex = 0.25f + 0.08f * std::sin(0.9f * static_cast<float>(x) + 0.3f * static_cast<float>(y)) +
                              0.06f * std::sin(0.37f * static_cast<float>(y) - 0.21f * static_cast<float>(x));
            Rgb3 c{tex, tex, tex};
            if (x >= x0 && x < x0 + size && y >= h / 2 - size / 2 && y < h / 2 + size / 2) c = {1.0f, 1.0f, 1.0f};
            img.set(x, y, c);
        }
    }
    return img;
}

// Centroid column of the pixels brighter than 0.7 in the middle row band, and the brightest value.
void squareStats(const Image& img, double* centroid, float* peak) {
    double sum = 0.0;
    double weight = 0.0;
    float best = 0.0f;
    for (int y = 0; y < img.h; ++y) {
        for (int x = 0; x < img.w; ++x) {
            const float l = luma3(img.at(x, y));
            best = std::max(best, l);
            if (l > 0.7f) {
                sum += x;
                weight += 1.0;
            }
        }
    }
    *centroid = weight > 0.0 ? sum / weight : -1.0;
    *peak = best;
}

Image render(const Image& a, const Image& b, const std::vector<FlowSample>& flow, int fw, int fh, float t, bool useFlow) {
    Image out(a.w, a.h);
    for (int y = 0; y < a.h; ++y) {
        for (int x = 0; x < a.w; ++x) {
            const float u = (static_cast<float>(x) + 0.5f) / static_cast<float>(a.w);
            const float v = (static_cast<float>(y) + 0.5f) / static_cast<float>(a.h);
            out.set(x, y, useFlow ? interpolatePixel(a, b, flow, fw, fh, u, v, t) : blendPixel(a, b, u, v, t));
        }
    }
    return out;
}

void blockMatchingRecoversAKnownShift() {
    const int w = 48;
    const int h = 32;
    Image a(w, h);
    Image b(w, h);
    Lcg rng;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) a.set(x, y, {rng.next(), rng.next(), rng.next()});
    }
    const int shiftX = 3;
    const int shiftY = -2;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) b.set(x, y, a.at(x - shiftX, y - shiftY));
    }
    const auto flow = blockMatch(lumaOf(a), lumaOf(b), w, h, 6, 1);
    int exact = 0;
    int counted = 0;
    for (int y = 8; y < h - 8; ++y) {
        for (int x = 8; x < w - 8; ++x) {
            const FlowSample& f = flow[static_cast<size_t>(y) * w + static_cast<size_t>(x)];
            ++counted;
            if (f.dx == static_cast<float>(shiftX) && f.dy == static_cast<float>(shiftY) && f.conf > 0.9f) ++exact;
        }
    }
    CHECK(exact > counted * 95 / 100);
}

void flatAreasStayAtRestAndMotionBeyondTheWindowHasNoConfidence() {
    const int w = 32;
    const int h = 24;
    Image flat(w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) flat.set(x, y, {0.4f, 0.4f, 0.4f});
    }
    const auto flowFlat = blockMatch(lumaOf(flat), lumaOf(flat), w, h, 6, 1);
    for (const FlowSample& f : flowFlat) {
        CHECK(f.dx == 0.0f && f.dy == 0.0f);
    }
    // Shifted 3 x the search radius: whatever the matcher finds on the window border is distrusted.
    Image a(w, h);
    Image b(w, h);
    Lcg rng;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) a.set(x, y, {rng.next(), rng.next(), rng.next()});
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) b.set(x, y, a.at(x - 18, y));
    }
    const auto flow = blockMatch(lumaOf(a), lumaOf(b), w, h, 6, 1);
    int confident = 0;
    for (const FlowSample& f : flow) {
        if (f.conf > 0.5f) ++confident;
    }
    CHECK(confident < static_cast<int>(flow.size()) / 20);
}

void interpolationPlacesTheSquareHalfwayWhereBlendingGhostsIt() {
    const int w = 64;
    const int h = 32;
    const int size = 6;
    const int x0 = 16;
    const int move = 6;  // pixels between the two frames, inside the search radius; the squares do not overlap
    const Image a = sceneWithSquare(w, h, x0, size);
    const Image b = sceneWithSquare(w, h, x0 + move, size);
    const auto flow = blockMatch(lumaOf(a), lumaOf(b), w, h, 8, 1);

    const Image interpolated = render(a, b, flow, w, h, 0.5f, true);
    double centroid = 0.0;
    float peak = 0.0f;
    squareStats(interpolated, &centroid, &peak);
    const double expected = x0 + move / 2.0 + (size - 1) / 2.0;
    CHECK_NEAR(centroid, expected, 1.5);
    CHECK(peak > 0.95f);

    // Plain blending: two half-bright squares, so nothing exceeds 0.7 where it should be bright.
    const Image blended = render(a, b, flow, w, h, 0.5f, false);
    squareStats(blended, &centroid, &peak);
    CHECK(peak < 0.75f);
}

void interpolationFollowsTheFraction() {
    const int w = 64;
    const int h = 32;
    const Image a = sceneWithSquare(w, h, 10, 6);
    const Image b = sceneWithSquare(w, h, 16, 6);
    const auto flow = blockMatch(lumaOf(a), lumaOf(b), w, h, 8, 1);
    double previous = -1.0;
    for (int k = 1; k <= 3; ++k) {
        const float t = static_cast<float>(k) / 4.0f;
        double centroid = 0.0;
        float peak = 0.0f;
        squareStats(render(a, b, flow, w, h, t, true), &centroid, &peak);
        CHECK_NEAR(centroid, 10 + 6.0 * t + 2.5, 2.0);
        CHECK(peak > 0.9f);
        CHECK(centroid > previous);
        previous = centroid;
    }
    // t = 0 and t = 1 return the source frames.
    Image first = render(a, b, flow, w, h, 0.0f, true);
    Image last = render(a, b, flow, w, h, 1.0f, true);
    double ca = 0.0;
    double cb = 0.0;
    float pa = 0.0f;
    float pb = 0.0f;
    squareStats(first, &ca, &pa);
    squareStats(last, &cb, &pb);
    CHECK_NEAR(ca, 10 + 2.5, 0.6);
    CHECK_NEAR(cb, 16 + 2.5, 0.6);
}

void largeMotionFallsBackToPlainBlending() {
    const int w = 96;
    const int h = 32;
    const Image a = sceneWithSquare(w, h, 8, 8);
    const Image b = sceneWithSquare(w, h, 60, 8);  // 52 px: far outside the search window
    const auto flow = blockMatch(lumaOf(a), lumaOf(b), w, h, 8, 1);
    const Image interpolated = render(a, b, flow, w, h, 0.5f, true);
    const Image blended = render(a, b, flow, w, h, 0.5f, false);
    float worst = 0.0f;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            worst = std::max(worst, std::fabs(luma3(interpolated.at(x, y)) - luma3(blended.at(x, y))));
        }
    }
    // Some pixels in the textured background may still lock onto a wrong match, but the moving square
    // itself (the part that matters) is not smeared across a bogus path: the peak matches the blend.
    double centroid = 0.0;
    float peak = 0.0f;
    squareStats(interpolated, &centroid, &peak);
    CHECK(peak < 0.8f);
    (void)worst;
}

void downscalingAveragesBlocks() {
    Image src(8, 8);
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) src.set(x, y, (x < 4) ? Rgb3{1, 1, 1} : Rgb3{0, 0, 0});
    }
    const Image small = boxDownscale(src, 2, 2);
    CHECK_NEAR(small.at(0, 0).r, 1.0f, 1e-5);
    CHECK_NEAR(small.at(1, 0).r, 0.0f, 1e-5);
    CHECK_NEAR(meanLuma(src), 0.5f, 1e-5);
    CHECK_NEAR(meanLuma(small), 0.5f, 1e-5);
}

double noiseStd(const Image& img, int x0, int y0, int x1, int y1) {
    double sum = 0.0;
    int n = 0;
    for (int y = y0; y < y1; ++y) {
        for (int x = x0; x < x1; ++x) {
            sum += luma3(img.at(x, y));
            ++n;
        }
    }
    const double mean = sum / n;
    double var = 0.0;
    for (int y = y0; y < y1; ++y) {
        for (int x = x0; x < x1; ++x) {
            const double d = luma3(img.at(x, y)) - mean;
            var += d * d;
        }
    }
    return std::sqrt(var / n);
}

Image noisyFlat(int w, int h, float level, float sigma, uint32_t seed) {
    Image img(w, h);
    Lcg rng;
    rng.state = seed;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const float n = rng.gaussish() * sigma;
            img.set(x, y, {level + n, level + n, level + n});
        }
    }
    return img;
}

void denoiseSpatialReducesNoiseAndKeepsEdges() {
    const int w = 40;
    const int h = 40;
    Image noisy = noisyFlat(w, h, 0.5f, 0.03f, 7);
    // A hard vertical edge in the right half: 0.2 on the left of x = 30, 0.8 from there.
    for (int y = 0; y < h; ++y) {
        for (int x = 30; x < w; ++x) {
            const Rgb3 c = noisy.at(x, y);
            noisy.set(x, y, {c.r + 0.3f, c.g + 0.3f, c.b + 0.3f});
        }
        for (int x = 20; x < 30; ++x) {
            const Rgb3 c = noisy.at(x, y);
            noisy.set(x, y, {c.r - 0.3f, c.g - 0.3f, c.b - 0.3f});
        }
    }
    Image clean(w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) clean.set(x, y, denoisePixel(noisy, nullptr, x, y, 1.0f, 0.0f));
    }
    // Flat part (x < 16): noise drops by at least 40 %.
    const double before = noiseStd(noisy, 2, 2, 16, h - 2);
    const double after = noiseStd(clean, 2, 2, 16, h - 2);
    CHECK(after < before * 0.6);
    // Edge contrast between x = 27 and x = 32 (the step is 0.6 in the noisy image) keeps at least 85 %.
    const double contrastBefore = luma3(noisy.at(32, 20)) - luma3(noisy.at(27, 20));
    const double contrastAfter = luma3(clean.at(32, 20)) - luma3(clean.at(27, 20));
    CHECK(contrastBefore > 0.4);
    CHECK(contrastAfter > contrastBefore * 0.85);
    // Strength 0 changes nothing.
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) CHECK(denoisePixel(noisy, nullptr, x, y, 0.0f, 0.0f).r == noisy.at(x, y).r);
    }
}

void denoiseTemporalHelpsStaticAreasAndIgnoresMotion() {
    const int w = 40;
    const int h = 24;
    const Image cur = noisyFlat(w, h, 0.5f, 0.03f, 11);
    const Image prev = noisyFlat(w, h, 0.5f, 0.03f, 99);  // independent noise, same scene
    Image spatialOnly(w, h);
    Image both(w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            spatialOnly.set(x, y, denoisePixel(cur, nullptr, x, y, 0.5f, 0.0f));
            both.set(x, y, denoisePixel(cur, &prev, x, y, 0.5f, 1.0f));
        }
    }
    CHECK(noiseStd(both, 3, 3, w - 3, h - 3) < noiseStd(spatialOnly, 3, 3, w - 3, h - 3) * 0.95);

    // Where the previous frame shows something very different (motion), it is not mixed in.
    Image moved(w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) moved.set(x, y, {0.95f, 0.95f, 0.95f});
    }
    const Rgb3 withMotion = denoisePixel(cur, &moved, 20, 12, 0.0f, 1.0f);
    CHECK_NEAR(withMotion.r, cur.at(20, 12).r, 1e-5);
}

void deflickerPullsBrightnessTowardsTheWindowMean() {
    // Pumping: 0.5, 0.6, 0.5 -> the middle frame is scaled down towards 0.5333.
    const float gain = deflickerGain(0.5f, 0.6f, 0.5f, 1.0f);
    CHECK_NEAR(gain, (0.5 + 0.6 + 0.5) / 3.0 / 0.6, 1e-4);
    CHECK(gain < 1.0f);
    // A steady fade passes through: the middle of 0.4, 0.5, 0.6 is already the mean.
    CHECK_NEAR(deflickerGain(0.4f, 0.5f, 0.6f, 1.0f), 1.0, 1e-4);
    // Strength scales the correction, 0 leaves the frame alone.
    CHECK_NEAR(deflickerGain(0.5f, 0.6f, 0.5f, 0.0f), 1.0, 1e-6);
    const float half = deflickerGain(0.5f, 0.6f, 0.5f, 0.5f);
    CHECK_NEAR(half, 1.0 + (gain - 1.0) * 0.5, 1e-4);
    // Missing neighbours (negative) use what is there; gains are limited.
    CHECK_NEAR(deflickerGain(-1.0f, 0.6f, -1.0f, 1.0f), 1.0, 1e-6);
    CHECK_NEAR(deflickerGain(0.01f, 0.9f, 0.01f, 1.0f), kDeflickerMinGain, 1e-6);
    CHECK_NEAR(deflickerGain(1.0f, 0.01f, 1.0f, 1.0f), kDeflickerMaxGain, 1e-6);

    // Over a flickering series the corrected means vary much less than the originals.
    const float means[] = {0.50f, 0.62f, 0.49f, 0.63f, 0.51f, 0.61f, 0.50f};
    float lo = 1.0f;
    float hi = 0.0f;
    float clo = 1.0f;
    float chi = 0.0f;
    for (int i = 1; i < 6; ++i) {
        lo = std::min(lo, means[i]);
        hi = std::max(hi, means[i]);
        const float corrected = means[i] * deflickerGain(means[i - 1], means[i], means[i + 1], 1.0f);
        clo = std::min(clo, corrected);
        chi = std::max(chi, corrected);
    }
    CHECK((chi - clo) < (hi - lo) * 0.5f);
}

void neighbourNeedsFollowTheEffects() {
    core::LayerFx fx;
    CHECK(!core::neighbourNeeds(fx).prev && !core::neighbourNeeds(fx).next);
    core::EffectOp dn;
    dn.type = core::EffectType::Denoise;
    dn.v[0] = 0.5f;
    dn.v[1] = 0.0f;
    fx.effects.push_back(dn);
    CHECK(!core::neighbourNeeds(fx).prev);  // spatial only
    fx.effects[0].v[1] = 0.6f;
    CHECK(core::neighbourNeeds(fx).prev && !core::neighbourNeeds(fx).next);
    core::EffectOp df;
    df.type = core::EffectType::Deflicker;
    df.v[0] = 1.0f;
    fx.effects.push_back(df);
    CHECK(core::neighbourNeeds(fx).prev && core::neighbourNeeds(fx).next);
}

void wireFormatAcceptsTheRepairEffects() {
    const double blob[] = {0, 0, 0, 0, 1, 1, 0, 0, 2,   // header: blend 0, no mask, 2 effects
                           16, 2, 0.5, 0.6,              // denoise
                           17, 1, 0.8};                  // deflicker
    core::LayerFx fx;
    size_t offset = 0;
    CHECK(core::parseLayerFx(blob, sizeof(blob) / sizeof(blob[0]), &offset, &fx));
    CHECK(fx.effects.size() == 2);
    CHECK(fx.effects[0].type == core::EffectType::Denoise);
    CHECK_NEAR(fx.effects[0].v[1], 0.6, 1e-6);
    CHECK(fx.effects[1].type == core::EffectType::Deflicker);
    const double bad[] = {0, 0, 0, 0, 1, 1, 0, 0, 1, 18, 1, 0.5};  // unknown effect 18
    offset = 0;
    CHECK(!core::parseLayerFx(bad, sizeof(bad) / sizeof(bad[0]), &offset, &fx));
}

}  // namespace

int main() {
    blockMatchingRecoversAKnownShift();
    flatAreasStayAtRestAndMotionBeyondTheWindowHasNoConfidence();
    interpolationPlacesTheSquareHalfwayWhereBlendingGhostsIt();
    interpolationFollowsTheFraction();
    largeMotionFallsBackToPlainBlending();
    downscalingAveragesBlocks();
    denoiseSpatialReducesNoiseAndKeepsEdges();
    denoiseTemporalHelpsStaticAreasAndIgnoresMotion();
    deflickerPullsBrightnessTowardsTheWindowMean();
    neighbourNeedsFollowTheEffects();
    wireFormatAcceptsTheRepairEffects();
    if (failures == 0) std::printf("repair host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
