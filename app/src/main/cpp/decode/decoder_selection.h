#pragma once

// Which decoder opens a video file: pure logic, no Android or libav types, so it is host-tested.
//
// MediaCodec always goes first. The software (FFmpeg) decoder is tried only when MediaCodec could not open
// the stream for a reason software can fix, and only when this build contains it (`-Puveditor.ffmpeg=<dir>`).

#include <algorithm>
#include <cstdint>
#include <string>

#include "decode/frame_rate.h"
#include "decode/status.h"

namespace uv::decode {

enum class Route { MediaCodec, Software, Failed };

// Whether a MediaCodec open failure with `status` is worth retrying in software. A file the platform
// cannot parse or has no decoder for may still be decodable by FFmpeg; a bad argument, a lack of memory or a
// wrong state would fail again.
inline bool softwareMayHelp(Status status) {
    switch (status) {
        case Status::UnsupportedFormat:
        case Status::CodecError:
        case Status::NotFound:  // MediaExtractor saw no video track (an unparsed container)
        case Status::IoError:   // MediaExtractor could not read the container at all
            return true;
        default:
            return false;
    }
}

// `hardware` is the result of the MediaCodec attempt (Status::Ok = it opened).
inline Route chooseRoute(Status hardware, bool softwareBuilt) {
    if (hardware == Status::Ok) return Route::MediaCodec;
    return (softwareBuilt && softwareMayHelp(hardware)) ? Route::Software : Route::Failed;
}

// The error to report when the file could not be opened at all: the MediaCodec one, with a hint when the
// software decoder was not available or also failed.
inline Error combineOpenErrors(const Error& hardware, const Error* software, bool softwareBuilt) {
    std::string message = hardware.message;
    if (software != nullptr) {
        message += "; software decoding also failed: " + software->message;
    } else if (softwareMayHelp(hardware.code) && !softwareBuilt) {
        message += " (software decoding is not included in this build)";
    }
    return Error{hardware.code, std::move(message)};
}

// What a phone CPU can be asked to do in the background. Frame threading uses the cores, but the render
// thread, the compositor and the encoder need some.
struct SoftwareBudget {
    int threads = 2;
    int64_t realtimePixels = 1920LL * 1080;  // frame size up to which 30 fps decoding is expected to keep up
};

inline SoftwareBudget softwareBudget(unsigned hardwareThreads) {
    SoftwareBudget b;
    b.threads = static_cast<int>(std::clamp<unsigned>(hardwareThreads / 2, 1u, 4u));
    return b;
}

enum class SoftwareSpeed { Realtime, Slow };

// Software decoding of a big or fast stream will not play in real time on a phone: the picture still works,
// scrubbing is fine once frames are cached, but the editor should say so and suggest a proxy.
inline SoftwareSpeed softwareSpeed(int32_t width, int32_t height, Rational fps, const SoftwareBudget& budget) {
    const int64_t pixels = static_cast<int64_t>(width) * height;
    if (pixels > budget.realtimePixels) return SoftwareSpeed::Slow;
    // Pixels per second against 1080p30.
    const __int128 perSecond = static_cast<__int128>(pixels) * fps.num;
    const __int128 limit = static_cast<__int128>(budget.realtimePixels) * 30 * fps.den;
    return perSecond > limit ? SoftwareSpeed::Slow : SoftwareSpeed::Realtime;
}

// A slow software stream gets a smaller look-ahead so it does not spend all the cores filling frames the
// playhead may never reach; a realtime one keeps the requested window.
inline int32_t softwareLookAhead(int32_t requested, SoftwareSpeed speed) {
    return speed == SoftwareSpeed::Slow ? std::min<int32_t>(requested, 12) : requested;
}

}  // namespace uv::decode
