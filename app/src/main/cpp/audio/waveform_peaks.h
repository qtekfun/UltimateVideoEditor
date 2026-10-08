#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "core/error.h"

namespace uv::audio {

// One zoom level: each peak summarises `samplesPerPeak` mono sample frames as (min, max), and from kFirstRmsSamplesPerPeak
// on also their RMS (a peak of a few samples has no meaningful RMS; zoomed in that far only the envelope is drawn).
struct PeakLevel {
    uint32_t samplesPerPeak = 0;
    std::vector<int16_t> data;  // interleaved min,max pairs
    std::vector<int16_t> rms;   // one per peak, root mean square of the folded samples, 0..32767; empty below kFirstRmsSamplesPerPeak
    size_t count() const { return data.size() / 2; }
    bool hasRms() const { return rms.size() == count() && !rms.empty(); }
};

struct PeakPyramid {
    uint32_t sampleRate = 0;
    int channels = 0;  // of the decoded source; diagnostics only, not stored in the cache (0 when read from it)
    int64_t totalFrames = 0;  // sample frames in the source (after decode)
    std::vector<PeakLevel> levels;  // ascending samplesPerPeak
};

// The finest level is 16 samples per peak: at the closest timeline zoom (96 px per frame) a pixel is 8 to 17 samples of a
// 60 to 30 fps project, so the envelope is about one peak per pixel and individual transients are visible when cutting.
constexpr uint32_t kBaseSamplesPerPeak = 16;
constexpr uint32_t kLevelRatio = 4;
constexpr int kLevelCount = 7;  // 16 .. 65536
constexpr uint32_t kFirstRmsSamplesPerPeak = 64;
// A source longer than this many sample frames (about 22 minutes at 48 kHz) keeps no 16 sample level: that level is a quarter
// byte per sample, so it would grow past 18 MB of memory and cache for one file. Its zoom is then limited to 64 samples per peak.
constexpr int64_t kMaxFineLevelFrames = 64'000'000;

// Streaming builder: feed decoded interleaved PCM in any chunk size. Channels are folded to
// mono by taking the extreme value across channels so loud channels are never hidden (min and max over all
// channels; the RMS is taken over the channel with the largest magnitude in each sample frame).
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
        double sumSq = 0.0;   // sum of squares of the samples covered so far
        uint64_t samples = 0; // samples covered so far
        bool any() const { return filled > 0; }
    };
    void pushPeak(int level, int16_t mn, int16_t mx, double sumSq, uint64_t samples);

    int channels_;
    int64_t totalFrames_ = 0;
    bool fineDropped_ = false;  // the 16 sample level was given up (see kMaxFineLevelFrames)
    PeakPyramid out_;
    Acc acc_[kLevelCount];
};

// What a stretch of samples looks like: the extremes and the RMS.
struct PeakStat {
    int16_t min = 0;
    int16_t max = 0;
    int16_t rms = 0;
};

// Index of the level to read for columns of `samplesPerColumn` samples: the coarsest one whose peaks are no wider than a
// column (so a column reads a handful of peaks, never one wide peak for many columns), or the finest one there is when even
// that is wider. Levels without peaks (dropped for a very long source) are never chosen.
size_t levelForColumn(const PeakPyramid& p, int64_t samplesPerColumn);

// Min, max and RMS of sample frames [startFrame, endFrame), read from the level chosen for that width. Silence and
// out-of-range stretches give zeros. A stretch shorter than a peak reads the peak it falls in, so the result can be a little
// wider than the exact extremes; never narrower.
PeakStat reducePeaks(const PeakPyramid& p, int64_t startFrame, int64_t endFrame);

// Fills `columns` (min,max) pairs covering sample frames [startFrame, endFrame). Picks the
// coarsest level that still has at least one peak per column. Out-of-range columns yield (0,0).
void queryPeaks(const PeakPyramid& p, int64_t startFrame, int64_t endFrame, int columns, int16_t* outMinMax);

// Loudest absolute sample of [startFrame, endFrame) as a fraction of full scale (read from the coarsest level, so it is an
// upper bound within one coarse peak), never below kMinReferenceLevel so near-silence is not blown up into a loud-looking
// waveform. The whole source when the range is empty.
constexpr float kMinReferenceLevel = 0.0625f;  // at most 24 dB of automatic gain
float referenceLevel(const PeakPyramid& p);
// The range version (a clip's own level, what the timeline draws against) takes half of the loudest peak: a lone click or
// pop would otherwise squash all speech into a thin line, so speech fills about half the lane and the odd spike is clipped.
constexpr float kClipReferenceHeadroom = 0.5f;
float referenceLevel(const PeakPyramid& p, int64_t startFrame, int64_t endFrame);

// How an amplitude (fraction of full scale) becomes a height in [0, 1] of the half lane.
enum class WaveScale : int {
    Linear = 0,   // amplitude / reference: speech and silence differ the way they sound
    Decibel = 1,  // 20 log10 over kWaveDbRange below the reference: quiet passages stay visible
};
constexpr float kWaveDbRange = 54.0f;
inline WaveScale waveScaleFromInt(int v) { return v == static_cast<int>(WaveScale::Decibel) ? WaveScale::Decibel : WaveScale::Linear; }

// Height in [0, 1] for the magnitude `amplitude` (fraction of full scale, sign ignored) against `reference`.
float waveHeight(float amplitude, float reference, WaveScale scale);

// File format "UVPK" v2: magic, version, sampleRate, totalFrames, levelCount, then per level
// {samplesPerPeak, count, int16 minmax[count*2], int16 rms[count] (only when samplesPerPeak >= kFirstRmsSamplesPerPeak)}.
// Version 1 (64 sample base, no rms) is rejected so the cache is rebuilt, and so is a file with sample rate 0 or no frames
// (it could only be drawn as nothing).
// Writes are atomic (temp file + rename).
core::Status savePeaks(const std::string& path, const PeakPyramid& p);
core::Status loadPeaks(const std::string& path, PeakPyramid* out);

}  // namespace uv::audio
