#pragma once

// Platform-independent heart of audio playback: snapshot hand-off to the audio thread, the
// decode worker that keeps per-clip buffers filled around the playhead, the play/pause/seek
// state machine and the master-clock anchor. Oboe and MediaCodec live outside this class (see
// audio_engine.h, android_pcm_decoder.h) so everything here builds and is tested on the host.
//
// Threads:
//   control  - play/pause/seek/setSnapshot/configure/poll* (serialised by controlMutex_)
//   audio    - render() only; real-time safe (no locks, no allocation, no JNI)
//   worker   - serviceOnce() loop; decodes, resamples, fills ClipBuffers

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <tuple>
#include <vector>

#include "audio/audio_snapshot.h"
#include "audio/audio_types.h"
#include "audio/clock_mapper.h"
#include "audio/pcm_decoder.h"
#include "core/error.h"

namespace uv::audio {

struct AudioFault {
    // OfflineStall: an offline render gave up waiting for a clip's audio (clipKey says which).
    enum class Kind : int32_t { Underrun = 1, Decode = 2, Device = 3, OfflineStall = 4 };
    Kind kind = Kind::Underrun;
    int64_t clipKey = -1;                  // -1 when not tied to a clip
    core::Status status = core::Status::Ok;
    int64_t count = 1;                     // Underrun: blocks that were short since the last poll
};

struct AudioCoreOptions {
    int32_t lookaheadMs = 1000;  // how far ahead of the playhead the worker decodes
    int32_t readyMs = 50;        // data that must be buffered before playback leaves a hold
    int32_t holdMaxMs = 500;     // give up waiting for buffers after this long
};

class AudioCore {
public:
    explicit AudioCore(DecoderFactory factory, AudioCoreOptions options = {});
    ~AudioCore();
    AudioCore(const AudioCore&) = delete;
    AudioCore& operator=(const AudioCore&) = delete;

    // ---- control thread ----
    // Sets the output rate (known once the stream is open). Rebuilds any current snapshot.
    core::Status configure(int32_t sampleRate);
    core::Status setSnapshot(const AudioSnapshotData& data);
    void play();
    // Freezes playback and rewinds to `atSample` (the sample actually heard), so resuming does
    // not skip the audio that was already rendered ahead of the speaker.
    void pause(int64_t atSample);
    void seekSamples(int64_t sample);
    int64_t framesToTimelineSamples(int64_t frame) const;
    int64_t samplesToTimelineFrames(int64_t sample) const;
    int32_t sampleRate() const { return sampleRate_.load(std::memory_order_acquire); }

    // Offline mode: render() is driven by a non-real-time caller (tests, export). It never
    // underruns; instead each block waits (bounded) for the worker to buffer what it needs.
    void setOfflineMode(bool offline) { offline_.store(offline, std::memory_order_release); }

    void startWorker();
    void stopWorker();
    // Stream lifecycle hooks: while no callback runs, snapshot hand-off bookkeeping is trivial.
    void streamStarting();
    void streamStopped();
    // A fresh stream restarts its frame counter at zero.
    void resetStreamClock();

    // ---- audio thread ----
    void render(float* out, int32_t frames);

    // ---- any thread ----
    ClockAnchor anchor() const;
    int64_t streamFrames() const { return streamFrames_.load(std::memory_order_acquire); }
    int64_t renderPosSamples() const { return renderPos_.load(std::memory_order_acquire); }
    bool isPlaying() const { return playing_.load(std::memory_order_acquire); }
    // Appends faults raised since the previous call (underruns are aggregated).
    void pollFaults(std::vector<AudioFault>* out);
    // Output peaks (linear, left and right) since the previous call; for the level meters.
    void takePeaks(float* left, float* right) { meter_.take(left, right); }
    void reportDeviceFault(core::Status status);
    int64_t underrunBlocks() const { return underrunBlocks_.load(std::memory_order_relaxed); }

    // One pass of the decode worker. The worker thread loops on this; tests call it directly.
    void serviceOnce();

    // Invariant check (tests, diagnostics): the snapshot the audio thread last rendered with is still
    // owned by the core, so the next block cannot adopt state from freed memory.
    bool audioThreadSnapshotIsAlive() const;

private:
    // clip, asset, source in-point, source rate (num, den), a hash of the retime knots and a hash of
    // the noise-suppression settings (a changed profile or strength makes a new source).
    using SourceKey = std::tuple<int64_t, int64_t, int64_t, int32_t, int32_t, uint64_t, uint64_t>;
    struct Retired {
        float* storage;
        int64_t atBlock;
    };

    core::Status setSnapshotLocked(const AudioSnapshotData& data);
    void renderBlock(float* out, int32_t frames);
    bool clipsReadyAt(int64_t pos, int64_t windowFrames) const;
    void waitUntilReady(int64_t pos, int32_t frames);
    void publishAnchor(int64_t streamFrame, int64_t timelineSample, bool playing);

    void workerLoop();
    void serviceClip(ClipSource& src, const PreparedClip& clip, int64_t needStart, int64_t needEnd, int32_t rate);
    void serviceRetimedClip(ClipSource& src, const PreparedClip& clip, int64_t needStart, int64_t needEnd, int32_t rate);
    void releaseClip(ClipSource& src);
    void failClip(ClipSource& src, core::Status status);
    bool openDecoder(ClipSource& src, int32_t rate);
    void drainRetired(bool force);
    void wake();
    void pruneAliveLocked();

    static constexpr int64_t kNoSeek = INT64_MIN;
    static constexpr int32_t kMaxBlock = 4096;
    static constexpr int32_t kDecodeChunk = 4096;
    static constexpr int32_t kRetimeBlock = 1024;  // output samples rendered per retimed read

    const DecoderFactory factory_;
    const AudioCoreOptions options_;

    // control state
    mutable std::mutex controlMutex_;
    std::atomic<int32_t> sampleRate_{48000};
    int32_t bufferFrames_ = 131072;
    uint64_t generation_ = 0;
    std::map<SourceKey, std::shared_ptr<ClipSource>> sources_;
    std::vector<std::shared_ptr<const PreparedSnapshot>> alive_;
    AudioSnapshotData lastData_;
    bool hasData_ = false;
    Rational fps_{30, 1};
    bool streamRunning_ = false;

    // snapshot hand-off
    std::atomic<const PreparedSnapshot*> pending_{nullptr};
    std::atomic<uint64_t> ackGeneration_{0};
    std::mutex latestMutex_;
    std::shared_ptr<const PreparedSnapshot> latest_;

    // transport (control -> audio)
    std::atomic<bool> playing_{false};
    std::atomic<int64_t> seekReq_{kNoSeek};
    std::atomic<bool> offline_{false};

    // audio-thread state
    const PreparedSnapshot* current_ = nullptr;
    int64_t pos_ = 0;
    bool wasPlaying_ = false;
    bool holding_ = false;
    int64_t holdFrames_ = 0;
    float scratch_[kMaxBlock * 2];

    dsp::PeakMeter meter_;  // written by the mixer, read by takePeaks()

    // published by the audio thread
    std::atomic<int64_t> renderPos_{0};
    std::atomic<int64_t> streamFrames_{0};
    std::atomic<int64_t> blockCounter_{0};
    std::atomic<int64_t> underrunBlocks_{0};
    std::atomic<int64_t> lastUnderrunClip_{-1};
    std::atomic<uint32_t> anchorSeq_{0};
    std::atomic<int64_t> anchorStreamFrame_{0};
    std::atomic<int64_t> anchorTimeline_{0};
    std::atomic<bool> anchorPlaying_{false};

    // faults
    std::mutex faultMutex_;
    std::vector<AudioFault> faults_;
    int64_t reportedUnderruns_ = 0;

    // worker
    std::thread worker_;
    std::atomic<bool> workerStop_{false};
    std::mutex wakeMutex_;
    std::condition_variable wakeCv_;
    std::vector<Retired> retired_;
    std::atomic<bool> rendering_{false};
};

}  // namespace uv::audio
