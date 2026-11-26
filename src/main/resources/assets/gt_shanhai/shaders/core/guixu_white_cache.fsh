#version 150
#moj_import <gt_shanhai:guixu_kerr.glsl>

uniform sampler2D SceneDepth;
uniform vec4 uBgRect;
uniform float uWhiteSceneDepth;
uniform vec2 uCacheOrigin;
uniform vec2 uCacheStride;
out vec4 directLight;
out float directDepth;

#moj_import <gt_shanhai:guixu_white_primary.glsl>

void main() {
    vec2 px = uCacheOrigin + gl_FragCoord.xy * uCacheStride;
    mat3 basis;
    vec3 origin, direction;
    cameraRay(px, basis, origin, direction);
    bool reduced = any(greaterThan(uCacheStride, vec2(1.0)));
    float sceneDepth = whiteSceneDepth(px);
    float cutoff = whiteWorldDistance(px, basis * direction, sceneDepth);
    // Negative depth tags a ray which traverses the whole volume. Window
    // depth varies linearly across planar terrain and can be reconstructed.
    directDepth = reduced ? (cutoff >= whiteSphereInterval(origin, direction, WHITE_SHELL_MAX).y ? -1.0 : sceneDepth)
                          : sceneDepth;
    directLight = whitePrimaryLight(origin, direction, cutoff);
}
