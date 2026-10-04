#include "audio/audio_core.h"

#include <algorithm>
#include <cstring>
#include <set>

#include "audio/audio_mixer.h"

#ifdef __ANDROID__
#include <android/log.h>
#define UV_AUDIO_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "uv_audio_core", __VA_ARGS__)
#else
#define UV_AUDIO_LOGW(...) ((void)0)
#endif

namespace uv::audio {

namespace {

using core::Status;
using Clock = std::chrono::steady_clock;

constexpr auto kRetryCooldown = std::chrono::seconds(1);
// An offline render (the export) retries a failing clip this many times before giving up on it:
// decoders can be reclaimed or hiccup under load, and a retry is cheap next to a lost export.
constexpr int32_t kMaxOfflineFailures = 4;
constexpr auto kOfflineWait = std::chrono::seconds(30);
constexpr size_t kMaxQueuedFaults = 64;

// Identity of a clip's retime: knots that differ in any frame or position give a different source.
uint64_t hashKnots(const std::vector<RetimeKnot>& knots) {
    uint64_t h = 1469598103934665603ull;
    const auto mix = [&h](uint64_t v) {
        h ^= v;
        h *= 1099511628211ull;
    };
    for (const RetimeKnot& k : knots) {
        mix(static_cast<uint64_t>(k.frame));
        uint64_t bits = 0;
        std::memcpy(&bits, &k.sourceFrame, sizeof(bits));
        mix(bits);
    }
    return h;
}

// Identity of a clip's noise suppression: strength and every profile bin.
uint64_t hashDenoise(float strength, const std::vector<float>& profile) {
    if (strength <= 0.0f) return 0;
    uint64_t h = 1469598103934665603ull;
    const auto mix = [&h](uint64_t v) {
        h ^= v;
        h *= 1099511628211ull;
    };
    uint32_t bits = 0;
    std::memcpy(&bits, &strength, sizeof(bits));
    mix(bits);
    for (float v : profile) {
        std::memcpy(&bits, &v, sizeof(bits));
        mix(bits);
    }
    return h | 1ull;  // never 0, so "no denoise" and "denoise" cannot collide
}

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

    // Tracks: bus compressor and volume/mute, plus the roles the ducker listens to and ducks.
    bool hasVoice = false, hasMusic = false;
    for (const AudioTrackDesc& t : data.tracks) {
        PreparedTrack pt;
        pt.key = t.trackKey;
        pt.gain = dbToLinear(t.gainDb);
        pt.muted = t.muted;
        pt.role = t.role;
        pt.comp.configure(t.comp, rate);
        pt.smoothedGain = pt.muted ? 0.0 : pt.gain;
        hasVoice = hasVoice || t.role == TrackRole::Voice;
        hasMusic = hasMusic || t.role == TrackRole::Music;
        prepared->tracks.push_back(pt);
    }
    if (prepared->tracks.empty()) prepared->tracks.push_back(PreparedTrack{});
    prepared->duckEnabled = data.ducking.amountDb > 0.0f && hasVoice && hasMusic;
    prepared->ducker.configure(data.ducking, rate);
    prepared->limiterOn = !data.limiterOff;
    prepared->limiter.configure(-1.0f, rate);
    prepared->meter = &meter_;
    prepared->allocateScratch();

    std::map<SourceKey, std::shared_ptr<ClipSource>> next;
    std::set<int64_t> clipKeys;
    for (const AudioClipDesc& d : data.clips) {
        if (!clipKeys.insert(d.clipKey).second) return Status::BadSnapshot;  // duplicate clip key
        if (d.trackIndex < 0 || static_cast<size_t>(d.trackIndex) >= prepared->tracks.size()) return Status::BadSnapshot;
        const int64_t start = framesToSamples(d.startFrame, data.fps, rate);
        const int64_t end = framesToSamples(d.startFrame + d.durationFrames, data.fps, rate);
        if (end <= start) continue;  // shorter than one output sample

        const uint64_t denoiseHash = hashDenoise(d.denoiseStrength, d.noiseProfile);
        const uint64_t voiceHash = d.voice.hash();
        const SourceKey key{d.clipKey, d.assetKey, d.sourceInFrame, d.sourceFps.num, d.sourceFps.den, hashKnots(d.knots), denoiseHash, voiceHash};
        std::shared_ptr<ClipSource> source;
        if (auto it = sources_.find(key); it != sources_.end()) {
            source = it->second;
        } else {
            source = std::make_shared<ClipSource>(d.clipKey, d.assetKey, sourceFramesToMicros(d.sourceInFrame, d.sourceFps),
                                                  bufferFrames_, d.knots, d.sourceFps, d.denoiseStrength, d.noiseProfile, denoiseHash, d.voice, voiceHash);
        }
        next[key] = source;

        PreparedClip pc;
        pc.startSample = start;
        pc.endSample = end;
        pc.gain = dbToLinear(d.gainDb);
        pc.track = d.trackIndex;
        pc.pan = d.pan;
        bool bandAnimated = false;
        for (const AutoLane& lane : d.lanes) {
            if (lane.points.empty()) return Status::BadSnapshot;
            PreparedLane pl;
            pl.param = lane.param;
            pl.samples.reserve(lane.points.size());
            pl.values.reserve(lane.points.size());
            for (const AutoPoint& pt : lane.points) {
                // Points sit on the same sample grid as the clip edges, counted from the clip's own start.
                pl.samples.push_back(framesToSamples(d.startFrame + pt.frame, data.fps, rate) - start);
                pl.values.push_back(lane.param == AutoParam::GainDb ? dbToLinear(pt.value) : pt.value);
            }
            // Distinct frames can land on one sample at an extreme rate: keep the later point only.
            for (size_t i = pl.samples.size(); i-- > 1;) {
                if (pl.samples[i] <= pl.samples[i - 1]) {
                    pl.samples.erase(pl.samples.begin() + static_cast<std::ptrdiff_t>(i - 1));
                    pl.values.erase(pl.values.begin() + static_cast<std::ptrdiff_t>(i - 1));
                }
            }
            bandAnimated = bandAnimated || static_cast<int32_t>(lane.param) >= static_cast<int32_t>(AutoParam::EqGain0);
            pc.lanes.push_back(std::move(pl));
        }
        pc.eqParams = d.eq;
        pc.sampleRate = rate;
        pc.eq = bandAnimated ? dsp::EqChain::designAll(d.eq, rate) : dsp::EqChain::design(d.eq, rate);
        if (d.userFadeInFrames > 0) pc.userFadeInSamples = framesToSamples(d.startFrame + d.userFadeInFrames, data.fps, rate) - start;
        if (d.userFadeOutFrames > 0) {
            pc.userFadeOutSamples = end - framesToSamples(d.startFrame + d.durationFrames - d.userFadeOutFrames, data.fps, rate);
        }
        // Fade lengths are measured on the same sample grid as the clip edges, so a fade-out
        // and the fade-in of the clip it hands over to cover exactly the same samples.
        if (d.fadeInFrames > 0) {
            pc.fadeInSamples = framesToSamples(d.startFrame + d.fadeInFrames, data.fps, rate) - start;
        }
        if (d.fadeOutFrames > 0) {
            pc.fadeOutSamples = end - framesToSamples(d.startFrame + d.durationFrames - d.fadeOutFrames, data.fps, rate);
        }
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
    if (const PreparedSnapshot* np = pending_.exchange(nullptr, std::memory_order_acq_rel)) {
        if (current_ != nullptr) np->adoptStateFrom(*current_);  // filters/envelopes keep running across an edit
        current_ = np;
    }
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
    const bool offline = offline_.load(std::memory_order_acquire);
    for (const PreparedClip& clip : current_->clips) {
        const int64_t a = std::max(pos, clip.startSample);
        const int64_t b = std::min(pos + windowFrames, clip.endSample);
        if (a >= b) continue;
        if (!clip.source->ready(a - clip.startSample, static_cast<int32_t>(b - a), offline, kMaxOfflineFailures)) return false;
    }
    return true;
}

void AudioCore::waitUntilReady(int64_t pos, int32_t frames) {
    // Offline only (the caller is not a real-time thread, so sleeping is fine).
    const auto deadline = Clock::now() + kOfflineWait;
    while (!clipsReadyAt(pos, frames) && Clock::now() < deadline) {
        wake();
        std::this_thread::sleep_for(std::chrono::microseconds(500));
    }
    if (clipsReadyAt(pos, frames)) return;
    // Mixing on would export silence where a clip should be: report which clip it was instead.
    int64_t stuck = -1;
    if (current_ != nullptr) {
        for (const PreparedClip& clip : current_->clips) {
            const int64_t a = std::max(pos, clip.startSample);
            const int64_t b = std::min(pos + frames, clip.endSample);
            if (a < b && !clip.source->ready(a - clip.startSample, static_cast<int32_t>(b - a), true, kMaxOfflineFailures)) {
                stuck = clip.source->clipKey;
                break;
            }
        }
    }
    UV_AUDIO_LOGW("offline render gave up waiting for clip %lld at sample %lld", static_cast<long long>(stuck),
                  static_cast<long long>(pos));
    std::lock_guard<std::mutex> lock(faultMutex_);
    if (faults_.size() >= kMaxQueuedFaults) return;
    AudioFault f;
    f.kind = AudioFault::Kind::OfflineStall;
    f.clipKey = stuck;
    f.status = Status::CodecError;
    faults_.push_back(f);
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
    if (!src.knots.empty()) {
        serviceRetimedClip(src, clip, needStart, needEnd, rate);
        return;
    }

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
        if (src.denoiser) src.denoiser->reset();
        if (src.voiceFx) src.voiceFx->reset();
        src.drainLeft = 0;
        src.buffer.reset(needStart);
        src.decodedEnd = needStart;
        src.hitEof = false;
        src.eofAt.store(INT64_MAX, std::memory_order_release);
        src.failed.store(false, std::memory_order_release);
    }
    if (src.failed.load(std::memory_order_acquire) || !src.decoder) return;

    const int64_t len = clip.endSample - clip.startSample;
    for (int guard = 0; guard < 64 && !src.hitEof && src.decodedEnd < needEnd; ++guard) {
        if (src.drainLeft > 0) {
            // The media ended but a voice effect (echo, reverb) is still sounding: feed it silence, a chunk per pass,
            // so its tail reaches the buffer progressively like decoded audio does.
            const int64_t chunk = std::min<int64_t>(kDecodeChunk, src.drainLeft);
            src.srcScratch.assign(static_cast<size_t>(chunk) * 2, 0.0f);
            src.voiceScratch.clear();
            src.voiceFx->process(src.srcScratch.data(), static_cast<size_t>(chunk), &src.voiceScratch);
            const int64_t produced = std::min<int64_t>(static_cast<int64_t>(src.voiceScratch.size() / 2), len - src.decodedEnd);
            if (produced > 0) {
                src.buffer.append(src.voiceScratch.data(), static_cast<int32_t>(produced));
                src.decodedEnd += produced;
            }
            src.drainLeft -= chunk;
            if (src.drainLeft <= 0 || src.decodedEnd >= len) {
                src.drainLeft = 0;
                src.hitEof = true;
                src.eofAt.store(src.decodedEnd, std::memory_order_release);  // media (and its tail) ends before the clip does
            }
            continue;
        }
        src.srcScratch.resize(static_cast<size_t>(kDecodeChunk) * 2);
        const PcmReadResult r = src.decoder->read(src.srcScratch.data(), kDecodeChunk);
        if (r.status != Status::Ok) {
            failClip(src, r.status);
            return;
        }
        if (r.frames > 0) {
            src.outScratch.clear();
            src.resampler->process(src.srcScratch.data(), static_cast<size_t>(r.frames), &src.outScratch);
            std::vector<float>* ready = &src.outScratch;
            if (src.denoiser) {
                src.denoisedScratch.clear();
                src.denoiser->process(src.outScratch.data(), src.outScratch.size() / 2, &src.denoisedScratch);
                ready = &src.denoisedScratch;
            }
            if (src.voiceFx) {
                src.voiceScratch.clear();
                src.voiceFx->process(ready->data(), ready->size() / 2, &src.voiceScratch);
                ready = &src.voiceScratch;
            }
            const int64_t produced = std::min<int64_t>(static_cast<int64_t>(ready->size() / 2), len - src.decodedEnd);
            if (produced > 0) {
                src.buffer.append(ready->data(), static_cast<int32_t>(produced));
                src.decodedEnd += produced;
                src.failures.store(0, std::memory_order_release);
            }
            if (src.decodedEnd >= len) src.hitEof = true;  // clip end reached; nothing more to decode
        }
        if (r.eof) {
            if ((src.denoiser || src.voiceFx) && src.decodedEnd < len) {
                // The suppressor holds back a few hundred samples: let the tail out before the media ends.
                src.denoisedScratch.clear();
                if (src.denoiser) src.denoiser->flush(&src.denoisedScratch);
                std::vector<float>* tail = &src.denoisedScratch;
                if (src.voiceFx) {
                    src.voiceScratch.clear();
                    if (!src.denoisedScratch.empty()) {
                        src.voiceFx->process(src.denoisedScratch.data(), src.denoisedScratch.size() / 2, &src.voiceScratch);
                    }
                    tail = &src.voiceScratch;
                }
                const int64_t produced = std::min<int64_t>(static_cast<int64_t>(tail->size() / 2), len - src.decodedEnd);
                if (produced > 0) {
                    src.buffer.append(tail->data(), static_cast<int32_t>(produced));
                    src.decodedEnd += produced;
                }
                if (src.voiceFx && src.decodedEnd < len) {
                    // Frames still inside the vocoder plus the echo/reverb tail, bounded by the clip's length.
                    const int64_t inside = src.voiceFx->framesIn() - src.voiceFx->framesOut();
                    src.drainLeft = inside + src.voice.tailFrames(rate) + kVoiceHop;
                    continue;  // the drain branch above lets it sound; the end of the media is declared when it is done
                }
            }
            src.hitEof = true;
            src.eofAt.store(src.decodedEnd, std::memory_order_release);  // media ends before the clip does
        }
        if (r.frames == 0 && !r.eof) break;  // nothing ready yet; try again next pass
    }
}

// Like serviceClip, for a clip whose source is read through its retime knots: the decoder is not
// positioned here (the reader seeks as the mapping needs), the worker just asks it for the next
// output samples of the clip.
void AudioCore::serviceRetimedClip(ClipSource& src, const PreparedClip& clip, int64_t needStart, int64_t needEnd,
                                   int32_t rate) {
    const bool inWindow = needStart >= src.buffer.windowStart() && needStart <= src.buffer.writeEnd();
    if (!inWindow || !src.decoder) {
        if (!src.decoder && !openDecoder(src, rate)) return;
        src.retimed->reset();
        if (src.denoiser) src.denoiser->reset();
        if (src.voiceFx) src.voiceFx->reset();
        src.drainLeft = 0;
        src.buffer.reset(needStart);
        src.decodedEnd = needStart;
        src.renderedEnd = needStart;
        src.hitEof = false;
        src.eofAt.store(INT64_MAX, std::memory_order_release);
        src.failed.store(false, std::memory_order_release);
    }
    if (src.failed.load(std::memory_order_acquire) || !src.decoder || !src.retimed) return;

    const int64_t len = clip.endSample - clip.startSample;
    // With a noise suppressor the buffer (decodedEnd) lags what was asked of the reader (renderedEnd).
    for (int guard = 0; guard < 64 && src.decodedEnd < needEnd && src.renderedEnd < len; ++guard) {
        const int32_t n = static_cast<int32_t>(std::min<int64_t>(kRetimeBlock, len - src.renderedEnd));
        src.outScratch.resize(static_cast<size_t>(n) * 2);
        const RetimedReader::Result r = src.retimed->render(src.renderedEnd, n, src.outScratch.data());
        if (r == RetimedReader::Result::Error) {
            failClip(src, src.retimed->lastStatus());
            return;
        }
        if (r == RetimedReader::Result::NotReady) break;  // nothing decoded yet; try again next pass
        src.renderedEnd += n;
        const float* data = src.outScratch.data();
        int32_t count = n;
        if (src.denoiser) {
            src.denoisedScratch.clear();
            src.denoiser->process(src.outScratch.data(), static_cast<size_t>(n), &src.denoisedScratch);
            if (src.renderedEnd >= len) src.denoiser->flush(&src.denoisedScratch);
            data = src.denoisedScratch.data();
            count = static_cast<int32_t>(src.denoisedScratch.size() / 2);
        }
        if (src.voiceFx) {
            src.voiceScratch.clear();
            if (count > 0) src.voiceFx->process(data, static_cast<size_t>(count), &src.voiceScratch);
            if (src.renderedEnd >= len) src.voiceFx->flush(&src.voiceScratch, 0);
            data = src.voiceScratch.data();
            count = static_cast<int32_t>(src.voiceScratch.size() / 2);
        }
        if (count > 0) src.buffer.append(data, count);
        src.decodedEnd += count;
        src.failures.store(0, std::memory_order_release);
    }
    if (src.decodedEnd >= len) src.hitEof = true;  // clip end reached; nothing more to render
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
    if (src.denoiseStrength > 0.0f && src.noiseProfile.size() == static_cast<size_t>(kDenoiseBins)) {
        src.denoiser = std::make_unique<SpectralDenoiser>(src.noiseProfile.data(), src.denoiseStrength);
    }
    if (!src.voice.isNeutral()) src.voiceFx = std::make_unique<VoiceProcessor>(src.voice, rate);
    src.drainLeft = 0;
    if (!src.knots.empty()) {
        src.retimed = std::make_unique<RetimedReader>(src.decoder.get(), RetimeMap(src.knots, src.fps, rate, srcRate));
    } else {
        src.resampler.emplace(srcRate, rate);
    }
    return true;
}

void AudioCore::releaseClip(ClipSource& src) {
    src.retimed.reset();  // before the decoder it reads from
    src.denoiser.reset();
    src.voiceFx.reset();
    src.drainLeft = 0;
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
    const int32_t failures = src.failures.fetch_add(1, std::memory_order_acq_rel) + 1;
    UV_AUDIO_LOGW("clip %lld failed (status %d, failure %d)", static_cast<long long>(src.clipKey), static_cast<int>(status),
                  failures);
    src.failed.store(true, std::memory_order_release);
    src.retryAfter = Clock::now() + kRetryCooldown;
    src.retimed.reset();
    src.denoiser.reset();
    src.voiceFx.reset();
    src.drainLeft = 0;
    src.decoder.reset();
    src.resampler.reset();
    src.hitEof = false;

    // An offline render waits for the retry; only a clip that keeps failing is reported.
    if (offline_.load(std::memory_order_acquire) && failures <= kMaxOfflineFailures) return;
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
