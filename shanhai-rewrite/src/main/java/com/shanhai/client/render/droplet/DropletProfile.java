package com.shanhai.client.render.droplet;

/**
 * 强相互作用水滴的<b>解析回转轮廓</b> —— 桌面预览 {@code 34-强相互作用水滴-外观预览.html}（下称 v3）
 * 的<b>逐字移植</b>，外加推进环 / 尾迹的解析参数。
 *
 * <h2>一、这一份是纯数学（零 Minecraft 依赖）</h2>
 * 本类只 {@code import java.lang}（连 {@code Math} 都是全限定短名）⇒
 * 离线几何探针 {@code temp/droplet-render/probe/DropletGeomProbe.java} 可以
 * <b>不带任何游戏 jar</b>直接跑（判据：探针的 classpath 里只有 {@code out/} 一个目录）。
 *
 * <h2>二、轮廓为什么长这样（v3 的推导，逐字保留）</h2>
 * 全长 3 格、最大半径 0.75 格（2:1 的水滴）。<b>头部</b>是精确半球：球心在局部 {@code x=XJ=0.75}，
 * 半径 {@code RM=0.75} ⇒ 头端 {@code x∈[0.75,1.5]}、到达最大宽度的位置就是接缝 {@code x=0.75}。
 * <b>尾部</b>由 {@code R(s)=RM−∫g} 给出，其中
 * {@code g(s)=(a/RM)(1−e^{−s/a})e^{−s/b}}、{@code s=XJ−x}：
 *
 * <ul>
 *   <li>{@code g} 在 {@code s=0} 处是 0 ⇒ 与半球接缝处 {@code R、R′} 相等（C1）；</li>
 *   <li>{@code g′} 在 {@code s=0} 处也是 0（{@code (1−e^{−s/a})} 的一阶导为 0）⇒ {@code R″} 相等，
 *       <b>曲率连续（C2）</b>；</li>
 *   <li>{@code g>0} 且 {@code (1−e^{−s/a})} 单调、{@code e^{−s/b}} 单调 ⇒ {@code R′} 在尾部<b>处处单调</b>，
 *       半径沿轴单调；</li>
 *   <li>{@code b} 由 {@link #solvePB} 二分到 {@code R(UTAIL)=0} ⇒ <b>尾端正好收到一个点</b>
 *       （{@code R′(s)} 在尾端不为 0 ⇒ 是个尖，不是圆头）。</li>
 * </ul>
 *
 * <p>🔴 <b>2026-11-23 口径更正（用户原话：「尾部没问题，就是我要这样的」）</b>：
 * 尾部参数 {@code a=0.60} / {@code b=2.183} / 尾端半角 <b>15.6°</b> <b>一律按 v3 原样</b>，
 * <b>不许再加粗、不许改半角</b>。所以本类的 {@link #PA}、{@link #solvePB} 与 {@link #V3}
 * 都必须是 v3 的数值，任何"为了让尾部更好看"的调整都属于越界。
 *
 * <h2>三、以后若加镜面/环境反射，最难看的会是尾部近锥面</h2>
 * 2026-11-23 已查清：v3 预览里本体上那层"网纹"<b>不是网格问题</b>，是<b>地面格线在本体上的倒影</b>
 * —— 关掉反射就一条不剩；只删环境里的格线也会消失；而弧线收敛成"网眼"是<b>近锥面掠射的奇点</b>，
 * 与 170 环 × 52 经线的均匀网格形态不符。⇒ <b>本类的网格密度与法线不需要、也不许为它做任何规避</b>。
 * 只留这一句告示：将来真做镜面/环境反射时，尾端那一段近锥面的掠射区会是第一个露馅的地方，
 * 而它<b>与网格密度无关</b>（加密网格只会让"网眼"更细，不会消失）。
 *
 * <h2>四、本类不含"身体上下浮动"</h2>
 * v3 有一个 {@code bodyOff(t)=0.42·sin(2πt/7)} 的竖直浮动（纯预览演出）。
 * 游戏内本体的位置由实体自己的 tick 决定，渲染<b>不许</b>再叠一层正弦位移
 * （那样会与 {@code makeBoundingBox()} 错开）。所以 {@code BODY_T/BODY_A} 不搬过来。
 */
public final class DropletProfile {

    // ══════════════════════════════════════════════════════════════════════════════
    //  一、v3 本体轮廓常量（逐字：LEN/RM/XJ/PA）
    // ══════════════════════════════════════════════════════════════════════════════

    /** 全长（格）—— v3 {@code LEN=3.0}，与 {@code ShanhaiDropletEntity.BODY_LENGTH} 同一个数。 */
    public static final double LEN = 3.0D;
    /** 最大半径（格）= 头端半球半径 —— v3 {@code RM=0.75}，直径 = {@code BODY_GIRTH}=1.5。 */
    public static final double RM = 0.75D;
    /** 头端半球的球心轴向位置（局部 x）—— v3 {@code XJ=0.75}。 */
    public static final double XJ = 0.75D;
    /** 尾部积分式的尺度 a —— v3 {@code PA=0.60}（<b>用户点名不许改</b>）。 */
    public static final double PA = 0.60D;
    /** 尾端轴向参数上界 = {@code XJ + LEN/2} = 2.25 —— v3 {@code UTAIL}。 */
    public static final double UTAIL = XJ + LEN * 0.5D;
    /** v3 二分出来的 b（{@code solvePB} 的根）—— 只作自检基准，真值由 {@link #solvePB} 现算。 */
    public static final double PB_V3 = 2.183D;

    // ══════════════════════════════════════════════════════════════════════════════
    //  二、推进环参数（v3 §11 逐字）
    // ══════════════════════════════════════════════════════════════════════════════

    /** 单个环的寿命（秒）—— v3 {@code RING_LIFE=2.0}。 */
    public static final double RING_LIFE = 2.0D;
    /** 同屏最多几个环 —— v3 {@code RING_N=2}。 */
    public static final int RING_COUNT = 2;
    /**
     * 环的生成间隔（秒）—— v3 是 {@code RING_STEP = RING_LIFE / RING_N = 2.0/2 = 1.0}。
     *
     * <p>🔴 任务书要求「<b>每 1 秒生成一个</b>（寿命 2.0 s ⇒ 同屏 ≤2 个）」——
     * v3 的 {@code RING_N=2} 正好就是 1 秒一个，两处同一个数 ⇒ 不需要另加参数。
     */
    public static final double RING_STEP = RING_LIFE / RING_COUNT;
    /** 环半径曲线：{@code R(p)=R0+(R1−R0)p^1.35}。 */
    public static final double RING_R0 = 0.013D;
    public static final double RING_R1 = 1.45D;
    /** 环后移曲线：{@code D(p)=D0+(D1−D0)p^0.90}（从尾尖往后量）。 */
    public static final double RING_D0 = 0.02D;
    public static final double RING_D1 = 3.60D;
    /** 环带宽：{@code th(p)=TH0+(TH1−TH0)p}。 */
    public static final double RING_TH0 = 0.010D;
    public static final double RING_TH1 = 0.034D;
    /** 环半径幂次 / 后移幂次 / 透明度幂次（v3 写死在 {@code ringAt} 里）。 */
    public static final double RING_R_POW = 1.35D;
    public static final double RING_D_POW = 0.90D;
    public static final double RING_A_POW = 1.10D;
    /** 透明度整体系数 1.05（v3 {@code 1.05*al*pow(1-p,1.10)}）。 */
    public static final double RING_A_GAIN = 1.05D;
    /** 出生 0.10 s 内的淡入（v3 {@code 0.62+0.38*min(1,age/0.10)}）。 */
    public static final double RING_A_FLOOR = 0.62D;
    public static final double RING_A_RAMP = 0.38D;
    public static final double RING_A_FADE = 0.10D;

    // ══════════════════════════════════════════════════════════════════════════════
    //  三、尾迹参数（🔴 2026-11-24 按用户两条验收意见重做，不再是 v3 §12 的逐字移植）
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * 尾迹总长（格）—— <b>24.0</b>。
     *
     * <h4>为什么不是 v3 的 8.2</h4>
     * 从参考图上量出来的比例是「光束 / 本体 = 8.2 / 3.0 = 2.73」，但用户实机反馈
     * 「<b>而且太短了</b>」⇒ 任务书给的下限是 <b>≥20 格</b>。这里取 24（= 本体长的 8 倍），
     * 同时它就是"路点淘汰"的弧长上限（{@link #BEAM_MAX_ARC}），两个数同一个值 ⇒
     * 几何画到哪淡出就到哪，不会出现"画了 4 格纯黑"的浪费。
     */
    public static final double BEAM_LEN = 24.0D;
    /** 路点保留的弧长上限（格）—— 与 {@link #BEAM_LEN} 同值（见上一条注释）。 */
    public static final double BEAM_MAX_ARC = BEAM_LEN;
    /** 路点保留的年龄上限（秒）：静止不动时靠它把老点淡出掉（不许留永久光柱）。 */
    public static final double BEAM_LIFE = 2.0D;

    /**
     * 尾迹<b>芯</b>半径（格）—— <b>沿轴恒定</b>，0.065。
     *
     * <h4>这两个数是量出来的，不是感觉</h4>
     * 参考图 {@code 34-强相互作用水滴-四分之三视角.png} 的像素标尺：本体 3.0 格 = 313 px、
     * 红球 1.16 格 = 120.5 px ⇒ <b>PU ≈ 104 px/格</b>。光束可见半宽：紧贴尾尖 8.5 px = 0.082 格、
     * 中远段最大 13.5 px = 0.130 格 ⇒ <b>可见直径 0.16~0.26 格，全程只变化 ≈1.6 倍</b>。
     * 所以这里取直径 0.13（芯）与 0.26（光晕），两者都<b>沿轴恒定</b>
     * ⇒ 半径 max/min = <b>1.00</b>（判据要求 ≤1.5）。
     *
     * <h4>为什么不能更细</h4>
     * 1080p / FOV 70° 下每格的屏幕高度 = {@code 771.2 / 距离} px。60 格处 1 格 = 12.85 px
     * ⇒ 直径 0.13 格 = <b>1.67 px</b>、光晕 0.26 格 = <b>3.34 px</b>（判据：≥1.3 px）。
     * 15 格处分别是 6.7 / 13.4 px。
     */
    public static final double BEAM_RADIUS_CORE = 0.065D;
    /** 尾迹<b>光晕</b>半径（格）—— 沿轴恒定，= 芯的 2 倍（0.13 ⇒ 直径 0.26 = 量到的最大可见宽度）。 */
    public static final double BEAM_RADIUS_HALO = 0.130D;
    /**
     * 芯的峰值 alpha（最靠近水滴的那一点）—— 2026-11-24 提亮：0.85 → <b>1.00</b>
     * （用户："感觉轨迹亮度还可以再亮一点"）。
     */
    public static final double BEAM_A_MAX = 1.00D;
    /**
     * 光晕的峰值 alpha —— 2026-11-24 提亮：0.22 → <b>0.50</b>。
     *
     * <p>为什么提光晕提得更多：光晕是更<b>宽</b>的那条带，它整片盖住光束中心
     * ⇒ 它的贡献相当于"给整根光束加一层底光"，提它对观感亮度的边际收益最大，
     * 而且完全不动宽度（宽度恒定是用户点名的红线）。
     */
    public static final double BEAM_HALO_A_MAX = 0.50D;
    /** 亮度沿长度的幂次（{@code pow(1-u,1.5)}，v3 同款，但 u 现在是"归一化代价"而不是世界距离）。 */
    public static final double BEAM_A_POW = 1.5D;
    /** 芯的颜色（青白偏蓝）—— 2026-11-24 一起提亮：(0.62,0.80,1.00) → <b>(0.72,0.90,1.00)</b>。 */
    public static final float[] BEAM_CORE_RGB = {0.72F, 0.90F, 1.00F};
    /** 光晕颜色 —— 2026-11-24：(0.16,0.34,0.80) → <b>(0.34,0.60,1.00)</b>（更青、更亮）。 */
    public static final float[] BEAM_HALO_RGB = {0.34F, 0.60F, 1.00F};

    /**
     * 改前（2026-11-24 之前）那组亮度常量 —— <b>只给自检/探针做"改前 vs 改后"对照用</b>。
     *
     * <p>留在这里而不是让探针自己抄一份：探针要证明的是"现在确实比过去亮 ≥35%"，
     * 那"过去"必须是<b>可核对的历史值</b>，所以由本类统一保存，探针只读。
     */
    public static final double LEGACY_BEAM_A_MAX = 0.85D;
    /** 改前光晕峰值 alpha（见 {@link #LEGACY_BEAM_A_MAX}）。 */
    public static final double LEGACY_BEAM_HALO_A_MAX = 0.22D;
    /** 改前芯颜色。 */
    public static final float[] LEGACY_BEAM_CORE_RGB = {0.62F, 0.80F, 1.00F};
    /** 改前光晕颜色。 */
    public static final float[] LEGACY_BEAM_HALO_RGB = {0.16F, 0.34F, 0.80F};

    // ══════════════════════════════════════════════════════════════════════════════
    //  四、默认（v3）实例
    // ══════════════════════════════════════════════════════════════════════════════

    /** 游戏内唯一使用的那份轮廓（v3 参数）。 */
    public static final DropletProfile V3 = new DropletProfile(LEN, RM, XJ, PA);

    private final double len;
    private final double halfLen;
    private final double rm;
    private final double xj;
    private final double pa;
    private final double utail;
    private final double pb;

    /**
     * 按 v3 的四个量（{@code LEN/RM/XJ/PA}）建一份轮廓，{@code b} 由 {@link #solvePB} 现算。
     *
     * <p>为什么<b>不</b>把 {@code b} 当常量传进来：v3 里 {@code b} 是"让尾端正好收到 0"的解，
     * 一旦 {@code RM} 变了（比如探针的负对照把 0.75 改成 1.2），旧的 {@code b} 就不再是解
     * —— 那时候尾端会收到一个<b>正的</b>半径（尾部被截断）或者中途就归零。现算才自洽。
     */
    public DropletProfile(double len, double rm, double xj, double pa) {
        if (!(len > 0.0D) || !(rm > 0.0D) || !(pa > 0.0D)) {
            throw new IllegalArgumentException("len/rm/pa 必须为正: " + len + "/" + rm + "/" + pa);
        }
        if (!(xj > -len * 0.5D) || !(xj < len * 0.5D)) {
            throw new IllegalArgumentException("xj 必须落在 [-len/2, len/2] 之内: xj=" + xj + " len=" + len);
        }
        this.len = len;
        this.halfLen = len * 0.5D;
        this.rm = rm;
        this.xj = xj;
        this.pa = pa;
        this.utail = xj + halfLen;
        this.pb = solvePB(rm, pa, utail);
    }

    // ------------------------------------------------------------------ 基本量

    public double len() {
        return this.len;
    }

    public double halfLen() {
        return this.halfLen;
    }

    public double rm() {
        return this.rm;
    }

    public double xj() {
        return this.xj;
    }

    public double pa() {
        return this.pa;
    }

    /** {@code b}（尾部积分式里的第二个尺度）—— v3 现算出来的 ≈2.183。 */
    public double pb() {
        return this.pb;
    }

    public double utail() {
        return this.utail;
    }

    // ------------------------------------------------------------------ 轮廓函数（v3 §2 逐字）

    /**
     * 尾部半径 {@code Rprof(s)} —— v3 的同名函数，{@code s≥0} 是"离头部接缝往尾端走了多远"。
     * 结果被夹到 {@code ≥0}（v3 也是 {@code return v>0?v:0}）。
     */
    public double rprof(double s) {
        final double i1 = this.pb * (1.0D - Math.exp(-s / this.pb));
        final double k = (this.pa + this.pb) / (this.pa * this.pb);
        final double i2 = (this.pa * this.pb) / (this.pa + this.pb) * (1.0D - Math.exp(-s * k));
        final double v = this.rm - (this.pa / this.rm) * (i1 - i2);
        return v > 0.0D ? v : 0.0D;
    }

    /** 尾部斜率 {@code dR/dx} —— v3 的 {@code Rslope(s)}（恒为正：越往头越粗）。 */
    public double rslope(double s) {
        return (this.pa / this.rm) * (1.0D - Math.exp(-s / this.pa)) * Math.exp(-s / this.pb);
    }

    /**
     * 半径关于局部轴向坐标 {@code x} 的函数（{@code +x} 朝头）。
     *
     * <p>逐字：{@code x≥XJ} 走半球 {@code sqrt(RM²−(x−XJ)²)}，否则走 {@link #rprof}。
     */
    public double radiusAtX(double x) {
        if (x >= this.xj) {
            final double d = x - this.xj;
            final double rr = this.rm * this.rm - d * d;
            return rr > 0.0D ? Math.sqrt(rr) : 0.0D;
        }
        return rprof(this.xj - x);
    }

    /**
     * {@code dR/dx}（{@code +x} 朝头）。
     *
     * <p>头端极点处切线竖直 ⇒ v3 返回 {@code -1e5}（一个"足够大的负数"，用来让法线归一化后
     * 正好朝轴向）。这里保留同一个哨兵值，<b>不要</b>改成 {@code Double.NEGATIVE_INFINITY}：
     * 那个值参与 {@code n/|n|} 会算出 {@code NaN}。
     */
    public double slopeAtX(double x) {
        if (x >= this.xj) {
            final double d = x - this.xj;
            final double rr = this.rm * this.rm - d * d;
            if (rr <= 1e-9D) {
                return -1e5D;
            }
            return -d / Math.sqrt(rr);
        }
        return rslope(this.xj - x);
    }

    /** 尾端（{@code x=−len/2}）的斜率绝对值 —— v3 是 <b>0.2786</b>（半角 15.6°）。 */
    public double tailSlope() {
        return rslope(this.utail);
    }

    /** 尾端半角（度）—— v3 是 <b>15.6°</b>。 */
    public double tailHalfAngleDeg() {
        return Math.toDegrees(Math.atan(tailSlope()));
    }

    /**
     * 头端（{@code x=+len/2}）的等效球半径。
     *
     * <p>只在"头部极点正好落在头端"时有意义（v3 满足：{@code XJ+RM = 0.75+0.75 = 1.5 = LEN/2}）。
     * 不满足时返回 {@code NaN} —— <b>不许</b>硬算一个看着像数字的伪值：负对照（RM 1.2）
     * 就是靠这个 {@code NaN} 让"头端半径=0.75"那条断言报红的。
     */
    public double noseOsculatingRadius() {
        if (Math.abs(radiusAtX(this.halfLen)) > 1e-9D) {
            return Double.NaN;
        }
        final double d = 1e-3D * this.halfLen;
        final double r = radiusAtX(this.halfLen - d);
        return r * r / (2.0D * d);
    }

    /** 最大半径出现在局部 x 的哪里 —— v3 的头部是半球 ⇒ 恒等于接缝 {@link #xj()}。 */
    public double maxWidthX() {
        return this.xj;
    }

    // ------------------------------------------------------------------ 推进环 / 尾迹采样（v3 §11 §12）

    /** 环在归一化年龄 {@code p∈[0,1]} 处的全部读数（v3 {@code ringAt} 的 Java 版）。 */
    public static RingSample ringAt(double p) {
        final double pc = p < 0.0D ? 0.0D : (p > 1.0D ? 1.0D : p);
        final double radius = RING_R0 + (RING_R1 - RING_R0) * Math.pow(pc, RING_R_POW);
        final double dist = RING_D0 + (RING_D1 - RING_D0) * Math.pow(pc, RING_D_POW);
        final double age = pc * RING_LIFE;
        final double rampIn = Math.min(1.0D, age / RING_A_FADE);
        final double alpha = RING_A_GAIN * (RING_A_FLOOR + RING_A_RAMP * rampIn)
                * Math.pow(Math.max(0.0D, 1.0D - pc), RING_A_POW);
        final double thickness = RING_TH0 + (RING_TH1 - RING_TH0) * pc;
        return new RingSample(radius, dist, alpha, thickness, pc);
    }

    /** 环的读数载体（避免每帧 new 一个对象：这是<b>一次性</b>分配的 record，只在探针里用）。 */
    public record RingSample(double radius, double dist, double alpha, double thickness, double p) {}

    /**
     * 环的读数<b>写进调用方给的数组</b>的版本（每帧用这个：{@link RingSample} 是 record，
     * 每调一次就 new 一个 ⇒ 渲染热路径里不许用）。
     *
     * @param out 长度 ≥4：{@code [0]=radius、[1]=dist、[2]=alpha、[3]=thickness}
     */
    public static void ringInto(double p, double[] out) {
        final double pc = p < 0.0D ? 0.0D : (p > 1.0D ? 1.0D : p);
        out[0] = RING_R0 + (RING_R1 - RING_R0) * Math.pow(pc, RING_R_POW);
        out[1] = RING_D0 + (RING_D1 - RING_D0) * Math.pow(pc, RING_D_POW);
        final double age = pc * RING_LIFE;
        final double rampIn = Math.min(1.0D, age / RING_A_FADE);
        out[2] = RING_A_GAIN * (RING_A_FLOOR + RING_A_RAMP * rampIn)
                * Math.pow(Math.max(0.0D, 1.0D - pc), RING_A_POW);
        out[3] = RING_TH0 + (RING_TH1 - RING_TH0) * pc;
    }

    /** 环读数的复用暂存（渲染线程独占；与 {@link #ringInto} 配套）。 */
    public static final double[] RING_SCRATCH4 = new double[4];

    /**
     * 芯半径（格）—— 🔴 <b>与轴向位置无关</b>（这是 2026-11-24 那次的病根：
     * 老实现让半径 0.045→0.62 线性增长，实机看到的是"越到后面越粗的扇形"）。
     */
    public static double beamRadiusCore() {
        return BEAM_RADIUS_CORE;
    }

    /** 光晕半径（格）—— 同样与轴向位置无关。 */
    public static double beamRadiusHalo() {
        return BEAM_RADIUS_HALO;
    }

    /**
     * 尾迹某一点的<b>归一化代价</b> {@code u∈[0,1]}：0 = 紧贴水滴，1 = 该丢掉了。
     *
     * <p>取两个截止条件的<b>较大者</b> ⇒ 任一条件先满足，alpha 就归零
     * （弧长封顶 24 格、年龄封顶 2 秒）。两个条件都是<b>单调</b>的，所以沿轨迹 alpha 单调递减。
     *
     * @param ageSeconds 该点距现在多少秒
     * @param arcBlocks  该点沿轨迹到水滴的弧长（格）
     */
    public static double beamCost(double ageSeconds, double arcBlocks) {
        final double byAge = ageSeconds / BEAM_LIFE;
        final double byArc = arcBlocks / BEAM_MAX_ARC;
        final double u = Math.max(byAge, byArc);
        return u < 0.0D ? 0.0D : (u > 1.0D ? 1.0D : u);
    }

    /**
     * 亮度包络：{@code peak·pow(1−u,1.5)}。
     *
     * <p>🔴 <b>衰减只作用在 alpha 上，不改半径</b>——这条是用户明确要求的
     * （「淡出只用 alpha，不许用变宽来表现衰减」）。
     */
    public static double beamAlphaAt(double u, double peak) {
        if (u <= 0.0D) {
            return peak;
        }
        if (u >= 1.0D) {
            return 0.0D;
        }
        return peak * Math.pow(1.0D - u, BEAM_A_POW);
    }

    /**
     * 光束<b>中心</b>（屏幕上落在轴上的那一点）在弧长 {@code arcBlocks} 处的加性累加颜色（<b>未 clamp</b>）。
     *
     * <p>为什么是"两层相加"：尾迹是两条同轴带，光晕比芯宽 ⇒ 中心处<b>两条都盖住</b>
     * ⇒ 加性混合下该像素拿到 {@code 芯色×芯alpha + 晕色×晕alpha}。
     * 这份模型给"改前 / 改后亮度"一个可复现的读数（探针直接调它）。
     *
     * @param arcBlocks 离水滴的弧长（格）
     * @param out       长度 ≥3：{@code [0..2]=R,G,B}（可能 >1，由显示端 clamp）
     */
    public static void beamCenterRgb(double arcBlocks, double[] out) {
        beamCenterRgb(arcBlocks, BEAM_A_MAX, BEAM_CORE_RGB, BEAM_HALO_A_MAX, BEAM_HALO_RGB, out);
    }

    /**
     * 同上，但亮度常量由调用方给（用于"改前 vs 改后"的对照；见 {@link #LEGACY_BEAM_A_MAX}）。
     */
    public static void beamCenterRgb(double arcBlocks, double corePeak, float[] coreRgb,
                                     double haloPeak, float[] haloRgb, double[] out) {
        final double u = beamCost(0.0D, arcBlocks);
        final double ac = beamAlphaAt(u, corePeak);
        final double ah = beamAlphaAt(u, haloPeak);
        out[0] = coreRgb[0] * ac + haloRgb[0] * ah;
        out[1] = coreRgb[1] * ac + haloRgb[1] * ah;
        out[2] = coreRgb[2] * ac + haloRgb[2] * ah;
    }

    /** 光束中心的峰值亮度（0~255）：每通道先 clamp 到 1，再按 0.299/0.587/0.114 取亮度。 */
    public static double beamLuminance255(double arcBlocks) {
        return beamLuminance255(arcBlocks, BEAM_A_MAX, BEAM_CORE_RGB, BEAM_HALO_A_MAX, BEAM_HALO_RGB);
    }

    /** 同上（亮度常量由调用方给）。 */
    public static double beamLuminance255(double arcBlocks, double corePeak, float[] coreRgb,
                                          double haloPeak, float[] haloRgb) {
        final double[] rgb = BEAM_RGB_SCRATCH;
        beamCenterRgb(arcBlocks, corePeak, coreRgb, haloPeak, haloRgb, rgb);
        final double r = Math.min(1.0D, rgb[0]);
        final double g = Math.min(1.0D, rgb[1]);
        final double b = Math.min(1.0D, rgb[2]);
        return 255.0D * (0.299D * r + 0.587D * g + 0.114D * b);
    }

    /** {@link #beamLuminance255} 的复用暂存。 */
    private static final double[] BEAM_RGB_SCRATCH = new double[3];

    // ------------------------------------------------------------------ b 的求解

    /**
     * v3 {@code solvePB} 的逐字移植：二分 {@code b∈[0.05,12]}，使 {@code Rprof(UTAIL)=0}。
     *
     * <p>为什么用二分而不给闭式解：{@code R(UTAIL)=0} 里 {@code b} 同时出现在指数与系数里
     * （{@code b²/(PA+b)} 那种形态），v3 自己也是二分的。200 次 = 区间缩到 {@code 1e-60}，
     * 远超 double 精度 ⇒ 只要一次 {@code Rprof} 判定就够，多点几次纯属浪费，但 200 次只跑一次。
     */
    public static double solvePB(double rm, double pa, double utail) {
        double lo = 0.05D;
        double hi = 12.0D;
        for (int it = 0; it < 200; it++) {
            final double mid = (lo + hi) * 0.5D;
            // 用"这个 b 下 Rprof(UTAIL) 还是正的吗"当判据（v3 同款）
            final double i1 = mid * (1.0D - Math.exp(-utail / mid));
            final double k = (pa + mid) / (pa * mid);
            final double i2 = (pa * mid) / (pa + mid) * (1.0D - Math.exp(-utail * k));
            final double v = rm - (pa / rm) * (i1 - i2);
            if (v > 0.0D) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5D;
    }
}
