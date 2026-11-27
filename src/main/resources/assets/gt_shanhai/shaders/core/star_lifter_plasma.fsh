#version 150

#moj_import <fog.glsl>

in vec3 localPosition;
in vec2 uv;
in vec4 tint;
in float vertexDistance;

uniform vec3 EyeLocal;
uniform float StarSeconds;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * vec3(.1031, .11369, .13787));
    p += dot(p, p.yxz + 19.19);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x),
            mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
        mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x),
            mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y),
        f.z);
}

vec3 photosphere() {
    vec3 normal = normalize(localPosition);
    float mu = max(0.0, dot(normal, normalize(EyeLocal - localPosition)));
    float spin = StarSeconds * .12 + .28 * normal.y;
    vec3 field = normal;
    field.xz = mat2(cos(spin), -sin(spin), sin(spin), cos(spin)) * field.xz;
    vec3 drift = vec3(sin(StarSeconds * .42), cos(StarSeconds * .35), sin(StarSeconds * .31));

    float currents = noise(field * 7.0 + .65 * drift);
    vec3 warped = field * 22.0 + currents * 2.6 + drift * .83;
    float filaments = 1.0 - abs(2.0 * noise(warped) - 1.0);
    float granules = noise(field * 63.0 + filaments * 1.6 - drift * 1.17);
    float heat = .51 * filaments + .29 * granules + .20 * currents;
    float white = smoothstep(.52, .84, heat);
    vec3 ionized = mix(vec3(.24, .31, .67), vec3(.57, .72, 1.0), smoothstep(.23, .63, heat));
    vec3 surface = mix(ionized, vec3(.96, .98, 1.0), white);
    float edge = pow(1.0 - mu, 3.4);
    return mix(surface, vec3(.91, .95, 1.0), edge);
}

vec4 corona(vec2 p) {
    float radius = length(p) * 1.62;
    if (radius < .995 || radius >= 1.62) return vec4(0.0);

    vec2 direction = normalize(p);
    float altitude = radius - 1.0;
    float clouds = noise(vec3(direction * 5.5, StarSeconds * .17));
    float threads = noise(vec3(direction * (18.0 + 13.0 * altitude), -StarSeconds * .35));
    float crest = .032 + .038 * clouds + .033 * threads;
    float limb = 1.0 - smoothstep(.004, crest, altitude);
    float veil = exp(-altitude * (5.6 - 2.1 * clouds)) * (.23 + .16 * threads);
    float wisps = exp(-altitude * 6.1) * smoothstep(.32, .83, clouds * .55 + threads * .45) * .33;
    float fade = 1.0 - smoothstep(1.29, 1.62, radius);
    float opacity = min(1.0, limb * .86 + veil + wisps) * fade;
    vec3 color = mix(vec3(.36, .32, .94), vec3(.75, .82, 1.0), exp(-altitude * 9.0));
    color = mix(color, vec3(.96, .98, 1.0), limb);
    return vec4(color, opacity);
}

void main() {
    if (uv.x < 1.0) {
        fragColor = linear_fog(vec4(photosphere(), 1.0), vertexDistance, FogStart, FogEnd, FogColor);
    } else {
        fragColor = corona(vec2(uv.x - 2.0, uv.y) * 2.0 - 1.0) * tint;
        fragColor.a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    }
}
