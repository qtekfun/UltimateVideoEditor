// GoogleTest-free host tests for the pure parts of the thumbnail pipeline: tile grid math, cell
// planning, atlas slot eviction, the on-disk tile store and YUV -> tile conversion.
// Build/run: see tests/CMakeLists.txt (documented in CLAUDE.md).
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <set>
#include <string>
#include <vector>

#include "thumbnail/rgba_tile.h"
#include "thumbnail/slot_lru.h"
#include "thumbnail/thumb_store.h"
#include "thumbnail/tile_math.h"
#include "thumbnail/yuv_tile.h"

using namespace uv;
using namespace uv::thumb;

static int g_failures = 0;
#define CHECK(...)                                                                    \
    do {                                                                              \
        if (!(__VA_ARGS__)) {                                                         \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
            ++g_failures;                                                             \
        }                                                                             \
    } while (0)

// ---------------------------------------------------------------------------------------------
// Grid math
// ---------------------------------------------------------------------------------------------
static void testGrid() {
    CHECK(spacingMs(0) == 500);
    CHECK(spacingMs(1) == 1000);
    CHECK(spacingMs(3) == 4000);
    CHECK(spacingMs(kMaxLevel) == 500 * 256);
    CHECK(spacingMs(99) == spacingMs(kMaxLevel));  // out-of-range levels clamp
    CHECK(spacingUs(2) == 2000000);

    // Nearest tile, rounding half up, never negative.
    CHECK(tileIndexForTimeUs(0, 0) == 0);
    CHECK(tileIndexForTimeUs(-5, 0) == 0);
    CHECK(tileIndexForTimeUs(249999, 0) == 0);
    CHECK(tileIndexForTimeUs(250000, 0) == 1);
    CHECK(tileIndexForTimeUs(1000000, 1) == 1);
    CHECK(tileIndexForTimeUs(1499999, 1) == 1);
    CHECK(tileIndexForTimeUs(1500000, 1) == 2);
    CHECK(tileTimeUs(2, 3) == 6000000);

    // Levels are nested: every level L+1 tile is a level L tile.
    for (int l = 0; l < kMaxLevel; ++l) CHECK(spacingMs(l + 1) == 2 * spacingMs(l));

    // Level choice follows the spacing one cell covers; the finest level is the floor.
    CHECK(levelForDesiredSpacingMs(0.0) == 0);
    CHECK(levelForDesiredSpacingMs(100.0) == 0);
    CHECK(levelForDesiredSpacingMs(500.0) == 0);
    CHECK(levelForDesiredSpacingMs(1000.0) == 1);
    CHECK(levelForDesiredSpacingMs(1400.0) == 1);   // log2(2.8) = 1.49 -> 1
    CHECK(levelForDesiredSpacingMs(1500.0) == 2);   // log2(3) = 1.58 -> 2
    CHECK(levelForDesiredSpacingMs(4000.0) == 3);
    CHECK(levelForDesiredSpacingMs(1e9) == kMaxLevel);

    // Source time keeps microsecond precision and does not overflow for very long media.
    CHECK(sourceTimeUs(30, 30, 1) == 1000000);
    CHECK(sourceTimeUs(60000, 60000, 1001) == 1001000000);      // 59.94 fps: 60000 frames = 1001 s
    CHECK(sourceTimeUs(0, 30, 1) == 0);
    CHECK(sourceTimeUs(-4, 30, 1) == 0);
    CHECK(sourceTimeUs(10, 0, 1) == 0);                          // bad fps is safe
    CHECK(sourceTimeUs(int64_t{1} << 40, 24, 1) > 0);

    CHECK(TileKey{1, 2, 3} == TileKey{1, 2, 3});
    CHECK(TileKey{1, 2, 3} < TileKey{1, 2, 4});
    CHECK(TileKey{1, 2, 9} < TileKey{1, 3, 0});
    CHECK(TileKeyHash{}(TileKey{1, 2, 3}) != TileKeyHash{}(TileKey{1, 2, 4}));
}

// ---------------------------------------------------------------------------------------------
// Cell planning
// ---------------------------------------------------------------------------------------------
static ClipCellParams baseParams() {
    ClipCellParams p{};
    p.assetKey = 7;
    p.clipLeftX = 100.0;
    p.clipRightX = 1100.0;  // 1000 px of clip
    p.visibleLeft = 0.0;
    p.visibleRight = 1440.0;
    p.cellWidth = 200.0;
    p.pxPerFrame = 2.0;  // 30 fps -> 60 px per second -> a 200 px cell covers 3.33 s
    p.projectFpsNum = 30;
    p.projectFpsDen = 1;
    p.sourceInFrame = 0;
    p.sourceFpsNum = 30;
    p.sourceFpsDen = 1;
    return p;
}

static void testPlanCells() {
    std::vector<CellPlan> cells;

    ClipCellParams p = baseParams();
    // 200 px / 2 px-per-frame = 100 frames = 3333 ms -> log2(3333/500) = 2.74 -> level 3.
    CHECK(levelForClip(p) == 3);
    planClipCells(p, &cells);
    CHECK(cells.size() == 5);  // 1000 px / 200 px
    CHECK(cells.front().x0 == 100.0 && cells.front().x1 == 300.0);  // anchored to the clip's left edge
    CHECK(cells.back().x1 == 1100.0);
    for (const CellPlan& c : cells) CHECK(c.key.asset == 7 && c.key.level == 3);
    // Cell k is sampled at its centre, 100k+50 frames after the clip start: 1.67 s, 5 s, 8.33 s ...
    // nearest 4 s tiles.
    CHECK(cells[0].key.index == 0);
    CHECK(cells[1].key.index == 1);  // 5 s -> 4 s tile
    CHECK(cells[2].key.index == 2);  // 8.33 s -> 8 s tile
    CHECK(cells[3].key.index == 3);  // 11.67 s -> 12 s tile

    // Source offset shifts which tiles are asked for (sourceIn = 90 frames = 3 s).
    p.sourceInFrame = 90;
    planClipCells(p, &cells);
    CHECK(cells[0].key.index == tileIndexForTimeUs(3000000 + 1666666, 3));

    // A clip trimmed to start at 1 s must not open on the tile at 0 s, which is media it cut away.
    p.sourceInFrame = 30;
    planClipCells(p, &cells);
    CHECK(tileTimeUs(3, cells[0].key.index) >= 1000000);

    // Scrolled so only the middle of the clip is visible: only those cells, same anchoring.
    p = baseParams();
    p.visibleLeft = 450.0;
    p.visibleRight = 650.0;
    planClipCells(p, &cells);
    CHECK(!cells.empty());
    CHECK(cells.front().x0 <= 450.0 && cells.front().x1 > 450.0);
    CHECK(cells.back().x0 < 650.0);
    CHECK(cells.size() <= 3);
    for (const CellPlan& c : cells) {
        const double k = (c.x0 - 100.0) / 200.0;
        CHECK(k == static_cast<double>(static_cast<int64_t>(k)));  // always on the clip's cell grid
    }

    // Clip entirely off screen: nothing planned.
    p = baseParams();
    p.visibleLeft = 2000.0;
    p.visibleRight = 3000.0;
    planClipCells(p, &cells);
    CHECK(cells.empty());

    // Clip starting left of the screen (negative x) still plans from its visible part only.
    p = baseParams();
    p.clipLeftX = -500.0;
    p.clipRightX = 500.0;
    planClipCells(p, &cells);
    CHECK(!cells.empty());
    CHECK(cells.front().x0 <= 0.0 && cells.front().x1 > 0.0);

    // Zooming out (fewer px per frame) coarsens the level; zooming in refines it, down to level 0.
    p = baseParams();
    p.pxPerFrame = 0.1;
    CHECK(levelForClip(p) > levelForClip(baseParams()));
    p.pxPerFrame = 90.0;
    CHECK(levelForClip(p) == 0);

    // Degenerate inputs never crash and plan nothing.
    p = baseParams();
    p.cellWidth = 0.0;
    planClipCells(p, &cells);
    CHECK(cells.empty());
    p = baseParams();
    p.pxPerFrame = 0.0;
    planClipCells(p, &cells);
    CHECK(cells.empty());
    p = baseParams();
    p.projectFpsNum = 0;
    planClipCells(p, &cells);
    CHECK(cells.empty());
}

static void testPlanCellsOfRetimedClips() {
    std::vector<CellPlan> cells;
    // 500 timeline frames at 2 px each; cell k is centred 100k + 50 frames in.
    ClipCellParams p = baseParams();

    // 2x: 1000 source frames over the 500 timeline frames, so each cell reads twice as far in.
    p.spanFrames = 1000;
    p.durationFrames = 500;
    planClipCells(p, &cells);
    CHECK(cells.size() == 5);
    for (int64_t k = 0; k < 5; ++k) {
        CHECK(cells[k].key.index == tileIndexForTimeUs(sourceTimeUs(2 * (100 * k + 50), 30, 1), 3));
    }

    // Half speed: 250 source frames over 500.
    p.spanFrames = 250;
    planClipCells(p, &cells);
    for (int64_t k = 0; k < 5; ++k) {
        CHECK(cells[k].key.index == tileIndexForTimeUs(sourceTimeUs((100 * k + 50) / 2, 30, 1), 3));
    }

    // Reversed: the first cell shows the end of the range.
    p.spanFrames = 500;
    p.reverse = true;
    planClipCells(p, &cells);
    CHECK(cells[0].key.index == tileIndexForTimeUs(sourceTimeUs(500 - 1 - 50, 30, 1), 3));
    CHECK(cells[4].key.index == tileIndexForTimeUs(sourceTimeUs(500 - 1 - 450, 30, 1), 3));

    // A freeze frame holds one source frame: every cell asks for the same tile (sourceIn = 90 frames = 3 s).
    p = baseParams();
    p.sourceInFrame = 90;
    p.spanFrames = 1;
    p.durationFrames = 500;
    p.freeze = true;
    planClipCells(p, &cells);
    CHECK(cells.size() == 5);
    for (const CellPlan& c : cells) CHECK(c.key.index == tileIndexForTimeUs(sourceTimeUs(90, 30, 1), 3));

    // A plain clip (no span) is unchanged by the new fields.
    p = baseParams();
    planClipCells(p, &cells);
    CHECK(cells[1].key.index == 1);
}

static void testResolve() {
    std::set<TileKey> resident;
    const auto has = [&](const TileKey& k) { return resident.count(k) != 0; };
    TileKey out;

    const TileKey want{1, 3, 5};  // 20 s at 4 s spacing
    CHECK(!resolveTile(want, has, &out));

    resident.insert({1, 5, 1});  // 16 s tile of a coarser level
    CHECK(resolveTile(want, has, &out) && out == (TileKey{1, 5, 1}));

    resident.insert({1, 4, 3});  // level 4 has 8 s spacing: the tile nearest 20 s is #3 (24 s)
    CHECK(resolveTile(want, has, &out));
    CHECK(out.level == 4);  // the closer coarse level wins over a farther one

    resident.insert({1, 1, 20});  // finer tile at exactly the same instant (20 s at 1 s spacing)
    CHECK(resolveTile(want, has, &out) && out == (TileKey{1, 1, 20}));

    resident.insert({1, 2, 10});  // a finer level nearer than level 1 is preferred
    CHECK(resolveTile(want, has, &out) && out == (TileKey{1, 2, 10}));

    resident.insert(want);  // exact always wins
    CHECK(resolveTile(want, has, &out) && out == want);

    // Another asset's tiles are never used.
    resident = {{2, 3, 5}};
    CHECK(!resolveTile(want, has, &out));
}

// ---------------------------------------------------------------------------------------------
// Atlas layout and slot LRU
// ---------------------------------------------------------------------------------------------
static void testAtlasLayout() {
    const AtlasLayout l = atlasLayoutFor(8u * 1024 * 1024, 4096);
    CHECK(l.cols == 16 && l.rows == 28);
    CHECK(l.width == 2048 && l.height == 2016);
    CHECK(l.slots() == 448);
    CHECK(l.bytes() <= 8u * 1024 * 1024);  // the budget is a hard ceiling

    // A smaller GPU texture limit shrinks the atlas.
    const AtlasLayout small = atlasLayoutFor(8u * 1024 * 1024, 1024);
    CHECK(small.cols == 8 && small.width == 1024);
    CHECK(small.bytes() <= 8u * 1024 * 1024);

    // A tight budget gives fewer rows; one that cannot hold a row gives no atlas at all.
    const AtlasLayout tight = atlasLayoutFor(16u * kTileBytes * 3, 2048);
    CHECK(tight.rows == 3 && tight.slots() == 48);
    CHECK(atlasLayoutFor(1000, 2048).slots() == 0);
    CHECK(atlasLayoutFor(8u * 1024 * 1024, 64).slots() == 0);
    for (size_t budget : {size_t{1} << 20, size_t{3} << 20, size_t{16} << 20}) {
        CHECK(atlasLayoutFor(budget, 8192).bytes() <= budget);
    }
}

static TileKey K(int64_t i) { return {1, 0, i}; }

static void testSlotLru() {
    SlotLru lru(3);
    lru.beginFrame();
    const int a = lru.acquire(K(1)), b = lru.acquire(K(2)), c = lru.acquire(K(3));
    CHECK(a >= 0 && b >= 0 && c >= 0);
    CHECK(a != b && b != c && a != c);
    CHECK(lru.size() == 3);

    // Everything was touched this frame: nothing may be evicted, the upload is refused.
    CHECK(lru.acquire(K(4)) == -1);
    CHECK(lru.contains(K(1)) && lru.contains(K(2)) && lru.contains(K(3)));

    // Next frame the least recently used slot (tile 1) is reused.
    lru.beginFrame();
    TileKey evicted;
    const int d = lru.acquire(K(4), &evicted);
    CHECK(d == a);
    CHECK(evicted == K(1));
    CHECK(!lru.contains(K(1)) && lru.contains(K(4)));

    // Touching a tile makes it the most recent: tile 2 survives, tile 3 goes next.
    lru.beginFrame();
    CHECK(lru.find(K(2)) == b);
    const int e = lru.acquire(K(5), &evicted);
    CHECK(evicted == K(3) && e == c);

    // Tiles used this frame are protected even if they are the oldest by recency order.
    lru.beginFrame();
    CHECK(lru.find(K(2)) == b);
    CHECK(lru.find(K(4)) == d);
    CHECK(lru.find(K(5)) == e);
    CHECK(lru.acquire(K(6)) == -1);

    // Re-acquiring a resident key returns its own slot without evicting anything.
    lru.beginFrame();
    CHECK(lru.acquire(K(5)) == e);
    CHECK(lru.size() == 3);
    CHECK(lru.find(K(99)) == -1);

    lru.clear();
    CHECK(lru.size() == 0 && !lru.contains(K(5)));
    lru.beginFrame();
    CHECK(lru.acquire(K(7)) >= 0);

    // Heavy churn keeps the invariants: size never exceeds capacity and slots stay unique.
    SlotLru big(16);
    std::set<int> seenSlots;
    for (int frame = 0; frame < 200; ++frame) {
        big.beginFrame();
        for (int i = 0; i < 8; ++i) {
            const int64_t id = (frame * 5 + i * 3) % 50;
            const int slot = big.acquire(K(id));
            CHECK(slot >= -1 && slot < 16);
            if (slot >= 0) seenSlots.insert(slot);
        }
        CHECK(big.size() <= 16);
    }
    CHECK(seenSlots.size() <= 16);
}

// ---------------------------------------------------------------------------------------------
// Tile store
// ---------------------------------------------------------------------------------------------
static std::vector<uint16_t> tilePattern(uint16_t seed) {
    std::vector<uint16_t> t(kTilePixels);
    for (size_t i = 0; i < t.size(); ++i) t[i] = static_cast<uint16_t>(seed * 31 + i);
    return t;
}

static std::string tempDir() {
    const std::filesystem::path p = std::filesystem::temp_directory_path() / ("uv_thumb_test_" + std::to_string(::getpid()));
    std::filesystem::remove_all(p);
    return p.string();
}

static void testStore() {
    const std::string dir = tempDir() + "/asset-1";
    std::vector<uint16_t> out(kTilePixels);

    {
        ThumbStore store(dir, 1234);
        CHECK(!store.has(2, 5));
        CHECK(store.read(2, 5, out.data()) == core::Status::IoError);  // absent, and nothing was created
        CHECK(!std::filesystem::exists(dir));

        CHECK(store.append(2, 5, tilePattern(1).data()) == core::Status::Ok);
        CHECK(store.append(2, 9, tilePattern(2).data()) == core::Status::Ok);
        CHECK(store.append(0, 0, tilePattern(3).data()) == core::Status::Ok);
        CHECK(store.has(2, 5) && store.has(2, 9) && store.has(0, 0));
        CHECK(!store.has(2, 6) && !store.has(1, 5));

        CHECK(store.read(2, 9, out.data()) == core::Status::Ok && out == tilePattern(2));
        CHECK(store.read(2, 5, out.data()) == core::Status::Ok && out == tilePattern(1));

        // A tile that is already stored is not overwritten.
        CHECK(store.append(2, 5, tilePattern(77).data()) == core::Status::Ok);
        CHECK(store.read(2, 5, out.data()) == core::Status::Ok && out == tilePattern(1));

        CHECK(store.append(0, -1, tilePattern(1).data()) == core::Status::InvalidArgument);
        CHECK(store.read(0, 0, nullptr) == core::Status::InvalidArgument);
    }

    // Reopening the same media finds everything again.
    {
        ThumbStore store(dir, 1234);
        CHECK(store.has(2, 5) && store.has(2, 9) && store.has(0, 0));
        CHECK(store.read(2, 9, out.data()) == core::Status::Ok && out == tilePattern(2));
        CHECK(store.append(2, 11, tilePattern(4).data()) == core::Status::Ok);  // appends continue after the old tail
        CHECK(store.read(2, 11, out.data()) == core::Status::Ok && out == tilePattern(4));
        CHECK(store.read(2, 5, out.data()) == core::Status::Ok && out == tilePattern(1));
    }

    // A torn trailing record (interrupted append) is cut off and later records stay aligned.
    {
        const std::string file = dir + "/L2.tiles";
        const auto size = std::filesystem::file_size(file);
        std::filesystem::resize_file(file, size - 100);  // damages the last record (index 11)
        ThumbStore store(dir, 1234);
        CHECK(store.has(2, 5) && store.has(2, 9));
        CHECK(!store.has(2, 11));
        CHECK(store.append(2, 12, tilePattern(5).data()) == core::Status::Ok);
        CHECK(store.read(2, 12, out.data()) == core::Status::Ok && out == tilePattern(5));
        CHECK(store.read(2, 9, out.data()) == core::Status::Ok && out == tilePattern(2));
        CHECK(std::filesystem::file_size(file) == ThumbStore::kHeaderBytes + 3 * ThumbStore::kRecordBytes);
    }

    // Different media (size changed) discards the cached tiles instead of showing stale pictures.
    {
        ThumbStore store(dir, 9999);
        CHECK(!store.has(2, 5) && !store.has(0, 0));
        CHECK(store.append(2, 1, tilePattern(6).data()) == core::Status::Ok);
        CHECK(store.read(2, 1, out.data()) == core::Status::Ok && out == tilePattern(6));
    }
    {
        ThumbStore store(dir, 1234);  // back to the old media: the file now belongs to the new one
        CHECK(!store.has(2, 1));
    }

    // Corrupt header: rebuilt on the next append, treated as empty before that.
    {
        std::ofstream(dir + "/L4.tiles", std::ios::binary) << "garbage that is not a header at all..";
        ThumbStore store(dir, 1234);
        CHECK(!store.has(4, 0));
        CHECK(store.append(4, 0, tilePattern(8).data()) == core::Status::Ok);
        CHECK(store.read(4, 0, out.data()) == core::Status::Ok && out == tilePattern(8));
    }

    std::filesystem::remove_all(std::filesystem::path(dir).parent_path());
}

// ---------------------------------------------------------------------------------------------
// YUV -> tile
// ---------------------------------------------------------------------------------------------
struct Planes {
    int w, h;
    std::vector<uint8_t> y, u, v;
    Planes(int width, int height, uint8_t yy, uint8_t uu, uint8_t vv)
        : w(width), h(height), y(static_cast<size_t>(width) * height, yy),
          u(static_cast<size_t>(width / 2) * (height / 2), uu), v(static_cast<size_t>(width / 2) * (height / 2), vv) {}
    YuvFrame frame() const {
        YuvFrame f;
        f.y = y.data();
        f.u = u.data();
        f.v = v.data();
        f.yRowStride = w;
        f.uvRowStride = w / 2;
        f.uvPixelStride = 1;
        f.cropWidth = w;
        f.cropHeight = h;
        return f;
    }
};

static void rgb(uint16_t px, int* r, int* g, int* b) {
    *r = ((px >> 11) & 31) * 255 / 31;
    *g = ((px >> 5) & 63) * 255 / 63;
    *b = (px & 31) * 255 / 31;
}

static bool nearColour(uint16_t px, int r, int g, int b, int tol = 12) {
    int rr, gg, bb;
    rgb(px, &rr, &gg, &bb);
    return std::abs(rr - r) <= tol && std::abs(gg - g) <= tol && std::abs(bb - b) <= tol;
}

static uint16_t at(const std::vector<uint16_t>& t, int x, int y) { return t[static_cast<size_t>(y) * kTileWidth + x]; }

static void testYuv() {
    std::vector<uint16_t> tile(kTilePixels);

    // Limited-range white, black and mid grey.
    CHECK(yuvToTile(Planes(64, 36, 235, 128, 128).frame(), 0, tile.data()));
    CHECK(nearColour(at(tile, 5, 5), 255, 255, 255));
    CHECK(nearColour(at(tile, 120, 70), 255, 255, 255));
    CHECK(yuvToTile(Planes(64, 36, 16, 128, 128).frame(), 0, tile.data()));
    CHECK(nearColour(at(tile, 64, 36), 0, 0, 0));
    CHECK(yuvToTile(Planes(64, 36, 126, 128, 128).frame(), 0, tile.data()));
    CHECK(nearColour(at(tile, 64, 36), 128, 128, 128, 6));

    // Colour: a strongly red-ish chroma (V high) gives R > G, B.
    CHECK(yuvToTile(Planes(64, 36, 81, 90, 240).frame(), 0, tile.data()));
    {
        int r, g, b;
        rgb(at(tile, 64, 36), &r, &g, &b);
        CHECK(r > 200 && g < 60 && b < 60);
    }

    // Orientation: left half bright, right half dark (landscape 16:9 source).
    {
        Planes p(64, 36, 16, 128, 128);
        for (int row = 0; row < 36; ++row) {
            for (int col = 0; col < 32; ++col) p.y[static_cast<size_t>(row) * 64 + col] = 235;
        }
        CHECK(yuvToTile(p.frame(), 0, tile.data()));
        CHECK(nearColour(at(tile, 10, 36), 255, 255, 255) && nearColour(at(tile, 117, 36), 0, 0, 0));
        CHECK(yuvToTile(p.frame(), 180, tile.data()));
        CHECK(nearColour(at(tile, 10, 36), 0, 0, 0) && nearColour(at(tile, 117, 36), 255, 255, 255));
        CHECK(yuvToTile(p.frame(), 360, tile.data()));  // full turn = no rotation
        CHECK(nearColour(at(tile, 10, 36), 255, 255, 255));
        CHECK(yuvToTile(p.frame(), -180, tile.data()));  // negative angles normalise
        CHECK(nearColour(at(tile, 10, 36), 0, 0, 0));
    }

    // Portrait source (36x64) with the top half bright: 90 deg clockwise puts the top on the right,
    // 270 puts it on the left. The 16:9 tile centre-crops the (upright, now landscape) image.
    {
        Planes p(36, 64, 16, 128, 128);
        for (int row = 0; row < 32; ++row) {
            for (int col = 0; col < 36; ++col) p.y[static_cast<size_t>(row) * 36 + col] = 235;
        }
        CHECK(yuvToTile(p.frame(), 90, tile.data()));
        CHECK(nearColour(at(tile, 10, 36), 0, 0, 0) && nearColour(at(tile, 117, 36), 255, 255, 255));
        CHECK(yuvToTile(p.frame(), 270, tile.data()));
        CHECK(nearColour(at(tile, 10, 36), 255, 255, 255) && nearColour(at(tile, 117, 36), 0, 0, 0));
        // Unrotated, the portrait source is cropped to its middle band: top half bright, bottom dark.
        CHECK(yuvToTile(p.frame(), 0, tile.data()));
        CHECK(nearColour(at(tile, 64, 3), 255, 255, 255) && nearColour(at(tile, 64, 68), 0, 0, 0));
    }

    // Wide source (21:9): the left and right edges are cropped away, the centre is kept.
    {
        Planes p(84, 36, 16, 128, 128);
        for (int row = 0; row < 36; ++row) {
            for (int col = 0; col < 84; ++col) {
                if (col < 10 || col >= 74) p.y[static_cast<size_t>(row) * 84 + col] = 235;  // bright outer bands
            }
        }
        CHECK(yuvToTile(p.frame(), 0, tile.data()));
        CHECK(nearColour(at(tile, 0, 36), 0, 0, 0, 20) && nearColour(at(tile, 127, 36), 0, 0, 0, 20));
    }

    // Crop rect and strides: padding bytes with junk must not leak into the tile.
    {
        const int stride = 80, w = 64, h = 36;
        std::vector<uint8_t> y(static_cast<size_t>(stride) * h, 235), u(static_cast<size_t>(stride) * h / 4, 128),
            v(static_cast<size_t>(stride) * h / 4, 128);
        for (int row = 0; row < h; ++row) {
            for (int col = w; col < stride; ++col) y[static_cast<size_t>(row) * stride + col] = 16;  // dark padding
        }
        YuvFrame f;
        f.y = y.data();
        f.u = u.data();
        f.v = v.data();
        f.yRowStride = stride;
        f.uvRowStride = stride / 2;
        f.uvPixelStride = 1;
        f.cropWidth = w;
        f.cropHeight = h;
        CHECK(yuvToTile(f, 0, tile.data()));
        CHECK(nearColour(at(tile, 127, 36), 255, 255, 255));
    }

    // Semi-planar chroma (NV12-style pixel stride 2) reads the right samples.
    {
        const int w = 64, h = 36;
        std::vector<uint8_t> y(static_cast<size_t>(w) * h, 126);
        std::vector<uint8_t> uv(static_cast<size_t>(w) * h / 2, 0);
        for (size_t i = 0; i < uv.size(); i += 2) {
            uv[i] = 90;       // U
            uv[i + 1] = 240;  // V
        }
        YuvFrame f;
        f.y = y.data();
        f.u = uv.data();
        f.v = uv.data() + 1;
        f.yRowStride = w;
        f.uvRowStride = w;
        f.uvPixelStride = 2;
        f.cropWidth = w;
        f.cropHeight = h;
        CHECK(yuvToTile(f, 0, tile.data()));
        int r, g, b;
        rgb(at(tile, 64, 36), &r, &g, &b);
        CHECK(r > g && r > b);
    }

    // 16-bit (P010-style) samples: only the high byte matters, and luma strides are in bytes.
    {
        const int w = 64, h = 36;
        std::vector<uint8_t> y(static_cast<size_t>(w) * h * 2), uv(static_cast<size_t>(w) * h);  // 16-bit Y, interleaved 16-bit UV
        for (size_t i = 0; i < y.size(); i += 2) {
            y[i] = 0x40;       // low byte noise
            y[i + 1] = 235;    // high byte: white
        }
        for (size_t i = 0; i < uv.size(); i += 4) {
            uv[i] = 0x10;
            uv[i + 1] = 128;
            uv[i + 2] = 0x20;
            uv[i + 3] = 128;
        }
        YuvFrame f;
        f.y = y.data();
        f.u = uv.data();
        f.v = uv.data() + 2;
        f.yRowStride = w * 2;
        f.uvRowStride = w * 2;
        f.uvPixelStride = 4;
        f.sampleBytes = 2;
        f.cropWidth = w;
        f.cropHeight = h;
        CHECK(yuvToTile(f, 0, tile.data()));
        CHECK(nearColour(at(tile, 64, 36), 255, 255, 255));
        f.sampleBytes = 3;
        CHECK(!yuvToTile(f, 0, tile.data()));
    }

    // Bad input is rejected without touching memory.
    CHECK(!yuvToTile(YuvFrame{}, 0, tile.data()));
    CHECK(!yuvToTile(Planes(64, 36, 100, 128, 128).frame(), 45, tile.data()));
    CHECK(!yuvToTile(Planes(64, 36, 100, 128, 128).frame(), 0, nullptr));
    YuvFrame tiny = Planes(64, 36, 100, 128, 128).frame();
    tiny.cropWidth = 1;
    CHECK(!yuvToTile(tiny, 0, tile.data()));
}

// ---------------------------------------------------------------------------------------------
// Photo -> tile
// ---------------------------------------------------------------------------------------------
static void testRgbaTile() {
    std::vector<uint16_t> tile(kTilePixels);
    // A wide photo: left half red, right half blue, with a green band top and bottom that the 16:9 crop must drop.
    const int w = 400, h = 300;  // 4:3, taller than 16:9
    std::vector<uint8_t> img(static_cast<size_t>(w) * h * 4, 255);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint8_t* p = &img[(static_cast<size_t>(y) * w + x) * 4];
            const bool band = y < 30 || y >= h - 30;
            p[0] = band ? 0 : (x < w / 2 ? 255 : 0);
            p[1] = band ? 255 : 0;
            p[2] = band ? 0 : (x < w / 2 ? 0 : 255);
            p[3] = 255;
        }
    }
    CHECK(rgbaToTile(img.data(), w, h, static_cast<size_t>(w) * 4, tile.data()));
    CHECK(nearColour(at(tile, 5, 0), 255, 0, 0));    // top-left is red, the green band was cropped away
    CHECK(nearColour(at(tile, 5, 71), 255, 0, 0));
    CHECK(nearColour(at(tile, 122, 36), 0, 0, 255));
    CHECK(nearColour(at(tile, 63, 36), 255, 0, 0));  // the split sits at the middle column
    CHECK(nearColour(at(tile, 64, 36), 0, 0, 255));

    // A row stride wider than the picture is honoured; a tiny 1x1 image fills the tile.
    std::vector<uint8_t> padded(static_cast<size_t>(3) * 64 * 4, 0);
    for (int y = 0; y < 3; ++y) {
        for (int x = 0; x < 2; ++x) {
            uint8_t* p = &padded[(static_cast<size_t>(y) * 64 + x) * 4];
            p[0] = 0;
            p[1] = 255;
            p[2] = 0;
            p[3] = 255;
        }
    }
    CHECK(rgbaToTile(padded.data(), 2, 3, 64 * 4, tile.data()));
    CHECK(nearColour(at(tile, 64, 36), 0, 255, 0));
    const uint8_t one[4] = {255, 255, 255, 255};
    CHECK(rgbaToTile(one, 1, 1, 4, tile.data()));
    CHECK(nearColour(at(tile, 100, 20), 255, 255, 255));

    // Unusable input is rejected without touching memory.
    CHECK(!rgbaToTile(nullptr, 4, 4, 16, tile.data()));
    CHECK(!rgbaToTile(one, 0, 1, 4, tile.data()));
    CHECK(!rgbaToTile(one, 1, 1, 3, tile.data()));
    CHECK(!rgbaToTile(one, 1, 1, 4, nullptr));
}

int main() {
    testGrid();
    testPlanCells();
    testPlanCellsOfRetimedClips();
    testResolve();
    testAtlasLayout();
    testSlotLru();
    testStore();
    testYuv();
    testRgbaTile();
    if (g_failures == 0) {
        std::printf("thumbnail host tests passed\n");
        return 0;
    }
    std::fprintf(stderr, "%d check(s) failed\n", g_failures);
    return 1;
}
