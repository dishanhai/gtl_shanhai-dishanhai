#version 150

#moj_import <fog.glsl>

in vec3 localPosition;
in vec2 uv;
in vec4 tint;
in float vertexDistance;

uniform vec3 EyeLocal;
uniform float PlasmaPhase;
uniform vec3 SurfaceDrift;
uniform vec3 CoronaDrift;
uniform vec4 Activity;
uniform mat4 CollectorFrame;
uniform float ContactAngle;
uniform float ReserveFraction;
uniform float ObservationExposure;
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

float collectorHeating(vec3 normal) {
    if (Activity.x <= .46 && Activity.z == 0.0) return 0.0;
    vec3 local = mat3(CollectorFrame) * normal;
    float band = .10 + .043 * Activity.y;
    if (abs(local.y) >= band) return 0.0;
    float angle = atan(local.z, local.x) - ContactAngle;
    const float separation = 2.09439510239;
    float primary = mod(angle + separation * .5, separation) - separation * .5;
    float secondary = mod(angle, separation) - separation * .5;
    float along = mix(abs(primary), min(abs(primary), abs(secondary)), Activity.y);
    float distance = length(vec2(along * .84, local.y)) / band;
    float halo = 1.0 - smoothstep(.11, 1.0, distance);
    float ripple = (.5 + .5 * cos(19.0 * distance - 23.0 * PlasmaPhase)) * halo;
    float wake = smoothstep(.46, .85, Activity.x);
    return (halo * .44 + ripple * .42) * (wake + Activity.z * .12) * (1.0 + Activity.y * .32);
}

vec3 reserveColor(vec3 color) {
    vec3 cooled = vec3(color.r * .83 + color.b * .09, color.g * .66, color.b * .94);
    return mix(cooled, color, ReserveFraction);
}

vec3 photosphere() {
    vec3 normal = normalize(localPosition);
    float mu = max(0.0, dot(normal, normalize(EyeLocal - localPosition)));
    float spin = PlasmaPhase + .28 * normal.y;
    vec3 field = normal;
    field.xz = mat2(cos(spin), -sin(spin), sin(spin), cos(spin)) * field.xz;
    vec3 drift = SurfaceDrift;

    float currents = noise(field * 7.0 + .65 * drift);
    float roll = sin(field.y * 6.0 + 2.0 * PlasmaPhase) + cos(field.z * 5.0 - 3.0 * PlasmaPhase);
    vec3 warped = field * 22.0 + currents * 2.6 + drift * .83 + vec3(roll * .42, roll * -.23, 0.0);
    float filaments = 1.0 - abs(2.0 * noise(warped) - 1.0);
    vec3 grain = field * 63.0 + filaments * 1.6 - drift * 1.17;
    float footprint = max(length(dFdx(grain)), length(dFdy(grain)));
    float grainWeight = 1.0 - smoothstep(.5, 1.25, footprint);
    float granules = grainWeight > 0.0 ? mix(.5, noise(grain), grainWeight) : .5;
    float contact = collectorHeating(normal);
    float spots = smoothstep(.58, .84, currents) * (.5 + .5 * sin(5.0 * PlasmaPhase + field.x * 9.0));
    float heat = .51 * filaments + .29 * granules + .20 * currents + .095 * spots + .22 * contact;
    float white = smoothstep(.52, .84, heat);
    vec3 ionized = mix(vec3(.24, .31, .67), vec3(.57, .72, 1.0), smoothstep(.23, .63, heat));
    vec3 surface = mix(ionized, vec3(.96, .98, 1.0), white);
    float edge = pow(1.0 - mu, 3.4);
    return reserveColor(mix(mix(surface, vec3(.88, .94, 1.0), edge * .92), vec3(.98, .99, 1.0), contact * .34));
}

vec4 corona(vec2 p) {
    float lengthSquared = dot(p, p);
    if (lengthSquared < (.995 / 1.62) * (.995 / 1.62) || lengthSquared >= 1.0) discard;

    float inverseLength = inversesqrt(lengthSquared);
    float radius = (1.0 / inverseLength) * 1.62;
    vec2 direction = p * inverseLength;
    float altitude = radius - 1.0;
    vec3 drift = CoronaDrift;
    float clouds = noise(vec3(direction * 5.5, 0.0) + drift);
    float shear = sin(9.0 * direction.x + 11.0 * altitude - 5.0 * PlasmaPhase) * exp(-10.0 * altitude);
    vec2 curl = vec2(-direction.y, direction.x) * shear * (.16 + .44 * Activity.y);
    float threads = noise(vec3(direction * (18.0 + 13.0 * altitude) + curl, 0.0) - 1.5 * drift);
    float breath = .5 + .5 * sin(3.0 * PlasmaPhase + 2.0 * direction.x - direction.y);
    float crest = .025 + .042 * clouds + .044 * threads + .016 * breath;
    float limb = 1.0 - smoothstep(.004, crest, altitude);
    float veil = exp(-altitude * (5.6 - 2.1 * clouds)) * (.23 + .16 * threads);
    float wisps = exp(-altitude * 6.1) * smoothstep(.32, .83, clouds * .55 + threads * .45) * .33;
    float fade = 1.0 - smoothstep(1.29, 1.62, radius);
    float angle = atan(direction.y, direction.x);
    float spiral = 8.0 * angle + altitude * (10.0 + 7.0 * clouds) - 3.0 * PlasmaPhase;
    float plumes = pow(.5 + .5 * cos(spiral), 7.0) * smoothstep(.015, .075, altitude)
            * exp(-altitude * 4.8) * (.35 + .65 * threads);
    float opacity = min(1.0, limb * .76 + veil + wisps + (.49 + .20 * Activity.y) * plumes) * fade;
    vec3 color = mix(vec3(.36, .32, .94), vec3(.75, .82, 1.0), exp(-altitude * 9.0));
    color = mix(color, vec3(.96, .98, 1.0), limb);
    color = mix(color, vec3(.44, .76, 1.0), plumes);
    return vec4(reserveColor(color), opacity * mix(.82, 1.0, ReserveFraction));
}

void main() {
    if (uv.x < 1.0) {
        fragColor = linear_fog(vec4(photosphere() * ObservationExposure, 1.0), vertexDistance, FogStart, FogEnd, FogColor);
    } else {
        fragColor = corona(vec2(uv.x - 2.0, uv.y) * 2.0 - 1.0) * tint;
        fragColor.a *= linear_fog_fade(vertexDistance, FogStart, FogEnd) * ObservationExposure;
    }
}
