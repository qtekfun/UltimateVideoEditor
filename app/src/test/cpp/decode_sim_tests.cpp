// Deterministic host simulation of the decoder worker + a sequential (export) consumer.
//
// It mirrors the algorithm of decode/video_decoder.cpp (findMissing / needsFrame / step / pump, one frame
// released to the image reader at a time, the reader keeping only the newest undrained image) in virtual time,
// and uses the production policy functions `needsSeek` and `pendingExpiredAfterDrain` unchanged. The codec is a
// fake: a stream with a key frame every `gop` frames, `decodeMs` per output frame, `seekMs` per flush.
//
// Purpose: reproduce the long-GOP export slowdown (backward seeks and re-decodes from the key frame), keep the
// fix (one frame in flight) from regressing, and print seeks / decoded / discarded / effective fps so the numbers
// can be quoted. It is a model: the real throughput on a device also depends on the GPU draw and the encoder,
// which only `scripts/run-export-throughput.sh` measures.
//
// Build: scripts/run-native-tests.sh (also part of the CMake host tests as uv_decode_sim_host_tests).
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <map>
#include <set>
#include <vector>

#include "decode/pending_policy.h"
#include "decode/seek_policy.h"

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

namespace {

struct SimParams {
    int64_t frames = 600;        // frames in the stream (and consumed by the export)
    int64_t gop = 240;           // key frame every `gop` frames
    double decodeMs = 4.0;       // codec time per output frame
    double seekMs = 20.0;        // flush + restart cost
    double renderLatencyMs = 1.0;  // release -> image available in the reader
    double drawMs = 8.0;         // consumer time per frame (draw + encode)
    size_t inFlight = uv::decode::kMaxInFlightFrames;  // the production value
    bool readerKeepsNewestOnly = true;  // BufferQueue behaviour seen on a Pixel 8
    int32_t lookBehind = 0;
    int32_t lookAhead = 4;       // kDecodeAhead in encode/export_engine.cpp
    // A jump back in the consumer's position (interactive scrub): after `jumpAt` frames target moves to jumpTo.
    int64_t jumpAt = -1;
    int64_t jumpTo = 0;
};

struct SimResult {
    int64_t seeks = 0;
    int64_t outputs = 0;    // frames the codec produced
    int64_t rendered = 0;   // released for rendering
    int64_t dropped = 0;    // produced and discarded without rendering
    int64_t lost = 0;       // rendered but replaced in the reader before the consumer took them
    int64_t consumed = 0;
    double elapsedMs = 0;
    bool timedOut = false;
    double fps() const { return elapsedMs > 0 ? 1000.0 * static_cast<double>(consumed) / elapsedMs : 0; }
};

constexpr int64_t kPendingTimeoutMs = 400;
constexpr int64_t kPendingHardTimeoutMs = 5000;

class Sim {
public:
    explicit Sim(const SimParams& p) : p_(p) { lastFrame_ = p.frames - 1; }

    SimResult run() {
        double tDecoder = 0, tConsumer = 0;
        int64_t next = 0;           // output frame being fetched
        bool drawing = false;       // consumer is in its draw phase
        bool jumped = false;
        int64_t consumerSource = 0;
        target_ = 0;
        while (next < p_.frames && now_ < 120000.0) {
            if (tDecoder <= tConsumer) {
                now_ = std::max(now_, tDecoder);
                tDecoder = now_ + decoderStep();
            } else {
                now_ = std::max(now_, tConsumer);
                if (drawing) {  // draw finished: evict, move on
                    for (auto it = cache_.begin(); it != cache_.end() && *it < consumerSource;) it = cache_.erase(it);
                    ++result_.consumed;
                    ++next;
                    drawing = false;
                    if (!jumped && p_.jumpAt >= 0 && next == p_.jumpAt) {  // interactive scrub, once
                        next = p_.jumpTo;
                        jumped = true;
                    }
                    if (next >= p_.frames) break;
                    tConsumer = now_;
                    continue;
                }
                consumerSource = next;
                target_ = consumerSource;  // setTarget
                drain();
                if (cache_.count(consumerSource) != 0) {
                    drawing = true;
                    tConsumer = now_ + p_.drawMs;
                } else {
                    tConsumer = now_ + 0.2;  // woken by the next image
                    if (now_ - lastProgress_ > 3000) {  // export_engine's recover()
                        pending_.clear();
                        forceSeek_ = true;
                        lastProgress_ = now_;
                    }
                }
            }
        }
        result_.timedOut = next < p_.frames;
        result_.elapsedMs = now_;
        return result_;
    }

private:
    // ---- consumer side -------------------------------------------------------------------------------
    void drain() {
        for (auto it = reader_.begin(); it != reader_.end();) {
            if (it->arrivalMs <= now_) {
                cache_.insert(it->frame);
                pending_.erase(it->frame);
                lastProgress_ = now_;
                it = reader_.erase(it);
            } else {
                ++it;
            }
        }
        lastDrainMs_ = static_cast<int64_t>(now_);
    }

    // ---- decoder side (mirrors video_decoder.cpp) ------------------------------------------------------
    bool expired(int64_t releasedMs) const {
        return uv::decode::pendingExpiredAfterDrain(releasedMs, lastDrainMs_, static_cast<int64_t>(now_),
                                                    kPendingTimeoutMs, kPendingHardTimeoutMs);
    }

    bool needsFrame(int64_t frame) {
        if (cache_.count(frame) != 0) return false;
        auto it = pending_.find(frame);
        if (it == pending_.end()) return true;
        if (expired(it->second)) {
            pending_.erase(it);
            return true;
        }
        return false;
    }

    size_t inFlightCount() {
        for (auto it = pending_.begin(); it != pending_.end();) {
            it = expired(it->second) ? pending_.erase(it) : std::next(it);
        }
        return pending_.size();
    }

    bool findMissing(int64_t target, int64_t* missing) {
        const int64_t clamped = std::clamp<int64_t>(target, 0, lastFrame_);
        const int64_t lo = std::max<int64_t>(0, clamped - p_.lookBehind);
        const int64_t hi = std::min<int64_t>(lastFrame_, clamped + p_.lookAhead);
        for (int64_t f = clamped; f <= hi; ++f) {
            if (needsFrame(f)) {
                *missing = f;
                return true;
            }
        }
        for (int64_t f = clamped - 1; f >= lo; --f) {
            if (needsFrame(f)) {
                *missing = f;
                return true;
            }
        }
        return false;
    }

    // Returns the virtual time this step took.
    double decoderStep() {
        int64_t missing = 0;
        if (!findMissing(target_, &missing)) return 0.2;  // idle until the next setTarget
        const int64_t clamped = std::clamp<int64_t>(target_, 0, lastFrame_);
        const int64_t lo = std::max<int64_t>(0, clamped - p_.lookBehind);
        const int64_t hi = std::min<int64_t>(lastFrame_, clamped + p_.lookAhead);
        double cost = 0;
        const bool forced = forceSeek_;
        forceSeek_ = false;
        if (uv::decode::needsSeek(forced, primed_, awaiting_, decodePos_, missing, seekGoal_)) {
            ++result_.seeks;
            cursor_ = (missing / p_.gop) * p_.gop;  // previous sync frame
            primed_ = true;
            awaiting_ = true;
            seekGoal_ = missing;
            cost += p_.seekMs;
        }
        // pump
        if (inFlightCount() >= p_.inFlight) return cost + 2.0;  // backpressure
        if (cursor_ > lastFrame_) {                              // end of stream
            primed_ = false;
            return cost + 1.0;
        }
        const int64_t frame = cursor_++;
        ++result_.outputs;
        cost += p_.decodeMs;
        if (seekGoal_ >= 0 && frame >= seekGoal_) seekGoal_ = -1;
        awaiting_ = false;
        decodePos_ = frame + 1;
        if (frame >= lo && frame <= hi && needsFrame(frame)) {
            ++result_.rendered;
            pending_[frame] = static_cast<int64_t>(now_ + cost);
            if (p_.readerKeepsNewestOnly && !reader_.empty()) {
                result_.lost += static_cast<int64_t>(reader_.size());  // replaced before it was taken
                reader_.clear();
            }
            reader_.push_back({frame, now_ + cost + p_.renderLatencyMs});
        } else {
            ++result_.dropped;
        }
        return cost;
    }

    struct Image {
        int64_t frame;
        double arrivalMs;
    };

    SimParams p_;
    SimResult result_;
    double now_ = 0;
    int64_t lastFrame_ = 0;
    int64_t target_ = 0;
    // decoder
    bool primed_ = false, awaiting_ = false, forceSeek_ = false;
    int64_t decodePos_ = 0, seekGoal_ = -1, cursor_ = 0;
    std::map<int64_t, int64_t> pending_;
    std::deque<Image> reader_;
    // shared with the consumer
    std::set<int64_t> cache_;
    int64_t lastDrainMs_ = 0;
    double lastProgress_ = 0;
};

SimResult simulate(const SimParams& p) { return Sim(p).run(); }

double idealFps(const SimParams& p) { return 1000.0 / std::max(p.decodeMs, p.drawMs); }

void report(const char* name, const SimParams& p, const SimResult& r) {
    std::printf("%-34s gop=%-4lld inflight=%zu | seeks %-4lld outputs %-5lld rendered %-5lld dropped %-5lld lost %-4lld | "
                "%7.1f fps (ideal %.1f, pure decode %.1f)%s\n",
                name, static_cast<long long>(p.gop), p.inFlight, static_cast<long long>(r.seeks),
                static_cast<long long>(r.outputs), static_cast<long long>(r.rendered),
                static_cast<long long>(r.dropped), static_cast<long long>(r.lost), r.fps(), idealFps(p),
                1000.0 / p.decodeMs, r.timedOut ? "  TIMED OUT" : "");
}

// The fix: one frame in flight. Sequential export on a stream with a single key frame performs exactly one
// seek, loses no frame, and runs within 15% of the pipelined ideal max(decode, draw).
void sequentialLongGopIsOneSeekAndNearIdeal() {
    CHECK(uv::decode::kMaxInFlightFrames == 1);  // raising it brings the slowdown back, see manyInFlight... below
    SimParams p;
    p.gop = 100000;
    const SimResult r = simulate(p);
    report("long GOP, 1 in flight (now)", p, r);
    CHECK(!r.timedOut);
    CHECK(r.consumed == p.frames);
    CHECK(r.seeks == 1);
    CHECK(r.lost == 0);
    CHECK(r.fps() >= 0.85 * idealFps(p));
}

void sequentialShortGopIsOneSeek() {
    SimParams p;
    p.gop = 30;
    const SimResult r = simulate(p);
    report("GOP 30, 1 in flight (now)", p, r);
    CHECK(r.seeks == 1);
    CHECK(r.lost == 0);
    CHECK(r.fps() >= 0.85 * idealFps(p));
}

void decodeBoundAndDrawBoundBothNearIdeal() {
    SimParams slowDecode;
    slowDecode.gop = 240;
    slowDecode.decodeMs = 12;  // 4K HEVC class
    slowDecode.drawMs = 6;
    const SimResult a = simulate(slowDecode);
    report("decode-bound (12 ms / 6 ms)", slowDecode, a);
    CHECK(a.seeks == 1);
    CHECK(a.fps() >= 0.85 * idealFps(slowDecode));

    SimParams slowDraw;
    slowDraw.gop = 240;
    slowDraw.decodeMs = 3;
    slowDraw.drawMs = 15;  // two layers + encoder
    const SimResult b = simulate(slowDraw);
    report("draw-bound (3 ms / 15 ms)", slowDraw, b);
    CHECK(b.seeks == 1);
    CHECK(b.fps() >= 0.85 * idealFps(slowDraw));
}

// The bug this pass reproduces: with several frames in flight the reader keeps only the newest, the lost frames
// are retried after the pending timeout, and each retry is a backward seek and a re-decode from the key frame.
void manyInFlightReproducesTheSlowdown() {
    SimParams good;
    good.gop = 100000;
    const SimResult fixed = simulate(good);
    SimParams bad = good;
    bad.inFlight = 4;
    const SimResult old = simulate(bad);
    report("long GOP, 4 in flight (before)", bad, old);
    CHECK(old.lost > 0);
    CHECK(old.seeks > fixed.seeks);
    CHECK(old.fps() < 0.6 * fixed.fps());
    std::printf("  -> speed-up from the fix: %.1fx (%.1f -> %.1f fps)\n", fixed.fps() / std::max(old.fps(), 1e-9), old.fps(),
                fixed.fps());
}

// Interactive behaviour must be unchanged: moving the target backwards past the decoder (a scrub) seeks once,
// then continues forwards without more seeks.
void scrubBackSeeksOnce() {
    SimParams p;
    p.gop = 240;
    p.frames = 400;
    p.jumpAt = 300;
    p.jumpTo = 100;
    const SimResult r = simulate(p);
    report("scrub back at 300 -> 100", p, r);
    CHECK(!r.timedOut);
    CHECK(r.seeks == 2);  // the initial one and the jump
}

// A target a long way ahead of the decoder (a cut to another part of the clip) seeks once.
void jumpForwardBeyondOneGopSeeksOnce() {
    SimParams p;
    p.gop = 60;
    p.frames = 600;
    p.jumpAt = 100;
    p.jumpTo = 500;
    const SimResult r = simulate(p);
    report("jump forward 100 -> 500", p, r);
    CHECK(!r.timedOut);
    CHECK(r.seeks == 2);
}

}  // namespace

int main() {
    sequentialLongGopIsOneSeekAndNearIdeal();
    sequentialShortGopIsOneSeek();
    decodeBoundAndDrawBoundBothNearIdeal();
    manyInFlightReproducesTheSlowdown();
    scrubBackSeeksOnce();
    jumpForwardBeyondOneGopSeeksOnce();
    if (g_failures == 0) std::puts("all decode simulation tests passed");
    return g_failures == 0 ? 0 : 1;
}
