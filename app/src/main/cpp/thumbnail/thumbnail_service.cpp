#include "thumbnail/thumbnail_service.h"

#include <android/log.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>

#define LOG_TAG "uv_thumb"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace uv::thumb {

using core::Status;
using Clock = std::chrono::steady_clock;

namespace {

constexpr size_t kMaxReadyTiles = 48;                    // bounds memory if the renderer stops draining
constexpr auto kDecoderIdleRelease = std::chrono::seconds(2);
constexpr int kBackgroundNice = 10;                       // ANDROID_PRIORITY_BACKGROUND

std::chrono::seconds backoffFor(int failures) { return std::chrono::seconds(std::min(60, 5 << std::min(failures, 4))); }

}  // namespace

ThumbnailService::ThumbnailService(Listener listener) : listener_(std::move(listener)) {
    worker_ = std::thread([this] { run(); });
}

ThumbnailService::~ThumbnailService() {
    stop_.store(true);
    cv_.notify_all();
    if (worker_.joinable()) worker_.join();
    for (auto& [key, asset] : assets_) {
        if (asset->fd >= 0) ::close(asset->fd);
    }
}

void ThumbnailService::registerAsset(int64_t assetKey, int fd, std::string dir) {
    struct stat st {};
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (assets_.count(assetKey) != 0 || fd < 0 || ::fstat(fd, &st) != 0) {
            if (fd >= 0) ::close(fd);  // duplicate registration or unusable descriptor
            return;
        }
        auto asset = std::make_unique<Asset>();
        asset->fd = fd;
        asset->store = std::make_unique<ThumbStore>(std::move(dir), static_cast<uint64_t>(st.st_size));
        assets_.emplace(assetKey, std::move(asset));
    }
    cv_.notify_one();
}

bool ThumbnailService::isActive(int64_t assetKey) const {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = assets_.find(assetKey);
    return it != assets_.end() && Clock::now() >= it->second->retryAt;
}

void ThumbnailService::want(int64_t assetKey, int level, const std::vector<int64_t>& indices) {
    bool added = false;
    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = assets_.find(assetKey);
        if (it == assets_.end() || Clock::now() < it->second->retryAt) return;
        Asset& a = *it->second;
        // The newest request wins: tiles the user has scrolled away from are dropped. Anything
        // already in flight stays in pending_, which keeps it from being queued twice.
        for (const TileKey& k : a.wanted) pending_.erase(k);
        a.wanted.clear();
        for (int64_t index : indices) {
            const TileKey key{assetKey, level, index};
            if (!pending_.insert(key).second) continue;
            a.wanted.push_back(key);
            added = true;
        }
    }
    if (added) cv_.notify_one();
}

size_t ThumbnailService::takeReady(std::vector<ReadyTile>* out, size_t max) {
    size_t remaining;
    {
        std::lock_guard<std::mutex> lock(mu_);
        while (!ready_.empty() && max > 0) {
            pending_.erase(ready_.front().key);
            out->push_back(std::move(ready_.front()));
            ready_.pop_front();
            --max;
        }
        remaining = ready_.size();
    }
    if (remaining < kMaxReadyTiles / 2) cv_.notify_one();  // the worker may be waiting for room
    return remaining;
}

bool ThumbnailService::pickJob(TileKey* key, Asset** asset, int* fd) {
    if (ready_.size() >= kMaxReadyTiles) return false;
    for (auto& [assetKey, a] : assets_) {
        if (a->wanted.empty() || Clock::now() < a->retryAt) continue;
        *key = a->wanted.front();
        a->wanted.pop_front();
        *asset = a.get();
        *fd = a->fd;
        return true;
    }
    return false;
}

void ThumbnailService::run() {
    setpriority(PRIO_PROCESS, 0, kBackgroundNice);

    std::unique_ptr<ThumbDecoder> decoder;
    int64_t decoderAsset = -1;
    auto lastDecode = Clock::now();

    while (true) {
        TileKey key;
        Asset* asset = nullptr;
        int fd = -1;
        {
            std::unique_lock<std::mutex> lock(mu_);
            while (!stop_.load() && !pickJob(&key, &asset, &fd)) {
                // Give the hardware decoder back when there has been nothing to do for a while.
                if (decoder != nullptr && Clock::now() - lastDecode > kDecoderIdleRelease) {
                    lock.unlock();
                    decoder.reset();
                    decoderAsset = -1;
                    lock.lock();
                    continue;
                }
                cv_.wait_for(lock, decoder != nullptr ? std::chrono::milliseconds(500) : std::chrono::seconds(30));
            }
            if (stop_.load()) return;
        }

        std::vector<uint16_t> pixels(kTilePixels);
        Status status = Status::Ok;
        std::string detail;
        if (asset->store->read(key.level, key.index, pixels.data()) != Status::Ok) {
            if (decoder == nullptr || decoderAsset != key.asset) {
                decoder.reset();  // one hardware decoder at a time
                decoder = ThumbDecoder::open(fd, &status, &detail);
                decoderAsset = decoder != nullptr ? key.asset : -1;
            }
            if (decoder != nullptr) {
                status = decoder->decodeTile(tileTimeUs(key.level, key.index), stop_, pixels.data());
                if (status != Status::Ok && status != Status::Cancelled) detail = "decoding the tile at level " + std::to_string(key.level) + " #" + std::to_string(key.index) + " failed";
                if (status == Status::Ok && asset->store->append(key.level, key.index, pixels.data()) != Status::Ok) {
                    LOGW("could not cache thumbnail tile L%d #%lld (asset %lld)", key.level, static_cast<long long>(key.index), static_cast<long long>(key.asset));
                }
                lastDecode = Clock::now();
            }
        }

        bool reportError = false;
        int retryFailures = 0;
        {
            std::lock_guard<std::mutex> lock(mu_);
            if (status == Status::Ok) {
                asset->consecutiveFailures = 0;
                ready_.push_back({key, std::move(pixels)});
            } else if (status != Status::Cancelled) {
                pending_.erase(key);
                for (const TileKey& k : asset->wanted) pending_.erase(k);
                asset->wanted.clear();
                ++asset->consecutiveFailures;
                retryFailures = asset->consecutiveFailures;
                asset->retryAt = Clock::now() + backoffFor(asset->consecutiveFailures);
                reportError = !asset->errorReported;
                asset->errorReported = true;
            } else {
                pending_.erase(key);
            }
        }
        if (status != Status::Ok && status != Status::Cancelled) {
            decoder.reset();  // a failed decoder is not reused
            decoderAsset = -1;
            LOGW("asset %lld: %s: %s (retry in %d s)", static_cast<long long>(key.asset), core::statusName(status), detail.c_str(),
                 static_cast<int>(backoffFor(retryFailures).count()));
            if (reportError && listener_.onError) listener_.onError(key.asset, status, detail);
        }
        if (status == Status::Ok && listener_.onTilesReady) listener_.onTilesReady();
    }
}

}  // namespace uv::thumb
