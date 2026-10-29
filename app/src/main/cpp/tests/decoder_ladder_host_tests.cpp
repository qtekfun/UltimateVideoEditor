// Host tests for the decoder open ladder: the order of fallbacks that keeps preview working on vendor decoders that
// refuse the preferred reader configuration (Huawei hisi on the MatePad's Maleoon GPU).
#include <cstdio>
#include <set>
#include <string>

#include "decode/decoder_ladder.h"

using namespace uv::decode;

static int g_failures = 0;
#define CHECK(...)                                                                    \
    do {                                                                              \
        if (!(__VA_ARGS__)) {                                                         \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
            ++g_failures;                                                             \
        }                                                                             \
    } while (0)

int main() {
    const auto avc = buildDecoderLadder("video/avc");
    CHECK(!avc.empty());
    // The preferred configuration comes first: the default decoder, a PRIVATE reader, 8 images.
    CHECK(avc.front().format == ReaderFormat::Private);
    CHECK(avc.front().maxImages == 8);
    CHECK(!avc.front().software());
    // The software decoder is the last resort and the only software rung, so hardware is always tried first.
    CHECK(avc.back().software());
    int software = 0;
    for (const auto& rung : avc) software += rung.software() ? 1 : 0;
    CHECK(software == 1);
    // The 6-image rung is the one that starts the hisi hardware decoder: it must be tried before the software one.
    int sixIndex = -1;
    for (size_t i = 0; i < avc.size(); ++i) {
        if (!avc[i].software() && avc[i].format == ReaderFormat::Private && avc[i].maxImages == 6) sixIndex = static_cast<int>(i);
    }
    CHECK(sixIndex > 0 && sixIndex < static_cast<int>(avc.size()) - 1);
    // Hardware rungs with the same reader format try fewer images in turn.
    int previous = 1 << 30;
    for (const auto& rung : avc) {
        if (rung.software() || rung.format != ReaderFormat::Private) continue;
        CHECK(rung.maxImages < previous);
        previous = rung.maxImages;
    }
    // Labels are unique so a log line identifies the rung.
    std::set<std::string> labels;
    for (const auto& rung : avc) labels.insert(rung.label);
    CHECK(labels.size() == avc.size());
    // Every reader keeps at least one image to decode into.
    for (const auto& rung : avc) CHECK(rung.maxImages >= 2);

    // The software fallback matches the stream's codec.
    CHECK(avc.back().codecName == "c2.android.avc.decoder");
    CHECK(buildDecoderLadder("video/hevc").back().codecName == "c2.android.hevc.decoder");
    CHECK(buildDecoderLadder("video/hevc").size() == avc.size());

    // The platform software decoders are 8-bit only and capped (often at 1920x1088): a stream they cannot decode gets no
    // software rung, or the open "succeeds" and the decoder never outputs a picture (a 4K HLG export stalled on it).
    const auto hasSoftware = [](const std::vector<DecoderRung>& ladder) {
        for (const auto& rung : ladder) if (rung.software()) return true;
        return false;
    };
    CHECK(hasSoftware(buildDecoderLadder("video/hevc", StreamShape{1920, 1080, false})));
    CHECK(hasSoftware(buildDecoderLadder("video/avc", StreamShape{1080, 1920, false})));  // portrait 1080p
    CHECK(hasSoftware(buildDecoderLadder("video/hevc", StreamShape{1920, 1088, false})));
    CHECK(!hasSoftware(buildDecoderLadder("video/hevc", StreamShape{3840, 2160, false})));
    CHECK(!hasSoftware(buildDecoderLadder("video/hevc", StreamShape{2560, 1440, false})));
    CHECK(!hasSoftware(buildDecoderLadder("video/hevc", StreamShape{1920, 1080, true})));
    CHECK(!hasSoftware(buildDecoderLadder("video/hevc", StreamShape{3840, 2160, true})));
    CHECK(hasSoftware(buildDecoderLadder("video/hevc", StreamShape{})));  // unknown shape: keep the last resort
    // The hardware rungs are the same whatever the stream.
    CHECK(buildDecoderLadder("video/hevc", StreamShape{3840, 2160, true}).size() == buildDecoderLadder("video/hevc").size() - 1);
    CHECK(!buildDecoderLadder("video/hevc", StreamShape{3840, 2160, true}).back().software());

    // Ten bits: an HDR transfer, or a 10-bit profile.
    CHECK(isTenBitStream("video/hevc", 1, 7));        // HLG recorded as Main by the container
    CHECK(isTenBitStream("video/hevc", -1, 6));       // PQ, no profile key
    CHECK(isTenBitStream("video/hevc", 0x2, 3));      // Main10, SDR
    CHECK(isTenBitStream("video/hevc", 0x1000, 0));   // Main10 HDR10
    CHECK(isTenBitStream("video/hevc", 0x2000, 0));   // Main10 HDR10+
    CHECK(isTenBitStream("video/dolby-vision", 0x2, 0));
    CHECK(isTenBitStream("video/avc", 0x10, 3));      // High 10
    CHECK(!isTenBitStream("video/hevc", 1, 3));       // Main, SDR
    CHECK(!isTenBitStream("video/avc", 0x8, 3));      // High
    CHECK(!isTenBitStream("video/avc", -1, 0));

    if (g_failures != 0) {
        std::fprintf(stderr, "%d failure(s)\n", g_failures);
        return 1;
    }
    std::printf("decoder ladder host tests passed\n");
    return 0;
}
