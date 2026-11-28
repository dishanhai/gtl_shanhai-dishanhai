#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec2 ActiveUV;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    // Additive effects may contribute RGB to pixels with zero coverage.
    vec2 halfTexel = .5 / vec2(textureSize(Sampler0, 0));
    fragColor = texture(Sampler0, clamp(texCoord0, halfTexel, ActiveUV - halfTexel)) * vertexColor * ColorModulator;
}
