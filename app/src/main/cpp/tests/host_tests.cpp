// GoogleTest-free host tests for the pure-logic parts of the timeline engine.
// Build/run: see tests/CMakeLists.txt (documented in CLAUDE.md).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "audio/waveform_peaks.h"
#include "timeline_view/drop_hint.h"
#include "timeline_view/fade_curve.h"
#include "timeline_view/glyphs.h"
#include "timeline_view/hit_test.h"
#include "timeline_view/lane_header.h"
#include "timeline_view/marker_style.h"
#include "timeline_view/ruler_ticks.h"
#include "timeline_view/snap_guide.h"
#include "timeline_view/text_atlas.h"
#include "timeline_view/timeline_theme.h"
#include "timeline_view/wave_columns.h"
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
        w.put<int32_t>((c.selected ? 1 : 0) | (c.hasFx ? 2 : 0) | (c.missing ? 4 : 0) | (c.primary ? 8 : 0) |
                       (static_cast<int32_t>(c.kind) << timeline::kClipKindShift));
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

    // Version 7: the longest allowed label is 24 bytes; one more byte is rejected. A cut or extended buffer is rejected too.
    const std::string longest(24, 'A');
    auto full = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, 7, {}, {}, {{1, longest}});
    CHECK(timeline::parseSnapshot(full.b.data(), full.b.size(), &s) == core::Status::Ok && s.labelOf(1)->size() == 24);
    auto tooLong = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, 7, {}, {}, {{1, std::string(25, 'A')}});
    CHECK(timeline::parseSnapshot(tooLong.b.data(), tooLong.b.size(), &s) == core::Status::BadSnapshot);

    // Version 8: labels are UTF-8 up to 96 bytes (accents, symbols, emoji); malformed UTF-8 and longer labels are rejected.
    const std::string accents = "Caf\xC3\xA9 \xE2\x98\x95 \xF0\x9F\x8E\xAC";  // Café ☕ 🎬
    auto utf8 = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {}, {}, {{1, accents}});
    CHECK(timeline::parseSnapshot(utf8.b.data(), utf8.b.size(), &s) == core::Status::Ok && *s.labelOf(1) == accents);
    auto ninetySix = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {}, {}, {{1, std::string(96, 'A')}});
    CHECK(timeline::parseSnapshot(ninetySix.b.data(), ninetySix.b.size(), &s) == core::Status::Ok && s.labelOf(1)->size() == 96);
    auto ninetySeven = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {}, {}, {{1, std::string(97, 'A')}});
    CHECK(timeline::parseSnapshot(ninetySeven.b.data(), ninetySeven.b.size(), &s) == core::Status::BadSnapshot);
    auto broken = makeSnapshot(1, {clip(1, 0, 0, 100)}, {}, {}, timeline::kSnapshotVersion, {}, {}, {{1, std::string("A\xC3")}});
    CHECK(timeline::parseSnapshot(broken.b.data(), broken.b.size(), &s) == core::Status::BadSnapshot);
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

static void testClipKinds() {
    auto photo = clip(1, 0, 0, 10);
    photo.kind = timeline::ClipKind::Image;
    auto sticker = clip(2, 0, 10, 10);
    sticker.kind = timeline::ClipKind::Sticker;
    auto multi = clip(3, 0, 20, 10);
    multi.kind = timeline::ClipKind::Multicam;
    auto buf = makeSnapshot(1, {photo, sticker, multi, clip(4, 0, 30, 10)});
    timeline::TimelineSnapshot s;
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.clips[0].kind == timeline::ClipKind::Image && s.clips[1].kind == timeline::ClipKind::Sticker);
    CHECK(s.clips[2].kind == timeline::ClipKind::Multicam && s.clips[3].kind == timeline::ClipKind::Default);
    // The kind bits do not disturb the other flags, and an older snapshot has no kinds.
    auto selected = clip(5, 0, 0, 10);
    selected.selected = true;
    selected.primary = true;
    selected.kind = timeline::ClipKind::Sticker;
    auto withFlags = makeSnapshot(1, {selected});
    CHECK(timeline::parseSnapshot(withFlags.b.data(), withFlags.b.size(), &s) == core::Status::Ok);
    CHECK(s.clips[0].selected && s.clips[0].primary && s.clips[0].kind == timeline::ClipKind::Sticker);
    auto v7 = makeSnapshot(1, {sticker}, {}, {}, 7);
    CHECK(timeline::parseSnapshot(v7.b.data(), v7.b.size(), &s) == core::Status::Ok);
    CHECK(s.clips[0].kind == timeline::ClipKind::Default);
}

static void testLabelHashAndUtf8() {
    // The same values are checked by LabelHashTest in the Kotlin tests: both sides must agree on every bitmap's identity.
    CHECK(timeline::labelHash("0:05", 4, 1) == 0xb962b729c3e24061ull);
    const std::string cafe = "Caf\xC3\xA9 \xE2\x98\x95";
    CHECK(timeline::labelHash(cafe, 0) == 0x35af4f84ab8cd82aull);
    CHECK(timeline::labelHash("", 0, 2) == 0xaf63bf4c8601bb45ull);
    CHECK(timeline::labelHash("A", 1, 0) != timeline::labelHash("A", 1, 1));  // the size class is part of the identity

    CHECK(timeline::utf8Valid("", 0) && timeline::utf8Valid("plain", 5));
    const std::string emoji = "\xF0\x9F\x8E\xAC";
    CHECK(timeline::utf8Valid(emoji.data(), emoji.size()) && timeline::utf8Valid(cafe.data(), cafe.size()));
    CHECK(!timeline::utf8Valid("\xC3", 1));                  // cut in the middle of a character
    CHECK(!timeline::utf8Valid("\xC0\xAF", 2));              // overlong form of '/'
    CHECK(!timeline::utf8Valid("\xED\xA0\x80", 3));          // a surrogate
    CHECK(!timeline::utf8Valid("\xF4\x90\x80\x80", 4));      // above U+10FFFF
    CHECK(!timeline::utf8Valid("\x80", 1));                  // a continuation byte on its own
    CHECK(timeline::isAscii("V1") && !timeline::isAscii(cafe));
}

static void testShelfPackerAndLabelTable() {
    timeline::ShelfPacker packer(64, 40);
    int x = -1, y = -1;
    CHECK(packer.pack(20, 10, &x, &y) && x == 0 && y == 0);
    CHECK(packer.pack(20, 10, &x, &y) && x == 21 && y == 0);  // next to the first, one pixel of gutter
    CHECK(packer.pack(20, 8, &x, &y) && x == 42 && y == 0);   // a little shorter still fits the same row
    CHECK(packer.pack(30, 10, &x, &y) && y == 11);            // the row is full: a new row below
    CHECK(!packer.pack(100, 4, &x, &y) && !packer.pack(4, 100, &x, &y));  // wider or taller than the atlas
    CHECK(packer.pack(60, 10, &x, &y) && y == 22);
    CHECK(!packer.pack(60, 10, &x, &y));  // full
    packer.reset();
    CHECK(packer.pack(60, 10, &x, &y) && x == 0 && y == 0 && packer.usedPixels() == 600);

    timeline::LabelTable table(128, 64);
    CHECK(table.generation() == 0 && table.size() == 0);
    CHECK(table.place(1, 40, 20, false, &x, &y) == timeline::LabelTable::Result::Placed);
    const timeline::LabelEntry* e = table.find(1);
    CHECK(e != nullptr && e->w == 40 && e->h == 20 && !e->colour);
    CHECK(e->u0 == static_cast<float>(x) / 128.0f && e->v1 == static_cast<float>(y + 20) / 64.0f);
    CHECK(table.place(1, 40, 20, false, &x, &y) == timeline::LabelTable::Result::Exists);  // sent twice: kept once
    CHECK(table.place(2, 30, 20, true, &x, &y) == timeline::LabelTable::Result::Placed && table.find(2)->colour);
    CHECK(table.place(3, 500, 20, false, &x, &y) == timeline::LabelTable::Result::TooBig);
    // Fill it: every place() either lands or reports Full (nothing has aged out yet, so nothing may be evicted).
    uint64_t key = 100;
    bool sawFull = false;
    for (int i = 0; i < 200 && !sawFull; ++i) sawFull = table.place(key++, 33, 17, false, &x, &y) == timeline::LabelTable::Result::Full;
    CHECK(sawFull);
    // The last resort empties the atlas: a new generation, nothing found, the same bitmap can be placed again.
    table.reset();
    CHECK(table.generation() == 1 && table.size() == 0 && table.find(1) == nullptr);
    CHECK(table.place(1, 40, 20, false, &x, &y) == timeline::LabelTable::Result::Placed && x == 0 && y == 0);
}

// Two frames on: what was placed or found before is no longer protected from eviction.
static void ageLabels(timeline::LabelTable& table) {
    table.beginFrame();
    table.beginFrame();
}

static std::vector<uint64_t> takeEvictedLabels(timeline::LabelTable& table) {
    std::vector<uint64_t> out;
    table.takeEvicted(&out);
    return out;
}

static void testLabelLruBudget() {
    using Result = timeline::LabelTable::Result;
    int x = 0, y = 0;
    // Three 10x10 bitmaps (400 bytes each) fill a 1200 byte budget in a 200x100 atlas that has plenty of room.
    timeline::LabelTable table(200, 100, 1200);
    CHECK(table.budgetBytes() == 1200);
    CHECK(table.place(1, 10, 10, false, &x, &y) == Result::Placed);
    CHECK(table.place(2, 10, 10, false, &x, &y) == Result::Placed);
    CHECK(table.place(3, 10, 10, false, &x, &y) == Result::Placed);
    CHECK(table.liveBytes() == 1200 && table.size() == 3);

    // Everything was placed this frame: a fourth bitmap has nothing it may evict.
    CHECK(table.place(4, 10, 10, false, &x, &y) == Result::Full);
    CHECK(table.size() == 3 && takeEvictedLabels(table).empty());

    // Two frames on, the budget is held by dropping the least recently used bitmap, and only that one.
    ageLabels(table);
    CHECK(table.place(4, 10, 10, false, &x, &y) == Result::Placed);
    CHECK(table.liveBytes() == 1200 && table.size() == 3);
    CHECK((takeEvictedLabels(table) == std::vector<uint64_t>{1}));
    CHECK(table.find(1) == nullptr && table.find(2) != nullptr && table.find(3) != nullptr && table.find(4) != nullptr);
    CHECK(takeEvictedLabels(table).empty());  // reported once
    CHECK(table.generation() == 0);           // evicting is not a reset: Kotlin keeps everything else

    // A bitmap larger than the whole budget can never be held; a repeated one is kept once.
    CHECK(table.place(9, 30, 20, false, &x, &y) == Result::TooBig);
    CHECK(table.place(4, 10, 10, false, &x, &y) == Result::Exists);
}

static void testLabelLruOrder() {
    using Result = timeline::LabelTable::Result;
    int x = 0, y = 0;
    timeline::LabelTable table(200, 100, 1200);
    for (uint64_t k = 1; k <= 3; ++k) CHECK(table.place(k, 10, 10, false, &x, &y) == Result::Placed);
    ageLabels(table);
    // Drawing 1 makes it the most recently used, and (drawn this frame) protected; 2 is now the oldest.
    CHECK(table.find(1) != nullptr);
    CHECK(table.place(4, 10, 10, false, &x, &y) == Result::Placed);
    CHECK((takeEvictedLabels(table) == std::vector<uint64_t>{2}));
    CHECK(table.peek(1) != nullptr && table.peek(3) != nullptr && table.peek(4) != nullptr);

    // peek() does not count as use: 3 stays the oldest unprotected one once the frames have moved on.
    ageLabels(table);
    CHECK(table.peek(3) != nullptr);
    CHECK(table.find(4) != nullptr);
    CHECK(table.place(5, 10, 10, false, &x, &y) == Result::Placed);
    CHECK((takeEvictedLabels(table) == std::vector<uint64_t>{3}));  // 1 was drawn after 3 was placed: only find() refreshes

    // What was drawn in the previous frame is still protected; only the frame after that releases it.
    timeline::LabelTable small(200, 100, 800);
    CHECK(small.place(1, 10, 10, false, &x, &y) == Result::Placed && small.place(2, 10, 10, false, &x, &y) == Result::Placed);
    small.beginFrame();
    small.beginFrame();
    CHECK(small.find(1) != nullptr && small.find(2) != nullptr);  // both drawn in this frame
    small.beginFrame();                                          // the next frame: they were used in the previous one
    CHECK(small.place(3, 10, 10, false, &x, &y) == Result::Full);
    small.beginFrame();                                          // and now they are two frames old
    CHECK(small.place(3, 10, 10, false, &x, &y) == Result::Placed);
    CHECK((takeEvictedLabels(small) == std::vector<uint64_t>{1}));
}

static void testLabelLruReuse() {
    using Result = timeline::LabelTable::Result;
    int x = 0, y = 0, ax = 0, ay = 0;
    // An evicted rectangle is reused by the next bitmap that fits, without taking new space from the packer.
    timeline::LabelTable table(100, 30, 800);
    CHECK(table.place(1, 10, 10, false, &ax, &ay) == Result::Placed);
    CHECK(table.place(2, 10, 10, false, &x, &y) == Result::Placed);
    const size_t packed = table.usedPixels();
    ageLabels(table);
    CHECK(table.place(3, 10, 10, false, &x, &y) == Result::Placed);
    CHECK(x == ax && y == ay);
    CHECK(table.usedPixels() == packed);
    CHECK(table.freeSlotCount() == 0);

    // A smaller bitmap goes in the same place and the unused right end stays available.
    // A tight budget forces the eviction of the 40 wide one.
    timeline::LabelTable tight(100, 30, 1700);
    CHECK(tight.place(1, 40, 10, false, &ax, &ay) == Result::Placed);
    ageLabels(tight);
    CHECK(tight.place(2, 10, 10, false, &x, &y) == Result::Placed && x == ax && y == ay);
    CHECK(tight.freeSlotCount() == 1);  // the 29 pixels left of the 40 wide slot
    CHECK(tight.place(3, 20, 10, false, &x, &y) == Result::Placed && x == ax + 11 && y == ay);
    CHECK(tight.freeSlotCount() == 1);  // 8 pixels still left (29 - 20 - 1)
}

static void testLabelLruMergesNeighbours() {
    using Result = timeline::LabelTable::Result;
    int x = 0, y = 0, ax = 0, ay = 0;
    // Three bitmaps side by side go in turn; a wide one then fits only where the three freed rectangles merged.
    timeline::LabelTable table(200, 100, 1300);
    CHECK(table.place(1, 10, 10, false, &ax, &ay) == Result::Placed);
    CHECK(table.place(2, 10, 10, false, &x, &y) == Result::Placed && y == ay && x == ax + 11);
    CHECK(table.place(3, 10, 10, false, &x, &y) == Result::Placed && y == ay && x == ax + 22);
    const size_t packed = table.usedPixels();
    ageLabels(table);
    CHECK(table.place(4, 30, 10, false, &x, &y) == Result::Placed);  // 1200 bytes: all three go, in the order 1, 2, 3
    CHECK((takeEvictedLabels(table) == std::vector<uint64_t>{1, 2, 3}));
    CHECK(x == ax && y == ay);
    CHECK(table.usedPixels() == packed);  // no new packer space
    CHECK(table.size() == 1 && table.liveBytes() == 1200);
}

static void testLabelLruResetIsTheLastResort() {
    using Result = timeline::LabelTable::Result;
    int x = 0, y = 0;
    // Everything in use and no room: place() says Full, the caller resets, and Kotlin sees a new generation.
    timeline::LabelTable table(64, 32, 1000000);
    uint64_t key = 1;
    Result r = Result::Placed;
    while (r == Result::Placed) r = table.place(key++, 20, 10, false, &x, &y);
    CHECK(r == Result::Full && table.generation() == 0 && table.size() > 1);
    table.reset();
    CHECK(table.generation() == 1 && table.size() == 0 && table.liveBytes() == 0 && table.freeSlotCount() == 0);
    CHECK(takeEvictedLabels(table).empty());
    CHECK(table.place(1, 20, 10, false, &x, &y) == Result::Placed && x == 0 && y == 0);

    // The same stream of bitmaps with frames passing never needs the reset: it keeps evicting the oldest.
    timeline::LabelTable churn(64, 32, 1000000);
    size_t evictions = 0;
    for (uint64_t k = 1; k <= 200; ++k) {
        churn.beginFrame();
        churn.beginFrame();
        CHECK(churn.place(k, 20, 10, false, &x, &y) == Result::Placed);
        evictions += takeEvictedLabels(churn).size();
    }
    CHECK(churn.generation() == 0 && evictions > 100 && churn.find(200) != nullptr && churn.find(1) == nullptr);
}

static void testSnapGuideRect() {
    auto lay = timeline::Layout::forDensity(1.0f).withHeaders(60.0f);  // ruler 28
    timeline::Viewport vp;
    vp.pxPerFrame = 2.0;
    vp.scrollX = 20.0;

    // A 2 px line centred on frame 100 (x = 180), from the ruler to the bottom of the view.
    auto r = timeline::snapGuideRect(100, vp, lay, 400.0f, 600.0f, 2.0f);
    CHECK(r.valid && r.x0 == 179.0f && r.x1 == 181.0f && r.y0 == 28.0f && r.y1 == 600.0f);
    // A 1 px line is exactly on the pixel; a line is never thinner than a pixel.
    r = timeline::snapGuideRect(100, vp, lay, 400.0f, 600.0f, 1.0f);
    CHECK(r.valid && r.x0 == 180.0f && r.x1 == 181.0f);
    r = timeline::snapGuideRect(100, vp, lay, 400.0f, 600.0f, 0.2f);
    CHECK(r.valid && r.x1 - r.x0 == 1.0f);
    // Fractional positions land on whole pixels.
    vp.scrollX = 20.4;
    r = timeline::snapGuideRect(100, vp, lay, 400.0f, 600.0f, 1.0f);
    CHECK(r.valid && r.x0 == 179.0f && r.x1 == 180.0f);
    vp.scrollX = 20.0;

    // No guide, a guide off the left of the view, under the header column, or off the right.
    CHECK(!timeline::snapGuideRect(timeline::kNoSnapGuide, vp, lay, 400.0f, 600.0f, 2.0f).valid);
    CHECK(!timeline::snapGuideRect(0, vp, lay, 400.0f, 600.0f, 2.0f).valid);      // x = -20
    CHECK(!timeline::snapGuideRect(20, vp, lay, 400.0f, 600.0f, 2.0f).valid);     // x = 20, inside the 60 px header
    CHECK(!timeline::snapGuideRect(220, vp, lay, 400.0f, 600.0f, 2.0f).valid);    // x = 420, past the right edge
    // On the header's edge only the part to its right is drawn.
    r = timeline::snapGuideRect(40, vp, lay, 400.0f, 600.0f, 4.0f);               // x = 60: 58..62
    CHECK(r.valid && r.x0 == 60.0f && r.x1 == 62.0f);
    // Frame 0 at no scroll sits left of the lanes' header only when there is one.
    vp.scrollX = 0.0;
    CHECK(!timeline::snapGuideRect(0, vp, lay, 400.0f, 600.0f, 2.0f).valid);
    CHECK(timeline::snapGuideRect(0, vp, timeline::Layout::forDensity(1.0f), 400.0f, 600.0f, 2.0f).valid);
    // A view too short to have lanes draws nothing.
    CHECK(!timeline::snapGuideRect(100, vp, lay, 400.0f, 28.0f, 2.0f).valid);
}

static void testDragShadowLayers() {
    const float spread = 9.0f;
    for (float peak : {0.0f, 0.25f, 0.5f, 1.0f}) {
        float transmitted = 1.0f;  // what still shows through of what is underneath after each layer
        float lastGrow = spread + 1.0f;
        for (int i = 0; i < timeline::kShadowLayers; ++i) {
            const auto layer = timeline::shadowLayer(i, spread, peak);
            CHECK(layer.alpha >= 0.0f && layer.alpha <= 1.0f);
            CHECK(layer.grow > 0.0f && layer.grow < lastGrow);  // the widest layer first, each tighter than the last
            lastGrow = layer.grow;
            transmitted *= 1.0f - layer.alpha;
            // The opacity next to the block builds up evenly to `peak`.
            const float opacity = 1.0f - transmitted;
            const float wanted = peak * static_cast<float>(i + 1) / static_cast<float>(timeline::kShadowLayers);
            CHECK(std::fabs(opacity - wanted) < 1e-5f);
        }
    }
    CHECK(timeline::shadowLayer(0, spread, 0.5f).grow == spread);
    CHECK(timeline::shadowLayer(timeline::kShadowLayers - 1, spread, 0.5f).grow == spread / static_cast<float>(timeline::kShadowLayers));
    // A fully opaque peak never divides by zero.
    CHECK(timeline::shadowLayer(timeline::kShadowLayers - 1, spread, 1.0f).alpha == 1.0f);
}

static void testThumbnailFadeCurve() {
    const int64_t d = timeline::kThumbFadeNanos;
    CHECK(timeline::fadeAlpha(0, d) == 0.0f);                // just uploaded: invisible
    CHECK(timeline::fadeAlpha(d, d) == 1.0f);                // done
    CHECK(timeline::fadeAlpha(d / 2, d) > 0.499f && timeline::fadeAlpha(d / 2, d) < 0.501f);
    CHECK(timeline::fadeAlpha(d * 3, d) == 1.0f);            // long after: fully visible
    CHECK(timeline::fadeAlpha(-5, d) == 1.0f);               // a clock that went backwards never hides a tile
    CHECK(timeline::fadeAlpha(10, 0) == 1.0f);               // no fade configured
    float previous = -1.0f;
    for (int64_t t = 0; t <= d; t += d / 64) {               // monotonic, within [0, 1], eased at both ends
        const float a = timeline::fadeAlpha(t, d);
        CHECK(a >= previous && a >= 0.0f && a <= 1.0f);
        previous = a;
    }
    CHECK(timeline::fadeAlpha(d / 20, d) < 0.05f && timeline::fadeAlpha(d - d / 20, d) > 0.95f);
}

static void testRulerPlanAndLabels() {
    // 30 fps, at least 72 px between labels, small ticks at least 7 px apart.
    auto planAt = [](double pxPerFrame) { return timeline::planRuler(30, pxPerFrame, 72.0, 7.0); };
    CHECK(planAt(80.0).step == 1);                           // zoomed right in: every frame
    CHECK(planAt(20.0).step == 5);                           // 5 frames * 20 px = 100 px
    CHECK(planAt(3.0).step == 30);                           // one second = 90 px
    // 2 s is 60 px (too close), 5 s is 150 px
    CHECK(planAt(1.0).step == 150);
    CHECK(planAt(0.1).step == 900 && planAt(0.02).step == 3600);  // half a minute, two minutes
    CHECK(timeline::planRuler(30, 1e-9, 72.0, 7.0).step == 86400LL * 30);  // never runs off the end of the candidates
    // Steps always grow with zoom-out, so labels never get closer than asked.
    int64_t previous = 0;
    for (double ppf = 96.0; ppf > 0.02; ppf *= 0.8) {
        const auto plan = planAt(ppf);
        CHECK(plan.step >= previous && (static_cast<double>(plan.step) * ppf >= 72.0 || plan.step == 86400LL * 30));
        previous = plan.step;
        CHECK(plan.minorDiv >= 1 && plan.step % plan.minorDiv == 0);
        if (plan.minorDiv > 1) CHECK(static_cast<double>(plan.step / plan.minorDiv) * ppf >= 7.0);
    }
    // A 25 fps timeline works the same; a 24 fps one has 24-frame seconds.
    CHECK(timeline::planRuler(24, 3.0, 72.0, 7.0).step == 24);
    CHECK(timeline::planRuler(1, 100.0, 72.0, 7.0).step == 1);  // degenerate frame rate

    char out[32];
    auto label = [&](int64_t frame, int64_t fps, int64_t step) {
        timeline::formatRulerLabel(frame, fps, step, out, sizeof(out));
        return std::string(out);
    };
    CHECK(label(150, 30, 30) == "0:05");
    CHECK(label(1800, 30, 30) == "1:00");
    CHECK(label(30 * 3725, 30, 30) == "1:02:05");  // hours appear only when there are some
    CHECK(label(30 * 125, 30, 300) == "2:05");
    CHECK(label(0, 30, 30) == "0:00");
    CHECK(label(157, 30, 1) == "0:05:07");           // below a second: minutes:seconds:frames
    CHECK(label(30 * 3600 + 31, 30, 5) == "1:00:01:01");
    CHECK(label(-5, 30, 30) == "0:00");
    char tiny[4];
    const size_t n = timeline::formatRulerLabel(30 * 3725, 30, 30, tiny, sizeof(tiny));
    CHECK(n == 3 && tiny[3] == '\0');  // cut to the buffer, still terminated
    CHECK(timeline::formatTimecode(157, 30, out, sizeof(out)) == 7 && std::string(out) == "0:05:07");
}

static void testTheme() {
    const timeline::TimelineTheme dark = timeline::TimelineTheme::dark();
    CHECK(std::abs(dark.background.r - 0x0F / 255.0f) < 1e-6f && dark.background.a == 1.0f);
    CHECK(std::abs(dark.clipVideo.b - 0xD6 / 255.0f) < 1e-6f);
    uint32_t custom[timeline::kNativeColourCount];
    for (size_t i = 0; i < timeline::kNativeColourCount; ++i) custom[i] = 0xFF000000u | static_cast<uint32_t>(i);
    timeline::TimelineTheme t = dark;
    t.assign(custom);
    CHECK(t.background.b == 0.0f && std::abs(t.laneA.b - 1.0f / 255.0f) < 1e-6f);
    CHECK(std::abs(t.error.b - 21.0f / 255.0f) < 1e-6f);  // the last of the 22 colours
    const timeline::Rgba half = timeline::rgbaFromArgb(0x80FF0000u);
    CHECK(std::abs(half.a - 128.0f / 255.0f) < 1e-6f && half.r == 1.0f && half.g == 0.0f);
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
    CHECK(timeline::Layout::forDensity(1.0f, 50.0f).trackHeight == 192.0f);
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
    // ceil(400/16) = 25 base peaks
    CHECK(p.levels[0].count() == 25);
    CHECK(p.levels[0].data[0] == 0 && p.levels[0].data[1] == 1000);  // right channel 0 is the min
    // Last base peak (samples 384..399) is all negative-or-zero.
    CHECK(p.levels[0].data[49] == 0);
    CHECK(p.levels[0].data[48] == -2000);
    // Top level folds everything.
    CHECK(p.levels.back().count() == 1);
    CHECK(p.levels.back().data[0] == -2000 && p.levels.back().data[1] == 1000);

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

// ---- waveform: peak reduction, mip selection, column algorithm, budget ----

static audio::PeakPyramid pyramidOf(const std::vector<int16_t>& pcm, int channels = 1, uint32_t rate = 48000) {
    audio::PeakBuilder b(rate, channels);
    b.addInterleaved(pcm.data(), pcm.size() / static_cast<size_t>(channels));
    return b.finish();
}

static void testPeakRms() {
    // A constant +-1000 square wave has an RMS of exactly 1000 at every level.
    std::vector<int16_t> sq(4096);
    for (size_t i = 0; i < sq.size(); ++i) sq[i] = (i / 32) % 2 == 0 ? 1000 : -1000;
    const auto p = pyramidOf(sq);
    for (const auto& lv : p.levels) {
        // The 16 sample level has no RMS (too few samples to mean anything); every other level has one per peak.
        CHECK(lv.hasRms() == (lv.samplesPerPeak >= audio::kFirstRmsSamplesPerPeak));
        for (const int16_t r : lv.rms) CHECK(r == 1000);
    }
    // A single full-scale click in silence: the RMS of its 64 sample peak is 32767/8, the min/max keep the click whole.
    std::vector<int16_t> click(640, 0);
    click[100] = 32767;
    const auto c = pyramidOf(click);
    CHECK(c.levels[0].data[2 * 6 + 1] == 32767);  // 16 sample peak 6 holds samples 96..111
    CHECK(c.levels[1].data[2 * 1 + 1] == 32767);  // 64 sample peak 1 holds samples 64..127
    CHECK(std::abs(c.levels[1].rms[1] - static_cast<int>(std::lround(32767.0 / 8.0))) <= 1);
    CHECK(c.levels[1].rms[0] == 0);
    // The RMS of a coarser peak is the RMS of what it covers: the 256 sample peak holds the click among 255 zeros.
    CHECK(std::abs(c.levels[2].rms[0] - static_cast<int>(std::lround(32767.0 / 16.0))) <= 1);
}

static void testPeakSilenceAndClipping() {
    const std::vector<int16_t> silence(10000, 0);
    const auto s = pyramidOf(silence);
    const audio::PeakStat quiet = audio::reducePeaks(s, 0, 10000);
    CHECK(quiet.min == 0 && quiet.max == 0 && quiet.rms == 0);
    CHECK(audio::referenceLevel(s) == audio::kMinReferenceLevel);
    const timeline::WaveColumn col = timeline::waveColumn(s, 0, 500, audio::referenceLevel(s), audio::WaveScale::Linear);
    CHECK(col.up == 0.0f && col.down == 0.0f && col.rms == 0.0f);

    // Clipped audio: full-scale samples fill the half lane but never exceed it, whichever the scale.
    std::vector<int16_t> clipped(2000);
    for (size_t i = 0; i < clipped.size(); ++i) clipped[i] = (i / 50) % 2 == 0 ? 32767 : -32768;
    const auto c = pyramidOf(clipped);
    const float ref = audio::referenceLevel(c);
    CHECK(ref >= 0.999f && ref <= 1.0f);
    for (const auto scale : {audio::WaveScale::Linear, audio::WaveScale::Decibel}) {
        const auto col2 = timeline::waveColumn(c, 0, 400, ref, scale);
        CHECK(col2.up <= 1.0f && col2.up > 0.99f);
        CHECK(col2.down <= 1.0f && col2.down > 0.99f);
        CHECK(col2.rms <= 1.0f && col2.rms > 0.99f);
    }
}

static void testPeakShortClips() {
    // Fewer samples than one peak: one partial peak, and queries clip to what exists.
    std::vector<int16_t> few = {10, -20, 30, -40, 50, 60, -70, 5, 0, 1};
    const auto p = pyramidOf(few);
    CHECK(p.totalFrames == 10);
    CHECK(p.levels[0].count() == 1 && p.levels.back().count() == 1);
    const audio::PeakStat all = audio::reducePeaks(p, 0, 10);
    CHECK(all.min == -70 && all.max == 60);
    CHECK(all.rms == 0);  // the finest level has no RMS
    const audio::PeakStat wide = audio::reducePeaks(p, 0, 200);  // a column of 200 samples reads the 64 sample level
    CHECK(wide.min == -70 && wide.max == 60 && wide.rms > 0 && wide.rms < 70);
    // Ranges past the end, before the start, empty or inverted give silence and never crash.
    CHECK(audio::reducePeaks(p, 10, 20).max == 0);
    CHECK(audio::reducePeaks(p, -50, -1).min == 0);
    CHECK(audio::reducePeaks(p, 5, 5).max == 0 && audio::reducePeaks(p, 7, 3).max == 0);
    // A range that starts before and ends after the data still sees all of it.
    CHECK(audio::reducePeaks(p, -100, 100).max == 60);
    // An empty source and an empty pyramid.
    CHECK(audio::reducePeaks(audio::PeakPyramid{}, 0, 100).max == 0);
    const auto none = pyramidOf({});
    CHECK(none.totalFrames == 0 && audio::reducePeaks(none, 0, 10).max == 0);
}

static void testPeakStereoDownmix() {
    // Left quiet, right loud with opposite signs: the fold keeps the extremes of both channels and the RMS follows
    // the louder channel of each frame, so a loud channel is never hidden by a quiet one.
    std::vector<int16_t> pcm;
    for (int i = 0; i < 256; ++i) {
        pcm.push_back(static_cast<int16_t>(i % 2 == 0 ? 100 : -100));    // left
        pcm.push_back(static_cast<int16_t>(i % 2 == 0 ? -8000 : 6000));  // right
    }
    const auto p = pyramidOf(pcm, 2);
    const audio::PeakStat st = audio::reducePeaks(p, 0, 256);
    CHECK(st.min == -8000 && st.max == 6000);
    const double expected = std::sqrt((8000.0 * 8000.0 + 6000.0 * 6000.0) / 2.0);
    CHECK(std::fabs(st.rms - expected) < 2.0);
    // Mono and the same signal duplicated on two channels agree.
    std::vector<int16_t> mono, dup;
    for (int i = 0; i < 300; ++i) {
        const int16_t v = static_cast<int16_t>((i * 97) % 3000 - 1500);
        mono.push_back(v);
        dup.push_back(v);
        dup.push_back(v);
    }
    const auto a = pyramidOf(mono, 1), b = pyramidOf(dup, 2);
    CHECK(a.levels[0].data == b.levels[0].data && a.levels[1].rms == b.levels[1].rms && !a.levels[1].rms.empty());
}

static void testPeakLongSourceDropsFineLevel() {
    // Past kMaxFineLevelFrames the 16 sample level is given up (memory), the rest still answers every query.
    audio::PeakBuilder b(48000, 1);
    std::vector<int16_t> chunk(1 << 20, 0);
    chunk[5] = 12345;
    int64_t fed = 0;
    while (fed <= audio::kMaxFineLevelFrames + (1 << 21)) {
        b.addInterleaved(chunk.data(), chunk.size());
        fed += static_cast<int64_t>(chunk.size());
    }
    const auto p = b.finish();
    CHECK(p.levels[0].count() == 0 && p.levels[1].count() > 0);
    CHECK(audio::levelForColumn(p, 1) == 1);  // narrower than any level left: the finest one there is
    CHECK(audio::reducePeaks(p, 0, 8).max == 12345);
    CHECK(audio::reducePeaks(p, fed - 100, fed).max == 12345 || audio::reducePeaks(p, fed - 100, fed).max == 0);
}

static void testPeakMipSelection() {
    std::vector<int16_t> pcm(300000, 0);
    const auto p = pyramidOf(pcm);
    // The coarsest level whose peaks are no wider than a column; the finest when even that is wider.
    CHECK(audio::levelForColumn(p, 1) == 0 && audio::levelForColumn(p, 15) == 0 && audio::levelForColumn(p, 16) == 0);
    CHECK(audio::levelForColumn(p, 63) == 0 && audio::levelForColumn(p, 64) == 1);
    CHECK(audio::levelForColumn(p, 255) == 1 && audio::levelForColumn(p, 256) == 2);
    CHECK(audio::levelForColumn(p, 1023) == 2 && audio::levelForColumn(p, 1024) == 3);
    CHECK(audio::levelForColumn(p, 65536) == audio::kLevelCount - 1);
    CHECK(audio::levelForColumn(p, int64_t{1} << 40) == audio::kLevelCount - 1);
    CHECK(audio::levelForColumn(audio::PeakPyramid{}, 1000) == 0);

    // A lone click keeps its height at every zoom: min/max never lose a transient when the level gets coarser.
    std::vector<int16_t> click(300000, 0);
    click[123457] = 20000;
    click[200000] = -15000;
    const auto c = pyramidOf(click);
    for (const int64_t width : {int64_t{16}, int64_t{200}, int64_t{3000}, int64_t{70000}}) {
        const int64_t start = 123457 / width * width;
        CHECK(audio::reducePeaks(c, start, start + width).max == 20000);
        const int64_t start2 = 200000 / width * width;
        CHECK(audio::reducePeaks(c, start2, start2 + width).min == -15000);
    }
    // A column further away from the click is silent at every zoom.
    CHECK(audio::reducePeaks(c, 0, 4096).max == 0);
    // Reading cost is bounded: at most a handful of peaks per column, however wide it is.
    for (const int64_t width : {int64_t{64}, int64_t{300}, int64_t{5000}, int64_t{200000}}) {
        const auto& lv = c.levels[audio::levelForColumn(c, width)];
        CHECK(width / static_cast<int64_t>(lv.samplesPerPeak) + 2 <= 2 * static_cast<int64_t>(audio::kLevelRatio) + 2 ||
              lv.samplesPerPeak == c.levels.back().samplesPerPeak);
    }
}

static void testWaveformDisplay() {
    // Quiet audio: its own loudest sample is 3000/32768 (about 9%), well above the floor.
    std::vector<int16_t> pcm(512, 0);
    pcm[10] = 3000;
    pcm[300] = -1500;
    const auto p = pyramidOf(pcm);
    const float ref = audio::referenceLevel(p);
    CHECK(ref > 0.09f && ref < 0.092f);
    // The clip's own range: a stretch without the loud sample is lifted to its own loudest one.
    const float refQuietPart = audio::referenceLevel(p, 256, 512);
    CHECK(refQuietPart > 0.045f && refQuietPart < 0.092f);
    CHECK(audio::referenceLevel(p, 0, 0) == ref);  // an empty range means the whole source

    // Linear: the loudest sample fills the display, a quarter as loud is a quarter as tall.
    using audio::WaveScale;
    CHECK(audio::waveHeight(ref, ref, WaveScale::Linear) > 0.999f);
    CHECK(std::fabs(audio::waveHeight(ref * 0.25f, ref, WaveScale::Linear) - 0.25f) < 1e-4f);
    CHECK(audio::waveHeight(-ref * 0.25f, ref, WaveScale::Linear) > 0.24f);  // the sign is ignored
    CHECK(audio::waveHeight(ref * 4.0f, ref, WaveScale::Linear) <= 1.0f);
    CHECK(audio::waveHeight(0.0f, ref, WaveScale::Linear) == 0.0f);
    CHECK(audio::waveHeight(ref * 0.1f, ref, WaveScale::Linear) < audio::waveHeight(ref * 0.2f, ref, WaveScale::Linear));
    // Decibels: the reference fills the display, -6 dB is 1 - 6/54, the floor and below is empty, and it is monotonic.
    CHECK(audio::waveHeight(ref, ref, WaveScale::Decibel) > 0.999f);
    CHECK(std::fabs(audio::waveHeight(ref * 0.5f, ref, WaveScale::Decibel) - (1.0f - 6.0206f / audio::kWaveDbRange)) < 1e-3f);
    CHECK(audio::waveHeight(ref * 0.001f, ref, WaveScale::Decibel) == 0.0f);  // -60 dB
    CHECK(audio::waveHeight(0.0f, ref, WaveScale::Decibel) == 0.0f);
    CHECK(audio::waveHeight(ref * 0.01f, ref, WaveScale::Decibel) < audio::waveHeight(ref * 0.02f, ref, WaveScale::Decibel));
    // Quiet detail is much taller in decibels than linear; loud stays comparable.
    CHECK(audio::waveHeight(ref * 0.03f, ref, WaveScale::Decibel) > 4.0f * audio::waveHeight(ref * 0.03f, ref, WaveScale::Linear));
    CHECK(audio::waveScaleFromInt(0) == WaveScale::Linear && audio::waveScaleFromInt(1) == WaveScale::Decibel);
    CHECK(audio::waveScaleFromInt(7) == WaveScale::Linear && audio::waveScaleFromInt(-1) == WaveScale::Linear);

    // Near-silence is not amplified into a loud-looking waveform: the reference has a floor (24 dB of gain at most).
    std::vector<int16_t> hiss(512, 5);
    const auto quiet = pyramidOf(hiss);
    CHECK(audio::referenceLevel(quiet) == audio::kMinReferenceLevel);
    CHECK(audio::waveHeight(5.0f / 32768.0f, audio::referenceLevel(quiet), WaveScale::Linear) < 0.01f);
    CHECK(audio::referenceLevel(audio::PeakPyramid{}) == audio::kMinReferenceLevel);
    CHECK(audio::referenceLevel(audio::PeakPyramid{}, 10, 20) == audio::kMinReferenceLevel);
}

static void testWaveColumnGeometry() {
    // Whole pixels, about 0.8 dp, never below one.
    CHECK(timeline::waveColumnWidth(600.0f, 1.0f) == 1.0f);
    CHECK(timeline::waveColumnWidth(1000.0f, 1.0f) == 2.0f);  // 1000 one-pixel columns would be over the budget
    CHECK(timeline::waveColumnWidth(1000.0f, 2.0f) == 2.0f);
    CHECK(timeline::waveColumnWidth(1000.0f, 2.6f) == 2.0f);
    CHECK(timeline::waveColumnWidth(1000.0f, 3.5f) == 3.0f);
    CHECK(timeline::waveColumnWidth(0.0f, 2.6f) == 2.0f);

    // The vertex budget: however wide the visible part of a clip, a frame stays within kMaxWaveColumns columns of three
    // quads (18 vertices each), plus the one or two columns at the edges of the grid.
    for (const float density : {1.0f, 1.5f, 2.0f, 2.6f, 3.0f, 3.5f, 4.0f}) {
        for (const float width : {120.0f, 800.0f, 1080.0f, 1440.0f, 2400.0f, 3840.0f, 8000.0f}) {
            const float colW = timeline::waveColumnWidth(width, density);
            const int64_t first = timeline::waveFirstColumn(0.0f, 777.3, colW);
            const int64_t last = timeline::waveLastColumn(width, 777.3, colW);
            const int64_t columns = last - first + 1;
            CHECK(columns <= timeline::kMaxWaveColumns + 2);
            CHECK(timeline::waveVertexBudget(columns) <= static_cast<size_t>(timeline::kMaxWaveColumns + 2) * 18);
            CHECK(static_cast<float>(columns - 2) * colW <= width + colW);  // the columns cover the width and no more than that
            CHECK(static_cast<float>(columns) * colW >= width);
        }
    }
    CHECK(timeline::waveVertexBudget(0) == 0 && timeline::waveVertexBudget(-5) == 0);
    CHECK(timeline::waveVertexBudget(10) == 10 * 3 * 6);
    // A typical phone (1080 px, density 2.6): about 540 columns, 9.7k vertices, against 720 columns at most.
    const float colW = timeline::waveColumnWidth(1080.0f, 2.6f);
    CHECK(colW == 2.0f && timeline::waveLastColumn(1080.0f, 0.0, colW) - timeline::waveFirstColumn(0.0f, 0.0, colW) + 1 == 541);
}

static void testWaveSampleMapping() {
    // 48 kHz, 30 fps: 1600 samples per frame. Zoomed in to 96 px per frame a 2 px column is 33 samples: consecutive
    // columns must give consecutive, non-empty sample ranges, not blocks of identical columns (the whole-frame mapping did).
    const double ppf = 96.0;
    const float colW = 2.0f;
    int64_t previous = -1;
    for (int64_t col = 1000; col < 1100; ++col) {
        const int64_t s = timeline::waveSampleAtColumn(col, colW, 0.0, ppf, 0, 100000, 0, 48000, 30, 1);
        CHECK(s > previous);  // strictly increasing: every column has its own samples
        if (previous >= 0) CHECK(s - previous >= 32 && s - previous <= 34);
        previous = s;
    }
    // The columns tile the clip: the right edge of one is the left edge of the next by construction, and the source offset
    // and the clip start move the mapping the way they should.
    const int64_t at = timeline::waveSampleAtColumn(50, colW, 0.0, ppf, 0, 1000, 0, 48000, 30, 1);
    CHECK(timeline::waveSampleAtColumn(50, colW, 0.0, ppf, 0, 1000, 10, 48000, 30, 1) == at + 10 * 1600);
    CHECK(timeline::waveSampleAtColumn(50, colW, 0.0, ppf, 3, 1000, 0, 48000, 30, 1) < at);
    CHECK(timeline::waveSampleAtColumn(50, colW, 96.0, ppf, 0, 1000, 0, 48000, 30, 1) == timeline::waveSampleAtColumn(98, colW, 0.0, ppf, 0, 1000, 0, 48000, 30, 1));
    // Outside the clip the position clamps to its ends, so those columns come out empty and are skipped by the renderer.
    CHECK(timeline::waveSampleAtColumn(-500, colW, 0.0, ppf, 10, 100, 0, 48000, 30, 1) == 0);
    CHECK(timeline::waveSampleAtColumn(1000000, colW, 0.0, ppf, 10, 100, 0, 48000, 30, 1) == 100 * 1600);
    // 29.97 fps (30000/1001) is exact in integers: 100 frames = 160160 samples.
    CHECK(timeline::waveSampleAtColumn(100, 1.0f, 0.0, 1.0, 0, 1000, 0, 48000, 30000, 1001) == 160160);
}

static void testWaveColumnsOnSpeech() {
    // A speech-like signal: a 200 Hz tone with a syllable envelope and a silent gap. The column algorithm must show the
    // gap as empty columns (cut points), the syllables as envelope with a narrower RMS band inside it.
    const uint32_t rate = 48000;
    std::vector<int16_t> pcm(rate);  // one second
    for (size_t i = 0; i < pcm.size(); ++i) {
        const double t = static_cast<double>(i) / rate;
        double env = 0.0;
        if (t < 0.3) env = 0.5 * std::sin(3.14159265 * t / 0.3);
        else if (t > 0.5 && t < 0.9) env = 0.3 * std::sin(3.14159265 * (t - 0.5) / 0.4);
        pcm[i] = static_cast<int16_t>(env * 32767.0 * std::sin(2.0 * 3.14159265 * 200.0 * t));
    }
    const auto p = pyramidOf(pcm, 1, rate);
    const float ref = audio::referenceLevel(p, 0, rate);
    CHECK(ref > 0.22f && ref < 0.26f);  // half of the loudest peak (0.5), see kClipReferenceHeadroom
    // 100 columns of 480 samples each.
    int silent = 0, loud = 0, bandInsideEnvelope = 0;
    for (int col = 0; col < 100; ++col) {
        const auto wc = timeline::waveColumn(p, col * 480, (col + 1) * 480, ref, audio::WaveScale::Linear);
        if (wc.up == 0.0f && wc.down == 0.0f) ++silent;
        if (wc.up > 0.6f) ++loud;
        if (wc.rms <= std::max(wc.up, wc.down) && wc.rms >= 0.0f) ++bandInsideEnvelope;
        if (wc.up > 0.1f) CHECK(wc.rms <= wc.up);  // the RMS band sits inside the envelope
    }
    CHECK(silent >= 26 && silent <= 31);  // the gap from 0.3 s to 0.5 s (20 columns) and the tail after 0.9 s (10) are empty
    CHECK(loud >= 3);
    CHECK(bandInsideEnvelope == 100);
    // The same gap is visible in decibels too, but the hiss floor of quiet passages is lifted.
    const auto quietCol = timeline::waveColumn(p, 35 * 480, 36 * 480, ref, audio::WaveScale::Decibel);
    CHECK(quietCol.up == 0.0f || quietCol.up < 0.2f);
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
    for (size_t i = 0; i < p.levels.size(); ++i) CHECK(q.levels[i].rms == p.levels[i].rms && q.levels[i].hasRms() == (p.levels[i].samplesPerPeak >= audio::kFirstRmsSamplesPerPeak));
    // A cache from before the RMS block (version 1) is not read, so it is rebuilt.
    {
        FILE* v = std::fopen(path.c_str(), "r+b");
        CHECK(v != nullptr);
        if (v) {
            std::fseek(v, 4, SEEK_SET);
            const uint32_t one = 1;
            std::fwrite(&one, sizeof(one), 1, v);
            std::fclose(v);
        }
        CHECK(audio::loadPeaks(path, &q) == core::Status::UnsupportedFormat);
        CHECK(audio::savePeaks(path, p) == core::Status::Ok);
    }
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
    testClipKinds();
    testLabelHashAndUtf8();
    testShelfPackerAndLabelTable();
    testLabelLruBudget();
    testLabelLruOrder();
    testLabelLruReuse();
    testLabelLruMergesNeighbours();
    testLabelLruResetIsTheLastResort();
    testSnapGuideRect();
    testDragShadowLayers();
    testThumbnailFadeCurve();
    testRulerPlanAndLabels();
    testTheme();
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
    testPeakRms();
    testPeakSilenceAndClipping();
    testPeakShortClips();
    testPeakStereoDownmix();
    testPeakLongSourceDropsFineLevel();
    testPeakMipSelection();
    testWaveformDisplay();
    testWaveColumnGeometry();
    testWaveSampleMapping();
    testWaveColumnsOnSpeech();
    testPeaksFile();
    if (g_failures == 0) std::puts("host tests: all passed");
    return g_failures == 0 ? 0 : 1;
}
