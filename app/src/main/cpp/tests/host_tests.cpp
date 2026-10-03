// GoogleTest-free host tests for the pure-logic parts of the timeline engine.
// Build/run: see tests/CMakeLists.txt (documented in CLAUDE.md).
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "audio/waveform_peaks.h"
#include "timeline_view/drop_hint.h"
#include "timeline_view/hit_test.h"
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
                        const std::vector<timeline::RetimeSnapshot>& retimes = {}) {
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
        w.put<int32_t>((c.selected ? 1 : 0) | (c.hasFx ? 2 : 0));
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
    return w;
}

static timeline::ClipSnapshot clip(int64_t key, int track, int64_t start, int64_t dur) {
    return {key, track, 0, start, dur, 0, 30, 1, false};
}

static void testSnapshotRoundTrip() {
    auto buf = makeSnapshot(2, {clip(7, 0, 0, 100), clip(8, 1, 50, 25)});
    CHECK(buf.b.size() == timeline::kSnapshotHeaderBytes + 2 * 4 + 2 * timeline::kSnapshotClipBytes + 4 + 4 + 4);
    timeline::TimelineSnapshot s;
    CHECK(timeline::parseSnapshot(buf.b.data(), buf.b.size(), &s) == core::Status::Ok);
    CHECK(s.tracks.size() == 2 && s.clips.size() == 2);
    CHECK(s.clips[1].clipKey == 8 && s.clips[1].startFrame == 50);
    CHECK(s.fpsNum == 30000 && s.fpsDen == 1001);
    CHECK(s.endFrame() == 100);
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
    auto future = makeSnapshot(1, {clip(1, 0, 0, 10)}, {}, {}, 5);
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
    testSnapshotTransitions();
    testSnapshotKeyframes();
    testSnapshotRetimes();
    testRetimeBoundaries();
    testSnapshotRejectsBadInput();
    testViewport();
    testHitTest();
    testBottomAnchoredLanes();
    testDropHintGeometry();
    testPeaks();
    testViewportFit();
    testViewportEnsureVisible();
    testWaveformDisplay();
    testPeaksFile();
    if (g_failures == 0) std::puts("host tests: all passed");
    return g_failures == 0 ? 0 : 1;
}
