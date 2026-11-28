#version 150

#moj_import <fog.glsl>

in vec4 vertexColor;
in float vertexDistance;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;

out vec4 fragColor;

void main() {
    float alpha = vertexColor.a * ColorModulator.a * linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb * alpha, alpha);
}
