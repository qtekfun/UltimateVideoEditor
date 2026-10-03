#include "audio/resampler.h"

#include <algorithm>

#include "audio/audio_time.h"

namespace uv::audio {

LinearResampler::LinearResampler(int32_t inRate, int32_t outRate) : inRate_(inRate), outRate_(outRate) {}

void LinearResampler::reset() {
    buf_.clear();
    bufStart_ = 0;
    nextOut_ = 0;
}

void LinearResampler::process(const float* in, size_t inFrames, std::vector<float>* out) {
    if (inRate_ == outRate_) {
        out->insert(out->end(), in, in + inFrames * 2);
        return;
    }
    buf_.insert(buf_.end(), in, in + inFrames * 2);
    const int64_t bufFrames = static_cast<int64_t>(buf_.size() / 2);
    const int64_t bufEnd = bufStart_ + bufFrames;

    for (;;) {
        const i128 num = static_cast<i128>(nextOut_) * inRate_;
        const int64_t i = floorDiv(num, outRate_);
        const int64_t rem = static_cast<int64_t>(num - static_cast<i128>(i) * outRate_);
        // Need input i, and i + 1 unless the position falls exactly on a sample.
        if (rem == 0 ? i >= bufEnd : i + 1 >= bufEnd) break;
        const float frac = static_cast<float>(rem) / static_cast<float>(outRate_);
        const size_t a = static_cast<size_t>(i - bufStart_) * 2;
        if (rem == 0) {
            out->push_back(buf_[a]);
            out->push_back(buf_[a + 1]);
        } else {
            out->push_back(buf_[a] + (buf_[a + 2] - buf_[a]) * frac);
            out->push_back(buf_[a + 1] + (buf_[a + 3] - buf_[a + 1]) * frac);
        }
        ++nextOut_;
    }

    // Drop history that no future output can reference.
    const int64_t keepFrom = floorDiv(static_cast<i128>(nextOut_) * inRate_, outRate_);
    if (keepFrom > bufStart_) {
        const int64_t drop = std::min<int64_t>(keepFrom - bufStart_, bufFrames);
        buf_.erase(buf_.begin(), buf_.begin() + drop * 2);
        bufStart_ += drop;
    }
}

}  // namespace uv::audio
