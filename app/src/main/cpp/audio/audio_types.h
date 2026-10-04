#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

#include "audio/audio_snapshot.h"
#include "audio/audio_time.h"
#include "audio/clip_buffer.h"
#include "audio/dsp.h"
#include "audio/pcm_decoder.h"
#include "audio/resampler.h"
#include "audio/retime_source.h"
#include "audio/spectral_denoise.h"
#include "audio/voice_fx.h"

namespace uv::audio {

// Per-clip decode state. `buffer`, `eofAt` and `failed` are shared with the audio thread; the
// rest is touched only by the decode worker. Sources survive snapshot edits that merely move or
// re-gain a clip (the buffer is clip-local), so such edits never restart decoding.
struct ClipSource {
    ClipSource(int64_t key, int64_t asset, int64_t srcInMicros, int32_t bufferFrames, std::vector<RetimeKnot> retimeKnots = {},
               Rational projectFps = {}, float strength = 0.0f, std::vector<float> profile = {}, uint64_t hash = 0,
               VoiceParams voiceParams = {}, uint64_t voiceHashValue = 0, std::shared_ptr<const VoiceSchedule> schedule = nullptr)
        : buffer(bufferFrames),
          clipKey(key),
          assetKey(asset),
          sourceInMicros(srcInMicros),
          knots(std::move(retimeKnots)),
          fps(projectFps),
          denoiseStrength(strength),
          noiseProfile(std::move(profile)),
          denoiseHash(hash),
          voice(voiceParams),
          voiceHash(voiceHashValue),
          voiceSchedule(std::move(schedule)),
          voiceEnvelope(voiceSchedule && !voiceSchedule->empty() ? voiceSchedule->envelope(voiceParams) : voiceParams) {}

    ClipBuffer buffer;
    const int64_t clipKey;
    const int64_t assetKey;
    const int64_t sourceInMicros;
    // A retimed clip (speed, ramp, reverse) reads its source through these knots instead of at 1x
    // from sourceInMicros; see audio/retime_source.h. Part of the source's identity: a new retime
    // makes a new source, so the clip buffer is never reused across it.
    const std::vector<RetimeKnot> knots;
    const Rational fps;  // project frame rate, the unit of the knots
    // Noise suppression runs in the decode worker, on the clip's own audio before it is buffered
    // (from the clip's snapshot block). Part of the source's identity like the retime knots.
    const float denoiseStrength;
    const std::vector<float> noiseProfile;
    const uint64_t denoiseHash;
    // Voice effects run in the decode worker too, after the noise suppressor (see audio/voice_fx.h). Part of the
    // source's identity like the denoise settings: a changed effect makes a new source and restarts decoding.
    const VoiceParams voice;
    const uint64_t voiceHash;
    // Keyframed voice settings (null: fixed) and the widest setting over them, which decides whether the effect exists and
    // how long its tail is. Both are part of the identity through voiceHash.
    const std::shared_ptr<const VoiceSchedule> voiceSchedule;
    const VoiceParams voiceEnvelope;
    std::atomic<int64_t> eofAt{INT64_MAX};  // clip-local sample where decoded audio ends
    std::atomic<bool> failed{false};
    std::atomic<int32_t> failures{0};  // consecutive failures; reset once the clip decodes again

    // Worker only.
    std::unique_ptr<PcmDecoder> decoder;
    std::optional<LinearResampler> resampler;
    std::unique_ptr<RetimedReader> retimed;  // set instead of `resampler` for a retimed clip
    std::unique_ptr<SpectralDenoiser> denoiser;
    std::unique_ptr<VoiceProcessor> voiceFx;
    std::vector<float> voiceScratch;
    int64_t drainLeft = 0;  // silence still to feed the voice effect after the media ended, to let its tail sound
    int64_t decodedEnd = 0;    // clip-local samples appended to the buffer
    int64_t renderedEnd = 0;   // retimed path: clip-local samples already asked of the reader (decodedEnd lags with a denoiser)
    std::vector<float> denoisedScratch;
    bool hitEof = false;
    std::chrono::steady_clock::time_point retryAfter{};  // failed clips are retried no sooner
    std::vector<float> srcScratch;
    std::vector<float> outScratch;

    // True when the audio thread can play [localPos, localPos + frames) without an underrun, or
    // when waiting would be pointless (failed or past the end of the media). An offline render passes
    // `waitForFailed`: a clip that failed fewer than `maxFailures` times is retried by the worker, and
    // rendering on without it would put a silent hole in the export.
    bool ready(int64_t localPos, int32_t frames, bool waitForFailed = false, int32_t maxFailures = 0) const {
        if (failed.load(std::memory_order_acquire)) {
            return !(waitForFailed && failures.load(std::memory_order_acquire) <= maxFailures);
        }
        const int64_t eof = eofAt.load(std::memory_order_acquire);
        if (localPos >= eof) return true;
        const int32_t need = static_cast<int32_t>(std::min<int64_t>(frames, eof - localPos));
        return buffer.covers(localPos, need);
    }
};

// Mixer-side state of one clip that must survive between blocks (and between snapshots that only
// change other things). Touched by the audio thread only.
struct ClipDspState {
    dsp::EqState eq;
    int64_t expectedLocal = -1;  // clip-local sample the next block should start at; else the filters restart
};

// A keyframed value of a clip converted to the output sample grid: `samples[i]` (clip-local) carries
// `values[i]`, linear in between, held before the first and after the last. A gain lane holds linear
// gain (the dB points converted once), the others hold the raw value (pan, EQ gain in dB).
struct PreparedLane {
    AutoParam param = AutoParam::GainDb;
    std::vector<int64_t> samples;
    std::vector<float> values;

    // The value at clip-local sample `s`.
    float at(int64_t s) const {
        if (s <= samples.front()) return values.front();
        if (s >= samples.back()) return values.back();
        const auto it = std::upper_bound(samples.begin(), samples.end(), s);
        const size_t hi = static_cast<size_t>(it - samples.begin());
        const size_t lo = hi - 1;
        const double t = static_cast<double>(s - samples[lo]) / static_cast<double>(samples[hi] - samples[lo]);
        return static_cast<float>(values[lo] + (values[hi] - values[lo]) * t);
    }
};

struct PreparedClip {
    int64_t startSample = 0;  // timeline samples at the output rate
    int64_t endSample = 0;
    float gain = 1.0f;        // linear
    int32_t track = 0;        // index into PreparedSnapshot::tracks
    float pan = 0.0f;
    int64_t userFadeInSamples = 0;   // the clip's own fade handles (equal power)
    int64_t userFadeOutSamples = 0;
    dsp::EqChain eq;                 // coefficients, designed once at the output rate (all five band stages when a lane drives a band)
    // Keyframed gain, pan and EQ band gains (empty when nothing is animated). While any lane exists the
    // clip is mixed in short chunks that re-evaluate the lanes; `eqParams` and `sampleRate` redesign a band
    // whose gain moves.
    std::vector<PreparedLane> lanes;
    dsp::EqParams eqParams;
    double sampleRate = 48000.0;
    mutable ClipDspState dspState;
    // Equal-power crossfade lengths in samples (0 = none): the clip fades in over its first
    // fadeInSamples and out over its last fadeOutSamples (core/crossfade_math.h).
    int64_t fadeInSamples = 0;
    int64_t fadeOutSamples = 0;
    std::shared_ptr<ClipSource> source;
};

struct PreparedTrack {
    int64_t key = 0;
    float gain = 1.0f;          // linear target (volume)
    bool muted = false;
    TrackRole role = TrackRole::Normal;
    mutable dsp::Compressor comp;
    mutable double smoothedGain = 1.0;  // follows the target without clicks
};

constexpr int32_t kMaxMixBlock = 4096;

// Snapshot converted to output-rate sample positions, ready for the audio thread. The `mutable`
// members are DSP state owned by the audio thread; adoptStateFrom() carries them over from the
// snapshot that was playing, so an edit (a slider moved during playback) does not restart filters.
struct PreparedSnapshot {
    uint64_t generation = 0;
    int32_t sampleRate = 48000;
    Rational fps;
    std::vector<PreparedClip> clips;
    std::vector<PreparedTrack> tracks;       // at least one
    bool duckEnabled = false;
    mutable dsp::Ducker ducker;
    bool limiterOn = true;
    mutable dsp::Limiter limiter;
    dsp::PeakMeter* meter = nullptr;         // owned by AudioCore, written by the mixer
    // Scratch (allocated when the snapshot is prepared, never on the audio thread).
    mutable std::vector<float> busStorage;   // tracks.size() * kMaxMixBlock * 2
    mutable std::vector<float> duckVoice;    // kMaxMixBlock * 2
    mutable std::vector<float> duckGains;    // kMaxMixBlock
    mutable int64_t expectedPos = -1;        // where the next block should start; else envelopes restart

    void allocateScratch() {
        busStorage.assign(tracks.size() * static_cast<size_t>(kMaxMixBlock) * 2, 0.0f);
        duckVoice.assign(static_cast<size_t>(kMaxMixBlock) * 2, 0.0f);
        duckGains.assign(static_cast<size_t>(kMaxMixBlock), 1.0f);
    }
    // Audio thread only, when this snapshot replaces `old`.
    void adoptStateFrom(const PreparedSnapshot& old) const;
};

}  // namespace uv::audio
