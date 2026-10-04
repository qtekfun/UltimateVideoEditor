// GoogleTest-free host tests for the pure-logic parts of the timeline engine.
// Build/run: see tests/CMakeLists.txt (documented in CLAUDE.md).
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "audio/waveform_peaks.h"
#include "timeline_view/drop_hint.h"
#include "timeline_view/glyphs.h"
#include "timeline_view/hit_test.h"
#include "timeline_view/lane_header.h"
#include "timeline_view/marker_style.h"
#include "timeline_view/timeline_snapshot.h"
#include "timeline_view/viewport.h"

using namespace uv;

static int g_failures = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

// ---- snapshot encoding helpers (mirror of the Kotlin encoder) ----
struct Buf {
    std::vector<uint8_t> b;
    template <typename T>
    void put(T v) {
        const auto* p = reinterpret_cast<const uint8_t*>(&v);
        b.insert(b.end(), p, p + sizeof(T));
    }
};

static Buf makeSnapshot(int tracks, const std::vector<timeline::ClipSnapshot>& clips,
                        const std::vector<timeline::TransitionSnapshot>& transitions = {},
                        const std::vector<timeline::KeyframeSnapshot>& keyframes = {}, uint32_t version = timeline::kSnapshotVersion,
                        const std::vector<timeline::RetimeSnapshot>& retimes = {},
                        const std::vector<timeline::MarkerSnapshot>& markers = {},
                        const std::vector<timeline::LabelSnapshot>& labels = {}) {
    Buf w;
    w.put<uint32_t>(timeline::kSnapshotMagic);
    w.put<uint32_t>(version);
    w.put<int32_t>(30000);
    w.put<int32_t>(1001);
    w.put<int32_t>(tracks);
    w.put<int32_t>(static_cast<int32_t>(clips.size()));
    for (int i = 0; i < tracks; ++i) w.put<int32_t>(0);
    for (const auto& c : clips) {
        w.put<int64_t>(c.clipKey);
        w.put<int32_t>(c.trackIndex);
        w.put<int64_t>(c.assetKey);
        w.put<int64_t>(c.startFrame);
        w.put<int64_t>(c.durationFrames);
        w.put<int64_t>(c.sourceInFrame);
        w.put<int32_t>(c.sourceFpsNum);
        w.put<int32_t>(c.sourceFpsDen);
        w.put<int32_t>((c.selected ? 1 : 0) | (c.hasFx ? 2 : 0) | (c.missing ? 4 : 0) | (c.primary ? 8 : 0));
    }
    w.put<int32_t>(static_cast<int32_t>(transitions.size()));
    for (const auto& t : transitions) {
        w.put<int32_t>(t.trackIndex);
        w.put<int32_t>(0);
        w.put<int64_t>(t.cutFrame);
        w.put<int64_t>(t.preFrames);
        w.put<int64_t>(t.postFrames);
    }
    if (version >= 3) {
        w.put<int32_t>(static_cast<int32_t>(keyframes.size()));
        for (const auto& k : keyframes) {
            w.put<int64_t>(k.clipKey);
            w.put<int64_t>(k.frame);
        }
    }
    if (version >= 4) {
        w.put<int32_t>(static_cast<int32_t>(retimes.size()));
        for (const auto& t : retimes) {
            w.put<int64_t>(t.clipKey);
            w.put<int64_t>(t.sourceSpanFrames);
            w.put<int32_t>(t.flags);
            w.put<int32_t>(0);
        }
    }
    if (version >= 5) {
        w.put<int32_t>(static_cast<int32_t>(markers.size()));
        for (const auto& m : markers) {
            w.put<int64_t>(m.frame);
            w.put<int32_t>(m.flags);
            w.put<int32_t>(m.extra);
        }
    }
    if (version >= 7) {
        w.put<int32_t>(static_cast<int32_t>(labels.size()));
        for (const auto& l : labels) {
            w.put<int64_t>(l.clipKey);
            w.put<int32_t>(static_cast<int32_t>(l.text.size()));
            for (char ch : l.text) w.put<char>(ch);
            for (size_t i = l.text.size(); i % 4 != 0; ++i) w.put<char>(0);
        }
    }
    return w;
}

static timeline::ClipSnapshot clip(int64_t key, int track, int64_t start, int64_t dur) {
    return {key, track, 0, start, dur, 0, 30, 1, false};
}

static void testSnapshotRoundTrip() {
    auto buf = makeSnapshot(2, {clip(7, 0, 0, 100), clip(8, 1, 50, 25)});
    // Five trailing counts: transitions, keyframes, retimes, markers and labels.
    CHECK(buf.b.size() == timeline::kSnapshotHeaderBytes + 2 * 4 + 2 * timeline::kSnapshotClipBytes + 4 + 4 + 4 + 4 + 4);
    timeline::TimelineSnapshot s;
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.tracks.size() == 2 && s.clips.size() == 2);
    CHECK(s.markers.empty());
    CHECK(s.clips[1].clipKey == 8 && s.clips[1].startFrame == 50);
    CHECK(s.fpsNum == 30000 && s.fpsDen == 1001);
    CHECK(s.endFrame() == 100);
}

static void testSnapshotMissingFlag() {
    auto gone = clip(1, 0, 0, 10);
    gone.missing = true;
    auto both = clip(2, 0, 10, 10);
    both.missing = true;
    both.hasFx = true;
    auto buf = makeSnapshot(1, {gone, both, clip(3, 0, 20, 10)});
    timeline::TimelineSnapshot s;
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.clips[0].missing && !s.clips[0].hasFx);
    CHECK(s.clips[1].missing && s.clips[1].hasFx);
    CHECK(!s.clips[2].missing);
}

static void testSnapshotFxFlag() {
    auto styled = clip(1, 0, 0, 10);
    styled.hasFx = true;
    auto both = clip(2, 0, 10, 10);
    both.hasFx = true;
    both.selected = true;
    auto buf = makeSnapshot(1, {styled, both, clip(3, 0, 20, 10)});
    timeline::TimelineSnapshot s;
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.clips[0].hasFx && !s.clips[0].selected);
    CHECK(s.clips[1].hasFx && s.clips[1].selected);
    CHECK(!s.clips[2].hasFx && !s.clips[2].selected);
}

static void testSnapshotTransitions() {
    timeline::TimelineSnapshot s;
    auto buf = makeSnapshot(2, {clip(1, 0, 0, 100), clip(2, 0, 100, 100)}, {{0, 100, 5, 5}, {1, 40, 2, 3}});
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.transitions.size() == 2);
    CHECK(s.transitions[0].trackIndex == 0 && s.transitions[0].cutFrame == 100 && s.transitions[0].preFrames == 5 &&
          s.transitions[0].postFrames == 5);
    CHECK(s.transitions[1].trackIndex == 1 && s.transitions[1].postFrames == 3);

    // A count that disagrees with the bytes, a bad track and negative lengths are rejected.
    auto missing = buf.b;
    missing.resize(missing.size() - 1);
    CHECK(timeline::parseSnapshot(missing.data(), missing.size(), &s) == core::Status::BadSnapshot);
    auto badTrack = makeSnapshot(1, {clip(1, 0, 0, 10)}, {{3, 5, 1, 1}});
    CHECK(timeline::parseSnapshot(badTrack.b.data(), badTrack.b.size(), &s) == core::Status::BadSnapshot);
    auto negative = makeSnapshot(1, {clip(1, 0, 0, 10)}, {{0, 5, -1, 1}});
    CHECK(timeline::parseSnapshot(negative.b.data(), negative.b.size(), &s) == core::Status::BadSnapshot);
    auto huge = makeSnapshot(1, {clip(1, 0, 0, 10)});
    huge.b[huge.b.size() - 8] = 0x7F;  // claims far more transitions than there are bytes
    huge.b[huge.b.size() - 5] = 0x7F;
    CHECK(timeline::parseSnapshot(huge.b.data(), huge.b.size(), &s) == core::Status::BadSnapshot);
}

static void testSnapshotKeyframes() {
    timeline::TimelineSnapshot s;
    // Markers are sorted per clip however they arrive, and each clip finds its own run.
    auto buf = makeSnapshot(1, {clip(7, 0, 0, 100), clip(9, 0, 100, 50)}, {}, {{9, 20}, {7, 40}, {7, 5}});
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.keyframes.size() == 3);
    const auto [a, b] = s.keyframesOf(7);
    CHECK(b - a == 2 && a[0].frame == 5 && a[1].frame == 40);
    const auto [c, d] = s.keyframesOf(9);
    CHECK(d - c == 1 && c[0].frame == 20);
    const auto [e, f] = s.keyframesOf(123);
    CHECK(e == f);

    // A version 2 snapshot (no keyframe trailer) still parses and has none.
    auto v2 = makeSnapshot(1, {clip(1, 0, 0, 10)}, {{0, 5, 1, 1}}, {}, 2);
    CHECK(timeline::parseSnapshot(v2.b.data(), v2.b.size(), &s) == core::Status::Ok);
    CHECK(s.transitions.size() == 1 && s.keyframes.empty() && s.retimes.empty());
    // So does version 3 (keyframes, no retime trailer).
    auto v3 = makeSnapshot(1, {clip(1, 0, 0, 10)}, {}, {{1, 4}}, 3);
    CHECK(timeline::parseSnapshot(v3.b.data(), v3.b.size(), &s) == core::Status::Ok);
    CHECK(s.keyframes.size() == 1 && s.retimes.empty());

    // A keyframe count that disagrees with the bytes, a negative frame and a future version are rejected.
    auto truncated = buf.b;
    truncated.pop_back();
    CHECK(timeline::parseSnapshot(truncated.data(), truncated.size(), &s) == core::Status::BadSnapshot);
    auto negative = makeSnapshot(1, {clip(1, 0, 0, 10)}, {}, {{1, -1}});
    CHECK(timeline::parseSnapshot(negative.b.data(), negative.b.size(), &s) == core::Status::BadSnapshot);
    auto future = makeSnapshot(1, {clip(1, 0, 0, 10)}, {}, {}, timeline::kSnapshotVersion + 1);
    CHECK(timeline::parseSnapshot(future.b.data(), future.b.size(), &s) == core::Status::BadSnapshot);
}


static void testSnapshotRetimes() {
    timeline::TimelineSnapshot s;
    // Clip 7 plays 100 source frames over 50 (2x); clip 3 is reversed; clip 9 is a freeze frame.
    const std::vector<timeline::RetimeSnapshot> retimes = {{9, 1, 2}, {7, 100, 0}, {3, 40, 1}};
    auto buf = makeSnapshot(1, {clip(3, 0, 0, 40), clip(7, 0, 40, 50), clip(9, 0, 100, 30), clip(11, 0, 140, 10)}, {}, {}, 4, retimes);
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.retimes.size() == 3);
    const auto* two = s.retimeOf(7);
    CHECK(two != nullptr && two->sourceSpanFrames == 100 && !two->reverse() && !two->freeze());
    const auto* back = s.retimeOf(3);
    CHECK(back != nullptr && back->reverse() && !back->freeze());
    const auto* still = s.retimeOf(9);
    CHECK(still != nullptr && still->freeze());
    CHECK(s.retimeOf(11) == nullptr);  // a plain clip has none
    CHECK(s.retimeOf(12345) == nullptr);

    // Version 3 snapshots carried no retimes; the keyframe trailer must still end exactly where it did.
    auto withKeys = makeSnapshot(1, {clip(1, 0, 0, 10)}, {}, {{1, 4}}, 4, retimes);
    CHECK(timeline::parseSnapshot(withKeys.b.data(), withKeys.b.size(), &s) == core::Status::Ok);
    CHECK(s.keyframes.size() == 1 && s.retimes.size() == 3);

    // Rejected: a count that disagrees with the bytes, an empty span.
    auto truncated = buf.b;
    truncated.pop_back();
    CHECK(timeline::parseSnapshot(truncated.data(), truncated.size(), &s) == core::Status::BadSnapshot);
    auto empty = makeSnapshot(1, {clip(1, 0, 0, 10)}, {}, {}, 4, {{1, 0, 0}});
    CHECK(timeline::parseSnapshot(empty.b.data(), empty.b.size(), &s) == core::Status::BadSnapshot);
}

static void testRetimeBoundaries() {
    const timeline::RetimeSnapshot fast{1, 100, 0};  // 100 source frames over 50: 2x
    CHECK(timeline::retimeBoundary(nullptr, 50, 20) == 20);
    CHECK(timeline::retimeBoundary(&fast, 50, 0) == 0);
    CHECK(timeline::retimeBoundary(&fast, 50, 20) == 40);
    CHECK(timeline::retimeBoundary(&fast, 50, 50) == 100);
    const timeline::RetimeSnapshot slow{2, 10, 0};  // 10 over 40: 0.25x
    CHECK(timeline::retimeBoundary(&slow, 40, 4) == 1);
    CHECK(timeline::retimeBoundary(&slow, 40, 39) == 9);
    const timeline::RetimeSnapshot back{3, 40, 1};  // reversed: the end of the range first
    CHECK(timeline::retimeBoundary(&back, 40, 0) == 40);
    CHECK(timeline::retimeBoundary(&back, 40, 10) == 30);
    CHECK(timeline::retimeBoundary(&back, 40, 40) == 0);
    const timeline::RetimeSnapshot still{4, 1, 2};
    CHECK(timeline::retimeBoundary(&still, 30, 29) == 0);
}

static timeline::TimelineSnapshot laneSnapshot(const std::vector<timeline::TrackType>& types) {
    timeline::TimelineSnapshot s;
    for (auto t : types) s.tracks.push_back({t});
    return s;
}

static void testLaneHeaders() {
    using timeline::TrackType;
    // Display order, top first: V3, V2, base (V1), A1, A2, T1.
    auto s = laneSnapshot({TrackType::Video, TrackType::Video, TrackType::Video, TrackType::Audio, TrackType::Audio, TrackType::Title});
    CHECK(timeline::baseLaneIndex(s) == 2);
    CHECK(timeline::laneLabel(s, 0) == "V3" && timeline::laneLabel(s, 1) == "V2" && timeline::laneLabel(s, 2) == "V1");
    CHECK(timeline::laneLabel(s, 3) == "A1" && timeline::laneLabel(s, 4) == "A2" && timeline::laneLabel(s, 5) == "T1");
    CHECK(timeline::laneLabel(s, -1).empty() && timeline::laneLabel(s, 6).empty());
    CHECK(timeline::baseLaneIndex(laneSnapshot({TrackType::Audio})) == -1);

    // The bar sits on the top edge of the target lane when moving up and the bottom edge when moving down.
    bool atTop = false;
    CHECK(timeline::laneDragBarEdge(2, 0, 6, &atTop) && atTop);
    CHECK(timeline::laneDragBarEdge(0, 1, 6, &atTop) && !atTop);
    CHECK(!timeline::laneDragBarEdge(1, 1, 6, &atTop));
    CHECK(!timeline::laneDragBarEdge(-1, 1, 6, &atTop) && !timeline::laneDragBarEdge(0, 6, 6, &atTop));

    // The header column takes the touch before any clip under it; without a header column nothing changes.
    timeline::Viewport vp;
    vp.pxPerFrame = 2.0;
    auto withClip = laneSnapshot({TrackType::Video, TrackType::Video});
    withClip.clips.push_back(clip(1, 0, 0, 100));
    const auto plain = timeline::Layout::forDensity(1.0f);
    const auto headed = plain.withHeaders(22.0f);
    CHECK(headed.headerWidth == 22.0f && plain.headerWidth == 0.0f);
    auto r = timeline::hitTest(withClip, vp, plain, 10, plain.trackTop(0) + 10);
    CHECK(r.kind != timeline::HitKind::LaneHeader);
    r = timeline::hitTest(withClip, vp, headed, 10, headed.trackTop(0) + 10);
    CHECK(r.kind == timeline::HitKind::LaneHeader && r.trackIndex == 0 && r.clipKey == -1);
    r = timeline::hitTest(withClip, vp, headed, 10, headed.trackTop(1) + 10);
    CHECK(r.kind == timeline::HitKind::LaneHeader && r.trackIndex == 1);
    r = timeline::hitTest(withClip, vp, headed, 60, headed.trackTop(0) + 10);
    CHECK(r.kind == timeline::HitKind::Clip);
    r = timeline::hitTest(withClip, vp, headed, 10, headed.rulerHeight - 2);
    CHECK(r.kind == timeline::HitKind::Ruler);
    CHECK(headed.withHeaders(-5.0f).headerWidth == 0.0f);

    // Mute and solo ride in the high bits of a track's type word; the low byte is still the type and unknown
    // high bits are ignored.
    auto buf = makeSnapshot(3, {clip(1, 0, 0, 100)});
    auto setWord = [&](size_t track, int32_t word) { std::memcpy(buf.b.data() + 24 + 4 * track, &word, 4); };
    setWord(0, 0);
    setWord(1, 1 | timeline::kTrackMutedBit);
    setWord(2, 1 | timeline::kTrackSoloBit | (1 << 20));
    timeline::TimelineSnapshot parsed;
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &parsed) == core::Status::Ok);
    CHECK(parsed.tracks.size() == 3);
    CHECK(parsed.tracks[0].type == TrackType::Video && !parsed.tracks[0].muted && !parsed.tracks[0].solo);
    CHECK(parsed.tracks[1].type == TrackType::Audio && parsed.tracks[1].muted && !parsed.tracks[1].solo);
    CHECK(parsed.tracks[2].type == TrackType::Audio && !parsed.tracks[2].muted && parsed.tracks[2].solo);
    setWord(0, 3);  // type 3 does not exist
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &parsed) != core::Status::Ok);
    setWord(0, -1);
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &parsed) != core::Status::Ok);
}

static void testMarkerHitTestAndLabels() {
    timeline::TimelineSnapshot s;
    s.markers.push_back({100, 0, 0});
    s.markers.push_back({400, 0, 0});
    s.markers.push_back({410, 1, 0});  // a beat right next to the marker at 400
    timeline::Viewport vp;
    vp.pxPerFrame = 2.0;
    const auto layout = timeline::Layout::forDensity(1.0f);
    CHECK(layout.markerHitHalf == 20.0f);  // a 40dp wide target

    // On the ruler, the target is wider than the line: 15 px beside the marker at x=200 still hits it.
    auto r = timeline::hitTest(s, vp, layout, 215.0f, layout.rulerHeight - 4.0f);
    CHECK(r.kind == timeline::HitKind::Marker && r.clipKey == 0 && r.frame == 107);  // the frame is the finger's, not the marker's
    r = timeline::hitTest(s, vp, layout, 185.0f, 5.0f);
    CHECK(r.kind == timeline::HitKind::Marker && r.clipKey == 0);
    // Farther than the target: plain ruler (a seek).
    r = timeline::hitTest(s, vp, layout, 225.0f, 5.0f);
    CHECK(r.kind == timeline::HitKind::Ruler);
    // The nearest of several markers wins; beats are markers too.
    r = timeline::hitTest(s, vp, layout, 819.0f, 5.0f);
    CHECK(r.kind == timeline::HitKind::Marker && r.clipKey == 2 && r.frame == 409);
    // Below the ruler nothing is a marker.
    r = timeline::hitTest(s, vp, layout, 200.0f, layout.rulerHeight + 5.0f);
    CHECK(r.kind != timeline::HitKind::Marker);

    // The playhead handle keeps the touch when it is strictly nearer than the marker; a marker under the playhead
    // (just dropped there) wins the tie so it can be tapped.
    r = timeline::hitTest(s, vp, layout, 205.0f, 5.0f, 104);  // playhead at x=208, marker at x=200
    CHECK(r.kind == timeline::HitKind::Playhead);
    r = timeline::hitTest(s, vp, layout, 200.0f, 5.0f, 100);
    CHECK(r.kind == timeline::HitKind::Marker && r.clipKey == 0);
    // A layout without a marker target (the tests of other features) is unchanged.
    timeline::Layout none = layout;
    none.markerHitHalf = 0.0f;
    CHECK(timeline::hitTest(s, vp, none, 200.0f, 5.0f).kind == timeline::HitKind::Ruler);

    // Name labels: negative keys that never clash with clip keys, and a fit rule that draws nothing when crowded.
    CHECK(timeline::markerLabelKey(0) == -2 && timeline::markerLabelKey(5) == -7);
    CHECK(timeline::markerLabelChars(100.0f, 5.0f, 10) == 10);  // fits whole
    CHECK(timeline::markerLabelChars(30.0f, 5.0f, 10) == 6);    // cut to the room
    CHECK(timeline::markerLabelChars(9.0f, 5.0f, 10) == 0);     // fewer than 3 characters would fit: draw none
    CHECK(timeline::markerLabelChars(9.0f, 5.0f, 1) == 1);      // a one-letter name only needs one
    CHECK(timeline::markerLabelChars(0.0f, 5.0f, 4) == 0 && timeline::markerLabelChars(50.0f, 0.0f, 4) == 0);
    CHECK(timeline::markerLabelChars(50.0f, 5.0f, 0) == 0);
}

static void testMarkerStyleColours() {
    // Six distinct, bright colours in MarkerColor order (red, orange, yellow, green, blue, purple), and a default.
    const timeline::MarkerRgb none = timeline::markerRgb(0);
    CHECK(none.r == 1.0f && none.g > 0.4f && none.g < 0.5f && none.b == 0.80f);  // the original pink
    for (int a = 1; a <= timeline::kMarkerColorCount; ++a) {
        const timeline::MarkerRgb ca = timeline::markerRgb(a);
        CHECK(ca.r >= 0.0f && ca.r <= 1.0f && ca.g >= 0.0f && ca.g <= 1.0f && ca.b >= 0.0f && ca.b <= 1.0f);
        const float peak = std::max(ca.r, std::max(ca.g, ca.b));
        CHECK(peak >= 0.9f);  // every flag colour is bright
        for (int b = a + 1; b <= timeline::kMarkerColorCount; ++b) {
            const timeline::MarkerRgb cb = timeline::markerRgb(b);
            CHECK(ca.r != cb.r || ca.g != cb.g || ca.b != cb.b);
        }
    }
    CHECK(timeline::markerRgb(1).r == 1.0f && timeline::markerRgb(1).g < 0.4f);   // red
    CHECK(timeline::markerRgb(4).g > timeline::markerRgb(4).r);                    // green
    CHECK(timeline::markerRgb(5).b == 1.0f && timeline::markerRgb(5).r < 0.4f);   // blue
    CHECK(timeline::markerColorCode(0) == 0 && timeline::markerColorCode(6) == 6 && timeline::markerColorCode(7) == 0);
    CHECK(timeline::markerColorCode(timeline::kMarkerNoteBit | 2) == 2);
    CHECK(timeline::markerHasNote(timeline::kMarkerNoteBit) && !timeline::markerHasNote(6));
}

static void testSnapshotMarkers() {
    timeline::TimelineSnapshot s;
    // Markers come out sorted by frame with their flags; frames may be anywhere on the timeline.
    auto buf = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {},
                            {{90, 1}, {30, 0}, {60, 1}});
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.markers.size() == 3);
    CHECK(s.markers[0].frame == 30 && !s.markers[0].beat());
    CHECK(s.markers[1].frame == 60 && s.markers[1].beat());
    CHECK(s.markers[2].frame == 90 && s.markers[2].beat());

    // The style word carries a colour code and a note bit; markers written without it read as unstyled.
    auto styled = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {},
                               {{30, 0, 0}, {60, 0, 3 | timeline::kMarkerNoteBit}, {90, 1, 6}, {120, 0, 7}});
    CHECK(timeline::parseSnapshot(styled.b.data(), styled.b.size(), &s) == core::Status::Ok);
    CHECK(s.markers.size() == 4);
    CHECK(timeline::markerColorCode(s.markers[0].extra) == 0 && !timeline::markerHasNote(s.markers[0].extra));
    CHECK(timeline::markerColorCode(s.markers[1].extra) == 3 && timeline::markerHasNote(s.markers[1].extra));
    CHECK(timeline::markerColorCode(s.markers[2].extra) == 6 && !timeline::markerHasNote(s.markers[2].extra));
    CHECK(timeline::markerColorCode(s.markers[3].extra) == 0);  // 7 is not a colour

    // Version 4 has no marker trailer and still parses, with no markers.
    auto v4 = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, 4);
    CHECK(timeline::parseSnapshot(v4.b.data(), v4.b.size(), &s) == core::Status::Ok);
    CHECK(s.markers.empty());

    // A count that disagrees with the bytes, trailing garbage and a negative frame are rejected.
    auto truncated = buf.b;
    truncated.resize(truncated.size() - 1);
    CHECK(timeline::parseSnapshot(truncated.data(), truncated.size(), &s) == core::Status::BadSnapshot);
    auto extra = buf.b;
    extra.push_back(0);
    CHECK(timeline::parseSnapshot(extra.data(), extra.size(), &s) == core::Status::BadSnapshot);
    auto negative = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {}, {{-5, 0}});
    CHECK(timeline::parseSnapshot(negative.b.data(), negative.b.size(), &s) == core::Status::BadSnapshot);
}

// The tiny font used for ruler numbers, speed labels and the text on title and sticker blocks.
static std::string glyphArt(char ch) {
    std::string art;
    const uint16_t bits = timeline::glyphBits(ch);
    for (int row = 0; row < 5; ++row) {
        for (int col = 0; col < 3; ++col) art += ((bits >> (14 - (row * 3 + col))) & 1) ? '#' : '.';
        art += '/';
    }
    return art;
}

static void testGlyphFont() {
    CHECK(glyphArt('T') == "###/.#./.#./.#./.#./");
    CHECK(glyphArt('H') == "#.#/#.#/###/#.#/#.#/");
    CHECK(glyphArt('-') == ".../.../###/.../.../");
    CHECK(glyphArt('0') == "###/#.#/#.#/#.#/###/");
    // Lower case letters draw as capitals; the multiplication sign of the speed labels ('x') is the same picture as 'X'.
    CHECK(glyphArt('h') == glyphArt('H'));
    CHECK(glyphArt('x') == glyphArt('X'));
    // A space and characters the font does not have draw nothing.
    CHECK(timeline::glyphBits(' ') == 0 && timeline::glyphBits('#') == 0 && timeline::glyphBits('~') == 0);
    // Every capital letter and digit is drawn and no two are the same picture, so a label can always be read.
    for (char a = 'A'; a <= 'Z'; ++a) {
        CHECK(timeline::glyphBits(a) != 0);
        for (char b = static_cast<char>(a + 1); b <= 'Z'; ++b) CHECK(timeline::glyphBits(a) != timeline::glyphBits(b));
        for (char d = '0'; d <= '9'; ++d) CHECK(timeline::glyphBits(a) != timeline::glyphBits(d));
    }
    for (char a = '0'; a <= '9'; ++a) {
        CHECK(timeline::glyphBits(a) != 0);
        for (char b = static_cast<char>(a + 1); b <= '9'; ++b) CHECK(timeline::glyphBits(a) != timeline::glyphBits(b));
    }
}

static void testSnapshotLabels() {
    timeline::TimelineSnapshot s;
    // Labels come out sorted by clip key with their text; lengths that are not a multiple of 4 are padded on the wire.
    auto buf = makeSnapshot(1, {clip(1, 0, 0, 100), clip(2, 0, 100, 50), clip(3, 0, 150, 50)}, {}, {}, timeline::kSnapshotVersion, {}, {},
                            {{3, "LOWER THIRD"}, {1, "HI"}, {2, ""}});
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.labels.size() == 3);
    CHECK(s.labelOf(1) != nullptr && *s.labelOf(1) == "HI");
    CHECK(s.labelOf(2) != nullptr && s.labelOf(2)->empty());
    CHECK(s.labelOf(3) != nullptr && *s.labelOf(3) == "LOWER THIRD");
    CHECK(s.labelOf(99) == nullptr);

    // The longest allowed label (24 bytes) parses; one more byte and a cut or extended buffer are rejected.
    const std::string longest(24, 'A');
    auto full = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {}, {}, {{1, longest}});
    CHECK(timeline::parseSnapshot(full.b.data(), full.b.size(), &s) == core::Status::Ok && s.labelOf(1)->size() == 24);
    auto tooLong = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {}, {}, {{1, std::string(25, 'A')}});
    CHECK(timeline::parseSnapshot(tooLong.b.data(), tooLong.b.size(), &s) == core::Status::BadSnapshot);
    auto cut = buf.b;
    cut.resize(cut.size() - 2);
    CHECK(timeline::parseSnapshot(cut.data(), cut.size(), &s) == core::Status::BadSnapshot);
    auto extra = buf.b;
    extra.push_back(0);
    CHECK(timeline::parseSnapshot(extra.data(), extra.size(), &s) == core::Status::BadSnapshot);

    // Version 6 has no label trailer and still parses, with no labels.
    auto v6 = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, 6);
    CHECK(timeline::parseSnapshot(v6.b.data(), v6.b.size(), &s) == core::Status::Ok);
    CHECK(s.labels.empty() && s.labelOf(1) == nullptr);
}

static void testSnapshotPrimarySelection() {
    // Version 6: the primary flag is read; several clips can be selected with one of them primary.
    auto first = clip(1, 0, 0, 10);
    first.selected = true;
    first.primary = true;
    auto second = clip(2, 0, 10, 10);
    second.selected = true;
    auto third = clip(3, 0, 20, 10);
    auto buf = makeSnapshot(1, {first, second, third});
    timeline::TimelineSnapshot s;
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.clips[0].selected && s.clips[0].primary);
    CHECK(s.clips[1].selected && !s.clips[1].primary);
    CHECK(!s.clips[2].selected && !s.clips[2].primary);

    // Version 5 has no primary bit: a selected clip is its own primary, so the old look is kept.
    auto only = clip(4, 0, 0, 10);
    only.selected = true;
    auto v5 = makeSnapshot(1, {only, clip(5, 0, 10, 10)}, {}, {}, 5);
    CHECK(timeline::parseSnapshot(v5.b.data(), v5.b.size(), &s) == core::Status::Ok);
    CHECK(s.clips[0].selected && s.clips[0].primary);
    CHECK(!s.clips[1].selected && !s.clips[1].primary);

    // A version newer than the parser is still rejected.
    auto future = makeSnapshot(1, {clip(1, 0, 0, 10)}, {}, {}, timeline::kSnapshotVersion + 1);
    CHECK(timeline::parseSnapshot(future.b.data(), future.b.size(), &s) == core::Status::BadSnapshot);
}

static void testClipsInRect() {
    timeline::TimelineSnapshot s;
    // Lane 0: clips 1 (0..100) and 2 (200..300); lane 1: clip 3 (50..150); lane 2: clip 4 (0..400).
    auto buf = makeSnapshot(3, {clip(1, 0, 0, 100), clip(2, 0, 200, 100), clip(3, 1, 50, 100), clip(4, 2, 0, 400)});
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    timeline::Viewport vp;
    vp.pxPerFrame = 1.0;
    const auto lay = timeline::Layout::forDensity(1.0f);  // ruler 28, track 64, gap 4
    auto keys = [&](float x0, float y0, float x1, float y1) { return timeline::clipsInRect(s, vp, lay, x0, y0, x1, y1); };

    // A rectangle over the first two lanes between frames 90 and 210 touches clips 1, 2 and 3.
    auto r = keys(90, lay.trackTop(0) + 5, 210, lay.trackTop(1) + 5);
    CHECK((r == std::vector<int64_t>{1, 2, 3}));
    // Corners in any order give the same result.
    CHECK(keys(210, lay.trackTop(1) + 5, 90, lay.trackTop(0) + 5) == r);
    // Only touching an edge does not count, but one pixel inside does.
    CHECK(keys(100, lay.trackTop(0) + 5, 200, lay.trackTop(0) + 20).empty());
    CHECK((keys(99, lay.trackTop(0) + 5, 201, lay.trackTop(0) + 20) == std::vector<int64_t>{1, 2}));
    // The gap between lanes selects nothing; the whole stack selects everything.
    CHECK(keys(0, lay.trackTop(0) + lay.trackHeight + 1, 500, lay.trackTop(1) - 1).empty());
    CHECK(keys(0, 0, 500, 1000).size() == 4);
    // The ruler never counts: a rectangle that only covers it is empty, one dragged up into it still selects below.
    CHECK(keys(0, 0, 500, lay.rulerHeight - 1).empty());
    CHECK((keys(0, 0, 60, lay.trackTop(0) + 5) == std::vector<int64_t>{1}));
    // Scrolling and zooming move the clips under the rectangle.
    vp.pxPerFrame = 2.0;
    vp.scrollX = 100.0;  // x 0..10 is now frames 50..55: still inside clip 1
    CHECK((keys(0, lay.trackTop(0) + 5, 10, lay.trackTop(0) + 20) == std::vector<int64_t>{1}));
    vp.scrollX = 300.0;  // frames 150..155 on lane 0: between clip 1 and clip 2
    CHECK(keys(0, lay.trackTop(0) + 5, 10, lay.trackTop(0) + 20).empty());
    vp.scrollX = 0.0;
    vp.scrollY = lay.trackHeight + lay.trackGap;  // lane 1 now sits where lane 0 was
    CHECK((keys(150, lay.trackTop(0) + 5, 180, lay.trackTop(0) + 20) == std::vector<int64_t>{3}));
}

static void testSnapshotRejectsBadInput() {
    timeline::TimelineSnapshot s;
    auto good = makeSnapshot(1, {clip(1, 0, 0, 10)});
    auto truncated = good.b;
    truncated.pop_back();
    CHECK(timeline::parseSnapshot(truncated.data(), truncated.size(), &s) == core::Status::BadSnapshot);
    auto badMagic = good.b;
    badMagic[0] ^= 0xFF;
    CHECK(timeline::parseSnapshot(badMagic.data(), badMagic.size(), &s) == core::Status::BadSnapshot);
    auto badTrack = makeSnapshot(1, {clip(1, 3, 0, 10)});
    CHECK(timeline::parseSnapshot(badTrack.b.data(), badTrack.b.size(), &s) == core::Status::BadSnapshot);
    auto zeroDur = makeSnapshot(1, {clip(1, 0, 0, 0)});
    CHECK(timeline::parseSnapshot(zeroDur.b.data(), zeroDur.b.size(), &s) == core::Status::BadSnapshot);
    CHECK(timeline::parseSnapshot(nullptr, 0, &s) == core::Status::InvalidArgument);
    CHECK(timeline::parseSnapshot(good.b.data(), 3, &s) == core::Status::BadSnapshot);
}

static void testViewport() {
    timeline::Viewport vp;
    vp.pxPerFrame = 2.0;
    vp.scrollX = 100.0;
    CHECK(vp.xToFrame(0) == 50);
    CHECK(vp.frameToX(50) == 0.0);
    // Zoom keeps the frame under the focus point fixed.
    const int64_t before = vp.xToFrame(300);
    vp.zoomAt(2.0, 300);
    CHECK(vp.pxPerFrame == 4.0);
    CHECK(vp.xToFrame(300) == before);
    vp.zoomAt(1e9, 0);
    CHECK(vp.pxPerFrame == timeline::Viewport::kMaxPxPerFrame);
    vp.zoomAt(1e-9, 0);
    CHECK(vp.pxPerFrame == timeline::Viewport::kMinPxPerFrame);
    vp.viewWidth = 1000;
    vp.scrollX = -50;
    vp.clamp(1000, 0, 100);
    CHECK(vp.scrollX == 0.0);
}

static void testLaneScale() {
    const auto base = timeline::Layout::forDensity(2.0f);
    CHECK(base.trackHeight == 128.0f);
    // Lanes stretch, everything else keeps its size.
    const auto big = timeline::Layout::forDensity(2.0f, 1.4f);
    CHECK(big.trackHeight > 178.0f && big.trackHeight < 180.0f);
    CHECK(big.rulerHeight == base.rulerHeight && big.trackGap == base.trackGap && big.handleWidth == base.handleWidth);
    const auto small = timeline::Layout::forDensity(2.0f, 0.75f);
    CHECK(small.trackHeight == 96.0f);
    // Out-of-range scales are clamped, never zero or huge.
    CHECK(timeline::Layout::forDensity(1.0f, 0.0f).trackHeight == 32.0f);
    CHECK(timeline::Layout::forDensity(1.0f, 50.0f).trackHeight == 128.0f);
    // A taller lane means a taller stack (what scrolling is clamped to) and a smaller anchoring inset.
    CHECK(big.contentHeight(3) > base.contentHeight(3));
    CHECK(big.anchoredBottom(3, 1000.0f).inset < base.anchoredBottom(3, 1000.0f).inset);
}

static void testBottomAnchoredLanes() {
    const auto lay = timeline::Layout::forDensity(1.0f);  // ruler 28, track 64, gap 4
    // Three lanes in a 600 px panel: the stack rests on the bottom, free room is above it.
    const auto a = lay.anchoredBottom(3, 600.0f);
    CHECK(a.inset == 600.0f - (28.0f + 3 * 68.0f));
    CHECK(a.trackTop(2) + lay.trackHeight + lay.trackGap == 600.0f);
    // The scroll range ignores the inset, so a short stack never scrolls.
    CHECK(a.contentHeight(3) == lay.contentHeight(3));
    // A stack taller than the panel has no inset and scrolls.
    CHECK(lay.anchoredBottom(20, 600.0f).inset == 0.0f);

    timeline::TimelineSnapshot s;
    auto buf = makeSnapshot(3, {clip(1, 0, 10, 100), clip(2, 1, 0, 100), clip(3, 2, 0, 100)});
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    timeline::Viewport vp;
    vp.pxPerFrame = 1.0;
    // Lane 0 (the topmost overlay) is found where the anchored layout draws it...
    auto r = timeline::hitTest(s, vp, a, 60, a.trackTop(0) + 10);
    CHECK(r.kind == timeline::HitKind::Clip && r.trackIndex == 0 && r.clipKey == 1);
    // ...the base (last lane) sits at the bottom...
    r = timeline::hitTest(s, vp, a, 60, a.trackTop(2) + 10);
    CHECK(r.trackIndex == 2 && r.clipKey == 3);
    // ...and the room above the stack is the 'add a lane' zone, not a lane.
    r = timeline::hitTest(s, vp, a, 60, lay.rulerHeight + 5);
    CHECK(r.kind == timeline::HitKind::AboveLanes && r.trackIndex == -1);
    // The ruler stays the ruler.
    r = timeline::hitTest(s, vp, a, 60, 10);
    CHECK(r.kind == timeline::HitKind::Ruler);
    // Without an inset nothing is 'above the lanes'.
    r = timeline::hitTest(s, vp, lay, 60, lay.rulerHeight + 1);
    CHECK(r.kind != timeline::HitKind::AboveLanes);
}

static void testDropHintGeometry() {
    const auto lay = timeline::Layout::forDensity(1.0f).anchoredBottom(3, 600.0f);  // ruler 28, track 64, gap 4
    timeline::Viewport vp;
    vp.pxPerFrame = 2.0;
    vp.scrollX = 20.0;
    const float bar = 3.0f;
    using timeline::DropHint;
    using timeline::DropHintKind;

    // Insert: a thin bar centred on the junction, as tall as the lane.
    auto r = timeline::dropHintRect({DropHintKind::Insert, 2, 100, 100}, vp, lay, 400.0f, 600.0f, 3, bar);
    CHECK(r.valid);
    CHECK(r.x0 == 180.0f - 1.5f && r.x1 == 180.0f + 1.5f);
    CHECK(r.y0 == lay.trackTop(2) && r.y1 == lay.trackTop(2) + lay.trackHeight);

    // Overwrite: the replaced frames, never thinner than the bar.
    r = timeline::dropHintRect({DropHintKind::Overwrite, 0, 50, 80}, vp, lay, 400.0f, 600.0f, 3, bar);
    CHECK(r.valid && r.x0 == 80.0f && r.x1 == 140.0f && r.y0 == lay.trackTop(0));
    r = timeline::dropHintRect({DropHintKind::Overwrite, 0, 50, 50}, vp, lay, 400.0f, 600.0f, 3, bar);
    CHECK(r.valid && r.x1 - r.x0 == 2.0f * bar);

    // New lane: the whole lane width.
    r = timeline::dropHintRect({DropHintKind::NewLane, 0, 10, 40}, vp, lay, 400.0f, 600.0f, 3, bar);
    CHECK(r.valid && r.x0 == 0.0f && r.x1 == 400.0f);

    // Cancel washes the lane area under the ruler, whatever lane it names.
    r = timeline::dropHintRect({DropHintKind::Cancel, -1, 0, 0}, vp, lay, 400.0f, 600.0f, 3, bar);
    CHECK(r.valid && r.y0 == lay.rulerHeight && r.y1 == 600.0f && r.x1 == 400.0f);

    // Nothing to draw: no hint, or a lane that is not in the snapshot.
    CHECK(!timeline::dropHintRect({}, vp, lay, 400.0f, 600.0f, 3, bar).valid);
    CHECK(!timeline::dropHintRect({DropHintKind::Insert, 3, 0, 0}, vp, lay, 400.0f, 600.0f, 3, bar).valid);
    CHECK(!timeline::dropHintRect({DropHintKind::Overwrite, -1, 0, 5}, vp, lay, 400.0f, 600.0f, 3, bar).valid);

    // Scrolling the lane stack moves the indicator with it.
    vp.scrollY = 10.0;
    r = timeline::dropHintRect({DropHintKind::NewLane, 0, 0, 0}, vp, lay, 400.0f, 600.0f, 3, bar);
    CHECK(r.y0 == lay.trackTop(0) - 10.0f);
}

static void testHitTest() {
    timeline::TimelineSnapshot s;
    auto buf = makeSnapshot(2, {clip(1, 0, 10, 100), clip(2, 1, 0, 5)});
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    timeline::Viewport vp;
    vp.pxPerFrame = 1.0;
    const auto lay = timeline::Layout::forDensity(1.0f);  // ruler 28, track 64, gap 4

    auto r = timeline::hitTest(s, vp, lay, 100, 10);
    CHECK(r.kind == timeline::HitKind::Ruler && r.frame == 100);

    r = timeline::hitTest(s, vp, lay, 100, 10, /*playheadFrame=*/110);
    CHECK(r.kind == timeline::HitKind::Playhead && r.frame == 100);
    r = timeline::hitTest(s, vp, lay, 100, 10, /*playheadFrame=*/200);
    CHECK(r.kind == timeline::HitKind::Ruler);
    // Below the ruler the playhead never hides a clip handle.
    r = timeline::hitTest(s, vp, lay, 12, lay.trackTop(0) + 10, /*playheadFrame=*/10);
    CHECK(r.kind == timeline::HitKind::ClipLeftEdge);

    r = timeline::hitTest(s, vp, lay, 60, lay.trackTop(0) + 10);
    CHECK(r.kind == timeline::HitKind::Clip && r.clipKey == 1 && r.trackIndex == 0);

    r = timeline::hitTest(s, vp, lay, 12, lay.trackTop(0) + 10);
    CHECK(r.kind == timeline::HitKind::ClipLeftEdge && r.clipKey == 1);

    r = timeline::hitTest(s, vp, lay, 108, lay.trackTop(0) + 10);
    CHECK(r.kind == timeline::HitKind::ClipRightEdge && r.clipKey == 1);

    r = timeline::hitTest(s, vp, lay, 500, lay.trackTop(0) + 10);
    CHECK(r.kind == timeline::HitKind::EmptyTrack && r.trackIndex == 0 && r.clipKey == -1);

    r = timeline::hitTest(s, vp, lay, 60, lay.trackTop(0) + lay.trackHeight + 2);  // gap
    CHECK(r.kind == timeline::HitKind::None);

    r = timeline::hitTest(s, vp, lay, 2, lay.trackTop(1) + 5);
    CHECK(r.clipKey == 2 && r.trackIndex == 1);

    r = timeline::hitTest(s, vp, lay, 60, lay.trackTop(2) + 5);  // below last track
    CHECK(r.kind == timeline::HitKind::None);

    // Narrow clips keep a grabbable body: edges shrink to a third of the width each.
    timeline::TimelineSnapshot n;
    auto nb = makeSnapshot(1, {clip(9, 0, 100, 12)});
    CHECK(timeline::parseSnapshot(nb.b.data(), nb.b.size(), &n) == core::Status::Ok);
    r = timeline::hitTest(n, vp, lay, 106, lay.trackTop(0) + 5);
    CHECK(r.kind == timeline::HitKind::Clip);

    // Vertical scroll shifts track rows.
    vp.scrollY = lay.trackHeight + lay.trackGap;
    r = timeline::hitTest(s, vp, lay, 2, lay.trackTop(0) + 5);
    CHECK(r.trackIndex == 1);
}

static void testPeaks() {
    // Square wave +1000 for 200 samples then -2000 for 200, stereo with a quiet right channel.
    std::vector<int16_t> pcm;
    for (int i = 0; i < 400; ++i) {
        const int16_t v = i < 200 ? 1000 : -2000;
        pcm.push_back(v);
        pcm.push_back(0);
    }
    audio::PeakBuilder b(48000, 2);
    // Feed in uneven chunks to exercise block carry-over.
    b.addInterleaved(pcm.data(), 37);
    b.addInterleaved(pcm.data() + 37 * 2, 363);
    auto p = b.finish();
    CHECK(p.totalFrames == 400);
    CHECK(p.levels.size() == audio::kLevelCount);
    // ceil(400/64) = 7 base peaks
    CHECK(p.levels[0].count() == 7);
    CHECK(p.levels[0].data[0] == 0 && p.levels[0].data[1] == 1000);  // right channel 0 is the min
    // Last base peak is partial and all negative-or-zero.
    CHECK(p.levels[0].data[13] == 0);
    CHECK(p.levels[0].data[12] == -2000);
    // Top level folds everything.
    CHECK(p.levels[5].count() == 1);
    CHECK(p.levels[5].data[0] == -2000 && p.levels[5].data[1] == 1000);

    int16_t col[4];
    audio::queryPeaks(p, 0, 400, 2, col);
    CHECK(col[1] == 1000);
    CHECK(col[2] == -2000);
    // Out-of-range query yields zeros without crashing.
    audio::queryPeaks(p, 1000, 2000, 2, col);
    CHECK(col[0] == 0 && col[1] == 0);
    audio::queryPeaks(p, -100, 0, 1, col);
    CHECK(col[0] == 0 && col[1] == 0);
}

static void testViewportEnsureVisible() {
    timeline::Viewport vp;
    vp.viewWidth = 1000.0;
    vp.pxPerFrame = 10.0;
    vp.scrollX = 0.0;
    CHECK(!vp.ensureVisible(50));  // x = 500: inside the band, no scroll
    CHECK(vp.scrollX == 0.0);
    CHECK(vp.ensureVisible(95));  // x = 950: past 90%, pages so the frame sits 10% from the left
    CHECK(vp.scrollX == 95 * 10.0 - 100.0);
    CHECK(!vp.ensureVisible(95));
    CHECK(vp.ensureVisible(3));  // before the left edge: pages back, never below zero
    CHECK(vp.scrollX == 0.0);
}

static void testViewportFit() {
    timeline::Viewport vp;
    vp.viewWidth = 1000.0;
    vp.pxPerFrame = 4.0;
    vp.scrollX = 123.0;

    vp.fitTo(500);
    // The whole 500 frames fit in the width with a small margin, scrolled back to the start.
    CHECK(vp.scrollX == 0.0);
    CHECK(vp.frameToX(500) < 1000.0);
    CHECK(vp.frameToX(500) > 940.0);

    // An empty timeline leaves the zoom untouched.
    const double before = vp.pxPerFrame;
    vp.fitTo(0);
    CHECK(vp.pxPerFrame == before);

    // Absurdly long or short timelines are clamped to the supported zoom range.
    vp.fitTo(1'000'000'000);
    CHECK(vp.pxPerFrame == timeline::Viewport::kMinPxPerFrame);
    vp.fitTo(1);
    CHECK(vp.pxPerFrame == timeline::Viewport::kMaxPxPerFrame);
}

static void testWaveformDisplay() {
    // Quiet audio: its own loudest sample is 3000/32768 (about 9%), well above the floor.
    std::vector<int16_t> pcm(512, 0);
    pcm[10] = 3000;
    pcm[300] = -1500;
    audio::PeakBuilder b(48000, 1);
    b.addInterleaved(pcm.data(), pcm.size());
    const auto p = b.finish();
    const float ref = audio::referenceLevel(p);
    CHECK(ref > 0.09f && ref < 0.092f);

    // The loudest sample fills the display; a sample a quarter as loud is half as tall (sqrt).
    CHECK(audio::displayAmplitude(ref, ref) > 0.999f);
    CHECK(audio::displayAmplitude(ref * 0.25f, ref) > 0.49f && audio::displayAmplitude(ref * 0.25f, ref) < 0.51f);
    // Signed, clamped and monotonic.
    CHECK(audio::displayAmplitude(-ref * 0.25f, ref) < -0.49f);
    CHECK(audio::displayAmplitude(ref * 4.0f, ref) <= 1.0f);
    CHECK(audio::displayAmplitude(0.0f, ref) == 0.0f);
    CHECK(audio::displayAmplitude(ref * 0.1f, ref) < audio::displayAmplitude(ref * 0.2f, ref));

    // Near-silence is not amplified into a loud-looking waveform: the reference has a floor.
    std::vector<int16_t> hiss(512, 5);
    audio::PeakBuilder q(48000, 1);
    q.addInterleaved(hiss.data(), hiss.size());
    const auto quiet = q.finish();
    CHECK(audio::referenceLevel(quiet) == audio::kMinReferenceLevel);
    CHECK(audio::displayAmplitude(5.0f / 32768.0f, audio::referenceLevel(quiet)) < 0.1f);
    CHECK(audio::referenceLevel(audio::PeakPyramid{}) == audio::kMinReferenceLevel);
}

static void testPeaksFile() {
    std::vector<int16_t> pcm(5000);
    for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<int16_t>((i * 37) % 2000 - 1000);
    audio::PeakBuilder b(44100, 1);
    b.addInterleaved(pcm.data(), pcm.size());
    auto p = b.finish();
    const std::string path = "uv_peaks_test.peaks";
    CHECK(audio::savePeaks(path, p) == core::Status::Ok);
    audio::PeakPyramid q;
    CHECK(audio::loadPeaks(path, &q) == core::Status::Ok);
    CHECK(q.sampleRate == 44100 && q.totalFrames == 5000 && q.levels.size() == p.levels.size());
    CHECK(q.levels[0].data == p.levels[0].data);
    // Truncated/corrupt files are reported, not silently accepted.
    FILE* f = std::fopen(path.c_str(), "r+b");
    CHECK(f != nullptr);
    if (f) {
        std::fputc('X', f);
        std::fclose(f);
    }
    CHECK(audio::loadPeaks(path, &q) == core::Status::UnsupportedFormat);
    CHECK(audio::loadPeaks("does_not_exist.peaks", &q) == core::Status::IoError);
    std::remove(path.c_str());
}

int main() {
    testSnapshotRoundTrip();
    testSnapshotFxFlag();
    testSnapshotMissingFlag();
    testSnapshotTransitions();
    testSnapshotKeyframes();
    testSnapshotRetimes();
    testLaneHeaders();
    testMarkerStyleColours();
    testMarkerHitTestAndLabels();
    testSnapshotMarkers();
    testSnapshotLabels();
    testGlyphFont();
    testRetimeBoundaries();
    testSnapshotRejectsBadInput();
    testSnapshotPrimarySelection();
    testClipsInRect();
    testViewport();
    testHitTest();
    testBottomAnchoredLanes();
    testLaneScale();
    testDropHintGeometry();
    testPeaks();
    testViewportFit();
    testViewportEnsureVisible();
    testWaveformDisplay();
    testPeaksFile();
    if (g_failures == 0) std::puts("host tests: all passed");
    return g_failures == 0 ? 0 : 1;
}
