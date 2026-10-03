// JNI bindings for audio playback. Bindings only, no business logic. Errors are returned as
// integer codes (a uv::core::Status value, or audio::kResultDeviceError) and mapped to typed
// Kotlin exceptions on the other side; nothing is swallowed here.
#include <jni.h>
#include <unistd.h>

#include <vector>

#include "audio/audio_engine.h"

namespace {

using uv::audio::AudioEngine;

AudioEngine* from(jlong h) { return reinterpret_cast<AudioEngine*>(h); }

}  // namespace

extern "C" {

#define JNI_FN(name) Java_com_ultimatevideo_uveditor_engine_audio_NativeAudio_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new AudioEngine());
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv*, jobject, jlong handle) { delete from(handle); }

JNIEXPORT jint JNICALL JNI_FN(nativeStart)(JNIEnv*, jobject, jlong handle, jboolean offline) {
    return from(handle)->start(offline == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv*, jobject, jlong handle) { from(handle)->stop(); }

JNIEXPORT jint JNICALL JNI_FN(nativeSetAssetFd)(JNIEnv*, jobject, jlong handle, jlong assetKey, jint fd) {
    return from(handle)->setAssetFd(assetKey, fd);
}

JNIEXPORT void JNICALL JNI_FN(nativeRemoveAsset)(JNIEnv*, jobject, jlong handle, jlong assetKey) {
    from(handle)->removeAsset(assetKey);
}

JNIEXPORT jint JNICALL JNI_FN(nativeSetSnapshot)(JNIEnv* env, jobject, jlong handle, jobject buffer, jint size) {
    auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr || size < 0 || env->GetDirectBufferCapacity(buffer) < size) {
        return static_cast<jint>(uv::core::Status::InvalidArgument);
    }
    return from(handle)->setSnapshot(data, static_cast<size_t>(size));
}

JNIEXPORT void JNICALL JNI_FN(nativePlay)(JNIEnv*, jobject, jlong handle) { from(handle)->play(); }

JNIEXPORT void JNICALL JNI_FN(nativePause)(JNIEnv*, jobject, jlong handle) { from(handle)->pause(); }

JNIEXPORT jint JNICALL JNI_FN(nativeSeek)(JNIEnv*, jobject, jlong handle, jlong frame) {
    return from(handle)->seekFrame(frame);
}

JNIEXPORT jlong JNICALL JNI_FN(nativePositionFrame)(JNIEnv*, jobject, jlong handle) {
    return from(handle)->positionFrame();
}

JNIEXPORT jlong JNICALL JNI_FN(nativePositionSamples)(JNIEnv*, jobject, jlong handle) {
    return from(handle)->positionSamples();
}

// [sampleRate, framesPerBurst, bufferSizeFrames, exclusive, lowLatency, api, latencyMicros,
//  xruns, underrunBlocks, deviceId]
JNIEXPORT jlongArray JNICALL JNI_FN(nativeStats)(JNIEnv* env, jobject, jlong handle) {
    const uv::audio::AudioStats s = from(handle)->stats();
    const jlong values[] = {s.sampleRate, s.framesPerBurst, s.bufferSizeFrames, s.exclusive,   s.lowLatency,
                            s.api,        s.latencyMicros,  s.xruns,            s.underrunBlocks, s.deviceId};
    jlongArray out = env->NewLongArray(static_cast<jsize>(std::size(values)));
    if (out != nullptr) env->SetLongArrayRegion(out, 0, static_cast<jsize>(std::size(values)), values);
    return out;
}

// Flat quadruples: [kind, clipKey, status, count]*
JNIEXPORT jlongArray JNICALL JNI_FN(nativePollFaults)(JNIEnv* env, jobject, jlong handle) {
    std::vector<uv::audio::AudioFault> faults;
    from(handle)->pollFaults(&faults);
    std::vector<jlong> flat;
    flat.reserve(faults.size() * 4);
    for (const auto& f : faults) {
        flat.push_back(static_cast<jlong>(f.kind));
        flat.push_back(f.clipKey);
        flat.push_back(static_cast<jlong>(f.status));
        flat.push_back(f.count);
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(flat.size()));
    if (out != nullptr && !flat.empty()) env->SetLongArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    return out;
}

// Offline mode (tests/export): renders into `out` and returns the playhead in samples.
JNIEXPORT jlong JNICALL JNI_FN(nativeRenderOffline)(JNIEnv* env, jobject, jlong handle, jfloatArray out, jint frames) {
    if (out == nullptr || frames <= 0 || env->GetArrayLength(out) < frames * 2) return -1;
    jfloat* dst = env->GetFloatArrayElements(out, nullptr);
    if (dst == nullptr) return -1;
    const jlong pos = from(handle)->renderOffline(dst, frames);
    env->ReleaseFloatArrayElements(out, dst, 0);
    return pos;
}

}  // extern "C"
