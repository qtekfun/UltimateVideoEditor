// JNI bindings for the timeline canvas and waveform service. Bindings only, no business logic.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <atomic>
#include <memory>
#include <string>

#include "audio/waveform_service.h"
#include "core/error.h"
#include "jni/timeline_handle.h"
#include "timeline_view/timeline_renderer.h"
#include "timeline_view/timeline_snapshot.h"

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

JNIEXPORT void JNICALL JNI_FN(nativeScrollBy)(JNIEnv*, jobject, jlong handle, jfloat dx, jfloat dy) {
    if (TimelineHandle* h = from(handle)) h->renderer->scrollBy(dx, dy);
}

JNIEXPORT void JNICALL JNI_FN(nativeZoomBy)(JNIEnv*, jobject, jlong handle, jfloat factor, jfloat focusX) {
    if (TimelineHandle* h = from(handle)) h->renderer->zoomBy(factor, focusX);
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

JNIEXPORT void JNICALL JNI_FN(nativeSetLaneScale)(JNIEnv*, jobject, jlong handle, jfloat scale) {
    if (TimelineHandle* h = from(handle)) h->renderer->setLaneScale(scale);
}

// Returns {kind, trackIndex, clipKey, frame}.
JNIEXPORT jlongArray JNICALL JNI_FN(nativeHitTest)(JNIEnv* env, jobject, jlong handle, jfloat x, jfloat y) {
    TimelineHandle* h = from(handle);
    if (h == nullptr) return nullptr;
    const auto r = h->renderer->hitTest(x, y);
    const jlong out[4] = {static_cast<jlong>(r.kind), r.trackIndex, r.clipKey, r.frame};
    jlongArray arr = env->NewLongArray(4);
    if (arr != nullptr) env->SetLongArrayRegion(arr, 0, 4, out);
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
