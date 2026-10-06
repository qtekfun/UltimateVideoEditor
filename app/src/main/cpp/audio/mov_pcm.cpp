#include "audio/mov_pcm.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <functional>

namespace uv::audio {

using core::Status;

namespace {

constexpr uint64_t kMaxMoovBytes = 256ull * 1024 * 1024;
constexpr size_t kReadFrames = 8192;

constexpr uint32_t fourcc(const char (&s)[5]) {
    return (static_cast<uint32_t>(static_cast<uint8_t>(s[0])) << 24) | (static_cast<uint32_t>(static_cast<uint8_t>(s[1])) << 16) |
           (static_cast<uint32_t>(static_cast<uint8_t>(s[2])) << 8) | static_cast<uint32_t>(static_cast<uint8_t>(s[3]));
}

uint16_t be16(const uint8_t* p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }
uint32_t be32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) | (static_cast<uint32_t>(p[2]) << 8) | p[3];
}
uint64_t be64(const uint8_t* p) { return (static_cast<uint64_t>(be32(p)) << 32) | be32(p + 4); }

// A bounds-checked view of bytes; every parser below stays inside it.
struct Span {
    const uint8_t* data = nullptr;
    size_t size = 0;
};

// Calls fn(type, payload) for each child box of `parent`; stops when fn returns true. Returns whether it did.
bool forEachBox(Span parent, const std::function<bool(uint32_t, Span)>& fn) {
    size_t pos = 0;
    while (parent.size - pos >= 8) {
        uint64_t size = be32(parent.data + pos);
        const uint32_t type = be32(parent.data + pos + 4);
        size_t header = 8;
        if (size == 1) {
            if (parent.size - pos < 16) return false;
            size = be64(parent.data + pos + 8);
            header = 16;
        } else if (size == 0) {
            size = parent.size - pos;
        }
        if (size < header || size > parent.size - pos) return false;
        if (fn(type, Span{parent.data + pos + header, static_cast<size_t>(size) - header})) return true;
        pos += static_cast<size_t>(size);
    }
    return false;
}

bool findChild(Span parent, uint32_t type, Span* out) {
    return forEachBox(parent, [&](uint32_t t, Span payload) {
        if (t != type) return false;
        *out = payload;
        return true;
    });
}

// The sample entry of a sound track. False: not an uncompressed layout this reader handles.
bool parseSampleEntry(Span stsd, MovPcmFormat* fmt) {
    // version/flags (4), entry count (4), then the first entry: size (4), format (4), reserved (6), data ref (2), sound description.
    if (stsd.size < 8 + 16 + 16 || be32(stsd.data + 4) < 1) return false;
    const uint8_t* e = stsd.data + 8;
    const size_t avail = stsd.size - 8;
    const uint32_t entrySize = be32(e);
    if (entrySize < 16 + 16 || entrySize > avail) return false;
    const uint32_t format = be32(e + 4);
    const uint16_t version = be16(e + 16);
    if (version == 2) {
        if (entrySize < 72 || be32(e + 36) < 72) return false;
        double rate = 0;
        const uint64_t bits = be64(e + 40);
        std::memcpy(&rate, &bits, sizeof rate);
        fmt->sampleRate = static_cast<int32_t>(std::lround(rate));
        fmt->channels = static_cast<int32_t>(be32(e + 48));
        fmt->bitsPerSample = static_cast<int32_t>(be32(e + 56));
        const uint32_t flags = be32(e + 60);
        const uint32_t bytesPerPacket = be32(e + 64);
        const uint32_t framesPerPacket = be32(e + 68);
        if (format != fourcc("lpcm")) return false;
        if ((flags & 32u) != 0) return false;  // non-interleaved planes
        fmt->isFloat = (flags & 1u) != 0;
        fmt->bigEndian = (flags & 2u) != 0;
        fmt->isSigned = (flags & 4u) != 0 || fmt->isFloat;
        if (fmt->bitsPerSample <= 0 || fmt->bitsPerSample % 8 != 0 || fmt->channels <= 0) return false;
        // Only tightly packed samples: a 24-bit sample held in four bytes (aligned flags) is not handled.
        if (framesPerPacket == 0 || bytesPerPacket != static_cast<uint32_t>(fmt->bytesPerFrame()) * framesPerPacket) return false;
    } else {
        if (entrySize < 36) return false;
        fmt->channels = be16(e + 24);
        fmt->bitsPerSample = be16(e + 26);
        fmt->sampleRate = static_cast<int32_t>(be32(e + 32) >> 16);
        switch (format) {
            case fourcc("sowt"): fmt->bigEndian = false; fmt->isSigned = true; break;
            case fourcc("twos"): fmt->bigEndian = true; fmt->isSigned = true; break;
            case fourcc("raw "): fmt->bigEndian = true; fmt->isSigned = false; fmt->bitsPerSample = 8; break;
            case fourcc("in24"): fmt->bigEndian = true; fmt->isSigned = true; fmt->bitsPerSample = 24; break;
            case fourcc("in32"): fmt->bigEndian = true; fmt->isSigned = true; fmt->bitsPerSample = 32; break;
            case fourcc("fl32"): fmt->bigEndian = true; fmt->isFloat = true; fmt->bitsPerSample = 32; break;
            case fourcc("fl64"): fmt->bigEndian = true; fmt->isFloat = true; fmt->bitsPerSample = 64; break;
            default: return false;
        }
        if (fmt->bitsPerSample != 8 && fmt->bitsPerSample != 16 && fmt->bitsPerSample != 24 && fmt->bitsPerSample != 32 &&
            fmt->bitsPerSample != 64) {
            return false;
        }
    }
    if (fmt->isFloat && fmt->bitsPerSample != 32 && fmt->bitsPerSample != 64) return false;
    if (!fmt->isFloat && fmt->bitsPerSample == 64) return false;
    return fmt->channels > 0 && fmt->channels <= 64 && fmt->sampleRate >= 8000 && fmt->sampleRate <= 768000;
}

// The chunk list of one track from stsz/stsc/stco|co64. `fileSize` clips damaged tables.
Status buildChunks(Span stbl, uint64_t fileSize, MovPcmTrack* track) {
    Span stsz, stsc, stco, co64;
    if (!findChild(stbl, fourcc("stsz"), &stsz) || !findChild(stbl, fourcc("stsc"), &stsc)) return Status::IoError;
    const bool wide = !findChild(stbl, fourcc("stco"), &stco) && findChild(stbl, fourcc("co64"), &co64);
    const Span offsets = wide ? co64 : stco;
    if (offsets.data == nullptr) return Status::IoError;
    if (stsz.size < 12 || stsc.size < 8 || offsets.size < 8) return Status::IoError;
    const uint32_t fixedSize = be32(stsz.data + 4);
    const uint32_t sampleCount = be32(stsz.data + 8);
    if (fixedSize == 0 && (stsz.size - 12) / 4 < sampleCount) return Status::IoError;
    const uint32_t runCount = be32(stsc.data + 4);
    if ((stsc.size - 8) / 12 < runCount || runCount == 0) return Status::IoError;
    const uint32_t chunkCount = be32(offsets.data + 4);
    const size_t entry = wide ? 8 : 4;
    if ((offsets.size - 8) / entry < chunkCount) return Status::IoError;

    const int32_t bpf = track->format.bytesPerFrame();
    uint64_t sample = 0;  // index of the first sample of the current chunk
    uint64_t frame = 0;
    for (uint32_t chunk = 1; chunk <= chunkCount; ++chunk) {
        // The run that applies to this chunk: the last one starting at or before it.
        uint32_t run = 0;
        while (run + 1 < runCount && be32(stsc.data + 8 + (run + 1) * 12) <= chunk) ++run;
        const uint32_t perChunk = be32(stsc.data + 8 + run * 12 + 4);
        if (sample + perChunk > sampleCount) break;
        uint64_t bytes = 0;
        if (fixedSize != 0) {
            bytes = static_cast<uint64_t>(fixedSize) * perChunk;
        } else {
            for (uint32_t i = 0; i < perChunk; ++i) bytes += be32(stsz.data + 12 + (sample + i) * 4);
        }
        sample += perChunk;
        const uint8_t* p = offsets.data + 8 + static_cast<size_t>(chunk - 1) * entry;
        const uint64_t offset = wide ? be64(p) : be32(p);
        if (offset >= fileSize) continue;
        bytes = std::min(bytes, fileSize - offset);  // a file cut short keeps what it has
        const uint64_t frames = bytes / static_cast<uint64_t>(bpf);
        if (frames == 0) continue;
        track->chunks.push_back(MovPcmChunk{offset, frame, frames});
        frame += frames;
    }
    track->totalFrames = frame;
    return frame > 0 ? Status::Ok : Status::IoError;
}

class FdReader final : public RandomReader {
public:
    static std::unique_ptr<FdReader> open(int fd, Status* status) {
        const int dupFd = ::fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (dupFd < 0) {
            *status = Status::IoError;
            return nullptr;
        }
        struct stat st {};
        if (::fstat(dupFd, &st) != 0 || st.st_size <= 0) {
            ::close(dupFd);
            *status = Status::IoError;
            return nullptr;
        }
        return std::unique_ptr<FdReader>(new FdReader(dupFd, static_cast<uint64_t>(st.st_size)));
    }
    ~FdReader() override { ::close(fd_); }
    uint64_t size() const override { return size_; }
    bool readAt(uint64_t offset, void* dst, size_t n) override {
        auto* out = static_cast<uint8_t*>(dst);
        while (n > 0) {
            const ssize_t got = ::pread(fd_, out, n, static_cast<off_t>(offset));
            if (got < 0 && errno == EINTR) continue;
            if (got <= 0) return false;
            out += got;
            offset += static_cast<uint64_t>(got);
            n -= static_cast<size_t>(got);
        }
        return true;
    }

private:
    FdReader(int fd, uint64_t size) : fd_(fd), size_(size) {}
    int fd_;
    uint64_t size_;
};

class MovPcmDecoder final : public PcmDecoder {
public:
    MovPcmDecoder(std::unique_ptr<RandomReader> reader, MovPcmTrack track) : reader_(std::move(reader)), track_(std::move(track)) {}

    int32_t sampleRate() const override { return track_.format.sampleRate; }

    Status seekToMicros(int64_t micros) override {
        const int64_t m = std::max<int64_t>(micros, 0);
        const uint64_t frame = static_cast<uint64_t>((static_cast<__int128>(m) * track_.format.sampleRate + 500000) / 1000000);
        cursor_ = std::min(frame, track_.totalFrames);
        return Status::Ok;
    }

    PcmReadResult read(float* dst, int32_t maxFrames) override {
        PcmReadResult r;
        const int32_t bpf = track_.format.bytesPerFrame();
        while (r.frames < maxFrames && cursor_ < track_.totalFrames) {
            const MovPcmChunk& chunk = chunkAt(cursor_);
            const uint64_t inChunk = chunk.firstFrame + chunk.frames - cursor_;
            const size_t n = static_cast<size_t>(std::min<uint64_t>({inChunk, static_cast<uint64_t>(maxFrames - r.frames), kReadFrames}));
            scratch_.resize(n * static_cast<size_t>(bpf));
            if (!reader_->readAt(chunk.offset + (cursor_ - chunk.firstFrame) * static_cast<uint64_t>(bpf), scratch_.data(), scratch_.size())) {
                r.status = Status::IoError;
                return r;
            }
            convertMovPcm(scratch_.data(), n, track_.format, dst + static_cast<size_t>(r.frames) * 2);
            r.frames += static_cast<int32_t>(n);
            cursor_ += n;
        }
        r.eof = cursor_ >= track_.totalFrames;
        return r;
    }

private:
    const MovPcmChunk& chunkAt(uint64_t frame) {
        // Reads are sequential, so the last chunk is almost always still the right one.
        if (hint_ >= track_.chunks.size() || frame < track_.chunks[hint_].firstFrame ||
            frame >= track_.chunks[hint_].firstFrame + track_.chunks[hint_].frames) {
            const auto it = std::upper_bound(track_.chunks.begin(), track_.chunks.end(), frame,
                                             [](uint64_t f, const MovPcmChunk& c) { return f < c.firstFrame; });
            hint_ = static_cast<size_t>(std::distance(track_.chunks.begin(), it)) - 1;
        }
        return track_.chunks[hint_];
    }

    std::unique_ptr<RandomReader> reader_;
    MovPcmTrack track_;
    uint64_t cursor_ = 0;
    size_t hint_ = 0;
    std::vector<uint8_t> scratch_;
};

}  // namespace

Status parseMovPcm(RandomReader& reader, MovPcmTrack* out) {
    if (out == nullptr) return Status::InvalidArgument;
    const uint64_t fileSize = reader.size();
    // Top-level boxes: skip over mdat by its size until moov.
    uint64_t pos = 0;
    uint64_t moovAt = 0, moovSize = 0;
    while (pos + 8 <= fileSize) {
        uint8_t h[16];
        if (!reader.readAt(pos, h, 8)) return Status::IoError;
        uint64_t size = be32(h);
        const uint32_t type = be32(h + 4);
        uint64_t header = 8;
        if (size == 1) {
            if (pos + 16 > fileSize || !reader.readAt(pos + 8, h + 8, 8)) return Status::IoError;
            size = be64(h + 8);
            header = 16;
        } else if (size == 0) {
            size = fileSize - pos;
        }
        if (size < header) return Status::UnsupportedFormat;
        if (type == fourcc("moov")) {
            moovAt = pos + header;
            moovSize = std::min(size, fileSize - pos) - header;
            break;
        }
        pos += size;
    }
    if (moovSize == 0) return Status::UnsupportedFormat;
    if (moovSize > kMaxMoovBytes) return Status::UnsupportedFormat;
    std::vector<uint8_t> moov(static_cast<size_t>(moovSize));
    if (!reader.readAt(moovAt, moov.data(), moov.size())) return Status::IoError;

    Status result = Status::UnsupportedFormat;
    forEachBox(Span{moov.data(), moov.size()}, [&](uint32_t type, Span trak) {
        if (type != fourcc("trak")) return false;
        Span mdia, hdlr, minf, stbl, stsd;
        if (!findChild(trak, fourcc("mdia"), &mdia) || !findChild(mdia, fourcc("hdlr"), &hdlr) || hdlr.size < 12 ||
            be32(hdlr.data + 8) != fourcc("soun") || !findChild(mdia, fourcc("minf"), &minf) || !findChild(minf, fourcc("stbl"), &stbl) ||
            !findChild(stbl, fourcc("stsd"), &stsd)) {
            return false;
        }
        MovPcmTrack track;
        if (!parseSampleEntry(stsd, &track.format)) return false;
        const Status built = buildChunks(stbl, fileSize, &track);
        if (built != Status::Ok) {
            result = built;
            return false;
        }
        *out = std::move(track);
        result = Status::Ok;
        return true;
    });
    return result;
}

void convertMovPcm(const uint8_t* src, size_t frames, const MovPcmFormat& fmt, float* dst) {
    const int32_t bytes = (fmt.bitsPerSample + 7) / 8;
    const int32_t channels = fmt.channels;
    auto sampleAt = [&](const uint8_t* p) -> float {
        if (fmt.isFloat) {
            if (bytes == 4) {
                uint32_t v = fmt.bigEndian ? be32(p) : static_cast<uint32_t>(p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24));
                float f;
                std::memcpy(&f, &v, sizeof f);
                return f;
            }
            uint64_t v = 0;
            for (int i = 0; i < 8; ++i) v = fmt.bigEndian ? (v << 8) | p[i] : v | (static_cast<uint64_t>(p[i]) << (8 * i));
            double d;
            std::memcpy(&d, &v, sizeof d);
            return static_cast<float>(d);
        }
        if (bytes == 1) return fmt.isSigned ? static_cast<int8_t>(p[0]) / 128.0f : (static_cast<int>(p[0]) - 128) / 128.0f;
        uint32_t v = 0;
        for (int i = 0; i < bytes; ++i) v = fmt.bigEndian ? (v << 8) | p[i] : v | (static_cast<uint32_t>(p[i]) << (8 * i));
        // Sign-extend from the sample's own width, then scale by its full-scale value.
        const int shift = 32 - bytes * 8;
        const int32_t s = static_cast<int32_t>(v << shift) >> shift;
        const uint32_t unsignedBias = fmt.isSigned ? 0u : (1u << (bytes * 8 - 1));
        return static_cast<float>(static_cast<double>(static_cast<int64_t>(s) - (fmt.isSigned ? 0 : static_cast<int64_t>(unsignedBias))) /
                                  static_cast<double>(1ull << (bytes * 8 - 1)));
    };
    for (size_t i = 0; i < frames; ++i) {
        const uint8_t* frame = src + i * static_cast<size_t>(bytes) * static_cast<size_t>(channels);
        const float left = sampleAt(frame);
        const float right = channels > 1 ? sampleAt(frame + bytes) : left;
        dst[i * 2] = left;
        dst[i * 2 + 1] = right;
    }
}

std::unique_ptr<PcmDecoder> openMovPcmDecoder(std::unique_ptr<RandomReader> reader, Status* status) {
    MovPcmTrack track;
    *status = parseMovPcm(*reader, &track);
    if (*status != Status::Ok) return nullptr;
    return std::make_unique<MovPcmDecoder>(std::move(reader), std::move(track));
}

std::unique_ptr<PcmDecoder> openMovPcmDecoderFd(int fd, Status* status) {
    std::unique_ptr<FdReader> reader = FdReader::open(fd, status);
    if (!reader) return nullptr;
    return openMovPcmDecoder(std::move(reader), status);
}

}  // namespace uv::audio
