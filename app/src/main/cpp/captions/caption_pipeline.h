#pragma once

#include <cstdint>
#include <string>

#include "core/error.h"

namespace uv::captions {

// Receives results on the calling thread. Everything Kotlin needs flows through here.
class CaptionSink {
public:
    virtual ~CaptionSink() = default;
    // 0..100. Return false to cancel; the run then ends with Status::Cancelled.
    virtual bool onProgress(int percent) = 0;
    virtual void onLanguage(const std::string& code) = 0;
    // `text` keeps the model's own spacing (a leading space marks the start of a word).
    virtual void onWord(const std::string& text, int64_t startMs, int64_t endMs) = 0;
};

// Longest span transcribed in one run (16 kHz mono float is ~3.8 MB per minute held in memory).
constexpr int64_t kMaxSpanMs = 30LL * 60 * 1000;

// Decodes [startMs, endMs) of the audio track behind `fd` (duplicated, the caller keeps its own),
// converts it to 16 kHz mono and transcribes it with the ggml model at `modelPath`.
// `language` is an ISO code or "auto". Word times are milliseconds on the source's own timeline.
core::Status transcribe(int fd, int64_t startMs, int64_t endMs, const std::string& modelPath,
                        const std::string& language, int threads, CaptionSink* sink);

}  // namespace uv::captions
