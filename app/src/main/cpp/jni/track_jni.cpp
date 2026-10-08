// JNI bindings for motion tracking. Bindings only, no business logic. Kotlin: engine/track/NativeMotionTracker.kt.
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <string>

#include "core/error.h"
#include "track/track_service.h"

using uv::core::Status;
using uv::track::NormBox;
using uv::track::TrackService;

namespace {

jint code(Status s) { return static_cast<jint>(s); }

TrackService* service(jlong handle) { return reinterpret_cast<TrackService*>(handle); }

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

#define JNI_FN(name) Java_com_qtekfun_ultimatevideoeditor_engine_track_NativeMotionTracker_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(JNIEnv* /*env*/, jobject /*thiz*/) { return reinterpret_cast<jlong>(new TrackService()); }

// Cancels any running job and waits for it to stop.
JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) { delete service(handle); }

// Takes ownership of fd (a detached ParcelFileDescriptor fd), closed when the job ends. The box is in fractions of
// the upright frame. Returns a Status code.
JNIEXPORT jint JNICALL JNI_FN(nativeStart)(JNIEnv* env, jobject /*thiz*/, jlong handle, jint fd, jlong startUs, jlong endUs, jlong seedUs,
                                           jlong halfFrameUs, jfloat cx, jfloat cy, jfloat w, jfloat h, jstring cachePath) {
    TrackService* s = service(handle);
    const std::string path = toString(env, cachePath);
    if (s == nullptr || path.empty()) {
        if (fd >= 0) ::close(fd);
        return code(Status::InvalidArgument);
    }
    NormBox box;
    box.cx = cx;
    box.cy = cy;
    box.w = w;
    box.h = h;
    return code(s->start(fd, startUs, endUs, seedUs, halfFrameUs, box, path));
}

JNIEXPORT void JNICALL JNI_FN(nativeCancel)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (TrackService* s = service(handle)) s->cancel();
}

// Packs the job state: bits 0..7 state (0 idle, 1 running, 2 done, 3 failed, 4 cancelled), bits 8..23 progress in
// permille, bits 24..31 the Status code of a failure.
JNIEXPORT jlong JNICALL JNI_FN(nativePoll)(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    TrackService* s = service(handle);
    if (s == nullptr) return 0;
    const TrackService::Poll p = s->poll();
    return static_cast<jlong>(static_cast<int>(p.state)) | (static_cast<jlong>(p.permille & 0xFFFF) << 8) |
           (static_cast<jlong>(static_cast<int>(p.error) & 0xFF) << 24);
}

}  // extern "C"
