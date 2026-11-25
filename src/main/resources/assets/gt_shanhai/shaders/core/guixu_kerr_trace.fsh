#version 150
#moj_import <gt_shanhai:guixu_kerr.glsl>

uniform vec4 uTraceRect;
uniform vec2 uTraceSize;
uniform vec2 uRayJitter;
out vec4 rayLight;
out vec4 rayExit;

void main() {
    vec2 px = uTraceRect.xy + (gl_FragCoord.xy + uRayJitter) / uTraceSize * uTraceRect.zw;
    rayExit = tracePixel(px, false, rayLight);
#ifdef KERR_FULL_CAPTURE
    rayLight.a = capturedAt(px);
#endif
}
