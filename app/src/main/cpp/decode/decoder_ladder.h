#pragma once

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

inline std::vector<DecoderRung> buildDecoderLadder(const std::string& mime) {
    const std::string softwareName = mime == "video/hevc" ? "c2.android.hevc.decoder" : "c2.android.avc.decoder";
    std::vector<DecoderRung> ladder;
    // The preferred reader first (8 images keeps the most frames in flight), then fewer images for decoders that
    // reserve extra buffers of their own, then a YUV reader, and the platform software decoder as the last resort.
    for (const int images : {8, 6, 4, 3}) {
        ladder.push_back({"default decoder, PRIVATE reader, " + std::to_string(images) + " images", ReaderFormat::Private,
                          images, ""});
    }
    ladder.push_back({"default decoder, YUV_420_888 reader, 4 images", ReaderFormat::Yuv420, 4, ""});
    ladder.push_back({"software decoder, PRIVATE reader, 8 images", ReaderFormat::Private, 8, softwareName});
    return ladder;
}

}  // namespace uv::decode
