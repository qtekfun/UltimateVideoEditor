#include "stabilise/tracker.h"

#include <algorithm>
#include <cmath>

namespace uv::stab {

namespace {

// Summed-area table over `values` (w x h), (w+1) x (h+1) entries.
std::vector<double> integral(const std::vector<float>& values, int w, int h) {
    std::vector<double> sat(static_cast<size_t>(w + 1) * static_cast<size_t>(h + 1), 0.0);
    for (int y = 0; y < h; ++y) {
        double row = 0.0;
        for (int x = 0; x < w; ++x) {
            row += values[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)];
            sat[static_cast<size_t>(y + 1) * static_cast<size_t>(w + 1) + static_cast<size_t>(x + 1)] =
                sat[static_cast<size_t>(y) * static_cast<size_t>(w + 1) + static_cast<size_t>(x + 1)] + row;
        }
    }
    return sat;
}

double boxSum(const std::vector<double>& sat, int w, int x0, int y0, int x1, int y1) {  // [x0,x1) x [y0,y1)
    const size_t stride = static_cast<size_t>(w + 1);
    return sat[static_cast<size_t>(y1) * stride + static_cast<size_t>(x1)] - sat[static_cast<size_t>(y0) * stride + static_cast<size_t>(x1)] -
           sat[static_cast<size_t>(y1) * stride + static_cast<size_t>(x0)] + sat[static_cast<size_t>(y0) * stride + static_cast<size_t>(x0)];
}

struct Candidate {
    int x;
    int y;
    float score;
};

}  // namespace

std::vector<Vec2> detectCorners(const Gray& image, const CornerOptions& options, const IntRect* roi) {
    std::vector<Vec2> result;
    if (image.w < 8 || image.h < 8) return result;
    IntRect r{0, 0, image.w, image.h};
    if (roi != nullptr) {
        r.x0 = std::clamp(roi->x0, 0, image.w);
        r.y0 = std::clamp(roi->y0, 0, image.h);
        r.x1 = std::clamp(roi->x1, 0, image.w);
        r.y1 = std::clamp(roi->y1, 0, image.h);
    }
    const int w = r.x1 - r.x0;
    const int h = r.y1 - r.y0;
    if (w < 8 || h < 8) return result;

    const size_t count = static_cast<size_t>(w) * static_cast<size_t>(h);
    std::vector<float> ixx(count), ixy(count), iyy(count);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const int gx = r.x0 + x, gy = r.y0 + y;
            const float dx = 0.5f * (image.at(gx + 1, gy) - image.at(gx - 1, gy));
            const float dy = 0.5f * (image.at(gx, gy + 1) - image.at(gx, gy - 1));
            const size_t i = static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x);
            ixx[i] = dx * dx;
            ixy[i] = dx * dy;
            iyy[i] = dy * dy;
        }
    }
    const std::vector<double> sxx = integral(ixx, w, h), sxy = integral(ixy, w, h), syy = integral(iyy, w, h);

    constexpr int kHalf = 2;
    std::vector<float> score(count, 0.0f);
    float best = 0.0f;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const int x0 = std::max(0, x - kHalf), y0 = std::max(0, y - kHalf);
            const int x1 = std::min(w, x + kHalf + 1), y1 = std::min(h, y + kHalf + 1);
            const double a = boxSum(sxx, w, x0, y0, x1, y1), b = boxSum(sxy, w, x0, y0, x1, y1), c = boxSum(syy, w, x0, y0, x1, y1);
            const double tr = a + c;
            const double disc = std::sqrt(std::max(0.0, (a - c) * (a - c) + 4.0 * b * b));
            const float lambda = static_cast<float>(0.5 * (tr - disc));
            score[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)] = lambda;
            best = std::max(best, lambda);
        }
    }
    if (best <= 0.0f) return result;
    const float threshold = best * options.quality;

    // Candidates: 3x3 local maxima above the threshold, bucketed on a grid.
    const int cols = std::max(1, options.gridCols), rows = std::max(1, options.gridRows);
    std::vector<std::vector<Candidate>> cells(static_cast<size_t>(cols) * static_cast<size_t>(rows));
    for (int y = 1; y + 1 < h; ++y) {
        for (int x = 1; x + 1 < w; ++x) {
            const float s = score[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)];
            if (s < threshold) continue;
            bool maximum = true;
            for (int dy = -1; dy <= 1 && maximum; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    if ((dx != 0 || dy != 0) && score[static_cast<size_t>(y + dy) * static_cast<size_t>(w) + static_cast<size_t>(x + dx)] > s) {
                        maximum = false;
                        break;
                    }
                }
            }
            if (!maximum) continue;
            const int cx = std::min(cols - 1, x * cols / w), cy = std::min(rows - 1, y * rows / h);
            cells[static_cast<size_t>(cy) * static_cast<size_t>(cols) + static_cast<size_t>(cx)].push_back({x, y, s});
        }
    }

    const int perCell = std::max(1, (options.maxCorners + cols * rows - 1) / (cols * rows));
    const int minDist = std::max(1, options.minDistance);
    const int occW = (image.w + minDist - 1) / minDist, occH = (image.h + minDist - 1) / minDist;
    std::vector<std::vector<Vec2>> occupancy(static_cast<size_t>(occW) * static_cast<size_t>(occH));
    const auto free = [&](float px, float py) {
        const int ox = static_cast<int>(px) / minDist, oy = static_cast<int>(py) / minDist;
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                const int cx = ox + dx, cy = oy + dy;
                if (cx < 0 || cy < 0 || cx >= occW || cy >= occH) continue;
                for (const Vec2& q : occupancy[static_cast<size_t>(cy) * static_cast<size_t>(occW) + static_cast<size_t>(cx)]) {
                    const float ddx = q.x - px, ddy = q.y - py;
                    if (ddx * ddx + ddy * ddy < static_cast<float>(minDist * minDist)) return false;
                }
            }
        }
        return true;
    };
    for (auto& cell : cells) {
        std::sort(cell.begin(), cell.end(), [](const Candidate& a, const Candidate& b) { return a.score > b.score; });
        int taken = 0;
        for (const Candidate& c : cell) {
            if (taken >= perCell || static_cast<int>(result.size()) >= options.maxCorners) break;
            const float px = static_cast<float>(r.x0 + c.x), py = static_cast<float>(r.y0 + c.y);
            if (!free(px, py)) continue;
            const Vec2 p{px, py};
            result.push_back(p);
            occupancy[static_cast<size_t>(static_cast<int>(py) / minDist) * static_cast<size_t>(occW) + static_cast<size_t>(static_cast<int>(px) / minDist)].push_back(p);
            ++taken;
        }
    }
    return result;
}

namespace {

// Pixel-centre position at pyramid level `level` of level-0 position `p`.
inline float toLevel(float p, int level) { return (p + 0.5f) / static_cast<float>(1 << level) - 0.5f; }

// One point, one direction. Returns false when the point cannot be followed.
bool lkOne(const std::vector<Gray>& prev, const std::vector<Gray>& next, Vec2 p0, Vec2 guess, Vec2* out, const LkOptions& o) {
    const int levels = static_cast<int>(std::min(prev.size(), next.size()));
    if (levels == 0) return false;
    const int half = o.halfWindow;
    const int n = (2 * half + 1) * (2 * half + 1);
    std::vector<float> ix(static_cast<size_t>(n)), iy(static_cast<size_t>(n)), iv(static_cast<size_t>(n));

    // Flow at level 0 is `guess - p0`; scale it down to the coarsest level.
    float fx = (guess.x - p0.x) / static_cast<float>(1 << (levels - 1));
    float fy = (guess.y - p0.y) / static_cast<float>(1 << (levels - 1));
    for (int level = levels - 1; level >= 0; --level) {
        const Gray& I = prev[static_cast<size_t>(level)];
        const Gray& J = next[static_cast<size_t>(level)];
        const float px = toLevel(p0.x, level), py = toLevel(p0.y, level);
        if (px < 0 || py < 0 || px > static_cast<float>(I.w - 1) || py > static_cast<float>(I.h - 1)) return false;

        double gxx = 0, gxy = 0, gyy = 0;
        int k = 0;
        for (int dy = -half; dy <= half; ++dy) {
            for (int dx = -half; dx <= half; ++dx, ++k) {
                const float x = px + static_cast<float>(dx), y = py + static_cast<float>(dy);
                const float gx = 0.5f * (I.sample(x + 1, y) - I.sample(x - 1, y));
                const float gy = 0.5f * (I.sample(x, y + 1) - I.sample(x, y - 1));
                ix[static_cast<size_t>(k)] = gx;
                iy[static_cast<size_t>(k)] = gy;
                iv[static_cast<size_t>(k)] = I.sample(x, y);
                gxx += gx * gx;
                gxy += gx * gy;
                gyy += gy * gy;
            }
        }
        const double det = gxx * gyy - gxy * gxy;
        const double minEig = 0.5 * ((gxx + gyy) - std::sqrt(std::max(0.0, (gxx - gyy) * (gxx - gyy) + 4.0 * gxy * gxy))) / n;
        if (minEig < o.minEigenvalue || det < 1e-9) {
            if (level == 0) return false;
        } else {
            for (int it = 0; it < o.maxIterations; ++it) {
                double bx = 0, by = 0;
                k = 0;
                for (int dy = -half; dy <= half; ++dy) {
                    for (int dx = -half; dx <= half; ++dx, ++k) {
                        const float diff = iv[static_cast<size_t>(k)] - J.sample(px + static_cast<float>(dx) + fx, py + static_cast<float>(dy) + fy);
                        bx += diff * ix[static_cast<size_t>(k)];
                        by += diff * iy[static_cast<size_t>(k)];
                    }
                }
                const float ux = static_cast<float>((gyy * bx - gxy * by) / det);
                const float uy = static_cast<float>((-gxy * bx + gxx * by) / det);
                fx += ux;
                fy += uy;
                if (!std::isfinite(fx) || !std::isfinite(fy)) return false;
                if (ux * ux + uy * uy < o.epsilon * o.epsilon) break;
            }
        }
        if (level > 0) {
            fx *= 2.0f;
            fy *= 2.0f;
        }
    }
    const Gray& base = next[0];
    const Vec2 result{p0.x + fx, p0.y + fy};
    if (result.x < 0 || result.y < 0 || result.x > static_cast<float>(base.w - 1) || result.y > static_cast<float>(base.h - 1)) return false;
    *out = result;
    return true;
}

}  // namespace

void trackLk(const std::vector<Gray>& prev, const std::vector<Gray>& next, const std::vector<Vec2>& points,
             std::vector<Vec2>* tracked, std::vector<uint8_t>* ok, const LkOptions& options) {
    tracked->assign(points.size(), Vec2{});
    ok->assign(points.size(), 0);
    for (size_t i = 0; i < points.size(); ++i) {
        Vec2 forward;
        if (!lkOne(prev, next, points[i], points[i], &forward, options)) continue;
        Vec2 back;
        if (!lkOne(next, prev, forward, forward, &back, options)) continue;
        const float ex = back.x - points[i].x, ey = back.y - points[i].y;
        if (ex * ex + ey * ey > options.maxBackwardError * options.maxBackwardError) continue;
        (*tracked)[i] = forward;
        (*ok)[i] = 1;
    }
}

bool trackPoint(const std::vector<Gray>& prev, const std::vector<Gray>& next, Vec2 point, Vec2* out, const LkOptions& options) {
    std::vector<Vec2> tracked;
    std::vector<uint8_t> ok;
    trackLk(prev, next, {point}, &tracked, &ok, options);
    if (ok.empty() || !ok[0]) return false;
    *out = tracked[0];
    return true;
}

BoxTracker::BoxTracker(const Gray& first, const Box& initial, int levels)
    : levels_(std::max(1, levels)), prev_(buildPyramid(first, std::max(1, levels))), box_(initial) {}

float BoxTracker::update(const Gray& next) {
    if (prev_.empty() || next.w != prev_[0].w || next.h != prev_[0].h) return 0.0f;
    const std::vector<Gray> nextPyramid = buildPyramid(next, levels_);

    const IntRect roi{static_cast<int>(std::floor(box_.cx - box_.w * 0.5f)), static_cast<int>(std::floor(box_.cy - box_.h * 0.5f)),
                      static_cast<int>(std::ceil(box_.cx + box_.w * 0.5f)), static_cast<int>(std::ceil(box_.cy + box_.h * 0.5f))};
    CornerOptions corners;
    corners.maxCorners = 80;
    corners.minDistance = 4;
    corners.gridCols = 6;
    corners.gridRows = 6;
    std::vector<Vec2> points = detectCorners(prev_[0], corners, &roi);
    if (points.size() < 6) {
        // A textureless region: fall back to a regular grid so there is still something to follow.
        points.clear();
        for (int gy = 0; gy < 7; ++gy) {
            for (int gx = 0; gx < 7; ++gx) {
                points.push_back({box_.cx + (static_cast<float>(gx) / 6.0f - 0.5f) * box_.w * 0.9f,
                                  box_.cy + (static_cast<float>(gy) / 6.0f - 0.5f) * box_.h * 0.9f});
            }
        }
    }
    std::vector<Vec2> tracked;
    std::vector<uint8_t> ok;
    trackLk(prev_, nextPyramid, points, &tracked, &ok);
    std::vector<Vec2> src, dst;
    for (size_t i = 0; i < points.size(); ++i) {
        if (ok[i]) {
            src.push_back(points[i]);
            dst.push_back(tracked[i]);
        }
    }
    RansacOptions ransac;
    ransac.threshold = 1.0;
    ransac.minInliers = 5;
    ransac.minScale = 0.7;
    ransac.maxScale = 1.4;
    RansacResult result;
    if (src.size() < 5 || !estimateSimilarityRansac(src, dst, ransac, &result)) return 0.0f;
    const float confidence = static_cast<float>(result.inliers) / static_cast<float>(points.size());
    if (confidence < 0.2f) return 0.0f;

    const Vec2 centre = result.model.apply({box_.cx, box_.cy});
    box_.cx = centre.x;
    box_.cy = centre.y;
    const float s = static_cast<float>(result.model.scale());
    box_.w *= s;
    box_.h *= s;
    box_.rotation += static_cast<float>(result.model.angle());
    prev_ = nextPyramid;
    return confidence;
}

}  // namespace uv::stab
