// JNI bindings for on-device captions. Bindings only: the work is in captions/caption_pipeline.
// Callbacks run on the thread that called nativeTranscribe. Errors come back as a
// uv::core::Status value and are mapped to typed Kotlin exceptions; nothing is swallowed here.
#include <jni.h>

#include <string>

#include "captions/caption_pipeline.h"

namespace {

std::string toStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

class JavaSink final : public uv::captions::CaptionSink {
public:
    JavaSink(JNIEnv* env, jobject callbacks) : env_(env), callbacks_(callbacks) {
        jclass cls = env->GetObjectClass(callbacks);
        progress_ = env->GetMethodID(cls, "onProgress", "(I)Z");
        language_ = env->GetMethodID(cls, "onLanguage", "(Ljava/lang/String;)V");
        word_ = env->GetMethodID(cls, "onWord", "(Ljava/lang/String;JJ)V");
        env->DeleteLocalRef(cls);
    }

    bool valid() const { return progress_ != nullptr && language_ != nullptr && word_ != nullptr; }

    bool onProgress(int percent) override {
        if (env_->ExceptionCheck()) return false;
        return env_->CallBooleanMethod(callbacks_, progress_, static_cast<jint>(percent)) == JNI_TRUE &&
               !env_->ExceptionCheck();
    }

    void onLanguage(const std::string& code) override {
        jstring s = env_->NewStringUTF(code.c_str());
        env_->CallVoidMethod(callbacks_, language_, s);
        env_->DeleteLocalRef(s);
    }

    void onWord(const std::string& text, int64_t startMs, int64_t endMs) override {
        jstring s = env_->NewStringUTF(text.c_str());
        env_->CallVoidMethod(callbacks_, word_, s, static_cast<jlong>(startMs), static_cast<jlong>(endMs));
        env_->DeleteLocalRef(s);
    }

private:
    JNIEnv* env_;
    jobject callbacks_;
    jmethodID progress_ = nullptr;
    jmethodID language_ = nullptr;
    jmethodID word_ = nullptr;
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_ultimatevideo_uveditor_engine_captions_NativeCaptions_nativeTranscribe(
    JNIEnv* env, jobject, jint fd, jlong startMs, jlong endMs, jstring modelPath, jstring language, jint threads,
    jobject callbacks) {
    JavaSink sink(env, callbacks);
    if (!sink.valid()) return static_cast<jint>(uv::core::Status::InvalidArgument);
    const uv::core::Status status = uv::captions::transcribe(fd, startMs, endMs, toStd(env, modelPath),
                                                             toStd(env, language), threads, &sink);
    return static_cast<jint>(status);
}
