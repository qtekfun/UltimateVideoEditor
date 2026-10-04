#pragma once

// Pure tracking logic of the motion-tracking package (SPECS.md 9.15): follows a box through frames given in
// tracking order with the stabiliser's `BoxTracker`, marks the frames where the target was lost, and merges a
// backward and a forward run into one chronological path. No Android dependencies, so host tests drive it with
// synthetic frames; the service (`track_service.cpp`) feeds it decoded luma.

#include <algorithm>
#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

#include "stabilise/gray.h"
#include "stabilise/tracker.h"
#include "track/track_path.h"

namespace uv::track {

// An 8-bit copy of a luma frame, kept while the frames before the seed are collected: the backward run needs
// them after the seed has been found, and 8 bits keep a long clip within a few tens of megabytes.
struct PackedFrame {
    int64_t ptsUs = 0;
    int w = 0;
    int h = 0;
    std::vector<uint8_t> px;
};

inline PackedFrame packFrame(int64_t ptsUs, const stab::Gray& g) {
    PackedFrame p;
    p.ptsUs = ptsUs;
    p.w = g.w;
    p.h = g.h;
    p.px.resize(g.px.size());
    for (size_t i = 0; i < g.px.size(); ++i) p.px[i] = static_cast<uint8_t>(std::clamp(g.px[i] + 0.5f, 0.0f, 255.0f));
    return p;
}

inline stab::Gray unpackFrame(const PackedFrame& p) {
    stab::Gray g(p.w, p.h);
    for (size_t i = 0; i < p.px.size(); ++i) g.px[i] = static_cast<float>(p.px[i]);
    return g;
}

// A box in fractions of the frame (cx, cy: centre, 0..1; w, h: size).
struct NormBox {
    float cx = 0.5f;
    float cy = 0.5f;
    float w = 0.1f;
    float h = 0.1f;
};

// Confidence below this marks a frame as lost (the box is held at its last believable position).
inline constexpr float kLostBelow = 0.15f;
// Smallest box side in pixels (a tiny box has no texture to follow).
inline constexpr float kMinBoxPixels = 12.0f;

class TrackRunner {
public:
    explicit TrackRunner(float lostBelow = kLostBelow) : lostBelow_(lostBelow) {}

    // The first frame of the run, with the target in it. Further frames are fed with `step`, in the order the
    // run moves (forward in time, or backward from the seed).
    void begin(int64_t ptsUs, const stab::Gray& frame, const NormBox& box) {
        w_ = frame.w;
        h_ = frame.h;
        samples_.clear();
        stab::Box b;
        b.cx = box.cx * static_cast<float>(w_) - 0.5f;
        b.cy = box.cy * static_cast<float>(h_) - 0.5f;
        b.w = std::max(kMinBoxPixels, box.w * static_cast<float>(w_));
        b.h = std::max(kMinBoxPixels, box.h * static_cast<float>(h_));
        b.rotation = 0.0f;
        tracker_ = std::make_unique<stab::BoxTracker>(frame, b);
        samples_.push_back(sampleOf(ptsUs, tracker_->box(), 1.0f));
    }

    bool started() const { return tracker_ != nullptr; }

    void step(int64_t ptsUs, const stab::Gray& frame) {
        if (!started() || frame.w != w_ || frame.h != h_) return;
        const float confidence = tracker_->update(frame);
        samples_.push_back(sampleOf(ptsUs, tracker_->box(), confidence));
    }

    // In the order of `begin` and `step` (the seed first).
    const std::vector<TrackSample>& samples() const { return samples_; }
    int width() const { return w_; }
    int height() const { return h_; }

private:
    TrackSample sampleOf(int64_t ptsUs, const stab::Box& b, float confidence) const {
        TrackSample s;
        s.ptsUs = ptsUs;
        s.cx = (b.cx + 0.5f) / static_cast<float>(w_);
        s.cy = (b.cy + 0.5f) / static_cast<float>(h_);
        s.w = b.w / static_cast<float>(w_);
        s.h = b.h / static_cast<float>(h_);
        s.rotation = b.rotation;
        s.confidence = confidence;
        s.lost = confidence < lostBelow_;
        return s;
    }

    float lostBelow_;
    int w_ = 0;
    int h_ = 0;
    std::unique_ptr<stab::BoxTracker> tracker_;
    std::vector<TrackSample> samples_;
};

// `backward` starts at the seed and goes back in time; `forward` starts at the same seed and goes ahead. The
// result is chronological with the seed once.
inline std::vector<TrackSample> mergeRuns(const std::vector<TrackSample>& backward, const std::vector<TrackSample>& forward) {
    std::vector<TrackSample> out;
    out.reserve(backward.size() + forward.size());
    for (size_t i = backward.size(); i > 1; --i) out.push_back(backward[i - 1]);
    for (const TrackSample& s : forward) out.push_back(s);
    return out;
}

// Convenience for tests and small jobs: tracks `frames` (chronological) from the one at `seedIndex`.
inline std::vector<TrackSample> trackFrames(const std::vector<std::pair<int64_t, stab::Gray>>& frames, size_t seedIndex, const NormBox& box) {
    if (seedIndex >= frames.size()) return {};
    TrackRunner forward, backward;
    forward.begin(frames[seedIndex].first, frames[seedIndex].second, box);
    backward.begin(frames[seedIndex].first, frames[seedIndex].second, box);
    for (size_t i = seedIndex + 1; i < frames.size(); ++i) forward.step(frames[i].first, frames[i].second);
    for (size_t i = seedIndex; i > 0; --i) backward.step(frames[i - 1].first, frames[i - 1].second);
    return mergeRuns(backward.samples(), forward.samples());
}

}  // namespace uv::track
