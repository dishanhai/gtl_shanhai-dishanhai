#version 150

// 归墟视界枢核 · 光柱环（torus）的顶点着色器 —— 伪神之锻炉 shanhai_host_beam.vsh 的移植。
//
// 与伪神那份的三处差别（第 ③ 条是 2026-10-25 §5 的 bug 修复，见 fsh 头注释）：
//   ① 伪神  localPosition = Position.xy      （整根光束共享一个本地坐标系，轴 = 本地 +Z）
//      本环  localPosition = Position          （管环的轴沿环周一直在拐弯）
//   ② 伪神  没有法线属性；rim 靠 localPosition.xy
//      本环  localNormal = Normal              （逐顶点面法线）
//   ③ 🔴 伪神的 rim 里「相机」与「顶点」都先 normalize(**只取 .xy 两个分量**) ——
//      这等价于【把沿轴（对环 = 沿管轴/切线）的分量丢掉】。本环第一版两者都按三维算，
//      切线分量留了下来 ⇒ 侧向那两段整片黑（探针 RingRimProbe：离环心 100 格时 42% 方位 α=0）。
//      修法：把该段方位角（UV1.y = 度×10）还原成切线，shader 里先减掉切线分量再算 rim。

in vec3 Position;
in vec2 UV0;
in ivec2 UV1;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord;
out vec3 localPosition;
out vec3 localNormal;
out vec3 localTangent;
out float transparency;

void main() {
    texCoord = UV0;
    localPosition = Position;
    // 顶点格式里 Normal 是 byte×3（归一化与否随 MC 实现），这里 normalize 一次把两种都兜住。
    // 兜底的 length 判据：万一属性没绑上（location=-1 ⇒ 全 0），normalize(0) 会出 NaN，
    // 片元里 dot(NaN,…) 的结果未定义（可能整根环变黑）—— 给一个固定朝上的备用法线。
    localNormal = (length(Normal) > 1.0e-6) ? normalize(Normal) : vec3(0.0, 1.0, 0.0);
    // UV1.y = 方位角（度 × 10，0..3599）⇒ 该点的环切线 = (−sinθ, 0, cosθ)
    float azRad = float(UV1.y) * 0.1 * 0.017453292519943295;
    localTangent = vec3(-sin(azRad), 0.0, cos(azRad));
    transparency = float(UV1.x) / 10000.0;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
