#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>

namespace uv::timeline {

// Where the ruler puts its labelled ticks and the small ticks between them, for the current zoom. Pure functions so the
// choices can be tested on the host.
struct RulerPlan {
    int64_t step;  // frames between two labelled ticks
    int minorDiv;  // the step is cut into this many parts by small ticks; 1 means none
};

// The nearest "round" label spacing (a frame count, seconds, minutes, hours) that leaves at least `minLabelPx` between
// labelled ticks, and a subdivision whose small ticks are at least `minMinorPx` apart.
inline RulerPlan planRuler(int64_t fps, double pxPerFrame, double minLabelPx, double minMinorPx) {
    if (fps < 1) fps = 1;
    const int64_t candidates[] = {1,        2,         5,         10,         fps,        2 * fps,    5 * fps,
                                  10 * fps, 15 * fps,  30 * fps,  60 * fps,   120 * fps,  300 * fps,  600 * fps,
                                  1800 * fps, 3600 * fps, 7200 * fps, 21600 * fps, 86400 * fps};
    int64_t step = candidates[sizeof(candidates) / sizeof(candidates[0]) - 1];
    int64_t prev = 0;
    for (int64_t cand : candidates) {
        if (cand <= prev) continue;  // a low frame rate makes the frame steps collide with the second steps
        prev = cand;
        if (static_cast<double>(cand) * pxPerFrame >= minLabelPx) {
            step = cand;
            break;
        }
    }
    int minor = 1;
    for (int d : {5, 4, 3, 2}) {
        if (step % d == 0 && static_cast<double>(step / d) * pxPerFrame >= minMinorPx) {
            minor = d;
            break;
        }
    }
    return {step, minor};
}

// "0:05", "12:30", "1:00:00" for whole-second steps; "0:05:12" (minutes:seconds:frames), or "1:00:05:12" with hours,
// when the step is shorter than a second. Returns the length written (without the terminator).
inline size_t formatRulerLabel(int64_t frame, int64_t fps, int64_t step, char* out, size_t cap) {
    if (fps < 1) fps = 1;
    if (frame < 0) frame = 0;
    const int64_t total = frame / fps;
    const int64_t h = total / 3600, m = (total / 60) % 60, s = total % 60;
    int n;
    if (step % fps == 0) {
        if (h > 0) {
            n = std::snprintf(out, cap, "%lld:%02lld:%02lld", static_cast<long long>(h), static_cast<long long>(m), static_cast<long long>(s));
        } else {
            n = std::snprintf(out, cap, "%lld:%02lld", static_cast<long long>(total / 60), static_cast<long long>(s));
        }
    } else if (h > 0) {
        n = std::snprintf(out, cap, "%lld:%02lld:%02lld:%02lld", static_cast<long long>(h), static_cast<long long>(m),
                          static_cast<long long>(s), static_cast<long long>(frame % fps));
    } else {
        n = std::snprintf(out, cap, "%lld:%02lld:%02lld", static_cast<long long>(total / 60), static_cast<long long>(s),
                          static_cast<long long>(frame % fps));
    }
    return n < 0 ? 0 : (static_cast<size_t>(n) < cap ? static_cast<size_t>(n) : cap - 1);
}

// The playhead's timecode: minutes:seconds:frames, with hours when there are any.
inline size_t formatTimecode(int64_t frame, int64_t fps, char* out, size_t cap) { return formatRulerLabel(frame, fps, 1, out, cap); }

}  // namespace uv::timeline
