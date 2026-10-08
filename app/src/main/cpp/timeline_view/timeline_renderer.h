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
#include <vector>

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
    // Zooms the time axis to show the whole timeline and resumes following it on resize. Lane heights are not touched.
    void fitToContent();
    // Refits the time axis to the project length if it is still following it (the timeline length changed).
    void followContent();
    // False once the user has zoomed by hand, until the next fitToContent().
    bool isAutoFit() const;
    void fling(float velocityX);
    void invalidate();
    // The live indicator of what releasing a dragged clip would do; DropHintKind::None clears it.
    void setDropHint(const DropHint& hint);
    // The selection rectangle dragged over empty space in select mode, in view pixels; `active` false hides it.
    void setMarquee(bool active, float x0, float y0, float x1, float y1);
    // What is drawn while clips are dragged or trimmed: a line at `snapGuideFrame` (negative for none) where an edge snapped,
    // and the blocks of the clips with these keys lifted (soft shadow, drawn over the others). An empty overlay (no guide,
    // no keys) costs nothing; call again with an empty one when the drag ends.
    void setDragOverlay(int64_t snapGuideFrame, const int64_t* clipKeys, size_t count);
    // The lane header drag: lane `from` is being moved and would land on lane `to`; -1 for both clears the indicator.
    void setLaneDrag(int from, int to);
    // Keys of the clips intersecting a view-pixel rectangle (see clipsInRect).
    std::vector<int64_t> clipsInRect(float x0, float y0, float x1, float y1) const;
    // Lane height as a multiple of the default, and the audio lanes' height as a multiple of that (see Layout::forDensity); redraws and keeps the scroll valid.
    void setLaneScale(float scale, float audioFactor = 1.0f);
    HitResult hitTest(float x, float y) const;
    // The canvas colours from the app palette (kNativeColourCount ARGB values, see timeline_theme.h); other sizes are ignored.
    void setPalette(const uint32_t* argb, size_t count);
    // How waveform amplitudes become heights: 0 linear (default), 1 decibels (see audio::WaveScale); other values mean linear.
    void setWaveformScale(int scale);
    // A text bitmap (premultiplied RGBA, w*h*4 bytes) made by Kotlin, keyed by labelHash(); any thread. Blocks briefly when
    // the render thread is far behind. The render thread places it in the atlas a few per frame.
    void putLabel(uint64_t hash, int w, int h, bool colour, const uint8_t* rgba);
    // Moves on whenever the text atlas is emptied: bitmaps sent before are gone and must be sent again.
    uint32_t labelGeneration() const { return labelGeneration_.load(); }
    // Copies up to `capacity` hashes of the bitmaps the atlas dropped to make room (least recently used first) into `out` and
    // forgets them; returns how many. Kotlin sends again the ones it still needs. Any thread.
    size_t takeEvictedLabels(uint64_t* out, size_t capacity);

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
    std::atomic<uint32_t> labelGeneration_{0};
};

}  // namespace uv::timeline
