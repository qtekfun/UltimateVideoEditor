#include "audio/audio_snapshot.h"

#include <cmath>
#include <cstring>
#include <utility>

namespace uv::audio {

namespace {

template <typename T>
T readLe(const uint8_t* p) {
    T v;
    std::memcpy(&v, p, sizeof(T));  // snapshots are little endian, like every Android ABI we target
    return v;
}

bool finiteIn(float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; }

// Frames are bounded well below 2^40 so 128-bit time math can never overflow.
constexpr int64_t kMaxFrame = int64_t{1} << 40;

bool parseTrack(const uint8_t* p, AudioTrackDesc* t) {
    t->trackKey = readLe<int64_t>(p);
    t->gainDb = readLe<float>(p + 8);
    const uint32_t flags = readLe<uint32_t>(p + 12);
    const uint32_t role = readLe<uint32_t>(p + 16);
    t->muted = (flags & 1u) != 0;
    t->comp.enabled = (flags & 2u) != 0;
    t->comp.thresholdDb = readLe<float>(p + 20);
    t->comp.ratio = readLe<float>(p + 24);
    t->comp.attackMs = readLe<float>(p + 28);
    t->comp.releaseMs = readLe<float>(p + 32);
    t->comp.makeupDb = readLe<float>(p + 36);
    if (role > 2u) return false;
    t->role = static_cast<TrackRole>(role);
    return finiteIn(t->gainDb, kMinGainDb, kMaxGainDb) && finiteIn(t->comp.thresholdDb, -80.0f, 0.0f) &&
           finiteIn(t->comp.ratio, 1.0f, 20.0f) && finiteIn(t->comp.attackMs, 0.1f, 500.0f) &&
           finiteIn(t->comp.releaseMs, 1.0f, 5000.0f) && finiteIn(t->comp.makeupDb, 0.0f, 24.0f);
}

}  // namespace

core::Status parseAudioSnapshot(const uint8_t* data, size_t size, AudioSnapshotData* out) {
    using core::Status;
    if (data == nullptr || out == nullptr || size < kAudioSnapshotHeaderBytes) return Status::BadSnapshot;
    if (readLe<uint32_t>(data) != kAudioSnapshotMagic) return Status::BadSnapshot;
    if (readLe<uint32_t>(data + 4) != kAudioSnapshotVersion) return Status::BadSnapshot;

    AudioSnapshotData result;
    result.fps.num = readLe<int32_t>(data + 8);
    result.fps.den = readLe<int32_t>(data + 12);
    const uint32_t count = readLe<uint32_t>(data + 16);
    const uint32_t trackCount = readLe<uint32_t>(data + 20);
    result.limiterOff = (readLe<uint32_t>(data + 24) & 1u) != 0;
    if (result.fps.num <= 0 || result.fps.den <= 0 || trackCount > kMaxAudioTracks) return Status::BadSnapshot;

    // Tracks, ducking, then the clips; the knots and noise profiles follow and the size must match exactly.
    const size_t tracksEnd = kAudioSnapshotHeaderBytes + static_cast<size_t>(trackCount) * kAudioSnapshotTrackBytes;
    if (tracksEnd > size || size - tracksEnd < kAudioSnapshotDuckingBytes) return Status::BadSnapshot;
    const size_t duckEnd = tracksEnd + kAudioSnapshotDuckingBytes;
    if (count > (size - duckEnd) / kAudioSnapshotClipBytes) return Status::BadSnapshot;
    const size_t clipsEnd = duckEnd + static_cast<size_t>(count) * kAudioSnapshotClipBytes;

    for (uint32_t i = 0; i < trackCount; ++i) {
        AudioTrackDesc t;
        if (!parseTrack(data + kAudioSnapshotHeaderBytes + static_cast<size_t>(i) * kAudioSnapshotTrackBytes, &t)) {
            return Status::BadSnapshot;
        }
        result.tracks.push_back(t);
    }
    if (result.tracks.empty()) result.tracks.push_back(AudioTrackDesc{});

    const uint8_t* d = data + tracksEnd;
    result.ducking.amountDb = readLe<float>(d);
    result.ducking.thresholdDb = readLe<float>(d + 4);
    result.ducking.attackMs = readLe<float>(d + 8);
    result.ducking.releaseMs = readLe<float>(d + 12);
    if (!finiteIn(result.ducking.amountDb, 0.0f, 48.0f) || !finiteIn(result.ducking.thresholdDb, -80.0f, 0.0f) ||
        !finiteIn(result.ducking.attackMs, 1.0f, 5000.0f) || !finiteIn(result.ducking.releaseMs, 1.0f, 5000.0f)) {
        return Status::BadSnapshot;
    }

    // First pass over the clips to learn how many knots and profile floats follow them.
    uint64_t knotsTotal = 0, profilesTotal = 0;
    for (uint32_t i = 0; i < count; ++i) {
        const uint8_t* p = data + duckEnd + static_cast<size_t>(i) * kAudioSnapshotClipBytes;
        knotsTotal += readLe<uint32_t>(p + 60);
        const int32_t bins = readLe<int32_t>(p + 64 + 88);
        if (bins != 0 && bins != kDenoiseBins) return Status::BadSnapshot;
        profilesTotal += static_cast<uint64_t>(bins);
    }
    const size_t tail = size - clipsEnd;
    if (knotsTotal > tail / kAudioSnapshotKnotBytes) return Status::BadSnapshot;
    if (tail != knotsTotal * kAudioSnapshotKnotBytes + profilesTotal * sizeof(float)) return Status::BadSnapshot;
    size_t knotsLeft = static_cast<size_t>(knotsTotal);
    const uint8_t* knotData = data + clipsEnd;
    const uint8_t* profileData = knotData + knotsTotal * kAudioSnapshotKnotBytes;

    result.clips.reserve(count);
    const uint8_t* p = data + duckEnd;
    for (uint32_t i = 0; i < count; ++i, p += kAudioSnapshotClipBytes) {
        AudioClipDesc c;
        c.clipKey = readLe<int64_t>(p);
        c.assetKey = readLe<int64_t>(p + 8);
        c.startFrame = readLe<int64_t>(p + 16);
        c.durationFrames = readLe<int64_t>(p + 24);
        c.sourceInFrame = readLe<int64_t>(p + 32);
        c.sourceFps.num = readLe<int32_t>(p + 40);
        c.sourceFps.den = readLe<int32_t>(p + 44);
        c.gainDb = readLe<float>(p + 48);
        c.fadeInFrames = readLe<int32_t>(p + 52);
        c.fadeOutFrames = readLe<int32_t>(p + 56);
        const uint32_t knotCount = readLe<uint32_t>(p + 60);
        if (c.startFrame < 0 || c.startFrame > kMaxFrame || c.durationFrames <= 0 ||
            c.durationFrames > kMaxFrame || c.sourceInFrame < 0 || c.sourceInFrame > kMaxFrame ||
            c.sourceFps.num <= 0 || c.sourceFps.den <= 0 || !std::isfinite(c.gainDb) ||
            c.gainDb < kMinGainDb || c.gainDb > kMaxGainDb || c.fadeInFrames < 0 || c.fadeOutFrames < 0 ||
            c.fadeInFrames > c.durationFrames || c.fadeOutFrames > c.durationFrames) {
            return Status::BadSnapshot;
        }

        const uint8_t* a = p + 64;
        c.trackIndex = readLe<int32_t>(a);
        c.pan = readLe<float>(a + 4);
        c.userFadeInFrames = readLe<int32_t>(a + 8);
        c.userFadeOutFrames = readLe<int32_t>(a + 12);
        c.eq.highPassHz = readLe<float>(a + 16);
        c.eq.lowPassHz = readLe<float>(a + 20);
        for (int b = 0; b < dsp::kEqBands; ++b) {
            c.eq.bands[b].freqHz = readLe<float>(a + 24 + b * 12);
            c.eq.bands[b].gainDb = readLe<float>(a + 28 + b * 12);
            c.eq.bands[b].q = readLe<float>(a + 32 + b * 12);
        }
        c.denoiseStrength = readLe<float>(a + 84);
        const int32_t bins = readLe<int32_t>(a + 88);
        if (c.trackIndex < 0 || static_cast<size_t>(c.trackIndex) >= result.tracks.size() ||
            !finiteIn(c.pan, -1.0f, 1.0f) || c.userFadeInFrames < 0 || c.userFadeOutFrames < 0 ||
            c.userFadeInFrames > c.durationFrames || c.userFadeOutFrames > c.durationFrames ||
            !(c.eq.highPassHz == 0.0f || finiteIn(c.eq.highPassHz, dsp::kMinFilterHz, 20000.0f)) ||
            !(c.eq.lowPassHz == 0.0f || finiteIn(c.eq.lowPassHz, dsp::kMinFilterHz, 20000.0f)) ||
            !finiteIn(c.denoiseStrength, 0.0f, 1.0f) || (c.denoiseStrength > 0.0f) != (bins != 0)) {
            return Status::BadSnapshot;
        }
        for (const dsp::EqBandParams& b : c.eq.bands) {
            if (!finiteIn(b.freqHz, dsp::kMinFilterHz, 20000.0f) || !finiteIn(b.gainDb, dsp::kMinEqGainDb, dsp::kMaxEqGainDb) ||
                !finiteIn(b.q, 0.1f, 18.0f)) {
                return Status::BadSnapshot;
            }
        }

        if (knotCount != 0) {
            if (knotCount < 2 || knotCount > kMaxClipKnots || knotCount > knotsLeft) return Status::BadSnapshot;
            c.knots.reserve(knotCount);
            for (uint32_t k = 0; k < knotCount; ++k, knotData += kAudioSnapshotKnotBytes) {
                RetimeKnot knot;
                knot.frame = readLe<int64_t>(knotData);
                knot.sourceFrame = readLe<double>(knotData + 8);
                const bool inOrder = c.knots.empty() ? knot.frame == 0 : knot.frame > c.knots.back().frame;
                if (!inOrder || knot.frame > c.durationFrames || !std::isfinite(knot.sourceFrame) ||
                    std::fabs(knot.sourceFrame) > static_cast<double>(kMaxFrame)) {
                    return Status::BadSnapshot;
                }
                c.knots.push_back(knot);
            }
            knotsLeft -= knotCount;
            if (c.knots.back().frame != c.durationFrames) return Status::BadSnapshot;
        }
        if (bins != 0) {
            c.noiseProfile.resize(static_cast<size_t>(bins));
            for (int32_t k = 0; k < bins; ++k, profileData += sizeof(float)) {
                const float v = readLe<float>(profileData);
                if (!std::isfinite(v) || v < 0.0f) return Status::BadSnapshot;
                c.noiseProfile[static_cast<size_t>(k)] = v;
            }
        }
        result.clips.push_back(std::move(c));
    }
    if (knotsLeft != 0) return Status::BadSnapshot;  // knots nobody asked for
    *out = std::move(result);
    return Status::Ok;
}

}  // namespace uv::audio
