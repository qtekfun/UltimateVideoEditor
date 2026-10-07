#include "encode/frame_probe.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <chrono>
#include <utility>

#include "decode/log.h"

namespace uv::encode {

FrameProbe::~FrameProbe() { release(); }

void FrameProbe::configure(int width, int height, bool tenBit, SigMatrix matrix, std::vector<int64_t> frames) {
    width_ = width;
    height_ = height;
    tenBit_ = tenBit;
    matrix_ = matrix;
    frames_ = std::move(frames);
    next_ = 0;
}

void FrameProbe::release() {
    for (Stage& s : stages_) {
        if (s.fbo != 0) glDeleteFramebuffers(1, &s.fbo);
        if (s.texture != 0) glDeleteTextures(1, &s.texture);
    }
    stages_.clear();
}

bool FrameProbe::build() {
    // Halve until the next step would be 64x36 or a little more, then land exactly on 64x36: every blit scales by at most
    // 2, so its bilinear filter is (nearly) a box average and no pixel of the picture is skipped.
    int w = width_;
    int h = height_;
    std::vector<std::pair<int, int>> sizes;
    while (w > 2 * kProbeW || h > 2 * kProbeH) {
        w = std::max(kProbeW, (w + 1) / 2);
        h = std::max(kProbeH, (h + 1) / 2);
        sizes.emplace_back(w, h);
    }
    sizes.emplace_back(kProbeW, kProbeH);
    GLint previousFbo = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousFbo);
    GLint previousTexture = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &previousTexture);
    bool ok = true;
    for (const auto& size : sizes) {
        Stage s;
        s.w = size.first;
        s.h = size.second;
        glGenTextures(1, &s.texture);
        glBindTexture(GL_TEXTURE_2D, s.texture);
        glTexStorage2D(GL_TEXTURE_2D, 1, tenBit_ ? GL_RGB10_A2 : GL_RGBA8, s.w, s.h);
        glGenFramebuffers(1, &s.fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, s.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s.texture, 0);
        ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        stages_.push_back(s);
        if (!ok) break;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(previousFbo));
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(previousTexture));
    return ok && glGetError() == GL_NO_ERROR;
}

void FrameProbe::capture(int64_t frame, int64_t ptsUs) {
    if (!wants(frame)) return;
    ++next_;
    if (failed_) return;
    const auto began = std::chrono::steady_clock::now();
    while (glGetError() != GL_NO_ERROR) {
    }  // errors of earlier calls are not ours
    if (!built_) {
        built_ = true;
        if (!build()) {
            failed_ = true;
            UV_LOGW("frame probe: cannot build the reduction chain (%dx%d, %s)", width_, height_, tenBit_ ? "10-bit" : "8-bit");
            release();
            return;
        }
    }
    GLint readBinding = 0;
    GLint drawBinding = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readBinding);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawBinding);
    const GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    if (scissor) glDisable(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glReadBuffer(GL_BACK);
    int srcW = width_;
    int srcH = height_;
    for (const Stage& s : stages_) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, s.fbo);
        glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, s.w, s.h, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, s.fbo);
        srcW = s.w;
        srcH = s.h;
    }
    std::vector<uint16_t> rgb(static_cast<size_t>(kProbeW) * kProbeH * 3);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    if (tenBit_) {
        std::vector<uint32_t> px(static_cast<size_t>(kProbeW) * kProbeH);
        glReadPixels(0, 0, kProbeW, kProbeH, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, px.data());
        for (int y = 0; y < kProbeH; ++y) {  // GL rows run bottom to top
            for (int x = 0; x < kProbeW; ++x) {
                const uint32_t v = px[static_cast<size_t>(kProbeH - 1 - y) * kProbeW + static_cast<size_t>(x)];
                uint16_t* o = &rgb[(static_cast<size_t>(y) * kProbeW + static_cast<size_t>(x)) * 3];
                o[0] = static_cast<uint16_t>(v & 0x3FF);
                o[1] = static_cast<uint16_t>((v >> 10) & 0x3FF);
                o[2] = static_cast<uint16_t>((v >> 20) & 0x3FF);
            }
        }
    } else {
        std::vector<uint8_t> px(static_cast<size_t>(kProbeW) * kProbeH * 4);
        glReadPixels(0, 0, kProbeW, kProbeH, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
        for (int y = 0; y < kProbeH; ++y) {
            for (int x = 0; x < kProbeW; ++x) {
                const uint8_t* v = &px[(static_cast<size_t>(kProbeH - 1 - y) * kProbeW + static_cast<size_t>(x)) * 4];
                uint16_t* o = &rgb[(static_cast<size_t>(y) * kProbeW + static_cast<size_t>(x)) * 3];
                o[0] = v[0];
                o[1] = v[1];
                o[2] = v[2];
            }
        }
    }
    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(readBinding));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(drawBinding));
    if (scissor) glEnable(GL_SCISSOR_TEST);
    const GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        failed_ = true;
        UV_LOGW("frame probe: GL error 0x%x while reading frame %lld back", error, static_cast<long long>(frame));
        return;
    }
    done_.push_back(reduceProbe(rgb.data(), tenBit_ ? 1023 : 255, matrix_, frame, ptsUs));
    captureUs_ += std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - began).count();
}

}  // namespace uv::encode
