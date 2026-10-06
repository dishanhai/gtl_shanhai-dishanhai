package com.dishanhai.gt_shanhai.client.holo;

import com.dishanhai.gt_shanhai.common.holo.ShanhaiHoloMenuBoards;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 山海重构 · <b>悬浮全息菜单 —— 全部可调参数的【唯一一份】定义</b>。
 *
 * <h2>0. 为什么单独开这一个类</h2>
 * 用户另外要一份 <b>HTML 简易 MC 场景预览</b>（网页里模拟超平坦草地 + 玩家 + 悬浮全息），
 * 那份预览要与游戏里渲染用<b>同一套参数</b>。
 * ⇒ 参数<b>只准写在这一处</b>：渲染器、几何解算、{@code /shanhai menu dump} 的 JSON 输出全部读它。
 * 任何"另一边再抄一份数字"的做法都会漂移，而漂移出来的分歧（「网页好看、游戏里不对」）最难查。
 *
 * <h2>1. 单位口径（三种，别混）</h2>
 * <table border="1">
 *   <tr><th>单位</th><th>记号</th><th>含义</th></tr>
 *   <tr><td><b>格</b></td><td>格 / blocks</td><td>世界坐标，1 格 = 1 个方块</td></tr>
 *   <tr><td><b>板内单位</b></td><td>bu</td><td>
 *       一块菜单板的内部坐标系，<b>刻意取成与甲案 HTML 的 CSS px 一一对应</b>
 *       （板宽 {@link #BOARD_WIDTH_BU}=560、板高 {@link #BOARD_HEIGHT_BU}=62）。
 *       ⇒ 从 HTML 抄一个 17px 的左边距过来，这里写 17 就是同一个位置。</td></tr>
 *   <tr><td><b>字体像素</b></td><td>MC font px</td><td>
 *       原版 {@code Font} 的像素：一个 ASCII 字符宽 ~6px、行高 9px。
 *       {@link #CN_TEXT_SCALE}=2 表示"把原版 9px 的字放大成 18 bu"（= 甲案里 18px 的中文标签）。</td></tr>
 * </table>
 * 换算只有一条：{@link #BLOCKS_PER_BU} = 板宽(格) ÷ 板宽(bu)。
 *
 * <h2>2. 数值出处（不是拍的）</h2>
 * <ul>
 *   <li><b>板内排版、四角括号、序号+分隔线、描边/扫描线/色散</b> —— 逐值取自
 *       {@code 10-全息主菜单-甲案-全息投影台.html}（本地预览页）
 *       的 {@code .slab / .idx / .zh / .en / .br} 规则（只读参考资料，未改动它）。</li>
 *   <li><b>{@link #BOARD_SCALE}</b> = {@code {0.965, 0.99, 1.0, 0.985, 0.96}}
 *       —— 逐字抄甲案的 {@code --sc}（两端板略小）。</li>
 *   <li><b>{@link #DISTANCE_BLOCKS}=2.8</b>、<b>{@link #ARC_STEP_DEG}=21</b>、
 *       <b>{@link #ARC_RADIUS}=1.255</b> —— 这三个是本轮新定的（甲案没有三维数据），
 *       取值的约束写在各自常量上。</li>
 *   <li>🔴 <b>不碰任何贴图图集</b>：全部几何是 {@code POSITION_COLOR} 的纯色渐变四边形，
 *       ⇒ 本工程那条"图集绝对 UV 存进静态 VBO、资源重载后串贴图"的老坑（见
 *       {@code PrimordialOmegaEngineRingBuffer} 类注释）<b>在这里不存在</b>。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloMenuTuning {

    private ShanhaiHoloMenuTuning() {}

    // ==================================================================== 一、内容

    /** 菜单板块数。<b>固定 5</b>（用户点单：还是 5 个按键）。 */
    public static final int BOARD_COUNT = ShanhaiHoloMenuBoards.BOARD_COUNT;

    /**
     * 中文标签（板上的主标题）。
     *
     * <p>🔴 <b>它是 {@link ShanhaiHoloMenuBoards#LABEL_ZH} 的同一个数组，不是抄一份</b>：
     * 本轮"五块里只有「配方修改」可用、其余四块写「未启用」"这条规格同时被三处读
     * （渲染器、客户端输入判定、服务端校验）⇒ 表只能有一份，两处各写一份中文标题必然漂移。
     */
    public static final String[] LABEL_ZH = ShanhaiHoloMenuBoards.LABEL_ZH;

    /** 英文副标题（板上的小字）。同样是 {@link ShanhaiHoloMenuBoards#LABEL_EN} 的同一个数组。 */
    public static final String[] LABEL_EN = ShanhaiHoloMenuBoards.LABEL_EN;

    /** 左侧序号（甲案是 {@code 01}..{@code 05}）。 */
    public static final String[] LABEL_INDEX = {"01", "02", "03", "04", "05"};

    /**
     * 高亮哪一块板。
     * <p>本轮 = {@link ShanhaiHoloMenuBoards#ENABLED_INDEX}（唯一能用的那一块）。
     * 甲案里是 {@code 03} 那块更亮 —— 那只是"看起来有一块是当前项"的静态观感，
     * 本轮把它改成"唯一可用的那一块更亮"，与"点了有反应的只有它"一致。
     */
    public static final int HIGHLIGHT_INDEX = ShanhaiHoloMenuBoards.ENABLED_INDEX;

    /** 高亮板相对普通板的亮度倍率（RGB 与发光层 alpha 同时乘它）。 */
    public static final float HIGHLIGHT_BRIGHT = 1.30f;

    /**
     * 🆕 <b>「未启用」那几块的亮度倍率</b>（底板 / 描边 / 四角 / 发光层一起乘它）。
     * <p>用户点单：「另外 4 个是未启用」，任务书给的观感口径是「灰 / 暗」。
     * <p>取值 0.42：与可用那块（1.30）差 <b>3.1 倍</b> ⇒ 一眼能分出"哪一块是活的"，
     * 又不至于暗到看不见板子本身（五块板都还在，只是没通电）。
     */
    public static final float DISABLED_BRIGHT = 0.42f;

    /**
     * 🆕 「未启用」那几块<b>文字</b>的亮度倍率。
     * <p>比 {@link #DISABLED_BRIGHT} 高一些（0.55 vs 0.42）：板身要"暗"，
     * 但「未启用」四个字仍然要看得清 —— 看不清就等于什么都没说。
     */
    public static final float DISABLED_TEXT_MUL = 0.55f;

    // ==================================================================== 二、面板尺寸

    /** 一块板的宽度（板内单位 bu）= 甲案 {@code .menu} 的 560px。 */
    public static final float BOARD_WIDTH_BU = 560.0f;

    /** 一块板的高度（板内单位 bu）= 甲案 {@code .slab} 的 62px。 */
    public static final float BOARD_HEIGHT_BU = 62.0f;

    /**
     * 一块板的<b>世界尺寸</b>：半宽（格）。板宽 = 2 × 它 = <b>2.30 格</b>。
     * <p>取值依据：2.8 格的观看距离 + 原版默认视场角下，板宽约占屏幕宽度的 <b>39%</b>、
     * 板高约占 7%（甲案里板宽占 48%、板高占 8.6%）⇒ 与参考图是同一个量级。
     */
    public static final float BOARD_HALF_WIDTH_BLOCKS = 1.15f;

    /** 板高（格），由板宽与 {@link #BOARD_WIDTH_BU}/{@link #BOARD_HEIGHT_BU} 的比例推出 ≈ 0.2546。 */
    public static final float BOARD_HALF_HEIGHT_BLOCKS =
            BOARD_HALF_WIDTH_BLOCKS * (BOARD_HEIGHT_BU / BOARD_WIDTH_BU);

    /** <b>唯一换算</b>：1 bu = 多少格。 */
    public static final float BLOCKS_PER_BU = (2.0f * BOARD_HALF_WIDTH_BLOCKS) / BOARD_WIDTH_BU;

    /** 每块板自己的缩放（甲案 {@code --sc}）。两端略小，中间最大 ⇒ 弧面上近大远小的叠加。 */
    public static final float[] BOARD_SCALE = {0.965f, 0.99f, 1.0f, 0.985f, 0.96f};

    // ==================================================================== 三、弧面排布（桶形）

    /**
     * 相邻两块板的夹角（度）。甲案 {@code --rx} 是 {@code -11,-5,0,6,12}（≈ 6 度一档），
     * 但那是 CSS 的 {@code rotateX} 小倾角；三维里要让"两端的板向外倾"看得出来，
     * 取 <b>21 度一档</b>（两端各 42 度）。
     */
    public static final float ARC_STEP_DEG = 21.0f;

    /**
     * 弧面半径（格）。板间距 = {@code ARC_RADIUS × sin(ARC_STEP_DEG)} = <b>0.450 格</b>。
     * <p>取值依据：板高 0.2546 格 + 留白 ⇒ 板间距取 0.45 时，5 块板的总高 = 1.93 格
     * （在 2.8 格距离上约占屏幕高度的 38%）。
     */
    public static final float ARC_RADIUS = 1.255f;

    /** 板间距（格），由弧半径与夹角推出 —— 只作读数用，不要单独改它。 */
    public static final float BOARD_PITCH_BLOCKS =
            ARC_RADIUS * (float) Math.sin(Math.toRadians(ARC_STEP_DEG));

    // ---- 🔴 2026-10-05（用户实测 A6）新增：第三种排布「环绕玩家的一圈」= 默认档 ----
    //
    //   用户原话：「这个曲率是不是反了啊，那个 y 轴就是反了，x 和 z 轴围绕玩家的那种感觉吧
    //            （就是一个圆围绕着玩家），然后就不需要跟随玩家的头转动了，
    //             但是玩家位置移动还是需要跟随的，这样玩家就可以选择了。」
    //
    //   旧的两档（column/row）都是【桶形】：弧圆心在玩家【前方】 ARC_RADIUS 处，
    //   两端板向【外】（远离玩家）倾 —— 曲率是反的（照他的说法）。而且整个全息随玩家偏航一起转。
    //   新档 ring 把两件事都翻过来：
    //     · 弧圆心 = 【玩家本人】⇒ 五块板沿水平面绕玩家一圈，每块板的法线都指向圆心（玩家）；
    //     · 全息本体的偏航【恒为 0】（与世界轴对齐）⇒ 玩家扭头不再带动它 ⇒ 他能"选"到不同那块。

    /** 环绕档：相邻两块板的夹角（度）= 360 ÷ 5 = 72。 */
    public static final float RING_STEP_DEG = 360.0f / BOARD_COUNT;

    /** 环绕档：0 号板在世界的哪个方位角（度，原版偏航口径：0 = +Z = 南）。 */
    public static final float RING_BASE_YAW_DEG = 0.0f;

    /**
     * 环绕档的半径<b>下限</b>（格）—— 相邻两块板的弦长必须 ≥ 一块板的宽度，否则板会互相插进去。
     * <pre>
     *   2·R·sin(step/2) ≥ 2·halfWidth   ⇒   R ≥ halfWidth / sin(step/2)
     *   代入 halfWidth=1.15、step=72°（sin36°=0.58779）⇒ R ≥ 1.9565
     * </pre>
     * 运行时半径取 {@code max(distance, 这个下限)}（见 {@code ShanhaiHoloMenuState#ringRadius}）。
     */
    public static final float RING_MIN_RADIUS_BLOCKS =
            BOARD_HALF_WIDTH_BLOCKS / (float) Math.sin(Math.toRadians(RING_STEP_DEG * 0.5f));

    // ==================================================================== 四、与玩家的位置关系

    /**
     * 全息中心与玩家<b>眼睛</b>的水平距离（格）。
     * <p>用户给的范围是「浮在玩家前方约 2~3 格处」⇒ 取中值 2.8。
     * <p>🔴 <b>锚点是玩家、不是摄像机</b>：第三人称时摄像机会退到玩家身后约 4 格，
     * 若锚在摄像机，全息会跑到玩家背后/身体里。锚在玩家眼睛 ⇒ 第一/第三人称都正常。
     */
    public static final float DISTANCE_BLOCKS = 2.8f;

    /**
     * 全息中心相对<b>眼睛高度</b>的竖直偏移（格）。负 = 略低。
     * <p>站立玩家眼睛高 1.62 格 ⇒ 中心离地 <b>1.52 格</b>；
     * 全息上下半高 0.967 格 ⇒ 最低点离地 <b>0.55 格</b>、最高点 2.49 格。
     */
    public static final float HEIGHT_OFFSET_FROM_EYE = -0.10f;

    /**
     * 🆕 2026-10-06：<b>全息面板（设置 / 命令）的行距</b>（格）。一块板一行、共 5 行。
     *
     * <p>数值来源（全部用本文件里的既有常量推出来，没有一个手抄的数）：
     * <pre>
     *   BLOCKS_PER_BU     = 2×BOARD_HALF_WIDTH_BLOCKS ÷ BOARD_WIDTH_BU = 2.30/560 = 0.0041071 格/bu
     *   一块板的世界尺寸  = 560 bu × 0.0041071 = 2.30 格宽
     *                       62 bu × 0.0041071 = 0.2546 格高（= BOARD_HALF_HEIGHT_BLOCKS × 2）
     *   行距 0.32 格      ⇒ 行与行之间留 0.32 − 0.2546 = 0.0654 格空隙（≈ 1/4 个板高，看得出是两行）
     *   整列高            = 4×0.32 + 0.2546 = 1.5346 格
     * </pre>
     * 面板中心仍在"眼睛高度 − {@link #HEIGHT_OFFSET_FROM_EYE}"= 离地 1.52 格 ⇒ 整列跨 0.75 ~ 2.29 格；
     * 在默认 2.8 格的观看距离上占竖直视野 <b>2·atan(0.767/2.8) ≈ 30.6°</b>
     * （原版默认视场角 70°）⇒ <b>一屏放得下，不用抬头低头</b>。
     */
    public static final float PANEL_ROW_PITCH_BLOCKS = 0.32f;

    /**
     * 站立玩家的<b>眼睛离地</b>高度（格）—— 原版玩家的固定值 1.62。
     * <p>它只用于"把脚底坐标换算成眼睛坐标"（{@code /shanhai menu dump} 要同时打出 {@code player_pos}
     * 与 {@code player_eye} 两个口径）。
     */
    public static final float EYE_HEIGHT_BLOCKS = 1.62f;

    /** 全息中心的<b>离地高度</b>（格）—— 站立在平坦草地上的读数，只用于预览与读数，不参与渲染。 */
    public static final float CENTER_HEIGHT_ABOVE_GROUND = EYE_HEIGHT_BLOCKS + HEIGHT_OFFSET_FROM_EYE;

    /** 全息包围盒的总高（格）= 两端板中心距 + 一块板高。 */
    public static final float TOTAL_HEIGHT_BLOCKS = 2.0f * ARC_RADIUS * (float) Math.sin(Math.toRadians(2.0f * ARC_STEP_DEG)) + 2.0f * BOARD_HALF_HEIGHT_BLOCKS;

    /** 全息包围盒的总宽（格）。 */
    public static final float TOTAL_WIDTH_BLOCKS = 2.0f * BOARD_HALF_WIDTH_BLOCKS;

    // ==================================================================== 五、跟随与朝向插值

    /**
     * 位置跟随的指数平滑时间常数（秒）。<b>0 = 瞬时贴住</b>。
     * <p>0.10 秒 ⇒ 步行 4.3 格/秒时拖后约 <b>0.43 格</b>（"飘着跟过来"的手感，但不会甩掉）。
     */
    public static final float POSITION_TAU_SEC = 0.10f;

    /**
     * 朝向插值时间常数（秒）—— <b>{@code FOLLOW} 档</b>。
     * <p>0.16 秒 ⇒ 快速扭头时全息会"慢半拍"，<b>那半拍里你就能看到它的侧面</b>
     * （= 用户硬要求④「转视角时它会偏出视野/能看到侧面」的实现机制）。
     */
    public static final float YAW_TAU_FOLLOW_SEC = 0.16f;

    /**
     * 朝向插值时间常数（秒）—— <b>{@code SNAP} 档</b>。<b>0 = 立即转向玩家</b>。
     * <p>这一档是<b>对照组</b>：它几乎总是正对着你，观感最接近"贴在屏幕上的 HUD"
     * —— 留着就是为了让用户亲眼对比"有插值"与"没插值"的差别。
     */
    public static final float YAW_TAU_SNAP_SEC = 0.0f;

    /** 单帧最大步进（秒）。窗口失焦/卡顿后回来时不让插值跳一大步。 */
    public static final float MAX_FRAME_DT_SEC = 0.25f;

    // ==================================================================== 六、板内排版（bu）

    /** 序号文字距板左边的距离（甲案 {@code .idx{left:17px}}）。 */
    public static final float INDEX_LEFT_BU = 17.0f;

    /** 序号文字顶端距板顶的距离 = 对齐中文那一行（见 {@link #CN_TEXT_TOP_Y_BU}）。 */
    public static final float INDEX_TOP_Y_BU = 19.5f;

    /** 序号后面的那根分隔竖线：距板左边的距离（甲案 {@code left:22px}，相对序号再偏 22）。 */
    public static final float INDEX_TICK_LEFT_BU = 17.0f + 22.0f;

    /** 分隔竖线尺寸（宽 × 高，bu）。 */
    public static final float INDEX_TICK_W_BU = 1.0f;

    /** 分隔竖线尺寸（bu）。 */
    public static final float INDEX_TICK_H_BU = 12.0f;

    /** 分隔竖线顶端距板顶的距离（甲案：与序号中线对齐）。 */
    public static final float INDEX_TICK_TOP_Y_BU = 18.0f;

    /** 中文标签距板左边的距离（甲案 {@code .slab{padding-left:58px}}）。 */
    public static final float LABEL_LEFT_BU = 58.0f;

    /** 中文那一行的<b>顶端</b> y（板中心为 0，向板顶为正）。板高 62 ⇒ 两行合计 30 bu 居中。 */
    public static final float CN_TEXT_TOP_Y_BU = 15.0f;

    /** 英文那一行的<b>顶端</b> y。= 15 − 18(中文行高) − 3(行距)。 */
    public static final float EN_TEXT_TOP_Y_BU = -6.0f;

    /** 中文的字体放大倍数：原版行高 9px × 2 = 18 bu（= 甲案 {@code font-size:18px}）。 */
    public static final float CN_TEXT_SCALE = 2.0f;

    /** 英文的字体放大倍数：9px × 1 = 9 bu（≈ 甲案 {@code font-size:9.5px}）。 */
    public static final float EN_TEXT_SCALE = 1.0f;

    /** 中文字距（bu/字）。甲案 {@code letter-spacing:.20em} ⇒ 0.20 × 18 = 3.6 bu。 */
    public static final float CN_LETTER_SPACING_BU = 3.6f;

    /** 英文字距（bu/字）。甲案 {@code letter-spacing:.22em} ⇒ 0.22 × 9 ≈ 2.0 bu。 */
    public static final float EN_LETTER_SPACING_BU = 2.0f;

    /** 色散（chroma）左右偏移（bu）。甲案 {@code text-shadow:±1px} ⇒ 1 bu。 */
    public static final float CHROMA_OFFSET_BU = 1.0f;

    // ---- 描边 / 装饰的尺寸（bu）----

    /** 外描边线宽（甲案 {@code border:1px}）。 */
    public static final float BORDER_W_BU = 1.0f;

    /** 左侧那道竖向流光条的宽度（甲案 {@code ::after{width:2px}}）。 */
    public static final float EDGE_BAR_W_BU = 2.0f;

    /** 顶边那道高光的厚度（甲案 {@code inset 0 1px 0}）。 */
    public static final float TOP_HIGHLIGHT_H_BU = 1.0f;

    /** 四角括号的边长（甲案 {@code .br{width:11px;height:11px}}）。 */
    public static final float CORNER_SIZE_BU = 11.0f;

    /** 四角括号距板边的内缩（甲案 {@code left:3px;top:3px}）。 */
    public static final float CORNER_INSET_BU = 3.0f;

    /** 扫描线的厚度（甲案 {@code height:3px}）。 */
    public static final float SCAN_H_BU = 3.0f;

    /** 扫描线的宽度（甲案 {@code width:150px}）。 */
    public static final float SCAN_W_BU = 150.0f;

    /** 色散干扰线的宽度（甲案 {@code .glitch{width:250px}}）。 */
    public static final float GLITCH_W_BU = 250.0f;

    /** 描边流光那一段高光的长度（bu）。 */
    public static final float FLOW_SEGMENT_BU = 90.0f;

    /** 描边流光的粗细（bu）。 */
    public static final float FLOW_THICKNESS_BU = 2.0f;

    /** 全息整体抖动的幅度（bu）。甲案 {@code translate(±1px)}。 */
    public static final float JITTER_BU = 1.0f;

    // ---- 图层 z 偏移（bu，沿板面法线朝观众）----
    //   相邻层至少差 2 bu ≈ 0.0082 格。原版投影（近 0.05/远 768）在 2.8 格处的深度分辨率
    //   约 1e-5 格 ⇒ 这个间距是它的 800 倍，绝不会 z-fighting；同时在屏上只有 0.16 度的视差。

    /** 底板层 z。 */
    public static final float LAYER_PLATE_BU = 0.0f;
    /**
     * 整板内发光层 z。
     * <p>⚠️ 它<b>必须严格大于</b> {@link #LAYER_PLATE_BU}：内发光是"加法叠在整块板面上"的第二遍，
     * 若与底板同 z，两层在深度缓冲上相等 —— 虽然 {@code LEQUAL} 允许通过，
     * 但那是在赌两遍算出的深度值逐位相同。给 1 bu 的间距就没有这个赌局。
     */
    public static final float LAYER_INNER_GLOW_BU = 1.0f;
    /** 顶边高光层 z。 */
    public static final float LAYER_TOP_HL_BU = 2.0f;
    /** 左侧流光条层 z。 */
    public static final float LAYER_EDGE_BU = 4.0f;
    /** 外描边层 z。 */
    public static final float LAYER_OUTLINE_BU = 6.0f;
    /** 四角括号层 z。 */
    public static final float LAYER_CORNER_BU = 8.0f;
    /** 扫描线/干扰线层 z。 */
    public static final float LAYER_SCAN_BU = 10.0f;
    /** 文字层 z。 */
    public static final float LAYER_TEXT_BU = 12.0f;

    // ==================================================================== 七、颜色与透明度（ARGB）

    /** 底板左上角色（甲案 {@code rgba(26,86,112,.46)}）。 */
    public static final int PLATE_LT = 0x801A5670;
    /** 底板左下角色（略暗）。 */
    public static final int PLATE_LB = 0x75144660;
    /** 底板右上角色（甲案渐变右端 {@code rgba(12,40,58,.10)} 提亮到 .14，否则右边几乎看不见）。 */
    public static final int PLATE_RT = 0x24122A3A;
    /** 底板右下角色。 */
    public static final int PLATE_RB = 0x1F0A2232;

    /** 外描边色（甲案 {@code rgba(126,228,255,.32)}）。 */
    public static final int OUTLINE = 0x527EE4FF;

    /** 顶边高光色（甲案 {@code rgba(200,250,255,.16)}）。 */
    public static final int TOP_HIGHLIGHT = 0x29C8FAFF;

    /** 左侧流光条中段的亮色（甲案 {@code rgba(190,248,255,.95)}）。 */
    public static final int EDGE_BAR_BRIGHT = 0xE6BEF8FF;
    /** 左侧流光条两端的完全透明色（甲案渐变两端 {@code transparent}）。 */
    public static final int EDGE_BAR_CLEAR = 0x00BEF8FF;

    /** 四角括号色（甲案 {@code rgba(255,196,116,.55)}）。 */
    public static final int CORNER = 0x8CFFC474;
    /** 四角括号的柔和外发光（叠加绘制的第二遍，alpha 更低）。 */
    public static final int CORNER_GLOW = 0x33FFC474;

    /** 序号文字色（甲案 {@code rgba(150,232,255,.82)}）。 */
    public static final int INDEX_TEXT = 0xD196E8FF;
    /** 序号后那根分隔竖线（甲案 {@code rgba(126,228,255,.35)}）。 */
    public static final int INDEX_TICK = 0x597EE4FF;

    /** 中文标签主色（甲案 {@code #e6faff}）。 */
    public static final int CN_TEXT = 0xFFE6FAFF;
    /** 中文色散 —— 右侧的暖红影子（甲案 {@code text-shadow:1px 0 rgba(255,96,132,.40)}）。 */
    public static final int CN_CHROMA_WARM = 0x66FF6084;
    /** 中文色散 —— 左侧的青色影子（甲案 {@code text-shadow:-1px 0 rgba(96,226,255,.55)}）。 */
    public static final int CN_CHROMA_COOL = 0x8C60E2FF;

    /** 英文副标题色（甲案 {@code rgba(130,196,224,.62)}）。 */
    public static final int EN_TEXT = 0x9E82C4E0;

    /** 扫描线色（甲案 {@code rgba(215,252,255,.95)}）。 */
    public static final int SCAN_BAR = 0xF2D7FCFF;
    /** 色散干扰线色（甲案 {@code rgba(255,180,190,.85)}）。 */
    public static final int GLITCH_BAR = 0xD9FFB4BE;
    /** 描边流光的颜色（甲案 {@code rgba(190,248,255,.95)}）。 */
    public static final int FLOW_COLOR = 0xF2BEF8FF;

    // ==================================================================== 八、动画周期与强度

    /** 左侧流光条明暗往返周期（秒）。甲案 {@code edgeRun 3.1s}。 */
    public static final float EDGE_RUN_PERIOD_SEC = 3.1f;

    /** 描边流光绕板一周的周期（秒）。甲案 {@code railRun 3.6s}。 */
    public static final float OUTLINE_FLOW_PERIOD_SEC = 3.6f;

    /** 扫描线自下而上扫一趟的周期（秒）。甲案 {@code barRise 3.4s}。 */
    public static final float SCAN_PERIOD_SEC = 3.4f;

    /** 色散干扰线的周期（秒）与相对扫描线的滞后（秒）。甲案 {@code 3.4s / delay 1.15s}。 */
    public static final float GLITCH_PERIOD_SEC = 3.4f;

    /** 色散干扰线的相位滞后（秒）。 */
    public static final float GLITCH_DELAY_SEC = 1.15f;

    /** 整体呼吸（发光强度起伏）周期（秒）。甲案 {@code breathe 5.4s}。 */
    public static final float BREATH_PERIOD_SEC = 5.4f;

    /** 呼吸的最暗值（甲案 {@code opacity:.82}）。 */
    public static final float BREATH_MIN = 0.82f;

    /** 呼吸的最亮值（甲案 {@code opacity:1}）。 */
    public static final float BREATH_MAX = 1.0f;

    /** 中文色散强度往返周期（秒）。甲案 {@code chroma 3.1s}。 */
    public static final float CHROMA_PERIOD_SEC = 3.1f;

    /** 色散偏移在周期内摆动的低/高值（甲案 {@code 1px → 1.7px}）。 */
    public static final float CHROMA_OFFSET_MIN_BU = 1.0f;

    /** 色散偏移的高值（甲案 {@code 1.7px}）。 */
    public static final float CHROMA_OFFSET_MAX_BU = 1.7f;

    /** 全息抖动周期（秒）。甲案 {@code holoJit 6.4s}：周期末尾 4% 的时间里跳两下。 */
    public static final float JITTER_PERIOD_SEC = 6.4f;

    /** 扫描线在板内的竖直行程（bu，相对板中心的上下摆幅）。 */
    public static final float SCAN_TRAVEL_BU = 22.0f;

    // ==================================================================== 九、读数 / JSON

    /**
     * 把全部参数打成<b>一行 JSON</b>（给 HTML 预览直接抄）。
     * <p>手写拼接而不是引 Jackson —— 本工程没在渲染/命令路径上引入过序列化库，
     * 而这里要的只是"一份可复制的读数"，不值得为它多背一个依赖。
     * <p>⚠️ 字段名与实际渲染读的是<b>同一批常量</b>（下面每个 {@code put(...)} 都直接引用常量），
     * 所以不存在"JSON 写了一套、渲染用另一套"的可能。
     */
    public static String toJson() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');
        // 内容
        sb.append("\"boardCount\":").append(BOARD_COUNT).append(',');
        sb.append("\"labelZh\":[");
        for (int i = 0; i < LABEL_ZH.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(LABEL_ZH[i]).append('"');
        }
        sb.append("],");
        sb.append("\"labelEn\":[");
        for (int i = 0; i < LABEL_EN.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(LABEL_EN[i]).append('"');
        }
        sb.append("],");
        sb.append("\"highlightIndex\":").append(HIGHLIGHT_INDEX).append(',');
        sb.append("\"highlightBright\":").append(HIGHLIGHT_BRIGHT).append(',');
        // 尺寸单位与换算
        sb.append("\"blocksPerBu\":").append(BLOCKS_PER_BU).append(',');
        // 面板尺寸
        sb.append("\"boardWidthBu\":").append(BOARD_WIDTH_BU).append(',');
        sb.append("\"boardHeightBu\":").append(BOARD_HEIGHT_BU).append(',');
        sb.append("\"boardHalfWidthBlocks\":").append(BOARD_HALF_WIDTH_BLOCKS).append(',');
        sb.append("\"boardHalfHeightBlocks\":").append(BOARD_HALF_HEIGHT_BLOCKS).append(',');
        sb.append("\"boardScale\":[");
        for (int i = 0; i < BOARD_SCALE.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(BOARD_SCALE[i]);
        }
        sb.append("],");
        // 弧面
        sb.append("\"arcStepDeg\":").append(ARC_STEP_DEG).append(',');
        sb.append("\"arcRadius\":").append(ARC_RADIUS).append(',');
        sb.append("\"boardPitchBlocks\":").append(BOARD_PITCH_BLOCKS).append(',');
        // 位置
        sb.append("\"distanceBlocks\":").append(DISTANCE_BLOCKS).append(',');
        sb.append("\"heightOffsetFromEye\":").append(HEIGHT_OFFSET_FROM_EYE).append(',');
        sb.append("\"centerHeightAboveGround\":").append(CENTER_HEIGHT_ABOVE_GROUND).append(',');
        sb.append("\"totalWidthBlocks\":").append(TOTAL_WIDTH_BLOCKS).append(',');
        sb.append("\"totalHeightBlocks\":").append(TOTAL_HEIGHT_BLOCKS).append(',');
        // 插值
        sb.append("\"positionTauSec\":").append(POSITION_TAU_SEC).append(',');
        sb.append("\"yawTauFollowSec\":").append(YAW_TAU_FOLLOW_SEC).append(',');
        sb.append("\"yawTauSnapSec\":").append(YAW_TAU_SNAP_SEC).append(',');
        // 板内排版
        sb.append("\"indexLeftBu\":").append(INDEX_LEFT_BU).append(',');
        sb.append("\"indexTopYBu\":").append(INDEX_TOP_Y_BU).append(',');
        sb.append("\"indexTickLeftBu\":").append(INDEX_TICK_LEFT_BU).append(',');
        sb.append("\"indexTickWBu\":").append(INDEX_TICK_W_BU).append(',');
        sb.append("\"indexTickHBu\":").append(INDEX_TICK_H_BU).append(',');
        sb.append("\"indexTickTopYBu\":").append(INDEX_TICK_TOP_Y_BU).append(',');
        sb.append("\"labelLeftBu\":").append(LABEL_LEFT_BU).append(',');
        sb.append("\"cnTextTopYBu\":").append(CN_TEXT_TOP_Y_BU).append(',');
        sb.append("\"enTextTopYBu\":").append(EN_TEXT_TOP_Y_BU).append(',');
        sb.append("\"cnTextScale\":").append(CN_TEXT_SCALE).append(',');
        sb.append("\"enTextScale\":").append(EN_TEXT_SCALE).append(',');
        sb.append("\"cnLetterSpacingBu\":").append(CN_LETTER_SPACING_BU).append(',');
        sb.append("\"enLetterSpacingBu\":").append(EN_LETTER_SPACING_BU).append(',');
        sb.append("\"chromaOffsetBu\":").append(CHROMA_OFFSET_BU).append(',');
        sb.append("\"borderWBu\":").append(BORDER_W_BU).append(',');
        sb.append("\"edgeBarWBu\":").append(EDGE_BAR_W_BU).append(',');
        sb.append("\"topHighlightHBu\":").append(TOP_HIGHLIGHT_H_BU).append(',');
        sb.append("\"cornerSizeBu\":").append(CORNER_SIZE_BU).append(',');
        sb.append("\"cornerInsetBu\":").append(CORNER_INSET_BU).append(',');
        sb.append("\"scanHBu\":").append(SCAN_H_BU).append(',');
        sb.append("\"scanWBu\":").append(SCAN_W_BU).append(',');
        sb.append("\"scanTravelBu\":").append(SCAN_TRAVEL_BU).append(',');
        sb.append("\"glitchWBu\":").append(GLITCH_W_BU).append(',');
        sb.append("\"flowSegmentBu\":").append(FLOW_SEGMENT_BU).append(',');
        sb.append("\"flowThicknessBu\":").append(FLOW_THICKNESS_BU).append(',');
        sb.append("\"jitterBu\":").append(JITTER_BU).append(',');
        sb.append("\"layerPlateBu\":").append(LAYER_PLATE_BU).append(',');
        sb.append("\"layerInnerGlowBu\":").append(LAYER_INNER_GLOW_BU).append(',');
        sb.append("\"layerTopHlBu\":").append(LAYER_TOP_HL_BU).append(',');
        sb.append("\"layerEdgeBu\":").append(LAYER_EDGE_BU).append(',');
        sb.append("\"layerOutlineBu\":").append(LAYER_OUTLINE_BU).append(',');
        sb.append("\"layerCornerBu\":").append(LAYER_CORNER_BU).append(',');
        sb.append("\"layerScanBu\":").append(LAYER_SCAN_BU).append(',');
        sb.append("\"layerTextBu\":").append(LAYER_TEXT_BU).append(',');
        // 颜色（写成 #AARRGGBB，与 CSS 的 rgba 可直接换算）
        sb.append("\"plateLt\":\"").append(hex(PLATE_LT)).append("\",");
        sb.append("\"plateLb\":\"").append(hex(PLATE_LB)).append("\",");
        sb.append("\"plateRt\":\"").append(hex(PLATE_RT)).append("\",");
        sb.append("\"plateRb\":\"").append(hex(PLATE_RB)).append("\",");
        sb.append("\"outline\":\"").append(hex(OUTLINE)).append("\",");
        sb.append("\"topHighlight\":\"").append(hex(TOP_HIGHLIGHT)).append("\",");
        sb.append("\"edgeBarBright\":\"").append(hex(EDGE_BAR_BRIGHT)).append("\",");
        sb.append("\"corner\":\"").append(hex(CORNER)).append("\",");
        sb.append("\"cornerGlow\":\"").append(hex(CORNER_GLOW)).append("\",");
        sb.append("\"indexText\":\"").append(hex(INDEX_TEXT)).append("\",");
        sb.append("\"indexTick\":\"").append(hex(INDEX_TICK)).append("\",");
        sb.append("\"cnText\":\"").append(hex(CN_TEXT)).append("\",");
        sb.append("\"cnChromaWarm\":\"").append(hex(CN_CHROMA_WARM)).append("\",");
        sb.append("\"cnChromaCool\":\"").append(hex(CN_CHROMA_COOL)).append("\",");
        sb.append("\"enText\":\"").append(hex(EN_TEXT)).append("\",");
        sb.append("\"scanBar\":\"").append(hex(SCAN_BAR)).append("\",");
        sb.append("\"glitchBar\":\"").append(hex(GLITCH_BAR)).append("\",");
        sb.append("\"flowColor\":\"").append(hex(FLOW_COLOR)).append("\",");
        // 周期
        sb.append("\"edgeRunPeriodSec\":").append(EDGE_RUN_PERIOD_SEC).append(',');
        sb.append("\"outlineFlowPeriodSec\":").append(OUTLINE_FLOW_PERIOD_SEC).append(',');
        sb.append("\"scanPeriodSec\":").append(SCAN_PERIOD_SEC).append(',');
        sb.append("\"glitchPeriodSec\":").append(GLITCH_PERIOD_SEC).append(',');
        sb.append("\"glitchDelaySec\":").append(GLITCH_DELAY_SEC).append(',');
        sb.append("\"breathPeriodSec\":").append(BREATH_PERIOD_SEC).append(',');
        sb.append("\"breathMin\":").append(BREATH_MIN).append(',');
        sb.append("\"breathMax\":").append(BREATH_MAX).append(',');
        sb.append("\"chromaPeriodSec\":").append(CHROMA_PERIOD_SEC).append(',');
        sb.append("\"chromaOffsetMinBu\":").append(CHROMA_OFFSET_MIN_BU).append(',');
        sb.append("\"chromaOffsetMaxBu\":").append(CHROMA_OFFSET_MAX_BU).append(',');
        sb.append("\"jitterPeriodSec\":").append(JITTER_PERIOD_SEC);
        sb.append('}');
        return sb.toString();
    }

    /** {@code 0xAARRGGBB} → {@code "#AARRGGBB"}（大写十六进制，便于与 CSS rgba 对照）。 */
    private static String hex(int argb) {
        String s = Integer.toHexString(argb).toUpperCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder("#");
        for (int i = s.length(); i < 8; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }
}
