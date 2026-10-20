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

// Display luminance in nit of an HLG signal (1000 nit system): the quantity the HDR level scales.
double hlgNits(const Vec3& hlg) {
    const Vec3 d = hlgOotf({hlgInverseOetf(hlg.r), hlgInverseOetf(hlg.g), hlgInverseOetf(hlg.b)});
    return luminance2020(d) * kPeakNits;
}

void levelOfOneChangesNothing() {
    // The default level is exact: no round trip through the curves, so a project without an analysis is untouched.
    const Vec3 hlg{0.6f, 0.7f, 0.5f};
    const Vec3 same = hlgScaleDisplay(hlg, 1.0f);
    CHECK(same.r == hlg.r && same.g == hlg.g && same.b == hlg.b);
    const Vec3 viaMode = convertToTarget(static_cast<int>(ColorMode::Hlg2020), hlg);
    CHECK(viaMode.r == hlg.r && viaMode.g == hlg.g && viaMode.b == hlg.b);
    const Vec3 sdrDefault = hlg2020ToSdr709(hlg);
    const Vec3 sdrOne = hlg2020ToSdr709(hlg, 1.0f);
    CHECK(sdrDefault.r == sdrOne.r && sdrDefault.g == sdrOne.g && sdrDefault.b == sdrOne.b);
}

void levelScalesHlgDisplayLight() {
    // A gain g on display light: the nit value of a grey (and of a colour) is multiplied by g, which is what the shader
    // promises for an HLG clip in an HLG project (scene light scaled by g^(1/1.2)).
    for (float g : {0.9f, 0.7071f, 0.5f, 0.3535f}) {
        for (float e : {0.3f, 0.5f, 0.75f, 0.9f}) {
            const Vec3 in{e, e, e};
            const Vec3 out = hlgScaleDisplay(in, g);
            CHECK_NEAR(hlgNits(out) / hlgNits(in), g, 2e-3);
        }
        const Vec3 colour{0.8f, 0.6f, 0.4f};
        CHECK_NEAR(hlgNits(hlgScaleDisplay(colour, g)) / hlgNits(colour), g, 2e-3);
    }
}

void levelNeverBrightens() {
    for (float e = 0.0f; e <= 1.0f; e += 0.05f) {
        const Vec3 out = hlgScaleDisplay({e, e, e}, 0.6f);
        CHECK(out.r <= e + 1e-6f);
    }
}

void levelAppliesBeforeTheToneMapIntoSdr() {
    // Dimming the HLG source first makes the SDR picture darker everywhere, never brighter.
    for (float e : {0.4f, 0.6f, 0.75f, 0.9f}) {
        const Vec3 plain = hlg2020ToSdr709({e, e, e});
        const Vec3 dimmed = hlg2020ToSdr709({e, e, e}, 0.7f);
        CHECK(dimmed.r <= plain.r + 1e-6f);
    }
    // 203 nit white dimmed to 70 % is 142 nit: below the knee the tone map is linear, so the result is the plain
    // conversion of a source whose display light is 0.7 times as large.
    const Vec3 a = hlg2020ToSdr709({0.75f, 0.75f, 0.75f}, 0.7f);
    const Vec3 b = hlg2020ToSdr709(hlgScaleDisplay({0.75f, 0.75f, 0.75f}, 0.7f));
    CHECK_NEAR(a.r, b.r, 3e-3);
}

void levelOnPqScalesDisplayLight() {
    // PQ is display referred: the level multiplies the 203-relative light before the HLG or SDR conversion.
    const Vec3 pq{0.5807f, 0.5807f, 0.5807f};  // 203 nit
    const Vec3 half = pq2020ToHlg2020(pq, 0.5f);
    CHECK_NEAR(hlgNits(half), 101.5, 1.5);
    const Vec3 full = pq2020ToHlg2020(pq, 1.0f);
    CHECK_NEAR(hlgNits(full), 203.0, 2.0);
    CHECK(pq2020ToSdr709(pq, 0.5f).r < pq2020ToSdr709(pq, 1.0f).r);
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
    levelOfOneChangesNothing();
    levelScalesHlgDisplayLight();
    levelNeverBrightens();
    levelAppliesBeforeTheToneMapIntoSdr();
    levelOnPqScalesDisplayLight();
    if (failures == 0) std::printf("hdr host tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
