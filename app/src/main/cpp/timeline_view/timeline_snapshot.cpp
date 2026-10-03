#include "timeline_view/timeline_snapshot.h"

#include <algorithm>
#include <cstring>

namespace uv::timeline {

int64_t TimelineSnapshot::endFrame() const {
    int64_t end = 0;
    for (const auto& c : clips) end = std::max(end, c.startFrame + c.durationFrames);
    return end;
}

namespace {

class Reader {
public:
    Reader(const uint8_t* p, size_t n) : p_(p), n_(n) {}
    template <typename T>
    bool read(T* out) {
        if (n_ - off_ < sizeof(T)) return false;
        std::memcpy(out, p_ + off_, sizeof(T));  // host is little endian on all Android ABIs
        off_ += sizeof(T);
        return true;
    }
    bool atEnd() const { return off_ == n_; }
    size_t remaining() const { return n_ - off_; }

private:
    const uint8_t* p_;
    size_t n_;
    size_t off_ = 0;
};

}  // namespace

core::Status parseSnapshot(const uint8_t* data, size_t size, TimelineSnapshot* out) {
    using core::Status;
    if (data == nullptr || out == nullptr) return Status::InvalidArgument;
    Reader r(data, size);
    uint32_t magic = 0, version = 0;
    int32_t fpsNum = 0, fpsDen = 0, trackCount = 0, clipCount = 0;
    if (!r.read(&magic) || !r.read(&version) || !r.read(&fpsNum) || !r.read(&fpsDen) ||
        !r.read(&trackCount) || !r.read(&clipCount)) {
        return Status::BadSnapshot;
    }
    if (magic != kSnapshotMagic || version != kSnapshotVersion) return Status::BadSnapshot;
    if (fpsNum <= 0 || fpsDen <= 0 || trackCount < 0 || clipCount < 0) return Status::BadSnapshot;
    // Reject sizes that cannot fit in the buffer before allocating. The transition count follows
    // the clips, so here only the part up to it must fit.
    const size_t fixed = kSnapshotHeaderBytes + static_cast<size_t>(trackCount) * 4 +
                         static_cast<size_t>(clipCount) * kSnapshotClipBytes + 4;
    if (fixed > size) return Status::BadSnapshot;

    TimelineSnapshot snap;
    snap.fpsNum = fpsNum;
    snap.fpsDen = fpsDen;
    snap.tracks.reserve(trackCount);
    snap.clips.reserve(clipCount);
    for (int32_t i = 0; i < trackCount; ++i) {
        int32_t type = 0;
        if (!r.read(&type) || type < 0 || type > 2) return Status::BadSnapshot;
        snap.tracks.push_back({static_cast<TrackType>(type)});
    }
    for (int32_t i = 0; i < clipCount; ++i) {
        ClipSnapshot c{};
        int32_t flags = 0;
        if (!r.read(&c.clipKey) || !r.read(&c.trackIndex) || !r.read(&c.assetKey) ||
            !r.read(&c.startFrame) || !r.read(&c.durationFrames) || !r.read(&c.sourceInFrame) ||
            !r.read(&c.sourceFpsNum) || !r.read(&c.sourceFpsDen) || !r.read(&flags)) {
            return Status::BadSnapshot;
        }
        if (c.trackIndex < 0 || c.trackIndex >= trackCount || c.durationFrames <= 0 ||
            c.startFrame < 0 || c.sourceFpsNum <= 0 || c.sourceFpsDen <= 0) {
            return Status::BadSnapshot;
        }
        c.selected = (flags & 1) != 0;
        snap.clips.push_back(c);
    }
    int32_t transitionCount = 0;
    if (!r.read(&transitionCount) || transitionCount < 0) return Status::BadSnapshot;
    if (r.remaining() != static_cast<size_t>(transitionCount) * kSnapshotTransitionBytes) return Status::BadSnapshot;
    snap.transitions.reserve(static_cast<size_t>(transitionCount));
    for (int32_t i = 0; i < transitionCount; ++i) {
        TransitionSnapshot t{};
        int32_t reserved = 0;
        if (!r.read(&t.trackIndex) || !r.read(&reserved) || !r.read(&t.cutFrame) || !r.read(&t.preFrames) ||
            !r.read(&t.postFrames)) {
            return Status::BadSnapshot;
        }
        if (t.trackIndex < 0 || t.trackIndex >= trackCount || t.cutFrame < 0 || t.preFrames < 0 || t.postFrames < 0) {
            return Status::BadSnapshot;
        }
        snap.transitions.push_back(t);
    }
    if (!r.atEnd()) return Status::BadSnapshot;
    *out = std::move(snap);
    return Status::Ok;
}

}  // namespace uv::timeline
