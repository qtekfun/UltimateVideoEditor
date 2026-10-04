#pragma once

#include <fcntl.h>
#include <unistd.h>

#include <cstdio>

namespace uv::core {

/**
 * A descriptor for the same file that does not share a read offset with [fd].
 *
 * dup() returns a second number for the same open file description, so both share one file offset. Media
 * extractors on different threads (video decode, audio decode, thumbnails) that read through such duplicates
 * can disturb each other's position: an exported clip lost its tail at a random frame ("end of stream" after
 * 177 of 300 samples) whenever the audio mixer read the same file at the same time. Opening
 * /proc/self/fd/<fd> creates a new open file description with its own offset. Falls back to dup() when that is
 * not possible. The caller owns the returned descriptor; returns -1 on failure.
 */
inline int openIndependent(int fd) {
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    const int fresh = ::open(path, O_RDONLY | O_CLOEXEC);
    if (fresh >= 0) return fresh;
    return ::dup(fd);
}

}  // namespace uv::core
