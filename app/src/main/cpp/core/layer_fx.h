#pragma once

// Per-layer look: shader effects, blend mode and mask, as the compositor and the exporter receive
// them. Kotlin mirrors the model in domain/Effects.kt and writes the wire form in
// engine/fx/FxWire.kt; change those together.
//
// Wire form, a flat array of doubles per layer, layers back to back (so a scene's fx array has
// exactly the layers' blobs concatenated and no separators):
//   blend, maskShape (0 none, 1 rectangle, 2 ellipse), maskCx, maskCy, maskW, maskH, maskFeather,
//   maskInvert (0/1), effectCount, then per effect: type, valueCount, values...

#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace uv::core {

enum class BlendMode : int { Normal = 0, Add = 1, Multiply = 2, Screen = 3, Overlay = 4 };

// Same numbering as domain/EffectType.code.
enum class EffectType : int {
    Brightness = 1,
    Contrast = 2,
    Saturation = 3,
    Exposure = 4,
    Temperature = 5,
    Tint = 6,
    Blur = 7,
    Sharpen = 8,
    Vignette = 9,
    Grayscale = 10,
    Sepia = 11,
    ChromaKey = 12,
    // v[0] = library key of an uploaded 3D LUT (uploadLut), v[1] = intensity 0..1. A missing LUT is skipped.
    Lut = 13,
    // The colour grade: kGradeParams values then kGradeCurveSamples x 4 baked curve samples, in `grade`
    // (not `v`). See render/grade_math.h for the layout and the maths.
    ColorGrade = 14,
};

inline constexpr int kMaxEffectValues = 6;
inline constexpr int kMaxEffectsPerLayer = 8;

// Colour grade wire layout: 21 parameters, then 33 curve samples of (master, red, green, blue).
inline constexpr int kGradeParams = 21;
inline constexpr int kGradeCurveSamples = 33;
inline constexpr int kGradeWireValues = kGradeParams + kGradeCurveSamples * 4;

struct EffectOp {
    EffectType type = EffectType::Brightness;
    float v[kMaxEffectValues] = {0, 0, 0, 0, 0, 0};
    std::vector<float> grade;  // ColorGrade only: kGradeWireValues floats

    bool operator==(const EffectOp& o) const {
        if (type != o.type) return false;
        for (int i = 0; i < kMaxEffectValues; ++i) {
            if (v[i] != o.v[i]) return false;
        }
        return grade == o.grade;
    }
};

// Fractions of the layer box, which spans -0.5..0.5 on both axes (+x right, +y down).
struct MaskParams {
    int shape = 0;  // 0 none, 1 rectangle, 2 ellipse
    float cx = 0.0f;
    float cy = 0.0f;
    float w = 1.0f;
    float h = 1.0f;
    float feather = 0.0f;
    bool invert = false;

    bool operator==(const MaskParams& o) const {
        return shape == o.shape && cx == o.cx && cy == o.cy && w == o.w && h == o.h && feather == o.feather &&
               invert == o.invert;
    }
};

struct LayerFx {
    BlendMode blend = BlendMode::Normal;
    MaskParams mask;
    std::vector<EffectOp> effects;

    bool neutral() const { return blend == BlendMode::Normal && mask.shape == 0 && effects.empty(); }
    bool operator==(const LayerFx& o) const {
        return blend == o.blend && mask == o.mask && effects == o.effects;
    }
};

constexpr size_t kLayerFxHeaderDoubles = 9;

// Reads one layer's blob from `data[*offset...]` (of `size` doubles) and advances `*offset`.
// Returns false, leaving `*out` unspecified, when the blob is truncated or holds values that cannot
// be rendered (unknown blend/effect/mask code, wrong value count, NaN, too many effects).
inline bool parseLayerFx(const double* data, size_t size, size_t* offset, LayerFx* out) {
    size_t at = *offset;
    if (data == nullptr || size < at || size - at < kLayerFxHeaderDoubles) return false;
    const double* h = data + at;
    for (size_t i = 0; i < kLayerFxHeaderDoubles; ++i) {
        if (!(h[i] == h[i])) return false;  // NaN
    }
    const int blend = static_cast<int>(h[0]);
    const int shape = static_cast<int>(h[1]);
    const int count = static_cast<int>(h[8]);
    if (blend < 0 || blend > 4 || shape < 0 || shape > 2 || count < 0 || count > kMaxEffectsPerLayer) return false;
    LayerFx fx;
    fx.blend = static_cast<BlendMode>(blend);
    fx.mask.shape = shape;
    fx.mask.cx = static_cast<float>(h[2]);
    fx.mask.cy = static_cast<float>(h[3]);
    fx.mask.w = static_cast<float>(h[4]);
    fx.mask.h = static_cast<float>(h[5]);
    fx.mask.feather = static_cast<float>(h[6]);
    fx.mask.invert = h[7] != 0.0;
    at += kLayerFxHeaderDoubles;
    for (int i = 0; i < count; ++i) {
        if (size - at < 2) return false;
        const int type = static_cast<int>(data[at]);
        const int n = static_cast<int>(data[at + 1]);
        at += 2;
        if (type < 1 || type > 14 || n < 0 || size - at < static_cast<size_t>(n)) return false;
        EffectOp op;
        op.type = static_cast<EffectType>(type);
        if (type == static_cast<int>(EffectType::ColorGrade)) {
            if (n != kGradeWireValues) return false;
            op.grade.resize(static_cast<size_t>(n));
            for (int k = 0; k < n; ++k) {
                const double v = data[at + static_cast<size_t>(k)];
                if (!(v == v)) return false;
                op.grade[static_cast<size_t>(k)] = static_cast<float>(v);
            }
            at += static_cast<size_t>(n);
            fx.effects.push_back(std::move(op));
            continue;
        }
        if (n > kMaxEffectValues) return false;
        for (int k = 0; k < n; ++k) {
            const double v = data[at + static_cast<size_t>(k)];
            if (!(v == v)) return false;
            op.v[k] = static_cast<float>(v);
        }
        at += static_cast<size_t>(n);
        fx.effects.push_back(op);
    }
    *out = std::move(fx);
    *offset = at;
    return true;
}

// Parses `layerCount` consecutive blobs. A null/empty array means every layer is plain. Returns false
// when the array does not hold exactly that many valid blobs.
inline bool parseSceneFx(const double* data, size_t size, size_t layerCount, std::vector<LayerFx>* out) {
    out->assign(layerCount, LayerFx{});
    if (size == 0) return true;
    size_t offset = 0;
    for (size_t i = 0; i < layerCount; ++i) {
        if (!parseLayerFx(data, size, &offset, &(*out)[i])) return false;
    }
    return offset == size;
}

}  // namespace uv::core
