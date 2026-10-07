#pragma once

// Smart export: what a source file offers (SPECS.md 5.10). Reads the file's sample tables, decides whether its samples can be
// copied into an export with the given settings, and lists the GOP structure the planner needs.

#include <cstdint>
#include <string>

#include "encode/export_math.h"
#include "encode/hevc_nal.h"
#include "encode/mp4_source.h"
#include "encode/smart_plan.h"

namespace uv::encode::smart {

struct OutputSpec {
    int32_t width = 0;
    int32_t height = 0;
    Fps fps;
    bool hdr = false;  // HEVC Main10, BT.2020 HLG; otherwise HEVC Main 8 bit, BT.709
};

struct InspectedAsset {
    AssetForSmart plan;           // what the planner sees (ok = false with `why` when the file cannot be copied)
    Mp4VideoTrack track;          // sample tables, valid when `parsed`
    hevc::ParameterSets sets;     // the file's own parameter sets
    std::vector<uint8_t> hvcc;    // the file's hvcC payload
    bool parsed = false;
    std::string why;
};

// Reads `source` and checks every property a copied stream must share with the output: HEVC with parameter sets in the sample
// entry and 4 byte lengths, the profile, bit depth and chroma format the encoder produces, the output's picture size, colour
// tags equal to the output's, a track matrix that is a plain 0 or 180 degree turn, sample times on the output's frame grid.
InspectedAsset inspectAsset(const ByteSource& source, uint64_t fileSize, const OutputSpec& out);

}  // namespace uv::encode::smart
