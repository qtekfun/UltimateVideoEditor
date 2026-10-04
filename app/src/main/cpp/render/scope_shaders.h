#pragma once

// GLSL of the video scopes. The point shader mirrors render/scope_math.h: change both together.

namespace uv::render {

// One point per sample of the frame copy, drawn with additive blending into a half-float accumulation
// texture. Waveform and vectorscope add their count to alpha; the parade adds to the channel's own
// colour; the histogram adds to red, green, blue and (luma) alpha, one draw per channel.
inline constexpr const char* kScopePointVertex = R"(#version 320 es
precision highp float;
precision highp int;
uniform highp sampler2D uSrc;
uniform ivec2 uSrcSize;
uniform int uMode;     // 0 waveform, 1 parade, 2 vectorscope, 3 histogram
uniform int uChannel;  // parade 0..2, histogram 0..3 (3 = luma)
out vec4 vColor;

void main() {
    ivec2 p = ivec2(gl_VertexID % uSrcSize.x, gl_VertexID / uSrcSize.x);
    vec3 c = texelFetch(uSrc, p, 0).rgb;
    float col = (float(p.x) + 0.5) / float(uSrcSize.x);
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec2 pos = vec2(0.0);
    vec4 color = vec4(0.0, 0.0, 0.0, 1.0);
    if (uMode == 0) {
        pos = vec2(col * 2.0 - 1.0, clamp(y, 0.0, 1.0) * 2.0 - 1.0);
    } else if (uMode == 1) {
        float v = uChannel == 0 ? c.r : (uChannel == 1 ? c.g : c.b);
        pos = vec2((col / 3.0 + float(uChannel) / 3.0) * 2.0 - 1.0, clamp(v, 0.0, 1.0) * 2.0 - 1.0);
        color = vec4(uChannel == 0 ? 1.0 : 0.0, uChannel == 1 ? 1.0 : 0.0, uChannel == 2 ? 1.0 : 0.0, 0.0);
    } else if (uMode == 2) {
        pos = vec2((c.b - y) / 1.8556, (c.r - y) / 1.5748) * 1.8;
    } else {
        float v = uChannel == 0 ? c.r : (uChannel == 1 ? c.g : (uChannel == 2 ? c.b : y));
        pos = vec2(clamp(v, 0.0, 1.0) * 2.0 - 1.0, 0.0);
        color = vec4(uChannel == 0 ? 1.0 : 0.0, uChannel == 1 ? 1.0 : 0.0, uChannel == 2 ? 1.0 : 0.0, uChannel == 3 ? 1.0 : 0.0);
    }
    gl_Position = vec4(pos, 0.0, 1.0);
    // Wider footprints keep the dots visible when the panel shows the texture smaller than it is.
    gl_PointSize = uMode == 2 ? 3.0 : (uMode == 3 ? 1.0 : 2.0);
    vColor = color;
}
)";

inline constexpr const char* kScopePointFragment = R"(#version 320 es
precision highp float;
in vec4 vColor;
out vec4 outColor;
void main() { outColor = vColor; }
)";

// Turns the accumulated counts into the picture of the scope, with its graticule. Runs over the whole
// target (the vectorscope's target is a centred square); `uv` has its origin at the bottom left.
inline constexpr const char* kScopeDisplayVertex = R"(#version 320 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

inline constexpr const char* kScopeDisplayFragment = R"(#version 320 es
precision highp float;
precision highp int;
in vec2 vUv;
uniform highp sampler2D uAccum;
uniform int uMode;
uniform float uWaveGain;
uniform float uVectorGain;
uniform float uHistFull;
out vec4 outColor;

float lineAt(float v, float target, float width) { return 1.0 - smoothstep(0.0, width, abs(v - target)); }

// Position of a colour on the vectorscope, 0..1 over the square (centre 0.5).
vec2 vectorUv(vec3 c) {
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return vec2((c.b - y) / 1.8556, (c.r - y) / 1.5748) * 0.9 + 0.5;
}

void main() {
    vec2 uv = vUv;
    vec3 rgb = vec3(0.04);
    if (uMode == 0 || uMode == 1) {
        vec4 a = texture(uAccum, uv);
        if (uMode == 0) {
            float i = 1.0 - exp(-uWaveGain * a.a);
            rgb += vec3(0.35, 1.0, 0.55) * i;
        } else {
            rgb += vec3(1.0 - exp(-uWaveGain * a.r), 1.0 - exp(-uWaveGain * a.g), 1.0 - exp(-uWaveGain * a.b));
        }
        float grid = 0.0;
        for (int k = 0; k <= 4; ++k) grid = max(grid, lineAt(uv.y, float(k) * 0.25, 0.004));
        if (uMode == 1) {
            grid = max(grid, max(lineAt(uv.x, 1.0 / 3.0, 0.003), lineAt(uv.x, 2.0 / 3.0, 0.003)));
        }
        rgb = mix(rgb, vec3(0.65), grid * 0.35);
    } else if (uMode == 2) {
        vec4 a = texture(uAccum, uv * vec2(0.5, 1.0));
        float i = 1.0 - exp(-uVectorGain * a.a);
        rgb += vec3(0.35, 1.0, 0.55) * i;
        float r = length(uv - 0.5);
        float rings = max(lineAt(r, 0.225, 0.004), lineAt(r, 0.45, 0.004));
        float cross = max(lineAt(uv.x, 0.5, 0.003), lineAt(uv.y, 0.5, 0.003));
        // The skin tone line: 123 degrees counter clockwise from +Cb.
        vec2 dir = vec2(cos(2.1468), sin(2.1468));
        vec2 d = uv - 0.5;
        float along = dot(d, dir);
        float across = abs(d.x * dir.y - d.y * dir.x);
        float skin = (along > 0.0 && along < 0.45) ? 1.0 - smoothstep(0.0, 0.004, across) : 0.0;
        float boxes = 0.0;
        vec3 targets[6] = vec3[6](vec3(0.75, 0.0, 0.0), vec3(0.75, 0.75, 0.0), vec3(0.0, 0.75, 0.0),
                                  vec3(0.0, 0.75, 0.75), vec3(0.0, 0.0, 0.75), vec3(0.75, 0.0, 0.75));
        for (int k = 0; k < 6; ++k) {
            vec2 t = vectorUv(targets[k]);
            vec2 q = abs(uv - t);
            boxes = max(boxes, (max(q.x, q.y) < 0.018 && max(q.x, q.y) > 0.013) ? 1.0 : 0.0);
        }
        rgb = mix(rgb, vec3(0.65), max(max(rings, cross), boxes) * 0.4);
        rgb = mix(rgb, vec3(1.0, 0.75, 0.4), skin * 0.8);
    } else {
        vec4 counts = texelFetch(uAccum, ivec2(int(clamp(uv.x, 0.0, 0.9999) * 256.0), 0), 0);
        vec4 h = log(vec4(1.0) + counts) / log(1.0 + uHistFull);
        vec3 fill = vec3(0.0);
        fill.r = uv.y < h.r ? 0.75 : 0.0;
        fill.g = uv.y < h.g ? 0.75 : 0.0;
        fill.b = uv.y < h.b ? 0.75 : 0.0;
        rgb += fill * 0.8;
        float lumaEdge = 1.0 - smoothstep(0.0, 0.012, abs(uv.y - h.a));
        rgb = mix(rgb, vec3(1.0), lumaEdge * 0.8);
        float grid = 0.0;
        for (int k = 0; k <= 4; ++k) grid = max(grid, lineAt(uv.x, float(k) * 0.25, 0.003));
        rgb = mix(rgb, vec3(0.65), grid * 0.3);
    }
    outColor = vec4(clamp(rgb, vec3(0.0), vec3(1.0)), 1.0);
}
)";

}  // namespace uv::render
