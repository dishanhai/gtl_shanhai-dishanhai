#version 150

// 商店内容区的局部可分离高斯模糊。
// 只处理 Rect 内部、Hole 外部的像素；Rect 外的像素原样输出，
// 因此整屏后处理的成本基本只跟矩形面积成正比。
// Smooth=0：5 次宽间距采样，给卡片飞行。文字会出残影，不要用在长文本上。
// Smooth=1：步长约 radius/10 的密采样高斯，给描述详情、购物车、快捷菜单和分组切页。
// WaveRadius>0：点击点的液态玻璃波，半径由调用方逐帧放大。0 时与旧路径相同。
// 注意：纯色面板做高斯完全看不出来，波必须带折射位移和高光才有表现。
uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform vec2 BlurDir;
uniform float Radius;
uniform float Progress;
uniform float Smooth;
// PostChain 的 float uniform 走 glUniform1fv，原点用 float[2]，不要写成 vec2。
uniform float WaveOrigin[2];
uniform float WaveRadius;      // 波峰所在半径（像素）
uniform float WaveThickness;   // 波峰厚度（像素）
uniform float WaveRefract;     // 波峰处沿半径方向的采样位移（像素），负值向外推
uniform float WaveGlow;        // 波峰高光强度 0..1
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
    vec2 lo = box.xy;
    vec2 hi = box.zw;

    // ── 液态玻璃点击波 ────────────────────────────────────────────────
    // 位移只给水平趟，高光只给垂直趟：两趟都做会把位移叠成双份、高光翻倍。
    // 高光放在第二趟才不会被第一趟的模糊抹平，环边才立得住。
    bool firstPass = BlurDir.x > 0.5;
    bool lastPass = BlurDir.y > 0.5;
    bool waveActive = WaveRadius > 0.5;
    vec2 baseUv = uv;
    float glowAdd = 0.0;
    if (waveActive) {
        vec2 origin = vec2(WaveOrigin[0], WaveOrigin[1]);
        vec2 deltaPx = (uv - origin) * InSize;          // v 轴朝上，长度仍然正确
        float distPx = length(deltaPx);
        vec2 dir = distPx > 0.001 ? deltaPx / distPx : vec2(0.0);
        float band = max(WaveThickness, 1.0);
        float ring = distPx - WaveRadius;               // 0 就是波峰所在圆
        float crest = exp(-0.5 * (ring * ring) / (band * band));
        float lens = 1.0 - smoothstep(0.0, max(WaveRadius, 1.0), distPx);
        float fall = 1.0 - smoothstep(WaveRadius * 0.55, max(WaveRadius, 1.0), distPx);
        // 模糊跟着圆盘走：中心最重，到波峰归零，圆外完全不糊
        radius *= fall;
        if (firstPass) {
            // 波峰径向折射：采样点往圆心收，画面看起来被向外推开——这是“液态玻璃”最直观的一层
            baseUv -= dir * (WaveRefract * crest) / InSize;
            // 圆盘内的透镜放大：采样向圆心收，内容看起来被顶起来
            baseUv -= (uv - origin) * (0.12 * lens);
        }
        if (lastPass) {
            // 波峰高光，光从左上来，让圆环有方向感而不是一圈均匀白边
            float lit = 0.55 + 0.45 * dot(dir, normalize(vec2(-0.65, 0.76)));
            glowAdd = WaveGlow * crest * lit;
        }
    }
    // 注意：这里不能只看 radius。波峰处 radius 恰好归零，而折射位移正是在波峰最强，
    // 一旦跟着 radius 提前返回，位移就整段被跳过——纯色面板上就完全看不出来。
    if (!waveActive && radius < 0.5) {
        fragColor = texture(DiffuseSampler, uv);
        return;
    }
    if (radius < 0.5) {
        vec3 flat = texture(DiffuseSampler, clamp(baseUv, lo, hi)).rgb
                + vec3(0.75, 0.92, 1.0) * glowAdd;
        fragColor = vec4(flat, 1.0);
        return;
    }

    // 文字背后要连续的高斯，不能用下面那组宽间距 5-tap，否则字形会裂成残影。
    if (Smooth > 0.5) {
        float stepPx = max(1.0, radius / 10.0);
        float sigma = max(radius / 2.5, stepPx);
        vec2 step = BlurDir * oneTexel * stepPx;
        vec4 sum = texture(DiffuseSampler, clamp(baseUv, lo, hi));
        float wsum = 1.0;
        for (int i = 1; i <= 10; i++) {
            float dist = float(i) * stepPx;
            float w = exp(-0.5 * (dist * dist) / (sigma * sigma));
            sum += texture(DiffuseSampler, clamp(baseUv + step * float(i), lo, hi)) * w;
            sum += texture(DiffuseSampler, clamp(baseUv - step * float(i), lo, hi)) * w;
            wsum += w * 2.0;
        }
        vec3 rgb = sum.rgb / wsum + vec3(0.75, 0.92, 1.0) * glowAdd;
        fragColor = vec4(rgb, 1.0);
        return;
    }

    vec2 dir2 = BlurDir * oneTexel * radius;

    // 采样坐标夹在面板内部，避免把左侧网格/边框的颜色吸进面板边缘。
    vec4 sum = texture(DiffuseSampler, clamp(baseUv, lo, hi)) * W0;
    sum += texture(DiffuseSampler, clamp(baseUv + dir2 * O1, lo, hi)) * W1;
    sum += texture(DiffuseSampler, clamp(baseUv - dir2 * O1, lo, hi)) * W1;
    sum += texture(DiffuseSampler, clamp(baseUv + dir2 * O2, lo, hi)) * W2;
    sum += texture(DiffuseSampler, clamp(baseUv - dir2 * O2, lo, hi)) * W2;

    fragColor = vec4(sum.rgb + vec3(0.75, 0.92, 1.0) * glowAdd, 1.0);
}
