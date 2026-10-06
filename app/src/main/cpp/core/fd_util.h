#pragma once

#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#ifdef __ANDROID__
#include <android/log.h>
#endif

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
#ifdef __ANDROID__
    // Files a document provider serves through FUSE refuse this re-open (EACCES): the descriptors then share one offset, so
    // media are read with pread (core/fd_source.h) and never rely on it.
    __android_log_print(ANDROID_LOG_INFO, "uv_fd", "re-open of descriptor %d refused (errno %d), using dup()", fd, errno);
#endif
    return ::dup(fd);
}

}  // namespace uv::core
