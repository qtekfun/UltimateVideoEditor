#pragma once

#include <cstddef>
#include <cstdint>

namespace uv::timeline {

struct Rgba {
    float r, g, b, a;
};

constexpr Rgba rgbaFromArgb(uint32_t argb) {
    return {static_cast<float>((argb >> 16) & 0xFF) / 255.0f, static_cast<float>((argb >> 8) & 0xFF) / 255.0f,
            static_cast<float>(argb & 0xFF) / 255.0f, static_cast<float>((argb >> 24) & 0xFF) / 255.0f};
}

// The colours the canvas takes from the app palette (ui/theme/Palette.kt, Palette.nativeColours()), in that order.
// kNativeColourCount must equal Palette.NATIVE_COLOUR_COUNT; a list of any other length is ignored and the defaults
// stay (so a mismatch shows as the dark defaults, never as garbage).
constexpr size_t kNativeColourCount = 22;

struct TimelineTheme {
    Rgba background, laneA, laneB, ruler, tick, rulerText;
    Rgba clipVideo, clipAudio, clipTitle, clipImage, clipSticker, clipMulticam, onClip;
    Rgba playhead, selection, keyframe, marker;
    Rgba surfaceHigh, onSurface, onSurfaceVariant, primary, error;

    // The dark palette of the app; the same values as Palette.Dark.
    static TimelineTheme dark() {
        const uint32_t d[kNativeColourCount] = {
            0xFF0F1115, 0xFF161920, 0xFF1B1F28, 0xFF222633, 0xFF8C93A6, 0xFFC4C9D6, 0xFF2F6FD6, 0xFF167A62,
            0xFF8A5CD6, 0xFFA85F1F, 0xFFC03A7A, 0xFF1F7F90, 0xFFFFFFFF, 0xFFFF5A52, 0xFFFFD94A, 0xFFFFC21A,
            0xFFFF5252, 0xFF272B38, 0xFFE6E8EE, 0xFFAAB0C0, 0xFF8AB4FF, 0xFFFF8A80,
        };
        TimelineTheme t{};
        t.assign(d);
        return t;
    }

    void assign(const uint32_t* c) {
        Rgba* fields[kNativeColourCount] = {&background, &laneA, &laneB, &ruler, &tick, &rulerText, &clipVideo, &clipAudio,
                                            &clipTitle, &clipImage, &clipSticker, &clipMulticam, &onClip, &playhead,
                                            &selection, &keyframe, &marker, &surfaceHigh, &onSurface, &onSurfaceVariant,
                                            &primary, &error};
        for (size_t i = 0; i < kNativeColourCount; ++i) *fields[i] = rgbaFromArgb(c[i]);
    }
};

}  // namespace uv::timeline
