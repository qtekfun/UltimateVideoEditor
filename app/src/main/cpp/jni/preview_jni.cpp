// JNI bindings for the preview engine (decode + GLES compositor). Bindings only: no logic here.
#include <android/native_window_jni.h>
#include <jni.h>

#include <memory>
#include <string>

#include "decode/log.h"
#include "render/preview_engine.h"

namespace {

using uv::decode::Error;
using uv::decode::Rational;
using uv::decode::Status;
using uv::render::ColorMode;
using uv::render::PreviewEngine;

constexpr const char* kExceptionClass = "com/ultimatevideo/uveditor/engine/preview/PreviewException";

void throwPreview(JNIEnv* env, Status code, const std::string& message) {
    jclass cls = env->FindClass(kExceptionClass);
    if (cls == nullptr) return;  // a NoClassDefFoundError is already pending
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    jstring text = env->NewStringUTF(message.c_str());
    auto exception = static_cast<jthrowable>(env->NewObject(cls, ctor, static_cast<jint>(code), text));
    env->Throw(exception);
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(cls);
}

// Detaches a natively created thread from the VM when the thread exits.
struct ThreadDetacher {
    JavaVM* vm = nullptr;
    ~ThreadDetacher() {
        if (vm != nullptr) vm->DetachCurrentThread();
    }
};

JNIEnv* envForCurrentThread(JavaVM* vm) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    thread_local ThreadDetacher detacher;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    detacher.vm = vm;
    return env;
}

struct ListenerRef {
    JavaVM* vm = nullptr;
    jobject listener = nullptr;  // global ref
    jmethodID onError = nullptr;

    void notifyError(const Error& error) const {
        JNIEnv* env = envForCurrentThread(vm);
        if (env == nullptr) return;
        jstring text = env->NewStringUTF(error.message.c_str());
        env->CallVoidMethod(listener, onError, static_cast<jint>(error.code), text);
        if (env->ExceptionCheck()) env->ExceptionClear();  // never let a listener bug unwind native threads
        env->DeleteLocalRef(text);
    }
};

struct Handle {
    // `listener` outlives `engine`: native threads may still report while the engine shuts down.
    std::unique_ptr<ListenerRef> listener;
    std::unique_ptr<PreviewEngine> engine;
};

Handle* fromHandle(jlong handle) { return reinterpret_cast<Handle*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jobject listener, jlong budgetBytes) {
    auto handle = std::make_unique<Handle>();
    handle->listener = std::make_unique<ListenerRef>();
    env->GetJavaVM(&handle->listener->vm);
    handle->listener->listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    handle->listener->onError = env->GetMethodID(cls, "onError", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (handle->listener->onError == nullptr) {
        env->DeleteGlobalRef(handle->listener->listener);
        return 0;  // NoSuchMethodError pending
    }

    ListenerRef* ref = handle->listener.get();
    auto created = PreviewEngine::create(static_cast<size_t>(budgetBytes),
                                         [ref](const Error& e) { ref->notifyError(e); });
    if (!created.ok()) {
        env->DeleteGlobalRef(ref->listener);
        throwPreview(env, created.error().code, created.error().message);
        return 0;
    }
    handle->engine = std::move(created.value());
    return reinterpret_cast<jlong>(handle.release());
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    std::unique_ptr<Handle> h(fromHandle(handle));
    if (!h) return;
    h->engine.reset();  // stops every native thread before the listener goes away
    env->DeleteGlobalRef(h->listener->listener);
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeAttachSurface(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        throwPreview(env, Status::InvalidArgument, "Surface is not valid");
        return;
    }
    Error error{Status::Ok, ""};
    const Status status = fromHandle(handle)->engine->attachSurface(window, &error);
    ANativeWindow_release(window);
    if (status != Status::Ok) throwPreview(env, error.code, error.message);
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeDetachSurface(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    fromHandle(handle)->engine->detachSurface();
}

// Returns {width, height, durationFrames, fpsNum, fpsDen, colorTransfer}. Takes ownership of fd.
JNIEXPORT jlongArray JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeOpenAsset(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint assetId, jint fd, jint fpsNum, jint fpsDen) {
    auto opened = fromHandle(handle)->engine->openAsset(static_cast<uint32_t>(assetId), fd, Rational{fpsNum, fpsDen});
    if (!opened.ok()) {
        throwPreview(env, opened.error().code, opened.error().message);
        return nullptr;
    }
    const auto& info = opened.value();
    const jlong values[6] = {info.width, info.height, info.durationFrames, info.fps.num, info.fps.den,
                             info.colorTransfer};
    jlongArray result = env->NewLongArray(6);
    env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeCloseAsset(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint assetId) {
    fromHandle(handle)->engine->closeAsset(static_cast<uint32_t>(assetId));
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeSeek(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint assetId, jlong frame) {
    fromHandle(handle)->engine->seek(static_cast<uint32_t>(assetId), frame);
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativePlay(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint assetId, jlong startFrame) {
    fromHandle(handle)->engine->play(static_cast<uint32_t>(assetId), startFrame);
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativePause(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    fromHandle(handle)->engine->pause();
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeSetColorMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint assetId, jint mode) {
    fromHandle(handle)->engine->setColorMode(static_cast<uint32_t>(assetId), static_cast<ColorMode>(mode));
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeSetCacheBudget(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong bytes) {
    fromHandle(handle)->engine->setCacheBudget(static_cast<size_t>(bytes));
}

// Returns {cacheUsedBytes, cacheBudgetBytes, cacheEntries, framesDrawn, stalls, framesDecoded}.
JNIEXPORT jlongArray JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeStats(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    const auto s = fromHandle(handle)->engine->stats();
    const jlong values[6] = {s.cacheUsedBytes, s.cacheBudgetBytes, s.cacheEntries,
                             s.framesDrawn,    s.stalls,           s.framesDecoded};
    jlongArray result = env->NewLongArray(6);
    env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

}  // extern "C"
