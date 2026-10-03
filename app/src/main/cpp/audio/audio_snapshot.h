#pragma once

// Immutable description of the audio side of the timeline, as sent from Kotlin.
// Binary layout (little endian, mirrored by engine/audio/AudioSnapshot.kt):
//   header: u32 magic "UVAS" | u32 version | i32 fpsNum | i32 fpsDen | u32 clipCount
//   clip (56 bytes): i64 clipKey | i64 assetKey | i64 startFrame | i64 durationFrames |
//                    i64 sourceInFrame | i32 srcFpsNum | i32 srcFpsDen | f32 gainDb | i32 reserved
// startFrame/durationFrames are project frames; sourceInFrame is in the asset's native frames.

#include <cstddef>
#include <cstdint>
#include <vector>

#include "audio/audio_time.h"
#include "core/error.h"

namespace uv::audio {

constexpr uint32_t kAudioSnapshotMagic = 0x53415655;  // "UVAS"
constexpr uint32_t kAudioSnapshotVersion = 1;
constexpr size_t kAudioSnapshotHeaderBytes = 20;
constexpr size_t kAudioSnapshotClipBytes = 56;
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
};

struct AudioSnapshotData {
    Rational fps;
    std::vector<AudioClipDesc> clips;
};

// Validates and decodes a snapshot. Never trusts sizes from the buffer.
core::Status parseAudioSnapshot(const uint8_t* data, size_t size, AudioSnapshotData* out);

}  // namespace uv::audio
