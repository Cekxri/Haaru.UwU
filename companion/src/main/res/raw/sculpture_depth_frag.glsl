#version 300 es
precision highp float;
in float vDepth;
out vec4 fragment;
vec4 packDepth(float depth) {
    vec4 depthBytes = fract(clamp(depth, 0.0, 0.999) * vec4(16777216.0, 65536.0, 256.0, 1.0));
    depthBytes -= depthBytes.xxyz * vec4(0.0, 0.00390625, 0.00390625, 0.00390625);
    // Base-256 digits must be rescaled for RGBA8's byte/255 normalisation.
    return depthBytes * (256.0 / 255.0);
}
void main() {
    fragment = packDepth(vDepth);
}
