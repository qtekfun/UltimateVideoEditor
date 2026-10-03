#pragma once

// Keyframe evaluation shared by the exporter. Kotlin mirrors it in domain/Keyframes.kt (evaluate):
// change both together; the host tests and the JVM tests check the same vectors.
//
// Times are integer clip frames. Before the first keyframe the first pose holds, after the last one
// the last pose holds. Between two keyframes the earlier one's interpolation decides the weight.

#include <cstdint>
#include <vector>

// Keep a + (b - a) * w as written, so the result matches the JVM bit for bit.
#if defined(__clang__)
#pragma clang fp contract(off)
#endif

namespace uv::core {

enum class Interpolation : int32_t { Linear = 0, Ease = 1, Hold = 2 };

// Mirrors ClipTransform: canvas pixels (+x right, +y down), clockwise degrees, opacity 0..1.
struct Pose {
    double posX = 0.0;
    double posY = 0.0;
    double scaleX = 1.0;
    double scaleY = 1.0;
    double rotationDeg = 0.0;
    double opacity = 1.0;
};

struct Keyframe {
    int64_t frame = 0;
    Pose pose;
    Interpolation interpolation = Interpolation::Linear;
};

inline double keyframeWeight(Interpolation mode, double t) {
    switch (mode) {
        case Interpolation::Ease:
            return t * t * (3.0 - 2.0 * t);
        case Interpolation::Hold:
            return 0.0;
        case Interpolation::Linear:
            break;
    }
    return t;
}

inline double keyframeLerp(double a, double b, double w) { return a + (b - a) * w; }

// `keys` must be sorted by strictly increasing frame (the Kotlin side guarantees it).
inline Pose evaluateKeyframes(const std::vector<Keyframe>& keys, int64_t frame, const Pose& base) {
    if (keys.empty()) return base;
    if (frame <= keys.front().frame) return keys.front().pose;
    if (frame >= keys.back().frame) return keys.back().pose;
    size_t index = 0;
    while (keys[index + 1].frame <= frame) ++index;
    const Keyframe& from = keys[index];
    const Keyframe& to = keys[index + 1];
    if (from.frame == frame) return from.pose;
    const double t = static_cast<double>(frame - from.frame) / static_cast<double>(to.frame - from.frame);
    const double w = keyframeWeight(from.interpolation, t);
    Pose out;
    out.posX = keyframeLerp(from.pose.posX, to.pose.posX, w);
    out.posY = keyframeLerp(from.pose.posY, to.pose.posY, w);
    out.scaleX = keyframeLerp(from.pose.scaleX, to.pose.scaleX, w);
    out.scaleY = keyframeLerp(from.pose.scaleY, to.pose.scaleY, w);
    out.rotationDeg = keyframeLerp(from.pose.rotationDeg, to.pose.rotationDeg, w);
    out.opacity = keyframeLerp(from.pose.opacity, to.pose.opacity, w);
    return out;
}

}  // namespace uv::core
