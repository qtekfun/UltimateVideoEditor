#include "timeline_view/timeline_renderer.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <iterator>
#include <map>
#include <set>
#include <vector>

#include "thumbnail/thumb_atlas.h"
#include "thumbnail/thumbnail_service.h"
#include "thumbnail/tile_math.h"

#define LOG_TAG "uv_timeline"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace uv::timeline {

namespace {

struct Color {
    float r, g, b, a;
};

constexpr Color kBackground{0.075f, 0.082f, 0.10f, 1.0f};
constexpr Color kLaneA{0.105f, 0.115f, 0.14f, 1.0f};
constexpr Color kLaneB{0.125f, 0.135f, 0.16f, 1.0f};
constexpr Color kRuler{0.14f, 0.15f, 0.18f, 1.0f};
constexpr Color kTick{0.55f, 0.58f, 0.65f, 1.0f};
constexpr Color kPlayhead{1.0f, 0.30f, 0.28f, 1.0f};
constexpr Color kSelection{1.0f, 0.85f, 0.25f, 1.0f};
constexpr Color kWaveScrim{0.0f, 0.0f, 0.0f, 0.5f};
constexpr Color kKeyframe{1.0f, 0.78f, 0.1f, 1.0f};
constexpr Color kSpeedLabel{1.0f, 1.0f, 1.0f, 0.95f};
constexpr Color kFxBadge{0.35f, 0.85f, 0.95f, 1.0f};
// A clip whose media cannot be read: a red veil with darker stripes so it reads as broken, not selected.
constexpr Color kMissingTint{0.85f, 0.15f, 0.15f, 0.45f};
constexpr Color kMissingStripe{0.35f, 0.0f, 0.0f, 0.55f};
constexpr Color kTransitionBand{1.0f, 1.0f, 1.0f, 0.38f};
constexpr Color kTransitionCut{1.0f, 1.0f, 1.0f, 0.95f};
constexpr Color kDropInsert{1.0f, 0.78f, 0.1f, 1.0f};
constexpr Color kDropInsertGlow{1.0f, 0.78f, 0.1f, 0.28f};
constexpr Color kDropOverwrite{1.0f, 0.42f, 0.2f, 0.38f};
constexpr Color kDropOverwriteEdge{1.0f, 0.42f, 0.2f, 0.95f};
constexpr Color kDropNewLane{0.3f, 0.9f, 0.5f, 0.3f};
constexpr Color kDropNewLaneEdge{0.3f, 0.9f, 0.5f, 0.95f};
constexpr Color kDropCancel{0.9f, 0.2f, 0.2f, 0.24f};
constexpr Color kMarker{1.0f, 0.45f, 0.8f, 1.0f};
constexpr Color kMarkerLine{1.0f, 0.45f, 0.8f, 0.35f};
constexpr Color kBeat{0.4f, 0.95f, 0.8f, 0.9f};

constexpr size_t kAtlasBudgetBytes = 8u * 1024u * 1024u;  // hard ceiling for thumbnail texture memory
constexpr size_t kUploadsPerFrame = 6;                     // keeps a frame cheap while tiles stream in
constexpr float kWaveStripFraction = 0.38f;                // share of the clip body used by the waveform over thumbnails

Color clipColor(TrackType t) {
    switch (t) {
        case TrackType::Video: return {0.18f, 0.42f, 0.78f, 1.0f};
        case TrackType::Audio: return {0.16f, 0.55f, 0.38f, 1.0f};
        case TrackType::Title: return {0.55f, 0.32f, 0.72f, 1.0f};
    }
    return {0.4f, 0.4f, 0.4f, 1.0f};
}

Color scaled(Color c, float k) { return {c.r * k, c.g * k, c.b * k, c.a}; }

// 3x5 pixel glyphs for 0-9, ':', 'x', '.', '<' (reverse) and '|' (freeze); bit 14 = top-left, row-major.
constexpr uint16_t kGlyphs[15] = {
    0b111101101101111, 0b010110010010111, 0b111001111100111, 0b111001111001111,
    0b101101111001001, 0b111100111001111, 0b111100111101111, 0b111001001001001,
    0b111101111101111, 0b111101111001111, 0b000010000010000, 0b101101010101101,
    0b000000000000010, 0b001010100010001, 0b010010010010010,
};

constexpr const char* kVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 aColor;
uniform vec2 uSize;
out vec4 vColor;
void main() {
    gl_Position = vec4(aPos.x / uSize.x * 2.0 - 1.0, 1.0 - aPos.y / uSize.y * 2.0, 0.0, 1.0);
    vColor = aColor;
}
)";

constexpr const char* kFragmentShader = R"(#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 outColor;
void main() { outColor = vColor; }
)";

constexpr const char* kTexVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
uniform vec2 uSize;
out vec2 vUV;
void main() {
    gl_Position = vec4(aPos.x / uSize.x * 2.0 - 1.0, 1.0 - aPos.y / uSize.y * 2.0, 0.0, 1.0);
    vUV = aUV;
}
)";

// Thumbnails are dimmed slightly so the clip header and the waveform on top stay legible.
constexpr const char* kTexFragmentShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUV;
out vec4 outColor;
void main() { outColor = vec4(texture(uTex, vUV).rgb * 0.88, 1.0); }
)";

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

}  // namespace

// ---------------------------------------------------------------------------------------------
// Shared state (guarded by TimelineRenderer::mutex_)
// ---------------------------------------------------------------------------------------------
struct TimelineRenderer::State {
    std::shared_ptr<const TimelineSnapshot> snapshot = std::make_shared<TimelineSnapshot>();
    Viewport vp;
    Layout layout = Layout::forDensity(1.0f);
    float density = 1.0f;
    int width = 0, height = 0;
    int64_t playhead = 0;
    DropHint dropHint;
    float flingVelocity = 0.0f;  // px/s
    bool flingStarted = false;
    bool dirty = true;
    // True until the user zooms by hand. While true the zoom follows the whole timeline: it is
    // refitted on resize and whenever fitToContent() is called.
    bool autoFit = true;
    std::weak_ptr<thumb::ThumbnailService> thumbs;

    ANativeWindow* requestedWindow = nullptr;
    bool windowRequestPending = false;
    uint64_t windowAckGeneration = 0;
    bool looperReady = false;

    void clampViewport() {
        vp.viewWidth = std::max(1, width);
        vp.clamp(snapshot->endFrame(), layout.contentHeight(static_cast<int>(snapshot->tracks.size())), height);
    }
};

// ---------------------------------------------------------------------------------------------
// GL painter (render thread only)
// ---------------------------------------------------------------------------------------------
class TimelineRenderer::Gl {
public:
    ~Gl() { destroy(); }

    bool attach(ANativeWindow* window) {
        if (display_ == EGL_NO_DISPLAY && !initDisplay()) return false;
        surface_ = eglCreateWindowSurface(display_, config_, window, nullptr);
        if (surface_ == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
            return false;
        }
        if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
            LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
            detach();
            return false;
        }
        eglSwapInterval(display_, 1);
        if (program_ == 0 && !initResources()) {
            detach();
            return false;
        }
        return true;
    }

    void detach() {
        if (display_ == EGL_NO_DISPLAY) return;
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }

    bool hasSurface() const { return surface_ != EGL_NO_SURFACE; }

    void destroy() {
        if (display_ == EGL_NO_DISPLAY) return;
        if (surface_ != EGL_NO_SURFACE && context_ != EGL_NO_CONTEXT) {
            eglMakeCurrent(display_, surface_, surface_, context_);
            releaseResources();
        }
        detach();
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
        context_ = EGL_NO_CONTEXT;
    }

    // ---- frame drawing ----
    void beginFrame(int width, int height) {
        width_ = static_cast<float>(width);
        height_ = static_cast<float>(height);
        verts_.clear();
        glViewport(0, 0, width, height);
        glClearColor(kBackground.r, kBackground.g, kBackground.b, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        setClip(0, 0, width_, height_);
    }

    bool endFrame() {
        flush();
        if (!eglSwapBuffers(display_, surface_)) {
            LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
            return false;
        }
        return true;
    }

    void setClip(float x0, float y0, float x1, float y1) {
        clip_[0] = std::max(0.0f, x0);
        clip_[1] = std::max(0.0f, y0);
        clip_[2] = std::min(width_, x1);
        clip_[3] = std::min(height_, y1);
    }

    void rect(float x0, float y0, float x1, float y1, Color c) {
        x0 = std::max(x0, clip_[0]);
        y0 = std::max(y0, clip_[1]);
        x1 = std::min(x1, clip_[2]);
        y1 = std::min(y1, clip_[3]);
        if (x1 <= x0 || y1 <= y0) return;
        const float quad[6][2] = {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (const auto& p : quad) {
            verts_.insert(verts_.end(), {p[0], p[1], c.r, c.g, c.b, c.a});
        }
        if (verts_.size() > 6 * 20000) flush();
    }

    void drawNumber(const char* text, float x, float y, float scale, Color c) {
        for (const char* p = text; *p != '\0'; ++p) {
            int g = -1;
            if (*p >= '0' && *p <= '9') g = *p - '0';
            else if (*p == ':') g = 10;
            else if (*p == 'x') g = 11;
            else if (*p == '.') g = 12;
            else if (*p == '<') g = 13;
            else if (*p == '|') g = 14;
            if (g >= 0) {
                for (int row = 0; row < 5; ++row) {
                    for (int col = 0; col < 3; ++col) {
                        if ((kGlyphs[g] >> (14 - (row * 3 + col))) & 1) {
                            rect(x + col * scale, y + row * scale, x + (col + 1) * scale, y + (row + 1) * scale, c);
                        }
                    }
                }
            }
            x += 4 * scale;
        }
    }

    thumb::ThumbAtlas& atlas() { return atlas_; }

    // Queues a textured quad from the thumbnail atlas, clipped like rect().
    void texQuad(float x0, float y0, float x1, float y1, const float uv[4]) {
        const float w = x1 - x0, h = y1 - y0;
        if (w <= 0.0f || h <= 0.0f) return;
        const float cx0 = std::max(x0, clip_[0]), cy0 = std::max(y0, clip_[1]);
        const float cx1 = std::min(x1, clip_[2]), cy1 = std::min(y1, clip_[3]);
        if (cx1 <= cx0 || cy1 <= cy0) return;
        const float du = uv[2] - uv[0], dv = uv[3] - uv[1];
        const float u0 = uv[0] + (cx0 - x0) / w * du, u1 = uv[0] + (cx1 - x0) / w * du;
        const float v0 = uv[1] + (cy0 - y0) / h * dv, v1 = uv[1] + (cy1 - y0) / h * dv;
        const float quad[6][4] = {{cx0, cy0, u0, v0}, {cx1, cy0, u1, v0}, {cx0, cy1, u0, v1},
                                  {cx1, cy0, u1, v0}, {cx1, cy1, u1, v1}, {cx0, cy1, u0, v1}};
        for (const auto& p : quad) tverts_.insert(tverts_.end(), {p[0], p[1], p[2], p[3]});
    }

    // Draws the queued tiles. Coloured geometry queued earlier is flushed first so it stays underneath.
    void flushTiles() {
        if (tverts_.empty()) return;
        flush();
        if (texProgram_ != 0 && atlas_.ready()) {
            glUseProgram(texProgram_);
            glUniform2f(texSizeLoc_, width_, height_);
            glUniform1i(texSamplerLoc_, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, atlas_.texture());
            glBindVertexArray(texVao_);
            glBindBuffer(GL_ARRAY_BUFFER, texVbo_);
            glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(tverts_.size() * sizeof(float)), tverts_.data(),
                         GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(tverts_.size() / 4));
        }
        tverts_.clear();
    }

    void flush() {
        if (verts_.empty()) return;
        glUseProgram(program_);
        glUniform2f(sizeLoc_, width_, height_);
        glBindVertexArray(vao_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(verts_.size() * sizeof(float)), verts_.data(),
                     GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(verts_.size() / 6));
        verts_.clear();
    }

private:
    bool initDisplay() {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) {
            LOGE("eglInitialize failed: 0x%x", eglGetError());
            display_ = EGL_NO_DISPLAY;
            return false;
        }
        const EGLint attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLint count = 0;
        if (!eglChooseConfig(display_, attribs, &config_, 1, &count) || count < 1) {
            LOGE("no EGL ES3 config: 0x%x", eglGetError());
            return false;
        }
        const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs);
        if (context_ == EGL_NO_CONTEXT) {
            LOGE("eglCreateContext failed: 0x%x", eglGetError());
            return false;
        }
        return true;
    }

    bool initResources() {
        const GLuint vs = compile(GL_VERTEX_SHADER, kVertexShader);
        const GLuint fs = compile(GL_FRAGMENT_SHADER, kFragmentShader);
        if (vs == 0 || fs == 0) return false;
        program_ = glCreateProgram();
        glAttachShader(program_, vs);
        glAttachShader(program_, fs);
        glLinkProgram(program_);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint ok = 0;
        glGetProgramiv(program_, GL_LINK_STATUS, &ok);
        if (!ok) {
            LOGE("program link failed");
            glDeleteProgram(program_);
            program_ = 0;
            return false;
        }
        sizeLoc_ = glGetUniformLocation(program_, "uSize");
        glGenVertexArrays(1, &vao_);
        glGenBuffers(1, &vbo_);
        glBindVertexArray(vao_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 6 * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 6 * sizeof(float),
                              reinterpret_cast<const void*>(2 * sizeof(float)));
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        const bool coloured = glGetError() == GL_NO_ERROR;
        initThumbnailResources();  // optional: a failure only disables thumbnails
        return coloured;
    }

    void initThumbnailResources() {
        const GLuint vs = compile(GL_VERTEX_SHADER, kTexVertexShader);
        const GLuint fs = compile(GL_FRAGMENT_SHADER, kTexFragmentShader);
        if (vs == 0 || fs == 0) return;
        texProgram_ = glCreateProgram();
        glAttachShader(texProgram_, vs);
        glAttachShader(texProgram_, fs);
        glLinkProgram(texProgram_);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked = 0;
        glGetProgramiv(texProgram_, GL_LINK_STATUS, &linked);
        if (!linked) {
            LOGE("thumbnail program link failed; thumbnails disabled");
            glDeleteProgram(texProgram_);
            texProgram_ = 0;
            return;
        }
        texSizeLoc_ = glGetUniformLocation(texProgram_, "uSize");
        texSamplerLoc_ = glGetUniformLocation(texProgram_, "uTex");
        glGenVertexArrays(1, &texVao_);
        glGenBuffers(1, &texVbo_);
        glBindVertexArray(texVao_);
        glBindBuffer(GL_ARRAY_BUFFER, texVbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                              reinterpret_cast<const void*>(2 * sizeof(float)));
        if (!atlas_.init(kAtlasBudgetBytes)) LOGE("thumbnail atlas unavailable; thumbnails disabled");
    }

    void releaseResources() {
        atlas_.release();
        if (texVbo_ != 0) glDeleteBuffers(1, &texVbo_);
        if (texVao_ != 0) glDeleteVertexArrays(1, &texVao_);
        if (texProgram_ != 0) glDeleteProgram(texProgram_);
        texVbo_ = texVao_ = texProgram_ = 0;
        if (vbo_ != 0) glDeleteBuffers(1, &vbo_);
        if (vao_ != 0) glDeleteVertexArrays(1, &vao_);
        if (program_ != 0) glDeleteProgram(program_);
        vbo_ = vao_ = 0;
        program_ = 0;
    }

    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    GLuint program_ = 0, vao_ = 0, vbo_ = 0;
    GLint sizeLoc_ = -1;
    GLuint texProgram_ = 0, texVao_ = 0, texVbo_ = 0;
    GLint texSizeLoc_ = -1, texSamplerLoc_ = -1;
    thumb::ThumbAtlas atlas_;
    float width_ = 0, height_ = 0;
    float clip_[4] = {0, 0, 0, 0};
    std::vector<float> verts_;
    std::vector<float> tverts_;
};

// ---------------------------------------------------------------------------------------------
// TimelineRenderer
// ---------------------------------------------------------------------------------------------
namespace {

struct RenderThreadCtx {
    TimelineRenderer::Gl* gl = nullptr;
    ANativeWindow* window = nullptr;
    AChoreographer* choreographer = nullptr;
    bool framePosted = false;
    int64_t lastFrameNanos = 0;
};

thread_local RenderThreadCtx* t_ctx = nullptr;

}  // namespace

TimelineRenderer::TimelineRenderer(float density, WaveformLookup lookup)
    : state_(std::make_unique<State>()), lookup_(std::move(lookup)) {
    state_->density = density;
    state_->layout = Layout::forDensity(density);
    thread_ = std::thread([this] { threadMain(); });
    std::unique_lock<std::mutex> lock(mutex_);
    cv_.wait(lock, [this] { return state_->looperReady; });
}

TimelineRenderer::~TimelineRenderer() {
    quit_.store(true);
    wake();
    if (thread_.joinable()) thread_.join();
    if (state_->requestedWindow != nullptr) ANativeWindow_release(state_->requestedWindow);
}

void TimelineRenderer::wake() {
    if (looper_ != nullptr) ALooper_wake(looper_);
}

void TimelineRenderer::surfaceCreated(ANativeWindow* window) {
    ANativeWindow_acquire(window);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_->requestedWindow != nullptr) ANativeWindow_release(state_->requestedWindow);
        state_->requestedWindow = window;
        state_->windowRequestPending = true;
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::surfaceChanged(int width, int height) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // After a rotation or resize the EGL window surface keeps the buffer geometry and
        // transform it had, and the compositor rejects those buffers (the timeline stays blank
        // or stuck at the old size). Recreating the EGL surface for the same window fixes it.
        const bool resized = state_->width > 0 && (state_->width != width || state_->height != height);
        if (resized && state_->requestedWindow != nullptr) state_->windowRequestPending = true;
        state_->width = width;
        state_->height = height;
        state_->vp.viewWidth = std::max(1, width);
        if (state_->autoFit) state_->vp.fitTo(state_->snapshot->endFrame());
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::surfaceDestroyed() {
    std::unique_lock<std::mutex> lock(mutex_);
    if (state_->requestedWindow != nullptr) {
        ANativeWindow_release(state_->requestedWindow);
        state_->requestedWindow = nullptr;
    }
    state_->windowRequestPending = true;
    const uint64_t target = state_->windowAckGeneration + 1;
    wake();
    // The render thread acks after dropping its EGL surface; bounded so a wedged thread cannot ANR.
    if (!cv_.wait_for(lock, std::chrono::seconds(2), [&] { return state_->windowAckGeneration >= target; })) {
        LOGE("render thread did not release the window in time");
    }
}

void TimelineRenderer::setSnapshot(std::shared_ptr<const TimelineSnapshot> snapshot) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const bool firstContent = state_->snapshot->tracks.empty() && !snapshot->tracks.empty();
        state_->snapshot = std::move(snapshot);
        // The base lane is at the bottom of the stack: when the stack is taller than the panel, open scrolled to it.
        if (firstContent) state_->vp.scrollY = 1.0e9;
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::setThumbnails(std::weak_ptr<thumb::ThumbnailService> service) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->thumbs = std::move(service);
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::setDropHint(const DropHint& hint) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->dropHint = hint;
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::setLaneScale(float scale) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->layout = Layout::forDensity(state_->density, scale);
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::setPlayhead(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->playhead = std::max<int64_t>(0, frame);
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::ensureVisible(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!state_->vp.ensureVisible(std::max<int64_t>(0, frame))) return;
        state_->flingVelocity = 0.0f;
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::scrollBy(float dx, float dy) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->flingVelocity = 0.0f;  // a new drag cancels any running fling
        state_->vp.scrollX += dx;
        state_->vp.scrollY += dy;
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::zoomBy(float factor, float focusX) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->vp.zoomAt(factor, focusX);
        state_->autoFit = false;  // the user chose a zoom; stop overriding it
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::fitToContent() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->autoFit = true;
        state_->vp.viewWidth = std::max(1, state_->width);
        state_->vp.fitTo(state_->snapshot->endFrame());
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

bool TimelineRenderer::isAutoFit() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return state_->autoFit;
}

void TimelineRenderer::fling(float velocityX) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->flingVelocity = velocityX;
        state_->flingStarted = true;
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::invalidate() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->dirty = true;
    }
    wake();
}

HitResult TimelineRenderer::hitTest(float x, float y) const {
    std::shared_ptr<const TimelineSnapshot> snap;
    Viewport vp;
    Layout layout;
    int64_t playhead = 0;
    int width = 0, height = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap = state_->snapshot;
        vp = state_->vp;
        layout = state_->layout.anchoredBottom(static_cast<int>(snap->tracks.size()), static_cast<float>(state_->height));
        playhead = state_->playhead;
        width = state_->width;
        height = state_->height;
    }
    // A finger that has left the panel is far from every valid drop: the caller treats it as a cancel.
    if (x < 0.0f || y < 0.0f || x > static_cast<float>(width) || y > static_cast<float>(height)) {
        HitResult outside;
        outside.kind = HitKind::Outside;
        outside.frame = std::max<int64_t>(0, vp.xToFrame(x));
        return outside;
    }
    return uv::timeline::hitTest(*snap, vp, layout, x, y, playhead);
}

void TimelineRenderer::onFrame(int64_t frameTimeNanos, void* data) {
    static_cast<TimelineRenderer*>(data)->frame(frameTimeNanos);
}

void TimelineRenderer::threadMain() {
    looper_ = ALooper_prepare(0);
    Gl gl;
    RenderThreadCtx ctx;
    ctx.gl = &gl;
    ctx.choreographer = AChoreographer_getInstance();
    t_ctx = &ctx;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->looperReady = true;
    }
    cv_.notify_all();

    while (!quit_.load()) {
        ALooper_pollOnce(-1, nullptr, nullptr, nullptr);

        // Window attach/detach requests.
        bool windowRequest = false;
        ANativeWindow* requested = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            windowRequest = state_->windowRequestPending;
            if (windowRequest) {
                requested = state_->requestedWindow;
                if (requested != nullptr) ANativeWindow_acquire(requested);
                state_->windowRequestPending = false;
            }
        }
        if (windowRequest) {
            gl.detach();
            if (ctx.window != nullptr) ANativeWindow_release(ctx.window);
            ctx.window = requested;  // reference taken above (may be null on destroy)
            if (requested != nullptr && !gl.attach(requested)) {
                LOGE("could not attach EGL surface; timeline stays blank");
            }
            {
                std::lock_guard<std::mutex> lock(mutex_);
                ++state_->windowAckGeneration;
            }
            cv_.notify_all();
        }

        bool needFrame;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            needFrame = state_->dirty || state_->flingVelocity != 0.0f;
        }
        if (needFrame && gl.hasSurface() && !ctx.framePosted) {
            ctx.framePosted = true;
            AChoreographer_postFrameCallback64(ctx.choreographer, &TimelineRenderer::onFrame, this);
        }
    }

    gl.detach();
    if (ctx.window != nullptr) ANativeWindow_release(ctx.window);
    t_ctx = nullptr;
}

void TimelineRenderer::frame(int64_t frameTimeNanos) {
    RenderThreadCtx& ctx = *t_ctx;
    ctx.framePosted = false;
    if (!ctx.gl->hasSurface()) return;

    // Snapshot of shared state for this frame; fling advances the real viewport.
    std::shared_ptr<const TimelineSnapshot> snap;
    Viewport vp;
    Layout layout;
    int width, height;
    int64_t playhead;
    DropHint dropHint;
    bool keepAnimating = false;
    std::weak_ptr<thumb::ThumbnailService> thumbWeak;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        State& s = *state_;
        if (s.flingStarted) {
            ctx.lastFrameNanos = 0;
            s.flingStarted = false;
        }
        if (s.flingVelocity != 0.0f) {
            const double dt = ctx.lastFrameNanos == 0
                                  ? 1.0 / 60.0
                                  : std::min(0.05, static_cast<double>(frameTimeNanos - ctx.lastFrameNanos) * 1e-9);
            const double before = s.vp.scrollX;
            s.vp.scrollX += static_cast<double>(s.flingVelocity) * dt;
            s.clampViewport();
            s.flingVelocity = static_cast<float>(s.flingVelocity * std::exp(-4.0 * dt));
            if (std::fabs(s.flingVelocity) < 30.0f || s.vp.scrollX == before) s.flingVelocity = 0.0f;
            keepAnimating = s.flingVelocity != 0.0f;
        }
        ctx.lastFrameNanos = frameTimeNanos;
        s.dirty = false;
        snap = s.snapshot;
        vp = s.vp;
        layout = s.layout.anchoredBottom(static_cast<int>(snap->tracks.size()), static_cast<float>(s.height));
        dropHint = s.dropHint;
        width = s.width;
        height = s.height;
        playhead = s.playhead;
        thumbWeak = s.thumbs;
    }
    if (width <= 0 || height <= 0) return;

    Gl& g = *ctx.gl;
    const std::shared_ptr<thumb::ThumbnailService> thumbs = thumbWeak.lock();
    thumb::ThumbAtlas& atlas = g.atlas();
    bool moreTilesReady = false;
    if (thumbs && atlas.ready()) {
        // Finished tiles go into the atlas here, on the GL thread, a few per frame.
        atlas.beginFrame();
        std::vector<thumb::ThumbnailService::ReadyTile> ready;
        moreTilesReady = thumbs->takeReady(&ready, kUploadsPerFrame) > 0;
        for (const auto& tile : ready) atlas.upload(tile.key, tile.pixels.data());
    }
    struct Wanted {
        int level = 0;
        std::set<int64_t> indices;
    };
    std::map<int64_t, Wanted> wanted;
    std::vector<thumb::CellPlan> cells;
    const float density = layout.rulerHeight / 28.0f;
    g.beginFrame(width, height);
    const float W = static_cast<float>(width), H = static_cast<float>(height);

    // Lanes and clips live below the ruler.
    g.setClip(0, layout.rulerHeight, W, H);
    const int trackCount = static_cast<int>(snap->tracks.size());
    for (int t = 0; t < trackCount; ++t) {
        const float top = layout.trackTop(t) - static_cast<float>(vp.scrollY);
        if (top + layout.trackHeight < layout.rulerHeight || top > H) continue;
        g.rect(0, top, W, top + layout.trackHeight, (t % 2 == 0) ? kLaneA : kLaneB);
    }

    const float colW = std::max(1.0f, 2.0f * density);
    for (const ClipSnapshot& c : snap->clips) {
        const float top = layout.trackTop(c.trackIndex) - static_cast<float>(vp.scrollY);
        const float bottom = top + layout.trackHeight;
        if (bottom < layout.rulerHeight || top > H) continue;
        const double x0 = vp.frameToX(c.startFrame);
        const double x1 = vp.frameToX(c.startFrame + c.durationFrames);
        if (x1 < 0 || x0 > W) continue;

        const TrackType type = snap->tracks[static_cast<size_t>(c.trackIndex)].type;
        const Color base = clipColor(type);
        const float fx0 = static_cast<float>(x0) + 1.0f, fx1 = static_cast<float>(x1) - 1.0f;
        g.setClip(0, layout.rulerHeight, W, H);
        const float header = 14.0f * density;
        g.rect(fx0, top, fx1, bottom, base);
        g.rect(fx0, top, fx1, top + header, scaled(base, 0.7f));

        // Thumbnail filmstrip across the body. Cells are drawn from whatever tile is already in the
        // atlas (the exact one, else a finer/coarser one); missing exact tiles are requested.
        const float bodyTop = top + header;
        const RetimeSnapshot* retime = snap->retimeOf(c.clipKey);
        bool hasThumbs = false;
        if (type == TrackType::Video && c.assetKey >= 0 && thumbs && atlas.ready() && thumbs->isActive(c.assetKey)) {
            hasThumbs = true;
            const float bodyH = bottom - bodyTop;
            const thumb::ClipCellParams params{c.assetKey, x0, x1, 0.0, static_cast<double>(W),
                                               static_cast<double>(bodyH) * thumb::kTileAspect, vp.pxPerFrame,
                                               snap->fpsNum, snap->fpsDen, c.sourceInFrame, c.sourceFpsNum, c.sourceFpsDen,
                                               retime != nullptr ? retime->sourceSpanFrames : 0, c.durationFrames,
                                               retime != nullptr && retime->reverse(), retime != nullptr && retime->freeze()};
            thumb::planClipCells(params, &cells);
            Wanted& want = wanted[c.assetKey];
            g.setClip(std::max(0.0f, fx0), std::max(layout.rulerHeight, bodyTop), std::min(W, fx1), bottom);
            for (const thumb::CellPlan& cell : cells) {
                thumb::TileKey got;
                float uv[4];
                const bool resolved = thumb::resolveTile(
                    cell.key, [&atlas](const thumb::TileKey& k) { return atlas.contains(k); }, &got);
                if (resolved && atlas.find(got, uv)) {
                    g.texQuad(static_cast<float>(cell.x0), bodyTop, static_cast<float>(cell.x1), bottom, uv);
                }
                if (!resolved || !(got == cell.key)) {
                    want.level = cell.key.level;
                    want.indices.insert(cell.key.index);
                }
            }
            g.flushTiles();
            g.setClip(0, layout.rulerHeight, W, H);
        }

        // Waveform from the cached peaks, anchored to content so it does not shimmer while scrolling.
        if (c.assetKey >= 0 && lookup_ && !(retime != nullptr && retime->freeze())) {
            if (auto peaks = lookup_(c.assetKey)) {
                // Without thumbnails the waveform gets the whole body below the header; with them it
                // sits in a strip along the bottom, over a scrim so it reads against the pictures.
                const float wTop = hasThumbs ? bottom - kWaveStripFraction * (bottom - bodyTop) : top + header;
                const float mid = (wTop + bottom) * 0.5f;
                const float half = (bottom - wTop) * 0.5f - 1.0f;
                g.setClip(std::max(0.0f, fx0), std::max(layout.rulerHeight, top), std::min(W, fx1), bottom);
                if (hasThumbs) g.rect(fx0, wTop, fx1, bottom, kWaveScrim);
                const double ppf = vp.pxPerFrame;
                const int64_t firstCol = static_cast<int64_t>(std::floor((std::max(0.0f, fx0) + vp.scrollX) / colW));
                const int64_t lastCol = static_cast<int64_t>(std::floor((std::min(W, fx1) + vp.scrollX) / colW));
                const Color wave = scaled(base, 1.6f);
                const float reference = audio::referenceLevel(*peaks);
                for (int64_t col = firstCol; col <= lastCol; ++col) {
                    const int64_t f0 = static_cast<int64_t>(std::floor(col * colW / ppf)) - c.startFrame;
                    const int64_t f1 = static_cast<int64_t>(std::floor((col + 1) * colW / ppf)) - c.startFrame;
                    const int64_t r0 = std::clamp<int64_t>(f0, 0, c.durationFrames);
                    const int64_t r1 = std::clamp<int64_t>(std::max(f1, f0 + 1), 0, c.durationFrames);
                    if (r1 <= r0) continue;
                    const int64_t rate = peaks->sampleRate;
                    // A retimed clip covers its source range at its average speed (a reversed one from the end).
                    const int64_t a = retimeBoundary(retime, c.durationFrames, r0);
                    const int64_t b = retimeBoundary(retime, c.durationFrames, r1);
                    const int64_t s0 = (c.sourceInFrame + std::min(a, b)) * rate * c.sourceFpsDen / c.sourceFpsNum;
                    int64_t s1 = (c.sourceInFrame + std::max(a, b)) * rate * c.sourceFpsDen / c.sourceFpsNum;
                    s1 = std::max(s1, s0 + 1);
                    int16_t mm[2];
                    audio::queryPeaks(*peaks, s0, s1, 1, mm);
                    // Normalised to the media's loudest sample so quiet audio still has visible shape.
                    const float lo = std::min(0.0f, audio::displayAmplitude(mm[0] / 32768.0f, reference));
                    const float hi = std::max(0.0f, audio::displayAmplitude(mm[1] / 32768.0f, reference));
                    const float x = static_cast<float>(col * colW - vp.scrollX);
                    g.rect(x, mid - hi * half - 0.5f, x + colW, mid - lo * half + 0.5f, wave);
                }
                g.setClip(0, layout.rulerHeight, W, H);
            }
        }

        // Keyframe markers: small diamonds in the clip's header strip, at their time in the clip.
        {
            const auto [first, last] = snap->keyframesOf(c.clipKey);
            const float cy = top + header * 0.5f;
            const float s = std::min(3.0f * density, header * 0.45f);
            for (const KeyframeSnapshot* k = first; k != last; ++k) {
                const float x = static_cast<float>(vp.frameToX(c.startFrame + k->frame));
                if (x < fx0 || x > fx1) continue;
                g.rect(x - s * 0.34f, cy - s, x + s * 0.34f, cy + s, kKeyframe);
                g.rect(x - s * 0.67f, cy - s * 0.67f, x + s * 0.67f, cy + s * 0.67f, kKeyframe);
                g.rect(x - s, cy - s * 0.34f, x + s, cy + s * 0.34f, kKeyframe);
            }
        }

        // Speed label at the right end of the header: "2x", "0.5x", "<" for reverse, "||" for a freeze.
        if (retime != nullptr) {
            char label[24];
            if (retime->freeze()) {
                std::snprintf(label, sizeof(label), "||");
            } else {
                const double speed = static_cast<double>(retime->sourceSpanFrames) / static_cast<double>(c.durationFrames);
                char number[16] = "";
                if (std::fabs(speed - 1.0) >= 0.005) {
                    if (std::fabs(speed - std::round(speed)) < 0.005) {
                        std::snprintf(number, sizeof(number), "%dx", static_cast<int>(std::lround(speed)));
                    } else {
                        std::snprintf(number, sizeof(number), "%.2f", speed);
                        // 0.50 -> 0.5: drop trailing zeros, then add the multiplication sign.
                        size_t n = std::strlen(number);
                        while (n > 0 && number[n - 1] == '0') number[--n] = '\0';
                        if (n < sizeof(number) - 1) number[n] = 'x';
                        if (n < sizeof(number) - 1) number[n + 1] = '\0';
                    }
                }
                std::snprintf(label, sizeof(label), "%s%s", retime->reverse() ? "<" : "", number);
            }
            const float gs = std::max(1.0f, header * 0.5f / 5.0f);
            const float width = static_cast<float>(std::strlen(label)) * 4.0f * gs;
            if (label[0] != '\0' && fx1 - fx0 > width + 8.0f * density) {
                g.setClip(std::max(0.0f, fx0), std::max(layout.rulerHeight, top), std::min(W, fx1), bottom);
                g.drawNumber(label, fx1 - width - (c.hasFx ? 12.0f : 3.0f) * density, top + (header - 5.0f * gs) * 0.5f, gs, kSpeedLabel);
                g.setClip(0, layout.rulerHeight, W, H);
            }
        }

        // Effects marker: a small badge at the right end of the header strip, "fx" drawn as two bars.
        if (c.hasFx) {
            const float cy = top + header * 0.5f;
            const float s = std::min(3.0f * density, header * 0.45f);
            const float right = fx1 - s * 1.5f;
            if (right - s * 4.0f > fx0) {
                g.rect(right - s * 3.0f, cy - s, right - s * 2.0f, cy + s, kFxBadge);
                g.rect(right - s * 1.5f, cy - s, right - s * 0.5f, cy + s, kFxBadge);
                g.rect(right - s * 3.0f, cy - s * 0.2f, right - s * 0.5f, cy + s * 0.2f, kFxBadge);
            }
        }

        if (c.missing) {
            g.setClip(std::max(0.0f, fx0), std::max(layout.rulerHeight, top), std::min(W, fx1), bottom);
            g.rect(fx0, top, fx1, bottom, kMissingTint);
            // Stripes stepped down the clip approximate a diagonal hatch with plain rectangles.
            const float step = std::max(6.0f, 8.0f * density);
            const float bar = std::max(1.0f, 1.5f * density);
            const int rows = 6;
            const float rowH = (bottom - top) / static_cast<float>(rows);
            for (int row = 0; row < rows; ++row) {
                const float y0 = top + rowH * static_cast<float>(row);
                const float offset = step * static_cast<float>(row) / static_cast<float>(rows);
                for (float x = fx0 - step + offset; x < fx1; x += step) {
                    g.rect(x, y0, x + bar, y0 + rowH, kMissingStripe);
                }
            }
            g.setClip(0, layout.rulerHeight, W, H);
        }

        if (c.selected) {
            const float b = std::max(1.0f, 2.0f * density);
            g.rect(fx0, top, fx1, top + b, kSelection);
            g.rect(fx0, bottom - b, fx1, bottom, kSelection);
            g.rect(fx0, top, fx0 + b, bottom, kSelection);
            g.rect(fx1 - b, top, fx1, bottom, kSelection);
        }
    }

    // Transitions: a translucent band over the span both clips cross-fade in, with a bright line at the cut.
    g.setClip(0, layout.rulerHeight, W, H);
    for (const TransitionSnapshot& t : snap->transitions) {
        const float top = layout.trackTop(t.trackIndex) - static_cast<float>(vp.scrollY);
        const float bottom = top + layout.trackHeight;
        if (bottom < layout.rulerHeight || top > H) continue;
        const float x0 = static_cast<float>(vp.frameToX(t.cutFrame - t.preFrames));
        const float x1 = static_cast<float>(vp.frameToX(t.cutFrame + t.postFrames));
        const float xc = static_cast<float>(vp.frameToX(t.cutFrame));
        if (x1 < 0 || x0 > W) continue;
        const float lineW = std::max(1.0f, 1.5f * density);
        g.rect(x0, top, std::max(x1, x0 + 2.0f * lineW), bottom, kTransitionBand);
        g.rect(xc - lineW * 0.5f, top, xc + lineW * 0.5f, bottom, kTransitionCut);
    }

    // Tell the service what is missing now; an empty set also drops requests the user scrolled past.
    if (thumbs) {
        for (const auto& [assetKey, want] : wanted) {
            thumbs->want(assetKey, want.level, std::vector<int64_t>(want.indices.begin(), want.indices.end()));
        }
    }

    // Drop indicator: what releasing the dragged clip would do.
    if (dropHint.kind != DropHintKind::None) {
        g.setClip(0, layout.rulerHeight, W, H);
        const float bar = std::max(2.0f, 3.0f * density);
        const HintRect hr = dropHintRect(dropHint, vp, layout, W, H, static_cast<int>(snap->tracks.size()), bar);
        if (hr.valid) {
            const float edge = std::max(1.0f, 2.0f * density);
            switch (dropHint.kind) {
                case DropHintKind::Insert: {
                    const float cx = (hr.x0 + hr.x1) * 0.5f;
                    g.rect(cx - 7.0f * density, hr.y0, cx + 7.0f * density, hr.y1, kDropInsertGlow);
                    g.rect(hr.x0, hr.y0 - 4.0f * density, hr.x1, hr.y1 + 4.0f * density, kDropInsert);
                    // A small arrow pointing down into the gap that will open.
                    for (int i = 0; i < 3; ++i) {
                        const float half = (7.0f - 2.5f * static_cast<float>(i)) * density;
                        const float y = hr.y0 + (4.0f + 3.0f * static_cast<float>(i)) * density;
                        g.rect(cx - half, y, cx + half, y + 3.0f * density, kDropInsert);
                    }
                    break;
                }
                case DropHintKind::Overwrite:
                    g.rect(hr.x0, hr.y0, hr.x1, hr.y1, kDropOverwrite);
                    g.rect(hr.x0, hr.y0, hr.x1, hr.y0 + edge, kDropOverwriteEdge);
                    g.rect(hr.x0, hr.y1 - edge, hr.x1, hr.y1, kDropOverwriteEdge);
                    g.rect(hr.x0, hr.y0, hr.x0 + edge, hr.y1, kDropOverwriteEdge);
                    g.rect(hr.x1 - edge, hr.y0, hr.x1, hr.y1, kDropOverwriteEdge);
                    break;
                case DropHintKind::NewLane: {
                    g.rect(hr.x0, hr.y0, hr.x1, hr.y1, kDropNewLane);
                    g.rect(hr.x0, hr.y0, hr.x1, hr.y0 + edge, kDropNewLaneEdge);
                    g.rect(hr.x0, hr.y1 - edge, hr.x1, hr.y1, kDropNewLaneEdge);
                    const float cx = W * 0.5f, cy = (hr.y0 + hr.y1) * 0.5f, arm = 10.0f * density;
                    g.rect(cx - arm, cy - edge * 0.5f, cx + arm, cy + edge * 0.5f, kDropNewLaneEdge);
                    g.rect(cx - edge * 0.5f, cy - arm, cx + edge * 0.5f, cy + arm, kDropNewLaneEdge);
                    break;
                }
                case DropHintKind::Cancel:
                    g.rect(hr.x0, hr.y0, hr.x1, hr.y1, kDropCancel);
                    break;
                default:
                    break;
            }
        }
    }

    // A faint line through the lanes at each user marker, so cuts can be lined up against it.
    g.setClip(0, layout.rulerHeight, W, H);
    for (const auto& m : snap->markers) {
        if (m.beat()) continue;
        const float x = static_cast<float>(vp.frameToX(m.frame));
        if (x < -2.0f) continue;
        if (x > W + 2.0f) break;
        g.rect(x, layout.rulerHeight, x + std::max(1.0f, density), H, kMarkerLine);
    }

    // Ruler on top so clips scroll underneath it.
    g.setClip(0, 0, W, H);
    g.rect(0, 0, W, layout.rulerHeight, kRuler);
    {
        const int64_t fps = std::max<int64_t>(1, (snap->fpsNum + snap->fpsDen / 2) / snap->fpsDen);
        const int64_t candidates[] = {1, 2, 5, 10, fps, 2 * fps, 5 * fps, 10 * fps, 15 * fps, 30 * fps,
                                      60 * fps, 120 * fps, 300 * fps, 600 * fps, 1800 * fps, 3600 * fps};
        const double minPx = 80.0 * density;
        int64_t step = candidates[std::size(candidates) - 1];
        int64_t prev = 0;
        for (int64_t cand : candidates) {
            if (cand <= prev) continue;
            prev = cand;
            if (static_cast<double>(cand) * vp.pxPerFrame >= minPx) {
                step = cand;
                break;
            }
        }
        const int64_t first = std::max<int64_t>(0, vp.xToFrame(0) / step) * step;
        const float scale = std::max(1.0f, std::round(1.5f * density));
        for (int64_t f = first;; f += step) {
            const float x = static_cast<float>(vp.frameToX(f));
            if (x > W) break;
            g.rect(x, layout.rulerHeight * 0.55f, x + 1.0f, layout.rulerHeight, kTick);
            if (step % 2 == 0) {
                const float xm = static_cast<float>(vp.frameToX(f + step / 2));
                g.rect(xm, layout.rulerHeight * 0.8f, xm + 1.0f, layout.rulerHeight, kTick);
            }
            char label[24];
            if (step % fps == 0) {
                const int64_t secs = f / fps;
                std::snprintf(label, sizeof(label), "%lld:%02lld", static_cast<long long>(secs / 60),
                              static_cast<long long>(secs % 60));
            } else {
                std::snprintf(label, sizeof(label), "%lld", static_cast<long long>(f));
            }
            g.drawNumber(label, x + 3.0f * density, layout.rulerHeight * 0.12f, scale, kTick);
        }
    }

    // Markers on the ruler: user markers are tall with a flag, detected beats are short ticks. Beats
    // closer than a few pixels are skipped so a fast song zoomed out stays readable.
    {
        const float w = std::max(1.0f, std::round(1.5f * density));
        float lastBeatX = -1.0e9f;
        for (const auto& m : snap->markers) {
            const float x = static_cast<float>(vp.frameToX(m.frame));
            if (x < -w) continue;
            if (x > W + w) break;
            if (m.beat()) {
                if (x - lastBeatX < 3.0f * density) continue;
                lastBeatX = x;
                g.rect(x, layout.rulerHeight * 0.62f, x + w * 0.67f, layout.rulerHeight, kBeat);
            } else {
                g.rect(x, layout.rulerHeight * 0.30f, x + w, layout.rulerHeight, kMarker);
                g.rect(x, layout.rulerHeight * 0.30f, x + 6.0f * density, layout.rulerHeight * 0.30f + 5.0f * density, kMarker);
            }
        }
    }

    // Playhead.
    {
        const float x = static_cast<float>(vp.frameToX(playhead));
        const float w = std::max(1.0f, 2.0f * density);
        g.rect(x - w * 0.5f, 0, x + w * 0.5f, H, kPlayhead);
        g.rect(x - 6.0f * density, 0, x + 6.0f * density, 8.0f * density, kPlayhead);
    }

    if (!g.endFrame()) return;

    bool dirtyAgain;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        dirtyAgain = state_->dirty || state_->flingVelocity != 0.0f;
    }
    if (keepAnimating || dirtyAgain || moreTilesReady) {
        ctx.framePosted = true;
        AChoreographer_postFrameCallback64(ctx.choreographer, &TimelineRenderer::onFrame, this);
    }
}

}  // namespace uv::timeline
