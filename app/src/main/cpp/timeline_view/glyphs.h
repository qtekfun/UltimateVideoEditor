#pragma once

#include <cstdint>

namespace uv::timeline {

// 3x5 pixel glyphs of the timeline's tiny font. A glyph is 15 bits, row-major, bit 14 = top-left.
//
// Order of kGlyphs: 0-9, ':', 'x', '.', '<' (reverse) and '|' (freeze).
constexpr uint16_t kGlyphs[15] = {
    0b111101101101111, 0b010110010010111, 0b111001111100111, 0b111001111001111,
    0b101101111001001, 0b111100111001111, 0b111100111101111, 0b111001001001001,
    0b111101111101111, 0b111101111001111, 0b000010000010000, 0b101101010101101,
    0b000000000000010, 0b001010100010001, 0b010010010010010,
};

// A-Z, for the text written on title and sticker blocks.
constexpr uint16_t kLetterGlyphs[26] = {
    0b010101111101101, 0b110101110101110, 0b011100100100011, 0b110101101101110,
    0b111100110100111, 0b111100110100100, 0b011100101101011, 0b101101111101101,
    0b111010010010111, 0b001001001101010, 0b101101110101101, 0b100100100100111,
    0b101111111101101, 0b101111111111101, 0b010101101101010, 0b110101110100100,
    0b010101101111011, 0b110101110101101, 0b011100010001110, 0b111010010010010,
    0b101101101101111, 0b101101101101010, 0b101101111111101, 0b101101010101101,
    0b101101010010010, 0b111001010100111,
};

constexpr uint16_t kDashGlyph = 0b000000111000000;

// The glyph bits of `ch`, or 0 for a space or any character the font does not have. Letters are drawn as
// capitals; the multiplication sign of the speed labels ("2x") is the picture of an 'X'.
constexpr uint16_t glyphBits(char ch) {
    if (ch >= '0' && ch <= '9') return kGlyphs[ch - '0'];
    switch (ch) {
        case ':': return kGlyphs[10];
        case 'x': return kGlyphs[11];
        case '.': return kGlyphs[12];
        case '<': return kGlyphs[13];
        case '|': return kGlyphs[14];
        case '-': return kDashGlyph;
        default: break;
    }
    if (ch >= 'A' && ch <= 'Z') return kLetterGlyphs[ch - 'A'];
    if (ch >= 'a' && ch <= 'z') return kLetterGlyphs[ch - 'a'];
    return 0;
}

}  // namespace uv::timeline
