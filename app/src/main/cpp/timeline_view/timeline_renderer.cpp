#include "timeline_view/timeline_renderer.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <sys/system_properties.h>

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
#include "core/fade_math.h"
#include "timeline_view/audio_shaping.h"
#include "timeline_view/fade_curve.h"
#include "timeline_view/glyphs.h"
#include "timeline_view/lane_header.h"
#include "timeline_view/marker_style.h"
#include "timeline_view/ruler_ticks.h"
#include "timeline_view/snap_guide.h"
#include "timeline_view/text_atlas.h"
#include "timeline_view/timeline_theme.h"
#include "timeline_view/wave_columns.h"

#define LOG_TAG "uv_timeline"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace uv::timeline {

namespace {

using Color = Rgba;

// Colours that do not come from the app palette: they carry a meaning of their own on every theme (drop targets,
// transitions, a missing file), so they stay fixed. Everything else is in TimelineTheme (timeline_theme.h).
constexpr Color kMarqueeAlpha{0.0f, 0.0f, 0.0f, 0.16f};  // fill alpha of the marquee over the theme's primary colour
constexpr Color kWaveScrim{0.0f, 0.0f, 0.0f, 0.5f};
constexpr Color kSpeedLabel{1.0f, 1.0f, 1.0f, 0.95f};
constexpr Color kClipLabel{1.0f, 1.0f, 1.0f, 0.92f};
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
constexpr Color kMarkerLineAlpha{0.0f, 0.0f, 0.0f, 0.35f};
constexpr Color kHeaderDragging{0.36f, 0.27f, 0.04f, 0.96f};
constexpr Color kHeaderMute{1.0f, 0.36f, 0.36f, 1.0f};
constexpr Color kHeaderSolo{1.0f, 0.84f, 0.25f, 1.0f};
constexpr Color kLaneDragBar{1.0f, 0.78f, 0.1f, 1.0f};
constexpr Color kLaneDragGlow{1.0f, 0.78f, 0.1f, 0.25f};
constexpr Color kBeat{0.4f, 0.95f, 0.8f, 0.9f};
constexpr Color kSnapGuide{1.0f, 0.3f, 0.78f, 0.95f};      // the line at the frame a dragged edge snapped to
constexpr Color kSnapGuideGlow{1.0f, 0.3f, 0.78f, 0.22f};
constexpr Color kDragShadow{0.0f, 0.0f, 0.0f, 1.0f};       // rgb of the soft shadow under a dragged block; alpha comes from shadowLayer()
constexpr float kDragShadowPeak = 0.5f;                    // opacity of the shadow next to the block

constexpr int kLabelAtlasW = 2048;                          // text atlas: 2048 x 1024 RGBA (8 MB), see text_atlas.h
constexpr int kLabelAtlasH = 1024;
constexpr size_t kLabelUploadBytesPerFrame = 512u * 1024u;  // bitmaps copied to the GPU per frame, at least one
constexpr size_t kLabelPendingBytes = 16u * 1024u * 1024u;  // queued bitmaps; senders wait past this
constexpr int kLabelMaxDim = 1700;                          // widest/tallest bitmap accepted
constexpr size_t kAtlasBudgetBytes = 8u * 1024u * 1024u;  // hard ceiling for thumbnail texture memory
constexpr size_t kUploadsPerFrame = 6;                     // keeps a frame cheap while tiles stream in
constexpr float kMinLabelHeaderDp = 9.0f;                   // header strip height below which clip names are not drawn
constexpr float kMinThumbBodyDp = 12.0f;                    // clip body height below which the filmstrip is not drawn
constexpr float kWaveStripFraction = 0.38f;                // share of the clip body used by the waveform over thumbnails

// The block colour: by what the clip is (photo, sticker, multicam) when the snapshot says, else by lane type.
Color clipColor(const TimelineTheme& th, TrackType t, ClipKind kind = ClipKind::Default) {
    switch (kind) {
        case ClipKind::Image: return th.clipImage;
        case ClipKind::Sticker: return th.clipSticker;
        case ClipKind::Multicam: return th.clipMulticam;
        case ClipKind::Default: break;
    }
    switch (t) {
        case TrackType::Video: return th.clipVideo;
        case TrackType::Audio: return th.clipAudio;
        case TrackType::Title: return th.clipTitle;
    }
    return th.clipVideo;
}

// Text size classes (text_atlas.h): the regular size for text in the body of a block, a small one for names in the header
// strip and on the ruler, and a bold one for lane names and timecodes. Kotlin makes the bitmaps (TimelineText.kt).
constexpr int kTextClassLabel = 0;
constexpr int kTextClassSmall = 1;
constexpr int kTextClassBold = 2;

// The size class of a clip's label: a title's text and a sticker's name sit in the body of the block, the name of a media
// clip in its header strip. Kotlin applies the same rule when it makes the bitmaps (labelNeeds in TimelineText.kt).
int labelClassOf(const TimelineSnapshot& snap, const ClipSnapshot& c) {
    const bool body = snap.tracks[static_cast<size_t>(c.trackIndex)].type == TrackType::Title || c.kind == ClipKind::Sticker;
    return body ? kTextClassLabel : kTextClassSmall;
}

Color withAlpha(Color c, float a) { return {c.r, c.g, c.b, a}; }
Color mix(Color a, Color b, float t) { return {a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, a.a + (b.a - a.a) * t}; }

Color scaled(Color c, float k) { return {c.r * k, c.g * k, c.b * k, c.a}; }

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
layout(location = 2) in float aAlpha;
uniform vec2 uSize;
out vec2 vUV;
out float vAlpha;
void main() {
    gl_Position = vec4(aPos.x / uSize.x * 2.0 - 1.0, 1.0 - aPos.y / uSize.y * 2.0, 0.0, 1.0);
    vUV = aUV;
    vAlpha = aAlpha;
}
)";

// Thumbnails are dimmed slightly so the clip header and the waveform on top stay legible. The per-vertex alpha fades a
// tile in over the block colour when it has just been uploaded (fade_curve.h); it is 1 for every settled tile.
constexpr const char* kTexFragmentShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUV;
in float vAlpha;
out vec4 outColor;
void main() { outColor = vec4(texture(uTex, vUV).rgb * 0.88, vAlpha); }
)";

// Text: one quad per bitmap from the label atlas. The bitmap is white-on-transparent (premultiplied) and is tinted by the
// vertex colour; a coloured glyph (emoji) is flagged by a negative alpha and drawn as it is. Premultiplied output.
constexpr const char* kTextVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec4 aColor;
uniform vec2 uSize;
out vec2 vUV;
out vec4 vColor;
void main() {
    gl_Position = vec4(aPos.x / uSize.x * 2.0 - 1.0, 1.0 - aPos.y / uSize.y * 2.0, 0.0, 1.0);
    vUV = aUV;
    vColor = aColor;
}
)";

constexpr const char* kTextFragmentShader = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUV;
in vec4 vColor;
out vec4 outColor;
void main() {
    vec4 t = texture(uTex, vUV);
    float coloured = step(vColor.a, 0.0);
    vec4 mono = vec4(vColor.rgb * t.a, t.a);
    outColor = mix(mono, t, coloured) * abs(vColor.a);
}
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

// A text bitmap waiting for the render thread to place it in the atlas and upload it.
struct PendingLabel {
    uint64_t hash;
    int w, h;
    bool colour;
    std::vector<uint8_t> pixels;
};

// ---------------------------------------------------------------------------------------------
// Shared state (guarded by TimelineRenderer::mutex_)
// ---------------------------------------------------------------------------------------------
struct TimelineRenderer::State {
    TimelineTheme theme = TimelineTheme::dark();
    std::vector<PendingLabel> pendingLabels;  // oldest first
    size_t pendingLabelBytes = 0;
    std::shared_ptr<const TimelineSnapshot> snapshot = std::make_shared<TimelineSnapshot>();
    Viewport vp;
    Layout layout = Layout::forDensity(1.0f);
    float density = 1.0f;
    int width = 0, height = 0;
    int64_t playhead = 0;
    DropHint dropHint;
    int64_t snapGuideFrame = kNoSnapGuide;  // the frame a dragged edge snapped to, or kNoSnapGuide
    std::vector<int64_t> draggedKeys;       // keys of the clips being dragged or trimmed, sorted
    std::vector<uint64_t> evictedLabels;    // text bitmaps the atlas dropped, waiting for Kotlin to take them
    int laneDragFrom = -1, laneDragTo = -1;  // lane header drag: the lane being moved and where it would land
    bool marqueeActive = false;
    float marqueeX0 = 0.0f, marqueeY0 = 0.0f, marqueeX1 = 0.0f, marqueeY1 = 0.0f;
    float flingVelocity = 0.0f;  // px/s
    bool flingStarted = false;
    bool dirty = true;
    // True until the user zooms by hand. While true the zoom follows the whole timeline: it is
    // refitted on resize and whenever fitToContent() is called.
    bool autoFit = true;
    // The lane height as a multiple of the default: the layout sheet's Small / Medium / Large preset and nothing else (the
    // vertical pinch zoom was removed, see DECISIONS "No vertical zoom"). Fixed until the preset changes.
    float laneScale = 1.0f;
    std::weak_ptr<thumb::ThumbnailService> thumbs;
    audio::WaveScale waveScale = audio::WaveScale::Linear;  // how waveform amplitudes become heights (the layout sheet's choice)

    ANativeWindow* requestedWindow = nullptr;
    bool windowRequestPending = false;
    uint64_t windowAckGeneration = 0;
    bool looperReady = false;

    // The audio lanes' height as a multiple of the other lanes' (the layout sheet's "Audio lane height"); 1 = the same.
    float audioFactor = 1.0f;

    void applyLaneScale(float scale, float audio) {
        laneScale = (scale == scale) ? scale : 1.0f;  // NaN keeps the default; Layout clamps the range
        audioFactor = (audio == audio) ? audio : 1.0f;
        layout = Layout::forDensity(density, laneScale, audioFactor).withHeaders(kLaneHeaderDp * density);
    }

    // The layout for one snapshot: per-lane heights (audio lanes can be taller), stack anchored to the panel bottom.
    Layout layoutFor(const TimelineSnapshot& snap) const {
        return layout.withTracks(snap.tracks).anchoredBottom(static_cast<int>(snap.tracks.size()), static_cast<float>(height));
    }

    void clampViewport() {
        vp.viewWidth = std::max(1, width);
        vp.clamp(snapshot->endFrame(), layout.withTracks(snapshot->tracks).contentHeight(static_cast<int>(snapshot->tracks.size())), height);
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
    void beginFrame(int width, int height, Color background) {
        width_ = static_cast<float>(width);
        height_ = static_cast<float>(height);
        verts_.clear();
        gverts_.clear();
        if (verts_.capacity() == 0) verts_.reserve(6 * 21000);
        if (tverts_.capacity() == 0) tverts_.reserve(kTexFloats * 6 * 512);
        if (gverts_.capacity() == 0) gverts_.reserve(8 * 6 * 512);
        draws_ = 0;
        vertCount_ = 0;
        glViewport(0, 0, width, height);
        glClearColor(background.r, background.g, background.b, 1.0f);
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
        float* v = growColoured(6);
        putColoured(v, x0, y0, c);
        putColoured(v + 6, x1, y0, c);
        putColoured(v + 12, x0, y1, c);
        putColoured(v + 18, x1, y0, c);
        putColoured(v + 24, x1, y1, c);
        putColoured(v + 30, x0, y1, c);
        if (verts_.size() > 6 * 20000) flush();
    }

    // Draws digits, ':', 'x', '.', '<', '|' and (for clip labels) letters, drawn as capitals, and '-' (see glyphs.h).
    void drawNumber(const char* text, float x, float y, float scale, Color c) {
        for (const char* p = text; *p != '\0'; ++p) {
            const uint16_t bits = glyphBits(*p);
            if (bits != 0) {
                for (int row = 0; row < 5; ++row) {
                    for (int col = 0; col < 3; ++col) {
                        if ((bits >> (14 - (row * 3 + col))) & 1) {
                            rect(x + col * scale, y + row * scale, x + (col + 1) * scale, y + (row + 1) * scale, c);
                        }
                    }
                }
            }
            x += 4 * scale;
        }
    }

    // ---- text (see text_atlas.h) ----
    // Places a bitmap in the atlas and uploads it. Room is made by evicting the least recently used bitmaps (reported to
    // Kotlin through takeEvictedLabels); only when all of them are in use is the atlas emptied (the generation moves on and
    // Kotlin sends again what it still needs). A bitmap that cannot fit even then is dropped.
    void addLabel(const PendingLabel& p) {
        if (labelTex_ == 0) return;
        int x = 0, y = 0;
        LabelTable::Result r = labels_.place(p.hash, p.w, p.h, p.colour, &x, &y);
        if (r == LabelTable::Result::Full) {
            // Everything in the atlas was used in the last two frames and there is still no room: start over.
            labels_.reset();
            r = labels_.place(p.hash, p.w, p.h, p.colour, &x, &y);
        }
        if (r != LabelTable::Result::Placed) return;
        glBindTexture(GL_TEXTURE_2D, labelTex_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, p.w, p.h, GL_RGBA, GL_UNSIGNED_BYTE, p.pixels.data());
    }

    uint32_t labelGeneration() const { return labels_.generation(); }
    void beginLabelFrame() { labels_.beginFrame(); }
    void takeEvictedLabels(std::vector<uint64_t>* out) { labels_.takeEvicted(out); }

    const LabelEntry* label(uint64_t hash) { return textProgram_ != 0 ? labels_.find(hash) : nullptr; }

    // Draws a bitmap with its top-left corner at (x, y), snapped to whole pixels so it stays sharp, cut to the clip
    // rectangle. Returns false when the bitmap is not in the atlas (yet).
    bool text(uint64_t hash, float x, float y, Color c) {
        const LabelEntry* e = label(hash);
        if (e == nullptr) return false;
        textQuad(*e, std::round(x), std::round(y), c);
        return true;
    }

    // The width of a run of single-glyph bitmaps (ruler digits, lane names); -1 when one of them is missing.
    float runWidth(const char* s, size_t n, int sizeClass) {
        float w = 0.0f;
        for (size_t i = 0; i < n; ++i) {
            const LabelEntry* e = label(labelHash(s + i, 1, sizeClass));
            if (e == nullptr) return -1.0f;
            w += static_cast<float>(e->w);
        }
        return w;
    }

    // Sets a run of single-glyph bitmaps; false (and nothing drawn) when one is missing.
    bool run(const char* s, size_t n, int sizeClass, float x, float y, Color c) {
        if (textProgram_ == 0 || n > 32) return false;
        const LabelEntry* found[32];
        for (size_t i = 0; i < n; ++i) {
            found[i] = labels_.find(labelHash(s + i, 1, sizeClass));
            if (found[i] == nullptr) return false;
        }
        x = std::round(x);
        y = std::round(y);
        for (size_t i = 0; i < n; ++i) {
            textQuad(*found[i], x, y, c);
            x += static_cast<float>(found[i]->w);
        }
        return true;
    }

    // Draws the queued text over everything queued so far.
    void flushText() {
        if (gverts_.empty()) return;
        flush();
        if (textProgram_ != 0 && labelTex_ != 0) {
            glUseProgram(textProgram_);
            glUniform2f(textSizeLoc_, width_, height_);
            glUniform1i(textSamplerLoc_, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, labelTex_);
            glBindVertexArray(textVao_);
            glBindBuffer(GL_ARRAY_BUFFER, textVbo_);
            glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(gverts_.size() * sizeof(float)), gverts_.data(), GL_STREAM_DRAW);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);  // the text shader outputs premultiplied colour
            ++draws_;
            vertCount_ += static_cast<uint32_t>(gverts_.size() / 8);
            glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(gverts_.size() / 8));
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        gverts_.clear();
    }

    // A filled triangle in the coloured batch, kept only when it lies wholly inside the clip rectangle (used for the
    // tiny corner pieces of rounded blocks, where cutting a triangle would cost more than the corner is worth).
    void triInside(float x0, float y0, float x1, float y1, float x2, float y2, Color c) {
        const float loX = std::min({x0, x1, x2}), hiX = std::max({x0, x1, x2});
        const float loY = std::min({y0, y1, y2}), hiY = std::max({y0, y1, y2});
        if (loX < clip_[0] || loY < clip_[1] || hiX > clip_[2] || hiY > clip_[3]) return;
        float* v = growColoured(3);
        putColoured(v, x0, y0, c);
        putColoured(v + 6, x1, y1, c);
        putColoured(v + 12, x2, y2, c);
    }

    // The area between a block's square corner and its rounded corner, painted in the colour behind the block. Corner
    // `quadrant`: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left; (cornerX, cornerY) is the block's corner.
    void cornerCut(float cornerX, float cornerY, float r, int quadrant, Color behind) {
        const float sx = (quadrant == 0 || quadrant == 3) ? 1.0f : -1.0f;  // direction into the block
        const float sy = (quadrant == 0 || quadrant == 1) ? 1.0f : -1.0f;
        const float cx = cornerX + sx * r, cy = cornerY + sy * r;
        float prevX = cornerX, prevY = cy;  // the arc starts on the vertical edge
        for (int i = 1; i <= kCornerSegments; ++i) {
            const float px = cx - sx * r * arc().c[i], py = cy - sy * r * arc().s[i];
            triInside(cornerX, cornerY, prevX, prevY, px, py, behind);
            prevX = px;
            prevY = py;
        }
    }

    // A filled rectangle with rounded corners of radius r: the body as three rectangles and a fan per corner.
    void roundedRect(float x0, float y0, float x1, float y1, float r, Color c) {
        r = std::max(0.0f, std::min({r, (x1 - x0) * 0.5f, (y1 - y0) * 0.5f}));
        rect(x0, y0 + r, x1, y1 - r, c);
        rect(x0 + r, y0, x1 - r, y0 + r, c);
        rect(x0 + r, y1 - r, x1 - r, y1, c);
        const float cxs[4] = {x0 + r, x1 - r, x1 - r, x0 + r};
        const float cys[4] = {y0 + r, y0 + r, y1 - r, y1 - r};
        for (int q = 0; q < 4; ++q) {
            const float sx = (q == 0 || q == 3) ? -1.0f : 1.0f;  // direction away from the block's centre
            const float sy = (q == 0 || q == 1) ? -1.0f : 1.0f;
            float prevX = cxs[q] + sx * r, prevY = cys[q];
            for (int i = 1; i <= kCornerSegments; ++i) {
                const float px = cxs[q] + sx * r * arc().c[i], py = cys[q] + sy * r * arc().s[i];
                triInside(cxs[q], cys[q], prevX, prevY, px, py, c);
                prevX = px;
                prevY = py;
            }
        }
    }

    // A rectangle that shades from `top` to `bottom`.
    void rectGradient(float x0, float y0, float x1, float y1, Color top, Color bottom) {
        const float h = y1 - y0;
        if (h <= 0.0f) return;
        const float cy0 = std::max(y0, clip_[1]), cy1 = std::min(y1, clip_[3]);
        const float cx0 = std::max(x0, clip_[0]), cx1 = std::min(x1, clip_[2]);
        if (cx1 <= cx0 || cy1 <= cy0) return;
        const Color a = mix(top, bottom, (cy0 - y0) / h), b = mix(top, bottom, (cy1 - y0) / h);
        float* v = growColoured(6);
        putColoured(v, cx0, cy0, a);
        putColoured(v + 6, cx1, cy0, a);
        putColoured(v + 12, cx0, cy1, b);
        putColoured(v + 18, cx1, cy0, a);
        putColoured(v + 24, cx1, cy1, b);
        putColoured(v + 30, cx0, cy1, b);
        if (verts_.size() > 6 * 20000) flush();
    }

    thumb::ThumbAtlas& atlas() { return atlas_; }

    // Queues a textured quad from the thumbnail atlas, clipped like rect().
    void texQuad(float x0, float y0, float x1, float y1, const float uv[4], float alpha = 1.0f) {
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
        const size_t at = tverts_.size();
        tverts_.resize(at + 6 * kTexFloats);
        float* o = tverts_.data() + at;
        for (const auto& p : quad) {
            o[0] = p[0]; o[1] = p[1]; o[2] = p[2]; o[3] = p[3]; o[4] = alpha;
            o += kTexFloats;
        }
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
            ++draws_;
            vertCount_ += static_cast<uint32_t>(tverts_.size() / kTexFloats);
            glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(tverts_.size() / kTexFloats));
        }
        tverts_.clear();
    }

    // Draw calls and vertices submitted since beginFrame(); read by the optional frame statistics.
    void frameCounters(uint32_t* draws, uint32_t* vertices) const {
        *draws = draws_;
        *vertices = vertCount_;
    }

    void flush() {
        if (verts_.empty()) return;
        ++draws_;
        vertCount_ += static_cast<uint32_t>(verts_.size() / 6);
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
    static constexpr int kCornerSegments = 3;
    static constexpr size_t kTexFloats = 5;  // thumbnail vertex: x, y, u, v, alpha

    // Appends `n` vertices to the coloured batch and returns where to write them (6 floats each: x, y, r, g, b, a).
    float* growColoured(size_t n) {
        const size_t at = verts_.size();
        verts_.resize(at + n * 6);
        return verts_.data() + at;
    }
    static void putColoured(float* v, float x, float y, const Color& c) {
        v[0] = x; v[1] = y; v[2] = c.r; v[3] = c.g; v[4] = c.b; v[5] = c.a;
    }

    // cos/sin of the corner arc steps, computed once: every rounded block uses the same angles.
    struct ArcTable {
        float c[kCornerSegments + 1], s[kCornerSegments + 1];
        ArcTable() {
            for (int i = 0; i <= kCornerSegments; ++i) {
                const float a = 1.5707963f * static_cast<float>(i) / static_cast<float>(kCornerSegments);
                c[i] = std::cos(a);
                s[i] = std::sin(a);
            }
        }
    };
    static const ArcTable& arc() {
        static const ArcTable table;
        return table;
    }

    // Queues one bitmap quad, cut to the clip rectangle. Coloured glyphs carry their flag as a negative alpha.
    void textQuad(const LabelEntry& e, float x, float y, Color c) {
        const float x1 = x + static_cast<float>(e.w), y1 = y + static_cast<float>(e.h);
        const float cx0 = std::max(x, clip_[0]), cy0 = std::max(y, clip_[1]);
        const float cx1 = std::min(x1, clip_[2]), cy1 = std::min(y1, clip_[3]);
        if (cx1 <= cx0 || cy1 <= cy0) return;
        const float du = e.u1 - e.u0, dv = e.v1 - e.v0;
        const float w = static_cast<float>(e.w), h = static_cast<float>(e.h);
        const float u0 = e.u0 + (cx0 - x) / w * du, u1 = e.u0 + (cx1 - x) / w * du;
        const float v0 = e.v0 + (cy0 - y) / h * dv, v1 = e.v0 + (cy1 - y) / h * dv;
        const float a = e.colour ? -c.a : c.a;
        const size_t at = gverts_.size();
        gverts_.resize(at + 48);
        float* o = gverts_.data() + at;
        const float q[6][4] = {{cx0, cy0, u0, v0}, {cx1, cy0, u1, v0}, {cx0, cy1, u0, v1},
                               {cx1, cy0, u1, v0}, {cx1, cy1, u1, v1}, {cx0, cy1, u0, v1}};
        for (const auto& p : q) {
            o[0] = p[0]; o[1] = p[1]; o[2] = p[2]; o[3] = p[3];
            o[4] = c.r; o[5] = c.g; o[6] = c.b; o[7] = a;
            o += 8;
        }
    }

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
        initTextResources();       // optional too: without it labels fall back to the built-in font
        return coloured;
    }

    void initTextResources() {
        const GLuint vs = compile(GL_VERTEX_SHADER, kTextVertexShader);
        const GLuint fs = compile(GL_FRAGMENT_SHADER, kTextFragmentShader);
        if (vs == 0 || fs == 0) return;
        textProgram_ = glCreateProgram();
        glAttachShader(textProgram_, vs);
        glAttachShader(textProgram_, fs);
        glLinkProgram(textProgram_);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked = 0;
        glGetProgramiv(textProgram_, GL_LINK_STATUS, &linked);
        if (!linked) {
            LOGE("text program link failed; text uses the built-in font");
            glDeleteProgram(textProgram_);
            textProgram_ = 0;
            return;
        }
        textSizeLoc_ = glGetUniformLocation(textProgram_, "uSize");
        textSamplerLoc_ = glGetUniformLocation(textProgram_, "uTex");
        glGenVertexArrays(1, &textVao_);
        glGenBuffers(1, &textVbo_);
        glBindVertexArray(textVao_);
        glBindBuffer(GL_ARRAY_BUFFER, textVbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 8 * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 8 * sizeof(float), reinterpret_cast<const void*>(2 * sizeof(float)));
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float), reinterpret_cast<const void*>(4 * sizeof(float)));
        glGenTextures(1, &labelTex_);
        glBindTexture(GL_TEXTURE_2D, labelTex_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, kLabelAtlasW, kLabelAtlasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (glGetError() != GL_NO_ERROR) {
            LOGE("text atlas texture failed; text uses the built-in font");
            glDeleteTextures(1, &labelTex_);
            labelTex_ = 0;
            glDeleteProgram(textProgram_);
            textProgram_ = 0;
            return;
        }
        labels_.reset();  // a fresh context starts a new generation: whatever Kotlin sent before is gone
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
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, kTexFloats * sizeof(float), nullptr);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, kTexFloats * sizeof(float),
                              reinterpret_cast<const void*>(2 * sizeof(float)));
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, kTexFloats * sizeof(float),
                              reinterpret_cast<const void*>(4 * sizeof(float)));
        if (!atlas_.init(kAtlasBudgetBytes)) LOGE("thumbnail atlas unavailable; thumbnails disabled");
    }

    void releaseResources() {
        if (labelTex_ != 0) glDeleteTextures(1, &labelTex_);
        if (textVbo_ != 0) glDeleteBuffers(1, &textVbo_);
        if (textVao_ != 0) glDeleteVertexArrays(1, &textVao_);
        if (textProgram_ != 0) glDeleteProgram(textProgram_);
        labelTex_ = textVbo_ = textVao_ = textProgram_ = 0;
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
    GLuint textProgram_ = 0, textVao_ = 0, textVbo_ = 0, labelTex_ = 0;
    GLint textSizeLoc_ = -1, textSamplerLoc_ = -1;
    LabelTable labels_{kLabelAtlasW, kLabelAtlasH};
    std::vector<float> gverts_;  // text quads: x, y, u, v, r, g, b, a
    float width_ = 0, height_ = 0;
    uint32_t draws_ = 0, vertCount_ = 0;
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
    // Optional frame statistics (system property debug.uveditor.timeline_stats=1): CPU time to build and submit a
    // frame, up to but not including the buffer swap, and the draw calls and vertices per frame.
    std::vector<PendingLabel> uploads;  // text bitmaps taken from the shared queue for this frame (kept to reuse its storage)
    std::vector<int64_t> dragKeys;      // this frame's copy of the dragged clips' keys (kept to reuse its storage)
    std::vector<uint64_t> evicted;      // text bitmaps the atlas evicted this frame
    uint32_t labelResend = 0;           // bumped when the eviction backlog overflowed: Kotlin must send everything again
    bool statsOn = false;
    std::vector<float> statMs;
    double statDraws = 0.0, statVerts = 0.0, statUploadMs = 0.0, statUploads = 0.0;
};

thread_local RenderThreadCtx* t_ctx = nullptr;

}  // namespace

TimelineRenderer::TimelineRenderer(float density, WaveformLookup lookup)
    : state_(std::make_unique<State>()), lookup_(std::move(lookup)) {
    state_->density = density;
    state_->layout = Layout::forDensity(density).withHeaders(kLaneHeaderDp * density);
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

void TimelineRenderer::setDragOverlay(int64_t snapGuideFrame, const int64_t* clipKeys, size_t count) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const int64_t guide = snapGuideFrame < 0 ? kNoSnapGuide : snapGuideFrame;
        std::vector<int64_t>& keys = state_->draggedKeys;
        // Called on every drag step, nearly always with the same keys: only a change needs a frame.
        const bool sameKeys = keys.size() == count && (count == 0 || std::is_permutation(keys.begin(), keys.end(), clipKeys));
        if (guide == state_->snapGuideFrame && sameKeys) return;
        state_->snapGuideFrame = guide;
        keys.assign(clipKeys, clipKeys + count);
        std::sort(keys.begin(), keys.end());
        state_->dirty = true;
    }
    wake();
}

size_t TimelineRenderer::takeEvictedLabels(uint64_t* out, size_t capacity) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<uint64_t>& list = state_->evictedLabels;
    const size_t n = std::min(capacity, list.size());
    std::copy(list.begin(), list.begin() + static_cast<std::ptrdiff_t>(n), out);
    list.erase(list.begin(), list.begin() + static_cast<std::ptrdiff_t>(n));
    return n;
}

void TimelineRenderer::setLaneDrag(int from, int to) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->laneDragFrom = from;
        state_->laneDragTo = to;
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::setMarquee(bool active, float x0, float y0, float x1, float y1) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->marqueeActive = active;
        state_->marqueeX0 = x0;
        state_->marqueeY0 = y0;
        state_->marqueeX1 = x1;
        state_->marqueeY1 = y1;
        state_->dirty = true;
    }
    wake();
}

std::vector<int64_t> TimelineRenderer::clipsInRect(float x0, float y0, float x1, float y1) const {
    std::shared_ptr<const TimelineSnapshot> snap;
    Viewport vp;
    Layout layout;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snap = state_->snapshot;
        vp = state_->vp;
        layout = state_->layoutFor(*snap);
    }
    return uv::timeline::clipsInRect(*snap, vp, layout, x0, y0, x1, y1);
}

void TimelineRenderer::setLaneScale(float scale, float audioFactor) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->applyLaneScale(scale, audioFactor);
        state_->clampViewport();
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::setWaveformScale(int scale) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const audio::WaveScale next = audio::waveScaleFromInt(scale);
        if (state_->waveScale == next) return;
        state_->waveScale = next;
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::setPalette(const uint32_t* argb, size_t count) {
    if (argb == nullptr || count != kNativeColourCount) return;  // a mismatch keeps the colours it had
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_->theme.assign(argb);
        state_->dirty = true;
    }
    wake();
}

void TimelineRenderer::putLabel(uint64_t hash, int w, int h, bool colour, const uint8_t* rgba) {
    if (rgba == nullptr || w <= 0 || h <= 0 || w > kLabelMaxDim || h > kLabelMaxDim) return;
    PendingLabel p{hash, w, h, colour, std::vector<uint8_t>(rgba, rgba + static_cast<size_t>(w) * static_cast<size_t>(h) * 4)};
    {
        std::unique_lock<std::mutex> lock(mutex_);
        // Backpressure on the sender (the text thread), never on the render thread.
        cv_.wait_for(lock, std::chrono::milliseconds(200),
                     [this] { return state_->pendingLabelBytes < kLabelPendingBytes || quit_.load(); });
        state_->pendingLabelBytes += p.pixels.size();
        state_->pendingLabels.push_back(std::move(p));
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

void TimelineRenderer::followContent() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!state_->autoFit) return;
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
        layout = state_->layoutFor(*snap);
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
        char prop[PROP_VALUE_MAX] = {0};
        ctx.statsOn = __system_property_get("debug.uveditor.timeline_stats", prop) > 0 && prop[0] == '1';
    }
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
    const auto frameStart = std::chrono::steady_clock::now();

    // Snapshot of shared state for this frame; fling advances the real viewport.
    std::shared_ptr<const TimelineSnapshot> snap;
    Viewport vp;
    Layout layout;
    TimelineTheme th;
    int width, height;
    int64_t playhead;
    DropHint dropHint;
    int64_t snapGuide = kNoSnapGuide;
    int laneDragFrom = -1, laneDragTo = -1;
    bool marqueeActive = false;
    float marquee[4] = {0, 0, 0, 0};
    bool keepAnimating = false;
    bool moreLabels = false;
    bool freedLabels = false;
    std::weak_ptr<thumb::ThumbnailService> thumbWeak;
    audio::WaveScale waveScale = audio::WaveScale::Linear;
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
        layout = s.layoutFor(*snap);
        th = s.theme;
        dropHint = s.dropHint;
        snapGuide = s.snapGuideFrame;
        ctx.dragKeys.assign(s.draggedKeys.begin(), s.draggedKeys.end());
        laneDragFrom = s.laneDragFrom;
        laneDragTo = s.laneDragTo;
        marqueeActive = s.marqueeActive;
        marquee[0] = s.marqueeX0;
        marquee[1] = s.marqueeY0;
        marquee[2] = s.marqueeX1;
        marquee[3] = s.marqueeY1;
        width = s.width;
        height = s.height;
        playhead = s.playhead;
        thumbWeak = s.thumbs;
        waveScale = s.waveScale;
        // Text bitmaps from Kotlin: take what the per-frame budget allows (always at least one) to place and upload.
        if (!s.pendingLabels.empty()) {
            size_t budget = kLabelUploadBytesPerFrame, take = 0, taken = 0;
            while (take < s.pendingLabels.size()) {
                const size_t bytes = s.pendingLabels[take].pixels.size();
                if (take > 0 && bytes > budget) break;
                budget -= std::min(budget, bytes);
                taken += bytes;
                ++take;
            }
            ctx.uploads.assign(std::make_move_iterator(s.pendingLabels.begin()),
                               std::make_move_iterator(s.pendingLabels.begin() + static_cast<std::ptrdiff_t>(take)));
            s.pendingLabels.erase(s.pendingLabels.begin(), s.pendingLabels.begin() + static_cast<std::ptrdiff_t>(take));
            s.pendingLabelBytes -= std::min(s.pendingLabelBytes, taken);
            moreLabels = !s.pendingLabels.empty();
            freedLabels = true;
        }
    }
    if (freedLabels) cv_.notify_all();  // a sender may be waiting for room
    Gl& g = *ctx.gl;
    g.beginLabelFrame();
    if (!ctx.uploads.empty()) {
        const auto upStart = std::chrono::steady_clock::now();
        for (const PendingLabel& p : ctx.uploads) g.addLabel(p);
        ctx.statUploadMs += std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - upStart).count();
        ctx.statUploads += ctx.uploads.size();
        ctx.uploads.clear();
    }
    g.takeEvictedLabels(&ctx.evicted);
    if (!ctx.evicted.empty()) {
        // Bitmaps the atlas made room by dropping: Kotlin takes the list and sends again what it still needs. A backlog that
        // nobody collects is cut and answered with a full resend instead of growing.
        constexpr size_t kMaxEvictedBacklog = 4096;
        std::lock_guard<std::mutex> lock(mutex_);
        std::vector<uint64_t>& list = state_->evictedLabels;
        list.insert(list.end(), ctx.evicted.begin(), ctx.evicted.end());
        if (list.size() > kMaxEvictedBacklog) {
            list.clear();
            ++ctx.labelResend;
        }
        ctx.evicted.clear();
    }
    labelGeneration_.store(g.labelGeneration() + ctx.labelResend);
    if (width <= 0 || height <= 0) return;

    const std::shared_ptr<thumb::ThumbnailService> thumbs = thumbWeak.lock();
    thumb::ThumbAtlas& atlas = g.atlas();
    bool moreTilesReady = false;
    if (thumbs && atlas.ready()) {
        // Finished tiles go into the atlas here, on the GL thread, a few per frame.
        atlas.beginFrame(frameTimeNanos);
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
    const float hair = std::max(1.0f, std::floor(density * 0.5f));  // a one-pixel line, a little thicker on very dense screens
    const Color white{1.0f, 1.0f, 1.0f, 1.0f};
    g.beginFrame(width, height, th.background);
    const float W = static_cast<float>(width), H = static_cast<float>(height);

    // A label as one bitmap at (x, band top), centred in the band; plain ASCII falls back to the built-in font until its
    // bitmap has arrived, any other text waits for it.
    auto labelText = [&](const std::string& text, int sizeClass, float x, float bandTop, float bandH, Color colour) {
        const uint64_t hash = labelHash(text, sizeClass);
        if (const LabelEntry* e = g.label(hash)) {
            g.text(hash, x, bandTop + (bandH - static_cast<float>(e->h)) * 0.5f, colour);
        } else if (isAscii(text)) {
            const float gs = std::max(1.0f, std::floor(bandH * 0.5f / 5.0f));
            g.drawNumber(text.c_str(), x, bandTop + (bandH - 5.0f * gs) * 0.5f, gs, colour);
        }
    };
    // A short run of single glyphs (ruler and lane text); returns its width in pixels.
    auto glyphWidth = [&](const char* s, size_t n, int sizeClass, float fallbackScale) {
        const float w = g.runWidth(s, n, sizeClass);
        return w >= 0.0f ? w : static_cast<float>(n) * 4.0f * fallbackScale;
    };
    auto glyphRun = [&](const char* s, size_t n, int sizeClass, float x, float bandTop, float bandH, Color colour) {
        const LabelEntry* first = n > 0 ? g.label(labelHash(s, 1, sizeClass)) : nullptr;
        if (first != nullptr && g.run(s, n, sizeClass, x, bandTop + (bandH - static_cast<float>(first->h)) * 0.5f, colour)) return;
        const std::string plain(s, n);
        const float gs = std::max(1.0f, std::floor(bandH * 0.5f / 5.0f));
        g.drawNumber(plain.c_str(), x, bandTop + (bandH - 5.0f * gs) * 0.5f, gs, colour);
    };

    // Lanes and clips live below the ruler.
    g.setClip(0, layout.rulerHeight, W, H);
    const int trackCount = static_cast<int>(snap->tracks.size());
    for (int t = 0; t < trackCount; ++t) {
        const float top = layout.trackTop(t) - static_cast<float>(vp.scrollY);
        const float laneHeight = layout.heightOf(t);
        if (top + laneHeight < layout.rulerHeight || top > H) continue;
        g.rect(0, top, W, top + laneHeight, (t % 2 == 0) ? th.laneA : th.laneB);
        // Hairlines on both edges so a lane reads as a band even where it is nearly the background colour.
        g.rect(0, top, W, top + hair, th.ruler);
        g.rect(0, top + laneHeight - hair, W, top + laneHeight, th.ruler);
    }

    const float cornerR = 4.0f * density;
    const float headerStrip = 18.0f * density;
    // While clips are dragged or trimmed they are drawn in a second pass, over the others and with a soft shadow, so they
    // read as lifted. Without a drag there is one pass and one cheap test per clip.
    const bool dragging = !ctx.dragKeys.empty();
    for (int pass = 0; pass < (dragging ? 2 : 1); ++pass)
    for (const ClipSnapshot& c : snap->clips) {
        const bool lifted = dragging && std::binary_search(ctx.dragKeys.begin(), ctx.dragKeys.end(), c.clipKey);
        if (dragging && lifted != (pass == 1)) continue;
        const float top = layout.trackTop(c.trackIndex) - static_cast<float>(vp.scrollY);
        const float bottom = top + layout.heightOf(c.trackIndex);
        if (bottom < layout.rulerHeight || top > H) continue;
        const double x0 = vp.frameToX(c.startFrame);
        const double x1 = vp.frameToX(c.startFrame + c.durationFrames);
        if (x1 < 0 || x0 > W) continue;

        const TrackType type = snap->tracks[static_cast<size_t>(c.trackIndex)].type;
        const Color base = clipColor(th, type, c.kind);
        const Color laneBg = (c.trackIndex % 2 == 0) ? th.laneA : th.laneB;
        const float fx0 = static_cast<float>(x0) + 1.0f, fx1 = static_cast<float>(x1) - 1.0f;
        g.setClip(0, layout.rulerHeight, W, H);

        // The block: a rounded outline when selected (the primary clip in the accent, the others softer), then the body
        // inside it. Content is drawn on a plain rectangle and the corners are cut back at the end, so pictures and
        // waveforms need no rounding of their own.
        float ix0 = fx0, ix1 = fx1, itop = top, ibottom = bottom;
        float radius = std::max(0.0f, std::min({cornerR, (fx1 - fx0) * 0.5f, (bottom - top) * 0.5f}));
        Color cutColour = laneBg;
        if (lifted) {
            // Soft shadow: nested rounded rectangles growing outwards, a little lower than the block.
            const float spread = 9.0f * density, drop = 3.0f * density;
            for (int i = 0; i < kShadowLayers; ++i) {
                const ShadowLayer layer = shadowLayer(i, spread, kDragShadowPeak);
                g.roundedRect(fx0 - layer.grow, top - layer.grow + drop, fx1 + layer.grow, bottom + layer.grow + drop,
                              cornerR + layer.grow, withAlpha(kDragShadow, layer.alpha));
            }
        }
        if (c.selected || lifted) {
            // A lifted clip that is not selected gets a light rim; opaque, since the corner cuts are painted in it.
            const Color outline = c.selected ? (c.primary ? th.selection : th.primary) : mix(laneBg, th.onClip, 0.7f);
            const float b = std::max(1.0f, ((c.selected && c.primary) ? 2.0f : 1.5f) * density);
            if (fx1 - fx0 > 2.0f * b + 2.0f) {
                g.roundedRect(fx0, top, fx1, bottom, radius, outline);
                ix0 += b;
                ix1 -= b;
                itop += b;
                ibottom -= b;
                radius = std::max(0.0f, radius - b);
                cutColour = outline;
            } else {
                g.rect(fx0, top, fx1, bottom, outline);
                radius = 0.0f;
            }
        }
        const float header = std::min(headerStrip, 0.42f * (ibottom - itop));
        // Below these heights a name or a filmstrip would be an unreadable sliver: the lane keeps its colour and waveform only.
        const bool roomForLabel = header >= kMinLabelHeaderDp * density;
        g.rect(ix0, itop, ix1, ibottom, base);
        g.rect(ix0, itop, ix1, itop + header, scaled(base, 0.72f));
        g.rect(ix0, itop, ix1, itop + std::max(1.0f, 0.75f * density), Color{1.0f, 1.0f, 1.0f, 0.16f});

        // A linked clip (for instance a video and the audio detached from it) carries a small chain mark at the right end of its
        // header, so the pair reads as one. Five rectangles, only for clips wide and tall enough to show it.
        if (c.linked && roomForLabel && ix1 - ix0 > 56.0f * density) {
            const float s = std::min(header * 0.55f, 9.0f * density);
            const float cy = itop + header * 0.5f;
            const float x = ix1 - 6.0f * density - 2.0f * s;
            const float t = std::max(1.0f, 1.2f * density);
            const Color mark = withAlpha(white, 0.92f);
            const Color hole = scaled(base, 0.72f);
            for (int i = 0; i < 2; ++i) {
                const float lx = x + i * 0.9f * s;
                g.rect(lx, cy - s * 0.5f, lx + 1.2f * s, cy + s * 0.5f, mark);
                g.rect(lx + t, cy - s * 0.5f + t, lx + 1.2f * s - t, cy + s * 0.5f - t, hole);
            }
            g.rect(x + 0.7f * s, cy - t * 0.5f, x + 1.4f * s, cy + t * 0.5f, mark);
        }

        // Thumbnail filmstrip across the body. Cells are drawn from whatever tile is already in the
        // atlas (the exact one, else a finer/coarser one); missing exact tiles are requested.
        const float bodyTop = itop + header;
        const RetimeSnapshot* retime = snap->retimeOf(c.clipKey);
        bool hasThumbs = false;
        if (type == TrackType::Video && c.assetKey >= 0 && ibottom - bodyTop >= kMinThumbBodyDp * density && thumbs && atlas.ready() && thumbs->isActive(c.assetKey)) {
            hasThumbs = true;
            const float bodyH = ibottom - bodyTop;
            const thumb::ClipCellParams params{c.assetKey, x0, x1, 0.0, static_cast<double>(W),
                                               static_cast<double>(bodyH) * thumb::kTileAspect, vp.pxPerFrame,
                                               snap->fpsNum, snap->fpsDen, c.sourceInFrame, c.sourceFpsNum, c.sourceFpsDen,
                                               retime != nullptr ? retime->sourceSpanFrames : 0, c.durationFrames,
                                               retime != nullptr && retime->reverse(), retime != nullptr && retime->freeze()};
            thumb::planClipCells(params, &cells);
            Wanted& want = wanted[c.assetKey];
            g.setClip(std::max(0.0f, ix0), std::max(layout.rulerHeight, bodyTop), std::min(W, ix1), ibottom);
            for (const thumb::CellPlan& cell : cells) {
                thumb::TileKey got;
                float uv[4];
                int64_t readyAt = 0;
                const bool resolved = thumb::resolveTile(
                    cell.key, [&atlas](const thumb::TileKey& k) { return atlas.contains(k); }, &got);
                if (resolved && atlas.find(got, uv, &readyAt)) {
                    // A tile that has just arrived fades in. The picture it replaces (a coarser or finer tile of the same
                    // instant) stays underneath while it does, so the cell never dips to the plain block colour.
                    const float alpha = readyAt > 0 ? fadeAlpha(frameTimeNanos - readyAt, kThumbFadeNanos) : 1.0f;
                    if (alpha < 1.0f) {
                        keepAnimating = true;
                        thumb::TileKey under;
                        float uvUnder[4];
                        if (thumb::resolveTile(
                                cell.key, [&atlas, &got](const thumb::TileKey& k) { return !(k == got) && atlas.contains(k); }, &under) &&
                            atlas.find(under, uvUnder)) {
                            g.texQuad(static_cast<float>(cell.x0), bodyTop, static_cast<float>(cell.x1), ibottom, uvUnder);
                        }
                    }
                    g.texQuad(static_cast<float>(cell.x0), bodyTop, static_cast<float>(cell.x1), ibottom, uv, alpha);
                }
                if (!resolved || !(got == cell.key)) {
                    want.level = cell.key.level;
                    want.indices.insert(cell.key.index);
                }
            }
            g.flushTiles();
            g.setClip(0, layout.rulerHeight, W, H);
        }

        // Waveform from the cached peaks, anchored to content so it does not shimmer while scrolling. Per column (see
        // wave_columns.h): a solid light envelope growing from the bottom edge over a fainter peak layer, one centre line across.
        // A video clip whose own sound was detached has none to show: its waveform now lives on the audio clip.
        if (c.assetKey >= 0 && lookup_ && !c.audioDetached && !(retime != nullptr && retime->freeze())) {
            if (auto peaks = lookup_(c.assetKey)) {
                // Without thumbnails the waveform gets the whole body below the header; with them it
                // sits in a strip along the bottom, over a scrim so it reads against the pictures.
                const float wTop = hasThumbs ? ibottom - kWaveStripFraction * (ibottom - bodyTop) : bodyTop;
                const float mid = (wTop + ibottom) * 0.5f;
                const float visLeft = std::max(0.0f, ix0), visRight = std::min(W, ix1);
                g.setClip(visLeft, std::max(layout.rulerHeight, itop), visRight, ibottom);
                // Solid light envelope (one quad per column, anchored at the bottom edge) over a fainter peak layer, on a
                // darker body: strong contrast with no outline halo. See wave_columns.h.
                const float areaH = std::max(1.0f, ibottom - wTop - hair);
                const Color envelope = hasThumbs ? withAlpha(mix(base, white, 0.85f), 0.92f) : mix(base, white, kWaveFillMix);
                const Color peakLayer = withAlpha(hasThumbs ? white : mix(base, white, kWavePeakMix), kWavePeakAlpha);
                const Color centre = withAlpha(hasThumbs ? white : mix(base, white, kWaveCentreMix), hasThumbs ? 0.4f : 0.55f);
                if (hasThumbs) {
                    g.rect(ix0, wTop, ix1, ibottom, kWaveScrim);
                } else {
                    g.rect(ix0, wTop, ix1, ibottom, scaled(base, kWaveBodyDim));
                }
                if (visRight > visLeft) {
                    const double ppf = vp.pxPerFrame;
                    const float colW = waveColumnWidth(visRight - visLeft, density);
                    const int64_t firstCol = waveFirstColumn(visLeft, vp.scrollX, colW);
                    const int64_t lastCol = waveLastColumn(visRight, vp.scrollX, colW);
                    const int64_t rate = peaks->sampleRate;
                    // The clip's own loudest level is the reference, so a quiet clip is lifted to a legible size.
                    const int64_t srcSpan = retime != nullptr ? retime->sourceSpanFrames : c.durationFrames;
                    const int64_t refStart = c.sourceInFrame * rate * c.sourceFpsDen / c.sourceFpsNum;
                    const int64_t refEnd = (c.sourceInFrame + srcSpan) * rate * c.sourceFpsDen / c.sourceFpsNum;
                    const float reference = audio::referenceLevel(*peaks, refStart, refEnd);
                    for (int64_t col = firstCol; col <= lastCol; ++col) {
                        int64_t s0, s1;
                        if (retime == nullptr) {
                            // Sub-frame positions: zoomed in, a column is a fraction of a frame.
                            s0 = waveSampleAtColumn(col, colW, vp.scrollX, ppf, c.startFrame, c.durationFrames, c.sourceInFrame, rate,
                                                    c.sourceFpsNum, c.sourceFpsDen);
                            s1 = waveSampleAtColumn(col + 1, colW, vp.scrollX, ppf, c.startFrame, c.durationFrames, c.sourceInFrame,
                                                    rate, c.sourceFpsNum, c.sourceFpsDen);
                        } else {
                            // A retimed clip covers its source range at its average speed (a reversed one from the end).
                            const int64_t f0 = static_cast<int64_t>(std::floor(col * colW / ppf)) - c.startFrame;
                            const int64_t f1 = static_cast<int64_t>(std::floor((col + 1) * colW / ppf)) - c.startFrame;
                            const int64_t r0 = std::clamp<int64_t>(f0, 0, c.durationFrames);
                            const int64_t r1 = std::clamp<int64_t>(std::max(f1, f0 + 1), 0, c.durationFrames);
                            if (r1 <= r0) continue;
                            const int64_t a = retimeBoundary(retime, c.durationFrames, r0);
                            const int64_t b = retimeBoundary(retime, c.durationFrames, r1);
                            s0 = (c.sourceInFrame + std::min(a, b)) * rate * c.sourceFpsDen / c.sourceFpsNum;
                            s1 = (c.sourceInFrame + std::max(a, b)) * rate * c.sourceFpsDen / c.sourceFpsNum;
                        }
                        if (s1 <= s0 && retime == nullptr) continue;  // a column outside the clip (both ends clamp to the same sample)
                        const WaveColumn wc = waveColumn(*peaks, s0, s1, reference, waveScale);
                        const float x = static_cast<float>(col * colW - vp.scrollX);
                        const float yFill = ibottom - wc.fill * areaH;
                        const float yPeak = ibottom - wc.peak * areaH;
                        if (yPeak < yFill - 0.5f) g.rect(x, yPeak, x + colW, yFill, peakLayer);
                        // Never less than the baseline pixel, so silence is a thin flat line, not a gap.
                        g.rect(x, std::min(yFill, ibottom - hair), x + colW, ibottom, envelope);
                    }
                }
                // The thin centre line across the area, over the envelope (the reference the owner's example has).
                g.rect(ix0, mid - hair * 0.5f, ix1, mid + hair * 0.5f, centre);
                g.setClip(0, layout.rulerHeight, W, H);
            }
        }

        // Sound shaping: the fade ramps and the volume curve, over the waveform. The selected audio clip also shows the
        // circles to grab (fade handles in the top corners, a dot per curve point); see audio_shaping.h.
        if (const ShapingSnapshot* shaping = snap->shapingOf(c.clipKey); shaping != nullptr && ix1 - ix0 > 8.0f) {
            g.setClip(std::max(0.0f, ix0), std::max(layout.rulerHeight, itop), std::min(W, ix1), ibottom);
            const float laneH = layout.heightOf(c.trackIndex);
            const float areaTop = shapeAreaTop(top, laneH), areaBottom = shapeAreaBottom(top, laneH);
            const double ppf = vp.pxPerFrame;
            const float step = std::max(2.0f, 2.0f * density);
            const Color ramp = withAlpha(Color{0.0f, 0.0f, 0.0f, 1.0f}, 0.38f);
            const Color edge = withAlpha(white, 0.9f);
            const Color halo = withAlpha(Color{0.0f, 0.0f, 0.0f, 1.0f}, 0.55f);
            const float lineHalf = std::max(1.0f, 1.0f * density);
            const auto fadeShape = core::fadeShapeFromWire(shaping->shape());
            // A ramp: what the fade takes away is shaded above its curve, and the curve is drawn as a line.
            auto drawRamp = [&](float xFrom, float xTo, bool rising) {
                const float width = xTo - xFrom;
                if (width < 1.0f) return;
                for (float x = std::max(xFrom, ix0 - step); x < std::min(xTo, ix1 + step); x += step) {
                    const float p = std::min(1.0f, std::max(0.0f, (x + step * 0.5f - xFrom) / width));
                    const float gain = core::fadeShapeGain(fadeShape, rising ? p : 1.0f - p);
                    const float y = areaBottom - gain * (areaBottom - areaTop);
                    g.rect(x, areaTop, x + step, y, ramp);
                    g.rect(x, y - lineHalf - hair, x + step, y + lineHalf + hair, halo);  // dark rim: white alone vanishes on the light envelope
                    g.rect(x, y - lineHalf, x + step, y + lineHalf, edge);
                }
            };
            if (shaping->fadeInFrames > 0) drawRamp(static_cast<float>(x0), static_cast<float>(x0 + shaping->fadeInFrames * ppf), true);
            if (shaping->fadeOutFrames > 0) drawRamp(static_cast<float>(x1 - shaping->fadeOutFrames * ppf), static_cast<float>(x1), false);

            // The curve: dB-linear between its points, held before the first and after the last. With no points an
            // editable clip shows a flat line at its static gain, so there is something to double tap.
            const Color curve = withAlpha(th.keyframe, 0.95f);
            if (!shaping->points.empty() || shaping->editable()) {
                auto xOf = [&](const ShapingPoint& p) { return static_cast<float>(vp.frameToX(c.startFrame + p.frame)); };
                auto curveDb = [&](float x) {
                    const auto& pts = shaping->points;
                    if (pts.empty()) return shaping->baseDb;
                    if (x <= xOf(pts.front())) return pts.front().db;
                    for (size_t i = 1; i < pts.size(); ++i) {
                        const float xb = xOf(pts[i]);
                        if (x <= xb) {
                            const float xa = xOf(pts[i - 1]);
                            const float t = xb > xa ? (x - xa) / (xb - xa) : 1.0f;
                            return pts[i - 1].db + (pts[i].db - pts[i - 1].db) * t;
                        }
                    }
                    return pts.back().db;
                };
                const float thick = std::max(1.0f, 1.25f * density);
                float prevY = dbToY(curveDb(ix0), top, laneH);
                for (float x = std::max(ix0, 0.0f); x < std::min(ix1, W); x += step) {
                    const float y = dbToY(curveDb(x + step), top, laneH);
                    g.rect(x, std::min(prevY, y) - thick - hair, x + step, std::max(prevY, y) + thick + hair, halo);
                    g.rect(x, std::min(prevY, y) - thick, x + step, std::max(prevY, y) + thick, curve);
                    prevY = y;
                }
            }
            if (shaping->editable()) {
                // The grabbable parts sit over everything and are not cut to the block, so a handle on a corner shows whole.
                g.setClip(0, layout.rulerHeight, W, H);
                const Color ring = withAlpha(Color{0.0f, 0.0f, 0.0f, 1.0f}, 0.75f);
                for (const ShapingPoint& p : shaping->points) {
                    const float px = static_cast<float>(vp.frameToX(c.startFrame + p.frame));
                    if (px < ix0 - 8.0f || px > ix1 + 8.0f) continue;
                    const float py = dbToY(p.db, top, laneH);
                    const float r = layout.handleWidth * 0.36f;
                    g.roundedRect(px - r - hair, py - r - hair, px + r + hair, py + r + hair, r + hair, ring);
                    g.roundedRect(px - r, py - r, px + r, py + r, r, curve);
                }
                const FadeHandles h = fadeHandles(x0, x1, ppf, shaping->fadeInFrames, shaping->fadeOutFrames, top, layout);
                for (const float hx : {h.inX, h.outX}) {
                    g.roundedRect(hx - h.drawR - hair, h.y - h.drawR - hair, hx + h.drawR + hair, h.y + h.drawR + hair, h.drawR + hair, ring);
                    g.roundedRect(hx - h.drawR, h.y - h.drawR, hx + h.drawR, h.y + h.drawR, h.drawR, white);
                }
            }
            g.setClip(0, layout.rulerHeight, W, H);
        }

        // Keyframe markers: small diamonds in the clip's header strip, at their time in the clip.
        {
            const auto [first, last] = snap->keyframesOf(c.clipKey);
            const float cy = itop + header * 0.5f;
            const float s = std::min(3.5f * density, header * 0.4f);
            for (const KeyframeSnapshot* k = first; k != last; ++k) {
                const float x = static_cast<float>(vp.frameToX(c.startFrame + k->frame));
                if (x < ix0 || x > ix1) continue;
                g.rect(x - s * 0.34f, cy - s, x + s * 0.34f, cy + s, th.keyframe);
                g.rect(x - s * 0.67f, cy - s * 0.67f, x + s * 0.67f, cy + s * 0.67f, th.keyframe);
                g.rect(x - s, cy - s * 0.34f, x + s, cy + s * 0.34f, th.keyframe);
            }
        }

        // What is crowded into the right end of the header strip: the speed label and the effects badge.
        float reserveRight = 0.0f;
        if (c.hasFx) reserveRight += 14.0f * density;

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
            const size_t n = std::strlen(label);
            const float width = glyphWidth(label, n, kTextClassSmall, gs);
            if (n > 0 && ix1 - ix0 > width + 8.0f * density) {
                g.setClip(std::max(0.0f, ix0), std::max(layout.rulerHeight, itop), std::min(W, ix1), ibottom);
                glyphRun(label, n, kTextClassSmall, ix1 - width - reserveRight - 4.0f * density, itop, header, kSpeedLabel);
                g.setClip(0, layout.rulerHeight, W, H);
                reserveRight += width + 8.0f * density;
            }
        }

        // The name of a media clip (in the header strip), the text of a title or the name of a sticker (in the body). It
        // starts at the visible left edge of the block, clear of the lane header, so it stays readable while the block is
        // scrolled partly out of view, and it is cut at the room that is left.
        if (const std::string* text = roomForLabel ? snap->labelOf(c.clipKey) : nullptr) {
            const int cls = labelClassOf(*snap, c);
            const float textLeft = std::max(ix0, layout.headerWidth) + 5.0f * density;
            const float textRight = ix1 - 4.0f * density - (cls == kTextClassLabel ? 0.0f : reserveRight);
            if (textRight - textLeft > 10.0f * density) {
                g.setClip(textLeft, std::max(layout.rulerHeight, itop), std::min(W, textRight), ibottom);
                if (cls == kTextClassLabel) {
                    labelText(*text, cls, textLeft, bodyTop, ibottom - bodyTop, kClipLabel);
                } else {
                    labelText(*text, cls, textLeft, itop, header, kClipLabel);
                }
                g.setClip(0, layout.rulerHeight, W, H);
            }
        }

        // Effects marker: a small badge at the right end of the header strip, "fx" drawn as two bars.
        if (c.hasFx) {
            const float cy = itop + header * 0.5f;
            const float s = std::min(3.0f * density, header * 0.4f);
            const float right = ix1 - s * 1.5f;
            if (right - s * 4.0f > ix0) {
                g.rect(right - s * 3.0f, cy - s, right - s * 2.0f, cy + s, kFxBadge);
                g.rect(right - s * 1.5f, cy - s, right - s * 0.5f, cy + s, kFxBadge);
                g.rect(right - s * 3.0f, cy - s * 0.2f, right - s * 0.5f, cy + s * 0.2f, kFxBadge);
            }
        }

        if (c.missing) {
            g.setClip(std::max(0.0f, ix0), std::max(layout.rulerHeight, itop), std::min(W, ix1), ibottom);
            g.rect(ix0, itop, ix1, ibottom, kMissingTint);
            // Stripes stepped down the clip approximate a diagonal hatch with plain rectangles.
            const float step = std::max(6.0f, 8.0f * density);
            const float bar = std::max(1.0f, 1.5f * density);
            const int rows = 6;
            const float rowH = (ibottom - itop) / static_cast<float>(rows);
            for (int row = 0; row < rows; ++row) {
                const float y0 = itop + rowH * static_cast<float>(row);
                const float offset = step * static_cast<float>(row) / static_cast<float>(rows);
                for (float x = ix0 - step + offset; x < ix1; x += step) {
                    g.rect(x, y0, x + bar, y0 + rowH, kMissingStripe);
                }
            }
            g.setClip(0, layout.rulerHeight, W, H);
        }

        // Round the corners: paint the corner areas in the colour behind the block.
        if (radius > 0.5f) {
            g.cornerCut(ix0, itop, radius, 0, cutColour);
            g.cornerCut(ix1, itop, radius, 1, cutColour);
            g.cornerCut(ix1, ibottom, radius, 2, cutColour);
            g.cornerCut(ix0, ibottom, radius, 3, cutColour);
        }

        // Trim handles on the clip being edited: a pill at each end, so the edges that can be dragged are visible.
        if (c.selected && c.primary && ix1 - ix0 > 48.0f * density) {
            const float pillW = 4.0f * density;
            const float pillH = std::min(26.0f * density, (ibottom - itop) * 0.5f);
            const float cy = (itop + ibottom) * 0.5f;
            const Color pill = withAlpha(th.onClip, 0.9f);
            g.roundedRect(ix0 + 3.0f * density, cy - pillH * 0.5f, ix0 + 3.0f * density + pillW, cy + pillH * 0.5f, pillW * 0.5f, pill);
            g.roundedRect(ix1 - 3.0f * density - pillW, cy - pillH * 0.5f, ix1 - 3.0f * density, cy + pillH * 0.5f, pillW * 0.5f, pill);
        }
    }

    // Transitions: a translucent band over the span both clips cross-fade in, with a bright line at the cut.
    g.setClip(0, layout.rulerHeight, W, H);
    for (const TransitionSnapshot& t : snap->transitions) {
        const float top = layout.trackTop(t.trackIndex) - static_cast<float>(vp.scrollY);
        const float bottom = top + layout.heightOf(t.trackIndex);
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

    // Lane headers: a name tab over the left edge of every lane with the lane's colour down its edge, and M / S chips on
    // muted and soloed audio lanes. Long pressing one starts a lane drag (see LaneHeader hits); the dragged lane is
    // tinted and a bar shows where it lands.
    if (layout.headerWidth > 0.0f) {
        g.setClip(0, layout.rulerHeight, W, H);
        const int baseLane = baseLaneIndex(*snap);
        const float accentW = 3.0f * density;
        for (int t = 0; t < trackCount; ++t) {
            const float top = layout.trackTop(t) - static_cast<float>(vp.scrollY);
            const float bottom = top + layout.heightOf(t);
            if (bottom < layout.rulerHeight || top > H) continue;
            const TrackSnapshot& track = snap->tracks[static_cast<size_t>(t)];
            const Color tab = t == laneDragFrom ? kHeaderDragging
                                                 : (t == baseLane ? mix(th.surfaceHigh, th.primary, 0.14f) : th.surfaceHigh);
            g.rect(0, top, layout.headerWidth, bottom, withAlpha(tab, 0.95f));
            g.rect(0, top, accentW, bottom, clipColor(th, track.type));
            g.rect(layout.headerWidth - hair, top, layout.headerWidth, bottom, th.ruler);
            const std::string label = laneLabel(*snap, t);
            const float gs = std::max(1.0f, std::floor(1.6f * density));
            const float textW = glyphWidth(label.c_str(), label.size(), kTextClassBold, gs);
            const float labelTop = top + 5.0f * density;
            const float labelBand = 14.0f * density;
            glyphRun(label.c_str(), label.size(), kTextClassBold, accentW + (layout.headerWidth - accentW - textW) * 0.5f, labelTop,
                     labelBand, th.onSurface);
            if (track.type == TrackType::Audio && (track.muted || track.solo)) {
                // One chip per state, stacked under the name.
                const float chip = 13.0f * density;
                float y = labelTop + labelBand + 3.0f * density;
                const float cx = accentW + (layout.headerWidth - accentW - chip) * 0.5f;
                if (track.muted) {
                    g.roundedRect(cx, y, cx + chip, y + chip, 2.5f * density, kHeaderMute);
                    glyphRun("M", 1, kTextClassBold, cx + (chip - glyphWidth("M", 1, kTextClassBold, gs)) * 0.5f, y, chip, th.background);
                    y += chip + 3.0f * density;
                }
                if (track.solo) {
                    g.roundedRect(cx, y, cx + chip, y + chip, 2.5f * density, kHeaderSolo);
                    glyphRun("S", 1, kTextClassBold, cx + (chip - glyphWidth("S", 1, kTextClassBold, gs)) * 0.5f, y, chip, th.background);
                }
            }
        }
        bool atTop = false;
        if (laneDragBarEdge(laneDragFrom, laneDragTo, trackCount, &atTop)) {
            const float top = layout.trackTop(laneDragTo) - static_cast<float>(vp.scrollY);
            const float y = atTop ? top : top + layout.heightOf(laneDragTo);
            const float bar = std::max(2.0f, 3.0f * density);
            g.rect(0, y - bar * 2.0f, W, y + bar * 2.0f, kLaneDragGlow);
            g.rect(0, y - bar * 0.5f, W, y + bar * 0.5f, kLaneDragBar);
        }
    }
    // The clip names and lane names go on now, over the blocks and headers and under the indicators drawn next.
    g.setClip(0, layout.rulerHeight, W, H);
    g.flushText();

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

    // Marquee: the rectangle being dragged to select clips.
    if (marqueeActive) {
        g.setClip(0, layout.rulerHeight, W, H);
        const float mx0 = std::min(marquee[0], marquee[2]), mx1 = std::max(marquee[0], marquee[2]);
        const float my0 = std::min(marquee[1], marquee[3]), my1 = std::max(marquee[1], marquee[3]);
        const float e = std::max(1.0f, 1.5f * density);
        const Color fill = withAlpha(th.primary, kMarqueeAlpha.a);
        const Color edge = withAlpha(th.primary, 0.95f);
        g.rect(mx0, my0, mx1, my1, fill);
        g.rect(mx0, my0, mx1, my0 + e, edge);
        g.rect(mx0, my1 - e, mx1, my1, edge);
        g.rect(mx0, my0, mx0 + e, my1, edge);
        g.rect(mx1 - e, my0, mx1, my1, edge);
    }

    // Snap guide: a line through the lanes at the frame a dragged or trimmed edge snapped to.
    if (snapGuide != kNoSnapGuide) {
        g.setClip(0, layout.rulerHeight, W, H);
        const float lineW = std::max(1.0f, std::round(1.5f * density));
        const HintRect glow = snapGuideRect(snapGuide, vp, layout, W, H, lineW * 5.0f);
        if (glow.valid) g.rect(glow.x0, glow.y0, glow.x1, glow.y1, kSnapGuideGlow);
        const HintRect line = snapGuideRect(snapGuide, vp, layout, W, H, lineW);
        if (line.valid) g.rect(line.x0, line.y0, line.x1, line.y1, kSnapGuide);
    }

    // A faint line through the lanes at each user marker, so cuts can be lined up against it.
    g.setClip(0, layout.rulerHeight, W, H);
    for (const auto& m : snap->markers) {
        if (m.beat()) continue;
        const float x = static_cast<float>(vp.frameToX(m.frame));
        if (x < -2.0f) continue;
        if (x > W + 2.0f) break;
        const MarkerRgb rgb = markerRgb(markerColorCode(m.extra));
        g.rect(x, layout.rulerHeight, x + hair, H, Color{rgb.r, rgb.g, rgb.b, kMarkerLineAlpha.a});
    }

    // Ruler on top so clips scroll underneath it: a long tick and a time label at every step, small ticks between.
    g.setClip(0, 0, W, H);
    g.rect(0, 0, W, layout.rulerHeight, th.ruler);
    g.rect(0, layout.rulerHeight - hair, W, layout.rulerHeight, withAlpha(th.tick, 0.45f));
    {
        const int64_t fps = std::max<int64_t>(1, (snap->fpsNum + snap->fpsDen / 2) / snap->fpsDen);
        const RulerPlan plan = planRuler(fps, vp.pxPerFrame, 72.0 * density, 7.0 * density);
        const int64_t step = plan.step;
        // One step before the left edge: the label of a tick just off screen still reaches into view.
        const int64_t first = std::max<int64_t>(0, vp.xToFrame(0) / step - 1) * step;
        const float labelBandTop = 3.0f * density;
        const float labelBandH = 13.0f * density;
        const Color minorTick = withAlpha(th.tick, 0.7f);
        for (int64_t f = first;; f += step) {
            const float x = std::floor(static_cast<float>(vp.frameToX(f)));
            if (x > W) break;
            g.rect(x, 4.0f * density, x + hair, layout.rulerHeight, th.tick);
            for (int k = 1; k < plan.minorDiv; ++k) {
                const float xm = std::floor(static_cast<float>(vp.frameToX(f + step * k / plan.minorDiv)));
                g.rect(xm, layout.rulerHeight * 0.78f, xm + hair, layout.rulerHeight, minorTick);
            }
            char label[24];
            const size_t n = formatRulerLabel(f, fps, step, label, sizeof(label));
            if (x + 80.0f * density < 0.0f) continue;  // wholly off screen to the left
            glyphRun(label, n, kTextClassSmall, x + 4.0f * density, labelBandTop, labelBandH, th.rulerText);
        }
    }

    // Markers on the ruler: user markers are tall with a flag, detected beats are short ticks. Beats
    // closer than a few pixels are skipped so a fast song zoomed out stays readable.
    {
        const float w = std::max(1.0f, std::round(1.5f * density));
        float lastBeatX = -1.0e9f;
        for (size_t mi = 0; mi < snap->markers.size(); ++mi) {
            const auto& m = snap->markers[mi];
            const float x = static_cast<float>(vp.frameToX(m.frame));
            if (x < -w) continue;
            if (x > W + w) break;
            if (m.beat()) {
                if (x - lastBeatX < 3.0f * density) continue;
                lastBeatX = x;
                g.rect(x, layout.rulerHeight * 0.62f, x + w * 0.67f, layout.rulerHeight, kBeat);
            } else {
                const MarkerRgb rgb = markerRgb(markerColorCode(m.extra));
                const Color flag{rgb.r, rgb.g, rgb.b, th.marker.a};
                g.rect(x, 2.0f * density, x + w, layout.rulerHeight, flag);
                // The pennant: a pointed flag at the top of the pole.
                const float fh = 8.0f * density, fw = 8.0f * density;
                g.rect(x, 2.0f * density, x + fw, 2.0f * density + fh * 0.5f, flag);
                g.rect(x, 2.0f * density + fh * 0.5f, x + fw * 0.6f, 2.0f * density + fh, flag);
                if (markerHasNote(m.extra)) {
                    // Note indicator: a small light square under the flag, on the line.
                    const float s = std::max(2.0f, 3.0f * density);
                    const float y = 2.0f * density + fh + 2.0f * density;
                    g.rect(x + w, y, x + w + s, y + s, Color{1.0f, 1.0f, 1.0f, 0.9f});
                }
                // The name, when there is room before the next marker: drawn right of the flag in the lower part of the ruler.
                if (const std::string* name = snap->labelOf(markerLabelKey(mi))) {
                    const float left = x + w + fw + 3.0f * density;
                    const float nextX = mi + 1 < snap->markers.size() ? static_cast<float>(vp.frameToX(snap->markers[mi + 1].frame)) : W;
                    const float room = nextX - left - 3.0f * density;
                    if (room > 24.0f * density) {
                        g.setClip(left, 0, std::min(W, left + room), H);
                        labelText(*name, kTextClassSmall, left, layout.rulerHeight * 0.42f, layout.rulerHeight * 0.58f, flag);
                        g.setClip(0, 0, W, H);
                    }
                }
            }
        }
    }

    // Playhead: a line through the lanes and a tag at the top with the time under it.
    {
        const float x = static_cast<float>(vp.frameToX(playhead));
        const float w = std::max(1.0f, std::round(1.5f * density));
        g.rect(x - w * 0.5f, 0, x + w * 0.5f, H, th.playhead);
        const int64_t fps = std::max<int64_t>(1, (snap->fpsNum + snap->fpsDen / 2) / snap->fpsDen);
        char label[24];
        const size_t n = formatTimecode(playhead, fps, label, sizeof(label));
        const float gs = std::max(1.0f, std::floor(1.6f * density));
        const float textW = glyphWidth(label, n, kTextClassBold, gs);
        const float tagW = textW + 10.0f * density, tagH = 17.0f * density;
        const float tagX = std::clamp(x - tagW * 0.5f, 0.0f, std::max(0.0f, W - tagW));
        g.roundedRect(tagX, 0, tagX + tagW, tagH, 4.0f * density, th.playhead);
        glyphRun(label, n, kTextClassBold, tagX + 5.0f * density, 0, tagH, th.background);
    }
    g.setClip(0, 0, W, H);
    g.flushText();

    if (ctx.statsOn) {
        g.flush();
        const float ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - frameStart).count();
        uint32_t draws = 0, verts = 0;
        g.frameCounters(&draws, &verts);
        ctx.statMs.push_back(ms);
        ctx.statDraws += draws;
        ctx.statVerts += verts;
        if (ctx.statMs.size() >= 240) {
            std::vector<float> sorted = ctx.statMs;
            std::sort(sorted.begin(), sorted.end());
            const size_t n = sorted.size();
            LOGI("stats frames=%zu cpu_ms p50=%.2f p95=%.2f p99=%.2f max=%.2f draws=%.1f verts=%.0f uploads=%.0f upload_ms=%.2f", n, sorted[n / 2],
                 sorted[n * 95 / 100], sorted[n * 99 / 100], sorted[n - 1], ctx.statDraws / static_cast<double>(n),
                 ctx.statVerts / static_cast<double>(n), ctx.statUploads, ctx.statUploadMs);
            ctx.statMs.clear();
            ctx.statDraws = ctx.statVerts = ctx.statUploadMs = ctx.statUploads = 0.0;
        }
    }
    if (!g.endFrame()) return;

    bool dirtyAgain;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        dirtyAgain = state_->dirty || state_->flingVelocity != 0.0f || !state_->pendingLabels.empty();
    }
    if (keepAnimating || dirtyAgain || moreTilesReady || moreLabels) {
        ctx.framePosted = true;
        AChoreographer_postFrameCallback64(ctx.choreographer, &TimelineRenderer::onFrame, this);
    }
}

}  // namespace uv::timeline
