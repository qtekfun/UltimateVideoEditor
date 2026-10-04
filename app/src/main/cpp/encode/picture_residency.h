#pragma once

#include <cstdint>
#include <list>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace uv::encode {

// Which stills are on the GPU during an export and how many bytes they take. An export loads a picture only when a
// frame needs it and evicts the least recently used ones beyond the budget, so an animation of hundreds of frames never
// has to sit in memory all at once. Pure bookkeeping (no GL), so it is tested on the host.
class PictureResidency {
public:
    explicit PictureResidency(int64_t budgetBytes) : budget_(budgetBytes > 0 ? budgetBytes : 1) {}

    bool contains(uint32_t key) const { return index_.count(key) != 0; }

    // Marks `key` as the most recently used. Does nothing for a key that is not resident.
    void touch(uint32_t key) {
        auto it = index_.find(key);
        if (it == index_.end()) return;
        order_.splice(order_.end(), order_, it->second);
    }

    // Records `key` as resident with `bytes` and returns the keys evicted to stay within the budget, least recently
    // used first. Keys in `protectedKeys` (the pictures the current frame draws) and `key` itself are never evicted,
    // so a frame that needs more than the budget still renders; the budget is then exceeded until it moves on.
    std::vector<uint32_t> admit(uint32_t key, int64_t bytes, const std::unordered_set<uint32_t>& protectedKeys) {
        std::vector<uint32_t> evicted;
        auto existing = index_.find(key);
        if (existing != index_.end()) {
            used_ -= existing->second->bytes;
            order_.erase(existing->second);
            index_.erase(existing);
        }
        order_.push_back(Entry{key, bytes});
        index_[key] = std::prev(order_.end());
        used_ += bytes;
        for (auto it = order_.begin(); used_ > budget_ && it != order_.end();) {
            if (it->key == key || protectedKeys.count(it->key) != 0) {
                ++it;
                continue;
            }
            used_ -= it->bytes;
            evicted.push_back(it->key);
            index_.erase(it->key);
            it = order_.erase(it);
        }
        return evicted;
    }

    int64_t usedBytes() const { return used_; }
    int64_t budgetBytes() const { return budget_; }
    size_t size() const { return index_.size(); }

private:
    struct Entry {
        uint32_t key;
        int64_t bytes;
    };
    int64_t budget_;
    int64_t used_ = 0;
    std::list<Entry> order_;  // least recently used first
    std::unordered_map<uint32_t, std::list<Entry>::iterator> index_;
};

}  // namespace uv::encode
