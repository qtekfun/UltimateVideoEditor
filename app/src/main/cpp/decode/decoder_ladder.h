#pragma once

#include <algorithm>
#include <string>
#include <vector>

namespace uv::decode {

// Decoder output goes to an AImageReader so every frame arrives as an AHardwareBuffer. Vendor decoders disagree about
// which reader they accept: the Huawei hisi decoder asks for more undequeued buffers than a reader of 8 images can
// give and AMediaCodec_start fails (-10000), while the same decoder runs fine with 6. Opening a decoder therefore walks
// this ladder and the first rung whose codec starts wins. Pure data, no NDK types, so host tests cover the order.
enum class ReaderFormat { Private, Yuv420 };

struct DecoderRung {
    std::string label;
    ReaderFormat format;
    int maxImages;
    std::string codecName;  // empty: the platform's default decoder for the mime type
    bool software() const { return !codecName.empty(); }
};

// What the ladder needs to know about the stream. Zero sizes mean unknown.
struct StreamShape {
    int width = 0;
    int height = 0;
    bool tenBit = false;
};

// Whether the stream is coded with more than 8 bits: a 10-bit or higher profile, or an HDR transfer (HLG, PQ), which phones
// only record in 10 bits. Profile values are MediaCodecInfo.CodecProfileLevel's; transfer values are MediaFormat's.
inline bool isTenBitStream(const std::string& mime, int profile, int transfer) {
    if (transfer == 6 || transfer == 7) return true;  // COLOR_TRANSFER_ST2084, COLOR_TRANSFER_HLG
    if (mime == "video/hevc" || mime == "video/dolby-vision") return profile == 0x2 || profile == 0x1000 || profile == 0x2000;
    if (mime == "video/avc") return profile == 0x10 || profile == 0x20 || profile == 0x40;  // High 10, 4:2:2, 4:4:4
    return false;
}

// The platform software decoders (c2.android.avc/hevc.decoder) decode 8 bits only, and vendors cap them, often at 1080p
// (some list 2x2-1920x1088). Asked for more they still start, then never output a picture: a 4K HLG export fell back
// to one and stalled. Such a stream gets no software rung, so a failed open says so instead.
inline bool platformSoftwareCanDecode(const StreamShape& shape) {
    if (shape.tenBit) return false;
    if (shape.width <= 0 || shape.height <= 0) return true;
    return std::max(shape.width, shape.height) <= 1920 && std::min(shape.width, shape.height) <= 1088;
}

inline std::vector<DecoderRung> buildDecoderLadder(const std::string& mime, const StreamShape& shape = {}) {
    const std::string softwareName = mime == "video/hevc" ? "c2.android.hevc.decoder" : "c2.android.avc.decoder";
    std::vector<DecoderRung> ladder;
    // The preferred reader first (8 images keeps the most frames in flight), then fewer images for decoders that
    // reserve extra buffers of their own, then a YUV reader, and the platform software decoder as the last resort.
    for (const int images : {8, 6, 4, 3}) {
        ladder.push_back({"default decoder, PRIVATE reader, " + std::to_string(images) + " images", ReaderFormat::Private,
                          images, ""});
    }
    ladder.push_back({"default decoder, YUV_420_888 reader, 4 images", ReaderFormat::Yuv420, 4, ""});
    if (platformSoftwareCanDecode(shape)) {
        ladder.push_back({"software decoder, PRIVATE reader, 8 images", ReaderFormat::Private, 8, softwareName});
    }
    return ladder;
}

}  // namespace uv::decode
