#pragma once

// GLSL ES 3.20 sources. The colour code mirrors render/color_math.h; change both together.

namespace uv::render {

inline constexpr const char* kFullscreenVertex = R"(#version 320 es
out vec2 vPos;
void main() {
    // Fullscreen triangle from gl_VertexID: (-1,-1) (3,-1) (-1,3)
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0;
    vPos = p;
    gl_Position = vec4(p, 0.0, 1.0);
}
)";

// Copies a decoder-produced external image into the cache buffer (YUV -> RGB by the sampler).
inline constexpr const char* kBlitFragment = R"(#version 320 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vPos;
uniform samplerExternalOES uTex;
out vec4 outColor;
void main() {
    outColor = vec4(texture(uTex, vPos * 0.5 + 0.5).rgb, 1.0);
}
)";

inline constexpr const char* kCompositeFragment = R"(#version 320 es
precision highp float;
in vec2 vPos;
uniform sampler2D uTex;
uniform int uMode;   // 0 = SDR Rec.709 pass-through, 1 = HLG Rec.2020 -> SDR Rec.709
uniform int uTurns;  // clockwise quarter turns applied for display (layout_math.h rotateUv)
out vec4 outColor;

const float A = 0.17883277;
const float B = 0.28466892;
const float C = 0.55991073;
const float SYSTEM_GAMMA = 1.2;
const float DIFFUSE_WHITE = 203.0;
const float PEAK = 1000.0;
const float KNEE = 0.9;

vec3 hlgInverseOetf(vec3 e) {
    e = clamp(e, 0.0, 1.0);
    vec3 lo = e * e / 3.0;
    vec3 hi = (exp((e - C) / A) + B) / 12.0;
    return mix(hi, lo, lessThanEqual(e, vec3(0.5)));
}

float toneMapLuma(float l) {
    if (l <= KNEE) return max(l, 0.0);
    float lw = PEAK / DIFFUSE_WHITE;
    float t = (l - KNEE) / (1.0 - KNEE);
    float tmax = (lw - KNEE) / (1.0 - KNEE);
    float o = t * (1.0 + t / (tmax * tmax)) / (1.0 + t);
    return min(KNEE + (1.0 - KNEE) * o, 1.0);
}

vec3 bt709Oetf(vec3 l) {
    l = clamp(l, 0.0, 1.0);
    vec3 lo = 4.5 * l;
    vec3 hi = 1.099 * pow(l, vec3(0.45)) - 0.099;
    return mix(hi, lo, lessThan(l, vec3(0.018)));
}

vec3 hlg2020ToSdr709(vec3 hlg) {
    vec3 scene = hlgInverseOetf(hlg);
    float ys = dot(scene, vec3(0.2627, 0.6780, 0.0593));
    float gain = ys > 0.0 ? pow(ys, SYSTEM_GAMMA - 1.0) : 0.0;
    vec3 display = scene * gain * (PEAK / DIFFUSE_WHITE);
    float l = dot(display, vec3(0.2627, 0.6780, 0.0593));
    if (l > 0.0) display *= toneMapLuma(l) / l;
    vec3 lin = vec3(
        dot(display, vec3(1.6605, -0.5876, -0.0728)),
        dot(display, vec3(-0.1246, 1.1329, -0.0083)),
        dot(display, vec3(-0.0182, -0.1006, 1.1187)));
    return bt709Oetf(clamp(lin, 0.0, 1.0));
}

vec2 rotateUv(vec2 o) {
    if (uTurns == 1) return vec2(o.y, 1.0 - o.x);
    if (uTurns == 2) return vec2(1.0 - o.x, 1.0 - o.y);
    if (uTurns == 3) return vec2(1.0 - o.y, o.x);
    return o;
}

void main() {
    // Cache buffers are stored top row first; screen y grows upwards, so flip.
    vec2 uv = rotateUv(vec2(vPos.x * 0.5 + 0.5, 0.5 - vPos.y * 0.5));
    vec3 rgb = texture(uTex, uv).rgb;
    if (uMode == 1) rgb = hlg2020ToSdr709(rgb);
    outColor = vec4(rgb, 1.0);
}
)";

}  // namespace uv::render
