// JNI bindings for the stabiliser. Bindings only, no business logic. Kotlin: engine/stabilise/NativeStabiliser.kt.
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <memory>
#include <string>

#include "core/error.h"
#include "decode/frame_rate.h"
#include "stabilise/path.h"
#include "stabilise/stab_registry.h"
#include "stabilise/stab_service.h"

#define LOG_TAG "uv_stab_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using uv::core::Status;
using uv::stab::StabService;

namespace {

jint code(Status s) { return static_cast<jint>(s); }

StabService* service(jlong handle) { return reinterpret_cast<StabService*>(handle); }

std::string toString(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

#define JNI_FN(name) Java_com_qtekfun_ultimatevideoeditor_engine_stabilise_NativeStabiliser_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new StabService());
}

// Cancels any running job and waits for it to stop.
JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete service(handle);
}

// Takes ownership of fd (a detached ParcelFileDescriptor fd), closed when the job ends. Returns a Status code.
JNIEXPORT jint JNICALL JNI_FN(nativeStart)(JNIEnv* env, jobject /*thiz*/, jlong handle, jint fd, jlong startUs, jlong endUs,
                                           jstring cachePath) {
    StabService* s = service(handle);
    const std::string path = toString(env, cachePath);
    if (s == nullptr || path.empty()) {
        if (fd >= 0) ::close(fd);
        return code(Status::InvalidArgument);
    }
    return code(s->start(fd, startUs, endUs, path));
}

JNIEXPORT void JNICALL JNI_FN(nativeCancel)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (StabService* s = service(handle)) s->cancel();
}

// Packs the job state: bits 0..7 state (0 idle, 1 running, 2 done, 3 failed, 4 cancelled), bits 8..23 progress in
// permille, bits 24..31 the Status code of a failure.
JNIEXPORT jlong JNICALL JNI_FN(nativePoll)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    StabService* s = service(handle);
    if (s == nullptr) return 0;
    const StabService::Poll p = s->poll();
    return static_cast<jlong>(static_cast<int>(p.state)) | (static_cast<jlong>(p.permille & 0xFFFF) << 8) |
           (static_cast<jlong>(static_cast<int>(p.error) & 0xFF) << 24);
}

// Builds the correction table for `key` from the analysis cache at `cachePath` and registers it with the
// compositor (preview and exporter). fpsNum/fpsDen is the frame rate the decoder numbers frames with (the project's).
JNIEXPORT jint JNICALL JNI_FN(nativeRegister)(JNIEnv* env, jobject /*thiz*/, jint key, jstring cachePath, jint fpsNum, jint fpsDen,
                                              jfloat strength, jint crop) {
    if (key <= 0 || crop < 0 || crop > 2) return code(Status::InvalidArgument);
    return code(uv::stab::registerFromCache(static_cast<uint32_t>(key), toString(env, cachePath), uv::decode::Rational{fpsNum, fpsDen},
                                            strength, static_cast<uv::stab::CropLevel>(crop)));
}

JNIEXPORT void JNICALL JNI_FN(nativeRelease)(JNIEnv* /*env*/, jobject /*thiz*/, jint key) {
    if (key > 0) uv::stab::StabRegistry::instance().remove(static_cast<uint32_t>(key));
}

JNIEXPORT void JNICALL JNI_FN(nativeReleaseAll)(JNIEnv* /*env*/, jobject /*thiz*/) { uv::stab::StabRegistry::instance().clear(); }

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsRegistered)(JNIEnv* /*env*/, jobject /*thiz*/, jint key) {
    return key > 0 && uv::stab::StabRegistry::instance().has(static_cast<uint32_t>(key)) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
