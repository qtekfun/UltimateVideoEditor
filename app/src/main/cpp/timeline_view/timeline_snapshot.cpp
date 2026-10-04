#include "timeline_view/timeline_snapshot.h"

#include "timeline_view/text_atlas.h"

#include <algorithm>
#include <cstring>

namespace uv::timeline {

int64_t TimelineSnapshot::endFrame() const {
    int64_t end = 0;
    for (const auto& c : clips) end = std::max(end, c.startFrame + c.durationFrames);
    return end;
}

std::pair<const KeyframeSnapshot*, const KeyframeSnapshot*> TimelineSnapshot::keyframesOf(int64_t clipKey) const {
    const auto range = std::equal_range(
        keyframes.begin(), keyframes.end(), KeyframeSnapshot{clipKey, 0},
        [](const KeyframeSnapshot& a, const KeyframeSnapshot& b) { return a.clipKey < b.clipKey; });
    const KeyframeSnapshot* base = keyframes.data();
    return {base + (range.first - keyframes.begin()), base + (range.second - keyframes.begin())};
}

const RetimeSnapshot* TimelineSnapshot::retimeOf(int64_t clipKey) const {
    const auto it = std::lower_bound(retimes.begin(), retimes.end(), clipKey,
                                     [](const RetimeSnapshot& a, int64_t key) { return a.clipKey < key; });
    return it != retimes.end() && it->clipKey == clipKey ? &*it : nullptr;
}

const std::string* TimelineSnapshot::labelOf(int64_t clipKey) const {
    const auto it = std::lower_bound(labels.begin(), labels.end(), clipKey,
                                     [](const LabelSnapshot& a, int64_t key) { return a.clipKey < key; });
    return it != labels.end() && it->clipKey == clipKey ? &it->text : nullptr;
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
    bool readBytes(char* out, size_t count) {
        if (n_ - off_ < count) return false;
        std::memcpy(out, p_ + off_, count);
        off_ += count;
        return true;
    }
    bool skip(size_t count) {
        if (n_ - off_ < count) return false;
        off_ += count;
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
    if (magic != kSnapshotMagic || version < kSnapshotMinVersion || version > kSnapshotVersion) return Status::BadSnapshot;
    const bool hasKeyframes = version >= 3;
    const bool hasRetimes = version >= 4;
    const bool hasMarkers = version >= 5;
    const bool hasLabels = version >= 7;
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
        int32_t word = 0;
        if (!r.read(&word) || word < 0) return Status::BadSnapshot;
        const int32_t type = word & kTrackTypeMask;
        if (type > 2) return Status::BadSnapshot;
        TrackSnapshot track{static_cast<TrackType>(type)};
        track.muted = (word & kTrackMutedBit) != 0;
        track.solo = (word & kTrackSoloBit) != 0;
        snap.tracks.push_back(track);
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
        c.hasFx = (flags & 2) != 0;
        c.missing = (flags & 4) != 0;
        c.primary = version >= 6 ? (flags & 8) != 0 : c.selected;
        // Version 8: bits 4..6 say what the block is, for its colour; an unknown value reads as "by lane type".
        if (version >= 8) {
            const int kind = (flags >> kClipKindShift) & kClipKindMask;
            c.kind = kind <= static_cast<int>(ClipKind::Multicam) ? static_cast<ClipKind>(kind) : ClipKind::Default;
        }
        snap.clips.push_back(c);
    }
    int32_t transitionCount = 0;
    if (!r.read(&transitionCount) || transitionCount < 0) return Status::BadSnapshot;
    // Version 3 has the keyframe count after the transitions; at least the transitions and it must fit.
    const size_t transitionBytes = static_cast<size_t>(transitionCount) * kSnapshotTransitionBytes;
    if (hasKeyframes ? r.remaining() < transitionBytes + 4 : r.remaining() != transitionBytes) return Status::BadSnapshot;
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
    if (hasKeyframes) {
        int32_t keyframeCount = 0;
        if (!r.read(&keyframeCount) || keyframeCount < 0) return Status::BadSnapshot;
        const size_t keyframeBytes = static_cast<size_t>(keyframeCount) * kSnapshotKeyframeBytes;
        if (hasRetimes ? r.remaining() < keyframeBytes + 4 : r.remaining() != keyframeBytes) return Status::BadSnapshot;
        snap.keyframes.reserve(static_cast<size_t>(keyframeCount));
        for (int32_t i = 0; i < keyframeCount; ++i) {
            KeyframeSnapshot k{};
            if (!r.read(&k.clipKey) || !r.read(&k.frame) || k.frame < 0) return Status::BadSnapshot;
            snap.keyframes.push_back(k);
        }
        std::sort(snap.keyframes.begin(), snap.keyframes.end(), [](const KeyframeSnapshot& a, const KeyframeSnapshot& b) {
            return a.clipKey != b.clipKey ? a.clipKey < b.clipKey : a.frame < b.frame;
        });
    }
    if (hasRetimes) {
        int32_t retimeCount = 0;
        if (!r.read(&retimeCount) || retimeCount < 0) return Status::BadSnapshot;
        const size_t retimeBytes = static_cast<size_t>(retimeCount) * kSnapshotRetimeBytes;
        // Version 5 has the marker count after the retimes; at least the retimes and it must fit.
        if (hasMarkers ? r.remaining() < retimeBytes + 4 : r.remaining() != retimeBytes) return Status::BadSnapshot;
        snap.retimes.reserve(static_cast<size_t>(retimeCount));
        for (int32_t i = 0; i < retimeCount; ++i) {
            RetimeSnapshot t{};
            int32_t reserved = 0;
            if (!r.read(&t.clipKey) || !r.read(&t.sourceSpanFrames) || !r.read(&t.flags) || !r.read(&reserved) ||
                t.sourceSpanFrames < 1) {
                return Status::BadSnapshot;
            }
            snap.retimes.push_back(t);
        }
        std::sort(snap.retimes.begin(), snap.retimes.end(),
                  [](const RetimeSnapshot& a, const RetimeSnapshot& b) { return a.clipKey < b.clipKey; });
    }
    if (hasMarkers) {
        int32_t markerCount = 0;
        if (!r.read(&markerCount) || markerCount < 0) return Status::BadSnapshot;
        const size_t markerBytes = static_cast<size_t>(markerCount) * kSnapshotMarkerBytes;
        // Version 7 has the label count after the markers; at least the markers and it must fit.
        if (hasLabels ? r.remaining() < markerBytes + 4 : r.remaining() != markerBytes) return Status::BadSnapshot;
        snap.markers.reserve(static_cast<size_t>(markerCount));
        for (int32_t i = 0; i < markerCount; ++i) {
            MarkerSnapshot m{};
            if (!r.read(&m.frame) || !r.read(&m.flags) || !r.read(&m.extra) || m.frame < 0) return Status::BadSnapshot;
            snap.markers.push_back(m);
        }
        std::sort(snap.markers.begin(), snap.markers.end(),
                  [](const MarkerSnapshot& a, const MarkerSnapshot& b) { return a.frame < b.frame; });
    }
    if (hasLabels) {
        int32_t labelCount = 0;
        if (!r.read(&labelCount) || labelCount < 0) return Status::BadSnapshot;
        // Every label takes at least 12 bytes, so a count the rest of the buffer cannot hold is rejected up front.
        if (static_cast<size_t>(labelCount) > r.remaining() / 12) return Status::BadSnapshot;
        snap.labels.reserve(static_cast<size_t>(labelCount));
        for (int32_t i = 0; i < labelCount; ++i) {
            LabelSnapshot label{};
            int32_t length = 0;
            // Up to version 7 a label is a few ASCII bytes; from version 8 it is UTF-8 (accents, symbols, emoji).
            const int32_t maxBytes = version >= 8 ? kSnapshotMaxLabelBytesUtf8 : kSnapshotMaxLabelBytes;
            if (!r.read(&label.clipKey) || !r.read(&length) || length < 0 || length > maxBytes) {
                return Status::BadSnapshot;
            }
            label.text.resize(static_cast<size_t>(length));
            if (length > 0 && !r.readBytes(label.text.data(), static_cast<size_t>(length))) return Status::BadSnapshot;
            if (version >= 8 && !utf8Valid(label.text.data(), label.text.size())) return Status::BadSnapshot;
            if (!r.skip(static_cast<size_t>((4 - length % 4) % 4))) return Status::BadSnapshot;
            snap.labels.push_back(std::move(label));
        }
        std::sort(snap.labels.begin(), snap.labels.end(),
                  [](const LabelSnapshot& a, const LabelSnapshot& b) { return a.clipKey < b.clipKey; });
    }
    if (!r.atEnd()) return Status::BadSnapshot;
    *out = std::move(snap);
    return Status::Ok;
}

}  // namespace uv::timeline
