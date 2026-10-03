#include "audio/audio_core.h"

#include <algorithm>
#include <cstring>
#include <set>

#include "audio/audio_mixer.h"

namespace uv::audio {

namespace {

using core::Status;
using Clock = std::chrono::steady_clock;

constexpr auto kRetryCooldown = std::chrono::seconds(1);
constexpr size_t kMaxQueuedFaults = 64;

int32_t pow2Ceil(int64_t v) {
    int64_t p = 1;
    while (p < v) p <<= 1;
    return static_cast<int32_t>(p);
}

}  // namespace

AudioCore::AudioCore(DecoderFactory factory, AudioCoreOptions options)
    : factory_(std::move(factory)), options_(options) {}

AudioCore::~AudioCore() {
    stopWorker();
    drainRetired(true);
}

// ---------------------------------------------------------------- control thread

Status AudioCore::configure(int32_t sampleRate) {
    if (sampleRate < 8000 || sampleRate > 192000) return Status::InvalidArgument;
    std::lock_guard<std::mutex> lock(controlMutex_);
    const int32_t old = sampleRate_.load(std::memory_order_acquire);
    sampleRate_.store(sampleRate, std::memory_order_release);
    bufferFrames_ = pow2Ceil(static_cast<int64_t>(sampleRate) * 3 / 2);
    sources_.clear();  // buffers are rate-specific
    if (old != sampleRate) {
        const int64_t pos = renderPos_.load(std::memory_order_acquire);
        seekReq_.store(floorDiv(static_cast<i128>(pos) * sampleRate, old), std::memory_order_release);
    }
    return hasData_ ? setSnapshotLocked(lastData_) : Status::Ok;
}

Status AudioCore::setSnapshot(const AudioSnapshotData& data) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return setSnapshotLocked(data);
}

Status AudioCore::setSnapshotLocked(const AudioSnapshotData& data) {
    const int32_t rate = sampleRate_.load(std::memory_order_acquire);
    auto prepared = std::make_shared<PreparedSnapshot>();
    prepared->sampleRate = rate;
    prepared->fps = data.fps;
    prepared->generation = ++generation_;

    std::map<SourceKey, std::shared_ptr<ClipSource>> next;
    std::set<int64_t> clipKeys;
    for (const AudioClipDesc& d : data.clips) {
        if (!clipKeys.insert(d.clipKey).second) return Status::BadSnapshot;  // duplicate clip key
        const int64_t start = framesToSamples(d.startFrame, data.fps, rate);
        const int64_t end = framesToSamples(d.startFrame + d.durationFrames, data.fps, rate);
        if (end <= start) continue;  // shorter than one output sample

        const SourceKey key{d.clipKey, d.assetKey, d.sourceInFrame, d.sourceFps.num, d.sourceFps.den};
        std::shared_ptr<ClipSource> source;
        if (auto it = sources_.find(key); it != sources_.end()) {
            source = it->second;
        } else {
            source = std::make_shared<ClipSource>(d.clipKey, d.assetKey, sourceFramesToMicros(d.sourceInFrame, d.sourceFps),
                                                  bufferFrames_);
        }
        next[key] = source;

        PreparedClip pc;
        pc.startSample = start;
        pc.endSample = end;
        pc.gain = dbToLinear(d.gainDb);
        pc.source = std::move(source);
        prepared->clips.push_back(std::move(pc));
    }
    std::stable_sort(prepared->clips.begin(), prepared->clips.end(),
                     [](const PreparedClip& a, const PreparedClip& b) { return a.startSample < b.startSample; });

    sources_ = std::move(next);
    lastData_ = data;
    hasData_ = true;
    fps_ = data.fps;

    alive_.push_back(prepared);
    {
        std::lock_guard<std::mutex> lk(latestMutex_);
        latest_ = prepared;
    }
    pending_.store(prepared.get(), std::memory_order_release);

    // Free snapshots the audio thread has moved on from. With no stream running nobody can be
    // using an old one, and the next render picks up pending_ before touching anything.
    if (!streamRunning_) {
        alive_.erase(alive_.begin(), alive_.end() - 1);
    } else {
        const uint64_t ack = ackGeneration_.load(std::memory_order_acquire);
        std::erase_if(alive_, [ack](const auto& s) { return s->generation < ack; });
    }
    wake();
    return Status::Ok;
}

void AudioCore::play() {
    playing_.store(true, std::memory_order_release);
    wake();
}

void AudioCore::pause(int64_t atSample) {
    playing_.store(false, std::memory_order_release);
    seekReq_.store(std::max<int64_t>(0, atSample), std::memory_order_release);
    wake();
}

void AudioCore::seekSamples(int64_t sample) {
    seekReq_.store(std::max<int64_t>(0, sample), std::memory_order_release);
    wake();
}

int64_t AudioCore::framesToTimelineSamples(int64_t frame) const {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return framesToSamples(frame, fps_, sampleRate_.load(std::memory_order_acquire));
}

int64_t AudioCore::samplesToTimelineFrames(int64_t sample) const {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return samplesToFrames(sample, fps_, sampleRate_.load(std::memory_order_acquire));
}

void AudioCore::startWorker() {
    if (worker_.joinable()) return;
    workerStop_.store(false, std::memory_order_release);
    worker_ = std::thread([this] { workerLoop(); });
}

void AudioCore::stopWorker() {
    if (!worker_.joinable()) return;
    workerStop_.store(true, std::memory_order_release);
    wake();
    worker_.join();
}

void AudioCore::streamStarting() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    streamRunning_ = true;
    rendering_.store(true, std::memory_order_release);
}

void AudioCore::streamStopped() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    streamRunning_ = false;
    rendering_.store(false, std::memory_order_release);
    if (alive_.size() > 1) alive_.erase(alive_.begin(), alive_.end() - 1);
}

void AudioCore::resetStreamClock() {
    std::lock_guard<std::mutex> lock(controlMutex_);
    streamFrames_.store(0, std::memory_order_release);
    wasPlaying_ = false;
    holding_ = false;
    publishAnchor(0, renderPos_.load(std::memory_order_acquire), false);
}

// ---------------------------------------------------------------- audio thread

void AudioCore::render(float* out, int32_t frames) {
    while (frames > 0) {
        const int32_t n = std::min(frames, kMaxBlock);
        renderBlock(out, n);
        out += static_cast<size_t>(n) * 2;
        frames -= n;
    }
}

void AudioCore::renderBlock(float* out, int32_t frames) {
    if (const PreparedSnapshot* np = pending_.exchange(nullptr, std::memory_order_acq_rel)) current_ = np;
    if (current_ != nullptr) ackGeneration_.store(current_->generation, std::memory_order_release);

    const int32_t rate = sampleRate_.load(std::memory_order_acquire);
    const bool wantPlay = playing_.load(std::memory_order_acquire);
    const int64_t seek = seekReq_.exchange(kNoSeek, std::memory_order_acq_rel);

    const bool offline = offline_.load(std::memory_order_acquire);
    bool reanchor = false;
    auto enterHold = [this, offline] {
        if (offline) return;  // offline blocks wait for data instead (waitUntilReady)
        holding_ = true;
        holdFrames_ = 0;
    };
    if (seek != kNoSeek) {
        pos_ = seek;
        renderPos_.store(pos_, std::memory_order_release);  // the worker must see the new target now
        reanchor = true;
        if (wantPlay) enterHold();
    }
    if (wantPlay != wasPlaying_) {
        wasPlaying_ = wantPlay;
        reanchor = true;
        if (wantPlay) enterHold();
    }
    if (wantPlay && holding_) {
        // Warm-up: after play or a seek, wait (briefly) for the worker to buffer the new position
        // instead of emitting a click-prone underrun.
        const int64_t holdMax = static_cast<int64_t>(rate) * options_.holdMaxMs / 1000;
        const int64_t readyWindow = static_cast<int64_t>(rate) * options_.readyMs / 1000;
        if (clipsReadyAt(pos_, readyWindow) || holdFrames_ >= holdMax) {
            holding_ = false;
            reanchor = true;
        } else {
            holdFrames_ += frames;
        }
    }
    if (!wantPlay) holding_ = false;

    const bool advancing = wantPlay && !holding_;
    const int64_t sf = streamFrames_.load(std::memory_order_relaxed);
    if (reanchor) publishAnchor(sf, pos_, advancing);

    if (advancing) {
        if (current_ != nullptr) {
            if (offline) waitUntilReady(pos_, frames);
            const MixResult r = mixBlock(*current_, pos_, frames, out, scratch_);
            if (r.underruns > 0) {
                underrunBlocks_.fetch_add(1, std::memory_order_relaxed);
                lastUnderrunClip_.store(r.lastUnderrunClip, std::memory_order_relaxed);
            }
        } else {
            std::memset(out, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
        }
        pos_ += frames;
    } else {
        std::memset(out, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
    }

    streamFrames_.store(sf + frames, std::memory_order_release);
    renderPos_.store(pos_, std::memory_order_release);
    blockCounter_.fetch_add(1, std::memory_order_release);
}

bool AudioCore::clipsReadyAt(int64_t pos, int64_t windowFrames) const {
    if (current_ == nullptr) return true;
    for (const PreparedClip& clip : current_->clips) {
        const int64_t a = std::max(pos, clip.startSample);
        const int64_t b = std::min(pos + windowFrames, clip.endSample);
        if (a >= b) continue;
        if (!clip.source->ready(a - clip.startSample, static_cast<int32_t>(b - a))) return false;
    }
    return true;
}

void AudioCore::waitUntilReady(int64_t pos, int32_t frames) {
    // Offline only (the caller is not a real-time thread, so sleeping is fine).
    constexpr auto kTimeout = std::chrono::seconds(5);
    const auto deadline = Clock::now() + kTimeout;
    while (!clipsReadyAt(pos, frames) && Clock::now() < deadline) {
        wake();
        std::this_thread::sleep_for(std::chrono::microseconds(500));
    }
}

void AudioCore::publishAnchor(int64_t streamFrame, int64_t timelineSample, bool playing) {
    anchorSeq_.fetch_add(1, std::memory_order_acq_rel);  // odd: write in progress
    anchorStreamFrame_.store(streamFrame, std::memory_order_relaxed);
    anchorTimeline_.store(timelineSample, std::memory_order_relaxed);
    anchorPlaying_.store(playing, std::memory_order_relaxed);
    anchorSeq_.fetch_add(1, std::memory_order_release);
}

ClockAnchor AudioCore::anchor() const {
    for (;;) {
        const uint32_t s1 = anchorSeq_.load(std::memory_order_acquire);
        if (s1 & 1u) continue;
        ClockAnchor a;
        a.streamFrame = anchorStreamFrame_.load(std::memory_order_relaxed);
        a.timelineSample = anchorTimeline_.load(std::memory_order_relaxed);
        a.playing = anchorPlaying_.load(std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (anchorSeq_.load(std::memory_order_relaxed) == s1) return a;
    }
}

// ---------------------------------------------------------------- faults

void AudioCore::pollFaults(std::vector<AudioFault>* out) {
    std::lock_guard<std::mutex> lock(faultMutex_);
    const int64_t underruns = underrunBlocks_.load(std::memory_order_relaxed);
    if (underruns > reportedUnderruns_) {
        AudioFault f;
        f.kind = AudioFault::Kind::Underrun;
        f.clipKey = lastUnderrunClip_.load(std::memory_order_relaxed);
        f.count = underruns - reportedUnderruns_;
        out->push_back(f);
        reportedUnderruns_ = underruns;
    }
    for (const AudioFault& f : faults_) out->push_back(f);
    faults_.clear();
}

void AudioCore::reportDeviceFault(Status status) {
    std::lock_guard<std::mutex> lock(faultMutex_);
    if (faults_.size() >= kMaxQueuedFaults) return;
    AudioFault f;
    f.kind = AudioFault::Kind::Device;
    f.status = status;
    faults_.push_back(f);
}

// ---------------------------------------------------------------- decode worker

void AudioCore::wake() { wakeCv_.notify_one(); }

void AudioCore::workerLoop() {
    while (!workerStop_.load(std::memory_order_acquire)) {
        serviceOnce();
        std::unique_lock<std::mutex> lock(wakeMutex_);
        wakeCv_.wait_for(lock, std::chrono::milliseconds(8));
    }
}

void AudioCore::serviceOnce() {
    drainRetired(false);
    std::shared_ptr<const PreparedSnapshot> snap;
    {
        std::lock_guard<std::mutex> lk(latestMutex_);
        snap = latest_;
    }
    if (!snap) return;

    const int32_t rate = snap->sampleRate;
    const int64_t pos = renderPos_.load(std::memory_order_acquire);
    const int64_t look = static_cast<int64_t>(rate) * options_.lookaheadMs / 1000;

    for (const PreparedClip& clip : snap->clips) {
        ClipSource& src = *clip.source;
        const int64_t len = clip.endSample - clip.startSample;
        const int64_t needStart = std::max<int64_t>(0, pos - clip.startSample);
        const int64_t needEnd = std::min(len, pos + look - clip.startSample);
        if (needEnd > needStart) {
            serviceClip(src, clip, needStart, needEnd, rate);
        } else if (clip.endSample < pos - rate || clip.startSample > pos + 2 * look) {
            releaseClip(src);  // far from the playhead: give back memory and the decoder
        }
    }
}

void AudioCore::serviceClip(ClipSource& src, const PreparedClip& clip, int64_t needStart, int64_t needEnd,
                            int32_t rate) {
    if (src.failed.load(std::memory_order_acquire) && Clock::now() < src.retryAfter) return;
    if (!src.buffer.allocated()) src.buffer.allocate();

    const bool inWindow = needStart >= src.buffer.windowStart() && needStart <= src.buffer.writeEnd();
    // (Re)position when the playhead left the buffered window, or when there is no decoder yet
    // (first use, released while in the window, or recovering after a failure).
    if (!inWindow || !src.decoder) {
        if (!src.decoder && !openDecoder(src, rate)) return;
        const Status st = src.decoder->seekToMicros(src.sourceInMicros + samplesToMicros(needStart, rate));
        if (st != Status::Ok) {
            failClip(src, st);
            return;
        }
        src.resampler->reset();
        src.buffer.reset(needStart);
        src.decodedEnd = needStart;
        src.hitEof = false;
        src.eofAt.store(INT64_MAX, std::memory_order_release);
        src.failed.store(false, std::memory_order_release);
    }
    if (src.failed.load(std::memory_order_acquire) || !src.decoder) return;

    const int64_t len = clip.endSample - clip.startSample;
    for (int guard = 0; guard < 64 && !src.hitEof && src.decodedEnd < needEnd; ++guard) {
        src.srcScratch.resize(static_cast<size_t>(kDecodeChunk) * 2);
        const PcmReadResult r = src.decoder->read(src.srcScratch.data(), kDecodeChunk);
        if (r.status != Status::Ok) {
            failClip(src, r.status);
            return;
        }
        if (r.frames > 0) {
            src.outScratch.clear();
            src.resampler->process(src.srcScratch.data(), static_cast<size_t>(r.frames), &src.outScratch);
            const int64_t produced = std::min<int64_t>(static_cast<int64_t>(src.outScratch.size() / 2), len - src.decodedEnd);
            if (produced > 0) {
                src.buffer.append(src.outScratch.data(), static_cast<int32_t>(produced));
                src.decodedEnd += produced;
            }
            if (src.decodedEnd >= len) src.hitEof = true;  // clip end reached; nothing more to decode
        }
        if (r.eof) {
            src.hitEof = true;
            src.eofAt.store(src.decodedEnd, std::memory_order_release);  // media ends before the clip does
        }
        if (r.frames == 0 && !r.eof) break;  // nothing ready yet; try again next pass
    }
}

bool AudioCore::openDecoder(ClipSource& src, int32_t rate) {
    Status st = Status::Ok;
    std::unique_ptr<PcmDecoder> dec = factory_(src.assetKey, &st);
    if (!dec) {
        failClip(src, st == Status::Ok ? Status::IoError : st);
        return false;
    }
    const int32_t srcRate = dec->sampleRate();
    if (srcRate <= 0) {
        failClip(src, Status::UnsupportedFormat);
        return false;
    }
    src.decoder = std::move(dec);
    src.resampler.emplace(srcRate, rate);
    return true;
}

void AudioCore::releaseClip(ClipSource& src) {
    src.decoder.reset();
    src.resampler.reset();
    src.hitEof = false;
    src.failed.store(false, std::memory_order_release);
    src.eofAt.store(INT64_MAX, std::memory_order_release);
    if (src.buffer.allocated()) {
        retired_.push_back({src.buffer.detach(), blockCounter_.load(std::memory_order_acquire)});
    }
}

void AudioCore::failClip(ClipSource& src, Status status) {
    src.failed.store(true, std::memory_order_release);
    src.retryAfter = Clock::now() + kRetryCooldown;
    src.decoder.reset();
    src.resampler.reset();
    src.hitEof = false;

    std::lock_guard<std::mutex> lock(faultMutex_);
    if (faults_.size() >= kMaxQueuedFaults) return;
    AudioFault f;
    f.kind = AudioFault::Kind::Decode;
    f.clipKey = src.clipKey;
    f.status = status;
    faults_.push_back(f);
}

void AudioCore::drainRetired(bool force) {
    const int64_t block = blockCounter_.load(std::memory_order_acquire);
    const bool noReaders = !rendering_.load(std::memory_order_acquire);
    // A buffer detached at block N may still be mid-read in block N; two further blocks prove
    // the audio thread has moved on and cannot hold the old pointer.
    std::erase_if(retired_, [&](const Retired& r) {
        if (force || noReaders || block >= r.atBlock + 2) {
            ClipBuffer::release(r.storage);
            return true;
        }
        return false;
    });
}

}  // namespace uv::audio
