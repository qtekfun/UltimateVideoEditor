#include "audio/audio_mixer.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "core/crossfade_math.h"

namespace uv::audio {

namespace {

constexpr double kTrackGainSmoothingMs = 8.0;

// Everything the clip's own chain does to a block that was just read into `scratch` (clip-local
// samples [local, local + n)): EQ, pan, fade handles, transition ramps and gain, summed into `dst`.
void mixClip(const PreparedClip& clip, int64_t local, int32_t n, float* scratch, float* dst) {
    ClipDspState& st = clip.dspState;
    if (st.expectedLocal != local) st.eq.reset();  // a seek or a gap: do not smear the old filter state
    st.expectedLocal = local + n;
    dsp::eqProcess(clip.eq, st.eq, scratch, n);

    float panL = 1.0f, panR = 1.0f;
    dsp::panGains(clip.pan, &panL, &panR);
    const float g = clip.gain;
    const int64_t length = clip.endSample - clip.startSample;
    const int64_t crossOutFrom = length - clip.fadeOutSamples;
    const int64_t userOutFrom = length - clip.userFadeOutSamples;
    const bool shaped = clip.fadeInSamples > 0 || clip.fadeOutSamples > 0 || clip.userFadeInSamples > 0 ||
                        clip.userFadeOutSamples > 0;
    if (!shaped) {
        const float gl = g * panL, gr = g * panR;
        for (int32_t i = 0; i < n; ++i) {
            dst[2 * i] += scratch[2 * i] * gl;
            dst[2 * i + 1] += scratch[2 * i + 1] * gr;
        }
        return;
    }
    for (int32_t i = 0; i < n; ++i) {
        const int64_t s = local + i;
        float gain = g;
        if (s < clip.fadeInSamples) gain *= core::crossfadeFadeInGain(s, clip.fadeInSamples);
        if (clip.fadeOutSamples > 0 && s >= crossOutFrom) gain *= core::crossfadeFadeOutGain(s - crossOutFrom, clip.fadeOutSamples);
        if (s < clip.userFadeInSamples) gain *= core::crossfadeFadeInGain(s, clip.userFadeInSamples);
        if (clip.userFadeOutSamples > 0 && s >= userOutFrom) gain *= core::crossfadeFadeOutGain(s - userOutFrom, clip.userFadeOutSamples);
        dst[2 * i] += scratch[2 * i] * gain * panL;
        dst[2 * i + 1] += scratch[2 * i + 1] * gain * panR;
    }
}

void restartContinuousState(const PreparedSnapshot& snap) {
    for (const PreparedClip& c : snap.clips) {
        c.dspState.eq.reset();
        c.dspState.expectedLocal = -1;
    }
    for (const PreparedTrack& t : snap.tracks) {
        t.comp.reset();
        t.smoothedGain = t.muted ? 0.0 : t.gain;
    }
    snap.ducker.reset();
    snap.limiter.reset();
}

}  // namespace

float dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }

void PreparedSnapshot::adoptStateFrom(const PreparedSnapshot& old) const {
    for (const PreparedClip& c : clips) {
        for (const PreparedClip& o : old.clips) {
            if (o.source->clipKey == c.source->clipKey) {
                c.dspState = o.dspState;
                break;
            }
        }
    }
    for (const PreparedTrack& t : tracks) {
        for (const PreparedTrack& o : old.tracks) {
            if (o.key == t.key) {
                t.comp.reductionDb = o.comp.reductionDb;
                t.smoothedGain = o.smoothedGain;
                break;
            }
        }
    }
    ducker.env = old.ducker.env;
    ducker.gain = old.ducker.gain;
    limiter.gain = old.limiter.gain;
    expectedPos = old.expectedPos;
}

MixResult mixBlock(const PreparedSnapshot& snapshot, int64_t startSample, int32_t frames, float* out, float* scratch) {
    MixResult result;
    std::memset(out, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
    if (frames <= 0 || frames > kMaxMixBlock || snapshot.tracks.empty()) return result;
    const int64_t blockEnd = startSample + frames;
    const size_t busSize = static_cast<size_t>(kMaxMixBlock) * 2;
    const double rate = snapshot.sampleRate;

    // After a seek (or the first block) the envelopes and filters start fresh instead of carrying
    // the state of an unrelated stretch of the timeline.
    if (snapshot.expectedPos != startSample) restartContinuousState(snapshot);
    snapshot.expectedPos = blockEnd;

    float* buses = snapshot.busStorage.data();
    for (size_t t = 0; t < snapshot.tracks.size(); ++t) std::memset(buses + t * busSize, 0, static_cast<size_t>(frames) * 2 * sizeof(float));

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
        float* bus = buses + static_cast<size_t>(clip.track) * busSize;
        mixClip(clip, local, n, scratch, bus + static_cast<size_t>(a - startSample) * 2);
    }

    // Track stage: bus compressor, then volume / mute with a short ramp so toggling never clicks.
    const double smoothCoef = dsp::timeConstantCoef(kTrackGainSmoothingMs, rate);
    for (size_t t = 0; t < snapshot.tracks.size(); ++t) {
        const PreparedTrack& track = snapshot.tracks[t];
        float* bus = buses + t * busSize;
        track.comp.process(bus, frames);
        const double target = track.muted ? 0.0 : track.gain;
        for (int32_t i = 0; i < frames; ++i) {
            track.smoothedGain = smoothCoef * track.smoothedGain + (1.0 - smoothCoef) * target;
            const float g = static_cast<float>(track.smoothedGain);
            bus[2 * i] *= g;
            bus[2 * i + 1] *= g;
        }
    }

    // Ducking: the voice buses drive a gain curve applied to the music buses, per sample.
    if (snapshot.duckEnabled) {
        float* voice = snapshot.duckVoice.data();
        std::memset(voice, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
        for (size_t t = 0; t < snapshot.tracks.size(); ++t) {
            if (snapshot.tracks[t].role != TrackRole::Voice) continue;
            const float* bus = buses + t * busSize;
            for (int32_t i = 0; i < frames * 2; ++i) voice[i] += bus[i];
        }
        float* gains = snapshot.duckGains.data();
        snapshot.ducker.process(voice, frames, gains);
        for (size_t t = 0; t < snapshot.tracks.size(); ++t) {
            if (snapshot.tracks[t].role != TrackRole::Music) continue;
            float* bus = buses + t * busSize;
            for (int32_t i = 0; i < frames; ++i) {
                bus[2 * i] *= gains[i];
                bus[2 * i + 1] *= gains[i];
            }
        }
    }

    for (size_t t = 0; t < snapshot.tracks.size(); ++t) {
        const float* bus = buses + t * busSize;
        for (int32_t i = 0; i < frames * 2; ++i) out[i] += bus[i];
    }
    if (snapshot.limiterOn) snapshot.limiter.process(out, frames);
    for (int32_t i = 0; i < frames * 2; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);
    if (snapshot.meter != nullptr) snapshot.meter->publish(out, frames);
    return result;
}

}  // namespace uv::audio
