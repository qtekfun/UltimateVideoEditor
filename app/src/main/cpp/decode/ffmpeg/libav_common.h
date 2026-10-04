#pragma once

// Shared libav plumbing for the software video and audio readers. Include only from .cpp files of the
// FFmpeg fallback: it pulls in libav headers, which exist only in builds made with `-Puveditor.ffmpeg=<dir>`
// (or against the system libav in the host tests).

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/display.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#include <sys/types.h>
#include <unistd.h>

#include <cstdint>
#include <memory>
#include <string>

namespace uv::decode::ffmpeg {

inline std::string avErrorString(int code) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(code, buf, sizeof(buf));
    return std::string(buf);
}

// A file descriptor read with pread(), so the demuxer never disturbs a shared file offset and several
// readers can use duplicates of the same descriptor. Owns the descriptor.
struct FdSource {
    int fd = -1;
    int64_t size = -1;
    int64_t pos = 0;
    ~FdSource() {
        if (fd >= 0) close(fd);
    }
};

inline int fdReadPacket(void* opaque, uint8_t* buf, int size) {
    auto* s = static_cast<FdSource*>(opaque);
    const ssize_t n = pread(s->fd, buf, static_cast<size_t>(size), static_cast<off_t>(s->pos));
    if (n < 0) return AVERROR(EIO);
    if (n == 0) return AVERROR_EOF;
    s->pos += n;
    return static_cast<int>(n);
}

inline int64_t fdSeek(void* opaque, int64_t offset, int whence) {
    auto* s = static_cast<FdSource*>(opaque);
    if (whence & AVSEEK_SIZE) return s->size;
    whence &= ~AVSEEK_FORCE;
    int64_t target = 0;
    switch (whence) {
        case SEEK_SET:
            target = offset;
            break;
        case SEEK_CUR:
            target = s->pos + offset;
            break;
        case SEEK_END:
            if (s->size < 0) return AVERROR(ENOSYS);
            target = s->size + offset;
            break;
        default:
            return AVERROR(EINVAL);
    }
    if (target < 0) return AVERROR(EINVAL);
    s->pos = target;
    return target;
}

// A demuxer opened on a descriptor.
struct FormatHandle {
    std::unique_ptr<FdSource> source;
    AVIOContext* io = nullptr;
    AVFormatContext* ctx = nullptr;

    FormatHandle() = default;
    FormatHandle(const FormatHandle&) = delete;
    FormatHandle& operator=(const FormatHandle&) = delete;
    ~FormatHandle() {
        if (ctx != nullptr) avformat_close_input(&ctx);  // does not free a custom AVIOContext
        if (io != nullptr) {
            av_freep(&io->buffer);
            avio_context_free(&io);
        }
    }
};

// Takes ownership of `fd` (closed with the handle, also when this fails). Returns 0 or an AVERROR.
inline int openFormat(int fd, FormatHandle* h, std::string* error) {
    h->source = std::make_unique<FdSource>();
    h->source->fd = fd;
    const off_t length = lseek(fd, 0, SEEK_END);
    h->source->size = length > 0 ? static_cast<int64_t>(length) : -1;
    if (length <= 0) {
        *error = "cannot determine the media size";
        return AVERROR(EIO);
    }

    constexpr int kBufferSize = 64 * 1024;
    auto* buffer = static_cast<uint8_t*>(av_malloc(kBufferSize));
    if (buffer == nullptr) return AVERROR(ENOMEM);
    h->io = avio_alloc_context(buffer, kBufferSize, 0, h->source.get(), fdReadPacket, nullptr, fdSeek);
    if (h->io == nullptr) {
        av_free(buffer);
        return AVERROR(ENOMEM);
    }
    h->ctx = avformat_alloc_context();
    if (h->ctx == nullptr) return AVERROR(ENOMEM);
    h->ctx->pb = h->io;
    h->ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

    int r = avformat_open_input(&h->ctx, "", nullptr, nullptr);  // frees ctx and nulls it on failure
    if (r < 0) {
        *error = "avformat_open_input: " + avErrorString(r);
        return r;
    }
    r = avformat_find_stream_info(h->ctx, nullptr);
    if (r < 0) {
        *error = "avformat_find_stream_info: " + avErrorString(r);
        return r;
    }
    return 0;
}

}  // namespace uv::decode::ffmpeg
