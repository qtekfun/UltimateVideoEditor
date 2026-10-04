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

// The bitmaps in the atlas by hash. Owned by the render thread. When the atlas is full everything is dropped and the
// generation counter moves on: Kotlin sees the new generation, forgets what it sent and sends what it still needs.
class LabelTable {
public:
    enum class Result { Placed, Exists, Full, TooBig };

    LabelTable(int width, int height) : packer_(width, height) {}

    Result place(uint64_t hash, int w, int h, bool colour, int* x, int* y) {
        if (entries_.count(hash) != 0) return Result::Exists;
        if (w + 1 > packer_.width() || h + 1 > packer_.height()) return Result::TooBig;
        if (!packer_.pack(w, h, x, y)) return Result::Full;
        const float iw = 1.0f / static_cast<float>(packer_.width());
        const float ih = 1.0f / static_cast<float>(packer_.height());
        entries_[hash] = {static_cast<float>(*x) * iw, static_cast<float>(*y) * ih, static_cast<float>(*x + w) * iw,
                          static_cast<float>(*y + h) * ih, w, h, colour};
        return Result::Placed;
    }

    const LabelEntry* find(uint64_t hash) const {
        const auto it = entries_.find(hash);
        return it == entries_.end() ? nullptr : &it->second;
    }

    void reset() {
        entries_.clear();
        packer_.reset();
        ++generation_;
    }

    uint32_t generation() const { return generation_; }
    size_t size() const { return entries_.size(); }
    size_t usedPixels() const { return packer_.usedPixels(); }

private:
    ShelfPacker packer_;
    std::unordered_map<uint64_t, LabelEntry> entries_;
    uint32_t generation_ = 0;
};

}  // namespace uv::timeline
