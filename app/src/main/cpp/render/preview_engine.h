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
#include "decode/open_decoder.h"
#include "decode/video_decoder_api.h"
#include "render/gl_context.h"
#include "render/gl_pipeline.h"
#include "render/scope_renderer.h"

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

// One layer of the scene: which frame of which open asset is shown, and how it is placed.
struct SceneLayer {
    uint32_t asset = 0;
    int64_t frame = 0;
    LayerTransform transform;
    uint32_t title = 0;  // != 0: a rasterised title (see uploadTitle); `asset` and `frame` are unused
    // playScene() only: `frame` is where playback starts, `baseFrame` is kept as the anchor and the
    // layer never advances past `limitFrame` (exclusive; the clip's out point), so a trimmed clip
    // holds its last frame instead of showing media the editor cut away.
    int64_t baseFrame = 0;
    int64_t limitFrame = INT64_MAX;
    // -1 when the clip plays backwards: playScene() then steps `frame` down, and the asset's decoder
    // keeps its window of decoded frames behind `frame` instead of ahead (see applyWindowForBudget).
    int32_t direction = 1;
    core::LayerFx fx = {};  // effects, blend mode and mask; neutral by default
    // The clip's colour override: a SourceTransfer value, or -1 to use the asset's own (detected) transfer.
    int32_t source = -1;
    // Smooth slow motion: how far the shown moment is from `frame` towards its neighbour, in 0..1. Positive
    // blends with the frame after `frame`, negative with the one before it (a reversed clip); 0 shows `frame`.
    float mix = 0.0f;
};

// Preview of a stack of layers: decode workers (one per open asset) fill the shared frame cache,
// the render thread colour-converts and composites cached frames on the attached Surface.
//
// Two ways to drive it:
//  - setScene(): the editor path. A project canvas plus layers bottom-to-top, each with its own
//    transform and opacity. It is redrawn once every layer's frame is cached.
//  - playScene(): the same, but the native clock advances every layer in step (project frames), so
//    the editor only has to re-anchor it when the composition changes or the audio clock disagrees.
//  - seek()/play(): single asset, shown full-surface with no transform (debug harness).
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
    // The surface was resized or reformatted: redraw the current frame at the new size.
    void surfaceChanged();

    // The video scopes panel: a second surface the engine draws the waveform, parade, vectorscope or
    // histogram of what the preview shows on, entirely on the GPU and at most 30 times a second, and only
    // while the surface is attached. Blocking; call from any thread but the render thread.
    decode::Status attachScopeSurface(ANativeWindow* window, decode::Error* error);
    void detachScopeSurface();
    void scopeSurfaceChanged();
    // `mode` is a scope::Mode value (0 waveform, 1 parade, 2 vectorscope, 3 histogram).
    void setScopeMode(int mode);

    // Blocking I/O: call from a background thread. Takes ownership of `fd`.
    decode::Result<decode::AssetInfo> openAsset(uint32_t assetId, int fd, decode::Rational fpsOverride);
    void closeAsset(uint32_t assetId);

    // Replaces the scene. `canvasW` x `canvasH` is the project resolution the layers are placed on
    // (letterboxed into the surface). Layers are listed bottom to top; their frames are indices in
    // each asset's own frame rate, clamped to the asset. Layers of assets that are not open are
    // reported and dropped. Stops native playback. Cheap enough to call on every playhead tick.
    void setScene(int canvasW, int canvasH, std::vector<SceneLayer> layers);

    // Titles are rasterised by the caller (premultiplied RGBA8, canvas pixels, top row first). The
    // pixels are copied; the texture is created on the render thread before the next scene is
    // applied, so upload first and then reference `key` from setScene(). `key` must not be 0.
    void uploadTitle(uint32_t key, int width, int height, std::vector<uint8_t> rgba, int displayWidth = 0, int displayHeight = 0);
    void releaseTitle(uint32_t key);
    // 3D LUTs for the LUT effect (see GlPipeline::uploadLut): size^3 RGB triples, red fastest.
    void uploadLut(uint32_t key, int size, std::vector<float> rgb);
    void releaseLut(uint32_t key);
    // Like setScene(), then plays: every layer advances at `fps` (project frames per second) from
    // now on the monotonic clock, each from its own start frame. Calling it again re-anchors.
    void playScene(int canvasW, int canvasH, std::vector<SceneLayer> layers, decode::Rational fps);

    void seek(uint32_t assetId, int64_t frame);
    void play(uint32_t assetId, int64_t startFrame);
    void pause();
    // Overrides what an asset's source is taken to be (SDR / HLG / PQ); its layer mode follows the target.
    void setColorMode(uint32_t assetId, ColorMode mode);
    // Chooses the colour space the preview surface is rendered in. Hlg2020 needs a ten-bit surface
    // tagged BT.2020 HLG and only takes effect when the device grants it; the result is what is
    // actually in use (Sdr709 when HLG was refused). Safe to call before the surface exists: the
    // request is remembered and applied when it is attached, so query again after attachSurface().
    OutputSpace setOutputSpace(OutputSpace requested);
    // What the surface is rendered in right now (Sdr709 until a surface is attached and HLG granted).
    OutputSpace outputSpace() const { return static_cast<OutputSpace>(effectiveSpace_.load()); }
    void setCacheBudget(size_t bytes);
    PreviewStats stats() const;

private:
    struct Asset {
        std::shared_ptr<decode::IVideoDecoder> decoder;
        ColorMode mode = ColorMode::Sdr709;  // derived from `transfer` and the output space
        int turns = 0;  // clockwise quarter turns for display, from the container rotation
        // Look-behind/ahead its decoder keeps filled; frames inside it are evicted last.
        int32_t windowBehind = 0;
        int32_t windowAhead = 0;
        bool reverse = false;  // the scene plays this asset backwards: the window above is mirrored
        SourceTransfer transfer = SourceTransfer::Sdr;
    };

    // What the last draw showed, to skip redundant draws.
    struct DrawnLayer {
        uint32_t asset;
        int64_t frame;
        LayerTransform transform;
        uint32_t title = 0;
        core::LayerFx fx = {};
        int32_t source = -1;
        float mix = 0.0f;           // the mix that was asked for
        uint8_t neighbours = 0;     // bit 0: blend frame drawn, bit 1: previous frame, bit 2: next frame (as decoded then)
        bool operator==(const DrawnLayer& o) const {
            return asset == o.asset && frame == o.frame && title == o.title && fx == o.fx && source == o.source &&
                   mix == o.mix && neighbours == o.neighbours &&
                   transform.posX == o.transform.posX &&
                   transform.posY == o.transform.posY && transform.scaleX == o.transform.scaleX &&
                   transform.scaleY == o.transform.scaleY && transform.rotationDeg == o.transform.rotationDeg &&
                   transform.opacity == o.transform.opacity;
        }
    };

    PreviewEngine(size_t cacheBudgetBytes, ErrorSink sink);

    std::shared_ptr<decode::IVideoDecoder> decoderFor(uint32_t assetId);
    void applyOutputSpace();
    void applyWindowForBudget();

    // Render-thread-only below.
    void drain(uint32_t assetId);
    // `presentNs` (CLOCK_MONOTONIC) asks the compositor to show the frame at that time; 0 = asap.
    void maybeDraw(bool force, int64_t presentNs = 0);
    // Installs `layers` as the scene and points each decoder at its frame. Returns false if none remain.
    bool applyScene(int canvasW, int canvasH, std::vector<SceneLayer> layers);
    // Draws the scopes from the last captured frame (render thread only); throttled to ~30 Hz unless `force`.
    void renderScope(bool force);
    void tick(uint64_t generation);
    void tickScene(uint64_t generation);
    void report(const decode::Error& error);

    ErrorSink sink_;
    cache::LruCache<FrameKey, std::shared_ptr<decode::GpuFrame>, FrameKeyHash> cache_;

    mutable std::mutex assetMu_;
    std::map<uint32_t, Asset> assets_;

    // Render-thread state.
    std::unique_ptr<EglContext> egl_;
    std::unique_ptr<GlPipeline> pipeline_;
    // Scopes (render thread only).
    std::unique_ptr<ScopeRenderer> scope_;
    bool scopeActive_ = false;  // a scope surface is attached and the renderer is ready
    scope::Mode scopeMode_ = scope::Mode::Waveform;
    std::chrono::steady_clock::time_point scopeLast_;
    bool scopeTaskPending_ = false;
    OutputSpace requestedSpace_ = OutputSpace::Sdr709;     // what the caller asked for
    std::atomic<int> effectiveSpace_{static_cast<int>(OutputSpace::Sdr709)};  // what the surface really is
    // Buffers evicted from the cache are recycled: allocating a 4K buffer per frame is too slow.
    std::vector<std::shared_ptr<decode::GpuFrame>> pool_;
    std::vector<SceneLayer> scene_;  // bottom to top
    int canvasW_ = 0;                // 0 = single-asset mode: the canvas is the first layer's displayed size
    int canvasH_ = 0;
    bool drawnValid_ = false;
    uint64_t drawnStabRevision_ = 0;
    // Smooth slow motion falls back to plain blending when its draws run over the frame budget.
    int slowInterpDraws_ = 0;
    int fastInterpDraws_ = 0;
    std::vector<DrawnLayer> drawn_;
    int drawnCanvasW_ = 0;
    int drawnCanvasH_ = 0;
    bool playing_ = false;
    bool sceneMode_ = false;  // playing a multi-layer scene (playScene) rather than one asset
    decode::Rational sceneFps_{1, 1};
    uint64_t playGeneration_ = 0;
    int64_t playStartFrame_ = 0;
    std::chrono::steady_clock::time_point playStart_;

    // Per-stage timing (render thread only); summarised in the log about once per second of playback.
    struct StageTimes {
        int64_t blitNs = 0;
        int64_t drawNs = 0;
        int64_t swapNs = 0;
        int64_t blits = 0;
        int64_t draws = 0;
        std::chrono::steady_clock::time_point lastLog;
    };
    void logStageTimes();
    StageTimes times_;

    std::atomic<int64_t> framesDrawn_{0};
    std::atomic<int64_t> stalls_{0};

    // Declared last so it is destroyed first (thread joins before members above go away).
    std::unique_ptr<RenderThread> thread_;
};

}  // namespace uv::render
