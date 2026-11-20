#version 300 es
precision highp float;
layout(location = 0) in vec2 aParameter;
uniform mat4 uModel;
uniform mat4 uNormalModel;
uniform mat4 uViewProjection;
uniform vec3 uCamera;
uniform vec4 uWeights;
uniform float uTime;
uniform float uDeform;
uniform float uObject;
out vec3 vWorld;
out vec3 vNormal;
out vec2 vParameter;
out float vDepth;
invariant gl_Position;
const float TAU = 6.28318530718;
const float PI = 3.14159265359;

// Four closed, equal-topology surfaces: irregular hollow fold, knot, ribbon and porcelain loop.
// Shape coefficients interpolate before evaluation, so their seam remains closed during a morph.
vec3 centreline(float theta) {
    float phase = uObject * 1.37;
    float radius = dot(uWeights, vec4(0.665, 0.605, 0.670, 0.630));
    float wave = dot(uWeights, vec4(0.095, 0.170, 0.065, 0.110));
    float aspect = dot(uWeights, vec4(0.85, 1.08, 0.76, 0.94));
    float fold = dot(uWeights, vec4(0.150, 0.300, 0.265, 0.110));
    radius += wave * sin(3.0 * theta + phase) + 0.065 * cos(2.0 * theta - 0.7);
    radius += 0.038 * cos(theta + phase);
    float breath = uDeform * sin(uTime * (0.58 + uObject * 0.12) + theta * 2.0 + phase);
    return vec3((radius + breath) * cos(theta),
        (radius * aspect + breath * 0.75) * sin(theta),
        fold * sin(2.0 * theta + phase) + 0.060 * cos(3.0 * theta - phase));
}

vec3 pebble(vec2 uv) {
    float theta = uv.x * TAU;
    float phi = (1.0 - uv.y) * PI;
    vec3 direction = vec3(sin(phi) * cos(theta), cos(phi), sin(phi) * sin(theta));
    float radius = 0.78 * (1.0 + 0.085 * sin(theta * 3.0 + 0.7) * sin(phi) * sin(phi) +
        0.065 * cos(phi * 2.0) + 0.085 * direction.x - 0.060 * direction.y * direction.z);
    vec3 axes = vec3(0.88, dot(uWeights, vec4(0.89, 1.03, 0.76, 0.94)), 0.69);
    vec3 p = direction * radius * axes;
    p += vec3(0.10 * direction.y * direction.y - 0.035,
        0.075 * direction.x * direction.z, 0.09 * direction.x * direction.y);
    p += direction * uDeform * 0.32 * sin(uTime * 0.93 + phi * 3.0);
    return p;
}

vec3 sculpture(vec2 uv) {
    if (uObject > 2.5) return pebble(uv);
    float theta = uv.x * TAU;
    float phi = uv.y * TAU;
    float phase = uObject * 1.37;
    vec3 centre = centreline(theta);
    vec3 tangent = normalize(centreline(theta + 0.002) - centreline(theta - 0.002));
    vec3 radial = vec3(cos(theta), sin(theta), 0.0);
    radial = normalize(radial - tangent * dot(radial, tangent));
    vec3 binormal = normalize(cross(tangent, radial));
    float twist = dot(uWeights, vec4(1.15, 2.4, 3.8, 0.72));
    twist = twist * sin(theta + 0.3) + 0.30 * cos(2.0 * theta + phase);
    float tubeA = dot(uWeights, vec4(0.248, 0.192, 0.282, 0.245));
    float tubeB = dot(uWeights, vec4(0.188, 0.265, 0.064, 0.205));
    if (uObject > 0.5 && uObject < 1.5) { tubeA *= 0.87; tubeB *= 1.15; }
    if (uObject > 1.5 && uObject < 2.5) { tubeA *= 1.16; tubeB *= 0.36; }
    if (uObject > 2.5) { tubeA *= 0.89; tubeB *= 1.10; }
    float swelling = 1.0 + 0.19 * sin(3.0 * theta + phase) + 0.13 * cos(theta - 0.8);
    float a = cos(phi) * tubeA * swelling;
    float b = sin(phi) * tubeB * swelling;
    vec3 twistedRadial = radial * cos(twist) + binormal * sin(twist);
    vec3 twistedBinormal = -radial * sin(twist) + binormal * cos(twist);
    vec3 position = centre + twistedRadial * a + twistedBinormal * b;
    position.z += uDeform * 0.52 * sin(theta * 3.0 + phi * 2.0 + uTime * (0.57 + uObject * 0.10));
    return position;
}

void main() {
    vec3 position = sculpture(aParameter);
    const float epsilon = 0.0012;
    vec2 normalParameter = aParameter;
    // The pebble's UV poles collapse to a point; use their nearby limiting normal.
    if (uObject > 2.5) normalParameter.y = clamp(normalParameter.y, 0.002, 0.998);
    vec3 du = sculpture(normalParameter + vec2(epsilon, 0.0)) - sculpture(normalParameter - vec2(epsilon, 0.0));
    vec3 dv = sculpture(normalParameter + vec2(0.0, epsilon)) - sculpture(normalParameter - vec2(0.0, epsilon));
    vec3 normal = normalize(cross(dv, du));
    vec4 world = uModel * vec4(position, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(uNormalModel) * normal);
    vParameter = aParameter;
    vDepth = (uCamera.z - world.z) / 10.0;
    gl_Position = uViewProjection * world;
}
