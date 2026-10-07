#pragma once

// Pure helpers for HEVC samples in the length-prefixed (MP4) form, used by smart export (SPECS.md 5.10): splitting a sample
// into NAL units, reading the profile/tier/level and bit depth from an SPS, building and parsing hvcC, and the three edits that
// a copied sample is allowed to receive (docs: DECISIONS.md, "Smart export"):
//   1. the first picture of a copied run, a CRA, becomes a BLA_N_LP (NAL header type only) with no_output_of_prior_pics_flag = 0;
//   2. Dolby Vision RPU NAL units (type 62) are removed;
//   3. VPS/SPS/PPS are put in front of the first picture of every run (the "hybrid" container layout).
// Everything else in a copied sample stays bit-identical.

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace uv::encode::hevc {

constexpr uint8_t kVps = 32;
constexpr uint8_t kSps = 33;
constexpr uint8_t kPps = 34;
constexpr uint8_t kDolbyRpu = 62;

inline uint8_t nalType(const uint8_t* nal) { return static_cast<uint8_t>((nal[0] >> 1) & 0x3f); }
inline bool isVcl(uint8_t type) { return type < 32; }
inline bool isIrap(uint8_t type) { return type >= 16 && type <= 23; }
inline bool isIdr(uint8_t type) { return type == 19 || type == 20; }
inline bool isBla(uint8_t type) { return type >= 16 && type <= 18; }
inline bool isCra(uint8_t type) { return type == 21; }
inline bool isRasl(uint8_t type) { return type == 8 || type == 9; }
inline bool isRadl(uint8_t type) { return type == 6 || type == 7; }

struct NalRef {
    const uint8_t* data = nullptr;
    size_t size = 0;
};

// Splits a length-prefixed sample (`lengthSize` bytes per length). Returns false when the lengths do not add up exactly.
inline bool splitSample(const uint8_t* data, size_t size, int lengthSize, std::vector<NalRef>* out) {
    out->clear();
    size_t p = 0;
    while (p < size) {
        if (p + static_cast<size_t>(lengthSize) > size) return false;
        size_t len = 0;
        for (int i = 0; i < lengthSize; ++i) len = (len << 8) | data[p + static_cast<size_t>(i)];
        p += static_cast<size_t>(lengthSize);
        if (len < 2 || p + len > size) return false;
        out->push_back({data + p, len});
        p += len;
    }
    return !out->empty();
}

// The type of the first VCL NAL unit of a sample, or -1 when there is none or the sample is malformed.
inline int firstVclType(const uint8_t* data, size_t size, int lengthSize) {
    std::vector<NalRef> nals;
    if (!splitSample(data, size, lengthSize, &nals)) return -1;
    for (const NalRef& n : nals) {
        if (isVcl(nalType(n.data))) return nalType(n.data);
    }
    return -1;
}

// Like firstVclType for the first bytes of a sample only (`have` of its `total` bytes): walks the NAL headers until a VCL
// unit starts. -1 when none starts inside the prefix.
inline int firstVclTypeInPrefix(const uint8_t* data, size_t have, int lengthSize) {
    size_t p = 0;
    while (p + static_cast<size_t>(lengthSize) + 2 <= have) {
        size_t len = 0;
        for (int i = 0; i < lengthSize; ++i) len = (len << 8) | data[p + static_cast<size_t>(i)];
        const uint8_t t = nalType(data + p + static_cast<size_t>(lengthSize));
        if (isVcl(t)) return t;
        if (len < 2) return -1;
        p += static_cast<size_t>(lengthSize) + len;
    }
    return -1;
}

inline void appendNal(std::vector<uint8_t>* out, const uint8_t* nal, size_t size) {
    out->push_back(static_cast<uint8_t>(size >> 24));
    out->push_back(static_cast<uint8_t>(size >> 16));
    out->push_back(static_cast<uint8_t>(size >> 8));
    out->push_back(static_cast<uint8_t>(size));
    out->insert(out->end(), nal, nal + size);
}

// Annex B (start code) data -> NAL units, e.g. the encoder's csd-0.
inline std::vector<NalRef> splitAnnexB(const uint8_t* data, size_t size) {
    std::vector<NalRef> out;
    size_t i = 0;
    auto startAt = [&](size_t p, size_t* len) {
        if (p + 3 <= size && data[p] == 0 && data[p + 1] == 0 && data[p + 2] == 1) { *len = 3; return true; }
        if (p + 4 <= size && data[p] == 0 && data[p + 1] == 0 && data[p + 2] == 0 && data[p + 3] == 1) { *len = 4; return true; }
        return false;
    };
    size_t sc = 0;
    while (i < size && !startAt(i, &sc)) ++i;
    while (i < size) {
        startAt(i, &sc);
        const size_t begin = i + sc;
        size_t j = begin;
        size_t next = 0;
        while (j < size && !startAt(j, &next)) ++j;
        size_t end = j;
        while (end > begin && data[end - 1] == 0 && j < size) --end;  // zero bytes belong to the next start code
        if (end > begin) out.push_back({data + begin, end - begin});
        i = j;
    }
    return out;
}

// Parameter sets of one stream, as NAL unit payloads without length or start code.
struct ParameterSets {
    std::vector<std::vector<uint8_t>> vps, sps, pps;
    bool complete() const { return !vps.empty() && !sps.empty() && !pps.empty(); }
    bool operator==(const ParameterSets& o) const { return vps == o.vps && sps == o.sps && pps == o.pps; }
};

inline void addTo(ParameterSets* ps, const uint8_t* nal, size_t size) {
    const uint8_t t = nalType(nal);
    std::vector<uint8_t> copy(nal, nal + size);
    if (t == kVps) ps->vps.push_back(std::move(copy));
    else if (t == kSps) ps->sps.push_back(std::move(copy));
    else if (t == kPps) ps->pps.push_back(std::move(copy));
}

inline ParameterSets parameterSetsFromAnnexB(const uint8_t* data, size_t size) {
    ParameterSets ps;
    for (const NalRef& n : splitAnnexB(data, size)) addTo(&ps, n.data, n.size);
    return ps;
}

// The in-band form: VPS, SPS, PPS each with a 4 byte length, ready to put in front of a sample.
inline std::vector<uint8_t> inBand(const ParameterSets& ps) {
    std::vector<uint8_t> out;
    for (const auto& v : ps.vps) appendNal(&out, v.data(), v.size());
    for (const auto& v : ps.sps) appendNal(&out, v.data(), v.size());
    for (const auto& v : ps.pps) appendNal(&out, v.data(), v.size());
    return out;
}

// What the SPS says that smart export compares.
struct SpsInfo {
    uint8_t profileSpace = 0;
    bool tierHigh = false;
    uint8_t profileIdc = 0;
    uint32_t compat = 0;
    uint64_t constraints = 0;  // 48 bits
    uint8_t levelIdc = 0;
    uint8_t chromaFormat = 1;
    uint8_t bitDepthLuma = 8;
    uint8_t bitDepthChroma = 8;
    uint32_t width = 0;   // coded size in luma samples
    uint32_t height = 0;
    uint32_t confLeft = 0, confRight = 0, confTop = 0, confBottom = 0;  // conformance window, in luma samples
    uint32_t displayWidth() const { return width - confLeft - confRight; }
    uint32_t displayHeight() const { return height - confTop - confBottom; }
};

class BitReader {
public:
    BitReader(const uint8_t* d, size_t n) : d_(d), n_(n) {}
    uint32_t bit() {
        if (pos_ >= n_ * 8) { bad_ = true; return 0; }
        const uint32_t v = (d_[pos_ >> 3] >> (7 - (pos_ & 7))) & 1u;
        ++pos_;
        return v;
    }
    uint64_t bits(int count) {
        uint64_t v = 0;
        for (int i = 0; i < count; ++i) v = (v << 1) | bit();
        return v;
    }
    uint32_t ue() {
        int zeros = 0;
        while (bit() == 0 && !bad_) {
            if (++zeros > 31) { bad_ = true; return 0; }
        }
        return zeros == 0 ? 0 : static_cast<uint32_t>(((1ull << zeros) - 1) + bits(zeros));
    }
    bool bad() const { return bad_; }

private:
    const uint8_t* d_;
    size_t n_;
    size_t pos_ = 0;
    bool bad_ = false;
};

inline std::vector<uint8_t> removeEmulationPrevention(const uint8_t* d, size_t n) {
    std::vector<uint8_t> out;
    out.reserve(n);
    int zeros = 0;
    for (size_t i = 0; i < n; ++i) {
        if (zeros >= 2 && d[i] == 3) { zeros = 0; continue; }
        out.push_back(d[i]);
        zeros = d[i] == 0 ? zeros + 1 : 0;
    }
    return out;
}

// Parses the start of an SPS NAL unit (with its two byte header).
inline std::optional<SpsInfo> parseSps(const uint8_t* nal, size_t size) {
    if (size < 16 || nalType(nal) != kSps) return std::nullopt;
    const std::vector<uint8_t> rbsp = removeEmulationPrevention(nal + 2, size - 2);
    BitReader r(rbsp.data(), rbsp.size());
    SpsInfo s;
    r.bits(4);  // vps id
    const uint32_t maxSubLayersMinus1 = static_cast<uint32_t>(r.bits(3));
    r.bit();    // temporal id nesting
    s.profileSpace = static_cast<uint8_t>(r.bits(2));
    s.tierHigh = r.bit() != 0;
    s.profileIdc = static_cast<uint8_t>(r.bits(5));
    s.compat = static_cast<uint32_t>(r.bits(32));
    s.constraints = r.bits(48);
    s.levelIdc = static_cast<uint8_t>(r.bits(8));
    std::array<bool, 8> profilePresent{}, levelPresent{};
    for (uint32_t i = 0; i < maxSubLayersMinus1; ++i) {
        profilePresent[i] = r.bit() != 0;
        levelPresent[i] = r.bit() != 0;
    }
    if (maxSubLayersMinus1 > 0) {
        for (uint32_t i = maxSubLayersMinus1; i < 8; ++i) r.bits(2);
    }
    for (uint32_t i = 0; i < maxSubLayersMinus1; ++i) {
        if (profilePresent[i]) r.bits(88);
        if (levelPresent[i]) r.bits(8);
    }
    r.ue();  // sps id
    s.chromaFormat = static_cast<uint8_t>(r.ue());
    if (s.chromaFormat == 3) r.bit();
    s.width = r.ue();
    s.height = r.ue();
    if (r.bit() != 0) {  // conformance window, in chroma units
        const uint32_t subW = (s.chromaFormat == 1 || s.chromaFormat == 2) ? 2 : 1;
        const uint32_t subH = s.chromaFormat == 1 ? 2 : 1;
        s.confLeft = r.ue() * subW;
        s.confRight = r.ue() * subW;
        s.confTop = r.ue() * subH;
        s.confBottom = r.ue() * subH;
    }
    s.bitDepthLuma = static_cast<uint8_t>(r.ue() + 8);
    s.bitDepthChroma = static_cast<uint8_t>(r.ue() + 8);
    if (r.bad()) return std::nullopt;
    return s;
}

// hvcC (ISO/IEC 14496-15) for one stream: length size 4, VPS/SPS/PPS arrays.
inline std::vector<uint8_t> buildHvcc(const ParameterSets& ps, const SpsInfo& s) {
    std::vector<uint8_t> o;
    auto u8 = [&](uint32_t v) { o.push_back(static_cast<uint8_t>(v)); };
    auto u16 = [&](uint32_t v) { u8(v >> 8); u8(v); };
    u8(1);
    u8((static_cast<uint32_t>(s.profileSpace) << 6) | (s.tierHigh ? 0x20u : 0u) | s.profileIdc);
    u8(s.compat >> 24); u8(s.compat >> 16); u8(s.compat >> 8); u8(s.compat);
    for (int i = 5; i >= 0; --i) u8(static_cast<uint32_t>(s.constraints >> (8 * i)));
    u8(s.levelIdc);
    u16(0xF000);                       // min_spatial_segmentation_idc = 0
    u8(0xFC);                          // parallelismType = 0
    u8(0xFC | s.chromaFormat);
    u8(0xF8 | (s.bitDepthLuma - 8));
    u8(0xF8 | (s.bitDepthChroma - 8));
    u16(0);                            // avgFrameRate
    u8((0u << 6) | (1u << 3) | (0u << 2) | 3u);  // constantFrameRate 0, numTemporalLayers 1, nested 0, lengthSize-1 = 3
    u8(3);
    auto array = [&](uint8_t type, const std::vector<std::vector<uint8_t>>& nals) {
        u8(0x80 | type);
        u16(static_cast<uint32_t>(nals.size()));
        for (const auto& n : nals) { u16(static_cast<uint32_t>(n.size())); o.insert(o.end(), n.begin(), n.end()); }
    };
    array(kVps, ps.vps);
    array(kSps, ps.sps);
    array(kPps, ps.pps);
    return o;
}

struct Hvcc {
    ParameterSets sets;
    int lengthSize = 4;
};

inline std::optional<Hvcc> parseHvcc(const uint8_t* d, size_t n) {
    if (n < 23 || d[0] != 1) return std::nullopt;
    Hvcc h;
    h.lengthSize = (d[21] & 3) + 1;
    const size_t arrays = d[22];
    size_t p = 23;
    for (size_t a = 0; a < arrays; ++a) {
        if (p + 3 > n) return std::nullopt;
        const uint8_t type = d[p] & 0x3f;
        const size_t count = (static_cast<size_t>(d[p + 1]) << 8) | d[p + 2];
        p += 3;
        for (size_t i = 0; i < count; ++i) {
            if (p + 2 > n) return std::nullopt;
            const size_t len = (static_cast<size_t>(d[p]) << 8) | d[p + 1];
            p += 2;
            if (p + len > n || len < 2) return std::nullopt;
            (void)type;
            addTo(&h.sets, d + p, len);
            p += len;
        }
    }
    return h;
}

// Edit 1: CRA -> BLA_N_LP, no_output_of_prior_pics_flag cleared. Only the NAL header type and one slice header bit change.
// Returns false when the sample has no CRA slice.
inline bool craToBla(std::vector<uint8_t>* sample, int lengthSize) {
    std::vector<NalRef> nals;
    if (!splitSample(sample->data(), sample->size(), lengthSize, &nals)) return false;
    bool changed = false;
    const uint8_t* base = sample->data();
    for (const NalRef& n : nals) {
        if (nalType(n.data) != 21 || n.size < 3) continue;
        uint8_t* p = sample->data() + (n.data - base);
        p[0] = static_cast<uint8_t>((p[0] & 0x81) | (18 << 1));
        p[2] = static_cast<uint8_t>(p[2] & ~0x40);  // no_output_of_prior_pics_flag follows first_slice_segment_in_pic_flag
        changed = true;
    }
    return changed;
}

// Edit 2: drop every NAL unit of `type` (Dolby Vision RPU).
inline bool dropNals(std::vector<uint8_t>* sample, int lengthSize, uint8_t type) {
    std::vector<NalRef> nals;
    if (!splitSample(sample->data(), sample->size(), lengthSize, &nals)) return false;
    bool any = false;
    for (const NalRef& n : nals) any = any || nalType(n.data) == type;
    if (!any) return true;
    std::vector<uint8_t> out;
    out.reserve(sample->size());
    for (const NalRef& n : nals) {
        if (nalType(n.data) != type) appendNal(&out, n.data, n.size);
    }
    sample->swap(out);
    return true;
}

}  // namespace uv::encode::hevc
