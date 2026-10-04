#include "stabilise/motion_analyser.h"

#include <algorithm>
#include <cmath>

namespace uv::stab {

MotionAnalyser::MotionAnalyser(int pyramidLevels) : levels_(std::max(1, pyramidLevels)) {}

void MotionAnalyser::reset() { prev_.clear(); }

FrameMotion MotionAnalyser::feed(const Gray& frame) {
    FrameMotion motion;
    std::vector<Gray> pyramid = buildPyramid(frame, levels_);
    if (prev_.empty() || prev_[0].w != frame.w || prev_[0].h != frame.h) {
        prev_ = std::move(pyramid);
        motion.valid = true;
        motion.quality = 1.0f;
        return motion;
    }

    CornerOptions corners;
    corners.maxCorners = 300;
    corners.minDistance = std::max(6, frame.h / 45);
    std::vector<Vec2> points = detectCorners(prev_[0], corners);
    std::vector<Vec2> tracked;
    std::vector<uint8_t> ok;
    trackLk(prev_, pyramid, points, &tracked, &ok);

    // Centre the coordinates so the rotation is about the middle of the picture.
    const float cx = 0.5f * static_cast<float>(frame.w - 1), cy = 0.5f * static_cast<float>(frame.h - 1);
    std::vector<Vec2> src, dst;
    for (size_t i = 0; i < points.size(); ++i) {
        if (!ok[i]) continue;
        src.push_back({points[i].x - cx, points[i].y - cy});
        dst.push_back({tracked[i].x - cx, tracked[i].y - cy});
    }

    RansacOptions ransac;
    ransac.iterations = 150;
    ransac.threshold = 1.0;
    ransac.minInliers = std::max(8, static_cast<int>(src.size()) / 5);
    // Between two consecutive frames the scale changes by a few percent at most.
    ransac.minScale = 0.8;
    ransac.maxScale = 1.25;
    ransac.seed = 0x9E3779B97F4A7C15ULL ^ ++frameCounter_;
    RansacResult result;
    if (!estimateSimilarityRansac(src, dst, ransac, &result)) {
        prev_ = std::move(pyramid);  // no estimate: carry on from this frame as if the camera had not moved
        return motion;
    }
    const double height = static_cast<double>(frame.h);
    motion.tx = result.model.tx / height;
    motion.ty = result.model.ty / height;
    motion.theta = result.model.angle();
    motion.logScale = std::log(result.model.scale());
    motion.quality = static_cast<float>(result.inliers) / static_cast<float>(std::max<size_t>(1, points.size()));
    motion.valid = true;
    prev_ = std::move(pyramid);
    return motion;
}

}  // namespace uv::stab
