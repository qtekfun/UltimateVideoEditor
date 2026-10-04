#include "thumbnail/thumb_atlas.h"

#include <android/log.h>
#include <sys/system_properties.h>

#include <cstdlib>
#include <memory>

#define LOG_TAG "uv_thumb"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace uv::thumb {

namespace {

// Debug builds only: `setprop debug.uveditor.atlas_bytes <n>` shrinks the atlas so slot eviction
// can be exercised on a device with a short clip.
size_t budgetOverride(size_t budgetBytes) {
#ifndef NDEBUG
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.uveditor.atlas_bytes", value) > 0) {
        const long long v = std::atoll(value);
        if (v > 0) return static_cast<size_t>(v);
    }
#endif
    return budgetBytes;
}

}  // namespace

bool ThumbAtlas::init(size_t budgetBytes) {
    if (texture_ != 0) return true;
    budgetBytes = budgetOverride(budgetBytes);
    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    const AtlasLayout layout = atlasLayoutFor(budgetBytes, maxSize);
    if (layout.slots() <= 0) {
        LOGE("no usable atlas layout (budget %zu, max texture %d)", budgetBytes, maxSize);
        return false;
    }
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGB565, layout.width, layout.height);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (glGetError() != GL_NO_ERROR) {
        LOGE("atlas texture allocation failed (%dx%d)", layout.width, layout.height);
        glDeleteTextures(1, &tex);
        return false;
    }
    layout_ = layout;
    texture_ = tex;
    lru_ = std::make_unique<SlotLru>(layout.slots());
    readyNanos_.assign(static_cast<size_t>(layout.slots()), 0);
    LOGI("atlas %dx%d, %d slots, %.1f MB", layout.width, layout.height, layout.slots(),
         static_cast<double>(layout.bytes()) / (1024.0 * 1024.0));
    return true;
}

void ThumbAtlas::release() {
    if (texture_ != 0) glDeleteTextures(1, &texture_);
    texture_ = 0;
    lru_.reset();
    readyNanos_.clear();
    layout_ = {};
}

bool ThumbAtlas::upload(const TileKey& key, const uint16_t* pixels) {
    if (texture_ == 0 || !lru_) return false;
    TileKey evicted;
    const bool resident = lru_->contains(key);
    const int slot = lru_->acquire(key, &evicted);
    if (slot < 0) return false;
    if (!resident) readyNanos_[static_cast<size_t>(slot)] = now_;  // a re-upload of the same tile keeps its age
#ifndef NDEBUG
    if (evicted.asset >= 0 && ++evictions_ % 20 == 1) {  // asset stays -1 unless a slot was reclaimed
        LOGI("atlas eviction #%u: tile L%d #%lld -> L%d #%lld", evictions_, evicted.level,
             static_cast<long long>(evicted.index), key.level, static_cast<long long>(key.index));
    }
#endif
    const int col = slot % layout_.cols, row = slot / layout_.cols;
    glBindTexture(GL_TEXTURE_2D, texture_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
    glTexSubImage2D(GL_TEXTURE_2D, 0, col * kTileWidth, row * kTileHeight, kTileWidth, kTileHeight, GL_RGB,
                    GL_UNSIGNED_SHORT_5_6_5, pixels);
    return true;
}

bool ThumbAtlas::find(const TileKey& key, float uv[4], int64_t* readyNanos) {
    if (texture_ == 0 || !lru_) return false;
    const int slot = lru_->find(key);
    if (slot < 0) return false;
    if (readyNanos != nullptr) *readyNanos = readyNanos_[static_cast<size_t>(slot)];
    const int col = slot % layout_.cols, row = slot / layout_.cols;
    const float w = static_cast<float>(layout_.width), h = static_cast<float>(layout_.height);
    // Half a texel inset so linear filtering never bleeds in a neighbouring slot.
    uv[0] = (static_cast<float>(col * kTileWidth) + 0.5f) / w;
    uv[1] = (static_cast<float>(row * kTileHeight) + 0.5f) / h;
    uv[2] = (static_cast<float>((col + 1) * kTileWidth) - 0.5f) / w;
    uv[3] = (static_cast<float>((row + 1) * kTileHeight) - 0.5f) / h;
    return true;
}

}  // namespace uv::thumb
