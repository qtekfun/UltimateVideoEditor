#pragma once

#include <cmath>
#include <cstdint>

namespace uv::decode {

struct Rational {
    int64_t num;
    int64_t den;
};

// Snaps a container-reported float fps to the exact rational used by video standards,
// so frame indices never drift. Unknown rates fall back to a 1/1000 approximation.
inline Rational snapFrameRate(double fps) {
    struct Entry {
        double fps;
        Rational r;
    };
    static constexpr Entry kTable[] = {
        {23.976, {24000, 1001}}, {24.0, {24, 1}},          {25.0, {25, 1}},
        {29.97, {30000, 1001}},  {30.0, {30, 1}},          {47.952, {48000, 1001}},
        {48.0, {48, 1}},         {50.0, {50, 1}},          {59.94, {60000, 1001}},
        {60.0, {60, 1}},         {119.88, {120000, 1001}}, {120.0, {120, 1}},
    };
    for (const Entry& e : kTable) {
        if (std::fabs(fps - e.fps) < 0.01) return e.r;
    }
    return {static_cast<int64_t>(std::llround(fps * 1000.0)), 1000};
}

// Integer-only pts <-> frame conversions (round half up), as required by the time-base rules.
inline int64_t ptsUsToFrame(int64_t ptsUs, Rational fps) {
    const __int128 n = static_cast<__int128>(ptsUs) * fps.num + 500000 * static_cast<__int128>(fps.den);
    const __int128 d = 1000000 * static_cast<__int128>(fps.den);
    __int128 q = n / d;
    if (n % d != 0 && n < 0) --q;  // floor for negatives
    return static_cast<int64_t>(q);
}

inline int64_t frameToPtsUs(int64_t frame, Rational fps) {
    const __int128 n = static_cast<__int128>(frame) * 1000000 * fps.den + fps.num / 2;
    return static_cast<int64_t>(n / fps.num);
}

}  // namespace uv::decode
