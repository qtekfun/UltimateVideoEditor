#pragma once

// Pure, host-testable maths for the export pipeline. Time is integer frames (SPECS.md section 3):
// presentation timestamps come from exact rational arithmetic on 128-bit intermediates, so a
// long export cannot drift the way accumulating a float frame duration would.

#include <algorithm>
#include <cstdint>
#include <vector>

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
// order leaves the topmost track on top. Empty in a gap.
inline std::vector<const VideoClip*> layersAt(const std::vector<VideoClip>& clips, int64_t frame) {
    std::vector<const VideoClip*> out;
    for (const VideoClip& clip : clips) {
        if (frame >= clip.startFrame && frame < clip.startFrame + clip.durationFrames) out.push_back(&clip);
    }
    std::stable_sort(out.begin(), out.end(), [](const VideoClip* a, const VideoClip* b) { return a->layer > b->layer; });
    return out;
}

// Output frame (at the export rate) -> the project frame shown at that instant: the last project
// frame that starts at or before it, in exact integer arithmetic. Identity when the rates match.
inline int64_t outputToProjectFrame(int64_t outFrame, Fps out, Fps project) {
    return static_cast<int64_t>((static_cast<i128>(outFrame) * out.den * project.num) /
                                (static_cast<i128>(out.num) * project.den));
}

inline int64_t sourceFrameFor(const VideoClip& clip, int64_t frame, int64_t assetFrames) {
    const int64_t wanted = clip.sourceInFrame + (frame - clip.startFrame);
    if (assetFrames <= 0) return std::max<int64_t>(wanted, 0);
    return std::clamp<int64_t>(wanted, 0, assetFrames - 1);
}

// End of the last clip: the length of the exported movie in project frames.
inline int64_t totalFramesOf(const std::vector<VideoClip>& clips, int64_t extraEndFrame) {
    int64_t end = extraEndFrame;
    for (const VideoClip& clip : clips) end = std::max(end, clip.startFrame + clip.durationFrames);
    return end;
}

}  // namespace uv::encode
