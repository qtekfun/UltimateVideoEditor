// Host tests of the FFmpeg fallback's readers against a real libav: decoding of formats the platform
// MediaCodec path rejects (MPEG-2, MPEG-4 part 2, ProRes), exact frame indices, seeking to a frame inside a long
// GOP, fractional frame rates, and the software audio decoder.
//
// Needs libav development files and the ffmpeg CLI to make the clips: scripts/run-ffmpeg-host-tests.sh builds
// and runs this (CI job "FFmpeg host tests" in .github/workflows/ffmpeg.yml). Not part of the default test run.
//
// Each test clip carries its frame number in the picture: frame N is flat grey with luma (3N mod 200) + 20, so a
// decoded picture identifies itself even through lossy coding. Usage: ffmpeg_reader_tests <directory with clips>.
#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "audio/ffmpeg_pcm.h"
#include "decode/ffmpeg/software_reader.h"

using namespace uv::decode;
using namespace uv::decode::ffmpeg;

static int g_failures = 0;
static int g_skipped = 0;
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++g_failures;                                                        \
        }                                                                        \
    } while (0)

namespace {

std::string g_dir;

int openClip(const std::string& name) { return open((g_dir + "/" + name).c_str(), O_RDONLY | O_CLOEXEC); }

// Expected grey (full-range RGB) of frame N: limited-range luma (3N mod 200) + 20 expanded to 0..255.
int expectedGrey(int64_t n) {
    const int luma = static_cast<int>((n * 3) % 200) + 20;
    return std::clamp((luma - 16) * 255 / 219, 0, 255);
}

struct Picture {
    int64_t index = -1;
    int grey = -1;
};

// Decodes the next picture and reads the centre pixel's green channel.
bool nextPicture(SoftwareVideoReader& r, Picture* p) {
    std::string error;
    int64_t index = 0;
    const ReadStatus s = r.decode(&index, &error);
    if (s != ReadStatus::Frame) return false;
    const int w = r.info().width;
    const int h = r.info().height;
    std::vector<uint8_t> rgba(static_cast<size_t>(w) * h * 4);
    if (!r.convertRgba(rgba.data(), w * 4, &error)) {
        std::fprintf(stderr, "convert failed: %s\n", error.c_str());
        return false;
    }
    p->index = index;
    p->grey = rgba[(static_cast<size_t>(h / 2) * w + w / 2) * 4 + 1];
    return true;
}

bool openReader(const char* name, Rational fps, std::unique_ptr<SoftwareVideoReader>* out) {
    const int fd = openClip(name);
    if (fd < 0) {
        std::printf("SKIP %s (not generated)\n", name);
        ++g_skipped;
        return false;
    }
    auto opened = SoftwareVideoReader::open(fd, fps, 2);
    if (!opened.ok()) {
        std::fprintf(stderr, "FAIL open %s: %s\n", name, opened.error().message.c_str());
        ++g_failures;
        return false;
    }
    *out = std::move(opened.value());
    return true;
}

void testVideoClip(const char* name, int64_t frames, int tolerance) {
    std::unique_ptr<SoftwareVideoReader> r;
    if (!openReader(name, {0, 0}, &r)) return;
    const VideoStreamInfo& info = r->info();
    std::printf("%s: %s %dx%d %lld/%lld fps, %lld frames\n", name, info.codec.c_str(), info.width, info.height,
                static_cast<long long>(info.fps.num), static_cast<long long>(info.fps.den), static_cast<long long>(info.durationFrames));
    CHECK(info.width == 320 && info.height == 240);
    CHECK(info.fps.num == 25 && info.fps.den == 1);
    // Exact when the container knows its duration; a few frames over when it is an estimate (MPEG-PS).
    CHECK(info.durationFrames >= frames - 1 && info.durationFrames <= frames + 4);

    // Sequential: every index once, in order, from 0, with the right picture.
    int64_t expected = 0;
    int worst = 0;
    Picture p;
    while (nextPicture(*r, &p)) {
        CHECK(p.index == expected);
        worst = std::max(worst, std::abs(p.grey - expectedGrey(p.index)));
        ++expected;
    }
    CHECK(expected == frames);
    CHECK(worst <= tolerance);
    std::printf("  sequential: %lld frames, worst grey error %d\n", static_cast<long long>(expected), worst);

    // Seeking: land at or before the target, decode forward to it exactly, then continue in order.
    std::string error;
    for (int64_t target : {0LL, 1LL, 11LL, 13LL, 57LL, 99LL}) {
        if (target >= frames) continue;
        CHECK(r->seek(target, &error));
        bool reached = false;
        int64_t first = -1;
        int64_t last = -1;
        int grey = -1;
        for (int i = 0; i < 400 && nextPicture(*r, &p); ++i) {
            if (first < 0) first = p.index;
            last = p.index;
            grey = p.grey;
            if (p.index >= target) {
                reached = true;
                break;
            }
        }
        std::printf("  seek %lld: first %lld, stopped at %lld (grey %d, expected %d)\n", static_cast<long long>(target),
                    static_cast<long long>(first), static_cast<long long>(last), grey, expectedGrey(target));
        CHECK(reached);
        CHECK(first <= target);
        CHECK(last == target);
        CHECK(std::abs(grey - expectedGrey(target)) <= tolerance);
        if (reached && target + 1 < frames) {
            CHECK(nextPicture(*r, &p));
            CHECK(p.index == target + 1);
        }
    }

    // After the last frame the stream ends.
    CHECK(r->seek(frames - 1, &error));
    while (nextPicture(*r, &p)) {
    }
    int64_t index = 0;
    CHECK(r->decode(&index, &error) == ReadStatus::EndOfStream);
}

void testFrameRateOverride() {
    std::unique_ptr<SoftwareVideoReader> r;
    if (!openReader("mpeg2_gop12.mpg", {50, 1}, &r)) return;
    CHECK(r->info().fps.num == 50 && r->info().fps.den == 1);
    CHECK(r->info().durationFrames >= 199 && r->info().durationFrames <= 206);  // 4 s at the overridden rate
}

void testNtsc() {
    std::unique_ptr<SoftwareVideoReader> r;
    if (!openReader("ntsc.mpg", {0, 0}, &r)) return;
    CHECK(r->info().fps.num == 30000 && r->info().fps.den == 1001);
    int64_t expected = 0;
    Picture p;
    while (nextPicture(*r, &p)) {
        CHECK(p.index == expected);
        ++expected;
    }
    CHECK(expected == 90);
}

// Counts upward crossings of the left channel through zero with hysteresis (a sine of f Hz crosses f times
// per second; codec noise near zero must not add crossings).
int upwardCrossings(const std::vector<float>& stereo) {
    float peak = 0.0f;
    for (size_t i = 0; i < stereo.size() / 2; ++i) peak = std::max(peak, std::fabs(stereo[i * 2]));
    const float hysteresis = 0.1f * peak;
    int n = 0;
    bool low = false;
    for (size_t i = 0; i < stereo.size() / 2; ++i) {
        const float v = stereo[i * 2];
        if (v < -hysteresis) low = true;
        if (low && v > hysteresis) {
            ++n;
            low = false;
        }
    }
    return n;
}

void testAudio(const char* name) {
    const int fd = openClip(name);
    if (fd < 0) {
        std::printf("SKIP %s (not generated)\n", name);
        ++g_skipped;
        return;
    }
    uv::core::Status st = uv::core::Status::Ok;
    auto decoder = uv::audio::openSoftwarePcmDecoder(fd, &st);
    close(fd);
    CHECK(decoder != nullptr);
    if (!decoder) return;
    CHECK(decoder->sampleRate() == 48000);

    std::vector<float> all;
    std::vector<float> chunk(4096 * 2);
    for (;;) {
        const auto r = decoder->read(chunk.data(), 4096);
        CHECK(r.status == uv::core::Status::Ok);
        all.insert(all.end(), chunk.begin(), chunk.begin() + static_cast<long>(r.frames) * 2);
        if (r.eof || r.status != uv::core::Status::Ok) break;
    }
    const long frames = static_cast<long>(all.size() / 2);
    float peak = 0.0f;
    for (float v : all) peak = std::max(peak, std::fabs(v));
    std::printf("%s: %ld frames, %d crossings, peak %.3f\n", name, frames, upwardCrossings(all), static_cast<double>(peak));
    CHECK(std::labs(frames - 96000) <= 4096);                  // 2 s at 48 kHz, minus/plus codec padding
    CHECK(std::abs(upwardCrossings(all) - 880) <= 16);        // 440 Hz for 2 s
    CHECK(peak > 0.2f && peak <= 1.1f);

    // Exact seek: reading from 1 s yields the second half, still a clean 440 Hz.
    CHECK(decoder->seekToMicros(1000000) == uv::core::Status::Ok);
    std::vector<float> tail;
    for (;;) {
        const auto r = decoder->read(chunk.data(), 4096);
        tail.insert(tail.end(), chunk.begin(), chunk.begin() + static_cast<long>(r.frames) * 2);
        if (r.eof || r.status != uv::core::Status::Ok) break;
    }
    const long tailFrames = static_cast<long>(tail.size() / 2);
    CHECK(std::labs(tailFrames - 48000) <= 4096);
    CHECK(std::abs(upwardCrossings(tail) - 440) <= 10);
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <directory with the generated clips>\n", argv[0]);
        return 2;
    }
    g_dir = argv[1];
    testVideoClip("mpeg2_gop12.mpg", 100, 10);
    testVideoClip("mpeg2_gop100.mpg", 100, 10);  // a long GOP: seeks land far before the target
    testVideoClip("mpeg4.avi", 100, 10);
    testVideoClip("prores.mov", 100, 6);
    testVideoClip("h264.mp4", 100, 8);  // MediaCodec handles H.264 on a phone; here it checks the reader on a B-frame stream
    testFrameRateOverride();
    testNtsc();
    testAudio("aac.m4a");
    testAudio("ac3.ac3");
    if (g_failures == 0) std::printf("all FFmpeg reader tests passed (%d skipped)\n", g_skipped);
    return g_failures == 0 ? 0 : 1;
}
