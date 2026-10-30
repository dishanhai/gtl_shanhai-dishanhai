package com.shanhai.client.render.hostfx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 归墟视界枢核 · <b>玻璃 AR 面板的"内容模型"</b>（版式 C · 左侧图列 + 右侧运行信息栏）——
 * <b>零 Minecraft 依赖</b>：只 import {@code java.util}。
 *
 * <h2>这个类为什么存在（它是"能离线判红判绿"的那一层）</h2>
 * 玻璃上那七块东西里，<b>真正有语义的</b>是两样：
 * <ol>
 *   <li><b>文字</b>（读数 16 行 / 物理量 6 行 / 主机节 12 行 / 模块列表 7 行 × 7 列）—— 从机器与
 *       {@link HostFxState} 取真值，取不到就是 {@code --}（<b>绝不放假数</b>）；</li>
 *   <li><b>几何</b>（内环带 / 8 舰 / 12 条细紫束 / 4 座解压机 / 中央两柱 / 黑洞 / 两条进度条）——
 *       全部是<b>平面四边形</b>（{@link Prim}），坐标单位是 <b>MC 像素</b>（1 px = 0.02 格 = 一格的 1/50）。</li>
 * </ol>
 * 两样都在本类里算完，{@link HostFxGlassArText} / {@link HostFxGlassArDraw} 只负责"把它们变成顶点"。
 * 于是这个文件可以被 {@code javac} 脱机编译 + 直接跑（不需要 MC、不需要开游戏），
 * 判据（关特效时主柱条数 = 0 / 中央两束取色 = 红 / 环角冻结 / 六项物理量与半径的比值 /
 * 读不到的数一律 {@code --}）一律打在<b>产品代码自己的输出</b>上，而不是打在预览器上
 * （{@code temp/ar-panel/}）。
 *
 * <h2>坐标系与两条硬口径</h2>
 * <ul>
 *   <li><b>墙面帧</b>：{@link HostFxGlassArPanel} 的 (u 向右, v 向下)，单位 px（22 格 = 1100 px、4 格 = 200 px）；</li>
 *   <li><b>同一个时钟</b>：所有转动/相位只用 {@link State#animSeconds}（渲染侧由
 *       {@link HostFxSwitch#spinSeconds()} = {@link HostFxState#animSeconds()} 填）——
 *       <b>关特效时它被 HostFxState 自己冻结</b>，所以这里<b>什么都不用做</b>就"冻结但仍画"；</li>
 *   <li><b>不许传 0</b>：本类从不把时钟归零，也不在降级态把角速度改成 0 —— 位置不动是"时钟不走了"的
 *       结果，不是"把角度乘 0"。这正好也对上了判据里的"两帧逐位相同"。</li>
 * </ul>
 *
 * <h2>降级态（{@code fx=false}）的语义：逐条对应 {@link HostFxSwitch}</h2>
 * <table border="1">
 *   <tr><th>项</th><th>正常</th><th>降级</th><th>在本类哪里</th></tr>
 *   <tr><td>内环 / 8 舰 / 4 解压机</td><td>按 animSeconds 转</td><td><b>冻结在同一姿态</b>（时钟不走了）</td>
 *       <td>{@link #ringAngleDeg} / {@link #shipUpAngleDeg} / {@link #shipDownAngleDeg}</td></tr>
 *   <tr><td>中央上下两束</td><td>青白</td><td><b>红</b>（{@link State#degradedRgb}）</td>
 *       <td>{@code side.jet_outer} / {@code side.jet_core}</td></tr>
 *   <tr><td>4 条解压机主柱</td><td>4 条</td><td><b>0 条</b>（整段不画，不是改透明）</td>
 *       <td>{@code top.md_pillar} / {@code side.md_pillar}（条数 = {@link State#mdPillarCount}）</td></tr>
 *   <tr><td>12 条细紫小束</td><td>12 条</td><td><b>仍是 12 条</b></td><td>{@code top.thin} / {@code side.thin}</td></tr>
 *   <tr><td>状态绿字（特效 / 透镜 / 环舰 / 主柱 / 小束）</td><td>开 / 转</td><td>降级 / 冻结</td>
 *       <td>并进 {@link #buildHost} 右列 r4/r5（上一版 ⑤ 徽标块与它的勾叉已按用户「这个可以删除」删掉）</td></tr>
 * </table>
 */
public final class HostFxGlassArModel {

    private HostFxGlassArModel() {}

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  世界真值（逐条抄自 HostFxGeometry，行号见各常量注释；改那边必须同步这里）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 内环带半径 48 格（{@code HostFxGeometry.INNER_R}）。 */
    public static final double INNER_R = 48.0D;
    /** 内环带半厚 1.1 格 ⇒ r ∈ [46.9, 49.1]，3 格厚（{@code HostFxGeometry.INNER_TOLERANCE}）。 */
    public static final double INNER_TOL = 1.1D;
    /** 8 艘飞船的轨道半径 63 格（{@code SHIP_RADIAL_CENTER}）。 */
    public static final double SHIP_R = 63.0D;
    /** 飞船切向半宽 3 格（{@code SHIP_TANGENTIAL_HALF}）。 */
    public static final double SHIP_TAN_HALF = 3.0D;
    /** 飞船径向半宽 2 格（{@code SHIP_RADIAL_HALF}）。 */
    public static final double SHIP_RAD_HALF = 2.0D;
    /** 4 座解压机的轴线半径 52.5 格（{@code MD_CENTER_R}）。 */
    public static final double MD_R = 52.5D;
    /** 4 座解压机的喷口半径 49.25 格（{@code mdNozzleRadius} = 52.5 − 3.25）。 */
    public static final double MD_NOZZLE_R = 49.25D;
    /** 解压机塔体径向长 9.0 格、切向宽 4.6 格（{@code MD_SIZE_RADIAL / MD_SIZE_TAN} 的现行值）。 */
    public static final double MD_SIZE_RAD = 9.0D;
    public static final double MD_SIZE_TAN = 4.6D;
    /** 4 座解压机的方位角（{@code MD_AZIMUTH_DEG = {0, 180, 90, 270}}）。 */
    public static final double[] MD_AZ_DEG = {0.0D, 180.0D, 90.0D, 270.0D};
    /** 每座解压机 3 条细紫束、绕本座喷口轴 r = 1.90 格（{@code THIN_BEAMS_PER_MD / THIN_BEAM_RING_R}）。 */
    public static final int THIN_PER_MD = 3;
    public static final double THIN_RING_R = 1.90D;
    /** 细紫束总条数 12 = 4 × 3（{@code THIN_BEAM_COUNT}）。 */
    public static final int THIN_COUNT = 4 * THIN_PER_MD;
    /** 中央上下两根主光柱各 18 格长（{@code JET_LENGTH}）。 */
    public static final double JET_LEN = 18.0D;
    /** 主光柱外层半径：靠洞端 6.20、外端 3.80（{@code PILLAR_OUTER_R0/R1}，即"真实 18 格锥度"）。 */
    public static final double PILLAR_R0 = 6.20D;
    public static final double PILLAR_R1 = 3.80D;
    /** 解压机主柱外层半径 1.25 → 1.55 格（{@code MD_BEAM_OUTER_R0/R1}）。 */
    public static final double MD_BEAM_R0 = 1.25D;
    public static final double MD_BEAM_R1 = 1.55D;
    /** 俯视图画到 r = 65 格（飞船外沿 63 + 2）。 */
    public static final double VIEW_R = 65.0D;
    /** 黑盘（阴影）半径 = B_CRIT × Rs，{@code B_CRIT = 3√3/2}（{@code BlackHoleLensMath.B_CRIT}）。 */
    public static final double B_CRIT = 2.598076211353316D;
    /** 吸积盘内 / 外缘（单位 Rs；{@code DISK_INNER_RS / DISK_OUTER_RS}）。 */
    public static final double DISK_INNER_RS = 3.0D;
    public static final double DISK_OUTER_RS = 11.0D;

    /**
     * ④ 那 6 条物理量的<b>比例表（以 Rs 为单位）</b>。
     *
     * <p>🔴 2026-10-25 收尾改口径（用户拍板）：<b>{@code Rs = holeRadiusBlocks() / B_CRIT}</b>。
     * 理由：渲染侧那个"黑洞半径"字段<b>本身</b>就是<b>黑盘（阴影）半径</b>
     * （{@code BlackHoleLensSpec}：黑盘屏幕半径 = {@code B_CRIT × rsBlocks}，{@code B_CRIT = 3√3/2}），
     * 把它标成 Rs 就是"把视觉半径当成史瓦西半径"，面板上的盘外缘会变成 57 格、而世界里的真吸积盘只有 22 格。
     * <pre>
     *   黑洞半径（黑盘）= B_CRIT × Rs     ← 第 0 项（= holeRadiusBlocks() 本身）
     *   Rs             = 1.000 × Rs
     *   光子球          = 1.500 × Rs       （史瓦西精确解 1.5 Rs）
     *   ISCO / 盘内缘    = 3.000 × Rs       （史瓦西 ISCO = 3 Rs；代码 DISK_INNER_RS）
     *   盘外缘          = 11.00 × Rs       （代码 DISK_OUTER_RS）
     *   逃逸速度（Rs 处）= 1.000 c         （见 {@link #ESC_AT_RS_C}）
     * </pre>
     * <p>判据（浮点容差 1e-3）两套都在（同一件事的两种问法）：
     * <ul>
     *   <li>对 <b>Rs</b> 的比值 = <b>{2.598, 1, 1.5, 3.0, 11.0}</b>（第 0 项就是 B_CRIT）；</li>
     *   <li>对 <b>holeRadiusBlocks()</b> 的比值 = <b>{1, 1/2.598, 1.5/2.598, 3/2.598, 11/2.598}</b>
     *       （= {1, 0.3849, 0.5774, 1.1547, 4.2339}）。</li>
     * </ul>
     * <p>⚠️ 物理量全部由<b>同一个</b>半径现算（{@link #buildPhysics}）⇒ 半径一改，六个数一起动，永远不会互相打架。
     */
    public static final double[] PHYS_RATIO_RS = {B_CRIT, 1.0D, 1.5D, DISK_INNER_RS, DISK_OUTER_RS};

    /** Rs 处的逃逸速度（单位 c）= 1.000（史瓦西：{@code v_esc = √(2GM/r)}，在 r = Rs 处正好 = c）。 */
    public static final double ESC_AT_RS_C = 1.0D;

    /**
     * 角速度的单位后缀。<b>只在这里写一次</b>：如果实机上 {@code °} 显示成方块（unicode 回退没覆盖），
     * 把它换成 {@code "度/秒"} 就行，全面板一处改完。
     */
    public static final String UNIT_DEG = "°/s";

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  混合模式 / 颜色
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 普通 alpha 混合（{@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}）—— 能给"变暗"（黑洞阴影）。 */
    public static final int BLEND_ALPHA = 0;
    /** 加性混合（{@code SRC_ALPHA, ONE}）—— 发光件（与 {@code HostFxDraw} 的光束层同一条）。 */
    public static final int BLEND_ADD = 1;

    /** 文字色：标题/主字/次要/告警（与 {@link HostFxGlassArText} 的常量同值）。 */
    public static final int C_HEAD = 0xFF00FFC8;
    public static final int C_MAIN = 0xFF00E676;
    public static final int C_DIM = 0xCC2FB57F;
    public static final int C_WARN = 0xFFFFB300;

    /** 分区角标（很淡，只是让五块"有边"）。 */
    private static final int C_FRAME = 0x3300E676;
    /** 内环带 / 环带截面。 */
    private static final int C_RING = 0xCC4A78AA;
    private static final int C_RING_EDGE = 0xCC96EBFF;
    /** 飞船上组（实心冷白）/ 下组（描边暖白）。 */
    private static final int C_SHIP_UP = 0xD8DBF2FF;
    private static final int C_SHIP_DN = 0xCCFFE0B8;
    /** 12 条细紫小束。 */
    private static final int C_THIN = 0xE6A874FF;
    /** 解压机塔体 / 喷口亮核。 */
    private static final int C_MD_BODY = 0xCC3A5674;
    private static final int C_MD_NOZZLE = 0xFFBEE7FF;
    /** 中央两束（正常：冷蓝白；降级：{@link State#degradedRgb}）。 */
    private static final int[] C_JET_NORMAL = {0xE6BEE6FF, 0xE6FFFFFF};
    /** 解压机主柱。 */
    private static final int C_MD_BEAM = 0xD8BEE7FF;
    /** 黑洞：阴影（纯黑）/ 光子环 / 吸积盘 / 外围辉光。 */
    private static final int C_HOLE = 0xFF000000;
    private static final int C_PHOTON = 0xE6FFE2AA;
    private static final int C_DISK = 0xCCFF9A40;
    private static final int C_HOLE_GLOW = 0x66FFB060;

    /** 进度条的槽底（暗绿灰，alpha 层：要"压暗"而不是"提亮"）；已执行段用 {@link #C_MAIN}。 */
    private static final int C_BAR_BACK = 0x66102018;
    /** 进度条边框（很淡的一条绿，让"条在哪里"这件事一眼看到，哪怕读数是 {@code --}）。 */
    private static final int C_BAR_EDGE = 0x9900E676;

    /** 分区下标（与 {@link HostFxGlassArPanel#ZONES} 同一个顺序）。 */
    public static final int Z_READ = 0;
    /** ② 俯视示意图（版式 C：左侧图列第 1 张）。 */
    public static final int Z_TOP = 1;
    /** ③ 侧视示意图（左侧图列第 2 张）。 */
    public static final int Z_SIDE = 2;
    /** ④ 黑洞小图（左侧图列第 3 张；6 条物理量已独立成 {@link #Z_PHYS}）。 */
    public static final int Z_HOLE = 3;
    /** ⑤ 六条物理量（黑洞半径 / Rs / 光子球 / ISCO / 盘外缘 / 逃逸速度）。 */
    public static final int Z_PHYS = 4;
    /** ⑥ 主机节（进度条 / 耗能 / 并行 / 线程 / 输出 ‖ 已装 / 总产出 / 总耗能·总发电 / 徽标绿字）。 */
    public static final int Z_HOST = 5;
    /** ⑦ 模块列表（表头 + 每个已装模块一行）。 */
    public static final int Z_MODLIST = 6;
    /** <b>不是分区</b>：AR 遮罩底（一整块，跨全墙；只用于标记，分区计数里不会出现它）。 */
    public static final int Z_MASK = -1;

    /**
     * <b>AR 遮罩的颜色</b>：中性灰 {@code RGB(110,112,118)} + 不透明度 <b>0.65</b>（{@code A=0xA6=166}）。
     *
     * <h2>为什么是这个数（用户口径：灰色半透明、0.55~0.70、接近中性灰）</h2>
     * <ul>
     *   <li><b>不是纯黑</b>：用户说的是"灰色"，而且这面玻璃后面就是黑洞与环，纯黑会把玻璃后面
     *       完全吃掉（他要的是"字更清楚"，不是"把后面挡住"）；</li>
     *   <li><b>0.65</b>：落在建议区间 0.55~0.70 的中段。低于 ~0.5 时玻璃上的高光/星空仍会穿透，
     *       {@code C_DIM}(0xCC2FB57F) 那档次要字就压不住了；高于 ~0.75 又会把吸积盘的橙色收掉；</li>
     *   <li><b>RGB 三通道差 8</b>（110/112/118，略偏冷）：纯等值灰在绿色文字周围会显得发脏，
     *       偏一点点冷与这套绿字同色系 ⇒ 观感更"屏幕"。</li>
     * </ul>
     * 关掉开关时这一整块<b>一个四边形都不产出</b>（不是画成全透明）⇒ 观感与改动前逐位相同。
     */
    public static final int C_AR_MASK = 0xA66E7076;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  输入状态
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 造一块面板需要的<b>全部</b>输入。渲染侧每帧从 {@link HostFxState} / {@link HostFxSwitch} /
     * {@code GuixuVisionCoreMachine} 填一份；离线探针也填这个（⇒ 产品代码与判据用同一个入口）。
     */
    public static final class State {
        // ── 特效开关（HostFxState） ──
        /** 特效总开关（机器 {@code hostFxEnabled}）。 */
        public boolean fxOn = true;
        /** 引力透镜开关（机器 {@code hostLensEnabled}）。 */
        public boolean lensOn = true;
        /** 降级态（= !fxOn；渲染侧直接取 {@link HostFxSwitch#degraded()}）。 */
        public boolean degraded = false;
        /** 内环角速度（°/s）。 */
        public float ringDegPerSec = HostFxState.DEFAULT_RING_DEG_PER_SEC;
        /** 上 4 舰角速度（°/s）。 */
        public float shipUpDegPerSec = HostFxState.DEFAULT_SHIP_UP_DEG_PER_SEC;
        /** 下 4 舰角速度<b>幅值</b>（°/s，正数；渲染时取负）。 */
        public float shipDownDegPerSec = HostFxState.DEFAULT_SHIP_DOWN_DEG_PER_SEC;
        /** 机器字段"黑洞半径"（格）—— <b>它是黑盘（阴影）半径</b>，不是 Rs；Rs = 它 ÷ B_CRIT。 */
        public float holeRadiusBlocks = HostFxState.DEFAULT_HOLE_RADIUS_BLOCKS;
        /** <b>动画时钟</b>（秒）—— 只准来自 {@link HostFxState#animSeconds()}（关特效自己冻结）。 */
        public float animSeconds;
        /** 降级态中央两束的颜色（{@link HostFxSwitch#DEGRADED_BEAM_RGB}）。 */
        public float[] degradedRgb = {1.0F, 64.0F / 255.0F, 48.0F / 255.0F};
        /** 解压机主柱这一帧画几条（{@link HostFxSwitch#mdMainBeamCount()}：正常 4、降级 0）。 */
        public int mdPillarCount = 4;
        /** 细紫小束这一帧画几条（{@link HostFxSwitch#thinBeamCount()}：恒 12）。 */
        public int thinBeamCount = THIN_COUNT;

        // ── 机器读数（取不到 = −1 ⇒ 显示 --） ──
        /** 结构是否成型（渲染闸门保证为 true；写成字段是为了"不成型就不许画"能被判据读到）。 */
        public boolean formed = true;
        /** 已连接模块数（{@code getFormedModuleCount()}，−1 = 读不到）。 */
        public int moduleCount = -1;
        /** 模块上限（{@code getMaxModuleCount()}，−1 = 读不到）。 */
        public int maxModules = -1;
        /** 物质模块等级（{@code moduleSlotBonus()}，−1 = 读不到）。 */
        public int matterLevel = -1;
        /** 模块等级复写（{@code getHostMatterLevelOverride()}，−1 = 读不到）。 */
        public int levelOverride = -1;
        /** 材料增产倍率（{@code productionMultiplier()}，−1 = 读不到）。 */
        public int productionMultiplier = -1;
        /** 能源仓容量（{@code IEnergyContainer.getEnergyCapacity()} 之和，−1 = 读不到）。 */
        public long energyInCapacity = -1;
        /** 动力仓容量（同上）。 */
        public long energyOutCapacity = -1;
        /**
         * 能源仓<b>已存</b> EU（2026-10-25 第二十三改；−1 = 读不到）。
         *
         * <p>🔴 这一项<b>只能</b>来自服务端快照（{@code GuixuVisionCoreMachine#hostEnergyInStored()}）：
         * 仓的 {@code energyContainer} 是 {@code @Persisted} 而不是 {@code @DescSynced}
         * ⇒ 客户端自己读恒 0，那就是假数（旧注释"不显示存量"就是这个原因，现在有通道了）。
         */
        public long energyInStored = -1;
        /** 动力仓已存 EU（同上）。 */
        public long energyOutStored = -1;

        // ── 2026-10-25 第二十一改新增：AR 遮罩 + 已装模块 ──
        /**
         * <b>AR 屏背后是否铺灰色半透明底</b>（机器 {@code hostArMaskEnabled} 的副本，经
         * {@code HostFxState.arMaskEnabled()} 到这里）。{@code false} ⇒ 一个四边形都不产出。
         */
        public boolean arMask = true;
        /**
         * 已装模块的<b>名字</b>（按槽位号升序，已经由喂料侧裁到
         * {@link HostFxGlassArPanel#MOD_MAX_NAMES} 个）。
         *
         * <p>空数组 = 真的一个都没装（不是"读不到"）；读不到时本字段单独有一条
         * {@link #moduleNamesUnavailable} 标记，免得把"未知"画成"0 个"。
         */
        public String[] moduleNames = new String[0];
        /** {@code true} = 名字这一侧读不到（此时只显示 `已装 N / 24 · 名字暂不可读`，不编名字）。 */
        public boolean moduleNamesUnavailable = false;
        /** 名字被截断时"还有几个没列出来"（0 = 没截断）。 */
        public int moduleNamesHidden = 0;
        /**
         * 已装模块的<b>槽位号</b>（升序，与 {@link #moduleNames} 一一对应）。
         *
         * <p>{@code null} = 槽位位图还没到 ⇒ 模块列表整块写"等伺服同步"，
         * <b>不按 0,1,2… 编一批槽位号出来</b>（那会把"未知"画成"有这么几个槽"）。
         */
        public int[] moduleSlots;

        /**
         * ⑦ 模块列表的<b>显示顺序</b>（{@link #moduleSlots} 的一个<b>置换</b>，不是另一个集合）。
         *
         * <p>🔴 2026-10-25 第三十二改（用户：「优先显示正在工作的机器，按最近一次运行时间从上往下排」）：
         * 这个数组由 {@link HostFxGlassArSort.Tracker} 在<b>"数据推送的那一拍"</b>算好
         * （{@code HostFxGlassArText#updateModuleOrder}，每 tick 一次、面板不可见时根本不跑），
         * {@code build()} 每帧<b>只读不排</b> —— 排序绝不在渲染帧里发生。
         *
         * <p>{@code null} = 还没有顺序 ⇒ 面板按 {@link #moduleSlots} <b>原序</b>画（喂料侧给的就是
         * 槽号升序 ⇒ 也就是改动前的行为）。长度/集合与 {@link #moduleSlots} 对不上时同样退回原序
         * （见 {@link HostFxGlassArModel#displayOrder}），保证"漏行 / 同一台机器画两行"结构上不可能发生。
         */
        public int[] moduleOrder;

        // ── 2026-10-25 第二十二改新增：主机 / 每模块的运行快照（AR 右侧信息栏） ─────────
        /**
         * 每个扫描位（0..23）的<b>配方进度百分比</b>（0..100）。
         *
         * <p>{@code null} = 这一份快照压根没到（服务端没同步 / 还没扫）⇒ 一律画 {@code --}；
         * 数组里某个元素 {@code < 0} = 那个槽位读不到（空槽或服务端没算出来）⇒ 也画 {@code --}。
         * <b>绝不用 0 冒充"没有进度"</b>（本工程红线：宁可缺，不可假）。
         */
        public byte[] modProgressPct;
        /** 每个扫描位当前 {@code EU/t}（{@code < 0} = 读不到）。 */
        public long[] modEuPerTick;
        /** 每个扫描位"同时处理几个配方"（{@code < 0} = 读不到）。 */
        public int[] modParallel;
        /** 每个扫描位"拥有几个跨配方线程"（{@code < 0} = 读不到）。 */
        public int[] modThreads;
        /** 每个扫描位"还有几秒做完"（{@code < 0} = 读不到）。 */
        public int[] modRemainSeconds;
        /**
         * 每个扫描位<b>「为什么没在跑」原因码</b>（服务端 {@code hostModReason} 的副本；
         * {@code null} / 元素 {@code < 0} = 读不到 ⇒ <b>不画标签</b>）。
         *
         * <p>取值见 {@link #REASON_NONE} / {@link #REASON_WORKING} / {@link #REASON_NO_POWER} /
         * {@link #REASON_OUTPUT_FULL} / {@link #REASON_BLOCKED} —— 与
         * {@code GuixuVisionCoreMachine.REASON_*} <b>逐值相同</b>（两处必须同步改）。
         *
         * <p>🔴 2026-10-25 第三十三改（用户：「机器"不工作"的四种形态…就按照你说的这4种」）。
         * 它<b>只影响 ⑦ 行末那个小标签</b>：不参与排序键、不改任何数值、读不到就什么都不画。
         */
        public byte[] modReason;
        /** 每个扫描位的输出（每项形如 {@code 名字×数量}），最多 2 项；{@code null} = 读不到。 */
        public String[][] modOutputs;

        /** 主机级：当前正在跑的配方进度百分比（0..100；{@code < 0} = 读不到）。 */
        public int hostProgressPct = -1;
        /** 主机级：这个配方还剩几秒（{@code < 0} = 读不到）。 */
        public int hostRemainSeconds = -1;
        /** 主机级：当前 {@code EU/t}（{@code < 0} = 读不到）。 */
        public long hostEuPerTick = -1L;
        /** 主机级：同时处理几个配方（{@code < 0} = 读不到）。 */
        public int hostParallel = -1;
        /** 主机级：拥有几个跨配方线程（{@code < 0} = 读不到）。 */
        public int hostThreads = -1;
        /** 主机级：当前在产出的东西（{@code 名字×数量}），最多 {@link HostFxGlassArPanel#HOST_OUTPUTS} 项。 */
        public String[] hostOutputs = new String[0];
        /** 主机级：输出总项数（> {@link HostFxGlassArPanel#HOST_OUTPUTS} 时文案补 {@code +N}）。 */
        public int hostOutputTotal = -1;

        /** 主机级：<b>总产出</b>条目数（所有模块配方输出累加；{@code < 0} = 读不到）。 */
        public long totalOutputCount = -1L;
        /** 主机级：总产出<b>种类</b>数（{@code < 0} = 读不到）。 */
        public int totalOutputKinds = -1;
        /**
         * 主机级：<b>总耗能功率</b>（{@code EU/t} 瞬时速率；{@code < 0} = 读不到）。
         *
         * <p>🔴 用户 2026-10-25 口径原话：「<b>总耗能/总发电我说的应该是总耗能功率/总发电功率</b>」
         * ⇒ 单位是 <b>EU/t</b>，是<b>速率</b>，<b>不是</b>"自加载以来的累计 EU"。
         */
        public long totalEuUsedPerTick = -1L;
        /** 主机级：<b>总发电功率</b>（{@code EU/t} 瞬时速率；{@code < 0} = 读不到）。 */
        public long totalEuGenPerTick = -1L;
        /** 主机级：已安装模块数（服务端权威；{@code < 0} = 读不到 ⇒ 回落到 {@link #moduleCount}）。 */
        public int installedModules = -1;

        /**
         * 🔴 2026-10-25 第二十六改（用户 bug：「<b>总耗能这里没显示</b>」）：能源仓<b>供能口径</b>。
         *
         * <pre>
         *   {@link #ENERGY_KIND_UNKNOWN}    −1 = 还没探测到（客户端读不到能力代理）
         *   {@link #ENERGY_KIND_NORMAL}      0 = 普通 / 无线 / 电网能源仓（仓侧<b>有</b>每秒吞吐读数）
         *   {@link #ENERGY_KIND_CREATIVE}    1 = 创造模式能源仓（无限供电，仓侧<b>没有</b>吞吐读数）
         *   {@link #ENERGY_KIND_NET_TERMINAL}2 = 无线电网输入终端（无限供电）
         * </pre>
         *
         * <p>🔴 判据来源：{@code InfinityEnergyContainer}（gtlcore，创造仓的容器）<b>没有</b>覆写
         * {@code getInputPerSec()}（{@code javap -p} 实证：只覆写 {@code acceptEnergyFromNetwork} /
         * {@code getEnergyStored}）⇒ 基类那个"上一个 20 tick 窗口累加"的字段不会被喂 ⇒
         * <b>恒 0</b>。这就是"5 个模块在跑、总产出 14.18M 件，而总耗能功率 0/t"的根因：
         * 不是没显示，是**仓侧根本没有这个量**。
         */
        public int energyInKind = ENERGY_KIND_UNKNOWN;
    }

    /** 见 {@link State#energyInKind}：还没探测到。 */
    public static final int ENERGY_KIND_UNKNOWN = -1;
    /** 见 {@link State#energyInKind}：普通 / 无线 / 电网能源仓。 */
    public static final int ENERGY_KIND_NORMAL = 0;
    /** 见 {@link State#energyInKind}：创造模式能源仓（无限供电）。 */
    public static final int ENERGY_KIND_CREATIVE = 1;
    /** 见 {@link State#energyInKind}：无线电网输入终端（无限供电）。 */
    public static final int ENERGY_KIND_NET_TERMINAL = 2;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ⑦ 行末「为什么没在跑」标签（2026-10-25 第三十三改）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 没在跑（也没查到原因）—— 不画标签。与 {@code GuixuVisionCoreMachine.REASON_NONE} 同值。 */
    public static final int REASON_NONE = 0;
    /** 正在跑 —— 不画标签（它已经排在最上面了）。 */
    public static final int REASON_WORKING = 1;
    /** 缺电 —— 红。 */
    public static final int REASON_NO_POWER = 2;
    /** 输出满 —— 橙。 */
    public static final int REASON_OUTPUT_FULL = 3;
    /** 被拦（类型闸 / 额外条件 / 温度 / 燃料 …）—— 黄。 */
    public static final int REASON_BLOCKED = 4;

    /**
     * <b>原因码 → 行末标签文字的【唯一映射表】</b>（下标 = 原因码；{@code null} = 这一档不画）。
     *
     * <p>为什么做成表而不是一串 {@code if}：① 探针要能逐档断言"码 → 字"；② 负对照要能<b>一行清空整张表</b>
     * （{@code temp/arsort/run-probe.ps1} 的 NEG-D 就是这么做的）——如果文字散在 {@code if} 里，
     * 那条负对照就只能靠改逻辑，不干净。
     * <p>{@code 1 = 正在跑} 刻意<b>不画</b>（用户：正在跑不显示，它已经排在列表最前面）。
     * <p>{@code 0 = 没在跑} 也<b>不画</b>：那既不是故障也不是工作，一个空格子比一个"闲"更安静。
     */
    private static final String[] REASON_TAG = {"", null, "缺电", "输出满", "被拦"};

    /**
     * <b>原因码 → 标签颜色</b>（与 {@link #REASON_TAG} 同下标、同长度；0 = 不画）。
     *
     * <p>红 {@code 0xFFFF4D4D} / 橙 {@code 0xFFFF9A40} / 黄 {@code 0xFFFFE24D} ——
     * 与既有的 {@link #C_WARN}（琥珀）区分得开，且三个都够亮（这面玻璃后面是黑洞，暗色会被吃掉）。
     */
    private static final int[] REASON_ARGB = {0, 0, 0xFFFF4D4D, 0xFFFF9A40, 0xFFFFE24D};

    /**
     * 原因码 → 行末标签文字（{@code null} = 不画）。
     *
     * <p>越界 / 负数（= 客户端拿不到服务端快照）一律 {@code null} ⇒ 什么都不画 ——
     * <b>"不知道"绝不画成一个标签</b>（本工程红线：宁可缺，不可假）。
     */
    static String reasonTag(int reason) {
        if (reason < 0 || reason >= REASON_TAG.length) {
            return null;
        }
        final String tag = REASON_TAG[reason];
        return tag == null || tag.isEmpty() ? null : tag;
    }

    /** 原因码 → 标签颜色（只对 {@link #reasonTag} 非 {@code null} 的码有意义）。 */
    static int reasonArgb(int reason) {
        if (reason < 0 || reason >= REASON_ARGB.length) {
            return 0;
        }
        return REASON_ARGB[reason];
    }

    /**
     * 进度条/标签的<b>统计串</b>：{@code 原因=[缺电2 输出满0 被拦1 在跑3]}（只统计已装槽位）。
     *
     * <p>进 {@code HostFxGlassArText#stats()} 那条每 5 秒的日志 —— 判据：
     * 这三个数与 {@code 在跑=} 相加应当 ≤ 已装模块数；长期全 0 而模块明显卡着 ⇒ 断在同步或分类。
     */
    public static String reasonCounts(State st) {
        final byte[] r = st.modReason;
        final int[] slots = st.moduleSlots;
        int noPower = 0;
        int outFull = 0;
        int blocked = 0;
        int working = 0;
        if (r != null && slots != null) {
            for (int slot : slots) {
                if (slot < 0 || slot >= r.length) {
                    continue;
                }
                switch (r[slot]) {
                    case REASON_WORKING -> working++;
                    case REASON_NO_POWER -> noPower++;
                    case REASON_OUTPUT_FULL -> outFull++;
                    case REASON_BLOCKED -> blocked++;
                    default -> {
                    }
                }
            }
        }
        return "原因=[缺电" + noPower + " 输出满" + outFull + " 被拦" + blocked + " 在跑" + working + "]";
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  输出：图元 / 文字 / 模型
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 一个<b>四边形</b>（两个三角形）：墙面帧 px 坐标，8 个数 = 4 个角 (x,y) 依次。
     *
     * <p>为什么一切都是四边形：{@code POSITION_COLOR} 这条层的顶点模式就是 QUADS
     * （先例 {@code HostFxDraw.beamLayer()}），三角形用"第 4 顶点 = 第 3 顶点"退化表示，
     * 于是画线/画圆/画梯形都只有<b>一种</b>提交路径。
     */
    public static final class Prim {
        /** 所属分区下标（0..4，见 {@link HostFxGlassArPanel#ZONES}）。 */
        public final int zone;
        /** 语义标签（判据按它数条数，例如 {@code side.md_pillar}）。 */
        public final String tag;
        /** {@link #BLEND_ALPHA} / {@link #BLEND_ADD}。 */
        public final int blend;
        /** 0xAARRGGBB（加性层里 alpha 当强度）。 */
        public final int argb;
        /** 4 个角：x0,y0,x1,y1,x2,y2,x3,y3。 */
        public final float[] xy;

        Prim(int zone, String tag, int blend, int argb, float[] xy) {
            this.zone = zone;
            this.tag = tag;
            this.blend = blend;
            this.argb = argb;
            this.xy = xy;
        }

        public int r() {
            return (argb >> 16) & 0xFF;
        }

        public int g() {
            return (argb >> 8) & 0xFF;
        }

        public int b() {
            return argb & 0xFF;
        }

        public int a() {
            return (argb >>> 24) & 0xFF;
        }
    }

    /** 一行文字（墙面帧 px 的左上角 = {@code drawInBatch} 的 x / y）。 */
    public static final class Txt {
        public final int zone;
        /** 槽位（① 是 {@code col*4+row}；④/⑤ 是行号；②③ 是 0）。 */
        public final int slot;
        public final float x;
        public final float y;
        public final String text;
        public final int argb;

        Txt(int zone, int slot, float x, float y, String text, int argb) {
            this.zone = zone;
            this.slot = slot;
            this.x = x;
            this.y = y;
            this.text = text;
            this.argb = argb;
        }
    }

    /** 一帧的成品：图元 + 文字 + 这一帧用的角度 + 六项物理量。 */
    public static final class Model {
        public final List<Prim> prims;
        public final List<Txt> texts;
        /** 内环这一帧的自转角（度，= animSeconds × ringDegPerSec）。 */
        public final float ringAngleDeg;
        /** 上 4 舰 / 下 4 舰的轨道角（度；下组为负）。 */
        public final float shipUpAngleDeg;
        public final float shipDownAngleDeg;
        /** 机器字段的黑洞半径（格，= 黑盘半径）与 6 项物理量：{黑洞半径(黑盘), Rs, 光子球, ISCO, 盘外缘}（格）+ 逃逸速度（c）。 */
        public final float holeRadiusBlocks;
        public final double[] physBlocks;
        public final double escapeAtRsC;
        public final boolean fxOn;

        Model(List<Prim> prims, List<Txt> texts, float ringAngleDeg, float shipUpAngleDeg,
              float shipDownAngleDeg, float holeRadiusBlocks, double[] physBlocks,
              double escapeAtRsC, boolean fxOn) {
            this.prims = prims;
            this.texts = texts;
            this.ringAngleDeg = ringAngleDeg;
            this.shipUpAngleDeg = shipUpAngleDeg;
            this.shipDownAngleDeg = shipDownAngleDeg;
            this.holeRadiusBlocks = holeRadiusBlocks;
            this.physBlocks = physBlocks;
            this.escapeAtRsC = escapeAtRsC;
            this.fxOn = fxOn;
        }

        /** 某个分区里某个标签的图元数（判据用：主柱条数 / 细紫束条数…）。 */
        public int count(int zone, String tag) {
            int n = 0;
            for (Prim p : prims) {
                if (p.zone == zone && p.tag.equals(tag)) {
                    n++;
                }
            }
            return n;
        }

        /** 某个分区里某个标签的<b>第一个</b>图元（判据用：中央两束取色）；没有就 null。 */
        public Prim first(int zone, String tag) {
            for (Prim p : prims) {
                if (p.zone == zone && p.tag.equals(tag)) {
                    return p;
                }
            }
            return null;
        }

        /** 某个分区 / 槽位的文字；没有就 null。 */
        public Txt text(int zone, int slot) {
            for (Txt t : texts) {
                if (t.zone == zone && t.slot == slot) {
                    return t;
                }
            }
            return null;
        }

        /** 某个分区里所有文字（按出现顺序）。 */
        public List<Txt> textsOf(int zone) {
            final List<Txt> out = new ArrayList<>();
            for (Txt t : texts) {
                if (t.zone == zone) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  入口
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 每帧造一份（几百个四边形的纯数学，不含任何 GL / 字体调用）。 */
    public static Model build(State st) {
        final List<Prim> prims = new ArrayList<>(600);
        final List<Txt> texts = new ArrayList<>(48);
        // ⓪ 🔴 2026-10-25：AR 遮罩底 —— 必须是【第一个】图元：它在 alpha 层里先提交，
        //    后面所有几何/文字都压在它上面（alpha 混合是与顺序相关的）。
        //    关掉时【一个四边形都不产出】，观感与改动前逐位相同。
        if (st.arMask) {
            arMaskBackdrop(prims);
        }
        // 分区角标（六块都画，很淡）
        for (int i = 0; i < HostFxGlassArPanel.ZONES.length; i++) {
            frame(prims, i, HostFxGlassArPanel.ZONES[i]);
        }
        final float ring = st.animSeconds * st.ringDegPerSec;
        final float shipUp = st.animSeconds * st.shipUpDegPerSec;
        final float shipDown = -st.animSeconds * st.shipDownDegPerSec;
        buildReads(st, texts);
        buildTop(st, ring, shipUp, shipDown, prims, texts);
        buildSide(st, ring, shipUp, shipDown, prims, texts);
        buildHole(st, prims);
        buildPhys(st, texts);
        buildHost(st, prims, texts);
        buildModuleList(st, prims, texts);
        final double[] phys = buildPhysics(st.holeRadiusBlocks);
        return new Model(prims, texts, ring % 360.0F, shipUp % 360.0F, shipDown % 360.0F,
                st.holeRadiusBlocks, phys, ESC_AT_RS_C, st.fxOn);
    }

    /**
     * AR 遮罩底：整面 AR 区域一块<b>中性灰半透明</b>矩形（{@link #C_AR_MASK}）。
     *
     * <p>覆盖范围 = 所有分区的最小外接矩形，再收回 {@link HostFxGlassArPanel#MARGIN}：
     * {@code u ∈ [0.40, 21.60]}、{@code v ∈ [0.40, 3.60]}（= 墙面帧去掉四边 0.40 格边距）。
     * 这与"六块分区"的实际占位逐位对齐（左 0.40 / 右 22−21.60 / 上 0.40 / 下 4−3.60 都是 0.40）。
     *
     * <p>⚠️ 用<b>alpha 层</b>（{@link #BLEND_ALPHA}）：加性层里"灰"会变成"提亮"，
     * 而这里要的是"压暗一点背景让绿字跳出来"。
     */
    private static void arMaskBackdrop(List<Prim> prims) {
        final float u0 = HostFxGlassArPanel.MARGIN;
        final float v0 = HostFxGlassArPanel.MARGIN;
        final float u1 = HostFxGlassArPanel.WALL_W - HostFxGlassArPanel.MARGIN;
        final float v1 = HostFxGlassArPanel.WALL_H - HostFxGlassArPanel.MARGIN;
        rect(prims, Z_MASK, "mask.backdrop", BLEND_ALPHA, C_AR_MASK,
                px(u0), px(v0), px(u1), px(v1));
    }

    /**
     * 六项物理量，<b>全部由同一个半径现算</b>（{@link #PHYS_RATIO_RS}）。
     *
     * <p>🔴 {@code Rs = holeRadiusBlocks / B_CRIT}：机器字段"黑洞半径"是<b>黑盘（阴影）半径</b>，
     * 不是史瓦西半径（见 {@link #PHYS_RATIO_RS} 的类注释）。
     *
     * @param holeRadiusBlocks 机器字段的"黑洞半径"（格）= <b>黑盘半径</b> = B_CRIT × Rs
     * @return {@code {黑洞半径(黑盘), Rs, 光子球, ISCO, 盘外缘}}（格）——
     *         默认 5.196 格 ⇒ {@code {5.196, 2.000, 3.000, 6.000, 22.000}}
     */
    public static double[] buildPhysics(float holeRadiusBlocks) {
        final double rs = holeRadiusBlocks / B_CRIT;
        final double[] out = new double[PHYS_RATIO_RS.length];
        for (int i = 0; i < PHYS_RATIO_RS.length; i++) {
            out[i] = PHYS_RATIO_RS[i] * rs;
        }
        return out;
    }

    /**
     * 同一组数值换一把尺子：<b>每一项 ÷ {@code holeRadiusBlocks()}</b>（判据表里那一列）。
     *
     * <p>默认半径下 = {@code {1, 0.3849, 0.5774, 1.1547, 4.2339}}。
     * 另一把尺子是除以 Rs ⇒ {@code {B_CRIT, 1, 1.5, 3, 11}}（见 {@link #PHYS_RATIO_RS}）。两套是同一件事。
     */
    public static double[] ratiosVsHoleRadius(float holeRadiusBlocks) {
        final double[] v = buildPhysics(holeRadiusBlocks);
        final double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] / holeRadiusBlocks;
        }
        return out;
    }

    /** 为 {@code true} 时表示 {@link #PHYS_RATIO_RS} 的口径就是"以 Rs 为单位"（判据/报告都读这个常量说明）。 */
    public static final boolean PHYS_UNIT_IS_RS = true;


    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ① 读数带（4 栏 × 4 行）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * ① 的 16 行读数。<b>基础 8 项</b>（身份 / 结构 / 模块 / 能源仓 / 动力仓 / 物质模块等级 / 等级复写 / 增产）
     * ＋<b>6 个新控件值</b>（内环 / 上舰 / 下舰 / 黑洞半径 / 引力透镜 / 特效渲染）＋<b>2 个活读数</b>
     * （动画时钟 / 主柱与细紫束条数——后者正是降级态差异的读数版）。
     *
     * <p>🔴 每一行都是 {@code --} 而不是编数：{@link State} 里 {@code <0} 就表示"读不到"。
     */
    private static void buildReads(State st, List<Txt> texts) {
        final HostFxGlassArPanel.Zone z = HostFxGlassArPanel.ZONE_READ;
        final String[] text = new String[HostFxGlassArPanel.READ_COLS * HostFxGlassArPanel.READ_ROWS];
        final int[] col = new int[text.length];
        for (int i = 0; i < text.length; i++) {
            col[i] = C_MAIN;
        }
        // 栏 1：身份 / 结构 / 模块 / 能源仓
        text[0] = "归墟视界枢核";
        col[0] = C_HEAD;
        text[1] = st.formed ? "结构已成型" : "结构未成型";
        col[1] = st.formed ? C_MAIN : C_WARN;
        text[2] = "已连接模块 " + intOrDash(st.moduleCount) + " / " + intOrDash(st.maxModules);
        text[3] = "能源仓 " + storedOverCapacity(st.energyInStored, st.energyInCapacity);
        if (st.energyInCapacity < 0) {
            col[3] = C_DIM;
        }
        // 栏 2：动力仓 / 等级 / 复写 / 增产
        text[4] = "动力仓 " + storedOverCapacity(st.energyOutStored, st.energyOutCapacity);
        if (st.energyOutCapacity < 0) {
            col[4] = C_DIM;
        }
        text[5] = "物质模块等级 " + levelText(st.matterLevel);
        text[6] = "模块等级复写 " + overrideText(st.levelOverride);
        text[7] = "材料增产 " + (st.productionMultiplier < 0 ? "--" : "x" + st.productionMultiplier);
        if (st.productionMultiplier < 0) {
            col[7] = C_DIM;
        }
        // 栏 3：三组角速度 + 动画时钟
        text[8] = "内环 " + one1(st.ringDegPerSec) + UNIT_DEG;
        text[9] = "上舰 +" + one1(st.shipUpDegPerSec) + UNIT_DEG;
        text[10] = "下舰 -" + one1(st.shipDownDegPerSec) + UNIT_DEG;
        text[11] = "动画 " + one1(st.animSeconds) + " s";
        if (!st.fxOn) {
            col[11] = C_DIM;                       // 冻结时这一行不再跳，颜色也压暗（"它停了"）
        }
        // 栏 4：黑洞半径 / 透镜 / 特效 / 条数
        text[12] = "黑洞半径 " + one1(st.holeRadiusBlocks) + " 格";
        text[13] = "引力透镜 " + (st.lensOn ? "开" : "关");
        text[14] = "特效渲染 " + (st.fxOn ? "开" : "降级");
        if (!st.fxOn) {
            col[14] = C_WARN;
        }
        text[15] = "主柱 " + st.mdPillarCount + " 条 小束 " + st.thinBeamCount + " 条";
        if (st.mdPillarCount <= 0) {
            col[15] = C_WARN;
        }

        for (int c = 0; c < HostFxGlassArPanel.READ_COLS; c++) {
            for (int r = 0; r < HostFxGlassArPanel.READ_ROWS; r++) {
                final int i = c * HostFxGlassArPanel.READ_ROWS + r;
                final float x = px(z.u + HostFxGlassArPanel.READ_COL_U[c]);
                final float y = px(z.v + HostFxGlassArPanel.READ_ROW_V0
                        + r * HostFxGlassArPanel.READ_ROW_DV);
                texts.add(new Txt(Z_READ, i, x, y, text[i], col[i]));
            }
        }
    }

    /** ① 的 16 行文字（脱机判据/报告直接读这个，不必跑渲染）。 */
    public static String[] readLines(State st) {
        final List<Txt> tmp = new ArrayList<>(16);
        buildReads(st, tmp);
        final String[] out = new String[tmp.size()];
        for (int i = 0; i < tmp.size(); i++) {
            out[i] = tmp.get(i).text;
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ② 俯视示意图（内环带 / 8 舰 / 12 细紫束 / 4 解压机 / 环心黑洞）
    // ═══════════════════════════════════════════════════════════════════════════════════

    private static void buildTop(State st, float ringDeg, float shipUpDeg, float shipDownDeg,
                                 List<Prim> prims, List<Txt> texts) {
        final int z = Z_TOP;
        final HostFxGlassArPanel.Zone zo = HostFxGlassArPanel.ZONE_FIG_TOP;
        final float bx = px(zo.u + HostFxGlassArPanel.FIG_INSET);
        final float by = px(zo.v + HostFxGlassArPanel.FIG_INSET);
        final float bw = px(zo.w) - 2 * px(HostFxGlassArPanel.FIG_INSET);
        final float bh = px(zo.h) - 2 * px(HostFxGlassArPanel.FIG_INSET);
        final float cx = bx + bw * 0.5F;
        final float cy = by + bh * 0.5F;
        final float R = Math.max(6.0F, Math.min(bw, bh) * 0.5F - 0.5F);
        final float k = R / (float) VIEW_R;
        // 视场底：一块很淡的暗面（让绿字在上面读得出来）
        disc(prims, z, "top.backdrop", BLEND_ALPHA, 0x33060E1A, cx, cy, R, 40);
        // 内环带（r 46.9..49.1）
        annulus(prims, z, "top.ring", BLEND_ALPHA, C_RING, cx, cy,
                (float) ((INNER_R - INNER_TOL) * k), (float) ((INNER_R + INNER_TOL) * k), 48);
        annulus(prims, z, "top.ring_edge", BLEND_ADD, 0x5596EBFF, cx, cy,
                (float) ((INNER_R + INNER_TOL) * k), (float) ((INNER_R + INNER_TOL) * k) + 1.0F, 48);
        if (st.fxOn) {
            // 转向弧（只在转的时候画：静止时"没有运动标记"本身就是判据）
            arc(prims, z, "top.ring_dir", BLEND_ADD, 0xAA96EBFF, cx, cy,
                    (float) ((INNER_R + INNER_TOL) * k) + 3.0F, -140.0F, -40.0F, 10, 1.0F);
            arrowHead(prims, z, "top.ring_dir", BLEND_ADD, 0xAA96EBFF, cx, cy,
                    (float) ((INNER_R + INNER_TOL) * k) + 3.0F, -140.0F);
        }
        // 4 座解压机（塔体 + 喷口 + 主柱 ×4 + 细紫 ×12），全部套自转角（机器是刚体）
        final float holePx = Math.max(1.5F, st.holeRadiusBlocks * k);
        for (int i = 0; i < MD_AZ_DEG.length; i++) {
            final double a = Math.toRadians(MD_AZ_DEG[i] + ringDeg);
            final float ca = (float) Math.cos(a);
            final float sa = (float) Math.sin(a);
            // 塔体
            rotRect(prims, z, "top.md_body", BLEND_ALPHA, C_MD_BODY,
                    cx + ca * (float) (MD_R * k), cy + sa * (float) (MD_R * k), (float) a,
                    (float) (MD_SIZE_RAD * k * 0.5D), (float) (MD_SIZE_TAN * k * 0.5D));
            // 喷口亮核
            disc(prims, z, "top.md_nozzle", BLEND_ADD, C_MD_NOZZLE,
                    cx + ca * (float) (MD_NOZZLE_R * k), cy + sa * (float) (MD_NOZZLE_R * k),
                    1.1F, 8);
            // 4 条解压机主柱（降级态 = 0 条：整段不提交，不是改透明）
            if (i < st.mdPillarCount) {
                final float r0 = (float) (MD_NOZZLE_R * k);
                line(prims, z, "top.md_pillar", BLEND_ADD, C_MD_BEAM,
                        cx + ca * r0, cy + sa * r0, cx + ca * holePx, cy + sa * holePx,
                        Math.max(2.0F, (float) (MD_BEAM_R1 * k * 2.0D)));
            }
            // 12 条细紫小束（每座 3 条；降级态照画）
            if (i * THIN_PER_MD + THIN_PER_MD <= st.thinBeamCount) {
                final float px2 = -sa;
                final float py2 = ca;
                // 真实间距只有 1.90 格（≈1 px），这里放大了画 —— 注记里已写明"示意·不按比例"
                final float spread = Math.max(2.6F, (float) (THIN_RING_R * k * 2.6D));
                for (int j = 0; j < THIN_PER_MD; j++) {
                    final float o = (j - (THIN_PER_MD - 1) * 0.5F) * spread;
                    final float r0 = (float) (MD_NOZZLE_R * k);
                    line(prims, z, "top.thin", BLEND_ADD, C_THIN,
                            cx + ca * r0 + px2 * o, cy + sa * r0 + py2 * o,
                            cx + ca * holePx + px2 * o, cy + sa * holePx + py2 * o, 1.0F);
                }
            }
        }
        // 8 艘飞船（上 4 实心 / 下 4 描边），各自绕环心转
        for (int up = 1; up >= 0; up--) {
            final float orbit = up == 1 ? shipUpDeg : shipDownDeg;
            for (int i = 0; i < 4; i++) {
                final float base = 90.0F * i;
                if (st.fxOn) {
                    // t=0 的残影：静止图上也能看出"它在走"
                    ship(prims, z, "top.ship_ghost", 0x30DBF2FF, up == 1, cx, cy, k,
                            (float) Math.toRadians(base));
                }
                ship(prims, z, up == 1 ? "top.ship_up" : "top.ship_down",
                        up == 1 ? C_SHIP_UP : C_SHIP_DN, up == 1, cx, cy, k,
                        (float) Math.toRadians(base + orbit));
            }
            if (st.fxOn) {
                final float rr = (float) (SHIP_R * k) + 3.5F;
                arc(prims, z, "top.ship_dir", BLEND_ADD, up == 1 ? 0x99AAE1FF : 0x99FFCD96,
                        cx, cy, rr, up == 1 ? -68.0F : -22.0F, up == 1 ? -34.0F : -56.0F, 6, 1.0F);
            }
        }
        // 环心黑洞：辉光 / 阴影 / 光子环
        disc(prims, z, "top.hole_glow", BLEND_ADD, C_HOLE_GLOW, cx, cy, holePx * 2.4F + 1.5F, 20);
        disc(prims, z, "top.hole_shadow", BLEND_ALPHA, C_HOLE, cx, cy, holePx, 20);
        annulus(prims, z, "top.photon_ring", BLEND_ADD, C_PHOTON, cx, cy, holePx, holePx + 1.0F, 24);
        // 中央上下两束在俯视里是"正对着你看的锥" ⇒ 投影成环心周围一圈（靠洞端 6.20 格）。
        // 🔴 颜色跟 ③ 的锥形柱同一个出口（jetColor）：降级态这里也变红，否则同一块面板会自相矛盾。
        annulus(prims, z, "top.jet_mouth", BLEND_ADD, jetColor(st)[0],
                cx, cy, holePx + 0.5F, holePx + 0.5F + (float) (PILLAR_R0 * k), 24);
        // 🔴 2026-10-25 第二十二改：底部注记行「俯视 示意·不按比例 r≤65 格」按用户（图6-7）删掉，
        //    连同它唯一服务的 CAPTION_V / Widths.topCaption 两处数据一起删 —— 不再产出 Txt。
        //    该图的"间距是放大画的"这件事现在只写在这里的注释里，不再占墙面。
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ③ 侧视示意图（中央两柱 18 格锥度 / 4 解压机主柱 / 环带截面 / 环心黑洞）
    // ═══════════════════════════════════════════════════════════════════════════════════

    private static void buildSide(State st, float ringDeg, float shipUpDeg, float shipDownDeg,
                                  List<Prim> prims, List<Txt> texts) {
        final int z = Z_SIDE;
        final HostFxGlassArPanel.Zone zo = HostFxGlassArPanel.ZONE_FIG_SIDE;
        final float bx = px(zo.u + HostFxGlassArPanel.FIG_INSET);
        final float by = px(zo.v + HostFxGlassArPanel.FIG_INSET);
        final float bw = px(zo.w) - 2 * px(HostFxGlassArPanel.FIG_INSET);
        final float bh = px(zo.h) - 2 * px(HostFxGlassArPanel.FIG_INSET);
        final float cx = bx + bw * 0.5F;
        final float cy = by + bh * 0.5F;
        final double halfH = JET_LEN + st.holeRadiusBlocks + 1.0D;
        final float k = Math.min(bw / (float) (2 * VIEW_R), bh / (float) (2 * halfH));
        final float holePx = Math.max(1.5F, st.holeRadiusBlocks * k);
        final float halfW = (float) (VIEW_R * k);
        // 环平面轴线（很淡）
        line(prims, z, "side.axis", BLEND_ALPHA, 0x55204060, cx - halfW, cy, cx + halfW, cy, 1.0F);
        // 环带截面（两侧各 3 格厚）+ 一条 1 px 亮芯（不然这块在深色底上糊成一团）
        for (int s = -1; s <= 1; s += 2) {
            final float x = cx + s * (float) (INNER_R * k);
            final float hx = (float) (INNER_TOL * k);
            final float hy = 1.5F * k;
            rect(prims, z, "side.ring", BLEND_ALPHA, C_RING, x - hx, cy - hy, x + hx, cy + hy);
            line(prims, z, "side.ring_ridge", BLEND_ADD, 0xCC96EBFF, x - hx, cy, x + hx, cy, 1.0F);
        }
        // 解压机塔体（±X 两座在剖面里；另两座在轴向上）
        for (int s = -1; s <= 1; s += 2) {
            final float x = cx + s * (float) (MD_R * k);
            rect(prims, z, "side.md_body", BLEND_ALPHA, C_MD_BODY,
                    x - (float) (MD_SIZE_RAD * k * 0.5D), cy - (float) (MD_SIZE_TAN * k * 0.5D),
                    x + (float) (MD_SIZE_RAD * k * 0.5D), cy + (float) (MD_SIZE_TAN * k * 0.5D));
        }
        // 12 条细紫小束（剖面里只画得进 ±X 两座，各 3 条；降级态照画）
        for (int s = -1; s <= 1; s += 2) {
            final float x0 = cx + s * (float) (MD_NOZZLE_R * k);
            final float x1 = cx + s * holePx;
            for (int j = 0; j < THIN_PER_MD; j++) {
                final float o = (j - 1) * 2.6F;
                line(prims, z, "side.thin", BLEND_ADD, C_THIN, x0, cy + o, x1, cy + o, 1.0F);
            }
        }
        // 4 条解压机主柱（降级态 = 0 条）
        if (st.mdPillarCount > 0) {
            final float w = Math.max(2.0F, (float) (MD_BEAM_R1 * k * 2.0D));
            for (int s = -1; s <= 1; s += 2) {
                line(prims, z, "side.md_pillar", BLEND_ADD, C_MD_BEAM,
                        cx + s * (float) (MD_NOZZLE_R * k), cy, cx + s * holePx, cy, w);
            }
            if (st.mdPillarCount > 2) {
                // 轴向那两座：真实正交投影会缩成一点（它们正对着你看）⇒ 本图错开成两段短桩
                // （注记里已写明"轴向两座已错开"）。桩要够长才数得出"这是另外两根"。
                for (int s = -1; s <= 1; s += 2) {
                    line(prims, z, "side.md_pillar", BLEND_ADD, C_MD_BEAM,
                            cx + s * (holePx + 2.0F), cy - s * 6.2F,
                            cx + s * (holePx + 19.0F), cy - s * 6.2F, w);
                }
            }
        }
        // 中央上下两根主光柱（真实 18 格锥度：靠洞端 6.20 → 外端 3.80）
        final int[] jetColor = jetColor(st);
        for (int s = -1; s <= 1; s += 2) {
            final float y0 = cy - s * holePx;
            final float y1 = cy - s * (holePx + (float) (JET_LEN * k));
            final float w0 = (float) (PILLAR_R0 * k);
            final float w1 = (float) (PILLAR_R1 * k);
            quad(prims, z, "side.jet_outer", BLEND_ADD, jetColor[0],
                    cx - w0, y0, cx + w0, y0, cx + w1, y1, cx - w1, y1);
            quad(prims, z, "side.jet_core", BLEND_ADD, jetColor[1],
                    cx - w0 * 0.45F, y0, cx + w0 * 0.45F, y0,
                    cx + w1 * 0.45F, y1, cx - w1 * 0.45F, y1);
        }
        // 8 艘飞船（侧视投影：x = cos(方位+自转)×63，上组高 / 下组低）
        for (int up = 1; up >= 0; up--) {
            final float orbit = up == 1 ? shipUpDeg : shipDownDeg;
            final float sy = cy - (up == 1 ? 1.0F : -1.0F) * 3.5F * k;
            for (int i = 0; i < 4; i++) {
                final float a = (float) Math.toRadians(90.0F * i + orbit);
                final float x = cx + (float) Math.cos(a) * (float) (SHIP_R * k);
                rect(prims, z, up == 1 ? "side.ship_up" : "side.ship_down", BLEND_ALPHA,
                        up == 1 ? C_SHIP_UP : C_SHIP_DN,
                        x - (float) (SHIP_TAN_HALF * k), sy - (float) (SHIP_RAD_HALF * k * 0.5D),
                        x + (float) (SHIP_TAN_HALF * k), sy + (float) (SHIP_RAD_HALF * k * 0.5D));
            }
        }
        // 环心黑洞
        disc(prims, z, "side.hole_glow", BLEND_ADD, C_HOLE_GLOW, cx, cy, holePx * 2.2F + 1.5F, 20);
        disc(prims, z, "side.hole_shadow", BLEND_ALPHA, C_HOLE, cx, cy, holePx, 20);
        annulus(prims, z, "side.photon_ring", BLEND_ADD, C_PHOTON, cx, cy, holePx, holePx + 1.0F, 24);
        // 🔴 2026-10-25 第二十二改：底部注记行「侧视 真值 130×48.4 格 轴向两座已错开」按用户（图6-7）删掉
        //    （连同 CAPTION_V / Widths.sideCaption 一起）。"轴向两座在剖面里是错开画的"这件事
        //    只保留在 buildSide 里那两段短桩的注释中。
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ④ 黑洞小图 + 6 条物理量
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * ④ <b>黑洞小图</b>（版式 C：挪到左侧图列第 3 格，独立成块）。
     *
     * <p>6 条物理量<b>不在这一块里</b>了（用户只让挪"图"），它们搬进 {@link #buildPhys}。
     */
    private static void buildHole(State st, List<Prim> prims) {
        final int z = Z_HOLE;
        final HostFxGlassArPanel.Zone zo = HostFxGlassArPanel.ZONE_FIG_HOLE;
        final float icx = px(zo.u + zo.w * 0.5F);
        final float icy = px(zo.v + zo.h * 0.5F);
        final float R = Math.min(px(zo.w), px(zo.h)) * 0.5F
                - px(HostFxGlassArPanel.FIG_INSET) - 2.0F;
        // 吸积盘（赤道压扁的椭圆环：内缘 3 Rs、外缘 11 Rs 的比例照抄真几何，图象本身是示意）
        final float rin = R * 0.42F;
        final float rout = R;
        annulusSquashed(prims, z, "hole.disk", BLEND_ADD, C_DISK, icx, icy, rin, rout, 0.30F, 28);
        // 阴影（纯黑，alpha 层：加性层画不出"黑"）
        disc(prims, z, "hole.shadow", BLEND_ALPHA, C_HOLE, icx, icy, R * 0.34F, 24);
        // 光子环（阴影外沿 1 px 亮环）
        annulus(prims, z, "hole.photon_ring", BLEND_ADD, C_PHOTON, icx, icy,
                R * 0.34F, R * 0.34F + 1.3F, 24);
    }

    /**
     * ⑤ 六条物理量（版式 C 从 ④ 里拆出来的独立块）：全部由 {@code holeRadiusBlocks} 现算
     * （{@code Rs = holeRadius / B_CRIT}，见 {@link #PHYS_RATIO_RS}）。
     */
    private static void buildPhys(State st, List<Txt> texts) {
        final int z = Z_PHYS;
        final HostFxGlassArPanel.Zone zo = HostFxGlassArPanel.ZONE_PHYS;
        final double[] p = buildPhysics(st.holeRadiusBlocks);
        final String[] lines = {
                // 第 0 项就是机器字段"黑洞半径"原值 —— 它其实是【黑盘（阴影）半径】，这行必须标明
                "黑洞半径 " + two2((float) p[0]) + " 格（黑盘）",
                "Rs " + two2((float) p[1]) + " 格",
                "光子球 " + two2((float) p[2]) + " 格（1.50 Rs）",
                "ISCO " + two2((float) p[3]) + " 格（3.00 Rs）",
                "盘外缘 " + one1((float) p[4]) + " 格（11.0 Rs）",
                "Rs 处逃逸速度 1.000c",
        };
        for (int i = 0; i < HostFxGlassArPanel.PHYS_ROWS; i++) {
            texts.add(new Txt(z, i, px(zo.u + HostFxGlassArPanel.PHYS_TEXT_U),
                    px(zo.v + HostFxGlassArPanel.PHYS_ROW_V0 + i * HostFxGlassArPanel.READ_ROW_DV),
                    lines[i], i == 0 ? C_HEAD : C_MAIN));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ⑥ 主机节（版式 C：右侧信息栏上半）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * ⑥ <b>主机节</b>：两列 × 6 行。
     *
     * <pre>
     *   左列（运行）                          右列（汇总 + 徽标绿字）
     *   r0 主机 · 配方执行                     r0 汇总
     *   r1 进度 62% 剩 4.2s  ▮▮▮▮▯▯（几何条）  r1 已装模块 18 / 24
     *   r2 耗能 1.2M EU/t 并行 8 线程 4        r2 总产出 8 类 · 512 件
     *   r3 输出① 物品A×64                      r3 总耗能 1.2G EU · 总发电 800M EU
     *   r4 输出② 物品B×32                      r4 特效 开 透镜 开 环舰 转
     *   r5 输出③ 物品C×16（+2）                r5 中央两束 青白 主柱 4 条 小束 12 条
     * </pre>
     *
     * <h2>🔴 用户口径逐条对上</h2>
     * <ul>
     *   <li>「配方执行进度（就是 jade 的条）」= r1 的<b>几何进度条</b> + 百分比 + 剩余秒数；</li>
     *   <li>「耗能 / 同时处理 x 个配方 / 拥有 x 个跨配方线程 / 配方输出」= r2 + r3..r5（最多 3 条，超出 {@code +N}）；</li>
     *   <li>「已经安装了多少个模块 / 总产出 / 总耗能 / 总发电」= 右列 r1..r3；</li>
     *   <li>动作：上一版 ⑤ 徽标块那 6 行绿字<b>并进右列 r4/r5</b>；<b>勾叉（黄色 X）整块删掉</b>
     *       （用户「这个可以删除，图4」）——{@code mark[]} 那份数据与 {@code cross()} / {@code check()}
     *       两个绘制函数一并删除，不是画成透明。</li>
     * </ul>
     *
     * <p>🔴 <b>拿不到的数是 {@code --}</b>（{@link State} 里 {@code <0} / {@code null}），
     * 进度条也只画空槽，<b>不画一条满格的假进度</b>。
     */
    private static void buildHost(State st, List<Prim> prims, List<Txt> texts) {
        final int z = Z_HOST;
        final HostFxGlassArPanel.Zone zo = HostFxGlassArPanel.ZONE_HOST;
        final float xa = px(zo.u + HostFxGlassArPanel.HOST_COL_A_U);
        final float xb = px(zo.u + HostFxGlassArPanel.HOST_COL_B_U);

        // ── 左列：运行 ─────────────────────────────────────────────────────────────
        final String pct = pctText(st.hostProgressPct);
        final String remain = secText(st.hostRemainSeconds);
        final String[] a = {
                "主机 · 配方执行",
                "进度 " + pct + " 剩 " + remain,
                // 🔴 2026-10-25 第三十四改（用户：「其余量 -- 要变成真值；真拿不到就写「读不到」而不是 --」）
                "耗能 " + hostEuText(st.hostEuPerTick) + " EU/t 并行 " + hostCountText(st.hostParallel)
                        + " 线程 " + hostCountText(st.hostThreads),
                hostOutputLine(st, 0),
                hostOutputLine(st, 1),
                hostOutputLine(st, 2),
        };
        final int[] ca = {
                C_HEAD,
                st.hostProgressPct < 0 ? C_DIM : C_MAIN,
                (st.hostEuPerTick < 0L) ? C_DIM : C_MAIN,
                C_MAIN, C_MAIN, C_MAIN,
        };
        // 超出 3 条时在最后一条尾巴上补 +N（不占额外行）
        if (st.hostOutputTotal > HostFxGlassArPanel.HOST_OUTPUTS) {
            a[5] = a[5] + "（+" + (st.hostOutputTotal - HostFxGlassArPanel.HOST_OUTPUTS) + "）";
        }
        if (st.hostOutputs == null || st.hostOutputs.length == 0) {
            ca[3] = C_DIM;
        }

        // ── 右列：汇总 + 徽标绿字（上一版 ⑤ 并入这里） ─────────────────────────────
        //
        //  2026-10-25 第二十六改（用户 bug：「总耗能这里没显示」；原话「同时给一行
        //  「模块耗电合计 X/t」」）：右列仍是 6 行（HOST_ROWS 是版式常量，**不动**），
        //  但把「已装模块」与「总产出」并成一行（两者都是"数量"，本来就在说同一件事），
        //  腾出来的那一行给「总耗能功率 / 模块耗电合计 / 总发电功率」三条口径：
        //      r1 已装 5/24 · 产出 3 类 14.18M 件
        //      r2 总耗能功率 ∞（创造能源仓）
        //      r3 模块耗电合计 12.3M/t · 总发电功率 0/t
        final boolean deg = !st.fxOn;
        final int installed = st.installedModules >= 0 ? st.installedModules : st.moduleCount;
        final long modEuSum = moduleEuSum(st);
        final String[] b = {
                "汇总",
                "已装 " + intOrDash(installed) + "/" + intOrDash(st.maxModules) + " · "
                        + totalOutputText(st.totalOutputKinds, st.totalOutputCount),
                // 🔴 2026-10-25 第二十四改的口径保留：功率（EU/t 瞬时速率，20 tick 滑动窗口）；
                //    第二十六改补上"无限供电"的三档语义（见 energyUsedPowerText 的 javadoc）。
                "总耗能功率 " + energyUsedPowerText(st, modEuSum),
                // 🔴 玩家真正想看的那条：模块侧实际烧了多少电（创造仓下也有意义）
                "模块耗电合计 " + powerText(modEuSum) + " · 总发电功率 "
                        + powerText(st.totalEuGenPerTick),
                "特效 " + (deg ? "降级" : "开") + " 透镜 " + (st.lensOn ? "开" : "关")
                        + " 环舰 " + (deg ? "冻结" : "转"),
                "中央两束 " + (deg ? "红" : "青白") + " 主柱 " + st.mdPillarCount
                        + " 条 小束 " + st.thinBeamCount + " 条",
        };
        final int[] cb = {
                C_HEAD,
                (installed < 0 && st.totalOutputCount < 0L && st.totalOutputKinds < 0) ? C_DIM : C_MAIN,
                (st.totalEuUsedPerTick < 0L && st.energyInKind != ENERGY_KIND_CREATIVE
                        && st.energyInKind != ENERGY_KIND_NET_TERMINAL) ? C_DIM : C_MAIN,
                (modEuSum < 0L && st.totalEuGenPerTick < 0L) ? C_DIM : C_MAIN,
                deg ? C_WARN : C_MAIN,
                st.mdPillarCount <= 0 ? C_WARN : C_MAIN,
        };

        for (int r = 0; r < HostFxGlassArPanel.HOST_ROWS; r++) {
            final float y = px(zo.v + HostFxGlassArPanel.HOST_ROW_V0 + r * HostFxGlassArPanel.READ_ROW_DV);
            texts.add(new Txt(z, r, xa, y, a[r], ca[r]));
            texts.add(new Txt(z, HostFxGlassArPanel.HOST_ROWS + r, xb, y, b[r], cb[r]));
        }

        // ── r1 的几何进度条（"Jade 那种条"）────────────────────────────────────────
        final float barX = px(zo.u + HostFxGlassArPanel.HOST_BAR_U);
        final float barY = px(zo.v + HostFxGlassArPanel.HOST_ROW_V0 + HostFxGlassArPanel.READ_ROW_DV
                + 0.01F);
        bar(prims, z, "host.bar_back", "host.bar_fill", barX, barY,
                px(HostFxGlassArPanel.HOST_BAR_W), px(HostFxGlassArPanel.HOST_BAR_H),
                st.hostProgressPct, HostFxGlassArPanel.HOST_BAR_SEG, C_BAR_BACK, C_MAIN);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  ⑦ 模块列表（版式 C：右侧信息栏下半；上一版 ⑥ 那三行模块名升级成 24 行表格）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * ⑦ <b>模块列表</b>：一行表头 + 每个已装模块一行。
     *
     * <pre>
     *   表头  槽  模块      进度        耗能EU/t  并行  线程  输出
     *   数据  12  反物质压缩机  ▮▮▮▮▯▯ 62%  1.2M    8     4    反物质×64 氢×32
     *   …
     *   …（还有 17 个）        ← 24 个装不下时（{@link HostFxGlassArPanel#MODLIST_ROWS} 由块高算死）
     * </pre>
     *
     * <h2>数据从哪来（🔴 每条都写清来源；读不到一律 {@code --}）</h2>
     * <table border="1">
     *   <tr><th>列</th><th>来源</th><th>读不到</th></tr>
     *   <tr><td>槽位号</td><td>{@link State#moduleSlots}（喂料侧从
     *       {@code GuixuVisionCoreMachine#hostModuleMask()} 的置位逐个数出来）</td><td>不画该行</td></tr>
     *   <tr><td>模块名</td><td>{@link State#moduleNames}（客户端按权威槽位去世界里取
     *       {@code getDefinition().getItem()} 的名字，同一个注册表数据）</td><td>{@code 槽#N}</td></tr>
     *   <tr><td>进度 / 耗能 / 并行 / 线程 / 输出</td><td>{@link State#modProgressPct} /
     *       {@link State#modEuPerTick} / {@link State#modParallel} / {@link State#modThreads} /
     *       {@link State#modOutputs}（<b>服务端 → 客户端快照</b>，见 {@code GuixuVisionCoreMachine}
     *       的 {@code hostMod*} 同步字段）</td><td>{@code --}</td></tr>
     * </table>
     *
     * <p>🔴 <b>数组是"按扫描位下标"的</b>（{@code [24]}），不是"按已装顺序"的：
     * 已装顺序由 {@link State#moduleSlots} 给出，两者用槽位号对齐 —— 这样服务端不用知道客户端画第几行。
     *
     * <h2>🔴 2026-10-25 第三十二改：行的顺序 = {@link State#moduleOrder}（不再等于槽号顺序）</h2>
     * 用户原话：「<b>优先显示正在工作的机器</b>，就是把机器<b>按照最近一次运行时间从上往下排</b>，
     * 因为后面机器多了它肯定写不下去」。
     * <ul>
     *   <li>顺序在<b>数据推送的那一拍</b>由 {@link HostFxGlassArSort} 算好，本方法<b>只按顺序取行</b>
     *       ⇒ 渲染帧里没有任何排序/遍历（成本与改动前逐字相同：同样只画 ≤7 行）；</li>
     *   <li><b>行里印的槽号仍然是真槽号</b>（{@link State#moduleSlots} 的原值）⇒ 排序只影响"第几行画谁"；</li>
     *   <li>名字必须<b>按槽号</b>取（{@code moduleNames[r]} 与 {@code moduleSlots[r]} 一一对应）——
     *       一旦重排还按下标取名字，名字就会张冠李戴（本轮专门修了这一点，见
     *       {@link #moduleNameAt(State, int[], int)}）；</li>
     *   <li>{@code moduleOrder} 为 {@code null} / 不是同一个集合 ⇒ 退回 {@code moduleSlots} 原序（旧行为）。</li>
     * </ul>
     */
    private static void buildModuleList(State st, List<Prim> prims, List<Txt> texts) {
        final int z = Z_MODLIST;
        final HostFxGlassArPanel.Zone zo = HostFxGlassArPanel.ZONE_MODLIST;
        final int installed = st.installedModules >= 0 ? st.installedModules : st.moduleCount;
        // ── 表头（一列一个 Txt，和数据行同一套 u 偏移） ─────────────────────────────
        final float yh = px(zo.v + HostFxGlassArPanel.MODLIST_HEAD_V0);
        final String[] head = {"槽", "模块", "进度", "耗能EU/t", "并行", "线程", "输出"};
        final float[] hu = {
                HostFxGlassArPanel.MOD_SLOT_U, HostFxGlassArPanel.MOD_NAME_U,
                HostFxGlassArPanel.MOD_BAR_U, HostFxGlassArPanel.MOD_EU_U,
                HostFxGlassArPanel.MOD_PAR_U, HostFxGlassArPanel.MOD_THR_U,
                HostFxGlassArPanel.MOD_OUT_U,
        };
        for (int c = 0; c < head.length; c++) {
            texts.add(new Txt(z, c, px(zo.u + hu[c]), yh, head[c], C_HEAD));
        }
        // ── 数据行 ─────────────────────────────────────────────────────────────────
        final int[] slots = st.moduleSlots;
        if (slots == null || slots.length == 0) {
            texts.add(new Txt(z, 100, px(zo.u + HostFxGlassArPanel.MOD_SLOT_U),
                    px(zo.v + HostFxGlassArPanel.MODLIST_ROW_V0),
                    installed < 0 ? "模块列表 --（等伺服同步）" : "模块列表 --（槽位位图未到）", C_DIM));
            return;
        }
        // 显示顺序（槽号的一个置换；null / 不是同一个集合 ⇒ 退回 moduleSlots 原序）
        final int[] order = displayOrder(st);
        final int total = order.length;
        final boolean overflow = total > HostFxGlassArPanel.MODLIST_ROWS;
        // 装不下时最后一行留给"+N 台未显示"（本轮的截断策略，见类注释与报告）
        final int rows = overflow ? HostFxGlassArPanel.MODLIST_ROWS - 1 : total;
        for (int r = 0; r < rows; r++) {
            final int slot = order[r];
            final float y = px(zo.v + HostFxGlassArPanel.MODLIST_ROW_V0
                    + r * HostFxGlassArPanel.MODLIST_ROW_DV);
            // 槽位号
            texts.add(new Txt(z, 200 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_SLOT_U), y,
                    String.valueOf(slot), C_DIM));
            // 名字（截到 MOD_NAME_CHARS；取不到 ⇒ 槽#N）
            final String name = moduleNameAt(st, slots, slot);
            texts.add(new Txt(z, 201 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_NAME_U), y,
                    clip(name, HostFxGlassArPanel.MOD_NAME_CHARS), C_MAIN));
            // 进度条（几何）+ 百分比
            final int pct = perModuleByte(st.modProgressPct, slot);
            bar(prims, z, "mod.bar_back", "mod.bar_fill",
                    px(zo.u + HostFxGlassArPanel.MOD_BAR_U), y + px(0.03F),
                    px(HostFxGlassArPanel.MOD_BAR_W), px(HostFxGlassArPanel.MOD_BAR_H),
                    pct, HostFxGlassArPanel.MOD_BAR_SEG, C_BAR_BACK, C_MAIN);
            texts.add(new Txt(z, 202 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_PCT_U), y,
                    pctText(pct), pct < 0 ? C_DIM : C_MAIN));
            // 耗能
            final long eu = perModuleLong(st.modEuPerTick, slot);
            texts.add(new Txt(z, 203 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_EU_U), y,
                    euText(eu), eu < 0L ? C_DIM : C_MAIN));
            // 并行 / 线程（🔴 2026-10-25 第二十六改：用户「并行，线程，输出」要紧凑量级
            //   —— GT 的并行份数能到百万，原始 int 会把这一列顶成一片数字墙）
            final int par = perModuleInt(st.modParallel, slot);
            final int thr = perModuleInt(st.modThreads, slot);
            texts.add(new Txt(z, 204 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_PAR_U), y,
                    countOrDash(par), par < 0 ? C_DIM : C_MAIN));
            texts.add(new Txt(z, 205 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_THR_U), y,
                    countOrDash(thr), thr < 0 ? C_DIM : C_MAIN));
            // 输出（最多 2 项）
            // 🔴 2026-10-25 第三十四改（用户回归「ar的物品输出不显示了」）—— 提交顺序反过来：
            //    **标签先提交、输出后提交**（同一批文字里后画的盖前画的）⇒ 万一将来宽度又算错，
            //    被盖住的一定是标签，**绝不会是输出**。
            //    上一版的两个病根（数字见 temp/arsort/报告.md §10）：
            //      ① 输出列只有 MOD_LABEL_U − MOD_OUT_U = 105 px，而"两项 × 8 当量"最坏 148 px
            //         ⇒ 输出伸进标签列 43 px，且标签是后画的 ⇒ 第二项被整块盖住；
            //      ② 有标签时又把输出砍到 1 项 ⇒ 看起来就是"物品输出不显示了"。
            //    ⇒ 现在：标签挪到 9.74 格（宽 18 px = 2 字），输出列 3.54 格 = 177 px，
            //      **输出恒为 2 项**，再按 18 当量的预算裁（先截数字、绝不丢整项）。
            final int reason = perModuleByte(st.modReason, slot);
            final String reasonTag = reasonTag(reason);
            if (reasonTag != null) {
                texts.add(new Txt(z, HostFxGlassArPanel.MOD_LABEL_SLOT0 + r * 10,
                        px(zo.u + HostFxGlassArPanel.MOD_LABEL_U), y,
                        reasonTag, reasonArgb(reason)));
            }
            // 输出（恒 2 项；超出预算的部分按当量裁 —— 输出优先，标签不参与裁剪）
            final String[] outs = perModuleOutputs(st.modOutputs, slot);
            final String outLine = clip(outputsText(outs, HostFxGlassArPanel.MOD_ROW_OUTPUTS),
                    HostFxGlassArPanel.MOD_OUT_LINE_CHARS);
            texts.add(new Txt(z, 206 + r * 10, px(zo.u + HostFxGlassArPanel.MOD_OUT_U), y,
                    outLine, outs == null ? C_DIM : C_MAIN));
        }
        if (overflow) {
            // 截断策略（本轮定稿，写进报告）：**装不下就截断，最后一行只写"+N 台未显示"，不逐台列**。
            // 为什么选这一条：被截掉的必然是最久没跑过的那些（排序已把在跑/最近跑过的顶到最上面），
            // 逐台列出来占的全是"最不重要"的行；而一个总数足够让人知道"下面还有"。
            texts.add(new Txt(z, 101, px(zo.u + HostFxGlassArPanel.MOD_SLOT_U),
                    px(zo.v + HostFxGlassArPanel.MODLIST_ROW_V0
                            + rows * HostFxGlassArPanel.MODLIST_ROW_DV),
                    "+" + (total - rows) + " 台未显示", C_DIM));
        }
    }

    /**
     * ⑦ 的显示顺序：{@link State#moduleOrder} 是 {@link State#moduleSlots} 的<b>置换</b>时才用它，
     * 否则退回 {@link State#moduleSlots} 的<b>原序</b>（= 喂料侧给的槽号升序 = 改动前的行为）。
     *
     * <p>两道校验，任一不过就退回原序，<b>绝不画错</b>：
     * <ol>
     *   <li>长度相同；</li>
     *   <li>恰为同一集合（{@link HostFxGlassArSort#sameSet}）——
     *       面板是只读的，"漏一台机器"和"同一台画两行"都比"顺序不对"严重得多。</li>
     * </ol>
     * <p>为什么退回的是"原序"而不是"重新排序一次"：这是改动前的逐字行为（{@code moduleSlots} 本来就
     * 由喂料侧按槽号升序建出来），退回它 = 零行为差异、零每帧分配。
     */
    static int[] displayOrder(State st) {
        final int[] slots = st.moduleSlots;
        final int[] ord = st.moduleOrder;
        if (slots == null || ord == null || ord.length != slots.length) {
            return slots;
        }
        return HostFxGlassArSort.sameSet(slots, ord) ? ord : slots;
    }

    /**
     * 第 {@code slot} 号槽位上那台模块的名字（<b>按槽号</b>取，不是按显示行号取）。
     *
     * <p>🔴 2026-10-25 第三十二改：{@link State#moduleNames} 与 {@link State#moduleSlots}
     * <b>按数组下标一一对应</b>（喂料侧同时建的），而行的顺序改成"最近运行时间"之后就<b>不再</b>
     * 等于那个下标 ⇒ 必须先在下标里找到这个槽，再取同下标的那个名字。
     * 不这么改的话，重排之后名字会整体错位（槽 3 显示槽 17 的名字），而槽号仍然是对的 ——
     * 那是最难被发现的一种错。
     *
     * <p>取不到 ⇒ {@code 槽#slot}（<b>绝不编名字</b>）。
     */
    private static String moduleNameAt(State st, int[] slots, int slot) {
        if (st.moduleNamesUnavailable || st.moduleNames == null) {
            return "槽#" + slot;
        }
        for (int i = 0; i < slots.length && i < st.moduleNames.length; i++) {
            if (slots[i] != slot) {
                continue;
            }
            final String n = st.moduleNames[i];
            return n == null || n.isEmpty() ? "槽#" + slot : n;
        }
        return "槽#" + slot;
    }

    /** 按扫描位下标取一个 int（数组短了 / 缺了 ⇒ −1）。 */
    private static int perModuleInt(int[] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? -1 : arr[slot];
    }

    /** 按扫描位下标取一个 long（数组短了 / 缺了 ⇒ −1）。 */
    private static long perModuleLong(long[] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? -1L : arr[slot];
    }

    /** 按扫描位下标取一个 byte（数组短了 / 缺了 ⇒ −1）。 */
    private static int perModuleByte(byte[] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? -1 : arr[slot];
    }

    /** 按扫描位下标取输出数组（缺了 ⇒ {@code null} = 读不到）。 */
    private static String[] perModuleOutputs(String[][] arr, int slot) {
        return (arr == null || slot < 0 || slot >= arr.length) ? null : arr[slot];
    }

    /** 输出列表 → 一行文字（最多 {@code max} 项；多了补 {@code +N}；读不到 ⇒ {@code --}）。 */
    private static String outputsText(String[] outs, int max) {
        if (outs == null || outs.length == 0) {
            return "--";
        }
        final StringBuilder sb = new StringBuilder();
        final int n = Math.min(max, outs.length);
        for (int i = 0; i < n; i++) {
            if (outs[i] == null || outs[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(clip(outs[i], HostFxGlassArPanel.MOD_OUT_CHARS));
        }
        if (outs.length > n) {
            sb.append(" +").append(outs.length - n);
        }
        return sb.length() == 0 ? "--" : sb.toString();
    }

    /**
     * 主机节第 {@code i} 条输出 —— <b>三种状态必须分开写</b>（2026-10-25 第三十四改）：
     * <pre>
     *   {@code outs == null}      ⇒ 「输出① <b>读不到</b>」  ← 通道没到（服务端没推 / 客户端没解码出来）
     *   {@code outs.length == 0}  ⇒ 「输出① <b>无</b>」     ← 真没有输出（这是真读数）
     *   有这一条                  ⇒ 「输出① 名字×数量」
     * </pre>
     * 用户原话：「真拿不到就写"读不到"而不是 {@code --}（{@code --} 看起来像没数据显示，无法区分）」。
     */
    private static String hostOutputLine(State st, int i) {
        final String[] outs = st.hostOutputs;
        final String tag = "输出" + (char) ('①' + i);
        if (outs == null) {
            return tag + " 读不到";
        }
        if (i >= outs.length || outs[i] == null || outs[i].isEmpty()) {
            return tag + " 无";
        }
        return tag + " " + clip(outs[i], 12);
    }

    /**
     * 主机级 {@code EU/t}：{@code <0}（读不到）⇒ <b>「读不到」</b>，不是 {@code --}。
     *
     * <p>为什么必须与 {@code --} 分开：{@code --} 在面板上同时表示"没数据"和"这格没意义"，
     * 用户没法判断是机器没跑、还是读不到（他这次就是被这一点困住的）。
     */
    static String hostEuText(long v) {
        return v < 0L ? "读不到" : compactMagnitude(v);
    }

    /** 主机级计数（并行 / 线程）：{@code <0} ⇒ 「读不到」。 */
    static String hostCountText(int v) {
        return v < 0 ? "读不到" : compactMagnitude(v);
    }

    /**
     * 主机块<b>六个量</b>的一行读数（日志判死用）：
     * {@code 主机=[耗能=1.2M 并行=读不到 线程=读不到 输出=1/3]}（输出 = 有效条数 / 应有条数）。
     */
    public static String hostBlockCounts(State st) {
        final String[] outs = st.hostOutputs;
        final int have = outs == null ? -1 : outs.length;
        return "主机=[耗能=" + hostEuText(st.hostEuPerTick)
                + " 并行=" + hostCountText(st.hostParallel)
                + " 线程=" + hostCountText(st.hostThreads)
                + " 输出=" + (have < 0 ? "读不到" : have + "/" + st.hostOutputTotal) + "]";
    }

    /**
     * 主机块「有配方在跑时至少 4 个量非空」的判据（探针与自检共用）。
     *
     * <p>为什么是 4 而不是 6：并行 / 线程在本机<b>没有权威现值</b>（服务端写 −1，
     * 见 {@code refreshHostRuntimeSnapshot$body} 里"绝不拿上限冒充现值"那段）⇒ 它们本来就该是「读不到」；
     * 剩下 4 个（进度 / 剩 / 耗能 / 输出）必须都有真值。
     */
    public static int hostFilledCount(State st) {
        int n = 0;
        if (st.hostProgressPct >= 0) {
            n++;
        }
        if (st.hostRemainSeconds >= 0) {
            n++;
        }
        if (st.hostEuPerTick >= 0L) {
            n++;
        }
        if (st.hostOutputs != null && st.hostOutputs.length > 0) {
            n++;
        }
        if (st.hostOutputTotal >= 0) {
            n++;
        }
        if (st.hostParallel >= 0 || st.hostThreads >= 0) {
            n++;
        }
        return n;
    }

    /** 把字符串截到 {@code chars} 个"汉字当量"（ASCII 算半个；超了补 {@code …}）。 */
    static String clip(String s, int chars) {
        if (s == null) {
            return "--";
        }
        double w = 0.0D;
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final double cw = c < 0x2E80 ? 0.55D : 1.0D;
            if (w + cw > chars) {
                return sb.append('…').toString();
            }
            sb.append(c);
            w += cw;
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  图元绘制的小工具（全部输出四边形）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 中央上下两束这一帧的颜色：正常 = 冷蓝白（外层 + 内芯），降级 = {@link State#degradedRgb} 的两种强度。
     *
     * <p>🔴 唯一的取色处：③ 的锥形柱用它，② 的"正对镜头的束口环"也用它 ——
     * 两处必须同一个颜色，否则"关特效变红"会在同一块面板里自相矛盾。
     */
    private static int[] jetColor(State st) {
        return st.degraded
                ? new int[]{rgb(st.degradedRgb, 0.92F), rgb(st.degradedRgb, 0.75F)}
                : C_JET_NORMAL;
    }

    private static float px(float blocks) {
        return HostFxGlassArPanel.px(blocks);
    }

    /** 一条半径 1 px 的矩形环（分区角标）。 */
    private static void frame(List<Prim> out, int zone, HostFxGlassArPanel.Zone z) {
        final float x0 = px(z.u);
        final float y0 = px(z.v);
        final float x1 = px(z.u1());
        final float y1 = px(z.v1());
        line(out, zone, "frame", BLEND_ALPHA, C_FRAME, x0, y0, x1, y0, 1.0F);
        line(out, zone, "frame", BLEND_ALPHA, C_FRAME, x1, y0, x1, y1, 1.0F);
        line(out, zone, "frame", BLEND_ALPHA, C_FRAME, x1, y1, x0, y1, 1.0F);
        line(out, zone, "frame", BLEND_ALPHA, C_FRAME, x0, y1, x0, y0, 1.0F);
    }

    /**
     * 一条<b>有宽度的线</b>（宽度按法线摊开成四边形）。
     *
     * <p>宽度下限 {@link HostFxGlassArPanel#MIN_LINE_PX} = 1 px = 0.02 格（再细会随亚像素抖动）。
     */
    private static void line(List<Prim> out, int zone, String tag, int blend, int argb,
                             float x0, float y0, float x1, float y1, float w) {
        final float dx = x1 - x0;
        final float dy = y1 - y0;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        final float hw = Math.max(HostFxGlassArPanel.MIN_LINE_PX, w) * 0.5F;
        if (len < 1.0E-4F) {
            // 退化成一个点：画一个小方
            quad(out, zone, tag, blend, argb,
                    x0 - hw, y0 - hw, x0 + hw, y0 - hw, x0 + hw, y0 + hw, x0 - hw, y0 + hw);
            return;
        }
        final float nx = -dy / len * hw;
        final float ny = dx / len * hw;
        quad(out, zone, tag, blend, argb,
                x0 - nx, y0 - ny, x1 - nx, y1 - ny, x1 + nx, y1 + ny, x0 + nx, y0 + ny);
    }

    /** 轴对齐矩形。 */
    private static void rect(List<Prim> out, int zone, String tag, int blend, int argb,
                             float x0, float y0, float x1, float y1) {
        quad(out, zone, tag, blend, argb, x0, y0, x1, y0, x1, y1, x0, y1);
    }

    /** 一个四边形（4 个角依次给）。 */
    private static void quad(List<Prim> out, int zone, String tag, int blend, int argb,
                             float x0, float y0, float x1, float y1, float x2, float y2,
                             float x3, float y3) {
        out.add(new Prim(zone, tag, blend, argb,
                new float[]{x0, y0, x1, y1, x2, y2, x3, y3}));
    }

    /** 填充圆（扇形，每个扇片都是"退化四边形"）。 */
    private static void disc(List<Prim> out, int zone, String tag, int blend, int argb,
                             float cx, float cy, float r, int seg) {
        final int n = Math.max(6, seg);
        for (int i = 0; i < n; i++) {
            final double a0 = Math.PI * 2.0D * i / n;
            final double a1 = Math.PI * 2.0D * (i + 1) / n;
            final float x0 = cx + (float) Math.cos(a0) * r;
            final float y0 = cy + (float) Math.sin(a0) * r;
            final float x1 = cx + (float) Math.cos(a1) * r;
            final float y1 = cy + (float) Math.sin(a1) * r;
            quad(out, zone, tag, blend, argb, cx, cy, x0, y0, x1, y1, x1, y1);
        }
    }

    /** 圆环（r0..r1）。 */
    private static void annulus(List<Prim> out, int zone, String tag, int blend, int argb,
                                float cx, float cy, float r0, float r1, int seg) {
        final int n = Math.max(8, seg);
        for (int i = 0; i < n; i++) {
            final double a0 = Math.PI * 2.0D * i / n;
            final double a1 = Math.PI * 2.0D * (i + 1) / n;
            final float c0 = (float) Math.cos(a0);
            final float s0 = (float) Math.sin(a0);
            final float c1 = (float) Math.cos(a1);
            final float s1 = (float) Math.sin(a1);
            quad(out, zone, tag, blend, argb,
                    cx + c0 * r0, cy + s0 * r0, cx + c1 * r0, cy + s1 * r0,
                    cx + c1 * r1, cy + s1 * r1, cx + c0 * r1, cy + s0 * r1);
        }
    }

    /** 竖直方向压扁的圆环（吸积盘示意）。 */
    private static void annulusSquashed(List<Prim> out, int zone, String tag, int blend, int argb,
                                        float cx, float cy, float r0, float r1, float squash,
                                        int seg) {
        final int n = Math.max(8, seg);
        for (int i = 0; i < n; i++) {
            final double a0 = Math.PI * 2.0D * i / n;
            final double a1 = Math.PI * 2.0D * (i + 1) / n;
            final float c0 = (float) Math.cos(a0);
            final float s0 = (float) Math.sin(a0) * squash;
            final float c1 = (float) Math.cos(a1);
            final float s1 = (float) Math.sin(a1) * squash;
            quad(out, zone, tag, blend, argb,
                    cx + c0 * r0, cy + s0 * r0, cx + c1 * r0, cy + s1 * r0,
                    cx + c1 * r1, cy + s1 * r1, cx + c0 * r1, cy + s0 * r1);
        }
    }

    /** 圆弧（折线，每段一条 1 px 线）。 */
    private static void arc(List<Prim> out, int zone, String tag, int blend, int argb,
                            float cx, float cy, float r, float a0Deg, float a1Deg,
                            int seg, float w) {
        final int n = Math.max(2, seg);
        for (int i = 0; i < n; i++) {
            final double a0 = Math.toRadians(a0Deg + (a1Deg - a0Deg) * i / (double) n);
            final double a1 = Math.toRadians(a0Deg + (a1Deg - a0Deg) * (i + 1) / (double) n);
            line(out, zone, tag, blend, argb,
                    cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r,
                    cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, w);
        }
    }

    /** 转向箭头（在 {@code aDeg} 处，沿逆时针切向一个小 V）。 */
    private static void arrowHead(List<Prim> out, int zone, String tag, int blend, int argb,
                                  float cx, float cy, float r, float aDeg) {
        final double a = Math.toRadians(aDeg);
        final float x = cx + (float) Math.cos(a) * r;
        final float y = cy + (float) Math.sin(a) * r;
        final float tx = (float) -Math.sin(a);
        final float ty = (float) Math.cos(a);
        final float rx = (float) Math.cos(a);
        final float ry = (float) Math.sin(a);
        line(out, zone, tag, blend, argb, x, y, x - tx * 4.0F + rx * 2.0F, y - ty * 4.0F + ry * 2.0F, 1.0F);
        line(out, zone, tag, blend, argb, x, y, x - tx * 4.0F - rx * 2.0F, y - ty * 4.0F - ry * 2.0F, 1.0F);
    }

    /** 绕本点转 {@code angDeg} 的矩形（飞船 / 解压机塔体）。 */
    private static void rotRect(List<Prim> out, int zone, String tag, int blend, int argb,
                                float cx, float cy, float angDeg, float halfRadial, float halfTan) {
        final double a = angDeg;
        final float rx = (float) Math.cos(a);
        final float ry = (float) Math.sin(a);
        final float tx = -ry;
        final float ty = rx;
        final float ax = rx * halfRadial;
        final float ay = ry * halfRadial;
        final float bx = tx * halfTan;
        final float by = ty * halfTan;
        quad(out, zone, tag, blend, argb,
                cx - ax - bx, cy - ay - by, cx + ax - bx, cy + ay - by,
                cx + ax + bx, cy + ay + by, cx - ax + bx, cy - ay + by);
    }

    /** 一艘飞船（上组 = 实心亮块；下组 = 暗底 + 暖白描边，一眼可分）。 */
    private static void ship(List<Prim> out, int zone, String tag, int argb, boolean up,
                             float cx, float cy, float k, float angRad) {
        final float x = cx + (float) Math.cos(angRad) * (float) (SHIP_R * k);
        final float y = cy + (float) Math.sin(angRad) * (float) (SHIP_R * k);
        final float halfTan = Math.max(3.0F, (float) (SHIP_TAN_HALF * k));
        final float halfRad = Math.max(2.0F, (float) (SHIP_RAD_HALF * k));
        if (up) {
            rotRect(out, zone, tag, BLEND_ALPHA, argb, x, y, angRad, halfRad, halfTan);
            return;
        }
        // 下组：先铺一块暗底（把身后的环/光束压掉 ⇒ 读起来是"空心舱"），再画 4 条边
        rotRect(out, zone, tag, BLEND_ALPHA, 0xF00A1222, x, y, angRad, halfRad, halfTan);
        final float rx = (float) Math.cos(angRad);
        final float ry = (float) Math.sin(angRad);
        final float tx = -ry;
        final float ty = rx;
        final float ax = rx * halfRad;
        final float ay = ry * halfRad;
        final float bx = tx * halfTan;
        final float by = ty * halfTan;
        final float[] xs = {x - ax - bx, x + ax - bx, x + ax + bx, x - ax + bx};
        final float[] ys = {y - ay - by, y + ay - by, y + ay + by, y - ay + by};
        for (int i = 0; i < 4; i++) {
            final int j = (i + 1) & 3;
            line(out, zone, tag, BLEND_ADD, 0xEEFFD8A0, xs[i], ys[i], xs[j], ys[j], 1.2F);
        }
    }

    /**
     * <b>一段式进度条</b>（Jade 那种条）：先铺槽底，再按 {@code pct} 画已执行段，最后描一圈 1 px 边框。
     *
     * <p>🔴 {@code pct < 0}（读不到）时<b>只画槽底与边框、一段填充都不画</b> ——
     * 既不画满格也不画"0%"，读的人一眼就知道"这里没数"。
     *
     * @param segs 分段数（每段之间留 1 px 缝，视觉上是"一格一格在长"）
     */
    private static void bar(List<Prim> out, int zone, String backTag, String fillTag,
                            float x, float y, float w, float h, int pct, int segs,
                            int backArgb, int fillArgb) {
        rect(out, zone, backTag, BLEND_ALPHA, backArgb, x, y, x + w, y + h);
        final int n = Math.max(1, segs);
        final float gap = HostFxGlassArPanel.MIN_LINE_PX;
        final float segW = (w - gap * (n - 1)) / n;
        if (pct >= 0) {
            final int lit = Math.min(n, Math.max(0, Math.round(pct * n / 100.0F)));
            for (int i = 0; i < lit; i++) {
                final float sx = x + i * (segW + gap);
                rect(out, zone, fillTag, BLEND_ADD, fillArgb, sx, y, sx + segW, y + h);
            }
        }
        // 边框（1 px，四条线）
        line(out, zone, backTag, BLEND_ALPHA, C_BAR_EDGE, x, y, x + w, y, 1.0F);
        line(out, zone, backTag, BLEND_ALPHA, C_BAR_EDGE, x + w, y, x + w, y + h, 1.0F);
        line(out, zone, backTag, BLEND_ALPHA, C_BAR_EDGE, x + w, y + h, x, y + h, 1.0F);
        line(out, zone, backTag, BLEND_ALPHA, C_BAR_EDGE, x, y + h, x, y, 1.0F);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  文本格式化（"绝不放假数"的那一层）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 把 0..1 的三通道色 + 强度合成 ARGB。 */
    private static int rgb(float[] c, float alpha) {
        final int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        final int r = Math.max(0, Math.min(255, Math.round(c[0] * 255.0F)));
        final int g = Math.max(0, Math.min(255, Math.round(c[1] * 255.0F)));
        final int b = Math.max(0, Math.min(255, Math.round(c[2] * 255.0F)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static String intOrDash(int v) {
        return v < 0 ? "--" : String.valueOf(v);
    }

    /** 百分比：{@code <0} ⇒ {@code --}。 */
    static String pctText(int pct) {
        return pct < 0 ? "--" : pct + "%";
    }

    /** 剩余秒数：{@code <0} ⇒ {@code --}。 */
    static String secText(int sec) {
        return sec < 0 ? "--" : sec + "s";
    }

    /**
     * 数值 → <b>紧凑量级</b>（不带单位，单位由调用方的标签给）：{@code 1.2M} / {@code 124k} / {@code 512}。
     *
     * <p>🔴 {@code <0}（读不到）⇒ {@code --}，<b>绝不是 0</b>。
     *
     * <p>2026-10-25 第二十六改：<b>本方法只是 {@link #compactMagnitude} 的"夹 {@code --}"外壳</b> ——
     * 以前它自己另有一套量级表（{@code %.1fk / %.2fM / %.2fG}，<b>没有 T/P/E</b>），
     * 而 {@code compactMagnitude} 有 T/P/E ⇒ 两套精度/后缀不一致（{@code 5e12} 走这里会印成
     * {@code 5000.00G}）。现在统一到一套：<b>唯一的量级实现在 {@link #compactMagnitude}</b>。
     */
    static String euText(long v) {
        return v < 0L ? "--" : compactMagnitude(v);
    }

    /**
     * 件数（与 {@link #euText} 同一种紧凑写法；{@code <0} ⇒ {@code --}）。
     *
     * <p>用户 2026-10-25 原话：「<b>这个数字有点大，都自动变成多少k多少m这种（并行，线程，输出）</b>」
     * ⇒ 条目数一律走这里，不再直接 {@code String.valueOf}。
     */
    static String countText(long v) {
        return euText(v);
    }

    /**
     * <b>计数</b>（并行份数 / 跨配方线程数 / 条目数）→ 紧凑量级；{@code < 0} = 读不到 ⇒ {@code --}。
     *
     * <p>为什么和 {@link #countText} 分开：这里收 {@code int}（同步字段本来就是 int 数组），
     * 省一次装箱/拓宽，且语义上明确"这是可并行到百万的计数，不是 EU"。
     */
    static String countOrDash(int v) {
        return v < 0 ? "--" : compactMagnitude(v);
    }

    /**
     * <b>功率</b>（{@code EU/t} 瞬时速率）→ 紧凑量级 + {@code /t}：{@code 1.20M/t} / {@code 124.0k/t}。
     *
     * <p>用户 2026-10-25 口径：「总耗能/总发电我说的应该是总耗能功率/总发电功率」⇒ 单位是
     * <b>EU/t</b>。量级用 {@code K/M/G/T/E}（1~2 位小数），后缀固定 {@code /t}
     * （{@code EU/t} 里的 {@code EU} 由行首那四个字承担，省 2 个字符宽度）。
     *
     * <p>🔴 {@code < 0}（读不到）⇒ {@code --/t}，<b>绝不显示 0</b>；{@code 0} 是合法真读数
     * （"在跑但这一瞬没吞吐"或"没装仓" —— 后者服务端给的是 −1，不会走到这里）。
     */
    static String powerText(long euPerTick) {
        if (euPerTick < 0L) {
            return "--/t";
        }
        return compactMagnitude(euPerTick) + "/t";
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  主机级「总耗能功率 / 模块耗电合计 / 总产出」的三条显示口径（2026-10-25 第二十六改）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * <b>模块侧实际耗电之和</b>（{@code EU/t}）：已装槽位的 recipe logic 当前 {@code EU/t} 相加。
     *
     * <p>🔴 为什么还要这一条（用户 bug「总耗能这里没显示」）：仓侧实测吞吐是"主机问仓要了多少"，
     * 而创造能源仓 / 无线电网终端<b>本来就没有每秒吞吐这个量</b>（{@code InfinityEnergyContainer}
     * 没覆写 {@code getInputPerSec()} ⇒ 恒 0）⇒ 那两档下"总耗能功率"只能写 {@code ∞}，
     * <b>但玩家真正想看的是"我这 5 台模块在烧多少电"</b> —— 那个量在模块自己身上，不受供电源影响。
     *
     * <p>🔴 <b>部分可读 ≠ 可信</b>：任何一个<b>已装</b>槽位读不到（{@code < 0}）⇒ 整条返回 {@code -1}
     * （面板画 {@code --}）。宁可缺，不可假 —— 少加一台就少一个数量级，比 {@code --} 危险得多。
     */
    static long moduleEuSum(State st) {
        final long[] eu = st.modEuPerTick;
        if (eu == null || eu.length == 0) {
            return -1L;
        }
        final int[] slots = st.moduleSlots;
        long sum = 0L;
        if (slots != null && slots.length > 0) {
            for (int slot : slots) {
                if (slot < 0 || slot >= eu.length) {
                    return -1L;
                }
                final long v = eu[slot];
                if (v < 0L) {
                    return -1L;
                }
                sum += v;
            }
            return sum;
        }
        // 没有权威槽位表（客户端还没同步位图）⇒ 只能把"可读的那些"加一遍；
        // 一个都没有 ⇒ 读不到（不是 0）。
        boolean any = false;
        for (long v : eu) {
            if (v >= 0L) {
                sum += v;
                any = true;
            }
        }
        return any ? sum : -1L;
    }

    /**
     * <b>总耗能功率</b>这一格的完整文案（含单位 / {@code ∞} 语义）。
     *
     * <pre>
     *   创造能源仓        ⇒ ∞（创造能源仓）   ← 仓侧没有吞吐量，不是 0
     *   无线电网输入终端  ⇒ ∞（无线电网终端）
     *   普通/无线/电网仓  ⇒ 1.2M/t            ← 仓侧实测吞吐（唯一"真的量到的数"）
     *   读不到            ⇒ --/t
     *   仓侧恒 0 而模块在烧电 ⇒ ∞（无限供电）  ← 计量盲，见 {@link #energyUsedIsBlind}
     * </pre>
     *
     * <p>🔴 用户口径：「<b>不要</b>把 0 伪装成 {@code --}，也不要把 {@code --} 伪装成 0」——
     * 这里四种语义四种文案，一个都不许合并。
     */
    static String energyUsedPowerText(State st, long moduleSum) {
        switch (st.energyInKind) {
            case ENERGY_KIND_CREATIVE:
                return "∞（创造能源仓）";
            case ENERGY_KIND_NET_TERMINAL:
                return "∞（无线电网终端）";
            default:
                break;
        }
        if (st.totalEuUsedPerTick < 0L) {
            return "--/t";
        }
        if (energyUsedIsBlind(st, moduleSum)) {
            return "∞（无限供电）";
        }
        return compactMagnitude(st.totalEuUsedPerTick) + "/t";
    }

    /**
     * <b>计量盲</b>：仓侧读到<b>恒 0</b>，而模块侧确有 {@code > 0} 的耗电。
     *
     * <p>为什么这个推断成立：GT 的配方 EU 是真扣的，模块在跑就必然从某个 {@code IEnergyContainer}
     * 里扣电；仓侧那一路读到 0 只剩一种解释 —— <b>供电源不记账</b>（无限供电的仓）。
     * 反之模块没在烧电（{@code moduleSum <= 0}）时，0 就是<b>真的 0</b>，照写 {@code 0/t}。
     *
     * <p>它只是<b>兜底</b>：{@link State#energyInKind} 能认出来时用前者（更明确）。
     */
    static boolean energyUsedIsBlind(State st, long moduleSum) {
        return st.totalEuUsedPerTick == 0L && moduleSum > 0L;
    }

    /**
     * <b>总产出</b>这一格：种类数 + 件数。
     *
     * <p>🔴 用户在截图里点出的毛病：「{@code 总产出 -- · 14.18M 件} 这种<b>前半 {@code --}
     * 后半有值</b>也要统一（要么都给数，要么整行说清缺哪半）」⇒ 缺哪半就点名哪半，
     * 不再出现"哑掉的 {@code --}"。
     */
    static String totalOutputText(int kinds, long count) {
        if (kinds < 0 && count < 0L) {
            return "产出 --（快照未到）";
        }
        if (kinds < 0) {
            return "产出 类数 -- · " + compactMagnitude(count) + " 件";
        }
        if (count < 0L) {
            return "产出 " + kinds + " 类 · 件数 --";
        }
        return "产出 " + kinds + " 类 " + compactMagnitude(count) + " 件";
    }

    /**
     * 纯数值 → 紧凑量级（不带单位）：{@code 1.2M} / {@code 124k} / {@code 512} / {@code 0}。
     *
     * <p>量级表按用户口径给到 {@code E}（10^18）：{@code K/M/G/T/P/E}。
     * 与 {@link #euText} 的区别：这里<b>不夹带 {@code --}</b>（调用方自己判 &lt;0）。
     *
     * <p>2026-10-25 第二十六改（用户原话：「<b>都自动变成多少k多少m这种</b>」）：
     * <b>保留 2 位小数、去掉尾随 {@code .0}/{@code .00}</b> —— 旧实现 {@code 124.0k}、
     * {@code 14.20M} 里那个没意义的 {@code .0} 就是在 AR 那点宽度里白占两个字符。
     * <b>四舍五入后跨档</b>（如 {@code 999_999} → {@code 1000.0k}）先升一档，印成 {@code 1M}。
     *
     * <p>🔴 这是<b>全工程唯一的紧凑量级实现</b>：{@link #euText} / {@link #countText} /
     * {@link #countOrDash} / {@link #powerText} 全部委派到这里（精度与后缀只此一份）。
     */
    static String compactMagnitude(long v) {
        if (v < 1_000L) {
            return String.valueOf(v);
        }
        final String[] suffix = MAGNITUDE_SUFFIX;
        double scaled = v;
        int u = 0;
        while (scaled >= 1000.0D && u < suffix.length - 1) {
            scaled /= 1000.0D;
            u++;
        }
        // 四舍五入到 2 位后若已经够 1000（999_999 ⇒ 1000.00k）⇒ 再升一档，绝不印 1000k
        if (round2(scaled) >= 1000.0D && u < suffix.length - 1) {
            scaled /= 1000.0D;
            u++;
        }
        return stripZeros(String.format(Locale.ROOT, "%.2f", scaled)) + suffix[u];
    }

    /** 量级后缀（下标 = 分了几次 1000）。 */
    private static final String[] MAGNITUDE_SUFFIX = {"", "k", "M", "G", "T", "P", "E"};

    /** 四舍五入到 2 位小数（与 {@code %.2f} 的舍入口径一致）。 */
    private static double round2(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }

    /** {@code "1.20"} → {@code "1.2"}、{@code "3.00"} → {@code "3"}、{@code "124.00"} → {@code "124"}。 */
    private static String stripZeros(String s) {
        if (s.indexOf('.') < 0) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 种类数：{@code <0} ⇒ {@code --}，否则 {@code n 类}。 */
    static String kindsText(int v) {
        return v < 0 ? "--" : v + " 类";
    }

    /** 物质模块等级：{@code <0} ⇒ --、{@code 0} ⇒ 未生效、{@code >0} ⇒ Lv.n / 17。 */
    private static String levelText(int v) {
        if (v < 0) {
            return "--";
        }
        return v == 0 ? "未生效" : "Lv." + v + " / 17";
    }

    /** 等级复写：{@code <0} ⇒ --、{@code 0} ⇒ 未生效。 */
    private static String overrideText(int v) {
        if (v < 0) {
            return "--";
        }
        return v == 0 ? "未生效" : "Lv." + v;
    }

    /**
     * 容量 → <b>紧凑格式</b>（用户口径：`1.07G EU`），拿不到 ⇒ {@code --}。
     *
     * <p>🔴 只显示<b>容量</b>（客户端可验证：按电压等级算出来的），<b>不显示存量</b>：
     * {@code EnergyHatchPartMachine.energyContainer} 是 {@code @Persisted} 而<b>没有</b>
     * {@code @DescSynced} ⇒ 存量根本不同步到客户端（读了永远是 0，那就是假数）。
     * 所以行文写的是"容量"，不是"存量/容量"。
     */
    static String energyText(long capacity) {
        if (capacity < 0L) {
            return "--";
        }
        // 2026-10-25 第二十六改：这里以前是**第四套**各自的量级阶梯（%.1fP / %.2fT / %.2fG …），
        // 与 compactMagnitude 精度不一致 ⇒ 统一委派（唯一实现见 compactMagnitude），后缀保留 " EU"。
        return compactMagnitude(capacity) + " EU";
    }

    /**
     * 「已存 / 容量」一格（2026-10-25 第二十三改）。
     *
     * <p>用户口径（截图 `4996 / 4996 EU`、动力仓是一串 19 位数字）：
     * <b>≥1000 一律紧凑单位</b>（K/M/G/T/E，1~2 位小数），别把整串数字塞进 AR 一列。
     * 已存读不到 ⇒ 仍显示容量（退回旧文案，不显示假的 0）；两者都读不到 ⇒ {@code --}。
     */
    static String storedOverCapacity(long stored, long capacity) {
        if (capacity < 0L) {
            return stored < 0L ? "--" : energyText(stored) + " / --";
        }
        return (stored < 0L ? "已存 --" : energyText(stored)) + " / " + energyText(capacity);
    }

    /** 一位小数（用于角速度 / 半径 / 秒）。 */
    private static String one1(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 两位小数（物理量的格数）。 */
    private static String two2(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
