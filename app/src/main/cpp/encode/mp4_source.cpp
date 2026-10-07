#include "encode/mp4_source.h"

#include <algorithm>
#include <cstring>

namespace uv::encode {
namespace {

struct Span {
    const uint8_t* p = nullptr;
    size_t n = 0;
};

uint32_t be32(const uint8_t* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }
uint64_t be64(const uint8_t* p) { return (uint64_t(be32(p)) << 32) | be32(p + 4); }
uint16_t be16(const uint8_t* p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }

struct Box {
    char type[5] = {};
    Span payload;
};

// Boxes directly inside `in`.
std::vector<Box> children(Span in, size_t skip = 0) {
    std::vector<Box> out;
    size_t p = skip;
    while (p + 8 <= in.n) {
        uint64_t size = be32(in.p + p);
        size_t header = 8;
        Box b;
        std::memcpy(b.type, in.p + p + 4, 4);
        if (size == 1) {
            if (p + 16 > in.n) break;
            size = be64(in.p + p + 8);
            header = 16;
        } else if (size == 0) {
            size = in.n - p;
        }
        if (size < header || p + size > in.n) break;
        b.payload = {in.p + p + header, static_cast<size_t>(size) - header};
        out.push_back(b);
        p += static_cast<size_t>(size);
    }
    return out;
}

const Box* find(const std::vector<Box>& v, const char* type) {
    for (const Box& b : v) {
        if (std::memcmp(b.type, type, 4) == 0) return &b;
    }
    return nullptr;
}

}  // namespace

std::optional<Mp4VideoTrack> readMp4VideoTrack(const ByteSource& source, uint64_t fileSize, std::string* whyNot) {
    auto no = [&](const char* why) -> std::optional<Mp4VideoTrack> {
        if (whyNot != nullptr) *whyNot = why;
        return std::nullopt;
    };
    // Top level: find moov (and refuse fragmented files).
    std::vector<uint8_t> moov;
    uint64_t pos = 0;
    bool fragmented = false;
    while (pos + 8 <= fileSize) {
        uint8_t h[16];
        if (!source(pos, h, 8)) return no("cannot read the file");
        uint64_t size = be32(h);
        size_t header = 8;
        if (size == 1) {
            if (!source(pos + 8, h + 8, 8)) return no("cannot read the file");
            size = be64(h + 8);
            header = 16;
        } else if (size == 0) {
            size = fileSize - pos;
        }
        if (size < header || pos + size > fileSize) return no("damaged box structure");
        if (std::memcmp(h + 4, "moof", 4) == 0) fragmented = true;
        if (std::memcmp(h + 4, "moov", 4) == 0) {
            if (size - header > (512ull << 20)) return no("moov too large");
            moov.resize(static_cast<size_t>(size - header));
            if (!source(pos + header, moov.data(), moov.size())) return no("cannot read moov");
        }
        pos += size;
    }
    if (fragmented) return no("fragmented MP4");
    if (moov.empty()) return no("no moov box");

    const std::vector<Box> top = children({moov.data(), moov.size()});
    for (const Box& trak : top) {
        if (std::memcmp(trak.type, "trak", 4) != 0) continue;
        const std::vector<Box> t = children(trak.payload);
        const Box* mdia = find(t, "mdia");
        const Box* tkhd = find(t, "tkhd");
        if (mdia == nullptr || tkhd == nullptr) continue;
        const std::vector<Box> m = children(mdia->payload);
        const Box* hdlr = find(m, "hdlr");
        if (hdlr == nullptr || hdlr->payload.n < 12 || std::memcmp(hdlr->payload.p + 8, "vide", 4) != 0) continue;

        Mp4VideoTrack out;
        // Matrix.
        {
            const uint8_t* p = tkhd->payload.p;
            const size_t base = (p[0] == 1) ? 4 + 8 + 8 + 4 + 4 + 8 : 4 + 4 + 4 + 4 + 4 + 4;  // up to the 8 reserved bytes
            const size_t matrix = base + 8 + 2 + 2 + 2 + 2;
            if (tkhd->payload.n < matrix + 36) return no("short tkhd");
            const int32_t a = static_cast<int32_t>(be32(p + matrix)), b = static_cast<int32_t>(be32(p + matrix + 4));
            const int32_t c = static_cast<int32_t>(be32(p + matrix + 12)), d = static_cast<int32_t>(be32(p + matrix + 16));
            const int32_t one = 0x10000;
            if (a == one && b == 0 && c == 0 && d == one) out.rotationDegrees = 0;
            else if (a == 0 && b == one && c == -one && d == 0) out.rotationDegrees = 90;
            else if (a == -one && b == 0 && c == 0 && d == -one) out.rotationDegrees = 180;
            else if (a == 0 && b == -one && c == one && d == 0) out.rotationDegrees = 270;
            else out.rotationDegrees = -1;
            out.width = be32(p + matrix + 36) >> 16;
            out.height = be32(p + matrix + 40) >> 16;
        }
        const Box* mdhd = find(m, "mdhd");
        const Box* minf = find(m, "minf");
        if (mdhd == nullptr || minf == nullptr || mdhd->payload.n < 24) return no("no mdhd/minf");
        out.timescale = mdhd->payload.p[0] == 1 ? be32(mdhd->payload.p + 4 + 8 + 8) : be32(mdhd->payload.p + 4 + 4 + 4);
        if (out.timescale == 0) return no("zero timescale");
        const std::vector<Box> mi = children(minf->payload);
        const Box* stbl = find(mi, "stbl");
        if (stbl == nullptr) return no("no stbl");
        const std::vector<Box> st = children(stbl->payload);
        const Box *stsd = find(st, "stsd"), *stts = find(st, "stts"), *stsz = find(st, "stsz"), *stsc = find(st, "stsc"),
                  *stco = find(st, "stco"), *co64 = find(st, "co64"), *ctts = find(st, "ctts"), *stss = find(st, "stss");
        if (!stsd || !stts || !stsz || !stsc || (!stco && !co64)) return no("incomplete sample tables");

        // Sample entry (exactly one).
        if (stsd->payload.n < 8 || be32(stsd->payload.p + 4) != 1) return no("several sample entries");
        const std::vector<Box> entry = children({stsd->payload.p + 8, stsd->payload.n - 8});
        if (entry.empty()) return no("no sample entry");
        out.entryType.assign(entry[0].type, 4);
        if (entry[0].payload.n < 78) return no("short sample entry");
        const std::vector<Box> ext = children(entry[0].payload, 78);
        if (const Box* c = find(ext, "hvcC")) out.codecConfig.assign(c->payload.p, c->payload.p + c->payload.n);
        else if (const Box* c2 = find(ext, "avcC")) out.codecConfig.assign(c2->payload.p, c2->payload.p + c2->payload.n);
        if (const Box* colr = find(ext, "colr")) {
            out.colrPayload.assign(colr->payload.p, colr->payload.p + colr->payload.n);
            if (colr->payload.n >= 10 && std::memcmp(colr->payload.p, "nclc", 4) == 0) {  // QuickTime form: no range flag (limited)
                out.hasNclx = true;
                out.primaries = be16(colr->payload.p + 4);
                out.transfer = be16(colr->payload.p + 6);
                out.matrix = be16(colr->payload.p + 8);
            } else if (colr->payload.n >= 11 && std::memcmp(colr->payload.p, "nclx", 4) == 0) {
                out.hasNclx = true;
                out.primaries = be16(colr->payload.p + 4);
                out.transfer = be16(colr->payload.p + 6);
                out.matrix = be16(colr->payload.p + 8);
                out.fullRange = (colr->payload.p[10] & 0x80) != 0;
            }
        }

        // Sizes.
        if (stsz->payload.n < 12) return no("short stsz");
        const uint32_t fixedSize = be32(stsz->payload.p + 4);
        const uint32_t count = be32(stsz->payload.p + 8);
        if (fixedSize == 0 && stsz->payload.n < 12 + static_cast<size_t>(count) * 4) return no("short stsz");
        out.samples.resize(count);
        for (uint32_t i = 0; i < count; ++i) out.samples[i].size = fixedSize != 0 ? fixedSize : be32(stsz->payload.p + 12 + static_cast<size_t>(i) * 4);

        // Times.
        {
            if (stts->payload.n < 8) return no("short stts");
            const uint32_t entries = be32(stts->payload.p + 4);
            if (stts->payload.n < 8 + static_cast<size_t>(entries) * 8) return no("short stts");
            uint32_t i = 0;
            int64_t dts = 0;
            for (uint32_t e = 0; e < entries && i < count; ++e) {
                const uint32_t n = be32(stts->payload.p + 8 + e * 8), delta = be32(stts->payload.p + 12 + e * 8);
                for (uint32_t k = 0; k < n && i < count; ++k, ++i) {
                    out.samples[i].dts = dts;
                    out.samples[i].pts = dts;
                    dts += delta;
                }
            }
            if (i != count) return no("stts does not cover every sample");
        }
        if (ctts != nullptr) {
            if (ctts->payload.n < 8) return no("short ctts");
            const uint32_t entries = be32(ctts->payload.p + 4);
            if (ctts->payload.n < 8 + static_cast<size_t>(entries) * 8) return no("short ctts");
            uint32_t i = 0;
            for (uint32_t e = 0; e < entries && i < count; ++e) {
                const uint32_t n = be32(ctts->payload.p + 8 + e * 8);
                // Signed in both versions: QuickTime writes negative offsets into version 0 boxes (the iPhone does).
                const int64_t off = static_cast<int32_t>(be32(ctts->payload.p + 12 + e * 8));
                for (uint32_t k = 0; k < n && i < count; ++k, ++i) out.samples[i].pts += off;
            }
            if (i != count) return no("ctts does not cover every sample");
        }
        // Sync samples.
        if (stss == nullptr) {
            for (Mp4Sample& s : out.samples) s.sync = true;
        } else {
            if (stss->payload.n < 8) return no("short stss");
            const uint32_t entries = be32(stss->payload.p + 4);
            if (stss->payload.n < 8 + static_cast<size_t>(entries) * 4) return no("short stss");
            for (uint32_t e = 0; e < entries; ++e) {
                const uint32_t n = be32(stss->payload.p + 8 + e * 4);
                if (n >= 1 && n <= count) out.samples[n - 1].sync = true;
            }
        }
        // Offsets: chunks and stsc.
        {
            const bool wide = co64 != nullptr;
            const Box* co = wide ? co64 : stco;
            if (co->payload.n < 8) return no("short chunk table");
            const uint32_t chunks = be32(co->payload.p + 4);
            if (co->payload.n < 8 + static_cast<size_t>(chunks) * (wide ? 8 : 4)) return no("short chunk table");
            if (stsc->payload.n < 8) return no("short stsc");
            const uint32_t runs = be32(stsc->payload.p + 4);
            if (stsc->payload.n < 8 + static_cast<size_t>(runs) * 12) return no("short stsc");
            uint32_t sample = 0;
            for (uint32_t c = 0; c < chunks; ++c) {
                // The run that holds chunk c+1.
                uint32_t perChunk = 0;
                for (uint32_t r = 0; r < runs; ++r) {
                    if (be32(stsc->payload.p + 8 + r * 12) <= c + 1) perChunk = be32(stsc->payload.p + 12 + r * 12);
                }
                uint64_t offset = wide ? be64(co->payload.p + 8 + static_cast<size_t>(c) * 8) : be32(co->payload.p + 8 + static_cast<size_t>(c) * 4);
                for (uint32_t k = 0; k < perChunk && sample < count; ++k, ++sample) {
                    out.samples[sample].offset = offset;
                    offset += out.samples[sample].size;
                }
            }
            if (sample != count) return no("chunk tables do not cover every sample");
        }
        // Edit list: none, or one non-empty edit at normal rate (the iPhone's shift of the first presentation time).
        if (const Box* edts = find(t, "edts")) {
            const std::vector<Box> e = children(edts->payload);
            if (const Box* elst = find(e, "elst")) {
                if (elst->payload.n < 8) return no("short elst");
                const bool v1 = elst->payload.p[0] == 1;
                const uint32_t entries = be32(elst->payload.p + 4);
                const size_t stride = v1 ? 20 : 12;
                if (elst->payload.n < 8 + static_cast<size_t>(entries) * stride) return no("short elst");
                if (entries > 1) return no("several edits");
                if (entries == 1) {
                    const uint8_t* q = elst->payload.p + 8;
                    const int64_t mediaTime = v1 ? static_cast<int64_t>(be64(q + 8)) : static_cast<int32_t>(be32(q + 4));
                    const uint8_t* rate = q + (v1 ? 16 : 8);
                    if (mediaTime < 0) return no("empty edit");
                    if (be16(rate) != 1 || be16(rate + 2) != 0) return no("edit at a different rate");
                    out.editStart = mediaTime;
                }
            }
        }
        return out;
    }
    return no("no video track");
}

}  // namespace uv::encode
