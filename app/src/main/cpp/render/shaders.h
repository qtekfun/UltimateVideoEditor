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

// One layer as a quad: p in [-1,1]^2 is mapped to clip space by uXform (layout_math.h QuadMap).
// vPos stays the quad coordinate so the fragment stage samples exactly as for the fullscreen case.
inline constexpr const char* kQuadVertex = R"(#version 320 es
uniform mat3 uXform;
out vec2 vPos;
void main() {
    // Triangle strip: (-1,-1) (1,-1) (-1,1) (1,1)
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    vPos = p;
    gl_Position = vec4((uXform * vec3(p, 1.0)).xy, 0.0, 1.0);
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
uniform int uMode;   // render/color_space.h ColorMode: 0 SDR, 1 HLG->SDR, 2 SDR->HLG, 3 HLG, 4 PQ->SDR, 5 PQ->HLG
uniform int uTurns;  // clockwise quarter turns applied for display (layout_math.h rotateUv)
uniform float uOpacity;  // layer opacity, applied through alpha blending
uniform int uPremul;     // 1 = premultiplied RGBA title texture (alpha comes from the texture)
uniform int uSrcGl;      // 1 = uTex is an effect intermediate stored with row 0 = image bottom
uniform int uOutPremul;  // 1 = write premultiplied RGBA (the pass that fills an intermediate)
uniform int uMaskShape;  // 0 none, 1 rectangle, 2 ellipse (effect_math.h maskCoverage)
uniform vec4 uMask;      // centre x, centre y, width, height, as fractions of the layer box
uniform vec2 uMaskSoft;  // feather, invert (0/1)
uniform int uBlend;      // 0 normal (fixed-function blending), 1 add, 2 multiply, 3 screen, 4 overlay
uniform sampler2D uDst;  // snapshot of the target below this layer, for blend modes
uniform vec4 uDstRect;   // x, y, w, h of the snapshot in window pixels
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

vec3 hlgOetf(vec3 e) {
    e = clamp(e, 0.0, 1.0);
    vec3 lo = sqrt(3.0 * e);
    vec3 hi = A * log(max(12.0 * e - B, 1e-6)) + C;
    return mix(hi, lo, lessThanEqual(e, vec3(1.0 / 12.0)));
}

// Display light (1.0 = 1000 nit, clipped) -> nonlinear HLG signal.
vec3 displayToHlgSignal(vec3 display) {
    display = clamp(display, 0.0, 1.0);
    float yd = dot(display, vec3(0.2627, 0.6780, 0.0593));
    float gain = yd > 0.0 ? pow(yd, (1.0 - SYSTEM_GAMMA) / SYSTEM_GAMMA) : 0.0;
    return hlgOetf(display * gain);
}

vec3 sdr709ToHlg2020(vec3 sdr) {
    vec3 lin = pow(clamp(sdr, 0.0, 1.0), vec3(2.4));
    vec3 d = vec3(
        dot(lin, vec3(0.6274, 0.3293, 0.0433)),
        dot(lin, vec3(0.0691, 0.9195, 0.0114)),
        dot(lin, vec3(0.0164, 0.0880, 0.8956)));
    return displayToHlgSignal(d * (DIFFUSE_WHITE / PEAK));
}

// ST 2084 signal -> display light where 1.0 = 203 nit.
vec3 pqToDisplay203(vec3 e) {
    const float M1 = 2610.0 / 16384.0;
    const float M2 = 2523.0 / 4096.0 * 128.0;
    const float C1 = 3424.0 / 4096.0;
    const float C2 = 2413.0 / 4096.0 * 32.0;
    const float C3 = 2392.0 / 4096.0 * 32.0;
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / M2));
    vec3 n = max(p - C1, vec3(0.0)) / (C2 - C3 * p);
    return pow(n, vec3(1.0 / M1)) * (10000.0 / DIFFUSE_WHITE);
}

vec3 pq2020ToSdr709(vec3 pq) {
    vec3 display = pqToDisplay203(pq);
    float l = dot(display, vec3(0.2627, 0.6780, 0.0593));
    if (l > 0.0) display *= toneMapLuma(l) / l;
    vec3 lin = vec3(
        dot(display, vec3(1.6605, -0.5876, -0.0728)),
        dot(display, vec3(-0.1246, 1.1329, -0.0083)),
        dot(display, vec3(-0.0182, -0.1006, 1.1187)));
    return bt709Oetf(clamp(lin, 0.0, 1.0));
}

vec3 pq2020ToHlg2020(vec3 pq) {
    return displayToHlgSignal(pqToDisplay203(pq) * (DIFFUSE_WHITE / PEAK));
}

vec2 rotateUv(vec2 o) {
    if (uTurns == 1) return vec2(o.y, 1.0 - o.x);
    if (uTurns == 2) return vec2(1.0 - o.x, 1.0 - o.y);
    if (uTurns == 3) return vec2(1.0 - o.y, o.x);
    return o;
}

float maskCoverage(vec2 p) {
    if (uMaskShape == 0) return 1.0;
    vec2 half_ = uMask.zw * 0.5;
    vec2 d = p - uMask.xy;
    float dist;
    if (uMaskShape == 1) {
        vec2 q = abs(d) - half_;
        dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0);
    } else {
        dist = (length(d / half_) - 1.0) * min(half_.x, half_.y);
    }
    float cov = uMaskSoft.x <= 0.0 ? (dist <= 0.0 ? 1.0 : 0.0) : 1.0 - smoothstep(-uMaskSoft.x, uMaskSoft.x, dist);
    return uMaskSoft.y > 0.5 ? 1.0 - cov : cov;
}

vec3 blendRgb(int mode, vec3 s, vec3 d) {
    if (mode == 1) return min(s + d, vec3(1.0));
    if (mode == 2) return s * d;
    if (mode == 3) return vec3(1.0) - (vec3(1.0) - s) * (vec3(1.0) - d);
    return mix(1.0 - 2.0 * (1.0 - s) * (1.0 - d), 2.0 * s * d, lessThan(d, vec3(0.5)));
}

void main() {
    // Cache buffers are stored top row first; screen y grows upwards, so flip. Effect intermediates
    // are rendered the other way round (row 0 = image bottom) and are sampled as they are.
    vec2 uv = uSrcGl == 1 ? vec2(vPos.x * 0.5 + 0.5, vPos.y * 0.5 + 0.5)
                          : rotateUv(vec2(vPos.x * 0.5 + 0.5, 0.5 - vPos.y * 0.5));
    vec4 texel = texture(uTex, uv);
    vec3 rgb = texel.rgb;
    float alpha = 1.0;
    if (uPremul == 1) {
        alpha = texel.a;
        rgb = alpha > 0.0 ? rgb / alpha : vec3(0.0);
    }
    if (uMode == 1) rgb = hlg2020ToSdr709(rgb);
    else if (uMode == 2) rgb = sdr709ToHlg2020(rgb);
    else if (uMode == 4) rgb = pq2020ToSdr709(rgb);
    else if (uMode == 5) rgb = pq2020ToHlg2020(rgb);
    if (uOutPremul == 1) {
        outColor = vec4(rgb * alpha, alpha);
        return;
    }
    float a = uOpacity * alpha * maskCoverage(vec2(vPos.x * 0.5, -vPos.y * 0.5));
    if (uBlend != 0) {
        vec3 d = texture(uDst, (gl_FragCoord.xy - uDstRect.xy) / uDstRect.zw).rgb;
        outColor = vec4(mix(d, blendRgb(uBlend, rgb, d), a), 1.0);
        return;
    }
    outColor = vec4(rgb, a);
}
)";

// One effect over a premultiplied RGBA intermediate (row 0 = image bottom), mirrored by
// render/effect_math.h. uType follows core::EffectType; blur runs once per axis (uDir).
inline constexpr const char* kEffectFragment = R"(#version 320 es
precision highp float;
in vec2 vPos;
uniform sampler2D uTex;
uniform highp sampler3D uLut;  // unit 1: the 3D LUT of a type-13 pass
uniform float uLutSize;        // edge length of uLut
uniform int uType;
uniform float uP[6];
uniform vec2 uTexel;  // 1 / texture size
uniform vec2 uDir;    // blur axis: (1,0) or (0,1)
uniform float uSigma; // blur sigma in pixels
uniform float uStep;  // blur tap spacing in texels
uniform float uG[21];       // type 14: the grade parameters (render/grade_math.h)
uniform vec4 uCurve[33];    // type 14: baked curve samples (master, red, green, blue)
out vec4 outColor;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float curveAt(float x, int ch) {
    float pos = clamp(x, 0.0, 1.0) * 32.0;
    int i = min(int(pos), 31);
    float t = pos - float(i);
    return mix(uCurve[i][ch], uCurve[i + 1][ch], t);
}

// Mirrors applyGrade in render/grade_math.h.
vec3 applyGrade(vec3 c) {
    c.r *= (1.0 + 0.2 * uG[19]) * (1.0 + 0.1 * uG[20]);
    c.g *= 1.0 - 0.2 * uG[20];
    c.b *= (1.0 - 0.2 * uG[19]) * (1.0 + 0.1 * uG[20]);
    vec3 offset = vec3(uG[12], uG[13], uG[14]);
    c = clamp((c + offset - uG[16]) * uG[15] + uG[16], 0.0, 1.0);
    vec3 lift = 0.5 * (uG[3] + vec3(uG[0], uG[1], uG[2]));
    c = c + lift * (1.0 - c);
    c = clamp(c * exp2(uG[11] + vec3(uG[8], uG[9], uG[10])), 0.0, 1.0);
    c = pow(c, exp2(-(uG[7] + vec3(uG[4], uG[5], uG[6]))));
    float l = luma(c);
    float chroma = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float factor = max(0.0, uG[17] * (1.0 + uG[18] * (1.0 - chroma)));
    c = vec3(l) + (c - vec3(l)) * factor;
    float mr = curveAt(c.r, 0);
    float mg = curveAt(c.g, 0);
    float mb = curveAt(c.b, 0);
    return clamp(vec3(curveAt(mr, 1), curveAt(mg, 2), curveAt(mb, 3)), 0.0, 1.0);
}

void main() {
    vec2 uv = vPos * 0.5 + 0.5;
    vec4 c = texture(uTex, uv);
    if (uType == 7) {
        vec4 acc = vec4(0.0);
        float wsum = 0.0;
        for (int i = -16; i <= 16; ++i) {
            float x = float(i) * uStep / uSigma;
            float w = exp(-0.5 * x * x);
            acc += texture(uTex, uv + uDir * uTexel * (float(i) * uStep)) * w;
            wsum += w;
        }
        outColor = acc / wsum;
        return;
    }
    if (uType == 8) {
        vec3 n = texture(uTex, uv + vec2(uTexel.x, 0.0)).rgb + texture(uTex, uv - vec2(uTexel.x, 0.0)).rgb +
                 texture(uTex, uv + vec2(0.0, uTexel.y)).rgb + texture(uTex, uv - vec2(0.0, uTexel.y)).rgb;
        vec3 sharp = c.rgb + uP[0] * (c.rgb - n * 0.25);
        outColor = vec4(clamp(sharp, vec3(0.0), vec3(c.a)), c.a);
        return;
    }
    float a = c.a;
    vec3 rgb = a > 0.0 ? c.rgb / a : vec3(0.0);
    if (uType == 1) {
        rgb += uP[0];
    } else if (uType == 2) {
        rgb = (rgb - 0.5) * uP[0] + 0.5;
    } else if (uType == 3) {
        rgb = mix(vec3(luma(rgb)), rgb, uP[0]);
    } else if (uType == 4) {
        rgb *= exp2(uP[0]);
    } else if (uType == 5) {
        rgb.r *= 1.0 + 0.2 * uP[0];
        rgb.b *= 1.0 - 0.2 * uP[0];
    } else if (uType == 6) {
        rgb.g *= 1.0 - 0.2 * uP[0];
        rgb.r *= 1.0 + 0.1 * uP[0];
        rgb.b *= 1.0 + 0.1 * uP[0];
    } else if (uType == 9) {
        vec2 p = (uv - 0.5) * 2.0;
        float r = length(p) / 1.41421356;
        float inner = 0.7 * (1.0 - uP[1]);
        rgb *= 1.0 - uP[0] * smoothstep(inner, 1.0, r);
    } else if (uType == 10) {
        rgb = mix(rgb, vec3(luma(rgb)), uP[0]);
    } else if (uType == 11) {
        vec3 sep = vec3(dot(rgb, vec3(0.393, 0.769, 0.189)), dot(rgb, vec3(0.349, 0.686, 0.168)),
                        dot(rgb, vec3(0.272, 0.534, 0.131)));
        rgb = mix(rgb, clamp(sep, vec3(0.0), vec3(1.0)), uP[0]);
    } else if (uType == 13) {
        // Texel centres: input 0 maps to the middle of the first texel, 1 to the middle of the last.
        vec3 coord = (clamp(rgb, vec3(0.0), vec3(1.0)) * (uLutSize - 1.0) + 0.5) / uLutSize;
        rgb = mix(rgb, texture(uLut, coord).rgb, uP[1]);
    } else if (uType == 14) {
        rgb = applyGrade(rgb);
    } else if (uType == 12) {
        vec3 key = vec3(uP[0], uP[1], uP[2]);
        float ky = luma(key);
        vec2 kc = vec2((key.b - ky) / 1.8556, (key.r - ky) / 1.5748);
        float y = luma(rgb);
        vec2 pc = vec2((rgb.b - y) / 1.8556, (rgb.r - y) / 1.5748);
        float d = length(pc - kc);
        float t0 = uP[3] * 0.5;
        float t1 = t0 + uP[4] * 0.5 + 0.0001;
        float keep = smoothstep(t0, t1, d);
        float spill = uP[5] * (1.0 - smoothstep(t1, t1 + 0.25, d));
        rgb = mix(rgb, vec3(y), spill);
        a *= keep;
    }
    rgb = clamp(rgb, vec3(0.0), vec3(1.0));
    outColor = vec4(rgb * a, a);
}
)";

}  // namespace uv::render
