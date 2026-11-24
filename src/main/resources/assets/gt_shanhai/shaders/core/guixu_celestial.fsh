#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float SurfaceSeconds;
uniform vec3 MagneticAxis;

in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.28318531;

vec4 surfaceTexel(int tile, vec2 uv) {
    vec2 atlasSize = vec2(textureSize(Sampler0, 0));
    vec2 tileSize = atlasSize / vec2(4.0, 5.0);
    vec2 origin = vec2(tile % 4, tile / 4) * tileSize;
    // Repeat inside the tile. Bilinear filtering sees the matching seam texels, never the next tile.
    vec2 pixel = origin + vec2(0.5) + vec2(fract(uv.x), clamp(uv.y, 0.0, 1.0)) * (tileSize - 1.0);
    return textureLod(Sampler0, pixel / atlasSize, 0.0);
}

vec4 solarPhotosphere(int tile, vec2 uv, float height, float equator, float tide) {
    // Differential rotation advects the spots and granules as a single map.
    float longitude = uv.x - 0.009 * height * height * SurfaceSeconds;
    float phi = TAU * longitude;
    float convection = sin(11.0 * phi + 17.0 * height + SurfaceSeconds * 0.7)
                     * sin(17.0 * phi - 21.0 * height - SurfaceSeconds * 0.4);
    float plume = sin(5.0 * phi - 10.0 * height + SurfaceSeconds * 2.3);
    return surfaceTexel(tile, vec2(longitude + tide * 0.018 * plume * equator,
                                 uv.y + (0.002 * convection + 0.033 * tide * plume) * equator));
}

vec4 cloudBelts(int tile, int kind, vec2 uv, float height, float equator, float tide) {
    // Neighboring jets flow east and west in the planet's rotating frame.
    float belt = sin(height * (kind == 3 ? 24.0 : 35.0) + 0.8);
    float jet = belt * (1.5 - 0.5 * belt * belt);
    // The circumpolar jet stays coherent while the lower latitude belts shear.
    float movingBelt = kind == 3 ? 1.0 - smoothstep(0.92, 0.976, abs(height)) : 1.0;
    float longitude = uv.x + 0.012 * jet * movingBelt * equator * SurfaceSeconds;
    float ripple = sin(TAU * longitude * 5.0 + height * 24.0 - SurfaceSeconds * 1.2);
    float boundary = (1.0 - belt * belt) * equator * movingBelt;
    float shear = tide * movingBelt * equator;
    float wave = sin(TAU * longitude * 3.0 - height * 11.0 + SurfaceSeconds * 2.1);
    vec4 surface = surfaceTexel(tile, vec2(longitude + 0.065 * shear * (wave + jet),
                                          uv.y + (0.004 * boundary + 0.043 * shear) * ripple));
    vec3 exposedClouds = kind == 3 ? vec3(0.72, 0.86, 0.99) : vec3(0.98, 0.81, 0.59);
    surface.rgb = mix(surface.rgb, exposedClouds, 0.63 * shear * (0.55 + 0.45 * wave));
    return surface;
}

vec3 surfaceNormal(vec2 uv, float height, float equator) {
    float longitude = TAU * uv.x;
    return vec3(equator * cos(longitude), height, equator * sin(longitude));
}

vec3 tidalFractures(vec3 color, vec3 normal, float tide) {
    // The fractures live on the crust instead of sliding across it as the body rotates.
    float warp = sin(dot(normal, vec3(8.2, 11.5, -7.0)));
    float rift = abs(sin(dot(normal, vec3(6.1, -3.4, 5.7)) + 0.46 * warp));
    float branch = abs(sin(dot(normal, vec3(-4.2, 8.4, 4.9)) + 0.53 * warp));
    float distance = min(rift, branch + 0.18 * (1.0 + warp));
    float crackWidth = 0.12 * tide;
    float pixel = min(0.22, fwidth(distance));
    float line = 1.0 - smoothstep(crackWidth, crackWidth + pixel + 0.025, distance);
    float heat = smoothstep(0.19, 0.72, tide) * (0.72 + 0.28 * warp);
    color *= 1.0 - line * smoothstep(0.025, 0.33, tide) * 0.82;
    return mix(color, vec3(1.0, 0.30, 0.07), line * heat);
}

vec4 pulsarSurface(vec2 uv, float height, float equator) {
    vec3 normal = surfaceNormal(uv, height, equator);
    // The emitting footprints stay fixed relative to the beams. An offset
    // cooler patch leaves a crescent-shaped hot region at the weaker pole.
    float magnetic = dot(normal, MagneticAxis);
    float offset = dot(normal, normalize(-MagneticAxis + vec3(0.0, 0.0, 0.11)));
    float cap = smoothstep(0.974, 0.999, magnetic);
    float crescent = smoothstep(0.948, 0.989, -magnetic) * (1.0 - smoothstep(0.965, 0.988, offset));
    float warm = smoothstep(0.912, 0.974, abs(magnetic));
    vec4 surface = surfaceTexel(9, uv);
    surface.rgb = mix(surface.rgb, vec3(0.52, 0.72, 0.89), 0.76 * warm);
    surface.rgb = mix(surface.rgb, vec3(0.94, 0.98, 1.0), max(cap, 0.82 * crescent));
    return surface;
}

vec4 redSupergiant(vec2 uv, float height, float equator) {
    float longitude = uv.x - 0.008 * height * height * SurfaceSeconds;
    vec3 normal = vec3(equator * cos(TAU * longitude), height, equator * sin(TAU * longitude));
    // Broad convection cells evolve more slowly than the grain of an ordinary photosphere.
    float overturn = sin(dot(normal, vec3(5.7, 3.1, 4.2)) - 0.42 * SurfaceSeconds) *
                     sin(dot(normal, vec3(-4.1, 5.8, 2.8)) + 0.37 * SurfaceSeconds);
    vec4 surface = surfaceTexel(14, vec2(longitude + 0.006 * overturn * equator, uv.y + 0.005 * overturn * equator));
    surface.rgb *= 1.0 + 0.08 * overturn;
    return surface;
}

void main() {
    int tile = int(floor(texCoord.y * 0.5));
    vec2 uv = vec2(texCoord.x, clamp(texCoord.y - 2.0 * float(tile), 0.0, 1.0));
    float height = cos(PI * uv.y);
    float equator = max(0.0, sin(PI * uv.y));
    int kind = tile % 4;
    // These bodies write opaque depth. Vertex alpha carries the local response to tidal forces.
    float tide = clamp((1.0 - vertexColor.a) / 0.85, 0.0, 1.0);
    vec4 tint = vertexColor;
    vec4 surface;
    if (tile < 8 && kind == 0) {
        surface = solarPhotosphere(tile, uv, height, equator, tide);
    } else if (tile < 8 && kind >= 2) {
        surface = cloudBelts(tile, kind, uv, height, equator, tide);
    } else if (tile == 9) {
        surface = pulsarSurface(uv, height, equator);
    } else if (tile == 14) {
        float core = tide;
        if (core < 0.004) {
            surface = redSupergiant(uv, height, equator);
        } else if (core > 0.996) {
            surface = surfaceTexel(8, uv);
        } else {
            surface = mix(redSupergiant(uv, height, equator), surfaceTexel(8, uv), core);
        }
    } else {
        surface = surfaceTexel(tile, uv);
    }
    tint.a = 1.0;
    vec4 color = surface * tint;
    if (tile < 8 && kind == 1) {
        color.rgb = tidalFractures(color.rgb, surfaceNormal(uv, height, equator), tide);
    } else if (tile < 8 && kind == 0) {
        vec3 normal = surfaceNormal(uv, height, equator);
        float plasma = 0.5 + 0.5 * sin(dot(normal, vec3(13.0, -7.0, 8.0)) + SurfaceSeconds * 3.1
                                       + 1.5 * sin(normal.y * 9.0 - SurfaceSeconds));
        vec3 glow = tile == 0 ? vec3(1.0, 0.75, 0.34) : vec3(0.66, 0.89, 1.0);
        color.rgb = mix(color.rgb, glow, tide * (0.21 + 0.78 * plasma * plasma));
    } else if (tile == 8 || tile == 10) {
        float flicker = tile == 8 ? 0.76 + 0.24 * cos(SurfaceSeconds * 13.0) : 1.0;
        color.rgb = mix(color.rgb, vec3(0.80, 0.94, 1.0), tide * flicker * 0.73);
    }
    if (color.a < 0.1) discard;
    fragColor = color * ColorModulator;
}
