// JNI bindings for the timeline canvas and waveform service. Bindings only, no business logic.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "audio/waveform_service.h"
#include "core/error.h"
#include "jni/timeline_handle.h"
#include "timeline_view/timeline_renderer.h"
#include "timeline_view/timeline_snapshot.h"
#include "timeline_view/timeline_theme.h"

#define LOG_TAG "uv_timeline_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using uv::core::Status;

TimelineHandle* from(jlong h) { return reinterpret_cast<TimelineHandle*>(h); }
jint code(Status s) { return static_cast<jint>(s); }

void notifyWaveform(TimelineHandle* h, int64_t assetKey, Status status) {
    if (h->closing.load()) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (h->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (h->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("could not attach worker thread to the JVM");
            return;
        }
        attached = true;
    }
    env->CallVoidMethod(h->listener, h->onWaveformReady, static_cast<jlong>(assetKey), code(status));
    if (env->ExceptionCheck()) {
        LOGE("waveform listener threw");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) h->vm->DetachCurrentThread();
    if (!h->closing.load() && h->renderer) h->renderer->invalidate();
}

}  // namespace

extern "C" {

#define JNI_FN(name) Java_com_ultimatevideo_uveditor_engine_timeline_NativeTimeline_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv* env, jobject /*thiz*/, jfloat density, jobject listener) {
    auto* h = new TimelineHandle();
    if (env->GetJavaVM(&h->vm) != JNI_OK) {
        delete h;
        return 0;
    }
    jclass cls = env->GetObjectClass(listener);
    h->onWaveformReady = env->GetMethodID(cls, "onWaveformReady", "(JI)V");
    env->DeleteLocalRef(cls);
    if (h->onWaveformReady == nullptr) {
        LOGE("listener lacks onWaveformReady(long,int)");
        delete h;
        return 0;  // NoSuchMethodError is pending; Kotlin sees the exception
    }
    h->listener = env->NewGlobalRef(listener);
    h->waveforms = std::make_shared<uv::audio::WaveformService>(
        [h](int64_t key, Status status) { notifyWaveform(h, key, status); });
    std::weak_ptr<uv::audio::WaveformService> service = h->waveforms;
    h->renderer = std::make_unique<uv::timeline::TimelineRenderer>(density, [service](int64_t key) {
        auto s = service.lock();
        return s ? s->get(key) : nullptr;
    });
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    TimelineHandle* h = from(handle);
    if (h == nullptr) return;
    h->closing.store(true);
    destroyThumbnails(env, h);  // thumbnail callbacks poke the renderer, so stop them first
    h->waveforms.reset();  // joins the worker (the renderer only holds a weak reference)
    h->renderer.reset();   // worker callbacks can no longer fire, so the renderer is safe to drop
    env->DeleteGlobalRef(h->listener);
    delete h;
}

JNIEXPORT jint JNICALL JNI_FN(nativeSurfaceCreated)(JNIEnv* env, jobject, jlong handle, jobject surface) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || surface == nullptr) return code(Status::InvalidArgument);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return code(Status::InvalidArgument);
    h->renderer->surfaceCreated(window);
    ANativeWindow_release(window);  // renderer holds its own reference
    return code(Status::Ok);
}

JNIEXPORT void JNICALL JNI_FN(nativeSurfaceChanged)(JNIEnv*, jobject, jlong handle, jint w, jint hgt) {
    if (TimelineHandle* h = from(handle)) h->renderer->surfaceChanged(w, hgt);
}

JNIEXPORT void JNICALL JNI_FN(nativeSurfaceDestroyed)(JNIEnv*, jobject, jlong handle) {
    if (TimelineHandle* h = from(handle)) h->renderer->surfaceDestroyed();
}

JNIEXPORT jint JNICALL JNI_FN(nativeSetSnapshot)(JNIEnv* env, jobject, jlong handle, jobject buffer, jint size) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || buffer == nullptr || size < 0) return code(Status::InvalidArgument);
    const void* data = env->GetDirectBufferAddress(buffer);
    if (data == nullptr || env->GetDirectBufferCapacity(buffer) < size) return code(Status::InvalidArgument);
    auto snap = std::make_shared<uv::timeline::TimelineSnapshot>();
    const Status st = uv::timeline::parseSnapshot(static_cast<const uint8_t*>(data), static_cast<size_t>(size), snap.get());
    if (st != Status::Ok) return code(st);
    h->renderer->setSnapshot(std::move(snap));
    return code(Status::Ok);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetPalette)(JNIEnv* env, jobject, jlong handle, jintArray colours) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || colours == nullptr) return;
    const jsize n = env->GetArrayLength(colours);
    if (n != static_cast<jsize>(uv::timeline::kNativeColourCount)) return;
    uint32_t argb[uv::timeline::kNativeColourCount];
    env->GetIntArrayRegion(colours, 0, n, reinterpret_cast<jint*>(argb));
    h->renderer->setPalette(argb, static_cast<size_t>(n));
}

// May be called from the Kotlin text thread at any time; the bitmap is copied before this returns.
JNIEXPORT jint JNICALL JNI_FN(nativeLabelPut)(JNIEnv* env, jobject, jlong handle, jlong hash, jobject buffer, jint width, jint height,
                                              jboolean colour) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || buffer == nullptr || width <= 0 || height <= 0) return code(Status::InvalidArgument);
    const void* data = env->GetDirectBufferAddress(buffer);
    const jlong need = static_cast<jlong>(width) * height * 4;
    if (data == nullptr || env->GetDirectBufferCapacity(buffer) < need) return code(Status::InvalidArgument);
    h->renderer->putLabel(static_cast<uint64_t>(hash), width, height, colour == JNI_TRUE, static_cast<const uint8_t*>(data));
    return code(Status::Ok);
}

JNIEXPORT jint JNICALL JNI_FN(nativeLabelGeneration)(JNIEnv*, jobject, jlong handle) {
    TimelineHandle* h = from(handle);
    return h == nullptr ? 0 : static_cast<jint>(h->renderer->labelGeneration());
}

// Fills `out` with the hashes of text bitmaps the atlas evicted (taking them off its list); returns how many.
JNIEXPORT jint JNICALL JNI_FN(nativeLabelTakeEvicted)(JNIEnv* env, jobject, jlong handle, jlongArray out) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || out == nullptr) return 0;
    const jsize capacity = env->GetArrayLength(out);
    if (capacity <= 0) return 0;
    std::vector<uint64_t> taken(static_cast<size_t>(capacity));
    const size_t n = h->renderer->takeEvictedLabels(taken.data(), taken.size());
    if (n > 0) env->SetLongArrayRegion(out, 0, static_cast<jsize>(n), reinterpret_cast<const jlong*>(taken.data()));
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL JNI_FN(nativeScrollBy)(JNIEnv*, jobject, jlong handle, jfloat dx, jfloat dy) {
    if (TimelineHandle* h = from(handle)) h->renderer->scrollBy(dx, dy);
}

JNIEXPORT void JNICALL JNI_FN(nativeZoomBy)(JNIEnv*, jobject, jlong handle, jfloat factor, jfloat focusX) {
    if (TimelineHandle* h = from(handle)) h->renderer->zoomBy(factor, focusX);
}

JNIEXPORT void JNICALL JNI_FN(nativeFollowContent)(JNIEnv*, jobject, jlong handle) {
    if (TimelineHandle* h = from(handle)) h->renderer->followContent();
}

JNIEXPORT void JNICALL JNI_FN(nativeFitToContent)(JNIEnv*, jobject, jlong handle) {
    if (TimelineHandle* h = from(handle)) h->renderer->fitToContent();
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsAutoFit)(JNIEnv*, jobject, jlong handle) {
    TimelineHandle* h = from(handle);
    return (h != nullptr && h->renderer->isAutoFit()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeFling)(JNIEnv*, jobject, jlong handle, jfloat vx) {
    if (TimelineHandle* h = from(handle)) h->renderer->fling(vx);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetPlayhead)(JNIEnv*, jobject, jlong handle, jlong frame) {
    if (TimelineHandle* h = from(handle)) h->renderer->setPlayhead(frame);
}

JNIEXPORT void JNICALL JNI_FN(nativeEnsureVisible)(JNIEnv*, jobject, jlong handle, jlong frame) {
    if (TimelineHandle* h = from(handle)) h->renderer->ensureVisible(frame);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetDropHint)(JNIEnv*, jobject, jlong handle, jint kind, jint trackIndex, jlong startFrame,
                                                 jlong endFrame) {
    TimelineHandle* h = from(handle);
    if (h == nullptr) return;
    uv::timeline::DropHint hint;
    hint.kind = static_cast<uv::timeline::DropHintKind>(kind);
    hint.trackIndex = trackIndex;
    hint.startFrame = startFrame;
    hint.endFrame = endFrame;
    h->renderer->setDropHint(hint);
}

// The snap guide frame (negative for none) and the keys of the clips being dragged (null or empty for none).
JNIEXPORT void JNICALL JNI_FN(nativeSetDragOverlay)(JNIEnv* env, jobject, jlong handle, jlong snapGuideFrame, jlongArray clipKeys) {
    TimelineHandle* h = from(handle);
    if (h == nullptr) return;
    const jsize n = clipKeys == nullptr ? 0 : env->GetArrayLength(clipKeys);
    if (n <= 0) {
        h->renderer->setDragOverlay(snapGuideFrame, nullptr, 0);
        return;
    }
    std::vector<int64_t> keys(static_cast<size_t>(n));
    env->GetLongArrayRegion(clipKeys, 0, n, reinterpret_cast<jlong*>(keys.data()));
    h->renderer->setDragOverlay(snapGuideFrame, keys.data(), keys.size());
}

JNIEXPORT void JNICALL JNI_FN(nativeSetMarquee)(JNIEnv*, jobject, jlong handle, jboolean active, jfloat x0, jfloat y0, jfloat x1,
                                                jfloat y1) {
    if (TimelineHandle* h = from(handle)) h->renderer->setMarquee(active == JNI_TRUE, x0, y0, x1, y1);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetLaneDrag)(JNIEnv*, jobject, jlong handle, jint fromLane, jint toLane) {
    if (TimelineHandle* h = from(handle)) h->renderer->setLaneDrag(fromLane, toLane);
}

// Keys of the clips inside a view-pixel rectangle.
JNIEXPORT jlongArray JNICALL JNI_FN(nativeClipsInRect)(JNIEnv* env, jobject, jlong handle, jfloat x0, jfloat y0, jfloat x1, jfloat y1) {
    TimelineHandle* h = from(handle);
    if (h == nullptr) return nullptr;
    const std::vector<int64_t> keys = h->renderer->clipsInRect(x0, y0, x1, y1);
    jlongArray arr = env->NewLongArray(static_cast<jsize>(keys.size()));
    if (arr != nullptr && !keys.empty()) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(keys.size()), reinterpret_cast<const jlong*>(keys.data()));
    }
    return arr;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetLaneScale)(JNIEnv*, jobject, jlong handle, jfloat scale) {
    if (TimelineHandle* h = from(handle)) h->renderer->setLaneScale(scale);
}

// Returns {kind, trackIndex, clipKey, frame, index, dbTenths}; dbTenths is the gain under the finger in tenths of a dB,
// or kNoDb when the touch is not in a lane.
constexpr jlong kNoDb = INT64_MIN;

JNIEXPORT jlongArray JNICALL JNI_FN(nativeHitTest)(JNIEnv* env, jobject, jlong handle, jfloat x, jfloat y) {
    TimelineHandle* h = from(handle);
    if (h == nullptr) return nullptr;
    const auto r = h->renderer->hitTest(x, y);
    const jlong out[6] = {static_cast<jlong>(r.kind), r.trackIndex, r.clipKey, r.frame, r.index,
                          r.hasDb ? static_cast<jlong>(std::lround(r.db * 10.0f)) : kNoDb};
    jlongArray arr = env->NewLongArray(6);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, 6, out);
    return arr;
}

// Takes ownership of fd (a detached ParcelFileDescriptor fd).
JNIEXPORT jint JNICALL JNI_FN(nativeRequestWaveform)(JNIEnv* env, jobject, jlong handle, jlong assetKey, jint fd,
                                                     jstring cachePath) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || cachePath == nullptr || fd < 0) return code(Status::InvalidArgument);
    const char* chars = env->GetStringUTFChars(cachePath, nullptr);
    if (chars == nullptr) return code(Status::InvalidArgument);
    std::string path(chars);
    env->ReleaseStringUTFChars(cachePath, chars);
    h->waveforms->request(assetKey, fd, std::move(path));
    return code(Status::Ok);
}

}  // extern "C"
