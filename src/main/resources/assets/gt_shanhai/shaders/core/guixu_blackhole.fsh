#version 150
#moj_import <gt_shanhai:guixu_kerr.glsl>

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D RayLight;
uniform sampler2D RayExit;
uniform sampler2D PreviousLight;
uniform sampler2D PreviousExit;
uniform sampler2D PixelTransfer;

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
uniform float uTransferPass; // 0 native, 1 integrate pixel footprints, 2 present field
uniform vec2 uResolveSize;
uniform float uSampleBackground;
uniform float uDepthGate;
uniform float uHoleDepth;
uniform float uGateEps;
uniform vec2 uDisplay; // exposure multiplier, bloom strength
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

vec4 exitAt(sampler2D field, vec2 uv, out float captured) {
    ivec2 size = textureSize(field, 0);
    vec2 pixel = uv * vec2(size) - 0.5;
    ivec2 cell = ivec2(floor(pixel));
    vec2 f = fract(pixel);
    vec4 nearest = texelFetch(field, clamp(ivec2(pixel + 0.5), ivec2(0), size - 1), 0);
    captured = 0.0;
    vec3 outgoing = vec3(0.0);
    float valid = 0.0;
    for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
        vec4 sampleRay = texelFetch(field, clamp(cell + ivec2(x, y), ivec2(0), size - 1), 0);
        float weight = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
        captured += weight * float(sampleRay.w > 1.5);
        float compatible = float(sampleRay.w == 1.0)
                         * float(nearest.w != 1.0 || dot(sampleRay.xyz, nearest.xyz) > 0.85);
        outgoing += weight * compatible * sampleRay.xyz;
        valid += weight * compatible;
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
    vec3 emission = texture(RayLight, uv + offset).rgb * uDisplay.x;
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

vec3 bloom(vec2 uv) {
    if (uDisplay.y <= 0.0) return vec3(0.0);
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

// A hot arc advances around the physical shadow, leaving its interior dark.
// Width is in display pixels, independent of the adaptive radiance texture.
vec3 horizonArc(float distancePx, float width, vec3 viewing, vec3 eye) {
    float d = distancePx / width;
    if (abs(d) > 9.0 || uDiskGain <= 0.0) return vec3(0.0);
    vec3 tangent = cross(uBhView - eye, viewing);
    tangent /= max(length(tangent), .0001);
    float phase = ROTATION * uTime.x * (6.2831853 / 64.0);
    vec3 orbit = cos(phase) * uEquatorialX + sin(phase) * cross(uDiskNormal, uEquatorialX);
    float arc = smoothstep(-.65, .95, dot(tangent, orbit));
    float core = exp2(-1.6 * d*d);
    float veil = exp2(-.65 * abs(d)) * (1.0 - smoothstep(-1.0, 2.0, d))
               * (1.0 - smoothstep(7.0, 9.0, abs(d)));
    float strength = uDiskGain * clamp(uDiskPhysics.w * uDiskPhysics.x * 3.0, 0.0, 1.0);
    return strength * (.65 + .55 * uDiskHeat) * (.32 + .68 * arc)
        * (core * vec3(.48, .30, .17) + veil * vec3(.072, .035, .11));
}

bool nearShadow(vec2 uv, float captured, float width) {
    if (captured > .0001 && captured < .9999) return true;
    vec2 reach = (9.0 * width + 2.0) / uTraceRect.zw + 1.0 / vec2(textureSize(RayExit, 0));
    bool inside = captured > .5;
    return (textureLod(RayExit, uv + reach, 0.0).w > 1.5) != inside
        || (textureLod(RayExit, uv - reach, 0.0).w > 1.5) != inside
        || (textureLod(RayExit, uv + vec2(reach.x,-reach.y), 0.0).w > 1.5) != inside
        || (textureLod(RayExit, uv - vec2(reach.x,-reach.y), 0.0).w > 1.5) != inside;
}

void main() {
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
    vec4 exitRay = exitAt(RayExit, uv, captured);
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

    bool mixedShadow = captured > .0001 && captured < .9999;
    bool refine = !cached && uRefine > .5 && ((!baking && mixedShadow) || needsSubpixels(uv));
    if (!cached && uHistoryWeight > 0.0 && !refine) {
        vec4 projected = uPreviousProjection * vec4(viewing, 0.0);
        vec2 previousPx = (projected.xy / max(projected.w, 0.001) * 0.5 + 0.5) * uScreenSize;
        vec2 previousUv = (previousPx - uPreviousRect.xy) / uPreviousRect.zw - uPreviousJitter;
        if (projected.w > 0.0 && all(greaterThan(previousUv, vec2(0.01))) &&
            all(lessThan(previousUv, vec2(0.99)))) {
            float previousCaptured;
            vec4 previousRay = exitAt(PreviousExit, previousUv, previousCaptured);
            vec4 previous = texture(PreviousLight, previousUv);
            float alphaConsistency = 1.0 - smoothstep(0.06, 0.34, abs(light.a - previous.a));
            float captureConsistency = 1.0 - smoothstep(0.15, 0.60, abs(captured - previousCaptured));
            float weight = uHistoryWeight * alphaConsistency * captureConsistency;
            light = mix(light, previous, weight);
            captured = mix(captured, previousCaptured, uHistoryWeight);
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
    float width = clamp(.008 * uShadowPixels, .85, 3.5);
    bool nearRim = !baking && nearShadow(uv, captured, width);
    if (cached && captured >= .99999 && !nearRim && edge == 1.0) {
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
    if (refine) {
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
        for (int i = 0; i < 4; i++) {
            if (i >= count) break;
            vec2 sub = count == 1 ? px : px + offsets[i] * footprint;
            vec4 emitted;
            vec4 ray = tracePixel(sub, baking, emitted);
            float through = emitted.a * float(ray.w < 1.5);
            escaped += float(ray.w < 1.5);
            if (!baking) {
                float layer;
                vec3 source = background(sub, viewing, ray, edge, false, layer);
                skyLight += through * layer * source;
                covered += through * layer;
            }
            transmission += through;
            radiance += emitted.rgb;
        }
        light.rgb = radiance / float(count);
        if (covered > .000001) incoming = skyLight / covered;
        skyCoverage = transmission > .000001 ? covered / transmission : skyCoverage;
        transmitted = transmission / float(count);
        escapeFraction = escaped / float(count);
    }
    if (baking) {
        fragColor = vec4(light.rgb * uDisplay.x + bloom(uv),
                         escapeFraction > .00001 ? transmitted / escapeFraction : light.a);
        return;
    }
    float margin = nearRim ? shadowMargin(px) : (captured > .5 ? -1.0 : 1.0);
    vec2 gradient = vec2(dFdx(margin), dFdy(margin));
    // A quad straddling the boundary of our narrow work region has no
    // derivative on its missing side. It is already outside the visible rim.
    bool wholeQuad = fwidth(float(nearRim)) == 0.0;
    float distancePx = margin / max(1e-8, min(length(gradient), wholeQuad ? .12 : .001));
    float visible = nearRim ? smoothstep(-.65, .65, distancePx) : escapeFraction;
    vec3 radiance;
    if (cached) {
        vec2 transferUv = (px - uTraceRect.xy) / uTraceRect.zw;
        vec4 transfer = texture(PixelTransfer, transferUv);
        if (nearRim && abs(distancePx) < 4.0) {
            // Smooth unresolved subrings along the silhouette. Sampling across
            // its normal instead would spread bright disk light into the core.
            vec2 tangent = vec2(-gradient.y, gradient.x) / max(length(gradient), 1e-8);
            vec2 texels = vec2(textureSize(PixelTransfer, 0)) / uTraceRect.zw;
            vec2 along = tangent / max(length(tangent * texels), .001) / uTraceRect.zw;
            transfer = .5 * reconstructCriticalTransfer(transferUv)
                     + .25 * (texture(PixelTransfer, transferUv + along)
                            + texture(PixelTransfer, transferUv - along));
        }
        radiance = transfer.rgb;
        transmitted = transfer.a * visible;
    } else {
        radiance = light.rgb * uDisplay.x + bloom(uv);
        transmitted = (escapeFraction > .00001 ? transmitted / escapeFraction : light.a) * visible;
    }
    transmitted = mix(1.0, transmitted, edge);
    vec3 emission = toneMap(radiance + horizonArc(distancePx, width, viewing, eye) * uDisplay.x);
    // Apply scene-layer opacity once. The host framebuffer is the unwarped
    // transmitted source where a scene copy is unavailable or disabled.
    float coverage = 1.0 - transmitted * (1.0 - skyCoverage);
    vec3 scene = displayEncode(clamp(incoming * transmitted + emission * edge, 0.0, 1.0));
    fragColor = vec4(mix(displayEncode(emission) * edge, scene, skyCoverage), coverage);
}
