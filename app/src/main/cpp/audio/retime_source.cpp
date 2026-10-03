#include "audio/retime_source.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace uv::audio {

namespace {
constexpr int64_t kMaxWindowSamples = 12 * 48000;  // the window never grows past about 12 s of source
constexpr int64_t kHeadMargin = 8;                  // samples kept below the playhead when trimming
}  // namespace

RetimeMap::RetimeMap(std::vector<RetimeKnot> knots, Rational projectFps, int32_t outRate, int32_t srcRate) {
    knotSample_.reserve(knots.size());
    knotSource_.reserve(knots.size());
    const double framesToOut = static_cast<double>(projectFps.den) * outRate / projectFps.num;
    const double framesToSrc = static_cast<double>(projectFps.den) * srcRate / projectFps.num;
    for (const RetimeKnot& k : knots) {
        knotSample_.push_back(static_cast<double>(k.frame) * framesToOut);
        knotSource_.push_back(k.sourceFrame * framesToSrc);
    }
    reversed_ = knotSource_.size() >= 2 && knotSource_.back() < knotSource_.front();
}

double RetimeMap::sourceSample(int64_t j) const {
    const size_t n = knotSample_.size();
    if (n == 0) return static_cast<double>(j);
    if (n == 1) return knotSource_[0] + (static_cast<double>(j) - knotSample_[0]);
    const double x = static_cast<double>(j);
    // The segment [i, i + 1] containing x; the first and last segments extend to infinity.
    size_t i = static_cast<size_t>(std::upper_bound(knotSample_.begin(), knotSample_.end(), x) - knotSample_.begin());
    i = i == 0 ? 0 : std::min(i - 1, n - 2);
    const double span = knotSample_[i + 1] - knotSample_[i];
    if (span <= 0.0) return knotSource_[i];
    return knotSource_[i] + (x - knotSample_[i]) / span * (knotSource_[i + 1] - knotSource_[i]);
}

RetimedReader::RetimedReader(PcmDecoder* decoder, RetimeMap map)
    : decoder_(decoder), map_(std::move(map)), srcRate_(decoder->sampleRate()) {}

void RetimedReader::reset() {
    window_.clear();
    winStart_ = winEnd_ = 0;
    eof_ = false;
    haveWindow_ = false;
}

RetimedReader::Result RetimedReader::fillTo(int64_t hi) {
    while (!eof_ && winEnd_ <= hi) {
        chunk_.resize(static_cast<size_t>(kReadChunk) * 2);
        const PcmReadResult r = decoder_->read(chunk_.data(), kReadChunk);
        if (r.status != core::Status::Ok) {
            status_ = r.status;
            return Result::Error;
        }
        if (r.frames > 0) {
            window_.insert(window_.end(), chunk_.begin(), chunk_.begin() + static_cast<std::ptrdiff_t>(r.frames) * 2);
            winEnd_ += r.frames;
        }
        if (r.eof) {
            eof_ = true;
        } else if (r.frames == 0) {
            return Result::NotReady;  // nothing decoded yet: the worker calls again
        }
    }
    return Result::Ok;
}

RetimedReader::Result RetimedReader::ensureWindow(int64_t lo, int64_t hi, bool reverse) {
    const bool covered = haveWindow_ && lo >= winStart_ && (hi < winEnd_ || eof_);
    if (covered) return Result::Ok;

    const bool canContinue = haveWindow_ && !eof_ && lo >= winStart_ && lo <= winEnd_ + kContinueGapSamples &&
                             winEnd_ - winStart_ < kMaxWindowSamples;
    if (canContinue) {
        const Result r = fillTo(hi);
        if (r == Result::Error) return r;
        if (!reverse) {  // forward playback never reads below the playhead again
            const int64_t keepFrom = std::max(winStart_, std::min(lo, winEnd_) - kHeadMargin);
            if (keepFrom > winStart_) {
                window_.erase(window_.begin(), window_.begin() + static_cast<std::ptrdiff_t>(keepFrom - winStart_) * 2);
                winStart_ = keepFrom;
            }
        }
        return r;
    }

    // Seek. Reverse playback decodes a whole block below the playhead so the next stretch needs no seek.
    const int64_t start = reverse ? std::max<int64_t>(0, std::min(lo, hi - kReverseBlockSamples)) : lo;
    const int64_t micros = samplesToMicros(start, srcRate_);
    const core::Status st = decoder_->seekToMicros(micros);
    if (st != core::Status::Ok) {
        status_ = st;
        return Result::Error;
    }
    window_.clear();
    winStart_ = winEnd_ = start;
    eof_ = false;
    haveWindow_ = true;
    return fillTo(hi);
}

RetimedReader::Result RetimedReader::render(int64_t from, int32_t frames, float* dst) {
    status_ = core::Status::Ok;
    if (frames <= 0) return Result::Ok;
    const double qa = map_.sourceSample(from);
    const double qb = map_.sourceSample(from + frames - 1);
    const bool reverse = qb < qa;
    const double qmin = std::min(qa, qb);
    const double qmax = std::max(qa, qb);
    const int64_t hi = static_cast<int64_t>(std::ceil(qmax)) + 2;
    if (hi < 0) {  // entirely before the start of the source: silence
        std::fill(dst, dst + static_cast<std::ptrdiff_t>(frames) * 2, 0.0f);
        return Result::Ok;
    }
    const int64_t lo = std::max<int64_t>(static_cast<int64_t>(std::floor(qmin)) - 1, 0);
    const Result ready = ensureWindow(lo, hi, reverse);
    if (ready != Result::Ok) return ready;

    const auto sampleAt = [this](int64_t i, int channel) -> float {
        if (i < winStart_ || i >= winEnd_) return 0.0f;  // before the media, or after it ended
        return window_[static_cast<size_t>(i - winStart_) * 2 + static_cast<size_t>(channel)];
    };
    for (int32_t j = 0; j < frames; ++j) {
        const double q = map_.sourceSample(from + j);
        const double base = std::floor(q);
        const float frac = static_cast<float>(q - base);
        const int64_t i0 = static_cast<int64_t>(base);
        for (int channel = 0; channel < 2; ++channel) {
            const float a = sampleAt(i0, channel);
            const float b = sampleAt(i0 + 1, channel);
            dst[static_cast<size_t>(j) * 2 + static_cast<size_t>(channel)] = a + (b - a) * frac;
        }
    }
    return Result::Ok;
}

}  // namespace uv::audio
