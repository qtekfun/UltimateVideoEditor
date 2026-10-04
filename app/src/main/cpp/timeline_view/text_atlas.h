#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace uv::timeline {

// The text of the canvas is drawn from a texture atlas of small bitmaps that Kotlin rasterises with the system font
// (see TimelineText.kt): one bitmap per ruler/lane glyph and one per label (a clip name, a title, a marker name). The
// two sides agree on a bitmap by a 64-bit hash of its text and size class, so nothing but pixels crosses the JNI.

// Size classes: 0 = regular label, 1 = small, 2 = bold. Kotlin renders each at its own pixel size.
constexpr int kTextSizeClasses = 3;

// FNV-1a over the size class byte then the UTF-8 bytes. Must match TimelineText.hashOf in Kotlin.
constexpr uint64_t labelHash(const char* text, size_t length, int sizeClass) {
    uint64_t h = 0xcbf29ce484222325ull;
    h ^= static_cast<uint8_t>(sizeClass);
    h *= 0x100000001b3ull;
    for (size_t i = 0; i < length; ++i) {
        h ^= static_cast<uint8_t>(text[i]);
        h *= 0x100000001b3ull;
    }
    return h;
}

inline uint64_t labelHash(const std::string& text, int sizeClass) { return labelHash(text.data(), text.size(), sizeClass); }

// True when `s` is well-formed UTF-8 (no overlongs, surrogates or values above U+10FFFF).
inline bool utf8Valid(const char* s, size_t n) {
    size_t i = 0;
    while (i < n) {
        const uint8_t c = static_cast<uint8_t>(s[i]);
        size_t extra = 0;
        uint32_t cp = 0;
        if (c < 0x80) {
            ++i;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            extra = 1;
            cp = c & 0x1F;
        } else if ((c & 0xF0) == 0xE0) {
            extra = 2;
            cp = c & 0x0F;
        } else if ((c & 0xF8) == 0xF0) {
            extra = 3;
            cp = c & 0x07;
        } else {
            return false;
        }
        if (i + extra >= n) return false;
        for (size_t k = 1; k <= extra; ++k) {
            const uint8_t d = static_cast<uint8_t>(s[i + k]);
            if ((d & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (d & 0x3F);
        }
        if ((extra == 1 && cp < 0x80) || (extra == 2 && cp < 0x800) || (extra == 3 && cp < 0x10000)) return false;
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        i += extra + 1;
    }
    return true;
}

inline bool isAscii(const std::string& s) {
    return std::all_of(s.begin(), s.end(), [](char c) { return static_cast<uint8_t>(c) < 0x80; });
}

// Packs rectangles into a w x h area in rows ("shelves"), each as tall as its first rectangle. One pixel of gutter
// keeps neighbours from bleeding into each other. Placement only: no pixels are touched here.
class ShelfPacker {
public:
    ShelfPacker(int width, int height) : width_(width), height_(height) {}

    bool pack(int w, int h, int* x, int* y) {
        if (w <= 0 || h <= 0 || w + kGutter > width_ || h + kGutter > height_) return false;
        for (Shelf& s : shelves_) {
            // A shelf takes rectangles up to its height and not much shorter, so tall rows are not wasted on slivers.
            if (h <= s.height && h * 2 > s.height && s.used + w + kGutter <= width_) {
                *x = s.used;
                *y = s.y;
                s.used += w + kGutter;
                usedPixels_ += static_cast<size_t>(w) * static_cast<size_t>(h);
                return true;
            }
        }
        if (nextY_ + h + kGutter > height_) return false;
        shelves_.push_back({nextY_, h, w + kGutter});
        *x = 0;
        *y = nextY_;
        nextY_ += h + kGutter;
        usedPixels_ += static_cast<size_t>(w) * static_cast<size_t>(h);
        return true;
    }

    void reset() {
        shelves_.clear();
        nextY_ = 0;
        usedPixels_ = 0;
    }

    int width() const { return width_; }
    int height() const { return height_; }
    size_t usedPixels() const { return usedPixels_; }

private:
    static constexpr int kGutter = 1;
    struct Shelf {
        int y;
        int height;
        int used;
    };
    int width_, height_;
    int nextY_ = 0;
    size_t usedPixels_ = 0;
    std::vector<Shelf> shelves_;
};

struct LabelEntry {
    float u0, v0, u1, v1;
    int w, h;
    bool colour;  // an emoji or another coloured glyph: drawn as it is, not tinted
};

// The bitmaps in the atlas by hash, kept within a byte budget by least-recently-used eviction. Owned by the render thread.
//
// Every bitmap that is drawn (find) or placed counts as used. When a new bitmap would push the live bytes past the budget,
// or finds no room in the atlas, the least recently used ones are evicted until it fits; their rectangles go to a free list
// (neighbours on a row are merged) and later bitmaps reuse them, so the atlas is never emptied just because it filled.
// Bitmaps used in the previous or the current frame are never evicted, so nothing that is on screen is overwritten before it
// is drawn. Evicted hashes are reported (takeEvicted) so that Kotlin sends them again if it still needs them. Only when
// every bitmap is protected does place() answer Full; the caller then reset()s, which starts a new generation.
class LabelTable {
public:
    enum class Result { Placed, Exists, Full, TooBig };

    // `budgetBytes` caps the bytes of live bitmaps (RGBA); the default leaves a quarter of the atlas for shelf waste.
    LabelTable(int width, int height, size_t budgetBytes = 0)
        : packer_(width, height),
          budgetBytes_(budgetBytes != 0 ? budgetBytes : static_cast<size_t>(width) * static_cast<size_t>(height) * 3) {}

    Result place(uint64_t hash, int w, int h, bool colour, int* x, int* y) {
        if (entries_.count(hash) != 0) return Result::Exists;
        if (w <= 0 || h <= 0 || w + 1 > packer_.width() || h + 1 > packer_.height()) return Result::TooBig;
        const size_t bytes = bytesOf(w, h);
        if (bytes > budgetBytes_) return Result::TooBig;
        for (;;) {
            if (liveBytes_ + bytes <= budgetBytes_ && allocate(w, h, x, y)) break;
            if (!evictOldest()) return Result::Full;
        }
        const float iw = 1.0f / static_cast<float>(packer_.width());
        const float ih = 1.0f / static_cast<float>(packer_.height());
        Node node;
        node.entry = {static_cast<float>(*x) * iw, static_cast<float>(*y) * ih, static_cast<float>(*x + w) * iw,
                      static_cast<float>(*y + h) * ih, w, h, colour};
        node.slot = {*x, *y, w, h};
        node.lastUse = ++stamp_;
        liveBytes_ += bytes;
        entries_.emplace(hash, node);
        return Result::Placed;
    }

    // The bitmap for `hash`, marking it used; null when it is not in the atlas.
    const LabelEntry* find(uint64_t hash) {
        const auto it = entries_.find(hash);
        if (it == entries_.end()) return nullptr;
        it->second.lastUse = ++stamp_;
        return &it->second.entry;
    }

    // Like find() without marking it used.
    const LabelEntry* peek(uint64_t hash) const {
        const auto it = entries_.find(hash);
        return it == entries_.end() ? nullptr : &it->second.entry;
    }

    // Call once per frame before placing or finding anything: what was used since the previous call started is protected.
    void beginFrame() {
        protectedFrom_ = frameStart_;
        frameStart_ = stamp_ + 1;
    }

    // Appends the hashes evicted since the last call to `out` and forgets them.
    void takeEvicted(std::vector<uint64_t>* out) {
        out->insert(out->end(), evicted_.begin(), evicted_.end());
        evicted_.clear();
    }

    // Empties the atlas (the last resort) and starts a new generation: Kotlin forgets what it sent and sends it again.
    void reset() {
        entries_.clear();
        freeSlots_.clear();
        evicted_.clear();
        packer_.reset();
        liveBytes_ = 0;
        ++generation_;
    }

    uint32_t generation() const { return generation_; }
    size_t size() const { return entries_.size(); }
    size_t usedPixels() const { return packer_.usedPixels(); }
    size_t liveBytes() const { return liveBytes_; }
    size_t budgetBytes() const { return budgetBytes_; }
    size_t freeSlotCount() const { return freeSlots_.size(); }

private:
    struct Slot {
        int x, y, w, h;
    };
    struct Node {
        LabelEntry entry;
        Slot slot;  // the rectangle reserved for it (as large as the bitmap)
        uint64_t lastUse;
    };

    static size_t bytesOf(int w, int h) { return static_cast<size_t>(w) * static_cast<size_t>(h) * 4u; }

    // A rectangle for w x h: the smallest free one that fits (and is not much taller, as a shelf would also require), its
    // unused right end going back to the free list; else new space from the packer.
    bool allocate(int w, int h, int* x, int* y) {
        size_t best = freeSlots_.size();
        for (size_t i = 0; i < freeSlots_.size(); ++i) {
            const Slot& s = freeSlots_[i];
            if (s.w < w || s.h < h || h * 2 <= s.h) continue;
            if (best == freeSlots_.size() || area(s) < area(freeSlots_[best])) best = i;
        }
        if (best != freeSlots_.size()) {
            const Slot s = freeSlots_[best];
            freeSlots_.erase(freeSlots_.begin() + static_cast<std::ptrdiff_t>(best));
            *x = s.x;
            *y = s.y;
            if (s.w - w - 1 >= kMinRemainder) freeSlots_.push_back({s.x + w + 1, s.y, s.w - w - 1, s.h});  // one pixel of gutter
            return true;
        }
        return packer_.pack(w, h, x, y);
    }

    static int area(const Slot& s) { return s.w * s.h; }

    // Evicts the least recently used bitmap that was not used in the previous or this frame; false when there is none.
    bool evictOldest() {
        auto oldest = entries_.end();
        for (auto it = entries_.begin(); it != entries_.end(); ++it) {
            if (it->second.lastUse >= protectedFrom_) continue;
            if (oldest == entries_.end() || it->second.lastUse < oldest->second.lastUse) oldest = it;
        }
        if (oldest == entries_.end()) return false;
        evicted_.push_back(oldest->first);
        liveBytes_ -= bytesOf(oldest->second.entry.w, oldest->second.entry.h);
        release(oldest->second.slot);
        entries_.erase(oldest);
        return true;
    }

    // Puts a rectangle on the free list, merged with free neighbours on the same row (same top and height, one gutter apart).
    void release(Slot s) {
        for (size_t i = 0; i < freeSlots_.size();) {
            const Slot& t = freeSlots_[i];
            if (t.y == s.y && t.h == s.h && t.x + t.w + 1 == s.x) {
                s = {t.x, s.y, t.w + 1 + s.w, s.h};
            } else if (t.y == s.y && t.h == s.h && s.x + s.w + 1 == t.x) {
                s = {s.x, s.y, s.w + 1 + t.w, s.h};
            } else {
                ++i;
                continue;
            }
            freeSlots_.erase(freeSlots_.begin() + static_cast<std::ptrdiff_t>(i));
            i = 0;  // the merged rectangle may now touch another one
        }
        freeSlots_.push_back(s);
    }

    static constexpr int kMinRemainder = 8;  // a leftover narrower than this is not worth a free-list entry

    ShelfPacker packer_;
    size_t budgetBytes_;
    std::unordered_map<uint64_t, Node> entries_;
    std::vector<Slot> freeSlots_;
    std::vector<uint64_t> evicted_;
    size_t liveBytes_ = 0;
    uint64_t stamp_ = 0;
    uint64_t frameStart_ = 1;      // first stamp of the current frame
    uint64_t protectedFrom_ = 0;   // stamps at or after this belong to the previous or the current frame
    uint32_t generation_ = 0;
};

}  // namespace uv::timeline
