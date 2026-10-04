#include "stabilise/motion_analyser.h"

#include <algorithm>
#include <cmath>

namespace uv::stab {

MotionAnalyser::MotionAnalyser(int pyramidLevels) : levels_(std::max(1, pyramidLevels)) {}

void MotionAnalyser::reset() { prev_.clear(); }

namespace {

// Corners in `prev` tracked into `next` and a robust similarity fitted to them; false when there is not enough to go on.
bool estimate(const std::vector<Gray>& prev, const std::vector<Gray>& next, uint64_t seed, FrameMotion* motion, int* tracked) {
    const Gray& base = prev[0];
    CornerOptions corners;
    corners.maxCorners = 300;
    corners.minDistance = std::max(6, base.h / 45);
    std::vector<Vec2> points = detectCorners(base, corners);
    std::vector<Vec2> moved;
    std::vector<uint8_t> ok;
    trackLk(prev, next, points, &moved, &ok);

    // Centre the coordinates so the rotation is about the middle of the picture.
    const float cx = 0.5f * static_cast<float>(base.w - 1), cy = 0.5f * static_cast<float>(base.h - 1);
    std::vector<Vec2> src, dst;
    for (size_t i = 0; i < points.size(); ++i) {
        if (!ok[i]) continue;
        src.push_back({points[i].x - cx, points[i].y - cy});
        dst.push_back({moved[i].x - cx, moved[i].y - cy});
    }

    RansacOptions ransac;
    ransac.iterations = 150;
    ransac.threshold = 1.0;
    ransac.minInliers = std::max(8, static_cast<int>(src.size()) / 5);
    // Between two consecutive frames the scale changes by a few percent at most.
    ransac.minScale = 0.8;
    ransac.maxScale = 1.25;
    ransac.seed = seed;
    RansacResult result;
    if (!estimateSimilarityRansac(src, dst, ransac, &result)) return false;
    const double height = static_cast<double>(base.h);
    motion->tx = result.model.tx / height;
    motion->ty = result.model.ty / height;
    motion->theta = result.model.angle();
    motion->logScale = std::log(result.model.scale());
    motion->quality = static_cast<float>(result.inliers) / static_cast<float>(std::max<size_t>(1, points.size()));
    motion->valid = true;
    *tracked = static_cast<int>(src.size());
    return true;
}

}  // namespace

FrameMotion MotionAnalyser::feed(const Gray& frame) {
    FrameMotion motion;
    std::vector<Gray> pyramid = buildPyramid(frame, levels_);
    if (prev_.empty() || prev_[0].w != frame.w || prev_[0].h != frame.h) {
        prev_ = std::move(pyramid);
        motion.valid = true;
        motion.quality = 1.0f;
        return motion;
    }

    int tracked = 0;
    const uint64_t seed = 0x9E3779B97F4A7C15ULL ^ ++frameCounter_;
    if (!estimate(prev_, pyramid, seed, &motion, &tracked) && levels_ < kDeepLevels) {
        // A large jump (a shake or a pan) can be beyond what a shallow pyramid follows: retry with a deeper one,
        // where the coarse levels see far larger displacements.
        const std::vector<Gray> deepPrev = buildPyramid(prev_[0], kDeepLevels);
        const std::vector<Gray> deepNext = buildPyramid(frame, kDeepLevels);
        motion = FrameMotion{};
        if (!estimate(deepPrev, deepNext, seed, &motion, &tracked)) motion = FrameMotion{};
    }
    if (motion.valid || heldFrames_ >= kMaxHeldFrames) {
        prev_ = std::move(pyramid);
        heldFrames_ = 0;
    } else {
        // Nothing could be measured (a flat frame, a blur). Keep the last good frame as the reference, so the next
        // frame's motion also covers what happened meanwhile and the camera path does not lose it for good. After a
        // few frames the reference is too old to be useful and tracking starts afresh from here.
        ++heldFrames_;
    }
    return motion;
}

}  // namespace uv::stab
