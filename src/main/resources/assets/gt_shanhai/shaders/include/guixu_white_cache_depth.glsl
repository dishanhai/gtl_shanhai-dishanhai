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
