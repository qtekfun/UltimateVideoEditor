#include "audio/waveform_peaks.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

namespace uv::audio {

namespace {

constexpr uint32_t kMagic = 0x4B505655;  // "UVPK"
constexpr uint32_t kVersion = 1;

uint32_t samplesPerPeakAt(int level) {
    uint32_t spp = kBaseSamplesPerPeak;
    for (int i = 0; i < level; ++i) spp *= kLevelRatio;
    return spp;
}

}  // namespace

PeakBuilder::PeakBuilder(uint32_t sampleRate, int channels)
    : channels_(std::max(1, channels)) {
    out_.sampleRate = sampleRate;
    out_.levels.resize(kLevelCount);
    for (int i = 0; i < kLevelCount; ++i) out_.levels[i].samplesPerPeak = samplesPerPeakAt(i);
}

void PeakBuilder::pushPeak(int level, int16_t mn, int16_t mx) {
    auto& lv = out_.levels[level];
    lv.data.push_back(mn);
    lv.data.push_back(mx);
    if (level + 1 >= kLevelCount) return;
    Acc& a = acc_[level + 1];
    a.min = std::min(a.min, mn);
    a.max = std::max(a.max, mx);
    if (++a.filled == kLevelRatio) {
        const int16_t cmn = a.min, cmx = a.max;
        a = Acc{};
        pushPeak(level + 1, cmn, cmx);
    }
}

void PeakBuilder::addInterleaved(const int16_t* samples, size_t frames) {
    Acc& a = acc_[0];
    for (size_t f = 0; f < frames; ++f) {
        const int16_t* s = samples + f * channels_;
        int16_t lo = s[0], hi = s[0];
        for (int c = 1; c < channels_; ++c) {
            lo = std::min(lo, s[c]);
            hi = std::max(hi, s[c]);
        }
        a.min = std::min(a.min, lo);
        a.max = std::max(a.max, hi);
        if (++a.filled == kBaseSamplesPerPeak) {
            const int16_t mn = a.min, mx = a.max;
            a = Acc{};
            pushPeak(0, mn, mx);
        }
    }
    totalFrames_ += static_cast<int64_t>(frames);
}

PeakPyramid PeakBuilder::finish() {
    // Flush partial blocks bottom-up so the tail of the clip is still represented.
    for (int level = 0; level < kLevelCount; ++level) {
        Acc a = acc_[level];
        acc_[level] = Acc{};
        if (a.any()) pushPeak(level, a.min, a.max);
    }
    out_.totalFrames = totalFrames_;
    PeakPyramid result = std::move(out_);
    out_ = PeakPyramid{};
    return result;
}

void queryPeaks(const PeakPyramid& p, int64_t startFrame, int64_t endFrame, int columns, int16_t* outMinMax) {
    for (int i = 0; i < columns; ++i) {
        outMinMax[i * 2] = 0;
        outMinMax[i * 2 + 1] = 0;
    }
    if (columns <= 0 || endFrame <= startFrame || p.levels.empty()) return;
    const int64_t span = endFrame - startFrame;
    const int64_t perColumn = std::max<int64_t>(1, span / columns);

    // Coarsest level whose peak is no wider than one column.
    size_t li = 0;
    for (size_t i = 0; i < p.levels.size(); ++i) {
        if (static_cast<int64_t>(p.levels[i].samplesPerPeak) <= perColumn) li = i;
    }
    const PeakLevel& lv = p.levels[li];
    const int64_t spp = lv.samplesPerPeak;
    const int64_t n = static_cast<int64_t>(lv.count());

    for (int i = 0; i < columns; ++i) {
        const int64_t c0 = startFrame + span * i / columns;
        int64_t c1 = startFrame + span * (i + 1) / columns;
        if (c1 <= c0) c1 = c0 + 1;
        if (c1 <= 0 || c0 >= p.totalFrames) continue;
        const int64_t first = std::max<int64_t>(0, c0) / spp;
        const int64_t last = std::min<int64_t>(n - 1, (std::min<int64_t>(c1, p.totalFrames) - 1) / spp);
        if (first > last) continue;
        int16_t mn = INT16_MAX, mx = INT16_MIN;
        for (int64_t k = first; k <= last; ++k) {
            mn = std::min(mn, lv.data[k * 2]);
            mx = std::max(mx, lv.data[k * 2 + 1]);
        }
        outMinMax[i * 2] = mn;
        outMinMax[i * 2 + 1] = mx;
    }
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
    if (!good || header[0] != kMagic || header[1] != kVersion || levelCount == 0 || levelCount > 16) {
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
        if (count > 0) r(lv.data.data(), lv.data.size() * sizeof(int16_t));
        p.levels.push_back(std::move(lv));
    }
    std::fclose(f);
    if (!good) return Status::UnsupportedFormat;
    *out = std::move(p);
    return Status::Ok;
}

}  // namespace uv::audio
