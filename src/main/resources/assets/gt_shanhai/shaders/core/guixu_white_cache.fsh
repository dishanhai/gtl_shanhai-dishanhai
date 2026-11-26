#version 150
#moj_import <gt_shanhai:guixu_kerr.glsl>

uniform sampler2D SceneDepth;
uniform vec4 uBgRect;
uniform float uWhiteSceneDepth;
uniform vec2 uCacheOrigin;
out vec4 directLight;
out float directDepth;

#moj_import <gt_shanhai:guixu_white_primary.glsl>

void main() {
    vec2 px = uCacheOrigin + gl_FragCoord.xy;
    mat3 basis;
    vec3 origin, direction;
    cameraRay(px, basis, origin, direction);
    directDepth = whiteSceneDepth(px);
    float cutoff = whiteWorldDistance(px, basis * direction, directDepth);
    directLight = whitePrimaryLight(origin, direction, cutoff);
}
