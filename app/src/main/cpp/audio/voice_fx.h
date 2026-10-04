#pragma once

// Voice effects: classical DSP only (STFT phase vocoder, biquads, delay lines, a Schroeder reverb),
// no learned model and no third-party code. One VoiceProcessor runs per clip in the decode worker,
// on the clip's own audio before it is buffered (like the noise suppressor), so the realtime stream
// and the offline export play exactly the same samples, and the host tests drive it directly.
//
// Chain order (every stage is optional and bypassed when its setting is neutral):
//   1. spectral stage: pitch shift of the voice excitation, independent formant (spectral envelope)
//      shift and the whisper (noise-excited) blend
//   2. ring modulation   3. band limit + soft-clip drive   4. echo / short comb   5. reverb
//
// The processor is aligned with its input like SpectralDenoiser: process() returns frames as they
// complete, flush() returns the rest (plus a bounded echo/reverb tail), and together they return as
// many frames as were fed (plus the tail). The 1536-sample latency of the spectral stage is hidden by
// priming the history with zeros, so a clip never shifts against the picture. Everything is
// sample-exact in the input stream: the output does not depend on how the stream is cut into calls.

#include <cstddef>
#include <cstdint>
#include <vector>

#include "audio/dsp.h"

namespace uv::audio {

constexpr int kVoiceFft = 2048;
constexpr int kVoiceHop = 512;               // 75 % overlap
constexpr int kVoiceLatency = kVoiceFft - kVoiceHop;
constexpr int kVoiceParamFloats = 16;        // wire size of VoiceParams (see audio_snapshot.h, version 6)
constexpr float kVoiceMaxShiftSemitones = 12.0f;
constexpr double kVoiceMaxTailSeconds = 8.0;

struct VoiceParams {
    float pitchSemitones = 0.0f;    // -12..12: shifts the excitation (the voice's pitch)
    float formantSemitones = 0.0f;  // -12..12: shifts the spectral envelope (the vocal tract); 0 keeps the timbre
    float whisperMix = 0.0f;        // 0..1: share of the noise-excited (random phase) version
    float ringHz = 0.0f;            // carrier of the ring modulator; 0 = off, else 10..2000
    float ringMix = 0.0f;           // 0..1
    float bandLowHz = 0.0f;         // high-pass corner (0 = off), 20..8000
    float bandHighHz = 0.0f;        // low-pass corner (0 = off), 200..20000
    float driveDb = 0.0f;           // 0..36 soft-clip drive
    float echoMs = 0.0f;            // 0 = off, else 1..2000 (a few ms gives a metallic comb)
    float echoFeedback = 0.0f;      // 0..0.95
    float echoMix = 0.0f;           // 0..1
    float reverbSize = 0.0f;        // 0..1
    float reverbDamping = 0.5f;     // 0..1
    float reverbMix = 0.0f;         // 0..1; 0 = off

    bool needsSpectral() const { return pitchSemitones != 0.0f || formantSemitones != 0.0f || whisperMix > 0.0f; }
    bool hasBand() const { return bandLowHz > 0.0f || bandHighHz > 0.0f; }
    bool hasRing() const { return ringHz > 0.0f && ringMix > 0.0f; }
    bool hasEcho() const { return echoMs > 0.0f && echoMix > 0.0f; }
    bool hasReverb() const { return reverbMix > 0.0f; }
    bool isNeutral() const { return !needsSpectral() && !hasBand() && driveDb == 0.0f && !hasRing() && !hasEcho() && !hasReverb(); }

    // Frames the echo and the reverb keep sounding after their input stops (capped at 8 s).
    int64_t tailFrames(double sampleRate) const;
    // Identity of the settings (never 0 for a non-neutral set), part of a clip source's key.
    uint64_t hash() const;
};

// True when every field is finite and inside its documented range.
bool voiceParamsValid(const VoiceParams& p);
// The wire layout is the fields above in declaration order, two reserved floats (zero) at the end.
void voiceParamsFromFloats(const float* f, VoiceParams* p);
void voiceParamsToFloats(const VoiceParams& p, float* f);

class VoiceProcessor {
public:
    // `forceSpectral` runs the spectral stage even for neutral shifts (the host tests use it to check
    // alignment and gain of the phase vocoder itself).
    VoiceProcessor(const VoiceParams& params, int32_t sampleRate, bool forceSpectral = false);
    ~VoiceProcessor();
    VoiceProcessor(const VoiceProcessor&) = delete;
    VoiceProcessor& operator=(const VoiceProcessor&) = delete;

    void reset();
    // Interleaved stereo in; appends the finished, input-aligned frames (stereo) to `out`.
    void process(const float* stereo, size_t frames, std::vector<float>* out);
    // Completes the pipeline: the frames still inside it, then up to `maxTailFrames` of echo/reverb tail
    // (never more than the settings' own tail). Re-primes afterwards.
    void flush(std::vector<float>* out, int64_t maxTailFrames);

    int64_t framesIn() const { return in_; }
    int64_t framesOut() const { return out_; }
    bool spectral() const { return spectral_; }

private:
    struct Impl;
    Impl* impl_;
    VoiceParams params_;
    int32_t rate_;
    bool spectral_;
    int64_t in_ = 0;
    int64_t out_ = 0;
};

}  // namespace uv::audio
