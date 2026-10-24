#version 150

// ============================================================================================
// 山海重构 · 黑洞的【真引力透镜】· 一级渲染的片元着色器
//
// 它做什么（三件，全部在相机空间里算）：
//   ① 把【同一帧已经渲染好的背景】（方块 + 天空 + 云）当成一张屏幕空间纹理，
//      按引力偏折角把采样点搬到别处 ⇒ 背景被真的扭曲（不是贴一张旋涡图）。
//   ② 程序化算吸积盘：被弯折后的射线与过黑洞的薄盘求交，用噪声 + 开普勒转速 +
//      多普勒集束（迎面更亮）着色 ⇒ 盘的"背面"会被透镜抬到黑影上方，形成经典弧。
//   ③ 光子环 / 黑影：碰撞参数 b < b_crit（= 3√3/2·Rs）⇒ 光子被吞 ⇒ 纯黑；
//      b ≈ b_crit 的一薄层是绕光子球转了几圈的强光 ⇒ 解析式高斯亮环。
//
// 与 Java 侧的契约（改这里必须同步改 BlackHoleLensMath 并在报告里标注）：
//   * 偏折角多项式 deflectAngle() 与 BlackHoleLensMath.deflectAngle() 逐字同款；
//   * B_CRIT 与 BlackHoleLensMath.B_CRIT 同值 2.5980762（= 3√3/2）；
//   * 所有 uniform 名与 blackhole.json 的 uniforms 列表逐字一致，
//     否则 ShaderInstance 找不到 location ⇒ Java 侧的安全检查会降级到二级。
//   * 边缘淡出常数 EDGE_FADE_INNER 与 BlackHoleLensSpec.EDGE_FADE_INNER 同值，
//     且 Java 侧 BlackHoleShaderProbe 的逐像素复算必须逐行同款（离线出图与游戏同一份）。
//
// 坐标系：像素 = gl_FragCoord（左下原点）；相机空间 = 相机在原点、朝 -z；
//         黑洞位置 uBhView 已经由 Java 减过 cameraPos 并乘过相机旋转。
// ============================================================================================

uniform sampler2D Sampler0;      // 背景：同一帧主渲染目标的一块颜色拷贝（1:1 像素，左下原点）

uniform mat4  uProjMat;          // 事件给的投影矩阵（只用来把"弯折后的方向"投回屏幕坐标）
uniform vec3  uBhView;           // 黑洞中心（相机空间，方块）
uniform vec3  uDiskNormal;       // 吸积盘法线（相机空间；由 Java 侧按固定倾角算）
uniform vec2  uScreenSize;       // 主渲染目标像素尺寸 (w, h)
uniform vec4  uBgRect;           // 背景拷贝区域 x0,y0,w,h（像素，左下原点）
uniform vec2  uBgTexel;          // 1/贴图边长（把像素换算成 uv）
uniform vec2  uHolePx;           // 黑洞中心在屏幕上的像素坐标
uniform float uLensRadiusPx;     // 透镜半径（像素）= 采样区域的半边长
uniform float uRs;               // 史瓦西半径（方块）
uniform float uTanHalfFov;       // tan(fovY/2)，从投影矩阵反解
uniform float uAspect;           // w/h
uniform float uTime;             // 秒（渲染刻 + partialTick）
uniform float uDiskGain;         // 盘亮度总增益
uniform float uRingGain;         // 光子环亮度总增益
uniform float uStrength;         // 偏折强度倍率（/shanhai bh strength；0 = 不弯背景但盘/环还在）

out vec4 fragColor;

// ---- 物理常数（与 Java 侧同值）----
const float B_CRIT      = 2.5980762;   // 临界碰撞参数 / Rs  (= 3*sqrt(3)/2)
const float DISK_INNER  = 3.0;         // 吸积盘内缘 / Rs (= 史瓦西 ISCO = 3 Rs)
const float DISK_OUTER  = 11.0;        // 吸积盘外缘 / Rs

// ---- 数值（不许改太离谱；报告 §3 有推导）----
const float RING_WIDTH  = 0.075;       // 光子环高斯宽度（单位 Rs）
const float RING_GAIN   = 2.40;
const float HALO_WIDTH  = 0.42;
const float HALO_GAIN   = 0.35;

// 边缘淡出：dHole ∈ [EDGE_FADE_INNER, 1.0] 内把 warp / 盘 / 光子环 / 光晕一起衰减到 0。
// 与 BlackHoleLensSpec.EDGE_FADE_INNER 同值。为什么要它：见 §④ 的 maskFade 注释。
const float EDGE_FADE_INNER = 0.86;

// ============================================================================================
// 噪声：便宜的 value noise + 3 抽样 fbm（吸积盘湍流）。不要换成 simplex：
// 这里每像素只跑一次求交，但整块 rect 是逐帧重画的，能省则省。
// ============================================================================================
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    return 0.55 * vnoise(p)
         + 0.30 * vnoise(p * 2.03 + 11.3)
         + 0.15 * vnoise(p * 4.07 + 27.1);
}

// ============================================================================================
// 偏折角（弧度）：α = 2t + 2.2t² + 1.6t³,  t = Rs/b
//   * 首项 2Rs/b = 弱场爱因斯坦偏折（4GM/(c²b)）；
//   * 后两项是近场经验高阶项：真解在 b→b_crit 发散，多项式做不到，所以把奇点交给
//     "b < b_crit 直接画黑"来接住。
//   ⚠️ 这是解析近似偏折律，不是逐像素积分零测地线（报告 §7 已标注）。
// ============================================================================================
float deflectAngle(float bn) {
    float t = 1.0 / max(bn, 0.35);
    return 2.0 * t + 2.2 * t * t + 1.6 * t * t * t;
}

void main() {
    vec2 px = gl_FragCoord.xy;
    vec2 ndc = px / uScreenSize * 2.0 - 1.0;

    // ---------------------------------------------------------------------------------------
    // ① 相机空间射线方向（不靠投影矩阵求逆：标准透视下解析式就是它，报告 §3 有离线交叉验证）
    // ---------------------------------------------------------------------------------------
    vec3 dir = normalize(vec3(ndc.x * uTanHalfFov * uAspect,
                              ndc.y * uTanHalfFov,
                              -1.0));

    // ---------------------------------------------------------------------------------------
    // ② 射线 ↔ 黑洞几何：tca = 最近接近点在线上的参数，b = 碰撞参数
    // ---------------------------------------------------------------------------------------
    vec3  bh  = uBhView;
    float tca = dot(bh, dir);
    float b   = sqrt(max(dot(bh, bh) - tca * tca, 0.0));
    float rs  = max(uRs, 1e-4);
    float bn  = b / rs;

    // ③ 把射线朝黑洞方向弯 α 角：在 (dir, 垂线) 张成的平面里转
    //    α 再乘运行期"偏折强度"（/shanhai bh strength）：0 = 背景不弯（盘/环照旧），1 = 物理弱场，2 = 夸张
    float alpha = deflectAngle(bn) * max(uStrength, 0.0);
    vec3  perp  = bh - dir * tca;
    float perpLen = length(perp);
    vec3  dirBent = dir;
    if (perpLen > 1e-6) {
        dirBent = normalize(dir * cos(alpha) + (perp / perpLen) * sin(alpha));
    }

    // ---------------------------------------------------------------------------------------
    // ④ 背景：把"弯折后的方向"投回屏幕，采样同一帧的颜色拷贝
    //    在 rect 边缘把 warp 收到 0 ⇒ 采样点回到"自己这个像素"⇒ 与已经画好的背景逐像素
    //    相同（无缝），同时保证永远不会采到 rect 之外（那里没有拷贝，采了就是脏数据）。
    // ---------------------------------------------------------------------------------------
    float dHole = length(px - uHolePx) / max(uLensRadiusPx, 1.0);
    float maskRadius = 1.0 - smoothstep(0.70, 1.0, dHole);

    vec2  edgePx   = min(px - uBgRect.xy, uBgRect.xy + uBgRect.zw - px);
    float edgeDist = min(edgePx.x, edgePx.y);
    float maskEdge = smoothstep(0.0, max(4.0, uLensRadiusPx * 0.10), edgeDist);

    // 🔴 边缘淡出（2026-10-25，用户「左右有明显的分割线」）：
    //    改前 warp 有两道掩码（maskRadius/maskEdge）⇒ 边界处已经是 0，
    //    但 **盘 / 光子环 / 光晕完全没有掩码**（下面 col += diskCol; col += ringCol; 直接加），
    //    只要矩形边界切到盘或环，就是一条硬边。像素预算一收矩形（近距常见）就切在 6~7 Rs 处。
    //    ⇒ 现在用同一个 fade 乘【全部四项】，dHole 按"到矩形中心的最大半径"归一化，四边一致，
    //      最后 14%（0.86..1.0）用 smoothstep 淡出。
    float maskFade = 1.0 - smoothstep(EDGE_FADE_INNER, 1.0, dHole);
    float warp     = maskRadius * maskEdge * maskFade;

    vec2 uvSelf = (px - uBgRect.xy) * uBgTexel;
    vec2 uvBent = uvSelf;
    vec4 clipB  = uProjMat * vec4(dirBent, 0.0);
    if (clipB.w > 1e-4) {
        vec2 bentPx = (clipB.xy / clipB.w * 0.5 + 0.5) * uScreenSize;
        uvBent = (bentPx - uBgRect.xy) * uBgTexel;
    }
    vec2 uvMax  = uBgRect.zw * uBgTexel;
    vec2 uvBentC = clamp(uvBent, vec2(0.0), uvMax);
    vec2 uv     = clamp(mix(uvSelf, uvBentC, warp), vec2(0.0), uvMax);
    vec3 bg     = texture(Sampler0, uv).rgb;

    // ---------------------------------------------------------------------------------------
    // ⑤ 吸积盘：弯折后的射线与"过黑洞、法线 uDiskNormal"的薄盘求交（解析解，一次求交，零步进）
    // ---------------------------------------------------------------------------------------
    vec3  n      = normalize(uDiskNormal);
    vec3  basisX = normalize(cross(n, abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3  basisZ = cross(n, basisX);

    vec3  diskCol = vec3(0.0);
    float diskLum = 0.0;
    float denom   = dot(dirBent, n);
    if (abs(denom) > 1e-4) {
        float tHit = dot(bh, n) / denom;
        if (tHit > 0.0) {
            vec3  hit = dirBent * tHit;
            vec3  rel = hit - bh;
            float rn  = length(rel) / rs;
            if (rn > DISK_INNER && rn < DISK_OUTER) {
                // 内/外缘软过渡
                float band = smoothstep(DISK_INNER, DISK_INNER * 1.12, rn)
                           * (1.0 - smoothstep(DISK_OUTER * 0.72, DISK_OUTER, rn));

                // 开普勒湍流：方位角 + 半径，按 ω ∝ r^-1.5 平移 ⇒ 内圈转得快（真吸积盘的样子）
                float ang   = atan(dot(rel, basisZ), dot(rel, basisX));
                float spin  = uTime * 2.6 / pow(max(rn, 1.0), 1.5);
                float turb  = fbm(vec2(rn * 1.35, ang * 2.6 + spin));
                float shade = band * (0.35 + 1.30 * turb);

                // 多普勒集束：盘沿 +φ 方向转，速度朝相机的一侧更亮（1/(1-βcosθ)³）
                vec3  velDir = normalize(cross(n, rel));
                float beta   = clamp(0.62 * inversesqrt(max(rn, 1.0)), 0.0, 0.80);
                float cosT   = dot(velDir, -dirBent);
                float beam   = 1.0 / pow(max(0.35, 1.0 - beta * cosT), 3.0);

                // 温度梯度：内白热 → 中橙 → 外暗红
                vec3 cHot = vec3(1.00, 0.97, 0.92);
                vec3 cMid = vec3(1.00, 0.62, 0.24);
                vec3 cLow = vec3(0.62, 0.13, 0.05);
                vec3 dc   = mix(cHot, cMid, smoothstep(DISK_INNER * 1.05, DISK_INNER * 3.0, rn));
                dc        = mix(dc,   cLow, smoothstep(DISK_INNER * 3.0,  DISK_OUTER,      rn));

                diskCol = dc * (shade * beam) * max(uDiskGain, 0.0);
                diskLum = clamp(shade * 0.85, 0.0, 1.0);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // ⑥ 黑影 + 光子环
    //    b < b_crit ⇒ 光子逃不出来 ⇒ 背景被完全遮住；
    //    b ≈ b_crit 的一薄层是"绕光子球转了几圈、最后擦边逃出来"的光 ⇒ 强亮环（解析高斯）。
    // ---------------------------------------------------------------------------------------
    float aa         = max(fwidth(bn), 1e-4) * 1.25;
    float shadowMask = 1.0 - smoothstep(B_CRIT - aa, B_CRIT + aa, bn);

    float ringCore = exp(-pow((bn - B_CRIT) / RING_WIDTH, 2.0));
    float ringHalo = exp(-pow((bn - B_CRIT) / HALO_WIDTH, 2.0)) * HALO_GAIN;
    vec3  ringCol  = vec3(1.00, 0.88, 0.72) * ((ringCore * RING_GAIN + ringHalo) * max(uRingGain, 0.0));

    // ---------------------------------------------------------------------------------------
    // ⑦ 合成：背景 → 被黑影吃掉 → 盘与环叠在上面（盘/环在黑洞前面，应该压在黑影上）
    // ---------------------------------------------------------------------------------------
    vec3 col = mix(bg, vec3(0.0), shadowMask);
    // 盘 / 环 / 光晕一起乘边缘淡出（改前是直接加 ⇒ 矩形边界处一条硬边）
    col += diskCol * maskFade;
    col += ringCol * maskFade;

    fragColor = vec4(col, 1.0);
}
