#include "thumbnail/thumb_store.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <filesystem>
#include <vector>

namespace uv::thumb {

using core::Status;

namespace {

constexpr uint32_t kMagic = 0x48545655;  // "UVTH"
constexpr uint32_t kVersion = 1;

struct Header {
    uint32_t magic;
    uint32_t version;
    uint32_t tileW;
    uint32_t tileH;
    uint64_t spacingMs;
    uint64_t mediaSize;
};
static_assert(sizeof(Header) == ThumbStore::kHeaderBytes, "header layout is part of the file format");

bool preadAll(int fd, void* buf, size_t n, uint64_t off) {
    auto* p = static_cast<uint8_t*>(buf);
    while (n > 0) {
        const ssize_t r = ::pread(fd, p, n, static_cast<off_t>(off));
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return false;
        p += r;
        n -= static_cast<size_t>(r);
        off += static_cast<uint64_t>(r);
    }
    return true;
}

bool pwriteAll(int fd, const void* buf, size_t n, uint64_t off) {
    const auto* p = static_cast<const uint8_t*>(buf);
    while (n > 0) {
        const ssize_t r = ::pwrite(fd, p, n, static_cast<off_t>(off));
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return false;
        p += r;
        n -= static_cast<size_t>(r);
        off += static_cast<uint64_t>(r);
    }
    return true;
}

}  // namespace

ThumbStore::ThumbStore(std::string dir, uint64_t mediaSize) : dir_(std::move(dir)), mediaSize_(mediaSize) {}

ThumbStore::~ThumbStore() { closeAll(); }

void ThumbStore::closeAll() {
    for (auto& [level, file] : levels_) {
        if (file.fd >= 0) ::close(file.fd);
    }
    levels_.clear();
}

ThumbStore::LevelFile* ThumbStore::openLevel(int level, bool create) {
    auto found = levels_.find(level);
    if (found != levels_.end()) return &found->second;

    const std::string path = dir_ + "/L" + std::to_string(level) + ".tiles";
    std::error_code ec;
    if (create) std::filesystem::create_directories(dir_, ec);
    int fd = ::open(path.c_str(), O_RDWR | O_CLOEXEC | (create ? O_CREAT : 0), 0644);
    if (fd < 0) return nullptr;

    struct stat st {};
    if (::fstat(fd, &st) != 0) {
        ::close(fd);
        return nullptr;
    }

    const Header want{kMagic, kVersion, kTileWidth, kTileHeight, static_cast<uint64_t>(spacingMs(level)), mediaSize_};
    Header have{};
    bool valid = static_cast<size_t>(st.st_size) >= kHeaderBytes && preadAll(fd, &have, sizeof(have), 0) &&
                 std::memcmp(&have, &want, sizeof(have)) == 0;
    LevelFile file;
    file.fd = fd;
    if (!valid) {
        if (!create) {
            ::close(fd);
            return nullptr;
        }
        // Wrong media, wrong format or brand new: start the log over.
        if (::ftruncate(fd, 0) != 0 || !pwriteAll(fd, &want, sizeof(want), 0)) {
            ::close(fd);
            return nullptr;
        }
        file.endOffset = kHeaderBytes;
    } else {
        const uint64_t body = static_cast<uint64_t>(st.st_size) - kHeaderBytes;
        const uint64_t records = body / kRecordBytes;
        for (uint64_t i = 0; i < records; ++i) {
            const uint64_t off = kHeaderBytes + i * kRecordBytes;
            uint32_t index = 0;
            if (!preadAll(fd, &index, sizeof(index), off)) break;
            file.offsets.emplace(index, off);
            file.endOffset = off + kRecordBytes;
        }
        if (file.endOffset == 0) file.endOffset = kHeaderBytes;
        // Cut a torn record left by an interrupted append so new records stay aligned.
        if (static_cast<uint64_t>(st.st_size) != file.endOffset) (void)::ftruncate(fd, static_cast<off_t>(file.endOffset));
    }
    return &levels_.emplace(level, std::move(file)).first->second;
}

bool ThumbStore::has(int level, int64_t index) {
    if (index < 0 || index > UINT32_MAX) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    LevelFile* f = openLevel(level, false);
    return f != nullptr && f->offsets.count(static_cast<uint32_t>(index)) != 0;
}

Status ThumbStore::read(int level, int64_t index, uint16_t* pixels) {
    if (pixels == nullptr || index < 0 || index > UINT32_MAX) return Status::InvalidArgument;
    std::lock_guard<std::mutex> lock(mutex_);
    LevelFile* f = openLevel(level, false);
    if (f == nullptr) return Status::IoError;
    auto it = f->offsets.find(static_cast<uint32_t>(index));
    if (it == f->offsets.end()) return Status::IoError;
    return preadAll(f->fd, pixels, kTileBytes, it->second + 4) ? Status::Ok : Status::IoError;
}

Status ThumbStore::append(int level, int64_t index, const uint16_t* pixels) {
    if (pixels == nullptr || index < 0 || index > UINT32_MAX) return Status::InvalidArgument;
    std::lock_guard<std::mutex> lock(mutex_);
    LevelFile* f = openLevel(level, true);
    if (f == nullptr) return Status::IoError;
    const uint32_t idx = static_cast<uint32_t>(index);
    if (f->offsets.count(idx) != 0) return Status::Ok;

    std::vector<uint8_t> record(kRecordBytes);
    std::memcpy(record.data(), &idx, sizeof(idx));
    std::memcpy(record.data() + 4, pixels, kTileBytes);
    if (!pwriteAll(f->fd, record.data(), record.size(), f->endOffset)) {
        (void)::ftruncate(f->fd, static_cast<off_t>(f->endOffset));  // drop the partial record
        return Status::IoError;
    }
    f->offsets.emplace(idx, f->endOffset);
    f->endOffset += kRecordBytes;
    return Status::Ok;
}

}  // namespace uv::thumb
