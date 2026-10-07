#pragma once

// Takes the cheap signatures of a few composed frames while exporting (encode/frame_signature.h). It reads the picture
// the compositor just drew into the encoder's window surface (before the swap) by blitting it down through a short chain
// of half-size textures to 64x36 and reading only that back: a handful of GPU passes and a 9 KB read for each probed
// frame, nothing at all for the others. Needs the encoder surface's EGL context current.

#include <GLES3/gl3.h>

#include <cstdint>
#include <utility>
#include <vector>

#include "encode/frame_signature.h"

namespace uv::encode {

class FrameProbe {
public:
    FrameProbe() = default;
    ~FrameProbe();
    FrameProbe(const FrameProbe&) = delete;
    FrameProbe& operator=(const FrameProbe&) = delete;

    // `tenBit`: the surface and the chain keep 10 bits per channel (HLG exports). `frames`: sorted frame indices to probe.
    void configure(int width, int height, bool tenBit, SigMatrix matrix, std::vector<int64_t> frames);

    bool wants(int64_t frame) const { return next_ < frames_.size() && frames_[next_] == frame; }

    // Signs the frame currently in the back buffer. Never throws: on a GL failure the probe stops and `failed()` says so.
    void capture(int64_t frame, int64_t ptsUs);

    bool failed() const { return failed_; }
    int64_t captureMicros() const { return captureUs_; }
    int64_t captured() const { return static_cast<int64_t>(done_.size()); }
    std::vector<FrameSignature> take() { return std::move(done_); }

private:
    bool build();
    void release();

    struct Stage {
        int w = 0;
        int h = 0;
        GLuint texture = 0;
        GLuint fbo = 0;
    };

    int width_ = 0;
    int height_ = 0;
    bool tenBit_ = false;
    SigMatrix matrix_ = SigMatrix::Bt709;
    std::vector<int64_t> frames_;
    size_t next_ = 0;
    std::vector<Stage> stages_;
    bool built_ = false;
    bool failed_ = false;
    int64_t captureUs_ = 0;
    std::vector<FrameSignature> done_;
};

}  // namespace uv::encode
