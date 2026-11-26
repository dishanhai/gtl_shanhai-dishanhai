#version 150
#moj_import <gt_shanhai:guixu_kerr.glsl>

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D RayLight;
uniform sampler2D RayExit;
uniform sampler2D PreviousLight;
uniform sampler2D PreviousExit;
uniform sampler2D PixelTransfer;
uniform sampler2D RayBloom;
uniform sampler2D CriticalLight;
uniform sampler2D CriticalRadii;
uniform sampler2D DiskDetail;
uniform sampler2D DiskDetailExit;
uniform sampler2D DiskDetailBloom;
uniform vec2 uDiskBloomLod;
uniform vec4 uDiskDetailRect; // integer screen origin, width, blend weight

uniform mat4 uProjMat;
uniform mat4 uPreviousProjection;
uniform mat4 uPreviousToView;
uniform vec4 uBgRect;
uniform vec2 uBgTexel;
uniform vec4 uTraceRect;
uniform vec2 uRayJitter;
uniform vec4 uPreviousRect;
uniform vec2 uPreviousJitter;
uniform float uHistoryWeight;
uniform float uStrength;
uniform float uShadowPixels;
uniform float uRefine;
uniform float uCaptureDepthOnly;
uniform float uTransferPass; // 0 native, 1 integrate pixel footprints, 2 present field, 3 seed host bloom
uniform vec2 uResolveSize;
uniform float uSampleBackground;
uniform float uDepthGate;
uniform float uHoleDepth;
uniform float uGateEps;
uniform vec2 uDisplay; // exposure multiplier, bloom strength
uniform vec2 uRayBloomLod; // highest mip (-1 for unfiltered host source), screen-space scale bias
uniform vec4 uCriticalBounds;
uniform vec3 uExterior; // analytic exterior weight, projected centre
uniform vec3 uSkyMix; // live world, procedural stars, dark

out vec4 fragColor;

vec3 linearize(vec3 color) {
    return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), color));
}

vec3 displayEncode(vec3 color) {
    return mix(12.92 * color, 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), color));
}

vec3 filmicCurve(vec3 radiance) {
    return clamp((radiance * (2.51 * radiance + 0.03)) / (radiance * (2.43 * radiance + 0.59) + 0.14),
                 0.0, 1.0);
}

vec3 toneMap(vec3 radiance) {
    float peak = max(radiance.r, max(radiance.g, radiance.b));
    vec3 chromatic = radiance * (filmicCurve(vec3(peak)).r / max(peak, 0.00001));
    // Retain the redshifted midtone colors. The shoulder rolls very hot peaks toward white.
    return mix(chromatic, filmicCurve(radiance), 0.18 + 0.70 * smoothstep(0.65, 3.1, peak));
}

vec3 sky(vec3 direction, float angularFootprint) {
    vec3 z = normalize(uDiskNormal);
    vec3 x = normalize(uEquatorialX - z * dot(z, uEquatorialX));
    vec3 d = transpose(mat3(x, cross(z, x), z)) * direction;
    // Evaluate stars on a 3-D lattice so projection seams cannot pass through the sky.
    vec3 p = d * 77.0;
    vec3 cell = floor(p + 0.5);
    vec3 h = fract(sin(vec3(dot(cell, vec3(37.1, 11.7, 61.3)),
                           dot(cell, vec3(21.7, 39.3, 13.2)),
                           dot(cell, vec3(43.2, 91.1, 17.2)))) * 4375.183);
    float footprint = 77.0 * angularFootprint;
    const float core = .086;
    float variance = core * core + min(.0625, footprint * footprint * .18);
    vec3 delta = p - cell - (h - 0.5) * 0.52;
    float stars = core * core / variance * exp(-dot(delta, delta) / variance) * smoothstep(.89, 1.0, h.z);
    stars = mix(stars, .0002, smoothstep(.35, .95, footprint));
    float latitude = dot(d, normalize(vec3(.19, .42, .89)));
    float wisps = sin(9.0 * d.x - 5.0 * d.y + 2.0 * sin(6.0 * d.z));
    float band = exp(-26.0 * latitude * latitude);
    float dust = exp(-440.0 * pow(abs(latitude - .035 * wisps), 2.0));
    return vec3(.0022, .0032, .0054) + band * (.78 + .22 * wisps)
         * (1.0 - .68 * dust) * vec3(.014, .019, .032)
         + stars * mix(vec3(.35, .56, 1.0), vec3(1.0, .72, .39), h.y);
}

float rectangleCoverage(vec2 px, vec4 rect, float feather) {
    vec2 low = rect.xy, high = low + rect.zw;
    vec4 margin = vec4(low, uScreenSize - high);
    vec4 edge = smoothstep(vec4(0.0), vec4(feather), vec4(px - low, high - px));
    vec4 coverage = mix(vec4(1.0), edge, step(vec4(0.5), margin));
    return min(min(coverage.x, coverage.y), min(coverage.z, coverage.w));
}

// For a perspective camera, window depth is affine in inverse view distance.
// This is D_ls / D_s for a source plane and a single lens plane.
float sourceDistanceFactor(float depth, float infinityDepth) {
    if (depth >= 1.0) return 1.0;
    return clamp((depth - uHoleDepth) / max(infinityDepth - uHoleDepth, 0.000001), 0.0, 1.0);
}

// Color uses bilinear filtering. Every contributing texel must be behind the
// lens, including the foreground side of a one-pixel silhouette.
bool behindLens(vec2 uv) {
    vec2 p = uv / uBgTexel - 0.5;
    vec2 lo = clamp(floor(p) + 0.5, vec2(0.5), uBgRect.zw - 0.5) * uBgTexel;
    vec2 hi = clamp(ceil(p) + 0.5, vec2(0.5), uBgRect.zw - 0.5) * uBgTexel;
    float nearest = min(min(texture(SceneDepth, lo).r, texture(SceneDepth, hi).r),
        min(texture(SceneDepth, vec2(lo.x, hi.y)).r, texture(SceneDepth, vec2(hi.x, lo.y)).r));
    return nearest > uHoleDepth + uGateEps;
}

vec3 background(vec2 px, vec3 viewing, vec4 exitRay, float traceEdge, bool filterWorld, out float coverage) {
    coverage = 0.0;
    if (uSampleBackground < 0.5) return vec3(0.0);
    vec3 direction = exitRay.w > 0.5 && length(exitRay.xyz) > 0.05 ? normalize(exitRay.xyz) : viewing;
    float artificialCoverage = (uSkyMix.y + uSkyMix.z) * traceEdge;
    float angularFootprint = 1.0 / uScreenSize.y;
    if (filterWorld && uSkyMix.y > 0.0) {
        angularFootprint = max(length(dFdx(direction)), length(dFdy(direction)));
    }
    vec3 artificialColor = uSkyMix.y > 0.0 ? sky(direction, angularFootprint) * uSkyMix.y * traceEdge : vec3(0.0);
    coverage = artificialCoverage;
    if (uSkyMix.x <= 0.0) return artificialColor / max(coverage, 0.0001);
    vec2 local = px - uBgRect.xy;
    vec2 border = min(local, uBgRect.zw - local);
    // Preserve the live Minecraft color at the rectangular cache boundary.
    float cacheFeather = max(4.0, min(uBgRect.z, uBgRect.w) * 0.035);
    float worldCoverage = uSkyMix.x * rectangleCoverage(px, uBgRect, cacheFeather);
    coverage += worldCoverage;
    vec2 self = clamp(local, vec2(0.5), uBgRect.zw - 0.5) * uBgTexel;
    vec4 clip = uProjMat * vec4(direction, 0.0);
    vec2 sampled = self;
    if (exitRay.w > 0.5 && clip.w > 0.0001 && uStrength > 0.0) {
        vec2 outgoingPx = (clip.xy / clip.w * 0.5 + 0.5) * uScreenSize;
        vec2 delta = outgoingPx - px;
        // A single view only resolves a local image of the world. Bound the
        // displacement by both its sampling footprint and available cache margin.
        float limit = min(0.55 * uShadowPixels, 0.5 * min(border.x, border.y));
        float confidence = smoothstep(0.86, 0.98, dot(viewing, direction));
        delta *= limit / (max(limit, 0.001) + length(delta)) * confidence * traceEdge * clamp(uStrength, 0.0, 1.0);
        if (uDepthGate > 0.5) {
            float infinityDepth = 0.5 + 0.5 * clip.z / clip.w;
            float factor = sourceDistanceFactor(texture(SceneDepth, self).r, infinityDepth);
            vec2 candidate = self + delta * factor * uBgTexel;
            factor = sourceDistanceFactor(texture(SceneDepth, candidate).r, infinityDepth);
            candidate = self + delta * factor * uBgTexel;
            float donorFactor = sourceDistanceFactor(texture(SceneDepth, candidate).r, infinityDepth);
            // At a depth discontinuity the first estimate can jump to another
            // surface. Accept a donor only if it projects back within one pixel.
            if (length(delta) * abs(donorFactor - factor) <= 1.0 && behindLens(candidate)) sampled = candidate;
        } else {
            sampled += delta * uBgTexel;
        }
    }
    vec3 worldColor = linearize(texture(SceneColor, sampled).rgb);
    // Integrate compressed world detail along the long axis of the pixel
    // footprint. Unit-scale donors retain their exact, single source sample.
    vec2 dx = vec2(0.0), dy = vec2(0.0);
    if (filterWorld) {
        dx = dFdx(sampled) / uBgTexel;
        dy = dFdy(sampled) / uBgTexel;
    }
    vec2 axis = dot(dx,dx) > dot(dy,dy) ? dx : dy;
    float span = length(axis);
    if (span > 1.35 && span < 8.0 && worldCoverage > .0) {
        vec2 stepUv = axis * (min(1.0, 6.0 / span) * .25) * uBgTexel;
        vec2 lo = .5 * uBgTexel, hi = (uBgRect.zw - .5) * uBgTexel;
        vec3 filtered = vec3(0.0);
        for (int j = 0; j < 4; j++) {
            vec2 tap = clamp(sampled + (float(j) - 1.5) * stepUv, lo, hi);
            filtered += uDepthGate < .5 || behindLens(tap)
                ? linearize(texture(SceneColor, tap).rgb) : worldColor;
        }
        worldColor = mix(worldColor, .25 * filtered, smoothstep(1.35, 2.4, span));
    }
    return (artificialColor + worldColor * worldCoverage) / max(coverage, 0.0001);
}

float traceCoverage(vec2 px) {
    float feather = max(1.0, min(uTraceRect.z, uTraceRect.w) * 0.15);
    return rectangleCoverage(px, uTraceRect, feather);
}

vec4 exitAt(sampler2D field, vec2 uv, bool resolveDirection, out float captured) {
    ivec2 size = textureSize(field, 0);
    vec2 pixel = uv * vec2(size) - 0.5;
    ivec2 cell = ivec2(floor(pixel));
    vec2 f = fract(pixel);
    vec4 nearest = resolveDirection
        ? texelFetch(field, clamp(ivec2(pixel + 0.5), ivec2(0), size - 1), 0) : vec4(0.0);
    captured = 0.0;
    vec3 outgoing = vec3(0.0);
    float valid = 0.0;
    for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
        vec4 sampleRay = texelFetch(field, clamp(cell + ivec2(x, y), ivec2(0), size - 1), 0);
        float weight = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
        captured += weight * float(sampleRay.w > 1.5);
        if (resolveDirection) {
            float compatible = float(sampleRay.w == 1.0)
                             * float(nearest.w != 1.0 || dot(sampleRay.xyz, nearest.xyz) > 0.85);
            outgoing += weight * compatible * sampleRay.xyz;
            valid += weight * compatible;
        }
    }
    return vec4(outgoing, float(valid > 0.0));
}

// Inspect neighboring samples in the pilot ray field. Resolve only actual
// capture boundaries and steep thermal gradients at display resolution.
bool needsSubpixels(vec2 uv) {
    ivec2 size = textureSize(RayLight, 0);
    ivec2 cell = ivec2(floor(uv * vec2(size) - .5));
    vec2 lo = vec2(1e5), hi = vec2(0.0);
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec4 v = texelFetch(RayLight, clamp(cell + ivec2(x,y), ivec2(0), size - 1), 0);
        vec2 signal = vec2(sqrt(v.r * uDisplay.x), v.a);
        lo = min(lo, signal);
        hi = max(hi, signal);
    }
    return hi.x - lo.x > .09 + .24 * lo.x || hi.y - lo.y > .18;
}

vec3 bright(vec2 uv, vec2 offset) {
    vec3 emission = (texture(RayLight, uv + offset).rgb) * uDisplay.x;
    float peak = max(emission.r, max(emission.g, emission.b));
    return emission * smoothstep(0.58, 1.65, peak);
}

vec4 cubicWeights(float t) {
    float t2 = t*t, t3 = t2*t;
    return .5 * vec4(-t + 2.0*t2 - t3, 2.0 - 5.0*t2 + 3.0*t3,
                     t + 4.0*t2 - 3.0*t3, -t2 + t3);
}

vec4 reconstructCriticalTransfer(vec2 uv) {
    ivec2 size = textureSize(PixelTransfer, 0);
    vec2 px = uv * vec2(size) - .5;
    ivec2 base = ivec2(floor(px));
    vec4 wx = cubicWeights(fract(px.x)), wy = cubicWeights(fract(px.y));
    vec4 result = vec4(0.0), lo = vec4(1e6), hi = vec4(0.0);
    for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
        vec4 sampleLight = texelFetch(PixelTransfer, clamp(base + ivec2(x-1,y-1), ivec2(0), size-1), 0);
        result += wx[x]*wy[y]*sampleLight;
        if ((x == 1 || x == 2) && (y == 1 || y == 2)) {
            lo = min(lo, sampleLight); hi = max(hi, sampleLight);
        }
    }
    return clamp(result, lo, hi);
}

vec3 filteredBloom(sampler2D highlights, vec2 uv, vec3 scales, vec2 levels) {
    vec3 lod = clamp(scales + levels.y, vec3(0.0), vec3(levels.x));
    return uDisplay.y * (.43 * textureLod(highlights, uv, lod.x).rgb
                       + .36 * textureLod(highlights, uv, lod.y).rgb
                       + .21 * textureLod(highlights, uv, lod.z).rgb);
}

vec3 bloom(vec2 uv) {
    if (uDisplay.y <= 0.0 || uExterior.x > 0.0) return vec3(0.0);
    if (uRayBloomLod.x > 0.0) {
        vec3 scales = vec3(.6, 1.75, 3.1);
        return filteredBloom(RayBloom, uv, scales, uRayBloomLod);
    }
    vec3 sum = vec3(0.0);
    vec2 pixelScale = clamp(uScreenSize.y / 672.0, 0.65, 3.5) / uTraceRect.zw;
    for (int i = 0; i < 10; ++i) {
        float angle = float(i) * 2.39996323;
        float radius = i < 4 ? 3.2 : 4.8 * sqrt(float(i) - 1.5);
        float weight = i < 4 ? 0.16 : 0.06;
        sum += weight * bright(uv, radius * vec2(cos(angle), sin(angle)) * pixelScale);
    }
    return sum * uDisplay.y;
}

bool nearShadow(vec2 uv, float captured) {
    if (captured > .0001 && captured < .9999) return true;
    // Five display pixels cover the cubic reconstruction band. The extra
    // pilot texel keeps the analytic coverage active across coarse samples.
    vec2 reach = 6.0 / uTraceRect.zw + 1.0 / vec2(textureSize(RayExit, 0));
    bool inside = captured > .5;
    return (textureLod(RayExit, uv + reach, 0.0).w > 1.5) != inside
        || (textureLod(RayExit, uv - reach, 0.0).w > 1.5) != inside
        || (textureLod(RayExit, uv + vec2(reach.x,-reach.y), 0.0).w > 1.5) != inside
        || (textureLod(RayExit, uv - vec2(reach.x,-reach.y), 0.0).w > 1.5) != inside;
}

// Balanced 8x8 blue-noise mask with suppressed low spatial frequencies.
// Both diagonals have the same centre so limiting refinement to two rays
// does not bias the image. The tile stays fixed while pilot jitter advances.
int subpixelPair() {
    ivec2 cell = ivec2(gl_FragCoord.xy) & 7;
    const uvec2 mask = uvec2(0x6ea15e91u, 0x6e916e91u);
    return 2 * int((mask[cell.y >> 2] >> ((cell.y & 3) * 8 + cell.x)) & 1u);
}

// Area of a unit pixel on the visible side of the locally linear critical curve.
float shadowVisibility(float distancePx, vec2 normal) {
    vec2 slope = abs(normal);
    float major = max(slope.x, slope.y);
    float minor = min(slope.x, slope.y);
    float d = abs(distancePx);
    if (major < .0001) return step(0.0, distancePx);
    if (d <= .5 * (major - minor)) return .5 + distancePx / major;
    float corner = max(0.0, .5 * (major + minor) - d);
    return .5 + sign(distancePx) * (.5 - corner * corner / max(2.0 * major * minor, 1e-8));
}

vec4 integrateCriticalTransfer(vec2 px, vec2 center, out bool valid) {
    vec2 delta = px - center;
    float radialDistance = length(delta);
    vec2 radial = delta / max(radialDistance, .001);
    vec2 tangent = vec2(-radial.y, radial.x);
    const float tau = 6.2831853;
    float azimuth = atan(delta.y, delta.x) / tau;
    vec3 head = textureLod(CriticalRadii, vec2(azimuth, .5), 0.0).rgb;
    valid = head.z > .999;
    if (!valid) return vec4(0.0);

    // Resolve a display pixel from the cached Kerr rays. The strip holds
    // linear radiance, including emission on paths ending on the horizon.
    vec4 integral = vec4(0.0);
    vec2 escaped = vec2(0.0);
    float samples = 0.0;
    int grid = uRefine >= 4.0 ? 4 : 3;
    int count = grid * grid;
    for (int i = 0; i < 16; ++i) {
        if (i >= count) break;
        vec2 cell = vec2(i % grid, i / grid);
        vec2 offset = (cell + .5) / float(grid) - .5;
        float t = azimuth + dot(tangent, offset) / max(radialDistance * tau, .001);
        vec3 sampleHead = textureLod(CriticalRadii, vec2(t, .5), 0.0).rgb;
        if (sampleHead.z < .999) continue;
        samples += 1.0;
        float d = radialDistance + dot(radial, offset) - sampleHead.x - sampleHead.y;
        float row = .5 + .5 * sign(d) * sqrt(2.0 * abs(d) / uCriticalBounds.z);
        vec4 emitted = textureLod(CriticalLight, vec2(t, row), 0.0);
        integral += emitted;
        if (d > 0.0) escaped += vec2(emitted.a, 1.0);
    }
    valid = samples > 0.0;
    integral /= max(1.0, samples);
    if (escaped.y > .00001) integral.a = escaped.x / escaped.y;
    return integral;
}

#moj_import <gt_shanhai:guixu_kerr_exterior.glsl>

void drawVolume() {
    bool baking = uTransferPass == 1.0;
    bool cached = uTransferPass == 2.0;
    vec2 footprint = baking ? uTraceRect.zw / uResolveSize : vec2(1.0);
    vec2 px = baking ? uTraceRect.xy + gl_FragCoord.xy * footprint : gl_FragCoord.xy;
    vec2 ndc = px / uScreenSize * 2.0 - 1.0;
    vec3 eye = uInvProj[2].xyz / uInvProj[2].w;
    vec4 nearPoint = uInvProj * vec4(ndc, -1.0, 1.0);
    vec3 viewing = normalize(nearPoint.xyz - eye * nearPoint.w);
    vec2 uv = (px - uTraceRect.xy) / uTraceRect.zw - uRayJitter;
    vec4 light = texture(RayLight, uv);
    float captured;
    vec4 exitRay = exitAt(RayExit, uv, !baking && uCaptureDepthOnly <= .5, captured);
    if (uCaptureDepthOnly > .5) {
        // Only the event's deferred bodies need a depth proxy. Resolve the
        // critical curve at the pixel centre using the same geodesic launch.
        if (captured > .0001 && captured < .9999) {
            captured = capturedAt(px);
        }
        if (captured < .5) discard;
        fragColor = vec4(0.0);
        return;
    }
    vec2 detailUv = (px - uDiskDetailRect.xy) / max(1.0, uDiskDetailRect.z);
    float diskWeight = baking ? 0.0 : uDiskDetailRect.w * (1.0 - smoothstep(.73, 1.0, length(2.0 * detailUv - 1.0)));
    float pilotCaptured = captured;
    if (diskWeight > 0.0) {
        float detailCaptured;
        vec4 fine = exitAt(DiskDetailExit, detailUv, true, detailCaptured);
        captured = mix(captured, detailCaptured, diskWeight);
        // Blend directions only within the same image of the sky. Across a
        // capture boundary or a wrapped image, interpolating would invent a star path.
        if (diskWeight >= .999 || exitRay.w < .5) exitRay = fine;
        else if (fine.w > .5 && dot(normalize(exitRay.xyz), normalize(fine.xyz)) > .85)
            exitRay.xyz = mix(normalize(exitRay.xyz), normalize(fine.xyz), diskWeight);
        else if (diskWeight >= .5) exitRay = fine;
    }
    bool mixedShadow = captured > .0001 && captured < .9999;
    bool radiantDisk = uDiskGain > 0.0 && uDiskPhysics.x > 0.0 && uDiskPhysics.w > 0.0;
    bool nearRim = !baking && (mixedShadow || nearShadow(uv, pilotCaptured));
    if (cached && uCriticalBounds.w > .5 && radiantDisk) {
        // Keep the contour active even when all coarse pilot taps miss the rim.
        vec2 fromCenter = px - uCriticalBounds.xy;
        vec3 contour = textureLod(CriticalRadii,
            vec2(atan(fromCenter.y, fromCenter.x) / 6.2831853, .5), 0.0).rgb;
        nearRim = nearRim || (contour.z > .999 && abs(length(fromCenter) - contour.x - contour.y) < uCriticalBounds.z);
    }
    float margin = nearRim ? shadowMargin(px) : (captured > .5 ? -1.0 : 1.0);
    vec2 gradient = vec2(dFdx(margin), dFdy(margin)) / footprint;
    // Do not differentiate across the synthetic +/-1 values outside the work region.
    bool wholeQuad = fwidth(float(nearRim)) == 0.0;
    if (nearRim && !wholeQuad) {
        vec2 dx = vec2(.5, 0.0), dy = vec2(0.0, .5);
        gradient = vec2(shadowMargin(px + dx) - shadowMargin(px - dx),
                        shadowMargin(px + dy) - shadowMargin(px - dy));
    }
    float slope = length(gradient);
    vec2 normal = gradient / max(slope, 1e-8);
    float distancePx = margin / max(1e-8, min(slope, .12));
    float criticalWeight = 0.0;
    vec4 criticalTransfer;
    if (uCriticalBounds.w > .5 && !baking && uRefine > .5 && radiantDisk && nearRim && slope > 1e-8) {
        float halfSpan = .5 * dot(abs(normal), footprint);
        float extension = cached ? .5 * (uCriticalBounds.z - 5.0) : 0.0;
        criticalWeight = 1.0 - smoothstep(halfSpan + .25 + extension, halfSpan + .95 + extension, abs(distancePx));
        if (criticalWeight > 0.0) {
            bool valid;
            criticalTransfer = integrateCriticalTransfer(px, uCriticalBounds.xy, valid);
            if (!valid) criticalWeight = 0.0;
        }
    }
    bool refine = !cached && diskWeight < 1.0 && uRefine > .5
               && (criticalWeight > 0.0 || (!baking && mixedShadow) || needsSubpixels(uv));
    if (!cached && uHistoryWeight > 0.0 && !refine && diskWeight < 1.0) {
        vec4 projected = uPreviousProjection * vec4(viewing, 0.0);
        vec2 previousPx = (projected.xy / max(projected.w, 0.001) * 0.5 + 0.5) * uScreenSize;
        vec2 previousUv = (previousPx - uPreviousRect.xy) / uPreviousRect.zw - uPreviousJitter;
        if (projected.w > 0.0 && all(greaterThan(previousUv, vec2(0.01))) &&
            all(lessThan(previousUv, vec2(0.99)))) {
            float previousCaptured;
            vec4 previousRay = exitAt(PreviousExit, previousUv, !baking, previousCaptured);
            vec4 previous = texture(PreviousLight, previousUv);
            float alphaConsistency = 1.0 - smoothstep(0.06, 0.34, abs(light.a - previous.a));
            float captureConsistency = 1.0 - smoothstep(0.15, 0.60, abs(captured - previousCaptured));
            vec3 delta = abs(light.rgb - previous.rgb) / (.10 + max(light.rgb, previous.rgb));
            float radianceConsistency = 1.0 - smoothstep(.18, .60, max(delta.r, max(delta.g, delta.b)));
            float weight = uHistoryWeight * (1.0 - diskWeight) * alphaConsistency * captureConsistency * radianceConsistency;
            light = mix(light, previous, weight);
            captured = mix(captured, previousCaptured, weight);
            if (exitRay.w > 0.5 && previousRay.w > 0.5) {
                vec3 priorDirection = (uPreviousToView * vec4(previousRay.xyz, 0.0)).xyz;
                float directionAgreement = dot(normalize(exitRay.xyz), normalize(priorDirection));
                exitRay.xyz = mix(exitRay.xyz, priorDirection, weight * smoothstep(0.980, 0.999, directionAgreement));
            }
        }
    }

    float skyCoverage;
    float edge = traceCoverage(px);
    vec3 incoming = vec3(0.0);
    skyCoverage = 0.0;
    if (cached && diskWeight == 0.0 && captured >= .99999 && !nearRim && edge == 1.0) {
        vec2 transferUv = (px - uTraceRect.xy) / uTraceRect.zw;
        fragColor = vec4(displayEncode(toneMap(texture(PixelTransfer, transferUv).rgb)), 1.0);
        return;
    }
    if (!baking && (captured < .9999 || nearRim || edge < 1.0 || refine))
        incoming = background(px, viewing, exitRay, edge, true, skyCoverage);
    float transmitted = light.a * (1.0 - captured);
    float escapeFraction = 1.0 - captured;
    const vec2 offsets[4] = vec2[4](vec2(-.375,-.125), vec2(.375,.125),
                                    vec2(.125,-.375), vec2(-.125,.375));
    if (!cached && criticalWeight > 0.0) {
        float covered = shadowVisibility(distancePx, normal);
        light = mix(light, criticalTransfer, criticalWeight);
        escapeFraction = mix(escapeFraction, covered, criticalWeight);
        transmitted = light.a * escapeFraction;
    } else if (refine) {
        vec3 radiance = vec3(0.0), skyLight = vec3(0.0);
        float transmission = 0.0, covered = 0.0, escaped = 0.0;
        int count = int(uRefine);
        // A large footprint means the GPU budget has already reduced the
        // transfer grid. Reserve the extra two subring rays for finer grids.
        if (baking) {
            bool coarse = max(footprint.x, footprint.y) > 2.5;
            if (coarse) count = mixedShadow ? 2 : 1;
            else if (count == 2) count = mixedShadow ? 4 : 1;
        }
        int pair = count == 2 ? subpixelPair() : 0;
        for (int i = 0; i < 4; i++) {
            if (i >= count) break;
            vec2 sub = count == 1 ? px : px + offsets[i + pair] * footprint;
            vec4 emitted;
            vec4 ray = tracePixel(sub, baking, emitted);
            float through = emitted.a * float(ray.w < 1.5);
            escaped += float(ray.w < 1.5);
            if (!baking) {
                float layer;
                vec3 source = background(sub, viewing, ray, edge, false, layer);
                float backgroundThrough = through;
                skyLight += backgroundThrough * layer * source;
                covered += backgroundThrough * layer;
            }
            transmission += through;
            radiance += emitted.rgb;
        }
        light.rgb = radiance / float(count);
        if (covered > .000001) incoming = skyLight / covered;
        float backgroundThrough = transmission;
        skyCoverage = backgroundThrough > .000001 ? covered / backgroundThrough : skyCoverage;
        transmitted = transmission / float(count);
        escapeFraction = escaped / float(count);
    }
    float escapedThrough = escapeFraction > .00001 ? transmitted / escapeFraction : light.a;
    if (baking) {
        fragColor = vec4(light.rgb * uDisplay.x + bloom(uv),
                         escapedThrough);
        return;
    }
    float visible = nearRim ? shadowVisibility(distancePx, normal) : escapeFraction;
    vec3 radiance;
    if (cached) {
        vec2 transferUv = (px - uTraceRect.xy) / uTraceRect.zw;
        vec4 transfer = texture(PixelTransfer, transferUv);
        float detail = 0.0;
        if (nearRim && abs(distancePx) < 5.0) {
            // Keep the cached radiance's local contrast across the rim.
            // The clamped cubic avoids overshoot, and the blend avoids a seam.
            detail = 1.0 - smoothstep(2.0, 5.0, abs(distancePx));
        }
        if (detail > 0.0 && criticalWeight < 1.0) {
            transfer = mix(transfer, reconstructCriticalTransfer(transferUv), detail);
        }
        if (criticalWeight > 0.0) {
            vec4 fromStrip = vec4(criticalTransfer.rgb * uDisplay.x + bloom(uv), criticalTransfer.a);
            transfer = mix(transfer, fromStrip, criticalWeight);
        }
        radiance = transfer.rgb;
        transmitted = transfer.a * visible;
    } else {
        radiance = light.rgb * uDisplay.x + bloom(uv);
        transmitted = escapedThrough * visible;
    }
    if (diskWeight > 0.0) {
        vec4 disk = texture(DiskDetail, detailUv);
        // The polar strip integrates subpixel images at the actual critical curve.
        // The Cartesian samples resolve the independent disk edge crossing its interior.
        float strip = criticalWeight * (1.0 - smoothstep(.25, 1.0, abs(distancePx)));
        if (strip > 0.0) disk = mix(disk, criticalTransfer, strip);
        vec3 halo = uDiskBloomLod.x > 0.0
            ? filteredBloom(DiskDetailBloom, detailUv, vec3(.6, 1.75, 3.1), uDiskBloomLod) : bloom(uv);
        radiance = mix(radiance, disk.rgb * uDisplay.x + halo, diskWeight);
        transmitted = mix(transmitted, disk.a * visible, diskWeight);
    }
    transmitted = mix(1.0, transmitted, edge);
    vec3 emission = toneMap(radiance);
    // Apply scene-layer opacity once. The host framebuffer is the unwarped
    // transmitted source where a scene copy is unavailable or disabled.
    float coverage = 1.0 - transmitted * (1.0 - skyCoverage);
    vec3 base = incoming * transmitted;
    vec3 source = emission * edge;
    vec3 scene = displayEncode(clamp(base + source, 0.0, 1.0));
    fragColor = vec4(mix(displayEncode(emission) * edge, scene, skyCoverage), coverage);
}

void main() {
    if (uTransferPass == 1.0 || uExterior.x <= 0.0) {
        drawVolume();
        return;
    }
    if (uCaptureDepthOnly > .5) {
        if (capturedAt(gl_FragCoord.xy) < .5) discard;
        fragColor = vec4(0.0);
        return;
    }
    vec4 exterior = drawKerrExterior(gl_FragCoord.xy);
    if (uExterior.x >= 1.0) {
        fragColor = exterior;
        return;
    }
    drawVolume();
    fragColor = mix(fragColor, exterior, uExterior.x);
}
