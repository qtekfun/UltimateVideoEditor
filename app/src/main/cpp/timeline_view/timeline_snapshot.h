#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "core/error.h"
#include "timeline_view/audio_shaping.h"

namespace uv::timeline {

enum class TrackType : int32_t { Video = 0, Audio = 1, Title = 2 };

// Wire word of a track: bits 0..7 the type, bit 8 muted, bit 9 solo (audio lanes; bits 10 and up are ignored).
constexpr int32_t kTrackTypeMask = 0xFF;
constexpr int32_t kTrackMutedBit = 1 << 8;
constexpr int32_t kTrackSoloBit = 1 << 9;

struct TrackSnapshot {
    TrackType type;
    bool muted = false;
    bool solo = false;
};

// What a block is, for its colour (version 8; older snapshots are all Default, which follows the lane type).
enum class ClipKind : int32_t { Default = 0, Image = 1, Sticker = 2, Multicam = 3 };
constexpr int32_t kClipKindShift = 4;
constexpr int32_t kClipKindMask = 7;
// Further per-clip flag bits, spare before and read from any version (an older snapshot has them zero): bit 7 = the clip's
// embedded audio is detached (a video clip then draws no waveform), bit 8 = the clip is linked to another (a small link mark).
constexpr int32_t kClipAudioDetachedBit = 1 << 7;
constexpr int32_t kClipLinkedBit = 1 << 8;

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
    bool missing = false;  // its media file cannot be read; the canvas tints it (flags bit2)
    // The clip the inspector edits (flags bit3, version 6). With several clips selected it is outlined
    // brighter than the rest; before version 6 a selected clip is its own primary.
    bool primary = false;
    ClipKind kind = ClipKind::Default;
    bool audioDetached = false;  // flags bit7: its own sound was detached, so the block shows no waveform
    bool linked = false;         // flags bit8: linked to another clip (edits apply to both)
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

// A ruler marker at timeline frame `frame`. flags: bit0 = detected beat (otherwise placed by the user).
struct MarkerSnapshot {
    int64_t frame;
    int32_t flags;
    // Colour code and note bit, see marker_style.h; 0 in snapshots written before markers were coloured.
    int32_t extra = 0;

    bool beat() const { return (flags & 1) != 0; }
};

// A short text drawn on the clip block with key `clipKey` (a clip's name, the text of a title, the name of a sticker or
// of a marker): ASCII letters, digits and a few signs, at most kSnapshotMaxLabelBytes long in version 7; UTF-8 text,
// at most kSnapshotMaxLabelBytesUtf8 bytes, from version 8.
struct LabelSnapshot {
    int64_t clipKey;
    std::string text;
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
    // Sorted by frame (version 5; empty before).
    std::vector<MarkerSnapshot> markers;
    // Sorted by clipKey (version 7; empty before).
    std::vector<LabelSnapshot> labels;
    // Sound shaping (fades and volume curve) of the clips that show some, sorted by clipKey (version 9; empty before).
    std::vector<ShapingSnapshot> shaping;

    int64_t endFrame() const;
    // The sound shaping of one clip, or null when it shows none.
    const ShapingSnapshot* shapingOf(int64_t clipKey) const;
    // The label of one clip, or null when it has none.
    const std::string* labelOf(int64_t clipKey) const;
    // The retime of one clip, or null when it plays at 1x forward.
    const RetimeSnapshot* retimeOf(int64_t clipKey) const;
    // The markers of one clip, as a [first, last) range into `keyframes`.
    std::pair<const KeyframeSnapshot*, const KeyframeSnapshot*> keyframesOf(int64_t clipKey) const;
};

// Wire layout (little endian), version 9 (version 8 plus a shaping trailer after the labels: i32 count, then per clip
//   i64 clipKey, i32 fadeInFrames, i32 fadeOutFrames, i32 flags (bits 0..1 fade shape, bit 2 editable), i32 pointCount,
//   f32 baseDb, i32 reserved (32 bytes), then pointCount x (i64 frame, f32 db, i32 reserved) (16 bytes each)),
// version 8 (the same layout as version 7; the per-clip flags gain bits 4..6 = ClipKind (bit 7 = audio detached and bit 8 =
// linked came later without a version bump: the bits were free and are read as zero from older data), and a
// label is UTF-8 up to 96 bytes), version 7 (version 6 plus a label trailer after the markers), version 6 (the same layout as version 5; the per-clip flags gain bit3 =
// primary selection, and a version 5 clip is primary when it is selected). Version 5 (version 4 is the same without the marker trailer, version 3
// also without the retime trailer, version 2 also without the keyframe trailer):
//   header: u32 magic 'UVTS', u32 version, i32 fpsNum, i32 fpsDen, i32 trackCount, i32 clipCount
//   tracks: i32 * trackCount: type in bits 0..7, bit8 = muted, bit9 = solo (the high bits were zero before lane headers)
//   clips : i64 clipKey, i32 trackIndex, i64 assetKey, i64 start, i64 duration, i64 sourceIn,
//           i32 srcFpsNum, i32 srcFpsDen, i32 flags(bit0=selected, bit1=hasFx, bit2=missing, bit3=primary)   (56 bytes each)
//   trailer: i32 transitionCount, then per transition:
//           i32 trackIndex, i32 reserved, i64 cutFrame, i64 preFrames, i64 postFrames   (32 bytes each)
//   keyframes (v3): i32 keyframeCount, then per keyframe: i64 clipKey, i64 frame           (16 bytes each)
//   retimes (v4): i32 retimeCount, then per retimed clip:
//           i64 clipKey, i64 sourceSpanFrames, i32 flags(bit0=reverse, bit1=freeze), i32 reserved   (24 bytes each)
//   markers (v5): i32 markerCount, then per marker: i64 frame, i32 flags(bit0=beat), i32 extra
//           (bits0..2 colour code 0..6, bit3 has note; was reserved zero, see marker_style.h)   (16 bytes each)
//   labels (v7): i32 labelCount, then per label: i64 clipKey, i32 length (0..24), then the ASCII bytes padded with
//           zeros to a multiple of 4
constexpr uint32_t kSnapshotMagic = 0x53545655;  // "UVTS"
constexpr uint32_t kSnapshotVersion = 9;
constexpr size_t kSnapshotShapingBytes = 32;
constexpr size_t kSnapshotShapingPointBytes = 16;
constexpr int32_t kSnapshotMaxShapingPoints = 4096;
constexpr int32_t kSnapshotMaxLabelBytes = 24;
constexpr int32_t kSnapshotMaxLabelBytesUtf8 = 96;
constexpr uint32_t kSnapshotMinVersion = 2;
constexpr size_t kSnapshotHeaderBytes = 24;
constexpr size_t kSnapshotClipBytes = 56;
constexpr size_t kSnapshotTransitionBytes = 32;
constexpr size_t kSnapshotKeyframeBytes = 16;
constexpr size_t kSnapshotRetimeBytes = 24;
constexpr size_t kSnapshotMarkerBytes = 16;

core::Status parseSnapshot(const uint8_t* data, size_t size, TimelineSnapshot* out);

}  // namespace uv::timeline
