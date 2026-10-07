#include "encode/mp4_writer.h"

#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstring>

namespace uv::encode {
namespace {

using Bytes = std::vector<uint8_t>;

void u8(Bytes& b, uint32_t v) { b.push_back(static_cast<uint8_t>(v)); }
void u16(Bytes& b, uint32_t v) { u8(b, v >> 8); u8(b, v); }
void u32(Bytes& b, uint32_t v) { u16(b, v >> 16); u16(b, v); }
void u64(Bytes& b, uint64_t v) { u32(b, static_cast<uint32_t>(v >> 32)); u32(b, static_cast<uint32_t>(v)); }
void tag(Bytes& b, const char* t) { b.insert(b.end(), t, t + 4); }
void append(Bytes& b, const Bytes& o) { b.insert(b.end(), o.begin(), o.end()); }

// A box: size, type, payload.
Bytes box(const char* type, const Bytes& payload) {
    Bytes b;
    u32(b, static_cast<uint32_t>(8 + payload.size()));
    tag(b, type);
    append(b, payload);
    return b;
}
Bytes fullBox(const char* type, uint32_t versionFlags, const Bytes& payload) {
    Bytes p;
    u32(p, versionFlags);
    append(p, payload);
    return box(type, p);
}

constexpr uint32_t kMovieTimescale = 1000;

struct VideoTime {
    uint32_t timescale;
    uint32_t frameDuration;
};
VideoTime videoTime(Fps fps) {
    const uint32_t k = static_cast<uint32_t>((10000 + fps.num - 1) / fps.num);
    return {static_cast<uint32_t>(fps.num) * k, static_cast<uint32_t>(fps.den) * k};
}

void matrixFor(Bytes& b, int rotation, int32_t w, int32_t h) {
    const int32_t one = 0x10000;
    int32_t a = one, bb = 0, c = 0, d = one;
    int32_t x = 0, y = 0;
    switch (rotation) {
        case 90: a = 0; bb = one; c = -one; d = 0; x = h << 16; break;
        case 180: a = -one; d = -one; x = w << 16; y = h << 16; break;
        case 270: a = 0; bb = -one; c = one; d = 0; y = w << 16; break;
        default: break;
    }
    for (int32_t v : {a, bb, 0, c, d, 0, x, y}) u32(b, static_cast<uint32_t>(v));
    u32(b, 0x40000000);
}

Bytes esds(const Mp4Writer::AudioConfig& a) {
    Bytes dec;
    u8(dec, 0x40);          // MPEG-4 audio
    u8(dec, 0x15);          // audio stream
    u8(dec, 0); u16(dec, 0);  // buffer size
    u32(dec, static_cast<uint32_t>(a.bitrate));
    u32(dec, static_cast<uint32_t>(a.bitrate));
    u8(dec, 5);
    u8(dec, static_cast<uint32_t>(a.audioSpecificConfig.size()));
    append(dec, a.audioSpecificConfig);
    Bytes es;
    u16(es, 0);  // ES_ID
    u8(es, 0);
    u8(es, 4);
    u8(es, static_cast<uint32_t>(dec.size()));
    append(es, dec);
    u8(es, 6);
    u8(es, 1);
    u8(es, 2);
    Bytes full;
    u8(full, 3);
    u8(full, static_cast<uint32_t>(es.size()));
    append(full, es);
    return fullBox("esds", 0, full);
}

uint32_t crc32(const uint8_t* d, size_t n) {
    static const std::vector<uint32_t> table = [] {
        std::vector<uint32_t> t(256);
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            t[i] = c;
        }
        return t;
    }();
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; ++i) c = table[(c ^ d[i]) & 0xFF] ^ (c >> 8);
    return ~c;
}

}  // namespace

Mp4Writer::Mp4Writer(int fd, VideoConfig video, const AudioConfig* audio)
    : fd_(fd), vcfg_(std::move(video)), hasAudio_(audio != nullptr) {
    if (audio != nullptr) acfg_ = *audio;
    buffer_.reserve(1 << 20);
    Bytes head;
    u32(head, 28);
    tag(head, "ftyp");
    tag(head, "isom");
    u32(head, 512);
    tag(head, "isom");
    tag(head, "iso2");
    tag(head, "mp41");
    u32(head, 1);  // mdat with a 64-bit size
    tag(head, "mdat");
    mdatSizeAt_ = head.size();
    u64(head, 0);  // patched in finish()
    put(head.data(), head.size());
    mdatStart_ = pos_;
}

bool Mp4Writer::flush() {
    size_t done = 0;
    while (done < buffer_.size()) {
        const ssize_t n = ::pwrite(fd_, buffer_.data() + done, buffer_.size() - done, static_cast<off_t>(flushed_ + done));
        if (n < 0) {
            if (errno == EINTR) continue;
            error_ = std::string("writing the output file failed: ") + std::strerror(errno);
            return false;
        }
        done += static_cast<size_t>(n);
    }
    flushed_ += buffer_.size();
    buffer_.clear();
    return true;
}

bool Mp4Writer::put(const uint8_t* data, size_t size) {
    if (!error_.empty()) return false;
    pos_ += size;
    if (buffer_.size() + size > (4u << 20) || size > (1u << 20)) {
        if (!flush()) return false;
        if (size > (1u << 20)) {  // large sample: write directly
            size_t done = 0;
            while (done < size) {
                const ssize_t n = ::pwrite(fd_, data + done, size - done, static_cast<off_t>(flushed_ + done));
                if (n < 0) {
                    if (errno == EINTR) continue;
                    error_ = std::string("writing the output file failed: ") + std::strerror(errno);
                    return false;
                }
                done += static_cast<size_t>(n);
            }
            flushed_ += size;
            return true;
        }
    }
    buffer_.insert(buffer_.end(), data, data + size);
    return true;
}

bool Mp4Writer::noteChunk(bool audio, uint8_t entry, size_t size) {
    const uint32_t index = static_cast<uint32_t>(audio ? audio_.size() : video_.size());
    if (chunks_.empty() || chunks_.back().audio != audio || chunks_.back().entry != entry) {
        chunks_.push_back({audio, entry, pos_ - size, index - 1, 0});
    }
    ++chunks_.back().count;
    return true;
}

bool Mp4Writer::addVideoSample(const uint8_t* data, size_t size, int entry, int64_t presFrame, bool sync) {
    if (finished_ || entry < 0 || static_cast<size_t>(entry) >= vcfg_.entries.size()) {
        error_ = "invalid video sample";
        return false;
    }
    if (!put(data, size)) return false;
    video_.push_back({static_cast<uint32_t>(size), presFrame, sync, static_cast<uint8_t>(entry), crc32(data, size)});
    return noteChunk(false, static_cast<uint8_t>(entry), size);
}

bool Mp4Writer::addAudioSample(const uint8_t* data, size_t size) {
    if (finished_ || !hasAudio_) {
        error_ = "invalid audio sample";
        return false;
    }
    if (!put(data, size)) return false;
    audio_.push_back({static_cast<uint32_t>(size), 0, true, 0, crc32(data, size)});
    return noteChunk(true, 0, size);
}

std::vector<uint8_t> Mp4Writer::buildMoov() const {
    const VideoTime vt = videoTime(vcfg_.fps);
    const int64_t n = static_cast<int64_t>(video_.size());
    // Decode index i shows frame pres: dts = i, cts = pres + shift. shift = largest lag of a frame behind its decode slot.
    int64_t shift = 0;
    for (int64_t i = 0; i < n; ++i) shift = std::max<int64_t>(shift, i - video_[static_cast<size_t>(i)].pres);
    const int64_t mediaDur = n * vt.frameDuration;
    const int64_t videoMs = (mediaDur * kMovieTimescale + vt.timescale / 2) / vt.timescale;
    const int64_t audioDur = static_cast<int64_t>(audio_.size()) * 1024;
    const int64_t audioMs = hasAudio_ ? (audioDur * kMovieTimescale + acfg_.sampleRate / 2) / acfg_.sampleRate : 0;
    const int64_t movieMs = std::max(videoMs, audioMs);

    auto chunkTables = [&](bool audio, Bytes* stsc, Bytes* stco) {
        Bytes runs;
        uint32_t runCount = 0, index = 0;
        uint32_t lastCount = 0;
        uint8_t lastEntry = 255;
        Bytes offsets;
        for (const Chunk& c : chunks_) {
            if (c.audio != audio) continue;
            ++index;
            u64(offsets, c.offset);
            if (c.count != lastCount || c.entry != lastEntry) {
                u32(runs, index);
                u32(runs, c.count);
                u32(runs, static_cast<uint32_t>(c.entry) + 1);
                ++runCount;
                lastCount = c.count;
                lastEntry = c.entry;
            }
        }
        Bytes a;
        u32(a, runCount);
        append(a, runs);
        *stsc = fullBox("stsc", 0, a);
        Bytes b;
        u32(b, index);
        append(b, offsets);
        *stco = fullBox("co64", 0, b);
    };

    // Video track.
    Bytes vtrak;
    {
        Bytes stsd;
        u32(stsd, static_cast<uint32_t>(vcfg_.entries.size()));
        for (const VideoEntry& e : vcfg_.entries) {
            Bytes se;
            for (int i = 0; i < 6; ++i) u8(se, 0);
            u16(se, 1);
            for (int i = 0; i < 16; ++i) u8(se, 0);
            u16(se, static_cast<uint32_t>(vcfg_.width));
            u16(se, static_cast<uint32_t>(vcfg_.height));
            u32(se, 0x480000);
            u32(se, 0x480000);
            u32(se, 0);
            u16(se, 1);
            for (int i = 0; i < 32; ++i) u8(se, 0);
            u16(se, 0x18);
            u16(se, 0xFFFF);
            append(se, box("hvcC", e.hvcc));
            if (!e.colr.empty()) append(se, box("colr", e.colr));
            append(stsd, box("hvc1", se));
        }
        Bytes stts;
        u32(stts, 1);
        u32(stts, static_cast<uint32_t>(n));
        u32(stts, vt.frameDuration);
        Bytes ctts;
        {
            Bytes runs;
            uint32_t count = 0, runLen = 0;
            int64_t last = -1;
            for (int64_t i = 0; i < n; ++i) {
                const int64_t off = (video_[static_cast<size_t>(i)].pres - i + shift) * vt.frameDuration;
                if (runLen > 0 && off == last) { ++runLen; continue; }
                if (runLen > 0) { u32(runs, runLen); u32(runs, static_cast<uint32_t>(last)); ++count; }
                runLen = 1;
                last = off;
            }
            if (runLen > 0) { u32(runs, runLen); u32(runs, static_cast<uint32_t>(last)); ++count; }
            Bytes p;
            u32(p, count);
            append(p, runs);
            ctts = fullBox("ctts", 0, p);
        }
        Bytes stss;
        {
            Bytes ids;
            uint32_t count = 0;
            for (int64_t i = 0; i < n; ++i) {
                if (video_[static_cast<size_t>(i)].sync) { u32(ids, static_cast<uint32_t>(i + 1)); ++count; }
            }
            Bytes p;
            u32(p, count);
            append(p, ids);
            stss = fullBox("stss", 0, p);
        }
        Bytes stsz;
        {
            Bytes p;
            u32(p, 0);
            u32(p, static_cast<uint32_t>(n));
            for (const Sample& s : video_) u32(p, s.size);
            stsz = fullBox("stsz", 0, p);
        }
        Bytes stsc, co64;
        chunkTables(false, &stsc, &co64);
        Bytes stbl;
        append(stbl, fullBox("stsd", 0, stsd));
        append(stbl, stts.empty() ? stts : fullBox("stts", 0, stts));
        append(stbl, ctts);
        append(stbl, stss);
        append(stbl, stsc);
        append(stbl, stsz);
        append(stbl, co64);
        Bytes minf;
        append(minf, fullBox("vmhd", 1, Bytes(8, 0)));
        append(minf, box("dinf", fullBox("dref", 0, [] { Bytes b; u32(b, 1); append(b, fullBox("url ", 1, {})); return b; }())));
        append(minf, box("stbl", stbl));
        Bytes mdhd;
        u32(mdhd, 0); u32(mdhd, 0); u32(mdhd, vt.timescale); u32(mdhd, static_cast<uint32_t>(mediaDur));
        u16(mdhd, 0x55C4); u16(mdhd, 0);
        Bytes hdlr;
        u32(hdlr, 0); tag(hdlr, "vide");
        for (int i = 0; i < 12; ++i) u8(hdlr, 0);
        const char* name = "VideoHandler";
        hdlr.insert(hdlr.end(), name, name + 13);
        Bytes mdia;
        append(mdia, fullBox("mdhd", 0, mdhd));
        append(mdia, fullBox("hdlr", 0, hdlr));
        append(mdia, box("minf", minf));
        Bytes tkhd;
        u32(tkhd, 0); u32(tkhd, 0); u32(tkhd, 1); u32(tkhd, 0); u32(tkhd, static_cast<uint32_t>(videoMs));
        u32(tkhd, 0); u32(tkhd, 0);
        u16(tkhd, 0); u16(tkhd, 0); u16(tkhd, 0); u16(tkhd, 0);
        matrixFor(tkhd, vcfg_.rotationDegrees, vcfg_.width, vcfg_.height);
        u32(tkhd, static_cast<uint32_t>(vcfg_.width) << 16);
        u32(tkhd, static_cast<uint32_t>(vcfg_.height) << 16);
        Bytes elst;
        u32(elst, 1);
        u32(elst, static_cast<uint32_t>(videoMs));
        u32(elst, static_cast<uint32_t>(shift * vt.frameDuration));
        u16(elst, 1); u16(elst, 0);
        append(vtrak, fullBox("tkhd", 3, tkhd));
        append(vtrak, box("edts", fullBox("elst", 0, elst)));
        append(vtrak, box("mdia", mdia));
    }

    Bytes atrak;
    if (hasAudio_) {
        Bytes stsd;
        u32(stsd, 1);
        Bytes se;
        for (int i = 0; i < 6; ++i) u8(se, 0);
        u16(se, 1);
        u32(se, 0); u32(se, 0);
        u16(se, static_cast<uint32_t>(acfg_.channels));
        u16(se, 16);
        u16(se, 0); u16(se, 0);
        u32(se, static_cast<uint32_t>(acfg_.sampleRate) << 16);
        append(se, esds(acfg_));
        append(stsd, box("mp4a", se));
        Bytes stts;
        u32(stts, 1);
        u32(stts, static_cast<uint32_t>(audio_.size()));
        u32(stts, 1024);
        Bytes stsz;
        {
            Bytes p;
            u32(p, 0);
            u32(p, static_cast<uint32_t>(audio_.size()));
            for (const Sample& s : audio_) u32(p, s.size);
            stsz = fullBox("stsz", 0, p);
        }
        Bytes stsc, co64;
        chunkTables(true, &stsc, &co64);
        Bytes stbl;
        append(stbl, fullBox("stsd", 0, stsd));
        append(stbl, fullBox("stts", 0, stts));
        append(stbl, stsc);
        append(stbl, stsz);
        append(stbl, co64);
        Bytes minf;
        append(minf, fullBox("smhd", 0, Bytes(4, 0)));
        append(minf, box("dinf", fullBox("dref", 0, [] { Bytes b; u32(b, 1); append(b, fullBox("url ", 1, {})); return b; }())));
        append(minf, box("stbl", stbl));
        Bytes mdhd;
        u32(mdhd, 0); u32(mdhd, 0); u32(mdhd, static_cast<uint32_t>(acfg_.sampleRate)); u32(mdhd, static_cast<uint32_t>(audioDur));
        u16(mdhd, 0x55C4); u16(mdhd, 0);
        Bytes hdlr;
        u32(hdlr, 0); tag(hdlr, "soun");
        for (int i = 0; i < 12; ++i) u8(hdlr, 0);
        const char* name = "SoundHandler";
        hdlr.insert(hdlr.end(), name, name + 13);
        Bytes mdia;
        append(mdia, fullBox("mdhd", 0, mdhd));
        append(mdia, fullBox("hdlr", 0, hdlr));
        append(mdia, box("minf", minf));
        Bytes tkhd;
        u32(tkhd, 0); u32(tkhd, 0); u32(tkhd, 2); u32(tkhd, 0); u32(tkhd, static_cast<uint32_t>(audioMs));
        u32(tkhd, 0); u32(tkhd, 0);
        u16(tkhd, 0); u16(tkhd, 0); u16(tkhd, 0x100); u16(tkhd, 0);
        matrixFor(tkhd, 0, 0, 0);
        u32(tkhd, 0); u32(tkhd, 0);
        append(atrak, fullBox("tkhd", 3, tkhd));
        append(atrak, box("mdia", mdia));
    }

    Bytes mvhd;
    u32(mvhd, 0); u32(mvhd, 0); u32(mvhd, kMovieTimescale); u32(mvhd, static_cast<uint32_t>(movieMs));
    u32(mvhd, 0x10000); u16(mvhd, 0x100); u16(mvhd, 0);
    u32(mvhd, 0); u32(mvhd, 0);
    matrixFor(mvhd, 0, 0, 0);
    for (int i = 0; i < 6; ++i) u32(mvhd, 0);
    u32(mvhd, hasAudio_ ? 3 : 2);
    Bytes moov;
    append(moov, fullBox("mvhd", 0, mvhd));
    append(moov, box("trak", vtrak));
    if (hasAudio_) append(moov, box("trak", atrak));
    return box("moov", moov);
}

bool Mp4Writer::finish() {
    if (finished_) return error_.empty();
    finished_ = true;
    if (!error_.empty()) return false;
    const uint64_t mdatEnd = pos_;
    mdatEnd_ = mdatEnd;
    const Bytes moov = buildMoov();
    if (!put(moov.data(), moov.size()) || !flush()) return false;
    Bytes size;
    u64(size, mdatEnd - (mdatStart_ - 16));  // mdat box size: header (16) + payload
    if (::pwrite(fd_, size.data(), size.size(), static_cast<off_t>(mdatSizeAt_)) != static_cast<ssize_t>(size.size())) {
        error_ = "patching the mdat size failed";
        return false;
    }
    if (::ftruncate(fd_, static_cast<off_t>(pos_)) != 0) {
        error_ = "truncating the output failed";
        return false;
    }
    return true;
}

bool Mp4Writer::skipBytesForTest(uint64_t bytes) {
    if (!flush()) return false;
    pos_ += bytes;
    flushed_ += bytes;
    return true;
}

bool Mp4Writer::verify(int stride, std::string* why) const {
    auto bad = [&](std::string m) {
        if (why != nullptr) *why = std::move(m);
        return false;
    };
    if (!finished_ || !error_.empty()) return bad("the writer did not finish");
    uint8_t h[8];
    if (::pread(fd_, h, 8, static_cast<off_t>(mdatEnd_)) != 8 || std::memcmp(h + 4, "moov", 4) != 0) return bad("no moov box after the samples");
    const uint64_t moovSize = (uint64_t(h[0]) << 24) | (uint64_t(h[1]) << 16) | (uint64_t(h[2]) << 8) | h[3];
    if (mdatEnd_ + moovSize != pos_) return bad("the moov box does not end the file");
    struct stat st {};
    if (::fstat(fd_, &st) != 0 || static_cast<uint64_t>(st.st_size) != pos_) return bad("the file size is not what was written");
    uint32_t seen[2] = {0, 0};
    std::vector<uint8_t> buf;
    for (const Chunk& c : chunks_) {
        const std::vector<Sample>& list = c.audio ? audio_ : video_;
        uint64_t offset = c.offset;
        for (uint32_t k = 0; k < c.count; ++k) {
            const uint32_t index = seen[c.audio]++;
            const Sample& s = list[index];
            if (index % static_cast<uint32_t>(stride) == 0 || index + 1 == list.size()) {
                buf.resize(s.size);
                size_t done = 0;
                while (done < s.size) {
                    const ssize_t n = ::pread(fd_, buf.data() + done, s.size - done, static_cast<off_t>(offset + done));
                    if (n <= 0) return bad("a sample cannot be read back");
                    done += static_cast<size_t>(n);
                }
                if (crc32(buf.data(), buf.size()) != s.crc) return bad(std::string(c.audio ? "audio" : "video") + " sample " + std::to_string(index) + " differs from what was written");
            }
            offset += s.size;
        }
    }
    if (seen[0] != video_.size() || seen[1] != audio_.size()) return bad("the chunk tables do not cover every sample");
    return true;
}

}  // namespace uv::encode
