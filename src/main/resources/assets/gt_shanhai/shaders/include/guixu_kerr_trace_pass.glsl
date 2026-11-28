uniform vec4 uTraceRect;
uniform vec2 uTraceSize;
uniform vec2 uRayJitter;
uniform sampler2D CachedRayExit;
uniform sampler2D CriticalRadii;
uniform float uReuseRayExit;
uniform float uTransferOnly;
uniform vec4 uCriticalBounds; // projected centre, radius search or strip width, pass
out vec4 rayLight;
out vec4 rayExit;

void main() {
    if (uCriticalBounds.w == 3.0) {
        vec2 uv = gl_FragCoord.xy / uTraceSize;
        vec2 px = uTraceRect.xy + uv * uTraceRect.zw;
        rayLight = vec4(0.0, 0.0, 0.0, 1.0);
        rayExit = vec4(0.0);
        // Keep the full reconstruction and mip-bloom footprint around the visible tile.
        float pad = max(192.0, 4.0 * uTraceRect.z / uTraceSize.x);
        float tileRadius = length(uv * 2.0 - 1.0);
        if (all(greaterThanEqual(px, vec2(-pad))) && all(lessThanEqual(px, uScreenSize + pad))
            && tileRadius < 1.02) {
            // The shadow, first lensed arcs and independent foreground edge
            // use the original tolerance. Farther out the disk varies more slowly.
            float toleranceCap = mix(.000006, .000035, smoothstep(.72, 1.0, tileRadius));
            rayExit = tracePixel(px, uTransferOnly > .5, toleranceCap, rayLight);
        }
        return;
    }
    if (uCriticalBounds.w > .5) {
        float angle = 6.2831853 * gl_FragCoord.x / uTraceSize.x;
        vec2 axis = vec2(cos(angle), sin(angle));
        vec2 center = uCriticalBounds.xy;
        rayExit = vec4(0.0);
        if (uCriticalBounds.w > 1.5) {
            vec3 head = texelFetch(CriticalRadii, ivec2(int(gl_FragCoord.x), 0), 0).rgb;
            rayLight = vec4(0.0, 0.0, 0.0, 1.0);
            if (head.z > .5) {
                float offset = criticalOffset(gl_FragCoord.y / uTraceSize.y, uCriticalBounds.z);
                rayExit = tracePixel(center + axis * (head.x + head.y + offset),
                                     uCriticalBounds.w < 3.5, true, rayLight);
            }
            return;
        }
        float inside = 0.0, outside = uCriticalBounds.z;
        if (shadowMargin(center) >= 0.0 || shadowMargin(center + axis * outside) <= 0.0) {
            rayLight = vec4(0.0);
            return;
        }
        for (int i = 0; i < 14; ++i) {
            float r = .5 * (inside + outside);
            if (shadowMargin(center + axis * r) < 0.0) inside = r;
            else outside = r;
        }
        float r = .5 * (inside + outside);
        rayLight = vec4(floor(r), fract(r), 1.0, 1.0);
        return;
    }
    vec2 px = uTraceRect.xy + (gl_FragCoord.xy + uRayJitter) / uTraceSize * uTraceRect.zw;
    bool transferOnly = uTransferOnly > .5;
#if !defined(KERR_CHECK_INVARIANTS) && !defined(KERR_FULL_CAPTURE)
    if (transferOnly && (uDiskGain <= 0.0 || uDiskPhysics.x <= 0.0 || uDiskPhysics.w <= 0.0)) {
        rayLight = vec4(0.0, 0.0, 0.0, 1.0);
        rayExit = vec4(0.0, 0.0, 0.0, 1.0 + capturedAt(px));
        return;
    }
#endif
    vec4 cached = uReuseRayExit > 0.5 ? texelFetch(CachedRayExit, ivec2(gl_FragCoord.xy), 0) : vec4(0.0);
    bool reuseExit = cached.w == 1.0;
    rayExit = tracePixel(px, transferOnly || reuseExit, rayLight);
    if (reuseExit) rayExit = cached;
#ifdef KERR_FULL_CAPTURE
    rayLight.a = capturedAt(px);
#endif
}
