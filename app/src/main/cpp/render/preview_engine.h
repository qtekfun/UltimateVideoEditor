#pragma once

#include <android/native_window.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "cache/lru_cache.h"
#include "decode/gpu_frame.h"
#include "decode/status.h"
#include "decode/video_decoder.h"
#include "render/gl_context.h"
#include "render/gl_pipeline.h"

namespace uv::render {

struct FrameKey {
    uint32_t asset;
    int64_t frame;
    bool operator==(const FrameKey& o) const { return asset == o.asset && frame == o.frame; }
};

struct FrameKeyHash {
    size_t operator()(const FrameKey& k) const {
        return std::hash<int64_t>()(k.frame) * 1315423911u ^ std::hash<uint32_t>()(k.asset);
    }
};

struct PreviewStats {
    int64_t cacheUsedBytes;
    int64_t cacheBudgetBytes;
    int64_t cacheEntries;
    int64_t framesDrawn;
    int64_t stalls;
    int64_t framesDecoded;
};

// Single-threaded task loop with delayed tasks. Owns the thread that holds the GL context.
class RenderThread {
public:
    using Task = std::function<void()>;
    RenderThread();
    ~RenderThread();
    void post(Task task);
    void postAt(Task task, std::chrono::steady_clock::time_point when);
    // Runs `task` on the render thread and waits for it. Must not be called from that thread.
    void postAndWait(Task task);

private:
    void run();

    std::mutex mu_;
    std::condition_variable cv_;
    std::multimap<std::chrono::steady_clock::time_point, Task> tasks_;
    bool stop_ = false;
    std::thread thread_;
};

// Preview of one asset at a time: decode workers fill the frame cache, the render thread
// colour-converts and presents cached frames on the attached Surface.
class PreviewEngine {
public:
    using ErrorSink = std::function<void(const decode::Error&)>;

    static decode::Result<std::unique_ptr<PreviewEngine>> create(size_t cacheBudgetBytes, ErrorSink sink);
    ~PreviewEngine();
    PreviewEngine(const PreviewEngine&) = delete;
    PreviewEngine& operator=(const PreviewEngine&) = delete;

    // Blocking; call from any thread but the render thread.
    decode::Status attachSurface(ANativeWindow* window, decode::Error* error);
    void detachSurface();

    // Blocking I/O: call from a background thread. Takes ownership of `fd`.
    decode::Result<decode::AssetInfo> openAsset(uint32_t assetId, int fd, decode::Rational fpsOverride);
    void closeAsset(uint32_t assetId);

    void seek(uint32_t assetId, int64_t frame);
    void play(uint32_t assetId, int64_t startFrame);
    void pause();
    void setColorMode(uint32_t assetId, ColorMode mode);
    void setCacheBudget(size_t bytes);
    PreviewStats stats() const;

private:
    struct Asset {
        std::shared_ptr<decode::VideoDecoder> decoder;
        ColorMode mode = ColorMode::Sdr709;
    };

    PreviewEngine(size_t cacheBudgetBytes, ErrorSink sink);

    std::shared_ptr<decode::VideoDecoder> decoderFor(uint32_t assetId);
    void applyWindowForBudget();

    // Render-thread-only below.
    void drain(uint32_t assetId);
    void maybeDraw(bool force);
    void setCurrent(uint32_t assetId, int64_t frame);
    void tick(uint64_t generation);
    void report(const decode::Error& error);

    ErrorSink sink_;
    cache::LruCache<FrameKey, std::shared_ptr<decode::GpuFrame>, FrameKeyHash> cache_;

    mutable std::mutex assetMu_;
    std::map<uint32_t, Asset> assets_;

    // Render-thread state.
    std::unique_ptr<EglContext> egl_;
    std::unique_ptr<GlPipeline> pipeline_;
    // Buffers evicted from the cache are recycled: allocating a 4K buffer per frame is too slow.
    std::vector<std::shared_ptr<decode::GpuFrame>> pool_;
    bool hasCurrent_ = false;
    uint32_t curAsset_ = 0;
    int64_t curFrame_ = 0;
    bool drawnValid_ = false;
    FrameKey drawnKey_{0, 0};
    bool playing_ = false;
    uint64_t playGeneration_ = 0;
    int64_t playStartFrame_ = 0;
    std::chrono::steady_clock::time_point playStart_;

    std::atomic<int64_t> framesDrawn_{0};
    std::atomic<int64_t> stalls_{0};

    // Declared last so it is destroyed first (thread joins before members above go away).
    std::unique_ptr<RenderThread> thread_;
};

}  // namespace uv::render
