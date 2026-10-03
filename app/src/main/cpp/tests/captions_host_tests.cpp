// GoogleTest-free host tests for the pure part of the captions pipeline: the stereo -> mono 16 kHz
// converter that feeds the speech model. Build/run: see tests/CMakeLists.txt.
#include <cmath>
#include <cstdio>
#include <vector>

#include "captions/mono16k.h"

using namespace uv::captions;

static int g_failures = 0;
#define CHECK(...)                                                                      \
    do {                                                                                \
        if (!(__VA_ARGS__)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #__VA_ARGS__); \
            ++g_failures;                                                               \
        }                                                                               \
    } while (0)

static std::vector<float> stereoOf(const std::vector<float>& left, const std::vector<float>& right) {
    std::vector<float> out;
    for (size_t i = 0; i < left.size(); ++i) {
        out.push_back(left[i]);
        out.push_back(right[i]);
    }
    return out;
}

static std::vector<float> convertAll(int32_t rate, const std::vector<float>& stereo, size_t chunkFrames) {
    Mono16kConverter c(rate);
    std::vector<float> out;
    const size_t frames = stereo.size() / 2;
    for (size_t at = 0; at < frames; at += chunkFrames) {
        const size_t n = std::min(chunkFrames, frames - at);
        c.process(stereo.data() + 2 * at, n, &out);
    }
    return out;
}

static void testLengthsAreExact() {
    // One second at each common rate yields exactly 16000 samples (a whole number of spans).
    for (int32_t rate : {16000, 32000, 48000, 44100, 22050, 96000}) {
        const std::vector<float> silence(static_cast<size_t>(rate) * 2, 0.0f);
        const auto out = convertAll(rate, silence, 4096);
        CHECK(out.size() == 16000);
    }
    // 10 s of 44.1 kHz: floor(441000 * 16000 / 44100) = 160000.
    const std::vector<float> ten(441000 * 2, 0.0f);
    CHECK(convertAll(44100, ten, 1000).size() == 160000);
}

static void testConstantStaysConstantAndStereoAverages() {
    const std::vector<float> left(48000, 0.8f);
    const std::vector<float> right(48000, 0.2f);
    const auto out = convertAll(48000, stereoOf(left, right), 777);
    CHECK(out.size() == 16000);
    bool allHalf = true;
    for (float v : out) allHalf = allHalf && std::fabs(v - 0.5f) < 1e-5f;
    CHECK(allHalf);
}

static void testChunkingDoesNotChangeTheResult() {
    std::vector<float> l;
    std::vector<float> r;
    for (int i = 0; i < 44100 * 3; ++i) {
        l.push_back(std::sin(i * 0.01f));
        r.push_back(std::cos(i * 0.013f));
    }
    const auto stereo = stereoOf(l, r);
    const auto whole = convertAll(44100, stereo, stereo.size() / 2);
    for (size_t chunk : {1u, 7u, 1000u, 8192u, 40000u}) {
        const auto parts = convertAll(44100, stereo, chunk);
        CHECK(parts.size() == whole.size());
        bool same = parts.size() == whole.size();
        for (size_t i = 0; same && i < whole.size(); ++i) same = whole[i] == parts[i];
        CHECK(same);
    }
}

static void testBoxAverageOfARamp() {
    // 48 kHz -> 16 kHz averages three input samples per output: a ramp 0,1,2,... maps to 1,4,7,...
    std::vector<float> ramp;
    for (int i = 0; i < 48000; ++i) ramp.push_back(static_cast<float>(i));
    const auto out = convertAll(48000, stereoOf(ramp, ramp), 5000);
    CHECK(out.size() == 16000);
    CHECK(std::fabs(out[0] - 1.0f) < 1e-3f);
    CHECK(std::fabs(out[1] - 4.0f) < 1e-3f);
    CHECK(std::fabs(out[100] - 301.0f) < 1e-2f);
}

static void testHighFrequencyIsAttenuatedNotAliased() {
    // 12 kHz tone sampled at 48 kHz is above the 8 kHz Nyquist of the output; a box filter must not
    // let it through at full amplitude.
    std::vector<float> tone;
    for (int i = 0; i < 48000; ++i) tone.push_back(std::sin(2.0f * 3.14159265f * 12000.0f * static_cast<float>(i) / 48000.0f));
    const auto out = convertAll(48000, stereoOf(tone, tone), 4096);
    float peak = 0.0f;
    for (float v : out) peak = std::max(peak, std::fabs(v));
    CHECK(peak < 0.6f);
}

static void testUpsamplingTerminatesAndCounts() {
    // 8 kHz telephone audio is held (box) up to 16 kHz: length doubles.
    const std::vector<float> in(8000 * 2, 0.25f);
    const auto out = convertAll(8000, in, 333);
    CHECK(out.size() == 16000);
    CHECK(std::fabs(out.back() - 0.25f) < 1e-6f);
}

static void testCountersTrackInput() {
    Mono16kConverter c(48000);
    std::vector<float> out;
    const std::vector<float> block(1200 * 2, 0.0f);
    c.process(block.data(), 1200, &out);
    CHECK(c.inputFrames() == 1200);
    CHECK(c.outputSamples() == 400);
    CHECK(out.size() == 400);
}

int main() {
    testLengthsAreExact();
    testConstantStaysConstantAndStereoAverages();
    testChunkingDoesNotChangeTheResult();
    testBoxAverageOfARamp();
    testHighFrequencyIsAttenuatedNotAliased();
    testUpsamplingTerminatesAndCounts();
    testCountersTrackInput();
    if (g_failures != 0) {
        std::fprintf(stderr, "%d check(s) failed\n", g_failures);
        return 1;
    }
    std::puts("captions host tests passed");
    return 0;
}
