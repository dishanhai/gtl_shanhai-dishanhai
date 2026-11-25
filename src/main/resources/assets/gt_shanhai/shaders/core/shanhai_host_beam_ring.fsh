#version 150
// Author: dishanhai — 激光环的亮芯、流纹和薄雾与 GuixuAccretionFlow 共用轮廓。
uniform float FlowTime;
uniform vec3 CameraPosition;
uniform vec3 BeamColor;
uniform float BeamBrightness;
in vec2 flowCoord;
in vec3 localPosition;
out vec4 fragColor;
// 兩組有界壽命的湍流平滑交替；不讓差速剪切隨世界年齡無限增長。
float density(float s, float a, float clock, float age, float omega) {
    float orbit = a - .7853981633974483 * (clock - age) - omega * age;
    float phase = 22.0 * s + 3.0 * orbit + .16 * age + .65 * sin(4.0 * orbit + 5.0 * s + .11 * age);
    float phase2 = 37.0 * s + 5.0 * orbit + .23 * age;
    float width = max(fwidth(phase), fwidth(phase2));
    float filtered = exp(-.5 * width * width);
    float wave = .5 + .5 * sin(phase);
    float ridgeSquared = wave * wave;
    float ridge = mix(.2734375, ridgeSquared * ridgeSquared, filtered);
    float secondary = mix(.5, .5 + .5 * sin(phase2), filtered);
    float clumpPhase = 7.0 * orbit + 9.0 * s + .18 * age + .8 * sin(3.0 * orbit + 4.0 * s + .07 * age);
    float clumpWidth = fwidth(clumpPhase);
    float clumpFilter = exp(-.5 * clumpWidth * clumpWidth);
    float clumpWave = .5 + .5 * sin(clumpPhase);
    float clumps = .32 + .68 * mix(.375, clumpWave * clumpWave, clumpFilter);
    return (.10 + .72 * ridge + .18 * secondary) * clumps;
}
void main() {
    float s = flowCoord.x;
    // 網格角度可跨 2π 插值，避免 atan 接縫及其導數尖峰。
    float a = flowCoord.y;
    float inverseRadius = 51.0 / (51.0 + 6.0 * s);
    float radialRoot = sqrt(inverseRadius);
    float omega = .72 * inverseRadius * radialRoot;
    float age = mod(FlowTime + 12.0, 24.0);
    float cycle = FlowTime * .2617993877991494;
    float weight = .5 - .5 * cos(cycle);
    float gas = mix(density(s, a, FlowTime, age, omega), density(s, a, FlowTime, FlowTime, omega), weight);
    vec3 toCam = CameraPosition - localPosition;
    vec3 view = toCam / max(length(toCam), 1e-5);
    float beta = .32 * radialRoot;
    float doppler = sqrt(1.0 - beta * beta) / (1.0 - beta * dot(vec3(-sin(a), 0.0, cos(a)), view));
    float emissivity = inverseRadius * inverseRadius * inverseRadius * (1.0 - .55 * s);
    float heat = clamp(1.0 - smoothstep(.08, .92, s) + .16 * (doppler - 1.0), 0.0, 1.0);
    float edge = smoothstep(0.0, .15, s) * (1.0 - smoothstep(.73, 1.0, s));
    float core = 1.0 - smoothstep(.045, .27, abs(s - .34));
    float pulsePhase = 5.0 * a - 13.0 * cycle + .32 * sin(2.0 * a + cycle);
    float pulseWave = .5 + .5 * cos(pulsePhase);
    float pulse = mix(pulseWave * pulseWave, .375, smoothstep(.65, 2.5, fwidth(pulsePhase)));
    float intensity = 1.40 * edge * (.50 + .50 * core) * (.58 + .42 * pulse)
            * emissivity * gas * doppler * doppler * doppler;
    vec3 halo = vec3(.52, .42, .78) + pulse * vec3(.02, .10, .10);
    vec3 color = mix(halo, vec3(1.0, .68 + .29 * heat, .57 + .38 * heat), core);
    fragColor = vec4(color * BeamColor, min(.70, intensity) * BeamBrightness);
}
