#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec2 vParameter;
in float vDepth;
uniform vec3 uCamera;
uniform vec3 uPrimary;
uniform vec3 uSecondary;
uniform vec3 uTertiary;
uniform vec3 uSurface;
uniform vec3 uInk;
uniform vec3 uLight;
uniform vec3 uBaseColour;
uniform vec4 uMaterial; // metallic, perceptual roughness, transmission, IOR.
uniform vec2 uResolution;
uniform sampler2D uScene;
uniform sampler2D uBackDepth;
uniform int uEncoding;
uniform int uQuality;
uniform float uEmission;
uniform float uObject;
uniform float uTime;
out vec4 fragment;
const float PI = 3.14159265359;

float hash(vec2 value) { return fract(sin(dot(value, vec2(127.1, 311.7))) * 43758.5453); }
float distributionGgx(float nDotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float denominator = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / max(PI * denominator * denominator, 0.00005);
}
float smith(float nDotV, float roughness) {
    float k = (roughness + 1.0) * (roughness + 1.0) * 0.125;
    return nDotV / max(nDotV * (1.0 - k) + k, 0.0001);
}
vec3 schlick(float cosine, vec3 f0) {
    return f0 + (vec3(1.0) - f0) * pow(1.0 - clamp(cosine, 0.0, 1.0), 5.0);
}
float softRectangle(vec2 p, vec2 halfSize, float feather) {
    vec2 distanceToEdge = abs(p) - halfSize;
    return 1.0 - smoothstep(-feather, feather, max(distanceToEdge.x, distanceToEdge.y));
}
vec3 environment(vec3 ray, float roughness) {
    // Studio softbox, a tall rain-window and a warmer café practical light.
    // Their spectral colours all come from the active theme, not a fixed purple scene.
    vec3 ambient = uSurface * 0.26 + uLight * 0.10 + uInk * 0.018;
    float front = max(ray.z, 0.10);
    vec2 p = ray.xy / front;
    float feather = 0.075 + roughness * 0.42;
    float box = softRectangle(p - vec2(-0.72, 0.66), vec2(0.39, 0.76), feather);
    float window = softRectangle(p - vec2(0.98, 0.12), vec2(0.24, 1.20), feather);
    float frame = 1.0 - (1.0 - smoothstep(0.010, 0.030 + roughness * 0.03, abs(p.x - 0.98))) * 0.40;
    window *= frame;
    float practical = exp(-dot(p - vec2(-0.40, -0.66), p - vec2(-0.40, -0.66)) * (9.0 - roughness * 5.0));
    vec3 softboxColour = mix(uLight, uInk, 0.20);
    vec3 windowColour = mix(softboxColour, uSecondary, 0.11);
    vec3 warmColour = mix(softboxColour, uTertiary, 0.24);
    ambient += softboxColour * box * 4.1 + windowColour * window * 2.8 + warmColour * practical * 1.25;
    // Sparse, anchored rain reflections. These belong to the window, never a screen grid.
    float rainLine = pow(max(0.0, sin(p.x * 61.0 + floor(p.y * 9.0) * 2.17)), 36.0);
    float rainBreak = smoothstep(0.67, 0.99, hash(floor(p * vec2(17.0, 24.0))));
    ambient += windowColour * window * rainLine * rainBreak * (1.0 - roughness) * 0.20;
    return ambient;
}
vec3 lightBrdf(vec3 normal, vec3 viewDirection, vec3 lightDirection, vec3 radiance,
               vec3 albedo, float metallic, float roughness, vec3 f0) {
    vec3 halfway = normalize(viewDirection + lightDirection);
    float nDotL = max(dot(normal, lightDirection), 0.0);
    float nDotV = max(dot(normal, viewDirection), 0.001);
    float nDotH = max(dot(normal, halfway), 0.0);
    vec3 fresnel = schlick(max(dot(halfway, viewDirection), 0.0), f0);
    float distribution = distributionGgx(nDotH, roughness);
    float geometry = smith(nDotV, roughness) * smith(nDotL, roughness);
    vec3 specular = distribution * geometry * fresnel / max(4.0 * nDotV * nDotL, 0.001);
    vec3 diffuse = (vec3(1.0) - fresnel) * (1.0 - metallic) * albedo / PI;
    return (diffuse + specular) * radiance * nDotL;
}
float unpackDepth(vec4 value) {
    return dot(value * (255.0 / 256.0), vec4(1.0 / 16777216.0, 1.0 / 65536.0, 1.0 / 256.0, 1.0));
}
vec3 unpackScene(vec3 colour) {
    return colour / max(vec3(0.025), vec3(1.0) - colour);
}
vec3 encodeSrgb(vec3 colour) {
    return mix(colour * 12.92, 1.055 * pow(max(colour, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        step(vec3(0.0031308), colour));
}

void main() {
    float metallic = clamp(uMaterial.x, 0.0, 1.0);
    float roughness = clamp(uMaterial.y, 0.055, 0.95);
    float transmission = clamp(uMaterial.z, 0.0, 1.0);
    float ior = uMaterial.w;
    vec3 normal = normalize(vNormal);
    vec3 viewDirection = normalize(uCamera - vWorld);
    // Fine wet/alloy/porcelain microstructure is attached to the surface's own UV coordinates.
    float micro = sin(vParameter.x * 527.0) * sin(vParameter.y * 313.0);
    roughness = clamp(roughness + micro * 0.014 * (1.0 - transmission), 0.055, 0.95);
    vec3 albedo = max(uBaseColour, vec3(0.002));
    float dielectric = pow((ior - 1.0) / (ior + 1.0), 2.0);
    vec3 f0 = mix(vec3(dielectric), albedo, metallic);
    vec3 reflection = reflect(-viewDirection, normal);
    vec3 fresnel = schlick(max(dot(normal, viewDirection), 0.0), f0);
    vec3 environmentSpecular = environment(reflection, roughness) * fresnel;
    vec3 diffuseAmbient = environment(normal, 0.82) * albedo * (1.0 - metallic) * 0.22;
    vec3 lightColour = mix(uLight, uInk, 0.17);
    vec3 colour = diffuseAmbient + environmentSpecular;
    colour += lightBrdf(normal, viewDirection, normalize(vec3(-0.62, 0.86, 1.10)),
        lightColour * 2.6, albedo, metallic, roughness, f0);
    colour += lightBrdf(normal, viewDirection, normalize(vec3(0.92, -0.15, 0.55)),
        mix(lightColour, uTertiary, 0.18) * 1.1, albedo, metallic, roughness, f0);

    if (transmission > 0.001 && uEncoding > 0) {
        vec2 screen = gl_FragCoord.xy / uResolution;
        float backDepth = unpackDepth(texture(uBackDepth, screen)) * 10.0;
        float frontDepth = vDepth * 10.0;
        float thickness = backDepth < 9.9 ? clamp(backDepth - frontDepth, 0.02, 0.85) : 0.08;
        vec3 ray = refract(-viewDirection, normal, 1.0 / ior);
        vec2 offset = ray.xy * thickness / max(frontDepth * 0.72, 0.3);
        offset.x *= uResolution.y / uResolution.x;
        offset *= 0.46;
        vec2 refractedUv = clamp(screen + offset, vec2(0.002), vec2(0.998));
        float level = roughness * (uQuality == 2 ? 3.0 : 4.5);
        vec3 refracted = textureLod(uScene, refractedUv, level).rgb;
        refracted = unpackScene(refracted);
        vec3 absorption = exp(-(vec3(1.0) - clamp(albedo, 0.0, 1.0)) * thickness * 0.95);
        float edgeFresnel = dielectric + (1.0 - dielectric) * pow(1.0 - max(dot(normal, viewDirection), 0.0), 5.0);
        vec3 frostScattering = albedo * lightColour * roughness * 0.34 * (1.0 - edgeFresnel);
        vec3 throughGlass = refracted * absorption * (1.0 - edgeFresnel) +
            environment(reflection, roughness) * edgeFresnel + frostScattering;
        colour = mix(colour, throughGlass + colour * 0.15, transmission);
    }
    // A small, primary-derived luminescent interior in the secondary ribbon.
    // Added radiance is independent of the light direction; there is no bloom or exterior halo.
    if (uEmission > 0.0) {
        float interior = 1.0 - smoothstep(0.028, 0.095, abs(vParameter.y - 0.5));
        float surfacePatch = 0.20 + 0.80 * (1.0 - smoothstep(0.15, 0.25, abs(vParameter.x - 0.64)));
        float breathing = 1.0 + 0.035 * sin(uTime * 0.89 + uObject * 1.1);
        colour += mix(uPrimary, uLight, 0.12) * uEmission * interior * surfacePatch * breathing;
    }
    // The private FBO stores tone-mapped linear values; presentation is encoded exactly once.
    colour = max(colour, vec3(0.0));
    colour = colour / (vec3(1.0) + colour);
    if (uEncoding == 0) {
        fragment = vec4(colour, 1.0);
    } else if (uEncoding == 2) {
        // High/balanced presentation blends and defocuses in linear premultiplied space.
        float alpha = 1.0 - transmission * 0.075;
        fragment = vec4(colour * alpha, alpha);
    } else {
        float alpha = 1.0 - transmission * 0.075;
        fragment = vec4(encodeSrgb(colour) * alpha, alpha);
    }
}
