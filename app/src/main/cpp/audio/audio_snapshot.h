#pragma once

// Immutable description of the audio side of the timeline, as sent from Kotlin.
// Binary layout, version 7 (little endian, mirrored by engine/audio/AudioSnapshot.kt):
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
//            3 peaking, high shelf) | f32 denoiseStrength (0 = off) | i32 profileBins (0 or 513) |
//            u32 laneCount (version 5; 0 in version 4, where this was reserved): bits 0..15 the lane count,
//            bits 16..17 the shape of the two user fades (core/fade_math.h; 0 = equal power, the default)
//   knots (16 bytes each, after all clips, in clip order): i64 frame | f64 sourceFrame
//   noise profiles (profileBins x f32 each, after the knots, for the clips that have one, in clip order)
//   voice blocks (version 6, after the automation lanes, one 64-byte block per clip in clip order): 16 x f32 =
//     pitchSemitones, formantSemitones, whisperMix, ringHz, ringMix, bandLowHz, bandHighHz, driveDb, echoMs,
//     echoFeedback, echoMix, reverbSize, reverbDamping, reverbMix, 0, 0 (audio/voice_fx.h); all zero = no effect
//   voice lanes (version 7, after the voice blocks, then a trailing u32 with the byte size of the voice lanes): per clip
//     in order a u32 laneCount (0..14), then each lane: lane header (16 bytes: i32 field | u32 pointCount | u32 0 |
//     u32 0) and points (16 bytes each: i64 frame | f32 value | u32 0). `field` is the position in the voice block (0
//     pitch .. 13 reverbMix) and the lane replaces that setting while the clip plays, like the automation lanes below
//     (frames from the clip's own start, increasing, linear in between, held outside). A clip whose voice settings
//     are all zero but that has lanes still gets the effect: the engine reads the widest setting to see what exists.
//   automation lanes (version 5, after the noise profiles, per clip in order, laneCount lanes each):
//     lane header (16 bytes): i32 param | u32 pointCount | u32 0 | u32 0
//     points (16 bytes each): i64 frame | f32 value | u32 0
//   A lane replaces one static value of its clip while it plays: param 0 the gain in dB (the same sum the
//   static gainDb holds, loudness normalise included), 1 the pan, 2..6 the gain in dB of EQ band 0..4.
//   `frame` counts project frames from the clip's own start (strictly increasing, within the clip); the
//   value is interpolated linearly between points per sample and holds before the first / after the last.
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
#include "audio/voice_fx.h"
#include "core/error.h"

namespace uv::audio {

constexpr uint32_t kAudioSnapshotLaneCountMask = 0xFFFF;
constexpr uint32_t kAudioSnapshotFadeShapeShift = 16;
constexpr uint32_t kAudioSnapshotFadeShapeMask = 3;
constexpr uint32_t kAudioSnapshotMagic = 0x53415655;  // "UVAS"
constexpr uint32_t kAudioSnapshotVersion = 7;
constexpr uint32_t kAudioSnapshotMinVersion = 4;  // version 4 has no automation lanes, 4 and 5 no voice blocks, 4 to 6 no voice lanes; all still parse
constexpr size_t kAudioSnapshotVoiceBytes = kVoiceParamFloats * sizeof(float);
constexpr size_t kAudioSnapshotLaneBytes = 16;
constexpr size_t kAudioSnapshotPointBytes = 16;
constexpr uint32_t kMaxClipLanes = 7;
constexpr uint32_t kMaxLanePoints = 1u << 20;
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

// What an automation lane drives; the values are the wire codes.
enum class AutoParam : int32_t { GainDb = 0, Pan = 1, EqGain0 = 2, EqGain1 = 3, EqGain2 = 4, EqGain3 = 5, EqGain4 = 6 };
constexpr int32_t kAutoParamCount = 7;

struct AutoPoint {
    int64_t frame = 0;  // project frames after the clip's own start
    float value = 0.0f;
};

struct AutoLane {
    AutoParam param = AutoParam::GainDb;
    std::vector<AutoPoint> points;  // never empty, frames strictly increasing
};

// A keyframed field of the clip's voice effect (see VoiceSchedule in audio/voice_fx.h).
struct VoiceAutoLane {
    int32_t field = 0;              // 0..kVoiceFieldCount, the position in the voice block
    std::vector<AutoPoint> points;  // never empty, frames strictly increasing
};

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
    int32_t fadeShape = 0;  // core::FadeShape of both user fades (0 equal power, 1 linear, 2 logarithmic)
    dsp::EqParams eq;
    float denoiseStrength = 0.0f;       // 0 = off
    std::vector<float> noiseProfile;    // kDenoiseBins magnitudes when denoiseStrength > 0
    std::vector<AutoLane> lanes;        // keyframed gain, pan and EQ gains (version 5)
    VoiceParams voice;                  // voice effects (version 6); neutral = none
    std::vector<VoiceAutoLane> voiceLanes;  // keyframed voice settings (version 7)
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
