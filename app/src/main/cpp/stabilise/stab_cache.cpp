#include "stabilise/stab_cache.h"

#include <cstdio>
#include <cstring>

namespace uv::stab {

using core::Status;

namespace {

constexpr char kMagic[4] = {'U', 'V', 'S', 'T'};
constexpr int64_t kMaxSamples = 1LL << 22;

void put(std::vector<uint8_t>* out, const void* value, size_t size) {
    const auto* bytes = static_cast<const uint8_t*>(value);
    out->insert(out->end(), bytes, bytes + size);
}
void putU32(std::vector<uint8_t>* out, uint32_t v) { put(out, &v, 4); }
void putI32(std::vector<uint8_t>* out, int32_t v) { put(out, &v, 4); }
void putI64(std::vector<uint8_t>* out, int64_t v) { put(out, &v, 8); }
void putF32(std::vector<uint8_t>* out, float v) { put(out, &v, 4); }

struct Reader {
    const uint8_t* data;
    size_t size;
    size_t at = 0;
    template <typename T>
    T read() {
        T v;
        std::memcpy(&v, data + at, sizeof(T));
        at += sizeof(T);
        return v;
    }
};

}  // namespace

uint32_t crc32(const uint8_t* data, size_t size) {
    static const auto table = [] {
        struct T {
            uint32_t v[256];
        } t{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1u) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            t.v[i] = c;
        }
        return t;
    }();
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < size; ++i) c = table.v[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

Status writeCache(const std::string& path, const CacheHeader& header, const std::vector<MotionSample>& samples) {
    std::vector<uint8_t> buf;
    buf.reserve(kCacheHeaderBytes + samples.size() * kCacheSampleBytes + 4);
    put(&buf, kMagic, 4);
    putU32(&buf, kCacheFormatVersion);
    putU32(&buf, header.analysisVersion);
    putF32(&buf, header.aspect);
    putI32(&buf, header.analysisWidth);
    putI32(&buf, header.analysisHeight);
    putI64(&buf, header.rangeStartUs);
    putI64(&buf, header.rangeEndUs);
    putI64(&buf, static_cast<int64_t>(samples.size()));
    for (const MotionSample& s : samples) {
        putI64(&buf, s.ptsUs);
        putF32(&buf, static_cast<float>(s.motion.tx));
        putF32(&buf, static_cast<float>(s.motion.ty));
        putF32(&buf, static_cast<float>(s.motion.theta));
        putF32(&buf, static_cast<float>(s.motion.logScale));
        putF32(&buf, s.motion.quality);
    }
    putU32(&buf, crc32(buf.data(), buf.size()));

    const std::string temp = path + ".tmp";
    std::FILE* f = std::fopen(temp.c_str(), "wb");
    if (f == nullptr) return Status::IoError;
    const bool wrote = std::fwrite(buf.data(), 1, buf.size(), f) == buf.size();
    const bool closed = std::fclose(f) == 0;
    if (!wrote || !closed) {
        std::remove(temp.c_str());
        return Status::IoError;
    }
    if (std::rename(temp.c_str(), path.c_str()) != 0) {
        std::remove(temp.c_str());
        return Status::IoError;
    }
    return Status::Ok;
}

Status readCache(const std::string& path, CacheHeader* header, std::vector<MotionSample>* samples) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return Status::IoError;
    std::vector<uint8_t> buf;
    uint8_t chunk[8192];
    size_t n;
    while ((n = std::fread(chunk, 1, sizeof(chunk), f)) > 0) {
        buf.insert(buf.end(), chunk, chunk + n);
        if (buf.size() > kCacheHeaderBytes + static_cast<size_t>(kMaxSamples) * kCacheSampleBytes + 4) break;
    }
    std::fclose(f);
    if (buf.size() < kCacheHeaderBytes + 4 || std::memcmp(buf.data(), kMagic, 4) != 0) return Status::UnsupportedFormat;

    Reader r{buf.data(), buf.size(), 4};
    if (r.read<uint32_t>() != kCacheFormatVersion) return Status::UnsupportedFormat;
    CacheHeader h;
    h.analysisVersion = r.read<uint32_t>();
    h.aspect = r.read<float>();
    h.analysisWidth = r.read<int32_t>();
    h.analysisHeight = r.read<int32_t>();
    h.rangeStartUs = r.read<int64_t>();
    h.rangeEndUs = r.read<int64_t>();
    const int64_t count = r.read<int64_t>();
    if (count < 0 || count > kMaxSamples) return Status::UnsupportedFormat;
    if (buf.size() != kCacheHeaderBytes + static_cast<size_t>(count) * kCacheSampleBytes + 4) return Status::UnsupportedFormat;
    uint32_t stored;
    std::memcpy(&stored, buf.data() + buf.size() - 4, 4);
    if (stored != crc32(buf.data(), buf.size() - 4)) return Status::UnsupportedFormat;
    if (!(h.aspect > 0.1f && h.aspect < 20.0f)) return Status::UnsupportedFormat;

    std::vector<MotionSample> out(static_cast<size_t>(count));
    for (MotionSample& s : out) {
        s.ptsUs = r.read<int64_t>();
        s.motion.tx = r.read<float>();
        s.motion.ty = r.read<float>();
        s.motion.theta = r.read<float>();
        s.motion.logScale = r.read<float>();
        s.motion.quality = r.read<float>();
        s.motion.valid = true;
    }
    *header = h;
    *samples = std::move(out);
    return Status::Ok;
}

}  // namespace uv::stab
