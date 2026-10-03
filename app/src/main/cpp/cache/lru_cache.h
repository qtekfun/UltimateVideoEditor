#pragma once

#include <cstddef>
#include <iterator>
#include <list>
#include <mutex>
#include <unordered_map>
#include <utility>
#include <vector>

namespace uv::cache {

// Thread-safe LRU cache with a strict byte budget. Values are stored by value (use shared_ptr
// for shared ownership), so eviction only drops the cache's reference.
template <typename K, typename V, typename Hash = std::hash<K>>
class LruCache {
public:
    explicit LruCache(size_t budgetBytes) : budget_(budgetBytes) {}

    // Inserts or replaces. Returns the evicted values so callers can release them outside the lock.
    // An entry larger than the whole budget is rejected (returned in the eviction list).
    std::vector<V> put(const K& key, V value, size_t bytes) {
        return put(key, std::move(value), bytes, [](const K&) { return false; });
    }

    // Like put(), but eviction takes the least recently used entry that `isProtected` does not
    // claim, and only falls back to protected ones when nothing else is left. Playback prefetch
    // needs this: frames ahead of the playhead are older in recency than frames just played, so
    // plain LRU would throw away exactly what is about to be shown.
    template <typename Protect>
    std::vector<V> put(const K& key, V value, size_t bytes, Protect isProtected) {
        std::vector<V> evicted;
        std::lock_guard<std::mutex> lock(mu_);
        auto it = index_.find(key);
        if (it != index_.end()) {
            used_ -= it->second->bytes;
            evicted.push_back(std::move(it->second->value));
            order_.erase(it->second);
            index_.erase(it);
        }
        if (bytes > budget_) {
            evicted.push_back(std::move(value));
            return evicted;
        }
        order_.push_front(Entry{key, std::move(value), bytes});
        index_[key] = order_.begin();
        used_ += bytes;
        evictLocked(evicted, isProtected);
        return evicted;
    }

    // Marks the entry most recently used. Returns false on a miss.
    bool get(const K& key, V* out) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = index_.find(key);
        if (it == index_.end()) return false;
        order_.splice(order_.begin(), order_, it->second);
        *out = it->second->value;
        return true;
    }

    // Presence check that does not change recency.
    bool contains(const K& key) const {
        std::lock_guard<std::mutex> lock(mu_);
        return index_.find(key) != index_.end();
    }

    std::vector<V> setBudget(size_t budgetBytes) {
        std::vector<V> evicted;
        std::lock_guard<std::mutex> lock(mu_);
        budget_ = budgetBytes;
        evictLocked(evicted, [](const K&) { return false; });
        return evicted;
    }

    // Removes every entry whose key satisfies `pred`; returns the removed values.
    template <typename Pred>
    std::vector<V> eraseIf(Pred pred) {
        std::vector<V> evicted;
        std::lock_guard<std::mutex> lock(mu_);
        for (auto it = order_.begin(); it != order_.end();) {
            if (pred(it->key)) {
                used_ -= it->bytes;
                index_.erase(it->key);
                evicted.push_back(std::move(it->value));
                it = order_.erase(it);
            } else {
                ++it;
            }
        }
        return evicted;
    }

    std::vector<V> clear() {
        std::vector<V> evicted;
        std::lock_guard<std::mutex> lock(mu_);
        for (Entry& e : order_) evicted.push_back(std::move(e.value));
        order_.clear();
        index_.clear();
        used_ = 0;
        return evicted;
    }

    size_t usedBytes() const {
        std::lock_guard<std::mutex> l(mu_);
        return used_;
    }
    size_t budgetBytes() const {
        std::lock_guard<std::mutex> l(mu_);
        return budget_;
    }
    size_t size() const {
        std::lock_guard<std::mutex> l(mu_);
        return index_.size();
    }

private:
    struct Entry {
        K key;
        V value;
        size_t bytes;
    };

    template <typename Protect>
    void evictLocked(std::vector<V>& evicted, Protect isProtected) {
        while (used_ > budget_ && !order_.empty()) {
            auto victim = order_.end();
            for (auto it = order_.end(); it != order_.begin();) {  // least recently used first
                --it;
                if (!isProtected(it->key)) {
                    victim = it;
                    break;
                }
            }
            if (victim == order_.end()) victim = std::prev(order_.end());
            used_ -= victim->bytes;
            index_.erase(victim->key);
            evicted.push_back(std::move(victim->value));
            order_.erase(victim);
        }
    }

    mutable std::mutex mu_;
    size_t budget_;
    size_t used_ = 0;
    std::list<Entry> order_;
    std::unordered_map<K, typename std::list<Entry>::iterator, Hash> index_;
};

}  // namespace uv::cache
