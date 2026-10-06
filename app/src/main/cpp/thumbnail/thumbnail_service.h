#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "core/error.h"
#include "thumbnail/thumb_decoder.h"
#include "thumbnail/thumb_store.h"
#include "thumbnail/tile_math.h"

namespace uv::thumb {

// Background thumbnail generation. One low-priority worker thread, one decoder open at a time (and
// only while there is work), tiles persisted in a per-asset ThumbStore. The render thread tells the
// service which tiles it is missing each frame (want) and collects finished tiles (takeReady); it
// never blocks on decoding or disk.
class ThumbnailService {
public:
    struct Listener {
        std::function<void()> onTilesReady;  // worker thread; poke the renderer
        std::function<void(int64_t assetKey, core::Status status, const std::string& detail)> onError;  // worker thread
    };

    struct ReadyTile {
        TileKey key;
        std::vector<uint16_t> pixels;  // kTilePixels RGB565 values
    };

    explicit ThumbnailService(Listener listener);
    ~ThumbnailService();
    ThumbnailService(const ThumbnailService&) = delete;
    ThumbnailService& operator=(const ThumbnailService&) = delete;

    // Takes ownership of fd (closed with the service). `dir` holds the asset's tile files.
    void registerAsset(int64_t assetKey, int fd, std::string dir);

    // True for an asset that is registered and not backing off after a failure.
    bool isActive(int64_t assetKey) const;

    // Replaces the set of tiles wanted for an asset (all at `level`). Tiles already queued or in
    // flight are not requested twice.
    void want(int64_t assetKey, int level, const std::vector<int64_t>& indices);

    // Moves up to `max` finished tiles to `out`. Returns how many tiles remain queued.
    size_t takeReady(std::vector<ReadyTile>* out, size_t max);

private:
    struct Asset {
        int fd = -1;
        std::unique_ptr<ThumbStore> store;
        std::deque<TileKey> wanted;
        int consecutiveFailures = 0;
        std::chrono::steady_clock::time_point retryAt{};
        bool errorReported = false;
    };

    void run();
    bool pickJob(TileKey* key, Asset** asset, int* fd);

    Listener listener_;
    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::map<int64_t, std::unique_ptr<Asset>> assets_;
    std::set<TileKey> pending_;  // queued for decoding, in flight, or waiting in ready_
    std::deque<ReadyTile> ready_;
    std::atomic<bool> stop_{false};
    std::thread worker_;
};

}  // namespace uv::thumb
