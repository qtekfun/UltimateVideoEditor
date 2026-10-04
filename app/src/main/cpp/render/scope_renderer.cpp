#include "render/scope_renderer.h"

#include <algorithm>
#include <string>
#include <vector>

#include "render/scope_shaders.h"

namespace uv::render {

using decode::Error;
using decode::Status;

namespace {

Status glFail(Error* error, const std::string& what) {
    if (error != nullptr) *error = Error{Status::GlError, what + " (gl error 0x" + std::to_string(glGetError()) + ")"};
    return Status::GlError;
}

Status compile(GLenum type, const char* source, GLuint* shader, Error* error) {
    *shader = glCreateShader(type);
    glShaderSource(*shader, 1, &source, nullptr);
    glCompileShader(*shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(*shader, GL_COMPILE_STATUS, &ok);
    if (ok == GL_TRUE) return Status::Ok;
    GLint len = 0;
    glGetShaderiv(*shader, GL_INFO_LOG_LENGTH, &len);
    std::vector<char> log(static_cast<size_t>(std::max(len, 1)));
    glGetShaderInfoLog(*shader, len, nullptr, log.data());
    glDeleteShader(*shader);
    if (error != nullptr) *error = Error{Status::GlError, std::string("scope shader compile failed: ") + log.data()};
    return Status::GlError;
}

Status buildProgram(const char* vertex, const char* fragment, unsigned* program, Error* error) {
    GLuint vs = 0;
    GLuint fs = 0;
    if (Status s = compile(GL_VERTEX_SHADER, vertex, &vs, error); s != Status::Ok) return s;
    if (Status s = compile(GL_FRAGMENT_SHADER, fragment, &fs, error); s != Status::Ok) {
        glDeleteShader(vs);
        return s;
    }
    *program = glCreateProgram();
    glAttachShader(*program, vs);
    glAttachShader(*program, fs);
    glLinkProgram(*program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(*program, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        glDeleteProgram(*program);
        *program = 0;
        return glFail(error, "scope program link failed");
    }
    return Status::Ok;
}

unsigned makeTexture(GLenum internalFormat, GLenum format, GLenum type, int w, int h, GLenum filter) {
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(internalFormat), w, h, 0, format, type, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, static_cast<GLint>(filter));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, static_cast<GLint>(filter));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return texture;
}

}  // namespace

ScopeRenderer::~ScopeRenderer() { release(); }

void ScopeRenderer::release() {
    if (srcFbo_ != 0) glDeleteFramebuffers(1, &srcFbo_);
    if (accumFbo_ != 0) glDeleteFramebuffers(1, &accumFbo_);
    if (srcTexture_ != 0) glDeleteTextures(1, &srcTexture_);
    if (accumTexture_ != 0) glDeleteTextures(1, &accumTexture_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    if (pointProgram_ != 0) glDeleteProgram(pointProgram_);
    if (displayProgram_ != 0) glDeleteProgram(displayProgram_);
    srcFbo_ = accumFbo_ = srcTexture_ = accumTexture_ = vao_ = pointProgram_ = displayProgram_ = 0;
    captured_ = false;
}

Status ScopeRenderer::init(Error* error) {
    release();
    if (Status s = buildProgram(kScopePointVertex, kScopePointFragment, &pointProgram_, error); s != Status::Ok) return s;
    if (Status s = buildProgram(kScopeDisplayVertex, kScopeDisplayFragment, &displayProgram_, error); s != Status::Ok) {
        release();
        return s;
    }
    pointSrcLoc_ = glGetUniformLocation(pointProgram_, "uSrc");
    pointSrcSizeLoc_ = glGetUniformLocation(pointProgram_, "uSrcSize");
    pointModeLoc_ = glGetUniformLocation(pointProgram_, "uMode");
    pointChannelLoc_ = glGetUniformLocation(pointProgram_, "uChannel");
    displayAccumLoc_ = glGetUniformLocation(displayProgram_, "uAccum");
    displayModeLoc_ = glGetUniformLocation(displayProgram_, "uMode");
    displayWaveGainLoc_ = glGetUniformLocation(displayProgram_, "uWaveGain");
    displayVectorGainLoc_ = glGetUniformLocation(displayProgram_, "uVectorGain");
    displayHistFullLoc_ = glGetUniformLocation(displayProgram_, "uHistFull");

    srcTexture_ = makeTexture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, scope::kSrcWidth, scope::kSrcHeight, GL_LINEAR);
    accumTexture_ = makeTexture(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, scope::kAccumWidth, scope::kAccumHeight, GL_NEAREST);
    glGenFramebuffers(1, &srcFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, srcFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, srcTexture_, 0);
    const bool srcOk = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glGenFramebuffers(1, &accumFbo_);
    glBindFramebuffer(GL_FRAMEBUFFER, accumFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, accumTexture_, 0);
    const bool accumOk = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glGenVertexArrays(1, &vao_);
    if (!srcOk || !accumOk) {
        release();
        return glFail(error, "scope framebuffers are incomplete");
    }
    return Status::Ok;
}

void ScopeRenderer::capture(int x, int y, int w, int h) {
    if (srcFbo_ == 0 || w <= 0 || h <= 0) return;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, srcFbo_);
    glDisable(GL_SCISSOR_TEST);
    glBlitFramebuffer(x, y, x + w, y + h, 0, 0, scope::kSrcWidth, scope::kSrcHeight, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    captured_ = glGetError() == GL_NO_ERROR;
}

void ScopeRenderer::accumulate(scope::Mode mode) {
    glBindFramebuffer(GL_FRAMEBUFFER, accumFbo_);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);
    glBlendEquation(GL_FUNC_ADD);
    glUseProgram(pointProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, srcTexture_);
    glUniform1i(pointSrcLoc_, 0);
    glUniform2i(pointSrcSizeLoc_, scope::kSrcWidth, scope::kSrcHeight);
    glUniform1i(pointModeLoc_, static_cast<int>(mode));
    glBindVertexArray(vao_);
    const int points = scope::kSrcWidth * scope::kSrcHeight;
    switch (mode) {
        case scope::Mode::Waveform:
            glViewport(0, 0, scope::kAccumWidth, scope::kAccumHeight);
            glUniform1i(pointChannelLoc_, 0);
            glDrawArrays(GL_POINTS, 0, points);
            break;
        case scope::Mode::Parade:
            glViewport(0, 0, scope::kAccumWidth, scope::kAccumHeight);
            for (int channel = 0; channel < 3; ++channel) {
                glUniform1i(pointChannelLoc_, channel);
                glDrawArrays(GL_POINTS, 0, points);
            }
            break;
        case scope::Mode::Vector:
            glViewport(0, 0, scope::kVectorSize, scope::kVectorSize);
            glUniform1i(pointChannelLoc_, 0);
            glDrawArrays(GL_POINTS, 0, points);
            break;
        case scope::Mode::Histogram:
            glViewport(0, 0, scope::kHistogramBins, 1);
            for (int channel = 0; channel < 4; ++channel) {
                glUniform1i(pointChannelLoc_, channel);
                glDrawArrays(GL_POINTS, 0, points);
            }
            break;
    }
    glDisable(GL_BLEND);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void ScopeRenderer::render(scope::Mode mode, int surfaceW, int surfaceH) {
    if (!captured_ || surfaceW <= 0 || surfaceH <= 0) return;
    accumulate(mode);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, surfaceW, surfaceH);
    glClearColor(0.02f, 0.02f, 0.02f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (mode == scope::Mode::Vector) {
        const int side = std::min(surfaceW, surfaceH);
        glViewport((surfaceW - side) / 2, (surfaceH - side) / 2, side, side);
    }
    glUseProgram(displayProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, accumTexture_);
    glUniform1i(displayAccumLoc_, 0);
    glUniform1i(displayModeLoc_, static_cast<int>(mode));
    glUniform1f(displayWaveGainLoc_, scope::kWaveformGain);
    glUniform1f(displayVectorGainLoc_, scope::kVectorGain);
    glUniform1f(displayHistFullLoc_, scope::kHistogramFull);
    glBindVertexArray(vao_);
    glDisable(GL_BLEND);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

}  // namespace uv::render
