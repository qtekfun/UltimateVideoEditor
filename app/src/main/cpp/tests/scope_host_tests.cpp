// Host tests for render/scope_math.h: where scope samples land and how counts become brightness.
// No Android or GL dependencies.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "render/scope_math.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                      \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                  \
        }                                                                \
    } while (0)

#define CHECK_NEAR(actual, expected, eps)                                                                \
    do {                                                                                                 \
        const double a_ = static_cast<double>(actual);                                                   \
        const double e_ = static_cast<double>(expected);                                                 \
        if (std::fabs(a_ - e_) > (eps)) {                                                                \
            std::printf("FAIL %s:%d: %s = %f, expected %f\n", __FILE__, __LINE__, #actual, a_, e_);      \
            ++failures;                                                                                  \
        }                                                                                                \
    } while (0)

using namespace uv::render::scope;

// A horizontal grey ramp: column i has value i / (w - 1) on every channel.
std::vector<uint8_t> greyRamp(int w, int h) {
    std::vector<uint8_t> img(static_cast<size_t>(w) * static_cast<size_t>(h) * 4, 255);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t v = static_cast<uint8_t>(std::lround(255.0 * x / (w - 1)));
            uint8_t* p = &img[(static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)) * 4];
            p[0] = p[1] = p[2] = v;
        }
    }
    return img;
}

std::vector<uint8_t> solid(int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    std::vector<uint8_t> img(static_cast<size_t>(w) * static_cast<size_t>(h) * 4, 255);
    for (size_t i = 0; i < img.size(); i += 4) {
        img[i] = r;
        img[i + 1] = g;
        img[i + 2] = b;
    }
    return img;
}

void waveformPointsMapPositionAndLevel() {
    const Point left = waveformPoint(0.0f, 0.0f);
    CHECK_NEAR(left.x, -1.0, 1e-6);
    CHECK_NEAR(left.y, -1.0, 1e-6);
    const Point right = waveformPoint(1.0f, 1.0f);
    CHECK_NEAR(right.x, 1.0, 1e-6);
    CHECK_NEAR(right.y, 1.0, 1e-6);
    CHECK_NEAR(waveformPoint(0.5f, 0.5f).y, 0.0, 1e-6);
    CHECK_NEAR(waveformPoint(0.5f, 2.0f).y, 1.0, 1e-6);  // out of range values are clamped
}

void paradeSplitsTheWidthIntoThirds() {
    CHECK_NEAR(paradePoint(0.0f, 0.5f, 0).x, -1.0, 1e-6);
    CHECK_NEAR(paradePoint(1.0f, 0.5f, 0).x, -1.0 / 3.0, 1e-6);
    CHECK_NEAR(paradePoint(0.0f, 0.5f, 1).x, -1.0 / 3.0, 1e-6);
    CHECK_NEAR(paradePoint(1.0f, 0.5f, 2).x, 1.0, 1e-6);
}

void aGreyRampDrawsADiagonalInTheWaveform() {
    const int w = kSrcWidth;
    const int h = kSrcHeight;
    const auto img = greyRamp(w, h);
    const Accumulation a = accumulate(Mode::Waveform, img.data(), w, h);
    CHECK(a.width == kAccumWidth && a.height == kAccumHeight);
    // Every row of the picture has the same ramp, so each used pixel holds `h` samples.
    float total = 0.0f;
    for (int y = 0; y < a.height; ++y) {
        for (int x = 0; x < a.width; ++x) total += a.get(x, y, 3);
    }
    CHECK_NEAR(total, static_cast<double>(w) * h, 0.5);
    // The brightest pixel of the first and last columns sit at the bottom and top of the scope.
    int firstRow = -1;
    int lastRow = -1;
    for (int y = 0; y < a.height; ++y) {
        if (a.get(0, y, 3) > 0.0f) firstRow = y;
        if (a.get(a.width - 1, y, 3) > 0.0f) lastRow = y;
    }
    CHECK(firstRow == 0);
    CHECK(lastRow == a.height - 1);
    // Monotone: the row of the column in the middle is in the middle.
    int midRow = -1;
    for (int y = 0; y < a.height; ++y) if (a.get(a.width / 2, y, 3) > 0.0f) midRow = y;
    CHECK(midRow > a.height / 2 - 4 && midRow < a.height / 2 + 4);
}

void aSolidColourIsOnePointInTheVectorscope() {
    // Pure red: Cr is the largest, Cb slightly negative.
    const auto img = solid(40, 20, 255, 0, 0);
    const Accumulation a = accumulate(Mode::Vector, img.data(), 40, 20);
    CHECK(a.width == kVectorSize && a.height == kVectorSize);
    int pixels = 0;
    float total = 0.0f;
    for (int y = 0; y < a.height; ++y) {
        for (int x = 0; x < a.width; ++x) {
            if (a.get(x, y, 3) > 0.0f) ++pixels;
            total += a.get(x, y, 3);
        }
    }
    CHECK(pixels == 1);
    CHECK_NEAR(total, 40.0 * 20.0, 0.5);
    const Point p = vectorPoint(1.0f, 0.0f, 0.0f);
    CHECK(p.y > 0.85f && p.y < 0.95f);  // up
    CHECK(p.x < 0.0f);                  // slightly left of centre
    CHECK(a.get(pixelOf(p.x, a.width), pixelOf(p.y, a.height), 3) > 0.0f);
}

void greysSitAtTheCentreOfTheVectorscope() {
    for (const float v : {0.0f, 0.3f, 0.7f, 1.0f}) {
        const Point p = vectorPoint(v, v, v);
        CHECK_NEAR(p.x, 0.0, 1e-6);
        CHECK_NEAR(p.y, 0.0, 1e-6);
    }
}

void primariesLandInTheirDirections() {
    const Point red = vectorPoint(1, 0, 0);
    const Point blue = vectorPoint(0, 0, 1);
    const Point green = vectorPoint(0, 1, 0);
    const Point yellow = vectorPoint(1, 1, 0);
    CHECK(blue.x > 0.85f && std::fabs(blue.y) < 0.4f);  // right
    CHECK(red.y > 0.85f);                               // up
    CHECK(green.x < 0.0f && green.y < 0.0f);            // down left
    CHECK(yellow.x < 0.0f && yellow.y < 0.0f + 0.9f && yellow.y > 0.0f);  // up left, opposite of blue
}

void theHistogramCountsEachChannelInItsBin() {
    const auto img = solid(10, 10, 255, 128, 0);
    const Accumulation a = accumulate(Mode::Histogram, img.data(), 10, 10);
    CHECK(a.width == kHistogramBins && a.height == 1);
    CHECK_NEAR(a.get(255, 0, 0), 100.0, 0.5);  // red at full
    CHECK_NEAR(a.get(128, 0, 1), 100.0, 0.5);  // green at 128
    CHECK_NEAR(a.get(0, 0, 2), 100.0, 0.5);    // blue at zero
    float lumaTotal = 0.0f;
    for (int x = 0; x < a.width; ++x) lumaTotal += a.get(x, 0, 3);
    CHECK_NEAR(lumaTotal, 100.0, 0.5);
}

void aGreyRampFillsTheHistogramEvenly() {
    const int w = 256;
    const auto img = greyRamp(w, 4);
    const Accumulation a = accumulate(Mode::Histogram, img.data(), w, 4);
    for (int bin = 0; bin < kHistogramBins; ++bin) CHECK_NEAR(a.get(bin, 0, 0), 4.0, 0.5);
}

void paradeKeepsEachChannelInItsThird() {
    const auto img = solid(30, 10, 255, 128, 0);
    const Accumulation a = accumulate(Mode::Parade, img.data(), 30, 10);
    float red = 0.0f, green = 0.0f, blue = 0.0f;
    float redOutside = 0.0f;
    for (int y = 0; y < a.height; ++y) {
        for (int x = 0; x < a.width; ++x) {
            red += a.get(x, y, 0);
            green += a.get(x, y, 1);
            blue += a.get(x, y, 2);
            if (x >= a.width / 3) redOutside += a.get(x, y, 0);
        }
    }
    CHECK_NEAR(red, 300.0, 0.5);
    CHECK_NEAR(green, 300.0, 0.5);
    CHECK_NEAR(blue, 300.0, 0.5);
    CHECK_NEAR(redOutside, 0.0, 0.5);
}

void brightnessCurvesAreMonotoneAndBounded() {
    float previous = -1.0f;
    for (float c = 0.0f; c < 400.0f; c += 7.0f) {
        const float i = intensity(c, kWaveformGain);
        CHECK(i >= previous && i >= 0.0f && i <= 1.0f);
        previous = i;
    }
    CHECK_NEAR(intensity(0.0f, kVectorGain), 0.0, 1e-9);
    CHECK(intensity(1.0f, kWaveformGain) > 0.05f);  // a lone sample is still visible
    CHECK_NEAR(histogramHeight(0.0f), 0.0, 1e-9);
    CHECK_NEAR(histogramHeight(kHistogramFull), 1.0, 1e-6);
    CHECK(histogramHeight(100.0f) > histogramHeight(10.0f));
    CHECK(histogramHeight(1e9f) <= 1.0f);
}

void pixelsAndBinsClampAtTheEdges() {
    CHECK(pixelOf(-1.0f, 256) == 0);
    CHECK(pixelOf(1.0f, 256) == 255);
    CHECK(pixelOf(5.0f, 256) == 255);
    CHECK(pixelOf(-9.0f, 256) == 0);
    CHECK(histogramBin(0.0f) == 0);
    CHECK(histogramBin(1.0f) == 255);
    CHECK(histogramBin(2.0f) == 255);
    CHECK(histogramBin(-1.0f) == 0);
}

}  // namespace

int main() {
    waveformPointsMapPositionAndLevel();
    paradeSplitsTheWidthIntoThirds();
    aGreyRampDrawsADiagonalInTheWaveform();
    aSolidColourIsOnePointInTheVectorscope();
    greysSitAtTheCentreOfTheVectorscope();
    primariesLandInTheirDirections();
    theHistogramCountsEachChannelInItsBin();
    aGreyRampFillsTheHistogramEvenly();
    paradeKeepsEachChannelInItsThird();
    brightnessCurvesAreMonotoneAndBounded();
    pixelsAndBinsClampAtTheEdges();
    if (failures == 0) std::printf("scope host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
