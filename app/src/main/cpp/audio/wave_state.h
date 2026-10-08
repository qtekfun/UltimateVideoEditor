#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>

#include "audio/waveform_peaks.h"

namespace uv::audio {

// What the timeline knows about the waveform of one asset. Every state is drawn differently (timeline_renderer.cpp), so a flat
// line never has two meanings: Loading (not delivered yet), Ready (draw the envelope), Silent (decoded fine, the audio really
// is silent: a flat baseline) and Failed (no usable peaks: a dashed line). A decode or cache problem is never Silent.
enum class WaveState : int { Loading = 0, Ready = 1, Silent = 2, Failed = 3, Unrequested = 4 };

struct WaveStatus {
    WaveState state = WaveState::Unrequested;
    std::shared_ptr<const PeakPyramid> peaks;  // set when state is Ready or Silent
};

// Unrequested is an asset nobody asked a waveform for (no audio track, or the file is not readable yet): nothing is drawn.

// A pyramid the renderer can map columns onto: a sample rate (a zero rate maps every column to sample 0), samples, and at
// least one level with peaks.
inline bool pyramidUsable(const PeakPyramid& p) {
    if (p.sampleRate == 0 || p.totalFrames <= 0) return false;
    return std::any_of(p.levels.begin(), p.levels.end(), [](const PeakLevel& l) { return l.count() > 0; });
}

// Loudest absolute sample of the whole source (from the coarsest level, which holds the global extremes), 0..32768.
inline int peakMax(const PeakPyramid& p) {
    int peak = 0;
    for (auto it = p.levels.rbegin(); it != p.levels.rend(); ++it) {
        if (it->count() == 0) continue;
        for (const int16_t v : it->data) peak = std::max(peak, std::abs(static_cast<int>(v)));
        break;
    }
    return peak;
}

inline WaveState classifyPeaks(const PeakPyramid& p) {
    if (!pyramidUsable(p)) return WaveState::Failed;
    return peakMax(p) == 0 ? WaveState::Silent : WaveState::Ready;
}

inline const char* waveStateName(WaveState s) {
    switch (s) {
        case WaveState::Loading: return "requested";
        case WaveState::Ready: return "ready";
        case WaveState::Silent: return "silent";
        case WaveState::Failed: return "failed";
        case WaveState::Unrequested: return "unrequested";
    }
    return "?";
}

// The one INFO line (tag uv_wave) written when an asset's waveform changes state: asset key, state, and for peaks the sample
// rate, channels (0 when read from the cache), duration in ms, loudest sample and the pyramid levels; `status` for a failure.
inline std::string waveLogLine(int64_t assetKey, WaveState state, const PeakPyramid* p, int status, bool fromCache) {
    char buf[256];
    int n = std::snprintf(buf, sizeof(buf), "asset=%lld %s", static_cast<long long>(assetKey), waveStateName(state));
    if (p != nullptr && n > 0 && static_cast<size_t>(n) < sizeof(buf)) {
        const long long ms = p->sampleRate > 0 ? static_cast<long long>(p->totalFrames * 1000 / p->sampleRate) : 0;
        size_t levels = 0;
        for (const auto& l : p->levels) levels += l.count() > 0 ? 1 : 0;
        std::snprintf(buf + n, sizeof(buf) - static_cast<size_t>(n), " rate=%u channels=%d duration_ms=%lld peak_max=%d levels=%zu/%zu source=%s",
                      p->sampleRate, p->channels, ms, peakMax(*p), levels, p->levels.size(), fromCache ? "cache" : "decode");
    }
    std::string line = buf;
    if (state == WaveState::Failed) line += " status=" + std::to_string(status);
    return line;
}

}  // namespace uv::audio
