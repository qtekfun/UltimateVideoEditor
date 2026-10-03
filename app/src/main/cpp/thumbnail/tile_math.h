#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

// Pure tile geometry for clip thumbnails: no Android or GL, so it is unit-tested on the host.
//
// A thumbnail "tile" is one small frame of an asset on a source-time grid. Level L of the grid has
// one tile every kBaseSpacingMs << L milliseconds, so every tile of level L+1 is also a tile of
// level L. The timeline picks the level whose spacing matches how much source time one on-screen
// cell covers at the current zoom, so zooming out never needs more tiles than zooming in.
namespace uv::thumb {

constexpr int kTileWidth = 128;   // 16:9, the video frame is centre-cropped to this shape
constexpr int kTileHeight = 72;
constexpr size_t kTilePixels = static_cast<size_t>(kTileWidth) * kTileHeight;
constexpr size_t kTileBytes = kTilePixels * 2;  // RGB565
constexpr double kTileAspect = static_cast<double>(kTileWidth) / kTileHeight;

constexpr int64_t kBaseSpacingMs = 500;
constexpr int kMaxLevel = 8;  // 500 ms ... 128 s

struct TileKey {
    int64_t asset = -1;
    int32_t level = 0;
    int64_t index = 0;

    bool operator==(const TileKey& o) const { return asset == o.asset && level == o.level && index == o.index; }
    bool operator<(const TileKey& o) const {
        if (asset != o.asset) return asset < o.asset;
        if (level != o.level) return level < o.level;
        return index < o.index;
    }
};

struct TileKeyHash {
    size_t operator()(const TileKey& k) const {
        uint64_t h = static_cast<uint64_t>(k.asset) * 0x9E3779B97F4A7C15ull;
        h ^= (static_cast<uint64_t>(k.level) + 0x7F4A7C15ull) * 0xC2B2AE3D27D4EB4Full;
        h ^= static_cast<uint64_t>(k.index) * 0x165667B19E3779F9ull;
        return static_cast<size_t>(h ^ (h >> 29));
    }
};

constexpr int64_t spacingMs(int level) { return kBaseSpacingMs << std::clamp(level, 0, kMaxLevel); }
constexpr int64_t spacingUs(int level) { return spacingMs(level) * 1000; }
constexpr int64_t tileTimeUs(int level, int64_t index) { return index * spacingUs(level); }

// Nearest tile to a source time, never before the start of the media.
inline int64_t tileIndexForTimeUs(int64_t timeUs, int level) {
    if (timeUs <= 0) return 0;
    const int64_t s = spacingUs(level);
    return (timeUs + s / 2) / s;
}

// Level whose spacing is closest (on a log scale) to the source time one cell spans.
inline int levelForDesiredSpacingMs(double desiredMs) {
    if (!(desiredMs > static_cast<double>(kBaseSpacingMs))) return 0;
    const int level = static_cast<int>(std::lround(std::log2(desiredMs / static_cast<double>(kBaseSpacingMs))));
    return std::clamp(level, 0, kMaxLevel);
}

// Time of a clip-local project frame in the source media, using the source frame rate (the same
// convention the waveform drawing uses).
inline int64_t sourceTimeUs(int64_t sourceFrame, int32_t fpsNum, int32_t fpsDen) {
    if (fpsNum <= 0 || sourceFrame <= 0) return 0;
    // sourceFrame * 1e6 * den / num without overflowing for multi-hour clips.
    const __int128 v = static_cast<__int128>(sourceFrame) * 1000000 * fpsDen / fpsNum;
    return static_cast<int64_t>(v);
}

struct CellPlan {
    double x0;      // screen-space left edge of the cell, in pixels
    double x1;
    TileKey key;    // tile the cell wants at the current zoom
};

struct ClipCellParams {
    int64_t assetKey;
    double clipLeftX;       // screen x of the clip's first frame (may be negative)
    double clipRightX;
    double visibleLeft;     // visible horizontal range on screen
    double visibleRight;
    double cellWidth;       // pixels, tile aspect times the body height
    double pxPerFrame;
    int32_t projectFpsNum;
    int32_t projectFpsDen;
    int64_t sourceInFrame;
    int32_t sourceFpsNum;
    int32_t sourceFpsDen;
    // A retimed clip: it covers `spanFrames` source frames over `durationFrames` timeline frames (0 span
    // = plain 1x), plays backwards when `reverse` and holds its first frame when `freeze`.
    int64_t spanFrames = 0;
    int64_t durationFrames = 0;
    bool reverse = false;
    bool freeze = false;
};

// The source frame (from sourceIn) that timeline frame `local` of the clip shows.
inline int64_t sourceOffsetOf(const ClipCellParams& p, int64_t local) {
    if (p.spanFrames <= 0 || p.durationFrames <= 0) return local;
    if (p.freeze) return 0;
    const int64_t forward = static_cast<int64_t>(static_cast<__int128>(local) * p.spanFrames / p.durationFrames);
    return p.reverse ? std::max<int64_t>(p.spanFrames - 1 - forward, 0) : forward;
}

// Level used for a clip at the current zoom: how much source time one cell covers.
inline int levelForClip(const ClipCellParams& p) {
    if (p.pxPerFrame <= 0.0 || p.projectFpsNum <= 0) return 0;
    const double frames = p.cellWidth / p.pxPerFrame;
    const double ms = frames * 1000.0 * static_cast<double>(p.projectFpsDen) / static_cast<double>(p.projectFpsNum);
    return levelForDesiredSpacingMs(ms);
}

// Cells that intersect the visible range, anchored to the clip's left edge so tiles stay put while
// the timeline scrolls. Each cell asks for the tile nearest to the source time at its centre (clamped
// to the clip's own range), which represents the cell best and keeps the first cell of a trimmed clip
// from showing media before the in point.
inline void planClipCells(const ClipCellParams& p, std::vector<CellPlan>* out) {
    out->clear();
    if (p.cellWidth < 1.0 || p.pxPerFrame <= 0.0 || p.projectFpsNum <= 0) return;
    const double left = std::max(p.clipLeftX, p.visibleLeft);
    const double right = std::min(p.clipRightX, p.visibleRight);
    if (right <= left) return;

    const int level = levelForClip(p);
    const int64_t clipFrames = static_cast<int64_t>(std::floor((p.clipRightX - p.clipLeftX) / p.pxPerFrame));
    const int64_t first = static_cast<int64_t>(std::floor((left - p.clipLeftX) / p.cellWidth));
    const int64_t last = static_cast<int64_t>(std::floor((right - p.clipLeftX) / p.cellWidth));
    for (int64_t k = first; k <= last; ++k) {
        const double x0 = p.clipLeftX + static_cast<double>(k) * p.cellWidth;
        if (x0 >= p.clipRightX) break;
        const double centre = (static_cast<double>(k) + 0.5) * p.cellWidth;
        const int64_t localFrames =
            std::clamp<int64_t>(static_cast<int64_t>(std::floor(centre / p.pxPerFrame)), 0, std::max<int64_t>(clipFrames - 1, 0));
        const int64_t timeUs = sourceTimeUs(p.sourceInFrame + sourceOffsetOf(p, localFrames), p.sourceFpsNum, p.sourceFpsDen);
        out->push_back({x0, x0 + p.cellWidth, {p.assetKey, level, tileIndexForTimeUs(timeUs, level)}});
    }
}

// Best tile already available for `wanted`: the exact one, else a finer tile at the same instant
// (kept from when the user was zoomed in), else the nearest tile of a coarser level. `has` answers
// whether a key is resident. Returns false when nothing usable exists yet.
inline bool resolveTile(const TileKey& wanted, const std::function<bool(const TileKey&)>& has, TileKey* out) {
    if (has(wanted)) {
        *out = wanted;
        return true;
    }
    const int64_t wantedUs = tileTimeUs(wanted.level, wanted.index);
    for (int l = wanted.level - 1; l >= 0; --l) {
        const TileKey k{wanted.asset, l, wanted.index << (wanted.level - l)};
        if (has(k)) {
            *out = k;
            return true;
        }
    }
    for (int l = wanted.level + 1; l <= kMaxLevel; ++l) {
        const TileKey k{wanted.asset, l, tileIndexForTimeUs(wantedUs, l)};
        if (has(k)) {
            *out = k;
            return true;
        }
    }
    return false;
}

// Atlas capacity from a memory budget and the largest texture edge the GPU accepts.
struct AtlasLayout {
    int width = 0, height = 0;  // texture size in pixels
    int cols = 0, rows = 0;
    int slots() const { return cols * rows; }
    size_t bytes() const { return static_cast<size_t>(width) * height * 2; }
};

inline AtlasLayout atlasLayoutFor(size_t budgetBytes, int maxTextureSize) {
    AtlasLayout l;
    const int edge = std::min(maxTextureSize, 2048);
    int cols = edge / kTileWidth;
    if (cols <= 0) return l;
    int rows = std::min(edge / kTileHeight, static_cast<int>(budgetBytes / (static_cast<size_t>(cols) * kTileBytes)));
    if (rows <= 0) return l;
    l.cols = cols;
    l.rows = rows;
    l.width = cols * kTileWidth;
    l.height = rows * kTileHeight;
    return l;
}

}  // namespace uv::thumb
