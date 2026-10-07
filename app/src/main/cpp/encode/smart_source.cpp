#include "encode/smart_source.h"

#include <algorithm>

namespace uv::encode::smart {

InspectedAsset inspectAsset(const ByteSource& source, uint64_t fileSize, const OutputSpec& out) {
    InspectedAsset r;
    auto no = [&](std::string why) {
        r.why = std::move(why);
        r.plan.ok = false;
        return std::move(r);
    };
    std::string why;
    auto track = readMp4VideoTrack(source, fileSize, &why);
    if (!track) return no(why);
    r.track = std::move(*track);
    r.parsed = true;
    const Mp4VideoTrack& t = r.track;
    r.plan.rotation = t.rotationDegrees;

    if (t.entryType != "hvc1" && t.entryType != "hev1") return no("not HEVC (" + t.entryType + ")");
    const auto cfg = hevc::parseHvcc(t.codecConfig.data(), t.codecConfig.size());
    if (!cfg || !cfg->sets.complete()) return no("no usable hvcC");
    if (cfg->lengthSize != 4) return no("NAL length size is not 4");
    r.sets = cfg->sets;
    r.hvcc = t.codecConfig;
    const auto sps = hevc::parseSps(cfg->sets.sps[0].data(), cfg->sets.sps[0].size());
    if (!sps) return no("unreadable SPS");
    const uint8_t depth = out.hdr ? 10 : 8;
    const uint8_t profile = out.hdr ? 2 : 1;
    if (sps->profileIdc != profile || sps->bitDepthLuma != depth || sps->bitDepthChroma != depth || sps->chromaFormat != 1) {
        return no("profile, bit depth or chroma format differs from the output's");
    }
    if (sps->displayWidth() != static_cast<uint32_t>(out.width) || sps->displayHeight() != static_cast<uint32_t>(out.height) ||
        t.width != static_cast<uint32_t>(out.width) || t.height != static_cast<uint32_t>(out.height)) {
        return no("picture size differs from the output's");
    }
    if (!t.hasNclx) return no("no colour description");
    if (out.hdr) {
        if (t.primaries != 9 || t.transfer != 18 || t.matrix != 9 || t.fullRange) return no("not BT.2020 HLG limited range");
    } else if (t.primaries != 1 || t.transfer != 1 || t.matrix != 1 || t.fullRange) {
        return no("not BT.709 limited range");
    }
    if (t.rotationDegrees != 0 && t.rotationDegrees != 180) return no("rotated 90 degrees or mirrored");

    // Sample times and random access points.
    // Frame 0 is the sample with the smallest presentation time, as in the decoders of the preview and the export
    // (VideoDecoder::startPtsUs_): an edit list that starts a little before the first frame does not shift the frames.
    int64_t minPts = t.samples.empty() ? 0 : t.samples[0].pts;
    for (const Mp4Sample& s : t.samples) minPts = std::min(minPts, s.pts);
    r.plan.samples.resize(t.samples.size());
    std::vector<uint8_t> head(4096);
    for (size_t i = 0; i < t.samples.size(); ++i) {
        const Mp4Sample& s = t.samples[i];
        GopSample& g = r.plan.samples[i];
        g.pres = presentationFrame(s.pts, minPts, t.timescale, out.fps);
        if (!s.sync) continue;
        const size_t have = std::min<size_t>(head.size(), s.size);
        if (!source(s.offset, head.data(), have)) return no("cannot read a sample");
        const int type = hevc::firstVclTypeInPrefix(head.data(), have, 4);
        g.irapType = hevc::isIrap(static_cast<uint8_t>(std::max(type, 0))) && type >= 0 ? type : -1;
    }
    r.plan.ok = true;
    r.why = "ok";
    return r;
}

}  // namespace uv::encode::smart
