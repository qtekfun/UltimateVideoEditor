// JNI bindings for the preview engine (decode + GLES compositor). Bindings only: no logic here.
#include <android/native_window_jni.h>
#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "core/layer_fx.h"
#include "decode/log.h"
#include "render/preview_engine.h"

namespace {

using uv::decode::Error;
using uv::decode::Rational;
using uv::decode::Status;
using uv::render::ColorMode;
using uv::render::OutputSpace;
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

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeSurfaceChanged(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    fromHandle(handle)->engine->surfaceChanged();
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeAttachScopeSurface(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        throwPreview(env, Status::InvalidArgument, "Scope surface is not valid");
        return;
    }
    Error error{Status::Ok, ""};
    const Status status = fromHandle(handle)->engine->attachScopeSurface(window, &error);
    ANativeWindow_release(window);
    if (status != Status::Ok) throwPreview(env, error.code, error.message);
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeDetachScopeSurface(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    fromHandle(handle)->engine->detachScopeSurface();
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeScopeSurfaceChanged(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    fromHandle(handle)->engine->scopeSurfaceChanged();
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeSetScopeMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint mode) {
    fromHandle(handle)->engine->setScopeMode(static_cast<int>(mode));
}

// Returns {width, height, durationFrames, fpsNum, fpsDen, colorTransfer, rotationDegrees}. Takes ownership of fd.
JNIEXPORT jlongArray JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeOpenAsset(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint assetId, jint fd, jint fpsNum, jint fpsDen) {
    auto opened = fromHandle(handle)->engine->openAsset(static_cast<uint32_t>(assetId), fd, Rational{fpsNum, fpsDen});
    if (!opened.ok()) {
        throwPreview(env, opened.error().code, opened.error().message);
        return nullptr;
    }
    const auto& info = opened.value();
    const jlong values[7] = {info.width, info.height, info.durationFrames, info.fps.num, info.fps.den,
                             info.colorTransfer, info.rotationDegrees};
    jlongArray result = env->NewLongArray(7);
    env->SetLongArrayRegion(result, 0, 7, values);
    return result;
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeCloseAsset(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint assetId) {
    fromHandle(handle)->engine->closeAsset(static_cast<uint32_t>(assetId));
}

namespace {
constexpr jsize kParamsPerLayer = 8;

// `ids` holds `idStride` longs per layer ({assetId, frame} and, for playback, the exclusive limit
// frame) and `params` {posX, posY, scaleX, scaleY, rotationDeg, opacity, direction}, both bottom to top.
// direction is +1 for a layer that plays forwards and -1 for one that plays backwards.
// A negative assetId -k is the title uploaded under key k (frame is ignored).
// Returns false after throwing if the arrays do not agree.
// `fx` (may be null) holds one effects/blend/mask blob per layer, see core/layer_fx.h.
bool parseScene(JNIEnv* env, jlongArray ids, jfloatArray params, jdoubleArray fx, jsize idStride,
                std::vector<uv::render::SceneLayer>* out) {
    const jsize layerCount = env->GetArrayLength(ids) / idStride;
    if (env->GetArrayLength(ids) != layerCount * idStride || env->GetArrayLength(params) != layerCount * kParamsPerLayer) {
        throwPreview(env, Status::InvalidArgument, "scene arrays do not match");
        return false;
    }
    std::vector<jlong> idValues(static_cast<size_t>(layerCount) * static_cast<size_t>(idStride));
    std::vector<jfloat> paramValues(static_cast<size_t>(layerCount) * kParamsPerLayer);
    if (layerCount > 0) {
        env->GetLongArrayRegion(ids, 0, layerCount * idStride, idValues.data());
        env->GetFloatArrayRegion(params, 0, layerCount * kParamsPerLayer, paramValues.data());
    }
    std::vector<uv::core::LayerFx> fxValues;
    {
        std::vector<jdouble> raw;
        if (fx != nullptr) {
            raw.resize(static_cast<size_t>(env->GetArrayLength(fx)));
            if (!raw.empty()) env->GetDoubleArrayRegion(fx, 0, static_cast<jsize>(raw.size()), raw.data());
        }
        if (!uv::core::parseSceneFx(raw.data(), raw.size(), static_cast<size_t>(layerCount), &fxValues)) {
            throwPreview(env, Status::InvalidArgument, "scene effects do not match the layers");
            return false;
        }
    }
    out->reserve(static_cast<size_t>(layerCount));
    for (jsize i = 0; i < layerCount; ++i) {
        uv::render::SceneLayer layer;
        layer.fx = std::move(fxValues[static_cast<size_t>(i)]);
        const jlong* id = &idValues[static_cast<size_t>(i) * static_cast<size_t>(idStride)];
        if (id[0] < 0) {
            layer.title = static_cast<uint32_t>(-id[0]);
        } else {
            layer.asset = static_cast<uint32_t>(id[0]);
        }
        layer.frame = id[1];
        if (idStride > 2) layer.limitFrame = id[2];
        const jfloat* p = &paramValues[static_cast<size_t>(i) * kParamsPerLayer];
        layer.transform = uv::render::LayerTransform{p[0], p[1], p[2], p[3], p[4], p[5]};
        layer.direction = p[6] < 0.0f ? -1 : 1;
        // -1 (or anything out of range) keeps the asset's own colour; 0..2 is a SourceTransfer override.
        layer.source = (p[7] >= 0.0f && p[7] <= 2.0f) ? static_cast<int32_t>(p[7]) : -1;
        out->push_back(layer);
    }
    return true;
}
}  // namespace

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeSetScene(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint canvasW, jint canvasH, jlongArray ids, jfloatArray params,
    jdoubleArray fx) {
    std::vector<uv::render::SceneLayer> layers;
    if (!parseScene(env, ids, params, fx, 2, &layers)) return;
    fromHandle(handle)->engine->setScene(canvasW, canvasH, std::move(layers));
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativePlayScene(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint canvasW, jint canvasH, jlongArray ids, jfloatArray params,
    jdoubleArray fx, jint fpsNum, jint fpsDen) {
    std::vector<uv::render::SceneLayer> layers;
    if (!parseScene(env, ids, params, fx, 3, &layers)) return;
    fromHandle(handle)->engine->playScene(canvasW, canvasH, std::move(layers), uv::decode::Rational{fpsNum, fpsDen});
}

// `pixels` is a direct buffer of width * height * 4 bytes (premultiplied RGBA, top row first); it is copied.
JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeUploadTitle(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint key, jint width, jint height, jobject pixels) {
    const void* data = pixels == nullptr ? nullptr : env->GetDirectBufferAddress(pixels);
    const jlong capacity = pixels == nullptr ? 0 : env->GetDirectBufferCapacity(pixels);
    const int64_t needed = static_cast<int64_t>(width) * height * 4;
    if (key <= 0 || width <= 0 || height <= 0 || data == nullptr || capacity < needed) {
        throwPreview(env, Status::InvalidArgument, "title pixels do not match the given size");
        return;
    }
    const auto* bytes = static_cast<const uint8_t*>(data);
    fromHandle(handle)->engine->uploadTitle(static_cast<uint32_t>(key), width, height,
                                            std::vector<uint8_t>(bytes, bytes + needed));
}

// `rgb` is a direct float buffer of size^3 * 3 values (red varying fastest); it is copied.
JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeUploadLut(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint key, jint size, jobject rgb) {
    const void* data = rgb == nullptr ? nullptr : env->GetDirectBufferAddress(rgb);
    const jlong capacity = rgb == nullptr ? 0 : env->GetDirectBufferCapacity(rgb);
    const int64_t count = static_cast<int64_t>(size) * size * size * 3;
    if (key <= 0 || size < 2 || size > 65 || data == nullptr || capacity < count * static_cast<int64_t>(sizeof(float))) {
        throwPreview(env, Status::InvalidArgument, "LUT data does not match the given size");
        return;
    }
    const auto* floats = static_cast<const float*>(data);
    fromHandle(handle)->engine->uploadLut(static_cast<uint32_t>(key), size, std::vector<float>(floats, floats + count));
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeReleaseLut(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint key) {
    fromHandle(handle)->engine->releaseLut(static_cast<uint32_t>(key));
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeReleaseTitle(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint key) {
    fromHandle(handle)->engine->releaseTitle(static_cast<uint32_t>(key));
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

JNIEXPORT jint JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeSetOutputSpace(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint space) {
    const OutputSpace requested = space == 1 ? OutputSpace::Hlg2020 : OutputSpace::Sdr709;
    return static_cast<jint>(fromHandle(handle)->engine->setOutputSpace(requested));
}

JNIEXPORT jint JNICALL Java_com_ultimatevideo_uveditor_engine_preview_NativePreview_nativeGetOutputSpace(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jint>(fromHandle(handle)->engine->outputSpace());
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
