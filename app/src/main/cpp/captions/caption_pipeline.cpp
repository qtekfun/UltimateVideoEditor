#include "captions/caption_pipeline.h"

#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <vector>

#include "audio/android_pcm_decoder.h"
#include "captions/mono16k.h"
#include "whisper.h"

namespace uv::captions {
namespace {

constexpr const char* kTag = "UVCaptions";
constexpr int kDecodeProgressShare = 10;  // percent of the bar spent on decoding
constexpr int32_t kReadFrames = 8192;

struct ProgressState {
    CaptionSink* sink;
    bool cancelled = false;
};

void onWhisperProgress(whisper_context*, whisper_state*, int progress, void* user) {
    auto* state = static_cast<ProgressState*>(user);
    const int scaled = kDecodeProgressShare + progress * (100 - kDecodeProgressShare) / 100;
    if (!state->sink->onProgress(std::min(scaled, 100))) state->cancelled = true;
}

bool onWhisperAbort(void* user) { return static_cast<ProgressState*>(user)->cancelled; }

std::string trimmed(const char* s) {
    std::string t = s == nullptr ? "" : s;
    size_t a = 0;
    size_t b = t.size();
    while (a < b && std::isspace(static_cast<unsigned char>(t[a]))) ++a;
    while (b > a && std::isspace(static_cast<unsigned char>(t[b - 1]))) --b;
    return t.substr(a, b - a);
}

// "[BLANK_AUDIO]", "[MUSIC]", "(applause)": the model's notes about non-speech, not words.
bool isAnnotation(const std::string& t) {
    if (t.size() < 2) return false;
    const char first = t.front();
    const char last = t.back();
    return (first == '[' && last == ']') || (first == '(' && last == ')') || (first == '*' && last == '*');
}

core::Status decodeToWhisperInput(int fd, int64_t startMs, int64_t endMs, CaptionSink* sink, std::vector<float>* pcm) {
    core::Status status = core::Status::Ok;
    auto decoder = audio::AndroidPcmDecoder::open(fd, &status);
    if (!decoder) return core::ok(status) ? core::Status::CodecError : status;

    status = decoder->seekToMicros(startMs * 1000);
    if (!core::ok(status)) return status;

    const int64_t wantedFrames = (endMs - startMs) * decoder->sampleRate() / 1000;
    Mono16kConverter converter(decoder->sampleRate());
    pcm->reserve(static_cast<size_t>((endMs - startMs) * kWhisperRate / 1000) + kReadFrames);

    std::vector<float> block(static_cast<size_t>(kReadFrames) * 2);
    while (converter.inputFrames() < wantedFrames) {
        const int64_t remaining = wantedFrames - converter.inputFrames();
        const audio::PcmReadResult r =
            decoder->read(block.data(), static_cast<int32_t>(std::min<int64_t>(kReadFrames, remaining)));
        if (!core::ok(r.status)) return r.status;
        converter.process(block.data(), static_cast<size_t>(r.frames), pcm);
        const int percent =
            static_cast<int>(converter.inputFrames() * kDecodeProgressShare / std::max<int64_t>(wantedFrames, 1));
        if (!sink->onProgress(percent)) return core::Status::Cancelled;
        if (r.eof) break;
    }
    return core::Status::Ok;
}

}  // namespace

core::Status transcribe(int fd, int64_t startMs, int64_t endMs, const std::string& modelPath,
                        const std::string& language, int threads, CaptionSink* sink) {
    if (sink == nullptr || endMs <= startMs || startMs < 0 || endMs - startMs > kMaxSpanMs || modelPath.empty()) {
        return core::Status::InvalidArgument;
    }

    std::vector<float> pcm;
    core::Status status = decodeToWhisperInput(fd, startMs, endMs, sink, &pcm);
    if (!core::ok(status)) return status;
    // whisper needs at least one second of input to produce anything.
    if (pcm.size() < static_cast<size_t>(kWhisperRate)) return core::Status::InvalidArgument;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(modelPath.c_str(), cparams);
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "could not load model %s", modelPath.c_str());
        return core::Status::IoError;
    }

    ProgressState progress{sink};
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, threads);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.translate = false;
    params.language = language.empty() ? "auto" : language.c_str();
    params.detect_language = false;  // "auto" already detects; true would stop after detection
    // One segment per word: the documented way to get word-level timestamps.
    params.token_timestamps = true;
    params.max_len = 1;
    params.split_on_word = true;
    params.progress_callback = onWhisperProgress;
    params.progress_callback_user_data = &progress;
    params.abort_callback = onWhisperAbort;
    params.abort_callback_user_data = &progress;

    const int rc = whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size()));
    if (progress.cancelled) {
        whisper_free(ctx);
        return core::Status::Cancelled;
    }
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "whisper_full failed: %d", rc);
        whisper_free(ctx);
        return core::Status::CodecError;
    }

    const char* lang = whisper_lang_str(whisper_full_lang_id(ctx));
    if (lang != nullptr) sink->onLanguage(lang);

    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char* raw = whisper_full_get_segment_text(ctx, i);
        const std::string word = trimmed(raw);
        if (word.empty() || isAnnotation(word)) continue;
        // whisper times are in 10 ms units.
        const int64_t t0 = startMs + whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1 = startMs + whisper_full_get_segment_t1(ctx, i) * 10;
        sink->onWord(raw, t0, std::max(t1, t0));
    }
    sink->onProgress(100);
    whisper_free(ctx);
    return core::Status::Ok;
}

}  // namespace uv::captions
