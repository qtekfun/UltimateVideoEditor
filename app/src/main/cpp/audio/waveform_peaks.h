#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "core/error.h"

namespace uv::audio {

// One zoom level: each peak summarises `samplesPerPeak` mono sample frames as (min, max).
struct PeakLevel {
    uint32_t samplesPerPeak = 0;
    std::vector<int16_t> data;  // interleaved min,max pairs
    size_t count() const { return data.size() / 2; }
};

struct PeakPyramid {
    uint32_t sampleRate = 0;
    int64_t totalFrames = 0;  // sample frames in the source (after decode)
    std::vector<PeakLevel> levels;  // ascending samplesPerPeak
};

constexpr uint32_t kBaseSamplesPerPeak = 64;
constexpr uint32_t kLevelRatio = 4;
constexpr int kLevelCount = 6;  // 64 .. 65536

// Streaming builder: feed decoded interleaved PCM in any chunk size. Channels are folded to
// mono by taking the extreme value across channels so loud channels are never hidden.
class PeakBuilder {
public:
    PeakBuilder(uint32_t sampleRate, int channels);
    void addInterleaved(const int16_t* samples, size_t frames);
    PeakPyramid finish();

private:
    struct Acc {
        int16_t min = INT16_MAX;
        int16_t max = INT16_MIN;
        uint32_t filled = 0;  // samples (level 0) or child peaks (higher levels)
        bool any() const { return filled > 0; }
    };
    void pushPeak(int level, int16_t mn, int16_t mx);

    int channels_;
    int64_t totalFrames_ = 0;
    PeakPyramid out_;
    Acc acc_[kLevelCount];
};

// Fills `columns` (min,max) pairs covering sample frames [startFrame, endFrame). Picks the
// coarsest level that still has at least one peak per column. Out-of-range columns yield (0,0).
void queryPeaks(const PeakPyramid& p, int64_t startFrame, int64_t endFrame, int columns, int16_t* outMinMax);

// File format "UVPK" v1: magic, version, sampleRate, totalFrames, levelCount, then per level
// {samplesPerPeak, count, int16 data[count*2]}. Writes are atomic (temp file + rename).
core::Status savePeaks(const std::string& path, const PeakPyramid& p);
core::Status loadPeaks(const std::string& path, PeakPyramid* out);

}  // namespace uv::audio
