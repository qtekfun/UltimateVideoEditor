#include "stabilise/path.h"

#include <algorithm>
#include <cmath>

namespace uv::stab {

namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr int64_t kMaxFrames = 1LL << 22;  // ~38 hours at 30 fps; a guard against corrupt input
}  // namespace

bool StabTable::lookup(int64_t frame, float out[4]) const {
    if (data.empty()) return false;
    const int64_t last = static_cast<int64_t>(frames()) - 1;
    const int64_t i = std::clamp<int64_t>(frame - firstFrame, 0, last);
    for (int k = 0; k < 4; ++k) out[k] = data[static_cast<size_t>(i) * 4 + static_cast<size_t>(k)];
    return true;
}

std::vector<Similarity> cumulativePath(const std::vector<MotionSample>& samples) {
    std::vector<Similarity> path;
    path.reserve(samples.size());
    Similarity current;  // identity
    for (size_t i = 0; i < samples.size(); ++i) {
        if (i > 0) current = compose(samples[i].motion.toSimilarity(), current);
        path.push_back(current);
    }
    return path;
}

std::vector<PathParams> toParams(const std::vector<Similarity>& path) {
    std::vector<PathParams> out;
    out.reserve(path.size());
    double previous = 0.0;
    for (const Similarity& s : path) {
        PathParams p;
        p.tx = s.tx;
        p.ty = s.ty;
        p.logScale = std::log(std::max(1e-9, s.scale()));
        double theta = s.angle();
        // Keep the angle continuous: choose the representative closest to the previous one.
        while (theta - previous > kPi) theta -= 2.0 * kPi;
        while (theta - previous < -kPi) theta += 2.0 * kPi;
        p.theta = theta;
        previous = theta;
        out.push_back(p);
    }
    return out;
}

Similarity toSimilarity(const PathParams& p) { return Similarity::fromParams(std::exp(p.logScale), p.theta, p.tx, p.ty); }

std::vector<PathParams> resampleToFrames(const std::vector<PathParams>& path, const std::vector<MotionSample>& samples,
                                         decode::Rational fps, int64_t* firstFrame) {
    std::vector<PathParams> out;
    if (path.empty() || path.size() != samples.size() || fps.num <= 0 || fps.den <= 0) {
        *firstFrame = 0;
        return out;
    }
    const int64_t first = decode::ptsUsToFrame(samples.front().ptsUs, fps);
    const int64_t last = std::max(first, decode::ptsUsToFrame(samples.back().ptsUs, fps));
    const int64_t count = std::min(last - first + 1, kMaxFrames);
    *firstFrame = first;
    out.reserve(static_cast<size_t>(count));
    size_t i = 0;
    for (int64_t k = 0; k < count; ++k) {
        const int64_t t = decode::frameToPtsUs(first + k, fps);
        while (i + 1 < samples.size() && samples[i + 1].ptsUs <= t) ++i;
        if (i + 1 >= samples.size() || t <= samples[i].ptsUs) {
            out.push_back(path[i]);
            continue;
        }
        const double span = static_cast<double>(samples[i + 1].ptsUs - samples[i].ptsUs);
        const double f = span > 0.0 ? static_cast<double>(t - samples[i].ptsUs) / span : 0.0;
        PathParams p;
        p.tx = path[i].tx + (path[i + 1].tx - path[i].tx) * f;
        p.ty = path[i].ty + (path[i + 1].ty - path[i].ty) * f;
        p.theta = path[i].theta + (path[i + 1].theta - path[i].theta) * f;
        p.logScale = path[i].logScale + (path[i + 1].logScale - path[i].logScale) * f;
        out.push_back(p);
    }
    return out;
}

std::vector<PathParams> smoothParams(const std::vector<PathParams>& path, double sigmaFrames) {
    const int n = static_cast<int>(path.size());
    if (n < 2 || sigmaFrames < 1.0) return path;
    const int radius = std::max(1, static_cast<int>(std::ceil(3.0 * sigmaFrames)));
    std::vector<double> kernel(static_cast<size_t>(2 * radius + 1));
    double total = 0.0;
    for (int k = -radius; k <= radius; ++k) {
        const double w = std::exp(-0.5 * (k * k) / (sigmaFrames * sigmaFrames));
        kernel[static_cast<size_t>(k + radius)] = w;
        total += w;
    }
    for (double& w : kernel) w /= total;

    const auto mirror = [n](int j) {
        if (n == 1) return 0;
        // Reflect about the end points (period 2*(n-1)).
        const int period = 2 * (n - 1);
        int m = j % period;
        if (m < 0) m += period;
        return m < n ? m : period - m;
    };
    std::vector<PathParams> out(path.size());
    for (int i = 0; i < n; ++i) {
        PathParams acc;
        for (int k = -radius; k <= radius; ++k) {
            // Reflecting a *path* about its end point must reflect the values too (odd extension), or the ends
            // would be pulled toward the inside; an odd extension keeps a steady drift steady up to the end.
            const int j = i + k;
            const PathParams& at = path[static_cast<size_t>(mirror(j))];
            const bool outside = j < 0 || j >= n;
            const PathParams& end = path[static_cast<size_t>(j < 0 ? 0 : n - 1)];
            const double w = kernel[static_cast<size_t>(k + radius)];
            if (outside) {
                acc.tx += w * (2.0 * end.tx - at.tx);
                acc.ty += w * (2.0 * end.ty - at.ty);
                acc.theta += w * (2.0 * end.theta - at.theta);
                acc.logScale += w * (2.0 * end.logScale - at.logScale);
            } else {
                acc.tx += w * at.tx;
                acc.ty += w * at.ty;
                acc.theta += w * at.theta;
                acc.logScale += w * at.logScale;
            }
        }
        out[static_cast<size_t>(i)] = acc;
    }
    return out;
}

double sigmaFramesFor(float strength, decode::Rational fps) {
    if (!(strength > 0.0f) || fps.num <= 0 || fps.den <= 0) return 0.0;
    const double s = std::min(1.0, static_cast<double>(strength));
    const double seconds = 0.1 + 2.4 * s;
    return seconds * static_cast<double>(fps.num) / static_cast<double>(fps.den);
}

double requiredZoom(const Similarity& c, double aspect, double maxZoom) {
    const double s = c.scale();
    if (!(s > 1e-9)) return maxZoom;
    const double theta = c.angle();
    const double cs = std::cos(theta), sn = std::sin(theta);
    // Source position of an output point v at zoom z = 1/u:  X = u * R(-theta) v / s  -  R(-theta) d / s.
    const auto rot = [&](double x, double y, double* ox, double* oy) {
        *ox = cs * x + sn * y;
        *oy = -sn * x + cs * y;
    };
    double bx, by;
    rot(c.tx, c.ty, &bx, &by);
    bx /= s;
    by /= s;
    const double halfW = aspect * 0.5, halfH = 0.5;
    double lo = 0.0, hi = 1.0;  // feasible range of u = 1 / zoom, within (0, 1]
    bool feasible = true;
    for (int sx = -1; sx <= 1; sx += 2) {
        for (int sy = -1; sy <= 1; sy += 2) {
            double ax, ay;
            rot(sx * halfW, sy * halfH, &ax, &ay);
            ax /= s;
            ay /= s;
            const double a[2] = {ax, ay}, b[2] = {bx, by}, h[2] = {halfW, halfH};
            for (int k = 0; k < 2; ++k) {
                // |u * a - b| <= h
                if (std::fabs(a[k]) < 1e-12) {
                    if (std::fabs(b[k]) > h[k]) feasible = false;
                    continue;
                }
                double u0 = (b[k] - h[k]) / a[k], u1 = (b[k] + h[k]) / a[k];
                if (u0 > u1) std::swap(u0, u1);
                lo = std::max(lo, u0);
                hi = std::min(hi, u1);
            }
        }
    }
    if (!feasible || lo > hi || hi <= 0.0) return maxZoom;
    return std::clamp(1.0 / hi, 1.0, maxZoom);
}

StabTable buildTable(const std::vector<MotionSample>& samples, decode::Rational fps, double aspect, float strength,
                     CropLevel crop) {
    StabTable table;
    if (samples.empty()) return table;
    const std::vector<PathParams> raw = resampleToFrames(toParams(cumulativePath(samples)), samples, fps, &table.firstFrame);
    if (raw.empty()) return table;
    const size_t n = raw.size();
    table.data.assign(n * 4, 0.0f);
    for (size_t i = 0; i < n; ++i) table.data[i * 4 + 3] = 1.0f;

    const double sigma = sigmaFramesFor(strength, fps);
    if (sigma < 1.0) return table;  // off: an identity table

    const std::vector<PathParams> smooth = smoothParams(raw, sigma);
    std::vector<Similarity> correction(n);
    double tightZoom = 1.0;
    for (size_t i = 0; i < n; ++i) {
        correction[i] = compose(toSimilarity(smooth[i]), toSimilarity(raw[i]).inverse());
        tightZoom = std::max(tightZoom, requiredZoom(correction[i], aspect));
    }
    double zoom = 1.0;
    switch (crop) {
        case CropLevel::Tight: zoom = tightZoom; break;
        case CropLevel::Medium: zoom = 1.0 + 0.5 * (tightZoom - 1.0); break;
        case CropLevel::Full: zoom = 1.0; break;
    }
    for (size_t i = 0; i < n; ++i) {
        const Similarity& c = correction[i];
        table.data[i * 4 + 0] = static_cast<float>(zoom * c.tx);
        table.data[i * 4 + 1] = static_cast<float>(zoom * c.ty);
        table.data[i * 4 + 2] = static_cast<float>(c.angle());
        table.data[i * 4 + 3] = static_cast<float>(zoom * c.scale());
    }
    return table;
}

}  // namespace uv::stab
