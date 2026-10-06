// JNI bindings for timeline thumbnails. Bindings only, no business logic.
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <memory>
#include <mutex>
#include <string>

#include "core/error.h"
#include "jni/timeline_handle.h"
#include "thumbnail/thumbnail_service.h"

#define LOG_TAG "uv_thumb_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using uv::core::Status;

struct ThumbBridge {
    std::mutex mu;
    bool closed = false;
    JavaVM* vm = nullptr;
    jobject listener = nullptr;  // global ref, released by destroyThumbnails
    jmethodID onError = nullptr;
    uv::timeline::TimelineRenderer* renderer = nullptr;
};

void destroyThumbnails(JNIEnv* env, TimelineHandle* h) {
    if (h->thumbBridge) {
        std::lock_guard<std::mutex> lock(h->thumbBridge->mu);
        h->thumbBridge->closed = true;
        h->thumbBridge->renderer = nullptr;
        if (h->thumbBridge->listener != nullptr) env->DeleteGlobalRef(h->thumbBridge->listener);
        h->thumbBridge->listener = nullptr;
    }
    h->thumbnails.reset();  // joins the worker unless the render thread still holds a reference
    h->thumbBridge.reset();
}

namespace {

TimelineHandle* from(jlong h) { return reinterpret_cast<TimelineHandle*>(h); }
jint code(Status s) { return static_cast<jint>(s); }

void notifyError(const std::shared_ptr<ThumbBridge>& bridge, int64_t assetKey, Status status, const std::string& detail) {
    std::lock_guard<std::mutex> lock(bridge->mu);
    if (bridge->closed || bridge->listener == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (bridge->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (bridge->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("could not attach thumbnail worker to the JVM");
            return;
        }
        attached = true;
    }
    jstring text = env->NewStringUTF(detail.c_str());
    env->CallVoidMethod(bridge->listener, bridge->onError, static_cast<jlong>(assetKey), code(status), text);
    if (text != nullptr) env->DeleteLocalRef(text);
    if (env->ExceptionCheck()) {
        LOGE("thumbnail listener threw");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) bridge->vm->DetachCurrentThread();
}

void notifyTiles(const std::shared_ptr<ThumbBridge>& bridge) {
    std::lock_guard<std::mutex> lock(bridge->mu);
    if (!bridge->closed && bridge->renderer != nullptr) bridge->renderer->invalidate();
}

}  // namespace

extern "C" {

#define JNI_FN(name) Java_com_ultimatevideo_uveditor_engine_timeline_NativeThumbnails_##name

// Creates the thumbnail service for a timeline handle and connects it to the renderer.
JNIEXPORT jint JNICALL JNI_FN(nativeAttach)(JNIEnv* env, jobject /*thiz*/, jlong handle, jobject listener) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || listener == nullptr) return code(Status::InvalidArgument);
    if (h->thumbnails) return code(Status::Ok);  // already attached

    auto bridge = std::make_shared<ThumbBridge>();
    if (env->GetJavaVM(&bridge->vm) != JNI_OK) return code(Status::InvalidArgument);
    jclass cls = env->GetObjectClass(listener);
    bridge->onError = env->GetMethodID(cls, "onThumbnailError", "(JILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (bridge->onError == nullptr) {
        LOGE("listener lacks onThumbnailError(long,int,String)");
        return code(Status::InvalidArgument);  // NoSuchMethodError is pending for Kotlin
    }
    bridge->listener = env->NewGlobalRef(listener);
    bridge->renderer = h->renderer.get();

    uv::thumb::ThumbnailService::Listener callbacks;
    callbacks.onTilesReady = [bridge] { notifyTiles(bridge); };
    callbacks.onError = [bridge](int64_t asset, Status st, const std::string& detail) { notifyError(bridge, asset, st, detail); };
    h->thumbBridge = bridge;
    h->thumbnails = std::make_shared<uv::thumb::ThumbnailService>(std::move(callbacks));
    h->renderer->setThumbnails(h->thumbnails);
    return code(Status::Ok);
}

// Takes ownership of fd (a detached ParcelFileDescriptor fd), closed when the service is destroyed.
JNIEXPORT jint JNICALL JNI_FN(nativeRegisterAsset)(JNIEnv* env, jobject /*thiz*/, jlong handle, jlong assetKey, jint fd,
                                                   jstring cacheDir) {
    TimelineHandle* h = from(handle);
    if (h == nullptr || !h->thumbnails || cacheDir == nullptr || fd < 0) {
        if (fd >= 0) ::close(fd);
        return code(Status::InvalidArgument);
    }
    const char* chars = env->GetStringUTFChars(cacheDir, nullptr);
    if (chars == nullptr) {
        ::close(fd);
        return code(Status::InvalidArgument);
    }
    std::string dir(chars);
    env->ReleaseStringUTFChars(cacheDir, chars);
    h->thumbnails->registerAsset(assetKey, fd, std::move(dir));
    return code(Status::Ok);
}

}  // extern "C"
