// Host tests for render/color_space.h and the HDR (HLG) additions of render/color_math.h, the CPU
// reference of the composite shader. Vectors are derived analytically in the comments.
#include <cmath>
#include <cstdio>

#include "render/color_math.h"
#include "render/color_space.h"

namespace {

int failures = 0;

#define CHECK(cond)                                                      \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
            ++failures;                                                  \
        }                                                                \
    } while (0)

#define CHECK_NEAR(actual, expected, tol)                                                                 \
    do {                                                                                                  \
        const double a_ = static_cast<double>(actual);                                                    \
        const double e_ = static_cast<double>(expected);                                                  \
        if (std::fabs(a_ - e_) > (tol)) {                                                                 \
            std::printf("FAIL %s:%d: %s = %f, expected %f\n", __FILE__, __LINE__, #actual, a_, e_);       \
            ++failures;                                                                                   \
        }                                                                                                 \
    } while (0)

using namespace uv::render;

void modeSelectionCoversEveryPair() {
    CHECK(colorModeFor(SourceTransfer::Sdr, OutputSpace::Sdr709) == ColorMode::Sdr709);
    CHECK(colorModeFor(SourceTransfer::Hlg, OutputSpace::Sdr709) == ColorMode::Hlg2020ToSdr709);
    CHECK(colorModeFor(SourceTransfer::Pq, OutputSpace::Sdr709) == ColorMode::Pq2020ToSdr709);
    CHECK(colorModeFor(SourceTransfer::Sdr, OutputSpace::Hlg2020) == ColorMode::Sdr709ToHlg2020);
    CHECK(colorModeFor(SourceTransfer::Hlg, OutputSpace::Hlg2020) == ColorMode::Hlg2020);
    CHECK(colorModeFor(SourceTransfer::Pq, OutputSpace::Hlg2020) == ColorMode::Pq2020ToHlg2020);
}

void sourceClassSurvivesTheRoundTrip() {
    for (int m = 0; m <= 5; ++m) {
        const ColorMode mode = static_cast<ColorMode>(m);
        const SourceTransfer src = sourceTransferOf(mode);
        // Re-deriving the mode for the target the mode already has must give the same mode.
        const OutputSpace target = (m == 0 || m == 1 || m == 4) ? OutputSpace::Sdr709 : OutputSpace::Hlg2020;
        CHECK(colorModeFor(src, target) == mode);
    }
}

void mediaTransferCodes() {
    CHECK(sourceTransferFromMedia(7) == SourceTransfer::Hlg);  // COLOR_TRANSFER_HLG
    CHECK(sourceTransferFromMedia(6) == SourceTransfer::Pq);   // COLOR_TRANSFER_ST2084
    CHECK(sourceTransferFromMedia(3) == SourceTransfer::Sdr);  // COLOR_TRANSFER_SDR_VIDEO
    CHECK(sourceTransferFromMedia(0) == SourceTransfer::Sdr);  // unspecified
}

void hlgOetfInvertsTheInverseOetf() {
    for (float e : {0.0f, 0.02f, 0.0833f, 0.2f, 0.5f, 0.8f, 1.0f}) {
        CHECK_NEAR(hlgInverseOetf(hlgOetf(e)), e, 1e-4);
    }
    CHECK_NEAR(hlgOetf(1.0f), 1.0f, 1e-4);          // peak maps to full signal
    CHECK_NEAR(hlgOetf(1.0f / 12.0f), 0.5f, 1e-4);  // the knee of the curve
}

void pqEotfAnchors() {
    CHECK_NEAR(pqEotfNits(0.0f), 0.0f, 1e-3);
    CHECK_NEAR(pqEotfNits(1.0f), 10000.0f, 5.0f);
    // Signal 0.5081 is 100 nit and 0.5807 is 203 nit (BT.2408 reference white) in ST 2084.
    CHECK_NEAR(pqEotfNits(0.5081f), 100.0f, 1.5f);
    CHECK_NEAR(pqEotfNits(0.5807f), 203.0f, 3.0f);
}

void sdrWhiteLandsOnHlgReferenceWhite() {
    // White: display 1.0 -> 0.203 of the 1000 nit display; inverse OOTF gives scene 0.203^(1/1.2) = 0.2665;
    // HLG OETF(0.2665) = 0.17883 * ln(12 * 0.2665 - 0.28467) + 0.55991 = 0.7512 (BT.2408: ~0.75).
    const Vec3 white = sdr709ToHlg2020({1.0f, 1.0f, 1.0f});
    CHECK_NEAR(white.r, 0.7512, 2e-3);
    CHECK_NEAR(white.g, 0.7512, 2e-3);
    CHECK_NEAR(white.b, 0.7512, 2e-3);
    const Vec3 black = sdr709ToHlg2020({0.0f, 0.0f, 0.0f});
    CHECK_NEAR(black.r, 0.0, 1e-5);
    CHECK_NEAR(black.b, 0.0, 1e-5);
}

void sdrToHlgIsMonotonicAndKeepsNeutrals() {
    float prev = -1.0f;
    for (int i = 0; i <= 20; ++i) {
        const float v = static_cast<float>(i) / 20.0f;
        const Vec3 o = sdr709ToHlg2020({v, v, v});
        CHECK(o.r >= prev);
        CHECK_NEAR(o.r, o.g, 2e-3);  // neutrals stay neutral through the gamut matrix (rows sum to ~1)
        CHECK_NEAR(o.g, o.b, 2e-3);
        prev = o.r;
    }
}

void sdrRedBecomesAMixInRec2020() {
    // Pure Rec.709 red is a mix in Rec.2020: all components positive, red dominant, below white.
    const Vec3 red = sdr709ToHlg2020({1.0f, 0.0f, 0.0f});
    CHECK(red.r > red.g && red.g > 0.0f && red.b > 0.0f);
    CHECK(red.r < 0.75f);
}

void hlgSourceIntoHlgProjectIsUntouched() {
    const Vec3 v{0.3f, 0.55f, 0.9f};
    const Vec3 o = convertToTarget(static_cast<int>(ColorMode::Hlg2020), v);
    CHECK_NEAR(o.r, v.r, 1e-6);
    CHECK_NEAR(o.g, v.g, 1e-6);
    CHECK_NEAR(o.b, v.b, 1e-6);
    const Vec3 sdr = convertToTarget(static_cast<int>(ColorMode::Sdr709), v);
    CHECK_NEAR(sdr.g, v.g, 1e-6);
}

void pqReferenceWhiteMatchesSdrReferenceWhiteInHlg() {
    // 203 nit PQ (signal 0.5807) carries the same light as SDR white placed at 203 nit, so both land on ~0.7512.
    const Vec3 pq = pq2020ToHlg2020({0.5807f, 0.5807f, 0.5807f});
    CHECK_NEAR(pq.r, 0.7512, 4e-3);
    CHECK_NEAR(pq.g, pq.r, 1e-4);
}

void pqHighlightsClipAtTheHlgPeak() {
    const Vec3 peak = pq2020ToHlg2020({1.0f, 1.0f, 1.0f});  // 10000 nit
    CHECK_NEAR(peak.r, 1.0, 1e-3);
    CHECK_NEAR(peak.g, 1.0, 1e-3);
}

void pqIntoSdrIsMonotonicAndInRange() {
    float prev = -1.0f;
    for (int i = 0; i <= 20; ++i) {
        const float v = static_cast<float>(i) / 20.0f;
        const Vec3 o = pq2020ToSdr709({v, v, v});
        CHECK(o.r >= prev - 1e-6f);
        CHECK(o.r >= 0.0f && o.r <= 1.0f);
        prev = o.r;
    }
    // 203 nit reference white sits just under SDR peak after the shoulder (toneMapLuma(1.0) = 0.95).
    const Vec3 ref = pq2020ToSdr709({0.5807f, 0.5807f, 0.5807f});
    CHECK(ref.r > 0.95f && ref.r <= 1.0f);
}

void hlgToSdrStillWorks() {
    // The existing path is unchanged: HLG signal 0.75 (reference white) maps close to SDR white.
    const Vec3 v = hlg2020ToSdr709({0.75f, 0.75f, 0.75f});
    CHECK(v.r > 0.9f && v.r <= 1.0f);
}

void titlesStayAtReferenceWhiteInAnHdrTarget() {
    // The pipeline converts titles with Sdr709ToHlg2020 in an HLG target: a white title is 203 nit, not peak.
    const Vec3 w = convertToTarget(static_cast<int>(ColorMode::Sdr709ToHlg2020), {1.0f, 1.0f, 1.0f});
    CHECK(w.r < 0.8f);
}

}  // namespace

int main() {
    modeSelectionCoversEveryPair();
    sourceClassSurvivesTheRoundTrip();
    mediaTransferCodes();
    hlgOetfInvertsTheInverseOetf();
    pqEotfAnchors();
    sdrWhiteLandsOnHlgReferenceWhite();
    sdrToHlgIsMonotonicAndKeepsNeutrals();
    sdrRedBecomesAMixInRec2020();
    hlgSourceIntoHlgProjectIsUntouched();
    pqReferenceWhiteMatchesSdrReferenceWhiteInHlg();
    pqHighlightsClipAtTheHlgPeak();
    pqIntoSdrIsMonotonicAndInRange();
    hlgToSdrStillWorks();
    titlesStayAtReferenceWhiteInAnHdrTarget();
    if (failures == 0) std::printf("hdr host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
