#pragma once

// Exact timestamp <-> frame mapping for FFmpeg streams. libav expresses time as `pts * timeBase`; the project
// counts integer frames at a rational rate. Everything here is integer arithmetic (128-bit intermediates), with
// no floats and no libav types, so it is host-tested.

#include <cstdint>

#include "decode/frame_rate.h"

namespace uv::decode::ffmpeg {

struct TimeBase {
    int64_t num = 1;  // seconds per tick = num / den
    int64_t den = 1000;
};

namespace detail {

inline __int128 floorDiv(__int128 n, __int128 d) {  // d > 0
    __int128 q = n / d;
    if (n % d != 0 && n < 0) --q;
    return q;
}

inline __int128 roundHalfUpDiv(__int128 n, __int128 d) { return floorDiv(2 * n + d, 2 * d); }

}  // namespace detail

// Frame index of the picture presented at `pts`, relative to the stream's first timestamp `startPts`
// (round half up, so a pts that is a rounding error away from a frame boundary still lands on that frame).
inline int64_t ptsToFrame(int64_t pts, TimeBase tb, int64_t startPts, Rational fps) {
    const __int128 ticks = static_cast<__int128>(pts) - startPts;
    const __int128 n = ticks * tb.num * fps.num;
    const __int128 d = static_cast<__int128>(tb.den) * fps.den;
    return static_cast<int64_t>(detail::roundHalfUpDiv(n, d));
}

// The first timestamp (stream ticks) at or before the presentation time of `frame`: what to ask a demuxer
// to seek to so the frame is not skipped (floor, never ahead of the frame).
inline int64_t frameToPtsFloor(int64_t frame, TimeBase tb, int64_t startPts, Rational fps) {
    const __int128 n = static_cast<__int128>(frame) * fps.den * tb.den;
    const __int128 d = static_cast<__int128>(fps.num) * tb.num;
    return static_cast<int64_t>(startPts + detail::floorDiv(n, d));
}

// Microseconds to stream ticks, rounded down (for seeking by time).
inline int64_t microsToPtsFloor(int64_t micros, TimeBase tb, int64_t startPts) {
    const __int128 n = static_cast<__int128>(micros) * tb.den;
    const __int128 d = static_cast<__int128>(1000000) * tb.num;
    return static_cast<int64_t>(startPts + detail::floorDiv(n, d));
}

// Number of frames covered by `durationTicks` (round half up), at least 0.
inline int64_t durationToFrames(int64_t durationTicks, TimeBase tb, Rational fps) {
    if (durationTicks <= 0) return 0;
    return ptsToFrame(durationTicks, tb, 0, fps);
}

// Container frame rate: an explicit override, else the average rate, else the codec's nominal rate (both as
// libav rationals; 0/0 means unknown), else 30 fps. Near-standard rates snap to the exact broadcast rational
// so frame indices never drift.
inline Rational chooseFrameRate(Rational override_, Rational average, Rational nominal) {
    if (override_.num > 0 && override_.den > 0) return override_;
    const auto valid = [](Rational r) { return r.num > 0 && r.den > 0; };
    const Rational picked = valid(average) ? average : (valid(nominal) ? nominal : Rational{30, 1});
    const double asDouble = static_cast<double>(picked.num) / static_cast<double>(picked.den);
    const Rational snapped = snapFrameRate(asDouble);
    if (snapped.den != 1000) return snapped;  // a table hit: the exact broadcast rational
    // snapFrameRate falls back to a 1/1000 approximation for unknown rates; keep the exact value, reduced.
    int64_t a = picked.num;
    int64_t b = picked.den;
    while (b != 0) {
        const int64_t t = a % b;
        a = b;
        b = t;
    }
    return Rational{picked.num / a, picked.den / a};
}

// MediaFormat.COLOR_TRANSFER_* (what the rest of the pipeline expects) from an AVColorTransferCharacteristic.
inline int32_t mediaTransferFromAv(int avTrc) {
    switch (avTrc) {
        case 8:   // AVCOL_TRC_LINEAR
            return 1;  // COLOR_TRANSFER_LINEAR
        case 16:  // AVCOL_TRC_SMPTE2084
            return 6;  // COLOR_TRANSFER_ST2084
        case 18:  // AVCOL_TRC_ARIB_STD_B67
            return 7;  // COLOR_TRANSFER_HLG
        case 0:   // reserved
        case 2:   // unspecified
            return 0;
        default:
            return 3;  // COLOR_TRANSFER_SDR_VIDEO
    }
}

// Clockwise degrees a player must rotate a picture (0/90/180/270) from a display-matrix rotation, which
// libav reports counter-clockwise in degrees (any value; snapped to the nearest quarter turn).
inline int32_t clockwiseRotationFromDisplayMatrix(double counterClockwiseDegrees) {
    long q = static_cast<long>(counterClockwiseDegrees / 90.0 + (counterClockwiseDegrees < 0 ? -0.5 : 0.5));
    q = ((-q) % 4 + 4) % 4;
    return static_cast<int32_t>(q * 90);
}

}  // namespace uv::decode::ffmpeg
