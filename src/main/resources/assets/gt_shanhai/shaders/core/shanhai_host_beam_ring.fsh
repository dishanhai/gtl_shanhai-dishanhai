#version 150
// Author: dishanhai — 扁薄外緣吸積流；與 GuixuAccretionFlow 的回落公式一致。
uniform float FlowTime;
uniform vec3 CameraPosition;
uniform vec3 BeamColor;
in vec2 flowCoord;
in vec3 localPosition;
in float transparency;
out vec4 fragColor;
// 兩組有界壽命的湍流平滑交替；不讓差速剪切隨世界年齡無限增長。
float density(float s, float a, float clock, float age) {
    float radius = 51.0 + 6.0 * s;
    float omega = .72 * pow(51.0 / radius, 1.5);
    float orbit = a - .7853981633974483 * (clock - age) - omega * age;
    float phase = 22.0 * s + 3.0 * orbit + .16 * age + .65 * sin(4.0 * orbit + 5.0 * s + .11 * age);
    float phase2 = 37.0 * s + 5.0 * orbit + .23 * age;
    float width = max(fwidth(phase), fwidth(phase2));
    float filtered = exp(-.5 * width * width);
    float ridge = mix(.2734375, pow(.5 + .5 * sin(phase), 4.0), filtered);
    float secondary = mix(.5, .5 + .5 * sin(phase2), filtered);
    float clumpPhase = 7.0 * orbit + 9.0 * s + .18 * age + .8 * sin(3.0 * orbit + 4.0 * s + .07 * age);
    float clumpFilter = exp(-.5 * fwidth(clumpPhase) * fwidth(clumpPhase));
    float clumpWave = .5 + .5 * sin(clumpPhase);
    float clumps = .32 + .68 * mix(.375, clumpWave * clumpWave, clumpFilter);
    return (.10 + .72 * ridge + .18 * secondary) * clumps;
}
void main() {
    float s = flowCoord.x;
    // 網格角度可跨 2π 插值，避免 atan 接縫及其導數尖峰。
    float a = flowCoord.y;
    float radius = 51.0 + 6.0 * s;
    float clock = mod(FlowTime, 24.0);
    float age = mod(clock + 12.0, 24.0);
    float weight = .5 - .5 * cos(clock * .2617993877991494);
    float gas = weight * density(s, a, clock, clock) + (1.0 - weight) * density(s, a, clock, age);
    float edge = smoothstep(0.0, .13, s) * (1.0 - smoothstep(.68, 1.0, s));
    vec3 toCam = CameraPosition - localPosition;
    vec3 view = toCam / max(length(toCam), 1e-5);
    float beta = .32 * sqrt(51.0 / radius);
    float doppler = sqrt(1.0 - beta * beta) / (1.0 - beta * dot(vec3(-sin(a), 0.0, cos(a)), view));
    float emissivity = pow(51.0 / radius, 3.0) * (1.0 - .55 * s);
    float alpha = min(.62, 1.35 * edge * emissivity * gas * doppler * doppler * doppler);
    float heat = clamp(1.0 - smoothstep(.08, .92, s) + .16 * (doppler - 1.0), 0.0, 1.0);
    vec3 color = vec3(1.0, .48 + .47 * heat, .16 + .68 * heat);
    fragColor = vec4(color * BeamColor, alpha * transparency);
}
