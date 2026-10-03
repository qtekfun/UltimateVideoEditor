#include "render/gl_pipeline.h"

#include <algorithm>
#include <string>
#include <vector>

#include "render/shaders.h"

namespace uv::render {

using decode::Error;
using decode::GpuFrame;
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
    if (error != nullptr) *error = Error{Status::GlError, std::string("shader compile failed: ") + log.data()};
    return Status::GlError;
}

}  // namespace

GlPipeline::~GlPipeline() {
    if (blitProgram_ != 0) glDeleteProgram(blitProgram_);
    if (compositeProgram_ != 0) glDeleteProgram(compositeProgram_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    if (fbo_ != 0) glDeleteFramebuffers(1, &fbo_);
}

Status GlPipeline::buildProgram(const char* vertex, const char* fragment, unsigned* program, Error* error) {
    GLuint vs = 0;
    GLuint fs = 0;
    if (Status s = compile(GL_VERTEX_SHADER, vertex, &vs, error); s != Status::Ok) return s;
    if (Status s = compile(GL_FRAGMENT_SHADER, fragment, &fs, error); s != Status::Ok) {
        glDeleteShader(vs);
        return s;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (ok != GL_TRUE) {
        glDeleteProgram(p);
        return glFail(error, "program link failed");
    }
    *program = p;
    return Status::Ok;
}

Status GlPipeline::init(Error* error) {
    if (Status s = buildProgram(kFullscreenVertex, kBlitFragment, &blitProgram_, error); s != Status::Ok) return s;
    if (Status s = buildProgram(kFullscreenVertex, kCompositeFragment, &compositeProgram_, error);
        s != Status::Ok) {
        return s;
    }
    glGenVertexArrays(1, &vao_);
    glGenFramebuffers(1, &fbo_);
    return Status::Ok;
}

Status GlPipeline::blitToFrame(AHardwareBuffer* src, const GpuFrame& dst, Error* error) {
    EGLImageKHR srcImage = egl_.createImage(src);
    if (srcImage == EGL_NO_IMAGE_KHR) {
        if (error != nullptr) *error = Error{Status::EglError, "eglCreateImage failed for decoder buffer"};
        return Status::EglError;
    }
    EGLImageKHR dstImage = egl_.createImage(dst.buffer());
    if (dstImage == EGL_NO_IMAGE_KHR) {
        egl_.destroyImage(srcImage);
        if (error != nullptr) *error = Error{Status::EglError, "eglCreateImage failed for cache buffer"};
        return Status::EglError;
    }

    GLuint textures[2] = {0, 0};
    glGenTextures(2, textures);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, textures[0]);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    egl_.bindImageToTexture(GL_TEXTURE_EXTERNAL_OES, srcImage);
    glBindTexture(GL_TEXTURE_2D, textures[1]);
    egl_.bindImageToTexture(GL_TEXTURE_2D, dstImage);

    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[1], 0);
    Status result = Status::Ok;
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        result = glFail(error, "cache framebuffer incomplete");
    } else {
        glViewport(0, 0, static_cast<GLsizei>(dst.width()), static_cast<GLsizei>(dst.height()));
        glDisable(GL_BLEND);
        glUseProgram(blitProgram_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textures[0]);
        glUniform1i(glGetUniformLocation(blitProgram_, "uTex"), 0);
        glBindVertexArray(vao_);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glFinish();  // the decoder buffer is released right after this call
        if (glGetError() != GL_NO_ERROR) result = glFail(error, "blit draw failed");
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteTextures(2, textures);
    egl_.destroyImage(srcImage);
    egl_.destroyImage(dstImage);
    return result;
}

void GlPipeline::clear(int surfaceWidth, int surfaceHeight) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
}

Status GlPipeline::draw(const GpuFrame& frame, ColorMode mode, int surfaceWidth, int surfaceHeight, Error* error) {
    EGLImageKHR image = egl_.createImage(frame.buffer());
    if (image == EGL_NO_IMAGE_KHR) {
        if (error != nullptr) *error = Error{Status::EglError, "eglCreateImage failed for preview frame"};
        return Status::EglError;
    }
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    egl_.bindImageToTexture(GL_TEXTURE_2D, image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    clear(surfaceWidth, surfaceHeight);
    // Letterbox: fit the frame inside the surface, preserving aspect ratio.
    const double frameAspect = static_cast<double>(frame.width()) / static_cast<double>(frame.height());
    const double surfaceAspect = static_cast<double>(surfaceWidth) / static_cast<double>(surfaceHeight);
    int vw = surfaceWidth;
    int vh = surfaceHeight;
    if (frameAspect > surfaceAspect) {
        vh = static_cast<int>(static_cast<double>(surfaceWidth) / frameAspect);
    } else {
        vw = static_cast<int>(static_cast<double>(surfaceHeight) * frameAspect);
    }
    glViewport((surfaceWidth - vw) / 2, (surfaceHeight - vh) / 2, vw, vh);

    glUseProgram(compositeProgram_);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(glGetUniformLocation(compositeProgram_, "uTex"), 0);
    glUniform1i(glGetUniformLocation(compositeProgram_, "uMode"), static_cast<int>(mode));
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    Status result = Status::Ok;
    if (glGetError() != GL_NO_ERROR) result = glFail(error, "composite draw failed");
    // Rendering is ordered before the swap; the texture can be dropped right away.
    glDeleteTextures(1, &texture);
    egl_.destroyImage(image);
    return result;
}

}  // namespace uv::render
