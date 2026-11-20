#version 300 es
precision highp float;
in vec2 vUv;
uniform vec3 uPrimary;
uniform vec3 uSecondary;
uniform vec3 uTertiary;
uniform vec3 uSurface;
uniform vec3 uInk;
uniform vec3 uLight;
uniform int uMode;
uniform float uTime;
uniform float uScroll;
uniform vec4 uWeights;
uniform int uEncoding;
out vec4 fragment;
vec3 encodeSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        step(vec3(0.0031308), c));
}
float shadow(vec2 p, vec2 centre, vec2 radii) {
    vec2 local = (p - centre) / radii;
    return exp(-dot(local, local) * 2.7);
}
void main() {
    if (uMode == 0) {
        // Private refraction environment. This plane is never drawn behind the Compose controls.
        vec3 environment = uSurface * 0.90 + uLight * 0.055;
        float window = exp(-pow((vUv.x - 0.94) * 6.0, 2.0) - pow((vUv.y - 0.79) * 2.5, 2.0));
        environment += mix(uLight, uSecondary, 0.17) * window * 0.17;
        fragment = vec4(environment / (vec3(1.0) + environment), 1.0);
    } else {
        float shift = uScroll * 0.48;
        float centreX = dot(uWeights, vec4(0.67, 0.53, 0.73, 0.62));
        float amount = shadow(vUv, vec2(centreX + 0.035, 0.115 + shift), vec2(0.165, 0.028)) * 0.14;
        amount += shadow(vUv, vec2(0.82, 0.067 + shift), vec2(0.053, 0.013)) * 0.055;
        vec3 colour = uEncoding == 2 ? uSurface * 0.14 : encodeSrgb(uSurface * 0.14);
        fragment = vec4(colour * amount, amount);
    }
}
