#pragma once

#include <cstdint>
#include <list>
#include <unordered_map>
#include <vector>

#include "thumbnail/tile_math.h"

namespace uv::thumb {

// Fixed set of atlas slots with least-recently-used eviction. Not thread-safe: it lives on the
// render thread together with the GL texture it describes.
//
// Slots touched during the current frame are never evicted, so a tile that is being drawn cannot be
// overwritten by one uploaded later in the same frame. When every slot is in use this frame,
// acquire() fails and the caller drops the upload (the tile is re-requested from disk next frame).
class SlotLru {
public:
    explicit SlotLru(int capacity) : capacity_(capacity) {
        free_.reserve(static_cast<size_t>(capacity));
        for (int i = capacity - 1; i >= 0; --i) free_.push_back(i);
    }

    int capacity() const { return capacity_; }
    size_t size() const { return index_.size(); }

    // Call once per rendered frame before touching slots.
    void beginFrame() { ++frame_; }

    // Slot holding `key`, marking it used this frame; -1 when absent.
    int find(const TileKey& key) {
        auto it = index_.find(key);
        if (it == index_.end()) return -1;
        touch(it->second);
        return it->second->slot;
    }

    bool contains(const TileKey& key) const { return index_.count(key) != 0; }

    // Slot for `key` (reusing its own slot when resident). Evicts the least recently used slot
    // that was not used this frame. `evicted` (optional) receives the key that lost its slot.
    int acquire(const TileKey& key, TileKey* evicted = nullptr) {
        auto it = index_.find(key);
        if (it != index_.end()) {
            touch(it->second);
            return it->second->slot;
        }
        int slot = -1;
        if (!free_.empty()) {
            slot = free_.back();
            free_.pop_back();
        } else {
            // order_ is most recently used first; walk from the least recently used end.
            for (auto rit = order_.rbegin(); rit != order_.rend(); ++rit) {
                if (rit->usedFrame == frame_) break;  // everything newer was used this frame too
                slot = rit->slot;
                if (evicted != nullptr) *evicted = rit->key;
                index_.erase(rit->key);
                order_.erase(std::next(rit).base());
                break;
            }
            if (slot < 0) return -1;
        }
        order_.push_front({key, slot, frame_});
        index_[key] = order_.begin();
        return slot;
    }

    void clear() {
        index_.clear();
        order_.clear();
        free_.clear();
        for (int i = capacity_ - 1; i >= 0; --i) free_.push_back(i);
    }

private:
    struct Entry {
        TileKey key;
        int slot;
        uint64_t usedFrame;
    };
    using List = std::list<Entry>;

    void touch(List::iterator it) {
        it->usedFrame = frame_;
        order_.splice(order_.begin(), order_, it);
    }

    int capacity_;
    uint64_t frame_ = 0;
    List order_;
    std::unordered_map<TileKey, List::iterator, TileKeyHash> index_;
    std::vector<int> free_;
};

}  // namespace uv::thumb
