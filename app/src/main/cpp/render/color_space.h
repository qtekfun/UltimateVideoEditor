#pragma once

// Colour-space vocabulary shared by the compositor, the preview and the exporter. No GL headers, so
// the host tests can include it.

namespace uv::render {

// How one source layer is converted into the colour space the target is rendered in. Mirrors
// com.qtekfun.ultimatevideoeditor.engine.preview.ColorMode; keep both in sync.
enum class ColorMode : int {
    Sdr709 = 0,           // SDR Rec.709 source into an SDR target: sampled as is
    Hlg2020ToSdr709 = 1,  // HLG / Rec.2020 source tone-mapped into an SDR target
    Sdr709ToHlg2020 = 2,  // SDR Rec.709 source placed in an HLG target (SDR white at 203 nit)
    Hlg2020 = 3,          // HLG / Rec.2020 source into an HLG target: sampled as is
    Pq2020ToSdr709 = 4,   // PQ / Rec.2020 source tone-mapped into an SDR target
    Pq2020ToHlg2020 = 5,  // PQ / Rec.2020 source re-encoded as HLG for an HLG target
};

// The colour space a render target (window surface or encoder surface) holds.
enum class OutputSpace : int {
    Sdr709 = 0,
    Hlg2020 = 1,
};

enum class SourceTransfer : int { Sdr = 0, Hlg = 1, Pq = 2 };

// MediaFormat.COLOR_TRANSFER_*: 7 = HLG, 6 = ST 2084 (PQ), anything else is treated as SDR.
inline SourceTransfer sourceTransferFromMedia(int mediaTransfer) {
    if (mediaTransfer == 7) return SourceTransfer::Hlg;
    if (mediaTransfer == 6) return SourceTransfer::Pq;
    return SourceTransfer::Sdr;
}

inline SourceTransfer sourceTransferOf(ColorMode mode) {
    switch (mode) {
        case ColorMode::Hlg2020ToSdr709:
        case ColorMode::Hlg2020:
            return SourceTransfer::Hlg;
        case ColorMode::Pq2020ToSdr709:
        case ColorMode::Pq2020ToHlg2020:
            return SourceTransfer::Pq;
        case ColorMode::Sdr709:
        case ColorMode::Sdr709ToHlg2020:
            break;
    }
    return SourceTransfer::Sdr;
}

inline ColorMode colorModeFor(SourceTransfer source, OutputSpace target) {
    if (target == OutputSpace::Sdr709) {
        switch (source) {
            case SourceTransfer::Hlg: return ColorMode::Hlg2020ToSdr709;
            case SourceTransfer::Pq: return ColorMode::Pq2020ToSdr709;
            case SourceTransfer::Sdr: return ColorMode::Sdr709;
        }
    }
    switch (source) {
        case SourceTransfer::Hlg: return ColorMode::Hlg2020;
        case SourceTransfer::Pq: return ColorMode::Pq2020ToHlg2020;
        case SourceTransfer::Sdr: return ColorMode::Sdr709ToHlg2020;
    }
    return ColorMode::Sdr709;
}

}  // namespace uv::render
