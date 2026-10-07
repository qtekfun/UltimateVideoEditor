#pragma once

// Smart export planner (SPECS.md 5.10): decides which stretches of the movie are copied from a source file without
// decoding and re-encoding. Pure and host-testable: the clips come as the exporter's VideoClip list, the source files as
// per-sample facts. Any doubt means "not copied": the planner only ever says yes when every precondition is proven.

#include <algorithm>
#include <cstdint>
#include <functional>
#include <limits>
#include <optional>
#include <string>
#include <vector>

#include "encode/export_math.h"
#include "render/color_space.h"

namespace uv::encode::smart {

constexpr int64_t kNoFrame = std::numeric_limits<int64_t>::min();

// One sample of a source track in decode order. `pres` is the source frame it shows (kNoFrame when it has none), `irapType` the HEVC NAL type when it is a random access point (16..23), else -1.
struct GopSample {
    int64_t pres = kNoFrame;
    int irapType = -1;
};

// Source frame a sample is shown as: its presentation time rounded to the nearest frame of `fps`, as the decoders of the
// preview and the export do. Footage that is not on the grid (variable frame rate, another frame rate) gets frames that are
// skipped or shown twice; trimToGop then refuses the stretch, because a copy shows every frame exactly once.
inline int64_t presentationFrame(int64_t pts, int64_t editStart, uint32_t timescale, Fps fps) {
    const i128 p = static_cast<i128>(pts) - editStart;
    const i128 unit = static_cast<i128>(timescale) * fps.den;
    const i128 num = 2 * p * fps.num + unit;  // round half up: floor((2 p num + unit) / (2 unit))
    const i128 den = 2 * unit;
    i128 frame = num / den;
    if (num % den != 0 && num < 0) --frame;
    return static_cast<int64_t>(frame);
}

struct CopyRun {
    size_t firstSample = 0;  // decode order, [firstSample, endSample)
    size_t endSample = 0;
    int64_t presStart = 0;   // first source frame shown
    int64_t count = 0;       // frames shown; the run shows presStart .. presStart + count - 1
};

inline bool startsCleanly(int irapType) {
    // IDR (19, 20), CRA (21) and BLA (16..18) can start a copy: CRA and BLA lose their leading pictures, IDR_W_RADL too.
    return irapType >= 16 && irapType <= 21;
}

// The longest run that starts at a random access point and shows only source frames in [srcStart, srcEnd): the run starts at the
// access point with its leading pictures dropped (they need earlier pictures that are not copied) and ends after any sample in
// decode order, because every prefix of decode order only references pictures already in it. The frames shown must be consecutive
// source frames, each once. The frame after the run is encoded, and the decoder reaches it with a seek: that frame must not be a
// leading picture of a CRA (shown before it, decoded after it), because a seek to it lands on that CRA, whose leading pictures
// then cannot be decoded; such ends are skipped. Returns nullopt when no run of at least `minFrames` exists.
inline std::optional<CopyRun> trimToGop(const std::vector<GopSample>& s, int64_t srcStart, int64_t srcEnd, int64_t minFrames) {
    const size_t n = s.size();
    for (size_t a = 0; a < n; ++a) {
        if (!startsCleanly(s[a].irapType) || s[a].pres == kNoFrame || s[a].pres < srcStart || s[a].pres >= srcEnd) continue;
        const int64_t base = s[a].pres;
        const int64_t span = srcEnd - base;
        // Decode index of the sample that shows each frame of the stretch (and a few after it), for the seek rule.
        std::vector<int64_t> at(static_cast<size_t>(span) + 8, -1);
        for (size_t k = 0; k < n; ++k) {
            const int64_t off = s[k].pres == kNoFrame ? -1 : s[k].pres - base;
            if (off >= 0 && off < static_cast<int64_t>(at.size()) && at[static_cast<size_t>(off)] < 0) at[static_cast<size_t>(off)] = static_cast<int64_t>(k);
        }
        std::vector<bool> seen(static_cast<size_t>(span), false);
        int64_t count = 0, maxPres = base - 1;
        std::optional<CopyRun> best;
        for (size_t e = a; e < n; ++e) {
            const int64_t p = s[e].pres;
            if (p == kNoFrame) break;
            if (e > a && p < base) continue;  // a leading picture of the first access point: dropped
            if (p >= srcEnd || seen[static_cast<size_t>(p - base)]) break;
            seen[static_cast<size_t>(p - base)] = true;
            ++count;
            maxPres = std::max(maxPres, p);
            if (maxPres - base + 1 != count) continue;  // not every frame up to the latest is in yet
            // The run [a, e + 1) shows base .. maxPres. The next frame must not be a leading picture of an access point.
            const int64_t next = maxPres + 1;
            bool leading = false;
            if (next - base < static_cast<int64_t>(at.size()) && at[static_cast<size_t>(next - base)] >= 0) {
                const size_t k = static_cast<size_t>(at[static_cast<size_t>(next - base)]);
                for (size_t r = k; r-- > 0 && k - r <= 64;) {
                    if (s[r].irapType >= 16) {
                        leading = s[r].pres != kNoFrame && s[k].pres < s[r].pres;
                        break;
                    }
                }
            }
            if (!leading) best = CopyRun{a, e + 1, base, count};
        }
        if (best && best->count >= minFrames) return best;
        // This start gives too little: a later start inside the stretch gives at most the same run minus its head.
        if (best) return std::nullopt;
    }
    return std::nullopt;
}

struct AssetForSmart {
    bool ok = false;           // codec, size, bit depth, colour and container all match the output
    int rotation = 0;          // track matrix, degrees clockwise (0, 90, 180, 270; anything else is not ok)
    std::vector<GopSample> samples;
};
using AssetLookup = std::function<const AssetForSmart*(int64_t assetKey)>;

struct Context {
    int32_t outWidth = 0, outHeight = 0;
    int32_t canvasWidth = 0, canvasHeight = 0;
    Fps outFps, projectFps;
    bool hdr = false;
    bool hevc = false;
    int64_t totalFrames = 0;
    int64_t minCopyFrames = 30;     // shorter runs are not worth the two joins
    int64_t minTotalFrames = 120;   // under this much copied footage the normal export is used
};

struct CopySegment {
    int64_t outStart = 0;   // first output frame
    int64_t frames = 0;
    int64_t assetKey = 0;
    CopyRun run;
};

struct Plan {
    bool usable = false;
    int rotation = 0;  // orientation the file is stored in, degrees clockwise to display; everything re-encoded is pre-rotated
    std::vector<CopySegment> segments;
    int64_t copiedFrames = 0;
    std::string reason;  // why not usable, or a short summary
};

// The clip that alone decides the picture of `frame`, or nullptr: the topmost clip, a plain video layer that covers the canvas
// exactly (identity pose, opacity 1, no effect, mask or blend, no retiming) with nothing above it. Layers below it are hidden.
inline const VideoClip* soleCoveringClip(const std::vector<VideoClip>& clips, int64_t frame, bool hdr) {
    const std::vector<const VideoClip*> layers = layersAt(clips, frame);
    if (layers.empty()) return nullptr;
    const VideoClip* top = layers.back();
    // The clip's colour mode says what its source is, whatever the target; a source of the output's own transfer is sampled as is.
    if (top->titleKey != 0 || !top->sourceTable.empty() || top->reverse || !top->keyframes.empty() || !top->fxFrames.empty() ||
        !top->fx.neutral() ||
        render::sourceTransferOf(static_cast<render::ColorMode>(top->colorMode)) != (hdr ? render::SourceTransfer::Hlg : render::SourceTransfer::Sdr)) {
        return nullptr;
    }
    const core::Pose pose = poseAt(*top, frame);
    if (pose.posX != 0.0 || pose.posY != 0.0 || pose.scaleX != 1.0 || pose.scaleY != 1.0 || pose.rotationDeg != 0.0) return nullptr;
    if (opacityAt(*top, frame) != 1.0) return nullptr;
    return top;
}

inline Plan planSmartExport(const std::vector<VideoClip>& clips, const Context& ctx, const AssetLookup& lookup) {
    Plan plan;
    auto refuse = [&](const char* why) {
        plan.reason = why;
        return plan;
    };
    if (!ctx.hevc) return refuse("smart export needs an HEVC export");
    if (ctx.outFps.num != ctx.projectFps.num || ctx.outFps.den != ctx.projectFps.den) return refuse("the export frame rate differs from the project's");
    if (ctx.outWidth != ctx.canvasWidth || ctx.outHeight != ctx.canvasHeight) return refuse("the export size differs from the project's");
    if (ctx.totalFrames <= 0) return refuse("empty movie");

    struct Stretch {
        int64_t start, frames, source;
        const VideoClip* clip;
    };
    std::vector<Stretch> stretches;
    bool open = false;  // the previous frame belongs to stretches.back()
    for (int64_t f = 0; f < ctx.totalFrames; ++f) {
        const VideoClip* c = soleCoveringClip(clips, f, ctx.hdr);
        const int64_t src = c != nullptr ? c->sourceInFrame + (f - c->startFrame) : 0;
        // Two clips of one file that follow each other in time and in the source are one stretch (a cut that changes nothing).
        if (c != nullptr && open && c->assetKey == stretches.back().clip->assetKey && src == stretches.back().source + stretches.back().frames) {
            ++stretches.back().frames;
            continue;
        }
        open = c != nullptr;
        if (c != nullptr) stretches.push_back({f, 1, src, c});
    }

    struct Choice {
        int rotation;
        std::vector<CopySegment> segments;
        int64_t frames = 0;
    };
    std::vector<Choice> choices = {{0, {}, 0}, {180, {}, 0}};
    for (const Stretch& s : stretches) {
        const AssetForSmart* asset = lookup(s.clip->assetKey);
        if (asset == nullptr || !asset->ok || (asset->rotation != 0 && asset->rotation != 180)) continue;
        auto run = trimToGop(asset->samples, s.source, s.source + s.frames, ctx.minCopyFrames);
        // The movie's first frame is always encoded: the encoder's output format (parameter sets) is needed before anything
        // can be written. A copy that would start at frame 0 starts at the next random access point instead.
        if (run && s.start + (run->presStart - s.source) == 0) run = trimToGop(asset->samples, run->presStart + 1, s.source + s.frames, ctx.minCopyFrames);
        if (!run) continue;
        for (Choice& ch : choices) {
            if (ch.rotation != asset->rotation) continue;
            ch.segments.push_back({s.start + (run->presStart - s.source), run->count, s.clip->assetKey, *run});
            ch.frames += run->count;
        }
    }
    const Choice& best = choices[1].frames > choices[0].frames ? choices[1] : choices[0];
    std::vector<CopySegment> segments = best.segments;
    int64_t copied = best.frames;
    // The encoder always delivers at least one frame (its stream needs its own parameter sets): when the whole movie could be
    // copied, the last stretch is encoded.
    if (copied >= ctx.totalFrames && !segments.empty()) {
        copied -= segments.back().frames;
        segments.pop_back();
    }
    if (copied < ctx.minTotalFrames) return refuse("not enough untouched footage to copy");
    plan.usable = true;
    plan.rotation = best.rotation;
    plan.segments = segments;
    plan.copiedFrames = copied;
    plan.reason = "ok";
    return plan;
}

}  // namespace uv::encode::smart
