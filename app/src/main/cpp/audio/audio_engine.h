#pragma once

// Android audio engine: Oboe (AAudio) output stream + AudioCore + MediaCodec decoders.
// Public methods are called from the Kotlin side through jni/audio_jni.cpp, from one thread at a
// time (main thread). The Oboe callbacks run on their own threads.

#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "audio/audio_core.h"
#include "core/error.h"

namespace uv::audio {

// JNI result codes: a core::Status value, or this audio-specific code.
constexpr int32_t kResultDeviceError = 100;

struct AudioStats {
    int32_t sampleRate = 0;
    int32_t framesPerBurst = 0;
    int32_t bufferSizeFrames = 0;
    int32_t exclusive = 0;      // 1 when the stream got exclusive (MMAP) sharing
    int32_t lowLatency = 0;     // 1 when the stream got the low-latency performance mode
    int32_t api = 0;            // oboe::AudioApi value
    int64_t latencyMicros = -1; // output latency estimate, -1 when unknown
    int32_t xruns = -1;         // device-side underruns, -1 when unsupported
    int64_t underrunBlocks = 0; // blocks where a clip's data was not ready (app-side)
    int32_t deviceId = 0;
};

class AudioEngine final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;
    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    // `offline` renders on demand via renderOffline() with no audio device (tests, export).
    int32_t start(bool offline);
    void stop();

    // Registers (a dup of) the file descriptor for an asset; decoders are opened lazily.
    int32_t setAssetFd(int64_t assetKey, int fd);
    void removeAsset(int64_t assetKey);
    int32_t setSnapshot(const uint8_t* data, size_t size);

    void play();
    void pause();
    int32_t seekFrame(int64_t frame);

    // Master clock: timeline position being heard, latency-compensated.
    int64_t positionSamples();
    int64_t positionFrame();
    int32_t sampleRate() const { return core_.sampleRate(); }

    AudioStats stats();
    void pollFaults(std::vector<AudioFault>* out) { core_.pollFaults(out); }
    // Output peaks (linear) since the previous call, for the level meters.
    void takePeaks(float* left, float* right) { core_.takePeaks(left, right); }

    // Offline measurements of a registered asset (see audio/analysis.h). They decode on the calling
    // thread, so call them from a background thread; cancelAnalysis() stops a running one.
    int32_t measureLoudness(int64_t assetKey, int64_t startMicros, int64_t endMicros, double* lufs, double* samplePeak);
    int32_t measureNoiseProfile(int64_t assetKey, int64_t startMicros, int64_t endMicros, float* magnitudes);
    void cancelAnalysis() { cancelAnalysis_.store(true, std::memory_order_release); }

    // Offline mode only: renders `frames` stereo frames and returns the playhead afterwards (it
    // does not advance while the engine is still buffering after a play/seek).
    int64_t renderOffline(float* out, int32_t frames);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    oboe::Result openStreamLocked();
    std::shared_ptr<oboe::AudioStream> currentStream();
    int dupAsset(int64_t key);
    std::unique_ptr<PcmDecoder> openAssetDecoder(int64_t assetKey, core::Status* status);

    std::atomic<bool> cancelAnalysis_{false};
    AudioCore core_;
    std::mutex lifecycleMutex_;
    std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<oboe::LatencyTuner> tuner_;
    int32_t configuredRate_ = 0;
    bool running_ = false;
    bool offline_ = false;

    std::mutex assetMutex_;
    std::unordered_map<int64_t, int> assets_;
};

}  // namespace uv::audio
