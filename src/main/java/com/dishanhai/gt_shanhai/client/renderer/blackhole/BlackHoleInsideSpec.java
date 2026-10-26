package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/**
 * 山海重构 · 黑洞「洞内往外看」的<b>唯一参数与逐像素算式</b>（纯类：零 MC / 零 GL / 零 Forge / 零 JOML）。
 *
 * <h2>0. 两版打回之后，这一版到底是什么</h2>
 * <ul>
 *   <li><b>v1</b>（已被删）：5 层几何图元（暗穹顶 / 亮环 / 金带 / 中心光 / 视界外黑），颜色是常量 ⇒ 用户：
 *       「<i>一个贴图了事</i>」；</li>
 *   <li><b>v2</b>（也被打回）：真采样背景，但保留了「<b>圆形窗口 + 窗外纯黑</b>」的构图 ⇒ 用户：
 *       「<i>一块玻璃球浮在纯黑上，反而更像贴图/摆件</i>」。病根是<b>那条硬边圆轮廓</b>：真实的坠落里
 *       根本不存在一个圆窗，整个视野都在扭曲里、被向外拉到屏幕边缘。</li>
 * </ul>
 * ⇒ 本版（v3）：<b>没有任何窗口边界</b>。
 * <ol>
 *   <li><b>全屏径向压缩</b>：把屏幕归一化到 {@code tN ∈ [0,1]}（1 = 屏幕<b>四角</b>），
 *       世界按 {@code 源半径 = SRC_RADIUS·tN^GAMMA} 铺满整屏、往中心压；{@code GAMMA < 1}
 *       使径向被拉开、越靠屏幕边缘拉伸越强 ⇒ <b>四角也有画面</b>，不存在"窗外"；</li>
 *   <li><b>拖影一路走到屏幕边缘</b>：每像素沿源半径取 {@value #TAPS} 个样本，
 *       长度随 {@code tN²} 增长（屏幕边缘最长），并在 {@code |p|} 上一直采到 {@code tN=1}；</li>
 *   <li><b>"窗口感"只用亮度表达</b>：{@code WIN_DIM + RIM_BOOST·t^RIM_POW} 在 <b>tN = 亮环半径</b>
 *       处见峰，之后按 {@code OUTER_FADE} 指数衰减到四角（四角仍有峰值亮度的 14%~27%，不是黑）；
 *       亮环本身是<b>采样结果的亮度剖面</b>，不是画出来的圆环；</li>
 *   <li><b>动</b>：条纹沿半径<b>向外</b>流动（相位随时间前进，位移按局部源半径成比例，永不为负），
 *       外加<b>整屏径向呼吸</b>（两个不同频率叠加 = 拍频/潮汐感）；</li>
 *   <li><b>暗部有结构</b>：最后一道是 <b>tone mapping</b>（{@code pow} 抬暗部 + Reinhard 型肩部），
 *       让地面的方块/格线在拖影里仍认得出。</li>
 * </ol>
 *
 * <h2>1. 坐标与映射（唯一口径）</h2>
 * <pre>
 *   p      = (fragCoord − 屏幕中心) / srcHalfPx        源圆半径 = min(屏宽,屏高) 的一半
 *   cornerR= hypot(屏宽/2, 屏高/2) / srcHalfPx         p 空间里"屏幕四角"的半径
 *   tN     = |p| / cornerR · (1 + breath(t))           0 = 屏幕中心、1 = 四角；breath = 呼吸
 *   srcR   = SRC_RADIUS · tN^GAMMA                     源半径（单位 = 源圆半径）
 *   亮环半径 rt = clamp(windowR / cornerR, 0.02, 0.98)  windowR 随进洞加深 0.92 → 0.15（屏幕半高单位）
 * </pre>
 * <b>为什么 {@code GAMMA < 1}</b>：{@code screenR ∝ tN ∝ srcR^(1/GAMMA)}，径向局部尺度
 * {@code d(screenR)/d(srcR)} 与切向尺度 {@code screenR/srcR} 的比值 = {@code 1/GAMMA}。
 * {@code GAMMA > 1} ⇒ 径向被压扁 ⇒ 星点变<b>切向弧</b>、整张图只能糊（v2 前几轮实测就是这个）；
 * {@code GAMMA < 1} ⇒ 径向被<b>拉开</b> ⇒ 点状光源被拉成<b>放射状条纹</b>。本版 0.62（径向拉开 ≈1.61 倍）。
 *
 * <h2>2. 物理口径（红移 / 蓝移的方向与理由）</h2>
 * 观测者 = <b>从无穷远自由下落</b>，{@code v = √(rs/r)}：
 * <pre>
 *   ν_obs / ν_∞ = 1 / (1 + v)      ⇒   1 + z = 1 + √(rs/r)
 *   （推导：f=1−rs/r，k^t=E/f, k^r=−E，u^t=1/f, u^r=−√(rs/r)
 *     ⇒ ν = −g_{μν}k^μu^ν = (E/f)(1−√(rs/r)) = E/(1+√(rs/r))）
 * </pre>
 * ⇒ 自由下落的观测者看到外面宇宙<b>整体偏红</b>（视界处 ×2，越深越红）——
 * 「挂着不动的观测者看到极端蓝移」那条不是我们的口径。
 * 于是：<b>中心偏红</b>（暖色分级 + 按 {@code 1/(1+0.55(z−1))} 变暗）、
 * <b>边缘偏蓝</b>（相对项：边缘的光是贴着光子球掠射的那一支，b→b_crit，势井里走得最深 ⇒ 引力蓝移份额最大）。
 * ⚠️ 没有逐像素积分零测地线，"边缘相对更蓝"是一阶近似。
 *
 * <h2>3. 与着色器的契约（{@code blackhole_inside.fsh}）</h2>
 * 可调参数全部走 uniform（{@code uP0..uP4}），其余是"形状常数"，与 fsh 的 {@code const} <b>逐字同名同值</b>；
 * {@code temp/bh-inside/InsideSpecCheck.java} 解析 fsh 文本逐条比对，并把 uniform 名与 {@link #UNIFORMS}
 * 做集合比对、检查渲染器是否真的设置了每一个 ⇒ "改了着色器忘了改 Java"有机器判据。
 * <p>算式有两份：GLSL（游戏里跑）与 {@link #shade}（离线出图与日志用），逐行对应。
 */
public final class BlackHoleInsideSpec {

    private BlackHoleInsideSpec() {}

    // ==========================================================================================
    //  ① 可调参数的默认值（运行期可被 BlackHoleTuning 覆盖：windowMul / streakOverride）
    // ==========================================================================================

    /**
     * 亮环半径，{@code depth01 = 0}（刚跨进"洞内"判据的边界，dist = 8.31 格）：{@code 0.92}（屏幕半高为 1）。
     * <p>⚠️ v3 起它<b>不是</b>"窗口边界"：屏幕任何位置都有画面，它只决定<b>最亮那一圈</b>落在哪。
     */
    public static final double WINDOW_R_AT_EDGE = 0.92D;

    /** 亮环半径，{@code depth01 = 1}（球心）：{@code 0.15} ⇒ 最亮的一圈缩到中心附近。 */
    public static final double WINDOW_R_AT_CENTER = 0.15D;

    /** 亮环半径随深度的曲线指数（&lt;1 ⇒ 前段收得快，四格读数分布更均匀）。 */
    public static final double WINDOW_R_POW = 0.85D;

    /** 拖影强度（用户可见的 0..1 刻度）：{@code depth01 = 0} 时的基准值。 */
    public static final double STREAK_BASE = 0.30D;

    /** 拖影强度随深度增加的量（{@code depth01 = 1} 时 = 0.85）。 */
    public static final double STREAK_DEPTH_GAIN = 0.55D;

    /** 色散强度（单位 = <b>源半径</b>）：{@code depth01 = 0} 时的基准值。 */
    public static final double DISP_BASE = 0.008D;

    /** 色散随深度增加的量（{@code depth01 = 1} 时 = 0.020 个源半径）。 */
    public static final double DISP_DEPTH_GAIN = 0.022D;

    /** 整屏亮度总增益（tone mapping 之后、最后一道乘子）。 */
    public static final double GAIN = 1.00D;

    /** 金带（赤道方向）总权重：0 = 完全没有金带；1 = 最浓。 */
    public static final double BAND_GAIN = 0.75D;

    // ==========================================================================================
    //  ② 算式内的形状常数（与 fsh 里的 const 同名同值；InsideSpecCheck 会逐条比对）
    // ==========================================================================================

    /** 源圆半径（tN=1 时采到的源半径；1.0 = 拷贝正方形的内接圆，永不越界）。 */
    public static final double SRC_RADIUS = 1.0D;

    /** 径向映射指数（&lt;1 ⇒ 径向被拉开 ⇒ 放射状条纹；见类注释 §1）。 */
    public static final double GAMMA = 0.62D;

    /** 色散随半径增长的指数（1 = 线性；1.35 ⇒ 中心干净、边缘彩虹）。 */
    public static final double DISP_POW = 1.35D;

    /** 拖影长度上限（单位 = 源半径）：实际 = {@code STREAK_SRC_MAX·streak·形状}。 */
    public static final double STREAK_SRC_MAX = 0.11D;

    /** "沿半径取最大"的混合上限：实际 = {@code STREAK_LINE_MAX·streak·tN²}。 */
    public static final double STREAK_LINE_MAX = 0.90D;

    /** 拖影长度的径向形状：{@code 0.25 + 0.75·tN²}（中心短、屏幕边缘最长）。 */
    public static final double SHAPE_STREAK_INNER = 0.25D;
    /** 同上（tN² 的系数）。 */
    public static final double SHAPE_STREAK_OUTER = 0.75D;
    /** 拖影长度占比的硬上限（防止样本半径变负 ⇒ 采到自己背后）。 */
    public static final double SHAPE_STREAK_MAX = 0.45D;

    /** 屏幕边缘的强扭曲：角度偏移幅度（弧度）。 */
    public static final double EDGE_WARP = 0.34D;
    /** 边缘扭曲的径向幂（2.4 ⇒ 靠外的部分越来越明显）。 */
    public static final double EDGE_WARP_POW = 2.4D;
    /** 边缘扭曲相位：沿源半径的空间频率。 */
    public static final double EDGE_PHASE_RADIAL = 7.0D;
    /** 边缘扭曲相位：时间速度。 */
    public static final double EDGE_PHASE_TIME = 0.9D;
    /** 边缘扭曲相位：随源半径的偏移。 */
    public static final double EDGE_PHASE_SRC = 3.0D;

    /**
     * 向外流动：周期位移幅度，<b>相对局部源半径</b>（{@code flow = FLOW_AMP·sin(...)}，乘在 tapR 上）。
     *
     * <p>为什么按 tapR 成比例：位移加在<b>源半径</b>上，靠近中心的 tapR 很小，
     * 固定位移会让半径变负（= 采到自己背后，出现镜像伪影）。
     */
    public static final double FLOW_AMP = 0.080D;
    /** 向外流动：源半径方向的空间频率。 */
    public static final double FLOW_K = 9.0D;
    /** 向外流动：相位速度（正号 = 向外；越大漂得越快）。 */
    public static final double FLOW_SPEED = 1.60D;

    /** 整屏径向呼吸幅度（±6% 的径向缩放 ⇒ 一眼能看出"在呼吸/潮汐"）。 */
    public static final double BREATH_AMP = 0.060D;
    /** 呼吸频率 A（rad/s）。 */
    public static final double BREATH_SPEED_A = 0.55D;
    /** 呼吸频率 B（rad/s）：与 A 叠成拍频。 */
    public static final double BREATH_SPEED_B = 0.23D;
    /** 呼吸里 A 的权重（B 的权重 = 1 − 它）。 */
    public static final double BREATH_MIX_A = 0.60D;

    /**
     * 亮环<b>以内</b>的亮度（1.0 = 原亮度）：环内到中心逐渐压到它。
     * <p>这是"窗口感"的<b>唯一</b>来源（v3 没有任何几何边界）：环内亮 → 环峰最亮 → 环外指数衰减。
     */
    public static final double WIN_DIM = 0.35D;

    /** 亮环处的亮度增益（峰值 = WIN_DIM + 它）。 */
    public static final double RIM_BOOST = 1.40D;

    /** 环内由中心到环峰的亮度幂（越大 ⇒ 中心越暗、越"亮成一圈"）。 */
    public static final double RIM_POW = 2.5D;

    /**
     * 环外到屏幕四角的<b>指数衰减率</b>：{@code k = 峰值·exp(−(tN−rt)·OUTER_FADE)}。
     *
     * <p>2.2 ⇒ 四角仍有峰值亮度的 {@code exp(−(1−rt)·2.2)}：浅洞（rt≈0.40）≈ 27%、深洞（rt≈0.10）≈ 14%。
     * <p>🔴 这是 v3 与 v2 的分界：<b>不再乘一个"窗外=0"的掩码</b>，四角不是黑。
     */
    public static final double OUTER_FADE = 5.00D;

    /**
     * 全屏亮度<b>下限</b>：任何像素的亮度乘子不低于它。
     *
     * <p>为什么必须有：打回意见第 1 条要求"<b>屏幕四角必须也有画面</b>"。只靠指数衰减，
     * 深洞那几格的四角会掉到 0.3%（= 视觉上的纯黑，等于又变回了"圆窗外面是黑"）。
     * 0.10 ⇒ 四角落到 10%~15%（看得见、但明显暗）⇒ <b>没有任何"边界"可言</b>。
     */
    public static final double WIN_FLOOR = 0.16D;

    /** 红移分级：色调混合比例（0 = 原色，1 = 全按色调重着色）。 */
    public static final double GRADE_MIX = 0.34D;

    /** 中心色调（偏红）：红移口径的视觉落点。 */
    public static final double TINT_WARM_R = 1.14D;
    public static final double TINT_WARM_G = 0.97D;
    public static final double TINT_WARM_B = 0.80D;

    /** 边缘色调（偏蓝）：掠射光在势井里走得最深 ⇒ 引力蓝移份额最大。 */
    public static final double TINT_COOL_R = 0.82D;
    public static final double TINT_COOL_G = 0.96D;
    public static final double TINT_COOL_B = 1.22D;

    /** 色调从"中心暖"过渡到"边缘冷"的半径区间（tN）。 */
    public static final double TINT_RAMP_IN = 0.08D;
    public static final double TINT_RAMP_OUT = 0.95D;

    /** 红移变暗的强度：{@code dimmer = 1/(1+REDSHIFT_DIM·(z−1))}。 */
    public static final double REDSHIFT_DIM = 0.55D;

    /** 金带的屏幕高斯宽度（p 空间）：{@code depth01=0} 时的值。 */
    public static final double BAND_SIGMA_BASE = 0.070D;
    /** 金带宽度随深度增加的量。 */
    public static final double BAND_SIGMA_DEPTH = 0.260D;

    /** 金带里额外加的拖影倍数。 */
    public static final double BAND_STREAK_GAIN = 1.10D;
    /** 金带里额外加的色散倍数。 */
    public static final double BAND_DISP_GAIN = 1.60D;

    /** 金带的暖色分级（乘在<b>采样结果</b>上 ⇒ 背景黑的地方仍是黑，不是一条纯色多边形）。 */
    public static final double BAND_WARM_R = 1.55D;
    public static final double BAND_WARM_G = 1.05D;
    public static final double BAND_WARM_B = 0.50D;
    /** 暖色分级的亮度权重：{@code A + B·亮度}。 */
    public static final double BAND_WARM_A = 0.55D;
    /** 同上（亮度项的系数）。 */
    public static final double BAND_WARM_GAIN_B = 1.10D;
    /** 金带分级靠近亮环外沿时衰减的比例（保住边缘那圈蓝）。 */
    public static final double BAND_RIM_FADE = 0.35D;
    /** 金带分级从亮环半径的几倍处开始衰减。 */
    public static final double BAND_RIM_FADE_IN = 1.10D;

    /** 亮度权重（Rec.709）。 */
    public static final double LUMA_R = 0.2126D;
    public static final double LUMA_G = 0.7152D;
    public static final double LUMA_B = 0.0722D;

    /** tone mapping：{@code pow} 指数（&lt;1 ⇒ 抬暗部；0.70 ⇒ 0.05 抬到 ≈0.12）。 */
    public static final double TONE_LIFT = 1.90D;
    /** tone mapping：Reinhard 型肩部（防高光爆白）。 */
    public static final double TONE_SHADOW = 0.10D;
    /** tone mapping 之后的增益。 */
    public static final double TONE_GAIN = 1.00D;

    /** 每个像素沿半径取的样本数。改这里必须同步改 fsh 里的 {@code TAPS}。 */
    public static final int TAPS = 10;

    /**
     * 取不到投影矩阵时用的 {@code tan(fovY/2)}：{@code tan(70°/2)=0.7002}（原版默认视野角）。
     * <p>只影响"金带那条迹线画在哪"，不参与除法，也不是闸门。
     */
    public static final double DEFAULT_TAN_HALF_FOV = 0.70D;

    // ==========================================================================================
    //  ③ 运行期夹取范围（BlackHoleTuning 的 setter 与命令参数共用）
    // ==========================================================================================

    /** 亮环半径倍率下限。 */
    public static final double WINDOW_MUL_MIN = 0.25D;
    /** 亮环半径倍率上限。 */
    public static final double WINDOW_MUL_MAX = 1.60D;
    /** 亮环半径（乘完倍率之后）的硬下限/上限。 */
    public static final double WINDOW_R_MIN = 0.06D;
    public static final double WINDOW_R_MAX = 1.30D;

    // ==========================================================================================
    //  ④ 与着色器的接口：uniform 名（改 fsh 必须同步改这里，InsideSpecCheck 做集合比对）
    // ==========================================================================================

    /**
     * fsh 里声明的<b>全部</b> uniform 名。
     *
     * <h2>🔴 这张表的契约（2026-10-25 事故之后写死）</h2>
     * 它必须与 fsh 的 {@code uniform} 声明<b>逐字集合相等</b>，而且 fsh 里声明的每一个
     * <b>都必须在 {@code main()} 里被真的读一次</b>。
     *
     * <p>上一版这里多了一个 {@code uScreenSize}：fsh 声明了、代码里也 {@code set} 了，
     * 但着色器<b>从来没读过它</b>。GLSL 编译器按规范把没用到的 uniform 优化掉 ⇒
     * {@code glGetUniformLocation} 返回 {@code -1} ⇒ MC 的 {@code ShaderInstance} 不把它放进
     * {@code uniformMap}（字节码证据见 {@code ShaderInstance.m_173366_} 偏移 199..258）
     * ⇒ {@code getUniform("uScreenSize")} 返回 {@code null} ⇒ 渲染器把它当"必需 uniform 缺失"，
     * <b>整条洞内路径一帧都没画</b>（屏幕上什么都没有，而日志还写着 draw=yes）。
     *
     * <p>所以：<b>要么在着色器里真用它，要么就别列在这里</b>。这一版选后者（它本来就冗余：
     * 屏幕中心/源半径已经是 {@code uCenterPx} / {@code uSrcHalfPx} 单独传的）。
     */
    public static final String[] UNIFORMS = {
            "Sampler0", "uCenterPx", "uSrcHalfPx", "uBgRect", "uBgTexel",
            "uBandNrm", "uBandOff", "uTime", "uSolid",
            "uP0", "uP1", "uP2", "uP3", "uP4"
    };

    /** 着色器 id（{@code assets/shanhai/shaders/core/<id>.fsh|vsh|json}）。 */
    public static final String SHADER_ID = "guixu_blackhole_inside";

    // ==========================================================================================
    //  ⑤ 随深度/距离变化的量（唯一来源：渲染器、日志、离线出图、图注全调这里）
    // ==========================================================================================

    /** 洞内深度 {@code depth01} = {@code clamp01(1 − dist/insideThr)}。 */
    public static double depthOf(double dist, double insideThr) {
        return insideThr > 1.0E-9D ? clamp01(1.0D - dist / insideThr) : 1.0D;
    }

    /** 亮环半径（屏幕半高为 1）。<b>越深越小</b>。 */
    public static double windowRadius(double depth01) {
        final double d = clamp01(depth01);
        return WINDOW_R_AT_CENTER + (WINDOW_R_AT_EDGE - WINDOW_R_AT_CENTER)
                * Math.pow(1.0D - d, WINDOW_R_POW);
    }

    /** 拖影强度（0..1 用户刻度）。 */
    public static double streakStrength(double depth01) {
        return clamp01(STREAK_BASE + STREAK_DEPTH_GAIN * clamp01(depth01));
    }

    /** 色散强度（单位 = 源半径）。 */
    public static double dispersion(double depth01) {
        return DISP_BASE + DISP_DEPTH_GAIN * clamp01(depth01);
    }

    /** 金带的 σ（p 空间）。 */
    public static double bandSigma(double depth01) {
        return BAND_SIGMA_BASE + BAND_SIGMA_DEPTH * clamp01(depth01);
    }

    /** 中心红移量 {@code 1+z = 1 + √(rs/r)}（自由下落观测者）。 */
    public static double redshift(double dist, double shadowR) {
        final double r = Math.max(dist, 0.05D);
        final double v = Math.sqrt(Math.max(shadowR, 0.0D) / r);
        return 1.0D + Math.min(v, 40.0D);
    }

    /** 红移导致的变暗。 */
    public static double dimmer(double redshift) {
        return 1.0D / (1.0D + REDSHIFT_DIM * Math.max(redshift - 1.0D, 0.0D));
    }

    /** 屏幕四角在 p 空间里的半径（{@code hypot(W/2, H/2) / srcHalfPx}）。 */
    public static double cornerRadius(double screenW, double screenH, double srcHalfPx) {
        if (srcHalfPx <= 1.0E-9D) {
            return 1.0D;
        }
        return Math.hypot(screenW * 0.5D, screenH * 0.5D) / srcHalfPx;
    }

    /**
     * 屏幕边缘的拖影大概有多长（像素；给日志/图注/自检用，<b>不</b>进着色器）。
     * <p>≈ {@code STREAK_SRC_MAX·streak·(1/GAMMA)·cornerR·srcHalfPx}（radial 导数在 srcR=SRC_RADIUS 处取最大）。
     */
    public static double streakPx(double depth01, double screenW, double screenH, double srcHalfPx) {
        final double cornerR = cornerRadius(screenW, screenH, srcHalfPx);
        return STREAK_SRC_MAX * streakStrength(depth01) * (1.0D / GAMMA) * cornerR * srcHalfPx;
    }

    /** 一行读数（日志 {@code inside_view} 与图注共用）。 */
    public static String describe(Params p) {
        return String.format(java.util.Locale.ROOT,
                "depth=%.3f ringR=%.3f(ringT=%.2f) streak=%.2f(edge≈%.0fpx) disp=%.3f band=%.2f(sigma=%.3f) "
                        + "redshift=%.2f dim=%.2f taps=%d srcSide=%d cornerP=%.2f",
                p.depth01, p.windowR, p.windowR / Math.max(p.cornerR, 1.0E-6D),
                p.streak, streakPx(p.depth01, p.screenW, p.screenH, p.srcHalfPx),
                p.disp, p.bandGain, p.bandSigma, p.redshift, p.dimmer, TAPS, p.srcSide, p.cornerR);
    }

    // ==========================================================================================
    //  ⑥ 一帧的全部输入（渲染器与离线出图器<b>共用同一个工厂</b>）
    // ==========================================================================================

    /** 洞内一帧的打包参数（{@code p0..p4} 与着色器的 {@code uP0..uP4} 逐位对应）。 */
    public static final class Params {
        public final double screenW;
        public final double screenH;
        public final double centerPxX;
        public final double centerPxY;
        public final double srcHalfPx;
        public final int srcSide;
        public final double bgX0;
        public final double bgY0;
        public final double bgW;
        public final double bgH;
        public final double bgTexel;
        public final double bandNrmX;
        public final double bandNrmY;
        public final double bandOff;
        public final double time;
        /** 屏幕四角在 p 空间里的半径（uP4.w）。 */
        public final double cornerR;
        /** uP0 = (windowR, streak, disp, bandGain)。 */
        public final double[] p0;
        /** uP1 = (gamma, srcR, rimBoost, rimPow)。 */
        public final double[] p1;
        /** uP2 = (edgeWarp, flowK, flowAmp, flowSpeed)。 */
        public final double[] p2;
        /** uP3 = (bandSigma, bandStreak, bandDisp, gain)。 */
        public final double[] p3;
        /** uP4 = (dimmer, bandWarmA, bandWarmB, cornerR)。 */
        public final double[] p4;

        // ---- 只进日志/图注 ----
        public final double depth01;
        public final double windowR;
        public final double streak;
        public final double disp;
        public final double bandGain;
        public final double bandSigma;
        public final double gain;
        public final double redshift;
        public final double dimmer;
        public final double dist;
        public final double shadowR;

        private Params(double screenW, double screenH, int srcSide, double depth01,
                       double windowR, double streak, double disp, double bandGain,
                       double bandSigma, double gain, double redshift, double dimmer,
                       double dist, double shadowR, double bandNrmX, double bandNrmY,
                       double bandOff, double time, double bgTexel) {
            this.screenW = screenW;
            this.screenH = screenH;
            this.srcSide = srcSide;
            this.srcHalfPx = srcSide * 0.5D;
            this.cornerR = cornerRadius(screenW, screenH, this.srcHalfPx);
            this.bgW = srcSide;
            this.bgH = srcSide;
            this.bgX0 = Math.floor((screenW - srcSide) * 0.5D);
            this.bgY0 = Math.floor((screenH - srcSide) * 0.5D);
            this.centerPxX = screenW * 0.5D;
            this.centerPxY = screenH * 0.5D;
            this.bgTexel = bgTexel;
            this.bandNrmX = bandNrmX;
            this.bandNrmY = bandNrmY;
            this.bandOff = bandOff;
            this.time = time;
            this.depth01 = depth01;
            this.windowR = windowR;
            this.streak = streak;
            this.disp = disp;
            this.bandGain = bandGain;
            this.bandSigma = bandSigma;
            this.gain = gain;
            this.redshift = redshift;
            this.dimmer = dimmer;
            this.dist = dist;
            this.shadowR = shadowR;
            this.p0 = new double[]{windowR, streak, disp, bandGain};
            this.p1 = new double[]{GAMMA, SRC_RADIUS, RIM_BOOST, RIM_POW};
            this.p2 = new double[]{EDGE_WARP, FLOW_K, FLOW_AMP, FLOW_SPEED};
            this.p3 = new double[]{bandSigma, BAND_STREAK_GAIN, BAND_DISP_GAIN, gain};
            this.p4 = new double[]{dimmer, BAND_WARM_A, BAND_WARM_GAIN_B, this.cornerR};
        }
    }

    /** 生成一帧的洞内参数（<b>唯一工厂</b>：渲染器与离线出图器都调它）。 */
    public static Params of(double screenW, double screenH, int srcSide, double depth01,
                            double dist, double shadowR, float[] diskNormalCam, double tanHalfFov,
                            double time, double windowMul, double streakOverride, double bgTexel) {
        final double d = clamp01(depth01);
        final double windowR = clamp(windowRadius(d) * clamp(windowMul, WINDOW_MUL_MIN, WINDOW_MUL_MAX),
                WINDOW_R_MIN, WINDOW_R_MAX);
        final double streak = streakOverride >= 0.0D
                ? clamp01(streakOverride) : streakStrength(d);
        final double disp = dispersion(d);
        // 金带 σ 夹在亮环半径的 0.72 倍以内（否则整屏暖色糊）
        final double sigma = Math.min(bandSigma(d), 0.72D * windowR);
        final double z = redshift(dist, shadowR);
        final double dim = dimmer(z);

        // ---- 金带在屏上的迹线：盘面过相机 ⇒ a·u + b·v = c，再换算到 p 空间 ----
        double nx = 0.0D;
        double ny = 0.0D;
        double off = 0.0D;
        double bandGain = BAND_GAIN;
        if (diskNormalCam != null) {
            final double tan = (Double.isFinite(tanHalfFov) && tanHalfFov > 0.01D)
                    ? tanHalfFov : DEFAULT_TAN_HALF_FOV;
            final double a = tan * diskNormalCam[0];
            final double b = tan * diskNormalCam[1];
            final double c = diskNormalCam[2];
            final double len = Math.hypot(a, b);
            if (len > 0.05D * tan) {
                final double k = (screenH > 1.0E-6D) ? (srcSide / screenH) : 1.0D;
                nx = a / len;
                ny = b / len;
                off = (c / len) / Math.max(k, 1.0E-6D);
            } else {
                bandGain = 0.0D;      // 盘面垂直于视线 ⇒ 迹线在无穷远 ⇒ 这一帧没有"赤道方向"
            }
        } else {
            bandGain = 0.0D;
        }
        return new Params(screenW, screenH, srcSide, d, windowR, streak, disp, bandGain,
                sigma, GAIN, z, dim, dist, shadowR, nx, ny, off, time, bgTexel);
    }

    // ==========================================================================================
    //  ⑦ 逐像素算式（<b>生产代码</b>；离线出图器直接调它）
    // ==========================================================================================

    /**
     * 背景采样器：按<b>屏幕像素坐标</b>（左下原点、浮点）取颜色。
     * <p>语义与 GL 侧 {@code texture(Sampler0, uv)} 一致：线性过滤 + clamp-to-edge + 夹在拷贝矩形内。
     */
    public interface Source {
        void samplePx(double px, double py, double[] out);
    }

    /**
     * 算一个像素（<b>与 {@code blackhole_inside.fsh} 的 {@code main()} 逐行同款</b>）。
     *
     * @param p     这一帧的参数（{@link #of}）
     * @param src   背景采样器
     * @param fragX 片元 x（屏幕像素，左下原点）
     * @param fragY 片元 y（屏幕像素，左下原点）
     * @param out   输出 rgb（0..1，长度 ≥ 3）
     */
    public static void shade(Params p, Source src, double fragX, double fragY, double[] out) {
        // ---- ① p 坐标 → tN（0 = 屏幕中心，1 = 屏幕四角；**不夹到 1、没有"窗外"**）----
        final double nx = (fragX - p.centerPxX) / p.srcHalfPx;
        final double ny = (fragY - p.centerPxY) / p.srcHalfPx;
        final double rad0 = Math.sqrt(nx * nx + ny * ny);
        final double ang = Math.atan2(ny, nx);
        // 整屏径向呼吸（两个频率叠加 = 拍频/潮汐感）
        final double breath = BREATH_AMP * (BREATH_MIX_A * Math.sin(p.time * BREATH_SPEED_A)
                + (1.0D - BREATH_MIX_A) * Math.sin(p.time * BREATH_SPEED_B));
        final double tN = rad0 / Math.max(p.cornerR, 1.0E-6D) * (1.0D + breath);
        // 亮环所在的归一化半径（随进洞加深变小）——只影响亮度，不构成边界
        final double rt = clamp(p.p0[0] / Math.max(p.cornerR, 1.0E-6D), 0.02D, 0.98D);

        // ---- ② 赤道带权重（盘面迹线的高斯带；乘在【采样结果】上）----
        final double bw = (p.bandGain > 0.0D && p.bandSigma > 1.0E-6D)
                ? p.bandGain * Math.exp(-sq((nx * p.bandNrmX + ny * p.bandNrmY - p.bandOff) / p.bandSigma))
                : 0.0D;

        // ---- ③ 源半径、色散、拖影长度（都以【源半径】为单位）----
        final double srcR = p.p1[1] * Math.pow(Math.max(tN, 1.0E-5D), p.p1[0]);
        final double dispR = p.disp * (1.0D + p.p3[2] * bw) * Math.pow(clamp01(tN), DISP_POW);
        final double shape = SHAPE_STREAK_INNER + SHAPE_STREAK_OUTER * tN * tN;
        final double sh = Math.min(STREAK_SRC_MAX * p.p0[1] * shape * (1.0D + p.p3[1] * bw),
                SHAPE_STREAK_MAX);

        // ---- ④ 沿【源半径】取 TAPS 个样本：同时累计【平均】与【最大】----
        final double[] s = new double[3];
        double ar = 0.0D;
        double ag = 0.0D;
        double ab = 0.0D;
        double mr = 0.0D;
        double mg = 0.0D;
        double mb = 0.0D;
        // 屏幕边缘的强扭曲：每像素只算一次（否则同一次拖影的样本落在不同方位角 ⇒ 条纹被卷成弧）
        final double warpAng = ang + p.p2[0] * Math.pow(Math.max(tN, 0.0D), EDGE_WARP_POW)
                * Math.sin(tN * EDGE_PHASE_RADIAL - p.time * EDGE_PHASE_TIME + srcR * EDGE_PHASE_SRC);
        final double cx = Math.cos(warpAng);
        final double cy = Math.sin(warpAng);
        for (int i = 0; i < TAPS; i++) {
            final double fi = (i + 0.5D) / (double) TAPS;
            final double tapR = srcR * (1.0D - sh * fi);
            // 向外流动：位移按局部源半径成比例（永不为负）+ 相位随时间前进 ⇒ 特征沿半径向外漂
            final double rr = tapR * (1.0D + p.p2[2] * Math.sin(tapR * p.p2[1] - p.time * p.p2[3]));
            final double kR = (rr + dispR) * p.srcHalfPx;
            final double kG = rr * p.srcHalfPx;
            final double kB = (rr - dispR) * p.srcHalfPx;
            src.samplePx(p.centerPxX + cx * kR, p.centerPxY + cy * kR, s);
            ar += s[0];
            mr = Math.max(mr, s[0]);
            src.samplePx(p.centerPxX + cx * kG, p.centerPxY + cy * kG, s);
            ag += s[1];
            mg = Math.max(mg, s[1]);
            src.samplePx(p.centerPxX + cx * kB, p.centerPxY + cy * kB, s);
            ab += s[2];
            mb = Math.max(mb, s[2]);
        }
        final double inv = 1.0D / (double) TAPS;
        final double lineMix = clamp01(STREAK_LINE_MAX * p.p0[1] * clamp01(tN));
        final double r = mix(ar * inv, mr, lineMix);
        final double g = mix(ag * inv, mg, lineMix);
        final double b = mix(ab * inv, mb, lineMix);

        // ---- ⑤ 红移/蓝移分级：中心暖、边缘冷 ----
        final double lum = LUMA_R * r + LUMA_G * g + LUMA_B * b;
        final double edgeMix = smoothstep(TINT_RAMP_IN, TINT_RAMP_OUT, clamp01(tN));
        final double tintR = mix(TINT_WARM_R, TINT_COOL_R, edgeMix);
        final double tintG = mix(TINT_WARM_G, TINT_COOL_G, edgeMix);
        final double tintB = mix(TINT_WARM_B, TINT_COOL_B, edgeMix);
        double cr = mix(r, lum * tintR, GRADE_MIX);
        double cg = mix(g, lum * tintG, GRADE_MIX);
        double cb = mix(b, lum * tintB, GRADE_MIX);

        // ---- ⑥ 金带：乘性暖色分级（黑的地方仍是黑 ⇒ 不是纯色多边形）----
        final double bandGrade = clamp01(bw * (1.0D - BAND_RIM_FADE
                * smoothstep(rt * BAND_RIM_FADE_IN, rt * BAND_RIM_FADE_IN + 0.45D, clamp01(tN))));
        final double warmMul = p.p4[1] + p.p4[2] * lum;
        cr = mix(cr, cr * BAND_WARM_R * warmMul, bandGrade);
        cg = mix(cg, cg * BAND_WARM_G * warmMul, bandGrade);
        cb = mix(cb, cb * BAND_WARM_B * warmMul, bandGrade);

        // ---- ⑦ 亮度剖面：环内渐亮 → 环峰 → 环外指数衰减到四角（**没有掩码，四角不是黑**）----
        final double peak = WIN_DIM + p.p1[2];
        final double profile = (tN <= rt)
                ? WIN_DIM + p.p1[2] * Math.pow(clamp01(tN / Math.max(rt, 1.0E-3D)), p.p1[3])
                : peak * Math.exp(-(tN - rt) * OUTER_FADE);
        // 全屏下限：保证四角也有画面（不是"窗外=黑"）
        final double windowGain = Math.max(profile, WIN_FLOOR);
        final double k = windowGain * p.p4[0] * p.p3[3];
        cr *= k;
        cg *= k;
        cb *= k;

        // ---- ⑧ tone mapping：pow 抬暗部 + Reinhard 型肩部（让暗部结构在拖影里还认得出）----
        cr = toneMap(cr);
        cg = toneMap(cg);
        cb = toneMap(cb);
        out[0] = Math.max(cr, 0.0D);
        out[1] = Math.max(cg, 0.0D);
        out[2] = Math.max(cb, 0.0D);
    }

    /**
     * tone mapping：<b>只抬暗部</b>，高光原样（{@code y = x·(1 + LIFT·exp(−x/SHADOW))·GAIN}）。
     *
     * <p>为什么不用 {@code pow(x, 0.7)}：那是"整体提亮"，会把中间调一起顶上去 ⇒ 整屏发白发糊
     * （v3 第一版实测就是"蓝色等离子隧道"）。指数衰减项只在 {@code x < ~0.3} 起作用：
     * {@code x=0.03 ⇒ ×2.1}、{@code x=0.2 ⇒ ×1.3}、{@code x≥1 ⇒ ×1.0} ⇒
     * 地面的方块/格线在暗部被抬出来，而亮环不被推爆。
     */
    private static double toneMap(double v) {
        final double x = Math.max(v, 0.0D);
        return x * (1.0D + TONE_LIFT * Math.exp(-x / TONE_SHADOW)) * TONE_GAIN;
    }

    // ==========================================================================================
    //  ⑧ 小工具
    // ==========================================================================================

    private static double sq(double v) {
        return v * v;
    }

    static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double smoothstep(double a, double b, double x) {
        if (b <= a) {
            return x >= b ? 1.0D : 0.0D;
        }
        final double t = clamp01((x - a) / (b - a));
        return t * t * (3.0D - 2.0D * t);
    }

    static double clamp01(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
