bool whiteCacheCoversDepth(vec2 px, vec2 uv, vec3 origin, vec3 direction, float cutoff, float depth) {
    ivec2 size = textureSize(WhiteDirectDepth, 0);
    ivec2 cell = ivec2(floor(uv * vec2(size) - .5));
    ivec2 lo = clamp(cell, ivec2(0), size - 1);
    ivec2 hi = clamp(cell + 1, ivec2(0), size - 1);
    vec4 depths = vec4(texelFetch(WhiteDirectDepth, lo, 0).r,
                       texelFetch(WhiteDirectDepth, ivec2(lo.x, hi.y), 0).r,
                       texelFetch(WhiteDirectDepth, ivec2(hi.x, lo.y), 0).r,
                       texelFetch(WhiteDirectDepth, hi, 0).r);
    if (all(lessThan(depths, vec4(0.0))))
        return depth >= 1.0 || cutoff >= whiteSphereInterval(origin, direction, WHITE_SHELL_MAX).y;
    if (depth >= 1.0 || any(lessThan(depths, vec4(0.0)))) return false;
    // Planar surfaces are affine in window depth. Validate the interpolated
    // plane against the live pixel, with a bounded slope at depth edges.
    // The depth fetch in the bake is snapped to a native scene texel.
    vec2 stride = uWhiteCacheRect.zw / vec2(size);
    vec2 a = floor((vec2(lo) + .5) * stride);
    vec2 b = floor((vec2(hi) + .5) * stride);
    vec2 f = clamp((floor(px - uWhiteCacheRect.xy) - a) / max(vec2(1.0), b - a), 0.0, 1.0);
    float predicted = mix(mix(depths.x, depths.z, f.x), mix(depths.y, depths.w, f.x), f.y);
    float w = abs(uInvProj[2].w * (2.0 * depth - 1.0) + uInvProj[3].w);
    float rgPerDepth = 2.0 * abs(uInvProj[2].w) * cutoff / max(w, 1e-7);
    vec2 span = vec2(max(max(depths.x, depths.y), max(depths.z, depths.w)),
                     min(min(depths.x, depths.y), min(depths.z, depths.w)));
    return abs(predicted - depth) * rgPerDepth < .12
        && abs(depths.x - depths.y - depths.z + depths.w) * rgPerDepth < .12
        && (span.x - span.y) * rgPerDepth < .65;
}

vec4 whiteWorldLight(vec2 px, vec3 origin, vec3 direction, float cutoff, float depth, bool highlightsOnly) {
    ivec2 local = ivec2(floor(px) - uWhiteCacheRect.xy);
    if (all(greaterThanEqual(local, ivec2(0))) && all(lessThan(local, ivec2(uWhiteCacheRect.zw)))) {
        if (uWhiteCacheReduced > .5) {
            vec2 uv = (px - uWhiteCacheRect.xy) / uWhiteCacheRect.zw;
            if (whiteCacheCoversDepth(px, uv, origin, direction, cutoff, depth))
                return texture(WhiteDirectLight, uv);
        } else if (texelFetch(WhiteDirectDepth, local, 0).r == depth) {
            return texelFetch(WhiteDirectLight, local, 0);
        }
    }
    return whitePrimaryLight(origin, direction, cutoff, highlightsOnly);
}

vec3 whiteWorldToneMap(vec3 radiance) {
    float peak = max(radiance.r, max(radiance.g, radiance.b));
    const float knee = .55;
    float base = filmicCurve(vec3(min(peak, knee))).r;
    float shoulder = max(0.0, peak - knee);
    // Match the existing midtones, then expend the remaining display range
    // on highlights with a gradual, hue-preserving rolloff.
    float mapped = base + .335 * shoulder / (.95 + shoulder);
    return radiance * (mapped / max(peak, .00001));
}

vec3 whiteCriticalPosition(vec2 px) {
    if (uCriticalBounds.w <= .5) return vec3(0.0);
    vec2 delta = px - uCriticalBounds.xy;
    float azimuth = atan(delta.y, delta.x) / 6.2831853;
    vec3 centre = textureLod(CriticalRadii, vec2(azimuth, .5), 0.0).rgb;
    float offset = length(delta) - centre.x - centre.y;
    float weight = (1.0 - smoothstep(.24, .46, abs(offset) / uCriticalBounds.z)) * step(.999, centre.z);
    return vec3(azimuth, offset, weight);
}

float whiteCriticalRow(float offset) {
    return .5 + .5 * sign(offset) * sqrt(2.0 * abs(offset) / uCriticalBounds.z);
}

vec3 whitePilotImage(vec2 uv) {
    // The pilot remains useful for broad, weak images. Reject single-cell
    // peaks before interpolation so they cannot turn into dotted arcs.
    vec2 texel = 1.0 / vec2(textureSize(RayLight, 0));
    vec3 adjacent = .25 * (texture(RayLight, uv + vec2(texel.x, 0)).rgb
                         + texture(RayLight, uv - vec2(texel.x, 0)).rgb
                         + texture(RayLight, uv + vec2(0, texel.y)).rgb
                         + texture(RayLight, uv - vec2(0, texel.y)).rgb);
    return mix(adjacent, min(texture(RayLight, uv).rgb, 1.6 * adjacent + .004), .33);
}

vec3 whiteOpticalImage(vec2 uv, vec3 critical) {
    vec3 pilot = uWhiteOpticalLayer > .5 ? texture(DiskDetail, uv).rgb : whitePilotImage(uv);
    if (critical.z <= 0.0) return pilot;
    // Neighboring columns follow the same signed distance from the actual
    // critical curve. Average within that footprint before tone mapping.
    float angularTexel = 1.0 / float(textureSize(CriticalLight, 0).x);
    vec3 strip = vec3(0.0);
    for (int x = -1; x <= 1; ++x) {
        float a = critical.x + float(x) * angularTexel;
        for (int y = -1; y <= 1; ++y) {
            float row = whiteCriticalRow(critical.y + .48 * float(y));
            strip += textureLod(CriticalLight, vec2(a, row), 0.0).rgb;
        }
    }
    return mix(pilot, strip / 9.0, critical.z);
}

void refineWhiteSky(vec3 critical, inout vec4 exitRay, inout float captured) {
    if (uCriticalBounds.w < 1.5 || critical.z <= 0.0) return;
    float ignored;
    vec2 uv = vec2(fract(critical.x), whiteCriticalRow(critical.y));
    vec4 fine = exitAt(LocalRayExit, uv, true, true, ignored);
    if (fine.w > .5) {
        if (exitRay.w < .5 || critical.z > .95) exitRay = fine;
        else if (dot(normalize(fine.xyz), normalize(exitRay.xyz)) > .85)
            exitRay.xyz = mix(exitRay.xyz, fine.xyz, critical.z);
    }
    captured = mix(captured, 1.0 - smoothstep(-.65, .65, critical.y), critical.z);
}

void drawWhiteWorldBloomSource() {
    vec2 uv = gl_FragCoord.xy / uResolveSize;
    vec2 px = uTraceRect.xy + uv * uTraceRect.zw;
    mat3 basis;
    vec3 origin, direction;
    cameraRay(px, basis, origin, direction);
    float depth = whiteSceneDepth(px);
    float cutoff = whiteWorldDistance(px, basis * direction, depth);
    vec3 direct = whiteWorldLight(px, origin, direction, cutoff, depth, uWhiteCacheReduced > .5).rgb;
    vec3 indirect = cutoff <= -dot(origin, direction) ? vec3(0.0) : texture(RayLight, uv).rgb;
    // The host's narrow direct emitters have lower linear peaks than the lens field.
    // Give them their own white point before the shared soft-knee extraction.
    float directBloomWhite = .25 + .12 * max(direct.r, max(direct.g, direct.b));
    fragColor = vec4(indirect + direct / directBloomWhite, 1.0);
}

float whiteHostSkyCoverage(vec3 toCenter, vec3 viewing) {
    // The whole sky becomes visible from within the host's star field. Fade in
    // the surrounding sky by observer distance so this transition survives a turn.
    float surrounding = 1.0 - smoothstep(12.0, 28.0, length(toCenter));
    float depth = -toCenter.z;
    if (depth <= 0.0) return surrounding;
    // Outside the field, feather the radial backdrop inside the trace tile.
    vec2 offset = viewing.xy / -viewing.z - toCenter.xy / depth;
    float radius = length(offset) * max(depth, 56.0);
    float outer = mix(24.0, 34.0, uWhiteHole.x);
    return mix(1.0 - smoothstep(.55 * outer, outer, radius), 1.0, surrounding);
}

void drawWhiteWorld() {
    vec2 px = gl_FragCoord.xy;
    vec2 uv = (px - uTraceRect.xy) / uTraceRect.zw - uRayJitter;
    float edge = traceCoverage(px);
    mat3 basis;
    vec3 origin, direction;
    cameraRay(px, basis, origin, direction);
    vec3 viewing = basis * direction;
    float depth = whiteSceneDepth(px);
    float cutoff = whiteWorldDistance(px, viewing, depth);
    // Local scene geometry stays at its original texel. Even in front of the
    // centre plane, primary emission in front of that geometry remains visible.
    float centre = -dot(origin, direction);
    bool foreground = cutoff <= centre;
    float capture = 0.0;
    float skyCoverage;
    // Only the host has world geometry to preserve. Without a depth copy,
    // the scene colour remains visible until open sky can be identified.
    bool keepWorld = uWhiteHostSky > .5 && (uWhiteSceneDepth <= .5 || depth < 1.0);
    float skyEdge = uWhiteHostSky > .5 ? whiteHostSkyCoverage(basis * -origin, viewing) : edge;
    bool sampleSkyRay = !foreground && uSampleBackground > .5
        && (uSkyMix.x > 0.0 || (!keepWorld && uSkyMix.y * skyEdge > 0.0));
    vec4 exitRay = sampleSkyRay ? exitAt(RayExit, uv, true, capture) : vec4(0.0);
    vec3 critical = foreground ? vec3(0.0) : whiteCriticalPosition(px);
    if (!keepWorld && uSkyMix.y * skyEdge > 0.0) refineWhiteSky(critical, exitRay, capture);
    vec3 incoming = background(px, viewing, exitRay, edge, skyEdge, true, keepWorld, 1.0 - capture, skyCoverage);
    vec4 light = whiteWorldLight(px, origin, direction, cutoff, depth, false);
    vec3 indirect = foreground || uWhiteHole.z <= 0.0 || uWhiteOpticalLayer < -.5
                  ? vec3(0.0) : .80 * whiteOpticalImage(uv, critical) * light.a;
    // The bloom source is clipped against terrain before it spreads across the display.
    vec3 halo = foreground && uRayBloomLod.x == 0.0 ? vec3(0.0) : bloom(uv);
    vec3 emission = whiteWorldToneMap(uDisplay.x * (light.rgb + indirect));
    float available = 1.0 - max(emission.r, max(emission.g, emission.b));
    emission += .23 * available * halo / (vec3(1.0) + halo);
    float through = mix(1.0, light.a, .20 * edge);
    vec3 color = displayEncode(emission) * edge;
    if (skyCoverage > 0.0) {
        vec3 base = incoming * through;
        vec3 scene = displayEncode(clamp(base + emission * edge * (1.0 - base), 0.0, 1.0));
        color = mix(color, scene, skyCoverage);
    }
    fragColor = vec4(color, 1.0 - through * (1.0 - skyCoverage));
}
