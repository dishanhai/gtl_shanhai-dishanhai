package com.shanhai.client.holo;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 山海重构 · <b>全息环绕层（数据环 + 星尘网格）—— 全部可调参数的【唯一一份】定义</b>。
 *
 * <h2>0. 这一份是什么，从哪来</h2>
 * 用户点单：那 5 块全息板旁边太空了，要加一层<b>环绕在玩家周围</b>的点与线条。
 * 美术已经在浏览器里验收过，规格就是这一份单文件 HTML：
 * <pre>
 *   C:\Users\david\Desktop\山海HTML\17-全息环绕-结合版.html
 * </pre>
 * 它是「A 数据环（贴身 4 圈细环 + 刻度 + 沿环流动的光点 + 环缓慢进动）」
 * 与「B 星尘网格（周围 200 个光点的壳层 + 近距连线 + 阈值呼吸重组）」的结合版。
 *
 * <h2>1. 🔴 为什么参数只准写在这一处（与 {@link ShanhaiHoloMenuTuning} 同一个理由）</h2>
 * <ul>
 *   <li>用户说过「<b>可以在设置里面调整</b>」⇒ 以后要在设置面板里加滑块
 *       ⇒ 参数散落在渲染代码里就没法接滑块；</li>
 *   <li>本类里的每个量都<b>一一对应</b> HTML 参数面板上的一个滑块（键名见 §2），
 *       数值 = 用户这一轮拍板的那一组（<b>不是</b> HTML 里的默认档）。</li>
 * </ul>
 *
 * <h2>2. 与 HTML 滑块的对应表（改任何一个都会同时影响环与点场）</h2>
 * <table border="1">
 *   <tr><th>HTML 滑块</th><th>本类常量</th><th>本轮取值</th></tr>
 *   <tr><td>环数</td><td>{@link #RING_COUNT}</td><td>2</td></tr>
 *   <tr><td>环半径倍率</td><td>{@link #RING_SCALE}</td><td>1.40</td></tr>
 *   <tr><td>环亮度</td><td>{@link #RING_BRIGHT}</td><td>1.00</td></tr>
 *   <tr><td>刻度密度</td><td>{@link #RING_TICK_DENSITY}</td><td>1.60</td></tr>
 *   <tr><td>流动光点</td><td>{@link #RING_FLOW_SCALE}</td><td>1.0</td></tr>
 *   <tr><td>进动速度</td><td>{@link #RING_PRECESS_SCALE}</td><td>1.00</td></tr>
 *   <tr><td>光点数</td><td>{@link #FIELD_POINT_COUNT}</td><td>200</td></tr>
 *   <tr><td>壳层内半径</td><td>{@link #FIELD_INNER_RADIUS_BLOCKS}</td><td>2.55 格</td></tr>
 *   <tr><td>壳层外半径</td><td>{@link #FIELD_OUTER_RADIUS_BLOCKS}</td><td>3.30 格</td></tr>
 *   <tr><td>连线阈值</td><td>{@link #FIELD_LINK_THRESHOLD_BLOCKS}</td><td>1.44 格</td></tr>
 *   <tr><td>每点接线数</td><td>{@link #FIELD_LINK_MAX_PER_POINT}</td><td>3</td></tr>
 *   <tr><td>呼吸周期(秒)</td><td>{@link #FIELD_LINK_PERIOD_SEC}</td><td>15</td></tr>
 *   <tr><td>点场亮度</td><td>{@link #FIELD_BRIGHT}</td><td>1.50</td></tr>
 *   <tr><td>整体亮度</td><td>{@link #GLOBAL_BRIGHT}</td><td>1.00</td></tr>
 * </table>
 *
 * <h2>3. 🔴 结合版的三条既定规则（也定义在这里，不许散落）</h2>
 * <pre>
 *   ① 环半径硬夹 {@link #RING_MAX_RADIUS_BLOCKS}=1.75 格 —— 再大就顶到那 5 块板了
 *   ② 壳层内半径自动 = max(滑块值, 环最大半径 + {@link #FIELD_INNER_GAP_BLOCKS}=0.32)
 *      ⇒ 环划到哪、点场就自动退到环外 ⇒ 两套永远不重叠
 *   ③ 不许压到那 5 块板上的字 —— 两道判据（都在 {@link ShanhaiHoloSurround} 里实现）：
 *        ① 三维   ：元素落在任一板面矩形内（正反 {@link #BOARD_FACE_CLEARANCE_BLOCKS}=0.28 格）⇒ 不画
 *        ② 屏幕兜底：元素【投影】落进任一板子的投影四边形 ⇒ 不画
 * </pre>
 *
 * <h2>4. 单位口径（三种，别混）</h2>
 * <table border="1">
 *   <tr><th>单位</th><th>含义</th></tr>
 *   <tr><td><b>格</b>（blocks）</td><td>世界坐标，与那 5 块板同一套</td></tr>
 *   <tr><td><b>HTML 参考像素</b>（htmlPx）</td><td>
 *       线宽那一栏是从 HTML 里<b>逐字抄</b>过来的（1.3 / 1.8 / 4.6 …），
 *       它的<b>角宽度</b> = {@code htmlPx / }{@link #HTML_REF_FOCAL_PX}。
 *       本类把它换算成世界宽度：{@code 世界宽 = htmlPx / HTML_REF_FOCAL_PX × 该处的深度}。
 *       ⇒ 这样换算出来的<b>角宽度与网页里看到的完全一致</b>，与游戏分辨率、视场角都无关。</td></tr>
 *   <tr><td><b>屏幕像素</b>（px）</td><td>只用于"屏幕兜底判据"的边距（{@link #DOT_SCREEN_PAD_FRAC} 等）</td></tr>
 * </table>
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloSurroundTuning {

    private ShanhaiHoloSurroundTuning() {}

    // ==================================================================== 一、用户点单的 14 个参数

    // ---- A · 数据环（贴身）----

    /** 环数（HTML 滑块 2~6）。只取 {@link #RING_TABLE} 的前 N 条。 */
    public static final int RING_COUNT = 2;

    /** 环半径倍率（HTML 滑块 0.6~1.4）。乘在 {@link #RING_TABLE} 每条自己的半径上，再被 ① 硬夹。 */
    public static final float RING_SCALE = 1.40f;

    /** 环亮度（HTML 滑块 0~2）。 */
    public static final float RING_BRIGHT = 1.00f;

    /** 刻度密度（HTML 滑块 0~1.6）。乘在每条环自己的刻度条数上；{@code 0} = 不画刻度。 */
    public static final float RING_TICK_DENSITY = 1.60f;

    /** 流动光点倍数（HTML 滑块 0~2）。乘在每条环自己的光点数上；{@code 0} = 不画。 */
    public static final float RING_FLOW_SCALE = 1.0f;

    /** 进动速度倍数（HTML 滑块 0~2.5）。同时影响环的自转与倾角摆动。 */
    public static final float RING_PRECESS_SCALE = 1.00f;

    // ---- B · 星尘网格（外围）----

    /** 光点数（HTML 滑块 0~200）。注意：光点总数是<b>预分配上限</b>，改它不重新分配内存。 */
    public static final int FIELD_POINT_COUNT = 200;

    /** 壳层内半径（格，HTML 滑块 1.2~4.0）—— 会被规则 ② 自动抬到"环外 + 0.32"。 */
    public static final float FIELD_INNER_RADIUS_BLOCKS = 2.55f;

    /** 壳层外半径（格，HTML 滑块 2.0~5.0）。 */
    public static final float FIELD_OUTER_RADIUS_BLOCKS = 3.30f;

    /** 连线阈值（格，HTML 滑块 0.5~1.8）—— 两点距离小于它才连一条线。 */
    public static final float FIELD_LINK_THRESHOLD_BLOCKS = 1.44f;

    /** 每个点最多接几条线（HTML 滑块 1~5）。 */
    public static final int FIELD_LINK_MAX_PER_POINT = 3;

    /**
     * 连线阈值的<b>呼吸周期（秒）</b>（HTML 滑块 3~30）。
     *
     * <p>🔴 <b>这里与 HTML 的实现有一处刻意的不同，写在这里当备案</b>：
     * HTML 那一行是
     * <pre>
     *   const per = Math.max(2, Math.PI * 2 / TUNE.linkPeriod);
     *   const LK  = TUNE.link * (1 + 0.22 * Math.sin(t * per));
     * </pre>
     * 那个 {@code Math.max(2, …)} 夹的是<b>角频率</b>而不是周期，于是 {@code linkPeriod} 一旦大于
     * {@code 2π/2 ≈ 3.14} 秒，周期就被钉死在 {@code π ≈ 3.14} 秒 —— 滑块上写"15 秒"、实际是 3.14 秒。
     * 本轮<b>按用户点单的"15 秒"做</b>（角频率 = {@code 2π / 周期}）。
     * 要回到 HTML 那个手感，把 {@link #FIELD_LINK_PERIOD_SEC} 改成 3.1416 即可。
     */
    public static final float FIELD_LINK_PERIOD_SEC = 15.0f;

    /** 点场亮度（HTML 滑块 0~2）。 */
    public static final float FIELD_BRIGHT = 1.50f;

    // ---- 整体 ----

    /** 整体亮度（HTML 滑块 0~2）。环与点场同时乘它；那 5 块板与地面<b>不受影响</b>。 */
    public static final float GLOBAL_BRIGHT = 1.00f;

    // ==================================================================== 二、三条既定规则的常量

    /**
     * 🔴 <b>规则 ①：环半径硬上限（格）</b> —— 再大就顶到那 5 块板了。
     *
     * <p>五块板在环绕档下落在半径 {@code max(2.8, 1.9565) = 2.8} 格的圆上，
     * 板半宽 1.15 格 ⇒ 板的内边缘在 {@code 2.8 − 1.15·|cos| … } 一带；
     * 1.75 是用户拍板的那条线，本类只负责原样执行。
     */
    public static final float RING_MAX_RADIUS_BLOCKS = 1.75f;

    /**
     * 🔴 <b>规则 ②：壳层内半径至少比"环的最大半径"再外推这么多（格）</b>
     * ⇒ 环划到哪、点场就自动退到环外 ⇒ 两套永远不重叠。
     */
    public static final float FIELD_INNER_GAP_BLOCKS = 0.32f;

    /** 壳层外半径至少比内半径大这么多（格）—— 防止用户把外半径拖到比内半径还小。 */
    public static final float FIELD_MIN_SHELL_THICKNESS_BLOCKS = 0.35f;

    /** 🔴 <b>规则 ③-①：板面正/反方向多少格内算"贴着板面"（格）</b> ⇒ 不画。 */
    public static final float BOARD_FACE_CLEARANCE_BLOCKS = 0.28f;

    /**
     * 🔴 <b>规则 ③-① 的软边宽度（格）</b>：从"板面矩形内"到"离开板面"之间这 0.05 格做线性淡出。
     * <p>即 {@code outside < 0} = 在矩形里（完全挡掉），{@code outside ≥ 0.05} = 已经离开板面（不受影响）。
     */
    public static final float BOARD_EDGE_FADE_BLOCKS = 0.05f;

    /**
     * 🔴 <b>规则 ③-②：屏幕兜底判据</b> —— 光点带光晕，留的边距要比线段大一些。
     * <p>单位 = <b>屏幕高度的几分之几</b>（HTML 里是 14px / 600px 的画布高）。
     */
    public static final float DOT_SCREEN_PAD_FRAC = 14.0f / 600.0f;

    /** 🔴 规则 ③-② 的线段边距（侧向）（HTML 里是 6px / 600px）。 */
    public static final float LINE_SCREEN_PAD_FRAC = 6.0f / 600.0f;

    /**
     * 判据的"完全挡掉"阈值：淡出值低于它就整个不画。
     * <p>与 HTML 的 {@code 0.02 / 0.03} 取同一个量级（这里统一成一个数，便于调）。
     */
    public static final float CULL_FADE = 0.02f;

    /** 光点自身的透明度低于它就不画（HTML 的 {@code a < 0.03}）。 */
    public static final float DOT_MIN_ALPHA = 0.03f;

    /**
     * 🔴 <b>屏幕兜底判据的面积下限（像素²）</b>：一块板投影出来的四边形面积小于它就<b>整块跳过</b>。
     *
     * <p>为什么要有这一条：环绕档下五块板绕玩家一圈 ⇒ <b>总有一块近乎侧对镜头</b>，
     * 它的投影会退化成一条线（面积 ≈ 0）。而"点在多边形内部"的判据在零面积多边形上会退化成
     * "点正好落在那条线上" —— 这类退化几何最容易引出"整片区域被误判"的老毛病。
     * 一块零面积的板在屏幕上本来就不占像素，直接跳过它既安全又不损失画面。
     *
     * <p>取值 0.5 像素²：远小于任何一块正常朝向的板（本机实测正面那块是 12 万像素² 量级），
     * 又足以吃掉浮点噪声。
     */
    public static final double MIN_QUAD_AREA_PX2 = 0.5;

    /** 环/点场的亮度低于它就不画（HTML 的 {@code bright <= 0.01}）。 */
    public static final float MIN_BRIGHT = 0.01f;

    /** 半径小于它就不画（HTML 的 {@code r <= 0.05}）。 */
    public static final float MIN_RADIUS_BLOCKS = 0.05f;

    // ==================================================================== 三、线宽 / 光点大小（逐字抄 HTML）

    /**
     * HTML 那台预览相机的焦距（像素）。
     * <pre>
     *   HTML:  focal = (VH/2) / tan(FOV/2) ；默认画布 VH = 600、FOV = 70（与原版一致）
     *   ⇒ (600/2) / tan(35°) = 300 / 0.7002075 = 428.444 px
     * </pre>
     * 网页里一条 {@code w} 像素的线，角宽度 = {@code w / 428.444} 弧度
     * ⇒ 游戏里换算成世界宽度 {@code w / 428.444 × 深度} 就得到<b>同一个角宽度</b>。
     */
    public static final float HTML_REF_FOCAL_PX = 428.444f;

    /** 环体那段圆弧的线宽（HTML {@code S.runs(runs, R.hue, 1.3, 0.26*bright, true)}）。 */
    public static final float RING_BODY_W_HTML_PX = 1.3f;

    /** 环体那段圆弧的透明度。 */
    public static final float RING_BODY_ALPHA = 0.26f;

    /** 短刻度与环体同款（HTML 把它们并进同一个 {@code runs}）。 */
    public static final float RING_TICK_SHORT_W_HTML_PX = 1.3f;

    /** 短刻度透明度。 */
    public static final float RING_TICK_SHORT_ALPHA = 0.26f;

    /** 长刻度的线宽（HTML {@code S.runs(longRuns, R.tk, 1.8, 0.60*bright, true)}）。 */
    public static final float RING_TICK_LONG_W_HTML_PX = 1.8f;

    /** 长刻度的透明度。 */
    public static final float RING_TICK_LONG_ALPHA = 0.60f;

    /** 长刻度的判定：每 4 条一根（HTML {@code k % 4 === 0}）。 */
    public static final int RING_TICK_LONG_EVERY = 4;

    /** 整圈柔和辉光的线宽（HTML {@code S.path(glow, R.hue, 4.6, 0.07*bright, true)}）。 */
    public static final float RING_GLOW_W_HTML_PX = 4.6f;

    /** 整圈柔和辉光的透明度。 */
    public static final float RING_GLOW_ALPHA = 0.07f;

    /** 地面那圈淡影的线宽（HTML {@code S.path(g, [126,228,255], 1.1, 0.10*bright, true)}）。 */
    public static final float RING_FOOT_W_HTML_PX = 1.1f;

    /** 地面那圈淡影的透明度。 */
    public static final float RING_FOOT_ALPHA = 0.10f;

    /** 流动光点拖尾的线宽（HTML {@code S.path(tail, R.hue, 2.2, 0.45*bright, true)}）。 */
    public static final float RING_TAIL_W_HTML_PX = 2.2f;

    /** 流动光点拖尾的透明度。 */
    public static final float RING_TAIL_ALPHA = 0.45f;

    /** 连线各档的线宽（HTML {@code S.runs(bands[b], cols[b], 1.0, alps[b]*bright, true)}）。 */
    public static final float FIELD_LINK_W_HTML_PX = 1.0f;

    /**
     * 光点精灵的<b>半径</b> = 这个倍数 × 上面给的那个半径（世界单位）。
     * <pre>
     *   HTML: rp = rB * focal / z （屏幕半径）; size = rp * 3.0 （精灵的整宽）
     *   ⇒ 精灵的网格半径 = size/2 = 1.5 * rp ⇒ 世界半径 = 1.5 * rB
     * </pre>
     * ⚠️ 所以这里是 <b>1.5</b> 而不是 3.0 —— 3.0 是"整宽"，直接用会把光点放大一倍。
     */
    public static final float DOT_SPRITE_RADIUS_MUL = 1.5f;

    // ==================================================================== 四、环的形状表（逐条抄 HTML 的 RING_TBL）

    /**
     * 一条环的全部形状参数（HTML {@code RING_TBL} 的一行）。
     *
     * @param y      环心相对<b>玩家脚底</b>的高度（格）
     * @param r      这条环自己的半径（格）—— 还要乘 {@link #RING_SCALE} 再被 ① 硬夹
     * @param tilt   基础倾角（弧度）
     * @param tiltAmp 倾角的摆动幅度（弧度）
     * @param phase  相位（既用于倾角摆动，也用于流动光点的起点）
     * @param spin0  自转初相（弧度）
     * @param spinV  自转速度（弧度/秒）× {@link #RING_PRECESS_SCALE}
     * @param flowN  流动光点的条数 × {@link #RING_FLOW_SCALE}
     * @param flowV  流动光点的角速度（弧度/秒；正负决定流向）
     * @param tickN  刻度的条数 × {@link #RING_TICK_DENSITY}
     * @param hueRgb 环体/刻度/拖尾的颜色（RGB）
     * @param tickRgb 长刻度的颜色（RGB）
     * @param foot   {@code true} = 在地上也画一圈同半径的淡影（把环"钉"在地上）
     */
    public record RingSpec(float y, float r, float tilt, float tiltAmp, float phase,
                           float spin0, float spinV, int flowN, float flowV, int tickN,
                           int hueRgb, int tickRgb, boolean foot) {}

    /** 六条环（HTML 逐字抄；{@link #RING_COUNT} 只取前 N 条）。 */
    public static final RingSpec[] RING_TABLE = {
            new RingSpec(0.20f, 1.50f, 0.00f, 0.05f, 0.0f, 0.00f, 0.10f, 4, 0.62f, 20,
                    0x7EE4FF, 0xFFC474, true),
            new RingSpec(0.92f, 1.26f, 0.10f, 0.10f, 1.7f, 0.40f, -0.07f, 3, -0.95f, 16,
                    0x7EE4FF, 0xFFC474, false),
            new RingSpec(1.46f, 1.40f, 1.42f, 0.06f, 3.1f, 0.20f, 0.06f, 5, 0.48f, 20,
                    0x96F0FF, 0x96F0FF, false),
            new RingSpec(1.95f, 0.95f, 0.42f, 0.12f, 4.4f, 1.00f, -0.12f, 3, -1.15f, 14,
                    0xFFC474, 0xFFC474, false),
            new RingSpec(1.22f, 1.62f, 0.78f, 0.09f, 5.6f, 0.60f, 0.09f, 3, 0.72f, 18,
                    0xBEEBFF, 0xBEEBFF, false),
            new RingSpec(0.58f, 1.34f, 0.34f, 0.08f, 2.3f, 1.30f, -0.05f, 3, -0.66f, 16,
                    0xFFD296, 0xFFC474, false)
    };

    // ==================================================================== 五、环的采样密度（逐条抄 HTML）

    /** 环体切成几段圆弧（HTML {@code ARCS = 12}）。段数越多越圆，代价是顶点数线性涨。 */
    public static final int RING_ARCS = 12;

    /** 每段圆弧再细分成几小段（HTML 循环 {@code k = 0..5} ⇒ 6 个点 = 5 段）。 */
    public static final int RING_ARC_SEGMENTS = 5;

    /** 整圈柔和辉光采样成几段（HTML {@code k = 0..48} ⇒ 49 点 = 48 段）。 */
    public static final int RING_GLOW_SEGMENTS = 48;

    /** 地面淡影采样成几段（HTML {@code k = 0..40}）。 */
    public static final int RING_FOOT_SEGMENTS = 40;

    /** 地面淡影离地多高（格）—— 太低会与地面 z-fighting。 */
    public static final float RING_FOOT_Y_OFFSET = 0.02f;

    /** 流动光点拖尾采几个点（HTML {@code k = 0..7} ⇒ 8 个点 = 7 段）。 */
    public static final int RING_TAIL_POINTS = 8;

    /** 拖尾相邻采样点之间的角距（弧度）。 */
    public static final float RING_TAIL_STEP_RAD = 0.055f;

    /** 刻度线从环半径的哪个比例起画（短刻度）。 */
    public static final float RING_TICK_SHORT_INNER = 0.965f;

    /** 短刻度到哪个比例（短刻度）。 */
    public static final float RING_TICK_SHORT_OUTER = 1.035f;

    /** 长刻度的起点比例。 */
    public static final float RING_TICK_LONG_INNER = 0.905f;

    /** 长刻度的终点比例。 */
    public static final float RING_TICK_LONG_OUTER = 1.10f;

    /** 刻度少于这个条数就干脆不画（HTML {@code if (tickN >= 4)}）。 */
    public static final int RING_TICK_MIN_COUNT = 4;

    // ==================================================================== 六、光点的尺寸与颜色

    /** 流动光点的<b>外晕</b>半径（世界格，HTML {@code S.dot(head, 0.100, hue, 0.20*bright)}）。 */
    public static final float FLOW_HALO_RADIUS_BLOCKS = 0.100f;
    /** 流动光点外晕的透明度。 */
    public static final float FLOW_HALO_ALPHA = 0.20f;
    /** 流动光点的<b>实心</b>半径（HTML {@code 0.046, 0.95*bright}）。 */
    public static final float FLOW_CORE_RADIUS_BLOCKS = 0.046f;
    /** 流动光点实心的透明度。 */
    public static final float FLOW_CORE_ALPHA = 0.95f;
    /** 流动光点<b>头部白点</b>的半径（HTML {@code S.dot(pt(th-dir*0.10), 0.028, [235,252,255], 0.55*bright)}）。 */
    public static final float FLOW_WHITE_RADIUS_BLOCKS = 0.028f;
    /** 流动光点头部白点的透明度。 */
    public static final float FLOW_WHITE_ALPHA = 0.55f;
    /** 头部白点相对流动光点的角滞后（弧度）。 */
    public static final float FLOW_WHITE_LAG_RAD = 0.10f;

    /** 小光点的半径（世界格，HTML {@code S.dot(p.pos, 0.030, [196,230,255], a)}）。 */
    public static final float STAR_SMALL_RADIUS_BLOCKS = 0.030f;
    /** 大光点<b>外晕</b>的半径（HTML {@code 0.17, a*0.22}）。 */
    public static final float STAR_BIG_HALO_RADIUS_BLOCKS = 0.17f;
    /** 大光点外晕的透明度倍率（相对该点算出来的 a）。 */
    public static final float STAR_BIG_HALO_ALPHA_MUL = 0.22f;
    /** 大光点<b>实心</b>的半径（HTML {@code 0.058}）。 */
    public static final float STAR_BIG_CORE_RADIUS_BLOCKS = 0.058f;
    /** 大光点的占比（HTML {@code Math.random() < 0.10}）。 */
    public static final float STAR_BIG_FRACTION = 0.10f;

    /** 小光点的颜色（RGB）。 */
    public static final int STAR_SMALL_RGB = 0xC4E6FF;
    /** 大光点外晕的颜色（RGB）。 */
    public static final int STAR_BIG_HALO_RGB = 0xFFCE8C;
    /** 大光点实心的颜色（RGB）。 */
    public static final int STAR_BIG_CORE_RGB = 0xFFD89E;
    /** 流动光点头部白点的颜色（RGB）。 */
    public static final int FLOW_WHITE_RGB = 0xEBFCFF;
    /** 地面淡影的颜色（RGB，HTML 写死 {@code [126,228,255]}，不跟该条的 hue 走）。 */
    public static final int RING_FOOT_RGB = 0x7EE4FF;

    /** 小光点的基准透明度（HTML {@code 0.42}）。 */
    public static final float STAR_SMALL_ALPHA = 0.42f;
    /** 大光点的基准透明度（HTML {@code 0.85}）。 */
    public static final float STAR_BIG_ALPHA = 0.85f;

    /**
     * 连线的四档颜色（HTML {@code cols}）。第 0 档最近、第 3 档最远。
     * <p>配 {@link #FIELD_LINK_BAND_ALPHA}：越近越亮 ⇒ "近的点对连得实、远的自然断开"。
     */
    public static final int[] FIELD_LINK_BAND_RGB = {0x78BEFF, 0x70B8FF, 0x68B2FF, 0x60ACFF};

    /** 连线的四档透明度（HTML {@code alps}），还要乘点场亮度。 */
    public static final float[] FIELD_LINK_BAND_ALPHA = {0.06f, 0.12f, 0.21f, 0.36f};

    // ==================================================================== 七、点场的运动

    /** 点场中心相对<b>玩家脚底</b>的高度（格，HTML {@code fieldC.y = player.y + 1.15}）。 */
    public static final float FIELD_CENTER_Y_BLOCKS = 1.15f;

    /** 点场中心追随玩家的指数时间常数（秒，HTML {@code dt/1.15}）。 */
    public static final float FIELD_FOLLOW_TAU_SEC = 1.15f;

    /** 点的仰角范围（HTML {@code (random()-0.5)*0.84} ⇒ ±0.42 弧度）—— 压扁成壳层，别钻到地里。 */
    public static final float FIELD_ELEVATION_SPAN_RAD = 0.84f;

    /** 点的半径抖动幅度下限（格，HTML {@code 0.05 + random()*0.14}）。 */
    public static final float FIELD_WOBBLE_MIN_BLOCKS = 0.05f;
    /** 点的半径抖动幅度上限（格）。 */
    public static final float FIELD_WOBBLE_MAX_BLOCKS = 0.19f;

    /** 点的抖动角频率下限（弧度/秒，HTML {@code 0.20 + random()*0.5}）。 */
    public static final float FIELD_WOBBLE_W_MIN = 0.20f;
    /** 点的抖动角频率上限。 */
    public static final float FIELD_WOBBLE_W_MAX = 0.70f;

    /** 点的闪烁角频率下限（HTML {@code 0.6 + random()*1.6}）。 */
    public static final float FIELD_TWINKLE_W_MIN = 0.6f;
    /** 点的闪烁角频率上限。 */
    public static final float FIELD_TWINKLE_W_MAX = 2.2f;

    /** 点的闪烁谷值（HTML {@code 0.45 + 0.55*sin}）。 */
    public static final float FIELD_TWINKLE_MIN = 0.45f;

    /** 点不能低于"玩家脚底 + 这么多格"（HTML {@code if (y < player.y + 0.12)}）。 */
    public static final float FIELD_MIN_Y_ABOVE_FEET = 0.12f;

    /** 跑得比"外半径 + 这么多"还远 ⇒ 悄悄在另一侧重生（格）。 */
    public static final float FIELD_RESPAWN_MARGIN_BLOCKS = 0.35f;

    /** 重生时半径参数的随机跳变幅度（± 一半，HTML {@code (random()-0.5)*0.3}）。 */
    public static final float FIELD_RESPAWN_T_RANGE = 0.3f;

    /** 重生后的淡入速度（每秒，HTML {@code dt*1.7}）。 */
    public static final float FIELD_FADE_IN_PER_SEC = 1.7f;

    /** 壳层外缘的淡出起点（外半径 + 这么多格开始变暗，HTML {@code outer + 0.25}）。 */
    public static final float FIELD_EDGE_FADE_START_BLOCKS = 0.25f;

    /** 壳层外缘的淡出跨度（格，HTML {@code /0.55}）。 */
    public static final float FIELD_EDGE_FADE_SPAN_BLOCKS = 0.55f;

    /** 呼吸时连线阈值的摆动幅度（HTML {@code 1 ± 0.22}）。 */
    public static final float FIELD_LINK_BREATH_AMP = 0.22f;

    /** 连线按距离分几档（HTML {@code bands = 4} 档）。 */
    public static final int FIELD_LINK_BANDS = 4;

    /**
     * 连线的<b>计数排序桶数</b>（实现细节，不是美术参数）。
     * <pre>
     *   HTML 是"把所有近的点对按距离全排序，再从近到远贪心接线"。
     *   Java 侧不许每帧 new 一个对象数组 ⇒ 换成<b>定桶计数排序</b>：
     *   把 [0, 阈值) 均分成 {@code FIELD_LINK_SORT_BUCKETS} 桶、桶内顺序任意，
     *   再按桶从小到大贪心。桶数 32 = 每档 8 桶 ⇒ 贪心结果与"全排序"几乎逐条相同，
     *   而全程只用一个预分配的 int[]。
     * </pre>
     */
    public static final int FIELD_LINK_SORT_BUCKETS = 32;

    /**
     * 候选点对的预分配上限（实现细节）。
     * <p>本轮参数下实测候选对数见 {@code ShanhaiHoloSurround.selfTest()}；
     * 超出上限时只取先扫到的那些（观感上只是"有几条线没连上"，不会崩、也不会变慢）。
     */
    public static final int FIELD_LINK_MAX_CANDIDATES = 16384;

    // ==================================================================== 八、渲染侧的实现常量

    /**
     * 近平面深度（格）。与 {@code GameRenderer} 用的 {@code 0.05} 一致。
     * <p>用途：<b>环绕层是围着玩家的</b> ⇒ 第一视角下相机就在环里面，
     * 绝大多数线段都会<b>跨过近平面</b> ⇒ 必须先按 {@code w >= NEAR} 裁剪再投影，
     * 否则 {@code w<0} 的那一端会被投影到屏幕的另一侧（整条线横穿半个屏幕）。
     */
    public static final float NEAR_DEPTH_BLOCKS = 0.05f;

    /** 渲染侧：一条线段 = 一个四边形（4 个顶点）—— 线宽靠"面向相机的四边形"实现，不吃 GL 的线宽。 */
    public static final int VERTS_PER_SEGMENT = 4;

    /** 渲染侧：一个光点 = 4 个退化三角形 = 4 个四边形 = 16 个顶点（见 {@code ShanhaiHoloSurroundRenderer}）。 */
    public static final int VERTS_PER_DOT = 16;

    /** 渲染侧：单个顶点多少字节（{@code POSITION_COLOR} = 3×float + 4×byte = 16）。 */
    public static final int BYTES_PER_VERTEX = 16;

    /** 每帧那个 {@code BufferBuilder} 的容量参数（与 {@link ShanhaiHoloMenuRenderer} 同一个口径）。 */
    public static final int PER_FRAME_BUFFER_CAPACITY = 1 << 14;

    /** 先算好的"容量字节数"：{@code BufferBuilder(int)} 内部是 {@code capacity * 6}。 */
    public static final int PER_FRAME_BUFFER_BYTES = PER_FRAME_BUFFER_CAPACITY * 6;

    // ==================================================================== 九、读数

    /** 一行摘要（日志用，也便于与用户手上的那三行黄字对照）。 */
    public static String summary() {
        final float[] shell = ShanhaiHoloSurround.shellRadii();
        return "rings=" + RING_COUNT
                + " ringScale=" + fmt(RING_SCALE, 2)
                + " ringBright=" + fmt(RING_BRIGHT, 2)
                + " tickDensity=" + fmt(RING_TICK_DENSITY, 2)
                + " flow=" + fmt(RING_FLOW_SCALE, 1)
                + " precess=" + fmt(RING_PRECESS_SCALE, 2)
                + " | points=" + FIELD_POINT_COUNT
                + " shell=" + fmt(shell[0], 2) + "-" + fmt(shell[1], 2) + "格"
                + " link=" + fmt(FIELD_LINK_THRESHOLD_BLOCKS, 2) + "x" + FIELD_LINK_MAX_PER_POINT
                + " breath=" + fmt(FIELD_LINK_PERIOD_SEC, 0) + "s"
                + " fieldBright=" + fmt(FIELD_BRIGHT, 2)
                + " | global=" + fmt(GLOBAL_BRIGHT, 2)
                + " | ringMaxR=" + fmt(RING_MAX_RADIUS_BLOCKS, 2) + "格";
    }

    /** 固定两位小数的数字格式化（不依赖 Locale，避免小数点变成逗号）。 */
    public static String fmt(float v, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }
}
