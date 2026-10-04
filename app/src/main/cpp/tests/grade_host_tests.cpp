// Host tests for the colour grade effect: wire parsing (core/layer_fx.h) and the CPU reference of its
// shader (render/grade_math.h). No Android dependencies.
#include <array>
#include <cmath>
#include <cstdio>
#include <vector>

#include "core/layer_fx.h"
#include "render/effect_math.h"
#include "render/grade_math.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                      \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                  \
        }                                                                \
    } while (0)

#define CHECK_NEAR(actual, expected, eps)                                                                \
    do {                                                                                                 \
        const double a_ = static_cast<double>(actual);                                                   \
        const double e_ = static_cast<double>(expected);                                                 \
        if (std::fabs(a_ - e_) > (eps)) {                                                                \
            std::printf("FAIL %s:%d: %s = %f, expected %f\n", __FILE__, __LINE__, #actual, a_, e_);      \
            ++failures;                                                                                  \
        }                                                                                                \
    } while (0)

using namespace uv;
using namespace uv::core;
using namespace uv::render;

// A neutral grade: every wheel at 0, contrast and saturation 1, pivot 0.5, identity curves.
std::vector<float> identityGrade() {
    std::vector<float> g(static_cast<size_t>(kGradeWireValues), 0.0f);
    g[15] = 1.0f;
    g[16] = 0.5f;
    g[17] = 1.0f;
    for (int i = 0; i < kGradeCurveSamples; ++i) {
        const float x = static_cast<float>(i) / static_cast<float>(kGradeCurveSamples - 1);
        for (int c = 0; c < 4; ++c) g[static_cast<size_t>(kGradeParams + i * 4 + c)] = x;
    }
    return g;
}

std::array<float, 3> grade(const std::vector<float>& g, float r, float gg, float b) {
    float rgb[3] = {r, gg, b};
    applyGrade(g.data(), rgb);
    return {rgb[0], rgb[1], rgb[2]};
}

void identityLeavesColoursAlone() {
    const auto g = identityGrade();
    const float samples[][3] = {{0, 0, 0}, {1, 1, 1}, {0.25f, 0.5f, 0.75f}, {0.9f, 0.1f, 0.4f}, {0.5f, 0.5f, 0.5f}};
    for (const auto& s : samples) {
        const auto out = grade(g, s[0], s[1], s[2]);
        CHECK_NEAR(out[0], s[0], 2e-5);
        CHECK_NEAR(out[1], s[1], 2e-5);
        CHECK_NEAR(out[2], s[2], 2e-5);
    }
}

void gainIsAStopPerUnit() {
    auto g = identityGrade();
    g[11] = 1.0f;  // master gain +1 stop
    CHECK_NEAR(grade(g, 0.25f, 0.25f, 0.25f)[0], 0.5, 1e-5);
    CHECK_NEAR(grade(g, 0.75f, 0.75f, 0.75f)[0], 1.0, 1e-5);  // clamped
    g = identityGrade();
    g[8] = 1.0f;  // red-only gain
    const auto out = grade(g, 0.25f, 0.25f, 0.25f);
    CHECK_NEAR(out[0], 0.5, 1e-5);
    CHECK_NEAR(out[1], 0.25, 1e-5);
}

void liftRaisesBlacksNotWhites() {
    auto g = identityGrade();
    g[3] = 1.0f;  // master lift: 0.5 * (1 + 0)
    CHECK_NEAR(grade(g, 0.0f, 0.0f, 0.0f)[1], 0.5, 1e-5);
    CHECK_NEAR(grade(g, 1.0f, 1.0f, 1.0f)[1], 1.0, 1e-5);
    CHECK_NEAR(grade(g, 0.5f, 0.5f, 0.5f)[1], 0.75, 1e-5);
}

void gammaBrightensMidtonesAndKeepsEnds() {
    auto g = identityGrade();
    g[7] = 1.0f;  // exponent 2^-1 = 0.5
    CHECK_NEAR(grade(g, 0.5f, 0.5f, 0.5f)[0], std::sqrt(0.5), 1e-5);
    CHECK_NEAR(grade(g, 0.0f, 0.0f, 0.0f)[0], 0.0, 1e-6);
    CHECK_NEAR(grade(g, 1.0f, 1.0f, 1.0f)[0], 1.0, 1e-6);
}

void contrastPivotsAndOffsetAdds() {
    auto g = identityGrade();
    g[15] = 2.0f;
    CHECK_NEAR(grade(g, 0.6f, 0.6f, 0.6f)[0], 0.7, 1e-5);
    CHECK_NEAR(grade(g, 0.5f, 0.5f, 0.5f)[0], 0.5, 1e-5);
    g[16] = 0.25f;  // pivot moves
    CHECK_NEAR(grade(g, 0.25f, 0.25f, 0.25f)[0], 0.25, 1e-5);
    g = identityGrade();
    g[12] = 0.1f;
    g[14] = -0.1f;
    const auto out = grade(g, 0.4f, 0.4f, 0.4f);
    CHECK_NEAR(out[0], 0.5, 1e-5);
    CHECK_NEAR(out[1], 0.4, 1e-5);
    CHECK_NEAR(out[2], 0.3, 1e-5);
}

void saturationAndVibrance() {
    auto g = identityGrade();
    g[17] = 0.0f;
    const auto grey = grade(g, 1.0f, 0.0f, 0.0f);
    CHECK_NEAR(grey[0], 0.2126, 1e-4);
    CHECK_NEAR(grey[1], 0.2126, 1e-4);
    CHECK_NEAR(grey[2], 0.2126, 1e-4);
    // Vibrance lifts a muted colour more than a vivid one.
    g = identityGrade();
    g[18] = 1.0f;
    const auto muted = grade(g, 0.6f, 0.5f, 0.5f);
    const auto vivid = grade(g, 1.0f, 0.1f, 0.1f);
    const float mutedGain = (muted[0] - muted[1]) / 0.1f;
    const float vividGain = (vivid[0] - vivid[1]) / 0.9f;
    CHECK(mutedGain > 1.5f);
    CHECK(vividGain < mutedGain);
}

void whiteBalanceWarmsAndTints() {
    auto g = identityGrade();
    g[19] = 1.0f;
    const auto warm = grade(g, 0.5f, 0.5f, 0.5f);
    CHECK_NEAR(warm[0], 0.6, 1e-5);
    CHECK_NEAR(warm[2], 0.4, 1e-5);
    g = identityGrade();
    g[20] = 1.0f;
    const auto magenta = grade(g, 0.5f, 0.5f, 0.5f);
    CHECK_NEAR(magenta[1], 0.4, 1e-5);
    CHECK_NEAR(magenta[0], 0.55, 1e-5);
}

void curvesMapThroughMasterThenChannel() {
    auto g = identityGrade();
    for (int i = 0; i < kGradeCurveSamples; ++i) {
        g[static_cast<size_t>(kGradeParams + i * 4)] = 1.0f - static_cast<float>(i) / 32.0f;  // master inverts
    }
    const auto inv = grade(g, 0.25f, 0.5f, 1.0f);
    CHECK_NEAR(inv[0], 0.75, 1e-5);
    CHECK_NEAR(inv[1], 0.5, 1e-5);
    CHECK_NEAR(inv[2], 0.0, 1e-5);
    g = identityGrade();
    for (int i = 0; i < kGradeCurveSamples; ++i) {
        g[static_cast<size_t>(kGradeParams + i * 4 + 1)] = 0.5f;  // red curve flat at 0.5
    }
    const auto flatRed = grade(g, 0.9f, 0.9f, 0.9f);
    CHECK_NEAR(flatRed[0], 0.5, 1e-5);
    CHECK_NEAR(flatRed[1], 0.9, 1e-5);
    // Between samples the lookup is linear: x = 0.015625 is halfway between samples 0 and 1.
    g = identityGrade();
    g[static_cast<size_t>(kGradeParams + 1 * 4)] = 0.2f;  // master sample 1 pulled up from 0.03125
    CHECK_NEAR(grade(g, 0.015625f, 0.015625f, 0.015625f)[0], 0.1, 1e-5);
}

void outputAlwaysInRange() {
    auto g = identityGrade();
    for (int i = 0; i < 12; ++i) g[static_cast<size_t>(i)] = (i % 2 == 0) ? 1.0f : -1.0f;
    g[15] = 2.0f;
    g[17] = 2.0f;
    g[18] = 1.0f;
    for (float v = 0.0f; v <= 1.0f; v += 0.125f) {
        const auto out = grade(g, v, 1.0f - v, 0.5f);
        for (float c : out) CHECK(c >= 0.0f && c <= 1.0f && c == c);
    }
}

// ---- wire format ------------------------------------------------------------------------------

std::vector<double> gradeBlob(const std::vector<float>& g, int count) {
    std::vector<double> blob = {0, 0, 0, 0, 1, 1, 0, 0, 1};  // plain layer header with one effect
    blob.push_back(14);
    blob.push_back(static_cast<double>(count));
    for (int i = 0; i < count; ++i) blob.push_back(i < static_cast<int>(g.size()) ? g[static_cast<size_t>(i)] : 0.0);
    return blob;
}

void gradeBlobParses() {
    const auto g = identityGrade();
    const auto blob = gradeBlob(g, kGradeWireValues);
    size_t offset = 0;
    LayerFx fx;
    CHECK(parseLayerFx(blob.data(), blob.size(), &offset, &fx));
    CHECK(offset == blob.size());
    CHECK(fx.effects.size() == 1);
    CHECK(fx.effects[0].type == EffectType::ColorGrade);
    CHECK(fx.effects[0].grade == g);
    CHECK(!fx.neutral());
}

void gradeBlobWithWrongCountIsRejected() {
    const auto g = identityGrade();
    for (const int count : {0, 21, kGradeWireValues - 1, 6}) {
        const auto blob = gradeBlob(g, count);
        size_t offset = 0;
        LayerFx fx;
        CHECK(!parseLayerFx(blob.data(), blob.size(), &offset, &fx));
    }
    auto bad = gradeBlob(g, kGradeWireValues);
    bad[12] = std::nan("");
    size_t offset = 0;
    LayerFx fx;
    CHECK(!parseLayerFx(bad.data(), bad.size(), &offset, &fx));
}

void gradeGoesThroughTheEffectChainMaths() {
    EffectOp op;
    op.type = EffectType::ColorGrade;
    op.grade = identityGrade();
    op.grade[11] = 1.0f;  // +1 stop
    const Rgba out = applyColorEffect(op, Rgba{0.2f, 0.2f, 0.2f, 0.7f}, 0.5f, 0.5f);
    CHECK_NEAR(out.r, 0.4, 1e-5);
    CHECK_NEAR(out.a, 0.7, 1e-6);  // alpha untouched
    EffectOp empty;
    empty.type = EffectType::ColorGrade;  // missing payload: leaves the pixel alone
    const Rgba same = applyColorEffect(empty, Rgba{0.2f, 0.3f, 0.4f, 1.0f}, 0.5f, 0.5f);
    CHECK_NEAR(same.g, 0.3, 1e-6);
}

void equalityIncludesTheGrade() {
    EffectOp a;
    a.type = EffectType::ColorGrade;
    a.grade = identityGrade();
    EffectOp b = a;
    CHECK(a == b);
    b.grade[3] = 0.1f;
    CHECK(!(a == b));
}

}  // namespace

int main() {
    identityLeavesColoursAlone();
    gainIsAStopPerUnit();
    liftRaisesBlacksNotWhites();
    gammaBrightensMidtonesAndKeepsEnds();
    contrastPivotsAndOffsetAdds();
    saturationAndVibrance();
    whiteBalanceWarmsAndTints();
    curvesMapThroughMasterThenChannel();
    outputAlwaysInRange();
    gradeBlobParses();
    gradeBlobWithWrongCountIsRejected();
    gradeGoesThroughTheEffectChainMaths();
    equalityIncludesTheGrade();
    if (failures == 0) std::printf("grade host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
