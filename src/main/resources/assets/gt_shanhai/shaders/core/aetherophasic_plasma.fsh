#version 150

#moj_import <fog.glsl>

in vec3 viewPosition;
in float vertexDistance;
in vec2 uv;
in vec4 tint;

uniform vec4 ColorModulator;
uniform float GameTime;
uniform float FogStart;
uniform float FogEnd;

out vec4 fragColor;

const float TAU = 6.28318530718;

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

vec3 sphereDirection(vec2 sphereUv) {
    float lon = TAU * sphereUv.x;
    float lat = .5 * TAU * sphereUv.y;
    return vec3(cos(lon) * sin(lat), cos(lat), sin(lon) * sin(lat));
}

float viewCosine() {
    vec3 normal = cross(dFdx(viewPosition), dFdy(viewPosition));
    return abs(dot(normalize(normal), normalize(viewPosition)));
}

vec4 star(vec3 direction, float t) {
    float spin = 17.0 * t + .31 * direction.y;
    mat2 rotation = mat2(cos(spin), -sin(spin), sin(spin), cos(spin));
    direction.xz = rotation * direction.xz;
    vec3 drift = vec3(sin(19.0 * t), cos(13.0 * t), sin(11.0 * t));
    float currents = noise(direction * 8.0 + .75 * drift);
    float granules = noise(direction * (22.0 + 3.0 * currents) + currents * 2.7 - drift);
    float flecks = noise(direction * 58.0 + granules * .68);
    float hot = smoothstep(.30, .69, .52 * granules + .32 * currents + .16 * flecks);
    float limb = pow(1.0 - viewCosine(), 2.0);
    vec3 plasma = mix(vec3(.05, .24, .58), vec3(.84, .96, 1.0), hot);
    return vec4(mix(plasma, vec3(.78, .91, 1.0), .45 * limb), .80 + .15 * hot + .05 * limb);
}

vec4 corona(vec3 direction, float t) {
    float mu = viewCosine();
    vec3 drift = vec3(cos(13.0 * t), sin(9.0 * t), sin(13.0 * t));
    float cloud = noise(direction * 5.6 + .62 * drift);
    float strands = noise(direction * 26.0 + cloud * 3.1 + .36 * drift);
    float crest = .16 + .32 * cloud + .11 * strands;
    float rim = (mu - crest) / (.09 + .17 * cloud);
    float limb = smoothstep(.025, .29, mu);
    float band = exp(-rim * rim) * limb;
    float patches = smoothstep(.28, .79, .67 * cloud + .33 * strands);
    float hotRim = band * (.04 + .90 * patches * patches);
    float halo = .14 * cloud * cloud * exp(-.28 * rim * rim) * limb;
    return vec4(mix(vec3(.64, .81, 1.0), vec3(1.0), patches),
                hotRim + halo + .021 * strands);
}

vec4 streamer(vec2 stripUv, float t) {
    float across = stripUv.x * 2.0 - 1.0;
    float field = across * across;
    float profile = (.65 * exp(-7.5 * field) + .35 * exp(-30.0 * field))
                    * (1.0 - smoothstep(.68, 1.0, abs(across)));
    float pulse = .72 + .18 * sin(29.0 * stripUv.y - 91.0 * t)
                       + .10 * sin(70.0 * stripUv.y - 161.0 * t);
    return vec4(vec3(1.0), profile * pulse);
}

vec4 aperture(vec2 patchUv, float t) {
    vec2 p = patchUv * 2.0 - 1.0;
    float r = length(p);
    float edge = 1.0 - smoothstep(.57, 1.0, r);
    float plume = exp(-6.7 * r * r);
    float spark = exp(-34.0 * r * r);
    vec2 slope = vec2(p.x + p.y, p.x - p.y);
    float rays = exp(-55.0 * abs(slope.x * slope.y) - 8.0 * r) * (.74 + .26 * cos(79.0 * t));
    vec3 warmth = mix(vec3(1.0, .74, .44), vec3(1.0), smoothstep(.12, 1.0, spark + rays));
    return vec4(warmth, edge * (.62 * plume + .41 * spark + .28 * rays));
}

vec4 veil(vec2 stripUv, float t) {
    float across = stripUv.x * 2.0 - 1.0;
    float along = stripUv.y;
    float bend = .19 * sin(15.0 * along - 69.0 * t) + .10 * sin(48.0 * along + 29.0 * t);
    float channel = across - bend;
    float edge = 1.0 - smoothstep(.54, 1.0, abs(across));
    float gauze = exp(-3.3 * channel * channel);
    float splits = .57 + .43 * sin(10.5 * channel + 1.4 * sin(30.0 * along - 73.0 * t));
    float folds = noise(vec3(3.1 * across, 17.0 * along, 1.9 * sin(17.0 * t)));
    return vec4(vec3(.82, .93, 1.0), edge * gauze * (.26 + .74 * folds) * (.44 + .56 * splits));
}

void main() {
    float t = GameTime * TAU;
    vec4 emission;
    // 每个四边形仅使用一段 U 区间，顶点格式和混合状态可以共用。
    if (uv.x < 1.5) {
        emission = star(sphereDirection(uv), t);
    } else if (uv.x < 3.5) {
        emission = streamer(uv - vec2(2, 0), t);
    } else if (uv.x < 5.5) {
        emission = corona(sphereDirection(uv - vec2(4, 0)), t);
    } else if (uv.x < 7.5) {
        emission = aperture(uv - vec2(6, 0), t);
    } else {
        emission = veil(uv - vec2(8, 0), t);
    }
    fragColor = emission * tint * ColorModulator;
    fragColor.a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
}
