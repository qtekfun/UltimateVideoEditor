// Host tests for smart export: encode/hevc_nal.h, encode/smart_plan.h, encode/mp4_writer.* and encode/mp4_source.*.
// No Android dependencies. Run with scripts/run-native-tests.sh or the CMake target uv_smart_export_host_tests.
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "encode/hevc_nal.h"
#include "encode/mp4_source.h"
#include "encode/mp4_writer.h"
#include "encode/smart_plan.h"
#include "encode/smart_source.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                                    \
    do {                                                                               \
        if (!(cond)) {                                                                 \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);                \
            ++failures;                                                                \
        }                                                                              \
    } while (0)
#define CHECK_EQ(actual, expected)                                                                                 \
    do {                                                                                                           \
        const long long a_ = static_cast<long long>(actual);                                                       \
        const long long e_ = static_cast<long long>(expected);                                                     \
        if (a_ != e_) {                                                                                            \
            std::printf("FAIL %s:%d: %s = %lld, expected %lld\n", __FILE__, __LINE__, #actual, a_, e_);           \
            ++failures;                                                                                            \
        }                                                                                                          \
    } while (0)

using namespace uv::encode;
using namespace uv::encode::smart;
namespace nal = uv::encode::hevc;

std::vector<uint8_t> hex(const char* s) {
    std::vector<uint8_t> out;
    for (; s[0] != 0 && s[1] != 0; s += 2) {
        char b[3] = {s[0], s[1], 0};
        out.push_back(static_cast<uint8_t>(std::strtoul(b, nullptr, 16)));
    }
    return out;
}

// Real parameter sets: an iPhone 4K60 HLG clip (High tier, level 5.1, two sub-layers) and the Pixel 8 HEVC encoder's output.
const char* kIphoneSps = "420102222000000300b000000300000300990000a001e020021c4d8815e49165535091209008";
const char* kPixelSps = "42010102200000030000030000030000030099a001e020021c4da2d2bb04bb9a848904815c50a4";

// ---------------------------------------------------------------- hevc_nal

std::vector<uint8_t> sampleOf(const std::vector<std::vector<uint8_t>>& nals) {
    std::vector<uint8_t> s;
    for (const auto& n : nals) nal::appendNal(&s, n.data(), n.size());
    return s;
}

std::vector<uint8_t> fakeNal(int type, size_t payload, uint8_t fill = 0x55) {
    std::vector<uint8_t> n(2 + payload, fill);
    n[0] = static_cast<uint8_t>(type << 1);
    n[1] = 1;
    return n;
}

void spsParsing() {
    const auto iphone = hex(kIphoneSps);
    const auto sps = nal::parseSps(iphone.data(), iphone.size());
    CHECK(sps.has_value());
    if (sps) {
        CHECK_EQ(sps->profileIdc, 2);
        CHECK(sps->tierHigh);
        CHECK_EQ(sps->levelIdc, 153);
        CHECK_EQ(sps->bitDepthLuma, 10);
        CHECK_EQ(sps->chromaFormat, 1);
        CHECK_EQ(sps->width, 3840);
        CHECK_EQ(sps->displayHeight(), 2160);
    }
    const auto pixel = hex(kPixelSps);
    const auto p = nal::parseSps(pixel.data(), pixel.size());
    CHECK(p.has_value());
    if (p) {
        CHECK(!p->tierHigh);
        CHECK_EQ(p->levelIdc, 153);
        CHECK_EQ(p->bitDepthChroma, 10);
        CHECK_EQ(p->displayWidth(), 3840);
        CHECK_EQ(p->displayHeight(), 2160);
    }
    CHECK(!nal::parseSps(iphone.data(), 5).has_value());
}

void hvccRoundTrip() {
    nal::ParameterSets ps;
    const auto vps = hex("40010c02ffff222000000300b00000030000030099000015c090");
    const auto sp = hex(kIphoneSps);
    const auto pp = hex("4401c02d9219853240");
    nal::addTo(&ps, vps.data(), vps.size());
    nal::addTo(&ps, sp.data(), sp.size());
    nal::addTo(&ps, pp.data(), pp.size());
    CHECK(ps.complete());
    const auto info = nal::parseSps(sp.data(), sp.size());
    const auto hvcc = nal::buildHvcc(ps, *info);
    const auto back = nal::parseHvcc(hvcc.data(), hvcc.size());
    CHECK(back.has_value());
    if (back) {
        CHECK(back->sets == ps);
        CHECK_EQ(back->lengthSize, 4);
    }
    CHECK_EQ(hvcc[1] & 0x20, 0x20);  // tier flag survives
    CHECK_EQ(hvcc[12], 153);         // level
    const auto inband = nal::inBand(ps);
    std::vector<nal::NalRef> nals;
    CHECK(nal::splitSample(inband.data(), inband.size(), 4, &nals));
    CHECK_EQ(nals.size(), 3);
}

void annexBSplitting() {
    const std::vector<uint8_t> csd = {0, 0, 0, 1, 0x40, 1, 0xAA, 0, 0, 1, 0x42, 1, 0xBB, 0xCC, 0, 0, 0, 1, 0x44, 1, 0xDD};
    const auto ps = nal::parameterSetsFromAnnexB(csd.data(), csd.size());
    CHECK_EQ(ps.vps.size(), 1);
    CHECK_EQ(ps.sps.size(), 1);
    CHECK_EQ(ps.pps.size(), 1);
    CHECK_EQ(ps.sps[0].size(), 4);
    CHECK_EQ(ps.pps[0].back(), 0xDD);
}

void craBecomesBla() {
    // SEI, CRA slice, RPU: the type changes, one bit clears, the rest is untouched; the RPU goes.
    auto slice = fakeNal(21, 20);
    slice[2] = 0xC8;  // first_slice_segment_in_pic_flag = 1, no_output_of_prior_pics_flag = 1
    auto sample = sampleOf({fakeNal(39, 5), slice, fakeNal(62, 7)});
    const auto original = sample;
    CHECK(nal::craToBla(&sample, 4));
    CHECK_EQ(sample.size(), original.size());
    std::vector<nal::NalRef> nals;
    CHECK(nal::splitSample(sample.data(), sample.size(), 4, &nals));
    CHECK_EQ(nal::nalType(nals[1].data), 18);
    CHECK_EQ(nals[1].data[1], 1);
    CHECK_EQ(nals[1].data[2], 0x88);
    size_t differing = 0;
    for (size_t i = 0; i < sample.size(); ++i) differing += sample[i] != original[i];
    CHECK_EQ(differing, 2);  // the NAL type byte and the flag byte, nothing else
    CHECK(nal::dropNals(&sample, 4, nal::kDolbyRpu));
    CHECK(nal::splitSample(sample.data(), sample.size(), 4, &nals));
    CHECK_EQ(nals.size(), 2);
    CHECK_EQ(nal::nalType(nals[0].data), 39);
    // A sample without a CRA is left alone and reports it.
    auto trail = sampleOf({fakeNal(1, 10)});
    CHECK(!nal::craToBla(&trail, 4));
    // IDR stays IDR.
    auto idr = sampleOf({fakeNal(20, 10)});
    CHECK(!nal::craToBla(&idr, 4));
    CHECK_EQ(nal::firstVclType(idr.data(), idr.size(), 4), 20);
    // Malformed length.
    std::vector<uint8_t> broken = {0, 0, 0, 99, 1, 2, 3};
    CHECK(!nal::splitSample(broken.data(), broken.size(), 4, &nals));
    CHECK_EQ(nal::firstVclType(broken.data(), broken.size(), 4), -1);
}

// ---------------------------------------------------------------- presentation frames

void presentationFrames() {
    const Fps f60{60, 1};
    // iPhone: 1/600 time base, 10 ticks per frame, an edit starting 20 ticks in.
    CHECK_EQ(presentationFrame(20, 20, 600, f60), 0);
    CHECK_EQ(presentationFrame(30, 20, 600, f60), 1);
    CHECK_EQ(presentationFrame(20 + 10 * 12345, 20, 600, f60), 12345);
    // Android phone: 90 kHz, steps of 1499 and 1500 around 1500 ticks per frame: still on the grid.
    CHECK_EQ(presentationFrame(1499, 0, 90000, f60), 1);
    CHECK_EQ(presentationFrame(2998, 0, 90000, f60), 2);
    // A dropped frame is a double step: that is on the grid, the sample after it simply is frame + 2.
    CHECK_EQ(presentationFrame(2997 + 1500, 0, 90000, f60), 3);
    // Rounding to the nearest frame: half a frame rounds up, a jittered 90 kHz stamp lands on its frame.
    CHECK_EQ(presentationFrame(750, 0, 90000, f60), 1);
    CHECK_EQ(presentationFrame(749, 0, 90000, f60), 0);
    CHECK_EQ(presentationFrame(11849, 1509 - 1509, 90000, f60), 8);
    // 59.94 footage against 60 drifts a whole frame in 1001 frames, but a sample is still mapped to one frame.
    const Fps f5994{60000, 1001};
    CHECK_EQ(presentationFrame(1501, 0, 90000, f5994), 1);
    CHECK_EQ(presentationFrame(1501 * 600, 0, 90000, f60), 600);
    // Negative presentation times (before the edit) round down, not toward zero.
    CHECK_EQ(presentationFrame(0, 20, 600, f60), -2);
}

// ---------------------------------------------------------------- GOP trimming

// Builds the samples of an iPhone-like open-GOP stream: a CRA every `gop` frames with `lead` leading pictures that follow it
// in decode order and are shown before it. Frame numbers start at `first`.
std::vector<GopSample> openGop(int gops, int gop, int lead, int type = 21, int64_t first = 0) {
    std::vector<GopSample> s;
    // First GOP of the file: IDR, no leading pictures.
    for (int g = 0; g < gops; ++g) {
        const int64_t start = first + static_cast<int64_t>(g) * gop;
        s.push_back({start, g == 0 ? 20 : type});
        for (int k = 0; k < lead && g > 0; ++k) s.push_back({start - lead + k, -1});  // shown before the CRA
        // Trailing pictures of this GOP: frames start+1 .. start+gop-1-lead (the last `lead` belong to the next CRA's leading).
        const int trailing = gop - 1 - (g + 1 < gops ? lead : 0);
        for (int k = 1; k <= trailing; ++k) s.push_back({start + k, -1});
    }
    return s;
}

void trimTable() {
    // IDR then CRA GOPs of 60 with 3 leading pictures: frames 0..599 over 10 GOPs.
    const auto s = openGop(10, 60, 3);
    // A stretch on GOP boundaries: starts at the CRA of frame 60 (its leading pictures, frames 57..59, are not in the stretch).
    // The run reaches the end of the stretch minus the frames it cannot end on: after frame 296 the next frame to encode, 297, is a
    // leading picture of the CRA at 300 (a seek to it lands on that CRA and its leading pictures cannot be decoded), so the run
    // ends one frame earlier, after 295.
    {
        const auto r = trimToGop(s, 60, 300, 30);
        CHECK(r.has_value());
        if (r) {
            CHECK_EQ(r->presStart, 60);
            CHECK_EQ(r->count, 236);  // 60 .. 295
            CHECK_EQ(s[r->firstSample].irapType, 21);
            CHECK_EQ(s[r->endSample].pres, 296);
        }
    }
    // A stretch inside a GOP: starts at the next CRA and runs to the end of the stretch (a partial last GOP is copied too).
    {
        const auto r = trimToGop(s, 70, 590, 30);
        CHECK(r.has_value());
        if (r) {
            CHECK_EQ(r->presStart, 120);
            CHECK_EQ(r->presStart + r->count, 590);
        }
    }
    // The IDR at the very start: copied from frame 0, nothing dropped.
    {
        const auto r = trimToGop(s, 0, 600, 30);
        CHECK(r.has_value());
        if (r) {
            CHECK_EQ(r->presStart, 0);
            CHECK_EQ(r->count, 600);
        }
    }
    // Between two access points: still a run, from the CRA at 120 to the end of the stretch.
    {
        const auto r = trimToGop(s, 70, 170, 30);
        CHECK(r.has_value() && r->presStart == 120 && r->presStart + r->count == 170);
    }
    // Shorter than the minimum run.
    CHECK(!trimToGop(s, 60, 85, 30).has_value());
    // Not a single random access point inside the stretch.
    CHECK(!trimToGop(s, 61, 119, 1).has_value());
    {
        const auto r = trimToGop(s, 60, 296, 30);
        CHECK(r.has_value() && r->presStart + r->count == 296);  // frame 296 is an ordinary trailing picture: a valid next frame
    }
}

void trimSeekRule() {
    // The frame after a run is reached by a seek. If it is a leading picture of a CRA the seek lands on that CRA and cannot
    // decode it, so the run must not end right before it. Decode order here: CRA(100) RASL(97,98,99) trailing(101..).
    std::vector<GopSample> s = {{40, 20}};
    for (int64_t f = 41; f < 97; ++f) s.push_back({f, -1});
    s.push_back({100, 21});
    for (int64_t f = 97; f < 100; ++f) s.push_back({f, -1});
    for (int64_t f = 101; f < 140; ++f) s.push_back({f, -1});
    // A stretch ending at 97 would put the next frame (97) at a leading picture: the run ends earlier, at 95, so that 96 follows.
    const auto r = trimToGop(s, 40, 97, 10);
    CHECK(r.has_value());
    if (r) CHECK_EQ(r->presStart + r->count, 96);
    // A stretch that includes the CRA and its leading pictures can run on.
    const auto q = trimToGop(s, 40, 120, 10);
    CHECK(q.has_value());
    if (q) CHECK_EQ(q->presStart + q->count, 120);
}

void trimClosedGop() {
    // Android phone clip: IDR every 61 frames, no leading pictures, a P-only stream.
    std::vector<GopSample> s;
    for (int64_t f = 0; f < 610; ++f) s.push_back({f, f % 61 == 0 ? 19 : -1});
    const auto r = trimToGop(s, 10, 600, 30);
    CHECK(r.has_value());
    if (r) {
        CHECK_EQ(r->presStart, 61);
        CHECK_EQ(r->count, 539);  // 61 .. 599: the run goes to the end of the stretch
    }
    const auto all = trimToGop(s, 0, 610, 30);
    CHECK(all && all->count == 610);
}

void trimBFrames() {
    // Closed GOP with B-frame reordering (decode order I P B B ...: presentation 0 3 1 2 6 4 5 ...), IDR every 12.
    std::vector<GopSample> s;
    for (int g = 0; g < 5; ++g) {
        const int64_t b = g * 12;
        s.push_back({b, 19});
        for (int k = 0; k < 11; k += 3) {
            // P at +k+3 (clamped), then Bs at +k+1, +k+2 where they exist
        }
        std::vector<int64_t> order = {3, 1, 2, 6, 4, 5, 9, 7, 8, 11, 10};
        for (int64_t o : order) s.push_back({b + o, -1});
    }
    const auto r = trimToGop(s, 5, 60, 12);
    CHECK(r.has_value());
    if (r) {
        CHECK_EQ(r->presStart, 12);
        CHECK_EQ(r->count, 48);
    }
}

void trimVfrHole() {
    // A dropped frame in the third GOP: frame 130 never exists. Copying stops before the GOP with the hole.
    std::vector<GopSample> s;
    for (int64_t f = 0; f < 360; ++f) {
        if (f == 130) continue;
        s.push_back({f, f % 60 == 0 ? 19 : -1});
    }
    const auto r = trimToGop(s, 0, 360, 30);
    CHECK(r.has_value());
    if (r) {
        CHECK_EQ(r->presStart, 0);
        CHECK_EQ(r->count, 130);  // everything before the hole (frames 0..129)
    }
    // A sample off the frame grid ends the run the same way.
    s.clear();
    for (int64_t f = 0; f < 240; ++f) s.push_back({f == 100 ? kNoFrame : f, f % 60 == 0 ? 19 : -1});
    const auto q = trimToGop(s, 0, 240, 30);
    CHECK(q.has_value() && q->count == 100);  // frames 0..99, then the one off the grid
    // 30 fps footage in a 60 fps project: every other frame is missing, so nothing can be copied.
    s.clear();
    for (int64_t f = 0; f < 600; ++f) s.push_back({f * 2, f % 30 == 0 ? 19 : -1});
    CHECK(!trimToGop(s, 0, 1200, 30).has_value());
    // Duplicate frame (two samples for one frame): not contiguous.
    s.clear();
    for (int64_t f = 0; f < 240; ++f) s.push_back({f == 70 ? 69 : f, f % 60 == 0 ? 19 : -1});
    const auto d = trimToGop(s, 0, 240, 30);
    CHECK(d.has_value() && d->count == 70);  // up to the duplicate
}

void trimRejectsNonIrapStarts() {
    // Sync flags without an IRAP NAL (irapType -1) never start a copy, and a start before srcStart is not used.
    std::vector<GopSample> s;
    for (int64_t f = 0; f < 200; ++f) s.push_back({f, -1});
    CHECK(!trimToGop(s, 0, 200, 1).has_value());
    // BLA and CRA start; reserved IRAP types 22 and 23 do not.
    std::vector<GopSample> t = {{0, 22}, {1, -1}, {2, -1}, {3, 22}};
    CHECK(!trimToGop(t, 0, 4, 1).has_value());
    t = {{0, 16}, {1, -1}, {2, -1}, {3, 16}};
    CHECK(trimToGop(t, 0, 4, 1).has_value());
}

// ---------------------------------------------------------------- planner

VideoClip plain(int64_t start, int64_t frames, int64_t sourceIn, int64_t asset, int layer = 0) {
    VideoClip c;
    c.startFrame = start;
    c.durationFrames = frames;
    c.sourceInFrame = sourceIn;
    c.assetKey = asset;
    c.layer = layer;
    c.colorMode = 3;  // HLG into HLG
    return c;
}

AssetForSmart iphoneAsset(int rotation = 180, bool ok = true) {
    AssetForSmart a;
    a.ok = ok;
    a.rotation = rotation;
    a.samples = openGop(40, 60, 3);
    return a;
}

Context hdrContext(int64_t total) {
    Context c;
    c.outWidth = c.canvasWidth = 3840;
    c.outHeight = c.canvasHeight = 2160;
    c.outFps = c.projectFps = {60, 1};
    c.hdr = true;
    c.hevc = true;
    c.totalFrames = total;
    return c;
}

Plan planFor(const std::vector<VideoClip>& clips, const Context& ctx, const AssetForSmart& a1, const AssetForSmart* a2 = nullptr) {
    return planSmartExport(clips, ctx, [&](int64_t key) -> const AssetForSmart* { return key == 1 ? &a1 : (key == 2 ? a2 : nullptr); });
}

void plannerBasics() {
    const AssetForSmart a = iphoneAsset();
    const auto plan = planFor({plain(0, 1200, 100, 1)}, hdrContext(1200), a);
    CHECK(plan.usable);
    CHECK_EQ(plan.rotation, 180);
    CHECK_EQ(plan.segments.size(), 1);
    if (!plan.segments.empty()) {
        // Source frames 100..1299: whole GOPs from the CRA at 120, ending at the leading pictures of the CRA at 1260.
        const CopySegment& s = plan.segments[0];
        CHECK_EQ(s.run.presStart, 120);
        CHECK_EQ(s.outStart, 20);  // timeline frame = start + (120 - 100)
        CHECK_EQ(s.frames, 1180);  // 120 .. 1299, to the end of the stretch
        CHECK_EQ(plan.copiedFrames, s.frames);
    }
}

void plannerDisqualifiers() {
    const AssetForSmart a = iphoneAsset();
    const Context ctx = hdrContext(1200);
    const VideoClip good = plain(0, 1200, 0, 1);
    CHECK(planFor({good}, ctx, a).usable);

    auto expectNo = [&](VideoClip c, const char* what) {
        const Plan p = planFor({c}, ctx, a);
        if (p.usable) std::printf("planner accepted: %s\n", what);
        CHECK(!p.usable);
    };
    VideoClip c = good; c.posX = 1; expectNo(c, "moved");
    c = good; c.posY = -0.5; expectNo(c, "moved y");
    c = good; c.scaleX = 0.99; expectNo(c, "scaled x");
    c = good; c.scaleY = 1.01; expectNo(c, "scaled y");
    c = good; c.rotationDeg = 0.5; expectNo(c, "rotated");
    c = good; c.opacity = 0.99; expectNo(c, "opacity");
    c = good; c.fadeInFrames = 2000; expectNo(c, "fade in over the whole clip");
    c = good; c.colorMode = 2; expectNo(c, "SDR source (placed in HLG) in an HDR export");
    c = good; c.colorMode = 0; expectNo(c, "SDR source in an HDR export");
    c = good; c.colorMode = 4; expectNo(c, "PQ source in an HLG export");
    c = good; c.colorMode = 5; expectNo(c, "PQ source in an HLG export (target form)");
    c = good; c.colorMode = 1; CHECK(planFor({c}, ctx, a).usable);  // an HLG source is described as 1 by the app
    Context sdr = ctx; sdr.hdr = false;
    c = good; c.colorMode = 0; CHECK(planFor({c}, sdr, a).usable);  // SDR into SDR
    c = good; c.colorMode = 1; CHECK(!planFor({c}, sdr, a).usable); // HLG source in an SDR export is tone mapped: never
    c = good; c.colorMode = 3; CHECK(!planFor({c}, sdr, a).usable);
    c = good; c.reverse = true; expectNo(c, "reverse");
    c = good; c.sourceTable = std::vector<int64_t>(1200, 0); expectNo(c, "speed change or freeze");
    c = good; c.fx.blend = uv::core::BlendMode::Screen; expectNo(c, "blend mode");
    c = good; c.fx.mask.shape = 2; expectNo(c, "mask");
    c = good; c.fx.effects.push_back({}); expectNo(c, "effect");
    c = good; c.fxFrames.assign(1200, {}); expectNo(c, "animated effect parameters");
    c = good; c.keyframes.push_back({}); expectNo(c, "keyframes");
    c = good; c.titleKey = 7; expectNo(c, "title or still");
    c = good; c.assetKey = 3; expectNo(c, "unknown asset");

    // Asset-level refusals.
    CHECK(!planFor({good}, ctx, iphoneAsset(180, false)).usable);   // different codec/size/colour/fps
    CHECK(!planFor({good}, ctx, iphoneAsset(90)).usable);           // portrait
    CHECK(!planFor({good}, ctx, iphoneAsset(270)).usable);
    CHECK(!planFor({good}, ctx, iphoneAsset(-1)).usable);           // mirrored

    // Context-level refusals.
    Context x = ctx; x.hevc = false; CHECK(!planFor({good}, x, a).usable);
    x = ctx; x.outFps = {30, 1}; CHECK(!planFor({good}, x, a).usable);             // 60 -> 30
    x = ctx; x.projectFps = {30, 1}; CHECK(!planFor({good}, x, a).usable);         // 30 -> 60 duplication
    x = ctx; x.outFps = {60000, 1001}; CHECK(!planFor({good}, x, a).usable);
    x = ctx; x.outWidth = 1920; x.outHeight = 1080; CHECK(!planFor({good}, x, a).usable);  // scaled export
    x = ctx; x.canvasWidth = 1920; CHECK(!planFor({good}, x, a).usable);
    x = ctx; x.hdr = false; CHECK(!planFor({good}, x, a).usable);                  // an HLG source in an SDR export
}

void plannerLayers() {
    const AssetForSmart a = iphoneAsset();
    const Context ctx = hdrContext(1200);
    // A title over the first half: only the second half can be copied.
    VideoClip title;
    title.startFrame = 0; title.durationFrames = 600; title.titleKey = 9; title.layer = 0;
    const VideoClip base = plain(0, 1200, 0, 1, 1);
    const Plan p = planFor({title, base}, ctx, a);
    CHECK(p.usable);
    for (const CopySegment& s : p.segments) CHECK(s.outStart >= 600);
    // A fully opaque clip on top hides the one below: the lower clip is not needed, the top one is copied.
    const VideoClip top = plain(0, 1200, 0, 2, 0);
    AssetForSmart b = iphoneAsset(180);
    const Plan q = planFor({top, base}, ctx, a, &b);
    CHECK(q.usable);
    if (q.usable) CHECK_EQ(q.segments[0].assetKey, 2);
    // A half-transparent clip above makes the clip below visible together with it: nothing qualifies.
    VideoClip half = plain(0, 1200, 0, 2, 0);
    half.opacity = 0.5;
    CHECK(!planFor({half, base}, ctx, a, &b).usable);
    // A transition: the incoming clip fades in over the outgoing one for 30 frames; those frames are never copied.
    VideoClip out = plain(0, 630, 0, 1, 1);
    VideoClip in = plain(600, 600, 100, 2, 1);
    in.fadeInFrames = 30;
    const Plan t = planFor({out, in}, ctx, a, &b);
    for (const CopySegment& s : t.segments) {
        CHECK(!(s.outStart < 630 && s.outStart + s.frames > 600));
    }
    // A gap (black) between clips is not copied and does not merge them.
    const Plan g = planFor({plain(0, 300, 0, 1), plain(500, 700, 300, 1)}, ctx, a);
    CHECK(g.usable);
    for (const CopySegment& s : g.segments) CHECK(!(s.outStart < 500 && s.outStart + s.frames > 300));
}

void plannerAdjacentCuts() {
    // A cut that changes nothing (the next clip continues in the source at the same time) is one stretch.
    const AssetForSmart a = iphoneAsset();
    const Plan p = planFor({plain(0, 500, 60, 1), plain(500, 700, 560, 1)}, hdrContext(1200), a);
    CHECK(p.usable);
    CHECK_EQ(p.segments.size(), 1);
    // A jump back in the source is not.
    const Plan q = planFor({plain(0, 500, 60, 1), plain(500, 700, 1000, 1)}, hdrContext(1200), a);
    CHECK_EQ(q.segments.size(), 2);
}

void plannerRotationChoice() {
    // Two sources, 700 frames of the 180 degree one and 1000 of the upright one: the upright one wins, and the other is re-encoded.
    AssetForSmart upside = iphoneAsset(180);
    AssetForSmart upright;
    upright.ok = true;
    upright.rotation = 0;
    for (int64_t f = 0; f < 2400; ++f) upright.samples.push_back({f, f % 61 == 0 ? 19 : -1});
    const Plan p = planFor({plain(0, 760, 0, 1), plain(760, 1000, 0, 2)}, hdrContext(1760), upside, &upright);
    CHECK(p.usable);
    CHECK_EQ(p.rotation, 0);
    for (const CopySegment& s : p.segments) CHECK_EQ(s.assetKey, 2);
    // Equal amounts: stays upright (no rotation flag needed).
    const Plan q = planFor({plain(0, 1000, 0, 1)}, hdrContext(1000), iphoneAsset(0));
    CHECK(q.usable);
    CHECK_EQ(q.rotation, 0);
}

void plannerKeepsFirstFrameEncoded() {
    // A closed-GOP clip that starts the movie: the copy would start at frame 0, but the first frame is always encoded (the
    // encoder's format is needed before anything is written), so the copy starts at the next random access point.
    AssetForSmart a;
    a.ok = true;
    a.rotation = 0;
    for (int64_t f = 0; f < 600; ++f) a.samples.push_back({f, f % 60 == 0 ? 19 : -1});
    const Plan p = planFor({plain(0, 600, 0, 1)}, hdrContext(600), a);
    CHECK(p.usable);
    CHECK_EQ(p.segments.size(), 1);
    if (!p.segments.empty()) {
        CHECK_EQ(p.segments[0].outStart, 60);
        CHECK_EQ(p.segments[0].frames, 540);
    }
    CHECK(p.copiedFrames < 600);
    // Two clips: the second one is copied from its start, and something before it is always encoded.
    const Plan q = planFor({plain(0, 600, 0, 1), plain(600, 600, 0, 2)}, hdrContext(1200), a, &a);
    CHECK(q.usable);
    CHECK_EQ(q.segments.size(), 2);
    if (q.segments.size() == 2) CHECK_EQ(q.segments[1].outStart, 600);
}

void plannerThresholds() {
    const AssetForSmart a = iphoneAsset();
    // Footage with no whole GOP inside it, or too little overall, is refused (the caller exports normally).
    CHECK(!planFor({plain(0, 100, 61, 1)}, hdrContext(100), a).usable);
    CHECK(!planFor({plain(0, 150, 0, 1)}, hdrContext(150), a).usable);  // 147 frames of whole GOPs but under minTotalFrames? 147 >= 120
}

// ---------------------------------------------------------------- writer

struct TempFile {
    int fd = -1;
    std::string path;
    TempFile() {
        char name[] = "/tmp/uv-smart-XXXXXX";
        fd = ::mkstemp(name);
        path = name;
    }
    ~TempFile() {
        if (fd >= 0) ::close(fd);
        ::unlink(path.c_str());
    }
    std::vector<uint8_t> bytes() const {
        std::vector<uint8_t> out(static_cast<size_t>(::lseek(fd, 0, SEEK_END)));
        if (::pread(fd, out.data(), out.size(), 0) != static_cast<ssize_t>(out.size())) out.clear();
        return out;
    }
};

size_t count(const std::vector<uint8_t>& b, const char* needle) {
    size_t n = 0;
    for (size_t i = 0; i + 4 <= b.size(); ++i) n += std::memcmp(b.data() + i, needle, 4) == 0;
    return n;
}

void writerRoundTrip() {
    TempFile f;
    CHECK(f.fd >= 0);
    Mp4Writer::VideoConfig v;
    v.width = 3840;
    v.height = 2160;
    v.fps = {60, 1};
    v.rotationDegrees = 180;
    nal::ParameterSets ps;
    for (const char* h : {"40010c02ffff222000000300b00000030000030099000015c090", kIphoneSps, "4401c02d9219853240"}) {
        const auto b = hex(h);
        nal::addTo(&ps, b.data(), b.size());
    }
    const auto sps = nal::parseSps(ps.sps[0].data(), ps.sps[0].size());
    Mp4Writer::VideoEntry e;
    e.hvcc = nal::buildHvcc(ps, *sps);
    e.colr = {'n', 'c', 'l', 'x', 0, 9, 0, 18, 0, 9, 0};
    v.entries = {e, e};
    v.entries[1].hvcc = nal::buildHvcc(ps, *sps);
    Mp4Writer::AudioConfig a;
    a.audioSpecificConfig = {0x11, 0x90};
    a.bitrate = 192000;
    {
        Mp4Writer w(f.fd, v, &a);
        // Decode order with B-frame style reordering: presentation 0 2 1 3 5 4 ... and a sync sample at the start of each run.
        const int64_t order[] = {0, 2, 1, 3, 5, 4, 6, 8, 7, 9};
        for (int i = 0; i < 10; ++i) {
            std::vector<uint8_t> data(100 + static_cast<size_t>(i), static_cast<uint8_t>(i));
            CHECK(w.addVideoSample(data.data(), data.size(), i < 5 ? 0 : 1, order[i], i == 0 || i == 6));
            if (i % 3 == 0) {
                std::vector<uint8_t> au(30, 0xA0);
                CHECK(w.addAudioSample(au.data(), au.size()));
            }
        }
        CHECK(w.finish());
        std::string why;
        CHECK(w.verify(2, &why));
        // A flipped byte in the first sample (the first sample is always checked) is found.
        uint8_t b = 0xFF;
        CHECK(::pwrite(f.fd, &b, 1, 44 + 50) == 1);
        CHECK(!w.verify(2, &why));
        CHECK(why.find("video sample 0") != std::string::npos);
        b = 0;
        CHECK(::pwrite(f.fd, &b, 1, 44 + 50) == 1);
        CHECK(w.verify(2, &why));
    }
    const auto bytes = f.bytes();
    CHECK_EQ(count(bytes, "moov"), 1);
    CHECK_EQ(count(bytes, "hvc1"), 2);
    CHECK_EQ(count(bytes, "mp4a"), 1);
    CHECK_EQ(count(bytes, "co64"), 2);
    CHECK_EQ(count(bytes, "colr"), 2);
    CHECK_EQ(count(bytes, "elst"), 1);
    CHECK(std::memcmp(bytes.data() + 4, "ftyp", 4) == 0);
    CHECK(std::memcmp(bytes.data() + 32, "mdat", 4) == 0);
    // The mdat size covers the samples exactly: moov starts right after.
    uint64_t mdat = 0;
    for (int i = 0; i < 8; ++i) mdat = (mdat << 8) | bytes[36 + static_cast<size_t>(i)];
    CHECK(std::memcmp(bytes.data() + 28 + mdat + 4, "moov", 4) == 0);

    // Read it back through the source reader after merging to a single entry: write a one-entry file with the same samples.
    TempFile g;
    Mp4Writer::VideoConfig v1 = v;
    v1.entries.resize(1);
    {
        Mp4Writer w(g.fd, v1, nullptr);
        const int64_t order[] = {0, 2, 1, 3, 5, 4, 6, 8, 7, 9};
        for (int i = 0; i < 10; ++i) {
            std::vector<uint8_t> data(100 + static_cast<size_t>(i), static_cast<uint8_t>(i));
            CHECK(w.addVideoSample(data.data(), data.size(), 0, order[i], i == 0 || i == 6));
        }
        CHECK(w.finish());
    }
    const auto file = g.bytes();
    std::string why;
    const auto track = readMp4VideoTrack(
        [&](uint64_t off, void* out, size_t n) {
            if (off + n > file.size()) return false;
            std::memcpy(out, file.data() + off, n);
            return true;
        },
        file.size(), &why);
    CHECK(track.has_value());
    if (track) {
        CHECK_EQ(track->samples.size(), 10);
        CHECK_EQ(track->rotationDegrees, 180);
        CHECK_EQ(track->width, 3840);
        CHECK(track->entryType == "hvc1");
        CHECK(track->hasNclx);
        CHECK_EQ(track->transfer, 18);
        // Frame i of presentation = (pts - editStart) / frameDuration; decode order sizes 100 + i.
        const int64_t order[] = {0, 2, 1, 3, 5, 4, 6, 8, 7, 9};
        const int64_t fd = 167;  // 10020 / 60
        for (size_t i = 0; i < 10; ++i) {
            CHECK_EQ(track->samples[i].size, 100 + i);
            CHECK_EQ((track->samples[i].pts - track->editStart) / fd, order[i]);
            CHECK_EQ(track->samples[i].dts, static_cast<int64_t>(i) * fd);
            CHECK_EQ(track->samples[i].sync, i == 0 || i == 6);
            uint8_t first = 0;
            CHECK(file[track->samples[i].offset] == i);
            (void)first;
        }
        const auto cfg = nal::parseHvcc(track->codecConfig.data(), track->codecConfig.size());
        CHECK(cfg.has_value() && cfg->sets == ps);
    }
}

// A one-entry MP4 with fake IDR samples, written by our writer, as bytes.
std::vector<uint8_t> tinyFile(const std::vector<std::string>& sets, int w, int h, std::vector<uint8_t> colr, int rotation = 0) {
    TempFile f;
    nal::ParameterSets ps;
    for (const auto& hexs : sets) {
        const auto b = hex(hexs.c_str());
        nal::addTo(&ps, b.data(), b.size());
    }
    const auto sps = nal::parseSps(ps.sps[0].data(), ps.sps[0].size());
    Mp4Writer::VideoConfig v;
    v.width = w;
    v.height = h;
    v.fps = {60, 1};
    v.rotationDegrees = rotation;
    Mp4Writer::VideoEntry e;
    e.hvcc = nal::buildHvcc(ps, *sps);
    e.colr = std::move(colr);
    v.entries = {e};
    Mp4Writer writer(f.fd, v, nullptr);
    for (int i = 0; i < 4; ++i) {
        std::vector<uint8_t> sample = sampleOf({fakeNal(i == 0 ? 20 : 1, 30)});
        writer.addVideoSample(sample.data(), sample.size(), 0, i, i == 0);
    }
    writer.finish();
    return f.bytes();
}

smart::InspectedAsset inspect(const std::vector<uint8_t>& file, int w, int h, bool hdr) {
    smart::OutputSpec spec;
    spec.width = w;
    spec.height = h;
    spec.fps = {60, 1};
    spec.hdr = hdr;
    return smart::inspectAsset(
        [&](uint64_t off, void* out, size_t n) {
            if (off + n > file.size()) return false;
            std::memcpy(out, file.data() + off, n);
            return true;
        },
        file.size(), spec);
}

void assetInspection() {
    const std::vector<std::string> hlgSets = {"40010c02ffff222000000300b00000030000030099000015c090", kIphoneSps, "4401c02d9219853240"};
    const std::vector<std::string> sdrSets = {"40010c01ffff01600000030090000003000003003f959809",
                                              "42010101600000030090000003000003003fa00502016965959a4932bc05a810100820000003002000000303c1", "4401c172b46240"};
    const std::vector<uint8_t> hlgColr = {'n', 'c', 'l', 'x', 0, 9, 0, 18, 0, 9, 0};
    const std::vector<uint8_t> sdrColr = {'n', 'c', 'l', 'x', 0, 1, 0, 1, 0, 1, 0};
    const std::vector<uint8_t> sdrQt = {'n', 'c', 'l', 'c', 0, 1, 0, 1, 0, 1};

    // HLG 10-bit footage: copyable into an HDR export of its size, never into an SDR one (tone mapping).
    const auto hlg = tinyFile(hlgSets, 3840, 2160, hlgColr, 180);
    const auto okHlg = inspect(hlg, 3840, 2160, true);
    CHECK(okHlg.plan.ok);
    CHECK_EQ(okHlg.plan.rotation, 180);
    CHECK_EQ(okHlg.plan.samples.size(), 4);
    CHECK_EQ(okHlg.plan.samples[0].irapType, 20);
    CHECK_EQ(okHlg.plan.samples[3].pres, 3);
    CHECK(!inspect(hlg, 3840, 2160, false).plan.ok);   // SDR export: not copyable
    CHECK(!inspect(hlg, 1920, 1080, true).plan.ok);    // scaled export
    CHECK(!inspect(tinyFile(hlgSets, 3840, 2160, hlgColr, 90), 3840, 2160, true).plan.ok);  // portrait matrix
    CHECK(!inspect(tinyFile(hlgSets, 3840, 2160, {}), 3840, 2160, true).plan.ok);           // no colour description
    CHECK(!inspect(tinyFile(hlgSets, 3840, 2160, sdrColr), 3840, 2160, true).plan.ok);      // tagged BT.709

    // SDR 8-bit HEVC footage (the sizes here are 640x360): copyable into an SDR export only; an HLG export needs a conversion.
    const auto sdr = tinyFile(sdrSets, 640, 360, sdrColr);
    const auto okSdr = inspect(sdr, 640, 360, false);
    CHECK(okSdr.plan.ok);
    CHECK_EQ(okSdr.plan.rotation, 0);
    CHECK(!inspect(sdr, 640, 360, true).plan.ok);
    CHECK(!inspect(sdr, 1280, 720, false).plan.ok);
    CHECK(inspect(tinyFile(sdrSets, 640, 360, sdrQt), 640, 360, false).plan.ok);  // QuickTime's colr form 'nclc'
    CHECK(!inspect(tinyFile(sdrSets, 640, 360, hlgColr), 640, 360, false).plan.ok);
    // Planner: the SDR file is accepted in an SDR project and refused when the clip says HLG... and the other way round.
    VideoClip clip = plain(0, 600, 0, 1);
    clip.colorMode = 0;
    Context ctx = hdrContext(600);
    ctx.hdr = false;
    ctx.outWidth = ctx.canvasWidth = 640;
    ctx.outHeight = ctx.canvasHeight = 360;
    AssetForSmart synthetic;
    synthetic.ok = true;
    for (int64_t f = 0; f < 600; ++f) synthetic.samples.push_back({f, f % 60 == 0 ? 19 : -1});
    CHECK(planFor({clip}, ctx, synthetic).usable);
    ctx.hdr = true;
    CHECK(!planFor({clip}, ctx, synthetic).usable);  // an SDR clip in an HDR export is converted, never copied
}

void largeOffsets() {
    // A file that is past 4 GB when its samples are written: the chunk offsets and the mdat size need 64 bits.
    TempFile f;
    Mp4Writer::VideoConfig v;
    v.width = 640;
    v.height = 360;
    v.fps = {60, 1};
    nal::ParameterSets ps;
    for (const char* h : {"40010c01ffff01600000030090000003000003003f959809",
                          "42010101600000030090000003000003003fa00502016965959a4932bc05a810100820000003002000000303c1", "4401c172b46240"}) {
        const auto b = hex(h);
        nal::addTo(&ps, b.data(), b.size());
    }
    Mp4Writer::VideoEntry e;
    e.hvcc = nal::buildHvcc(ps, *nal::parseSps(ps.sps[0].data(), ps.sps[0].size()));
    v.entries = {e};
    const uint64_t skip = 5ull * 1024 * 1024 * 1024;
    {
        Mp4Writer w(f.fd, v, nullptr);
        CHECK(w.skipBytesForTest(skip));
        for (int i = 0; i < 3; ++i) {
            const auto sample = sampleOf({fakeNal(i == 0 ? 20 : 1, 40 + static_cast<size_t>(i))});
            CHECK(w.addVideoSample(sample.data(), sample.size(), 0, i, i == 0));
        }
        CHECK(w.finish());
        std::string why;
        CHECK(w.verify(1, &why));
        CHECK(w.bytesWritten() > static_cast<int64_t>(skip));
    }
    std::string why;
    const auto track = readMp4VideoTrack(
        [&](uint64_t off, void* out, size_t n) { return ::pread(f.fd, out, n, static_cast<off_t>(off)) == static_cast<ssize_t>(n); },
        static_cast<uint64_t>(::lseek(f.fd, 0, SEEK_END)), &why);
    CHECK(track.has_value());
    if (track) {
        CHECK_EQ(track->samples.size(), 3);
        CHECK(track->samples[0].offset > skip);
        CHECK(track->samples[1].offset == track->samples[0].offset + track->samples[0].size);
        uint8_t type = 0;
        CHECK(::pread(f.fd, &type, 1, static_cast<off_t>(track->samples[0].offset + 4)) == 1);
        CHECK_EQ(type, 20 << 1);  // the first NAL unit of the first sample is where the table says
    }
}

void sourceRefusals() {
    // Garbage and fragmented files are refused with a reason, never crash.
    std::vector<uint8_t> junk(64, 0xEE);
    std::string why;
    auto src = [&](uint64_t off, void* out, size_t n) {
        if (off + n > junk.size()) return false;
        std::memcpy(out, junk.data() + off, n);
        return true;
    };
    CHECK(!readMp4VideoTrack(src, junk.size(), &why).has_value());
    CHECK(!why.empty());
    junk = {0, 0, 0, 16, 'm', 'o', 'o', 'f', 0, 0, 0, 0, 0, 0, 0, 0};
    CHECK(!readMp4VideoTrack(src, junk.size(), &why).has_value());
    CHECK(why.find("fragmented") != std::string::npos);
    CHECK(!readMp4VideoTrack(src, 0, &why).has_value());
}

}  // namespace

int main() {
    spsParsing();
    hvccRoundTrip();
    annexBSplitting();
    craBecomesBla();
    presentationFrames();
    trimTable();
    trimSeekRule();
    trimClosedGop();
    trimBFrames();
    trimVfrHole();
    trimRejectsNonIrapStarts();
    plannerBasics();
    plannerDisqualifiers();
    plannerLayers();
    plannerAdjacentCuts();
    plannerRotationChoice();
    plannerKeepsFirstFrameEncoded();
    plannerThresholds();
    writerRoundTrip();
    assetInspection();
    largeOffsets();
    sourceRefusals();
    if (failures == 0) std::printf("smart export host tests passed\n");
    return failures == 0 ? 0 : 1;
}
