#version 150

// 山海重构 · 黑洞【洞内往外看】· 顶点着色器
//
// 顶点位置【已经是裁剪空间】：
//   xy = −1..1（整屏覆盖）
//   z  = 0（深度测试是 always、且不写深度 ⇒ z 无意义）
// ⇒ 不需要任何矩阵：与 blackhole.vsh（一级透镜）同一套约定，
//   整条"世界 → 屏幕"的账在 Java 侧算一次，这里不重复算。

in vec3 Position;

void main() {
    gl_Position = vec4(Position, 1.0);
}
