#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 BlurDir;

in vec2 texCoord;
in vec2 oneTexel;
out vec4 fragColor;

void main() {
    vec2 dir = oneTexel * BlurDir;
    vec4 sum = texture(DiffuseSampler, texCoord) * 0.227027;
    sum += texture(DiffuseSampler, texCoord + dir * 1.384615) * 0.316216;
    sum += texture(DiffuseSampler, texCoord - dir * 1.384615) * 0.316216;
    sum += texture(DiffuseSampler, texCoord + dir * 3.230769) * 0.070270;
    sum += texture(DiffuseSampler, texCoord - dir * 3.230769) * 0.070270;
    fragColor = vec4(sum.rgb, 1.0);
}
