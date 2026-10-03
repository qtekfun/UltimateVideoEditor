#pragma once

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <unordered_map>

#include "core/error.h"
#include "thumbnail/tile_math.h"

namespace uv::thumb {

// On-disk thumbnail cache for one asset: `<dir>/L<level>.tiles`, an append-only log of fixed-size
// RGB565 tiles after a small header. A header that does not match the current media (size, tile
// shape, spacing) discards the file, and a torn tail record from a crash is cut off on open.
// Host-testable: plain POSIX file I/O only.
class ThumbStore {
public:
    // `dir` is created on demand. `mediaSize` ties the cache to the file it was built from.
    ThumbStore(std::string dir, uint64_t mediaSize);
    ~ThumbStore();
    ThumbStore(const ThumbStore&) = delete;
    ThumbStore& operator=(const ThumbStore&) = delete;

    bool has(int level, int64_t index);
    // Reads one tile (kTilePixels uint16_t values). Status::IoError when absent or unreadable.
    core::Status read(int level, int64_t index, uint16_t* pixels);
    // Appends a tile; a tile that is already stored is left as it is.
    core::Status append(int level, int64_t index, const uint16_t* pixels);

    static constexpr size_t kHeaderBytes = 32;
    static constexpr size_t kRecordBytes = 4 + kTileBytes;

private:
    struct LevelFile {
        int fd = -1;
        std::unordered_map<uint32_t, uint64_t> offsets;  // tile index -> record offset
        uint64_t endOffset = 0;
    };

    LevelFile* openLevel(int level, bool create);  // mutex_ held
    void closeAll();

    std::string dir_;
    uint64_t mediaSize_;
    std::mutex mutex_;
    std::map<int, LevelFile> levels_;
};

}  // namespace uv::thumb
