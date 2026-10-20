#version 150

// 商店内容区的局部可分离高斯模糊。
// 只处理 Rect 内部、Hole 外部的像素；Rect 外的像素原样输出，
// 因此整屏后处理的成本基本只跟矩形面积成正比。
// Smooth=0：5 次宽间距采样，给卡片飞行。文字会出残影，不要用在长文本上。
// Smooth=1：步长约 radius/10 的密采样高斯，给描述详情和分组切页。
uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 BlurDir;
uniform float Radius;
uniform float Progress;
uniform float Smooth;
uniform vec4 Rect;   // xy = 左下角 UV，zw = 右上角 UV
uniform vec4 Hole;   // 正在飞行的卡片或浮层矩形，保持清晰
// PostChain 的 float uniform 走 glUniform1fv，所以这里是 8 个矩形 × 4 分量，不是 vec4[8]。
// CellCount=0 时忽略，行为与只有 Rect 的旧路径相同。
uniform float CellRects[32];
uniform int CellCount;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

// 9-tap 高斯（σ≈2）用 5 次双线性采样近似，权重和为 1。
const float W0 = 0.227027;
const float W1 = 0.316216;
const float W2 = 0.070270;
const float O1 = 1.384615;
const float O2 = 3.230769;

bool insideBox(vec2 p, vec4 b) {
    return p.x >= b.x && p.x <= b.z && p.y >= b.y && p.y <= b.w;
}

void main() {
    vec2 uv = texCoord;

    bool insideHole = Hole.z > Hole.x && Hole.w > Hole.y
            && uv.x >= Hole.x && uv.x <= Hole.z
            && uv.y >= Hole.y && uv.y <= Hole.w;
    if (!insideBox(uv, Rect) || insideHole) {
        fragColor = texture(DiffuseSampler, uv);
        return;
    }

    vec4 box = Rect;
    if (CellCount > 0) {
        bool hit = false;
        vec4 c0 = vec4(CellRects[0], CellRects[1], CellRects[2], CellRects[3]);
        vec4 c1 = vec4(CellRects[4], CellRects[5], CellRects[6], CellRects[7]);
        vec4 c2 = vec4(CellRects[8], CellRects[9], CellRects[10], CellRects[11]);
        vec4 c3 = vec4(CellRects[12], CellRects[13], CellRects[14], CellRects[15]);
        vec4 c4 = vec4(CellRects[16], CellRects[17], CellRects[18], CellRects[19]);
        vec4 c5 = vec4(CellRects[20], CellRects[21], CellRects[22], CellRects[23]);
        vec4 c6 = vec4(CellRects[24], CellRects[25], CellRects[26], CellRects[27]);
        vec4 c7 = vec4(CellRects[28], CellRects[29], CellRects[30], CellRects[31]);
        if (insideBox(uv, c0)) { box = c0; hit = true; }
        else if (CellCount > 1 && insideBox(uv, c1)) { box = c1; hit = true; }
        else if (CellCount > 2 && insideBox(uv, c2)) { box = c2; hit = true; }
        else if (CellCount > 3 && insideBox(uv, c3)) { box = c3; hit = true; }
        else if (CellCount > 4 && insideBox(uv, c4)) { box = c4; hit = true; }
        else if (CellCount > 5 && insideBox(uv, c5)) { box = c5; hit = true; }
        else if (CellCount > 6 && insideBox(uv, c6)) { box = c6; hit = true; }
        else if (CellCount > 7 && insideBox(uv, c7)) { box = c7; hit = true; }
        if (!hit) {
            fragColor = texture(DiffuseSampler, uv);
            return;
        }
    }

    float radius = Radius * clamp(Progress, 0.0, 1.0);
    if (radius < 0.5) {
        fragColor = texture(DiffuseSampler, uv);
        return;
    }

    vec2 lo = box.xy;
    vec2 hi = box.zw;

    // 文字背后要连续的高斯，不能用下面那组宽间距 5-tap，否则字形会裂成残影。
    if (Smooth > 0.5) {
        float stepPx = max(1.0, radius / 10.0);
        float sigma = max(radius / 2.5, stepPx);
        vec2 step = BlurDir * oneTexel * stepPx;
        vec4 sum = texture(DiffuseSampler, clamp(uv, lo, hi));
        float wsum = 1.0;
        for (int i = 1; i <= 10; i++) {
            float dist = float(i) * stepPx;
            float w = exp(-0.5 * (dist * dist) / (sigma * sigma));
            sum += texture(DiffuseSampler, clamp(uv + step * float(i), lo, hi)) * w;
            sum += texture(DiffuseSampler, clamp(uv - step * float(i), lo, hi)) * w;
            wsum += w * 2.0;
        }
        fragColor = vec4(sum.rgb / wsum, 1.0);
        return;
    }

    vec2 dir = BlurDir * oneTexel * radius;

    // 采样坐标夹在面板内部，避免把左侧网格/边框的颜色吸进面板边缘。
    vec4 sum = texture(DiffuseSampler, clamp(uv, lo, hi)) * W0;
    sum += texture(DiffuseSampler, clamp(uv + dir * O1, lo, hi)) * W1;
    sum += texture(DiffuseSampler, clamp(uv - dir * O1, lo, hi)) * W1;
    sum += texture(DiffuseSampler, clamp(uv + dir * O2, lo, hi)) * W2;
    sum += texture(DiffuseSampler, clamp(uv - dir * O2, lo, hi)) * W2;

    fragColor = vec4(sum.rgb, 1.0);
}
