#version 150

// 商店右侧详情面板的局部可分离高斯模糊。
// 只处理 Rect 内部、Hole 外部的像素；Rect 外的像素原样输出，
// 因此整屏后处理的成本基本只跟面板面积成正比。
uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 BlurDir;
uniform float Radius;
uniform float Progress;
uniform vec4 Rect;   // xy = 左下角 UV，zw = 右上角 UV
uniform vec4 Hole;   // 正在飞行的卡片矩形，保持清晰

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

// 9-tap 高斯（σ≈2）用 5 次双线性采样近似，权重和为 1。
const float W0 = 0.227027;
const float W1 = 0.316216;
const float W2 = 0.070270;
const float O1 = 1.384615;
const float O2 = 3.230769;

void main() {
    vec2 uv = texCoord;

    bool insideRect = uv.x >= Rect.x && uv.x <= Rect.z
            && uv.y >= Rect.y && uv.y <= Rect.w;
    bool insideHole = Hole.z > Hole.x && Hole.w > Hole.y
            && uv.x >= Hole.x && uv.x <= Hole.z
            && uv.y >= Hole.y && uv.y <= Hole.w;

    if (!insideRect || insideHole) {
        fragColor = texture(DiffuseSampler, uv);
        return;
    }

    float radius = Radius * clamp(Progress, 0.0, 1.0);
    if (radius < 0.5) {
        fragColor = texture(DiffuseSampler, uv);
        return;
    }

    vec2 dir = BlurDir * oneTexel * radius;
    vec2 lo = Rect.xy;
    vec2 hi = Rect.zw;

    // 采样坐标夹在面板内部，避免把左侧网格/边框的颜色吸进面板边缘。
    vec4 sum = texture(DiffuseSampler, clamp(uv, lo, hi)) * W0;
    sum += texture(DiffuseSampler, clamp(uv + dir * O1, lo, hi)) * W1;
    sum += texture(DiffuseSampler, clamp(uv - dir * O1, lo, hi)) * W1;
    sum += texture(DiffuseSampler, clamp(uv + dir * O2, lo, hi)) * W2;
    sum += texture(DiffuseSampler, clamp(uv - dir * O2, lo, hi)) * W2;

    fragColor = vec4(sum.rgb, 1.0);
}
