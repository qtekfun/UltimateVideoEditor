#include <jni.h>

#include "core/engine_version.h"

// JNI bindings only: no business logic belongs here.
extern "C" JNIEXPORT jstring JNICALL
Java_com_ultimatevideo_uveditor_engine_NativeEngine_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(uv::core::engineVersion());
}
