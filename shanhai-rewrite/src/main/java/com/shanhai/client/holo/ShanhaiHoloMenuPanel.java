package com.shanhai.client.holo;

import com.shanhai.common.holo.ShanhaiHoloMenuBoards;

/**
 * 山海重构 · 全息菜单的<b>两个二级面板</b>（设置 / 命令）—— <b>纯算术 + 纯文本模型</b>。
 *
 * <h2>1. 用户原话（逐字，两条点单）</h2>
 * <blockquote>
 *   可以在全息屏幕里面把我们的设置加进去了，然后点开配置设置的也是全息的面版
 * </blockquote>
 * <blockquote>
 *   还可以新增一个 cmd 的功能，全息输入框，可以执行cmd的命令，拥有管理员权限
 * </blockquote>
 *
 * <h2>2. 🔴 为什么是"一列行板"，而不是"开一个 GUI Screen"</h2>
 * 用户明确要的是「<b>全息的面版</b>」—— 即与他已经验收过的那五块板<b>同一套观感</b>
 * （同一块底板、同一圈描边流光、同一套文字、同样被方块挡住、同样在世界里）。
 * 开一个普通 GUI Screen 会立刻变成"贴在屏幕上的另一套界面"，那是他明确否掉的东西
 * （他对全息的定义就是"不是 HUD"，见 {@link ShanhaiHoloMenuRenderer} 类注释 §1）。
 * <pre>
 *   ⇒ 面板 = {@link ShanhaiHoloMenuLayout#panelRows()} 给出的【5 块行板】，
 *     每块板 560×62 bu，竖着叠成一列、不自转 ⇒ 渲染与命中判定【全部复用既有代码】。
 * </pre>
 *
 * <h2>3. 一行 = 4 个格子（这是"面板能不能点"的全部口径）</h2>
 * <pre>
 *   板内坐标 x ∈ [−280, +280] bu（板宽 560），横切成 4 个等宽格子（每格 140 bu）：
 *     格 0 = 这一行的【标签】（左对齐）      格 1..3 = 三个选项/按钮
 *   命中环：射线打中某一行 ⇒ 得到 {@code Hit#localX}（板内 bu）⇒ {@link #cellOf} 换算成格子号。
 * </pre>
 * ⚠️ 标签占掉第 0 格是刻意的：这样"距离 − 值 +"这种三件套正好落在格 1/2/3 上，
 * 不用为每一行单独算坐标，也就不会出现"某一行的按钮与文字错位"。
 *
 * <h2>4. 三个面板的内容（两层，两层都用满同一块 5 行网格）</h2>
 * <pre>
 *   设置面板（第一层，5 行全画）              命令面板（只画 2 行）
 *   行 0 ┌ 距离     [−0.2 格] [2.8 格] [+0.2 格]   行 0   （不画）
 *   行 1 │ 布局     [环绕] [竖列] [横排]          行 1 ┌ 输入命令，回车执行 / 你打的字（整行）
 *   行 2 │ 环绕特效 [关] [弱] [中] [强]            行 2 └ 返回（整行可点）
 *   行 3 │ 高级调参 （整行可点 ⇒ 进第二层）         行 3   （不画）
 *   行 4 └ 返回     （整行可点）                   行 4   （不画）
 *
 *   高级调参页（第二层，MODE_ADVANCED，每页 4 项 + 第 5 行导航，共 4 页 = 4/4/4/2）
 *   行 0 ┌ [环数]        [− 1 条]  [2 条]     [+ 1 条]
 *   行 1 │ [环半径倍率]  [− 0.05 倍] [1.40 倍] [+ 0.05 倍]
 *   行 2 │ [环亮度]      …（第 4 页只有 2 项：行 2 / 行 3 不画）
 *   行 3 │ [刻度密度]    …
 *   行 4 └ [上一页] [环·第1/4页] [下一页] [返回]      ← 返回 = 回第一层，不是关面板
 * </pre>
 *
 * <p>🔴 <b>2026-10-06（用户第三轮点单）：「朝向」那一行【整行删掉】</b>。用户原话逐字：
 * <blockquote>
 *   你干脆把朝向的修改选项删了得了，反正另外两个正常人肯定不会用
 * </blockquote>
 * 起因是一条<b>死循环</b>：面板上的「立即转向」会让面板贴回玩家脸正前方 ⇒ 准星永远落在面板
 * 横向正中 ⇒ 最右格那颗「世界固定」<b>指不到</b> ⇒ 点不到就改不回来。
 * <pre>
 *   ① 「朝向」那一行【不存在】（不是"画了但不可点"）—— 判据见 {@link #selfTest()} 的 N8/N9；
 *   ② 面板的朝向【固定为「世界固定」】= 打开面板那一刻钉在世界方位上（见 ShanhaiHoloMenuState）
 *      ⇒ 死循环从根上消失（两条互相独立的保险：改不了、也不受影响）；
 *   ③ 🔴 朝向档本身<b>没有丢</b>：{@code /shanhai menu facing follow|snap|fixed} 一个字没动
 *      （用户真要用可以直接敲；那三条命令影响的是五块菜单板）。
 * </pre>
 * <p>设置那两行的选项与两条既有命令<b>逐条对应</b>（{@code /shanhai menu distance} /
 * {@code layout ring|column|row}），所以"面板改了之后命令读到的值"必然一致
 * —— 两边都是 {@link ShanhaiHoloMenuState} 的同一份状态。
 *
 * <p>🔴 <b>2026-10-06（第四轮点单）环绕特效做成游戏内可调</b>，形式 = <b>四档预设（关/弱/中/强）</b>
 * ＋ <b>高级页翻 15 项</b>。落地：
 * <pre>
 *   ① 「环绕特效」那一行四格 = 四档，点一格整组切换（当前档带 ▸，与「布局」行同构）；
 *   ② 「高级调参」那一行整行可点 ⇒ 第二层：每页 4 项 + 第 5 行导航，4 页（4/4/4/3）＝ 那 15 项（2026-10-06 16:2x 追加第 15 项「跟随延迟」）；
 *   ③ 🔴 两层都用满同一块 5 行网格 ⇒ 几何、行位、准星可达性与已验收版逐位相同
 *      （没有为容纳新行动过任何一个数）—— 理由见 {@link #MODE_ADVANCED} 的注释；
 *   ④ 档位与 15 项取值只存内存（{@link ShanhaiHoloSurroundTuning}），不新增落盘文件。
 * </pre>
 *
 * <p>🔴 <b>2026-10-06（用户第 ② 条点单）命令面板只剩两个框</b>，用户原话逐字：
 * <blockquote>
 *   不对啊，我不要这么多框，我只要两个框，一个输入，一个返回，怎么还有5个框，多的都删了
 * </blockquote>
 * 落地（每条都有机器判据，见 {@link #selfTest()}）：
 * <pre>
 *   ① 「命令（管理员权限）」标题框【删掉】⇒ {@link #title(int)} 对命令面板返回 ""、第 0 行不画。
 *   ② 输入框【只占一行】（原来跨两块行板 —— 那看起来就是"两个框叠着"）。
 *      宽度照旧占满整行 560 bu（文字挂在第 0 格、从板左边缘起画，1..3 格全空）。
 *   ③ 「结果：…」框【删掉】：反馈走聊天栏 + 日志（不占框，见 {@link #setLastResult(String)}）。
 *   ④ 没画出来的三行【既没有字、也没有一格能点】—— 唯一口径是 {@link #rowVisible(int, int)}，
 *      渲染器三个绘制遍与输入层都读它 ⇒ "画出来的行"与"能点的行"不可能分成两套。
 * </pre>
 * ⚠️ <b>上一轮（第 6 条点单）保留的两条仍然成立</b>：「执行」「清空」两行不许再出现（N6）；
 * 占位符是【空 ⇒ 说明文字 / 有字 ⇒ 输入本身 / 删空 ⇒ 说明文字回来】（逐字比对）。
 *
 * <h2>5. 命令文本存在哪</h2>
 * 存在本类的静态字段里（客户端单线程：输入框 Screen 写、渲染器每帧读）。
 * <b>它不进任何数据包</b>——只有"按下执行"那一刻才把整条字符串发给服务端
 * （见 {@code ShanhaiHoloMenuNetwork#requestCommand}）。
 */
public final class ShanhaiHoloMenuPanel {

    private ShanhaiHoloMenuPanel() {}

    // ================================================================== 模式

    /** 没有面板（= 显示五块菜单板）。 */
    public static final int MODE_NONE = 0;
    /** 设置面板。 */
    public static final int MODE_SETTINGS = 1;
    /** 命令输入面板。 */
    public static final int MODE_COMMAND = 2;
    /**
     * 🆕 2026-10-06（第二轮点单）<b>高级调参页</b> —— 设置面板里点「高级调参」那一行进来的第二层。
     *
     * <pre>
     *   每页 4 个参数项 ＋ 第 5 行导航，共 4 页（4/4/4/3），一共 15 项（第 15 项「跟随延迟」是 2026-10-06 16:2x 追加的）。
     *   每行 4 格 = [名称][−][当前值][+]
     *   导航 4 格  = [上一页][类别·第 n/N 页][下一页][返回]（返回 = 回第一层，不是关面板）
     * </pre>
     * 🔴 <b>为什么是"每页 4 项"而不是原设计的 3 页 6/7/1</b>：面板的行数
     * {@link #ROWS} = {@link ShanhaiHoloMenuTuning#BOARD_COUNT} = 5，而这个 5 <b>同时</b>被
     * 五块菜单板用（{@code RING_STEP_DEG = 360/5}）⇒ <b>加行数就会挪动用户已验收的菜单板</b>，
     * 那是红线。所以 5 行网格一个数都不动 ⇒ 扣掉 1 行导航，每页最多 4 项。
     */
    public static final int MODE_ADVANCED = 3;

    /** 行数 = 板块数（一块板一行）。 */
    public static final int ROWS = ShanhaiHoloMenuTuning.BOARD_COUNT;

    /** 每行横切成几格。 */
    public static final int CELLS = 4;

    /** 一格的宽度（bu）= 560/4 = 140。 */
    public static final float CELL_W_BU = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU / CELLS;

    /** 距离那一行的步长（格）。与 {@code /shanhai menu distance} 的取值范围 [0.8, 8.0] 一致。 */
    public static final float DISTANCE_STEP = 0.2f;

    // ================================================================== 🆕 命令面板的行布局（第 6 条点单）

    /**
     * 🆕 <b>输入框</b>所在的行。
     * <p>它同时是"文字挂在哪一格"的答案：输入文字挂在<b>第 0 格</b>（= 板左边缘起画），
     * 1..3 格全部为空 ⇒ 输入区横跨<b>整行</b>（560 bu）。
     * <p>🔴 2026-10-06（用户第 ② 条点单：「我不要这么多框，我只要两个框，一个输入，一个返回，
     * 怎么还有5个框，多的都删了」）：输入框<b>只占这一行</b>（原来跨两块行板那半块已经删掉）。
     */
    public static final int COMMAND_INPUT_ROW = 1;

    /**
     * 🆕 <b>返回框</b>所在的行 —— 紧贴输入框下面那一块（用户点单的「上下两块板」）。
     * <p>它整行可点：点它就是"收起面板、回到五块菜单板"（<b>不关投影</b>）。
     * <p>⚠️ 它<b>不再</b>是 {@code ROWS − 1}：命令面板只画两行，把「返回」留在第 4 行会变成
     * 一块孤零零飘在下方的框（中间隔了三行空白）。
     */
    public static final int COMMAND_BACK_ROW = COMMAND_INPUT_ROW + 1;

    /** 🔴 <b>命令面板真正画出来的那两行</b>（顺序 = 从上到下）：输入框、返回框。 */
    public static final int[] COMMAND_ROWS = {COMMAND_INPUT_ROW, COMMAND_BACK_ROW};

    // ================================================== 🆕 设置面板的行布局（2026-10-06 第三 / 四轮）

    /**
     * 🆕 <b>设置面板用满这 5 行</b>（= 板数；一块板一行，"5" 这个数一个都没挪）：
     * <pre>
     *   行 0  距离      [− 0.2 格] [2.8 格] [+ 0.2 格]
     *   行 1  布局      [环绕] [竖列] [横排]
     *   行 2  环绕特效  [关] [弱] [中] [强]          ← 四格，点一格整组切换
     *   行 3  高级调参  （整行可点 ⇒ 进第二层高级页）
     *   行 4  返回      （整行可点 ⇒ 收起面板回到五块菜单板）
     * </pre>
     *
     * <p>用户原话逐字（第三条点单）：
     * <blockquote>你干脆把朝向的修改选项删了得了，反正另外两个正常人肯定不会用</blockquote>
     * ⇒ 原来的「朝向」那一行<b>整行不存在</b>（不是"画了但不可点"）；而朝向档本身<b>没有丢</b>：
     * {@code /shanhai menu facing follow|snap|fixed} 一个字没动（它影响的是五块菜单板）。
     *
     * <p>🔴 <b>行 0 与行 4 现在都画出来了</b>（改版前它们分别是"标题行"与"空行"）：
     * 用户拍板的版面就是「距离 / 布局 / 环绕特效 / 高级调参 / 返回」五行，正好占满这块 5 行网格
     * ⇒ 几何、行位、行高、准星可达性与已验收版<b>逐位相同</b>（没有为容纳新行动过任何一个数）。
     */
    public static final int SETTINGS_DIST_ROW = 0;

    /** 「布局」那一行 —— 紧贴距离行下面。 */
    public static final int SETTINGS_LAYOUT_ROW = 1;

    /**
     * 🆕 「环绕特效」那一行：<b>[关][弱][中][强] 四格</b>，点一格即整组切换。
     * <p>与「布局」行同构：当前生效那一档带 {@code ▸} 标记（{@link #isCurrent}），
     * 点任意一格都切到那一档。
     * <p>⚠️ 这一行<b>没有单独的名称格</b>：一行只有 4 格、而四档正好占满四格
     * （名称靠日志与这一行的位置表达；要加名称格就得砍掉一档，那是用户没要的）。
     */
    public static final int SETTINGS_PRESET_ROW = 2;

    /** 🆕 「高级调参」那一行：<b>整行可点</b> ⇒ 进第二层（{@link #MODE_ADVANCED}）。 */
    public static final int SETTINGS_ADV_ROW = 3;

    /** 「返回」那一行：整行可点 ⇒ 收起面板、回到五块菜单板（<b>不关投影</b>）。 */
    public static final int SETTINGS_BACK_ROW = 4;

    /** 🔴 <b>设置面板画出来的行</b>（= 全部 5 行，顺序 = 从上到下）。 */
    public static final int[] SETTINGS_ROWS = {SETTINGS_DIST_ROW, SETTINGS_LAYOUT_ROW,
            SETTINGS_PRESET_ROW, SETTINGS_ADV_ROW, SETTINGS_BACK_ROW};

    // ---------------------------------------------------------------- 🆕 第二层：高级调参页

    /**
     * 高级页的第几行是导航 = <b>最后一行</b>（与第一层「返回」同一行号 ⇒ 两层用满同一块 5 行网格）。
     * <p>导航四格：{@code [上一页][类别·第 n/N 页][下一页][返回]}。
     */
    public static final int ADV_NAV_ROW = ROWS - 1;

    /** 导航格 0 = 上一页。 */
    public static final int ADV_CELL_PREV = 0;
    /** 导航格 1 = 「类别 · 第 n/N 页」（只读，不可点）。 */
    public static final int ADV_CELL_INFO = 1;
    /** 导航格 2 = 下一页。 */
    public static final int ADV_CELL_NEXT = 2;
    /** 导航格 3 = 返回（<b>回第一层设置面板，不是关面板</b>）。 */
    public static final int ADV_CELL_BACK = 3;

    /** 参数值那一行：格 1 = {@code −}、格 2 = 当前值（只读）、格 3 = {@code +}。 */
    public static final int ADV_CELL_MINUS = 1;
    public static final int ADV_CELL_VALUE = 2;
    public static final int ADV_CELL_PLUS = 3;

    /** 当前在第几页（0 基）。<b>只在内存里</b>，不落盘。 */
    private static int advPage;

    /** 当前高级页（0 基）。 */
    public static int advPage() {
        return advPage;
    }

    /** 一共几页（= 4）。 */
    public static int advPageCount() {
        return ShanhaiHoloSurroundTuning.PAGE_COUNT;
    }

    /** 切到某一页（越界钳住；进高级页时一律回到第 1 页）。 */
    public static void setAdvPage(int page) {
        advPage = Math.max(0, Math.min(ShanhaiHoloSurroundTuning.PAGE_COUNT - 1, page));
    }

    /** 翻页（{@code +1 / −1}），到头就停住（不循环 —— 循环会让人不知道自己在第几页）。 */
    public static void flipAdvPage(int delta) {
        setAdvPage(advPage + delta);
    }

    /** 当前页有几项（第 4 页只有 3 项：15 = 4+4+4+3）。 */
    public static int advParamCountOnPage() {
        return ShanhaiHoloSurroundTuning.paramCountOnPage(advPage);
    }

    /**
     * （高级页的某个行号, 当前页）⇒ 第几项（0..13）；{@code -1} = 这一行这一页没有项。
     * <p>它把 {@link ShanhaiHoloSurroundTuning#indexOf} 包了一层 —— 调用方（面板 / 输入层 /
     * 离线探针）都走它，于是"第几行是哪一项"只有一处答案。
     */
    public static int advParamIndex(int row) {
        return ShanhaiHoloSurroundTuning.indexOf(advPage, row);
    }

    /** 第 idx 项在哪一行（当前页）；不在本页返回 −1。 */
    public static int advRowOf(int idx) {
        final int page = ShanhaiHoloSurroundTuning.pageOf(idx);
        return page == advPage ? ShanhaiHoloSurroundTuning.pageRowOf(idx) : -1;
    }

    /**
     * 🔴 <b>这一行要不要画 / 要不要参与命中</b>（三个面板各自"只有几行"的唯一落点）。
     *
     * <pre>
     *   命令面板  ：只有 {@link #COMMAND_INPUT_ROW} 与 {@link #COMMAND_BACK_ROW} 两行
     *   设置面板  ：{@link #SETTINGS_ROWS} 那五行（距离 / 布局 / 环绕特效 / 高级调参 / 返回）
     *   高级页    ：前几行 = 本页的参数项（最后一页只有 2 项）+ 最后一行导航
     * </pre>
     * 渲染器三个绘制遍、环绕层的板面禁区、输入层的"指着谁/点了哪一格"都读它 ⇒
     * <b>画出来的行与能点的行只可能是一套</b>（判据见 {@link #selfTest()} 的 N7/N8/N9/N10）。
     */
    public static boolean rowVisible(int mode, int row) {
        if (row < 0 || row >= ROWS) {
            return false;
        }
        if (mode == MODE_SETTINGS) {
            for (int r : SETTINGS_ROWS) {
                if (row == r) {
                    return true;
                }
            }
            return false;
        }
        if (mode == MODE_ADVANCED) {
            return row == ADV_NAV_ROW || advParamIndex(row) >= 0;
        }
        if (mode == MODE_COMMAND) {
            return row == COMMAND_INPUT_ROW || row == COMMAND_BACK_ROW;
        }
        return true;
    }

    /** 某个面板画出来的行数（自检与读数用；<b>数出来的，不写死</b>）。 */
    public static int visibleRowCount(int mode) {
        int k = 0;
        for (int r = 0; r < ROWS; r++) {
            if (rowVisible(mode, r)) {
                k++;
            }
        }
        return k;
    }

    /** 🆕 输入框里那句【真正的占位符】（空输入时显示，打一个字立刻换成你打的字）。 */
    public static final String COMMAND_PLACEHOLDER = "输入命令，回车执行";

    // ================================================================== 点击动作码

    public static final int CLICK_NONE = 0;
    /** 「返回」：关掉面板、回到五块菜单板（<b>不关投影</b>）。 */
    public static final int CLICK_CLOSE = 1;
    public static final int CLICK_DIST_DOWN = 2;
    public static final int CLICK_DIST_UP = 3;
    /**
     * 🆕 2026-10-06（第三轮）：<b>面板上已经没有「朝向」那一行了</b>（用户点单整行删掉）
     * ⇒ 这三个动作码<b>不会被任何一格返回</b>（判据见 {@link #selfTest()} 的 N8，那是一条
     * "扫全表"的负对照，不是"看某一行"）。
     * <p>⚠️ 留着这三个常量与它们在 {@code ShanhaiHoloMenuInput} 里的处理分支，是因为
     * <b>朝向档本身还在</b>：{@code /shanhai menu facing follow|snap|fixed} 一个字没动。
     * 删掉它们只会让"哪几档存在"这件事分裂成两处说法（命令一处、单击一处）。
     */
    public static final int CLICK_FACING_FOLLOW = 4;
    public static final int CLICK_FACING_SNAP = 5;
    public static final int CLICK_FACING_FIXED = 6;
    public static final int CLICK_LAYOUT_RING = 7;
    public static final int CLICK_LAYOUT_COLUMN = 8;
    public static final int CLICK_LAYOUT_ROW = 9;
    /** 命令面板：打开键盘输入框。 */
    public static final int CLICK_CMD_TYPE = 10;
    /** 命令面板：执行已输入的这条命令。 */
    public static final int CLICK_CMD_RUN = 11;
    /** 命令面板：清空输入。 */
    public static final int CLICK_CMD_CLEAR = 12;

    // ---- 🆕 2026-10-06（第二轮）：环绕特效四档 + 高级调参页 ----

    /** 设置面板「环绕特效」那一行的四格（点一格 = 整组切到那一档）。 */
    public static final int CLICK_PRESET_OFF = 13;
    public static final int CLICK_PRESET_WEAK = 14;
    public static final int CLICK_PRESET_MID = 15;
    public static final int CLICK_PRESET_STRONG = 16;

    /** 设置面板「高级调参」那一行 ⇒ 进第二层（回到第 1 页）。 */
    public static final int CLICK_ADV_OPEN = 17;
    /** 高级页导航：上一页 / 下一页。 */
    public static final int CLICK_ADV_PREV = 18;
    public static final int CLICK_ADV_NEXT = 19;
    /** 高级页导航：返回<b>第一层设置面板</b>（不是关面板）。 */
    public static final int CLICK_ADV_BACK = 20;
    /** 高级页参数行：那一项的 − / +（具体减哪一项由 (行, 当前页) 决定，见 {@link #advParamIndex}）。 */
    public static final int CLICK_PARAM_DOWN = 21;
    public static final int CLICK_PARAM_UP = 22;

    /**
     * 🆕 动作码 ⇒ 环绕特效档位号；不是预设那四个码就返回 {@code -1}。
     * <p>它把"点哪一格 = 切到哪一档"收成一处（输入层与自检都读它，不各写一遍 switch）。
     */
    public static int presetOfClick(int click) {
        return switch (click) {
            case CLICK_PRESET_OFF -> ShanhaiHoloSurroundTuning.PRESET_OFF;
            case CLICK_PRESET_WEAK -> ShanhaiHoloSurroundTuning.PRESET_WEAK;
            case CLICK_PRESET_MID -> ShanhaiHoloSurroundTuning.PRESET_MID;
            case CLICK_PRESET_STRONG -> ShanhaiHoloSurroundTuning.PRESET_STRONG;
            default -> -1;
        };
    }

    /** 🆕 「环绕特效」那一行的第 {@code cell} 格对应哪一档（{@code -1} = 没有）。 */
    public static int presetOfCell(int cell) {
        return cell < 0 || cell >= ShanhaiHoloSurroundTuning.PRESET_COUNT ? -1 : cell;
    }

    /** 动作码 ⇒ 中文（日志与报告都用它，<b>不许让英文码出现在人看的行里</b>）。 */
    public static String clickName(int click) {
        return switch (click) {
            case CLICK_CLOSE -> "返回";
            case CLICK_DIST_DOWN -> "距离减一档";
            case CLICK_DIST_UP -> "距离加一档";
            case CLICK_FACING_FOLLOW -> "朝向=平滑跟随";
            case CLICK_FACING_SNAP -> "朝向=立即转向";
            case CLICK_FACING_FIXED -> "朝向=世界固定";
            case CLICK_LAYOUT_RING -> "布局=环绕";
            case CLICK_LAYOUT_COLUMN -> "布局=竖列";
            case CLICK_LAYOUT_ROW -> "布局=横排";
            case CLICK_CMD_TYPE -> "打开键盘输入";
            case CLICK_CMD_RUN -> "执行命令";
            case CLICK_CMD_CLEAR -> "清空输入";
            case CLICK_PRESET_OFF -> "环绕特效=关";
            case CLICK_PRESET_WEAK -> "环绕特效=弱";
            case CLICK_PRESET_MID -> "环绕特效=中";
            case CLICK_PRESET_STRONG -> "环绕特效=强";
            case CLICK_ADV_OPEN -> "进高级调参";
            case CLICK_ADV_PREV -> "上一页";
            case CLICK_ADV_NEXT -> "下一页";
            case CLICK_ADV_BACK -> "高级页返回";
            case CLICK_PARAM_DOWN -> "参数减一档";
            case CLICK_PARAM_UP -> "参数加一档";
            default -> "无动作";
        };
    }

    /** 面板名（日志用）。 */
    public static String modeName(int mode) {
        return switch (mode) {
            case MODE_SETTINGS -> "设置面板";
            case MODE_COMMAND -> "命令面板";
            case MODE_ADVANCED -> "高级调参页";
            default -> "无面板";
        };
    }

    // ================================================================== 几何

    /** 面板那 5 块行板的局部位姿（= 几何核里唯一一处，渲染与命中判定共用）。 */
    public static ShanhaiHoloMenuLayout.Board[] rows() {
        return ShanhaiHoloMenuLayout.panelRows();
    }

    /**
     * 面板的落地位姿：<b>玩家眼睛沿视线前移 {@code distance} 格、再降 0.10 格</b>。
     * <p>复用几何核里既有的 {@code centerOf("column", …)} —— 面板与甲案的列排版共用同一条公式，
     * 本轮<b>没有再写第二套</b>（写第二套 = 两处必然漂）。
     */
    public static double[] centerOf(double eyeX, double eyeY, double eyeZ, float playerYawDeg,
                                    float distance) {
        return ShanhaiHoloMenuLayout.centerOf("column", eyeX, eyeY, eyeZ, playerYawDeg, distance);
    }

    /**
     * 面板的框偏航 = {@code 180 − 玩家偏航}（整列随玩家扭头一起转，始终正对着玩家）。
     * <p>⚠️ 与环绕档的菜单板<b>不同</b>：环绕档那五块板恒不跟头转（用户点单要的"能选"）；
     * 而面板是"贴在你面前的一张表"，跟头转才是对的。
     */
    public static float frameYaw(float playerYawDeg) {
        return ShanhaiHoloMenuLayout.frameYawOf("column", playerYawDeg);
    }

    /**
     * 板内 x（bu）⇒ 格子号 0..3。
     * <p>{@code localX ∈ [−280, 280]}；越界一律夹到两端（板面已经被命中环裁过，夹一下只是为了
     * 浮点边界上不返回 −1 让整行变成"点了没反应"）。
     */
    public static int cellOf(float localXBu) {
        final float half = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        final float rel = (localXBu + half) / CELL_W_BU;
        final int c = (int) Math.floor(rel);
        if (c < 0) {
            return 0;
        }
        return Math.min(c, CELLS - 1);
    }

    /** 某一格左边缘的板内 x（bu）—— 渲染器画文字用它，与 {@link #cellOf} 严格互逆。 */
    public static float cellLeftBu(int cell) {
        final float half = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        return -half + cell * CELL_W_BU;
    }

    // ================================================================== 点击模型

    /**
     * （模式, 行, 格）⇒ 动作码。
     *
     * <p>🔴 <b>这张表就是"面板能点什么"的唯一口径</b>：输入层拿到它直接 switch，
     * 不在别处再写第二套行列判断（否则"哪一格能点"会有两个版本）。
     *
     * <p>🆕 命令面板（第 6 条点单）：只有<b>输入框</b>（两块行板，整行可点 ⇒ 开键盘）与
     * <b>返回</b>（最后一行）有动作；「执行」靠回车、「清空」靠 Ctrl+A/Delete，
     * 所以那两行<b>连行都没有了</b> —— 表里因此找不到 {@link #CLICK_CMD_RUN} /
     * {@link #CLICK_CMD_CLEAR}，这正是"删干净了没有"的机器判据。
     *
     * <p>🔴 设置面板（2026-10-06 第三轮）：<b>「朝向」那一行整行删掉</b> ⇒ 表里再也找不到
     * {@link #CLICK_FACING_FOLLOW} / {@link #CLICK_FACING_SNAP} / {@link #CLICK_FACING_FIXED}
     * （判据见 {@link #selfTest()} 的 N8）。
     */
    public static int clickAction(int mode, int row, int cell) {
        if (row < 0 || row >= ROWS || cell < 0 || cell >= CELLS) {
            return CLICK_NONE;
        }
        if (mode == MODE_SETTINGS) {
            return switch (row) {
                case SETTINGS_DIST_ROW -> switch (cell) {
                    case 1 -> CLICK_DIST_DOWN;
                    case 3 -> CLICK_DIST_UP;
                    default -> CLICK_NONE;          // 格 0 = 标签，格 2 = 当前值（只读）
                };
                case SETTINGS_LAYOUT_ROW -> switch (cell) {
                    case 1 -> CLICK_LAYOUT_RING;
                    case 2 -> CLICK_LAYOUT_COLUMN;
                    case 3 -> CLICK_LAYOUT_ROW;
                    default -> CLICK_NONE;
                };
                // 🔴 环绕特效那一行：四格全部可点，点哪一格 = 整组切到那一档
                //    （格号 == 档位号，见 presetOfCell；两者不可能对不上）
                case SETTINGS_PRESET_ROW -> switch (cell) {
                    case 0 -> CLICK_PRESET_OFF;
                    case 1 -> CLICK_PRESET_WEAK;
                    case 2 -> CLICK_PRESET_MID;
                    case 3 -> CLICK_PRESET_STRONG;
                    default -> CLICK_NONE;
                };
                // 「高级调参」整行可点 ⇒ 进第二层
                case SETTINGS_ADV_ROW -> CLICK_ADV_OPEN;
                // 「返回」整行可点
                case SETTINGS_BACK_ROW -> CLICK_CLOSE;
                default -> CLICK_NONE;
            };
        }
        if (mode == MODE_ADVANCED) {
            if (row == ADV_NAV_ROW) {
                return switch (cell) {
                    case ADV_CELL_PREV -> CLICK_ADV_PREV;
                    case ADV_CELL_NEXT -> CLICK_ADV_NEXT;
                    case ADV_CELL_BACK -> CLICK_ADV_BACK;
                    // 格 1 = 「类别·第 n/N 页」读数，只读（不可点）
                    default -> CLICK_NONE;
                };
            }
            // 参数行：只有本页真的有那一项时才有按钮
            if (advParamIndex(row) < 0) {
                return CLICK_NONE;
            }
            return switch (cell) {
                case ADV_CELL_MINUS -> CLICK_PARAM_DOWN;
                case ADV_CELL_PLUS -> CLICK_PARAM_UP;
                // 格 0 = 参数名（标签）、格 2 = 当前值（只读）
                default -> CLICK_NONE;
            };
        }
        if (mode == MODE_COMMAND) {
            return switch (row) {
                // 输入框：整行可点（四格任意一格都开键盘）
                case COMMAND_INPUT_ROW -> CLICK_CMD_TYPE;
                // 返回框：整行可点 —— 收起面板回到五块菜单板
                case COMMAND_BACK_ROW -> CLICK_CLOSE;
                // 🔴 其余三行【没画出来】⇒ 一律不可点（"点到一个看不见的东西"是这一条要防的）
                default -> CLICK_NONE;
            };
        }
        return CLICK_NONE;
    }

    /**
     * 这一格画成"标签"（白、字号大一点）还是"按钮"（淡青、指到会变白）。
     *
     * <p>渲染器在改版前用的是 {@code cell == 0}，那一刀对"格 0 不是标签"的两行是错的：
     * 「环绕特效」那一行的格 0 是<b>一颗按钮</b>（「关」），「高级调参」那一行的格 0 也是
     * （整行可点）。⇒ 收成这一个函数，渲染器只读它。
     * <p>⚠️ 「返回」那一行的格 0 <b>照旧算标签</b>：那是用户已经验收过的观感（白字），
     * 本次不动它。
     */
    public static boolean isLabelCell(int mode, int row, int cell) {
        if (cell != 0) {
            return false;
        }
        if (mode == MODE_SETTINGS) {
            return row == SETTINGS_DIST_ROW || row == SETTINGS_LAYOUT_ROW
                    || row == SETTINGS_BACK_ROW;
        }
        if (mode == MODE_ADVANCED) {
            // 参数名是标签；导航那一行四格全是按钮
            return row != ADV_NAV_ROW;
        }
        return true;
    }

    /** 这个名字是不是"可点的按钮"（渲染器用它决定要不要画成亮色）。 */
    public static boolean clickable(int mode, int row, int cell) {
        return clickAction(mode, row, cell) != CLICK_NONE;
    }

    // ================================================================== 文本模型

    /**
     * 第 0 行的标题。
     *
     * <p>🔴 <b>现在两个面板都没有标题框 ⇒ 本函数恒返回 {@code ""}</b>：
     * <pre>
     *   命令面板：2026-10-06 第 ② 条点单（「我只要两个框，一个输入，一个返回…多的都删了」）删的；
     *   设置面板：2026-10-06 第三轮点单删的 —— 用户要的判据是「设置面板只剩三行：距离 / 布局 / 返回」，
     *             标题框会变成第 4 行（与命令面板那次同一个口径）。
     * </pre>
     * <p>⚠️ 它<b>仍然留着</b>：它是"面板有没有标题行"的<b>唯一口径</b> —— 渲染器与自检都读它，
     * 两处不可能分成两套说法（判据见 {@link #selfTest()} 的 N9）。面板名没有丢：它照旧由
     * {@link #modeName(int)} 出现在日志里。
     */
    public static String title(int mode) {
        return "";
    }

    /**
     * 某一格的文字（{@code ""} = 这一格不画）。
     * <p>⚠️ 文本里的数值<b>全部从 {@link ShanhaiHoloMenuState} 现读</b>——
     * 面板上显示的就是"命令会读到的那个值"，两边不可能不一致。
     * <p>🔴 设置面板只有 {@link #SETTINGS_ROWS} 三行有文字；行 0 与行 4（以及原「朝向」行的位置）
     * <b>一个字都没有</b>。
     */
    public static String cellText(int mode, int row, int cell) {
        if (row < 0 || row >= ROWS || cell < 0 || cell >= CELLS) {
            return "";
        }
        if (mode == MODE_SETTINGS) {
            return switch (row) {
                case SETTINGS_DIST_ROW -> switch (cell) {
                    case 0 -> "距离";
                    case 1 -> "− " + trimStep();
                    case 2 -> distanceText();
                    case 3 -> "+ " + trimStep();
                    default -> "";
                };
                case SETTINGS_LAYOUT_ROW -> switch (cell) {
                    case 0 -> "布局";
                    case 1 -> mark(ShanhaiHoloMenuState.layout() == ShanhaiHoloMenuState.Layout.RING)
                            + "环绕";
                    case 2 -> mark(ShanhaiHoloMenuState.layout() == ShanhaiHoloMenuState.Layout.COLUMN)
                            + "竖列";
                    case 3 -> mark(ShanhaiHoloMenuState.layout() == ShanhaiHoloMenuState.Layout.ROW)
                            + "横排";
                    default -> "";
                };
                // 🆕 环绕特效那一行：四格 = 四档，当前生效那一档带 ▸
                case SETTINGS_PRESET_ROW -> cell < ShanhaiHoloSurroundTuning.PRESET_COUNT
                        ? mark(ShanhaiHoloSurroundTuning.preset() == cell)
                        + ShanhaiHoloSurroundTuning.presetName(cell)
                        : "";
                // 🆕 高级调参那一行：整行可点，名称挂在格 0
                case SETTINGS_ADV_ROW -> cell == 0 ? "高级调参" : "";
                case SETTINGS_BACK_ROW -> cell == 0 ? "返回" : "";
                default -> "";
            };
        }
        if (mode == MODE_ADVANCED) {
            if (row == ADV_NAV_ROW) {
                return switch (cell) {
                    case ADV_CELL_PREV -> "上一页";
                    case ADV_CELL_INFO -> advNavInfoText();
                    case ADV_CELL_NEXT -> "下一页";
                    case ADV_CELL_BACK -> "返回";
                    default -> "";
                };
            }
            final int idx = advParamIndex(row);
            if (idx < 0) {
                return "";
            }
            return switch (cell) {
                case 0 -> ShanhaiHoloSurroundTuning.PARAM_NAME[idx];
                case ADV_CELL_MINUS -> "− " + ShanhaiHoloSurroundTuning.paramStepText(idx);
                case ADV_CELL_VALUE -> ShanhaiHoloSurroundTuning.paramText(idx);
                case ADV_CELL_PLUS -> "+ " + ShanhaiHoloSurroundTuning.paramStepText(idx);
                default -> "";
            };
        }
        if (mode == MODE_COMMAND) {
            return switch (row) {
                // 🆕 输入框：文字挂在第 0 格（板左边缘起画）⇒ 横跨整行；1..3 格刻意留空
                case COMMAND_INPUT_ROW -> cell == 0 ? inputDisplay() : "";
                // 🆕 返回框：这一个框里只有「返回」两个字（上一条命令的返回值走聊天栏，不占框）
                case COMMAND_BACK_ROW -> cell == 0 ? "返回" : "";
                // 🔴 其余三行【不画】（标题 / 空行 /「结果」行）—— 用户点单"多的都删了"
                default -> "";
            };
        }
        return "";
    }

    // ============================================================ 🆕 高级页的类别 / 页码文字

    /**
     * 第 idx 项属于哪一类（<b>短的</b>名字，为了塞得进一格 140 bu）：
     * 前 6 项 = {@code 环}（数据环）、中 7 项 = {@code 星尘}、最后 1 项 = {@code 整体}。
     */
    public static String categoryOf(int idx) {
        if (idx < 0 || idx >= ShanhaiHoloSurroundTuning.PARAM_COUNT) {
            return "";
        }
        if (idx <= ShanhaiHoloSurroundTuning.P_RING_PRECESS_SCALE) {
            return "环";
        }
        if (idx <= ShanhaiHoloSurroundTuning.P_FIELD_BRIGHT) {
            return "星尘";
        }
        return "整体";
    }

    /**
     * 导航格 1 的文字 = {@code 类别·第 n/N 页}。
     * <p>一页可能横跨两类（第 2 页 = 数据环 2 项 + 星尘 2 项）⇒ 写成 {@code 环+星尘}。
     */
    public static String pageCategoryText() {
        return "第" + (advPage + 1) + "/" + advPageCount() + "页";
    }

    /** 这一页是哪几类（单独一个函数，方便自检、探针与报告逐页念出来）。 */
    public static String pageCategories() {
        return pageCategoriesOf(advPage);
    }

    /** 指定某一页是哪几类（{@code ""} = 那一页没有项）。 */
    public static String pageCategoriesOf(int page) {
        final int cnt = ShanhaiHoloSurroundTuning.paramCountOnPage(page);
        if (cnt <= 0) {
            return "";
        }
        final int first = ShanhaiHoloSurroundTuning.indexOf(page, 0);
        final int last = ShanhaiHoloSurroundTuning.indexOf(page, cnt - 1);
        final String a = categoryOf(first);
        final String b = categoryOf(last);
        return a.equals(b) ? a : a + "+" + b;
    }

    /** 导航那一格的完整文字（{@code 环+星尘·第2/4页}）—— 面板上画的就是它。 */
    public static String advNavInfoText() {
        final String cat = pageCategories();
        return (cat.isEmpty() ? "" : cat + "·") + pageCategoryText();
    }

    /**
     * 这一格是不是"当前生效的那一档"（设置面板的朝向/布局两行用它打勾）。
     * <p>实现口径：文字前面那个 {@code ▸}（见 {@link #mark}）—— 只有当前档才带。
     */
    public static boolean isCurrent(String text) {
        return text != null && text.startsWith(MARK);
    }

    /** 当前档的前缀（渲染器不直接用它，只在自检里当口径用）。 */
    public static final String MARK = "▸";

    private static String mark(boolean on) {
        return on ? MARK : "";
    }

    /**
     * 「± 一格」那两格上的文字。
     * <p>🔴 <b>必须在类加载时算一次，不可以在渲染帧里算</b>：
     * {@code cellText} 是<b>每帧</b>被渲染器调的（5 行 × 4 格），
     * 里面但凡有一次 {@code String.format}，就是每帧一次的格式化 + 一个临时对象。
     * （本工程自己的口径：卡顿的判据之一就是"每帧字符串拼接"。）
     * <p>它从 {@link #DISTANCE_STEP} 算出来 ⇒ 与步长不可能漂（不是手抄的 "0.2 格"）。
     */
    private static final String STEP_TEXT =
            String.format(java.util.Locale.ROOT, "%.1f 格", DISTANCE_STEP);

    private static String trimStep() {
        return STEP_TEXT;
    }

    /** 上一次格式化过的距离值与它的文字（值没变就直接复用，见 {@link #trimStep()} 的同一条理由）。 */
    private static float cachedDistanceValue = Float.NaN;
    private static String cachedDistanceText = "";

    private static String distanceText() {
        final float d = ShanhaiHoloMenuState.distance();
        if (Float.compare(d, cachedDistanceValue) != 0) {
            cachedDistanceValue = d;
            cachedDistanceText = String.format(java.util.Locale.ROOT, "%.1f 格", d);
        }
        return cachedDistanceText;
    }

    // ================================================================== 命令文本

    /** 命令最长多少个字符（超了就不收 —— 免得一行画不下、也免得服务端收到一条超长命令）。 */
    public static final int MAX_COMMAND_CHARS = 256;

    /** 命令那一行最多画多少个字符（超了留尾巴：光标永远看得见）。<b>只影响画出来的宽度</b>。 */
    public static final int VISIBLE_COMMAND_CHARS = 30;

    private static final StringBuilder COMMAND = new StringBuilder();

    /** 最近一次命令的返回值读数（<b>客户端自己记的</b>；写日志与自检用）。 */
    private static String lastResult = "（还没执行过）";

    public static String command() {
        return COMMAND.toString();
    }

    public static boolean commandEmpty() {
        return COMMAND.length() == 0;
    }

    /** 追加一个字符（输入法/键盘来的都一样，见 {@code ShanhaiHoloCommandScreen#charTyped}）。 */
    public static void append(char c) {
        if (COMMAND.length() >= MAX_COMMAND_CHARS) {
            return;
        }
        COMMAND.append(c);
        dirty = true;                       // 板上那一行下次读时重建（不在渲染帧里拼）
    }

    public static void backspace() {
        if (COMMAND.length() > 0) {
            COMMAND.deleteCharAt(COMMAND.length() - 1);
            dirty = true;
        }
    }

    public static void clear() {
        if (COMMAND.length() > 0) {
            COMMAND.setLength(0);
        }
        dirty = true;
    }

    public static String lastResult() {
        return lastResult;
    }

    /**
     * 🆕 2026-10-06（用户第 ② 条点单）：<b>命令的反馈不再占用任何框</b> —— 那个「结果：…」
     * 行已经删掉了。反馈走两条既有通道（都不占板）：
     * <pre>
     *   ① 聊天栏：服务端 {@code ShanhaiHoloMenuNetwork#onCommandRequest} 本来就会把命令自己的
     *      成功/失败输出 + 一行「§b[全息命令] §7已以管理员权限执行 §f/xxx（返回码 N）」发给玩家；
     *   ② 日志：{@code holo_cmd_executed … result=N} 与下面这个 {@link #lastResult()} 读数。
     * </pre>
     * 这个 setter 因此只更新读数（供日志/自检读），<b>不产生任何绘制</b>。
     */
    public static void setLastResult(String text) {
        lastResult = text == null || text.isEmpty() ? "（还没执行过）" : text;
    }

    /**
     * 🆕 <b>输入框里现在该显示什么</b> —— 这就是"真正的占位符"的唯一落点。
     *
     * <pre>
     *   没打字   ⇒ 返回 {@link #COMMAND_PLACEHOLDER}（说明文字）
     *   打了字   ⇒ 返回【输入本身】（逐字相同，不加"…"、不加光标）
     *   又删空   ⇒ 说明文字【回来】（因为判据就是 {@link #commandEmpty()}，不是"进过字就变")
     * </pre>
     * 🔴 与改版前的区别：原来那句「（点这一行开始打字）」是<b>当成"值"拼进字符串</b>的
     * （{@code visibleCommand()} 里 {@code "…" + tail + "_"} 那一套），
     * 于是"有没有打字"这件事在文本层面看不出来。现在
     * <b>占位符是一个独立的常量、由"空 / 非空"当场选出来</b>
     * ⇒ 用户点单的那条判据（空 ⇒ 说明；有字符 ⇒ 输入本身）成了可以逐字比对的断言。
     *
     * <p>⚠️ 它<b>不缓存</b>：占位符不是"打字时重建一次"那类东西，而 {@code commandEmpty()}
     * 只是一次长度比较（{@link StringBuilder#length()}），比走缓存分支还便宜。
     */
    public static String inputDisplay() {
        return commandEmpty() ? COMMAND_PLACEHOLDER : COMMAND.toString();
    }

    /** 现在显示的是不是那句占位符（渲染器用它决定用暗色还是亮色画）。 */
    public static boolean inputIsPlaceholder() {
        return commandEmpty();
    }

    /**
     * 画在板上的命令正文（只留<b>尾部</b> {@link #VISIBLE_COMMAND_CHARS} 个字符，前面加省略号）。
     * <p>🔴 它<b>只影响画出来的宽度</b>（板宽就 560 bu，256 字的命令画不下），
     * 不是"面板上写的是什么"的口径 —— 那个口径是 {@link #inputDisplay()}。
     * <p>同样<b>不</b>在渲染帧里拼：命令文本只在打字/清空/复位时变，所以
     * {@link #dirty} 一置位就在下一次读时重建一次（打字那一拍重建一次，之后每帧零开销）。
     */
    private static boolean dirty = true;
    private static String visibleCache = "";

    public static String visibleCommandTail() {
        if (dirty) {
            final String s = command();
            visibleCache = s.isEmpty() ? COMMAND_PLACEHOLDER
                    : (s.length() <= VISIBLE_COMMAND_CHARS
                            ? s                                   // ⚠️ 短输入【原样】画（不加任何装饰）
                            : "…" + tail(s, VISIBLE_COMMAND_CHARS));
            dirty = false;
        }
        return visibleCache;
    }

    /** 取末尾 n 个字符（不切代理对以外的复杂情况：命令是 ASCII 为主，中文整字也不会被切坏）。 */
    public static String tail(String s, int n) {
        if (s == null) {
            return "";
        }
        if (n <= 0) {
            return "";
        }
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /** 取开头 n 个字符，超了用 … 收尾。 */
    public static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        if (n <= 0) {
            return "";
        }
        return s.length() <= n ? s : (s.substring(0, n) + "…");
    }

    /** 供离线自检与"面板被关掉"时复位用。 */
    public static void resetForTest() {
        COMMAND.setLength(0);
        lastResult = "（还没执行过）";
        dirty = true;
    }

    /** 面板被关掉时（返回 / 关投影 / 模块被拿走）复位命令缓冲与那一行读数。 */
    public static void closePanel() {
        resetForTest();
    }

    // ================================================================== 自检

    /** 自检结果。 */
    public record Report(int pass, int total, String bad) {
        public boolean ok() {
            return bad == null;
        }
    }

    /**
     * 离线自检（<b>不需要 Minecraft</b>）：几何、格子换算、点击表、文本表四块的判据。
     *
     * <p>判据里 <b>6 条是负对照</b>：
     * <pre>
     *   N1 格子换算：格 1 的<b>最右边缘</b>（x = +140−ε）必须仍算格 1，<b>不许</b>算成格 2
     *   N2 点击表：设置面板的「距离」那一行的格 0（标签）与格 2（当前值）必须【不可点】
     *      —— 否则"点标签也改距离"这种手感会冒出来
     *   N3 两个面板的「返回」都在最后一行、都整行可点（缺一个就有一条路出不去）
     *   N4 面板的五块行板必须【不自转】（rotX=rotY=0）且【不缩放】（scale=1）
     *      —— 列排版那两端的外倾是给菜单用的，混进面板会让首末两行歪掉
     *   N5 行板必须是【从上到下】排：index 0 的 y 最大（写反了整张表会上下颠倒）
     *   N6 🆕（第 6 条点单）<b>命令面板全表扫一遍，「执行」与「清空」一个都不许剩</b>
     *      —— 用户点名要删掉那两行；这条判据抓的是"删没删干净"（挪到别的行也会被抓到）
     * </pre>
     * 另有 3 条【占位符】判据（用户点单逐字）：空 ⇒ 说明文字；打一个字 ⇒ 输入本身；
     * 删空 ⇒ 说明文字回来。加上"输入框横跨整行 / 跨两块行板 / 返回框只读"三条布局判据。
     * 判定用的是"唯一正确答案"的输入，格数/行数是<b>运行时数出来的</b>，不写死常量（写死的数字一定会漂）。
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};

        // ---- 格子换算：4 格，每格 140 bu，边界逐条 ----
        final float half = ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f;
        c(bad, n, "最左边缘算第 0 格", cellOf(-half), 0);
        c(bad, n, "0 号格右边缘内算第 0 格", cellOf(-half + CELL_W_BU - 0.001f), 0);
        c(bad, n, "1 号格左边缘算第 1 格", cellOf(-half + CELL_W_BU), 1);
        c(bad, n, "板心算第 2 格", cellOf(0.0f), 2);
        c(bad, n, "最右边缘算第 3 格", cellOf(half), CELLS - 1);
        c(bad, n, "越界一律夹在 0..3（不许返回 −1 让整行点不动）",
                Math.max(cellOf(-half - 500.0f), 0) == 0 && cellOf(half + 500.0f) == CELLS - 1, true);
        // N1 负对照（把边界写错一格是最容易犯的错）
        c(bad, n, "N1 负对照：1 号格右边缘内【不许】算成第 2 格",
                cellOf(-half + 2.0f * CELL_W_BU - 0.001f), 1);

        // ---- cellLeftBu 与 cellOf 互逆（画的位置与点的位置必须是同一个口径）----
        for (int i = 0; i < CELLS; i++) {
            c(bad, n, "cellLeftBu(" + i + ") 换算回来还是第 " + i + " 格",
                    cellOf(cellLeftBu(i) + 1.0f), i);
        }

        // ---- 点击表：设置面板两行按钮 + 一行返回 ----
        c(bad, n, "设置-距离-格1 = 距离减", clickAction(MODE_SETTINGS, SETTINGS_DIST_ROW, 1), CLICK_DIST_DOWN);
        c(bad, n, "设置-距离-格3 = 距离加", clickAction(MODE_SETTINGS, SETTINGS_DIST_ROW, 3), CLICK_DIST_UP);
        c(bad, n, "设置-布局-格1 = 环绕", clickAction(MODE_SETTINGS, SETTINGS_LAYOUT_ROW, 1), CLICK_LAYOUT_RING);
        c(bad, n, "设置-布局-格2 = 竖列", clickAction(MODE_SETTINGS, SETTINGS_LAYOUT_ROW, 2), CLICK_LAYOUT_COLUMN);
        c(bad, n, "设置-布局-格3 = 横排", clickAction(MODE_SETTINGS, SETTINGS_LAYOUT_ROW, 3), CLICK_LAYOUT_ROW);
        // N2 负对照：标签格与"当前值"格必须不可点
        c(bad, n, "N2 负对照：设置-距离-格0(标签) 不可点",
                clickAction(MODE_SETTINGS, SETTINGS_DIST_ROW, 0), CLICK_NONE);
        c(bad, n, "N2 负对照：设置-距离-格2(当前值) 不可点",
                clickAction(MODE_SETTINGS, SETTINGS_DIST_ROW, 2), CLICK_NONE);
        // 🔴 2026-10-06（第四轮）：设置面板【5 行全用满】，一个行位都不空着
        c(bad, n, "设置面板画出来的行数 == 板块数 " + ROWS + "（五行全用满）",
                visibleRowCount(MODE_SETTINGS), ROWS);
        c(bad, n, "设置面板的行位恰好是 0..4（顺序：距离/布局/环绕特效/高级调参/返回）",
                SETTINGS_ROWS.length, ROWS);
        for (int r = 0; r < ROWS; r++) {
            c(bad, n, "设置面板第 " + r + " 行画得出来", rowVisible(MODE_SETTINGS, r), true);
        }
        c(bad, n, "行 0 = 距离那一行（格 2 写着当前距离）",
                cellText(MODE_SETTINGS, SETTINGS_DIST_ROW, 0), "距离");
        c(bad, n, "行 1 = 布局那一行", cellText(MODE_SETTINGS, SETTINGS_LAYOUT_ROW, 0), "布局");
        c(bad, n, "行 2 = 环绕特效那一行（没有名称格，四格就是四档）",
                cellText(MODE_SETTINGS, SETTINGS_PRESET_ROW, 0).replace(MARK, ""),
                ShanhaiHoloSurroundTuning.presetName(0));
        c(bad, n, "行 3 = 高级调参那一行", cellText(MODE_SETTINGS, SETTINGS_ADV_ROW, 0), "高级调参");
        c(bad, n, "行 4 = 返回那一行", cellText(MODE_SETTINGS, SETTINGS_BACK_ROW, 0), "返回");
        // 🆕 环绕特效那一行：四格 = 四档，逐格比对"点它 = 切到哪一档"与"格子上写的是哪一档"
        for (int col = 0; col < CELLS; col++) {
            final int want = switch (col) {
                case 0 -> CLICK_PRESET_OFF;
                case 1 -> CLICK_PRESET_WEAK;
                case 2 -> CLICK_PRESET_MID;
                default -> CLICK_PRESET_STRONG;
            };
            final String pn = ShanhaiHoloSurroundTuning.presetName(col);
            c(bad, n, "设置-环绕特效-格" + col + " 的动作码 = 切到「" + pn + "」档",
                    clickAction(MODE_SETTINGS, SETTINGS_PRESET_ROW, col), want);
            c(bad, n, "设置-环绕特效-格" + col + " 上的字 = 「" + pn + "」（可能带 ▸）",
                    cellText(MODE_SETTINGS, SETTINGS_PRESET_ROW, col).replace(MARK, ""), pn);
            c(bad, n, "设置-环绕特效-格" + col + " 的档位号 == 格号（格号即档位，不可能对不上）",
                    presetOfClick(want), presetOfCell(col));
        }
        // 🆕 「高级调参」整行可点 ⇒ 四格同一个动作码
        for (int col = 0; col < CELLS; col++) {
            c(bad, n, "设置-高级调参-格" + col + " = 进第二层（整行可点）",
                    clickAction(MODE_SETTINGS, SETTINGS_ADV_ROW, col), CLICK_ADV_OPEN);
        }
        // 「返回」整行可点
        for (int col = 0; col < CELLS; col++) {
            c(bad, n, "设置-返回-格" + col + " = 收起面板",
                    clickAction(MODE_SETTINGS, SETTINGS_BACK_ROW, col), CLICK_CLOSE);
        }
        // 命令面板（🆕 第 ② 条点单：**只有两个框** —— 【输入框】与【返回框】）
        for (int col = 0; col < CELLS; col++) {
            c(bad, n, "命令-输入框第 " + col + " 格 = 开键盘",
                    clickAction(MODE_COMMAND, COMMAND_INPUT_ROW, col), CLICK_CMD_TYPE);
            c(bad, n, "命令-返回框第 " + col + " 格 = 收起面板",
                    clickAction(MODE_COMMAND, COMMAND_BACK_ROW, col), CLICK_CLOSE);
        }
        // N6🔴 负对照（第 6 条点单的判据，保留）：整张命令面板上【不许】再出现
        //     「执行」与「清空」——这两行是用户点名要删掉的。
        //     判据用"扫全表"而不是"看某一行"：把它挪到别的行、或改成别的格子，一样会被抓到。
        final StringBuilder leftovers = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            for (int col = 0; col < CELLS; col++) {
                final int a = clickAction(MODE_COMMAND, r, col);
                if (a == CLICK_CMD_RUN || a == CLICK_CMD_CLEAR) {
                    leftovers.append('(').append(r).append(',').append(col).append(")=")
                            .append(clickName(a)).append(' ');
                }
            }
        }
        c(bad, n, "N6 负对照：命令面板上已无「执行」「清空」两行（只剩下输入框与返回）",
                leftovers.toString(), "");
        // 🔴 N7（第 ② 条点单的核心判据）：命令面板【只有两行是"画出来的"】
        //    —— 用户原话「我不要这么多框，我只要两个框，一个输入，一个返回，怎么还有5个框，多的都删了」。
        //    判据分三层：① 可见行数 == 2；② 可见行恰好是输入框与返回框；
        //    ③ 其余三行【一个字都不画】且【一格都不可点】。
        int visibleRows = 0;
        final StringBuilder visibleList = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            if (rowVisible(MODE_COMMAND, r)) {
                visibleRows++;
                visibleList.append(r).append(' ');
            }
        }
        c(bad, n, "N7 命令面板画出来的行数 == 2（用户点单：只要两个框）",
                visibleRows, COMMAND_ROWS.length);
        c(bad, n, "N7 命令面板画出来的行 = 输入框(" + COMMAND_INPUT_ROW + ") + 返回框("
                        + COMMAND_BACK_ROW + ")，其余三行一个都不画",
                visibleList.toString().trim(),
                COMMAND_INPUT_ROW + " " + COMMAND_BACK_ROW);
        // N7 负对照：把"删掉的那三行"当成【应当为空】来验
        //   —— 这三行原来是「命令（管理员权限）」标题 / 空行 /「结果：…」，任何一行冒出文字都算没删干净
        final StringBuilder ghosts = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            if (rowVisible(MODE_COMMAND, r)) {
                continue;
            }
            for (int col = 0; col < CELLS; col++) {
                if (!cellText(MODE_COMMAND, r, col).isEmpty()) {
                    ghosts.append('(').append(r).append(',').append(col).append(")=「")
                            .append(cellText(MODE_COMMAND, r, col)).append("」 ");
                }
                if (clickAction(MODE_COMMAND, r, col) != CLICK_NONE) {
                    ghosts.append("点得到(").append(r).append(',').append(col).append(") ");
                }
            }
        }
        c(bad, n, "N7 负对照：删掉的那三行既没有字、也没有一格能点（它们根本没画出来）",
                ghosts.toString(), "");
        c(bad, n, "N7 负对照：射线打到没画出来的行必须【不算命中】（输入层读的就是这个）",
                rowVisible(MODE_COMMAND, 0) || rowVisible(MODE_COMMAND, 3)
                        || rowVisible(MODE_COMMAND, 4), false);
        // 🔴 输入框【占满整行】：文字挂在第 0 格，1..3 格必须全空
        //    （三个格 420 bu 的宽度腾出来 ⇒ 输入区从 140 bu 变成整行 560 bu）
        boolean inputSpansRow = true;
        for (int col = 1; col < CELLS; col++) {
            if (!cellText(MODE_COMMAND, COMMAND_INPUT_ROW, col).isEmpty()) {
                inputSpansRow = false;
            }
        }
        c(bad, n, "输入框横跨整行：第 1..3 格全空、文字只从第 0 格（板左边缘）起画", inputSpansRow, true);
        c(bad, n, "第 0 格的左边缘就是板的左边缘（占满整行宽的下界）",
                Float.compare(cellLeftBu(0), -ShanhaiHoloMenuTuning.BOARD_WIDTH_BU * 0.5f), 0);
        // 🔴 输入框【只占一行】：用户第 ② 条点单的另一半（跨两行看起来像两个框）
        c(bad, n, "N7 输入框只占一行（返回框在它的下一行，中间没有空行）",
                COMMAND_BACK_ROW - COMMAND_INPUT_ROW, 1);
        c(bad, n, "返回框里写着「返回」两个字（出口没有丢）",
                cellText(MODE_COMMAND, COMMAND_BACK_ROW, 0), "返回");
        // 🆕 占位符规则（用户点单的三条，逐字比对）
        final String savedCmdForPlaceholder = command();
        clear();
        c(bad, n, "空输入时输入框==说明文字", inputDisplay(), COMMAND_PLACEHOLDER);
        c(bad, n, "空输入时 is_placeholder=true", inputIsPlaceholder(), true);
        append('t');
        c(bad, n, "打一个字之后，输入框==输入本身（不是说明文字）", inputDisplay(), "t");
        c(bad, n, "打一个字之后 is_placeholder=false", inputIsPlaceholder(), false);
        c(bad, n, "N-负对照：非空时【不许】等于说明文字",
                inputDisplay().equals(COMMAND_PLACEHOLDER), false);
        clear();
        c(bad, n, "删空之后说明文字【回来】", inputDisplay(), COMMAND_PLACEHOLDER);
        for (char ch : savedCmdForPlaceholder.toCharArray()) {
            append(ch);
        }
        // 🆕 短输入画出来的就是输入本身（长输入才走"留尾巴"那条路）
        clear();
        for (char ch : "time set day".toCharArray()) {
            append(ch);
        }
        c(bad, n, "短输入画出来==输入本身（不加 … 不加光标）", visibleCommandTail(), "time set day");
        clear();
        c(bad, n, "清空之后画出来的==说明文字", visibleCommandTail(), COMMAND_PLACEHOLDER);
        for (char ch : savedCmdForPlaceholder.toCharArray()) {
            append(ch);
        }
        // N3 负对照：两个面板的「返回」都在最后一行、都整行可点
        //   ⚠️ 2026-10-06：原来写的是 `ROWS - 1`（硬编码到最后一行）。现在设置面板的「返回」
        //      行号由 SETTINGS_BACK_ROW 说了算（它是 4，与 ROWS-1 相等，但**不许**再靠这个巧合）。
        for (int[] modeRow : new int[][]{{MODE_SETTINGS, SETTINGS_BACK_ROW},
                {MODE_COMMAND, COMMAND_BACK_ROW}}) {
            for (int col = 0; col < CELLS; col++) {
                c(bad, n, "N3 负对照：" + modeName(modeRow[0]) + " 第 " + modeRow[1]
                                + " 行（它的「返回」那一行）第 " + col + " 格必须是「返回」",
                        clickAction(modeRow[0], modeRow[1], col), CLICK_CLOSE);
            }
        }
        // 面板没开的时候，任何格子都不许有动作（否则五块菜单板的点击会串到面板上）
        boolean noneClickable = true;
        for (int r = 0; r < ROWS; r++) {
            for (int col = 0; col < CELLS; col++) {
                if (clickAction(MODE_NONE, r, col) != CLICK_NONE) {
                    noneClickable = false;
                }
            }
        }
        c(bad, n, "面板未打开时任何格子都没有动作", noneClickable, true);

        // ---- 几何：不自转、不缩放、从上到下 ----
        final ShanhaiHoloMenuLayout.Board[] rows = rows();
        c(bad, n, "行板数 = 板块数", rows.length, ROWS);
        for (int i = 0; i < rows.length; i++) {
            c(bad, n, "N4 负对照：第 " + i + " 行不自转(rotX)", Float.compare(rows[i].rotX(), 0.0f), 0);
            c(bad, n, "N4 负对照：第 " + i + " 行不自转(rotY)", Float.compare(rows[i].rotY(), 0.0f), 0);
            c(bad, n, "N4 负对照：第 " + i + " 行不缩放", Float.compare(rows[i].scale(), 1.0f), 0);
            c(bad, n, "第 " + i + " 行横向居中(x=0)", Float.compare(rows[i].x(), 0.0f), 0);
        }
        // N5 负对照：index 0 必须在最上（y 最大）
        boolean topFirst = true;
        for (int i = 1; i < rows.length; i++) {
            if (!(rows[i - 1].y() > rows[i].y())) {
                topFirst = false;
            }
        }
        c(bad, n, "N5 负对照：行 0 在最上、y 逐行递减", topFirst, true);

        // ---- 文本模型：设置那几行必须"读得出当前值" ----
        //    🔴 这里【绝不调用 ShanhaiHoloMenuState.resetForTest()】—— 自检会在进世界后的
        //       第一个 tick 被跑到（见 ShanhaiHoloMenuInput#logSelfTests），那会把用户此刻的
        //       距离/档位/开关<b>当场抹回默认</b>。凡是"自检会不会改坏现场"都要在这一层拒掉。
        //       ⇒ 改成与当前值无关的不变量：每一组档位里<b>恰好一档</b>带标记。
        //
        //    🔴 2026-10-06（第三轮收尾）：原来这里有一条 "朝向那一行恰好一档带 ▸ 标记"，
        //       扫的是「行 2」。删掉「朝向」那一行之后行号全挪了 —— 那条判据会去扫<b>别的行</b>
        //       而"恰好一档带 ▸"对任何一档选择都成立 ⇒ <b>它永远绿，却什么也没验</b>
        //       （这正是本工程最忌讳的"判据恒绿"）。现在改成三条各有唯一答案的判据：
        //       ① 布局那一行扫【布局那一行】；② 环绕特效那一行扫它自己；
        //       ③ 「朝向」这两个字以及那三个动作码在<b>整张设置面板</b>上一个都不许剩（扫全表）。
        int layoutMarked = 0;
        for (int col = 1; col <= 3; col++) {
            if (isCurrent(cellText(MODE_SETTINGS, SETTINGS_LAYOUT_ROW, col))) {
                layoutMarked++;
            }
        }
        c(bad, n, "布局那一行恰好一档带 ▸ 标记", layoutMarked, 1);
        int presetMarked = 0;
        for (int col = 0; col < ShanhaiHoloSurroundTuning.PRESET_COUNT; col++) {
            if (isCurrent(cellText(MODE_SETTINGS, SETTINGS_PRESET_ROW, col))) {
                presetMarked++;
            }
        }
        c(bad, n, "环绕特效那一行恰好一档带 ▸ 标记", presetMarked, 1);
        c(bad, n, "N-负对照：真正的标签格【不许】带 ▸ 标记",
                isCurrent(cellText(MODE_SETTINGS, SETTINGS_DIST_ROW, 0)), false);
        c(bad, n, "距离那一行写着当前距离（带「格」字）",
                cellText(MODE_SETTINGS, SETTINGS_DIST_ROW, 2).contains("格"), true);
        // 🔴 2026-10-06：这一条原来写的是"设置面板的标题不为空"（标题框那一版）。
        //    用户拍板的版面是「距离 / 布局 / 环绕特效 / 高级调参 / 返回」五行 —— 行 0 被「距离」
        //    占了，标题框已经没有位置 ⇒ 判据反过来：两个面板都【没有】标题，行 0 也不再是标题。
        c(bad, n, "N9 负对照：设置面板没有标题（行 0 是「距离」，标题框已删）",
                title(MODE_SETTINGS), "");
        c(bad, n, "N9 负对照：设置面板第 0 行的第 1 格是「− 步长」而不是标题",
                cellText(MODE_SETTINGS, SETTINGS_DIST_ROW, 1).startsWith("−"), true);
        // 🔴 N8：整张设置面板扫一遍 —— 「朝向」那一行必须【彻底不存在】。
        //    判据分两层：① 文字层（任何格子里不许出现"朝向/跟随/转向/世界固定"）；
        //                ② 动作码层（那三个码一个都不许被任何一格返回）。
        //    用"扫全表"而不是"看某一行"：把它挪回别的行、或改成别的格子，一样会被抓到。
        c(bad, n, "N8 负对照：设置面板上「朝向」那一行彻底没了（既没有这些字、也没有一格能改朝向）",
                facingLeakScan(MODE_SETTINGS, -1, -1, null, CLICK_NONE), "");
        // N8 的正对照：同一条扫描函数，喂一格"故意混进去的朝向按钮" ⇒ 它必须报出来。
        //    （没有这一条，"扫描结果为空"可能只是因为扫描器从来不报错 —— 本工程血规矩。）
        final String leakCtrl = facingLeakScan(MODE_SETTINGS, SETTINGS_LAYOUT_ROW, 1,
                "▸平滑跟随", CLICK_FACING_FOLLOW);
        c(bad, n, "N8 正对照：同一套扫描喂一格假的「平滑跟随」⇒ 必须报出文字层与动作码层两条",
                leakCtrl.contains("平滑跟随") && leakCtrl.contains("点得到朝向"), true);
        c(bad, n, "朝向档本身还在（{@link #clickName} 仍认得那三个码；命令一个字没动）",
                clickName(CLICK_FACING_FIXED), "朝向=世界固定");
        // N7 负对照（第 ② 条点单）：命令面板的标题框【删掉了】⇒ title() 必须是空的
        c(bad, n, "N7 负对照：命令面板没有标题（那个「命令（管理员权限）」框已经删掉）",
                title(MODE_COMMAND), "");
        c(bad, n, "N7 负对照：命令面板第 0 行一个字都不画（标题行没了）",
                cellText(MODE_COMMAND, 0, 0), "");

        // ---- 🔴 N10（第四轮点单）：高级调参页（第二层）的行/格/翻页模型 ----
        //    用户拍板的版面：每页 4 项 + 第 5 行导航，共 4 页（4/4/4/3），一共那 15 项。
        //    判据全部从 ShanhaiHoloSurroundTuning 的 15 项现算（页数/项数不写死）。
        //    ⚠️ 这里会翻页 ⇒ 进来先存、出去原样放回（自检不改现场）。
        final int savedPage = advPage;
        try {
            c(bad, n, "N10 高级页一共 " + ShanhaiHoloSurroundTuning.PAGE_COUNT + " 页（每页 "
                            + ShanhaiHoloSurroundTuning.PARAMS_PER_PAGE + " 项 + 1 行导航）",
                    advPageCount(), ShanhaiHoloSurroundTuning.PAGE_COUNT);
            int pageSum = 0;
            for (int page = 0; page < advPageCount(); page++) {
                setAdvPage(page);
                final int cnt = advParamCountOnPage();
                pageSum += cnt;
                c(bad, n, "N10 第 " + (page + 1) + " 页画出来的行数 == " + cnt + " 个参数行 + 1 行导航",
                        visibleRowCount(MODE_ADVANCED), cnt + 1);
                for (int r = 0; r < ROWS; r++) {
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行的可见性"
                                    + "（导航行 " + ADV_NAV_ROW + " 永远可见）",
                            rowVisible(MODE_ADVANCED, r), r == ADV_NAV_ROW || r < cnt);
                }
                c(bad, n, "N10 导航-格0 = 上一页",
                        clickAction(MODE_ADVANCED, ADV_NAV_ROW, ADV_CELL_PREV), CLICK_ADV_PREV);
                c(bad, n, "N10 导航-格1（类别·第 n/N 页）不可点（它是读数，不是按钮）",
                        clickAction(MODE_ADVANCED, ADV_NAV_ROW, ADV_CELL_INFO), CLICK_NONE);
                c(bad, n, "N10 导航-格2 = 下一页",
                        clickAction(MODE_ADVANCED, ADV_NAV_ROW, ADV_CELL_NEXT), CLICK_ADV_NEXT);
                c(bad, n, "N10 导航-格3 = 回第一层（不是关面板）",
                        clickAction(MODE_ADVANCED, ADV_NAV_ROW, ADV_CELL_BACK), CLICK_ADV_BACK);
                c(bad, n, "N10 导航-格3 上写着「返回」",
                        cellText(MODE_ADVANCED, ADV_NAV_ROW, ADV_CELL_BACK), "返回");
                c(bad, n, "N10 导航-格1 写着「类别·第 n/N 页」",
                        cellText(MODE_ADVANCED, ADV_NAV_ROW, ADV_CELL_INFO), advNavInfoText());
                c(bad, n, "N10 导航-格1 里带着「第 " + (page + 1) + "/" + advPageCount() + "页」",
                        cellText(MODE_ADVANCED, ADV_NAV_ROW, ADV_CELL_INFO)
                                .contains("第" + (page + 1) + "/" + advPageCount() + "页"), true);
                for (int r = 0; r < cnt; r++) {
                    final int idx = advParamIndex(r);
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行 ⇒ 第 " + (idx + 1) + " 项",
                            idx, page * ShanhaiHoloSurroundTuning.PARAMS_PER_PAGE + r);
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行格0 写着参数名"
                                    + "（第 " + (idx + 1) + " 项）",
                            cellText(MODE_ADVANCED, r, 0),
                            ShanhaiHoloSurroundTuning.PARAM_NAME[idx]);
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行格2 写着当前值（现读）",
                            cellText(MODE_ADVANCED, r, ADV_CELL_VALUE),
                            ShanhaiHoloSurroundTuning.paramText(idx));
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行格1 = 这一项减一档",
                            clickAction(MODE_ADVANCED, r, ADV_CELL_MINUS), CLICK_PARAM_DOWN);
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行格3 = 这一项加一档",
                            clickAction(MODE_ADVANCED, r, ADV_CELL_PLUS), CLICK_PARAM_UP);
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行格0/格2 不可点（名称与值是只读的）",
                            clickAction(MODE_ADVANCED, r, 0) == CLICK_NONE
                                    && clickAction(MODE_ADVANCED, r, ADV_CELL_VALUE) == CLICK_NONE,
                            true);
                }
                for (int r = cnt; r < ADV_NAV_ROW; r++) {
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行（本页没有项）一个字都不画",
                            cellText(MODE_ADVANCED, r, 0), "");
                    c(bad, n, "N10 第 " + (page + 1) + " 页第 " + r + " 行（本页没有项）一格都不可点",
                            clickAction(MODE_ADVANCED, r, ADV_CELL_MINUS), CLICK_NONE);
                }
            }
            c(bad, n, "N10 四页加起来恰好是那 " + ShanhaiHoloSurroundTuning.PARAM_COUNT + " 项（4+4+4+3）",
                    pageSum, ShanhaiHoloSurroundTuning.PARAM_COUNT);
            setAdvPage(0);
            flipAdvPage(-1);
            c(bad, n, "N10 第 1 页再往回翻 ⇒ 停在第 1 页（不循环，免得不知道自己在哪页）",
                    advPage(), 0);
            setAdvPage(advPageCount() - 1);
            flipAdvPage(+1);
            c(bad, n, "N10 最后一页再往下翻 ⇒ 停在最后一页", advPage(), advPageCount() - 1);
            setAdvPage(99);
            c(bad, n, "N10 setAdvPage(99) 被钳到最后一页", advPage(), advPageCount() - 1);
            setAdvPage(-99);
            c(bad, n, "N10 setAdvPage(−99) 被钳到第 1 页", advPage(), 0);
        } finally {
            advPage = savedPage;
        }

        // ---- 命令文本：追加/退格/清空/截断 ----
        //    同样先存后恢复：自检不许留下"用户没敲过的命令"。
        final String savedCommand = command();
        final String savedResult = lastResult();
        setCommandRaw("");
        for (char ch : "give @s stone".toCharArray()) {
            append(ch);
        }
        c(bad, n, "命令文本按字符追加", command(), "give @s stone");
        backspace();
        c(bad, n, "退格删一个字符", command(), "give @s ston");
        clear();
        c(bad, n, "清空之后是空的", commandEmpty(), true);
        for (int i = 0; i < MAX_COMMAND_CHARS + 50; i++) {
            append('a');
        }
        c(bad, n, "超出上限就不收（长度夹在 " + MAX_COMMAND_CHARS + "）",
                command().length(), MAX_COMMAND_CHARS);
        c(bad, n, "超长命令画出来的是【尾巴】（光标看得见）",
                visibleCommandTail().startsWith("…"), true);
        tailClipSelfCheck(bad, n);
        clear();
        c(bad, n, "清空之后画出来的是说明文字",
                visibleCommandTail(), COMMAND_PLACEHOLDER);
        // 复原现场
        setCommandRaw(savedCommand);
        lastResult = savedResult;

        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b);
    }

    /** 只在自检里用：把命令缓冲整条换成给定字符串（复原现场用）。 */
    private static void setCommandRaw(String text) {
        COMMAND.setLength(0);
        if (text != null) {
            COMMAND.append(text);
        }
        dirty = true;
    }

    /**
     * 扫一遍某个面板，找「朝向」那一行的<b>任何</b>残迹（文字层 + 动作码层）。
     *
     * @param fakeRow/fakeCell/fakeText/fakeAction 负对照用：把<b>这一格</b>换成给定内容再扫。
     *        {@code fakeRow < 0} = 不注入（生产扫描就走这个）。
     * @return 找到的残迹（空串 = 一点都没有）
     */
    private static String facingLeakScan(int mode, int fakeRow, int fakeCell,
                                         String fakeText, int fakeAction) {
        final StringBuilder out = new StringBuilder();
        for (int r = 0; r < ROWS; r++) {
            for (int col = 0; col < CELLS; col++) {
                final boolean fake = r == fakeRow && col == fakeCell;
                final String t = fake && fakeText != null ? fakeText : cellText(mode, r, col);
                final int a = fake ? fakeAction : clickAction(mode, r, col);
                if (t.contains("朝向") || t.contains("跟随") || t.contains("转向")
                        || t.contains("世界固定")) {
                    out.append('(').append(r).append(',').append(col).append(")=「")
                            .append(t).append("」 ");
                }
                if (a == CLICK_FACING_FOLLOW || a == CLICK_FACING_SNAP
                        || a == CLICK_FACING_FIXED) {
                    out.append("点得到朝向(").append(r).append(',').append(col).append(") ");
                }
            }
        }
        return out.toString();
    }

    /** 截断两个函数的边界（含 2 条负对照）。 */
    private static void tailClipSelfCheck(StringBuilder bad, int[] n) {
        c(bad, n, "tail 太短就原样", tail("abc", 5), "abc");
        c(bad, n, "tail 太长就留尾巴", tail("abcdef", 3), "def");
        c(bad, n, "clip 太短就原样", clip("abc", 5), "abc");
        c(bad, n, "clip 太长就留头 + 省略号", clip("abcdef", 3), "abc…");
        c(bad, n, "N-负对照：tail 与 clip 在同一个输入上必须【不同】（证明它们不是同一个函数）",
                tail("abcdef", 3).equals(clip("abcdef", 3)), false);
    }

    /** 一行自检读数（写进日志；失败时由调用方打 ERROR）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_panel_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok()
                + (r.ok() ? "（判据：" + ROWS + " 行 × " + CELLS + " 格 = 三个面板共用同一块网格；"
                + "含 10 组负对照：格子边界 / 标签与当前值不可点 / 两个面板都有「返回」 / "
                + "行板不自转不缩放 / 行序从上到下 / 命令面板上已无「执行」「清空」两行 / "
                + "命令面板只剩两个框 / 输入框只占一行 / "
                + "设置面板上「朝向」那一行彻底没了（含一条「喂一格假按钮」的正对照） / "
                + "设置面板没有标题；"
                + "另含两个面板的版面判据：设置面板 5 行全用满（距离/布局/环绕特效/高级调参/返回）+ "
                + "环绕特效四格=四档 + 高级页 4 页（4/4/4/3）逐行逐格对到那 15 项）"
                : (" FAILED:" + r.bad()));
    }

    /** 自检里一格"应当等于"。 */
    private static void c(StringBuilder bad, int[] n, String what, int actual, int expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到 ").append(actual)
                    .append("，应当是 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, float actual, float expected) {
        n[1]++;
        if (Float.compare(actual, expected) == 0) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到 ").append(actual)
                    .append("，应当是 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, boolean actual, boolean expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到 ").append(actual)
                    .append("，应当是 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, String actual, String expected) {
        n[1]++;
        if (expected == null ? actual == null : expected.equals(actual)) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到「").append(actual)
                    .append("」，应当是「").append(expected).append("」;");
        }
    }

    /** 面板是不是"能开"（板表说了算；这里只做一次口径转发，避免两处各判一次）。 */
    public static boolean isPanelBoard(int index) {
        final int a = ShanhaiHoloMenuBoards.actionOf(index);
        return a == ShanhaiHoloMenuBoards.ACTION_HOLO_SETTINGS
                || a == ShanhaiHoloMenuBoards.ACTION_COMMAND;
    }

    /** 动作码 ⇒ 面板模式（不是面板板就返回 {@link #MODE_NONE}）。 */
    public static int modeOfAction(int action) {
        if (action == ShanhaiHoloMenuBoards.ACTION_HOLO_SETTINGS) {
            return MODE_SETTINGS;
        }
        if (action == ShanhaiHoloMenuBoards.ACTION_COMMAND) {
            return MODE_COMMAND;
        }
        return MODE_NONE;
    }
}
