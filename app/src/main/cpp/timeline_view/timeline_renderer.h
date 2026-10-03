#pragma once

#include <android/choreographer.h>
#include <android/looper.h>
#include <android/native_window.h>

#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>

#include "audio/waveform_peaks.h"
#include "timeline_view/drop_hint.h"
#include "timeline_view/hit_test.h"
#include "timeline_view/layout.h"
#include "timeline_view/timeline_snapshot.h"
#include "timeline_view/viewport.h"

namespace uv::thumb {
class ThumbnailService;
}

namespace uv::timeline {

using WaveformLookup = std::function<std::shared_ptr<const audio::PeakPyramid>(int64_t assetKey)>;

// Draws the timeline canvas (ruler, lanes, clips, waveforms, playhead) on its own thread with an
// EGL/GLES 3 context. Frames are paced by AChoreographer and only produced while something changed.
// All public methods are thread-safe and non-blocking, except surfaceDestroyed (see its comment).
class TimelineRenderer {
public:
    TimelineRenderer(float density, WaveformLookup lookup);
    ~TimelineRenderer();
    TimelineRenderer(const TimelineRenderer&) = delete;
    TimelineRenderer& operator=(const TimelineRenderer&) = delete;

    void surfaceCreated(ANativeWindow* window);  // acquires a reference
    void surfaceChanged(int width, int height);
    // Blocks until the render thread has released the window, so the Surface may be destroyed after.
    void surfaceDestroyed();

    void setSnapshot(std::shared_ptr<const TimelineSnapshot> snapshot);
    // Source of video thumbnails; held weakly so the owner can destroy it first. May be empty.
    void setThumbnails(std::weak_ptr<thumb::ThumbnailService> service);
    void setPlayhead(int64_t frame);
    // Scrolls (without changing the zoom) so that `frame` is on screen; see Viewport::ensureVisible.
    void ensureVisible(int64_t frame);
    void scrollBy(float dx, float dy);
    void zoomBy(float factor, float focusX);
    // Zooms to show the whole timeline and resumes following it on resize.
    void fitToContent();
    // False once the user has zoomed by hand, until the next fitToContent().
    bool isAutoFit() const;
    void fling(float velocityX);
    void invalidate();
    // The live indicator of what releasing a dragged clip would do; DropHintKind::None clears it.
    void setDropHint(const DropHint& hint);
    HitResult hitTest(float x, float y) const;

    class Gl;  // render-thread only (public so file-local helpers can name it)

private:
    struct State;  // guarded by mutex_

    void threadMain();
    void wake();
    static void onFrame(int64_t frameTimeNanos, void* data);
    void frame(int64_t frameTimeNanos);

    std::thread thread_;
    ALooper* looper_ = nullptr;  // owned by the render thread
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::unique_ptr<State> state_;
    WaveformLookup lookup_;
    std::atomic<bool> quit_{false};
};

}  // namespace uv::timeline
