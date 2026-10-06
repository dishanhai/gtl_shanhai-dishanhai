package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 山海配方编辑器 · <b>大工作区面板</b>（第二刀的主体）。
 *
 * <h2>1. 三段式（用户点单的流程，逐条对应）</h2>
 * <pre>
 *   第一屏  §7 类型列表       —— 打开就是这一屏（他明确否掉了"拿物品进"那个入口）
 *   第二屏  §7 该类型的配方   —— 点一行进第三屏
 *   第三屏  §7 编辑           —— 24 个 IO 格子 ＋ 耗时/耗能/电压 ＋ 保存/删除/恢复
 * </pre>
 * 三屏共用同一批控件：<b>12 个行按钮 ＋ 12 个行标签</b>在列表屏用，
 * <b>24 个格子 ＋ 数字控件</b>在编辑屏用，靠 {@code setVisible} 切换
 * （LDLib 的控件表在 UI 建好之后不能再改，所以只能"全都建好、按屏显隐"）。
 *
 * <h2>2. 🔴 尺寸是按"别盖住左右两列 JEI"定的</h2>
 * 用户的原话：「最左侧（jei的收藏夹）和最右侧（jei）不要动，中间这一块就是我们的工作区」。
 * JEI 的收藏夹在最左（约 32 px）、配方列表在最右（约 170~200 px），本面板取
 * <b>{@link #W}×{@link #H} = 256×232</b> 并<b>把可操作的控件全压在左右各 60 px 之内</b>
 * （输入 3 列在 x=6..60、输出 3 列在 x=176..230），中间那一条留给标题与数字。
 * ⚠️ 屏幕太窄时（GUI 缩放 4 且分辨率不高）仍然可能被 JEI 压住右边一点 —— 这一条<b>只能他看</b>，
 * 见交付报告里的验收清单。
 *
 * <h2>3. 🔴 这条链上三个"不报错但会坏事"的坑，都在这里绕开了</h2>
 * <ol>
 *   <li><b>数字输入框必须用带 {@code Position} 的构造器</b>：{@code NumberInputWidget.buildUI()}
 *       用 {@code n = clamp(width/5, 15, 40)}、{@code n2 = width - 2n - 4} 决定内部那个真正的
 *       {@code TextFieldWidget} 有多宽；{@code (int,int,int,int,…)} 那个重载在窄宽度下会把输入框
 *       压到 8 px，而 LDLib 的 {@code TextFieldWidget.mouseClicked} <b>唯一</b>的焦点来源就是
 *       "鼠标在不在这个矩形里" ⇒ 点不到 = 永远拿不到焦点 = 一个字符都不吃。
 *       带 {@code Position} 的重载内部固定 {@code new Size(100,20)} ⇒ 输入框 56 px。</li>
 *   <li><b>{@code ButtonWidget} 没有 {@code setText}</b> ⇒ 既有写法是"按钮铺满 ＋ 上面盖一个
 *       {@code LabelWidget}"（留档 {@code ParallelOverrideConfigurator.java:278-282}）。本面板每一支按钮都照这个来。</li>
 *   <li><b>读数用的 {@code LabelWidget} 【不】调 {@code setClientSideWidget()}</b>：本面板要的是
 *       "服务端说了算"，文字由服务端那份 session 产出、经 {@code writeInitialData /
 *       detectAndSendChanges} 推下去；客户端那份 session 的所有 {@code xxxText()} 返回空串
 *       （空串不会被画出来，画的是推下来的那份）。</li>
 * </ol>
 *
 * <h2>4. ⚠️ 如实交代：这一屏<b>没有</b>被真机渲染验过</h2>
 * 红线禁止启动客户端 ⇒ 本类只被"编译通过 ＋ 服务端能建出 UI"覆盖到。能机器验的那部分全在
 * {@link ShanhaiRecipeEditorWorkspace} / {@link ShanhaiIoTable}（自检逐条打读数）；
 * 本类只负责"把 session 的读数贴到控件上、把点击与拖动转回 session"。
 */
public final class ShanhaiRecipeEditorPanel extends WidgetGroup implements IGhostIngredientTarget {

    /**
     * 面板尺寸。🔴 2026-10-05 用户点单（B4）：「B4面版可能不够大 …有时需要满足<b>非常巨大的输入和输出格</b>，
     * 我<b>未来还打算新增组件</b>，估计放不下」。
     *
     * <p>老尺寸 256×232 ⇒ 现在 <b>{@link #W}×{@link #H} = 340×300</b>（面积 ×1.7），
     * 其中格子区是 <b>8 列 × 4 行 × 左右两栏 = 每页 64 格</b>，超出的用<b>分页</b>翻
     * （每栏一对 ◀ ▶ 按钮 + 页号；见 {@link ShanhaiRecipeEditorWorkspace#CELLS_PER_PAGE}）。
     *
     * <h4>🔴 为什么不是"直接铺满整个屏幕"</h4>
     * 用户 2026-10-04 原话：「<b>最左侧（jei 的收藏夹）和最右侧（jei）不要动，中间这一块就是我们的工作区</b>」
     * ⇒ 面板<b>必须</b>小到能同时看见左右两列 JEI。JEI 右侧列表约 180（逻辑）px、左侧收藏夹约 32px，
     * 所以 340 宽是在"最小可用 GUI 宽度"下仍然放得下的值。
     *
     * <h4>⚠️ 客户端会按自己的窗口再把尺寸夹一次（两侧尺寸可以不同，理由如下）</h4>
     * {@code ShanhaiRecipeEditorFactory#buildUI} 在客户端用
     * {@code min(上述尺寸, 可用屏幕 − JEI 余量)}。这<b>不会</b>让两侧的控件树对不上：
     * 控件<b>数量与构造顺序完全相同</b>（LDLib 的动作通道按控件 id 走），
     * 只有坐标/宽度不同，而坐标只在客户端参与绘制与命中判定。这是本面板<b>唯一</b>允许两侧不同步的东西，
     * 换来的是"GUI 缩放 4 时面板不会盖住 JEI"。
     */
    public static final int W = 340;
    public static final int H = 300;

    private static final int ROWS = ShanhaiRecipeEditorWorkspace.ROWS;
    private static final int ROW_H = 12;
    private static final int ROW_STEP = 13;
    private static final int ROW_Y0 = 40;
    private static final int CELL_STEP = 18;
    private static final int GRID_COLS = 8;
    private static final int GRID_ROWS = ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE / GRID_COLS;   // 4
    private static final int IN_X = 6;
    private static final int OUT_X = 176;
    private static final int GRID_Y0 = 50;
    private static final int PAGE_Y = GRID_Y0 + GRID_ROWS * CELL_STEP + 2;                          // 124

    // ── 🆕 2026-10-05 第 7 轮：版面重排（因为第一屏底部要装下"物品框 ＋ 3 个按钮 ＋ 搜索框"）──
    //
    // 用户当时的原话（逐字）：
    //   「第一：在这下面加一个允许我放任意物品的框（我可以把物品通过jei拖入），下面有3个按钮：
    //     1：获取途径，2：作为物品的用处，3：作为机器的用处」
    //   「第二：文本搜索框，可输入中英文，直接匹配配方id和配方种类名称/id」
    // 并当场确认「这下面 = 第一屏」「第一屏那句"不需要大改"＝别动原有列表与翻页，新东西是【加】上去的」。
    //
    // ⇒ 原有 12 行类型列表（40..196）**一个像素没动**；新东西全部加在它下面：
    //     199..217  物品槽 ＋ 搜索框 ＋ 搜索/清除
    //     220..238  获取途径 / 作为物品的用处 / 作为机器的用处
    //     241/251   两行查询说明
    //     260       原有底部状态行（从 286 上移，因为下面要放公共翻页行）
    //     276..294  公共翻页行（四种屏共用，见 NAV_* ）
    // 这套 y 值加起来 Σ ≤ 300，逐条算过（红线：他报过"重叠/看不清"，不许堆到最后发现挤爆）。
    private static final int QSLOT_Y = 199;                              // 物品槽那一行
    private static final int QBTN_Y = 220;                               // 三个查询按钮那一行
    private static final int QNOTE_Y = 241;                              // 查询结论第 1 行
    private static final int QNOTE2_Y = 251;                             // 查询结论第 2 行
    private static final int ST1LIST_Y = 260;                            // 列表屏的底部状态行

    /** 卡片屏那一排机器图标（照 JEI 的催化剂那一排）。 */
    private static final int TAB_Y = 38;
    private static final int TABINFO_Y = 62;
    /** 卡片区：4 张 × 50 = 200 ⇒ 74..274。 */
    private static final int CARD_Y0 = 74;
    private static final int CARD_STEP = ShanhaiRecipeCardWidget.CH;

    /** 公共翻页行（四种屏共用一行，避免出现"两排按钮"）。 */
    private static final int NAV_Y = 276;
    private static final int NAV_H = 18;

    // ── 🆕 2026-10-05（B 组：额外条件）条件页的坐标（相对它自己那个 WidgetGroup）──
    //    为什么单独一个覆盖页而不是塞进编辑屏：编辑屏 y 已经排到 292，
    //    而"列出 N 条 + 一个加号 + 一套编辑控件"至少要 250px 高。
    //    做法与"中键改数量"那个小窗一致（同一层覆盖组，靠显隐切换）。
    private static final int C_W = W - 8;
    private static final int C_H = 258;
    private static final int C_ROW_Y0 = 28;
    private static final int C_ROW_STEP = 13;
    private static final int C_ROW_H = 12;
    private static final int C_ADD_Y = 112;
    private static final int C_TYPE_Y = 134;
    private static final int C_CLEAN_Y = 156;
    private static final int C_MOD_Y = 178;
    private static final int C_NOTE_Y = 240;

    // ── 第三屏（编辑屏）的坐标：🔴 第 7 轮【一个数都没改】（用户原话「图5的编辑页面也不需要大改」）──
    //
    // 历史（2026-10-05 上一轮的重排，逐字留档）：修掉"「电压等级」这个标签和「电流(A)」输入框重叠"。
    //   旧排版实测：耗时输入框 62..162、而「电压等级」标签落在 136 ⇒ 重叠区 x=136..162；
    //   电压下拉 136..276 又压住电流那一列的下半截。
    // ⇒ 当时改成：数值行只放两个输入框（耗时 / 电流）、「电压等级」整行搬到底部做成按钮外观。
    private static final int CAP_Y = 142;                                // 上限读数
    private static final int NUM_Y = 156;                                // 耗时/电压/电流/耗能 读数
    private static final int INP_Y = 174;                                // 两个输入框那一行
    private static final int BTN_Y = 196;                                // 按钮那一行
    private static final int LEG_Y = 220;                                // 边框含义图例
    private static final int HOV_Y = 232;                                // "指到哪一格"那一行
    private static final int ST1_Y = 246;                                // 状态 1
    /** 🆕 第 7 轮（#5）：真实耗时那一行（只读），插在 状态1 与 状态2 之间。 */
    private static final int REAL_Y = 256;
    private static final int ST2_Y = 266;                                // 状态 2
    private static final int TIER_Y = 276;                               // 电压等级（必须最后 add，见 §z-order）

    /** 🆕 阶段 2：非 GT 输入侧那一组控件（标题/说明 ＋ 3×3 网格 ＋ 产物格）。 */
    private final List<Widget> vanillaOnly = new ArrayList<>();
    /**
     * 🆕 第 12 刀：<b>3×3 网格版的那 9 个格子 —— 有序与无序共用同一套</b>。
     *
     * <p>用户拍板（原话）：「如果我想要2个红蘑菇，那我直接在网格里面再补一个不就行了吗……
     * 主要是这样玩家好操作你懂吧，不然太麻烦了」
     * ⇒ 无序合成也画这张 3×3，"位置无所谓"；两屏手势逐字相同（拖入／右键／中键）。
     * ⇒ 原来那 9 个"5 个一行"的列表格子（{@code vanillaCellsList}）与「+ 加一格 / − 删一格」
     * 两颗按钮<b>整个删掉</b>（用户点名删）。
     */
    private final ShanhaiIOWidget[] vanillaCellsGrid =
            new ShanhaiIOWidget[ShanhaiVanillaRecipeShape.GRID_CELLS];
    /**
     * 🆕 第 12 刀：<b>非 GT 屏的产物格</b>。
     *
     * <p>🔴 为什么必须单独建一个：第 11 刀为了修"3×3 与 GT 格子重叠"，把 GT 那 32 个格子
     * 在非 GT 屏上<b>整体</b>藏掉了 —— 而<b>产物格也在那 32 个里</b>
     * ⇒ 非 GT 屏上产物区一个格子都没有（用户截图：「连输出都没有」）。
     * 现在输入侧归我们的 3×3，产物侧就给这一格，位置与原 GT 输出第 0 格完全相同
     * （{@code OUT_X, GRID_Y0}）。
     */
    private ShanhaiIOWidget vanillaOutCell;

    /**
     * 🆕 阶段 2：非 GT 输入侧的版面常量。
     *
     * <h4>位置是怎么定的（没有开客户端，所以只按"不与既有控件重叠"推）</h4>
     * GT 那 32 个输入格占的是 {@code x=6..150 / y=50..122}（8 列 × 4 行 × 18px）。
     * 非 GT 屏上那 32 个格子是<b>整体藏掉</b>的（见 {@code refreshVisibility}）
     * ⇒ 整块区域空出来给我们用：
     * <pre>
     *   3×3 网格   x=6,26,46   y=50,70,90   （格 18px ＋ 空隙 2px）
     *   无序列的列表 x=6,26,46,66,86  y=52,72 （5 个一行，最多 9 个 ＝ 两行）
     *   按钮/文字   x=90 起（右侧那块 90..168 是空的）
     * </pre>
     * ⚠️ 输出栏从 {@code OUT_X=176} 起，所以 x 一律不超过 170；按钮宽度定死 30/60。
     */
    private static final int VAN_X = 6;
    private static final int VAN_Y = 50;
    private static final int VAN_STEP = 20;
    private static final int VAN_LIST_Y = 52;
    private static final int VAN_LIST_PER_ROW = 5;
    private static final int VAN_TXT_X = 90;
    private static final int VAN_BTN_W = 30;
    private static final int VAN_BTN_W2 = 60;

    private final ShanhaiRecipeEditorWorkspace session;
    private final boolean clientSide;

    /**
     * 各屏各自的控件清单（LDLib 的控件表在 UI 建好之后不能再改 ⇒ 全部先建好、按屏显隐）。
     *
     * <p>🔴 第 7 轮从"两分法（listOnly / editOnly）"扩成<b>五组</b>，因为屏从 3 个变成了 4 个
     * （第一屏类型列表 / 第二屏卡片 / 查询结果屏 / 第三屏编辑），
     * 而"第二屏"与"查询结果屏"共用同一批卡片控件。
     */
    private final List<Widget> typeRowWidgets = new ArrayList<>();   // 第一屏那 12 行
    private final List<Widget> queryOnly = new ArrayList<>();        // 第一屏底部那一条查询栏
    private final List<Widget> navWidgets = new ArrayList<>();       // 公共翻页行
    private final List<Widget> newRecipeOnly = new ArrayList<>();    // 「新建配方」（只在第二屏）
    private final List<Widget> cardStageWidgets = new ArrayList<>(); // 那一排图标 ＋ 卡片 ＋ 信息行
    private final List<Widget> editOnly = new ArrayList<>();
    /**
     * 🆕 2026-10-05（工作台 / 原版配方）：<b>只有 GT 配方才有意义的控件</b>
     * （电压等级下拉 / 电流输入 / 条件入口）。
     *
     * <p>编辑原版配方时这些控件必须<b>藏起来</b> —— 它们改的东西（EU/t、GT 的额外条件）
     * 在原版配方上根本不存在；留着会让人以为"改了会生效"（本工程红线：活的界面上不许放假数据）。
     * 这些控件同时也在 {@code editOnly} 里，所以 {@code refreshVisibility} 里
     * <b>必须把这一组放在 editOnly 之后</b>刷。
     */
    private final List<Widget> gtOnly = new ArrayList<>();

    /**
     * 🆕 同上：<b>只有"有时间这个字段"的配方才有意义</b>的控件（现在只有「耗时/烧炼时间」那一行）。
     * 原版的有形状合成没有时间字段 ⇒ 那一行必须消失（而不是显示一个 0）。
     */
    private final List<Widget> chronoOnly = new ArrayList<>();
    /** 列表屏的底部状态行（四种屏里除编辑屏都显示）。 */
    private final List<Widget> statusBar = new ArrayList<>();

    /** 🆕 第 7 轮：卡片屏的 4 张卡（下标 = 本页第几张）。 */
    private final ShanhaiRecipeCardWidget[] cards =
            new ShanhaiRecipeCardWidget[ShanhaiRecipeEditorWorkspace.CARDS_PER_PAGE];

    /** 🆕 第 7 轮：第一屏那个物品槽（面板要把 JEI 落点转发给它）。 */
    private ShanhaiQuerySlotWidget querySlot;
    /** 🆕 第 7 轮：文本搜索框（服务端 tick 时要用它做防抖判据）。 */
    private com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget searchField;
    /** 🆕 第 7 轮：顶部那一排机器图标。 */
    private ShanhaiRecipeTabBarWidget tabBar;

    /** 输入栏的格子控件（下标 0..CELLS_PER_PAGE-1）＋ 输出栏的（CELLS_PER_PAGE..2*CELLS_PER_PAGE-1）。 */
    private final ShanhaiIOWidget[] cells = new ShanhaiIOWidget[ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE * 2];

    private WidgetGroup popup;
    private WidgetGroup condGroup;
    private com.lowdragmc.lowdraglib.gui.widget.SelectorWidget tierSelector;
    private StatePump pump;

    // 🆕 第 5 轮：条件页里那三个控件的句柄 —— 「档位」那一行要按【类型】显隐，
    //    所以必须在每 tick 那次 refresh 里拿得到它们（控件表建好之后不能再增删控件）。
    private com.lowdragmc.lowdraglib.gui.widget.SelectorWidget condValueSel;
    private Widget condValueLabel;
    private Widget condNoValueHint;
    private Widget condReverseBtn;

    /** 🆕 "指到哪一格就在这一行显示它的 中文名(id)"（由 24 个格子写进来，客户端渲染时）。 */
    private String hoverLine = "";

    public ShanhaiRecipeEditorPanel(ShanhaiRecipeEditorHolder holder, Player player) {
        super(0, 0, W, H);
        this.clientSide = player.level().isClientSide();
        // 客户端那份 session 的 server 是 null（故意）：客户端没有配方表，也不许凭空显示数字。
        this.session = new ShanhaiRecipeEditorWorkspace(clientSide ? null : player.getServer(), player);
        if (!clientSide) {
            session.reloadTypes();
            if (holder.typeId != null) {
                session.selectType(holder.typeId);
                if (holder.recipeId != null) {
                    session.selectRecipe(holder.recipeId);
                }
            }
        }

        setBackground(GuiTextures.BACKGROUND);

        buildHeader();
        buildListStage();
        buildCardStage();
        buildQueryBar();
        buildNavRow();
        buildEditStage();
        // 🆕 阶段 2：非 GT 输入侧那一套（3×3 网格 / 无序列表 / 单格）。
        //    ⚠️ 必须排在 buildTierSelector() 【之前】—— 电压下拉必须是【最后一个】子控件
        //       （LDLib 的 mouseClicked 倒序遍历，见 buildTierSelector 的类注释）。
        buildVanillaStage();
        buildPopup();
        // 🆕 B 组：条件页（覆盖组；显隐由 refreshVisibility 管）
        buildConditionsStage();
        // 🔴 电压下拉【必须最后 add】：LDLib 的 WidgetGroup.mouseClicked 是【倒序】遍历子控件的
        //    （字节码实测：i 从 widgets.size()-1 递减）。下拉展开时它画在别的控件上面，
        //    如果它不是最后一个子控件，点在下拉项上的那一下会先被"删除这条"那颗按钮吃掉 ——
        //    这正是用户 图4 报的「我选个电压都能点到删除这条」。
        //    放在最后 ⇒ 它先拿到点击 ⇒ 吃掉 ⇒ 不穿透。
        //    ⚠️ 条件页打开时会把电压下拉藏起来（refreshVisibility）⇒ 两者不会抢点击。
        buildTierSelector();
        buildPump();
    }

    /** 供自检/命令直接拿到底层 session（面板壳之外那条路走的是同一个对象）。 */
    public ShanhaiRecipeEditorWorkspace session() {
        return session;
    }

    /**
     * 🔴 <b>JEI 拖动落点的转发（少了这一层，24 个格子一个都收不到拖进来的东西）</b>
     *
     * <h4>为什么必须由"面板"这一层实现，而不是让格子自己实现就够了</h4>
     * LDLib 那边拿落点的唯一入口是 {@code ModularUIJeiHandler.getTargetsTyped}，而它只做一件事
     * （javap -c 实测的调用链）：
     * <pre>
     *   modularUI.mainGroup.getPhantomTargets(ingredient)
     * </pre>
     * 而 {@code WidgetGroup.getPhantomTargets} 的实现是：
     * <pre>
     *   if (!isVisible()) return emptyList();
     *   for (Widget w : widgets)                       ← 只看【直接子控件】
     *       if (w.isVisible() &amp;&amp; w instanceof IGhostIngredientTarget)
     *           out.addAll(w.getPhantomTargets(ingredient));
     * </pre>
     * ⇒ <b>它【不】递归进嵌套的 WidgetGroup</b>。而我们的 24 个格子挂在 {@code ShanhaiRecipeEditorPanel}
     * 里，面板本身又是 {@code mainGroup} 的直接子控件 —— 面板若不实现这个接口并往下转发，
     * 拖动就会"一个落点都找不到"（而且<b>不报错、不写日志</b>，正是本工程最怕的那一类失败）。
     */
    @Override
    public java.util.List<com.lowdragmc.lowdraglib.gui.ingredient.Target> getPhantomTargets(Object ingredient) {
        if (session == null || cells == null) {
            return java.util.List.of();
        }
        // 🔴 2026-10-05：**不再**按 stageIsEdit() 提前返回 —— 理由与读数见
        //    ShanhaiIOWidget#getPhantomTargets 与 ShanhaiIOWidget#normalize 的注释：
        //    JEI 在 drag 起手时拿不到任何 target 就直接 return false（拖动根本不开始），
        //    所以落点必须常驻；"这一屏能不能收"由 accept() 负责并记账。
        final java.util.List<com.lowdragmc.lowdraglib.gui.ingredient.Target> out = new ArrayList<>();
        for (ShanhaiIOWidget cell : cells) {
            // 🔴 第 12 刀：**不可见的格子不许当落点**。GT 那 32 个格子在第一屏 / 非 GT 屏上是被藏起来的，
            //    但它们仍然在这个数组里 ⇒ 不加 `isVisible()` 的话，它们会在同样的坐标上抢走
            //    本该落到我们 3×3 网格上的那一次拖放（用户看到的是"拖进去没反应"，而且不报错）。
            if (cell != null && cell.isVisible()) {
                out.addAll(cell.getPhantomTargets(ingredient));
            }
        }
        // 🆕 阶段 2 / 第 12 刀：非 GT 的 3×3 网格 ＋ 产物格也是落点。少了这一轮转发，
        //    用户看到的就是「网格怎么拖都拖不进去」（而且不报错、不写日志）。
        for (ShanhaiIOWidget cell : vanillaCellsGrid) {
            if (cell != null && cell.isVisible()) {
                out.addAll(cell.getPhantomTargets(ingredient));
            }
        }
        if (vanillaOutCell != null && vanillaOutCell.isVisible()) {
            out.addAll(vanillaOutCell.getPhantomTargets(ingredient));
        }
        // 🆕 第 7 轮：第一屏那个"任意物品框"也是落点。少转发这一个，
        //    用户看到的就是"第一屏那个框怎么拖都拖不进去"（而且不报错）。
        if (querySlot != null) {
            out.addAll(querySlot.getPhantomTargets(ingredient));
        }
        ShanhaiDragStats.phantomCall(out.size());
        return out;
    }

    // ------------------------------------------------------------------ 头部

    private void buildHeader() {
        addWidget(new LabelWidget(4, 4, ShanhaiLdlText.esc("§b§l山海配方编辑器 §8(服务端权威)")));
        addWidget(new LabelWidget(4, 15, ShanhaiLdlText.sup((Supplier<String>) session::headerText)));
        addWidget(new LabelWidget(4, 26, ShanhaiLdlText.sup((Supplier<String>) session::hintText)));
    }

    // ------------------------------------------------------------------ 列表两屏

    /**
     * 第一屏的 12 行类型列表。
     *
     * <p>🔴 第 7 轮：这 12 行的坐标<b>一个像素没动</b>（用户原话「主页面还是不需要大改，
     * 就改我说的那几个小点就行了」）。新加的东西全在它下面，见 {@link #buildQueryBar()}。
     */
    private void buildListStage() {
        for (int i = 0; i < ROWS; i++) {
            final int row = i;
            final Widget button = new ButtonWidget(4, ROW_Y0 + row * ROW_STEP, W - 8, ROW_H,
                    GuiTextures.BUTTON, cd -> onRow(row));
            final Widget label = new LabelWidget(8, ROW_Y0 + row * ROW_STEP + 2,
                    ShanhaiLdlText.sup((Supplier<String>) () -> session.rowText(row)));
            addWidget(button);
            addWidget(label);
            typeRowWidgets.add(button);
            typeRowWidgets.add(label);
        }
    }

    /**
     * 🆕 第 7 轮（功能 A 的上半）：第一屏底部那个 <b>任意物品框</b> ＋ <b>文本搜索框</b>。
     *
     * <p>用户原话：「在这下面加一个允许我放任意物品的框（我可以把物品通过jei拖入）」／
     * 「文本搜索框，可输入中英文，直接匹配配方id和配方种类名称/id」。
     */
    private void buildQueryBar() {
        // ── 物品槽（幽灵槽：从 JEI 拖入，不消耗）──
        final ShanhaiQuerySlotWidget slot = new ShanhaiQuerySlotWidget(session, 4, QSLOT_Y);
        addWidget(slot);
        queryOnly.add(slot);
        this.querySlot = slot;
        final Widget slotLabel = new LabelWidget(24, QSLOT_Y + 5, ShanhaiLdlText.sup(
                (Supplier<String>) session::querySlotText));
        addWidget(slotLabel);
        queryOnly.add(slotLabel);

        // ── 文本搜索框 ──
        //    🔴🔴 2026-10-05（用户报「文本搜索框输入什么好像什么都搜索不到」）：
        //    **根因就是这里原来那一句 `search.setClientSideWidget()`** —— 已删。
        //
        //    证据（javap -c，LDLib 1.0.33）：
        //      public Widget setClientSideWidget() { this.isClientSideWidget = true; return this; }
        //      protected final void writeClientAction(int id, Consumer<FriendlyByteBuf> w) {
        //          if (uiAccess != null && !isClientSideWidget)   // ← 这个 !isClientSideWidget 就是凶手
        //              uiAccess.writeClientAction(this, id, w);
        //      }                                                  //   false 那一支：**什么也不做**
        //    而 TextFieldWidget.onTextChanged 把"用户刚敲的字"送回服务端**只有**这一条路：
        //      writeClientAction(1, buf -> buf.writeUtf(valid))
        //    ⇒ 设了 clientSideWidget 之后，那句话在【客户端被静默丢弃】⇒
        //      **服务端永远收不到关键词**（服务端 session.searchText 恒为空串）
        //      ⇒ byText 收到空串 ⇒ 每次都报「请输入关键词」⇒ 用户看到的就是"搜什么都搜不到"。
        //    （当时设它的理由是"怕客户端每帧 onPositionUpdate 把刚敲的字抹掉"——
        //      但 onTextChanged 在【那之前】就已经无条件 setCurrentString(valid) 了
        //      （字节码偏移 38，在 `if (isClientSideWidget)` 那颗 44 之前）
        //      ⇒ 不设它，本地 currentString 一样是同步的，字不会被抹掉。
        //      ⇒ 那次取舍选错了：它换来的是一整条功能失效。）
        final com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget search =
                new SearchFieldWidget(
                        106, QSLOT_Y, 150, 18,
                        session::searchTextRaw,
                        text -> session.setSearchText(text));
        try {
            search.setMaxStringLength(48);
            search.setBordered(true);
        } catch (Throwable ignored) {
            // 外观拿不到不影响功能
        }
        addWidget(search);
        queryOnly.add(search);
        this.searchField = search;

        addNavLike(258, 38, QSLOT_Y, "§f搜索", cd -> onSearchNow(), queryOnly);
        addNavLike(298, 38, QSLOT_Y, "§7清空", cd -> onClearSearch(), queryOnly);

        // ── 三个查询按钮（用户点名的 1/2/3）──
        addNavLike(4, 106, QBTN_Y, "§a获取途径", cd -> onQuery(ShanhaiRecipeQuery.Kind.SOURCE), queryOnly);
        addNavLike(112, 112, QBTN_Y, "§a作为物品的用处",
                cd -> onQuery(ShanhaiRecipeQuery.Kind.USE), queryOnly);
        addNavLike(228, 108, QBTN_Y, "§a作为机器的用处",
                cd -> onQuery(ShanhaiRecipeQuery.Kind.MACHINE), queryOnly);

        // ── 两行说明（"查的是什么 / 命中多少 / 为什么没有"）──
        final Widget note1 = new LabelWidget(4, QNOTE_Y, ShanhaiLdlText.sup(
                (Supplier<String>) session::queryKindText));
        final Widget note2 = new LabelWidget(4, QNOTE2_Y, ShanhaiLdlText.sup(
                (Supplier<String>) session::queryNoteText));
        addWidget(note1);
        addWidget(note2);
        queryOnly.add(note1);
        queryOnly.add(note2);
    }

    /**
     * 🆕 第 7 轮：卡片屏（第二屏 ＋ 查询结果屏）那一排机器图标 ＋ 4 张卡 ＋ 信息行。
     *
     * <p>🔴 省着建：一屏只建 {@link ShanhaiRecipeEditorWorkspace#CARDS_PER_PAGE} 张卡
     * （用户报过第二屏"化学浸洗机共 1378 条"，一次全建会把这一帧拖死）。
     */
    private void buildCardStage() {
        final ShanhaiRecipeTabBarWidget tabBar = new ShanhaiRecipeTabBarWidget(session, 4, TAB_Y);
        addWidget(tabBar);
        cardStageWidgets.add(tabBar);
        this.tabBar = tabBar;

        // 那一排翻"排"的两颗箭头：🔴 与那一排【同一行、贴右端】，
        // 免得它们压到下面卡片区（版面是算过的：4×74−2 = 294 ⇒ 4..298，箭头 302/320 各 16 宽 ⇒ 到 336）。
        addTinyNav(302, TAB_Y, "§f◀", cd -> onTabPage(false));
        addTinyNav(320, TAB_Y, "§f▶", cd -> onTabPage(true));

        final Widget info = new LabelWidget(4, TABINFO_Y, ShanhaiLdlText.sup(
                (Supplier<String>) session::queryInfoText));
        addWidget(info);
        cardStageWidgets.add(info);

        for (int i = 0; i < ShanhaiRecipeEditorWorkspace.CARDS_PER_PAGE; i++) {
            final ShanhaiRecipeCardWidget card =
                    new ShanhaiRecipeCardWidget(session, i, 4, CARD_Y0 + i * CARD_STEP);
            addWidget(card);
            cardStageWidgets.add(card);
            cards[i] = card;
        }
    }

    /**
     * 🆕 公共翻页行（四种屏共用，位置固定 ⇒ 不会出现"两排按钮打架"）。
     *
     * <p>队列 #6「翻页加速」在这里落地：
     * <ul>
     *   <li>🆕 第 8 轮：<b>上页 / 下页接上了 Shift / Ctrl</b> —— 按住修饰键点一次翻
     *       {@link ShanhaiRecipeEditorWorkspace#PAGE_STEP_SHIFT} /
     *       {@link ShanhaiRecipeEditorWorkspace#PAGE_STEP_CTRL} 页。
     *       修饰键来自 LDLib 的 {@code ClickData}（客户端读好、随点击包上行，
     *       服务端同一个回调里就能读到 —— 通道由另一条只读线逐段字节码核实，
     *       本轮只把早已传进来的参数接上；细节见 {@link #onNext(boolean, boolean)}）；</li>
     *   <li>🔴 上一轮那两颗<b>首页 / 末页</b>显式按钮<b>保留不动</b>（它们与加速不冲突，
     *       而且是"只知道鼠标、不想按键盘"时的兜底）。</li>
     * </ul>
     * 队列 #3「新建配方」也在这一行（只在第二屏可见）。
     *
     * <p>⚠️ 可发现性（用户点单的硬要求）：能加速的四颗按钮都挂了 tooltip，
     * 另外 {@code hintText()} 那一行也写了同一句话（见 {@code pageJumpHint()}）。
     */
    private void buildNavRow() {
        final String jumpTip = ShanhaiRecipeEditorWorkspace.pageJumpHint();
        addNavLike(4, 44, NAV_Y, "§f首页", cd -> onFirst(), navWidgets).setHoverTooltips(tip(jumpTip));
        addNavLike(50, 44, NAV_Y, "§f上页",
                cd -> onPrev(shiftOf(cd), ctrlOf(cd)), navWidgets).setHoverTooltips(tip(jumpTip));
        addNavLike(96, 44, NAV_Y, "§f下页",
                cd -> onNext(shiftOf(cd), ctrlOf(cd)), navWidgets).setHoverTooltips(tip(jumpTip));
        addNavLike(142, 44, NAV_Y, "§f末页", cd -> onLast(), navWidgets).setHoverTooltips(tip(jumpTip));
        addNavLike(190, 54, NAV_Y, "§f重读配方表", cd -> onReload(), navWidgets);
        addNavLike(248, 54, NAV_Y, "§a新建配方", cd -> onNewRecipe(), navWidgets, newRecipeOnly);
        addNavLike(306, 30, NAV_Y, "§f返回", cd -> onBack(), navWidgets);
    }

    /**
     * tooltip 文本（🔴 必须过 {@link ShanhaiLdlText#esc} —— 裸 {@code %} 会让整行变成
     * {@code Format error: …}，见该类注释里的三层字节码实证；本句里目前没有 {@code %}，
     * 但这个收口要留着，免得以后往句子里加单位时又踩一次）。
     */
    private static net.minecraft.network.chat.Component tip(String text) {
        return net.minecraft.network.chat.Component.literal(ShanhaiLdlText.esc(text));
    }

    /**
     * 🔴 <b>从 LDLib 的点击回调里取修饰键（第 8 轮 · 队列 #6）</b>。
     *
     * <h4>为什么能这么取（而不是去问 {@code Widget.isShiftDown()}）</h4>
     * {@code ButtonWidget.mouseClicked} 在<b>客户端</b>就 {@code new ClickData()}
     * （读 button / Shift / Ctrl），然后 {@code writeClientAction(1, cd::writeToBuf)} 把它
     * 随点击包上行；服务端 {@code ButtonWidget.handleClientAction(1, …)} 用
     * {@code ClickData.readFromBuf} 还原后交给<b>同一个</b> {@code onPressCallback}
     * ⇒ 服务端回调里 {@code cd.isShiftClick} / {@code cd.isCtrlClick} 就是真值。
     * <p>本面板早就在用同一个对象里的字段了（条件页那一行：{@code cd -> onCondRow(row, cd.button == 1)}
     * 的右键编辑），只是翻页那两个回调此前把参数丢掉了。
     *
     * <h4>🔴 绝对不许走的那条路</h4>
     * LDLib 的 {@code Widget.isShiftDown()/isCtrlDown()/isAltDown()/isKeyDown()} 四个都走
     * {@code Minecraft.m_91087_()} / {@code Screen.m_96637_()}，<b>都没有 {@code @OnlyIn}</b>
     * ⇒ 专用服务端上不会变 false、而是直接抛 {@code NoClassDefFoundError}
     * （探针实测：{@code at …Widget.isShiftDown(Widget.java:598)}）。本类<b>一次都不调</b>它们。
     *
     * <p>拿不到 {@code ClickData}（理论上不会发生）时一律回落到 false = 原来的"一次一页"，
     * <b>绝不猜</b>。
     */
    private static boolean shiftOf(Object cd) {
        return cd instanceof com.lowdragmc.lowdraglib.gui.util.ClickData c && c.isShiftClick;
    }

    private static boolean ctrlOf(Object cd) {
        return cd instanceof com.lowdragmc.lowdraglib.gui.util.ClickData c && c.isCtrlClick;
    }

    /** 「按钮铺满 ＋ 上面盖 Label」的通用小按钮（理由见类注释 §3②）。返回按钮本体，调用方可以挂 tooltip。 */
    private Widget addNavLike(int x, int w, int y, String text, Consumer<Object> onClick,
                              List<Widget> group) {
        return addNavLike(x, w, y, text, onClick, group, null);
    }

    /** 16×20 的小按钮（只给"翻一排"那两颗箭头用，所以高度与公共翻页行不同）。 */
    private void addTinyNav(int x, int y, String text, Consumer<Object> onClick) {
        final Widget button = new ButtonWidget(x, y, 16, 20, GuiTextures.BUTTON, cd -> onClick.accept(cd));
        final Widget label = new LabelWidget(x + 5, y + 6, ShanhaiLdlText.esc(text));
        addWidget(button);
        addWidget(label);
        cardStageWidgets.add(button);
        cardStageWidgets.add(label);
    }

    private Widget addNavLike(int x, int w, int y, String text, Consumer<Object> onClick,
                              List<Widget> group, List<Widget> extraGroup) {
        final Widget button = new ButtonWidget(x, y, w, NAV_H, GuiTextures.BUTTON, cd -> onClick.accept(cd));
        final Widget label = new LabelWidget(x + 5, y + 5, ShanhaiLdlText.esc(text));
        addWidget(button);
        addWidget(label);
        group.add(button);
        group.add(label);
        if (extraGroup != null) {
            extraGroup.add(button);
            extraGroup.add(label);
        }
        return button;
    }

    // ------------------------------------------------------------------ 编辑屏

    private void buildEditStage() {
        addEditLabel(IN_X, 38, (Supplier<String>) session::inTitle);
        addEditLabel(OUT_X, 38, (Supplier<String>) session::outTitle);

        // ── 格子区：左栏 = 输入（物品在前、流体在后），右栏 = 输出 ──
        //    🔴 每栏 CELLS_PER_PAGE 格，超出部分靠下面那对 ◀ ▶ 翻页。
        //       控件【数量固定】，具体代表哪一格由 indexSupplier 现算（类型/页变了就换），
        //       理由是 LDLib 的控件表在 UI 建好之后不能再改。
        for (int i = 0; i < ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE; i++) {
            final int slot = i;
            addCell(i, () -> session.inCellIndex(slot),
                    IN_X + (i % GRID_COLS) * CELL_STEP, GRID_Y0 + (i / GRID_COLS) * CELL_STEP);
        }
        for (int i = 0; i < ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE; i++) {
            final int slot = i;
            addCell(ShanhaiRecipeEditorWorkspace.CELLS_PER_PAGE + i, () -> session.outCellIndex(slot),
                    OUT_X + (i % GRID_COLS) * CELL_STEP, GRID_Y0 + (i / GRID_COLS) * CELL_STEP);
        }

        // ── 分页（用户要求"格子区要能滚动或分页"）──
        //    🆕 阶段 2：输入那一对翻页 + 那行读数在非 GT 屏上【藏掉】——
        //    非 GT 屏最多 9 格（1 页），留着只会显示"输入 1/1 (9 格)"这种噪音，
        //    而且那块位置要给"3×3 网格 ＋ 宽高按钮"用。
        //    ⚠️ 输出那一对【保持原样】（产物还是走原来那一套）。
        addEditButton(IN_X, PAGE_Y, 20, "§f◀", cd -> onInPage(false), gtOnly);
        addEditButton(IN_X + 24, PAGE_Y, 20, "§f▶", cd -> onInPage(true), gtOnly);
        addEditLabel(IN_X + 50, PAGE_Y + 5, () -> "§7输入 §f" + (session.inPage() + 1) + "§7/§f"
                + session.inPageCount() + " §8(" + session.ioTable().inSection() + " 格)", gtOnly);
        addEditButton(OUT_X, PAGE_Y, 20, "§f◀", cd -> onOutPage(false), gtOnly);
        addEditButton(OUT_X + 24, PAGE_Y, 20, "§f▶", cd -> onOutPage(true), gtOnly);
        addEditLabel(OUT_X + 50, PAGE_Y + 5, () -> "§7输出 §f" + (session.outPage() + 1) + "§7/§f"
                + session.outPageCount() + " §8(" + session.ioTable().outSection() + " 格)", gtOnly);

        // ── 上限读数（"随配方类型变化"这件事必须看得见）──
        addEditLabel(IN_X, CAP_Y, (Supplier<String>) session::capacityText);
        addEditLabel(IN_X, NUM_Y, (Supplier<String>) session::numbersText);

        // ── 两个输入框那一行（耗时 / 电流）。🔴 必须用带 Position 的构造器，理由见类注释 §3① ──
        //    位置：耗时 标签(6) + 输入框 44..144 ；电流 标签(176) + 输入框 214..314
        //    两栏之间留 70px 空档 ⇒ 任何标签都不会落进另一个输入框里（旧版就是这里重叠的）。
        addEditLabel(IN_X, INP_Y + 4, (Supplier<String>) session::durationLabel, chronoOnly);
        final Widget dur = new IntInputWidget(new Position(IN_X + 38, INP_Y),
                (Supplier<Integer>) session::pendingDuration,
                (Consumer<Integer>) session::setPendingDuration);
        addEdit(dur, chronoOnly);
        addEditLabel(OUT_X, INP_Y + 4, () -> "§7电流(A)", gtOnly);
        final Widget amps = new IntInputWidget(new Position(OUT_X + 38, INP_Y),
                (Supplier<Integer>) session::amperage,
                (Consumer<Integer>) session::setAmperage);
        addEdit(amps, gtOnly);

        // ── 按钮（按钮铺满 ＋ 上面盖 Label，理由见类注释 §3②）──
        //    🆕「保存并退出」⇒「保存并返回」（用户原话：「你这个保存并退出怎么是直接关闭 gui 了，
        //       应该是保存并退回吧，不然我编辑第二条的时候还要再打开面版」）；
        //      并且编辑屏【也】给一颗返回键（他原话：「配方编辑的面版怎么连个返回键都没有」）。
        addEditButton(4, BTN_Y, 84, "§a保存并返回", cd -> onSaveAndReturn());
        addEditButton(92, BTN_Y, 44, "§f保存", cd -> onSave());
        addEditButton(140, BTN_Y, 44, "§f返回", cd -> onBack());
        addEditButton(188, BTN_Y, 72, "§c删除这条", cd -> onDelete());
        addEditButton(264, BTN_Y, 72, "§e恢复原样", cd -> onRestore());

        // ── 🆕 边框含义图例（用户问过「这个黄的是没解锁的槽对吧」⇒ 直接把含义写在界面上）──
        addEditLabel(IN_X, LEG_Y, () -> "§8外框 §7灰§8=物品格 · §9蓝§8=流体格 §8| 内框 "
                + "§e金§8=正在改数量的那格 · §f白§8=鼠标悬停");
        // ── 🆕 "指到哪一格"那一行：物品名（id ＋ 中文名）放在格子【外面】 ──
        addEditLabel(IN_X, HOV_Y, () -> hoverLine.isEmpty()
                ? "§8指到哪一格，这里就显示那一格的中文名(id)、数量与概率"
                : "§8▸ " + hoverLine);

        // ── 状态读数（耗时/电压/电流/耗能 那一行已经在 NUM_Y 上，这里不再重复画一遍）──
        final Widget st1Edit = new LabelWidget(4, ST1_Y, ShanhaiLdlText.sup((Supplier<String>) session::status1));
        final Widget st2Edit = new LabelWidget(4, ST2_Y, ShanhaiLdlText.sup((Supplier<String>) session::status2));
        // 🆕 第 7 轮（队列 #5）：编辑屏多一行【只读】的"真实耗时"。
        //    用户口径：「第三屏：原始时间 显示·可以改 / 真实时间 显示·不可改」——
        //    上面那个「耗时(t)」输入框改的就是原始值，这一行把它换算之后的实际值显示出来。
        final Widget realEdit = new LabelWidget(4, REAL_Y, ShanhaiLdlText.sup(
                (Supplier<String>) session::realDurationText));
        final Widget st1List = new LabelWidget(4, ST1LIST_Y, ShanhaiLdlText.sup((Supplier<String>) session::status1));
        addWidget(st1Edit);
        addWidget(st2Edit);
        addWidget(realEdit);
        addWidget(st1List);
        editOnly.add(st1Edit);
        editOnly.add(st2Edit);
        editOnly.add(realEdit);
        statusBar.add(st1List);

        // ── 🆕 B 组：条件页的入口（与「电压等级」同一行，电压下拉相应缩窄，两者不重叠）──
        //    位置：电压标签 x=6 · 电压下拉 58..258 · 条件按钮 262..334
        final Widget condBtn = new ButtonWidget(262, TIER_Y, 72, 18, GuiTextures.BUTTON, cd -> onToggleConditions());
        final Widget condLbl = new LabelWidget(266, TIER_Y + 5,
                ShanhaiLdlText.sup((Supplier<String>) session::condButtonText));
        addWidget(condBtn);
        addWidget(condLbl);
        editOnly.add(condBtn);
        editOnly.add(condLbl);
        // 🆕 「额外条件」是 GT 专有的字段（原版配方没有这个概念）⇒ 编辑原版配方时藏起来
        gtOnly.add(condBtn);
        gtOnly.add(condLbl);
    }

    /**
     * 🆕 阶段 2：<b>非 GT 输入侧的那一套控件</b>（3×3 网格 / 无序列表 / 单格输入）。
     *
     * <h4>为什么另建 18 个格子，而不是把 GT 那 32 个挪位置</h4>
     * ① 版面上 3×3 是"方形"、GT 那套是 8 列 × 4 行，两者的位置没法共用；
     * ② LDLib 的控件位置虽然能 {@code setSelfPosition} 改，但那是一条<b>没有验过</b>的路
     *    （本刀禁止开客户端 ⇒ 改位置的效果我验不了）⇒ 宁可多建 18 个位置写死的控件；
     * ③ 两类各 9 个、都只在非 GT 那一屏显示 ⇒ 与 GT 那套<b>永远不同时可见</b>，不会互相抢点击。
     *
     * <h4>手势完全沿用（一个都不重写）</h4>
     * 用的还是 {@link ShanhaiIOWidget}：JEI 拖入 / 右键删 / 中键改数量。
     * 它读的是 {@code session.cell(index)}，而 index = 0..8 就是
     * {@link ShanhaiVanillaRecipeShape} 那 9 个物理格在 {@code io} 镜像里的下标。
     * "这一格能不能动"由 {@code session.offerIngredient / clearCell} 的入口闸门统一负责。
     */
    private void buildVanillaStage() {
        // ── 3×3 网格（有形状合成 / 无序合成 / 单格输入 —— 三种形态都画这一套）──
        for (int i = 0; i < ShanhaiVanillaRecipeShape.GRID_CELLS; i++) {
            final int x = VAN_X + (i % ShanhaiVanillaRecipeShape.MAX_SIDE) * VAN_STEP;
            final int y = VAN_Y + (i / ShanhaiVanillaRecipeShape.MAX_SIDE) * VAN_STEP;
            vanillaCellsGrid[i] = newVanillaCell(i, x, y);
        }
        // ── 🆕 第 12 刀：产物格（位置 = 原 GT 输出第 0 格，用户上一刀看到的就是这个位置）──
        vanillaOutCell = new ShanhaiIOWidget(session, () -> session.outCellIndex(0), OUT_X, GRID_Y0);
        vanillaOutCell.setHoverSink(text -> this.hoverLine = text);
        addWidget(vanillaOutCell);
        vanillaOutCell.setVisible(false);
        vanillaOnly.add(vanillaOutCell);

        // ── 右侧：上面两行是"这份输入长什么样"，下面那行是怎么改 ──
        //    ⚠️ 位置必须避开 x≥176 的产物栏，所以只放在 y=98 / y=110 那两行。
        addVanillaLabel(VAN_TXT_X, 98, ShanhaiLdlText.sup(session::vanillaShapeTitle));
        addVanillaLabel(VAN_TXT_X, 110, ShanhaiLdlText.sup(session::vanillaShapeHint));

        // 🔴 2026-10-05 第 11 刀（用户点名删的）：「这图3的四个按钮也删了，不需要」
        //    原话：「**重叠了，不通过**，如图2，然后**这图3的四个按钮也删了，不需要**」
        //    他同时给了口径：「**工作台是固定的 3×3、不会变**」。
        // 🔴 2026-10-05 第 12 刀（用户点名删的）：「**+ 加一格 / − 删一格**那两颗按钮 ⇒ 删掉」
        //    他的替代方案（原话）：「**直接在网格里面再补一个**」+「主要是这样玩家好操作」
        //    ⇒ 有序/无序共用这一张 3×3：往空格里拖 = 加一样材料，右键 = 删掉那一样。
        //    ⇒ 这一屏<b>一颗这类按钮都不剩</b>，"增 / 删"全靠网格本身。
    }

    /**
     * 建一个非 GT 用的格子。
     *
     * <p>⚠️ <b>不</b>登记进 {@code editOnly} —— 它们的显隐口径与 GT 那批完全不同
     * （"非 GT 屏 ＋ 这一格在这份输入的形状里"），登记进去会被 {@code refreshVisibility}
     * 里那两轮 {@code setVisible} 互相盖掉，而"谁最后写谁赢"这种 bug 在本工程已经出过一次。
     */
    private ShanhaiIOWidget newVanillaCell(int index, int x, int y) {
        final ShanhaiIOWidget cell = new ShanhaiIOWidget(session, index, x, y);
        cell.setHoverSink(text -> this.hoverLine = text);
        addWidget(cell);
        cell.setVisible(false);
        return cell;
    }

    private void addVanillaLabel(int x, int y, Supplier<String> text) {
        final Widget label = new LabelWidget(x, y, text);
        addWidget(label);
        vanillaOnly.add(label);
        label.setVisible(false);
    }

    // (第 12 刀：`addVanillaButton` 随那两颗按钮一起删掉了 —— 现在这一屏一颗按钮都没有。)

    /**
     * 🔴 <b>2026-10-05 第 11 刀：搜索框 —— 只在【点按钮】或【按回车】时才查</b>。
     *
     * <p>用户原话（逐字）：「是可以搜索了，但是<b>不要我每打一个字就搜索一次</b>啊，
     * 要我<b>点击搜索按钮</b>再去搜索啊」。
     * <ul>
     *   <li>「每打一个字就搜」的那条闸在 {@code ShanhaiRecipeEditorWorkspace#tick()} 里 —— 已经摘掉
     *       （它原来是"停手 0.2 秒自动查一次"的防抖）；</li>
     *   <li>本类这一层只负责<b>把回车键接成同一个动作</b>（他一贯喜欢手不用离开键盘）。
     *       ⚠️ 回调必须在【服务端】跑（查询要读配方表）⇒ 这里发一个 client action，
     *       由 {@link #handleClientAction} 在服务端调 {@code onSearchNow()}
     *       —— 与「搜索」按钮走的是同一条口径（LDLib 的 ButtonWidget 也是这么上行的）。</li>
     * </ul>
     */
    private final class SearchFieldWidget
            extends com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget {

        /** 自定义动作号（⚠️ 不能是 1：那是 TextFieldWidget 自己用来同步文本的）。 */
        private static final int ACT_ENTER_SEARCH = 77;

        SearchFieldWidget(int x, int y, int w, int h, java.util.function.Supplier<String> getter,
                          java.util.function.Consumer<String> setter) {
            super(x, y, w, h, getter, setter);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // GLFW_KEY_ENTER = 257 / GLFW_KEY_KP_ENTER = 335
            if (keyCode == 257 || keyCode == 335) {
                if (isRemote()) {
                    writeClientAction(ACT_ENTER_SEARCH, buf -> {});
                } else {
                    onSearchNow();
                }
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == ACT_ENTER_SEARCH) {
                // ⚠️ 这里【不】读 buffer：上面一个字段都没写，读了会抛"缓冲区不够"
                onSearchNow();
                com.shanhai.ShanhaiMod.LOGGER.info("{} search_enter_pressed (回车 = 与「搜索」按钮同一个动作)",
                        "[SHANHAI-EDIT] editor");
                return;
            }
            super.handleClientAction(id, buffer);
        }
    }

    /**
     * 电压档位下拉。
     * 
     *
     * <h4>🔴 为什么整条搬到面板最底下、而且必须最后 add</h4>
     * ① <b>外观</b>：用户原话「你这个电压等级的设置得是一个按钮吧，不然谁知道能点」⇒
     * 这里给它装了 {@code BUTTON} 底图（{@code setButtonBackground}），并且左边有一个明确的标签。
     * ② <b>防穿透</b>：{@code WidgetGroup.mouseClicked} 是<b>倒序</b>遍历子控件的（javap 实测），
     * 所以"下拉展开时盖住了下面那颗按钮"这件事的唯一正确修法就是<b>让下拉是最后一个子控件</b>——
     * 它先拿到点击，返回 true，事件不再往下走。见构造函数里的调用顺序注释。
     * ③ <b>展开方向</b>：{@code setIsUp(true)} ⇒ 列表向上展开，落在"读数/图例"这些<b>非可点</b>的行上，
     * 不会盖住输入框那一行。
     */
    private void buildTierSelector() {
        addEditLabel(IN_X, TIER_Y + 5, () -> "§7电压等级", gtOnly);
        final com.lowdragmc.lowdraglib.gui.widget.SelectorWidget tier =
                new com.lowdragmc.lowdraglib.gui.widget.SelectorWidget(
                        IN_X + 52, TIER_Y, 200, 18,
                        List.of(ShanhaiRecipeEditorWorkspace.TIER_NAMES), 8);
        tier.setSupplier(() -> {
            final int idx = session.tierIndex();
            return idx >= 0 ? ShanhaiRecipeEditorWorkspace.TIER_NAMES[idx] : "§8未定";
        });
        tier.setOnChanged(name -> {
            if (!clientSide) {                 // 写操作只在服务端做
                session.setTierByName(name);
            }
        });
        tier.setIsUp(true);
        try {
            tier.setButtonBackground(GuiTextures.BUTTON);
            tier.setBackground(GuiTextures.BACKGROUND);
        } catch (Throwable ignored) {
            // 底图拿不到只是不好看，不影响功能 —— 不许因此让整个面板建不起来
        }
        addWidget(tier);
        editOnly.add(tier);
        this.tierSelector = tier;
    }

    /**
     * 编辑屏的一行静态/动态标签。
     *
     * <p>🔴 A7（2026-10-05）：文本一律先过 {@link ShanhaiLdlText#esc}。
     * 这条链的后果是"整行字变成 <code>Format error: …</code>"，见 {@link ShanhaiLdlText} 类注释里的三层字节码实证。
     * 收口放在这里（而不是每个调用点各写一遍）是因为<b>调用点会继续增加</b>，
     * 而"漏一处就重现同一个 bug"的形态最容易复发。
     */
    private void addEditLabel(int x, int y, Supplier<String> text) {
        final Widget label = new LabelWidget(x, y, ShanhaiLdlText.sup(text));
        addWidget(label);
        editOnly.add(label);
    }

    /**
     * 🆕 同 {@link #addEditLabel}，但<b>额外登记进某一个"条件可见"组</b>
     * （{@link #gtOnly} / {@link #chronoOnly}）。
     *
     * <p>⚠️ 一定要走"先 {@code addWidget} 再登记"这个顺序 —— 控件的<b>建立顺序</b>不能在两种写法之间变，
     * 因为 LDLib 的 {@code WidgetGroup.mouseClicked} 是<b>倒序</b>遍历子控件的
     * （见 {@link #buildTierSelector} 的类注释：下拉必须最后 add 才拿得到点击）。
     */
    private void addEditLabel(int x, int y, Supplier<String> text, List<Widget> group) {
        final Widget label = new LabelWidget(x, y, ShanhaiLdlText.sup(text));
        addWidget(label);
        editOnly.add(label);
        group.add(label);
    }

    private void addEdit(Widget widget) {
        addWidget(widget);
        editOnly.add(widget);
    }

    private void addEdit(Widget widget, List<Widget> group) {
        addWidget(widget);
        editOnly.add(widget);
        group.add(widget);
    }

    private void addEditButton(int x, int y, int w, String text, Consumer<Object> onClick) {
        final Widget button = new ButtonWidget(x, y, w, 20, GuiTextures.BUTTON, cd -> onClick.accept(cd));
        final Widget label = new LabelWidget(x + 6, y + 7, ShanhaiLdlText.esc(text));
        addWidget(button);
        addWidget(label);
        editOnly.add(button);
        editOnly.add(label);
    }

    /** 同 {@link #addEditButton}，但额外登记进某一个"按非 GT 压下去"的组（{@code gtOnly}）。 */
    private void addEditButton(int x, int y, int w, String text, Consumer<Object> onClick,
                               List<Widget> group) {
        final Widget button = new ButtonWidget(x, y, w, 20, GuiTextures.BUTTON, cd -> onClick.accept(cd));
        final Widget label = new LabelWidget(x + 6, y + 7, ShanhaiLdlText.esc(text));
        addWidget(button);
        addWidget(label);
        editOnly.add(button);
        editOnly.add(label);
        group.add(button);
        group.add(label);
    }

    private void addCell(int widgetIndex, java.util.function.IntSupplier index, int x, int y) {
        final ShanhaiIOWidget cell = new ShanhaiIOWidget(session, index, x, y);
        // 🆕 "指到哪一格就在格子下面那一行显示它的中文名(id)"（名字绝不画在图标上）
        cell.setHoverSink(text -> this.hoverLine = text);
        cells[widgetIndex] = cell;
        addWidget(cell);
        editOnly.add(cell);
    }

    /**
     * 🆕 每帧先把"指到哪一格"那一行清空，再由被指到的那一格写进去。
     *
     * <p>为什么必须由面板清：64 个格子每帧都会走 {@code drawInBackground}，
     * 而"没被指到"的格子如果主动写空串，就会把前面被指到那格刚写的字<b>盖掉</b>
     * （谁最后画谁赢）。⇒ 正确做法是"面板每帧清一次、只有被指到的格子写一次"。
     * 子控件的绘制顺序是插入序，而格子是在那一行标签<b>之前</b> add 的，所以读到的必然是本帧的值。
     */
    @Override
    public void drawInBackground(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
                                 float partialTicks) {
        this.hoverLine = "";
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
    }

    // ------------------------------------------------------------------ 改这一格的小窗

    /**
     * 中键点到的这一格的小窗。
     *
     * <p>🆕 2026-10-05：从"只改数量"扩成"数量 ＋ 概率 ＋ 随电压递增 ＋ 是否催化剂"
     * （用户点单 B6/B7：「你中间还可以设置物品是否作为催化剂」
     * 「产出的概率（那些随着电压等级递增什么的都要可以改）」）。
     */
    private void buildPopup() {
        popup = new WidgetGroup(4, 104, W - 8, 116);
        popup.setBackground(GuiTextures.BACKGROUND);
        popup.addWidget(new LabelWidget(6, 4, ShanhaiLdlText.esc("§e改这一格（中键点到的那一格）")));

        // 🔴 A7 重排（2026-10-05，用户截图：「你这个字好像重叠了」）：
        //    ① 标签里【一个字都不许有裸 %】—— 见 ShanhaiLdlText 的三层字节码实证；
        //    ② 标签一律≤5 个可见字（≈45px），保证在「标签 x=6 → 输入框 x=70」这 64px 里画得下。
        //       原文案 "§7随电压递增(万分比)" 有 12 个可见字（≈108px）⇒ 必然被输入框压掉尾巴，
        //       这正是截图里 "随电压递增(万分比…" 那半截的来源。单位改为写在输入框【右侧】。
        popup.addWidget(new LabelWidget(6, 22, ShanhaiLdlText.esc("§7数量")));
        popup.addWidget(new IntInputWidget(new Position(70, 18),
                (Supplier<Integer>) session::pendingCount,
                (Consumer<Integer>) session::setPendingCount));

        popup.addWidget(new LabelWidget(6, 46, ShanhaiLdlText.esc("§7概率")));
        popup.addWidget(new IntInputWidget(new Position(70, 42),
                (Supplier<Integer>) session::pendingChance,
                (Consumer<Integer>) session::setPendingChance));
        popup.addWidget(new LabelWidget(134, 46, ShanhaiLdlText.esc("§8万分比")));
        popup.addWidget(new LabelWidget(184, 46, ShanhaiLdlText.sup(session::pendingChanceText)));

        popup.addWidget(new LabelWidget(6, 70, ShanhaiLdlText.esc("§7随电压递增")));
        popup.addWidget(new IntInputWidget(new Position(70, 66),
                (Supplier<Integer>) session::pendingBoost,
                (Consumer<Integer>) session::setPendingBoost));
        popup.addWidget(new LabelWidget(134, 70, ShanhaiLdlText.esc("§8万分比")));
        // 催化剂开关（按钮铺满 ＋ 上面盖 Label）
        final Widget catBtn = new ButtonWidget(184, 66, 142, 18, GuiTextures.BUTTON,
                cd -> onToggleCatalyst());
        popup.addWidget(catBtn);
        popup.addWidget(new LabelWidget(190, 71, ShanhaiLdlText.sup(() -> session.pendingCatalyst()
                ? "§a催化剂：不消耗 = 开" : "§7催化剂：不消耗 = 关")));

        popup.addWidget(new ButtonWidget(70, 90, 48, 18, GuiTextures.BUTTON, cd -> onConfirmCount()));
        popup.addWidget(new LabelWidget(78, 95, ShanhaiLdlText.esc("§f确定")));
        popup.addWidget(new ButtonWidget(124, 90, 48, 18, GuiTextures.BUTTON, cd -> onCancelCount()));
        popup.addWidget(new LabelWidget(132, 95, ShanhaiLdlText.esc("§f取消")));
        // 说明：概率只对"输出"有意义；把这句话写在面板里，免得用户以为输入格设概率没生效
        popup.addWidget(new LabelWidget(184, 92, ShanhaiLdlText.esc("§8概率/递增只对输出格有意义")));
        popup.setVisible(false);
        addWidget(popup);
    }

    // ------------------------------------------------------------------ 🆕 B 组：额外条件页

    /**
     * <b>额外条件页</b>（用户点单：「添加一个加号…新增额外条件 … 已有的条件也要在面版中列出，并且右键可以编辑」）。
     *
     * <h4>三个必需的件（逐条对应他的原话）</h4>
     * <ol>
     *   <li><b>加号</b> = 下面那颗 {@code ＋新增}：按「类型」下拉里选的那种，往列表尾部加一条；</li>
     *   <li><b>列出现有条件</b> = {@link ShanhaiRecipeEditorWorkspace#COND_ROWS} 行（文字由服务端算好推下来）；
     *       认不出的类型由 {@code describe()} 如实写成「未知条件：&lt;type&gt;（只读）」；</li>
     *   <li><b>右键编辑</b> = 行按钮的 {@code cd.button == 1}（LDLib 的 {@code ClickData.button}
     *       读的是客户端 {@code MouseHandler.activeButton}，字节码实测 ⇒ 0=左键、1=右键）。</li>
     * </ol>
     *
     * <h4>🔴 2026-10-05 第 5 轮：按用户当场纠正的口径重排这一屏</h4>
     * 用户原话（逐字）：
     * <blockquote>「3：你这个挡位应该是根据上面的类型而变动的啊，根本不需要输入模块的 id 那么麻烦，
     * 然后这个数量也不需要啊，取反是什么意思」<br>
     * 「挡位应该根据上面的类型变动指的是我要是选物质模块等级，那挡位应该是17种物质模块」</blockquote>
     * ⇒ 这一屏现在只剩<b>三样控件</b>：
     * <pre>
     *   ① 类型    下拉（13 种已知类型，中文名）
     *   ② 档位    下拉 —— 🔴 候选随【类型】变（走 LDLib 自己的 SelectorWidget#setCandidatesSupplier，
     *             服务端每 tick 比一次、变了才发包推给客户端；字节码实测的通道，
     *             不需要重建控件表 —— 本面板的控件表建好之后本来就不能改）
     *             类型没有取值（无重力那种"要/不要"）⇒ 这一行整行藏掉
     *   ③ 满足/不满足  一个开关（原来叫"取反"，他说看不懂 ⇒ 换大白话）
     * </pre>
     * <b>删掉的</b>：模块 id 输入框、手持按钮、数量输入框（真实门槛 = 该模块自身等级，
     * 见 {@code ModuleLevelCondition#requiredLevelForGate()}）⇒ 前两样用户点名不要，第三样是多余的。
     *
     * <h4>⚠️ 如实交代</h4>
     * 这一屏<b>没被真机渲染验过</b>（红线禁止开客户端）⇒「档位下拉展开是不是 17 项」这一条
     * 只能由他在游戏里看。能机器验的部分（候选表内容、项数、切换类型后候选是否真的换了一套）
     * 在 {@code CONDITIONS_SELFCHECK} 与自检里逐条打读数；
     * <b>坐标是否压住别的控件只能他看</b>。
     */
    private void buildConditionsStage() {
        condGroup = new WidgetGroup(4, 28, C_W, C_H);
        condGroup.setBackground(GuiTextures.BACKGROUND);
        condGroup.addWidget(new LabelWidget(6, 4,
                ShanhaiLdlText.sup((Supplier<String>) session::condHeaderText)));
        condGroup.addWidget(new LabelWidget(6, 16,
                ShanhaiLdlText.sup((Supplier<String>) session::condHintText)));

        for (int i = 0; i < ShanhaiRecipeEditorWorkspace.COND_ROWS; i++) {
            final int row = i;
            final int y = C_ROW_Y0 + i * C_ROW_STEP;
            final Widget b = new ButtonWidget(6, y, C_W - 12, C_ROW_H, GuiTextures.BUTTON,
                    cd -> onCondRow(row, cd.button == 1));
            condGroup.addWidget(b);
            condGroup.addWidget(new LabelWidget(10, y + 2,
                    ShanhaiLdlText.sup((Supplier<String>) () -> session.conditionRowText(row))));
        }

        // ── 加号 / 减号 / 关闭（按钮铺满 ＋ 上面盖 Label，理由见类注释 §3②）──
        condGroup.addWidget(new ButtonWidget(6, C_ADD_Y, 64, 18, GuiTextures.BUTTON, cd -> onCondAdd()));
        condGroup.addWidget(new LabelWidget(14, C_ADD_Y + 5, ShanhaiLdlText.esc("§a＋ 新增")));
        condGroup.addWidget(new ButtonWidget(76, C_ADD_Y, 96, 18, GuiTextures.BUTTON, cd -> onCondRemove()));
        condGroup.addWidget(new LabelWidget(84, C_ADD_Y + 5, ShanhaiLdlText.esc("§c－ 删除选中")));
        condGroup.addWidget(new ButtonWidget(240, C_ADD_Y, 86, 18, GuiTextures.BUTTON, cd -> onCondClose()));
        condGroup.addWidget(new LabelWidget(252, C_ADD_Y + 5, ShanhaiLdlText.esc("§f关闭条件页")));

        // ── ① 类型下拉（作用于选中那条；没选中 = 只记着"新增时用哪种"）──
        condGroup.addWidget(new LabelWidget(6, C_TYPE_Y + 5, ShanhaiLdlText.esc("§7类型")));
        final com.lowdragmc.lowdraglib.gui.widget.SelectorWidget typeSel =
                new com.lowdragmc.lowdraglib.gui.widget.SelectorWidget(50, C_TYPE_Y, 276, 18,
                        session.condTypeLabels(), 8);
        typeSel.setSupplier((Supplier<String>) session::condTypeLabel);
        typeSel.setOnChanged(name -> onCondType(name));
        typeSel.setIsUp(true);
        styleSelector(typeSel);
        condGroup.addWidget(typeSel);

        // ── ② 档位下拉（🔴 候选随「类型」变 —— 这就是用户说的"挡位应该根据上面的类型变动"）──
        //
        // 🔴 为什么用 setCandidatesSupplier 而不是"换一个控件"：本面板的控件表在 UI 建好之后
        //    不能再改（LDLib 的控件表是建一次就固定的），而 SelectorWidget 自己带了这个机制。
        //    字节码实测（ldlib-forge-1.20.1-1.0.33.b.jar）：
        //      detectAndSendChanges()：非客户端时 supplier.get() → List.equals 比一次 →
        //          不同才 setCandidates(...) ＋ writeUpdateInfo(4, …) 把整张表推给客户端
        //      readUpdateInfo(id=4)：读那张表 → setCandidates(...)
        //      readUpdateInfo(id=3)：读一个字符串 → setValue(...)（当前值那条）
        //    ⇒ 服务端算出"这个类型的档位有哪些"，客户端只负责画。
        condValueLabel = new LabelWidget(6, C_CLEAN_Y + 5, ShanhaiLdlText.esc("§7档位"));
        condGroup.addWidget(condValueLabel);
        condValueSel = new com.lowdragmc.lowdraglib.gui.widget.SelectorWidget(50, C_CLEAN_Y, 180, 18,
                session.condValueCandidates(), 10);
        condValueSel.setCandidatesSupplier((Supplier<java.util.List<String>>) session::condValueCandidates);
        condValueSel.setSupplier((Supplier<String>) session::condValueLabel);
        condValueSel.setOnChanged(name -> onCondValue(name));
        condValueSel.setIsUp(true);
        styleSelector(condValueSel);
        condGroup.addWidget(condValueSel);

        // 没有档位的类型 ⇒ 这一行换成一句大白话（而不是画一个空下拉）
        condNoValueHint = new LabelWidget(6, C_CLEAN_Y + 5,
                ShanhaiLdlText.sup((Supplier<String>) session::condNoValueText));
        condGroup.addWidget(condNoValueHint);

        // ── ③ 「要求满足 / 要求不满足」开关（原来叫"取反"，用户说看不懂）──
        condReverseBtn = new ButtonWidget(240, C_CLEAN_Y, 86, 18, GuiTextures.BUTTON, cd -> onCondReverse());
        condReverseBtn.setHoverTooltips(net.minecraft.network.chat.Component
                .literal(ShanhaiLdlText.esc(session.condReverseHintText())));
        condGroup.addWidget(condReverseBtn);
        condGroup.addWidget(new LabelWidget(244, C_CLEAN_Y + 5,
                ShanhaiLdlText.sup((Supplier<String>) session::condReverseButtonText)));

        // ── 读数（正在改哪一条、它现在是什么）＋ 上一次动作的机器可判读数 ──
        condGroup.addWidget(new LabelWidget(6, C_MOD_Y + 6,
                ShanhaiLdlText.sup((Supplier<String>) session::condEditorText)));
        condGroup.addWidget(new LabelWidget(6, C_NOTE_Y,
                ShanhaiLdlText.sup((Supplier<String>) session::condNote)));
        // 🆕 第 5 轮：把"这一份条件是从哪个来源读出来的"如实画出来（ledger / 文件 / 活配方）——
        //    上一轮"重开就没了"之所以只能靠猜，就是因为界面上没有这个读数。
        condGroup.addWidget(new LabelWidget(6, C_NOTE_Y + 12,
                ShanhaiLdlText.sup((Supplier<String>) session::condSourceText)));

        condGroup.setVisible(false);
        addWidget(condGroup);
    }

    /**
     * 档位那一行的显隐（每 tick 由状态泵调；见 {@link #refreshVisibility}）。
     *
     * <p>判据是 {@link ShanhaiRecipeEditorWorkspace#condHasValueColumn()} —— 它按<b>类型名</b>判，
     * 两侧给出同一个答案（客户端本地枚举不出"维度"那张表，所以不能用会枚举的那一版）。
     */
    private void refreshCondValueRow() {
        if (condValueSel == null || condNoValueHint == null) {
            return;
        }
        final boolean has = session.condHasValueColumn();
        condValueSel.setVisible(has);
        if (condValueLabel != null) {
            condValueLabel.setVisible(has);
        }
        condNoValueHint.setVisible(!has);
        // ⚠️ 「满足 / 不满足」那个开关与"有没有档位"无关：所有条件都有这一维，所以它一直在。
        // 🆕 第 5 轮（#7「没看懂」）：内部 id 只放在 tooltip 里 —— 界面上只出现中文名。
        try {
            condValueSel.setHoverTooltips(net.minecraft.network.chat.Component
                    .literal(ShanhaiLdlText.esc(session.condValueHint())));
        } catch (Throwable ignored) {
            // tooltip 拿不到不影响功能
        }
    }

    /** 下拉控件的统一外观（拿不到底图只是不好看，不许因此让整个面板建不起来）。 */
    private static void styleSelector(com.lowdragmc.lowdraglib.gui.widget.SelectorWidget sel) {
        try {
            sel.setButtonBackground(GuiTextures.BUTTON);
            sel.setBackground(GuiTextures.BACKGROUND);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ------------------------------------------------------------------ 状态推送 / 显隐

    private void buildPump() {
        pump = new StatePump(session, this::refreshVisibility);
        addWidget(pump);
    }
    /**
     * 显隐切换（两侧各做自己那一份；见 {@link StatePump} 类注释）。
     *
     * <p>⚠️ 除了 {@code setVisible}，每个控件的回调里<b>另有一道"当前是哪一屏"的判断</b>
     * （例如行按钮判断 {@link ShanhaiRecipeEditorWorkspace#stageIsList()}）——
     * 因为 {@code setVisible} 会不会拦掉鼠标事件属于 LDLib 的实现细节，
     * 本面板<b>不把正确性押在它上面</b>。
     */
    /**
     * 显隐切换（两侧各做自己那一份；见 {@link StatePump} 类注释）。
     *
     * <p>⚠️ 除了 {@code setVisible}，每个控件的回调里<b>另有一道"当前是哪一屏"的判断</b> ——
     * 因为 {@code setVisible} 会不会拦掉鼠标事件属于 LDLib 的实现细节，
     * 本面板<b>不把正确性押在它上面</b>。
     *
     * <p>🆕 第 7 轮：从"编辑屏 / 非编辑屏"两分法扩成<b>四屏</b>
     * （第一屏类型列表 / 第二屏卡片 / 查询结果屏 / 第三屏编辑）。
     */
    private void refreshVisibility(ShanhaiRecipeEditorWorkspace.Stage stage) {
        final boolean types = stage == ShanhaiRecipeEditorWorkspace.Stage.TYPES;
        final boolean cards = stage == ShanhaiRecipeEditorWorkspace.Stage.RECIPES
                || stage == ShanhaiRecipeEditorWorkspace.Stage.QUERY;
        final boolean edit = stage == ShanhaiRecipeEditorWorkspace.Stage.EDIT;
        final boolean condsOpen = edit && session.condOpen();
        final boolean popupOpen = edit && session.countCell() >= 0 && !condsOpen;

        for (Widget w : typeRowWidgets) {
            w.setVisible(types);
        }
        for (Widget w : queryOnly) {
            w.setVisible(types);
        }
        for (Widget w : cardStageWidgets) {
            w.setVisible(cards);
        }
        // 🔴 底部状态行只在【第一屏】显示：卡片屏的第 4 张卡一直画到 y=274，
        //    而这一行在 y=260 ⇒ 两屏同时显示就会把字压在卡片上。
        //    卡片屏自己的那一行信息在 TABINFO_Y（62）上（见 queryInfoText）。
        for (Widget w : statusBar) {
            w.setVisible(types);
        }
        for (Widget w : navWidgets) {
            w.setVisible(!edit);
        }
        // 🔴🔴 2026-10-06（用户报「从『搜索 / 获取途径』进去的类型页，无法新增配方」）：
        //    「新建配方」原来【只在】第二屏（stage == RECIPES）可见 —— 而查询结果屏（stage == QUERY）
        //    也是"一屏一种配方种类"的卡片屏，用户在它上面同样需要新建。
        //    ⇒ 可见性口径改成 `cards`（= RECIPES ∪ QUERY），与卡片、标签栏、翻页那一排完全一致
        //      （`cards` 就是上面 cardStageWidgets 用的同一个布尔 ⇒ 不可能再漂）。
        //    ⚠️ 判据本身不写在这里：它是
        //      {@link ShanhaiRecipeEditorWorkspace#stageAllowsNewRecipe}，
        //      与点击闸门、后端入口共用同一个函数（三处各写一份 `stage == RECIPES` 正是这次的病根）。
        for (Widget w : newRecipeOnly) {
            w.setVisible(cards && ShanhaiRecipeEditorWorkspace.stageAllowsNewRecipe(stage));
        }
        for (Widget w : editOnly) {
            // 🔴🔴 2026-10-05 修复③：**条件页打开时，第三屏那批控件必须一起藏掉。**
            //
            // 现场（用户截图）：条件页的标题与副标题画在上面，而下面【整块和第三屏的控件叠在一起】——
            //   `(空)` / `64` / `1024` / 太极图 叠着；`物品入 16 · 流体入 4 · 物品出 1 · 流体出 3`、
            //   `耗时 230400 t · 电压 MAX(2147483647V) × 电流 1A = …`、`类型 / 耗时(t) / 档位`、
            //   `要求【满足】这个条件`、`打开条件页（0 条）`、`电压等级：…` —— 全糊在一起。
            // 根因：条件页是**另一组控件**（condGroup），而编辑屏那批控件的可见性只看 `edit`
            //   ⇒ 条件页一打开，两批同时 `visible=true` 并同时 draw ⇒ 逐字对上他截图里的那一片重叠。
            // 修法：编辑屏那批的可见性口径从 `edit` 改成 `edit && !condsOpen`。
            //   ⚠️ 条件页自己的那批仍然由下面 `condGroup.setVisible(condsOpen)` 管，一行没动。
            w.setVisible(edit && !condsOpen);
        }
        if (popup != null) {
            popup.setVisible(popupOpen);
        }
        if (condGroup != null) {
            condGroup.setVisible(condsOpen);
        }
        // 🆕 第 5 轮：条件页里「档位」那一行的显隐（随类型变）—— 每 tick 都刷，
        //    因为用户随时可能在上面的「类型」下拉里换一种。
        refreshCondValueRow();
        // 🆕 2026-10-05（工作台 / 原版配方）：编辑原版配方时，把 GT 专有的那几件藏掉。
        //    🔴 顺序是必需的：这两组里的控件也都在 editOnly 里，
        //       上面那个循环刚把它们设成 visible=edit ⇒ 必须【之后】再按 vanilla 压回去。
        final boolean vanilla = edit && session.vanillaMode();
        for (Widget w : gtOnly) {
            // ⚠️ `!condsOpen` 必需：这一组也在 editOnly 里，而上面那轮刚按 condsOpen 压过一次
            //    ⇒ 这里不带这个条件就会把条件页底下的控件又显示回来（修复③的口径必须两处一致）。
            w.setVisible(edit && !vanilla && !condsOpen);
        }
        // 🔴🔴 2026-10-05 第 11 刀（用户报④「重叠了，不通过」）：
        //    **GT 那 32 个格子必须在这一屏藏掉。** 它们也在 editOnly 里，上面那个循环刚按
        //    `edit` 把它们设成可见 ⇒ 非 GT 屏上就是"GT 的 8 列×4 行槽位 ＋ 我们的 3×3 网格
        //    ＋ 右边那几颗按钮"三层叠在一起 —— 用户截图里那一片重叠就是这个。
        //    （上一刀只把 vanilla 那一屏的"藏 GT 控件"规则写在 gtOnly 上，而这 32 个格子
        //      从来不登记进 gtOnly —— 它们是通过 `newCell(...)` 直接进 editOnly 的。）
        if (vanilla) {
            for (Widget w : cells) {
                if (w != null) {
                    w.setVisible(false);
                }
            }
        }
        final boolean chrono = edit && session.hasDurationField() && !condsOpen;
        for (Widget w : chronoOnly) {
            w.setVisible(chrono);
        }
        // 🔴 条件页打开时把电压下拉藏掉：它是【最后一个子控件】、点击优先级最高，
        //    而条件页覆盖了它的位置 ⇒ 不藏的话"点条件页里那一行"会被它截走。
        if (tierSelector != null) {
            tierSelector.setVisible(edit && !condsOpen && !vanilla);
        }
        // 🆕 阶段 2：非 GT 输入侧那一套（3×3 网格 / 单格 / 产物格）。
        //    🔴 口径只有一条：`edit && vanilla && !condsOpen`，输入那 9 格再按"这份输入的形态"细分。
        //       这几个 setVisible 必须排在【最后】——上面每一轮都可能把同样这些控件设成别的值。
        final boolean van = edit && vanilla && !condsOpen;
        final int vanMode = session.vanillaShapeOrd();
        // 🔴 第 12 刀：有序（GRID）与无序（LIST）画的是【同一张 3×3】——
        //    用户拍板"玩家好操作、不用学两套"；单格（SINGLE）也复用同一张网格的第 0 格。
        final boolean vanGrid = van && (vanMode == ShanhaiVanillaRecipeShape.Mode.GRID.ordinal()
                || vanMode == ShanhaiVanillaRecipeShape.Mode.LIST.ordinal());
        final boolean vanSingle = van && vanMode == ShanhaiVanillaRecipeShape.Mode.SINGLE.ordinal();
        for (Widget w : vanillaOnly) {
            w.setVisible(van);
        }
        // 有形状 / 无形状：整张 3×3 都画出来（空格也要画 —— 用户就是靠"往空格里再补一个"
        //   来加材料的；往那里拖会被入口闸门收下）。单格输入：只画第 0 格。
        for (int i = 0; i < vanillaCellsGrid.length; i++) {
            vanillaCellsGrid[i].setVisible(vanGrid || (vanSingle && i == 0));
        }
    }

    /**
     * 状态泵：<b>服务端</b>把 session 的状态推给客户端（本屏的行文字由各自的 {@code LabelWidget} 推，
     * 而 24 个格子的内容与"现在是哪一屏"由本控件推）。
     *
     * <h4>为什么推的是"面板一整份状态"而不是每个格子各自推</h4>
     * 格子里的内容只有几十字节，一次推完比分 24 条推送便宜得多，而且<b>不会出现"格子之间不同步"的中间态</b>。
     *
     * <h4>去重口径</h4>
     * 服务端每 tick 比一次 {@code session.version()}；客户端只在收到推送时读一次
     * （LDLib 的 {@code detectAndSendChanges} 只有服务端会调，客户端走 {@code readUpdateInfo}）。
     */
    private static final class StatePump extends Widget {

        private final ShanhaiRecipeEditorWorkspace session;
        private final Consumer<ShanhaiRecipeEditorWorkspace.Stage> visibility;

        private int sentVersion = -1;
        private int sentNumbers = -1;

        StatePump(ShanhaiRecipeEditorWorkspace session,
                  Consumer<ShanhaiRecipeEditorWorkspace.Stage> visibility) {
            super(0, 0, 0, 0);
            this.session = session;
            this.visibility = visibility;
        }

        @Override
        public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            if (!isRemote()) {
                // 开面板那一拍就把第一屏推下去（不然第一帧是空的）
                session.writeState(buffer, true);
                sentVersion = session.version();
                sentNumbers = session.numbersVersion();
            }
        }

        @Override
        public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            session.readState(buffer);
            visibility.accept(session.stage());
        }

        @Override
        public void detectAndSendChanges() {
            super.detectAndSendChanges();
            // 🆕 第 7 轮：文本搜索的防抖闸挂在这里（服务端每 tick 一次）。
            //    用户每敲一个键都会把文本推上来，但"全表 5.2 万条查一次"不能每键都跑。
            session.tick();
            visibility.accept(session.stage());
            if (isRemote()) {
                return;
            }
            if (session.version() == sentVersion) {
                return;
            }
            final boolean includeNumbers = session.numbersVersion() != sentNumbers;
            sentVersion = session.version();
            sentNumbers = session.numbersVersion();
            writeUpdateInfo(1, buffer -> session.writeState(buffer, includeNumbers));
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == 1) {
                session.readState(buffer);
                visibility.accept(session.stage());
                return;
            }
            super.readUpdateInfo(id, buffer);
        }
    }

    // ------------------------------------------------------------------ 点击转发（全部只在服务端生效）

    private void onRow(int row) {
        if (clientSide || !session.stageIsList()) {
            return;
        }
        session.clickRow(row);
    }

    /**
     * 🔴 <b>「上页」（第 8 轮 · 队列 #6：接上 Shift / Ctrl）</b>
     *
     * <p>参数是客户端上报的修饰键（怎么来的见 {@link #shiftOf(Object)}）。
     * {@code clientSide} 早退<b>必须保留</b>：LDLib 的 {@code ButtonWidget.mouseClicked}
     * 在客户端发完包之后<b>还会本地再跑一次同一个回调</b>（字节码 offset 42-48 的
     * {@code Consumer.accept}），而那一份 {@code ClickData} 的 {@code isShiftClick} 也是 true
     * —— 不挡掉的话客户端会拿本地态再翻一次。
     */
    private void onPrev(boolean shift, boolean ctrl) {
        if (clientSide) {
            return;
        }
        session.jumpPage(-pageStep(shift, ctrl));
    }

    /** 「下页」——同 {@link #onPrev(boolean, boolean)}。 */
    private void onNext(boolean shift, boolean ctrl) {
        if (clientSide) {
            return;
        }
        session.jumpPage(pageStep(shift, ctrl));
    }

    /**
     * 这一下点击翻几页（档位与理由见
     * {@link ShanhaiRecipeEditorWorkspace#PAGE_STEP_SHIFT}）。
     *
     * <p>换算本身收口在 {@link ShanhaiRecipeEditorWorkspace#pageStepFor(boolean, boolean)}
     * —— 本类只负责"从 {@code ClickData} 里把两个布尔量取出来"，这样"哪个键翻几页"只有一处真值。
     * 什么都不按 ⇒ <b>1</b> 页（与改动之前完全相同的行为，日常手感不变）；两个都按 ⇒ 按 Ctrl 那一档。
     */
    private static int pageStep(boolean shift, boolean ctrl) {
        return ShanhaiRecipeEditorWorkspace.pageStepFor(shift, ctrl);
    }

    /** 🆕 队列 #6：翻页加速的显式入口（服务端读不到修饰键 ⇒ 不去猜 Shift/Ctrl）。 */
    private void onFirst() {
        if (clientSide) {
            return;
        }
        session.firstPage();
    }

    private void onLast() {
        if (clientSide) {
            return;
        }
        session.lastPage();
    }

    private void onReload() {
        if (clientSide) {
            return;
        }
        session.reloadTypes();
    }

    // ------------------------------------------------------------------ 🆕 第 7 轮：查询

    /** 三个查询按钮共用的入口（只在第一屏生效）。 */
    private void onQuery(ShanhaiRecipeQuery.Kind kind) {
        if (clientSide || session.stage() != ShanhaiRecipeEditorWorkspace.Stage.TYPES) {
            return;
        }
        // 文本搜索框里已经有字时，"获取途径"仍然按物品框里那个物品查 —— 两件事互不干扰。
        session.runQuery(kind);
    }

    /** 「搜索」按钮：立刻按当前文本查一次（不等防抖）。 */
    private void onSearchNow() {
        if (clientSide || session.stage() != ShanhaiRecipeEditorWorkspace.Stage.TYPES) {
            return;
        }
        session.runTextSearchNow();
    }

    /** 「清空」按钮：把搜索框清掉并退回类型列表。 */
    private void onClearSearch() {
        if (clientSide || session.stage() != ShanhaiRecipeEditorWorkspace.Stage.TYPES) {
            return;
        }
        session.setSearchText("");
        session.clearQuery();
    }

    /** 顶部那一排"上一排 / 下一排"（只在查询结果屏有意义）。 */
    private void onTabPage(boolean forward) {
        if (clientSide || !session.stageIsQuery()) {
            return;
        }
        if (forward) {
            session.tabPageNext();
        } else {
            session.tabPagePrev();
        }
    }

    /** 🆕 队列 #3：「新建配方」——在【当前这一屏的类型】下建一条空配方，然后进第三屏编辑它。 */
    private void onNewRecipe() {
        // 🔴 2026-10-06：闸门与可见性必须【是同一个函数】（原来是各写一份 `stage != RECIPES`）——
        //    那正是"查询结果屏上按钮就算画出来也点不动"的第二道锁。
        if (clientSide || !ShanhaiRecipeEditorWorkspace.stageAllowsNewRecipe(session.stage())) {
            return;
        }
        session.newRecipe();
    }

    /** 输入栏翻页（只在服务端改：页号是 session 的一部分，改完会推给客户端）。 */
    private void onInPage(boolean forward) {
        if (clientSide || !session.stageIsEdit()) {
            return;
        }
        session.setInPage(session.inPage() + (forward ? 1 : -1));
    }

    /** 输出栏翻页。 */
    private void onOutPage(boolean forward) {
        if (clientSide || !session.stageIsEdit()) {
            return;
        }
        session.setOutPage(session.outPage() + (forward ? 1 : -1));
    }

    private void onBack() {
        if (clientSide) {
            return;
        }
        session.back();
    }

    private void onSave() {
        if (clientSide || session.countCell() >= 0) {
            return;
        }
        session.save();
    }

    /**
     * 🆕「保存并返回」：保存完<b>回到该类型的配方列表</b>，不是关掉整个 GUI。
     * （用户原话：「你这个保存并退出怎么是直接关闭 gui 了，应该是保存并退回吧，
     *   不然我编辑第二条的时候还要再打开面版」）
     */
    private void onSaveAndReturn() {
        if (clientSide || session.countCell() >= 0) {
            return;
        }
        session.saveAndReturn();
    }

    private void onToggleCatalyst() {
        if (clientSide || session.countCell() < 0) {
            return;
        }
        session.togglePendingCatalyst();
    }

    private void onDelete() {
        if (clientSide || session.countCell() >= 0) {
            return;
        }
        session.deleteSelected();
    }

    private void onRestore() {
        if (clientSide || session.countCell() >= 0) {
            return;
        }
        session.restoreSelected();
    }

    private void onConfirmCount() {
        if (clientSide || session.countCell() < 0) {
            return;
        }
        session.confirmCountEditor();
    }

    private void onCancelCount() {
        if (clientSide || session.countCell() < 0) {
            return;
        }
        session.cancelCountEditor();
    }

    // ------------------------------------------------------------------ 🆕 阶段 2：非 GT 输入侧那几颗按钮

    /**
     * 「宽 −/+」「高 −/+」（有形状合成）。
     *
     * <p>为什么是四颗按钮而不是下拉：下拉在本面板已经出过一次事故
     * （"点下拉项被下面的按钮吃掉"，见 {@link #buildTierSelector}），而按钮的点击语义没有歧义。
     * 缩小时如果新范围的外侧还有材料，服务端会<b>拒绝</b>并在状态行说明 —— 绝不静默丢材料。
     */
    private void onVanillaResize(int dw, int dh) {
        if (clientSide || !session.stageIsEdit() || !session.vanillaMode()) {
            return;
        }
        session.vanillaResize(dw, dh);
    }

    // 🔴 第 12 刀：`onVanillaAdd` / `onVanillaRemoveLast`（以及建按钮用的 `addVanillaButton`）
    //    已经随「+ 加一格 / − 删一格」一起删掉了 —— 用户拍板的口径是
    //    「**直接在网格里面再补一个**」，两屏都用同一张 3×3。
    //    服务端那两个 API（{@code vanillaAddEntry/vanillaRemoveLastEntry}）留着不动，
    //    只是面板上再也没有入口。

    // ------------------------------------------------------------------ 🆕 B 组：条件页的点击转发

    private void onToggleConditions() {
        if (clientSide || !session.stageIsEdit()) {
            return;
        }
        // 条件页与"改数量小窗"互斥：两个覆盖组在同一片区域上，同时开着会互相压住
        if (session.countCell() >= 0) {
            session.cancelCountEditor();
        }
        session.toggleConditions();
    }

    private void onCondClose() {
        if (clientSide || !session.condOpen()) {
            return;
        }
        session.toggleConditions();
    }

    private void onCondRow(int row, boolean rightClick) {
        if (clientSide || !session.condOpen()) {
            return;
        }
        session.selectCondition(row, rightClick);
    }

    private void onCondAdd() {
        if (clientSide || !session.condOpen()) {
            return;
        }
        session.addCondition();
    }

    private void onCondRemove() {
        if (clientSide || !session.condOpen()) {
            return;
        }
        session.removeCondition();
    }

    private void onCondType(String label) {
        if (clientSide || !session.condOpen()) {
            return;
        }
        session.setCondType(label);
    }

    /** 🔴 第 5 轮：档位下拉的回调（作用在选中那条上；候选随类型变）。 */
    private void onCondValue(String label) {
        if (clientSide || !session.condOpen()) {
            return;
        }
        session.setCondValue(label);
    }

    private void onCondReverse() {
        if (clientSide || !session.condOpen()) {
            return;
        }
        session.toggleCondReverse();
    }
}
