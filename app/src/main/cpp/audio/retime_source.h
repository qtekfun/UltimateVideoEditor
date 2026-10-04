#pragma once

// Audio of a retimed clip (speed change, ramp or reverse). The domain (domain/Retime.kt) gives the
// clip as knots: clip-local project frames and the source position, in project frames, that plays at
// that moment (a reversed clip simply has positions that fall). Between knots the position is linear,
// so output sample j reads the source at a continuous position, interpolated linearly: the pitch
// follows the speed (varispeed, like a tape), which is the policy for the 0.25x..4x range the editor
// lets through; outside it the clip is muted before it gets here.
//
// Everything here is plain C++ so it is tested on the host (tests/audio_host_tests.cpp); the worker
// thread of AudioCore drives it.

#include <cstdint>
#include <vector>

#include "audio/audio_time.h"
#include "audio/pcm_decoder.h"
#include "core/error.h"

namespace uv::audio {

// A clip-local project frame and the source position (project frames, continuous) played at it.
struct RetimeKnot {
    int64_t frame = 0;
    double sourceFrame = 0.0;
};

// Output sample (clip local, at the output rate) -> source sample position (at the source rate).
class RetimeMap {
public:
    RetimeMap(std::vector<RetimeKnot> knots, Rational projectFps, int32_t outRate, int32_t srcRate);

    // Continuous position in source samples of clip-local output sample `j`; beyond the first and
    // last knot the nearest segment's speed continues.
    double sourceSample(int64_t j) const;
    // True when the position falls over the clip (playing backwards).
    bool reversed() const { return reversed_; }

private:
    std::vector<double> knotSample_;  // knot positions as output samples (clip local)
    std::vector<double> knotSource_;  // knot positions as source samples
    bool reversed_ = false;
};

// Renders retimed audio from a sequential PcmDecoder, keeping a window of decoded source samples:
// forward playback streams through it, reverse playback decodes a block below the playhead and then
// walks it backwards, so a seek is only needed once per block.
class RetimedReader {
public:
    enum class Result { Ok, NotReady, Error };

    RetimedReader(PcmDecoder* decoder, RetimeMap map);

    // Writes `frames` stereo frames for clip-local output samples [from, from + frames). NotReady
    // means the decoder had nothing yet (try again later), Error that it failed (see lastStatus()).
    Result render(int64_t from, int32_t frames, float* dstStereo);
    core::Status lastStatus() const { return status_; }

    // Forgets the window (after the playhead jumped).
    void reset();

    static constexpr int64_t kReverseBlockSamples = 96000;  // source decoded below the playhead per seek
    static constexpr int64_t kContinueGapSamples = 24000;   // forward reads this far ahead instead of seeking
    static constexpr int32_t kReadChunk = 4096;

private:
    Result ensureWindow(int64_t lo, int64_t hi, bool reverse);
    Result fillTo(int64_t hi);

    PcmDecoder* decoder_;
    RetimeMap map_;
    int32_t srcRate_;
    std::vector<float> window_;  // interleaved stereo
    std::vector<float> chunk_;
    int64_t winStart_ = 0;       // source sample index of window_[0]
    int64_t winEnd_ = 0;         // one past the last decoded source sample
    bool eof_ = false;           // the source ends at winEnd_
    bool haveWindow_ = false;
    int64_t pendingFillHi_ = -1; // after a seek: the sample the window must reach; -1 when it was reached
    core::Status status_ = core::Status::Ok;
};

}  // namespace uv::audio
