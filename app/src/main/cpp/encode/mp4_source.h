#pragma once

// Reads the sample tables of the first video track of an ISO BMFF (MP4/MOV) file, through pread so that readers of one
// descriptor never disturb each other's offset. Smart export (SPECS.md 5.10) copies compressed samples from here. Pure
// parsing over a byte source: the host tests feed it memory buffers.

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <vector>

namespace uv::encode {

// pread-like: fills `size` bytes at `offset` or returns false.
using ByteSource = std::function<bool(uint64_t offset, void* out, size_t size)>;

struct Mp4Sample {
    uint64_t offset = 0;
    uint32_t size = 0;
    int64_t dts = 0;  // media time units, from stts
    int64_t pts = 0;  // dts + ctts, before the edit list
    bool sync = false;
};

struct Mp4VideoTrack {
    uint32_t timescale = 0;
    std::vector<Mp4Sample> samples;  // decode order
    int64_t editStart = 0;           // media time of the first presented sample (single edit list entry), 0 without one
    std::string entryType;           // "hvc1", "hev1", "avc1", ...
    std::vector<uint8_t> codecConfig;  // payload of the hvcC / avcC box
    uint32_t width = 0;
    uint32_t height = 0;
    int rotationDegrees = 0;  // 0, 90, 180, 270 clockwise from the track matrix; -1 for a mirrored or skewed one
    bool hasNclx = false;
    uint16_t primaries = 0, transfer = 0, matrix = 0;
    bool fullRange = false;
    std::vector<uint8_t> colrPayload;  // the whole colr box payload, empty when absent
};

// Returns nullopt with a reason when the file has no usable first video track (unsupported layout: fragmented, several sample
// entries, several edits...). Never throws.
std::optional<Mp4VideoTrack> readMp4VideoTrack(const ByteSource& source, uint64_t fileSize, std::string* whyNot);

}  // namespace uv::encode
