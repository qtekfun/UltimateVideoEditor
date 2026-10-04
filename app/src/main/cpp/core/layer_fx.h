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
    // Camera-shake correction (stabiliser), always first in a layer's list. Wire values: v[0] = table key
    // (stabilise/stab_registry.h), v[1] = edge mode (1 repeats the border pixels, 0 leaves them transparent),
    // v[2..5] = dx, dy (height units), theta (radians, clockwise), scale: the correction of the frame being
    // drawn, filled in by resolveStabilisation() from the registered table (the wire carries placeholders).
    Stabilise = 15,
    // Noise reduction: v[0] = spatial strength 0..1, v[1] = temporal strength 0..1 (blend with the previous
    // source frame, faded out where the picture moved). See render/repair_math.h.
    Denoise = 16,
    // Flicker removal: v[0] = strength 0..1; scales the frame so its mean luma follows the mean over the
    // previous, current and next source frame.
    Deflicker = 17,
    // Secondary colour correction: an HSL key (hue range, saturation range, luma range, each with softness) builds a
    // matte and the hue shift / saturation gain / lightness correction applies only where it is open. kQualifierParams
    // values in `grade` (not `v`); see render/qualifier_math.h for the layout and the maths.
    Qualifier = 18,
};

inline constexpr int kMaxEffectValues = 6;
inline constexpr int kMaxEffectsPerLayer = 8;
// A layer may also carry the stabiliser's effect on top of its user effects.
inline constexpr int kMaxWireEffectsPerLayer = kMaxEffectsPerLayer + 1;

// Colour grade wire layout: 21 parameters, then 33 curve samples of (master, red, green, blue).
inline constexpr int kGradeParams = 21;
inline constexpr int kGradeCurveSamples = 33;
inline constexpr int kGradeWireValues = kGradeParams + kGradeCurveSamples * 4;

// Qualifier wire layout: 14 parameters (render/qualifier_math.h), carried in `grade` like the colour grade's.
inline constexpr int kQualifierParams = 14;

struct EffectOp {
    EffectType type = EffectType::Brightness;
    float v[kMaxEffectValues] = {0, 0, 0, 0, 0, 0};
    std::vector<float> grade;  // ColorGrade (kGradeWireValues floats) and Qualifier (kQualifierParams floats) only

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

// Which neighbouring source frames a layer's effects read besides the frame shown: the previous one for noise
// reduction with a temporal part and for flicker removal, the next one for flicker removal. The preview and
// the exporter fetch them (when decoded) and hand them to the compositor.
struct NeighbourNeeds {
    bool prev = false;
    bool next = false;
};

inline NeighbourNeeds neighbourNeeds(const LayerFx& fx) {
    NeighbourNeeds needs;
    for (const EffectOp& op : fx.effects) {
        if (op.type == EffectType::Denoise && op.v[1] > 0.0f && op.v[0] + op.v[1] > 0.0f) needs.prev = true;
        if (op.type == EffectType::Deflicker && op.v[0] > 0.0f) needs.prev = needs.next = true;
    }
    return needs;
}

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
    if (blend < 0 || blend > 4 || shape < 0 || shape > 2 || count < 0 || count > kMaxWireEffectsPerLayer) return false;
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
        if (type < 1 || type > 18 || n < 0 || size - at < static_cast<size_t>(n)) return false;
        EffectOp op;
        op.type = static_cast<EffectType>(type);
        if (type == static_cast<int>(EffectType::ColorGrade) || type == static_cast<int>(EffectType::Qualifier)) {
            if (n != (type == static_cast<int>(EffectType::Qualifier) ? kQualifierParams : kGradeWireValues)) return false;
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

// Keyframed effect values for the exporter: `pairs` lists {clipIndex, frameCount} for each clip whose effects
// change over its length, and `data` holds, in that order, `frameCount` consecutive blobs per entry (one per
// project frame of the clip). `(*out)[clipIndex]` receives the table; clips not listed get an empty one.
// Returns false when an index is out of range or repeated, a count is negative, or the blobs do not fill
// `data` exactly.
inline bool parseFxFrameTables(const int64_t* pairs, size_t pairLongs, const double* data, size_t size, size_t clipCount,
                               std::vector<std::vector<LayerFx>>* out) {
    out->assign(clipCount, std::vector<LayerFx>{});
    if (pairLongs == 0) return size == 0;
    if (pairs == nullptr || pairLongs % 2 != 0) return false;
    size_t offset = 0;
    for (size_t p = 0; p < pairLongs; p += 2) {
        const int64_t index = pairs[p];
        const int64_t count = pairs[p + 1];
        if (index < 0 || static_cast<uint64_t>(index) >= clipCount || count < 0) return false;
        auto& table = (*out)[static_cast<size_t>(index)];
        if (!table.empty()) return false;
        // Never trust the count to size an allocation: every blob is at least a header long.
        if (static_cast<uint64_t>(count) > (size - offset) / kLayerFxHeaderDoubles) return false;
        table.resize(static_cast<size_t>(count));
        for (auto& layer : table) {
            if (!parseLayerFx(data, size, &offset, &layer)) return false;
        }
    }
    return offset == size;
}

}  // namespace uv::core
