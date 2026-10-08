// Host reference rasteriser of the timeline's audio clip waveform, to judge the drawing without a phone.
//
//   waveform_render_tool --pcm in.s16le --channels 2 --rate 48000 --fps 60 --start-sec 12 --ppf 6 --width 1080
//                        --density 2.6 --mode before|after [--scale linear|db] --out clip.ppm
//
// "after" runs the same column code the renderer uses (timeline_view/wave_columns.h, audio/waveform_peaks.*) and the same
// layers and colours as TimelineRenderer. "before" reproduces the previous drawing (2 dp columns, whole-frame positions, sqrt
// of the amplitude against the loudest sample of the file, one solid band). Developer aid only, not a test.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "audio/waveform_peaks.h"
#include "timeline_view/wave_columns.h"

using namespace uv;

namespace {

struct Color {
    float r, g, b, a;
};
Color mix(Color a, Color b, float t) { return {a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, a.a + (b.a - a.a) * t}; }
Color withAlpha(Color c, float a) { return {c.r, c.g, c.b, a}; }
Color scaled(Color c, float k) { return {c.r * k, c.g * k, c.b * k, c.a}; }

struct Canvas {
    int w, h;
    std::vector<float> px;  // rgb
    Canvas(int w_, int h_) : w(w_), h(h_), px(static_cast<size_t>(w_) * h_ * 3, 0.0f) {}
    // Source-over like GL_SRC_ALPHA, ONE_MINUS_SRC_ALPHA; coverage of partial pixels as the GPU's edge pixels would be
    // (rects are not anti-aliased: a pixel is covered when its centre is inside).
    void rect(float x0, float y0, float x1, float y1, Color c) {
        const int ix0 = std::max(0, static_cast<int>(std::lround(x0))), ix1 = std::min(w, static_cast<int>(std::lround(x1)));
        const int iy0 = std::max(0, static_cast<int>(std::lround(y0))), iy1 = std::min(h, static_cast<int>(std::lround(y1)));
        for (int y = iy0; y < iy1; ++y) {
            for (int x = ix0; x < ix1; ++x) {
                float* p = &px[(static_cast<size_t>(y) * w + x) * 3];
                p[0] = p[0] * (1 - c.a) + c.r * c.a;
                p[1] = p[1] * (1 - c.a) + c.g * c.a;
                p[2] = p[2] * (1 - c.a) + c.b * c.a;
            }
        }
    }
    void gradient(float x0, float y0, float x1, float y1, Color top, Color bottom) {
        for (int y = static_cast<int>(std::lround(y0)); y < static_cast<int>(std::lround(y1)); ++y) {
            const float t = (static_cast<float>(y) + 0.5f - y0) / (y1 - y0);
            rect(x0, static_cast<float>(y), x1, static_cast<float>(y + 1), mix(top, bottom, t));
        }
    }
    void writePpm(const char* path) const {
        FILE* f = std::fopen(path, "wb");
        if (f == nullptr) std::exit(2);
        std::fprintf(f, "P6\n%d %d\n255\n", w, h);
        for (float v : px) std::fputc(static_cast<int>(std::lround(std::clamp(v, 0.0f, 1.0f) * 255.0f)), f);
        std::fclose(f);
    }
};

Color hex(uint32_t argb) {
    return {static_cast<float>((argb >> 16) & 0xFF) / 255.0f, static_cast<float>((argb >> 8) & 0xFF) / 255.0f,
            static_cast<float>(argb & 0xFF) / 255.0f, 1.0f};
}

const char* arg(int argc, char** argv, const char* name, const char* fallback) {
    for (int i = 1; i + 1 < argc; ++i) {
        if (std::strcmp(argv[i], name) == 0) return argv[i + 1];
    }
    return fallback;
}

// The previous drawing's amplitude shaping: signed sqrt of amplitude / loudest sample of the file (floor 0.02).
float legacyDisplay(float amplitude, float reference) {
    const float x = std::min(1.0f, std::fabs(amplitude) / std::max(reference, 0.02f));
    const float shaped = std::sqrt(x);
    return amplitude < 0.0f ? -shaped : shaped;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string pcmPath = arg(argc, argv, "--pcm", "");
    const int channels = std::atoi(arg(argc, argv, "--channels", "2"));
    const int rate = std::atoi(arg(argc, argv, "--rate", "48000"));
    const int fps = std::atoi(arg(argc, argv, "--fps", "30"));
    const double startSec = std::atof(arg(argc, argv, "--start-sec", "0"));
    const double ppf = std::atof(arg(argc, argv, "--ppf", "4"));
    const int width = std::atoi(arg(argc, argv, "--width", "1080"));
    const float density = static_cast<float>(std::atof(arg(argc, argv, "--density", "2.6")));
    const std::string mode = arg(argc, argv, "--mode", "after");
    const audio::WaveScale scale = std::string(arg(argc, argv, "--scale", "linear")) == "db" ? audio::WaveScale::Decibel : audio::WaveScale::Linear;
    const std::string out = arg(argc, argv, "--out", "wave.ppm");
    FILE* f = std::fopen(pcmPath.c_str(), "rb");
    if (f == nullptr || channels < 1) {
        std::fprintf(stderr, "cannot read --pcm\n");
        return 1;
    }
    audio::PeakBuilder builder(static_cast<uint32_t>(rate), channels);
    std::vector<int16_t> buf(static_cast<size_t>(channels) * 65536);
    size_t got;
    while ((got = std::fread(buf.data(), sizeof(int16_t) * static_cast<size_t>(channels), 65536, f)) > 0) builder.addInterleaved(buf.data(), got);
    std::fclose(f);
    const audio::PeakPyramid peaks = builder.finish();

    // One Medium lane (64 dp): an 18 dp header strip, the rest is the waveform body, like an audio clip with no thumbnails.
    const int height = static_cast<int>(std::lround(64.0f * density));
    Canvas cv(width, height);
    const Color base = hex(0x167A62), white{1, 1, 1, 1};
    const float hair = std::max(1.0f, std::floor(density * 0.5f));
    cv.rect(0, 0, static_cast<float>(width), static_cast<float>(height), base);
    const float header = 18.0f * density;
    cv.rect(0, 0, static_cast<float>(width), header, scaled(base, 0.72f));
    const float wTop = header, ibottom = static_cast<float>(height), mid = (wTop + ibottom) * 0.5f, half = (ibottom - wTop) * 0.5f - 1.0f;
    cv.gradient(0, wTop, static_cast<float>(width), ibottom, scaled(base, 0.95f), scaled(base, 0.62f));

    const int64_t sourceInFrame = static_cast<int64_t>(std::llround(startSec * fps));
    const int64_t duration = peaks.totalFrames * fps / rate;
    const double scrollX = 0.0;
    if (mode == "before") {
        const float colW = std::max(1.0f, 2.0f * density);
        const Color wave = mix(base, white, 0.6f);
        cv.rect(0, mid - hair * 0.5f, static_cast<float>(width), mid + hair * 0.5f, withAlpha(wave, 0.35f));
        float peak = 0.0f;
        for (const int16_t v : peaks.levels.back().data) peak = std::max(peak, std::fabs(static_cast<float>(v)) / 32768.0f);
        const float reference = std::max(peak, 0.02f);
        for (int64_t col = 0; col <= static_cast<int64_t>(std::floor((width + scrollX) / colW)); ++col) {
            const int64_t f0 = static_cast<int64_t>(std::floor(col * colW / ppf));
            const int64_t f1 = static_cast<int64_t>(std::floor((col + 1) * colW / ppf));
            const int64_t r0 = std::clamp<int64_t>(f0, 0, duration);
            const int64_t r1 = std::clamp<int64_t>(std::max(f1, f0 + 1), 0, duration);
            if (r1 <= r0) continue;
            const int64_t s0 = (sourceInFrame + r0) * rate / fps;
            const int64_t s1 = std::max((sourceInFrame + r1) * rate / fps, s0 + 1);
            int16_t mm[2];
            audio::queryPeaks(peaks, s0, s1, 1, mm);
            const float lo = std::min(0.0f, legacyDisplay(mm[0] / 32768.0f, reference));
            const float hi = std::max(0.0f, legacyDisplay(mm[1] / 32768.0f, reference));
            const float x = static_cast<float>(col * colW - scrollX);
            cv.rect(x, mid - hi * half - 0.5f, x + colW, mid - lo * half + 0.5f, wave);
        }
    } else if (mode == "after") {
        // The LumaFusion-style look: one-sided, bottom-anchored solid envelope over a fainter peak layer, thin centre line across.
        const float areaH = ibottom - wTop - hair;
        cv.rect(0, wTop, static_cast<float>(width), ibottom, scaled(base, timeline::kWaveBodyDim));
        const Color fill = mix(base, white, timeline::kWaveFillMix);
        const Color peakC = withAlpha(mix(base, white, timeline::kWavePeakMix), timeline::kWavePeakAlpha);
        const Color centre = withAlpha(mix(base, white, timeline::kWaveCentreMix), 0.55f);
        const float colW = timeline::waveColumnWidth(static_cast<float>(width), density);
        const int64_t first = timeline::waveFirstColumn(0.0f, scrollX, colW), last = timeline::waveLastColumn(static_cast<float>(width), scrollX, colW);
        const int64_t refStart = sourceInFrame * rate / fps, refEnd = (sourceInFrame + duration) * rate / fps;
        const float reference = audio::referenceLevel(peaks, refStart, refEnd);
        int64_t quads = 0;
        for (int64_t col = first; col <= last; ++col) {
            const int64_t s0 = timeline::waveSampleAtColumn(col, colW, ppf, 0, duration, sourceInFrame, rate, fps, 1);
            const int64_t s1 = timeline::waveSampleAtColumn(col + 1, colW, ppf, 0, duration, sourceInFrame, rate, fps, 1);
            if (s1 <= s0) continue;
            const timeline::WaveColumn wc = timeline::waveColumn(peaks, s0, s1, reference, scale);
            const float x = static_cast<float>(col * colW - scrollX);
            const float yPeak = ibottom - wc.peak * areaH, yFill = ibottom - wc.fill * areaH;
            if (yPeak < yFill - 0.5f) {
                cv.rect(x, yPeak, x + colW, yFill, peakC);
                ++quads;
            }
            cv.rect(x, yFill, x + colW, ibottom, fill);  // in silence this is the one pixel baseline
            ++quads;
        }
        cv.rect(0, mid - hair * 0.5f, static_cast<float>(width), mid + hair * 0.5f, centre);
        std::fprintf(stderr, "columns %lld of %.0f px, %lld quads (%lld vertices), reference %.3f\n", static_cast<long long>(last - first + 1),
                     colW, static_cast<long long>(quads), static_cast<long long>(quads * 6), reference);
    } else {  // "v165": the drawing of PR #165 (outline, envelope, RMS band), kept for comparison
        const Color envelope = mix(base, white, 0.45f), inner = mix(base, white, 0.88f), centre = withAlpha(mix(base, white, 0.6f), 0.4f);
        const Color outline{0, 0, 0, 0.42f};
        cv.rect(0, mid - hair * 0.5f, static_cast<float>(width), mid + hair * 0.5f, centre);
        const float colW = timeline::waveColumnWidth(static_cast<float>(width), density);
        const int64_t first = timeline::waveFirstColumn(0.0f, scrollX, colW), last = timeline::waveLastColumn(static_cast<float>(width), scrollX, colW);
        const int64_t refStart = sourceInFrame * rate / fps, refEnd = (sourceInFrame + duration) * rate / fps;
        const float reference = audio::referenceLevel(peaks, refStart, refEnd);
        int64_t quads = 0;
        for (int64_t col = first; col <= last; ++col) {
            const int64_t s0 = timeline::waveSampleAtColumn(col, colW, ppf, 0, duration, sourceInFrame, rate, fps, 1);
            const int64_t s1 = timeline::waveSampleAtColumn(col + 1, colW, ppf, 0, duration, sourceInFrame, rate, fps, 1);
            if (s1 <= s0) continue;
            const timeline::WaveColumn wc = timeline::waveColumn(peaks, s0, s1, reference, scale);
            if (wc.up <= 0.0f && wc.down <= 0.0f) continue;
            const float x = static_cast<float>(col * colW - scrollX);
            const float yTop = mid - wc.up * half, yBottom = mid + wc.down * half;
            cv.rect(x, yTop - hair, x + colW, yBottom + hair, outline);
            cv.rect(x, yTop, x + colW, yBottom, envelope);
            quads += 2;
            if (wc.rms * half >= 1.0f) {
                cv.rect(x, mid - std::min(wc.rms, wc.up) * half, x + colW, mid + std::min(wc.rms, wc.down) * half, inner);
                ++quads;
            }
        }
        std::fprintf(stderr, "columns %lld of %.0f px, %lld quads (%lld vertices), reference %.3f\n", static_cast<long long>(last - first + 1),
                     colW, static_cast<long long>(quads), static_cast<long long>(quads * 6), reference);
    }
    cv.writePpm(out.c_str());
    return 0;
}
