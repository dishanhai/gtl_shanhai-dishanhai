package com.shanhai.client.render.hostfx;

import java.util.Locale;

/**
 * 归墟视界枢核 · <b>玻璃 AR 面板的版式（版式 C · 左侧图列 + 右侧运行信息栏）</b> —— 纯排版/几何，
 * <b>零 Minecraft 依赖</b>。
 *
 * <h2>用户口径（原话，本轮 2026-10-25 第二次改版）</h2>
 * <pre>
 *   「<b>这个可以删除</b>，图4」                        ← ⑤ 徽标块里那两个<b>黄色叉</b>（cross）删掉
 *   「<b>这三张图（红框）可以统一挪到左侧</b>，它们占的空间有点大，图5」
 *                                                      ← ②俯视 / ③侧视 / ④黑洞小图 ⇒ 左侧竖排一列、缩小
 *   「<b>这两行字可以删了</b>，图6-7」                  ← ②③ 底部那两行注记（见 {@link #FIG_INSET} 注释）
 *   「<b>右侧空出来这么大块地方都可以显示模块和主机的运行配方的信息</b>…」
 *                                                      ← 右侧 46% = {@link #ZONE_HOST}（主机）+ {@link #ZONE_MODLIST}（模块列表）
 *   「然后6我说的是<b>AR上面的显示</b>啊」              ← 全部画在这面玻璃墙上，不是 GUI/Jade
 * </pre>
 * 上一版（版式 B）的"五块分区 + ⑥ 已装模块"已被本轮替换：⑤ 徽标的绿字并进
 * {@link #ZONE_HOST} 右侧那一列，⑥ 的三行模块名升级成 {@link #ZONE_MODLIST} 的 24 行列表。
 *
 * <h2>玻璃面在哪（可复核的推导，与上一版逐字相同）</h2>
 * 结构 {@code guixu_vision_core.bin}（148×135×131）里 {@code J}（{@code gtceu:fusion_glass}）
 * <b>恰好 88 格 = 22 × 4 × 1</b>，落在生成器
 * <pre>
 *   x = 64（单层）, y = 21..24（4 层）, z = -10..11（22 列）
 *   ⇒ 环心本地（每格占 [n−0.5, n+0.5]）：整面玻璃 x ∈ [63.5, 64.5]、y ∈ [10.5, 14.5]、z ∈ [−10.5, 11.5]
 *   ⇒ <b>朝环心那一面 = 本地 x = 63.5</b>（环心在 x=0 那一侧，房间在 +X）
 * </pre>
 *
 * <h2>两套坐标（画面上所有东西都在第二套里排版）</h2>
 * <table border="1">
 *   <tr><th>坐标</th><th>定义</th><th>用途</th></tr>
 *   <tr><td><b>本地（世界）</b></td><td>环心为原点：{@code x} = 玻璃法线方向，{@code y} 向上，{@code z} 沿墙</td>
 *       <td>只出现在 {@link #localY(float)} / {@link #localZ(float)} 这两个映射里</td></tr>
 *   <tr><td><b>墙面帧 wall frame</b></td><td>左上原点、{@code u} 向右（房间里的右手边）、{@code v} 向下，<b>单位格</b>；
 *       整面墙 {@code u ∈ [0,22]}、{@code v ∈ [0,4]}</td>
 *       <td><b>本类与 {@link HostFxGlassArModel} 里的所有数字都是这套</b></td></tr>
 * </table>
 * 映射（{@code rotY(+90°) · scale(s, −s, −s)} 那一套，推导见 {@link HostFxGlassArText} 类注释）：
 * <pre>
 *   local y = WALL_Y1 − v      local z = WALL_Z1 − u
 * </pre>
 * 于是"房间里看的左上角" = 墙面帧的 (0,0)，<b>墙面向右下长</b>，与写 HTML 的手感一致。
 *
 * <h2>七块分区（版式 C）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>名称</th><th>u 起</th><th>v 起</th><th>宽 × 高（格）</th><th>内容</th></tr>
 *   <tr><td>①</td><td>{@link #ZONE_READ}</td><td>3.80</td><td>0.40</td><td>6.80 × 1.70</td>
 *       <td>读数 <b>2 栏 × 8 行</b>（版式 B 是 4×4；左侧被图列占了 ⇒ 折成 2 栏 8 行，16 项一项不少）</td></tr>
 *   <tr><td>②</td><td>{@link #ZONE_FIG_TOP}</td><td>0.40</td><td>0.40</td><td>3.20 × 0.98</td>
 *       <td>俯视示意图（内环带 / 8 舰 / 12 细紫束 / 4 解压机 / 环心黑洞）</td></tr>
 *   <tr><td>③</td><td>{@link #ZONE_FIG_SIDE}</td><td>0.40</td><td>1.51</td><td>3.20 × 0.98</td>
 *       <td>侧视示意图（中央两柱 / 4 解压机主柱 / 环带截面 / 环心黑洞）</td></tr>
 *   <tr><td>④</td><td>{@link #ZONE_FIG_HOLE}</td><td>0.40</td><td>2.62</td><td>3.20 × 0.98</td>
 *       <td>黑洞小图（阴影 + 光子环 + 吸积盘）</td></tr>
 *   <tr><td>⑤</td><td>{@link #ZONE_PHYS}</td><td>3.80</td><td>2.20</td><td>6.80 × 1.40</td>
 *       <td>6 条物理量（黑洞半径 / Rs / 光子球 / ISCO / 盘外缘 / 逃逸速度）</td></tr>
 *   <tr><td>⑥</td><td>{@link #ZONE_HOST}</td><td>11.20</td><td>0.40</td><td>10.20 × 1.35</td>
 *       <td><b>主机节</b>：进度条+剩余 / 耗能 / 并行 / 线程 / 输出×3 ‖ 已装 / 总产出 / 总耗能·总发电 / 徽标绿字</td></tr>
 *   <tr><td>⑦</td><td>{@link #ZONE_MODLIST}</td><td>11.20</td><td>1.90</td><td>10.20 × 1.70</td>
 *       <td><b>模块列表</b>：表头 + 最多 {@link #MODLIST_ROWS} 行（槽位 / 名字 / 进度条 / 耗能 / 并行 / 线程 / 输出）</td></tr>
 * </table>
 * 四边边距 <b>0.40 格</b>（{@link #MARGIN}）：左 0.40、上 0.40、右 <b>0.60</b>（22 − 21.40）、下 0.40。
 * <b>左侧图列宽 3.20 格 = 面板宽的 14.5%</b>（用户上限是 22% = 4.84 格，见 {@link #FIG_COL_W}）。
 *
 * <h2>字号（沿用 1/50，改版不动这个常量）</h2>
 * {@link #PIXEL} = 1/50 = 0.02 格/MC 像素 ⇒ 字高 8 px × 0.02 = <b>0.16 格</b>、行距 9 px × 0.02 = <b>0.18 格</b>。
 * 依据（{@code temp/host-ui/报告-AR版式预览.md} §3）：1080p/70° 视野下，站在控制台前（约 8 格）字高 <b>17.7 px</b>
 * ⇒ 可读；1/60 只有 14.7 px（偏小）。<b>1/60 不许整体用</b>。
 *
 * <h2>线宽下限 0.02 格 = 1 MC 像素 = {@link #MIN_LINE_PX}</h2>
 * 依据：{@code POSITION_COLOR} 这条路径没有抗锯齿，1 px 细线在移动时已经是最小可画单位，
 * 再细（0.5 px）会随亚像素抖动。本类把"1 px"钉成常量，模型侧所有线都夹到它。
 */
public final class HostFxGlassArPanel {

    private HostFxGlassArPanel() {}

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  玻璃墙（生成器坐标，可复核）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 玻璃墙所在生成器 x（单层）。 */
    public static final int CELL_X = 64;
    /** 玻璃墙生成器 y 范围（4 层）。 */
    public static final int CELL_Y0 = 21;
    public static final int CELL_Y1 = 24;
    /** 玻璃墙生成器 z 范围（22 列）。 */
    public static final int CELL_Z0 = -10;
    public static final int CELL_Z1 = 11;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  本地坐标（= 生成器 − 环心格，见类注释）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** <b>朝环心那一面</b>的本地 x = 63.5（房间在 +X 那侧）。 */
    public static final float FACE_LOCAL_X = 63.5F;
    /** 文字平面再往环心方向让 0.02 格（防 z-fighting，也让"投影"有厚度感）。 */
    public static final float TEXT_OFFSET = 0.020F;
    /**
     * 几何（线/面）平面再比文字平面往环心让 0.012 格。
     *
     * <p>为什么必须分开：{@code RenderType.text} 与 {@code POSITION_COLOR} 两条层都会写深度，
     * 贴在同一平面上会出现<b>逐像素闪烁</b>（z-fighting）。让几何在后面 0.012 格 ⇒ 从房间里看顺序恒定：
     * 玻璃（63.5）→ 文字（63.48）→ 几何（63.468）。
     */
    public static final float GEOM_OFFSET = 0.012F;
    /** 窗口净高：[{@link #WALL_Y0}, {@link #WALL_Y1}]。 */
    public static final float WALL_Y0 = 10.5F;
    public static final float WALL_Y1 = 14.5F;
    /** 窗口净宽：[{@link #WALL_Z0}, {@link #WALL_Z1}]。 */
    public static final float WALL_Z0 = -10.5F;
    public static final float WALL_Z1 = 11.5F;
    /** 窗口中心（y = 12.5、z = 0.5）。 */
    public static final float CENTER_Y = (WALL_Y0 + WALL_Y1) * 0.5F;
    public static final float CENTER_Z = (WALL_Z0 + WALL_Z1) * 0.5F;
    /** 墙面帧的尺寸（格）：22 × 4。 */
    public static final float WALL_W = WALL_Z1 - WALL_Z0;
    public static final float WALL_H = WALL_Y1 - WALL_Y0;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  排版（字号 / 行距 / 边距）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 每像素多少格 = <b>1/50 = 0.020</b>（用户：「字太大了，就现在左上角显示小的就行」，1/50 是已批准的档位）。 */
    public static final float PIXEL = 1.0F / 50.0F;
    /** 一行多少像素（MC 字体行距）。 */
    public static final int LINE_PX = 9;
    /** 字形墨迹高度（像素）。 */
    public static final int INK_PX = 8;
    /** 四边最小边距（格）—— 用户硬要求 ≥ 0.40。 */
    public static final float MARGIN = 0.40F;
    /** 分区内边距（格）。 */
    public static final float ZONE_PAD = 0.10F;
    /** 线宽下限：<b>1 MC 像素 = 0.02 格</b>（再细会抖，见类注释）。 */
    public static final float MIN_LINE_PX = 1.0F;
    /** 一格多少 MC 像素（= 1 / {@link #PIXEL}）。 */
    public static final float PX_PER_BLOCK = 1.0F / PIXEL;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  进度条格数（2026-10-25 第三十一改：10 格太粗 ⇒ 按"条有多宽"推导，上限 40）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 进度条分段数上限 <b>40</b>（一格 = 2.5%）。
     *
     * <p>用户口径：「进度条不流畅，但 60fps 也不流畅 ⇒ 不是刷新率」。上一轮已经把刷新频率修到
     * <b>每 tick 一次</b>（服务端每 tick 刷 4 个进度字段、客户端每 tick 重建模型），剩下的直接原因
     * 就只有这条：{@code 10} 格 ⇒ 一格 10% ⇒ 400 tick 的配方 <b>1.8 秒才蹦一格</b>，
     * 无论多少 fps 都只能看到"一格一格跳"。40 格时同样配方 <b>0.45 秒一格</b>。
     */
    public static final int BAR_SEG_MAX = 40;

    /**
     * 每格<b>平均节距</b>的下限（MC 像素）—— 也就是"糊不糊"的判据。
     *
     * <p>节距 = 一格占的横向空间（亮段 + 后面那 1 px 缝）= {@code 条宽 / 格数}。
     * 取 <b>1.6</b>：1.5 px 亮段 + 缝的余量。低于它，相邻格会连成一片，读的人分不清"几格"，
     * 细分就白做了。
     */
    public static final float BAR_SEG_MIN_PITCH_PX = 1.6F;

    /**
     * 由<b>条有多宽</b>推出这条进度条该分几格。
     *
     * <p>取三条判据里最紧的那条（再夹到 {@link #BAR_SEG_MAX}）：
     * <ol>
     *   <li><b>目标上限</b> {@link #BAR_SEG_MAX} = 40 格（一格 2.5%）；</li>
     *   <li><b>节距判据</b> {@code floor(条宽px / 1.6)} —— 用户口径
     *       {@code maxSeg = min(40, floor(可用像素宽 / 1.6))}；</li>
     *   <li><b>亮段本身不小于 1 px</b> {@code floor((条宽px + 1) / 2)}。
     *       这条是<b>本类自己的既有红线</b>：{@link #MIN_LINE_PX} 那一节已经写明"再细（0.5 px）
     *       会随亚像素抖动"，而 {@code HostFxGlassArModel#bar} 里每格之间固定留 1 px 缝，
     *       所以 {@code 亮段 = (条宽 − (格数−1) × 1) / 格数 ≥ 1} ⇔ {@code 格数 ≤ (条宽 + 1) / 2}。
     *       <b>它比第 2 条更紧的只有窄条</b>（模块行那条），见 {@link #MOD_BAR_SEG} 的注释。</li>
     * </ol>
     *
     * <p>实测两个条各自的结果（{@code temp/ar-segs/报告.md} 有原始读数）：
     * <table border="1">
     *   <tr><th>条</th><th>宽</th><th>判据 2</th><th>判据 3</th><th>生效</th><th>亮段 / 节距</th></tr>
     *   <tr><td>主机</td><td>{@link #HOST_BAR_W} = 1.90 格 = <b>95 px</b></td><td>59</td><td>48</td>
     *       <td><b>40</b></td><td>1.40 px / 2.38 px</td></tr>
     *   <tr><td>模块行</td><td>{@link #MOD_BAR_W} = 0.86 格 = <b>43 px</b></td><td>26</td><td>22</td>
     *       <td><b>22</b></td><td>1.00 px / 1.95 px</td></tr>
     * </table>
     *
     * @param barW 条本身的宽度（格，= {@code px(条宽)} 之前的值）
     * @return 分段数，恒 ≥ 1
     */
    public static int barSegments(float barW) {
        final int pxW = Math.round(px(barW));                                    // 条净宽（MC 像素）
        final int byPitch = (int) Math.floor(pxW / BAR_SEG_MIN_PITCH_PX);         // 判据 2
        final int byFill = (int) Math.floor((pxW + MIN_LINE_PX) / 2.0F);         // 判据 3
        return Math.max(1, Math.min(BAR_SEG_MAX, Math.min(byPitch, byFill)));
    }

    /** 一行"格数怎么来的"读数（日志 / 探针 / 报告共用；含三条判据的中间值，便于复核）。 */
    public static String barSegStats() {
        final StringBuilder sb = new StringBuilder();
        final String[] names = {"主机条", "模块条"};
        final float[] ws = {HOST_BAR_W, MOD_BAR_W};
        final int[] segs = {HOST_BAR_SEG, MOD_BAR_SEG};
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append("；");
            }
            final int pxW = Math.round(px(ws[i]));
            final int byPitch = (int) Math.floor(pxW / BAR_SEG_MIN_PITCH_PX);
            final int byFill = (int) Math.floor((pxW + MIN_LINE_PX) / 2.0F);
            final float segW = (pxW - MIN_LINE_PX * (segs[i] - 1)) / segs[i];
            sb.append(names[i]).append(' ').append(pxW).append(" px / 判据2=").append(byPitch)
                    .append(" 判据3=").append(byFill).append(" ⇒ ").append(segs[i]).append(" 格")
                    .append("（一格 ").append(f2(100.0F / segs[i])).append("%、亮段 ")
                    .append(f2(segW)).append(" px、节距 ").append(f2((float) pxW / segs[i]))
                    .append(" px）");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  分区（墙面帧，单位格：u 向右、v 向下）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 一块 AR 分区。 */
    public static final class Zone {
        /** 中文名（日志/判据里点名用）。 */
        public final String name;
        /** 左上角（格）。 */
        public final float u;
        public final float v;
        /** 尺寸（格）。 */
        public final float w;
        public final float h;

        public Zone(String name, float u, float v, float w, float h) {
            this.name = name;
            this.u = u;
            this.v = v;
            this.w = w;
            this.h = h;
        }

        /** 右沿（格）。 */
        public float u1() {
            return u + w;
        }

        /** 下沿（格）。 */
        public float v1() {
            return v + h;
        }

        /** 含内边距的可用区（格）。 */
        public float cu() {
            return u + ZONE_PAD;
        }

        public float cv() {
            return v + ZONE_PAD;
        }

        public float cw() {
            return w - 2 * ZONE_PAD;
        }

        public float ch() {
            return h - 2 * ZONE_PAD;
        }

        /** 点在不在块里（含容差）。 */
        public boolean contains(float pu, float pv, float eps) {
            return pu >= u - eps && pu <= u1() + eps && pv >= v - eps && pv <= v1() + eps;
        }
    }

    // ── 左侧图列（②③④ 竖排；用户：「统一挪到左侧」「占的空间有点大」） ────────────────
    /** 一列图的分区：宽 = {@link #FIG_COL_W}、高 = {@link #FIG_H}。 */
    public static final float FIG_COL_U = 0.40F;
    /** 左列宽 3.20 格 = <b>面板宽的 14.5%</b>（用户上限 4.84 格 = 22%，这里更窄 ⇒ 满足）。 */
    public static final float FIG_COL_W = 3.20F;
    /**
     * 每张图的高度（格）= (3.20 − 2 × {@link #FIG_GAP}) / 3。
     *
     * <p>3.20 = 可用净高（4.00 − 上下各 0.40 边距）。{@link #FIG_GAP} = 0.13 ⇒ 三张各 0.98、
     * 下沿正好落在 v = 3.60（= 4.00 − {@link #MARGIN}）上，<b>一像素边距都不吃</b>。
     */
    public static final float FIG_H = 0.98F;
    /** 图与图之间的竖向间隙（格）。 */
    public static final float FIG_GAP = 0.13F;
    /**
     * 绘图框相对本块的内缩（格，四边同值）。
     *
     * <p>🔴 <b>它同时就是"删掉那两行注记"的实现</b>：版式 B 里绘图框的下沿是
     * {@code v + CAPTION_V − 0.06}（给底部注记留了 0.40 格），注记一删，绘图框直接吃满
     * {@code [v+FIG_INSET, v+h−FIG_INSET]}，{@code CAPTION_V} 这个常量连同类一起删掉了。
     */
    public static final float FIG_INSET = 0.03F;

    /** ② 俯视示意图（左列第 1 张）。 */
    public static final Zone ZONE_FIG_TOP = new Zone("②俯视示意图", FIG_COL_U, 0.40F, FIG_COL_W, FIG_H);
    /** ③ 侧视示意图（左列第 2 张）。 */
    public static final Zone ZONE_FIG_SIDE =
            new Zone("③侧视示意图", FIG_COL_U, 0.40F + FIG_H + FIG_GAP, FIG_COL_W, FIG_H);
    /** ④ 黑洞小图（左列第 3 张；6 条物理量已独立成 {@link #ZONE_PHYS}）。 */
    public static final Zone ZONE_FIG_HOLE =
            new Zone("④黑洞小图", FIG_COL_U, 0.40F + 2 * (FIG_H + FIG_GAP), FIG_COL_W, FIG_H);

    // ── 中间：① 读数带（2 栏 × 8 行） + ⑤ 物理量 ───────────────────────────────────
    /** ① 读数带：版式 C 折成 <b>2 栏 × 8 行</b>（左列被图列占了 3.20 + 0.20 间隙）。 */
    public static final Zone ZONE_READ = new Zone("①读数带", 3.80F, 0.40F, 6.80F, 1.70F);
    /** ⑤ 六条物理量（黑洞半径 / Rs / 光子球 / ISCO / 盘外缘 / 逃逸速度）。 */
    public static final Zone ZONE_PHYS = new Zone("⑤物理量", 3.80F, 2.20F, 6.80F, 1.40F);

    // ── 右侧：⑥ 主机节 + ⑦ 模块列表（用户：「右侧空出来这么大块地方」） ─────────────
    /** 右侧栏宽 10.20 格 = <b>面板宽的 46.4%</b>（用户要求 45~50%）。 */
    public static final float RIGHT_W = 10.20F;
    public static final float RIGHT_U = 11.20F;
    /** ⑥ 主机节（上半）：进度条 + 耗能 + 并行 + 线程 + 输出×3 ‖ 汇总 4 项 + 徽标绿字。 */
    public static final Zone ZONE_HOST = new Zone("⑥主机", RIGHT_U, 0.40F, RIGHT_W, 1.35F);
    /** ⑦ 模块列表（下半）：表头 + {@link #MODLIST_ROWS} 行。 */
    public static final Zone ZONE_MODLIST = new Zone("⑦模块列表", RIGHT_U, 1.90F, RIGHT_W, 1.70F);

    /** 七块（顺序 = 编号；{@link HostFxGlassArModel} 的 {@code Z_*} 常量与这里同序）。 */
    public static final Zone[] ZONES = {
            ZONE_READ, ZONE_FIG_TOP, ZONE_FIG_SIDE, ZONE_FIG_HOLE, ZONE_PHYS, ZONE_HOST, ZONE_MODLIST};

    // ── ① 读数带的栏/行栅格（相对本块左上角，格） ───────────────────────────────────
    /** 2 栏各自的 u 偏移（相对本块左边）。栏宽 = 3.36 格 = 168 px。 */
    public static final float[] READ_COL_U = {0.12F, 3.48F};
    /** 读数占位数（栏 × 行）：{@code col * READ_ROWS + row}。 */
    public static final int READ_COLS = 2;
    public static final int READ_ROWS = 8;
    /** 第 0 行的 v 偏移与行距（格）。 */
    public static final float READ_ROW_V0 = 0.14F;
    public static final float READ_ROW_DV = LINE_PX * PIXEL;          // 0.18

    // ── ⑤ 物理量：6 行单栏 ────────────────────────────────────────────────────────
    /** 物理量文字的 u 偏移（相对本块左边，格）。 */
    public static final float PHYS_TEXT_U = 0.10F;
    /** 行数与首行 v 偏移（格）。 */
    public static final int PHYS_ROWS = 6;
    public static final float PHYS_ROW_V0 = 0.14F;

    // ── ⑥ 主机节：两列 × 6 行 ──────────────────────────────────────────────────────
    /** 左列（主机运行）/ 右列（汇总 + 徽标绿字）的 u 偏移（相对本块左边，格）。 */
    public static final float HOST_COL_A_U = 0.10F;
    public static final float HOST_COL_B_U = 5.20F;
    /** 每列行数（两列同高，都在 1.35 格里放 6 行）。 */
    public static final int HOST_ROWS = 6;
    public static final float HOST_ROW_V0 = 0.14F;
    /**
     * 主机进度条（"Jade 那种条"）的几何：相对本块左边的 u / v、宽高（格）。
     *
     * <p>它占第 1 行（{@code r = 1}）左半：条本身画在文字右侧 2.90 格处、长 1.90 格、高 0.14 格
     * （= 7 px，够粗看得见），文字在 {@link #HOST_COL_A_U} 起。
     */
    public static final float HOST_BAR_U = 3.20F;
    public static final float HOST_BAR_W = 1.90F;
    public static final float HOST_BAR_H = 0.14F;
    /**
     * <b>主机条分几格</b> —— 由 {@link #barSegments} 按条宽 1.90 格 = 95 px 现算：
     * 判据 2 给 59、判据 3 给 48 ⇒ 夹到 {@link #BAR_SEG_MAX} ⇒ <b>40 格</b>（一格 2.5%、
     * 亮段 1.40 px、节距 2.38 px）。改 {@link #HOST_BAR_W} 会自动跟着变。
     */
    public static final int HOST_BAR_SEG = barSegments(HOST_BAR_W);

    // ── ⑦ 模块列表：表头 + 24 行（放不下就截断） ───────────────────────────────────
    /** 表头之后的模块行数上限（24 个模块里最多画几行；由块高算死，别溢出）。 */
    public static final int MODLIST_ROWS = 7;
    /** 表头行的 v 偏移 / 数据行的首行 v 偏移（相对本块上沿，格）。 */
    public static final float MODLIST_HEAD_V0 = 0.10F;
    public static final float MODLIST_ROW_V0 = 0.28F;
    /** 数据行的行距（格）= 行距 9 px，与全表一致。 */
    public static final float MODLIST_ROW_DV = READ_ROW_DV;
    /** 各列的 u 偏移（相对本块左边，格）—— 表头与数据行共用同一套，改这里两边一起动。 */
    public static final float MOD_SLOT_U = 0.10F;
    public static final float MOD_NAME_U = 0.46F;
    public static final float MOD_BAR_U = 2.26F;
    public static final float MOD_PCT_U = 3.24F;
    public static final float MOD_EU_U = 3.82F;
    public static final float MOD_PAR_U = 4.78F;
    public static final float MOD_THR_U = 5.54F;
    public static final float MOD_OUT_U = 6.20F;
    /** ⑦ 内容区的右内沿（本块局部格）= w − ZONE_PAD = 10.20 − 0.10 = <b>10.10</b>。 */
    public static final float MOD_ROW_RIGHT = 10.10F;
    /** 模块名最多几个<b>汉字当量</b>（1.70 格 = 85 px ÷ 9 px/汉字 ≈ 9，取 8 给 ASCII 名留余量）。 */
    public static final int MOD_NAME_CHARS = 8;
    /** 每行输出最多几项（用户口径：模块行最多 2 项）。 */
    public static final int MOD_ROW_OUTPUTS = 2;
    /** 模块行里每一项输出的最大"汉字当量"（输出列 3.90 格 = 195 px，两项各 8 字 ≈ 148 px，留余量）。 */
    public static final int MOD_OUT_CHARS = 7;
    /** 主机节的"配方输出"最多几条（用户口径：最多 3 条，超出显示 {@code +N}）。 */
    public static final int HOST_OUTPUTS = 3;
    /** 模块行进度条的几何（相对本块左边，格；高度与主机条同档）。 */
    public static final float MOD_BAR_W = 0.86F;
    public static final float MOD_BAR_H = 0.12F;
    /**
     * 🔴 <b>行末「原因标签」那一列的 u 偏移</b>（相对本块左边，格；2026-10-25 第三十三改）。
     *
     * <p>取值 8.30：输出列从 {@link #MOD_OUT_U} = 6.20 起，给它 2.10 格 = <b>105 px</b>
     * （有标签时输出只画 1 项，最多 72 px ⇒ 留 33 px 余量）；标签列从 8.30 到右内沿 10.10
     * ⇒ <b>1.80 格 = 90 px</b>，装得下最长的「输出满」3 个汉字（27 px），还剩 63 px 富余。
     *
     * <p>为什么不把标签并进输出那段文字：一个 {@code Txt} 只有一个颜色，而用户要求
     * <b>缺电=红 / 输出满=橙 / 被拦=黄</b>，输出本身是绿的 ⇒ 必须另起一个 {@code Txt}。
     */
    public static final int MOD_LABEL_CHARS = 2;
    /** 标签列右侧的安全余量（像素）—— 汉字宽 9 px 的取整误差 + 抗锯齿，给 2 px。 */
    public static final int MOD_LABEL_PAD_PX = 2;
    /**
     * 🔴 <b>标签列的起点（推导量，不是拍的位置）</b>：右内沿往回让出
     * {@code MOD_LABEL_CHARS × 9 px + MOD_LABEL_PAD_PX}。
     *
     * <p>这样"标签列宽"只有一个旋钮（{@link #MOD_LABEL_CHARS}），改它，输出列的宽度**自动**跟着变：
     * 输出列 = {@code [MOD_OUT_U, MOD_LABEL_U)}，<b>输出优先</b>。
     * <p>当前值 = 10.10 − (2×8+2)/50 = <b>9.74 格</b> ⇒ 输出列宽 = 9.74 − 6.20 = <b>3.54 格 = 177 px</b>。
     */
    public static final float MOD_LABEL_U = MOD_ROW_RIGHT
            - (MOD_LABEL_CHARS * INK_PX + MOD_LABEL_PAD_PX) * PIXEL;
    /** 输出列的可用宽（格）= {@code MOD_LABEL_U − MOD_OUT_U}（**输出优先**：标签只能往后让）。 */
    public static final float MOD_OUT_W = MOD_LABEL_U - MOD_OUT_U;
    /** 输出列的可用宽（像素），日志/自检/探针共用的那个数。 */
    public static final int MOD_OUT_W_PX = Math.round(px(MOD_OUT_W));
    /**
     * 输出整行的<b>最大"汉字当量"</b> = {@code MOD_OUT_W_PX / 9 − 1}。
     *
     * <p>为什么 −1：{@code HostFxGlassArModel#clip} 截断时会补一个 {@code …}（本身占 1 个当量），
     * 所以"长度上限 N"的真实最坏宽度是 {@code (N+1) × 9 px}。当前 = 177/9−1 = <b>18 当量 ≈ 171 px</b>。
     */
    public static final int MOD_OUT_LINE_CHARS = MOD_OUT_W_PX / 9 - 1;
    /**
     * 标签 {@code Txt} 的槽位号基数（{@code >= 300}）。
     *
     * <p>🔴 为什么避开 200..299：{@code HostFxGlassArText#measure} 把 {@code slot >= 200} 的 ⑦ 文字
     * 全部算进"数据行最宽"，而那条判据用的是<b>输出列的 x</b>（{@code MOD_OUT_U + 宽度}）；
     * 标签的 x 是 {@link #MOD_LABEL_U}，两套 x 混进同一条判据会得出错误的越界结论
     * ⇒ 标签单独一个号段，宽度由 {@link #MOD_LABEL_CHARS} 那条判据管。
     */
    public static final int MOD_LABEL_SLOT0 = 300;
    /**
     * 模块行进度条的分段数（一段 1 px 宽带 1 px 缝；视觉上"一条一条在长"）。
     *
     * <p>🔴 <b>这里是 22 而不是 40</b>，理由是可复核的、不是拍脑袋：条只有
     * {@link #MOD_BAR_W} = 0.86 格 = <b>43 px</b>，而 {@code HostFxGlassArModel#bar} 每两格之间
     * 固定留 1 px 缝 ⇒ 40 格时亮段只剩 {@code (43 − 39) / 40 = 0.10 px}，
     * 连 1 px 都画不满（{@link #MIN_LINE_PX} 那一节写的就是"再细会随亚像素抖动"）⇒ 会糊成一条
     * 灰带，反而看不出进度。判据 2（{@code 43 / 1.6 = 26}）也不够：26 格时亮段
     * {@code (43 − 25) / 26 = 0.69 px}，同样低于 1 px ⇒ 取判据 3 的 {@code (43 + 1) / 2 = 22}
     * ⇒ <b>22 格</b>（一格 4.55%、亮段正好 1.00 px、节距 1.95 px）。
     * <b>比原来的 10 格仍然细了一倍多</b>：一格从 10% 降到 4.55%。
     */
    public static final int MOD_BAR_SEG = barSegments(MOD_BAR_W);

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  映射
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 格 → MC 像素（墙面帧里 1 格 = 50 px）。 */
    public static float px(float blocks) {
        return blocks * PX_PER_BLOCK;
    }

    /** 墙面帧 v → 本地 y。 */
    public static float localY(float v) {
        return WALL_Y1 - v;
    }

    /** 墙面帧 u → 本地 z。 */
    public static float localZ(float u) {
        return WALL_Z1 - u;
    }

    /** 文字平面的本地 x。 */
    public static float textPlaneX() {
        return FACE_LOCAL_X - TEXT_OFFSET;
    }

    /** 几何平面的本地 x（比文字再让 {@link #GEOM_OFFSET}）。 */
    public static float geomPlaneX() {
        return textPlaneX() - GEOM_OFFSET;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  自检（判据：任一块越界就点名）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 实测文本宽度（由 {@link HostFxGlassArText} 用 {@code font.width} 填；0 = 未知 ⇒ 跳过宽度判据）。 */
    public static final class Widths {
        /** ① 每一栏里最宽那行（像素）。 */
        public final int[] readCol = new int[READ_COLS];
        /** ⑤ 6 条物理量里最宽那行（像素）。 */
        public int phys;
        /** ⑥ 主机节左列 / 右列各自最宽那行（像素）。 */
        public int hostA;
        public int hostB;
        /** ⑦ 模块列表数据行里最宽那行（像素）。 */
        public int modRows;

        /** 全 0 = 还没量过（自检只做几何部分）。 */
        public boolean measured() {
            if (phys > 0 || hostA > 0 || hostB > 0 || modRows > 0) {
                return true;
            }
            for (int v : readCol) {
                if (v > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final float EPS = 1.0E-4F;

    private static String f2(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /**
     * <b>启动自检</b>：{@code null} = 全部通过；否则一行，<b>点名第一块出问题的分区</b>。
     *
     * <p>判据（都能翻红）：
     * <ol>
     *   <li>每块都在四边 {@link #MARGIN} 之内（<b>用户硬要求 0.40 格边距</b>）；</li>
     *   <li>七块两两不重叠；</li>
     *   <li>① 的 8 行 / ⑤ 的 6 行 / ⑥ 的 6 行 / ⑦ 的表头 + 7 行都装得进本块高度；</li>
     *   <li><b>左侧图列宽 ≤ 面板宽 22%</b>（用户原话的硬上限）；</li>
     *   <li><b>右侧栏宽落在 45~50%</b>（用户原话）；</li>
     *   <li>②③④ 绘图框高度 ≥ 0.80 格（再小就什么都看不出来）；</li>
     *   <li>各栏文字宽度（{@code font.width} 实测）装得进分到的宽度。</li>
     * </ol>
     */
    public static String selfCheck(Widths widths) {
        // ① 四边边距
        for (Zone z : ZONES) {
            if (z.u < MARGIN - EPS) {
                return z.name + " 左边距 " + f2(z.u) + " 格 < " + f2(MARGIN);
            }
            if (z.v < MARGIN - EPS) {
                return z.name + " 上边距 " + f2(z.v) + " 格 < " + f2(MARGIN);
            }
            if (WALL_W - z.u1() < MARGIN - EPS) {
                return z.name + " 右边距 " + f2(WALL_W - z.u1()) + " 格 < " + f2(MARGIN);
            }
            if (WALL_H - z.v1() < MARGIN - EPS) {
                return z.name + " 下边距 " + f2(WALL_H - z.v1()) + " 格 < " + f2(MARGIN);
            }
        }
        // ② 两两不重叠
        for (int i = 0; i < ZONES.length; i++) {
            for (int j = i + 1; j < ZONES.length; j++) {
                final Zone a = ZONES[i];
                final Zone b = ZONES[j];
                final boolean apart = a.u1() <= b.u + EPS || b.u1() <= a.u + EPS
                        || a.v1() <= b.v + EPS || b.v1() <= a.v + EPS;
                if (!apart) {
                    return a.name + " 与 " + b.name + " 重叠";
                }
            }
        }
        // ③ 行数装得进各自高度（⑦ 的表头单独在 MODLIST_HEAD_V0，数据行各有自己的 v0 ⇒ 分开算）
        final Zone[] rowZones = {ZONE_READ, ZONE_PHYS, ZONE_HOST};
        final float[] v0s = {READ_ROW_V0, PHYS_ROW_V0, HOST_ROW_V0};
        final int[] rows = {READ_ROWS, PHYS_ROWS, HOST_ROWS};
        for (int i = 0; i < rowZones.length; i++) {
            final float need = v0s[i] + (rows[i] - 1) * READ_ROW_DV + INK_PX * PIXEL;
            if (need > rowZones[i].h - ZONE_PAD + EPS) {
                return rowZones[i].name + " 内容高 " + f2(need) + " 格 &gt; 给的高度 " + f2(rowZones[i].h);
            }
        }
        // ③b ⑦ 模块列表：表头 + {@link #MODLIST_ROWS} 行数据（表头与数据行不是同一个 v0）
        {
            final float headNeed = MODLIST_HEAD_V0 + INK_PX * PIXEL;
            final float dataNeed = MODLIST_ROW_V0 + (MODLIST_ROWS - 1) * MODLIST_ROW_DV + INK_PX * PIXEL;
            if (headNeed + 0.02F > MODLIST_ROW_V0 + EPS) {
                return ZONE_MODLIST.name + " 表头下沿 " + f2(headNeed) + " 格 压到数据行 " + f2(MODLIST_ROW_V0);
            }
            if (dataNeed > ZONE_MODLIST.h - ZONE_PAD + EPS) {
                return ZONE_MODLIST.name + " 内容高 " + f2(dataNeed) + " 格 &gt; 给的高度 "
                        + f2(ZONE_MODLIST.h);
            }
        }
        // ④ 左侧图列宽 ≤ 22%
        final float figPct = FIG_COL_W / WALL_W;
        if (figPct > 0.22F + EPS) {
            return "②③④ 图列宽 " + f2(FIG_COL_W) + " 格 = 面板宽 " + f2(figPct * 100) + "% &gt; 用户上限 22%";
        }
        // ⑤ 右侧栏宽 45~50%
        final float rightPct = RIGHT_W / WALL_W;
        if (rightPct < 0.45F - EPS || rightPct > 0.50F + EPS) {
            return "⑥⑦ 右栏宽 " + f2(RIGHT_W) + " 格 = 面板宽 " + f2(rightPct * 100) + "% 不在 45~50% 之间";
        }
        // ⑥ 图框高度
        final float figBoxH = FIG_H - 2 * FIG_INSET;
        if (figBoxH < 0.80F - EPS) {
            return "②③④ 绘图框只有 " + f2(figBoxH) + " 格高（≥ 0.80 才看得清）";
        }
        // ⑥b ⑦ 输出列 / 原因标签列（2026-10-25 第三十四改：**输出优先**）
        //     判据三条，任一条红都说明"输出会被挤掉/被标签盖住"：
        //       ① 标签自己装得进右内沿；
        //       ② 输出列宽 &gt; 0（否则输出无处可画）；
        //       ③ 输出列宽装得下【两项输出 + 一个空格】—— 这是"绝不把输出挤没"的量化判据。
        {
            final float labelNeed = MOD_LABEL_U + MOD_LABEL_CHARS * INK_PX * PIXEL;
            if (labelNeed > ZONE_MODLIST.w - ZONE_PAD + EPS) {
                return ZONE_MODLIST.name + " 原因标签列到 " + f2(labelNeed) + " 格 &gt; 分到的 "
                        + f2(ZONE_MODLIST.w - ZONE_PAD) + " 格（最长 " + MOD_LABEL_CHARS + " 字）";
            }
            if (MOD_OUT_W <= 0.0F) {
                return ZONE_MODLIST.name + " 输出列宽 " + f2(MOD_OUT_W) + " 格 ≤ 0（输出无处可画）";
            }
            final int needChars = 2 * MOD_OUT_CHARS + 1;      // 两项输出 + 中间一个空格（当量）
            if (MOD_OUT_LINE_CHARS < needChars) {
                return ZONE_MODLIST.name + " 输出预算 " + MOD_OUT_LINE_CHARS + " 当量 &lt; 两项所需的 "
                        + needChars + "（标签列吃掉太多宽度）";
            }
        }
        if (!widths.measured()) {
            return null;
        }
        // ⑦ ① 两栏宽度
        for (int c = 0; c < READ_COLS; c++) {
            final float needW = READ_COL_U[c] + widths.readCol[c] * PIXEL;
            final float limitW = (c + 1 < READ_COLS) ? READ_COL_U[c + 1] : ZONE_READ.w - ZONE_PAD;
            if (needW > limitW + EPS) {
                return ZONE_READ.name + " 第 " + (c + 1) + " 栏宽 " + f2(needW) + " 格 &gt; 分到的 "
                        + f2(limitW) + " 格（" + widths.readCol[c] + " px）";
            }
        }
        // ⑧ ⑤ 物理量
        if (widths.phys > 0 && PHYS_TEXT_U + widths.phys * PIXEL > ZONE_PHYS.w - ZONE_PAD + EPS) {
            return ZONE_PHYS.name + " 文字宽 " + f2(PHYS_TEXT_U + widths.phys * PIXEL)
                    + " 格 &gt; 分到的 " + f2(ZONE_PHYS.w - ZONE_PAD) + " 格（" + widths.phys + " px）";
        }
        // ⑨ ⑥ 两列（右列可以吃到本块右沿）
        if (widths.hostA > 0
                && HOST_COL_A_U + widths.hostA * PIXEL > HOST_COL_B_U - 0.04F + EPS) {
            return ZONE_HOST.name + " 左列宽 " + f2(HOST_COL_A_U + widths.hostA * PIXEL)
                    + " 格 压到右列 " + f2(HOST_COL_B_U) + "（" + widths.hostA + " px）";
        }
        if (widths.hostB > 0
                && HOST_COL_B_U + widths.hostB * PIXEL > ZONE_HOST.w - ZONE_PAD + EPS) {
            return ZONE_HOST.name + " 右列宽 " + f2(HOST_COL_B_U + widths.hostB * PIXEL)
                    + " 格 &gt; 分到的 " + f2(ZONE_HOST.w - ZONE_PAD) + " 格（" + widths.hostB + " px）";
        }
        // ⑩ ⑦ 模块行（输出列最长，最可能顶破）
        if (widths.modRows > 0
                && MOD_OUT_U + widths.modRows * PIXEL > ZONE_MODLIST.w - ZONE_PAD + EPS) {
            return ZONE_MODLIST.name + " 数据行宽 " + f2(MOD_OUT_U + widths.modRows * PIXEL)
                    + " 格 &gt; 分到的 " + f2(ZONE_MODLIST.w - ZONE_PAD) + " 格（" + widths.modRows + " px）";
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  读数（日志 / 报告 / 预览共用）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * ⑦ 三列的宽度读数（<b>日志判死用</b>）：
     * {@code 输出列=177px 标签列=18px 输出预算=18当量}。
     *
     * <p>为什么放进每 5 秒一行的那条 {@code 玻璃AR} 日志：用户下一次进游戏只看这一串就能判
     * "输出列还剩多少地方"——三者之一被改坏（例如标签列被调宽到吞掉输出），这里立刻能看出来。
     */
    public static String modRowColumns() {
        final int labelPx = Math.round(px(MOD_ROW_RIGHT - MOD_LABEL_U));
        return "输出列=" + MOD_OUT_W_PX + "px 标签列=" + labelPx + "px 输出预算="
                + MOD_OUT_LINE_CHARS + "当量";
    }
    /** 分区表（一格一行，日志与报告逐字相同）。 */
    public static String zoneTable() {
        final StringBuilder sb = new StringBuilder();
        for (Zone z : ZONES) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(z.name).append(" u=").append(f2(z.u)).append(" v=").append(f2(z.v))
                    .append(" ").append(f2(z.w)).append("×").append(f2(z.h));
        }
        return sb.toString();
    }

    /** 一行摘要（渲染器日志里带；含边距与字号）。 */
    public static String stats() {
        float minL = WALL_W;
        float minT = WALL_H;
        float minR = WALL_W;
        float minB = WALL_H;
        for (Zone z : ZONES) {
            minL = Math.min(minL, z.u);
            minT = Math.min(minT, z.v);
            minR = Math.min(minR, WALL_W - z.u1());
            minB = Math.min(minB, WALL_H - z.v1());
        }
        return "玻璃AR 墙面 " + f2(WALL_W) + "×" + f2(WALL_H) + " 格；面 x=" + f2(FACE_LOCAL_X)
                + "（文字面 x=" + f2(textPlaneX()) + "，几何面 x=" + f2(geomPlaneX()) + "）；"
                + "字号 1/" + Math.round(1 / PIXEL) + "（字高 " + f2(INK_PX * PIXEL) + " 格、行距 "
                + f2(LINE_PX * PIXEL) + " 格）；边距 左" + f2(minL) + "/上" + f2(minT)
                + "/右" + f2(minR) + "/下" + f2(minB) + " 格；图列 " + f2(FIG_COL_W) + " 格="
                + f2(FIG_COL_W / WALL_W * 100) + "%、右栏 " + f2(RIGHT_W) + " 格="
                + f2(RIGHT_W / WALL_W * 100) + "%；七块：" + zoneTable();
    }
}
