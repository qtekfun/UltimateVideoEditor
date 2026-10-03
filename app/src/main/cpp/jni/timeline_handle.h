#pragma once

#include <jni.h>

#include <atomic>
#include <memory>

#include "audio/waveform_service.h"
#include "thumbnail/thumbnail_service.h"
#include "timeline_view/timeline_renderer.h"

// Native state behind one Kotlin `NativeTimeline` handle. Shared by timeline_jni.cpp (canvas and
// waveforms) and thumbnail_jni.cpp (thumbnails), which hang off the same renderer.

// Lifetime anchor for thumbnail callbacks: the worker thread can outlive the handle by a moment, so
// its callbacks go through this object (never through the handle) and become no-ops once closed.
struct ThumbBridge;

struct TimelineHandle {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;  // global ref
    jmethodID onWaveformReady = nullptr;
    std::atomic<bool> closing{false};
    std::shared_ptr<uv::audio::WaveformService> waveforms;
    std::shared_ptr<uv::thumb::ThumbnailService> thumbnails;
    std::shared_ptr<ThumbBridge> thumbBridge;
    std::unique_ptr<uv::timeline::TimelineRenderer> renderer;
};

// Stops thumbnail callbacks and releases the service and the Kotlin listener. Call before the
// renderer is destroyed.
void destroyThumbnails(JNIEnv* env, TimelineHandle* h);
