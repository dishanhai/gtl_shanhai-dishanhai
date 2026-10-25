#version 150

// ============================================================================================
// 山海重构 · 黑洞【洞内往外看】· 全屏径向重投影（洞内路径的唯一绘制件）
//
// 🔴 v3（第三版）：**没有任何窗口边界**。
//   v2 保留了"圆形窗口 + 窗外纯黑"的构图，被打回为"一块玻璃球浮在纯黑上，比贴图更像摆件"。
//   真实坠落里不存在一个圆窗：整个视野都在扭曲里、被向外拉到屏幕边缘。
//   ⇒ 本版：世界铺满整屏、往中心压；"窗口感"只由【亮度】表达（环峰 → 环外指数衰减、四角不黑）。
//
// 坐标与映射：
//   p       = (gl_FragCoord − 屏幕中心) / 源圆半径
//   cornerR = hypot(屏宽/2, 屏高/2) / 源圆半径            （p 空间里"四角"的半径）
//   tN      = |p| / cornerR · (1 + breath)                0 = 中心、1 = 四角（不夹）
//   srcR    = SRC_RADIUS · tN^gamma                       gamma<1 ⇒ 径向被拉开、越靠边越长
//   亮环半径 rt = clamp(windowR / cornerR, 0.02, 0.98)    只影响亮度，不构成边界
//
// 「在坠落」的五条（对应打回意见）：
//   ① 无硬边：屏幕任何位置都采样（四角也有画面），没有"窗外=0"的掩码；
//   ② 拖影到屏幕边缘：每像素沿源半径取 TAPS 个样本，长度随 tN² 增长；屏幕边缘最长；
//   ③ 亮度造"窗口"：环内渐亮 → 环峰（RIM_BOOST）→ 环外 exp(-(tN-rt)·OUTER_FADE)；亮环是采样结果的亮度剖面；
//   ④ 动：条纹沿半径向外流动（相位随 uTime 前进）+ 整屏径向呼吸（两频叠加 = 拍频）；
//   ⑤ 暗部结构：最后做 tone mapping（pow 抬暗部 + Reinhard 肩部）。
//
// ⚠️ 数值约定：可调参数全部由 Java 以 uniform 传入（uP0..uP4，来自 BlackHoleInsideSpec），
//   这里只留"算式形状常数"，与 BlackHoleInsideSpec 的同名字段逐字同名同值，
//   temp/bh-inside/InsideSpecCheck.java 解析本文件逐条比对（对不上就红）。
// ⚠️ 算式有两份：本文件（GLSL，游戏里跑）与 BlackHoleInsideSpec.shade（Java，离线出图），
//   下面 ①②③ 段落与 Java 的编号一一对应，改一份必须改另一份。
// ============================================================================================

uniform sampler2D Sampler0;      // 本帧主渲染目标的颜色拷贝（1:1 像素，左下原点）
// 🔴 这里【不许】再声明 uScreenSize。
//   2026-10-25 实机事故的根因：它上一版声明了却<b>一次都没在 main() 里用过</b>
//   ⇒ GLSL 编译器按规范把它优化掉 ⇒ glGetUniformLocation 返回 -1
//   ⇒ MC 的 ShaderInstance 只把"location ≠ -1"的 uniform 放进 uniformMap
//   （字节码证据：ShaderInstance.m_173366_ 的 199..258，location==-1 时只 warn 不 put）
//   ⇒ 我们那边 sh.getUniform("uScreenSize") == null
//   ⇒ 旧代码把它当"着色器坏了"⇒ close + 整条洞内永不绘制（屏幕上什么都没有）。
//   ⇒ 结论：**fsh 里声明的每个 uniform 都必须真的被用到**，否则它取不回来。
uniform vec2  uCenterPx;         // 屏幕中心（像素，左下原点）
uniform float uSrcHalfPx;        // 源圆半径（像素）= 拷贝正方形边长的一半
uniform vec4  uBgRect;           // 拷贝区域 x0,y0,w,h（像素，左下原点）
uniform vec2  uBgTexel;          // 1/贴图边长
uniform vec2  uBandNrm;          // 盘面迹线的单位法线（p 空间）
uniform float uBandOff;          // 迹线到屏幕中心的距离（p 空间，有符号）
uniform float uTime;             // 秒（渲染刻 + partialTick）
uniform float uSolid;            // 0 = 正常采样（默认）；1 = 直接输出纯亮绿（/shanhai bh insidedebug solid）

uniform vec4  uP0;               // (windowR, streak, disp, bandGain)
uniform vec4  uP1;               // (gamma, srcR, rimBoost, rimPow)
uniform vec4  uP2;               // (edgeWarp, flowK, flowAmp, flowSpeed)
uniform vec4  uP3;               // (bandSigma, bandStreak, bandDisp, gain)
uniform vec4  uP4;               // (dimmer, bandWarmA, bandWarmB, cornerR)

out vec4 fragColor;

// ---- 算式形状常数（与 BlackHoleInsideSpec 同名字段逐字同值；不要在这里调参）----
const int   TAPS                = 10;


const float DISP_POW            = 1.35;
const float STREAK_SRC_MAX      = 0.11;
const float STREAK_LINE_MAX     = 0.90;
const float SHAPE_STREAK_INNER  = 0.25;
const float SHAPE_STREAK_OUTER  = 0.75;
const float SHAPE_STREAK_MAX    = 0.45;
const float EDGE_WARP_POW       = 2.4;
const float EDGE_PHASE_RADIAL   = 7.0;
const float EDGE_PHASE_TIME     = 0.9;
const float EDGE_PHASE_SRC      = 3.0;
const float BREATH_AMP          = 0.060;
const float BREATH_SPEED_A      = 0.55;
const float BREATH_SPEED_B      = 0.23;
const float BREATH_MIX_A        = 0.60;
const float WIN_DIM             = 0.35;


const float OUTER_FADE          = 5.00;
const float WIN_FLOOR           = 0.16;
const float GRADE_MIX           = 0.34;
const float TINT_WARM_R         = 1.14;
const float TINT_WARM_G         = 0.97;
const float TINT_WARM_B         = 0.80;
const float TINT_COOL_R         = 0.82;
const float TINT_COOL_G         = 0.96;
const float TINT_COOL_B         = 1.22;
const float TINT_RAMP_IN        = 0.08;
const float TINT_RAMP_OUT       = 0.95;
const float BAND_WARM_R         = 1.55;
const float BAND_WARM_G         = 1.05;
const float BAND_WARM_B         = 0.50;
const float BAND_RIM_FADE       = 0.35;
const float BAND_RIM_FADE_IN    = 1.10;
const float LUMA_R              = 0.2126;
const float LUMA_G              = 0.7152;
const float LUMA_B              = 0.0722;
const float TONE_LIFT           = 1.90;
const float TONE_SHADOW         = 0.10;
const float TONE_GAIN           = 1.00;

// 与 GL 的 texture() 语义对齐：线性过滤 + clamp-to-edge + 夹在拷贝矩形内。
vec3 samplePx(vec2 px) {
    vec2 uv = (px - uBgRect.xy) * uBgTexel;
    uv = clamp(uv, vec2(0.0), uBgRect.zw * uBgTexel);
    return texture(Sampler0, uv).rgb;
}

void main() {
    // ---- ⓪ 诊断隔离位（任务书第 2 条）：/shanhai bh insidedebug solid ----
    //   不采样、不看任何参数，直接一整屏纯亮绿 ⇒ 能区分「四边形没提交/被别的 pass 覆盖」
    //   与「采样到全黑」。默认 uSolid = 0（= 正常采样，行为与改前完全一致）。
    if (uSolid > 0.5) {
        fragColor = vec4(0.0, 1.0, 0.0, 1.0);
        return;
    }
    // ---- ① p 坐标 → tN（0 = 屏幕中心，1 = 屏幕四角；不夹、没有"窗外"）----
    vec2  p    = (gl_FragCoord.xy - uCenterPx) / uSrcHalfPx;
    float rad0 = length(p);
    float ang  = atan(p.y, p.x);
    float breath = BREATH_AMP * (BREATH_MIX_A * sin(uTime * BREATH_SPEED_A)
                               + (1.0 - BREATH_MIX_A) * sin(uTime * BREATH_SPEED_B));
    float tN   = rad0 / max(uP4.w, 1e-6) * (1.0 + breath);
    float rt   = clamp(uP0.x / max(uP4.w, 1e-6), 0.02, 0.98);

    // ---- ② 赤道带权重（盘面迹线的高斯带；乘在【采样结果】上）----
    float bandW = 0.0;
    if (uP0.w > 0.0 && uP3.x > 1e-6) {
        float bd = dot(p, uBandNrm) - uBandOff;
        bandW = uP0.w * exp(-(bd / uP3.x) * (bd / uP3.x));
    }

    // ---- ③ 源半径、色散、拖影长度（都以【源半径】为单位）----
    float srcR  = uP1.y * pow(max(tN, 1e-5), uP1.x);
    float dispR = uP0.z * (1.0 + uP3.z * bandW) * pow(clamp(tN, 0.0, 1.0), DISP_POW);
    float shape = SHAPE_STREAK_INNER + SHAPE_STREAK_OUTER * tN * tN;
    float sh    = min(STREAK_SRC_MAX * uP0.y * shape * (1.0 + uP3.y * bandW), SHAPE_STREAK_MAX);

    // ---- ④ 沿【源半径】取 TAPS 个样本：同时累计【平均】与【最大】----
    //   平均 = 拖影，最大 = 把沿半径最亮的样本拉出来（亮点 → 放射状条纹）。
    vec3  acc    = vec3(0.0);
    vec3  accMax = vec3(0.0);
    // 屏幕边缘的强扭曲：每像素只算一次（否则同一次拖影的样本落在不同方位角 ⇒ 条纹被卷成弧）
    float warpAng = ang + uP2.x * pow(max(tN, 0.0), EDGE_WARP_POW)
                  * sin(tN * EDGE_PHASE_RADIAL - uTime * EDGE_PHASE_TIME + srcR * EDGE_PHASE_SRC);
    vec2  dir2    = vec2(cos(warpAng), sin(warpAng));
    for (int i = 0; i < TAPS; i++) {
        float fi   = (float(i) + 0.5) / float(TAPS);
        float tapR = srcR * (1.0 - sh * fi);
        // 向外流动：位移按局部源半径成比例（永不为负）+ 相位随时间前进 ⇒ 特征沿半径向外漂
        float rr   = tapR * (1.0 + uP2.z * sin(tapR * uP2.y - uTime * uP2.w));
        // 三通道色散：R 外移、G 居中、B 内移（每通道一次采样 ⇒ 与 Java 取样次数相同）
        float scr = samplePx(uCenterPx + dir2 * ((rr + dispR) * uSrcHalfPx)).r;
        float scg = samplePx(uCenterPx + dir2 * ( rr          * uSrcHalfPx)).g;
        float scb = samplePx(uCenterPx + dir2 * ((rr - dispR) * uSrcHalfPx)).b;
        acc    += vec3(scr, scg, scb);
        accMax  = max(accMax, vec3(scr, scg, scb));
    }
    acc /= float(TAPS);
    float lineMix = clamp(STREAK_LINE_MAX * uP0.y * clamp(tN, 0.0, 1.0), 0.0, 1.0);
    acc = mix(acc, accMax, lineMix);

    // ---- ⑤ 红移/蓝移分级：中心暖、边缘冷 ----
    float lum     = LUMA_R * acc.r + LUMA_G * acc.g + LUMA_B * acc.b;
    float edgeMix = smoothstep(TINT_RAMP_IN, TINT_RAMP_OUT, clamp(tN, 0.0, 1.0));
    vec3  tint    = vec3(mix(TINT_WARM_R, TINT_COOL_R, edgeMix),
                         mix(TINT_WARM_G, TINT_COOL_G, edgeMix),
                         mix(TINT_WARM_B, TINT_COOL_B, edgeMix));
    vec3  col     = mix(acc, vec3(lum) * tint, GRADE_MIX);

    // ---- ⑥ 金带：乘性暖色分级（黑的地方仍是黑 ⇒ 不是纯色多边形）----
    float bandGrade = clamp(bandW * (1.0 - BAND_RIM_FADE
                        * smoothstep(rt * BAND_RIM_FADE_IN, rt * BAND_RIM_FADE_IN + 0.45, clamp(tN, 0.0, 1.0))),
                        0.0, 1.0);
    float warmMul   = uP4.y + uP4.z * lum;
    col.r = mix(col.r, col.r * BAND_WARM_R * warmMul, bandGrade);
    col.g = mix(col.g, col.g * BAND_WARM_G * warmMul, bandGrade);
    col.b = mix(col.b, col.b * BAND_WARM_B * warmMul, bandGrade);

    // ---- ⑦ 亮度剖面：环内渐亮 → 环峰 → 环外指数衰减到四角（没有掩码，四角不是黑）----
    float peak = WIN_DIM + uP1.z;
    float profile = (tN <= rt)
        ? WIN_DIM + uP1.z * pow(clamp(tN / max(rt, 1e-3), 0.0, 1.0), uP1.w)
        : peak * exp(-(tN - rt) * OUTER_FADE);
    // 全屏下限：保证四角也有画面（不是"窗外=黑"）
    float windowGain = max(profile, WIN_FLOOR);
    float k = windowGain * uP4.x * uP3.w;
    col *= k;

    // ---- ⑧ tone mapping：只抬暗部（y = x·(1 + LIFT·exp(−x/SHADOW))·GAIN），高光原样 ----
    //   用 pow 整体提亮会把中间调顶上去 ⇒ 整屏发白发糊（v3 第一版实测"蓝色等离子隧道"）。
    vec3 x = max(col, vec3(0.0));
    vec3 tm = x * (vec3(1.0) + vec3(TONE_LIFT) * exp(-x / vec3(TONE_SHADOW))) * TONE_GAIN;
    fragColor = vec4(max(tm, vec3(0.0)), 1.0);
}
