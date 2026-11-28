#version 150

uniform float SkyTime;
uniform float SkyAspect;
uniform float SkyReveal;
uniform float SkyOpacity;
uniform vec2 SkyParallax;

in vec2 skyUv;
out vec4 fragColor;

float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * vec3(.1031, .1030, .0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(cell), hash(cell + vec2(1.0, 0.0)), f.x),
               mix(hash(cell + vec2(0.0, 1.0)), hash(cell + vec2(1.0, 1.0)), f.x), f.y);
}

vec3 stars(vec2 screen, float scale, float speed, float threshold, float seed, bool nearLayer) {
    vec2 grid = screen * scale + vec2(seed, seed * .31)
                + SkyTime * speed * vec2(1.0, .24);
    vec2 cell = floor(grid);
    float magnitude = hash(cell + 7.21);
    if (magnitude < threshold) return vec3(0.0);

    vec2 center = .2 + .6 * vec2(hash(cell + 19.12), hash(cell + 3.17));
    vec2 delta = abs(fract(grid) - center);
    float radius = length(delta);
    float core = 1.0 - smoothstep(.013, nearLayer ? .078 : .105, radius);
    float halo = (1.0 - smoothstep(.02, .20, radius)) * (nearLayer ? .19 : .05);
    vec3 tint = mix(vec3(.56, .71, 1.0), vec3(1.0, .80, .56), hash(cell + 8.66));
    float phase = SkyTime * (1.25 + magnitude) + seed + magnitude * 238.0;
    float flicker = nearLayer ? .85 + .15 * sin(phase) : .93 + .07 * sin(phase * .62);
    return tint * (core + halo) * flicker * (nearLayer ? .82 : .48);
}

void main() {
    vec2 centered = (skyUv - .5) * vec2(SkyAspect, 1.0);
    vec2 galactic = mat2(.84, .54, -.54, .84) * centered;
    float clock = SkyTime * .005;
    vec2 cloud = (galactic + SkyParallax * .12) * vec2(5.3, 9.0) + vec2(clock, -.34 * clock);
    float low = noise(cloud);
    float mid = noise(cloud * 2.25 + vec2(8.7, 4.1));
    float filament = noise(cloud * 5.7 + vec2(23.1, 13.9));
    float latitude = galactic.y + .19 - .21 * (low - .5);
    float band = exp(-13.0 * latitude * latitude);
    float dust = smoothstep(.36, .75, .52 * low + .34 * mid + .14 * filament);
    float pale = smoothstep(.24, .75, mid + .18 * filament);
    vec3 mist = mix(vec3(.20, .14, .32), vec3(.29, .47, .76), pale);
    vec3 color = vec3(.016, .020, .044) + mist * band * (.04 + .42 * dust);
    color += vec3(.085, .038, .019) * smoothstep(-.15, .57, galactic.x) * band * low * mid;
    color += stars(centered + SkyParallax * .04, 121.0, .065, .75, 27.9, false);
    color += stars(centered + SkyParallax * .09, 72.0, .085, .82, 49.1, false);
    color += stars(centered + SkyParallax * .27, 32.0, .105, .91, 83.4, true);
    color *= 1.0 - .48 * smoothstep(.27, 1.14, length(centered * vec2(.84, 1.0)));
    fragColor = vec4(mix(vec3(.016, .020, .044), color, SkyReveal), SkyOpacity);
}
