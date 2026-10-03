#include "audio/audio_engine.h"

#include <android/log.h>
#include <time.h>
#include <unistd.h>

#include "audio/android_pcm_decoder.h"
#include "audio/audio_time.h"

#define LOG_TAG "uv_audio_engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace uv::audio {

namespace {

using core::Status;

constexpr int32_t kOfflineSampleRate = 48000;

int64_t monotonicNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

}  // namespace

AudioEngine::AudioEngine()
    : core_([this](int64_t assetKey, Status* status) -> std::unique_ptr<PcmDecoder> {
          const int fd = dupAsset(assetKey);
          if (fd < 0) {
              *status = Status::InvalidArgument;  // asset was never registered or already removed
              return nullptr;
          }
          std::unique_ptr<PcmDecoder> decoder = AndroidPcmDecoder::open(fd, status);
          close(fd);  // the decoder keeps its own duplicate
          return decoder;
      }) {}

AudioEngine::~AudioEngine() {
    stop();
    std::lock_guard<std::mutex> lock(assetMutex_);
    for (auto& [key, fd] : assets_) close(fd);
}

int AudioEngine::dupAsset(int64_t key) {
    std::lock_guard<std::mutex> lock(assetMutex_);
    const auto it = assets_.find(key);
    return it == assets_.end() ? -1 : dup(it->second);
}

int32_t AudioEngine::setAssetFd(int64_t assetKey, int fd) {
    const int copy = dup(fd);
    if (copy < 0) return static_cast<int32_t>(Status::IoError);
    std::lock_guard<std::mutex> lock(assetMutex_);
    if (auto it = assets_.find(assetKey); it != assets_.end()) close(it->second);
    assets_[assetKey] = copy;
    return static_cast<int32_t>(Status::Ok);
}

void AudioEngine::removeAsset(int64_t assetKey) {
    std::lock_guard<std::mutex> lock(assetMutex_);
    if (auto it = assets_.find(assetKey); it != assets_.end()) {
        close(it->second);
        assets_.erase(it);
    }
}

oboe::Result AudioEngine::openStreamLocked() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)  // falls back to shared when unavailable
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Movie)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(result));
        return result;
    }
    // Two bursts is the smallest buffer that is usually glitch-free; the tuner grows it on xruns.
    stream->setBufferSizeInFrames(stream->getFramesPerBurst() * 2);
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        tuner_ = std::make_unique<oboe::LatencyTuner>(*stream);
        stream_ = stream;
    }
    LOGI("stream open: rate=%d burst=%d buffer=%d sharing=%s perf=%s api=%s", stream->getSampleRate(),
         stream->getFramesPerBurst(), stream->getBufferSizeInFrames(), oboe::convertToText(stream->getSharingMode()),
         oboe::convertToText(stream->getPerformanceMode()), oboe::convertToText(stream->getAudioApi()));

    if (stream->getSampleRate() != configuredRate_) {
        const Status st = core_.configure(stream->getSampleRate());
        if (st != Status::Ok) {
            stream->close();
            std::lock_guard<std::mutex> lock(streamMutex_);
            stream_.reset();
            tuner_.reset();
            return oboe::Result::ErrorInternal;
        }
        configuredRate_ = stream->getSampleRate();
    }
    return oboe::Result::OK;
}

std::shared_ptr<oboe::AudioStream> AudioEngine::currentStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    return stream_;
}

int32_t AudioEngine::start(bool offline) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (running_) return static_cast<int32_t>(Status::Ok);
    offline_ = offline;

    if (offline) {
        if (configuredRate_ != kOfflineSampleRate) {
            const Status st = core_.configure(kOfflineSampleRate);
            if (st != Status::Ok) return static_cast<int32_t>(st);
            configuredRate_ = kOfflineSampleRate;
        }
    } else if (openStreamLocked() != oboe::Result::OK) {
        return kResultDeviceError;
    }

    core_.setOfflineMode(offline);
    core_.streamStarting();
    core_.resetStreamClock();
    core_.startWorker();
    if (!offline) {
        const oboe::Result r = currentStream()->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(r));
            core_.stopWorker();
            core_.streamStopped();
            std::lock_guard<std::mutex> sl(streamMutex_);
            stream_->close();
            stream_.reset();
            tuner_.reset();
            return kResultDeviceError;
        }
    }
    running_ = true;
    return static_cast<int32_t>(Status::Ok);
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (!running_) return;
    if (std::shared_ptr<oboe::AudioStream> stream = currentStream()) {
        stream->requestStop();
        stream->close();
        std::lock_guard<std::mutex> sl(streamMutex_);
        stream_.reset();
        tuner_.reset();
    }
    core_.stopWorker();
    core_.streamStopped();
    running_ = false;
}

int32_t AudioEngine::setSnapshot(const uint8_t* data, size_t size) {
    AudioSnapshotData parsed;
    const Status parseStatus = parseAudioSnapshot(data, size, &parsed);
    if (parseStatus != Status::Ok) return static_cast<int32_t>(parseStatus);
    return static_cast<int32_t>(core_.setSnapshot(parsed));
}

void AudioEngine::play() { core_.play(); }

void AudioEngine::pause() { core_.pause(positionSamples()); }

int32_t AudioEngine::seekFrame(int64_t frame) {
    if (frame < 0) return static_cast<int32_t>(Status::InvalidArgument);
    core_.seekSamples(core_.framesToTimelineSamples(frame));
    return static_cast<int32_t>(Status::Ok);
}

int64_t AudioEngine::positionSamples() {
    const ClockAnchor anchor = core_.anchor();
    if (!anchor.playing) return anchor.timelineSample;
    const int64_t rendered = core_.streamFrames();

    int64_t presented = rendered;  // offline: nothing is buffered between us and the "speaker"
    if (const std::shared_ptr<oboe::AudioStream> stream = currentStream()) {
        int64_t hwFrame = 0, hwTimeNs = 0;
        if (stream->getTimestamp(CLOCK_MONOTONIC, &hwFrame, &hwTimeNs) == oboe::Result::OK) {
            presented = presentedStreamFrame(hwFrame, hwTimeNs, monotonicNanos(), stream->getSampleRate());
        } else {
            // No hardware timestamp yet (stream just started): assume a full buffer is queued.
            presented = rendered - stream->getBufferSizeInFrames();
        }
    }
    return timelineSampleAt(anchor, presented, rendered);
}

int64_t AudioEngine::positionFrame() { return core_.samplesToTimelineFrames(positionSamples()); }

AudioStats AudioEngine::stats() {
    AudioStats s;
    s.sampleRate = core_.sampleRate();
    s.underrunBlocks = core_.underrunBlocks();
    if (const std::shared_ptr<oboe::AudioStream> stream = currentStream()) {
        s.framesPerBurst = stream->getFramesPerBurst();
        s.bufferSizeFrames = stream->getBufferSizeInFrames();
        s.exclusive = stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0;
        s.lowLatency = stream->getPerformanceMode() == oboe::PerformanceMode::LowLatency ? 1 : 0;
        s.api = static_cast<int32_t>(stream->getAudioApi());
        s.deviceId = stream->getDeviceId();
        if (const auto latency = stream->calculateLatencyMillis(); latency) {
            s.latencyMicros = static_cast<int64_t>(latency.value() * 1000.0);
        }
        if (const auto xruns = stream->getXRunCount(); xruns) s.xruns = xruns.value();
    }
    return s;
}

int64_t AudioEngine::renderOffline(float* out, int32_t frames) {
    core_.render(out, frames);
    return core_.renderPosSamples();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData, int32_t numFrames) {
    core_.render(static_cast<float*>(audioData), numFrames);
    if (tuner_) tuner_->tune();
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    LOGE("stream error: %s", oboe::convertToText(error));
    core_.reportDeviceFault(Status::IoError);
    // Typically a disconnect (headset unplugged, route change): reopen on the new default device.
    // try_lock: if stop() holds the lifecycle lock we are being shut down, so do nothing.
    std::unique_lock<std::mutex> lock(lifecycleMutex_, std::try_to_lock);
    if (!lock.owns_lock() || !running_ || offline_) return;
    {
        std::lock_guard<std::mutex> sl(streamMutex_);
        stream_.reset();
        tuner_.reset();
    }
    if (openStreamLocked() != oboe::Result::OK) {
        core_.reportDeviceFault(Status::IoError);
        return;
    }
    core_.resetStreamClock();
    if (currentStream()->requestStart() != oboe::Result::OK) {
        LOGE("could not restart the stream after an error");
        core_.reportDeviceFault(Status::IoError);
    }
}

}  // namespace uv::audio
