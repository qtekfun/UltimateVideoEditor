#pragma once

// On-disk result of a motion-tracking analysis: where one chosen point or region is in every frame of a
// stretch of one media file, in coordinates relative to the upright (rotation applied) video frame.
//
// File `track/<assetId>.<hash>`; little-endian:
//   0   char[4]  "UVTK"
//   4   u32      format version (1)
//   8   u32      analysis version (bumped when the tracker changes in a way that alters results)
//   12  f32      aspect ratio (width / height) of the upright frame
//   16  i64      seed time, microseconds from the first frame of the media (the frame the user picked)
//   24  i64      range start, 32 i64 range end (first and last tracked frame's time)
//   40  i64      sample count N
//   48  N x { i64 ptsUs, f32 cx, f32 cy, f32 w, f32 h, f32 rotation, f32 confidence, u8 lost, u8[3] pad }  (36 bytes each)
//   end u32      CRC-32 of everything before it
// cx, cy, w, h are fractions of the frame width / height (cx 0.5 is the middle); rotation is in radians, clockwise
// on screen. A `lost` sample holds the last position the tracker believed in. Kotlin reads the file
// (engine/track/TrackCacheFile.kt); keep the offsets in sync.

#include <cstdint>
#include <string>
#include <vector>

#include "core/error.h"

namespace uv::track {

inline constexpr uint32_t kTrackFormatVersion = 1;
inline constexpr uint32_t kTrackAnalysisVersion = 1;
inline constexpr size_t kTrackHeaderBytes = 48;
inline constexpr size_t kTrackSampleBytes = 36;

struct TrackSample {
    int64_t ptsUs = 0;
    float cx = 0.0f;
    float cy = 0.0f;
    float w = 0.0f;
    float h = 0.0f;
    float rotation = 0.0f;
    float confidence = 0.0f;
    bool lost = false;
};

struct TrackHeader {
    uint32_t analysisVersion = kTrackAnalysisVersion;
    float aspect = 16.0f / 9.0f;
    int64_t seedUs = 0;
    int64_t rangeStartUs = 0;
    int64_t rangeEndUs = 0;
};

// Writes through a temporary file and renames it, so a reader never sees a half-written cache.
core::Status writeTrackCache(const std::string& path, const TrackHeader& header, const std::vector<TrackSample>& samples);

// UnsupportedFormat for a file that is not a valid track cache (wrong magic, version, size or checksum).
core::Status readTrackCache(const std::string& path, TrackHeader* header, std::vector<TrackSample>* samples);

}  // namespace uv::track
