package com.dishanhai.gt_shanhai.client.holo;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 山海重构 · <b>全息环绕层（数据环 + 星尘网格）—— 全部可调参数的【唯一一份】定义</b>。
 *
 * <h2>0. 这一份是什么，从哪来</h2>
 * 用户点单：那 5 块全息板旁边太空了，要加一层<b>环绕在玩家周围</b>的点与线条。
 * 美术已经在浏览器里验收过，规格就是这一份单文件 HTML：
 * <pre>
 *   17-全息环绕-结合版.html
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

    // ==================================================================== 一、用户点单的那批参数（原 14 项 + 本轮新增的「跟随延迟」）

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

    /**
     * 点场中心追随玩家的指数时间常数（秒，HTML {@code dt/1.15}）——<b>出厂默认值 = 0（即时）</b>。
     *
     * <p>🔴 2026-10-06 16:2x（用户第 ④ 条）：「这个类似与北斗七星的渲染完全跟不上玩家的速度
     * （就是和其他的渲染不同步，跟随玩家太慢了）」——"像星座"的那一片就是点场 + 连线，
     * 而它的中心一直被这一条以 τ 秒的指数平滑拖着走：
     * <pre>
     *   fieldC += (player − fieldC) · (1 − e^(−dt/τ))
     *   ⇒ 玩家以 v 匀速跑，稳态"拖在身后"的距离 = v · dt · (1−k)/k，k = 1 − e^(−dt/τ)
     *   ⇒ v = 5 格/秒、τ = 1.15 秒、60 fps ⇒ 5.71 格（连续近似 v·τ = 5.75 格）
     *   ⇒ 离线装置实测：τ=1.15 ⇒ 5.708 格（用户手上那一版）；τ=0 ⇒ 0.000 格
     * </pre>
     * 现在这一个常量只是"出厂值"，运行期生效的是 {@code param(P_FIELD_FOLLOW_TAU)}
     * （用户能在面板高级调参页上拨那一项「跟随延迟」）。
     *
     * <p>🔴 <b>为什么出厂值改成 0.00</b>：1.15 秒是照 HTML 那份效果抄来的"整片星尘缓缓飘过来"，
     * 但第一人称跑起来它就是"整片星尘拖在身后 5.7 格"，而<b>同一层的其他部分全是即时跟随</b>
     * （数据环直接读当前帧玩家位置；点与点之间的连线用的是同一批点；光晕与文字层挂在板上）
     * ⇒ 只有这一片不同步，用户的描述（"跟不上"、"和其他渲染不同步"）与实测完全吻合。
     * <p>0 在 {@code ShanhaiHoloSurround#fieldAdvance} 里是<b>短路</b>（当场等于当前帧玩家位置），
     * 不是"极小的时间常数"—— 后者会退化成 v·dt·(1−k)/k 那一档的残余滞后。
     */
    public static final float FIELD_FOLLOW_TAU_SEC = 0.0f;

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

    /**
     * 一行摘要（日志用，也便于与用户手上的那三行黄字对照）。
     *
     * <p>🔴 2026-10-06（第二轮）：<b>这里读的数改成"运行期生效的那一份"</b>
     * （{@link #param(int)}），不再是编译期常量 —— 否则用户在面板里换了档，
     * 日志打的还是"出厂参数"，那就是一条会撒谎的读数。
     */
    public static String summary() {
        final float[] shell = ShanhaiHoloSurround.shellRadii();
        return "preset=" + presetName(preset) + "(" + preset + ")"
                + " rings=" + paramInt(P_RING_COUNT)
                + " ringScale=" + fmt(param(P_RING_SCALE), 2)
                + " ringBright=" + fmt(param(P_RING_BRIGHT), 2)
                + " tickDensity=" + fmt(param(P_RING_TICK_DENSITY), 2)
                + " flow=" + fmt(param(P_RING_FLOW_SCALE), 1)
                + " precess=" + fmt(param(P_RING_PRECESS_SCALE), 2)
                + " | points=" + paramInt(P_FIELD_POINT_COUNT)
                + " shell=" + fmt(shell[0], 2) + "-" + fmt(shell[1], 2) + "格"
                + " link=" + fmt(param(P_FIELD_LINK_THRESHOLD), 2)
                + "x" + paramInt(P_FIELD_LINK_MAX_PER_POINT)
                + " breath=" + fmt(param(P_FIELD_LINK_PERIOD), 0) + "s"
                + " fieldBright=" + fmt(param(P_FIELD_BRIGHT), 2)
                + " followDelay=" + fmt(param(P_FIELD_FOLLOW_TAU), 2) + "s"
                + " | global=" + fmt(param(P_GLOBAL_BRIGHT), 2)
                + " | ringMaxR=" + fmt(RING_MAX_RADIUS_BLOCKS, 2) + "格";
    }

    /** 固定两位小数的数字格式化（不依赖 Locale，避免小数点变成逗号）。 */
    public static String fmt(float v, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }

    // ==================================================================== 十、🆕 四档预设 + 15 项【运行期生效】的取值

    /*
     * 2026-10-06（第二轮点单）：环绕特效要做成游戏内可调。
     *
     * 🔴 为什么需要这一节：上面那批常量是 `public static final` —— 编译期常量会被 javac
     *    【内联】到每一个调用点（`ShanhaiHoloSurround.T.*` 就是那一批），运行期改不动。
     *    所以本轮把"生效值"搬进本类的一个静态数组 LIVE，并把 §1 的常量降级为
     *    【出厂默认值】（= 「中」档），调用点一律改读 {@link #param(int)}。
     *    这样只有一处存值（LIVE），不存在"两处各存一份必然漂"。
     *
     * 🔴 版面为什么是"4 项一页"而不是原设计的 3 页 6/7/1：
     *    面板的行数 = {@link ShanhaiHoloMenuTuning#BOARD_COUNT} = 5，而这个 5 同时被
     *    【五块菜单板】用（RING_STEP_DEG = 360/5）⇒ 加行数就会挪动用户已经验收过的菜单板，
     *    那是红线。所以 5 行网格一个数都不动：**每页 4 项（4 行）+ 第 5 行导航**，
     *    原先 14 项 ⇒ 4 页（4/4/4/2）；🆕 本轮追加第 15 项「跟随延迟」⇒ 4 页（4/4/4/3），仍 4 页。
     */

    // ---- 15 项的下标（顺序 = 面板高级页的翻页顺序，也是报表顺序）----

    public static final int P_RING_COUNT = 0;
    public static final int P_RING_SCALE = 1;
    public static final int P_RING_BRIGHT = 2;
    public static final int P_RING_TICK_DENSITY = 3;
    public static final int P_RING_FLOW_SCALE = 4;
    public static final int P_RING_PRECESS_SCALE = 5;
    public static final int P_FIELD_POINT_COUNT = 6;
    public static final int P_FIELD_INNER = 7;
    public static final int P_FIELD_OUTER = 8;
    public static final int P_FIELD_LINK_THRESHOLD = 9;
    public static final int P_FIELD_LINK_MAX_PER_POINT = 10;
    public static final int P_FIELD_LINK_PERIOD = 11;
    public static final int P_FIELD_BRIGHT = 12;
    public static final int P_GLOBAL_BRIGHT = 13;

    /**
     * 🆕 2026-10-06 16:2x（用户第 ④ 条点单）：<b>第 15 项 = 点场（星尘）中心的"跟随延迟"</b>（秒）。
     *
     * <p>加它的理由：用户报"星尘跟不上玩家"，而修法是"出厂即时跟随"——
     * 但他明确想要的话，可以自己把这一项拨大，回到那种"整片星尘慢慢飘过来"的旧观感。
     * 拨到 1.15 秒就<b>逐格等于</b>他抱怨的那一版（离线装置的负对照就是拿这个值跑的）。
     *
     * <p>⚠️ 它只影响<b>点场</b>（星尘点 + 连线 + 它们的壳层中心）；
     * <b>数据环</b>从来就是每帧读当前帧的玩家位置、压根不读这个数（离线装置 10.3 段实测）。
     *
     * <p>版面：<b>追加在最后</b>（不动任何一项的下标）⇒ 4 页变成 4/4/4/<b>3</b>，
     * 仍然是"每页 4 项 + 第 5 行导航"，仍是 4 页，5 行网格一个数都没动。
     */
    public static final int P_FIELD_FOLLOW_TAU = 14;

    /** 一共几项（14 + 1 = 15）。 */
    public static final int PARAM_COUNT = 15;

    /** 高级页每页放几项 = 面板行数 − 1（第 5 行是导航）。 */
    public static final int PARAMS_PER_PAGE = 4;

    /** 高级页一共几页 = ceil(15/4) = 4（4/4/4/3）。 */
    public static final int PAGE_COUNT = (PARAM_COUNT + PARAMS_PER_PAGE - 1) / PARAMS_PER_PAGE;

    /** 第 idx 项在第几页（0 基）。 */
    public static int pageOf(int idx) {
        return idx < 0 || idx >= PARAM_COUNT ? -1 : idx / PARAMS_PER_PAGE;
    }

    /** 第 idx 项在它那一页的第几行（0 基；行号 = 面板的行号）。 */
    public static int pageRowOf(int idx) {
        return idx < 0 || idx >= PARAM_COUNT ? -1 : idx % PARAMS_PER_PAGE;
    }

    /** （页, 页内行）⇒ 第几项；越界返回 {@code -1}。 */
    public static int indexOf(int page, int pageRow) {
        if (page < 0 || page >= PAGE_COUNT || pageRow < 0 || pageRow >= PARAMS_PER_PAGE) {
            return -1;
        }
        final int idx = page * PARAMS_PER_PAGE + pageRow;
        return idx < PARAM_COUNT ? idx : -1;
    }

    /** 某一页真的有几项（最后一页可能不满：15 = 4+4+4+3）。 */
    public static int paramCountOnPage(int page) {
        if (page < 0 || page >= PAGE_COUNT) {
            return 0;
        }
        final int left = PARAM_COUNT - page * PARAMS_PER_PAGE;
        return Math.min(PARAMS_PER_PAGE, Math.max(0, left));
    }

    /** 15 项的中文名（面板上的格子 0 与报表第一列都用它）。 */
    public static final String[] PARAM_NAME = {
            "环数", "环半径倍率", "环亮度", "刻度密度", "流动光点", "进动速度",
            "光点数", "壳层内半径", "壳层外半径", "连线阈值", "每点接线数", "呼吸周期",
            "点场亮度", "整体亮度", "跟随延迟"
    };

    /** 15 项的单位（不带空格的写法，面板上拼成 {@code "2.55 格"}）。 */
    public static final String[] PARAM_UNIT = {
            "条", "倍", "", "", "", "",
            "个", "格", "格", "格", "条", "秒",
            "", "", "秒"
    };

    /** 15 项显示几位小数。 */
    public static final int[] PARAM_DECIMALS = {
            0, 2, 2, 2, 2, 2,
            0, 2, 2, 2, 0, 1,
            2, 2, 2
    };

    /**
     * 15 项每一档 ± 一次跨多少。
     * <p>取的都是"看得见变化"的最小步子：环数 / 光点数 / 接线数是整数（步长 1 / 10 / 1），
     * 亮度与小半径是 0.05，呼吸周期是 1 秒，跟随延迟是 0.05 秒。
     */
    public static final float[] PARAM_STEP = {
            1.0f, 0.05f, 0.05f, 0.05f, 0.10f, 0.05f,
            10.0f, 0.05f, 0.05f, 0.05f, 1.0f, 1.0f,
            0.05f, 0.05f, 0.05f
    };

    /**
     * 🔴 15 项的<b>安全区间下限</b>（每一档、每一次 ± 之后都必须落在区间内，越界就钳住）。
     * <p>取值来源：HTML 那张参数面板上的滑块 min（例如环半径倍率 0.6、刻度密度 0、
     * 光点数 0、壳层内半径 1.2、外半径 2.0、连线阈值 0.5、接线数 1、周期 3、亮度 0）。
     * <p>新增的"跟随延迟"下限取 <b>0.00</b>（= 即时跟随，生产代码里 τ ≤ 0 会短路成瞬时贴合）。
     */
    public static final float[] PARAM_MIN = {
            0.0f, 0.60f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.20f, 2.00f, 0.50f, 1.0f, 3.0f,
            0.0f, 0.0f, 0.00f
    };

    /**
     * 🔴 15 项的<b>安全区间上限</b>。
     * <pre>
     *   ① 结构性的两项取【硬容量】而不是"美术喜好"：
     *      环数 ≤ {@code RING_TABLE.length}（= 6，表里就 6 条环，再多也取不到）
     *      光点数 ≤ {@code ShanhaiHoloSurround.FIELD_CAPACITY}（= 200，光点数组是预分配的定长数组）
     *   ② 其余七项取 HTML 滑块 max；亮度三项取 2（新增的"整体亮度"上限 2 与点场亮度同口径）
     *   ③ 环半径倍率的上限是 1.40 —— 再大也只会被 {@link #RING_MAX_RADIUS_BLOCKS} 硬夹到 1.75 格，
     *      放开它只会让"刻度值"与"生效值"对不上
     *   ④ 跟随延迟上限 2.00 秒（旧观感 1.15 秒要拨得到；再大就纯粹是"跟不上"了）
     * </pre>
     */
    public static final float[] PARAM_MAX = {
            RING_TABLE.length, 1.40f, 2.0f, 1.60f, 2.0f, 2.50f,
            ShanhaiHoloSurround.FIELD_CAPACITY, 4.00f, 5.00f, 1.80f, 5.0f, 30.0f,
            2.0f, 2.0f, 2.00f
    };

    // ---- 四档 ----

    /** 「关」：不画环绕层（跳过绘制遍）。<b>不动那 15 项取值</b> ⇒ 切回来现场还在。 */
    public static final int PRESET_OFF = 0;
    public static final int PRESET_WEAK = 1;
    public static final int PRESET_MID = 2;
    public static final int PRESET_STRONG = 3;
    public static final int PRESET_COUNT = 4;

    /** 出厂默认档 = 中（= 用户已经验收过的那一组数）。 */
    public static final int PRESET_DEFAULT = PRESET_MID;

    /** 档位中文名（面板四格与日志都用它）。 */
    public static String presetName(int p) {
        return switch (p) {
            case PRESET_OFF -> "关";
            case PRESET_WEAK -> "弱";
            case PRESET_MID -> "中";
            case PRESET_STRONG -> "强";
            default -> "?";
        };
    }

    /**
     * 🔴 <b>四档 × 15 项的取值表</b>（整组替换）。
     *
     * <pre>
     *   第 0 行（关）：<b>不会被写进 LIVE</b> —— 「关」只表示"不画"，15 项取值原样保留。
     *                 这一行必须与中档【逐项相同】（自检断言），它存在只是为了让"下标 = 档位号"。
     *   第 2 行（中）：<b>必须逐项等于 §1 那些常量的默认值</b>（自检里是机器比对，不靠人看）。
     *   弱 ≤ 中 ≤ 强：逐项单调（唯一反向的是"呼吸周期"—— 周期越短呼吸越快 = 越强）。
     * </pre>
     *
     * <p>🔴 <b>为什么第 15 项「跟随延迟」四档全是 0.00</b>：
     * <pre>
     *   ① 判据 ⑤ 要求逐项 弱 ≤ 中 ≤ 强 ⇒ 只要"弱"是 0（即时），"中/强"就只能 ≥ 0，
     *      而任何 &gt; 0 的值都会把用户刚报的那条 bug（星尘拖在身后）重新带回来；
     *   ② 四档是"亮不亮、多不多"的强度档，不该顺手改变"跟不跟得上"这种<b>正确性</b>属性；
     * "弱"若取非 0（比如 0.3 秒），"中"就必须 ≥ 0.3 ⇒ 用户手里的默认档又开始拖尾 —— 这是一条死路。
     * 所以四档一律 0.00，想回"慢慢飘"的人自己去高级页拨那一项（拨到 1.15 就逐格等于旧版）。
     * </pre>
     */
    public static final float[][] PRESET_VALUES = {
            // 环数  倍率   环亮   刻度   流动   进动 | 光点  内径   外径   阈值   接线  周期 | 点亮   全局   跟随
            {2, 1.40f, 1.00f, 1.60f, 1.00f, 1.00f, 200, 2.55f, 3.30f, 1.44f, 3, 15.0f, 1.50f, 1.00f, 0.00f},
            {1, 1.00f, 0.55f, 0.80f, 0.50f, 0.50f, 110, 2.20f, 3.00f, 1.10f, 2, 20.0f, 0.90f, 0.80f, 0.00f},
            {2, 1.40f, 1.00f, 1.60f, 1.00f, 1.00f, 200, 2.55f, 3.30f, 1.44f, 3, 15.0f, 1.50f, 1.00f, 0.00f},
            {3, 1.40f, 1.60f, 1.60f, 2.00f, 1.80f, 200, 2.80f, 3.90f, 1.75f, 4, 10.0f, 2.00f, 1.30f, 0.00f}
    };

    // ---- 运行期状态（唯一一份存值处；只存内存，不落盘）----

    /** 15 项当前生效值。 */
    private static final float[] LIVE = new float[PARAM_COUNT];

    /** 当前档位。 */
    private static int preset = PRESET_DEFAULT;

    static {
        System.arraycopy(PRESET_VALUES[PRESET_DEFAULT], 0, LIVE, 0, PARAM_COUNT);
    }

    /** 当前档位号。 */
    public static int preset() {
        return preset;
    }

    /** 这一帧要不要画环绕层（「关」档 = 跳过整个绘制遍）。 */
    public static boolean drawEnabled() {
        return preset != PRESET_OFF;
    }

    /** 第 idx 项当前生效值（渲染与日志读的就是它）。 */
    public static float param(int idx) {
        return idx < 0 || idx >= PARAM_COUNT ? 0.0f : LIVE[idx];
    }

    /** 第 idx 项当前生效值，取整（环数 / 光点数 / 接线数用）。 */
    public static int paramInt(int idx) {
        return Math.round(param(idx));
    }

    /** 第 idx 项的显示文字（{@code "2.55 格"} / {@code "200 个"} / {@code "1.00"}）。 */
    public static String paramText(int idx) {
        if (idx < 0 || idx >= PARAM_COUNT) {
            return "";
        }
        final String u = PARAM_UNIT[idx];
        return fmt(LIVE[idx], PARAM_DECIMALS[idx]) + (u.isEmpty() ? "" : " " + u);
    }

    /** 第 idx 项"± 一步"的文字（面板上那颗 ± 按钮写的就是它）。 */
    public static String paramStepText(int idx) {
        if (idx < 0 || idx >= PARAM_COUNT) {
            return "";
        }
        final String u = PARAM_UNIT[idx];
        return fmt(PARAM_STEP[idx], PARAM_DECIMALS[idx]) + (u.isEmpty() ? "" : " " + u);
    }

    /**
     * 🔴 <b>切档</b>（整组替换）。
     *
     * <p>「关」= 只置档位、<b>一个数都不改</b> ⇒ 切回弱/中/强时按那一档整组重写，
     * 而切回"关之前的那些数"不需要恢复动作（它们从来没被改过）。
     *
     * @return 生效之后的档位号（越界一律落回 {@link #PRESET_DEFAULT}）
     */
    public static int setPreset(int next) {
        preset = next < 0 || next >= PRESET_COUNT ? PRESET_DEFAULT : next;
        if (preset != PRESET_OFF) {
            System.arraycopy(PRESET_VALUES[preset], 0, LIVE, 0, PARAM_COUNT);
            settleShell();
        }
        return preset;
    }

    /**
     * 🔴 <b>单步 ±</b>（面板高级页每行那两颗按钮）。
     *
     * @param dir {@code +1} / {@code -1}
     * @return 步进之后的生效值（<b>已经钳在安全区间内</b>）
     */
    public static float nudge(int idx, int dir) {
        if (idx < 0 || idx >= PARAM_COUNT) {
            return 0.0f;
        }
        return setParam(idx, LIVE[idx] + (dir >= 0 ? PARAM_STEP[idx] : -PARAM_STEP[idx]));
    }

    /** 直接设某一项（自检与将来的命令入口用）；返回值同样已经钳过。 */
    public static float setParam(int idx, float want) {
        if (idx < 0 || idx >= PARAM_COUNT) {
            return 0.0f;
        }
        LIVE[idx] = clampParam(idx, want);
        settleShell();
        return LIVE[idx];
    }

    /** 把所有档位与 15 项还原成出厂状态（离线自检 / 复位用）。 */
    public static void resetTuning() {
        preset = PRESET_DEFAULT;
        System.arraycopy(PRESET_VALUES[PRESET_DEFAULT], 0, LIVE, 0, PARAM_COUNT);
        settleShell();
    }

    /** 单项钳制（只认它自己那一列的上下限）。 */
    public static float clampParam(int idx, float v) {
        if (idx < 0 || idx >= PARAM_COUNT) {
            return 0.0f;
        }
        final float lo = PARAM_MIN[idx];
        final float hi = PARAM_MAX[idx];
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 🔴 <b>物理约束的收口处</b>：壳层内半径 + 间隙 ≤ 外半径 − 最小壳厚。
     * <pre>
     *   需要：outer ≥ inner + {@link #FIELD_INNER_GAP_BLOCKS} + {@link #FIELD_MIN_SHELL_THICKNESS_BLOCKS}
     *   先抬外半径（抬得动就抬）；实在抬不动（已经顶到上限）就把内半径压下来。
     *   两个方向都能得到一组合法值，因为：
     *     outer≤5.0、inner≥1.2 ⇒ 内半径最多被压到 max(1.2, 5.0−0.67)=4.33 —— 恒可行
     * </pre>
     * ⚠️ 规则 ②（{@code shellRadii()}）在渲染侧还会再抬一次内半径（环划到哪点场就退到环外），
     * 所以这里是"双保险"：即便有人绕开了这一条，渲染也不会画出重叠的两套。
     */
    private static void settleShell() {
        final float need = FIELD_INNER_GAP_BLOCKS + FIELD_MIN_SHELL_THICKNESS_BLOCKS;
        float inner = LIVE[P_FIELD_INNER];
        float outer = LIVE[P_FIELD_OUTER];
        if (outer < inner + need) {
            final float want = inner + need;
            if (want <= PARAM_MAX[P_FIELD_OUTER]) {
                outer = want;
            } else {
                outer = PARAM_MAX[P_FIELD_OUTER];
                inner = Math.max(PARAM_MIN[P_FIELD_INNER], outer - need);
            }
        }
        LIVE[P_FIELD_INNER] = inner;
        LIVE[P_FIELD_OUTER] = outer;
    }

    /** 第 idx 项的<b>出厂默认值</b>（= 「中」档的值）—— 这是"下标 ⇒ 常量"的唯一一处对应。 */
    public static float defaultOf(int idx) {
        return switch (idx) {
            case P_RING_COUNT -> RING_COUNT;
            case P_RING_SCALE -> RING_SCALE;
            case P_RING_BRIGHT -> RING_BRIGHT;
            case P_RING_TICK_DENSITY -> RING_TICK_DENSITY;
            case P_RING_FLOW_SCALE -> RING_FLOW_SCALE;
            case P_RING_PRECESS_SCALE -> RING_PRECESS_SCALE;
            case P_FIELD_POINT_COUNT -> FIELD_POINT_COUNT;
            case P_FIELD_INNER -> FIELD_INNER_RADIUS_BLOCKS;
            case P_FIELD_OUTER -> FIELD_OUTER_RADIUS_BLOCKS;
            case P_FIELD_LINK_THRESHOLD -> FIELD_LINK_THRESHOLD_BLOCKS;
            case P_FIELD_LINK_MAX_PER_POINT -> FIELD_LINK_MAX_PER_POINT;
            case P_FIELD_LINK_PERIOD -> FIELD_LINK_PERIOD_SEC;
            case P_FIELD_BRIGHT -> FIELD_BRIGHT;
            case P_GLOBAL_BRIGHT -> GLOBAL_BRIGHT;
            case P_FIELD_FOLLOW_TAU -> FIELD_FOLLOW_TAU_SEC;
            default -> Float.NaN;
        };
    }

    // ---- 自检 ----

    /** 自检结果（{@code bad == null} = 全过）。 */
    public record Report(int pass, int total, String bad) {
        public boolean ok() {
            return bad == null;
        }
    }

    /** 一条判据：{@code what} 失败时写进 bad。 */
    private static void c(StringBuilder bad, int[] n, String what, boolean ok, String actual) {
        n[1]++;
        if (ok) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到 ").append(actual).append(';');
        }
    }

    /**
     * 🔴 <b>四档预设 + 15 项取值的离线自检</b>（机器比对，不靠人看）。
     *
     * <p>9 组判据 + 4 条负对照，全部走的是上面那些生产函数（{@link #setPreset} /
     * {@link #nudge} / {@link #clampParam}），没有另写一套算术。
     * <p>⚠️ 它<b>不改现场</b>：进来自存 {@code LIVE} 与档位，出去原样放回 ——
     * 万一日后有人把它挂进游戏里跑，也不会把用户的档位抹掉。
     */
    public static Report presetSelfCheck() {
        final float[] savedLive = LIVE.clone();
        final int savedPreset = preset;
        try {
            final StringBuilder bad = new StringBuilder();
            final int[] n = {0, 0};

            // ① 中档 == 那批出厂默认值（逐项，机器比对）
            for (int i = 0; i < PARAM_COUNT; i++) {
                final float def = defaultOf(i);
                final float got = PRESET_VALUES[PRESET_MID][i];
                c(bad, n, "①「中」档第 " + (i + 1) + " 项「" + PARAM_NAME[i] + "」== 出厂默认值 "
                                + fmt(def, 2), Float.compare(got, def) == 0, fmt(got, 2));
            }
            // ② 关档那一行不写值 ⇒ 必须与中档逐项相同
            for (int i = 0; i < PARAM_COUNT; i++) {
                c(bad, n, "②「关」档第 " + (i + 1) + " 项与中档相同（关档不写值）",
                        Float.compare(PRESET_VALUES[PRESET_OFF][i], PRESET_VALUES[PRESET_MID][i]) == 0,
                        fmt(PRESET_VALUES[PRESET_OFF][i], 2));
            }
            // ③ 每一档、每一项都落在安全区间内
            for (int p = 0; p < PRESET_COUNT; p++) {
                for (int i = 0; i < PARAM_COUNT; i++) {
                    final float v = PRESET_VALUES[p][i];
                    final boolean ok = v >= PARAM_MIN[i] - 1.0e-6f && v <= PARAM_MAX[i] + 1.0e-6f;
                    c(bad, n, "③「" + presetName(p) + "」档第 " + (i + 1) + " 项「" + PARAM_NAME[i]
                            + "」落在 [" + fmt(PARAM_MIN[i], 2) + ", " + fmt(PARAM_MAX[i], 2) + "]",
                            ok, fmt(v, 2));
                }
            }
            // ④ 壳层物理约束：外 − 内 ≥ 间隙 + 最小壳厚
            for (int p = 0; p < PRESET_COUNT; p++) {
                final float inner = PRESET_VALUES[p][P_FIELD_INNER];
                final float outer = PRESET_VALUES[p][P_FIELD_OUTER];
                final float need = FIELD_INNER_GAP_BLOCKS + FIELD_MIN_SHELL_THICKNESS_BLOCKS;
                c(bad, n, "④「" + presetName(p) + "」档 外−内 = " + fmt(outer - inner, 2)
                                + " ≥ " + fmt(need, 2) + "（间隙 " + fmt(FIELD_INNER_GAP_BLOCKS, 2)
                                + " + 最小壳厚 " + fmt(FIELD_MIN_SHELL_THICKNESS_BLOCKS, 2) + "）",
                        outer - inner >= need - 1.0e-6f, fmt(outer - inner, 2));
            }
            // ⑤ 弱 ≤ 中 ≤ 强 逐项单调（周期反向：越短越强），且不允许"三档全一样"
            for (int i = 0; i < PARAM_COUNT; i++) {
                final float w = PRESET_VALUES[PRESET_WEAK][i];
                final float m = PRESET_VALUES[PRESET_MID][i];
                final float s = PRESET_VALUES[PRESET_STRONG][i];
                final boolean reverse = i == P_FIELD_LINK_PERIOD;   // 周期越短 = 呼吸越快 = 越强
                final boolean ok = reverse ? (w >= m && m >= s) : (w <= m && m <= s);
                c(bad, n, "⑤ 第 " + (i + 1) + " 项「" + PARAM_NAME[i] + "」弱≤中≤强"
                        + (reverse ? "（周期是反向：越短越强）" : ""), ok, fmt(w, 2) + " / " + fmt(m, 2)
                        + " / " + fmt(s, 2));
            }
            // ⑥ 结构性上限：环数 ≤ 环表长度、光点数 ≤ 光点数组容量
            for (int p = PRESET_WEAK; p <= PRESET_STRONG; p++) {
                c(bad, n, "⑥「" + presetName(p) + "」档 环数 ≤ " + RING_TABLE.length + "（环表就这么多条）",
                        PRESET_VALUES[p][P_RING_COUNT] <= RING_TABLE.length,
                        fmt(PRESET_VALUES[p][P_RING_COUNT], 0));
                c(bad, n, "⑥「" + presetName(p) + "」档 光点数 ≤ " + ShanhaiHoloSurround.FIELD_CAPACITY
                                + "（光点数组是定长预分配）",
                        PRESET_VALUES[p][P_FIELD_POINT_COUNT] <= ShanhaiHoloSurround.FIELD_CAPACITY,
                        fmt(PRESET_VALUES[p][P_FIELD_POINT_COUNT], 0));
            }
            // ⑦ 运行期钳制：对每一项，把值拖到区间外一步 ⇒ 结果必须仍在区间内
            for (int i = 0; i < PARAM_COUNT; i++) {
                final float low = setParam(i, PARAM_MIN[i] - PARAM_STEP[i] * 3.0f);
                c(bad, n, "⑦ 第 " + (i + 1) + " 项「" + PARAM_NAME[i] + "」拖到底仍 ≥ " + fmt(PARAM_MIN[i], 2),
                        low >= PARAM_MIN[i] - 1.0e-6f, fmt(low, 2));
                final float high = setParam(i, PARAM_MAX[i] + PARAM_STEP[i] * 3.0f);
                c(bad, n, "⑦ 第 " + (i + 1) + " 项「" + PARAM_NAME[i] + "」拖到顶仍 ≤ " + fmt(PARAM_MAX[i], 2),
                        high <= PARAM_MAX[i] + 1.0e-6f, fmt(high, 2));
            }
            // ⑧ 「关」档不写值：切到关，15 项必须逐项不变
            resetTuning();
            final float[] before = LIVE.clone();
            setPreset(PRESET_OFF);
            for (int i = 0; i < PARAM_COUNT; i++) {
                c(bad, n, "⑧ 切到「关」之后第 " + (i + 1) + " 项「" + PARAM_NAME[i] + "」原样保留",
                        Float.compare(before[i], LIVE[i]) == 0, fmt(LIVE[i], 2));
            }
            // ⑨ 三档整组替换：切到某一档之后 LIVE 必须逐项等于那一档的表
            for (int p = PRESET_WEAK; p <= PRESET_STRONG; p++) {
                setPreset(p);
                for (int i = 0; i < PARAM_COUNT; i++) {
                    c(bad, n, "⑨ 切到「" + presetName(p) + "」之后第 " + (i + 1) + " 项「"
                                    + PARAM_NAME[i] + "」== 那一档的表",
                            Float.compare(LIVE[i], PRESET_VALUES[p][i]) == 0, fmt(LIVE[i], 2));
                }
            }

            // ---- 负对照：判据必须真的会响（否则"全绿"只是因为判据从不报错）----
            final StringBuilder neg = new StringBuilder();
            // N1：把中档第 1 项改掉 ⇒ ① 组必须报红
            final float[][] n1 = copyTable();
            n1[PRESET_MID][P_RING_COUNT] = 5.0f;
            final String n1bad = scanTable(n1);
            c(bad, n, "N1 负对照：把「中」档第 1 项改掉 ⇒ 判据必须报出「!= 出厂默认值」",
                    n1bad.contains("①"), n1bad.isEmpty() ? "（没有报红）" : "报红了");
            // N2：把强档外半径压到内半径以下 ⇒ ④ 组必须报红
            final float[][] n2 = copyTable();
            n2[PRESET_STRONG][P_FIELD_OUTER] = n2[PRESET_STRONG][P_FIELD_INNER] - 0.1f;
            final String n2bad = scanTable(n2);
            c(bad, n, "N2 负对照：把强档外半径压到内半径以内 ⇒ 判据必须报出壳层约束被破坏",
                    n2bad.contains("④"), n2bad.isEmpty() ? "（没有报红）" : "报红了");
            // N3：把弱档亮度抬到强档之上 ⇒ ⑤ 组必须报红
            final float[][] n3 = copyTable();
            n3[PRESET_WEAK][P_FIELD_BRIGHT] = 2.0f;
            final String n3bad = scanTable(n3);
            c(bad, n, "N3 负对照：把弱档点场亮度抬到强档之上 ⇒ 判据必须报出单调性被破坏",
                    n3bad.contains("⑤"), n3bad.isEmpty() ? "（没有报红）" : "报红了");
            // N4：把某档光点数设成容量 +1 ⇒ ⑥ 组必须报红
            final float[][] n4 = copyTable();
            n4[PRESET_MID][P_FIELD_POINT_COUNT] = ShanhaiHoloSurround.FIELD_CAPACITY + 1.0f;
            final String n4bad = scanTable(n4);
            c(bad, n, "N4 负对照：把光点数设成容量+1 ⇒ 判据必须报出结构性上限被顶破",
                    n4bad.contains("⑥"), n4bad.isEmpty() ? "（没有报红）" : "报红了");
            // neg 只是把四条负对照的"报红内容"留个痕（便于日后排查为什么某条不响）
            neg.append("N1=").append(n1bad.isEmpty() ? "-" : "red")
                    .append(" N2=").append(n2bad.isEmpty() ? "-" : "red")
                    .append(" N3=").append(n3bad.isEmpty() ? "-" : "red")
                    .append(" N4=").append(n4bad.isEmpty() ? "-" : "red");
            if (neg.length() == 0) {
                throw new IllegalStateException();
            }

            return new Report(n[0], n[1], bad.length() == 0 ? null : bad.toString());
        } finally {
            System.arraycopy(savedLive, 0, LIVE, 0, PARAM_COUNT);
            preset = savedPreset;
        }
    }

    /** 复制一张档位表（负对照用）。 */
    private static float[][] copyTable() {
        final float[][] out = new float[PRESET_COUNT][PARAM_COUNT];
        for (int p = 0; p < PRESET_COUNT; p++) {
            System.arraycopy(PRESET_VALUES[p], 0, out[p], 0, PARAM_COUNT);
        }
        return out;
    }

    /**
     * 把 ①②③④⑤⑥ 六组判据跑在一张<b>给定</b>的表上，返回失败项的组号前缀串。
     * <p>抽出来只为一件事：负对照要拿一张<b>故意改坏</b>的表跑同一套判据，
     * 否则"判据会响"这件事就永远只是推理。
     */
    private static String scanTable(float[][] table) {
        final StringBuilder bad = new StringBuilder();
        for (int i = 0; i < PARAM_COUNT; i++) {
            if (Float.compare(table[PRESET_MID][i], defaultOf(i)) != 0) {
                bad.append('①');
            }
            if (Float.compare(table[PRESET_OFF][i], table[PRESET_MID][i]) != 0) {
                bad.append('②');
            }
        }
        for (int p = 0; p < PRESET_COUNT; p++) {
            for (int i = 0; i < PARAM_COUNT; i++) {
                final float v = table[p][i];
                if (v < PARAM_MIN[i] - 1.0e-6f || v > PARAM_MAX[i] + 1.0e-6f) {
                    bad.append('③');
                }
            }
            final float need = FIELD_INNER_GAP_BLOCKS + FIELD_MIN_SHELL_THICKNESS_BLOCKS;
            if (table[p][P_FIELD_OUTER] - table[p][P_FIELD_INNER] < need - 1.0e-6f) {
                bad.append('④');
            }
        }
        for (int i = 0; i < PARAM_COUNT; i++) {
            final float w = table[PRESET_WEAK][i];
            final float m = table[PRESET_MID][i];
            final float s = table[PRESET_STRONG][i];
            final boolean reverse = i == P_FIELD_LINK_PERIOD;
            if (reverse ? !(w >= m && m >= s) : !(w <= m && m <= s)) {
                bad.append('⑤');
            }
        }
        for (int p = PRESET_WEAK; p <= PRESET_STRONG; p++) {
            if (table[p][P_RING_COUNT] > RING_TABLE.length
                    || table[p][P_FIELD_POINT_COUNT] > ShanhaiHoloSurround.FIELD_CAPACITY) {
                bad.append('⑥');
            }
        }
        return bad.toString();
    }

    /** 一行读数（日志与 Harness 都用它）。 */
    public static String presetSelfCheckLine() {
        final Report r = presetSelfCheck();
        return "holo_surround_preset_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok()
                + (r.ok() ? "（判据：「中」档逐项 == 那批出厂默认值 / 关档不写值 / 四档逐项落在安全区间 / "
                + "壳层外−内 ≥ 间隙+最小壳厚 / 弱≤中≤强 逐项单调 / 结构性上限（环数≤"
                + RING_TABLE.length + "、光点数≤" + ShanhaiHoloSurround.FIELD_CAPACITY
                + "）/ ± 步进越界被钳住 / 关档原样保留 / 整组替换；"
                + "另含 4 条负对照：改坏中档 / 压扁壳层 / 弱档比强档亮 / 光点数顶破容量）"
                : (" FAILED:" + r.bad()));
    }
}
