#include "track/track_path.h"

#include <cstdio>
#include <cstring>

#include "stabilise/stab_cache.h"

namespace uv::track {

using core::Status;

namespace {

void put32(std::vector<uint8_t>& b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}
void put64(std::vector<uint8_t>& b, uint64_t v) {
    for (int i = 0; i < 8; ++i) b.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xFF));
}
void putF(std::vector<uint8_t>& b, float f) {
    uint32_t v;
    std::memcpy(&v, &f, 4);
    put32(b, v);
}
uint32_t get32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint64_t get64(const uint8_t* p) { return static_cast<uint64_t>(get32(p)) | (static_cast<uint64_t>(get32(p + 4)) << 32); }
float getF(const uint8_t* p) {
    const uint32_t v = get32(p);
    float f;
    std::memcpy(&f, &v, 4);
    return f;
}

}  // namespace

Status writeTrackCache(const std::string& path, const TrackHeader& header, const std::vector<TrackSample>& samples) {
    std::vector<uint8_t> b;
    b.reserve(kTrackHeaderBytes + samples.size() * kTrackSampleBytes + 4);
    const char magic[4] = {'U', 'V', 'T', 'K'};
    for (char c : magic) b.push_back(static_cast<uint8_t>(c));
    put32(b, kTrackFormatVersion);
    put32(b, header.analysisVersion);
    putF(b, header.aspect);
    put64(b, static_cast<uint64_t>(header.seedUs));
    put64(b, static_cast<uint64_t>(header.rangeStartUs));
    put64(b, static_cast<uint64_t>(header.rangeEndUs));
    put64(b, static_cast<uint64_t>(samples.size()));
    for (const TrackSample& s : samples) {
        put64(b, static_cast<uint64_t>(s.ptsUs));
        putF(b, s.cx);
        putF(b, s.cy);
        putF(b, s.w);
        putF(b, s.h);
        putF(b, s.rotation);
        putF(b, s.confidence);
        b.push_back(s.lost ? 1 : 0);
        b.push_back(0);
        b.push_back(0);
        b.push_back(0);
    }
    put32(b, stab::crc32(b.data(), b.size()));

    const std::string tmp = path + ".tmp";
    std::FILE* f = std::fopen(tmp.c_str(), "wb");
    if (f == nullptr) return Status::IoError;
    const bool wrote = std::fwrite(b.data(), 1, b.size(), f) == b.size();
    const bool closed = std::fclose(f) == 0;
    if (!wrote || !closed) {
        std::remove(tmp.c_str());
        return Status::IoError;
    }
    if (std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        return Status::IoError;
    }
    return Status::Ok;
}

Status readTrackCache(const std::string& path, TrackHeader* header, std::vector<TrackSample>* samples) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (f == nullptr) return Status::IoError;
    std::vector<uint8_t> b;
    uint8_t chunk[4096];
    size_t n;
    while ((n = std::fread(chunk, 1, sizeof(chunk), f)) > 0) b.insert(b.end(), chunk, chunk + n);
    std::fclose(f);
    if (b.size() < kTrackHeaderBytes + 4 || std::memcmp(b.data(), "UVTK", 4) != 0) return Status::UnsupportedFormat;
    if (get32(b.data() + 4) != kTrackFormatVersion) return Status::UnsupportedFormat;
    const uint64_t count = get64(b.data() + 40);
    if (count > (b.size() - kTrackHeaderBytes) / kTrackSampleBytes || b.size() != kTrackHeaderBytes + count * kTrackSampleBytes + 4) {
        return Status::UnsupportedFormat;
    }
    if (get32(b.data() + b.size() - 4) != stab::crc32(b.data(), b.size() - 4)) return Status::UnsupportedFormat;
    if (header != nullptr) {
        header->analysisVersion = get32(b.data() + 8);
        header->aspect = getF(b.data() + 12);
        header->seedUs = static_cast<int64_t>(get64(b.data() + 16));
        header->rangeStartUs = static_cast<int64_t>(get64(b.data() + 24));
        header->rangeEndUs = static_cast<int64_t>(get64(b.data() + 32));
    }
    if (samples != nullptr) {
        samples->clear();
        samples->reserve(static_cast<size_t>(count));
        for (uint64_t i = 0; i < count; ++i) {
            const uint8_t* p = b.data() + kTrackHeaderBytes + i * kTrackSampleBytes;
            TrackSample s;
            s.ptsUs = static_cast<int64_t>(get64(p));
            s.cx = getF(p + 8);
            s.cy = getF(p + 12);
            s.w = getF(p + 16);
            s.h = getF(p + 20);
            s.rotation = getF(p + 24);
            s.confidence = getF(p + 28);
            s.lost = p[32] != 0;
            samples->push_back(s);
        }
    }
    return Status::Ok;
}

}  // namespace uv::track
