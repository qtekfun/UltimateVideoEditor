#pragma once

// Frame-to-frame camera motion from a sequence of grayscale frames: corners in the previous frame are
// tracked into the next one (pyramidal Lucas-Kanade with a forward-backward check), and a similarity
// transform is fitted robustly (RANSAC) so a moving subject does not drag the estimate.

#include <vector>

#include "stabilise/gray.h"
#include "stabilise/similarity.h"
#include "stabilise/tracker.h"

namespace uv::stab {

// Motion of the picture from one frame to the next, in height units: coordinates are centred on the image
// and divided by its height (so they do not depend on the analysis resolution), +x right, +y down,
// theta clockwise on screen. A point at p in the previous frame is at s*R(theta)*p + (tx, ty) in this one.
struct FrameMotion {
    double tx = 0.0;
    double ty = 0.0;
    double theta = 0.0;
    double logScale = 0.0;
    float quality = 0.0f;  // share of tracked points that agree with the motion; 0 when it could not be estimated
    bool valid = false;    // false: the frame had too little to track and the motion was taken as none

    Similarity toSimilarity() const {
        return Similarity::fromParams(std::exp(logScale), theta, tx, ty);
    }
};

class MotionAnalyser {
public:
    explicit MotionAnalyser(int pyramidLevels = 3);

    // Feeds the next frame (all frames must have the same size). The first call returns no motion with
    // `valid` true; later calls return the motion from the previous frame.
    FrameMotion feed(const Gray& frame);

    // Forgets the previous frame (a discontinuity: the next frame is treated as a first one).
    void reset();

private:
    int levels_;
    std::vector<Gray> prev_;
    uint64_t frameCounter_ = 0;
};

}  // namespace uv::stab
