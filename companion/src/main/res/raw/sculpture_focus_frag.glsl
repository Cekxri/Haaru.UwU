#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uColour;
uniform sampler2D uDepth;
uniform vec2 uTexel;
uniform float uFocusPlane;
uniform float uMaximumRadius;
out vec4 fragment;

float depthAt(vec2 uv) {
    vec4 depthBytes = texture(uDepth, uv);
    return dot(depthBytes * (255.0 / 256.0),
        vec4(1.0 / 16777216.0, 1.0 / 65536.0, 1.0 / 256.0, 1.0)) * 10.0;
}
vec3 encodeSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        step(vec3(0.0031308), c));
}
vec4 present(vec4 linearPremultiplied) {
    float alpha = clamp(linearPremultiplied.a, 0.0, 1.0);
    if (alpha < 0.00001) return vec4(0.0);
    vec3 linearColour = max(linearPremultiplied.rgb / alpha, vec3(0.0));
    return vec4(encodeSrgb(linearColour) * alpha, alpha);
}
void main() {
    vec4 centre = texture(uColour, vUv);
    float depth = depthAt(vUv);
    // No geometry here means no camera blur: Compose and the empty stage remain untouched.
    if (depth >= 9.9 || centre.a < 0.00001) {
        fragment = present(centre);
        return;
    }
    float radius = clamp((abs(depth - uFocusPlane) - 0.18) * 4.8, 0.0, uMaximumRadius);
    if (radius < 0.10) {
        fragment = present(centre);
        return;
    }
    vec2 directions[4] = vec2[4](vec2(-0.7071, -0.7071), vec2(0.7071, -0.7071),
        vec2(-0.7071, 0.7071), vec2(0.7071, 0.7071));
    vec4 sum = centre * 0.50;
    float total = 0.50;
    for (int index = 0; index < 4; index++) {
        vec2 uv = clamp(vUv + directions[index] * radius * uTexel, vec2(0.002), vec2(0.998));
        float neighbourDepth = depthAt(uv);
        // Reject another near/focal object's colour, but let transparent edges soften inwards.
        float belongs = neighbourDepth >= 9.9 ? 1.0 :
            1.0 - smoothstep(0.12, 0.36, abs(neighbourDepth - depth));
        float weight = 0.125 * belongs;
        sum += texture(uColour, uv) * weight;
        total += weight;
    }
    fragment = present(sum / total);
}
