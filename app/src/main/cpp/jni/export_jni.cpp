// JNI bindings for the offline exporter. Bindings only: no logic here.
#include <jni.h>
#include <unistd.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "decode/log.h"
#include "encode/export_engine.h"

namespace {

using uv::core::Status;
using uv::encode::ExportJob;
using uv::encode::ExportParams;
using uv::encode::VideoClip;

constexpr const char* kExceptionClass = "com/ultimatevideo/uveditor/engine/export/ExportException";
constexpr size_t kClipLongs = 6;  // start, duration, sourceIn, assetKey, layer, colorMode

void throwExport(JNIEnv* env, Status code, const std::string& message) {
    jclass cls = env->FindClass(kExceptionClass);
    if (cls == nullptr) return;  // a NoClassDefFoundError is already pending
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    jstring text = env->NewStringUTF(message.c_str());
    auto exception = static_cast<jthrowable>(env->NewObject(cls, ctor, static_cast<jint>(code), text));
    env->Throw(exception);
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(cls);
}

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
    jmethodID onProgress = nullptr;
    jmethodID onFinished = nullptr;

    void progress(int32_t permille) const {
        JNIEnv* env = envForCurrentThread(vm);
        if (env == nullptr) return;
        env->CallVoidMethod(listener, onProgress, static_cast<jint>(permille));
        if (env->ExceptionCheck()) env->ExceptionClear();  // a listener bug must not unwind native threads
    }

    void finished(Status status, const std::string& message) const {
        JNIEnv* env = envForCurrentThread(vm);
        if (env == nullptr) return;
        jstring text = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(listener, onFinished, static_cast<jint>(status), text);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(text);
    }
};

struct Handle {
    // `listener` outlives `job`: the job's thread may still report while it shuts down.
    std::unique_ptr<ListenerRef> listener;
    std::unique_ptr<ExportJob> job;
};

Handle* fromHandle(jlong handle) { return reinterpret_cast<Handle*>(handle); }

void closeAll(const std::vector<std::pair<int64_t, int>>& fds, int outputFd) {
    for (const auto& e : fds) {
        if (e.second >= 0) ::close(e.second);
    }
    if (outputFd >= 0) ::close(outputFd);
}

}  // namespace

extern "C" {

// Takes ownership of every descriptor passed in, whether or not it succeeds.
JNIEXPORT jlong JNICALL Java_com_ultimatevideo_uveditor_engine_export_NativeExport_nativeStart(
    JNIEnv* env, jobject /*thiz*/, jobject listener, jint width, jint height, jint fpsNum, jint fpsDen, jint projectFpsNum,
    jint projectFpsDen, jint codec, jint videoBitrate, jint audioBitrate, jlong totalFrames, jlongArray assetKeys, jintArray assetFds, jlongArray clips,
    jobject audioSnapshot, jint outputFd) {
    ExportParams params;
    params.width = width;
    params.height = height;
    params.fps = {fpsNum, fpsDen};
    params.projectFps = {projectFpsNum, projectFpsDen};
    params.codec = codec == 1 ? uv::encode::VideoCodec::Hevc : uv::encode::VideoCodec::H264;
    params.videoBitrate = videoBitrate;
    params.audioBitrate = audioBitrate;
    params.totalFrames = totalFrames;
    params.outputFd = outputFd;

    const jsize assetCount = env->GetArrayLength(assetKeys);
    if (env->GetArrayLength(assetFds) != assetCount) {
        throwExport(env, Status::InvalidArgument, "asset keys and descriptors differ in length");
        closeAll({}, outputFd);
        return 0;
    }
    std::vector<jlong> keys(static_cast<size_t>(assetCount));
    std::vector<jint> fds(static_cast<size_t>(assetCount));
    env->GetLongArrayRegion(assetKeys, 0, assetCount, keys.data());
    env->GetIntArrayRegion(assetFds, 0, assetCount, fds.data());
    for (size_t i = 0; i < keys.size(); ++i) params.assetFds.emplace_back(keys[i], fds[i]);

    const jsize clipLongs = env->GetArrayLength(clips);
    if (static_cast<size_t>(clipLongs) % kClipLongs != 0) {
        throwExport(env, Status::InvalidArgument, "malformed clip array");
        closeAll(params.assetFds, outputFd);
        return 0;
    }
    std::vector<jlong> flat(static_cast<size_t>(clipLongs));
    env->GetLongArrayRegion(clips, 0, clipLongs, flat.data());
    for (size_t i = 0; i + kClipLongs <= flat.size(); i += kClipLongs) {
        VideoClip c;
        c.startFrame = flat[i];
        c.durationFrames = flat[i + 1];
        c.sourceInFrame = flat[i + 2];
        c.assetKey = flat[i + 3];
        c.layer = static_cast<int32_t>(flat[i + 4]);
        c.colorMode = static_cast<int32_t>(flat[i + 5]);
        params.clips.push_back(c);
    }

    if (audioSnapshot != nullptr) {
        const void* data = env->GetDirectBufferAddress(audioSnapshot);
        const jlong size = env->GetDirectBufferCapacity(audioSnapshot);
        if (data == nullptr || size < 0) {
            throwExport(env, Status::InvalidArgument, "the audio snapshot must be a direct buffer");
            closeAll(params.assetFds, outputFd);
            return 0;
        }
        params.audioSnapshot.assign(static_cast<const uint8_t*>(data), static_cast<const uint8_t*>(data) + size);
    }

    auto handle = std::make_unique<Handle>();
    handle->listener = std::make_unique<ListenerRef>();
    env->GetJavaVM(&handle->listener->vm);
    handle->listener->listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    handle->listener->onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
    handle->listener->onFinished = env->GetMethodID(cls, "onFinished", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (handle->listener->onProgress == nullptr || handle->listener->onFinished == nullptr) {
        env->DeleteGlobalRef(handle->listener->listener);
        closeAll(params.assetFds, outputFd);
        return 0;  // NoSuchMethodError pending
    }

    ListenerRef* ref = handle->listener.get();
    handle->job = std::make_unique<ExportJob>(
        std::move(params), [ref](int32_t permille) { ref->progress(permille); },
        [ref](Status status, const std::string& message) { ref->finished(status, message); });
    handle->job->start();
    return reinterpret_cast<jlong>(handle.release());
}

JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_export_NativeExport_nativeCancel(JNIEnv* /*env*/,
                                                                                              jobject /*thiz*/,
                                                                                              jlong handle) {
    if (handle != 0) fromHandle(handle)->job->cancel();
}

// Joins the export thread: call off the main thread, and never from a listener callback.
JNIEXPORT void JNICALL Java_com_ultimatevideo_uveditor_engine_export_NativeExport_nativeDestroy(JNIEnv* env,
                                                                                               jobject /*thiz*/,
                                                                                               jlong handle) {
    if (handle == 0) return;
    std::unique_ptr<Handle> owned(fromHandle(handle));
    owned->job.reset();
    env->DeleteGlobalRef(owned->listener->listener);
}

}  // extern "C"
