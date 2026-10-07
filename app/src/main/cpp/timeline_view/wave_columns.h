#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "audio/waveform_peaks.h"

namespace uv::timeline {

// The column algorithm of the waveform drawn inside an audio clip, free of GL so a host tool can rasterise the very same
// numbers (tests/waveform_render_tool.cpp) and the unit tests can check them. One column is a vertical slice of the clip
// a few pixels wide: it reads the min, max and RMS of the samples under it from the peak pyramid, mapped to heights.
//
// Layers drawn per column, in this order (see TimelineRenderer): a dark outline 1 px beyond the envelope, the envelope
// (min..max), and the RMS band (symmetrical about the centre line) in a lighter tint. Silence draws nothing but the
// centre line, which is what makes the gaps between words, and so the cut points, easy to see.

// What one column shows, as fractions (0..1) of the half lane height.
struct WaveColumn {
    float up = 0.0f;    // envelope above the centre line
    float down = 0.0f;  // envelope below it
    float rms = 0.0f;   // RMS band (drawn both ways, never beyond the envelope)
};

// Layers drawn per column (outline, envelope, RMS): the vertex budget is columns * kWaveLayers * 6.
constexpr int kWaveLayers = 3;
// Columns drawn per clip and frame, at most. A clip wider than the screen is only drawn where it is visible, so this bounds
// the vertices of a frame to kMaxWaveColumns * kWaveLayers * 6 per audio clip on screen (about 13k), whatever the zoom.
constexpr int kMaxWaveColumns = 720;

// Width of a column in pixels: about 0.8 dp (whole pixels, never below one), widened when the visible part of the clip would
// otherwise need more than kMaxWaveColumns columns.
inline float waveColumnWidth(float visibleWidthPx, float density) {
    const float wanted = std::max(1.0f, std::round(0.8f * density));
    if (!(visibleWidthPx > 0.0f)) return wanted;
    const float needed = std::ceil(visibleWidthPx / static_cast<float>(kMaxWaveColumns));
    return std::max(wanted, needed);
}

// Columns needed to cover [leftPx, rightPx) when the grid starts at multiples of colW.
inline int64_t waveFirstColumn(float leftPx, double scrollX, float colW) {
    return static_cast<int64_t>(std::floor((static_cast<double>(leftPx) + scrollX) / colW));
}
inline int64_t waveLastColumn(float rightPx, double scrollX, float colW) {
    return static_cast<int64_t>(std::floor((static_cast<double>(rightPx) + scrollX) / colW));
}

// Vertices a clip of `columns` columns can put in the frame (6 per quad).
inline size_t waveVertexBudget(int64_t columns) { return static_cast<size_t>(std::max<int64_t>(0, columns)) * kWaveLayers * 6; }

// Sample frame under the left edge of column `col` for an unretimed clip. The position inside the clip is a fraction of a
// frame (columns are narrower than a frame once zoomed in, so whole frames would give blocks of identical columns), the
// sample is the integer part. `scrollX`, `pxPerFrame` and the clip placement are those of the viewport.
inline int64_t waveSampleAtColumn(int64_t col, float colW, double scrollX, double pxPerFrame, int64_t clipStartFrame,
                                  int64_t clipDurationFrames, int64_t sourceInFrame, int64_t sampleRate, int64_t fpsNum,
                                  int64_t fpsDen) {
    double inClip = (static_cast<double>(col) * colW + scrollX) / pxPerFrame - static_cast<double>(clipStartFrame);
    inClip = std::clamp(inClip, 0.0, static_cast<double>(clipDurationFrames));
    const double frames = static_cast<double>(sourceInFrame) + inClip;
    return static_cast<int64_t>(std::floor(frames * static_cast<double>(sampleRate) * static_cast<double>(fpsDen) /
                                           static_cast<double>(std::max<int64_t>(1, fpsNum))));
}

// The column for sample frames [s0, s1): heights from the min, max and RMS there. `reference` is the loudest level of the
// clip (audio::referenceLevel), so quiet recordings are lifted to a legible size.
inline WaveColumn waveColumn(const audio::PeakPyramid& peaks, int64_t s0, int64_t s1, float reference, audio::WaveScale scale) {
    const audio::PeakStat st = audio::reducePeaks(peaks, s0, std::max(s1, s0 + 1));
    WaveColumn c;
    c.up = audio::waveHeight(static_cast<float>(std::max<int>(0, st.max)) / 32768.0f, reference, scale);
    c.down = audio::waveHeight(static_cast<float>(std::max<int>(0, -static_cast<int>(st.min))) / 32768.0f, reference, scale);
    c.rms = std::min(audio::waveHeight(static_cast<float>(st.rms) / 32768.0f, reference, scale), std::max(c.up, c.down));
    return c;
}

}  // namespace uv::timeline
