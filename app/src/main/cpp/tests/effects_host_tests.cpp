// Host tests for core/layer_fx.h (wire format) and render/effect_math.h (CPU reference of the
// effect, mask and blend shaders). No Android dependencies.
#include <array>
#include <cmath>
#include <cstdio>
#include <vector>

#include "core/layer_fx.h"
#include "render/effect_math.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);     \
            ++failures;                                                      \
        }                                                                    \
    } while (0)

#define CHECK_NEAR(actual, expected)                                                                     \
    do {                                                                                                 \
        const double a_ = static_cast<double>(actual);                                                   \
        const double e_ = static_cast<double>(expected);                                                 \
        if (std::fabs(a_ - e_) > 1e-4) {                                                                 \
            std::printf("FAIL %s:%d: %s = %f, expected %f\n", __FILE__, __LINE__, #actual, a_, e_);      \
            ++failures;                                                                                  \
        }                                                                                                \
    } while (0)

using namespace uv;
using namespace uv::core;
using namespace uv::render;

bool near(float a, float b, float eps) { return std::fabs(a - b) <= eps; }

EffectOp op(EffectType type, std::vector<float> values) {
    EffectOp o;
    o.type = type;
    for (size_t i = 0; i < values.size(); ++i) o.v[i] = values[i];
    return o;
}

// ---- wire format -------------------------------------------------------------------------------

void plainLayerParses() {
    const std::vector<double> blob = {0, 0, 0, 0, 1, 1, 0, 0, 0};
    size_t offset = 0;
    LayerFx fx;
    CHECK(parseLayerFx(blob.data(), blob.size(), &offset, &fx));
    CHECK(offset == blob.size());
    CHECK(fx.neutral());
}

void fullLayerParses() {
    // screen, ellipse centre (0.1,-0.2) size 0.5 x 0.4 feather 0.05 inverted, two effects.
    const std::vector<double> blob = {3, 2, 0.1, -0.2, 0.5, 0.4, 0.05, 1, 2,
                                      2, 1, 1.5,
                                      12, 6, 0, 1, 0, 0.4, 0.1, 0.2};
    size_t offset = 0;
    LayerFx fx;
    CHECK(parseLayerFx(blob.data(), blob.size(), &offset, &fx));
    CHECK(offset == blob.size());
    CHECK(fx.blend == BlendMode::Screen);
    CHECK(fx.mask.shape == 2 && fx.mask.invert);
    CHECK_NEAR(fx.mask.cx, 0.1);
    CHECK_NEAR(fx.mask.h, 0.4);
    CHECK(fx.effects.size() == 2);
    CHECK(fx.effects[0].type == EffectType::Contrast);
    CHECK_NEAR(fx.effects[0].v[0], 1.5);
    CHECK(fx.effects[1].type == EffectType::ChromaKey);
    CHECK_NEAR(fx.effects[1].v[5], 0.2);
    CHECK(!fx.neutral());
}

void scenesParseLayersBackToBack() {
    const std::vector<double> blob = {0, 0, 0, 0, 1, 1, 0, 0, 0,           // plain
                                      1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0.5};  // add + one effect
    std::vector<LayerFx> layers;
    CHECK(parseSceneFx(blob.data(), blob.size(), 2, &layers));
    CHECK(layers.size() == 2);
    CHECK(layers[0].neutral());
    CHECK(layers[1].blend == BlendMode::Add && layers[1].effects.size() == 1);
    CHECK(!(layers[0] == layers[1]));
    CHECK(layers[1] == layers[1]);
}

// Keyframed effect values for the exporter: one blob per project frame of an animated clip.
void frameTablesParsePerClip() {
    const std::vector<double> blob = {1, 0, 0, 0, 1, 1, 0, 0, 1, 2, 1, 0.5,   // clip 2, frame 0: add + contrast 0.5
                                      1, 0, 0, 0, 1, 1, 0, 0, 1, 2, 1, 1.5,   // clip 2, frame 1: contrast 1.5
                                      0, 0, 0, 0, 1, 1, 0, 0, 0};             // clip 0, frame 0: plain
    const std::vector<int64_t> pairs = {2, 2, 0, 1};
    std::vector<std::vector<LayerFx>> tables;
    CHECK(parseFxFrameTables(pairs.data(), pairs.size(), blob.data(), blob.size(), 3, &tables));
    CHECK(tables.size() == 3);
    CHECK(tables[0].size() == 1 && tables[0][0].neutral());
    CHECK(tables[1].empty());
    CHECK(tables[2].size() == 2);
    CHECK_NEAR(tables[2][0].effects[0].v[0], 0.5);
    CHECK_NEAR(tables[2][1].effects[0].v[0], 1.5);
    CHECK(tables[2][0].blend == BlendMode::Add);
}

void frameTablesRejectBadInput() {
    std::vector<std::vector<LayerFx>> tables;
    const std::vector<double> plain = {0, 0, 0, 0, 1, 1, 0, 0, 0};
    // No pairs: only an empty table is valid.
    CHECK(parseFxFrameTables(nullptr, 0, nullptr, 0, 2, &tables));
    CHECK(tables.size() == 2 && tables[0].empty());
    CHECK(!parseFxFrameTables(nullptr, 0, plain.data(), plain.size(), 2, &tables));
    // Odd pair array, out-of-range or negative index, negative count, repeated clip.
    const std::vector<int64_t> odd = {0, 1, 1};
    CHECK(!parseFxFrameTables(odd.data(), odd.size(), plain.data(), plain.size(), 2, &tables));
    const std::vector<int64_t> beyond = {5, 1};
    CHECK(!parseFxFrameTables(beyond.data(), beyond.size(), plain.data(), plain.size(), 2, &tables));
    const std::vector<int64_t> negative = {-1, 1};
    CHECK(!parseFxFrameTables(negative.data(), negative.size(), plain.data(), plain.size(), 2, &tables));
    const std::vector<int64_t> negCount = {0, -1};
    CHECK(!parseFxFrameTables(negCount.data(), negCount.size(), plain.data(), plain.size(), 2, &tables));
    const std::vector<double> two = {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0};
    const std::vector<int64_t> repeated = {0, 1, 0, 1};
    CHECK(!parseFxFrameTables(repeated.data(), repeated.size(), two.data(), two.size(), 2, &tables));
    // A count that claims more blobs than the data holds, and data left over.
    const std::vector<int64_t> tooMany = {0, 3};
    CHECK(!parseFxFrameTables(tooMany.data(), tooMany.size(), plain.data(), plain.size(), 2, &tables));
    const std::vector<int64_t> one = {0, 1};
    CHECK(!parseFxFrameTables(one.data(), one.size(), two.data(), two.size(), 2, &tables));
    // An absurd count never allocates.
    const std::vector<int64_t> absurd = {0, int64_t{1} << 60};
    CHECK(!parseFxFrameTables(absurd.data(), absurd.size(), plain.data(), plain.size(), 2, &tables));
}

void emptyArrayMeansPlainLayers() {
    std::vector<LayerFx> layers;
    CHECK(parseSceneFx(nullptr, 0, 3, &layers));
    CHECK(layers.size() == 3 && layers[2].neutral());
}

void malformedBlobsAreRejected() {
    std::vector<LayerFx> layers;
    const std::vector<double> truncated = {0, 0, 0, 0, 1, 1, 0, 0};
    CHECK(!parseSceneFx(truncated.data(), truncated.size(), 1, &layers));
    const std::vector<double> badBlend = {9, 0, 0, 0, 1, 1, 0, 0, 0};
    CHECK(!parseSceneFx(badBlend.data(), badBlend.size(), 1, &layers));
    const std::vector<double> badShape = {0, 5, 0, 0, 1, 1, 0, 0, 0};
    CHECK(!parseSceneFx(badShape.data(), badShape.size(), 1, &layers));
    const std::vector<double> badType = {0, 0, 0, 0, 1, 1, 0, 0, 1, 99, 1, 0.5};
    CHECK(!parseSceneFx(badType.data(), badType.size(), 1, &layers));
    const std::vector<double> shortValues = {0, 0, 0, 0, 1, 1, 0, 0, 1, 2, 3, 1.0};
    CHECK(!parseSceneFx(shortValues.data(), shortValues.size(), 1, &layers));
    const std::vector<double> nan = {0, 0, 0, 0, 1, 1, 0, 0, 1, 2, 1, std::nan("")};
    CHECK(!parseSceneFx(nan.data(), nan.size(), 1, &layers));
    const std::vector<double> tooMany = {0, 0, 0, 0, 1, 1, 0, 0, 9};
    CHECK(!parseSceneFx(tooMany.data(), tooMany.size(), 1, &layers));
    // One blob too many for the layer count.
    const std::vector<double> twice = {0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0};
    CHECK(!parseSceneFx(twice.data(), twice.size(), 1, &layers));
    CHECK(parseSceneFx(twice.data(), twice.size(), 2, &layers));
}

// ---- colour effects ----------------------------------------------------------------------------

void colourAdjustments() {
    Rgba c{0.5f, 0.25f, 0.75f, 1.0f};
    Rgba r = applyColorEffect(op(EffectType::Brightness, {0.2f}), c, 0.5f, 0.5f);
    CHECK_NEAR(r.r, 0.7);
    CHECK_NEAR(r.g, 0.45);
    CHECK_NEAR(r.b, 0.95);
    r = applyColorEffect(op(EffectType::Brightness, {-0.6f}), c, 0, 0);
    CHECK_NEAR(r.g, 0.0);  // clamped

    r = applyColorEffect(op(EffectType::Contrast, {2.0f}), c, 0, 0);
    CHECK_NEAR(r.r, 0.5);   // the pivot does not move
    CHECK_NEAR(r.g, 0.0);   // (0.25 - 0.5) * 2 + 0.5
    CHECK_NEAR(r.b, 1.0);   // clamped
    r = applyColorEffect(op(EffectType::Contrast, {0.0f}), c, 0, 0);
    CHECK_NEAR(r.r, 0.5);
    CHECK_NEAR(r.b, 0.5);

    r = applyColorEffect(op(EffectType::Saturation, {0.0f}), c, 0, 0);
    CHECK_NEAR(r.r, r.g);
    CHECK_NEAR(r.g, r.b);
    CHECK_NEAR(r.r, luma709(0.5f, 0.25f, 0.75f));
    r = applyColorEffect(op(EffectType::Saturation, {1.0f}), c, 0, 0);
    CHECK_NEAR(r.r, 0.5);
    CHECK_NEAR(r.b, 0.75);

    r = applyColorEffect(op(EffectType::Exposure, {1.0f}), Rgba{0.25f, 0.1f, 0.6f, 1.0f}, 0, 0);
    CHECK_NEAR(r.r, 0.5);
    CHECK_NEAR(r.g, 0.2);
    CHECK_NEAR(r.b, 1.0);  // 1.2 clamped
    r = applyColorEffect(op(EffectType::Exposure, {-1.0f}), Rgba{0.5f, 0.5f, 0.5f, 1.0f}, 0, 0);
    CHECK_NEAR(r.r, 0.25);

    r = applyColorEffect(op(EffectType::Temperature, {1.0f}), Rgba{0.5f, 0.5f, 0.5f, 1.0f}, 0, 0);
    CHECK_NEAR(r.r, 0.6);
    CHECK_NEAR(r.g, 0.5);
    CHECK_NEAR(r.b, 0.4);
    r = applyColorEffect(op(EffectType::Tint, {1.0f}), Rgba{0.5f, 0.5f, 0.5f, 1.0f}, 0, 0);
    CHECK_NEAR(r.g, 0.4);
    CHECK_NEAR(r.r, 0.55);
    CHECK_NEAR(r.b, 0.55);
}

void greyAndSepia() {
    const Rgba c{1.0f, 0.0f, 0.0f, 1.0f};
    Rgba r = applyColorEffect(op(EffectType::Grayscale, {1.0f}), c, 0, 0);
    CHECK_NEAR(r.r, 0.2126);
    CHECK_NEAR(r.g, 0.2126);
    r = applyColorEffect(op(EffectType::Grayscale, {0.5f}), c, 0, 0);
    CHECK_NEAR(r.r, 0.6063);
    CHECK_NEAR(r.g, 0.1063);

    r = applyColorEffect(op(EffectType::Sepia, {1.0f}), Rgba{1.0f, 1.0f, 1.0f, 1.0f}, 0, 0);
    CHECK_NEAR(r.r, 1.0);                   // 1.351 clamped
    CHECK_NEAR(r.g, 1.0);                   // 1.203 clamped
    CHECK_NEAR(r.b, 0.937);                 // 0.272 + 0.534 + 0.131
    r = applyColorEffect(op(EffectType::Sepia, {0.0f}), Rgba{0.3f, 0.4f, 0.5f, 1.0f}, 0, 0);
    CHECK_NEAR(r.r, 0.3);
    CHECK_NEAR(r.b, 0.5);
}

void vignetteDarkensTheCornersOnly() {
    const Rgba white{1.0f, 1.0f, 1.0f, 1.0f};
    const EffectOp v = op(EffectType::Vignette, {1.0f, 0.5f});
    CHECK_NEAR(applyColorEffect(v, white, 0.5f, 0.5f).r, 1.0);      // centre untouched
    CHECK_NEAR(applyColorEffect(v, white, 0.0f, 0.0f).r, 0.0);      // corner fully dark at amount 1
    const float edge = applyColorEffect(v, white, 1.0f, 0.5f).r;    // mid-edge: partly dark
    CHECK(edge > 0.0f && edge < 1.0f);
    CHECK_NEAR(applyColorEffect(op(EffectType::Vignette, {0.0f, 0.5f}), white, 0, 0).r, 1.0);
}

// ---- chroma key --------------------------------------------------------------------------------

void chromaKeyRemovesTheKeyColourAndKeepsTheRest() {
    const EffectOp key = op(EffectType::ChromaKey, {0.0f, 1.0f, 0.0f, 0.4f, 0.1f, 0.0f});
    const Rgba green = applyColorEffect(key, Rgba{0.0f, 1.0f, 0.0f, 1.0f}, 0, 0);
    CHECK_NEAR(green.a, 0.0);
    const Rgba nearGreen = applyColorEffect(key, Rgba{0.1f, 0.9f, 0.1f, 1.0f}, 0, 0);
    CHECK(nearGreen.a < 0.05f);
    const Rgba red = applyColorEffect(key, Rgba{1.0f, 0.0f, 0.0f, 1.0f}, 0, 0);
    CHECK_NEAR(red.a, 1.0);
    const Rgba skin = applyColorEffect(key, Rgba{0.9f, 0.7f, 0.6f, 1.0f}, 0, 0);
    CHECK_NEAR(skin.a, 1.0);
    // Grey has no chroma at all, and the green key is far from it in the chroma plane.
    CHECK_NEAR(applyColorEffect(key, Rgba{0.5f, 0.5f, 0.5f, 1.0f}, 0, 0).a, 1.0);
    // Existing transparency is kept.
    CHECK_NEAR(applyColorEffect(key, Rgba{1.0f, 0.0f, 0.0f, 0.5f}, 0, 0).a, 0.5);
}

void chromaKeySmoothnessGivesAGradedEdge() {
    const EffectOp soft = op(EffectType::ChromaKey, {0.0f, 1.0f, 0.0f, 0.2f, 0.6f, 0.0f});
    const EffectOp hard = op(EffectType::ChromaKey, {0.0f, 1.0f, 0.0f, 0.2f, 0.0f, 0.0f});
    const Rgba mid{0.3f, 0.8f, 0.3f, 1.0f};
    const float s = applyColorEffect(soft, mid, 0, 0).a;
    const float h = applyColorEffect(hard, mid, 0, 0).a;
    CHECK(s > 0.0f && s < 1.0f);
    CHECK(h >= s);
    // More similarity keys out more.
    const EffectOp wide = op(EffectType::ChromaKey, {0.0f, 1.0f, 0.0f, 0.9f, 0.0f, 0.0f});
    CHECK(applyColorEffect(wide, mid, 0, 0).a <= h);
}

void chromaKeySpillPullsKeyTintedPixelsToGrey() {
    const EffectOp withSpill = op(EffectType::ChromaKey, {0.0f, 1.0f, 0.0f, 0.2f, 0.0f, 1.0f});
    const EffectOp noSpill = op(EffectType::ChromaKey, {0.0f, 1.0f, 0.0f, 0.2f, 0.0f, 0.0f});
    // Just outside the keyed zone: opaque but still greenish.
    const Rgba edge{0.15f, 0.95f, 0.15f, 1.0f};
    const Rgba a = applyColorEffect(withSpill, edge, 0, 0);
    const Rgba b = applyColorEffect(noSpill, edge, 0, 0);
    CHECK_NEAR(b.g, 0.95);
    CHECK_NEAR(a.a, 1.0);
    CHECK(a.g < b.g - 0.1f);
    // Far from the key nothing changes.
    const Rgba red = applyColorEffect(withSpill, Rgba{1.0f, 0.0f, 0.0f, 1.0f}, 0, 0);
    CHECK_NEAR(red.r, 1.0);
    CHECK_NEAR(red.g, 0.0);
}

// ---- blur and sharpen --------------------------------------------------------------------------

void blurSizingIsResolutionIndependent() {
    CHECK_NEAR(blurSigmaPx(1.0f, 1080.0f), 21.6);
    CHECK_NEAR(blurSigmaPx(0.5f, 2160.0f), 21.6);
    CHECK_NEAR(blurStepTexels(2.0f), 1.0);  // small blurs sample every texel
    CHECK_NEAR(blurStepTexels(32.0f), 6.0);  // 3 sigma = 96 texels over 16 taps
    CHECK(blurSigmaPx(0.005f, 1080.0f) < kMinBlurSigmaPx);
}

void blurKernelIsSymmetricAndCoversThreeSigma() {
    const float sigma = 10.0f;
    const float step = blurStepTexels(sigma);
    float sum = 0.0f;
    for (int i = -kBlurTapsEachSide; i <= kBlurTapsEachSide; ++i) {
        CHECK_NEAR(blurWeight(i, step, sigma), blurWeight(-i, step, sigma));
        sum += blurWeight(i, step, sigma);
    }
    CHECK_NEAR(blurWeight(0, step, sigma), 1.0);
    CHECK(blurWeight(kBlurTapsEachSide, step, sigma) < 0.02f);  // ~3 sigma out
    CHECK(sum > 1.0f);
}

void sharpenAmplifiesDetailAndStaysInGamut() {
    CHECK_NEAR(sharpenPremultiplied(0.5f, 0.5f, 1.0f, 1.0f), 0.5);  // flat area unchanged
    CHECK_NEAR(sharpenPremultiplied(0.6f, 0.4f, 1.0f, 1.0f), 0.8);
    CHECK_NEAR(sharpenPremultiplied(0.9f, 0.3f, 2.0f, 1.0f), 1.0);   // clamped to alpha
    CHECK_NEAR(sharpenPremultiplied(0.1f, 0.6f, 1.0f, 1.0f), 0.0);   // clamped to zero
    CHECK_NEAR(sharpenPremultiplied(0.4f, 0.1f, 2.0f, 0.5f), 0.5);   // never above alpha
}

// ---- masks -------------------------------------------------------------------------------------

core::MaskParams mask(int shape, float cx, float cy, float w, float h, float feather, bool invert) {
    core::MaskParams m;
    m.shape = shape;
    m.cx = cx;
    m.cy = cy;
    m.w = w;
    m.h = h;
    m.feather = feather;
    m.invert = invert;
    return m;
}

void noMaskLetsEverythingThrough() {
    CHECK_NEAR(maskCoverage(core::MaskParams{}, 0.3f, -0.4f), 1.0);
}

void hardRectangleMask() {
    const auto m = mask(1, 0.0f, 0.0f, 0.5f, 0.5f, 0.0f, false);  // spans -0.25..0.25
    CHECK_NEAR(maskCoverage(m, 0.0f, 0.0f), 1.0);
    CHECK_NEAR(maskCoverage(m, 0.24f, -0.24f), 1.0);
    CHECK_NEAR(maskCoverage(m, 0.26f, 0.0f), 0.0);
    CHECK_NEAR(maskCoverage(m, 0.0f, 0.4f), 0.0);
    const auto moved = mask(1, 0.2f, 0.0f, 0.2f, 0.2f, 0.0f, false);
    CHECK_NEAR(maskCoverage(moved, 0.2f, 0.0f), 1.0);
    CHECK_NEAR(maskCoverage(moved, 0.0f, 0.0f), 0.0);
}

void ellipseMaskCornersAreOutside() {
    const auto m = mask(2, 0.0f, 0.0f, 0.8f, 0.4f, 0.0f, false);  // half-axes 0.4 and 0.2
    CHECK_NEAR(maskCoverage(m, 0.0f, 0.0f), 1.0);
    CHECK_NEAR(maskCoverage(m, 0.39f, 0.0f), 1.0);
    CHECK_NEAR(maskCoverage(m, 0.0f, 0.19f), 1.0);
    CHECK_NEAR(maskCoverage(m, 0.41f, 0.0f), 0.0);
    CHECK_NEAR(maskCoverage(m, 0.3f, 0.15f), 0.0);  // inside the bounding box, outside the ellipse
}

void featherMakesASoftEdgeCentredOnTheBoundary() {
    const auto m = mask(1, 0.0f, 0.0f, 0.5f, 0.5f, 0.1f, false);
    CHECK_NEAR(maskCoverage(m, 0.25f, 0.0f), 0.5);                 // exactly on the edge
    CHECK(maskCoverage(m, 0.20f, 0.0f) > 0.5f);                    // inside the band
    CHECK(maskCoverage(m, 0.30f, 0.0f) < 0.5f);
    CHECK_NEAR(maskCoverage(m, 0.0f, 0.0f), 1.0);                  // well inside
    CHECK_NEAR(maskCoverage(m, 0.40f, 0.0f), 0.0);                 // well outside
    // Coverage never increases moving outwards.
    float last = 1.0f;
    for (int i = 0; i <= 40; ++i) {
        const float c = maskCoverage(m, 0.1f + 0.01f * static_cast<float>(i), 0.0f);
        CHECK(c <= last + 1e-6f);
        last = c;
    }
}

void invertSwapsInsideAndOutside() {
    const auto m = mask(2, 0.0f, 0.0f, 0.5f, 0.5f, 0.05f, true);
    const auto plain = mask(2, 0.0f, 0.0f, 0.5f, 0.5f, 0.05f, false);
    for (float x : {-0.4f, -0.1f, 0.0f, 0.2f, 0.45f}) {
        CHECK_NEAR(maskCoverage(m, x, 0.1f) + maskCoverage(plain, x, 0.1f), 1.0);
    }
}

// ---- blending ----------------------------------------------------------------------------------

void blendModesMatchTheirDefinitions() {
    using core::BlendMode;
    CHECK_NEAR(blendChannel(BlendMode::Normal, 0.3f, 0.8f), 0.3);
    CHECK_NEAR(blendChannel(BlendMode::Add, 0.3f, 0.4f), 0.7);
    CHECK_NEAR(blendChannel(BlendMode::Add, 0.8f, 0.7f), 1.0);
    CHECK_NEAR(blendChannel(BlendMode::Multiply, 0.5f, 0.6f), 0.3);
    CHECK_NEAR(blendChannel(BlendMode::Multiply, 1.0f, 0.37f), 0.37);  // white is neutral
    CHECK_NEAR(blendChannel(BlendMode::Screen, 0.5f, 0.6f), 0.8);
    CHECK_NEAR(blendChannel(BlendMode::Screen, 0.0f, 0.37f), 0.37);    // black is neutral
    CHECK_NEAR(blendChannel(BlendMode::Overlay, 0.5f, 0.25f), 0.25);   // 2 * 0.5 * 0.25
    CHECK_NEAR(blendChannel(BlendMode::Overlay, 0.5f, 0.75f), 0.75);   // 1 - 2 * 0.5 * 0.25
    CHECK_NEAR(blendChannel(BlendMode::Overlay, 0.8f, 0.25f), 0.4);
    CHECK_NEAR(blendChannel(BlendMode::Overlay, 0.8f, 0.75f), 0.9);
}

void compositeFadesFromTheDestinationToTheBlend() {
    using core::BlendMode;
    CHECK_NEAR(compositeChannel(BlendMode::Multiply, 0.5f, 0.6f, 0.0f), 0.6);
    CHECK_NEAR(compositeChannel(BlendMode::Multiply, 0.5f, 0.6f, 1.0f), 0.3);
    CHECK_NEAR(compositeChannel(BlendMode::Multiply, 0.5f, 0.6f, 0.5f), 0.45);
    CHECK_NEAR(compositeChannel(BlendMode::Normal, 0.2f, 0.8f, 0.25f), 0.65);
}

}  // namespace

// A `size`^3 identity LUT, or one that swaps red and blue, in .cube order (red fastest).
std::vector<float> makeLut(int size, bool swapRedBlue) {
    std::vector<float> data;
    const float d = static_cast<float>(size - 1);
    for (int b = 0; b < size; ++b) {
        for (int g = 0; g < size; ++g) {
            for (int r = 0; r < size; ++r) {
                const float rf = r / d, gf = g / d, bf = b / d;
                data.push_back(swapRedBlue ? bf : rf);
                data.push_back(gf);
                data.push_back(swapRedBlue ? rf : bf);
            }
        }
    }
    return data;
}

void lutParsesAsAnEffect() {
    using namespace uv::core;
    // blend=0, no mask, one effect: type 13, two values (key 7, intensity 0.5).
    const std::vector<double> blob = {0, 0, 0, 0, 1, 1, 0, 0, 1, 13, 2, 7, 0.5};
    size_t offset = 0;
    LayerFx fx;
    CHECK(parseLayerFx(blob.data(), blob.size(), &offset, &fx));
    CHECK(fx.effects.size() == 1 && fx.effects[0].type == EffectType::Lut);
    CHECK(fx.effects[0].v[0] == 7.0f && fx.effects[0].v[1] == 0.5f);
    // Type 14 does not exist.
    std::vector<double> bad = blob;
    bad[9] = 14;
    offset = 0;
    CHECK(!parseLayerFx(bad.data(), bad.size(), &offset, &fx));
}

void identityLutLeavesColoursAlone() {
    for (int size : {2, 17, 33, 65}) {
        const std::vector<float> lut = makeLut(size, false);
        for (const auto& c : std::vector<std::array<float, 3>>{{0, 0, 0}, {1, 1, 1}, {0.25f, 0.5f, 0.8f}, {0.123f, 0.9f, 0.01f}}) {
            float rgb[3] = {c[0], c[1], c[2]};
            applyLut(lut.data(), size, 1.0f, rgb);
            CHECK(near(rgb[0], c[0], 1e-4f) && near(rgb[1], c[1], 1e-4f) && near(rgb[2], c[2], 1e-4f));
        }
    }
}

void lutSwapAndIntensity() {
    const std::vector<float> lut = makeLut(17, true);
    float full[3] = {0.9f, 0.5f, 0.2f};
    applyLut(lut.data(), 17, 1.0f, full);
    CHECK(near(full[0], 0.2f, 1e-4f) && near(full[1], 0.5f, 1e-4f) && near(full[2], 0.9f, 1e-4f));
    float half[3] = {0.9f, 0.5f, 0.2f};
    applyLut(lut.data(), 17, 0.5f, half);  // halfway between the input and the swap
    CHECK(near(half[0], 0.55f, 1e-4f) && near(half[2], 0.55f, 1e-4f));
    float none[3] = {0.9f, 0.5f, 0.2f};
    applyLut(lut.data(), 17, 0.0f, none);
    CHECK(near(none[0], 0.9f, 1e-6f) && near(none[2], 0.2f, 1e-6f));
}

void lutClampsItsInput() {
    const std::vector<float> lut = makeLut(2, false);
    float rgb[3] = {2.0f, -1.0f, 9.0f};
    applyLut(lut.data(), 2, 1.0f, rgb);
    CHECK(near(rgb[0], 1.0f, 1e-6f) && near(rgb[1], 0.0f, 1e-6f) && near(rgb[2], 1.0f, 1e-6f));
}

int main() {
    lutParsesAsAnEffect();
    identityLutLeavesColoursAlone();
    lutSwapAndIntensity();
    lutClampsItsInput();
    plainLayerParses();
    fullLayerParses();
    scenesParseLayersBackToBack();
    frameTablesParsePerClip();
    frameTablesRejectBadInput();
    emptyArrayMeansPlainLayers();
    malformedBlobsAreRejected();
    colourAdjustments();
    greyAndSepia();
    vignetteDarkensTheCornersOnly();
    chromaKeyRemovesTheKeyColourAndKeepsTheRest();
    chromaKeySmoothnessGivesAGradedEdge();
    chromaKeySpillPullsKeyTintedPixelsToGrey();
    blurSizingIsResolutionIndependent();
    blurKernelIsSymmetricAndCoversThreeSigma();
    sharpenAmplifiesDetailAndStaysInGamut();
    noMaskLetsEverythingThrough();
    hardRectangleMask();
    ellipseMaskCornersAreOutside();
    featherMakesASoftEdgeCentredOnTheBoundary();
    invertSwapsInsideAndOutside();
    blendModesMatchTheirDefinitions();
    compositeFadesFromTheDestinationToTheBlend();
    if (failures == 0) std::printf("effects host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
