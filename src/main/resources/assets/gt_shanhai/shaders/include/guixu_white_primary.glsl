// Host radiation is sampled along the same projective rays as the blocks.
// Positions and distances are in gravitational radii, in the host's spin basis.
vec2 whiteSphereInterval(vec3 origin, vec3 direction, float radius) {
    float middle = -dot(origin, direction);
    vec3 nearest = origin + middle * direction;
    float chord = radius * radius - dot(nearest, nearest);
    if (chord <= 0.0) return vec2(1.0, 0.0);
    float extent = sqrt(chord);
    return vec2(middle - extent, middle + extent);
}

vec2 whiteHeightInterval(float origin, float direction, float lower, float upper) {
    if (abs(direction) <= 1e-7) return origin >= lower && origin <= upper
                                        ? vec2(-1e20, 1e20) : vec2(1.0, 0.0);
    vec2 ends = (vec2(lower, upper) - origin) / direction;
    return vec2(min(ends.x, ends.y), max(ends.x, ends.y));
}

vec2 whiteIntersect(vec2 a, vec2 b) {
    return vec2(max(a.x, b.x), min(a.y, b.y));
}

vec2 whiteJetInterval(vec3 origin, vec3 direction, float signZ, vec2 limit) {
    if (uWhiteTime.z <= 0.0) return vec2(1.0, 0.0);
    vec2 height = whiteHeightInterval(signZ * origin.z, signZ * direction.z, HORIZON + .20, WHITE_JET_MAX);
    vec2 span = whiteIntersect(height, limit);
    if (span.x >= span.y) return span;
    // Bound the faint halo as well as the thin spine. The radius increases
    // along the jet, so the furthest axial position bounds the whole chord.
    float lengthXY = length(direction.xy);
    if (lengthXY <= 1e-7) return span;
    vec3 transverse = vec3(direction.xy / lengthXY, 0.0);
    for (int i = 0; i < 3 && span.x < span.y; ++i) {
        float z = max(abs(origin.z + direction.z * span.x), abs(origin.z + direction.z * span.y));
        vec2 chord = whiteSphereInterval(vec3(origin.xy, 0.0), transverse, 3.0 * whiteJetWidth(z));
        span = whiteIntersect(span, chord / lengthXY);
    }
    return span;
}

vec2 whiteDiskInterval(vec3 origin, vec3 direction, vec2 limit, float thickness) {
    vec2 support = whiteIntersect(limit, whiteSphereInterval(origin, direction, WHITE_DISK_MAX + .03));
    vec2 span = support;
    for (int i = 0; i < 4 && span.x < span.y; ++i) {
        // Distance from the axis along a segment is bounded by its endpoints.
        // Re-evaluate the vertical envelope after narrowing the segment.
        float radius = max(length(origin + span.x * direction), length(origin + span.y * direction));
        float height = 3.0 * whiteDiskThickness(radius, thickness);
        span = whiteIntersect(span, whiteHeightInterval(origin.z, direction.z, -height, height));
    }
    if (span.x >= span.y) return span;
    // Use the radial volume as the sampling frame when the camera is already
    // immersed in the flow. Grazing rays would otherwise move every sample
    // sharply as the clipped vertical interval changes.
    float radius = length(origin);
    float inside = (1.0 - smoothstep(20.0, WHITE_DISK_MAX, radius))
                 * (1.0 - smoothstep(1.0, 2.75, abs(origin.z) / whiteDiskThickness(radius, thickness)));
    return mix(span, support, inside);
}

float whiteSceneDepth(vec2 px) {
    if (uWhiteSceneDepth <= .5) return 1.0;
    vec2 local = floor(px) - uBgRect.xy;
    if (any(lessThan(local, vec2(0.0))) || any(greaterThanEqual(local, uBgRect.zw))) return 1.0;
    return texelFetch(SceneDepth, clamp(ivec2(local), ivec2(0), textureSize(SceneDepth, 0) - 1), 0).r;
}

float whiteWorldDistance(vec2 px, vec3 viewRay, float sceneDepth) {
    if (sceneDepth >= 1.0) return 1e20;
    vec3 eye = uInvProj[2].xyz / uInvProj[2].w;
    vec4 hit = uInvProj * vec4(px / uScreenSize * 2.0 - 1.0, sceneDepth * 2.0 - 1.0, 1.0);
    return max(0.0, dot(hit.xyz / hit.w - eye, viewRay) * (2.0 / uRs));
}

vec4 whitePrimaryLight(vec3 origin, vec3 direction, float cutoff) {
    vec4 light = vec4(0.0, 0.0, 0.0, 1.0);
    if (uWhiteHole.z <= 0.0) return light;
    WhiteEmission emission = whiteEmission();
    vec2 limit = whiteIntersect(vec2(0.0, cutoff), whiteSphereInterval(origin, direction, WHITE_SHELL_MAX));
    if (limit.x >= limit.y) return light;
    vec2 field[5];
    field[0] = emission.style.w > 0.0
             ? whiteIntersect(limit, whiteSphereInterval(origin, direction, HORIZON + 4.0)) : vec2(1.0, 0.0);
    field[1] = emission.style.x > 0.0 ? whiteDiskInterval(origin, direction, limit, emission.style.y) : vec2(1.0, 0.0);
    field[2] = whiteJetInterval(origin, direction, 1.0, limit);
    field[3] = whiteJetInterval(origin, direction, -1.0, limit);
    field[4] = emission.shells || uWhiteParticleGain > 0.0
             ? whiteIntersect(limit, whiteSphereInterval(origin, direction, emission.shells ? WHITE_SHELL_MAX : 30.0))
             : vec2(1.0, 0.0);
    if (field[0].x >= field[0].y && field[1].x >= field[1].y && field[2].x >= field[2].y
            && field[3].x >= field[3].y && field[4].x >= field[4].y) return light;
    // The primary image is an exterior emission approximation. Smoothly damp the far
    // side through the projected horizon without stamping an opaque ellipsoid.
    vec3 normalization = vec3(inversesqrt(HORIZON * HORIZON + SPIN * SPIN));
    normalization.z = 1.0 / HORIZON;
    vec3 eye = origin * normalization, ray = direction * normalization;
    float closest = -dot(eye, ray) / dot(ray, ray);
    float impact = length(eye + closest * ray);
    float farThrough = mix(.32, 1.0, smoothstep(.45, 1.65, impact));
    // Integrate each medium before resolving overlap. Depth moments keep the
    // transition continuous when neighboring rays cross two overlapping media.
    vec4 layer[5];
    vec2 depthMoments[5];
    for (int material = 0; material < 5; ++material) {
        vec2 span = field[material];
        vec4 medium = vec4(0.0, 0.0, 0.0, 1.0);
        vec2 moments = vec2(0.0);
        int count = material == 0 ? 24 : material == 1 ? (uIntegration.y >= 480.0 ? 18 : 12)
                  : material == 4 ? (emission.shells ? 22 : 10) : 11;
        float ds = (span.y - span.x) / float(count);
        if (ds > .00001) for (int i = 0; i < count && medium.a > .002; ++i) {
            float t = (float(i) + .5) * ds;
            float middle = span.x + t;
            vec3 p = origin + middle * direction;
            float weight = mix(1.0, farThrough, smoothstep(closest - .45, closest + .45, middle));
            float previous = medium.a;
            transferWhiteSample(State(p, direction, 0.0), 1.0, ds * weight, emission, material, medium);
            moments += (previous - medium.a) * vec2(t, t * t);
        }
        float opacity = 1.0 - medium.a;
        vec2 mean = moments / max(opacity, .00001);
        layer[material] = medium;
        depthMoments[material] = vec2(span.x + mean.x, sqrt(max(0.0, mean.y - mean.x * mean.x)));
        light.a *= medium.a;
    }
    for (int i = 0; i < 5; ++i) {
        if (layer[i].a >= .99999) continue;
        float transmission = 1.0;
        for (int j = 0; j < 5; ++j) {
            if (j == i || layer[j].a >= .99999) continue;
            float spread = .12 + 1.5 * (depthMoments[i].y + depthMoments[j].y);
            float inFront = smoothstep(-spread, spread, depthMoments[i].x - depthMoments[j].x);
            transmission *= mix(1.0, layer[j].a, inFront);
        }
        light.rgb += transmission * layer[i].rgb;
    }
    return light;
}
