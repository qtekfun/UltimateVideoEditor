#pragma once

// Pure, host-testable maths for the export pipeline. Time is integer frames (SPECS.md section 3):
// presentation timestamps come from exact rational arithmetic on 128-bit intermediates, so a
// long export cannot drift the way accumulating a float frame duration would.

#include <algorithm>
#include <cstdint>
#include <vector>

#include "core/crossfade_math.h"
#include "core/keyframe_math.h"

namespace uv::encode {

using i128 = __int128;

struct Fps {
    int32_t num = 30;
    int32_t den = 1;
};

// One video clip as the exporter needs it. All frames are project frames (the preview and the
// editor interpret source ranges the same way).
struct VideoClip {
    int64_t startFrame = 0;
    int64_t durationFrames = 0;
    int64_t sourceInFrame = 0;
    int64_t assetKey = 0;
    int32_t layer = 0;      // 0 is the topmost track; a lower number is drawn over a higher one
    int32_t colorMode = 0;  // mirrors render::ColorMode
    // Mirrors ClipTransform: canvas pixels (+x right, +y down), clockwise degrees, opacity 0..1.
    double posX = 0.0;
    double posY = 0.0;
    double scaleX = 1.0;
    double scaleY = 1.0;
    double rotationDeg = 0.0;
    double opacity = 1.0;
    // A transition's incoming clip fades in over its first fadeInFrames (0 = none): opacity is
    // scaled by core::crossfadeProgress. Mirrors domain/RenderPlan.kt (RenderClip.opacityAt).
    int64_t fadeInFrames = 0;
    // Decoder slot within a layer: clips of one media that show together (the two sides of a
    // transition) must not share a decoder, so the incoming one is in lane 1.
    int32_t lane = 0;
    // != 0: a rasterised title (ExportParams::titles) instead of video media; assetKey is unused.
    uint32_t titleKey = 0;
    // Animated pose (clip frames counted from keyOriginFrame). When non-empty it replaces the fixed
    // pose above; mirrors domain/Keyframes.kt.
    std::vector<core::Keyframe> keyframes;
    int64_t keyOriginFrame = 0;
    // A retimed clip (speed, ramp, reverse or freeze) lists the source frame of each of its project
    // frames (index = frame - startFrame), computed by domain/Retime.kt so the preview and the export
    // agree; empty means source frame sourceInFrame + (frame - startFrame). `reverse` tells the decoder
    // to keep its window of decoded frames behind the frame being drawn.
    std::vector<int64_t> sourceTable;
    bool reverse = false;
};

// Frame index -> nanoseconds, rounded half up. Monotonic and exact for any realistic length.
inline int64_t frameToNs(int64_t frame, Fps fps) {
    return static_cast<int64_t>((static_cast<i128>(frame) * 1000000000 * fps.den + fps.num / 2) / fps.num);
}

inline int64_t frameToUs(int64_t frame, Fps fps) {
    return static_cast<int64_t>((static_cast<i128>(frame) * 1000000 * fps.den + fps.num / 2) / fps.num);
}

// Number of audio samples that make up `frames` project frames, rounded half up. Adjacent ranges
// computed from frame boundaries tile exactly, so audio and video end together.
inline int64_t framesToSamples(int64_t frames, Fps fps, int32_t sampleRate) {
    return static_cast<int64_t>((static_cast<i128>(2) * frames * sampleRate * fps.den + fps.num) /
                                (static_cast<i128>(2) * fps.num));
}

inline int64_t samplesToUs(int64_t samples, int32_t sampleRate) {
    return static_cast<int64_t>((static_cast<i128>(samples) * 1000000 + sampleRate / 2) / sampleRate);
}

// Progress in 0..1000, never going backwards for increasing `done`.
inline int32_t progressPermille(int64_t done, int64_t total) {
    if (total <= 0) return 0;
    const int64_t clamped = std::clamp<int64_t>(done, 0, total);
    return static_cast<int32_t>((static_cast<i128>(clamped) * 1000) / total);
}

// Topmost clip covering `frame` (lowest layer number), or nullptr in a gap.
inline const VideoClip* clipAt(const std::vector<VideoClip>& clips, int64_t frame) {
    const VideoClip* best = nullptr;
    for (const VideoClip& clip : clips) {
        if (frame < clip.startFrame || frame >= clip.startFrame + clip.durationFrames) continue;
        if (best == nullptr || clip.layer < best->layer) best = &clip;
    }
    return best;
}

// Every clip covering `frame`, bottom layer first (the highest layer number), so drawing them in
// order leaves the topmost track on top. Within one layer the later-starting clip is drawn last,
// which puts the incoming clip of a transition over the outgoing one. Empty in a gap.
inline std::vector<const VideoClip*> layersAt(const std::vector<VideoClip>& clips, int64_t frame) {
    std::vector<const VideoClip*> out;
    for (const VideoClip& clip : clips) {
        if (frame >= clip.startFrame && frame < clip.startFrame + clip.durationFrames) out.push_back(&clip);
    }
    std::stable_sort(out.begin(), out.end(), [](const VideoClip* a, const VideoClip* b) {
        if (a->layer != b->layer) return a->layer > b->layer;
        return a->startFrame < b->startFrame;
    });
    return out;
}

// The pose of `clip` at project frame `frame`: its keyframes if it has any, else the fixed pose.
// The opacity is the clip's own, before the crossfade.
inline core::Pose poseAt(const VideoClip& clip, int64_t frame) {
    const core::Pose base{clip.posX, clip.posY, clip.scaleX, clip.scaleY, clip.rotationDeg, clip.opacity};
    return core::evaluateKeyframes(clip.keyframes, frame - clip.keyOriginFrame, base);
}

// Opacity of `clip` at project frame `frame`: its own (animated) opacity times the crossfade ramp.
inline double opacityAt(const VideoClip& clip, int64_t frame) {
    return poseAt(clip, frame).opacity * core::crossfadeProgress(frame - clip.startFrame, clip.fadeInFrames);
}

// Output frame (at the export rate) -> the project frame shown at that instant: the last project
// frame that starts at or before it, in exact integer arithmetic. Identity when the rates match.
inline int64_t outputToProjectFrame(int64_t outFrame, Fps out, Fps project) {
    return static_cast<int64_t>((static_cast<i128>(outFrame) * out.den * project.num) /
                                (static_cast<i128>(out.num) * project.den));
}

inline int64_t sourceFrameFor(const VideoClip& clip, int64_t frame, int64_t assetFrames) {
    int64_t wanted = clip.sourceInFrame + (frame - clip.startFrame);
    if (!clip.sourceTable.empty()) {
        const int64_t last = static_cast<int64_t>(clip.sourceTable.size()) - 1;
        wanted = clip.sourceTable[static_cast<size_t>(std::clamp<int64_t>(frame - clip.startFrame, 0, last))];
    }
    if (assetFrames <= 0) return std::max<int64_t>(wanted, 0);
    return std::clamp<int64_t>(wanted, 0, assetFrames - 1);
}

// How many already-decoded frames a decoder keeps behind the frame being drawn while a clip plays
// backwards: a whole stretch of a GOP is decoded in one pass and then consumed frame by frame. Bounded
// by memory (a 4K frame is 33 MB), so long GOPs at high resolution re-decode more often.
constexpr int64_t kReverseWindowBudgetBytes = 256ll * 1024 * 1024;
constexpr int32_t kMinReverseWindowFrames = 4;
constexpr int32_t kMaxReverseWindowFrames = 48;

inline int32_t reverseWindowFrames(int64_t frameBytes) {
    if (frameBytes <= 0) return kMinReverseWindowFrames;
    return static_cast<int32_t>(std::clamp<int64_t>(kReverseWindowBudgetBytes / frameBytes, kMinReverseWindowFrames,
                                                    kMaxReverseWindowFrames));
}

// End of the last clip: the length of the exported movie in project frames.
inline int64_t totalFramesOf(const std::vector<VideoClip>& clips, int64_t extraEndFrame) {
    int64_t end = extraEndFrame;
    for (const VideoClip& clip : clips) end = std::max(end, clip.startFrame + clip.durationFrames);
    return end;
}

}  // namespace uv::encode
