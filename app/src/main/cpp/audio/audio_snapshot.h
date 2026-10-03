#pragma once

// Immutable description of the audio side of the timeline, as sent from Kotlin.
// Binary layout (little endian, mirrored by engine/audio/AudioSnapshot.kt):
//   header: u32 magic "UVAS" | u32 version | i32 fpsNum | i32 fpsDen | u32 clipCount
//   clip (64 bytes): i64 clipKey | i64 assetKey | i64 startFrame | i64 durationFrames |
//                    i64 sourceInFrame | i32 srcFpsNum | i32 srcFpsDen | f32 gainDb |
//                    i32 fadeInFrames | i32 fadeOutFrames | i32 knotCount
//   knots (16 bytes each, after all clips, in clip order): i64 frame | f64 sourceFrame
// A clip with knotCount 0 plays its source at 1x from sourceInFrame. A retimed clip (speed change,
// ramp or reverse) lists knotCount >= 2 knots instead: `frame` counts project frames from the clip's
// own start (first 0, last durationFrames, strictly increasing) and `sourceFrame` is the source
// position in project frames played at that moment, linear in between and falling for a reversed
// clip (sourceInFrame is then unused).
// startFrame/durationFrames are project frames; sourceInFrame is in the asset's native frames.
// fadeInFrames/fadeOutFrames (project frames, 0 = none) are the equal-power crossfade ramps of
// a transition at the start/end of the clip; each must fit within durationFrames.

#include <cstddef>
#include <cstdint>
#include <vector>

#include "audio/audio_time.h"
#include "audio/retime_source.h"
#include "core/error.h"

namespace uv::audio {

constexpr uint32_t kAudioSnapshotMagic = 0x53415655;  // "UVAS"
constexpr uint32_t kAudioSnapshotVersion = 3;
constexpr size_t kAudioSnapshotHeaderBytes = 20;
constexpr size_t kAudioSnapshotClipBytes = 64;
constexpr size_t kAudioSnapshotKnotBytes = 16;
constexpr uint32_t kMaxClipKnots = 4096;
constexpr float kMinGainDb = -96.0f;
constexpr float kMaxGainDb = 24.0f;

struct AudioClipDesc {
    int64_t clipKey = 0;
    int64_t assetKey = 0;
    int64_t startFrame = 0;
    int64_t durationFrames = 0;
    int64_t sourceInFrame = 0;
    Rational sourceFps;
    float gainDb = 0.0f;
    int64_t fadeInFrames = 0;
    int64_t fadeOutFrames = 0;
    std::vector<RetimeKnot> knots;  // empty: plain 1x playback
};

struct AudioSnapshotData {
    Rational fps;
    std::vector<AudioClipDesc> clips;
};

// Validates and decodes a snapshot. Never trusts sizes from the buffer.
core::Status parseAudioSnapshot(const uint8_t* data, size_t size, AudioSnapshotData* out);

}  // namespace uv::audio
