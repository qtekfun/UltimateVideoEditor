#pragma once

// Classical feature tracking (no models, no third-party libraries): Shi-Tomasi corners, pyramidal
// Lucas-Kanade with a forward-backward check, and a box tracker built on them. The stabiliser uses it
// to estimate camera motion; the motion-tracking package (SPECS.md 9.15) reuses `BoxTracker` and
// `trackPoint` to follow a point or a region across frames.

#include <cstdint>
#include <vector>

#include "stabilise/gray.h"
#include "stabilise/similarity.h"

namespace uv::stab {

struct IntRect {
    int x0 = 0;
    int y0 = 0;
    int x1 = 0;  // exclusive
    int y1 = 0;  // exclusive
};

struct CornerOptions {
    int maxCorners = 300;
    int minDistance = 8;   // pixels between accepted corners
    int gridCols = 8;      // corners are spread over a grid so one textured area does not take them all
    int gridRows = 6;
    float quality = 0.01f; // minimum score as a fraction of the best one
};

// Shi-Tomasi corners (smaller eigenvalue of the 5x5 structure tensor), spread over a grid, inside `roi`
// (the whole image when null). Positions are pixel-centre coordinates.
std::vector<Vec2> detectCorners(const Gray& image, const CornerOptions& options, const IntRect* roi = nullptr);

struct LkOptions {
    int halfWindow = 7;        // 15x15 window
    int maxIterations = 20;
    float epsilon = 0.02f;     // stop when an update moves less than this many pixels
    float minEigenvalue = 1e-3f;
    float maxBackwardError = 1.0f;  // forward-backward consistency, pixels
};

// Tracks `points` from `prev` to `next` (pyramids from buildPyramid, same number of levels). `ok[i]`
// is false for points that were lost, left the image, had no texture or failed the backward check.
void trackLk(const std::vector<Gray>& prev, const std::vector<Gray>& next, const std::vector<Vec2>& points,
             std::vector<Vec2>* tracked, std::vector<uint8_t>* ok, const LkOptions& options = {});

// Convenience for a single point. Returns false when it cannot be followed.
bool trackPoint(const std::vector<Gray>& prev, const std::vector<Gray>& next, Vec2 point, Vec2* out,
                const LkOptions& options = {});

// A region followed through a clip. Rotation is in radians, clockwise on screen.
struct Box {
    float cx = 0.0f;
    float cy = 0.0f;
    float w = 0.0f;
    float h = 0.0f;
    float rotation = 0.0f;
};

class BoxTracker {
public:
    BoxTracker(const Gray& first, const Box& initial, int levels = 3);

    // Advances to the next frame (same size as the first). Returns the confidence in 0..1, the share of
    // tracked points that agree with the estimated motion; 0 means the target was lost and the box stays.
    float update(const Gray& next);

    const Box& box() const { return box_; }

private:
    int levels_;
    std::vector<Gray> prev_;
    Box box_;
};

}  // namespace uv::stab
