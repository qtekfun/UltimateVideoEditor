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

    if (g_failures != 0) {
        std::fprintf(stderr, "%d failure(s)\n", g_failures);
        return 1;
    }
    std::printf("decoder ladder host tests passed\n");
    return 0;
}
