#version 150

// 归墟视界枢核 · 光柱环（torus）的片元着色器 —— 伪神之锻炉 shanhai_host_beam.fsh 的移植。
//
// 🔴 2026-10-25 §5 修的就是 rim 这一处（真因：不是遮挡，是 rim 丢了"沿轴分量"）：
//     伪神原版   viewEffect = dot(normalize(CameraPosition.xy), normalize(localPosition.xy))
//                —— 相机与顶点都【只取横截面两个分量】，沿轴分量被丢掉。
//     本环第一版 viewEffect = dot(normalize(Normal), normalize(CameraPosition - Position))
//                —— 三维全留 ⇒ 对圆环来说"沿轴"就是【沿管轴的切线】。侧向那两段的视线
//                   几乎完全是切向的，而法线恒垂直于切线 ⇒ 点积上限 sqrt(1−t²) 随距离趋 0
//                   ⇒ 那些段 alpha=0。这就是"靠近才渲染 / 远段整片黑"的真因
//                   （离线探针 RingRimProbe：离环心 100 格时 42% 的方位 α=0，见报告 §5）。
//     现在       toCam 先减掉切线分量再与法线点积 ⇒ 与伪神 .xy 版【数学等价】
//                （法线 ⟂ 切线，两者只差那一个被丢掉的轴向分量），且与相机距离无关。
//
// 其余（(v−0.5)*2、pow(Intensity)、贴图亮度变换、mix、clamp、BeamColor）与伪神一字未改。

uniform sampler2D Sampler0;
uniform float Intensity;
uniform float RimFloor;   // rim 下限（0..1）：任何视线角度都不许塌到 0 —— 用户症状②③的修复
uniform vec3 CameraPosition;
uniform vec3 BeamColor;

in vec2 texCoord;
in vec3 localPosition;
in vec3 localNormal;
in vec3 localTangent;
in float transparency;

out vec4 fragColor;

float luminanceTransform(vec3 color) {
    return 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;
}

void main() {
    vec3 toCam = CameraPosition - localPosition;
    // 去掉沿管轴（环切线）的分量 —— 这一步就是"伪神 normalize(...xy)"的等价物，也是 §5 的修复点
    vec3 perp = toCam - dot(toCam, localTangent) * localTangent;
    float perpLen = length(perp);
    // 退化兜底：视线完全沿管轴时（正对管口看）没有横截面方向 ⇒ 反过来用切线本身，避免 NaN
    vec3 viewDir = (perpLen > 1.0e-4) ? (perp / perpLen) : normalize(localTangent);
    float viewEffect = abs(dot(normalize(localNormal), viewDir));
    float angleAlpha = max((viewEffect - 0.5) * 2.0, 0.0);
    angleAlpha = pow(angleAlpha, Intensity);
    // 🔴 2026-10-25 三轮：rim 加下限 —— 掠射 / 背光那一半不再被压到 0（用户："没被挡却没画""室内部一点都不渲染"）
    float shape = RimFloor + (1.0 - RimFloor) * angleAlpha;

    vec4 texColor = texture(Sampler0, texCoord);
    float luminance = 1.0 - luminanceTransform(texColor.xyz);
    luminance = mix(luminance, 1.0, 1.0 - pow(angleAlpha, 6.0));
    float alpha = clamp(shape * transparency * luminance, 0.0, 1.0);
    fragColor = vec4(BeamColor, alpha);
}
