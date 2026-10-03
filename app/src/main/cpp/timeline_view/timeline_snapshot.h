#pragma once

#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

#include "core/error.h"

namespace uv::timeline {

enum class TrackType : int32_t { Video = 0, Audio = 1, Title = 2 };

struct TrackSnapshot {
    TrackType type;
};

struct ClipSnapshot {
    int64_t clipKey;
    int32_t trackIndex;
    int64_t assetKey;  // -1 when the clip has no media (titles)
    int64_t startFrame;
    int64_t durationFrames;
    int64_t sourceInFrame;
    int32_t sourceFpsNum;
    int32_t sourceFpsDen;
    bool selected;
    bool hasFx = false;  // the clip carries effects, a blend mode or a mask (flags bit1)
};

// A transition across the cut at `cutFrame`, shown from `cutFrame - preFrames` to
// `cutFrame + postFrames` on one track.
struct TransitionSnapshot {
    int32_t trackIndex;
    int64_t cutFrame;
    int64_t preFrames;
    int64_t postFrames;
};

// A keyframe marker `frame` frames after the start of the clip with key `clipKey`.
struct KeyframeSnapshot {
    int64_t clipKey;
    int64_t frame;
};

// A clip that is not a plain 1x forward span (speed change, ramp, reverse or freeze frame). The clip
// uses `sourceSpanFrames` source frames from its sourceIn, spread over its `durationFrames` (speed =
// span / duration); a ramp is drawn as that average speed. flags: bit0 = plays backwards, bit1 = freeze.
struct RetimeSnapshot {
    int64_t clipKey;
    int64_t sourceSpanFrames;
    int32_t flags;

    bool reverse() const { return (flags & 1) != 0; }
    bool freeze() const { return (flags & 2) != 0; }
};

// Source frame offset (from sourceIn) of timeline frame `local` of a clip `duration` frames long; the
// boundary of frame `local`, so `retimeBoundary(c, 0) .. retimeBoundary(c, duration)` is the whole span.
// A null `retime` is 1x forward. A reversed clip's offsets fall from the end of its range.
inline int64_t retimeBoundary(const RetimeSnapshot* retime, int64_t duration, int64_t local) {
    if (retime == nullptr || duration <= 0) return local;
    if (retime->freeze()) return 0;
    const int64_t forward = static_cast<int64_t>(static_cast<__int128>(local) * retime->sourceSpanFrames / duration);
    return retime->reverse() ? retime->sourceSpanFrames - forward : forward;
}

// Immutable view of the timeline handed from Kotlin. All time values are integer frames.
struct TimelineSnapshot {
    int32_t fpsNum = 30;
    int32_t fpsDen = 1;
    std::vector<TrackSnapshot> tracks;
    std::vector<ClipSnapshot> clips;
    std::vector<TransitionSnapshot> transitions;
    // Sorted by clipKey then frame, so a clip's markers are one contiguous run (see keyframesOf).
    std::vector<KeyframeSnapshot> keyframes;
    // Retimed clips only, sorted by clipKey (see retimeOf).
    std::vector<RetimeSnapshot> retimes;

    int64_t endFrame() const;
    // The retime of one clip, or null when it plays at 1x forward.
    const RetimeSnapshot* retimeOf(int64_t clipKey) const;
    // The markers of one clip, as a [first, last) range into `keyframes`.
    std::pair<const KeyframeSnapshot*, const KeyframeSnapshot*> keyframesOf(int64_t clipKey) const;
};

// Wire layout (little endian), version 4 (version 3 is the same without the retime trailer, version 2
// also without the keyframe trailer):
//   header: u32 magic 'UVTS', u32 version, i32 fpsNum, i32 fpsDen, i32 trackCount, i32 clipCount
//   tracks: i32 type * trackCount
//   clips : i64 clipKey, i32 trackIndex, i64 assetKey, i64 start, i64 duration, i64 sourceIn,
//           i32 srcFpsNum, i32 srcFpsDen, i32 flags(bit0=selected, bit1=hasFx)   (56 bytes each)
//   trailer: i32 transitionCount, then per transition:
//           i32 trackIndex, i32 reserved, i64 cutFrame, i64 preFrames, i64 postFrames   (32 bytes each)
//   keyframes (v3): i32 keyframeCount, then per keyframe: i64 clipKey, i64 frame           (16 bytes each)
//   retimes (v4): i32 retimeCount, then per retimed clip:
//           i64 clipKey, i64 sourceSpanFrames, i32 flags(bit0=reverse, bit1=freeze), i32 reserved   (24 bytes each)
constexpr uint32_t kSnapshotMagic = 0x53545655;  // "UVTS"
constexpr uint32_t kSnapshotVersion = 4;
constexpr uint32_t kSnapshotMinVersion = 2;
constexpr size_t kSnapshotHeaderBytes = 24;
constexpr size_t kSnapshotClipBytes = 56;
constexpr size_t kSnapshotTransitionBytes = 32;
constexpr size_t kSnapshotKeyframeBytes = 16;
constexpr size_t kSnapshotRetimeBytes = 24;

core::Status parseSnapshot(const uint8_t* data, size_t size, TimelineSnapshot* out);

}  // namespace uv::timeline
