#pragma once

#include <sys/stat.h>

#include <map>
#include <memory>
#include <mutex>
#include <utility>

namespace uv::core {

/** One lock per underlying file, shared by every reader of it in this process. */
using FileLock = std::shared_ptr<std::mutex>;

/**
 * The lock of the file behind [fd]: the same object for every descriptor of one file (dup()s and separate opens alike, by
 * device and inode), a different one for another file. It is released when the last reader lets go of it.
 *
 * Why: a media extractor reads its file through a duplicate of the descriptor in the extractor service, with lseek + read. All
 * duplicates of one open file description share one offset, and files a document provider serves through FUSE refuse the
 * re-open that would give each reader its own (EACCES; see openIndependent in fd_util.h). Two extractors reading such a file
 * at once (the exporter's video decoder and its audio decoder, a thumbnail worker) then see each other's offsets: the MP4
 * reader parses garbage ("MPEG4Extractor: buffer too small: 127132562 > 1035831") and the stream ends early. Extractor calls are
 * synchronous, so holding this lock around every call that reads the file keeps them from interleaving.
 */
inline FileLock fileLockFor(int fd) {
    static std::mutex registryMutex;
    static std::map<std::pair<dev_t, ino_t>, std::weak_ptr<std::mutex>> registry;
    struct stat st {};
    if (fd < 0 || ::fstat(fd, &st) != 0) return std::make_shared<std::mutex>();
    const auto key = std::make_pair(st.st_dev, st.st_ino);
    std::lock_guard<std::mutex> guard(registryMutex);
    if (auto it = registry.find(key); it != registry.end()) {
        if (auto alive = it->second.lock()) return alive;
    }
    auto fresh = std::make_shared<std::mutex>();
    registry[key] = fresh;
    return fresh;
}

}  // namespace uv::core
