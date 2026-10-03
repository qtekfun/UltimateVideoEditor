#include "captions/mono16k.h"

#include <algorithm>

namespace uv::captions {

Mono16kConverter::Mono16kConverter(int32_t inRate) : in_(inRate > 0 ? inRate : kWhisperRate), out_(kWhisperRate) {}

void Mono16kConverter::process(const float* stereo, size_t frames, std::vector<float>* out) {
    mono_.reserve(mono_.size() + frames);
    for (size_t i = 0; i < frames; ++i) mono_.push_back(0.5f * (stereo[2 * i] + stereo[2 * i + 1]));
    inputFrames_ += static_cast<int64_t>(frames);

    // Output sample k covers input span [k*in, (k+1)*in) measured in 1/out input-sample units;
    // input sample i occupies [i*out, (i+1)*out) in the same units.
    for (;;) {
        const int64_t spanStart = nextOut_ * in_;
        const int64_t spanEnd = spanStart + in_;
        if (spanEnd > inputFrames_ * out_) break;

        const int64_t first = spanStart / out_;
        const int64_t last = (spanEnd - 1) / out_;
        double sum = 0.0;
        for (int64_t i = first; i <= last; ++i) {
            const int64_t overlap = std::min(spanEnd, (i + 1) * out_) - std::max(spanStart, i * out_);
            sum += static_cast<double>(mono_[static_cast<size_t>(i - monoStart_)]) * static_cast<double>(overlap);
        }
        out->push_back(static_cast<float>(sum / static_cast<double>(in_)));
        ++nextOut_;

        // Drop input that no later span can reach.
        const int64_t keepFrom = (nextOut_ * in_) / out_;
        if (keepFrom - monoStart_ > 8192) {
            mono_.erase(mono_.begin(), mono_.begin() + static_cast<std::ptrdiff_t>(keepFrom - monoStart_));
            monoStart_ = keepFrom;
        }
    }
}

}  // namespace uv::captions
