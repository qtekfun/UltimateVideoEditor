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
    if (result.fps.num <= 0 || result.fps.den <= 0) return Status::BadSnapshot;
    // The clips come first, then the knots of all clips; the size must match exactly.
    if (count > (size - kAudioSnapshotHeaderBytes) / kAudioSnapshotClipBytes) return Status::BadSnapshot;
    const size_t clipsEnd = kAudioSnapshotHeaderBytes + static_cast<size_t>(count) * kAudioSnapshotClipBytes;
    const size_t knotBytes = size - clipsEnd;
    if (knotBytes % kAudioSnapshotKnotBytes != 0) return Status::BadSnapshot;
    size_t knotsLeft = knotBytes / kAudioSnapshotKnotBytes;
    const uint8_t* knotData = data + clipsEnd;

    result.clips.reserve(count);
    const uint8_t* p = data + kAudioSnapshotHeaderBytes;
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
        // Frames are bounded well below 2^40 so 128-bit time math can never overflow.
        constexpr int64_t kMaxFrame = int64_t{1} << 40;
        if (c.startFrame < 0 || c.startFrame > kMaxFrame || c.durationFrames <= 0 ||
            c.durationFrames > kMaxFrame || c.sourceInFrame < 0 || c.sourceInFrame > kMaxFrame ||
            c.sourceFps.num <= 0 || c.sourceFps.den <= 0 || !std::isfinite(c.gainDb) ||
            c.gainDb < kMinGainDb || c.gainDb > kMaxGainDb || c.fadeInFrames < 0 || c.fadeOutFrames < 0 ||
            c.fadeInFrames > c.durationFrames || c.fadeOutFrames > c.durationFrames) {
            return Status::BadSnapshot;
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
        result.clips.push_back(std::move(c));
    }
    if (knotsLeft != 0) return Status::BadSnapshot;  // knots nobody asked for
    *out = std::move(result);
    return Status::Ok;
}

}  // namespace uv::audio
