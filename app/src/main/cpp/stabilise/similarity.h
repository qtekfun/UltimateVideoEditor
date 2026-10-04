#pragma once

// 2D similarity transforms (translation, rotation, uniform scale) and their estimation from point
// pairs. Shared by the stabiliser and the motion tracker (SPECS.md 9.6 and 9.15).
//
// Coordinates are image coordinates with y down. A Similarity maps
//     x' = a*x - b*y + tx
//     y' = b*x + a*y + ty
// so a = s*cos(theta) and b = s*sin(theta): theta is the clockwise rotation on screen.

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace uv::stab {

struct Vec2 {
    float x = 0.0f;
    float y = 0.0f;
};

struct Similarity {
    double a = 1.0;
    double b = 0.0;
    double tx = 0.0;
    double ty = 0.0;

    double scale() const { return std::hypot(a, b); }
    double angle() const { return std::atan2(b, a); }

    Vec2 apply(Vec2 p) const {
        return {static_cast<float>(a * p.x - b * p.y + tx), static_cast<float>(b * p.x + a * p.y + ty)};
    }

    static Similarity fromParams(double scale, double theta, double tx, double ty) {
        return {scale * std::cos(theta), scale * std::sin(theta), tx, ty};
    }

    // The transform that undoes this one; the scale must not be zero.
    Similarity inverse() const {
        const double d = a * a + b * b;
        const double ia = a / d;
        const double ib = -b / d;
        return {ia, ib, -(ia * tx - ib * ty), -(ib * tx + ia * ty)};
    }
};

// outer o inner: applies `inner` first.
inline Similarity compose(const Similarity& outer, const Similarity& inner) {
    return {outer.a * inner.a - outer.b * inner.b, outer.a * inner.b + outer.b * inner.a,
            outer.a * inner.tx - outer.b * inner.ty + outer.tx, outer.b * inner.tx + outer.a * inner.ty + outer.ty};
}

// Least-squares similarity taking src[i] to dst[i] (closed form over centred points). Needs at least
// two distinct source points.
inline bool fitSimilarity(const Vec2* src, const Vec2* dst, size_t n, Similarity* out) {
    if (n < 2 || src == nullptr || dst == nullptr) return false;
    double mx = 0, my = 0, nx = 0, ny = 0;
    for (size_t i = 0; i < n; ++i) {
        mx += src[i].x;
        my += src[i].y;
        nx += dst[i].x;
        ny += dst[i].y;
    }
    const double inv = 1.0 / static_cast<double>(n);
    mx *= inv;
    my *= inv;
    nx *= inv;
    ny *= inv;
    double sa = 0, sb = 0, denom = 0;
    for (size_t i = 0; i < n; ++i) {
        const double x = src[i].x - mx, y = src[i].y - my;
        const double u = dst[i].x - nx, v = dst[i].y - ny;
        sa += x * u + y * v;
        sb += x * v - y * u;
        denom += x * x + y * y;
    }
    if (denom < 1e-9) return false;
    Similarity s;
    s.a = sa / denom;
    s.b = sb / denom;
    s.tx = nx - (s.a * mx - s.b * my);
    s.ty = ny - (s.b * mx + s.a * my);
    *out = s;
    return true;
}

struct RansacOptions {
    int iterations = 120;
    double threshold = 1.5;  // inlier residual, in the units of the points
    int minInliers = 8;
    double minScale = 0.5;   // a frame-to-frame model outside this range is rejected as degenerate
    double maxScale = 2.0;
    uint64_t seed = 1;       // fixed by default so analysis is deterministic
};

struct RansacResult {
    Similarity model;
    std::vector<uint8_t> inlier;
    int inliers = 0;
};

namespace detail {
inline uint64_t nextRandom(uint64_t* state) {
    uint64_t x = *state;
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    *state = x;
    return x * 2685821657736338717ULL;
}

inline int countInliers(const Similarity& m, const std::vector<Vec2>& src, const std::vector<Vec2>& dst, double threshold,
                        std::vector<uint8_t>* mask) {
    int count = 0;
    const double t2 = threshold * threshold;
    if (mask != nullptr) mask->assign(src.size(), 0);
    for (size_t i = 0; i < src.size(); ++i) {
        const Vec2 p = m.apply(src[i]);
        const double dx = p.x - dst[i].x, dy = p.y - dst[i].y;
        if (dx * dx + dy * dy <= t2) {
            ++count;
            if (mask != nullptr) (*mask)[i] = 1;
        }
    }
    return count;
}
}  // namespace detail

// Robust similarity from noisy correspondences (some of which are wrong or on moving objects): 2-point
// hypotheses, best consensus, then a least-squares refit on the inliers. Deterministic for a given seed.
inline bool estimateSimilarityRansac(const std::vector<Vec2>& src, const std::vector<Vec2>& dst, const RansacOptions& options,
                                     RansacResult* out) {
    const size_t n = src.size();
    if (n != dst.size() || n < 2 || static_cast<int>(n) < options.minInliers) return false;
    uint64_t state = options.seed != 0 ? options.seed : 1;
    Similarity best;
    int bestCount = -1;
    for (int it = 0; it < options.iterations; ++it) {
        const size_t i = detail::nextRandom(&state) % n;
        size_t j = detail::nextRandom(&state) % n;
        if (j == i) j = (j + 1) % n;
        const double dx = src[j].x - src[i].x, dy = src[j].y - src[i].y;
        const double len2 = dx * dx + dy * dy;
        if (len2 < 4.0) continue;  // too close together to give a stable rotation
        const Vec2 pair[2] = {src[i], src[j]};
        const Vec2 target[2] = {dst[i], dst[j]};
        Similarity m;
        if (!fitSimilarity(pair, target, 2, &m)) continue;
        const double s = m.scale();
        if (s < options.minScale || s > options.maxScale) continue;
        const int count = detail::countInliers(m, src, dst, options.threshold, nullptr);
        if (count > bestCount) {
            bestCount = count;
            best = m;
        }
    }
    if (bestCount < options.minInliers) return false;

    // Refit on the consensus set, twice, so a few borderline points do not bias the result.
    RansacResult result;
    result.model = best;
    for (int pass = 0; pass < 2; ++pass) {
        std::vector<Vec2> s, d;
        std::vector<uint8_t> mask;
        detail::countInliers(result.model, src, dst, options.threshold, &mask);
        for (size_t i = 0; i < n; ++i) {
            if (mask[i]) {
                s.push_back(src[i]);
                d.push_back(dst[i]);
            }
        }
        Similarity refined;
        if (s.size() < 2 || !fitSimilarity(s.data(), d.data(), s.size(), &refined)) break;
        const double sc = refined.scale();
        if (sc < options.minScale || sc > options.maxScale) break;
        result.model = refined;
    }
    result.inliers = detail::countInliers(result.model, src, dst, options.threshold, &result.inlier);
    if (result.inliers < options.minInliers) return false;
    *out = std::move(result);
    return true;
}

}  // namespace uv::stab
