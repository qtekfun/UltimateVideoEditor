#include "render/gl_pipeline.h"

#include <algorithm>
#include <string>
#include <vector>

#include "render/layout_math.h"
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
    for (auto& entry : frameTextures_) destroy(entry.second);
    for (auto& entry : sourceTextures_) destroy(entry.second);
    for (auto& entry : titleTextures_) glDeleteTextures(1, &entry.second.texture);
    if (blitProgram_ != 0) glDeleteProgram(blitProgram_);
    if (compositeProgram_ != 0) glDeleteProgram(compositeProgram_);
    if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
    if (fbo_ != 0) glDeleteFramebuffers(1, &fbo_);
}

void GlPipeline::destroy(ImageTexture& entry) {
    if (entry.texture != 0) glDeleteTextures(1, &entry.texture);
    egl_.destroyImage(entry.image);
    entry = ImageTexture{};
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
    if (Status s = buildProgram(kQuadVertex, kCompositeFragment, &compositeProgram_, error);
        s != Status::Ok) {
        return s;
    }
    // Sampler bindings never change, so set them once.
    glUseProgram(blitProgram_);
    glUniform1i(glGetUniformLocation(blitProgram_, "uTex"), 0);
    glUseProgram(compositeProgram_);
    glUniform1i(glGetUniformLocation(compositeProgram_, "uTex"), 0);
    compositeModeLoc_ = glGetUniformLocation(compositeProgram_, "uMode");
    compositeTurnsLoc_ = glGetUniformLocation(compositeProgram_, "uTurns");
    compositeXformLoc_ = glGetUniformLocation(compositeProgram_, "uXform");
    compositeOpacityLoc_ = glGetUniformLocation(compositeProgram_, "uOpacity");
    compositePremulLoc_ = glGetUniformLocation(compositeProgram_, "uPremul");
    glGenVertexArrays(1, &vao_);
    glGenFramebuffers(1, &fbo_);
    return Status::Ok;
}

void GlPipeline::releaseRetired() {
    for (uint64_t id : decode::takeRetiredFrameIds()) {
        auto it = frameTextures_.find(id);
        if (it == frameTextures_.end()) continue;
        destroy(it->second);
        frameTextures_.erase(it);
    }
}

Status GlPipeline::uploadTitle(uint32_t key, int width, int height, const uint8_t* rgba, Error* error) {
    if (key == 0 || width <= 0 || height <= 0 || rgba == nullptr) {
        if (error != nullptr) *error = Error{Status::InvalidArgument, "invalid title texture"};
        return Status::InvalidArgument;
    }
    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    if (width > maxSize || height > maxSize) {
        if (error != nullptr) *error = Error{Status::InvalidArgument, "title texture is larger than the GPU supports"};
        return Status::InvalidArgument;
    }
    releaseTitle(key);
    TitleTexture entry;
    entry.width = width;
    entry.height = height;
    glGenTextures(1, &entry.texture);
    glBindTexture(GL_TEXTURE_2D, entry.texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (glGetError() != GL_NO_ERROR) {
        glDeleteTextures(1, &entry.texture);
        return glFail(error, "title texture upload failed");
    }
    titleTextures_[key] = entry;
    return Status::Ok;
}

void GlPipeline::releaseTitle(uint32_t key) {
    auto it = titleTextures_.find(key);
    if (it == titleTextures_.end()) return;
    glDeleteTextures(1, &it->second.texture);
    titleTextures_.erase(it);
}

void GlPipeline::clearSourceCache() {
    for (auto& entry : sourceTextures_) destroy(entry.second);
    sourceTextures_.clear();
}

Status GlPipeline::sourceTexture(AHardwareBuffer* buffer, unsigned* texture, Error* error) {
    // The reader hands out a small fixed set of buffers; a larger map means they were reallocated.
    constexpr size_t kMaxSources = 32;
    auto it = sourceTextures_.find(buffer);
    if (it != sourceTextures_.end()) {
        *texture = it->second.texture;
        return Status::Ok;
    }
    if (sourceTextures_.size() >= kMaxSources) clearSourceCache();
    ImageTexture entry;
    entry.image = egl_.createImage(buffer);
    if (entry.image == EGL_NO_IMAGE_KHR) {
        if (error != nullptr) *error = Error{Status::EglError, "eglCreateImage failed for decoder buffer"};
        return Status::EglError;
    }
    glGenTextures(1, &entry.texture);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, entry.texture);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    egl_.bindImageToTexture(GL_TEXTURE_EXTERNAL_OES, entry.image);
    *texture = entry.texture;
    sourceTextures_.emplace(buffer, entry);
    return Status::Ok;
}

Status GlPipeline::frameTexture(const GpuFrame& frame, unsigned* texture, bool* created, Error* error) {
    auto it = frameTextures_.find(frame.id());
    if (it != frameTextures_.end()) {
        *texture = it->second.texture;
        *created = false;
        return Status::Ok;
    }
    ImageTexture entry;
    entry.image = egl_.createImage(frame.buffer());
    if (entry.image == EGL_NO_IMAGE_KHR) {
        if (error != nullptr) *error = Error{Status::EglError, "eglCreateImage failed for cache buffer"};
        return Status::EglError;
    }
    glGenTextures(1, &entry.texture);
    glBindTexture(GL_TEXTURE_2D, entry.texture);
    egl_.bindImageToTexture(GL_TEXTURE_2D, entry.image);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    *texture = entry.texture;
    *created = true;
    frameTextures_.emplace(frame.id(), entry);
    return Status::Ok;
}

Status GlPipeline::blitToFrame(AHardwareBuffer* src, const GpuFrame& dst, int* releaseFenceFd, Error* error) {
    *releaseFenceFd = -1;
    releaseRetired();
    unsigned srcTexture = 0;
    unsigned dstTexture = 0;
    bool created = false;
    if (Status s = sourceTexture(src, &srcTexture, error); s != Status::Ok) return s;
    if (Status s = frameTexture(dst, &dstTexture, &created, error); s != Status::Ok) return s;

    glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dstTexture, 0);
    // Completeness only depends on the attached buffer, so checking when it is first seen is enough.
    if (created && glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return glFail(error, "cache framebuffer incomplete");
    }
    glViewport(0, 0, static_cast<GLsizei>(dst.width()), static_cast<GLsizei>(dst.height()));
    glDisable(GL_BLEND);
    glUseProgram(blitProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, srcTexture);
    glBindVertexArray(vao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // The decoder may reuse `src` only after the GPU finished reading it: hand it a fence instead
    // of stalling this thread. Without fence support fall back to waiting.
    const int fence = egl_.createReleaseFence();
    if (fence == EglContext::kFenceUnsupported) {
        glFinish();
    } else {
        *releaseFenceFd = fence;
    }
    return Status::Ok;
}

void GlPipeline::clear(int surfaceWidth, int surfaceHeight) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
}

Status GlPipeline::draw(const GpuFrame& frame, ColorMode mode, int turns, int surfaceWidth, int surfaceHeight,
                        Error* error) {
    // A single layer on a canvas of its own (displayed) size fills the letterboxed viewport.
    int displayW = 0;
    int displayH = 0;
    displaySize(static_cast<int>(frame.width()), static_cast<int>(frame.height()), turns, &displayW, &displayH);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    const std::vector<LayerDraw> layers{LayerDraw{&frame, mode, turns, LayerTransform{}}};
    return drawScene(layers, displayW, displayH, surfaceWidth, surfaceHeight, error);
}

Status GlPipeline::drawScene(const std::vector<LayerDraw>& layers, int canvasWidth, int canvasHeight,
                             int surfaceWidth, int surfaceHeight, Error* error) {
    // Resolve every texture first: a failure must not leave a half-drawn frame on the surface.
    std::vector<unsigned> textures;
    textures.reserve(layers.size());
    for (const LayerDraw& layer : layers) {
        unsigned texture = 0;
        bool created = false;
        if (layer.titleKey != 0) {
            auto title = titleTextures_.find(layer.titleKey);
            if (title == titleTextures_.end()) {
                if (error != nullptr) *error = Error{Status::NotFound, "title texture " + std::to_string(layer.titleKey) + " is missing"};
                return Status::NotFound;
            }
            textures.push_back(title->second.texture);
            continue;
        }
        if (layer.frame == nullptr) {
            if (error != nullptr) *error = Error{Status::InvalidArgument, "layer without a frame"};
            return Status::InvalidArgument;
        }
        if (Status s = frameTexture(*layer.frame, &texture, &created, error); s != Status::Ok) return s;
        textures.push_back(texture);
    }

    glViewport(0, 0, surfaceWidth, surfaceHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    const Viewport vp = letterbox(canvasWidth, canvasHeight, surfaceWidth, surfaceHeight);
    glViewport(vp.x, vp.y, vp.w, vp.h);

    glUseProgram(compositeProgram_);
    glBindVertexArray(vao_);
    glActiveTexture(GL_TEXTURE0);
    glEnable(GL_BLEND);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    for (size_t i = 0; i < layers.size(); ++i) {
        const LayerDraw& layer = layers[i];
        QuadMap map;
        if (layer.titleKey != 0) {
            const TitleTexture& title = titleTextures_.at(layer.titleKey);
            map = titleQuadMap(canvasWidth, canvasHeight, title.width, title.height, layer.transform);
        } else {
            int displayW = 0;
            int displayH = 0;
            displaySize(static_cast<int>(layer.frame->width()), static_cast<int>(layer.frame->height()), layer.turns,
                        &displayW, &displayH);
            map = layerQuadMap(canvasWidth, canvasHeight, displayW, displayH, layer.transform);
        }
        float matrix[9];
        quadMapToMat3(map, matrix);
        glBindTexture(GL_TEXTURE_2D, textures[i]);
        glUniformMatrix3fv(compositeXformLoc_, 1, GL_FALSE, matrix);
        glUniform1f(compositeOpacityLoc_, clampOpacity(layer.transform.opacity));
        glUniform1i(compositeModeLoc_, layer.titleKey != 0 ? 0 : static_cast<int>(layer.mode));
        glUniform1i(compositeTurnsLoc_, layer.titleKey != 0 ? 0 : layer.turns);
        glUniform1i(compositePremulLoc_, layer.titleKey != 0 ? 1 : 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glDisable(GL_BLEND);
    return Status::Ok;
}

}  // namespace uv::render
