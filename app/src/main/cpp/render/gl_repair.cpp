// Smooth slow motion (optical-flow interpolation) and the helpers of the repair effects, on the GL pipeline.
// The maths is specified by render/repair_math.h; the shaders are in render/shaders.h.
#include <algorithm>
#include <cmath>
#include <string>

#include "render/gl_pipeline.h"
#include "render/repair_math.h"
#include "render/shaders.h"

namespace uv::render {

using decode::Error;
using decode::Status;

namespace {

Status repairFail(Error* error, const std::string& what) {
    if (error != nullptr) *error = Error{Status::GlError, what + " (gl error 0x" + std::to_string(glGetError()) + ")"};
    return Status::GlError;
}

// Interpolated frames are made at most this wide/tall: the layer is shown at canvas size anyway.
constexpr int kMaxInterpSide = 2048;

}  // namespace

Status GlPipeline::ensureTexture(SizedTexture& tex, int width, int height, int internalFormat, int format, int type,
                                 int filter, Error* error) {
    if (tex.id != 0 && tex.width == width && tex.height == height) return Status::Ok;
    if (tex.id == 0) glGenTextures(1, &tex.id);
    glBindTexture(GL_TEXTURE_2D, tex.id);
    glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, static_cast<GLenum>(format), static_cast<GLenum>(type), nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (glGetError() != GL_NO_ERROR) {
        glDeleteTextures(1, &tex.id);
        tex = SizedTexture{};
        return repairFail(error, "repair texture allocation failed");
    }
    tex.width = width;
    tex.height = height;
    return Status::Ok;
}

Status GlPipeline::ensureRepairResources(Error* error) {
    if (repairReady_) return Status::Ok;
    if (Status s = buildProgram(kFullscreenVertex, kLumaDownFragment, &lumaProgram_, error); s != Status::Ok) return s;
    if (Status s = buildProgram(kFullscreenVertex, kFlowFragment, &flowProgram_, error); s != Status::Ok) return s;
    if (Status s = buildProgram(kFullscreenVertex, kInterpFragment, &interpProgram_, error); s != Status::Ok) return s;
    if (Status s = buildProgram(kFullscreenVertex, kReduceFragment, &reduceProgram_, error); s != Status::Ok) return s;
    glUseProgram(lumaProgram_);
    glUniform1i(glGetUniformLocation(lumaProgram_, "uTex"), 0);
    lumaSizeLoc_ = glGetUniformLocation(lumaProgram_, "uSize");
    lumaTapsLoc_ = glGetUniformLocation(lumaProgram_, "uTaps");
    glUseProgram(flowProgram_);
    glUniform1i(glGetUniformLocation(flowProgram_, "uA"), 0);
    glUniform1i(glGetUniformLocation(flowProgram_, "uB"), 1);
    flowSizeLoc_ = glGetUniformLocation(flowProgram_, "uSize");
    flowRadiusLoc_ = glGetUniformLocation(flowProgram_, "uRadius");
    glUseProgram(interpProgram_);
    glUniform1i(glGetUniformLocation(interpProgram_, "uA"), 0);
    glUniform1i(glGetUniformLocation(interpProgram_, "uB"), 1);
    glUniform1i(glGetUniformLocation(interpProgram_, "uFlow"), 2);
    interpFlowSizeLoc_ = glGetUniformLocation(interpProgram_, "uFlowSize");
    interpTLoc_ = glGetUniformLocation(interpProgram_, "uT");
    interpUseFlowLoc_ = glGetUniformLocation(interpProgram_, "uUseFlow");
    glUseProgram(reduceProgram_);
    glUniform1i(glGetUniformLocation(reduceProgram_, "uTex"), 0);
    reduceModeLoc_ = glGetUniformLocation(reduceProgram_, "uMode");
    repairReady_ = true;
    return Status::Ok;
}

void GlPipeline::releaseRepairResources() {
    for (unsigned program : {lumaProgram_, flowProgram_, interpProgram_, reduceProgram_}) {
        if (program != 0) glDeleteProgram(program);
    }
    lumaProgram_ = flowProgram_ = interpProgram_ = reduceProgram_ = 0;
    auto drop = [](SizedTexture& t) {
        if (t.id != 0) glDeleteTextures(1, &t.id);
        t = SizedTexture{};
    };
    drop(lumaA_);
    drop(lumaB_);
    drop(reduceCells_);
    for (SizedTexture& t : means_) drop(t);
    for (SizedTexture& t : interpOuts_) drop(t);
    interpOuts_.clear();
    for (FlowEntry& e : flowCache_) {
        drop(e.texture);
        e = FlowEntry{};
    }
    repairReady_ = false;
}

Status GlPipeline::flowBetween(const LayerDraw& layer, unsigned texA, unsigned texB, int quality, SizedTexture** flow,
                               Error* error) {
    const uint64_t idA = layer.frame->id();
    const uint64_t idB = layer.blendWith->id();
    // The same two frames are blended over and over while a clip is slowed (4 output frames per source pair
    // at 0.25x): keep recent flow fields.
    for (FlowEntry& entry : flowCache_) {
        if (entry.texture.id != 0 && entry.a == idA && entry.b == idB && entry.quality == quality) {
            entry.stamp = ++flowStamp_;
            *flow = &entry.texture;
            return Status::Ok;
        }
    }
    FlowEntry* slot = &flowCache_[0];
    for (FlowEntry& entry : flowCache_) {
        if (entry.stamp < slot->stamp) slot = &entry;
    }

    const repair::FlowQuality q = quality >= 2 ? repair::kFlowHigh : repair::kFlowLow;
    const int srcW = std::max(1, static_cast<int>(layer.frame->width()));
    const int srcH = std::max(1, static_cast<int>(layer.frame->height()));
    const int fw = q.flowWidth;
    const int fh = std::max(16, static_cast<int>(std::lround(static_cast<double>(fw) * srcH / srcW)));
    const int taps = std::clamp((srcW + fw - 1) / fw, 1, 8);
    if (Status s = ensureTexture(lumaA_, fw, fh, GL_R8, GL_RED, GL_UNSIGNED_BYTE, GL_NEAREST, error); s != Status::Ok) return s;
    if (Status s = ensureTexture(lumaB_, fw, fh, GL_R8, GL_RED, GL_UNSIGNED_BYTE, GL_NEAREST, error); s != Status::Ok) return s;
    if (Status s = ensureTexture(slot->texture, fw, fh, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, GL_LINEAR, error); s != Status::Ok) return s;

    glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_);
    glDisable(GL_BLEND);
    glBindVertexArray(vao_);
    glViewport(0, 0, fw, fh);
    glUseProgram(lumaProgram_);
    glUniform2f(lumaSizeLoc_, static_cast<float>(fw), static_cast<float>(fh));
    glUniform1i(lumaTapsLoc_, taps);
    glActiveTexture(GL_TEXTURE0);
    const struct {
        unsigned source;
        SizedTexture* target;
    } lumaPasses[2] = {{texA, &lumaA_}, {texB, &lumaB_}};
    for (const auto& pass : lumaPasses) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, pass.target->id, 0);
        glBindTexture(GL_TEXTURE_2D, pass.source);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, slot->texture.id, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return repairFail(error, "flow framebuffer incomplete");
    glUseProgram(flowProgram_);
    glUniform2i(flowSizeLoc_, fw, fh);
    glUniform1i(flowRadiusLoc_, q.radius);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, lumaA_.id);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, lumaB_.id);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glActiveTexture(GL_TEXTURE0);

    slot->a = idA;
    slot->b = idB;
    slot->quality = quality;
    slot->stamp = ++flowStamp_;
    *flow = &slot->texture;
    return Status::Ok;
}

Status GlPipeline::interpolateFrames(const LayerDraw& layer, unsigned texA, unsigned texB, size_t slot, unsigned* result,
                                     Error* error) {
    if (Status s = ensureRepairResources(error); s != Status::Ok) return s;
    const int quality = interpQuality_;
    SizedTexture* flow = nullptr;
    if (quality > 0) {
        if (Status s = flowBetween(layer, texA, texB, quality, &flow, error); s != Status::Ok) return s;
    }
    const int srcW = std::max(1, static_cast<int>(layer.frame->width()));
    const int srcH = std::max(1, static_cast<int>(layer.frame->height()));
    const double scale = std::min(1.0, static_cast<double>(kMaxInterpSide) / std::max(srcW, srcH));
    const int w = std::max(8, static_cast<int>(std::lround(srcW * scale)));
    const int h = std::max(8, static_cast<int>(std::lround(srcH * scale)));
    if (interpOuts_.size() <= slot) interpOuts_.resize(slot + 1);
    SizedTexture& out = interpOuts_[slot];
    if (Status s = ensureTexture(out, w, h, GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV, GL_LINEAR, error); s != Status::Ok) {
        return s;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, out.id, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return repairFail(error, "interpolation framebuffer incomplete");
    glDisable(GL_BLEND);
    glBindVertexArray(vao_);
    glViewport(0, 0, w, h);
    glUseProgram(interpProgram_);
    glUniform1f(interpTLoc_, std::clamp(layer.blendMix, 0.0f, 1.0f));
    glUniform1i(interpUseFlowLoc_, flow != nullptr ? 1 : 0);
    if (flow != nullptr) glUniform2f(interpFlowSizeLoc_, static_cast<float>(flow->width), static_cast<float>(flow->height));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texA);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, texB);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, flow != nullptr ? flow->id : 0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glActiveTexture(GL_TEXTURE0);
    *result = out.id;
    return Status::Ok;
}

Status GlPipeline::reduceMeanLuma(unsigned texture, SizedTexture& out, Error* error) {
    if (Status s = ensureRepairResources(error); s != Status::Ok) return s;
    if (Status s = ensureTexture(reduceCells_, 16, 16, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, GL_NEAREST, error); s != Status::Ok) return s;
    if (Status s = ensureTexture(out, 1, 1, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, GL_NEAREST, error); s != Status::Ok) return s;
    glBindFramebuffer(GL_FRAMEBUFFER, fxFbo_);
    glDisable(GL_BLEND);
    glBindVertexArray(vao_);
    glUseProgram(reduceProgram_);
    glActiveTexture(GL_TEXTURE0);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, reduceCells_.id, 0);
    glViewport(0, 0, 16, 16);
    glUniform1i(reduceModeLoc_, 0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, out.id, 0);
    glViewport(0, 0, 1, 1);
    glUniform1i(reduceModeLoc_, 1);
    glBindTexture(GL_TEXTURE_2D, reduceCells_.id);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    return Status::Ok;
}

}  // namespace uv::render
