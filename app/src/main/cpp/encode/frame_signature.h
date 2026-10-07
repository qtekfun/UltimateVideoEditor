#pragma once

// Pure, host-testable maths for the post-export verification (SPECS.md 5.10, DECISIONS.md "Post-export verification").
// While exporting, the engine keeps a cheap signature of a few composed frames: a 64x36 picture of what the encoder was
// fed (see frame_probe.cpp) reduced here to a 32x18 grid of luma and chroma means in the OUTPUT colour domain (nominal
// Y'CbCr of the stream: BT.709 for SDR, BT.2020 non-constant luminance for HLG). After the file is closed the app decodes
// the same frames from the file and signs them the same way (engine/verify/FrameSigner.kt); the two are compared with a
// tolerance for lossy coding. The grid size and the quantisation below are mirrored by `SignatureFormat` in Kotlin.

#include <algorithm>
#include <cstdint>
#include <vector>

#include "encode/export_math.h"

namespace uv::encode {

constexpr int kProbeW = 64;  // the reduced picture the GPU hands back
constexpr int kProbeH = 36;
constexpr int kSigW = 32;  // grid of means: each cell averages 2x2 probe pixels
constexpr int kSigH = 18;
constexpr int kSigCells = kSigW * kSigH;
constexpr int kSigScale = 65535;  // nominal value 0..1 stored as 0..65535 (chroma: -0.5..0.5 stored as 0..1)

enum class SigMatrix : int32_t { Bt709 = 0, Bt2020 = 1 };

// One probed frame. `y`, `cb`, `cr` hold kSigCells values each, row-major, top row first.
struct FrameSignature {
    int64_t frame = 0;  // output frame index
    int64_t ptsUs = 0;  // its presentation time in microseconds
    std::vector<uint16_t> y;
    std::vector<uint16_t> cb;
    std::vector<uint16_t> cr;
};

struct MatrixCoefficients {
    double kr;
    double kb;
};

constexpr MatrixCoefficients matrixCoefficients(SigMatrix m) {
    return m == SigMatrix::Bt2020 ? MatrixCoefficients{0.2627, 0.0593} : MatrixCoefficients{0.2126, 0.0722};
}

// `rgb` is kProbeW x kProbeH x 3 values (top row first), each 0..maxValue (255 for an 8-bit surface, 1023 for 10-bit).
inline FrameSignature reduceProbe(const uint16_t* rgb, int maxValue, SigMatrix matrix, int64_t frame, int64_t ptsUs) {
    const MatrixCoefficients k = matrixCoefficients(matrix);
    const double kg = 1.0 - k.kr - k.kb;
    FrameSignature sig;
    sig.frame = frame;
    sig.ptsUs = ptsUs;
    sig.y.resize(kSigCells);
    sig.cb.resize(kSigCells);
    sig.cr.resize(kSigCells);
    const double norm = 1.0 / (4.0 * maxValue);
    for (int cy = 0; cy < kSigH; ++cy) {
        for (int cx = 0; cx < kSigW; ++cx) {
            double sum[3] = {0, 0, 0};
            for (int dy = 0; dy < 2; ++dy) {
                for (int dx = 0; dx < 2; ++dx) {
                    const uint16_t* p = rgb + (static_cast<size_t>(cy * 2 + dy) * kProbeW + static_cast<size_t>(cx * 2 + dx)) * 3;
                    for (int c = 0; c < 3; ++c) sum[c] += p[c];
                }
            }
            const double r = sum[0] * norm;
            const double g = sum[1] * norm;
            const double b = sum[2] * norm;
            const double y = k.kr * r + kg * g + k.kb * b;
            const double cb = (b - y) / (2.0 * (1.0 - k.kb));
            const double cr = (r - y) / (2.0 * (1.0 - k.kr));
            auto q = [](double v) { return static_cast<uint16_t>(std::clamp(v, 0.0, 1.0) * kSigScale + 0.5); };
            const size_t i = static_cast<size_t>(cy) * kSigW + static_cast<size_t>(cx);
            sig.y[i] = q(y);
            sig.cb[i] = q(cb + 0.5);
            sig.cr[i] = q(cr + 0.5);
        }
    }
    return sig;
}

// Which output frames are probed: only the first and the last (a loose sanity check; the file's structure and a decode of its start
// and last seconds carry the verification, see DECISIONS.md "Post-export verification"). Sorted, unique, within [0, totalFrames).
inline std::vector<int64_t> probeFrames(int64_t totalFrames, Fps /*fps*/ = Fps{}) {
    std::vector<int64_t> out;
    if (totalFrames <= 0) return out;
    out.push_back(0);
    if (totalFrames > 1) out.push_back(totalFrames - 1);
    return out;
}

// True when every cell of every plane is within `tolerance` of the others: a flat picture (black, grey, one colour).
inline bool isFlatSignature(const FrameSignature& s, int tolerance) {
    auto flat = [tolerance](const std::vector<uint16_t>& v) {
        if (v.empty()) return true;
        const auto mm = std::minmax_element(v.begin(), v.end());
        return *mm.second - *mm.first <= tolerance;
    };
    return flat(s.y) && flat(s.cb) && flat(s.cr);
}

}  // namespace uv::encode
