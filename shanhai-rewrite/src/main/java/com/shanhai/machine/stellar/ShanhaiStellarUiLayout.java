package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 星律样板总成界面的<b>版面几何唯一常量处</b>（阶段七：左侧竖排页签 + 四页）。
 *
 * <h2>一、为什么单独一个类</h2>
 * 「四个页签 + 两个开关按钮都排在左边缘那一列，且不压页区」这条要求是<b>纯算术</b>：
 * 能放进一个不引用任何 Minecraft 类的类里，就能被无头探针实跑（含负对照）。
 * 机器侧只引用这里的常量与函数，<b>不再写字面量</b>。
 *
 * <h2>二、坐标系（两套，别混）</h2>
 * <pre>
 *   根坐标（root）      ：整个面板的左上角 = (0,0)
 *   页签容器局部坐标    ：{@code TabContainer} 的原点（= 根坐标的 (PAGE_X, 0)）
 *                        LDLib 的页签按钮惯例是 x = {@link #TAB_X_REL}（= -28），即<b>伸出容器左缘</b>
 * （{@code TabContainer.initTemplate} 的字节码原文：{@code new TabButton(-28, 0, 32, 28)}）
 * </pre>
 * ⇒ 页签在根坐标里占的 x 区间 = {@code [PAGE_X + TAB_X_REL, PAGE_X + TAB_X_REL + TAB_SIZE)} = <b>[8, 36)</b>，
 * 页区从 {@code PAGE_X = 36} 开始 ⇒ <b>两者相邻但不重叠</b>（探针 A 组断言）。
 *
 * <h2>三、四个页</h2>
 * 顺序 = 页签从上到下的顺序（{@link Page} 的声明顺序即页号顺序）：
 * ① 普通样板槽  ② 通配符样板槽  ③ 库存输入  ④ 标签过滤输入。
 * ⚠️ 页号只用于<b>界面</b>；它与槽号空间（0..1329，常量在 {@link ShanhaiWildcardLayout}）
 * <b>没有任何关系</b>，不许互相换算。
 */
public final class ShanhaiStellarUiLayout {

    /** 四个页（声明顺序 = 页签顺序 = 页号顺序）。 */
    public enum Page {
        /** ① 普通样板槽（1080 格，9×6×20 分页）。 */
        PATTERNS,
        /** ② 通配符样板槽（50 格，10×5）＋ 母槽配置行。 */
        WILDCARD,
        /** ③ 库存输入（160 格，8×2×10）。 */
        STOCK,
        /** ④ 标签过滤输入（白/黑名单 ＋ 说明 ＋ 匹配预览）。 */
        TAG_FILTER
    }

    /** 页数 = {@link Page#values()} 的长度（探针断言 = 4）。 */
    public static final int PAGE_COUNT = 4;

    /** 页签按钮边长（正方形，与 LDLib 模板的 32×28 取 28×28 折中）。 */
    public static final int TAB_SIZE = 28;

    /** 页签竖直间距（28 高 + 2 缝隙）。 */
    public static final int TAB_PITCH = 30;

    /** 页签相对容器原点的 x（LDLib 惯例：负数 = 伸到容器左外侧）。 */
    public static final int TAB_X_REL = -28;

    /** 第一个页签的 y（根坐标）。 */
    public static final int TAB_FIRST_Y = 4;

    /**
     * 页签容器（{@code TabContainer}）在根坐标里的原点 x；页区也从这个 x 开始。
     *
     * <p>🔴 阶段十一（A 刀）从 36 提到 <b>64</b>：用户报「页签条压在内容上」（ref4）。
     * 36 时页签占 [8,36)，页区从 36 起 —— 数学上相邻，但页签按钮自带 4 px 右侧边框/阴影、
     * 且库存页第一格从 page 内 x=4 起（绝对 40），视觉上仍与页签贴边甚至压边。
     * 提到 64 后：页签 [8,36)，页区 [64, 64+196)，中间留 28 px 空白带 ⇒ 内容不再贴页签。
     */
    public static final int PAGE_X = 64;

    /** 页区宽（四页共用；最宽的一页 = 通配符页 10×18+16 = 196）。 */
    public static final int PAGE_W = 196;

    /** 页区高（四页共用；最高的一页 = 普通样板页 140 + 余量）。 */
    public static final int PAGE_H = 178;

    /** 开关按钮（闸/锻）宽（与页签同宽，排在同一列）。 */
    public static final int TOGGLE_W = 28;

    /** 开关按钮高。 */
    public static final int TOGGLE_H = 22;

    /** 第一个开关按钮的 y = 四个页签之后再空 8 px。 */
    public static final int TOGGLE_FIRST_Y = TAB_FIRST_Y + PAGE_COUNT * TAB_PITCH + 8;

    /** 开关按钮竖直间距。 */
    public static final int TOGGLE_PITCH = 26;

    /** 根宽 = 页区起点 + 页区宽。 */
    public static final int ROOT_WIDTH = PAGE_X + PAGE_W;

    /** 根高 = 页区高 + 上下留白。 */
    public static final int ROOT_HEIGHT = PAGE_H + 8;

    // ---------------------------------------------------------------- 神锻面板 / 开面板按钮（2026-10-24 三修 ①）

    /** 左下角「开/收神锻面板」按钮的 x（根坐标）。 */
    public static final int FORGE_BUTTON_X = 4;

    /**
     * 左下角按钮的 y（根坐标）= {@code ROOT_HEIGHT - 26} = <b>160</b>。
     *
     * <p>⚠️ 这个数字被 {@code verify-phase7.ps1} 的 B5 断言盯着（字节码里折成 {@code sipush 186}），
     * 改它要同时改那条断言 —— 这正是我们想要的耦合：版面改了，审计必须跟着改。
     */
    public static final int FORGE_BUTTON_Y = ROOT_HEIGHT - 26;

    /** 左下角按钮宽。 */
    public static final int FORGE_BUTTON_W = 24;

    /** 左下角按钮高（下沿 = 160+22 = 182，离根底 186 还有 4 px）。 */
    public static final int FORGE_BUTTON_H = 22;

    /**
     * 面板底边与按钮上沿之间<b>必须</b>留出的竖直缝隙（px）。
     *
     * <h2>🔴 为什么必须 ≥ 2，而不是「不重叠就行」</h2>
     * 2026-10-24 的真实 bug（用户报「一点就关」）：面板底边<b>正好压</b>在按钮上沿
     * （panel.y + panel.h == button.y == 160），矩形算术上「相邻不算重叠」，
     * 但按钮自带的边框/阴影会画进那 1–2 px，且 {@code createUIWidget} 里面板先 add、按钮后 add
     * ⇒ 按钮在上层 ⇒ <b>点面板最下面那条边 = 点按钮 = 面板自己关掉</b>。
     * ⇒ 判据不能是 {@link #overlaps} 的「不重叠」，而是「缝隙 ≥ 本常量」。
     */
    public static final int FORGE_PANEL_MIN_GAP = 2;

    /** 实际摆放时用的缝隙（比 {@link #FORGE_PANEL_MIN_GAP} 再宽 2 px，留出贴图边框）。 */
    public static final int FORGE_PANEL_GAP = FORGE_PANEL_MIN_GAP + 2;

    private ShanhaiStellarUiLayout() {}

    // ---------------------------------------------------------------- 神锻面板几何（唯一来源 + 自检）

    /** 左下角「开/收神锻面板」按钮的矩形（根坐标）。 */
    public static int @NotNull [] forgeButtonRect() {
        return rect(FORGE_BUTTON_X, FORGE_BUTTON_Y, FORGE_BUTTON_W, FORGE_BUTTON_H);
    }

    /**
     * 神锻面板的矩形（根坐标）：<b>底边 = 按钮上沿 - {@link #FORGE_PANEL_GAP}</b>。
     *
     * <p>面板往<b>上</b>长（y 变小），所以面板再高也不会压到按钮 —— 这是与「面板底边贴按钮」
     * 那版几何的根本区别（见 {@link #FORGE_PANEL_MIN_GAP}）。
     */
    public static int @NotNull [] forgePanelRect(int panelW, int panelH) {
        return rect(FORGE_BUTTON_X, FORGE_BUTTON_Y - FORGE_PANEL_GAP - panelH, panelW, panelH);
    }

    /**
     * <b>负对照</b>用的旧几何：面板底边<b>正好</b>贴按钮上沿（2026-10-24 那个 bug 的矩形）。
     *
     * <p>只给自检用，生产代码不许调用它 —— 它存在的唯一目的是让
     * {@link #forgeGeometrySelfCheck} 能证明「这个检查器真的抓得住那个 bug」。
     */
    public static int @NotNull [] forgePanelRectPressingButton(int panelW, int panelH) {
        return rect(FORGE_BUTTON_X, FORGE_BUTTON_Y - panelH, panelW, panelH);
    }

    /**
     * 校验一个<b>实际要用的</b>面板矩形与开面板按钮的关系：不重叠、缝隙 ≥ {@link #FORGE_PANEL_MIN_GAP}、
     * 且整块面板落在根区内。
     *
     * <p>🔴 为什么不是「算一遍再断言一遍」（那样是恒真）：这里的入参是<b>调用方真正拿去建壳的矩形</b>，
     * 计算在 {@link #forgePanelRect}。谁把摆放公式改回去（或手写一个旧坐标），这里就会当场抛。
     *
     * @throws IllegalStateException 面板压住按钮 / 贴得太近 / 越出根区
     */
    public static void checkForgePanelClearOfButton(int @NotNull [] panelRect) {
        final int[] button = forgeButtonRect();
        if (overlaps(panelRect, button)) {
            throw new IllegalStateException("神锻面板压住了开面板按钮（点面板底边 = 点按钮 = 面板自己关掉）：panel="
                    + describeRect(panelRect) + " button=" + describeRect(button));
        }
        final int gap = button[1] - (panelRect[1] + panelRect[3]);
        if (gap < FORGE_PANEL_MIN_GAP) {
            throw new IllegalStateException("神锻面板底边离按钮只有 " + gap + " px（要求 ≥ "
                    + FORGE_PANEL_MIN_GAP + "）：按钮边框/阴影会压到面板底边 ⇒ 点面板 = 点按钮。panel="
                    + describeRect(panelRect) + " button=" + describeRect(button));
        }
        if (panelRect[0] < 0 || panelRect[1] < 0
                || panelRect[0] + panelRect[2] > ROOT_WIDTH
                || panelRect[1] + panelRect[3] > ROOT_HEIGHT) {
            throw new IllegalStateException("神锻面板越出根区：panel=" + describeRect(panelRect)
                    + " root=" + ROOT_WIDTH + "x" + ROOT_HEIGHT);
        }
    }

    /** 矩形的一行式描述（日志 / 断言用）。 */
    public static @NotNull String describeRect(int @NotNull [] r) {
        return "(" + r[0] + "," + r[1] + " " + r[2] + "x" + r[3] + ")";
    }

    /**
     * <b>神锻面板几何自检</b>（纯算术，不引用任何 Minecraft 类 ⇒ 可以被 headless 探针直接跑）。
     *
     * <p>三段，缺一段这条自检就是自说自话：
     * <ol>
     *   <li><b>正对照</b>：{@link #forgePanelRect} 算出来的矩形必须过 {@link #checkForgePanelClearOfButton}；</li>
     *   <li><b>负对照</b>：{@link #forgePanelRectPressingButton}（旧几何：底边贴按钮）<b>必须</b>被同一个检查抛出来
     *       —— 不抛就说明检查器抓不住那个 bug，「检查通过」也就没有意义；</li>
     *   <li><b>越界对照</b>：把面板挪到 y = -1 <b>必须</b>被同一个检查抛出来。</li>
     * </ol>
     *
     * @param panelW 面板实际宽（调用方传 {@code AutoForgeMultiplierPanel.PANEL_W}）
     * @param panelH 面板实际高（调用方传「上游面板高 + 闸那一行」）
     * @return 一行读数（日志 / 审计脚本用来断言「确实跑了」）
     */
    public static @NotNull String forgeGeometrySelfCheck(int panelW, int panelH) {
        final int[] panel = forgePanelRect(panelW, panelH);
        checkForgePanelClearOfButton(panel);                    // ① 正对照：当前实现必须过
        final int[] pressing = forgePanelRectPressingButton(panelW, panelH);
        boolean caughtPressing;
        try {
            checkForgePanelClearOfButton(pressing);
            caughtPressing = false;
        } catch (IllegalStateException expected) {
            caughtPressing = true;
        }
        if (!caughtPressing) {
            throw new IllegalStateException("负对照失效：「面板底边贴按钮上沿」的旧几何没被检查抓住 ⇒ 检查器本身不对");
        }
        final int[] outOfRoot = rect(FORGE_BUTTON_X, -1, panelW, panelH);
        boolean caughtOutOfRoot;
        try {
            checkForgePanelClearOfButton(outOfRoot);
            caughtOutOfRoot = false;
        } catch (IllegalStateException expected) {
            caughtOutOfRoot = true;
        }
        if (!caughtOutOfRoot) {
            throw new IllegalStateException("越界对照失效：y=-1 的面板没被检查抓住 ⇒ 检查器本身不对");
        }
        final int gap = FORGE_BUTTON_Y - (panel[1] + panel[3]);
        return "FORGE-GEOM SELFCHECK = PASS panel=" + describeRect(panel)
                + " button=" + describeRect(forgeButtonRect())
                + " gap=" + gap + " (min " + FORGE_PANEL_MIN_GAP + ")";
    }

    /**
     * 第 {@code index} 个页签在<b>根坐标</b>里的 y。
     *
     * @throws IllegalArgumentException 越界（页号算错属于代码 bug，必须响亮失败）
     */
    public static int tabY(int index) {
        checkPageIndex(index);
        return TAB_FIRST_Y + index * TAB_PITCH;
    }

    /** 第 {@code index} 个页签按钮在<b>容器局部坐标</b>里的 x（恒为 {@link #TAB_X_REL}）。 */
    public static int tabXLocal() {
        return TAB_X_REL;
    }

    /** 第 {@code index} 个页签在<b>根坐标</b>里的 x。 */
    public static int tabXRoot() {
        return PAGE_X + TAB_X_REL;
    }

    /**
     * 第 {@code index} 个开关按钮（0 = 闸，1 = 锻）在<b>容器局部坐标</b>里的 y。
     *
     * @throws IllegalArgumentException 越界
     */
    public static int toggleYLocal(int index) {
        if (index < 0 || index >= 2) {
            throw new IllegalArgumentException("开关按钮下标越界：" + index + "（合法 0..1）");
        }
        return TOGGLE_FIRST_Y + index * TOGGLE_PITCH;
    }

    /** 开关按钮在容器局部坐标里的 x（与页签同一列）。 */
    public static int toggleXLocal() {
        return TAB_X_REL;
    }

    /**
     * 页号 → {@link Page}。
     *
     * @throws IllegalArgumentException 越界（<b>这就是负对照要打的点</b>：页号越界必须报红，不许夹住）
     */
    public static @NotNull Page pageOf(int index) {
        checkPageIndex(index);
        return Page.values()[index];
    }

    /** 页号越界检查（唯一判定点）。 */
    public static void checkPageIndex(int index) {
        if (index < 0 || index >= PAGE_COUNT) {
            throw new IllegalArgumentException("界面页号越界：" + index + "（合法 0.." + (PAGE_COUNT - 1) + "）");
        }
    }

    // ---------------------------------------------------------------- 矩形算术（给探针用）

    /** 矩形 = {@code {x, y, w, h}}（根坐标）。 */
    public static int @NotNull [] rect(int x, int y, int w, int h) {
        return new int[] { x, y, w, h };
    }

    /** 第 {@code index} 个页签的矩形（根坐标）。 */
    public static int @NotNull [] tabRect(int index) {
        return rect(tabXRoot(), tabY(index), TAB_SIZE, TAB_SIZE);
    }

    /** 第 {@code index} 个开关按钮的矩形（根坐标：容器原点 PAGE_X + 局部 x）。 */
    public static int @NotNull [] toggleRect(int index) {
        return rect(PAGE_X + toggleXLocal(), toggleYLocal(index), TOGGLE_W, TOGGLE_H);
    }

    /** 页区矩形（根坐标）。 */
    public static int @NotNull [] pageRect() {
        return rect(PAGE_X, 0, PAGE_W, PAGE_H);
    }

    /**
     * 两个矩形是否重叠。
     *
     * <p>⚠️ <b>相邻不算重叠</b>（左闭右开）：页签 [8,36) 与页区 [36,232) 必须判「不重叠」，
     * 否则「页签贴边」这个正确版面会被误判。探针里有一条<b>正对照</b>先证明本方法对
     * 一对明知重叠的矩形返回 true，再用它判页签/页区。
     */
    public static boolean overlaps(int @NotNull [] a, int @NotNull [] b) {
        final boolean xOverlap = a[0] < b[0] + b[2] && b[0] < a[0] + a[2];
        final boolean yOverlap = a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
        return xOverlap && yOverlap;
    }

    // ---------------------------------------------------------------- 阶段十二 B 刀：通配符页 5×2 分页

    /** 通配符页：每页列数（用户口径：上方一格一页 10 格 = 5 列 × 2 行）。 */
    public static final int WILDCARD_PAGE_COLUMNS = 5;

    /** 通配符页：每页行数。 */
    public static final int WILDCARD_PAGE_ROWS = 2;

    /** 通配符页：每页格数 = 10。 */
    public static final int WILDCARD_SLOTS_PER_PAGE = WILDCARD_PAGE_COLUMNS * WILDCARD_PAGE_ROWS;

    /** 通配符母槽总数（与机器侧常量同值；这里复制一份是为了让本类保持「零 Minecraft 依赖」）。 */
    public static final int WILDCARD_MOTHER_SLOTS = 50;

    /** 通配符页的页数 = ceil(50 / 10) = 5。 */
    public static final int WILDCARD_PAGES = (WILDCARD_MOTHER_SLOTS + WILDCARD_SLOTS_PER_PAGE - 1) / WILDCARD_SLOTS_PER_PAGE;

    /** 两处 3×2 信息组：每行 3 个、共 2 行 = 6 格（用户口径）。 */
    public static final int INFO_COLUMNS = 3;

    /** 信息组行数。 */
    public static final int INFO_ROWS = 2;

    /** 信息组格数 = 6。 */
    public static final int INFO_CELLS = INFO_COLUMNS * INFO_ROWS;

    // ------------------------------------------------ 2026-10-24 通配页重排（逐控件照图2）

    /*
     * 版面来源 = 「超级通配符ME样板总成」的主页面
     * （{@code com.shanhai.machine.wildcard.SuperWildcardPatternBufferPartMachine#createUIWidget()}，
     *  那一版是 158×174 的组、控件 y 依次为 4 / 25(格区) / 68 / 80 / 122 / 134）。
     * 本页把那张表逐行搬过来，只把「宽 158」换成本页的 {@link #PAGE_W} = 196，
     * 于是「水平居中」那条式子照样成立：(196-90)/2 = 53。
     *
     * 🔴 三条硬约束（都是用户 2026-10-24 截图里的病，审计 P 组逐条断言）：
     *   ① 同一 y 上不许出现两段文字 —— 本组常量里 4/44/68/122 两两相差 ≥ 10；
     *   ② 槽区容器的尺寸必须 = 网格真实尺寸（旧版写 (10, 0)，格子被夹死成「灰底方块」）；
     *   ③ 翻页用箭头贴图（GuiTextures.BUTTON_LEFT / BUTTON_RIGHT），<b>不许出现字符 &lt; &gt;</b>。
     */

    /** 通配符页：顶部那一行字（在线/离线）的 y —— 与参照机 (8, 4) 同值。 */
    public static final int WILDCARD_TITLE_Y = 4;

    /** 通配符页：每格的边长（= 参照机的 18 px 格距，逐值相同）。 */
    public static final int WILDCARD_SLOT_CELL = 18;

    /** 通配符页：格区宽 = 5 列 × 18 = 90（= 参照机的 90）。 */
    public static final int WILDCARD_GRID_W = WILDCARD_PAGE_COLUMNS * WILDCARD_SLOT_CELL;

    /** 通配符页：格区高 = 2 行 × 18 = 36（= 参照机的 36）。 */
    public static final int WILDCARD_GRID_H = WILDCARD_PAGE_ROWS * WILDCARD_SLOT_CELL;

    /** 通配符页：格区左上角 x —— 参照机那条「水平居中」式子，只把 158 换成 196。 */
    public static final int WILDCARD_GRID_X = (PAGE_W - WILDCARD_GRID_W) / 2;

    /** 通配符页：格区左上角 y —— 与参照机的 25 逐值相同。 */
    public static final int WILDCARD_GRID_Y = 25;

    /** 通配符页：翻页簇的 x（页宽 196 ⇒ 右侧留 44 px 给两个箭头 + 页码）。 */
    public static final int WILDCARD_NAV_X = PAGE_W - 44;

    /** 通配符页：翻页按钮边长（正方形，与参照机右上的改名按钮同高）。 */
    public static final int WILDCARD_NAV_SIZE = 16;

    /** 通配符页：「上一页」箭头的 y（与格区首行同高，落在格区右侧空白带里）。 */
    public static final int WILDCARD_NAV_PREV_Y = WILDCARD_GRID_Y;

    /** 通配符页：页码文字的 y（夹在两个箭头之间，单独一个 y）。 */
    public static final int WILDCARD_NAV_LABEL_Y = WILDCARD_NAV_PREV_Y + WILDCARD_NAV_SIZE + 3;

    /** 通配符页：「下一页」箭头的 y。 */
    public static final int WILDCARD_NAV_NEXT_Y = WILDCARD_NAV_LABEL_Y + 14;

    /** 通配符页：状态行（样板数 ｜ 已缓存）的 y —— 格区以下第一行，与参照机的 68 同值。 */
    public static final int WILDCARD_STATUS_Y = 68;

    /** 通配符页：展开预览（GT 原生 PatternCycleWidget）的 y —— 与参照机的 80 同值。 */
    public static final int WILDCARD_CYCLE_Y = 80;

    /** 通配符页：展开预览的高（= 参照机的 36）。 */
    public static final int WILDCARD_CYCLE_H = 36;

    /** 通配符页：展开预览的宽（= 参照机的 142）。 */
    public static final int WILDCARD_CYCLE_W = 142;

    /** 通配符页：母槽配置行的 y —— 与参照机的 122 同值。 */
    public static final int WILDCARD_CONFIG_Y = 122;

    /** 通配符页：配方种类选择器的 y —— 与参照机的 134 同值。 */
    public static final int WILDCARD_PICKER_Y = 134;

    /** 通配符页：选择器的高（用到页底 174，页高 178 ⇒ 留 4 px，与参照机同样的余量）。 */
    public static final int WILDCARD_PICKER_H = 40;

    /**
     * 通配符页：第 {@code page}（1 基）第 {@code cell}（0 基）格 ⇒ 母槽号（0..49）。
     *
     * @return 该格对应的母槽号；超出 50 的格返回 {@code -1}（末页不满时留空，不越界）
     */
    public static int wildcardSlotAt(int page, int cell) {
        if (page < 1 || page > WILDCARD_PAGES) {
            throw new IllegalArgumentException("通配符页号越界：" + page + "（合法 1.." + WILDCARD_PAGES + "）");
        }
        if (cell < 0 || cell >= WILDCARD_SLOTS_PER_PAGE) {
            throw new IllegalArgumentException("通配符页内格号越界：" + cell + "（合法 0.." + (WILDCARD_SLOTS_PER_PAGE - 1) + "）");
        }
        final int slot = (page - 1) * WILDCARD_SLOTS_PER_PAGE + cell;
        return slot < WILDCARD_MOTHER_SLOTS ? slot : -1;
    }

    /** 母槽号 → 它在第几页（1 基）。 */
    public static int wildcardPageOf(int motherSlot) {
        if (motherSlot < 0 || motherSlot >= WILDCARD_MOTHER_SLOTS) {
            throw new IllegalArgumentException("母槽号越界：" + motherSlot + "（合法 0.." + (WILDCARD_MOTHER_SLOTS - 1) + "）");
        }
        return motherSlot / WILDCARD_SLOTS_PER_PAGE + 1;
    }
    // ---------------------------------------------------------------- 阶段八 ③：行数/页数可配（列数固定 9）

    /** 普通样板区的列数：<b>固定 9</b>（上游分页器几何写死，不开放配置）。 */
    public static final int PATTERN_COLUMNS_FIXED = 9;

    /** 普通样板区每页行数的默认值（= 加配置项之前的行为）。 */
    public static final int DEFAULT_PATTERN_ROWS_PER_PAGE = 6;

    /** 普通样板区页数的默认值（= 加配置项之前的行为）。 */
    public static final int DEFAULT_PATTERN_PAGES = 20;

    /**
     * 行数上限：{@code PAGE_H = 178}，分页器占 {@code 16 + 18*rows + 4 + 12} ⇒ rows ≤ 8 才不溢出页区。
     * 超过就必须加高面板（那是界面重排，不在本轮），所以这里夹住。
     */
    public static final int PATTERN_ROWS_MAX = 8;

    /** 页数上限：可见槽数反正被 1080 夹住，这里只防「用户填个天文数字」时算溢出。 */
    public static final int PATTERN_PAGES_MAX = 120;

    /** 普通样板「可见槽数」上界 = 底层 storage 的固定容量（构造参数 1080）。 */
    public static final int PATTERN_SLOTS_HARD_CAP = 1080;

    /** 行数夹取（1..{@link #PATTERN_ROWS_MAX}）。 */
    public static int clampPatternRows(int rows) {
        return Math.max(1, Math.min(PATTERN_ROWS_MAX, rows));
    }

    /** 页数夹取（1..{@link #PATTERN_PAGES_MAX}）。 */
    public static int clampPatternPages(int pages) {
        return Math.max(1, Math.min(PATTERN_PAGES_MAX, pages));
    }

    /**
     * 配置的「行数 × 页数」→ 界面可见的普通样板槽数（= 分页器的 maxPatternCount）。
     *
     * <p>🔴 三条性质（探针实跑）：
     * <ol>
     *   <li>默认值 6 × 20 ⇒ <b>1080</b>（与加配置项之前逐字节一致）；</li>
     *   <li>结果永远 ≤ {@link #PATTERN_SLOTS_HARD_CAP}（1080）—— 底层 storage 就是这么大；</li>
     *   <li>结果 ≥ 9（至少一页一行），且是 9 的整数倍（每行必须整 9 格）。</li>
     * </ol>
     * ⚠️ 调小只会让「界面翻不到后面的格子」，<b>不会</b>删数据（storage 容量与槽号空间都没变）。
     */
    public static int patternVisibleSlots(int rows, int pages) {
        final int slots = PATTERN_COLUMNS_FIXED * clampPatternRows(rows) * clampPatternPages(pages);
        return Math.max(PATTERN_COLUMNS_FIXED, Math.min(PATTERN_SLOTS_HARD_CAP, slots));
    }
    /** 一行式描述（日志/断言用）。 */
    public static @NotNull String describe() {
        return "ui=" + PAGE_COUNT + "pages tab@" + (PAGE_X + TAB_X_REL) + ".." + PAGE_X
                + " page=" + PAGE_X + ".." + (PAGE_X + PAGE_W) + "x0.." + PAGE_H
                + " toggles=" + TOGGLE_FIRST_Y + "," + (TOGGLE_FIRST_Y + TOGGLE_PITCH);
    }

    /** 供日志/工具用的空值防御（不参与判断）。 */
    public static @NotNull String pageName(@Nullable Page page) {
        return page == null ? "?" : page.name();
    }
}
