#pragma once

// On-disk result of the analysis pass: the frame-to-frame camera motion of a stretch of one media file,
// before any smoothing, so changing the strength or the crop level never needs a new analysis.
//
// File `stab/<assetId>.<hash>`; little-endian:
//   0   char[4]  "UVST"
//   4   u32      format version (1)
//   8   u32      analysis version (bumped when the tracker changes in a way that alters results)
//   12  f32      aspect ratio (width / height) of the upright frame
//   16  i32      analysis width, 20 i32 analysis height (informational)
//   24  i64      range start, microseconds from the first frame of the media
//   32  i64      range end (the last analysed frame's time)
//   40  i64      sample count N
//   48  N x { i64 ptsUs, f32 tx, f32 ty, f32 theta, f32 logScale, f32 quality }   (28 bytes each)
//   end u32      CRC-32 of everything before it
// Kotlin reads the header (engine/stabilise/StabCacheHeader.kt); keep the offsets in sync.

#include <cstdint>
#include <string>
#include <vector>

#include "core/error.h"
#include "stabilise/path.h"

namespace uv::stab {

inline constexpr uint32_t kCacheFormatVersion = 1;
inline constexpr uint32_t kAnalysisVersion = 1;
inline constexpr size_t kCacheHeaderBytes = 48;
inline constexpr size_t kCacheSampleBytes = 28;

struct CacheHeader {
    uint32_t analysisVersion = kAnalysisVersion;
    float aspect = 16.0f / 9.0f;
    int32_t analysisWidth = 0;
    int32_t analysisHeight = 0;
    int64_t rangeStartUs = 0;
    int64_t rangeEndUs = 0;
};

uint32_t crc32(const uint8_t* data, size_t size);

// Writes through a temporary file and renames it, so a reader never sees a half-written cache.
core::Status writeCache(const std::string& path, const CacheHeader& header, const std::vector<MotionSample>& samples);

// UnsupportedFormat for a file that is not a valid cache (wrong magic, version, size or checksum).
core::Status readCache(const std::string& path, CacheHeader* header, std::vector<MotionSample>* samples);

}  // namespace uv::stab
