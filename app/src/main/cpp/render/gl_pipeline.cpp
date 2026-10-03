#include "render/gl_pipeline.h"

#include <algorithm>
#include <string>
#include <vector>

#include "render/effect_math.h"
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
    if (effectProgram_ != 0) glDeleteProgram(effectProgram_);
    for (FxTarget& target : fxTargets_) {
        if (target.texture != 0) glDeleteTextures(1, &target.texture);
    }
    if (dstSnapshot_.texture != 0) glDeleteTextures(1, &dstSnapshot_.texture);
    if (fxFbo_ != 0) glDeleteFramebuffers(1, &fxFbo_);
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
    if (Status s = buildProgram(kFullscreenVertex, kEffectFragment, &effectProgram_, error); s != Status::Ok) {
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
    compositeSrcGlLoc_ = glGetUniformLocation(compositeProgram_, "uSrcGl");
    compositeOutPremulLoc_ = glGetUniformLocation(compositeProgram_, "uOutPremul");
    compositeMaskShapeLoc_ = glGetUniformLocation(compositeProgram_, "uMaskShape");
    compositeMaskLoc_ = glGetUniformLocation(compositeProgram_, "uMask");
    compositeMaskSoftLoc_ = glGetUniformLocation(compositeProgram_, "uMaskSoft");
    compositeBlendLoc_ = glGetUniformLocation(compositeProgram_, "uBlend");
    compositeDstRectLoc_ = glGetUniformLocation(compositeProgram_, "uDstRect");
    glUniform1i(glGetUniformLocation(compositeProgram_, "uDst"), 1);  // the blend snapshot lives on unit 1
    glUseProgram(effectProgram_);
    glUniform1i(glGetUniformLocation(effectProgram_, "uTex"), 0);
    effectTypeLoc_ = glGetUniformLocation(effectProgram_, "uType");
    effectParamsLoc_ = glGetUniformLocation(effectProgram_, "uP");
    effectTexelLoc_ = glGetUniformLocation(effectProgram_, "uTexel");
    effectDirLoc_ = glGetUniformLocation(effectProgram_, "uDir");
    effectSigmaLoc_ = glGetUniformLocation(effectProgram_, "uSigma");
    effectStepLoc_ = glGetUniformLocation(effectProgram_, "uStep");
    glGenVertexArrays(1, &vao_);
    glGenFramebuffers(1, &fbo_);
    glGenFramebuffers(1, &fxFbo_);
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

void GlPipeline::setOutputSpace(OutputSpace space) {
    outputSpace_ = space;  // intermediates are re-specified lazily when their format no longer matches
}

Status GlPipeline::ensureFxTarget(FxTarget& target, int width, int height, Error* error) {
    const bool hdr = outputSpace_ == OutputSpace::Hlg2020;
    if (target.texture != 0 && target.width == width && target.height == height && target.hdr == hdr) return Status::Ok;
    if (target.texture == 0) glGenTextures(1, &target.texture);
    glBindTexture(GL_TEXTURE_2D, target.texture);
    // HDR keeps the effect chain in half floats so HLG highlights survive intermediate passes.
    if (hdr) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, nullptr);
    } else {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (glGetError() != GL_NO_ERROR) {
        glDeleteTextures(1, &target.texture);
        target = FxTarget{};
        return glFail(error, "effect texture allocation failed");
    }
    target.width = width;
    target.height = height;
    target.hdr = hdr;
    return Status::Ok;
}

void GlPipeline::effectPass(unsigned sourceTexture, const FxTarget& destination, const core::EffectOp& op,
                            float dirX, float dirY, float sigma, float step) {
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, destination.texture, 0);
    glViewport(0, 0, destination.width, destination.height);
    glUseProgram(effectProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sourceTexture);
    glUniform1i(effectTypeLoc_, static_cast<int>(op.type));
    glUniform1fv(effectParamsLoc_, core::kMaxEffectValues, op.v);
    glUniform2f(effectTexelLoc_, 1.0f / static_cast<float>(destination.width), 1.0f / static_cast<float>(destination.height));
    glUniform2f(effectDirLoc_, dirX, dirY);
    glUniform1f(effectSigmaLoc_, sigma);
    glUniform1f(effectStepLoc_, step);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

Status GlPipeline::runEffectChain(const LayerDraw& layer, unsigned sourceTexture, int layerWidth, int layerHeight,
                                  unsigned* result, Error* error) {
    // The layer is rendered at the size it covers on the canvas (more when it is scaled up), so effects
    // see what the viewer sees and blur radii are the same in preview and export.
    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    const double boost = std::min(4.0, std::max({1.0, static_cast<double>(layer.transform.scaleX),
                                                 static_cast<double>(layer.transform.scaleY)}));
    double w = layerWidth * boost;
    double h = layerHeight * boost;
    const double limit = static_cast<double>(std::min<GLint>(maxSize > 0 ? maxSize : 4096, 4096));
    const double over = std::max(w, h) / limit;
    if (over > 1.0) {
        w /= over;
        h /= over;
    }
    const int width = std::max(8, static_cast<int>(std::lround(w)));
    const int height = std::max(8, static_cast<int>(std::lround(h)));
    for (FxTarget& target : fxTargets_) {
        if (Status s = ensureFxTarget(target, width, height, error); s != Status::Ok) return s;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fxTargets_[0].texture, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        return glFail(error, "effect framebuffer incomplete");
    }
    glDisable(GL_BLEND);
    glBindVertexArray(vao_);

    // Pass 0: the source layer (colour mode, rotation, title alpha) into the first intermediate.
    glViewport(0, 0, width, height);
    glUseProgram(compositeProgram_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, sourceTexture);
    const float identity[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    glUniformMatrix3fv(compositeXformLoc_, 1, GL_FALSE, identity);
    glUniform1f(compositeOpacityLoc_, 1.0f);
    glUniform1i(compositeModeLoc_, static_cast<int>(layer.titleKey != 0 ? titleMode() : layer.mode));
    glUniform1i(compositeTurnsLoc_, layer.titleKey != 0 ? 0 : layer.turns);
    glUniform1i(compositePremulLoc_, layer.titleKey != 0 ? 1 : 0);
    glUniform1i(compositeSrcGlLoc_, 0);
    glUniform1i(compositeOutPremulLoc_, 1);
    glUniform1i(compositeMaskShapeLoc_, 0);
    glUniform1i(compositeBlendLoc_, 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    int current = 0;
    for (const core::EffectOp& op : layer.fx.effects) {
        const int other = 1 - current;
        if (op.type == core::EffectType::Blur) {
            const float sigma = blurSigmaPx(op.v[0], static_cast<float>(height));
            if (sigma < kMinBlurSigmaPx) continue;
            const float step = blurStepTexels(sigma);
            effectPass(fxTargets_[current].texture, fxTargets_[other], op, 1.0f, 0.0f, sigma, step);
            effectPass(fxTargets_[other].texture, fxTargets_[current], op, 0.0f, 1.0f, sigma, step);
            continue;
        }
        effectPass(fxTargets_[current].texture, fxTargets_[other], op, 0.0f, 0.0f, 1.0f, 1.0f);
        current = other;
    }
    *result = fxTargets_[current].texture;
    return Status::Ok;
}

void GlPipeline::snapshotDestination(const Viewport& vp) {
    glActiveTexture(GL_TEXTURE1);
    const bool hdr = outputSpace_ == OutputSpace::Hlg2020;
    if (dstSnapshot_.texture == 0 || dstSnapshot_.width != vp.w || dstSnapshot_.height != vp.h || dstSnapshot_.hdr != hdr) {
        if (dstSnapshot_.texture == 0) glGenTextures(1, &dstSnapshot_.texture);
        glBindTexture(GL_TEXTURE_2D, dstSnapshot_.texture);
        // glCopyTexSubImage2D needs a fixed-point texture for a fixed-point target: an HLG target is
        // a 10-bit window/encoder surface, so the snapshot is RGB10_A2 rather than half float.
        if (hdr) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB10_A2, vp.w, vp.h, 0, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, nullptr);
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, vp.w, vp.h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        dstSnapshot_.width = vp.w;
        dstSnapshot_.height = vp.h;
        dstSnapshot_.hdr = hdr;
    } else {
        glBindTexture(GL_TEXTURE_2D, dstSnapshot_.texture);
    }
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, vp.x, vp.y, vp.w, vp.h);
    glActiveTexture(GL_TEXTURE0);
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

    GLint targetFramebuffer = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &targetFramebuffer);
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
        const bool titleLayer = layer.titleKey != 0;
        QuadMap map;
        int layerW = 0;
        int layerH = 0;
        if (titleLayer) {
            const TitleTexture& title = titleTextures_.at(layer.titleKey);
            map = titleQuadMap(canvasWidth, canvasHeight, title.width, title.height, layer.transform);
            layerW = title.width;
            layerH = title.height;
        } else {
            int displayW = 0;
            int displayH = 0;
            displaySize(static_cast<int>(layer.frame->width()), static_cast<int>(layer.frame->height()), layer.turns,
                        &displayW, &displayH);
            map = layerQuadMap(canvasWidth, canvasHeight, displayW, displayH, layer.transform);
            // Effects run at the size the layer covers on the canvas (contain fit).
            const double fit = std::min(static_cast<double>(canvasWidth) / displayW, static_cast<double>(canvasHeight) / displayH);
            layerW = std::max(1, static_cast<int>(std::lround(displayW * fit)));
            layerH = std::max(1, static_cast<int>(std::lround(displayH * fit)));
        }

        unsigned texture = textures[i];
        bool fromIntermediate = false;
        if (!layer.fx.effects.empty()) {
            unsigned result = 0;
            const Status chain = runEffectChain(layer, texture, layerW, layerH, &result, error);
            // Whatever happened, hand back the target and the state this loop relies on.
            glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(targetFramebuffer));
            glViewport(vp.x, vp.y, vp.w, vp.h);
            glUseProgram(compositeProgram_);
            glBindVertexArray(vao_);
            glActiveTexture(GL_TEXTURE0);
            if (chain != Status::Ok) {
                glDisable(GL_BLEND);
                return chain;
            }
            texture = result;
            fromIntermediate = true;
        }

        if (layer.fx.blend != core::BlendMode::Normal) {
            snapshotDestination(vp);
            glDisable(GL_BLEND);
            glUniform4f(compositeDstRectLoc_, static_cast<float>(vp.x), static_cast<float>(vp.y), static_cast<float>(vp.w),
                        static_cast<float>(vp.h));
        } else {
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }
        const bool premultiplied = titleLayer || fromIntermediate;
        float matrix[9];
        quadMapToMat3(map, matrix);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniformMatrix3fv(compositeXformLoc_, 1, GL_FALSE, matrix);
        glUniform1f(compositeOpacityLoc_, clampOpacity(layer.transform.opacity));
        // Effect intermediates already went through the colour conversion in pass 0; titles are SDR
        // graphics, so in an HLG target they are placed at reference white.
        const ColorMode drawMode = fromIntermediate ? ColorMode::Sdr709 : titleLayer ? titleMode() : layer.mode;
        glUniform1i(compositeModeLoc_, static_cast<int>(drawMode));
        glUniform1i(compositeTurnsLoc_, premultiplied ? 0 : layer.turns);
        glUniform1i(compositePremulLoc_, premultiplied ? 1 : 0);
        glUniform1i(compositeSrcGlLoc_, fromIntermediate ? 1 : 0);
        glUniform1i(compositeOutPremulLoc_, 0);
        glUniform1i(compositeBlendLoc_, static_cast<int>(layer.fx.blend));
        const core::MaskParams& mask = layer.fx.mask;
        glUniform1i(compositeMaskShapeLoc_, mask.shape);
        glUniform4f(compositeMaskLoc_, mask.cx, mask.cy, mask.w, mask.h);
        glUniform2f(compositeMaskSoftLoc_, mask.feather, mask.invert ? 1.0f : 0.0f);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glDisable(GL_BLEND);
    return Status::Ok;
}

}  // namespace uv::render
