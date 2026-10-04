#pragma once

// From per-frame camera motion to the per-frame correction the compositor applies.
//
// The camera path P_t is the composition of the frame-to-frame motions: it maps a point's position in the
// first frame to its position in frame t. The smoothed path Q_t is a low-pass filter of P_t's parameters.
// The correction C_t = Q_t o P_t^-1 maps a raw frame-t position to where the stabilised picture shows it,
// so a static scene point follows the smooth path instead of the shaky one. The picture is then scaled
// about its centre by a constant zoom (the crop level) so the moving frame edges stay out of view.
//
// Everything here is in height units (see motion_analyser.h) and pure C++, so it runs in host tests.

#include <cstddef>
#include <cstdint>
#include <vector>

#include "decode/frame_rate.h"
#include "stabilise/motion_analyser.h"
#include "stabilise/similarity.h"

namespace uv::stab {

// One analysed frame: its presentation time (microseconds from the first frame of the media) and the
// motion from the previous analysed frame.
struct MotionSample {
    int64_t ptsUs = 0;
    FrameMotion motion;
};

enum class CropLevel : int {
    Tight = 0,   // zoom enough that no frame edge is ever visible
    Medium = 1,  // half of that zoom; a little edge fill may show on the shakiest frames
    Full = 2,    // no zoom; edges are filled by repeating the border pixels
};

// Per-frame correction, indexed by the decoder's frame number (project frame rate, counted from the first
// frame of the media). Four floats per frame: dx, dy (height units), theta (radians, clockwise), scale.
struct StabTable {
    int64_t firstFrame = 0;
    std::vector<float> data;

    size_t frames() const { return data.size() / 4; }
    // Frames outside the covered range use the nearest covered one. False for an empty table.
    bool lookup(int64_t frame, float out[4]) const;
};

struct PathParams {
    double tx = 0.0;
    double ty = 0.0;
    double theta = 0.0;  // unwrapped, so it never jumps by 2*pi
    double logScale = 0.0;
};

// P_i for every sample (P_0 is the identity).
std::vector<Similarity> cumulativePath(const std::vector<MotionSample>& samples);

// The parameters of the path with the rotation unwrapped.
std::vector<PathParams> toParams(const std::vector<Similarity>& path);
Similarity toSimilarity(const PathParams& p);

// Resamples the path to one entry per frame of `fps` from the first to the last sample (linear
// interpolation in time). `*firstFrame` receives the frame number of the first entry.
std::vector<PathParams> resampleToFrames(const std::vector<PathParams>& path, const std::vector<MotionSample>& samples,
                                         decode::Rational fps, int64_t* firstFrame);

// Gaussian low-pass of each parameter with mirrored ends. A sigma below one frame leaves the path as is.
std::vector<PathParams> smoothParams(const std::vector<PathParams>& path, double sigmaFrames);

// Standard deviation of the filter, in frames, for a strength of 0..1 (0 means no stabilisation).
double sigmaFramesFor(float strength, decode::Rational fps);

// Smallest zoom (about the centre, at least 1, at most `maxZoom`) for which the correction `c` leaves the
// viewport inside the source frame. `aspect` is width / height.
double requiredZoom(const Similarity& c, double aspect, double maxZoom = 2.0);

// The whole pipeline from analysed samples to a table.
StabTable buildTable(const std::vector<MotionSample>& samples, decode::Rational fps, double aspect, float strength,
                     CropLevel crop);

}  // namespace uv::stab
