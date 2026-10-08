#include "audio/waveform_peaks.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace uv::audio {

namespace {

constexpr uint32_t kMagic = 0x4B505655;  // "UVPK"
constexpr uint32_t kVersion = 2;

uint32_t samplesPerPeakAt(int level) {
    uint32_t spp = kBaseSamplesPerPeak;
    for (int i = 0; i < level; ++i) spp *= kLevelRatio;
    return spp;
}

}  // namespace

PeakBuilder::PeakBuilder(uint32_t sampleRate, int channels)
    : channels_(std::max(1, channels)) {
    out_.sampleRate = sampleRate;
    out_.channels = channels_;
    out_.levels.resize(kLevelCount);
    for (int i = 0; i < kLevelCount; ++i) out_.levels[i].samplesPerPeak = samplesPerPeakAt(i);
}

void PeakBuilder::pushPeak(int level, int16_t mn, int16_t mx, double sumSq, uint64_t samples) {
    auto& lv = out_.levels[level];
    // The finest level is dropped for good once it would pass kMaxFineLevelFrames, so a very long source never holds it.
    if (level == 0 && !fineDropped_) {
        lv.data.push_back(mn);
        lv.data.push_back(mx);
        if (static_cast<int64_t>(lv.count()) * lv.samplesPerPeak > kMaxFineLevelFrames) {
            lv.data = {};
            fineDropped_ = true;
        }
    } else if (level > 0) {
        lv.data.push_back(mn);
        lv.data.push_back(mx);
        if (lv.samplesPerPeak >= kFirstRmsSamplesPerPeak) {
            const double meanSq = samples > 0 ? sumSq / static_cast<double>(samples) : 0.0;
            lv.rms.push_back(static_cast<int16_t>(std::min(32767.0, std::round(std::sqrt(meanSq)))));
        }
    }
    if (level + 1 >= kLevelCount) return;
    Acc& a = acc_[level + 1];
    a.min = std::min(a.min, mn);
    a.max = std::max(a.max, mx);
    a.sumSq += sumSq;
    a.samples += samples;
    if (++a.filled == kLevelRatio) {
        const Acc done = a;
        a = Acc{};
        pushPeak(level + 1, done.min, done.max, done.sumSq, done.samples);
    }
}

void PeakBuilder::addInterleaved(const int16_t* samples, size_t frames) {
    Acc& a = acc_[0];
    for (size_t f = 0; f < frames; ++f) {
        const int16_t* s = samples + f * channels_;
        int16_t lo = s[0], hi = s[0];
        int loudest = s[0];
        for (int c = 1; c < channels_; ++c) {
            lo = std::min(lo, s[c]);
            hi = std::max(hi, s[c]);
            if (std::abs(static_cast<int>(s[c])) > std::abs(loudest)) loudest = s[c];
        }
        a.min = std::min(a.min, lo);
        a.max = std::max(a.max, hi);
        a.sumSq += static_cast<double>(loudest) * static_cast<double>(loudest);
        ++a.samples;
        if (++a.filled == kBaseSamplesPerPeak) {
            const Acc done = a;
            a = Acc{};
            pushPeak(0, done.min, done.max, done.sumSq, done.samples);
        }
    }
    totalFrames_ += static_cast<int64_t>(frames);
}

PeakPyramid PeakBuilder::finish() {
    // Flush partial blocks bottom-up so the tail of the clip is still represented.
    for (int level = 0; level < kLevelCount; ++level) {
        Acc a = acc_[level];
        acc_[level] = Acc{};
        if (a.any()) pushPeak(level, a.min, a.max, a.sumSq, a.samples);
    }
    out_.totalFrames = totalFrames_;
    PeakPyramid result = std::move(out_);
    out_ = PeakPyramid{};
    return result;
}

size_t levelForColumn(const PeakPyramid& p, int64_t samplesPerColumn) {
    size_t li = 0;
    bool found = false;
    for (size_t i = 0; i < p.levels.size(); ++i) {
        if (p.levels[i].count() == 0) continue;
        if (!found) {
            li = i;  // the finest level there is, if every present level is wider than a column
            found = true;
        }
        if (static_cast<int64_t>(p.levels[i].samplesPerPeak) <= samplesPerColumn) li = i;
    }
    return li;
}

PeakStat reducePeaks(const PeakPyramid& p, int64_t startFrame, int64_t endFrame) {
    PeakStat out;
    if (p.levels.empty() || endFrame <= startFrame) return out;
    if (endFrame <= 0 || startFrame >= p.totalFrames) return out;
    const PeakLevel& lv = p.levels[levelForColumn(p, endFrame - startFrame)];
    const int64_t spp = lv.samplesPerPeak;
    const int64_t n = static_cast<int64_t>(lv.count());
    if (spp <= 0 || n <= 0) return out;
    const int64_t first = std::max<int64_t>(0, startFrame) / spp;
    const int64_t last = std::min<int64_t>(n - 1, (std::min<int64_t>(endFrame, p.totalFrames) - 1) / spp);
    if (first > last) return out;
    int16_t mn = INT16_MAX, mx = INT16_MIN;
    double sumSq = 0.0, weight = 0.0;
    for (int64_t k = first; k <= last; ++k) {
        mn = std::min(mn, lv.data[static_cast<size_t>(k) * 2]);
        mx = std::max(mx, lv.data[static_cast<size_t>(k) * 2 + 1]);
        // The last peak of the source may be partial; weight by the samples it really covers.
        const double w = static_cast<double>(std::min<int64_t>(spp, p.totalFrames - k * spp));
        const double r = static_cast<size_t>(k) < lv.rms.size() ? lv.rms[static_cast<size_t>(k)] : 0;
        sumSq += r * r * w;
        weight += w;
    }
    out.min = mn;
    out.max = mx;
    out.rms = static_cast<int16_t>(weight > 0.0 ? std::min(32767.0, std::sqrt(sumSq / weight)) : 0.0);
    return out;
}

void queryPeaks(const PeakPyramid& p, int64_t startFrame, int64_t endFrame, int columns, int16_t* outMinMax) {
    for (int i = 0; i < columns; ++i) {
        outMinMax[i * 2] = 0;
        outMinMax[i * 2 + 1] = 0;
    }
    if (columns <= 0 || endFrame <= startFrame || p.levels.empty()) return;
    const int64_t span = endFrame - startFrame;
    for (int i = 0; i < columns; ++i) {
        const int64_t c0 = startFrame + span * i / columns;
        int64_t c1 = startFrame + span * (i + 1) / columns;
        if (c1 <= c0) c1 = c0 + 1;
        const PeakStat s = reducePeaks(p, c0, c1);
        outMinMax[i * 2] = s.min;
        outMinMax[i * 2 + 1] = s.max;
    }
}

float referenceLevel(const PeakPyramid& p) {
    float peak = 0.0f;
    if (!p.levels.empty()) {
        // The coarsest level has few entries and still contains the global extremes.
        for (const int16_t v : p.levels.back().data) {
            peak = std::max(peak, std::fabs(static_cast<float>(v)) / 32768.0f);
        }
    }
    return std::max(peak, kMinReferenceLevel);
}

float referenceLevel(const PeakPyramid& p, int64_t startFrame, int64_t endFrame) {
    if (p.levels.empty() || endFrame <= startFrame) return referenceLevel(p);
    const PeakLevel& lv = p.levels.back();
    const int64_t spp = lv.samplesPerPeak;
    const int64_t n = static_cast<int64_t>(lv.count());
    if (spp <= 0 || n <= 0 || endFrame <= 0 || startFrame >= p.totalFrames) return kMinReferenceLevel;
    const int64_t first = std::max<int64_t>(0, startFrame) / spp;
    const int64_t last = std::min<int64_t>(n - 1, (std::min<int64_t>(endFrame, p.totalFrames) - 1) / spp);
    float peak = 0.0f;
    for (int64_t k = first; k <= last; ++k) {
        peak = std::max(peak, std::fabs(static_cast<float>(lv.data[static_cast<size_t>(k) * 2])) / 32768.0f);
        peak = std::max(peak, std::fabs(static_cast<float>(lv.data[static_cast<size_t>(k) * 2 + 1])) / 32768.0f);
    }
    return std::max(peak * kClipReferenceHeadroom, kMinReferenceLevel);
}

float waveHeight(float amplitude, float reference, WaveScale scale) {
    const float ref = std::max(reference, kMinReferenceLevel);
    const float x = std::fabs(amplitude) / ref;
    if (!(x > 0.0f)) return 0.0f;
    if (scale == WaveScale::Decibel) {
        const float db = 20.0f * std::log10(x);  // 0 dB at the reference, negative below
        return std::clamp((db + kWaveDbRange) / kWaveDbRange, 0.0f, 1.0f);
    }
    return std::min(1.0f, x);
}

core::Status savePeaks(const std::string& path, const PeakPyramid& p) {
    using core::Status;
    const std::string tmp = path + ".tmp";
    FILE* f = std::fopen(tmp.c_str(), "wb");
    if (f == nullptr) return Status::IoError;
    bool good = true;
    auto w = [&](const void* d, size_t n) { good = good && std::fwrite(d, 1, n, f) == n; };
    const uint32_t levelCount = static_cast<uint32_t>(p.levels.size());
    const uint32_t header[3] = {kMagic, kVersion, p.sampleRate};
    w(header, sizeof(header));
    w(&p.totalFrames, sizeof(p.totalFrames));
    w(&levelCount, sizeof(levelCount));
    for (const auto& lv : p.levels) {
        const uint32_t count = static_cast<uint32_t>(lv.count());
        w(&lv.samplesPerPeak, sizeof(uint32_t));
        w(&count, sizeof(count));
        if (count > 0) w(lv.data.data(), lv.data.size() * sizeof(int16_t));
        if (count > 0 && lv.samplesPerPeak >= kFirstRmsSamplesPerPeak) w(lv.rms.data(), lv.rms.size() * sizeof(int16_t));
    }
    good = (std::fclose(f) == 0) && good;
    if (!good || std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        return Status::IoError;
    }
    return Status::Ok;
}

core::Status loadPeaks(const std::string& path, PeakPyramid* out) {
    using core::Status;
    if (out == nullptr) return Status::InvalidArgument;
    FILE* f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return Status::IoError;
    PeakPyramid p;
    bool good = true;
    auto r = [&](void* d, size_t n) { good = good && std::fread(d, 1, n, f) == n; };
    uint32_t header[3] = {0, 0, 0};
    uint32_t levelCount = 0;
    r(header, sizeof(header));
    r(&p.totalFrames, sizeof(p.totalFrames));
    r(&levelCount, sizeof(levelCount));
    if (!good || header[0] != kMagic || header[1] != kVersion || levelCount == 0 || levelCount > 16 || header[2] == 0 || p.totalFrames <= 0) {
        std::fclose(f);
        return Status::UnsupportedFormat;
    }
    p.sampleRate = header[2];
    for (uint32_t i = 0; i < levelCount && good; ++i) {
        PeakLevel lv;
        uint32_t count = 0;
        r(&lv.samplesPerPeak, sizeof(uint32_t));
        r(&count, sizeof(count));
        // Bound by what a sane file can hold so a corrupt count cannot trigger a huge allocation.
        if (!good || lv.samplesPerPeak == 0 || count > (1u << 28)) {
            good = false;
            break;
        }
        lv.data.resize(static_cast<size_t>(count) * 2);
        const bool withRms = lv.samplesPerPeak >= kFirstRmsSamplesPerPeak;
        if (withRms) lv.rms.resize(count);
        if (count > 0) r(lv.data.data(), lv.data.size() * sizeof(int16_t));
        if (count > 0 && withRms) r(lv.rms.data(), lv.rms.size() * sizeof(int16_t));
        p.levels.push_back(std::move(lv));
    }
    std::fclose(f);
    if (!good) return Status::UnsupportedFormat;
    *out = std::move(p);
    return Status::Ok;
}

}  // namespace uv::audio
