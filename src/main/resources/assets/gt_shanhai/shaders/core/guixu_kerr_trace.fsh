#version 150
#moj_import <gt_shanhai:guixu_kerr.glsl>

uniform vec4 uTraceRect;
uniform vec2 uTraceSize;
uniform vec2 uRayJitter;
uniform sampler2D CachedRayExit;
uniform sampler2D CriticalRadii;
uniform float uReuseRayExit;
uniform vec4 uCriticalBounds; // projected centre, radius search or strip width, pass
out vec4 rayLight;
out vec4 rayExit;

void main() {
    if (uCriticalBounds.w == 3.0) {
        vec2 uv = gl_FragCoord.xy / uTraceSize;
        rayLight = vec4(0.0, 0.0, 0.0, 1.0);
        rayExit = vec4(0.0);
        if (length(uv * 2.0 - 1.0) < 1.02)
            rayExit = tracePixel(uTraceRect.xy + uv * uTraceRect.zw, false, true, rayLight);
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
                float t = 2.0 * gl_FragCoord.y / uTraceSize.y - 1.0;
                float offset = .5 * uCriticalBounds.z * t * abs(t);
                tracePixel(center + axis * (head.x + head.y + offset), true, true, rayLight);
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
    vec4 cached = uReuseRayExit > 0.5 ? texelFetch(CachedRayExit, ivec2(gl_FragCoord.xy), 0) : vec4(0.0);
    bool reuseExit = cached.w == 1.0;
    rayExit = tracePixel(px, reuseExit, rayLight);
    if (reuseExit) rayExit = cached;
#ifdef KERR_FULL_CAPTURE
    rayLight.a = capturedAt(px);
#endif
}
