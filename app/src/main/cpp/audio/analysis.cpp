#include "audio/analysis.h"

#include <algorithm>
#include <chrono>
#include <thread>
#include <vector>

#include "audio/loudness.h"
#include "audio/resampler.h"
#include "audio/spectral_denoise.h"

namespace uv::audio {

namespace {

using core::Status;

constexpr int32_t kChunk = 4096;
// A decoder that keeps answering "nothing ready yet" for this long is treated as stuck.
constexpr auto kStallLimit = std::chrono::seconds(3);

// Streams the decoded, 48 kHz stereo audio of the range to `sink(frames, stereo)`.
template <typename Sink>
Status pump(PcmDecoder& decoder, int64_t startMicros, int64_t endMicros, const CancelCheck& cancel, Sink&& sink) {
    if (startMicros < 0 || (endMicros >= 0 && endMicros <= startMicros)) return Status::InvalidArgument;
    const int32_t rate = decoder.sampleRate();
    if (rate <= 0) return Status::UnsupportedFormat;
    if (Status st = decoder.seekToMicros(startMicros); st != Status::Ok) return st;

    LinearResampler resampler(rate, kAnalysisRate);
    const int64_t wanted = endMicros < 0 ? INT64_MAX : (endMicros - startMicros) * kAnalysisRate / 1'000'000;
    int64_t produced = 0;
    std::vector<float> in(static_cast<size_t>(kChunk) * 2), out;
    auto lastProgress = std::chrono::steady_clock::now();
    while (produced < wanted) {
        if (cancel && cancel()) return Status::Cancelled;
        const PcmReadResult r = decoder.read(in.data(), kChunk);
        if (r.status != Status::Ok) return r.status;
        if (r.frames > 0) {
            lastProgress = std::chrono::steady_clock::now();
            out.clear();
            resampler.process(in.data(), static_cast<size_t>(r.frames), &out);
            const int64_t n = std::min<int64_t>(static_cast<int64_t>(out.size() / 2), wanted - produced);
            if (n > 0) sink(n, out.data());
            produced += n;
        }
        if (r.eof) break;
        if (r.frames == 0) {
            if (std::chrono::steady_clock::now() - lastProgress > kStallLimit) return Status::CodecError;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }
    return Status::Ok;
}

}  // namespace

Status measureLoudness(PcmDecoder& decoder, int64_t startMicros, int64_t endMicros, double* lufsOut,
                       double* samplePeakOut, const CancelCheck& cancel) {
    if (lufsOut == nullptr) return Status::InvalidArgument;
    LoudnessMeter meter(kAnalysisRate);
    const Status st = pump(decoder, startMicros, endMicros, cancel,
                           [&meter](int64_t frames, const float* stereo) { meter.addFrames(stereo, frames); });
    if (st != Status::Ok) return st;
    *lufsOut = meter.integratedLufs();
    if (samplePeakOut != nullptr) *samplePeakOut = meter.samplePeak();
    return Status::Ok;
}

Status measureNoiseProfile(PcmDecoder& decoder, int64_t startMicros, int64_t endMicros, float* magnitudeOut,
                           const CancelCheck& cancel) {
    if (magnitudeOut == nullptr) return Status::InvalidArgument;
    const int64_t end = endMicros < 0 ? startMicros + kMaxNoiseProfileMicros : std::min(endMicros, startMicros + kMaxNoiseProfileMicros);
    std::vector<float> mono;
    const Status st = pump(decoder, startMicros, end, cancel, [&mono](int64_t frames, const float* stereo) {
        for (int64_t i = 0; i < frames; ++i) mono.push_back(0.5f * (stereo[2 * i] + stereo[2 * i + 1]));
    });
    if (st != Status::Ok) return st;
    return computeNoiseProfile(mono.data(), mono.size(), magnitudeOut);
}

}  // namespace uv::audio
