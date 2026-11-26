vec4 whiteWorldLight(vec2 px, vec3 origin, vec3 direction, float cutoff, float depth) {
    ivec2 local = ivec2(floor(px) - uWhiteCacheRect.xy);
    if (all(greaterThanEqual(local, ivec2(0))) && all(lessThan(local, ivec2(uWhiteCacheRect.zw)))) {
        if (texelFetch(WhiteDirectDepth, local, 0).r == depth)
            return texelFetch(WhiteDirectLight, local, 0);
    }
    return whitePrimaryLight(origin, direction, cutoff);
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

vec3 whiteOpticalImage(vec2 px, vec2 uv) {
    // The pilot remains useful for broad, weak images. Reject single-cell
    // peaks before interpolation so they cannot turn into dotted arcs.
    vec2 texel = 1.0 / vec2(textureSize(RayLight, 0));
    vec3 adjacent = .25 * (texture(RayLight, uv + vec2(texel.x, 0)).rgb
                         + texture(RayLight, uv - vec2(texel.x, 0)).rgb
                         + texture(RayLight, uv + vec2(0, texel.y)).rgb
                         + texture(RayLight, uv - vec2(0, texel.y)).rgb);
    vec3 pilot = mix(adjacent, min(texture(RayLight, uv).rgb, 1.6 * adjacent + .004), .33);
    if (uCriticalBounds.w <= .5) return pilot;
    vec2 delta = px - uCriticalBounds.xy;
    float distancePx = length(delta);
    float azimuth = atan(delta.y, delta.x) / 6.2831853;
    vec3 centre = textureLod(CriticalRadii, vec2(azimuth, .5), 0.0).rgb;
    float offset = distancePx - centre.x - centre.y;
    float weight = (1.0 - smoothstep(.24, .46, abs(offset) / uCriticalBounds.z)) * step(.999, centre.z);
    if (weight <= 0.0) return pilot;
    // Neighboring columns follow the same signed distance from the actual
    // critical curve. Average within that footprint before tone mapping.
    float angularTexel = 1.0 / float(textureSize(CriticalLight, 0).x);
    vec3 strip = vec3(0.0);
    for (int x = -1; x <= 1; ++x) {
        float a = azimuth + float(x) * angularTexel;
        for (int y = -1; y <= 1; ++y) {
            float d = offset + .48 * float(y);
            float row = .5 + .5 * sign(d) * sqrt(2.0 * abs(d) / uCriticalBounds.z);
            strip += textureLod(CriticalLight, vec2(a, row), 0.0).rgb;
        }
    }
    return mix(pilot, strip / 9.0, weight);
}

void drawWhiteWorldBloomSource() {
    vec2 uv = gl_FragCoord.xy / uResolveSize;
    vec2 px = uTraceRect.xy + uv * uTraceRect.zw;
    mat3 basis;
    vec3 origin, direction;
    cameraRay(px, basis, origin, direction);
    float depth = whiteSceneDepth(px);
    float cutoff = whiteWorldDistance(px, basis * direction, depth);
    vec3 direct = whiteWorldLight(px, origin, direction, cutoff, depth).rgb;
    vec3 indirect = cutoff <= -dot(origin, direction) ? vec3(0.0) : texture(RayLight, uv).rgb;
    // The host's narrow direct emitters have lower linear peaks than the lens field.
    // Give them their own white point before the shared soft-knee extraction.
    const float directBloomWhite = .25;
    fragColor = vec4(indirect + direct / directBloomWhite, 1.0);
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
    float capture;
    vec4 exitRay = exitAt(RayExit, uv, true, capture);
    // Local scene geometry stays at its original texel. Even in front of the
    // centre plane, primary emission in front of that geometry remains visible.
    float centre = -dot(origin, direction);
    bool foreground = cutoff <= centre;
    if (foreground) exitRay = vec4(0.0);
    float skyCoverage;
    vec3 incoming = background(px, viewing, exitRay, edge, true, skyCoverage);
    vec4 light = whiteWorldLight(px, origin, direction, cutoff, depth);
    vec3 indirect = foreground ? vec3(0.0) : whiteOpticalImage(px, uv) * light.a;
    // The bloom source is clipped against terrain before it spreads across the display.
    vec3 halo = foreground && uRayBloomLod.x == 0.0 ? vec3(0.0) : bloom(uv);
    vec3 emission = whiteWorldToneMap(uDisplay.x * (light.rgb + indirect));
    float available = 1.0 - max(emission.r, max(emission.g, emission.b));
    emission += .4 * available * halo / (vec3(1.0) + halo);
    float through = mix(1.0, light.a, .20 * edge);
    vec3 base = incoming * through;
    vec3 scene = displayEncode(clamp(base + emission * edge * (1.0 - base), 0.0, 1.0));
    fragColor = vec4(mix(displayEncode(emission) * edge, scene, skyCoverage),
                     1.0 - through * (1.0 - skyCoverage));
}
