#version 150

// 商店内容区的局部可分离高斯模糊 + 液态玻璃层。
// 只处理 Rect 内部、Hole 外部的像素；Rect 外的像素原样输出。
// Java 侧同时按 Rect 开了 scissor，所以整屏后处理的成本只跟矩形面积成正比。
// Smooth=0：13 点高斯，步长 = σ/2（σ = 2·Radius 像素），无重影，给卡片飞行。
// Smooth=1：步长约 radius/10 的密采样高斯，给描述详情、购物车、快捷菜单和分组切页。
// WaveRadius>0：点击点的液态玻璃波，半径由调用方逐帧放大。0 时与旧路径相同。
// 注意：纯色面板做高斯完全看不出来，波必须带折射位移和高光才有表现。
//
// 玻璃层（GlassTint / GlassParams / GlassEdge，全零等价于旧行为），全部乘 Progress 随模糊淡入：
//   第一趟（H）：靠近 box 边缘的采样向内偏移，做出透镜边的折射；
//   最后一趟（V）：饱和度、色调、提亮、静态颗粒、内缘亮边，以及 Hole 外缘的柔阴影。
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
uniform vec4 GlassTint;        // rgb = 色调，a = 混合量
uniform vec4 GlassParams;      // x = 饱和度增量，y = 颗粒幅度，z = 提亮，w = 亮边强度
uniform vec4 GlassEdge;        // x = 边缘折射 px，y = 亮边宽 px，z = Hole 阴影半径 px，w = Hole 阴影强度
uniform vec4 Rect;   // xy = 左下角 UV，zw = 右上角 UV
uniform vec4 Hole;   // 正在飞行的卡片或浮层矩形，保持清晰
// PostChain 的 float uniform 走 glUniform1fv，所以这里是 8 个矩形 × 4 分量，不是 vec4[8]。
// CellCount=0 时忽略，行为与只有 Rect 的旧路径相同。
uniform float CellRects[32];
uniform int CellCount;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

// 与点击波高光同一个冷白，玻璃的亮边/光晕/色调都往这里靠。
const vec3 GLASS_LIGHT = vec3(0.75, 0.92, 1.0);
// 光从左上来，normalize(-0.65, 0.76)。
const vec2 LIGHT_DIR = vec2(-0.6502, 0.7598);

bool insideBox(vec2 p, vec4 b) {
    return p.x >= b.x && p.x <= b.z && p.y >= b.y && p.y <= b.w;
}

// 距边 d 像素处的权重：贴边为 1，到 w 像素处衰减到 0，二次曲线让边缘折射集中在最外圈。
float edgeFall(float d, float w) {
    float t = clamp(1.0 - d / w, 0.0, 1.0);
    return t * t;
}

// Interleaved gradient noise。按整数像素坐标取值，所以是静态颗粒，不随帧闪烁。
float grainNoise(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

vec3 applyGlass(vec3 rgb, float k, float edgeD, vec2 inward, vec2 uv) {
    if (GlassTint.a <= 0.0 && GlassParams.x <= 0.0 && GlassParams.y <= 0.0
            && GlassParams.z <= 0.0 && GlassParams.w <= 0.0 && GlassEdge.w <= 0.0) {
        return rgb;
    }
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    rgb = mix(vec3(luma), rgb, 1.0 + GlassParams.x * k);
    rgb = mix(rgb, GlassTint.rgb, GlassTint.a * k);
    rgb += GlassParams.z * k;
    rgb += (grainNoise(floor(uv * InSize)) - 0.5) * GlassParams.y * k;

    // 内缘亮边：一条细线加一圈软光晕，朝光的两条边更亮，才像玻璃的边而不是描边。
    if (GlassParams.w > 0.0) {
        float rimW = max(GlassEdge.y, 1.0);
        float inLen = length(inward);
        vec2 outward = inLen > 0.001 ? -inward / inLen : vec2(0.0);
        float lit = 0.25 + 0.75 * (0.5 + 0.5 * dot(outward, LIGHT_DIR));
        float line = 1.0 - smoothstep(0.0, max(rimW * 0.22, 1.0), edgeD);
        float halo = exp(-max(edgeD, 0.0) / rimW);
        rgb += GLASS_LIGHT * (GlassParams.w * k * (line * 0.40 + halo * 0.12) * lit);
    }

    // Hole 外缘：贴边极淡光晕 + 向外衰减的柔阴影，让浮层/飞行卡片像一块浮起来的玻璃板。
    if (GlassEdge.w > 0.0 && Hole.z > Hole.x && Hole.w > Hole.y) {
        vec2 dh = max(max(Hole.xy - uv, uv - Hole.zw), vec2(0.0)) * InSize;
        float hd = length(dh);
        float sp = max(GlassEdge.z, 1.0);
        float shadow = exp(-hd / sp) * GlassEdge.w * k;
        rgb *= 1.0 - shadow * 0.55;
        rgb += GLASS_LIGHT * (GlassParams.w * k * 0.10 * exp(-hd / (sp * 0.3)));
    }
    return clamp(rgb, 0.0, 1.0);
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

    float k = clamp(Progress, 0.0, 1.0);
    float radius = Radius * k;
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
            float lit = 0.55 + 0.45 * dot(dir, LIGHT_DIR);
            glowAdd = WaveGlow * crest * lit;
        }
    }
    // 注意：这里不能只看 radius。波峰处 radius 恰好归零，而折射位移正是在波峰最强，
    // 一旦跟着 radius 提前返回，位移就整段被跳过——纯色面板上就完全看不出来。
    if (!waveActive && radius < 0.5) {
        fragColor = texture(DiffuseSampler, uv);
        return;
    }

    // ── 玻璃边缘：到 box 四边的像素距离，以及指向内侧的合成方向 ──────────────
    float rimW = max(GlassEdge.y, 1.0);
    vec2 toLo = (uv - lo) * InSize;
    vec2 toHi = (hi - uv) * InSize;
    float edgeD = min(min(toLo.x, toLo.y), min(toHi.x, toHi.y));
    vec2 inward = vec2(edgeFall(toLo.x, rimW) - edgeFall(toHi.x, rimW),
                       edgeFall(toLo.y, rimW) - edgeFall(toHi.y, rimW));
    if (firstPass && GlassEdge.x > 0.0) {
        // 透镜边：越靠边越多地采到内侧的内容，边缘看起来被拉伸、弯折。
        baseUv += inward * (GlassEdge.x * k) / InSize;
    }

    vec3 rgb;
    if (radius < 0.5) {
        rgb = texture(DiffuseSampler, clamp(baseUv, lo, hi)).rgb;
    } else if (Smooth > 0.5) {
        // 文字背后要连续的高斯，步长细到 radius/10。
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
        rgb = sum.rgb / wsum;
    } else {
        // σ = 2·radius，步长 = radius = σ/2，13 点覆盖 ±3σ。步长不超过 σ/2，高斯没有可见重影。
        // 采样坐标夹在面板内部，避免把左侧网格/边框的颜色吸进面板边缘。
        vec2 step = BlurDir * oneTexel * radius;
        vec4 sum = texture(DiffuseSampler, clamp(baseUv, lo, hi));
        float wsum = 1.0;
        for (int i = 1; i <= 6; i++) {
            float w = exp(-0.125 * float(i * i));
            sum += texture(DiffuseSampler, clamp(baseUv + step * float(i), lo, hi)) * w;
            sum += texture(DiffuseSampler, clamp(baseUv - step * float(i), lo, hi)) * w;
            wsum += w * 2.0;
        }
        rgb = sum.rgb / wsum;
    }

    rgb += GLASS_LIGHT * glowAdd;
    if (lastPass) {
        rgb = applyGlass(rgb, k, edgeD, inward, uv);
    }
    fragColor = vec4(rgb, 1.0);
}
