#version 150
// Author: dishanhai — 扁薄外緣吸積流；與 GuixuAccretionFlow 的回落公式一致。
uniform float FlowTime;
uniform vec3 CameraPosition;
uniform vec3 BeamColor;
in vec2 flowCoord;
in vec3 localPosition;
in float transparency;
out vec4 fragColor;
void main() {
    float s = flowCoord.x;
    // 網格角度可跨 2π 插值，避免 atan 接縫及其導數尖峰。
    float a = flowCoord.y;
    float phase = 18.0 * s + 2.0 * a + .85 * FlowTime + .7 * sin(3.0 * a - .2 * FlowTime)
            + 1.3 * sin(4.0 * a + 5.0 * s - .31 * FlowTime);
    float phase2 = 11.0 * s - 3.0 * a + .6 * FlowTime;
    float width = max(fwidth(phase), fwidth(phase2));
    float filtered = exp(-.5 * width * width);
    float ridge = mix(.2734375, pow(.5 + .5 * sin(phase), 4.0), filtered);
    float secondary = mix(.5, .5 + .5 * sin(phase2), filtered);
    float clumpPhase = 5.0 * a + 7.0 * s - .37 * FlowTime + .8 * sin(2.0 * a + 4.0 * s + .23 * FlowTime);
    float clumpFilter = exp(-.5 * fwidth(clumpPhase) * fwidth(clumpPhase));
    float clumpWave = .5 + .5 * sin(clumpPhase);
    float clumps = .18 + .82 * mix(.375, clumpWave * clumpWave, clumpFilter);
    float edge = smoothstep(0.0, .13, s) * (1.0 - smoothstep(.68, 1.0, s));
    vec3 toCam = CameraPosition - localPosition;
    vec3 view = toCam / max(length(toCam), 1e-5);
    float brightness = .78 + .22 * dot(vec3(-sin(a), 0.0, cos(a)), view);
    float alpha = min(.62, .95 * edge * (.06 + .78 * ridge + .16 * secondary) * clumps * brightness);
    float heat = 1.0 - smoothstep(.10, .85, s);
    vec3 color = vec3(1.0, .60 + .35 * heat, .34 + .54 * heat);
    fragColor = vec4(color * BeamColor, alpha * transparency);
}
