#version 150
// 山海：程式驅動雲氣扭曲及雙向流動，正常兩次取樣，降級一次；不做體積步進。
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float NebulaPhase;
uniform float NebulaDetail;
in vec2 texCoord;
in vec4 vertexColor;
in vec3 viewPosition;
in vec3 viewNormal;
out vec4 fragColor;

vec2 mirrorFlow(vec2 uv) {
    return vec2(1.0 - abs(fract(uv.x * 0.5) * 2.0 - 1.0), clamp(uv.y, 0.0, 1.0));
}

void main() {
    float edge = smoothstep(0.0, 0.12, min(texCoord.y, 1.0 - texCoord.y));
    // 掠视截面柔和淡出；用一次逆平方根同时归一化两个向量。
    float facing = abs(dot(viewNormal, viewPosition))
        * inversesqrt(max(1e-12, dot(viewNormal, viewNormal) * dot(viewPosition, viewPosition)));
    float softFacing = smoothstep(0.05, 0.60, facing);

    float drift = NebulaPhase / 3.141592653589793;
    vec2 warp = vec2(sin(texCoord.y * 8.0 + NebulaPhase * 2.0) + sin(texCoord.x * 11.0 - NebulaPhase),
                     cos(texCoord.x * 9.0 + NebulaPhase)) * 0.021;
    vec4 gas = texture(Sampler0, mirrorFlow(vec2(texCoord.x * 2.0 + drift + warp.x, texCoord.y + warp.y * edge)));
    if (NebulaDetail > 0.5) {
        vec4 counterFlow = texture(Sampler0, mirrorFlow(vec2(texCoord.x * 2.0 + drift + 0.37 - warp.y,
                                                            texCoord.y - warp.x * edge)));
        gas = mix(gas, counterFlow, 0.38 + 0.08 * sin(NebulaPhase + texCoord.x * 6.0));
    }
    float pulse = 0.92 + 0.08 * sin(NebulaPhase * 2.0 + texCoord.x * 11.0 + texCoord.y * 3.0);
    fragColor = vec4(gas.rgb, gas.a * edge * pulse * softFacing) * vertexColor * ColorModulator;
}
