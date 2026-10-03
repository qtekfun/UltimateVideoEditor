#pragma once

#include <cstddef>
#include <cstdint>
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
};

// A transition across the cut at `cutFrame`, shown from `cutFrame - preFrames` to
// `cutFrame + postFrames` on one track.
struct TransitionSnapshot {
    int32_t trackIndex;
    int64_t cutFrame;
    int64_t preFrames;
    int64_t postFrames;
};

// Immutable view of the timeline handed from Kotlin. All time values are integer frames.
struct TimelineSnapshot {
    int32_t fpsNum = 30;
    int32_t fpsDen = 1;
    std::vector<TrackSnapshot> tracks;
    std::vector<ClipSnapshot> clips;
    std::vector<TransitionSnapshot> transitions;

    int64_t endFrame() const;
};

// Wire layout (little endian), version 2:
//   header: u32 magic 'UVTS', u32 version, i32 fpsNum, i32 fpsDen, i32 trackCount, i32 clipCount
//   tracks: i32 type * trackCount
//   clips : i64 clipKey, i32 trackIndex, i64 assetKey, i64 start, i64 duration, i64 sourceIn,
//           i32 srcFpsNum, i32 srcFpsDen, i32 flags(bit0=selected)   (56 bytes each)
//   trailer: i32 transitionCount, then per transition:
//           i32 trackIndex, i32 reserved, i64 cutFrame, i64 preFrames, i64 postFrames   (32 bytes each)
constexpr uint32_t kSnapshotMagic = 0x53545655;  // "UVTS"
constexpr uint32_t kSnapshotVersion = 2;
constexpr size_t kSnapshotHeaderBytes = 24;
constexpr size_t kSnapshotClipBytes = 56;
constexpr size_t kSnapshotTransitionBytes = 32;

core::Status parseSnapshot(const uint8_t* data, size_t size, TimelineSnapshot* out);

}  // namespace uv::timeline
