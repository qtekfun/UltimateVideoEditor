#pragma once

// Immutable description of the audio side of the timeline, as sent from Kotlin.
// Binary layout, version 4 (little endian, mirrored by engine/audio/AudioSnapshot.kt):
//   header (28 bytes): u32 magic "UVAS" | u32 version | i32 fpsNum | i32 fpsDen | u32 clipCount |
//                      u32 trackCount | u32 flags (bit0: master limiter off)
//   tracks (40 bytes each): i64 trackKey | f32 gainDb | u32 flags (bit0 muted, bit1 compressor on) |
//                      u32 role (0 normal, 1 voice, 2 music) | f32 compThresholdDb | f32 compRatio |
//                      f32 compAttackMs | f32 compReleaseMs | f32 compMakeupDb
//   ducking (16 bytes): f32 amountDb (0 = off) | f32 thresholdDb | f32 attackMs | f32 releaseMs
//   clips (160 bytes each): the 64-byte base record, then a 96-byte audio block
//     base: i64 clipKey | i64 assetKey | i64 startFrame | i64 durationFrames |
//           i64 sourceInFrame | i32 srcFpsNum | i32 srcFpsDen | f32 gainDb |
//           i32 fadeInFrames | i32 fadeOutFrames | i32 knotCount
//     audio: i32 trackIndex | f32 pan | i32 userFadeInFrames | i32 userFadeOutFrames |
//            f32 highPassHz | f32 lowPassHz | 5 x (f32 freqHz, f32 gainDb, f32 q) EQ bands (low shelf,
//            3 peaking, high shelf) | f32 denoiseStrength (0 = off) | i32 profileBins (0 or 513) | u32 reserved
//   knots (16 bytes each, after all clips, in clip order): i64 frame | f64 sourceFrame
//   noise profiles (profileBins x f32 each, after the knots, for the clips that have one, in clip order)
// A clip with knotCount 0 plays its source at 1x from sourceInFrame. A retimed clip (speed change,
// ramp or reverse) lists knotCount >= 2 knots instead: `frame` counts project frames from the clip's
// own start (first 0, last durationFrames, strictly increasing) and `sourceFrame` is the source
// position in project frames played at that moment, linear in between and falling for a reversed
// clip (sourceInFrame is then unused).
// startFrame/durationFrames are project frames; sourceInFrame is in the asset's native frames.
// fadeInFrames/fadeOutFrames (project frames, 0 = none) are the equal-power crossfade ramps of
// a transition at the start/end of the clip; each must fit within durationFrames. The userFade
// fields are the clip's own fade handles, with the same rule.

#include <cstddef>
#include <cstdint>
#include <vector>

#include "audio/audio_time.h"
#include "audio/dsp.h"
#include "audio/retime_source.h"
#include "audio/spectral_denoise.h"
#include "core/error.h"

namespace uv::audio {

constexpr uint32_t kAudioSnapshotMagic = 0x53415655;  // "UVAS"
constexpr uint32_t kAudioSnapshotVersion = 4;
constexpr size_t kAudioSnapshotHeaderBytes = 28;
constexpr size_t kAudioSnapshotTrackBytes = 40;
constexpr size_t kAudioSnapshotDuckingBytes = 16;
constexpr size_t kAudioSnapshotClipBytes = 160;
constexpr uint32_t kMaxAudioTracks = 256;
constexpr size_t kAudioSnapshotKnotBytes = 16;
constexpr uint32_t kMaxClipKnots = 4096;
constexpr float kMinGainDb = -96.0f;
constexpr float kMaxGainDb = 24.0f;

enum class TrackRole : uint32_t { Normal = 0, Voice = 1, Music = 2 };

struct AudioTrackDesc {
    int64_t trackKey = 0;
    float gainDb = 0.0f;
    bool muted = false;
    TrackRole role = TrackRole::Normal;
    dsp::CompressorParams comp;
};

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
    // Audio tools (version 4).
    int32_t trackIndex = 0;
    float pan = 0.0f;
    int64_t userFadeInFrames = 0;
    int64_t userFadeOutFrames = 0;
    dsp::EqParams eq;
    float denoiseStrength = 0.0f;       // 0 = off
    std::vector<float> noiseProfile;    // kDenoiseBins magnitudes when denoiseStrength > 0
};

struct AudioSnapshotData {
    Rational fps;
    std::vector<AudioTrackDesc> tracks;  // never empty after parsing: a default track is added
    dsp::DuckerParams ducking;           // amountDb 0 = off
    bool limiterOff = false;
    std::vector<AudioClipDesc> clips;
};

// Validates and decodes a snapshot. Never trusts sizes from the buffer.
core::Status parseAudioSnapshot(const uint8_t* data, size_t size, AudioSnapshotData* out);

}  // namespace uv::audio
