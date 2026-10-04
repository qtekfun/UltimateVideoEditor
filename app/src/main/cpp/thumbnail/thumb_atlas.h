#pragma once

#include <GLES3/gl3.h>

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "thumbnail/slot_lru.h"
#include "thumbnail/tile_math.h"

namespace uv::thumb {

// One RGB565 texture holding thumbnail tiles in fixed slots, with LRU slot reuse. Render thread
// only, with the timeline's GL context current. The budget is a hard ceiling on texture memory.
class ThumbAtlas {
public:
    ~ThumbAtlas() { release(); }

    // Creates the texture (at most `budgetBytes`). False when GL refuses it.
    bool init(size_t budgetBytes);
    void release();
    bool ready() const { return texture_ != 0; }
    GLuint texture() const { return texture_; }
    size_t textureBytes() const { return layout_.bytes(); }
    int slotCount() const { return layout_.slots(); }

    // `nowNanos` is the frame time; tiles uploaded during this frame are stamped with it (see find()).
    void beginFrame(int64_t nowNanos = 0) {
        now_ = nowNanos;
        if (lru_) lru_->beginFrame();
    }

    // Uploads a tile into its slot, evicting the least recently used slot if needed. Returns false
    // when every slot is in use this frame (the tile stays on disk and is requested again).
    bool upload(const TileKey& key, const uint16_t* pixels);

    bool contains(const TileKey& key) const { return lru_ && lru_->contains(key); }

    // UV rectangle {u0, v0, u1, v1} of a resident tile, marking it used this frame. `readyNanos` (optional) receives the
    // frame time at which the tile was uploaded (0 when that was not recorded), for the fade-in.
    bool find(const TileKey& key, float uv[4], int64_t* readyNanos = nullptr);

private:
    AtlasLayout layout_;
    GLuint texture_ = 0;
    std::unique_ptr<SlotLru> lru_;
    std::vector<int64_t> readyNanos_;  // per slot: when the tile in it was uploaded
    int64_t now_ = 0;
#ifndef NDEBUG
    unsigned evictions_ = 0;  // debug logging only (only read by the NDEBUG-guarded log in the .cpp)
#endif
};

}  // namespace uv::thumb
