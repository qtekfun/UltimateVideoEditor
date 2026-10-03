#include "audio/audio_mixer.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace uv::audio {

float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }

MixResult mixBlock(const PreparedSnapshot& snapshot, int64_t startSample, int32_t frames, float* out,
                   float* scratch) {
    MixResult result;
    std::memset(out, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
    const int64_t blockEnd = startSample + frames;

    for (const PreparedClip& clip : snapshot.clips) {
        const int64_t a = std::max(startSample, clip.startSample);
        const int64_t b = std::min(blockEnd, clip.endSample);
        if (a >= b) continue;

        ClipSource& src = *clip.source;
        const int64_t local = a - clip.startSample;
        const int64_t eof = src.eofAt.load(std::memory_order_acquire);
        if (local >= eof) continue;  // media shorter than the clip: silence
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(b - a, eof - local));

        if (!src.buffer.read(local, n, scratch)) {
            // A clip the worker already gave up on is reported once by the worker, not per block.
            if (!src.failed.load(std::memory_order_acquire)) {
                ++result.underruns;
                result.lastUnderrunClip = src.clipKey;
            }
            continue;
        }
        float* dst = out + static_cast<size_t>(a - startSample) * 2;
        const float g = clip.gain;
        for (int32_t i = 0; i < n * 2; ++i) dst[i] += scratch[i] * g;
    }

    for (int32_t i = 0; i < frames * 2; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);
    return result;
}

}  // namespace uv::audio
