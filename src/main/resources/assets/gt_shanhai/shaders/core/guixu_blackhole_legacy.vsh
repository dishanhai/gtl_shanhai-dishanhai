#version 150

// 山海重构 · 黑洞透镜（一级）顶点着色器。
//
// 顶点位置【已经是 NDC】：
//   xy = 这一帧要覆盖的屏幕矩形（左下原点 → NDC 的换算在 Java 里做完）
//   z  = 黑洞中心所在平面的 NDC 深度（所有 4 个角相同 ⇒ 透视插值后恒定）
// ⇒ 本着色器不需要任何矩阵、不需要 poseStack、也不需要减 camPos。
//   整条"世界 → 屏幕"的账只在 Java 侧算一次（BlackHoleLensMath.toView /
//   projectToScreen），这里不重复算，避免出现两套账。

in vec3 Position;

void main() {
    gl_Position = vec4(Position, 1.0);
}
