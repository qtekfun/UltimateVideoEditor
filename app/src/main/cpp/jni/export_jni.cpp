// JNI bindings for the offline exporter. Bindings only: no logic here.
#include <jni.h>
#include <unistd.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "core/layer_fx.h"
#include "decode/log.h"
#include "encode/export_engine.h"

namespace {

using uv::core::Status;
using uv::encode::ExportJob;
using uv::encode::ExportParams;
using uv::encode::VideoClip;

constexpr const char* kExceptionClass = "com/ultimatevideo/uveditor/engine/export/ExportException";
constexpr size_t kClipLongs = 9;     // start, duration, sourceIn, assetKey, layer, colorMode, lane, fadeIn, titleKey
constexpr size_t kTitleInts = 3;     // key, width, height per title
constexpr size_t kClipDoubles = 6;   // posX, posY, scaleX, scaleY, rotationDeg, opacity
constexpr size_t kKeyClipLongs = 2;  // keyframe origin frame, keyframe count per clip
constexpr size_t kKeyLongs = 2;      // frame, interpolation per keyframe
constexpr size_t kKeyDoubles = 6;    // the same six pose values per keyframe
constexpr size_t kSourceClipLongs = 2;  // reverse flag, source table length per clip

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
    jint projectFpsDen, jint canvasWidth, jint canvasHeight, jint codec, jint videoBitrate, jint audioBitrate,
    jlong totalFrames, jlongArray assetKeys, jintArray assetFds, jlongArray clips, jdoubleArray transforms,
    jlongArray keyClips, jlongArray keyFrames, jdoubleArray keyValues, jdoubleArray fx, jlongArray sourceClips,
    jlongArray sourceTable, jintArray titleMeta, jobjectArray titlePixels, jobject audioSnapshot, jint outputFd) {
    ExportParams params;
    params.width = width;
    params.height = height;
    params.fps = {fpsNum, fpsDen};
    params.projectFps = {projectFpsNum, projectFpsDen};
    params.canvasWidth = canvasWidth;
    params.canvasHeight = canvasHeight;
    // `codec` is 0 (H.264) or 1 (HEVC), plus 0x100 for an HLG (HEVC Main10) export.
    params.codec = (codec & 0xFF) == 1 ? uv::encode::VideoCodec::Hevc : uv::encode::VideoCodec::H264;
    params.hdr = (codec & 0x100) != 0;
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
    const size_t clipCount = static_cast<size_t>(clipLongs) / kClipLongs;
    if (static_cast<size_t>(env->GetArrayLength(transforms)) != clipCount * kClipDoubles) {
        throwExport(env, Status::InvalidArgument, "clip transforms do not match the clips");
        closeAll(params.assetFds, outputFd);
        return 0;
    }
    std::vector<jlong> flat(static_cast<size_t>(clipLongs));
    env->GetLongArrayRegion(clips, 0, clipLongs, flat.data());
    std::vector<jdouble> xf(clipCount * kClipDoubles);
    env->GetDoubleArrayRegion(transforms, 0, static_cast<jsize>(xf.size()), xf.data());
    for (size_t n = 0; n < clipCount; ++n) {
        const size_t i = n * kClipLongs;
        const size_t t = n * kClipDoubles;
        VideoClip c;
        c.startFrame = flat[i];
        c.durationFrames = flat[i + 1];
        c.sourceInFrame = flat[i + 2];
        c.assetKey = flat[i + 3];
        c.layer = static_cast<int32_t>(flat[i + 4]);
        c.colorMode = static_cast<int32_t>(flat[i + 5]);
        c.posX = xf[t];
        c.posY = xf[t + 1];
        c.scaleX = xf[t + 2];
        c.scaleY = xf[t + 3];
        c.rotationDeg = xf[t + 4];
        c.opacity = xf[t + 5];
        c.lane = static_cast<int32_t>(flat[i + 6]);
        c.fadeInFrames = flat[i + 7];
        c.titleKey = static_cast<uint32_t>(flat[i + 8]);
        params.clips.push_back(c);
    }

    // Keyframes: `keyClips` holds {origin frame, count} per clip, `keyFrames` {frame, interpolation}
    // per key and `keyValues` six pose values per key, all keys of all clips in clip order.
    const jsize keyClipLongs = keyClips == nullptr ? 0 : env->GetArrayLength(keyClips);
    const jsize keyLongs = keyFrames == nullptr ? 0 : env->GetArrayLength(keyFrames);
    const jsize keyDoubles = keyValues == nullptr ? 0 : env->GetArrayLength(keyValues);
    const size_t keyCount = static_cast<size_t>(keyLongs) / kKeyLongs;
    if (static_cast<size_t>(keyClipLongs) != clipCount * kKeyClipLongs || static_cast<size_t>(keyLongs) % kKeyLongs != 0 ||
        static_cast<size_t>(keyDoubles) != keyCount * kKeyDoubles) {
        throwExport(env, Status::InvalidArgument, "clip keyframes do not match the clips");
        closeAll(params.assetFds, outputFd);
        return 0;
    }
    if (clipCount > 0) {
        std::vector<jlong> perClip(static_cast<size_t>(keyClipLongs));
        std::vector<jlong> keyMeta(static_cast<size_t>(keyLongs));
        std::vector<jdouble> keyPose(static_cast<size_t>(keyDoubles));
        env->GetLongArrayRegion(keyClips, 0, keyClipLongs, perClip.data());
        if (keyLongs > 0) env->GetLongArrayRegion(keyFrames, 0, keyLongs, keyMeta.data());
        if (keyDoubles > 0) env->GetDoubleArrayRegion(keyValues, 0, keyDoubles, keyPose.data());
        size_t next = 0;
        for (size_t n = 0; n < clipCount; ++n) {
            VideoClip& c = params.clips[n];
            c.keyOriginFrame = perClip[n * kKeyClipLongs];
            const jlong count = perClip[n * kKeyClipLongs + 1];
            if (count < 0 || next + static_cast<size_t>(count) > keyCount) {
                throwExport(env, Status::InvalidArgument, "clip keyframe counts exceed the keyframes given");
                closeAll(params.assetFds, outputFd);
                return 0;
            }
            for (jlong k = 0; k < count; ++k, ++next) {
                uv::core::Keyframe key;
                key.frame = keyMeta[next * kKeyLongs];
                key.interpolation = static_cast<uv::core::Interpolation>(keyMeta[next * kKeyLongs + 1]);
                const size_t v = next * kKeyDoubles;
                key.pose = {keyPose[v], keyPose[v + 1], keyPose[v + 2], keyPose[v + 3], keyPose[v + 4], keyPose[v + 5]};
                c.keyframes.push_back(key);
            }
        }
    }

    // Retimed clips: `sourceClips` holds {reverse flag, table length} per clip and `sourceTable` the
    // source frame of every project frame of those clips, concatenated in clip order.
    const jsize sourceClipLongs = sourceClips == nullptr ? 0 : env->GetArrayLength(sourceClips);
    const jsize sourceLongs = sourceTable == nullptr ? 0 : env->GetArrayLength(sourceTable);
    if (static_cast<size_t>(sourceClipLongs) != clipCount * kSourceClipLongs) {
        throwExport(env, Status::InvalidArgument, "clip source tables do not match the clips");
        closeAll(params.assetFds, outputFd);
        return 0;
    }
    if (clipCount > 0) {
        std::vector<jlong> perClip(static_cast<size_t>(sourceClipLongs));
        std::vector<jlong> table(static_cast<size_t>(sourceLongs));
        env->GetLongArrayRegion(sourceClips, 0, sourceClipLongs, perClip.data());
        if (sourceLongs > 0) env->GetLongArrayRegion(sourceTable, 0, sourceLongs, table.data());
        size_t next = 0;
        for (size_t n = 0; n < clipCount; ++n) {
            VideoClip& c = params.clips[n];
            c.reverse = perClip[n * kSourceClipLongs] != 0;
            const jlong length = perClip[n * kSourceClipLongs + 1];
            if (length < 0 || next + static_cast<size_t>(length) > table.size()) {
                throwExport(env, Status::InvalidArgument, "clip source table lengths exceed the table given");
                closeAll(params.assetFds, outputFd);
                return 0;
            }
            const auto first = table.begin() + static_cast<std::ptrdiff_t>(next);
            c.sourceTable.assign(first, first + static_cast<std::ptrdiff_t>(length));
            next += static_cast<size_t>(length);
        }
    }

    // Effects, blend mode and masks: one blob per clip in clip order (core/layer_fx.h); null = all plain.
    {
        std::vector<jdouble> raw;
        if (fx != nullptr) {
            raw.resize(static_cast<size_t>(env->GetArrayLength(fx)));
            if (!raw.empty()) env->GetDoubleArrayRegion(fx, 0, static_cast<jsize>(raw.size()), raw.data());
        }
        std::vector<uv::core::LayerFx> parsed;
        if (!uv::core::parseSceneFx(raw.data(), raw.size(), clipCount, &parsed)) {
            throwExport(env, Status::InvalidArgument, "clip effects do not match the clips");
            closeAll(params.assetFds, outputFd);
            return 0;
        }
        for (size_t n = 0; n < clipCount; ++n) params.clips[n].fx = std::move(parsed[n]);
    }

    // Titles: `titleMeta` holds {key, width, height} per title and `titlePixels` one direct
    // premultiplied RGBA buffer each; the pixels are copied.
    const jsize metaLength = titleMeta == nullptr ? 0 : env->GetArrayLength(titleMeta);
    const jsize pixelCount = titlePixels == nullptr ? 0 : env->GetArrayLength(titlePixels);
    if (static_cast<size_t>(metaLength) != static_cast<size_t>(pixelCount) * kTitleInts) {
        throwExport(env, Status::InvalidArgument, "title descriptions do not match the title images");
        closeAll(params.assetFds, outputFd);
        return 0;
    }
    if (pixelCount > 0) {
        std::vector<jint> meta(static_cast<size_t>(metaLength));
        env->GetIntArrayRegion(titleMeta, 0, metaLength, meta.data());
        for (jsize n = 0; n < pixelCount; ++n) {
            const jint key = meta[static_cast<size_t>(n) * kTitleInts];
            const jint w = meta[static_cast<size_t>(n) * kTitleInts + 1];
            const jint h = meta[static_cast<size_t>(n) * kTitleInts + 2];
            jobject buffer = env->GetObjectArrayElement(titlePixels, n);
            const void* data = buffer == nullptr ? nullptr : env->GetDirectBufferAddress(buffer);
            const jlong capacity = buffer == nullptr ? 0 : env->GetDirectBufferCapacity(buffer);
            const int64_t needed = static_cast<int64_t>(w) * h * 4;
            const bool valid = key > 0 && w > 0 && h > 0 && data != nullptr && capacity >= needed;
            if (valid) {
                uv::encode::TitleImage image;
                image.key = static_cast<uint32_t>(key);
                image.width = w;
                image.height = h;
                const auto* bytes = static_cast<const uint8_t*>(data);
                image.rgba.assign(bytes, bytes + needed);
                params.titles.push_back(std::move(image));
            }
            if (buffer != nullptr) env->DeleteLocalRef(buffer);
            if (!valid) {
                throwExport(env, Status::InvalidArgument, "a title image is not a direct RGBA buffer of the given size");
                closeAll(params.assetFds, outputFd);
                return 0;
            }
        }
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
