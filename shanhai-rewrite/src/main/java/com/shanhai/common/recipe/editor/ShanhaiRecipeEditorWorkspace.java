package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.google.gson.JsonElement;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.shanhai.common.recipe.ShanhaiRecipeTypes;
import com.shanhai.ShanhaiMod;

/**
 * 工作区面板的<b>全部状态与动作</b>（与 LDLib 控件完全解耦）。
 *
 * <h2>1. 三段式流程（用户点单的原话）</h2>
 * <pre>
 *   ① 类型   —— 打开面板【第一屏就是配方类型列表】（他明确说过"入口不是拿个物品就进入"）
 *   ② 配方   —— 选了类型 ⇒ 列该类型下的配方 ⇒ 选中一条进编辑
 *   ③ 编辑   —— 24 个 IO 格子（拖入/右键删/中键改数量）＋ 耗时 / 耗能 / 电压下拉 ＋ 保存/删除/恢复
 * </pre>
 *
 * <h2>2. 🔴 两侧各一份，但只有服务端那份"有东西"</h2>
 * 与第一刀同一条纪律（见 {@link ShanhaiRecipeEditorSession} 的类注释）：客户端那份
 * {@code server == null} ⇒ 列表、配方、缓冲<b>全是空的</b>；界面上每一个字、每一个格子里的物品
 * 都是<b>服务端那份推下来的</b>（{@link #readState}）。所以"客户端不可能凭本地状态显示一个
 * 服务端并不认可的数"——即本工程红线里那条「活的界面上不许放假数据」。
 *
 * <h2>3. 🔴 推送去重：两个版本号，不是一个</h2>
 * 状态推送（{@link #writeState}）挂在面板自己那一个隐藏同步控件上，每 tick 比一次版本号。
 * 两个版本号是<b>故意分开</b>的：
 * <ul>
 *   <li>{@link #version} —— 任何变化（翻页、切格子…）⇒ 触发一次推送（含 24 个格子）；</li>
 *   <li>{@link #numbersVersion} —— <b>只在"载入了一条配方"时</b>自增 ⇒ 只有这一拍才把
 *       {@code 耗时 / 耗能 / 电压档}推给客户端。</li>
 * </ul>
 * 否则：他在耗时输入框里打字 → 顺手拖一个物品（触发推送）→ 服务端把旧耗时推回来
 * <b>把他刚打的字抹掉</b>，而这件事看起来像"输入框坏了"。
 */
public final class ShanhaiRecipeEditorWorkspace {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 列表一页几行（面板的行按钮数 = 这个值）。 */
    public static final int ROWS = 12;

    /**
     * 四屏。
     *
     * <p>🆕 2026-10-05 第 7 轮加了 {@link #QUERY}：第一屏底部那个"物品框 ＋ 文本搜索框"的
     * <b>结果屏</b>。它与第二屏共用同一套卡片控件（4 张/页），区别只有一个：
     * 顶部多一排"配方种类"切换（照 JEI 顶栏那排机器图标的意思）。
     */
    public enum Stage { TYPES, RECIPES, QUERY, EDIT }

    /** 🆕 第 7 轮：查询结果屏顶部那一排"配方种类"一页显示几个。 */
    public static final int TABS_PER_PAGE = 4;

    /** 🆕 第 7 轮：卡片屏（第二屏 / 查询结果屏）一页几张卡。 */
    public static final int CARDS_PER_PAGE = 4;

    /**
     * 🆕 第 8 轮（队列 #6）：<b>按住 Shift / Ctrl 点「上页 / 下页」一次翻几页</b>。
     *
     * <p>用户原话（逐字）：「还有这个 <b>17 页翻得比较慢（有的甚至配方有上百页）</b>，
     * 把 <b>shift 和 ctrl 加速</b>也加进去吧」。
     *
     * <h4>档位怎么定的</h4>
     * 上一轮那份只读报告给的是 {@code Shift=10 / Ctrl=5}，本轮按"Ctrl 给得更大"的直觉反过来：
     * <pre>
     *   什么都不按 ⇒ 1 页   （卡片屏 4 条；类型屏 12 种）
     *   Shift     ⇒ 5 页   （卡片屏 20 条）
     *   Ctrl      ⇒ 10 页  （卡片屏 40 条；上百页的列表 10 下就到头）
     *   Shift+Ctrl（两个都按）⇒ 按 Ctrl 那一档（10 页）—— 不叠加，免得出现"15 页"这种要人算的档
     * </pre>
     * ⚠️ 档位是<b>可发现性</b>的一半：界面上必须写得出来（见面板里那两条 tooltip 与
     * {@link #hintText()} 末尾那一句），否则玩家根本不知道有这功能。
     */
    public static final int PAGE_STEP_SHIFT = 5;
    public static final int PAGE_STEP_CTRL = 10;

    /**
     * 这一个点击翻几页（<b>纯函数</b>，所以"档位表"本身是机器可判的 —— 见自检
     * {@code case=page_step_*}）。
     *
     * <p>面板那边只负责从 LDLib 的 {@code ClickData} 里取出两个布尔量，档位换算收口在这里
     * ⇒ "哪个键翻几页"这件事只有一处真值，界面提示文本也是从这两个常量算出来的。
     */
    public static int pageStepFor(boolean shift, boolean ctrl) {
        if (ctrl) {
            return PAGE_STEP_CTRL;
        }
        return shift ? PAGE_STEP_SHIFT : 1;
    }

    /** 电压档位表（GT 的口径：EU/t = 该档电压；名字沿用 GT 自己的档位名）。 */
    public static final String[] TIER_NAMES = {
            "ULV 8", "LV 32", "MV 128", "HV 512", "EV 2048", "IV 8192",
            "LuV 32768", "ZPM 131072", "UV 524288", "UHV 2097152",
            "UEV 8388608", "UIV 33554432", "UXV 134217728", "OpV 536870912", "MAX 2147483647"};
    public static final long[] TIER_VOLTAGE = {
            8L, 32L, 128L, 512L, 2048L, 8192L,
            32768L, 131072L, 524288L, 2097152L,
            8388608L, 33554432L, 134217728L, 536870912L, 2147483647L};

    /**
     * 🔴 耗能 = <b>电压档位 × 电流</b>（用户 2026-10-05 原话：
     * 「关于耗能我觉得你可以改成电流的输入吧，然后通过电压和电流来共同计算出耗能」）。
     *
     * <p>老口径是"选电压档 ⇒ 把耗能直接设成该档电压"（即隐含电流恒为 1A），用户要的是把
     * 电流单独拿出来当输入 ⇒ 本类因此多了一个 {@link #amperage} 字段，而
     * {@link #pendingEut} 变成<b>由两者算出来的量</b>（不再是独立输入）。
     *
     * <p>⚠️ 历史血账（本工程红线）：{@code data.euTier} 必须与 {@code tickInputs} <b>同拍写</b>，
     * 只改一个 ⇒ 机器看到的档位与真实耗电分叉。所以 {@link #loadBuffer} 与保存路径
     * 都从 {@code pendingEut} 这一个来源同时推 {@code euTier}（见
     * {@code ShanhaiRecipeIoApply.applyEut}）。
     */
    public static final int AMPERAGE_MIN = 1;
    public static final int AMPERAGE_MAX = 64;

    /**
     * 每页多少格（一栏一页）。用户要求「格子区要能滚动或分页」——
     * LDLib 的控件表在 UI 建好之后不能再改（见面板类注释），所以格子控件数固定，
     * 用<b>分页</b>把"非常巨大的输入输出格"装下。
     */
    public static final int CELLS_PER_PAGE = 32;

    /** 一条"类型"（列表行）。 */
    public record TypeRow(ResourceLocation id, String name, String nameSource, int count) {}

    // ---------------------------------------------------------------- 权威状态（服务端）

    private final MinecraftServer server;            // 客户端那份 = null
    private final Player player;                     // "保存并退出"要关的是他的容器

    private Stage stage = Stage.TYPES;
    private ResourceLocation typeId;
    private ResourceLocation recipeId;
    private int page;

    private int version = 1;
    private int numbersVersion = 1;

    private final List<TypeRow> types = new ArrayList<>();
    private final List<ResourceLocation> recipes = new ArrayList<>();
    private final ShanhaiIoTable io = new ShanhaiIoTable();

    private int pendingDuration;
    private int pendingEut;
    private int tierIndex = -1;
    private int amperage = 1;
    private int pendingCount = 1;
    /** 🆕 格子小窗里的概率三件套（GT 口径 10000 = 100%）。 */
    private int pendingChance = 10000;
    private int pendingBoost;
    /** 🆕 格子小窗里的"不消耗"开关（催化剂）。 */
    private boolean pendingCatalyst;
    private int countCell = -1;
    private int inPage;
    private int outPage;

    /** 这条配方所属类型的 IO 上限四元组（{@code {物品入,流体入,物品出,流体出}}）。 */
    private int[] ioCapacity = ShanhaiRecipeTypes.FALLBACK_MAX_IO.clone();

    // ---------------------------------------------------------------- 🆕 B 组：额外条件
    //
    // 用户点单（逐字）：「还有一些配方需要额外条件（例如超净间，或者我们的物质模块等级），
    //   你可以添加一个加号，然后可以通过这个加号来新增额外条件，同时，对于已有的条件，
    //   你应该也需要在面版中列出，并且右键可以编辑」
    //
    // 数据形状与 IO 同一套纪律：缓冲里存的就是【要落盘的那份 GT 平铺 JSON】，
    // 面板上显示/编辑的都是从它派生出来的读数（服务端权威，客户端只是把推下来的那份画出来）。

    /** 条件缓冲（GT 平铺形状；来源 = 载入时 live.conditions，或用户在面板上改的）。 */
    private com.google.gson.JsonArray conditions = new com.google.gson.JsonArray();

    /** 面板上"选中/正在编辑"的那一行（-1 = 没选）。 */
    private int condSel = -1;

    /** 条件页开没开（面板据此显隐；同时也决定了"编辑屏那一行按钮"上写什么）。 */
    private boolean condOpen;

    /** 「＋新增 / 改类型」那个下拉里选的类型名（键，不是标签）。 */
    private String condTypeKey = ShanhaiRecipeConditions.TYPE_CLEANROOM;

    /**
     * 🔴 2026-10-05 第 5 轮：编辑控件的值是<b>一个 key</b>（＝「档位」下拉里选的那一项），
     * <b>不再是</b>「模块 id ＋ 数量 ＋ 取反」三件套。
     *
     * <p>用户原话（逐字，他当场纠正过口径）：
     * <blockquote>「挡位应该根据上面的类型变动指的是我要是选物质模块等级，那挡位应该是17种物质模块」</blockquote>
     * ⇒ 「档位」这一个下拉就是那个类型自己的取值表：超净间给档位、物质模块等级给 17 种模块、
     * 维度给维度、没有取值的类型（无重力那种"要/不要"）<b>整个不出现这一栏</b>。
     * ⇒ 于是「模块 id 输入框」「数量」「手持」三样自然全都不需要了。
     */
    private String condValue = "";

    /** 「要求【不满足】这个条件」那个开关（原来叫"取反"，用户说看不懂 ⇒ 改了大白话）。 */
    private boolean condReverse;

    /** 一次编辑动作的机器可判读数（日志 + 面板状态行共用）。 */
    private String condNote = "(未动过条件)";

    /**
     * 🆕 第 5 轮：面板这一份条件是从<b>哪个来源</b>读出来的（{@code ledger} / {@code file} / {@code live}）。
     * <p>判据行 {@code workspace_conditions_source …} 会把它连同三个来源各自的条数一起打出来 ——
     * 上一轮就是缺了这一行，"面板显示丢了"才只能靠人去猜是哪一层。
     */
    private String condSource = "(未载入)";

    private boolean loadedRecipe;

    /** 🆕 第 7 轮（队列 #5）：当前这条活配方的类型对象 —— 算"真实耗时"要用它。 */
    private GTRecipeType liveType;

    // ---------------------------------------------------------------- 🆕 非 GT（工作台 / 原版配方）
    //
    // 用户 2026-10-05 原话：「然后工作台和原版配方你也加进清单里面」，并在"只做 minecraft: 那 12,673 条 /
    // 完整版 / 先不做"三选一里选了【完整版】。
    //
    // 🔴 这一整块与上面那条 GT 线【互不干扰】：判据是"这个类型在 GT 的注册表里有没有"。
    //    没有任何非 GT 编辑时，GT 那条路的每一行读数都与改动之前逐字节相同。

    /** 当前停在非 GT 那一支（由 {@link #selectType} / {@link #selectVanillaRecipe} 置位）。 */
    private boolean vanillaType;

    /** 当前编辑的那条非 GT 配方（活对象）。 */
    private net.minecraft.world.item.crafting.Recipe<?> vanillaLive;

    /** 它的只读视图（面板上那些读数都是从它派生的）。 */
    private ShanhaiVanillaRecipeView vanillaView;

    /**
     * 🆕 阶段 2：<b>输入侧的统一可编辑中间表示</b>（见 {@link ShanhaiVanillaRecipeShape}）。
     *
     * <p>它才是非 GT 输入的<b>权威缓冲</b>；面板上那几格显示的是它的镜像
     * （{@link #rebuildVanillaIo()} 把 shape 摊进 {@code io} 表格，格子的拖入/右键/中键
     * 都先改 shape 再回写镜像）—— 这样"已经验收过的格子控件"一行都不用重写。
     */
    private ShanhaiVanillaRecipeShape vanillaShape;

    // ── 下面这四个是【客户端那份】的读数：由 writeState 推下来（客户端没有配方表，
    //    也不许凭空造形状；面板据此决定画几个格子、按钮显不显示）。
    private int vanillaSyncMode = -1;
    private int vanillaSyncW;
    private int vanillaSyncH;
    private int vanillaSyncSlots;
    private String vanillaSyncTitle = "";
    private String vanillaSyncNote = "";
    private String vanillaSyncHint = "";

    /** 非 GT 编辑屏的一行说明（哪些能改 / 为什么不能改）。 */
    private String vanillaNote = "";

    // ---------------------------------------------------------------- 🆕 阶段 2：输入侧读数 / 动作

    /** 服务端那份的中间表示（客户端那份为 null ⇒ 别拿它做事，读数用下面那几个访问器）。 */
    public ShanhaiVanillaRecipeShape vanillaShape() {
        return vanillaShape;
    }

    /** 现在这份输入用哪种形态编辑（客户端读同步值，服务端读真对象）。 */
    public int vanillaShapeOrd() {
        return vanillaShape != null ? vanillaShape.mode().ordinal() : vanillaSyncMode;
    }

    /** 有形状合成的宽（非 GRID ⇒ -1）。 */
    public int vanillaGridW() {
        return vanillaShape != null ? vanillaShape.width() : vanillaSyncW;
    }

    /** 有形状合成的高（非 GRID ⇒ -1）。 */
    public int vanillaGridH() {
        return vanillaShape != null ? vanillaShape.height() : vanillaSyncH;
    }

    /** 输入缓冲里现在有几个物理格子（GRID 恒 9；LIST/SINGLE 等于槽数）。 */
    public int vanillaSlotSlots() {
        return vanillaShape != null ? vanillaShape.slotCount() : vanillaSyncSlots;
    }

    /**
     * 面板第 {@code i} 个物理位置此刻算不算"这一份输入的一部分"。
     * <p>GRID 里"格子外"的那几格返回 false ⇒ 面板画成不可交互的空位。
     */
    public boolean vanillaActiveCell(int i) {
        if (vanillaShape != null) {
            return vanillaShape.active(i);
        }
        // 客户端：GRID 用推下来的 w/h 现算，其它形态一律前 N 个
        if (vanillaSyncMode == ShanhaiVanillaRecipeShape.Mode.GRID.ordinal()) {
            return (i % ShanhaiVanillaRecipeShape.MAX_SIDE) < vanillaSyncW
                    && (i / ShanhaiVanillaRecipeShape.MAX_SIDE) < vanillaSyncH;
        }
        return i >= 0 && i < vanillaSyncSlots;
    }

    /** 编辑屏那一行"形状/列表"读数（客户端读的是服务端推下来的那一份）。 */
    public String vanillaShapeTitle() {
        return vanillaShape != null ? vanillaShape.titleText() : vanillaSyncTitle;
    }

    /** 「宽 −/+」「高 −/+」（{@code dw/dh} 各给 -1/0/+1）。只在服务端生效。 */
    public boolean vanillaResize(int dw, int dh) {
        if (blank() || vanillaShape == null || vanillaShape.mode() != ShanhaiVanillaRecipeShape.Mode.GRID) {
            return false;
        }
        final int nw = vanillaShape.width() + dw;
        final int nh = vanillaShape.height() + dh;
        final int cw = Math.max(1, Math.min(ShanhaiVanillaRecipeShape.MAX_SIDE, nw));
        final int ch = Math.max(1, Math.min(ShanhaiVanillaRecipeShape.MAX_SIDE, nh));
        if (cw == vanillaShape.width() && ch == vanillaShape.height()) {
            say("§7形状已经到头了", "§8宽/高只能在 1 到 3 之间");
            return false;
        }
        if (!vanillaShape.resize(nw, nh)) {
            say("§e缩不了：新范围的外侧还有材料", "§8先把那些格子里的东西清掉（右键），或者把它们挪进新范围");
            return false;
        }
        rebuildVanillaIo();
        touch();
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_resize id={} shape={}", PREFIX, recipeId,
                vanillaShape.statsLine());
        return true;
    }

    /** 「加一格」（无序列表专属）：追加一个空槽，等用户往里拖物品。 */
    public boolean vanillaAddEntry() {
        if (blank() || vanillaShape == null || vanillaShape.mode() != ShanhaiVanillaRecipeShape.Mode.LIST) {
            return false;
        }
        if (!vanillaShape.addEntry()) {
            say("§e加不了了", "§8无序列表最多 " + ShanhaiVanillaRecipeShape.MAX_LIST + " 格（原版合成容器就是 3×3）");
            return false;
        }
        rebuildVanillaIo();
        touch();
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_add_entry id={} shape={}", PREFIX, recipeId,
                vanillaShape.statsLine());
        return true;
    }

    /** 「− 删一格」（无序列表专属）：把最后一格去掉（还剩 1 格时拒删）。 */
    public boolean vanillaRemoveLastEntry() {
        if (blank() || vanillaShape == null || vanillaShape.mode() != ShanhaiVanillaRecipeShape.Mode.LIST) {
            return false;
        }
        final int last = vanillaShape.slotCount() - 1;
        if (!vanillaShape.removeAt(last)) {
            say("§e至少要留一格", "§8想删掉整条配方，请用下面那颗「删除这条」");
            return false;
        }
        rebuildVanillaIo();
        touch();
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_remove_entry id={} shape={}", PREFIX, recipeId,
                vanillaShape.statsLine());
        return true;
    }

    /** 面板：这一屏要不要画「宽/高」那四颗按钮。 */
    public boolean vanillaShapedButtons() {
        return (vanillaShape != null ? vanillaShape.mode().ordinal() : vanillaSyncMode)
                == ShanhaiVanillaRecipeShape.Mode.GRID.ordinal();
    }

    /** 面板：这一屏要不要画「加一格 / − 删一格」那两颗按钮。 */
    public boolean vanillaListButtons() {
        return (vanillaShape != null ? vanillaShape.mode().ordinal() : vanillaSyncMode)
                == ShanhaiVanillaRecipeShape.Mode.LIST.ordinal();
    }

    // ---------------------------------------------------------------- 输入缓冲 ↔ io 镜像

    /**
     * 把 {@link #vanillaShape} 摊进 {@code io} 表格（面板上那些格子读的就是 {@code io}）。
     *
     * <p>🔴 为什么要有这一层镜像：面板的格子控件（拖入 / 右键删 / 中键改数量）是
     * <b>已经验收过</b>的东西，本刀一行都不想重写。让它们继续读 {@code io}，
     * 而 {@code io} 的内容由 shape 决定 —— 两边永远一致，且"哪些格不能动"由
     * {@link #offerIngredient} 的入口闸门统一负责。
     *
     * <p>⚠️ 产物的那一格<b>不</b>归 shape 管（它属于 {@code Edit.result}），
     * 所以这里要把它的当前值（含 dirty 标记）先存下来再重建。
     */
    private void rebuildVanillaIo() {
        if (vanillaShape == null || vanillaView == null) {
            return;
        }
        final ItemStack prevOut;
        final boolean prevOutDirty;
        if (io.outSection() > 0) {
            final ShanhaiIoTable.Cell oc = io.cell(io.outIndex(0));
            prevOut = oc == null ? ItemStack.EMPTY : oc.item.copy();
            prevOutDirty = oc != null && oc.dirty;
        } else {
            prevOut = ItemStack.EMPTY;
            prevOutDirty = false;
        }
        // GRID：物理格恒 9（3×3 网格要画满）；LIST/SINGLE：等于槽数
        final int inN = Math.max(1, vanillaShape.layoutCount());
        final int outN = vanillaView.canEditResult() ? 1 : 0;
        if (io.itemIn() != inN || io.fluidIn() != 0 || io.itemOut() != outN || io.fluidOut() != 0) {
            io.resize(inN, 0, outN, 0);
        }
        io.clearAll();
        for (int i = 0; i < inN; i++) {
            final ShanhaiIoTable.Cell c = io.cell(i);
            if (c == null) {
                continue;
            }
            c.item = vanillaShape.shown(i).copy();
            c.fluid = FluidStack.empty();
            c.dirty = false;                 // io 这侧只是镜像；"脏不脏"由 shape 自己记
            c.chance = 10000;
            c.maxChance = 10000;
            c.tierChanceBoost = 0;
            c.notConsumable = false;
            c.chanceDirty = false;
            c.catalystRepair = false;
        }
        if (outN > 0) {
            final ShanhaiIoTable.Cell oc = io.cell(io.outIndex(0));
            if (oc != null) {
                oc.item = prevOutDirty ? prevOut
                        : (vanillaView.result.isEmpty() ? ItemStack.EMPTY : vanillaView.result.copy());
                oc.fluid = FluidStack.empty();
                oc.dirty = prevOutDirty;
            }
        }
    }

    /** 这不是一个 GT 配方类型吗（判据 = GT 的注册表里查不到）。 */
    public static boolean isVanillaTypeId(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        try {
            return com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(id) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 面板用：这一屏是不是在编辑一条非 GT 配方（据此藏掉 GT 专用控件）。 */
    public boolean vanillaMode() {
        return vanillaType;
    }

    /** 面板用：这一条有没有"时间"这个字段（GT 恒有；非 GT 只有烧炼那一族有）。 */
    public boolean hasDurationField() {
        if (!vanillaType) {
            return true;
        }
        return vanillaView != null && vanillaView.canEditCooking();
    }

    /** 编辑屏那一行「耗时」的标签（非 GT 的烧炼配方改的是烧炼时间）。 */
    public String durationLabel() {
        return vanillaType ? "§7烧炼时间(t)" : "§7耗时(t)";
    }

    /** 非 GT 编辑屏的那一行说明。 */
    public String vanillaNoteText() {
        if (blank() || !vanillaType) {
            return "";
        }
        return vanillaNote;
    }

    /** 🆕 阶段 2：输入那一侧的操作说明（客户端读服务端推下来的那一份）。 */
    public String vanillaShapeNote() {
        return vanillaShape != null ? vanillaShape.noteText() : vanillaSyncNote;
    }

    /** 🆕 阶段 2：第二行说明（"这个形态怎么增删"）。 */
    public String vanillaShapeHint() {
        return vanillaShape != null ? vanillaShape.hintText() : vanillaSyncHint;
    }

    // ---------------------------------------------------------------- 🆕 第 7 轮：查询
    //
    // 用户点单（逐字，2026-10-05）：
    //   「第一：在这下面加一个允许我放任意物品的框（我可以把物品通过jei拖入），下面有3个按钮：
    //     1：获取途径，2：作为物品的用处，3：作为机器的用处」
    //   「第二：文本搜索框，可输入中英文，直接匹配配方id和配方种类名称/id」
    // 三个口径他后来逐条确认过：「这下面＝第一屏」「作为机器的用处＝这个物品当机器用时能跑哪些配方」
    //   「改为选择按钮＝选中之后直接进第三屏编辑这条配方」。

    /** 这个物品槽里放的东西（服务端权威；客户端那份只是显示）。 */
    private ItemStack queryItem = ItemStack.EMPTY;

    /**
     * 文本搜索框里的字。
     *
     * <p>⚠️ 这个字段<b>不</b>受 {@link #blank()} 影响 —— 因为输入框要用它做
     * {@code TextFieldWidget} 的 supplier，而那个 supplier 在<b>两侧都会被调</b>
     * （客户端用它判断"要不要把用户刚打的字抹掉"）。所以它必须是纯字段。
     */
    private String searchText = "";

    /** 当前结果是怎么查出来的（左键"获取途径"／文本搜索…）。 */
    private ShanhaiRecipeQuery.Kind queryKind = ShanhaiRecipeQuery.Kind.NONE;

    /** 一次查询的结论（按配方种类分好组）。 */
    private ShanhaiRecipeQuery.Result queryResult =
            new ShanhaiRecipeQuery.Result(ShanhaiRecipeQuery.Kind.NONE, List.of(), 0, "(还没查过)");

    /** 顶部那一排里当前选中的是第几组。 */
    private int tabIndex;

    /** 顶部那一排翻到第几页（每页 {@link #TABS_PER_PAGE} 个）。 */
    private int tabPage;

    /** 文本搜索的防抖：最后一次改动是第几个 tick。 */
    private long textDirtyTick = -1;
    private boolean textDirty;
    private long tickCounter;

    /** 从哪一屏进的编辑屏（"返回"要回到那一屏、那一页 —— 队列 #7）。 */
    private Stage returnStage = Stage.RECIPES;
    private int returnPage;
    /** 从类型列表进配方列表时记下它翻到第几页。 */
    private int typesPage;

    /** 客户端那一份：顶部一排的标签页（服务端推下来的，最多 {@link #TABS_PER_PAGE} 条）。 */
    private List<ShanhaiRecipeQuery.Tab> tabs = List.of();
    /** 客户端那一份：本页要画的卡片（服务端推下来的）。 */
    private List<ShanhaiRecipeQuery.Card> cards = List.of();

    private String status1 = "§7正在读出配方类型…";
    private String status2 = "§8先选配方类型 ⇒ 再选配方 ⇒ 进编辑";

    public ShanhaiRecipeEditorWorkspace(MinecraftServer server, Player player) {
        this.server = server;
        this.player = player;
    }

    // ---------------------------------------------------------------- 读数

    public boolean available() {
        return server != null;
    }

    public Stage stage() {
        return stage;
    }

    /** 客户端渲染要用：编辑段才画格子与数字控件。 */
    public boolean stageIsEdit() {
        return stage == Stage.EDIT;
    }

    public boolean stageIsList() {
        return stage != Stage.EDIT;
    }

    public int version() {
        return version;
    }

    public int numbersVersion() {
        return numbersVersion;
    }

    public int pendingDuration() {
        return pendingDuration;
    }

    public int pendingEut() {
        return pendingEut;
    }

    public int tierIndex() {
        return tierIndex;
    }

    public int amperage() {
        return amperage;
    }

    public int inPage() {
        return inPage;
    }

    public int outPage() {
        return outPage;
    }

    /** 这一栏一共几页（至少 1）。 */
    public int inPageCount() {
        return pageCountOf(io.inSection());
    }

    public int outPageCount() {
        return pageCountOf(io.outSection());
    }

    private static int pageCountOf(int section) {
        return Math.max(1, (section + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE);
    }

    /** 输入栏第 {@code slot} 格对应的整表下标（越界 = 这一页没这一格）。 */
    public int inCellIndex(int slot) {
        final int idx = inPage * CELLS_PER_PAGE + slot;
        return idx >= 0 && idx < io.inSection() ? io.inIndex(idx) : -1;
    }

    /** 输出栏第 {@code slot} 格对应的整表下标（越界 = 这一页没这一格）。 */
    public int outCellIndex(int slot) {
        final int idx = outPage * CELLS_PER_PAGE + slot;
        return idx >= 0 && idx < io.outSection() ? io.outIndex(idx) : -1;
    }

    /** 当前这条配方的 IO 上限四元组（面板标题里要显示）。 */
    public String capacityText() {
        return "§8物品入 " + io.itemIn() + " · 流体入 " + io.fluidIn()
                + " · 物品出 " + io.itemOut() + " · 流体出 " + io.fluidOut();
    }

    public int pendingCount() {
        return pendingCount;
    }

    public int countCell() {
        return countCell;
    }

    public ShanhaiIoTable.Cell cell(int index) {
        return io.cell(index);
    }

    public int cells() {
        return io.cellCount();
    }

    public String status1() {
        return status1;
    }

    public String status2() {
        return status2;
    }

    /** 客户端那份所有文字都返回空串（画出来的是服务端推下来的那份）。 */
    private boolean blank() {
        return server == null;
    }

    private void touch() {
        version++;
    }

    private void say(String a, String b) {
        status1 = a;
        status2 = b;
        touch();
    }

    // ---------------------------------------------------------------- ①② 列表

    /**
     * 进面板 / 点"重读配方表"：把类型列表重新算一遍。
     *
     * <h4>🔴 队列 #4（本轮）：类型列表现在是【当前表里的类型】∪【开机快照里的类型】</h4>
     * 用户原话（逐字）：
     * <blockquote>「如果我把一个配方种类下面的配方<b>都删完了</b>，就算我输入 {@code /shanhai edit restore}，
     * 然后在面板里点<b>重读配方表</b>，那个配方种类<b>也不会显示了</b>，必须要<b>退存档重进</b>才能再看见」</blockquote>
     * 根因：改之前 {@code types} <b>只</b>由下面那张 {@code byType} 计数表派生，而那张表只收
     * "当前表里还有配方的类型" ⇒ 删空之后那个键根本不存在 ⇒ 列表里没有它，重读也没用
     * （读的还是同一张已经空了的表）。
     * <p>修法见 {@link ShanhaiRecipeTypeRoster}：先按当前表算出这一遍的"实有类型"，
     * <b>记进开机快照</b>，再取<b>并集</b>。删空的类型因此仍然在列表里、条数如实显示 <b>0 条</b>。
     * 「重读配方表」走的就是本方法 ⇒ 它<b>同时</b>重建了这张列表（用户点单的第二条要求）。
     */
    public void reloadTypes() {
        if (blank()) {
            return;
        }
        ShanhaiRecipeReverseIndex.invalidate();
        ShanhaiRecipeReverseIndex.ensure(server);
        final int vanillaRows = buildTypeRows();
        stage = Stage.TYPES;
        page = 0;
        recipeId = null;
        loadedRecipe = false;
        // 🆕 第 7 轮：配方表重建了 ⇒ 上一次查询的结果作废（它引用的 id 可能已经不在了）
        queryResult = new ShanhaiRecipeQuery.Result(ShanhaiRecipeQuery.Kind.NONE, List.of(), 0, "(还没查过)");
        queryKind = ShanhaiRecipeQuery.Kind.NONE;
        tabIndex = 0;
        tabPage = 0;
        cards = List.of();
        tabs = List.of();
        say("§a共 §f" + types.size() + " §a种配方类型",
                "§8" + ShanhaiRecipeReverseIndex.statsLine()
                        + (vanillaRows == 0 ? "" : " · 非 GT " + vanillaRows + " 种"));
        ShanhaiMod.LOGGER.info("{} workspace_types types={} recipes={} vanilla_types={} vanilla_rules={} "
                        + "roster=[{}]",
                PREFIX, types.size(), ShanhaiRecipeReverseIndex.size(),
                vanillaRows, ShanhaiVanillaRecipeTable.liveSize(),
                ShanhaiRecipeTypeRoster.statsLine());
    }

    /**
     * 🔴🔴 <b>重建第一屏那份「类型 → 条数」（用户 2026-10-05 报：第一屏还显示旧条数）。</b>
     *
     * <h4>现场</h4>
     * 用户原话：「但是配方编辑器还是会显示旧的配方条数（<b>在第一页中</b>），需要重读配方表才能同步」——
     * 第二屏已经能同步了（那条路走的是 {@code rebuildCurrentList}），而第一屏的 {@code (N 条)} 是
     * <b>点开面板那一刻算的</b>，之后没人重算。
     *
     * <h4>口径（用户点名要的，别搞混）</h4>
     * · <b>类型集合</b> = 「类型花名册」= 当前表 ∪ 开机快照（删空的类型仍然在列表里、条数如实显示 0）✓
     * · <b>条数</b> = <b>按当前活配方表现算</b>（GT 走反查索引，非 GT 走
     *   {@link ShanhaiVanillaRecipeTable#countOf}）✓
     *
     * <p>⚠️ 本方法<b>只重算列表内容</b>：不动 stage / page / 当前选中
     * （那是 {@link #reloadTypes()} 的语义，那个是"用户点了重读配方表"）。
     *
     * @return 非 GT 那一段收进来几种类型（读数用）
     */
    private int buildTypeRows() {
        types.clear();
        final Map<ResourceLocation, int[]> byType = new LinkedHashMap<>();
        final Map<ResourceLocation, String[]> nameByType = new LinkedHashMap<>();
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            final GTRecipeType t = r.getType();
            final ResourceLocation key = t == null || t.registryName == null
                    ? new ResourceLocation("minecraft", "unknown") : t.registryName;
            byType.computeIfAbsent(key, k -> new int[1])[0]++;
            if (!nameByType.containsKey(key)) {
                final ShanhaiRecipeEditorSession.TypeName tn = ShanhaiRecipeEditorSession.typeName(
                        t == null ? null : t);
                nameByType.put(key, new String[]{tn.text(), tn.source()});
            }
        }
        // ── ① 当前表里"真的有配方"的那些类型 ──
        final List<TypeRow> current = new ArrayList<>(byType.size());
        for (Map.Entry<ResourceLocation, int[]> e : byType.entrySet()) {
            final String[] n = nameByType.get(e.getKey());
            current.add(new TypeRow(e.getKey(), n == null ? e.getKey().getPath() : n[0],
                    n == null ? "fallback-id" : n[1], e.getValue()[0]));
        }
        // ── ② 记进开机快照（第一次调用 = 装机），再取并集 ──
        ShanhaiRecipeTypeRoster.observe(current);
        // ── ③ 非 GT（工作台 / 原版配方）：它们的类型不在 GT 的注册表里，得单独收一遍 ──
        //    放在列表【最前面】：这一批是用户点名要加的，藏在第 17 页后面等于没加。
        final List<TypeRow> vanillaRows = new ArrayList<>();
        try {
            ShanhaiVanillaRecipeTable.captureIfAbsent(server);
            ShanhaiVanillaRecipeTable.rebuildIndex(server);
            for (ResourceLocation vt : ShanhaiVanillaRecipeTable.types()) {
                vanillaRows.add(new TypeRow(vt, ShanhaiVanillaRecipeView.typeLabel(vt),
                        "vanilla-recipe", ShanhaiVanillaRecipeTable.countOf(vt)));
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} workspace_vanilla_types_failed {}", PREFIX, t.toString(), t);
        }
        types.addAll(vanillaRows);
        types.addAll(ShanhaiRecipeTypeRoster.union(current));
        return vanillaRows.size();
    }

    /** 选了某个类型 ⇒ 列它的配方。 */
    public boolean selectType(ResourceLocation id) {
        if (blank() || id == null) {
            return false;
        }
        if (stage == Stage.TYPES) {
            typesPage = page;              // 队列 #7：从类型列表进来时记下页码
        }
        // 🆕 非 GT（工作台 / 原版配方）：类型不在 GT 注册表里 ⇒ 走另一条列表来源。
        if (isVanillaTypeId(id)) {
            return selectVanillaType(id);
        }
        vanillaType = false;
        vanillaLive = null;
        vanillaView = null;
        vanillaNote = "";
        vanillaShape = null;          // 🆕 阶段 2：切回 GT 那一支 ⇒ 输入侧的中间表示也丢掉
        typeId = id;
        recipes.clear();
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            final GTRecipeType t = r.getType();
            if (t != null && t.registryName != null && t.registryName.equals(id) && r.id != null) {
                recipes.add(r.id);
            }
        }
        recipes.sort(Comparator.comparing(ResourceLocation::toString));
        stage = Stage.RECIPES;
        page = 0;
        recipeId = null;
        loadedRecipe = false;
        final String zh = ShanhaiRecipeEditorSession.typeName(id.getPath()).text();
        say("§a" + zh + " §7（§f" + id + "§7）共 §f" + recipes.size() + " §7条配方",
                "§8点一张卡片进编辑 · 「返回」回类型列表");
        ShanhaiMod.LOGGER.info("{} workspace_type_selected type={} recipes={}", PREFIX, id, recipes.size());
        return true;
    }

    /**
     * 选了某条配方 ⇒ 把它的 IO 读进缓冲。
     *
     * <p>🆕 第 7 轮两处改动（都是为了"从查询结果直接进编辑"这条路能走通）：
     * <ol>
     *   <li>把 {@code typeId} 与 {@code recipes} 也切到<b>这条配方自己的类型</b> ——
     *       否则从查询结果点进来时，编辑屏顶部会显示上一个类型的名字、"返回"也会回到一个
     *       与这条配方无关的列表；</li>
     *   <li>记下 {@link #returnStage} / {@link #returnPage} ⇒ "返回"回到<b>来的那一屏、那一页</b>
     *       （队列 #7）。</li>
     * </ol>
     */
    public boolean selectRecipe(ResourceLocation id) {
        if (blank() || id == null) {
            return false;
        }
        // 🆕 非 GT（工作台 / 原版配方）：GT 反查索引里【一条都收不到】非 GT 配方
        //    （ShanhaiRecipeReverseIndex 那 5 处扫描循环第一句就是 `instanceof GTRecipe`），
        //    所以先在这里分一次流 —— 分流不成功再走原来的 GT 路。
        if (vanillaType) {
            final net.minecraft.world.item.crafting.Recipe<?> vr = vanillaFromIndexOrTable(id);
            if (vr != null) {
                return selectVanillaRecipe(vr);
            }
            say("§c这条配方已经不在了", "§8点「重读配方表」再来");
            return false;
        }
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, id);
        if (live == null) {
            // 兜底：万一用户是从一条非 GT 的卡片点进来的（类型上下文没跟上）
            final net.minecraft.world.item.crafting.Recipe<?> vr = vanillaFromIndexOrTable(id);
            if (vr != null) {
                return selectVanillaRecipe(vr);
            }
            say("§c这条配方已经不在了", "§8点「重读配方表」再来");
            return false;
        }
        if (stage == Stage.RECIPES || stage == Stage.QUERY) {
            returnStage = stage;
            returnPage = page;
        }
        // 把类型上下文切到这条配方自己的类型（同类型时不重建列表，省一次全表扫）
        final GTRecipeType t = live.getType();
        final ResourceLocation liveType = t == null ? null : t.registryName;
        if (liveType != null && !liveType.equals(typeId)) {
            selectTypeKeepStage(liveType);
        }
        recipeId = id;
        loadBuffer(live);
        stage = Stage.EDIT;
        say("§a已载入 §f" + ShanhaiRecipeEditorSession.shortId(id),
                "§8" + ShanhaiRecipeEditorSession.typeName(live.getType()).text()
                        + " · 输入 " + io.count("inputs") + " 条 · 输出 " + io.count("outputs") + " 条");
        ShanhaiMod.LOGGER.info("{} workspace_recipe_loaded id={} type={} dur={} eu={} euTier={} in={} out={}",
                PREFIX, id, live.getType() == null ? "?" : live.getType().registryName,
                live.duration, ShanhaiRecipeIoApply.euOf(live), live.data.getInt("euTier"),
                io.count("inputs"), io.count("outputs"));
        return true;
    }

    /** 只换"当前类型 + 它的配方表"，<b>不动 stage 与 page</b>（{@link #selectRecipe} 用）。 */
    private void selectTypeKeepStage(ResourceLocation id) {
        if (isVanillaTypeId(id)) {
            selectVanillaType(id);
            return;
        }
        vanillaType = false;
        vanillaLive = null;
        vanillaView = null;
        typeId = id;
        recipes.clear();
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            final GTRecipeType t = r.getType();
            if (t != null && t.registryName != null && t.registryName.equals(id) && r.id != null) {
                recipes.add(r.id);
            }
        }
        recipes.sort(Comparator.comparing(ResourceLocation::toString));
    }

    // ================================================================= 🆕 非 GT（工作台 / 原版配方）

    /** 选了一个非 GT 类型 ⇒ 列这一类型下的配方（来源不是 GT 反查索引）。 */
    private boolean selectVanillaType(ResourceLocation id) {
        vanillaType = true;
        typeId = id;
        vanillaLive = null;
        vanillaView = null;
        vanillaNote = "";
        liveType = null;
        try {
            ShanhaiVanillaRecipeTable.captureIfAbsent(server);
            ShanhaiVanillaRecipeTable.rebuildIndex(server);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} workspace_vanilla_index_failed {}", PREFIX, t.toString(), t);
        }
        recipes.clear();
        recipes.addAll(ShanhaiVanillaRecipeTable.idsOf(id));
        stage = Stage.RECIPES;
        page = 0;
        recipeId = null;
        loadedRecipe = false;
        final String zh = ShanhaiVanillaRecipeView.typeLabel(id);
        say("§a" + zh + " §7（§f" + id + "§7）共 §f" + recipes.size() + " §7条配方",
                "§8非 GT 那一条路 · 点卡片右下角的「选择」进第三屏");
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_type_selected type={} recipes={}",
                PREFIX, id, recipes.size());
        return true;
    }

    /** 先在纯缓存索引里找，找不到再老老实实扫一遍 {@code RecipeManager}。 */
    private net.minecraft.world.item.crafting.Recipe<?> vanillaFromIndexOrTable(ResourceLocation id) {
        net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
        if (r != null) {
            return r;
        }
        if (!ShanhaiVanillaRecipeTable.isIndexBuilt()) {
            try {
                ShanhaiVanillaRecipeTable.rebuildIndex(server);
                r = ShanhaiVanillaRecipeTable.liveById(id);
                if (r != null) {
                    return r;
                }
            } catch (Throwable ignored) {
                // 落到下面那条扫表路
            }
        }
        return ShanhaiVanillaRecipeOps.readFromTable(server, id);
    }

    /** 选中一条非 GT 配方 ⇒ 把它的输入/产物读进缓冲并进第三屏。 */
    private boolean selectVanillaRecipe(net.minecraft.world.item.crafting.Recipe<?> live) {
        final ResourceLocation id = live.getId();
        if (id == null) {
            say("§c这条配方没有 id（编辑器按 id 索引，定位不到）", "§8class=" + live.getClass().getName());
            return false;
        }
        if (stage == Stage.RECIPES || stage == Stage.QUERY) {
            returnStage = stage;
            returnPage = page;
        }
        vanillaType = true;
        final ResourceLocation liveTypeId = ShanhaiVanillaRecipeTable.typeIdOf(live);
        if (liveTypeId != null && !liveTypeId.equals(typeId)) {
            selectVanillaType(liveTypeId);
        }
        recipeId = id;
        loadVanillaBuffer(live);
        stage = Stage.EDIT;
        say("§a已载入 §f" + ShanhaiRecipeEditorSession.shortId(id),
                "§8" + vanillaView.typeLabel() + " · 输入 " + vanillaView.inputs.size()
                        + " 格 · 产物 " + (vanillaView.result.isEmpty() ? "(无)"
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(vanillaView.result.getItem()) + " x" + vanillaView.result.getCount()));
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_recipe_loaded id={} type={} kind={} in={} out={} "
                        + "cook={} xp={} editable={}",
                PREFIX, id, vanillaView.typeId, vanillaView.kind, vanillaView.inputs.size(),
                vanillaView.result.isEmpty() ? "(empty)"
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(vanillaView.result.getItem()) + " x" + vanillaView.result.getCount(),
                vanillaView.cookingTime, vanillaView.experience, vanillaView.editable());
        return true;
    }

    /**
     * 把一条非 GT 配方的<b>输入 / 产物 / 时间</b>读进编辑缓冲。
     *
     * <h4>🆕 阶段 2：输入侧改成"中间表示 ＋ io 镜像"两层</h4>
     * 权威缓冲是 {@link ShanhaiVanillaRecipeShape}（GRID / LIST / SINGLE 三种形态），
     * {@code io} 表格只是它的<b>镜像</b> —— 面板上那些格子控件（JEI 拖入 / 右键 / 中键改数量）
     * 是已经验收过的，本刀一行都不重写。
     * <pre>
     *   有形状合成   ⇒ GRID：9 个物理格（3×3），其中 w×h 那一块是"配方实际占的形状"
     *   无形状合成   ⇒ LIST：1..9 个槽，顺序无关
     *   烧炼 / 切石机 ⇒ SINGLE：恰好 1 格
     *   其它 55 种类型 ⇒ NONE：不给编辑（面板给一句可见提示）
     * </pre>
     */
    private void loadVanillaBuffer(net.minecraft.world.item.crafting.Recipe<?> live) {
        final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(live);
        vanillaLive = live;
        vanillaView = v;
        liveType = null;
        vanillaShape = ShanhaiVanillaRecipeShape.of(live);
        final int itemOut = Math.max(1, v.canEditResult() ? 1 : 0);
        // 先按目标形状开好格子（rebuildVanillaIo 里会用新 shape 再对齐一次）
        io.resize(Math.max(1, vanillaShape == null ? 1 : vanillaShape.layoutCount()), 0, itemOut, 0);
        io.clearAll();
        rebuildVanillaIo();
        // 「耗时(t)」这个输入框：烧炼那一族 = 烧炼时间；其它类型没有这个字段（面板会把它藏掉）
        pendingDuration = v.canEditCooking() ? Math.max(0, v.cookingTime) : 0;
        pendingEut = 0;
        tierIndex = -1;
        amperage = 1;
        inPage = 0;
        outPage = 0;
        countCell = -1;
        conditions = new com.google.gson.JsonArray();
        condSel = -1;
        condOpen = false;
        loadedRecipe = true;
        vanillaNote = v.editable()
                ? "§7这条是§f原版配方§7：可以改 §f产物物品 / 产物数量"
                + (v.canEditCooking() ? " / §f烧炼时间 / §f经验" : "")
                + " §7；§f输入也能改§7（拖入＝改/增，右键＝删，中键＝改数量）。"
                : v.whyNotEditable();
        numbersVersion++;
        touch();
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_io_shape id={} type={} kind={} "
                        + "shape={}/{}/{}/{} cells={} pages(in={} out={}) editable={} note_source={} | {}",
                PREFIX, live.getId(), v.typeId, v.kind, io.itemIn(), io.fluidIn(), io.itemOut(), io.fluidOut(),
                io.cellCount(), inPageCount(), outPageCount(), v.editable(),
                v.editable() ? "editable" : "not-editable",
                vanillaShape == null ? "(no shape)" : vanillaShape.statsLine());
    }

    /** 把一条活配方的 IO 读进编辑缓冲（"载入配方"与"保存之后重新对齐"共用这一条）。 */
    private void loadBuffer(GTRecipe live) {
        // 🔴 先按【这条配方的类型】决定四元组，再开格子（用户点单 B3）。
        //    两个来源取大：① 类型自己的 setMaxIOSize（经 GTRecipeType 读，原版类型也覆盖）；
        //                  ② 这条配方实际用到的条数（保证任何配方都不会被截断 —— 用户说过
        //                     "非常巨大的输入和输出格"，宁可多留不可少开）。
        final int[] cap = ShanhaiRecipeTypes.maxIoOf(live.getType());
        final int[] used = ShanhaiIoTable.usedBy(live);
        final int[] shape = new int[]{
                Math.max(cap[0], used[0]), Math.max(cap[1], used[1]),
                Math.max(cap[2], used[2]), Math.max(cap[3], used[3])};
        this.ioCapacity = shape;
        liveType = live.getType();
        io.resize(shape[0], shape[1], shape[2], shape[3]);
        io.clearAll();
        io.copyFrom(ShanhaiIoTable.fromRecipe(live, shape[0], shape[1], shape[2], shape[3]));
        // 🔴 2026-10-05（duration 原始值）：面板上显示/编辑的是【原始值】，不是 GTRecipe 上那个实际值。
        //    ⚠️ 这个数来自 ShanhaiRecipeDuration 那张表 —— 保存/恢复时必须同步它，否则重开会显示回旧值
        //       （用户实测那条「关闭后再打开编辑器里面重新变回了 1200」）。
        final Integer durFromTable = ShanhaiRecipeDuration.rawOriginal(live.id);
        pendingDuration = ShanhaiRecipeDuration.originalOf(live);
        pendingEut = (int) Math.max(0, Math.min(Integer.MAX_VALUE, ShanhaiRecipeIoApply.euOf(live)));
        tierIndex = tierOf(pendingEut);
        amperage = amperageOf(pendingEut, tierIndex);
        // 🆕 B 组：条件缓冲跟着活配方重新对齐（dirty 全清）
        // 🔴🔴 2026-10-05 第 5 轮修的就是这一行 —— 用户原话：「6：额外条件重进存档会丢失」。
        //    真相（原始读数）：覆盖文件里明明有 conditions、开机重放也 APPLIED 成功，
        //    而面板重开时读的是【活配方对象】那一条路：
        //        workspace_display_duration id=shanhai:zero_point_conversion/test/… live=1 conditions=0
        //    ⇒ 数据没丢，是面板读不到。这和上一轮"耗时改完重开变回旧值"是同一个病
        //    （面板读的那份来源与"当前生效值"不是同一份）。
        //    ⇒ 修法照 duration 那条：**按优先级解析"当前生效值"**，并把三个来源一起打进日志。
        loadConditionsFor(live);
        condSel = -1;
        condOpen = false;
        inPage = 0;
        outPage = 0;
        countCell = -1;
        loadedRecipe = true;
        numbersVersion++;
        touch();
        ShanhaiMod.LOGGER.info("{} workspace_io_shape id={} type={} shape={}/{}/{}/{} (type_max={}/{}/{}/{} used={}/{}/{}/{})"
                        + " cells={} pages(in={} out={})",
                PREFIX, live.id, live.getType() == null ? "?" : live.getType().registryName,
                shape[0], shape[1], shape[2], shape[3],
                cap[0], cap[1], cap[2], cap[3], used[0], used[1], used[2], used[3],
                io.cellCount(), inPageCount(), outPageCount());
        // 🔴 这一行就是本轮那个显示 bug 的机器判据：面板【重开/重新载入】之后读到的耗时是哪个数。
        //    期望：用户改成 5000 并保存之后，这里必须打 5000（不是开机时那一份 1200）。
        ShanhaiMod.LOGGER.info("{} workspace_display_duration id={} shown={} table_now={} table_boot={} "
                        + "live={} conditions={} cond_types={}",
                PREFIX, live.id, pendingDuration,
                durFromTable == null ? "(absent)" : durFromTable,
                ShanhaiRecipeDuration.bootOriginal(live.id) == null
                        ? "(absent)" : ShanhaiRecipeDuration.bootOriginal(live.id),
                live.duration, conditions.size(), ShanhaiRecipeConditions.summary(conditions));
    }

    /**
     * 🔴 <b>把"当前生效的额外条件"读进面板缓冲</b> —— 第 5 轮修 #6 的那一条。
     *
     * <h4>为什么不能直接读 {@code live.conditions}</h4>
     * 用户实测（原始读数，见本轮报告）：覆盖文件里有 conditions、开机重放 {@code APPLIED} 成功，
     * 而面板重开时读活配方对象得到的是 <b>0 条</b>。也就是说"面板读的那一份"与"当前生效值"
     * <b>不是同一份</b> —— 这跟上一轮"耗时改完重开变回旧值"是同一个病。
     *
     * <h4>三个来源，按"离用户的意图最近"排序</h4>
     * <pre>
     *   ① 台账（本局刚改过、还没重启）      —— 最权威：那是用户这一局亲手选的值
     *   ② 覆盖文件 entry.fields.conditions —— 持久化的真值；开机重放读的就是它
     *   ③ 活配方 live.conditions            —— 最后兜底
     * </pre>
     * 三者都被打进一行 {@code workspace_conditions_source}，<b>包括"谁和谁不一致"</b> ——
     * 上一轮缺的就是这一行读数，所以"面板显示丢了"只能靠人猜在哪一层。
     */
    private void loadConditionsFor(GTRecipe live) {
        final Resolved r = resolveConditions(server, live.id, live);
        conditions = r.conditions();
        condSource = r.source();
        condNote = "载入时 " + conditions.size() + " 条（来源 " + condSource + "）";

        ShanhaiMod.LOGGER.info("{} workspace_conditions_source id={} source={} n={} "
                        + "ledger={} file={} live={} ledger_vs_live={} file_vs_live={} types={}",
                PREFIX, live.id, condSource, conditions.size(),
                r.ledgerN() < 0 ? "(none)" : String.valueOf(r.ledgerN()),
                r.fileN() < 0 ? "(none)" : String.valueOf(r.fileN()),
                r.liveN(), r.ledgerAgreesLive(), r.fileAgreesLive(),
                ShanhaiRecipeConditions.summary(conditions));
        if (!r.ledgerAgreesLive() || !r.fileAgreesLive()) {
            // 不判红：文件/live 不一致在"这一局刚改完还没落盘"等场合是正常的。
            // 但必须留一条能 grep 的痕迹 —— 这正是上一轮没有的那条线。
            ShanhaiMod.LOGGER.warn("{} workspace_conditions_source MISMATCH id={} source={} "
                            + "ledger_n={} file_n={} live_n={} ⇒ 面板显示的是 source={} 那一份；"
                            + "若用户报「重开就没了」，就靠这一行定位是哪一层没跟上",
                    PREFIX, live.id, condSource, r.ledgerN(), r.fileN(), r.liveN(), condSource);
        }
    }

    /**
     * 三个来源各自读一次之后的结论（给日志与自检共用 —— 自检要的正是"面板读得到哪一份"）。
     *
     * @param conditions      面板该显示的那一份（永不为 null）
     * @param source          {@code ledger} / {@code file} / {@code live}
     * @param ledgerN        台账里几条（{@code -1} = 台账里没有这一条）
     * @param fileN          覆盖文件里几条（{@code -1} = 文件里没写条件）
     * @param liveN          活配方上几条
     */
    public record Resolved(com.google.gson.JsonArray conditions, String source,
                           int ledgerN, int fileN, int liveN,
                           boolean ledgerAgreesLive, boolean fileAgreesLive) {}

    /**
     * 🔴 <b>「面板重开时该显示哪一份条件」的纯函数版</b>（第 5 轮修 #6 的核心）。
     *
     * <p>优先级：<b>台账 → 覆盖文件 → 活配方</b>。理由：
     * <ol>
     *   <li>台账 = 用户这一局刚改的（最贴他的意图）；</li>
     *   <li>覆盖文件 = 持久化的真值，开机重放读的就是它 ⇒ <b>"重启之后还在不在"由它决定</b>；</li>
     *   <li>活配方 = 兜底（这条从来没有被改过）。</li>
     * </ol>
     * <p>把它抽成静态方法是为了让它<b>可被自检直接调</b>：
     * 用户点单的判据是「保存条件之后重开面板 ⇒ 那几条必须还在」，
     * 而"重开面板"走的正是这一条路（见 {@code conditions_panel_readback*} 那几条 case）。
     */
    public static Resolved resolveConditions(MinecraftServer server, ResourceLocation id, GTRecipe live) {
        final com.google.gson.JsonArray fromLive =
                live == null ? new com.google.gson.JsonArray() : ShanhaiRecipeConditions.encodeOf(live);
        com.google.gson.JsonArray fromLedger = null;
        com.google.gson.JsonArray fromFile = null;
        com.google.gson.JsonArray fromTable = null;
        try {
            fromLedger = ShanhaiRecipeBase.conditionsOf(id);
        } catch (Throwable ignored) {
            fromLedger = null;
        }
        try {
            // 🔴 第 5 轮：与 duration 那条【同一张表】的思路（写台账同拍同步"当前生效值"）。
            //    台账为空、文件也没有时这一层通常就等于 live；它的价值在于
            //    "这一局改过、又被别的路径（例如恢复某条）动过"这种中途状态下也读得对。
            fromTable = fromLedger == null ? ShanhaiRecipeBase.effectiveConditions(id) : null;
        } catch (Throwable ignored) {
            fromTable = null;
        }
        try {
            fromFile = conditionsFromOverrideFile(id);
        } catch (Throwable ignored) {
            fromFile = null;
        }
        final com.google.gson.JsonArray chosen;
        final String src;
        if (fromLedger != null) {
            chosen = fromLedger.deepCopy();
            src = "ledger";
        } else if (fromFile != null) {
            chosen = fromFile.deepCopy();
            src = "file";
        } else {
            // 🔴🔴 2026-10-05 修复④：**这里原来读的是 fromTable（那张"当前生效值"缓存表），
            //    而它会在"这条配方从没被我们编辑过"的时候把【活配方的真值】盖掉。**
            //
            // 现场（用户实例 logs\latest.log 原文，逐字）：
            //   workspace_conditions_source id=shanhai:spacetime_distortion/pf/primordial_debug_module
            //     source=table n=0 ledger=(none) file=(none) live=1 ledger_vs_live=true file_vs_live=true
            //   ↑ **live=1**（那条活配方上确实挂着 1 条条件），而面板显示的是 table 那一份 **n=0**
            //     ⇒ 第三屏底下写着「额外条件 0 条」，用户原话：「物质模块条件没显示」。
            //
            // 为什么会分叉：`table` = `ShanhaiRecipeBase.effectiveConditions(id)`，它的第二层是
            //   `COND_CURRENT` —— 那张表是**开机建底本那一拍**从 `BASE[id].conditions` 抄下来的
            //   只读快照。而面板 `live` 那一份是从【当前 RecipeManager】现取的。
            //   ⇒ 只要底本那一拍之后 `RecipeManager` 里这条配方的对象被换过（KubeJS 覆盖层 /
            //     一次表重建都会换），两者就分叉，而**分叉时该信的是 live**：
            //     GT 判定用的是 live.conditions，live 才是"机器此刻真正认的门槛"。
            //
            // 修法 = **台账 → 覆盖文件 → 活配方**（把 table 从"决策链"里摘掉，只留作诊断读数）。
            //   三层各自的职责没有丢：
            //     ① ledger = 用户这一局刚改的（最贴意图）；
            //     ② file   = 持久化真值 ⇒ "重启之后还在不在"由它决定（第 5 轮 #6 修的正是这条）；
            //     ③ live   = 其余一切情况下的真值（这一版把它的优先级摆对了）。
            chosen = fromLive;
            src = "live";
            if (fromTable != null && !ShanhaiRecipeConditions.sameAs(fromTable, fromLive)) {
                // 不判红，但必须留痕：这条读数就是"分叉发生过"的证据（下一轮排查从这里开始）
                ShanhaiMod.LOGGER.warn("{} conditions_source_diverge id={} table_n={} live_n={} "
                                + "⇒ 面板按【live】显示（table 那份是开机快照，已经过期）；"
                                + "这一行是 2026-10-05 修复④的判据",
                        PREFIX, id, fromTable.size(), fromLive.size());
            }
        }
        return new Resolved(chosen, src,
                fromLedger == null ? -1 : fromLedger.size(),
                fromFile == null ? -1 : fromFile.size(),
                fromLive.size(),
                fromLedger == null || ShanhaiRecipeConditions.sameAs(fromLedger, fromLive),
                fromFile == null || ShanhaiRecipeConditions.sameAs(fromFile, fromLive));
    }

    /**
     * 从覆盖文件里读这条 id 的 {@code fields.conditions}（没有 / 形状不对 ⇒ {@code null}）。
     *
     * <p>口径与覆盖层脚本一致：{@code fields.conditions} 就是 GT 的平铺形状数组。
     */
    public static com.google.gson.JsonArray conditionsFromOverrideFile(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        final com.google.gson.JsonObject entry = ShanhaiRecipeOverrideStore.findEntry(id);
        if (entry == null || !entry.has("fields") || !entry.get("fields").isJsonObject()) {
            return null;
        }
        final com.google.gson.JsonElement c = entry.getAsJsonObject("fields")
                .get(ShanhaiRecipeConditions.FIELD);
        return c != null && c.isJsonArray() ? c.getAsJsonArray() : null;
    }

    /**
     * 由 EU/t 与档位反推电流（A）。{@code eut = 电压 × 电流} ⇒ {@code 电流 = eut / 电压}。
     * <p>取不到就回 1A（老口径 = 隐含 1A，读数不会突变）。
     */
    public static int amperageOf(long eut, int tier) {
        if (tier < 0) {
            return 1;
        }
        final long v = TIER_VOLTAGE[tier];
        if (v <= 0L) {
            return 1;
        }
        final long a = Math.round((double) Math.max(0L, eut) / (double) v);
        return (int) Math.max(AMPERAGE_MIN, Math.min(AMPERAGE_MAX, a));
    }

    public boolean back() {
        if (blank()) {
            return false;
        }
        // 🆕 B 组：离开编辑屏就把条件页收起来（它挂在编辑屏上，留着会让"返回"看起来没生效）
        condOpen = false;
        if (stage == Stage.EDIT) {
            // 🆕 第 7 轮（队列 #7）：回到【来的那一屏、那一页】，而不是永远回第二屏第 1 页。
            stage = returnStage == Stage.QUERY ? Stage.QUERY : Stage.RECIPES;
            recipeId = null;
            loadedRecipe = false;
            countCell = -1;
            page = clampPage(returnPage);
            say(stage == Stage.QUERY ? "§7已回到查询结果" : "§7已回到配方列表",
                    "§8共 §f" + (stage == Stage.QUERY
                            ? currentGroupRecipes().size() : recipes.size()) + " §7条");
            return true;
        }
        if (stage == Stage.RECIPES || stage == Stage.QUERY) {
            stage = Stage.TYPES;
            typeId = null;
            page = clampPage(typesPage);
            say("§7已回到类型列表", "§8选一个配方类型，或直接用下面的物品框/搜索框查");
            return true;
        }
        return false;
    }

    /** 列表行被点（行号 = 本页第几行）。第一屏一行 = 一个类型；卡片屏一行 = 一张卡。 */
    public boolean clickRow(int row) {
        if (blank()) {
            return false;
        }
        if (stage == Stage.TYPES) {
            final int idx = page * ROWS + row;
            return idx >= 0 && idx < types.size() && selectType(types.get(idx).id());
        }
        if (stage == Stage.RECIPES || stage == Stage.QUERY) {
            final int idx = page * CARDS_PER_PAGE + row;
            final List<ResourceLocation> list = stage == Stage.RECIPES ? recipes : currentGroupRecipes();
            return idx >= 0 && idx < list.size() && selectRecipe(list.get(idx));
        }
        return false;
    }

    public boolean prevPage() {
        if (blank() || page <= 0) {
            return false;
        }
        page--;
        touch();
        return true;
    }

    public boolean nextPage() {
        if (blank() || page >= pageCount() - 1) {
            return false;
        }
        page++;
        touch();
        return true;
    }

    /** 队列 #6「翻页加速」：直接跳首页 / 末页（服务端读不到修饰键 ⇒ 用显式按钮）。 */
    public boolean firstPage() {
        if (blank() || page <= 0) {
            return false;
        }
        page = 0;
        touch();
        return true;
    }

    /**
     * 🆕 第 8 轮（队列 #6）：<b>按住 Shift / Ctrl 点「上页 / 下页」⇒ 一次翻多页</b>的落点。
     *
     * <h4>为什么是"一次跳 N 页"而不是"循环调 N 次 nextPage"</h4>
     * ① 循环调 {@code nextPage} 会 {@code touch()} N 次（N 次状态推送）、还要 N 次边界判断；
     * ② 更要紧的是：循环版本在"已经到末页"时会<b>静默什么都不做</b>，而 {@code page + delta}
     * 一次算完再夹回合法区间，语义是明确的「往那个方向翻 N 页，翻不动就停在末页」。
     * ③ 夹回用的是<b>已有那个</b> {@link #clampPage(int)}（队列 #7 那次"页数变少时安全退到末页"
     * 同一条口径）⇒ 越界不可能发生。
     *
     * <h4>修饰键从哪来</h4>
     * 从 LDLib 的 {@code ClickData}（{@code ButtonWidget.mouseClicked} 在<b>客户端</b>就读好
     * {@code isShiftClick} / {@code isCtrlClick}，随这一颗按钮的点击包一起上行，
     * 服务端 {@code ButtonWidget.handleClientAction} 再还原给同一个回调）。
     * 详见 {@link ShanhaiRecipeEditorPanel} 里那一段注释 —— 那条通道是另一条只读线
     * 逐段字节码核实过的，本轮只是把参数接上。
     *
     * @param delta 正数往后翻、负数往前翻（0 = 不动）
     * @return true = 页码真的动了
     */
    public boolean jumpPage(int delta) {
        if (blank() || delta == 0) {
            return false;
        }
        final int target = clampPage(page + delta);
        if (target == page) {
            return false;
        }
        page = target;
        touch();
        return true;
    }

    public boolean lastPage() {
        if (blank()) {
            return false;
        }
        final int last = pageCount() - 1;
        if (page >= last) {
            return false;
        }
        page = last;
        touch();
        return true;
    }

    /** 把页码夹回合法范围（页数变少时"安全退到末页" —— 队列 #7）。 */
    private int clampPage(int p) {
        final int last = pageCount() - 1;
        return Math.max(0, Math.min(p, last));
    }

    /** 当前页号（0 基）。自检/日志判据用它读"翻了几页"。 */
    public int page() {
        return page;
    }

    public int pageCount() {
        final int n;
        if (stage == Stage.RECIPES) {
            n = recipes.size();
        } else if (stage == Stage.QUERY) {
            n = currentGroupRecipes().size();
        } else {
            n = types.size();
        }
        final int perPage = stage == Stage.TYPES ? ROWS : CARDS_PER_PAGE;
        return Math.max(1, (n + perPage - 1) / perPage);
    }

    /** 一页几行/几张（面板据此排版；两侧同值）。 */
    public int perPage() {
        return stage == Stage.TYPES ? ROWS : CARDS_PER_PAGE;
    }

    // ================================================================ 🆕 第 7 轮：查询

    /** 物品槽里现在是什么（客户端那份是服务端推下来的空壳，别拿它做判断）。 */
    public ItemStack queryItem() {
        return queryItem == null ? ItemStack.EMPTY : queryItem;
    }

    /** 文本搜索框里的字（<b>两侧都有效</b>，理由见字段注释）。 */
    public String searchTextRaw() {
        return searchText == null ? "" : searchText;
    }

    /**
     * 往物品槽里放一个东西（JEI 拖进来的那一下）。
     *
     * <p>只收物品：三问（获取途径 / 作为物品的用处 / 作为机器的用处）都是<b>按物品</b>问的，
     * 流体进这个框没有对应的语义 ⇒ 收不下就明确记账（{@link ShanhaiDragStats#dropRejected}），
     * 不静默丢弃。
     */
    public boolean offerQueryItem(Object ingredient) {
        if (blank() || ingredient == null) {
            return false;
        }
        final ItemStack stack = toItemStack(ingredient);
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        queryItem = stack.copy();
        // 🔴 2026-10-05 第 11 刀（用户报「物品框里没有图标」）：这一句原来【漏了】。
        //    面板那一层是"版本号变了才推状态"（StatePump：`if (session.version() == sentVersion) return;`）
        //    而 `clearQueryItem()` 有 `touch()`、`offerQueryItem()` 没有 ⇒ 放进物品这一拍
        //    是【靠同一拍里的 say(...) 顺手 touch()】才推下去的。那种"顺手"一旦被人改动
        //    （比如把 say 挪到前面、或者以后有人加了条不 say 的路径）就会静默丢同步 ——
        //    症状正是"服务端有物品、客户端那个框是空的"。这里显式补上。
        touch();
        say("§a已放入 §f" + ShanhaiRecipeEditorSession.shortId(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        + "§a，点下面三个按钮之一开查",
                "§8获取途径 / 作为物品的用处 / 作为机器的用处");
        ShanhaiMod.LOGGER.info("{} query_item_set item={} count={}",
                PREFIX, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()),
                stack.getCount());
        return true;
    }

    /** 空掉物品槽。 */
    public boolean clearQueryItem() {
        if (queryItem == null || queryItem.isEmpty()) {
            return false;
        }
        queryItem = ItemStack.EMPTY;
        touch();
        return true;
    }

    /**
     * 递过来的东西 ⇒ {@code ItemStack}。
     *
     * <p>🔴 <b>这里【不】认 {@code ITypedIngredient}</b>：那是 JEI 的类型，而本类是
     * 公共（两侧都在）的类 —— 在没装 JEI 的专服上碰到它就是一个 {@code NoClassDefFoundError}。
     * JEI 那一层壳由 {@code ShanhaiQuerySlotWidget}（客户端专用、那个类里 JEI 一定在）剥掉，
     * 到这里只剩物品栈或物品。
     */
    public static ItemStack toItemStack(Object ingredient) {
        if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
            return stack;
        }
        if (ingredient instanceof net.minecraft.world.item.Item item) {
            return new ItemStack(item);
        }
        return null;
    }

    /**
     * 文本搜索框改字。
     *
     * <p>🔴 <b>2026-10-05 第 11 刀：改成"只收字、不搜"</b> —— 用户点名的。
     * 原话（逐字）：「<b>是可以搜索了，但是不要我每打一个字就搜索一次啊，要我点击搜索按钮再去搜索啊</b>」
     * ⇒ 这里只把字记下来（并推给服务端渲染用），<b>一次查询都不发</b>；
     *   真要查就点那颗「搜索」按钮（{@link #runTextSearchNow()}）或在那框里按回车
     *   （面板那一层把它接成同一个动作）。
     *
     * <p>⚠️ 上一版是"停手 0.2 秒后自动查一次"（防抖）—— 那对"全表 5.2 万条"是个省钱的折中，
     * 但用户要的是<b>他自己控制什么时候查</b>（他一边打字一边看到屏幕在跳，很烦）。
     * 省钱的诉求改由"只在按下去那一拍才扫表"满足 ⇒ 比防抖更省。
     */
    public void setSearchText(String t) {
        final String v = t == null ? "" : t;
        if (v.equals(searchText)) {
            return;
        }
        searchText = v;
        // 🔴 这里【不再】置 textDirty：改字不等于"要查"（用户点名）
        touch();
    }

    /** 立刻按当前文本查一次（「搜索」按钮 / 回车键走这条）。 */
    public boolean runTextSearchNow() {
        textDirty = false;
        return runQuery(ShanhaiRecipeQuery.Kind.TEXT);
    }

    /**
     * 服务端每 tick 一次（由面板的状态泵调）。
     *
     * <p>🔴 第 11 刀：<b>这里不再自动发起文本搜索</b>。原来它是"防抖闸"（停手 4 tick 后查一次），
     * 而用户明确要求"要点了搜索按钮才搜" ⇒ 自动那一条整条摘掉。
     * 这个 tick 仍然要跑（它维护 {@code tickCounter}，别的地方在用），只是不再触发查询。
     */
    public void tick() {
        if (blank()) {
            return;
        }
        tickCounter++;
        textDirty = false;   // 任何残留的"脏"标记都不再触发查询（只由按钮/回车触发）
        refreshListIfTableChanged();
    }

    /**
     * 🔴🔴 <b>2026-10-05（用户两条报障的共同根因）：表变过了 ⇒ 编辑器自己那份列表必须跟着重建。</b>
     *
     * <h4>现场</h4>
     * <ol>
     *   <li>「我新建了 GT 配方 …… <b>甚至我们的配方编辑器都没有即时刷新</b>」——
     *       面板手里那份 id 列表是"点进这个类型那一刻"拷下来的，之后再没人重建；</li>
     *   <li>「{@code /shanhai edit restore} 之后卡片上还留着刚被抹掉的那条，
     *       <b>而且输入产物是空的</b>」—— 同源：<b>列表用旧快照、数据现读 ⇒ 剩一个空壳</b>。</li>
     * </ol>
     *
     * <h4>为什么用"版本号"而不是在每个改动点各自去刷</h4>
     * 写表的收口只有一个（{@link ShanhaiRecipeTableHook#forceWriteBack}），版本号挂在那儿
     * ⇒ 新建 / 改 / 删 / 恢复原样 / 恢复全部 / 覆盖层重放<b>一次都不会漏</b>，
     * 而且这里每帧只比一个 int，没变就什么都不做。
     *
     * <h4>⚠️ 守住的三件事</h4>
     * ① <b>不碰编辑屏</b>（第三屏手里有正在编辑的缓冲，重建列表会把它搅乱）；
     * ② <b>不翻页</b>（页号保留；页数变少了才夹到最后一页）；
     * ③ 查询结果屏只把"已经不在活表里"的 id 剔掉，<b>不重跑查询</b>（不打断用户看结果）。
     */
    private void refreshListIfTableChanged() {
        final int v = ShanhaiRecipeTableHook.tableVersion();
        if (lastTableVersion < 0) {
            lastTableVersion = v;         // 第一次进来只对齐，不触发重建
            return;
        }
        if (v == lastTableVersion) {
            return;
        }
        lastTableVersion = v;
        if (typeId == null) {
            return;
        }
        // 🔴 第一屏的「(N 条)」也要跟着变（用户 2026-10-05：「配方编辑器还是会显示旧的配方条数
        //    （在第一页中），需要重读配方表才能同步」）⇒ 与第二屏**共用同一次重建**的产物。
        final int keepTypePage = page;
        final int beforeRows = types.size();
        buildTypeRows();
        // 🔴🔴 2026-10-05（用户实测：**新增之后第二屏热更新了、第一屏不动**；他的口径
        //    「上 mixin 之前我们好歹得把自己的逻辑修好」）：
        //    **根因就在这一行**：第一屏那些数字（`(N 条)`）是**服务端算好推给客户端**的，
        //    而"推"这件事由面板的 `detectAndSendChanges` 驱动，它的闸是：
        //      · 列表文字 ⇒ 看 `version()`（我重建列表时会涨 ⇒ 第二屏就通了 ✓）
        //      · 数字文字 ⇒ **看 `numbersVersion()`**（`writeState(buf, includeNumbers)`）
        //    ⇒ 我重算了数据却**没碰 numbersVersion** ⇒ 面板认为"数字没变" ⇒ 一个字节都不推
        //    ⇒ 客户端就一直画旧条数（用户看到的正是这个）✓
        //    ⇒ 修法：重算完把两个版本号都涨一下（与 setAmperage 等处同一写法：numbersVersion++; touch();）。
        numbersVersion++;
        touch();
        if (stage == Stage.TYPES) {
            page = Math.min(keepTypePage, Math.max(0, pageCount() - 1));
            ShanhaiMod.LOGGER.info("{} workspace_types_recounted reason=table_changed version={} "
                            + "types={}（{} -> 行）page={}/{}：第一屏那个 (N 条) 当场就是新数",
                    PREFIX, v, types.size(), beforeRows, page + 1, pageCount());
        }
        if (stage == Stage.EDIT) {
            return;
        }
        final int keepPage = page;
        if (stage == Stage.RECIPES) {
            rebuildCurrentList();
            final int last = Math.max(0, pageCount() - 1);
            page = Math.min(keepPage, last);
            ShanhaiMod.LOGGER.info("{} workspace_list_rebuilt reason=table_changed version={} type={} "
                            + "recipes={} page={}/{}（新建/删除/恢复之后列表当场重建，不用点「重读配方表」）",
                    PREFIX, v, typeId, recipes.size(), page + 1, pageCount());
        } else if (stage == Stage.QUERY) {
            final int before = recipes.size();
            try {
                recipes.removeIf(this::missingFromLiveTable);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} workspace_query_prune_failed err={}", PREFIX, t.toString());
            }
            ShanhaiMod.LOGGER.info("{} workspace_query_pruned reason=table_changed version={} {} -> {}"
                            + "（查询结果里已经不在活表里的 id 当场剔掉：不剔就会留一个空壳卡片）",
                    PREFIX, v, before, recipes.size());
        }
    }

    /** 这个 id 在【活表】里还有没有（查询结果剔壳用）。判不出来就当作"还在"。 */
    private boolean missingFromLiveTable(ResourceLocation id) {
        if (id == null) {
            return true;
        }
        try {
            if (ShanhaiVanillaRecipeTable.liveById(id) != null
                    || ShanhaiVanillaRecipeOps.readFromTable(server, id) != null) {
                return false;
            }
            return ShanhaiRecipeReverseIndex.byId(server, id) == null;
        } catch (Throwable t) {
            return false;                 // 判不出来就不剔（宁可多留一格，也不许把真配方抹掉）
        }
    }

    /** 面板自己那一份"表版本"（用来判断要不要重建列表）。 */
    private int lastTableVersion = -1;

    /**
     * 只重建【当前类型】的 id 列表，<b>页号 / 屏 / 当前选中</b>一律不动。
     *
     * <p>与 {@link #selectTypeKeepStage} 的区别：那个是"用户点了这个类型"的语义
     * （会重置 {@code page}，非 GT 那支还会把屏切到列表屏）；这里是"表变了，顺手把列表对齐"。
     */
    private void rebuildCurrentList() {
        try {
            if (typeId == null) {
                return;
            }
            if (isVanillaTypeId(typeId)) {
                ShanhaiVanillaRecipeTable.captureIfAbsent(server);
                ShanhaiVanillaRecipeTable.rebuildIndex(server);
                recipes.clear();
                recipes.addAll(ShanhaiVanillaRecipeTable.idsOf(typeId));
                return;
            }
            ShanhaiRecipeReverseIndex.ensure(server);
            recipes.clear();
            for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
                final GTRecipeType t = r.getType();
                if (t != null && t.registryName != null && t.registryName.equals(typeId) && r.id != null) {
                    recipes.add(r.id);
                }
            }
            recipes.sort(Comparator.comparing(ResourceLocation::toString));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} workspace_list_rebuild_failed type={} err={}",
                    PREFIX, typeId, t.toString(), t);
        }
    }

    /**
     * 按某一问查一次。
     *
     * <p>两种"空态"都<b>明确写出来</b>而不是静默不动：没放物品 / 关键词为空。
     */
    public boolean runQuery(ShanhaiRecipeQuery.Kind kind) {
        if (blank() || kind == null || kind == ShanhaiRecipeQuery.Kind.NONE) {
            return false;
        }
        if (kind == ShanhaiRecipeQuery.Kind.TEXT) {
            final String q = searchText == null ? "" : searchText.trim();
            if (q.isEmpty()) {
                return emptyState(kind, "请输入关键词（配方 id 或配方种类名，中英文都行）");
            }
        } else {
            if (queryItem == null || queryItem.isEmpty()) {
                return emptyState(kind, "请先从 JEI 拖一个物品到上面的框里，再点这三个按钮");
            }
        }
        final long t0 = System.nanoTime();
        final ShanhaiRecipeQuery.Result r;
        switch (kind) {
            case SOURCE -> r = ShanhaiRecipeQuery.byOutput(server, queryItem.getItem());
            case USE -> r = ShanhaiRecipeQuery.byInput(server, queryItem.getItem());
            case MACHINE -> r = ShanhaiRecipeQuery.asMachine(server, queryItem.getItem());
            case TEXT -> r = ShanhaiRecipeQuery.byText(server, searchText);
            default -> r = ShanhaiRecipeQuery.byText(server, searchText);
        }
        final int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
        queryResult = r;
        queryKind = kind;
        tabIndex = 0;
        tabPage = 0;
        page = 0;
        // 查得到东西才切到结果屏；查不到就留在第一屏，把"为什么没有"写在提示行上
        stage = r.groups().isEmpty() ? Stage.TYPES : Stage.QUERY;
        say(r.groups().isEmpty()
                        ? "§e" + kind.zh + "：§f没有命中"
                        : "§a" + kind.zh + "：§f" + r.total() + " §a条 · §f" + r.groups().size() + " §a种配方种类",
                "§8" + r.note());
        ShanhaiMod.LOGGER.info("{} query kind={} item={} text={} total={} groups={} ms={} note={}",
                PREFIX, kind, queryItem == null || queryItem.isEmpty() ? "(none)"
                        : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(queryItem.getItem()),
                searchText, r.total(), r.groups().size(), ms, r.note());
        touch();
        return !r.groups().isEmpty();
    }

    private boolean emptyState(ShanhaiRecipeQuery.Kind kind, String note) {
        queryResult = new ShanhaiRecipeQuery.Result(kind, List.of(), 0, note);
        queryKind = kind;
        tabIndex = 0;
        tabPage = 0;
        stage = Stage.TYPES;
        say("§e" + note, "§8" + kind.zh);
        ShanhaiMod.LOGGER.info("{} query_empty kind={} note={}", PREFIX, kind, note);
        touch();
        return false;
    }

    /** 清掉查询结果（回到"没查过"）。 */
    public void clearQuery() {
        queryResult = new ShanhaiRecipeQuery.Result(ShanhaiRecipeQuery.Kind.NONE, List.of(), 0, "(还没查过)");
        queryKind = ShanhaiRecipeQuery.Kind.NONE;
        tabIndex = 0;
        tabPage = 0;
        cards = List.of();
        tabs = List.of();
        if (stage == Stage.QUERY) {
            stage = Stage.TYPES;
        }
        touch();
    }

    // ---- 顶部那一排（配方种类切换）----

    public ShanhaiRecipeQuery.Kind queryKind() {
        return queryKind;
    }

    public int tabIndex() {
        return tabIndex;
    }

    public int tabPageIndex() {
        return tabPage;
    }

    public int tabCount() {
        return queryResult.groups().size();
    }

    public int tabPageCount() {
        final int n = tabCount();
        return Math.max(1, (n + TABS_PER_PAGE - 1) / TABS_PER_PAGE);
    }

    /** 本页第 {@code i} 个"配方种类"标签页（含机器图标；服务端算好，两侧读同一个字段）。 */
    public ShanhaiRecipeQuery.Tab tabAt(int i) {
        if (tabs == null || i < 0 || i >= tabs.size()) {
            return null;
        }
        return tabs.get(i);
    }

    /** 面板据此决定那一排图标要不要画（第二屏也要画：只画当前类型那一个）。 */
    public boolean stageIsQuery() {
        return stage == Stage.QUERY;
    }

    /** 卡片屏（第二屏 / 查询结果屏）。 */
    public boolean stageIsCards() {
        return stage == Stage.QUERY || stage == Stage.RECIPES;
    }

    public boolean tabSelect(int i) {
        final int global = tabPage * TABS_PER_PAGE + i;
        if (blank() || global < 0 || global >= tabCount() || global == tabIndex) {
            return false;
        }
        tabIndex = global;
        page = 0;
        final String type = queryResult.groups().get(global).typeName();
        say("§a已切到配方种类 §f" + type + " §7（§f"
                        + queryResult.groups().get(global).recipes().size() + " §7条）",
                "§8第 " + (global + 1) + "/" + tabCount() + " 种");
        touch();
        return true;
    }

    public boolean tabPagePrev() {
        return tabPageMove(-1);
    }

    public boolean tabPageNext() {
        return tabPageMove(1);
    }

    private boolean tabPageMove(int d) {
        if (blank()) {
            return false;
        }
        final int last = tabPageCount() - 1;
        final int np = Math.max(0, Math.min(last, tabPage + d));
        if (np == tabPage) {
            return false;
        }
        tabPage = np;
        tabIndex = Math.min(tabIndex, Math.max(0, tabCount() - 1));
        // 换页之后让"选中的那一种"落在本页里，否则会出现"这一页一个都没选中"
        if (tabIndex < tabPage * TABS_PER_PAGE) {
            tabIndex = tabPage * TABS_PER_PAGE;
        }
        page = 0;
        touch();
        return true;
    }

    /** 当前选中那一组的配方 id 列表（服务端有内容；客户端那份是空的，靠推下来的页码与卡片）。 */
    private List<ResourceLocation> currentGroupRecipes() {
        final List<ShanhaiRecipeQuery.Group> gs = queryResult.groups();
        if (tabIndex < 0 || tabIndex >= gs.size()) {
            return List.of();
        }
        return gs.get(tabIndex).recipes();
    }

    /** 本页要画哪几条配方 id。 */
    private List<ResourceLocation> currentCardList() {
        if (stage == Stage.QUERY) {
            return currentGroupRecipes();
        }
        if (stage == Stage.RECIPES) {
            return recipes;
        }
        return List.of();
    }

    /**
     * 本页的 4 张卡片（服务端算；客户端读推下来的那份，见 {@link #cardAt}）。
     *
     * <p>⚠️ 只建<b>本页</b>那几张 —— 用户报过第二屏有 1378 条，
     * 一次全建 1378 张卡会把这一帧拖死。
     */
    public List<ShanhaiRecipeQuery.Card> buildCards() {
        if (blank() || (stage != Stage.QUERY && stage != Stage.RECIPES)) {
            return List.of();
        }
        final List<ResourceLocation> list = currentCardList();
        final List<ShanhaiRecipeQuery.Card> out = new ArrayList<>(CARDS_PER_PAGE);
        for (int i = 0; i < CARDS_PER_PAGE; i++) {
            final int idx = page * CARDS_PER_PAGE + i;
            if (idx < 0 || idx >= list.size()) {
                continue;
            }
            final ResourceLocation id = list.get(idx);
            // 🆕 2026-10-05（用户报「通过获取途径搜索不到工作台配方」）：查询结果屏是
            //    **GT 与非 GT 混在一起**的，所以这里不能只看 `vanillaType`（那个标志描述的是
            //    "用户是从非 GT 类型列表点进来的"）。改成【按 id 现查两边】：
            //    先 GT 反查索引，落空再查非 GT 表 —— 两边都没有才跳过。
            if (vanillaType) {
                final net.minecraft.world.item.crafting.Recipe<?> vr = vanillaFromIndexOrTable(id);
                if (vr == null) {
                    continue;
                }
                final ShanhaiRecipeQuery.Card c =
                        ShanhaiRecipeQuery.cardOfVanilla(ShanhaiVanillaRecipeView.of(vr), id.equals(recipeId));
                if (c != null) {
                    out.add(c);
                }
                continue;
            }
            final GTRecipe r = ShanhaiRecipeReverseIndex.byId(server, id);
            if (r != null) {
                out.add(ShanhaiRecipeQuery.cardOf(r, id.equals(recipeId)));
                continue;
            }
            final net.minecraft.world.item.crafting.Recipe<?> vr2 = vanillaFromIndexOrTable(id);
            if (vr2 != null) {
                final ShanhaiRecipeQuery.Card c2 =
                        ShanhaiRecipeQuery.cardOfVanilla(ShanhaiVanillaRecipeView.of(vr2), id.equals(recipeId));
                if (c2 != null) {
                    out.add(c2);
                }
            }
        }
        return out;
    }

    /** 客户端要画的那一张（越界 ⇒ {@code null}，界面画空格）。 */
    public ShanhaiRecipeQuery.Card cardAt(int i) {
        if (cards == null || i < 0 || i >= cards.size()) {
            return null;
        }
        return cards.get(i);
    }

    /**
     * 卡片被点（＝用户说的那个「选中」按钮）。
     *
     * <p>🔴 用户 2026-10-05 逐字确认的口径：
     * 「指的就在JEI 里是书签和加号，你把他俩删了，改成一个选中按钮，点了进去就是第三屏编辑」
     * ⇒ 本方法只有这一件事：<b>选中这条配方并进第三屏</b>。没有第二个动作。
     *
     * @param slot 本页第几张卡（0..{@link #CARDS_PER_PAGE}-1）
     */
    public boolean cardAction(int slot) {
        if (blank() || !stageIsCards()) {
            return false;
        }
        final int idx = page * CARDS_PER_PAGE + slot;
        final List<ResourceLocation> list = currentCardList();
        if (idx < 0 || idx >= list.size()) {
            return false;
        }
        ShanhaiMod.LOGGER.info("{} card_select stage={} slot={} idx={} id={}",
                PREFIX, stage, slot, idx, list.get(idx));
        return selectRecipe(list.get(idx));
    }

    /**
     * 顶部那一排的标签页（服务端现算，写进 {@link #writeState} 之前调）。
     *
     * <p>第二屏（{@link Stage#RECIPES}）也画那一排 —— 只画<b>当前类型那一个</b>，
     * 这样两屏的版面一致，而且顶部那行会显示"现在看的是哪台机器"。
     */
    private void refreshTabs() {
        final List<ShanhaiRecipeQuery.Tab> out = new ArrayList<>(TABS_PER_PAGE);
        if (stage == Stage.QUERY) {
            final List<ShanhaiRecipeQuery.Group> gs = queryResult.groups();
            final int from = tabPage * TABS_PER_PAGE;
            for (int i = 0; i < TABS_PER_PAGE; i++) {
                final int gi = from + i;
                if (gi >= gs.size()) {
                    continue;
                }
                out.add(ShanhaiRecipeQuery.tabOf(gs.get(gi), gi == tabIndex));
            }
        } else if (stage == Stage.RECIPES && typeId != null) {
            out.add(ShanhaiRecipeQuery.tabOf(
                    new ShanhaiRecipeQuery.Group(typeId,
                            vanillaType ? ShanhaiVanillaRecipeView.typeLabel(typeId)
                                    : ShanhaiRecipeEditorSession.typeName(typeId.getPath()).text(),
                            recipes),
                    true));
        }
        tabs = List.copyOf(out);
    }

    // ---- 卡片屏要显示的读数字 ----

    /** 查询结果屏顶部那一行：本问是什么、命中多少、第几页。 */
    public String queryInfoText() {
        if (blank()) {
            return "";
        }
        if (stage == Stage.QUERY) {
            final List<ShanhaiRecipeQuery.Group> gs = queryResult.groups();
            final int n = currentGroupRecipes().size();
            final String type = tabIndex >= 0 && tabIndex < gs.size() ? gs.get(tabIndex).typeName() : "?";
            return "§7" + queryKind.zh + " §8· §f" + queryResult.total() + " §7条 / §f"
                    + gs.size() + " §7种 · 本种 §f" + type + " §7共 §f" + n + " §7条 · 第 §f"
                    + (page + 1) + "§7/§f" + pageCount() + " §7页";
        }
        if (stage == Stage.RECIPES) {
            return "§7共 §f" + recipes.size() + " §7条 · 第 §f" + (page + 1)
                    + "§7/§f" + pageCount() + " §7页 · §8点卡片右下角的「选择」进第三屏"
                    + (vanillaType ? " §8(原版配方)" : "");
        }
        return "";
    }

    /** 第一屏底部那两行提示：物品槽里现在是什么 / 上一问的结论。 */
    public String querySlotText() {
        if (blank()) {
            return "";
        }
        if (queryItem == null || queryItem.isEmpty()) {
            return "§8从 JEI 拖一个物品进来";
        }
        final ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(queryItem.getItem());
        String name;
        try {
            name = com.shanhai.common.text.ShanhaiTextParser
                    .stripStyleCode(queryItem.getHoverName().getString());
        } catch (Throwable t) {
            name = String.valueOf(id);
        }
        return "§f" + ShanhaiRecipeEditorSession.clipUnits(name, 10) + " §8("
                + ShanhaiRecipeEditorSession.clipUnits(id == null ? "?" : id.toString(), 22) + ")";
    }

    public String queryNoteText() {
        return blank() ? "" : "§8" + queryResult.note();
    }

    public String queryKindText() {
        return blank() ? "" : "§7当前：§f" + queryKind.zh;
    }

    // ---------------------------------------------------------------- ③ 编辑：手势

    /**
     * 往某个格子放东西（JEI 拖进来的那一下）。
     *
     * <p>⚠️ 这一条<b>两侧都允许改</b>（与"保存"那类动作不同）：客户端那份改的是自己的显示缓冲，
     * 服务端那份才是权威；服务端改完会把状态推下来覆盖客户端。见 {@link ShanhaiIOWidget} 类注释 §3。
     *
     * @return true = 收下了
     */
    public boolean offerIngredient(int index, Object ingredient) {
        final ShanhaiIoTable.Cell c = io.cell(index);
        if (c == null) {
            return false;
        }
        if (vanillaType && index < io.inSection()) {
            return vanillaOfferInput(index, ingredient);
        }
        if (ingredient instanceof ItemStack stack && c.itemKind) {
            final int count = stack.getCount() <= 0 ? 1 : stack.getCount();
            c.setItem(stack, count);
            touch();
            return true;
        }
        if (ingredient instanceof FluidStack fluid && !c.itemKind) {
            if (fluid.isEmpty()) {
                return false;
            }
            c.setFluid(fluid);
            touch();
            return true;
        }
        return false;
    }

    /**
     * 🆕 阶段 2：<b>非 GT 配方往输入格里放东西</b>（JEI 拖进来的那一下）。
     *
     * <p>三个闸门，逐条都必须给可见理由（不许静默吞掉）：
     * <ol>
     *   <li>{@link ShanhaiVanillaRecipeShape.Mode#NONE}（本刀不支持的 55 种类型）⇒ 拒收并说明；</li>
     *   <li>GRID 里"格子外"的那几格 ⇒ 拒收，提示先把宽/高放大；</li>
     *   <li>{@link ShanhaiVanillaRecipeShape.Mode#SINGLE} ⇒ 允许（就是"换个材料"）。</li>
     * </ol>
     */
    private boolean vanillaOfferInput(int index, Object ingredient) {
        if (vanillaShape == null || vanillaShape.mode() == ShanhaiVanillaRecipeShape.Mode.NONE) {
            say("§e这个配方类型本版不能编辑输入", "§8列表里看得见、第三屏只读");
            return false;
        }
        if (!vanillaShape.active(index)) {
            say("§e这一格在这个形状的外面", "§8先用右边的「宽 +」「高 +」把网格放大");
            return false;
        }
        if (!(ingredient instanceof ItemStack stack) || stack.isEmpty()) {
            return false;
        }
        final int count = stack.getCount() <= 0 ? 1 : stack.getCount();
        final ItemStack v = stack.copy();
        v.setCount(count);
        if (!vanillaShape.setAt(index, v)) {
            return false;
        }
        rebuildVanillaIo();
        touch();
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_input_set cell={} value={} shape={}",
                PREFIX, index, describeStack(v), vanillaShape.statsLine());
        return true;
    }

    /** 右键：清掉这一格（同样两侧都允许，理由见 {@link #offerIngredient}）。 */
    public boolean clearCell(int index) {
        final ShanhaiIoTable.Cell c = io.cell(index);
        if (c == null || c.empty()) {
            return false;
        }
        if (vanillaType && index < io.inSection()) {
            return vanillaClearInput(index);
        }
        if (vanillaType && index >= io.inSection() && io.inSection() + io.outSection() > index) {
            // 原版配方的产物清空 = 造一条产不出东西的配方 ⇒ 直接拦（要删整条请用「删除这条」）
            say("§e产物不能清空", "§8想删掉这条配方，请用下面那颗「删除这条」");
            return false;
        }
        c.clear();
        touch();
        return true;
    }

    /**
     * 🆕 阶段 2：<b>非 GT 配方右键删掉一格的语义</b>（三种形态各不相同，逐条给可见理由）：
     * <pre>
     *   GRID  ⇒ 清空那一格（变成空格）—— "这个位置不要东西"是合法形状
     *   LIST  ⇒ 把那一格从列表里【摘掉】（后面的往前挪）—— 留下一个空槽是不合法的
     *   SINGLE⇒ 拒绝 —— 单格输入清掉就等于造一条没有输入的配方
     * </pre>
     */
    private boolean vanillaClearInput(int index) {
        if (vanillaShape == null || vanillaShape.mode() == ShanhaiVanillaRecipeShape.Mode.NONE) {
            say("§e这个配方类型本版不能编辑输入", "§8列表里看得见、第三屏只读");
            return false;
        }
        if (!vanillaShape.active(index)) {
            return false;
        }
        switch (vanillaShape.mode()) {
            case LIST -> {
                if (!vanillaShape.canRemoveAt(index)) {
                    say("§e至少要留一格", "§8想删掉整条配方，请用下面那颗「删除这条」");
                    return false;
                }
                if (!vanillaShape.removeAt(index)) {
                    return false;
                }
                say("§7已删掉列表里的第 " + (index + 1) + " 格",
                        "§8现在 " + vanillaShape.slotCount() + " 格");
            }
            case SINGLE -> {
                say("§e这类配方只有一格输入，不能清空", "§8换个材料＝把别的物品拖进这一格");
                return false;
            }
            default -> {
                if (!vanillaShape.clearAt(index)) {
                    return false;
                }
            }
        }
        rebuildVanillaIo();
        touch();
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_input_clear cell={} mode={} shape={}",
                PREFIX, index, vanillaShape.mode(), vanillaShape.statsLine());
        return true;
    }

    /** 中键：打开"改数量"小窗（作用在他点的这一格）。两侧都允许，客户端那份立刻显示。 */
    public boolean openCountEditor(int index) {
        final ShanhaiIoTable.Cell c = io.cell(index);
        if (c == null || c.empty()) {
            return false;
        }
        // 🆕 阶段 2：非 GT 的输入格现在【可以】改数量了（阶段 1 是拒的）。
        //    ⚠️ 口径如实写清：原版合成的消耗按【格】算（每格扣 1 个），
        //       这里改的是写进 Ingredient 的那个 ItemStack 的数量（JEI 与工作台预览照它显示）；
        //       对"烧炼那一族"同理（一次烧一格）。⇒ 它不是"一次吃 N 个"的意思。
        if (vanillaType && index < io.inSection()
                && vanillaShape != null
                && vanillaShape.mode() == ShanhaiVanillaRecipeShape.Mode.NONE) {
            say("§e这个配方类型本版不能编辑输入", "§8列表里看得见、第三屏只读");
            return false;
        }
        countCell = index;
        pendingCount = Math.max(1, c.shownCount());
        pendingChance = c.chance;
        pendingBoost = c.tierChanceBoost;
        pendingCatalyst = c.notConsumable;
        touch();
        return true;
    }

    /** 🆕 小窗里的"概率(万分比)"输入。 */
    public int pendingChance() {
        return pendingChance;
    }

    /** 🆕 小窗里的"随电压递增(万分比)"输入。 */
    public int pendingBoost() {
        return pendingBoost;
    }

    /** 🆕 小窗里的"不消耗"开关。 */
    public boolean pendingCatalyst() {
        return pendingCatalyst;
    }

    public void setPendingChance(int v) {
        pendingChance = Math.max(1, Math.min(10000, v));
    }

    public void setPendingBoost(int v) {
        pendingBoost = Math.max(0, Math.min(10000, v));
    }

    /**
     * 点小窗里那个"不消耗"开关。
     *
     * <p>⚠️ 只有在【服务端】那一份上才推状态（{@code touch()}）—— 客户端那份改的只是自己的显示，
     * 推下来会把用户正在打字的输入框抹掉（同类问题见类注释 §3）。
     */
    public void setPendingCatalyst(boolean v) {
        pendingCatalyst = v;
    }

    /** 服务端：把开关翻一下并推给客户端（按钮回调两侧都会跑，由调用方判 clientSide）。 */
    public boolean togglePendingCatalyst() {
        pendingCatalyst = !pendingCatalyst;
        touch();
        return pendingCatalyst;
    }

    /** 小窗里那一行"= x.xx%"的读数。 */
    public String pendingChanceText() {
        if (pendingCatalyst) {
            return "§7催化剂 · 不消耗";
        }
        return "§e= " + String.format(java.util.Locale.ROOT, "%.2f%%",
                (double) pendingChance * 100.0 / 10000.0);
    }

    public boolean cancelCountEditor() {
        if (countCell < 0) {
            return false;
        }
        countCell = -1;
        touch();
        return true;
    }

    /** 「确定」：把数量小窗里的值写到那一格上（含 🆕 概率/递增/催化剂）。 */
    public boolean confirmCountEditor() {
        if (countCell < 0) {
            return false;
        }
        final ShanhaiIoTable.Cell c = io.cell(countCell);
        if (c == null || c.empty()) {
            countCell = -1;
            touch();
            return false;
        }
        // 🔴 2026-10-05（第 6 轮）：这里原来是 Math.min(999999, …)。
        //    用户原话：「实际上机器可以接受超过64个的啊」—— 而真正的上限是 GT 那个字段的类型
        //    （SizedIngredient.amount:int）⇒ 见 ShanhaiIoTable#MAX_ITEM_COUNT 的取证。
        final int want = Math.max(1, Math.min(ShanhaiIoTable.MAX_ITEM_COUNT, pendingCount));
        // 🆕 阶段 2：非 GT 的输入格 —— 数量改的是"写进 Ingredient 的那个栈"，
        //    走 shape 再回写镜像（chance / 递增 / 催化剂这三个 GT 专有项对原版配方没有意义，
        //    那几件控件在 vanilla 屏上本来就是藏着的）。
        if (vanillaType && countCell < io.inSection()) {
            if (vanillaShape == null || vanillaShape.mode() == ShanhaiVanillaRecipeShape.Mode.NONE) {
                countCell = -1;
                touch();
                return false;
            }
            final ItemStack cur = vanillaShape.shown(countCell);
            if (!cur.isEmpty() && cur.getCount() != want) {
                final ItemStack next = cur.copy();
                next.setCount(want);
                vanillaShape.setAt(countCell, next);
                rebuildVanillaIo();
                ShanhaiMod.LOGGER.info("{} workspace_vanilla_input_count cell={} {} -> {} shape={}",
                        PREFIX, countCell, cur.getCount(), want, vanillaShape.statsLine());
            }
            countCell = -1;
            touch();
            return true;
        }
        // 🔴 只在"真的变了"的时候才置 dirty —— 打开小窗又直接点确定，
        //    不应该把一条"用标签/电路写的"入料重建成一个具体物品栈（那是静默改语义）。
        if (c.shownCount() != want) {
            if (c.itemKind) {
                c.setItem(c.item, want);
            } else {
                c.setFluid(FluidStack.create(c.fluid, want));
            }
        }
        if (c.chance != pendingChance || c.tierChanceBoost != pendingBoost
                || c.notConsumable != pendingCatalyst) {
            c.setChanceAndCatalyst(pendingChance, pendingBoost, pendingCatalyst);
        }
        ShanhaiMod.LOGGER.info("{} workspace_cell_edit cell={} count={} chance={} boost={} notConsumable={} itemKind={}",
                PREFIX, countCell, want, c.chance, c.tierChanceBoost, c.notConsumable, c.itemKind);
        countCell = -1;
        touch();
        return true;
    }

    /** 数字框的回调（两侧都会跑；只有服务端那份会被写盘）。 */
    public void setPendingDuration(int v) {
        pendingDuration = v;
    }

    /**
     * 耗能（EU/t）不再是直接输入的量：它是 {@code 电压档位 × 电流} 算出来的。
     * <p>保留这个 setter 只为了让"反推电流"这条链可读（例如将来想直接写 EU/t = 快速反算）；
     * <b>面板上那个耗能输入框已经拿掉</b>（用户口径：改电流、由电压和电流共同算出耗能）。
     */
    public void setPendingEut(int v) {
        pendingEut = v;
        tierIndex = tierOf(v);
        amperage = amperageOf(v, tierIndex);
    }

    /** 电流输入（用户点单：把电流做成输入框）。 */
    public void setAmperage(int amps) {
        amperage = Math.max(AMPERAGE_MIN, Math.min(AMPERAGE_MAX, amps));
        recomputeEut();
        numbersVersion++;
        touch();
    }

    public void setPendingCount(int v) {
        pendingCount = v;
    }

    /** 分页：输入栏 / 输出栏各一个页号（用户要求"格子区要能滚动或分页"）。 */
    public void setInPage(int p) {
        final int np = Math.max(0, Math.min(inPageCount() - 1, p));
        if (np == inPage) {
            return;
        }
        inPage = np;
        touch();
    }

    public void setOutPage(int p) {
        final int np = Math.max(0, Math.min(outPageCount() - 1, p));
        if (np == outPage) {
            return;
        }
        outPage = np;
        touch();
    }

    /** 电压下拉：选中某一档 ⇒ 用「电压 × 电流」重算耗能（不再直接把耗能设成档位电压）。 */
    public void setTierByName(String name) {
        final int idx = tierIndexByName(name);
        if (idx < 0) {
            return;
        }
        tierIndex = idx;
        recomputeEut();
        // 🔴 这里必须把数字也推一次：否则"在服务端选了档、客户端的电流/耗能框还显示旧值"。
        //    与 setPendingEut（客户端输入框回调）不同 —— 那条路是客户端自己改的，不许回推（会打架）。
        numbersVersion++;
        touch();
    }

    /**
     * 耗能的<b>唯一</b>计算点：{@code pendingEut = 电压档位 × 电流}。
     * <p>🔴 任何改电压/改电流的地方都必须走它 —— 这样 {@code pendingEut} 不可能与两个输入分叉，
     * 而 {@code data.euTier} 又是从 {@code pendingEut} 反推的（保存路径），三者同拍。
     */
    private void recomputeEut() {
        if (tierIndex < 0) {
            pendingEut = 0;
            return;
        }
        final long v = TIER_VOLTAGE[tierIndex];
        final long total = v * (long) amperage;
        pendingEut = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, total));
    }

    // ---------------------------------------------------------------- ③ 编辑：保存 / 删除 / 恢复

    /** 「保存」：把 24 个格子 ＋ 耗时 ＋ 电压一次性写下去（见 {@link ShanhaiRecipeEditorOps#applyEdits}）。 */
    public ShanhaiRecipeEditorOps.Result save() {
        if (blank()) {
            return new ShanhaiRecipeEditorOps.Result(false, "客户端不可写", "client side", 0, 0, false, false, null);
        }
        // 🆕 非 GT（工作台 / 原版配方）：另一条完全独立的保存路径，见 saveVanilla()。
        if (vanillaType) {
            return saveVanilla();
        }
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (live == null) {
            say("§c这条配方已经不在了", "§8点「返回」再重新选一条");
            return new ShanhaiRecipeEditorOps.Result(false, "配方已不存在", "id=" + recipeId, 0, 0, false, false, null);
        }
        // 🔴 A6 迁移（2026-10-05）：待修复的老数据（催化剂被写成 chance=0,maxChance=0）也算"要写"。
        //    它【不】计进 anyDirty()，所以下面那条"没改动就不写盘"的早退对它是放行的
        //    —— 用户只要对那条配方点一次「保存这条」，写坏的 maxChance 就修回 10000。
        final boolean ioRepair = io.anyCatalystRepair();
        final boolean ioDirty = io.anyDirty() || ioRepair;
        // 🔴 2026-10-05（duration 原始值）：pending 那一侧是【原始值】、live 那一侧是【实际值】
        //    ⇒ 必须换算之后再比。直接比会在有乘数的实例上把"改成同一个数"判成脏（或反过来）。
        final boolean durDirty = ShanhaiRecipeDuration.toLive(live.getType(), pendingDuration) != live.duration;
        final boolean eutDirty = pendingEut != ShanhaiRecipeIoApply.euOf(live);
        // 🆕 B 组：条件脏不脏 —— 与【活配方当前那一份】比（口径与 IO/duration 一致：改回原样就不算脏）
        final com.google.gson.JsonArray condNow = ShanhaiRecipeConditions.encodeOf(live);
        final boolean condDirty = !ShanhaiRecipeConditions.sameAs(conditions, condNow);
        if (!ioDirty && !durDirty && !eutDirty && !condDirty) {
            say("§7这次没有任何改动", "§8（点数、拖动、右键删、中键改数量之后再保存）");
            return new ShanhaiRecipeEditorOps.Result(true, "没有改动，未写盘", "no dirty field", 0, 0, false, false, null);
        }
        final com.google.gson.JsonObject inputs = ioDirty ? io.json("inputs") : null;
        final com.google.gson.JsonObject outputs = ioDirty ? io.json("outputs") : null;
        final com.google.gson.JsonObject tickInputs = null;   // 电压走 eut 参数（它同拍写 tickInputs + data.euTier）

        final ShanhaiRecipeEditorOps.Result r = ShanhaiRecipeEditorOps.applyEdits(
                server, live, inputs, outputs, tickInputs,
                durDirty ? pendingDuration : null,
                eutDirty ? (long) pendingEut : null,
                condDirty ? conditions : null,
                true, true);

        say((r.ok() ? "§a" : "§c") + r.message(), "§8" + r.detail());
        ShanhaiMod.LOGGER.info("{} workspace_save id={} io_dirty={} io_repair={} dur_dirty={} eut_dirty={} "
                        + "cond_dirty={} cond_now={} cond_buf={} ok={} persisted={}",
                PREFIX, recipeId, ioDirty, ioRepair, durDirty, eutDirty,
                condDirty, ShanhaiRecipeConditions.summary(condNow),
                ShanhaiRecipeConditions.summary(conditions), r.ok(), r.persisted());

        // 🔴 保存之后必须【重新取一次活的那条配方】再广播给客户端：
        //    applyEdits 会把 GT 索引里那条换成一个【新对象】（它出自台账重建），
        //    手里这个 live 已经是旧实例 ⇒ 拿它发出去等于把【旧值】推给客户端，
        //    而日志上一切正常（最难发现的那种错）。优先读原版两张表（O(1)）；
        //    拿不到才退回"重建倒排索引"那条（慢，且会打一条 warn 留痕）。
        GTRecipe after = ShanhaiRecipeEditorOps.readFromVanilla(server, live.getType(), recipeId);
        if (after == null) {
            ShanhaiMod.LOGGER.warn("{} workspace_after_read_fallback reason=vanilla_table_miss id={}",
                    PREFIX, recipeId);
            after = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        }
        if (after != null) {
            loadBuffer(after);          // 缓冲重新对齐权威值（dirty 全清）
        } else {
            ShanhaiMod.LOGGER.error("{} workspace_after_read_failed id={} (缓冲仍是保存前的显示值)", PREFIX, recipeId);
        }
        ShanhaiJeiBridge.broadcastRecipeChanged(server, after != null ? after : live);
        return r;
    }

    /**
     * 🆕 <b>非 GT 配方的「保存」</b>（工作台 / 原版配方那一支）。
     *
     * <h4>这一次保存要动什么</h4>
     * <pre>
     *   ① 输入      ← {@link ShanhaiVanillaRecipeShape}（3×3 网格 / 无序列表 / 单格）
     *   ② 产物      ← 输出栏第 1 格（用户拖进去的那个物品 ＋ 中键改的数量）
     *   ③ 烧炼时间  ← 「烧炼时间(t)」输入框（只有 smelting/blasting/smoking/campfire 有）
     *   ④ 经验      ← 本版不提供编辑控件 ⇒ 不动（ledger 里传 null）
     * </pre>
     *
     * <h4>🆕 阶段 2 改掉了什么</h4>
     * 阶段 1 里"输入格脏 ⇒ 整条拒收"，是因为那时输入还只读。
     * 现在输入<b>能改</b>了 ⇒ 拒收只留给"<b>形状不合法</b>"那一种：
     * <pre>
     *   有形状合成全空 / 无序列表里还有空槽 / 单格输入被清空
     * </pre>
     * 不合法就整条不写，并把理由显示出来 —— 绝不静默产出一条被清空的配方。
     */
    private ShanhaiRecipeEditorOps.Result saveVanilla() {
        if (recipeId == null || vanillaView == null) {
            say("§c没有载入任何配方", "§8先回上一屏选一条");
            return new ShanhaiRecipeEditorOps.Result(false, "没有载入配方", "vanillaView == null",
                    0, 0, false, false, null);
        }
        if (!vanillaView.editable()) {
            say("§e这条配方本版不能编辑", "§8" + vanillaView.whyNotEditable());
            return new ShanhaiRecipeEditorOps.Result(false, "这条配方不可编辑（自定义合成）",
                    "kind=" + vanillaView.kind, 0, 0, false, false, null);
        }
        // ① 输入：形状不合法 ⇒ 整条拒收（并且【不】动缓冲 —— 用户的东西还在，他能接着改）
        final boolean inputsDirty = vanillaShape != null && vanillaShape.isDirty();
        if (vanillaShape != null && vanillaShape.mode() == ShanhaiVanillaRecipeShape.Mode.NONE) {
            // 不支持的 55 种类型：输入格仍可拖（面板拦得住大部分），这里再兜一层
            int dirtyUnsupported = 0;
            for (int i = 0; i < io.inSection(); i++) {
                final ShanhaiIoTable.Cell c = io.cell(i);
                if (c != null && c.dirty) {
                    dirtyUnsupported++;
                }
            }
            if (dirtyUnsupported > 0) {
                loadVanillaBuffer(vanillaLive);
                say("§e这个配方类型本版不能改输入（改动已还原）",
                        "§8你拖进去的东西没有保存：" + vanillaShape.invalidReason());
                return new ShanhaiRecipeEditorOps.Result(false, "这个配方类型不能改输入（改动已还原）",
                        "unsupported_dirty_cells=" + dirtyUnsupported, 0, 0, false, false, null);
            }
        }
        if (inputsDirty && !vanillaShape.valid()) {
            ShanhaiMod.LOGGER.warn("{} workspace_vanilla_save_refused id={} reason=inputs_invalid mode={} reason_text={}",
                    PREFIX, recipeId, vanillaShape.mode(), vanillaShape.invalidReason());
            say("§e" + vanillaShape.invalidReason(), "§8这一次没有保存任何东西（输入还是你刚才改的样子）");
            return new ShanhaiRecipeEditorOps.Result(false, "输入不合法（这次没有保存）",
                    "inputs_invalid mode=" + vanillaShape.mode(), 0, 0, false, false, null);
        }
        // ② 产物：只在真的变了的时候才写
        final ShanhaiIoTable.Cell outCell = io.outSection() > 0 ? io.cell(io.outIndex(0)) : null;
        ItemStack newResult = null;
        if (outCell != null && outCell.dirty) {
            final ItemStack want = outCell.item == null ? ItemStack.EMPTY : outCell.item;
            if (!sameStack(want, vanillaView.result)) {
                if (want.isEmpty()) {
                    ShanhaiMod.LOGGER.warn("{} workspace_vanilla_save_refused id={} reason=result_cleared"
                                    + "（把产物清空等于造一条产不出东西的配方，本版不允许）",
                            PREFIX, recipeId);
                    say("§e产物不能为空", "§8想删掉这条配方，请用下面那颗「删除这条」");
                    return new ShanhaiRecipeEditorOps.Result(false, "产物不能为空", "result cleared",
                            0, 0, false, false, null);
                }
                newResult = want.copy();
            }
        }
        // ③ 时间：只有烧炼那一族有；改回原值就不算改动
        Integer newCook = null;
        if (vanillaView.canEditCooking() && pendingDuration != vanillaView.cookingTime) {
            newCook = pendingDuration;
        }
        if (newResult == null && newCook == null && !inputsDirty) {
            say("§7这次没有任何改动", "§8（拖一个物品到格子里 / 中键改数量 / 改烧炼时间 之后再保存）");
            return new ShanhaiRecipeEditorOps.Result(true, "没有改动，未写盘", "no dirty field",
                    0, 0, false, false, null);
        }
        final ShanhaiRecipeEditorOps.Result r = ShanhaiVanillaRecipeOps.applyEdits(
                server, recipeId, newResult, newCook, null,
                inputsDirty ? vanillaShape : null, true, true);
        say((r.ok() ? "§a" : "§c") + r.message(), "§8" + r.detail());
        ShanhaiMod.LOGGER.info("{} workspace_vanilla_save id={} ok={} new_result={} new_cook={} "
                        + "inputs_dirty={} shape={} persisted={}",
                PREFIX, recipeId, r.ok(),
                newResult == null ? "(untouched)" : newResult.getCount() + "x "
                        + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(newResult.getItem()),
                newCook == null ? "(untouched)" : newCook,
                inputsDirty,
                vanillaShape == null ? "(none)" : vanillaShape.statsLine(),
                r.persisted());
        // 保存之后重新按 id 取一次【活的那条】再对齐缓冲（与 GT 那条路同一个理由：
        // 表里那条已经被换成新对象了，手里这个引用是旧的）
        final net.minecraft.world.item.crafting.Recipe<?> after =
                ShanhaiVanillaRecipeOps.readFromTable(server, recipeId);
        if (after != null) {
            loadVanillaBuffer(after);
        } else {
            ShanhaiMod.LOGGER.error("{} workspace_vanilla_after_read_failed id={} (缓冲仍是保存前的显示值)",
                    PREFIX, recipeId);
        }
        // ④ 通知客户端：JEI 上那一条也要跟着变（走我们本来就挂着的那个注入点）
        try {
            final String fields = vanillaFieldsJson(newResult, newCook, inputsDirty);
            // 🔴 本轮（只修"卡几秒"与"JEI 不显示"）：**必须把这条配方整条编成字节一起带上**。
            //    现场读数（用户实例 logs\latest.log 原文）：
            //      jei_patch_stored id=shanhai:crafting/new_recipe_1 … kind=vanilla recipe_bytes=0
            //      jei_append_skipped id=shanhai:crafting/new_recipe_1 reason=no_recipe_bytes
            //      jei_sync matched=0 hidden=0 added=0 … expected=1 PASS=false
            //    ⇒ 客户端手里没有原件（刚新建的那条本来就不在 JEI 那一页里）⇒ 只能靠字节补进去；
            //      而"改了输入"的那条，光贴三个字段也换不掉 JEI 上显示的材料 ⇒ 字节同样解决它。
            final byte[] bytes = ShanhaiJeiBridge.encodeVanillaRecipe(after);
            ShanhaiJeiBridge.broadcastVanillaChanged(server, recipeId,
                    after != null ? ShanhaiVanillaRecipeTable.typeIdOf(after) : vanillaView.typeId,
                    fields, bytes);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} workspace_vanilla_jei_broadcast_failed id={} err={}",
                    PREFIX, recipeId, t.toString(), t);
        }
        return r;
    }

    /**
     * 这次要贴给客户端的那些字段（形状与覆盖层 entry 的 {@code fields} 一致）。
     *
     * <p>🆕 阶段 2：输入那一段也一起带过去（{@code pattern}/{@code key} 或 {@code ingredients}
     * 或 {@code ingredient}）—— 否则"改完输入、JEI 上还是老样子"。
     */
    private String vanillaFieldsJson(ItemStack result, Integer cookingTime, boolean inputsDirty) {
        final com.google.gson.JsonObject f = new com.google.gson.JsonObject();
        final ItemStack shown = result != null ? result
                : (vanillaView == null ? ItemStack.EMPTY : vanillaView.result);
        if (!shown.isEmpty()) {
            f.add("result", ShanhaiVanillaRecipeView.resultJson(shown));
        }
        if (cookingTime != null) {
            f.addProperty("cookingtime", cookingTime);
        }
        if (inputsDirty && vanillaShape != null) {
            final com.google.gson.JsonObject sf = vanillaShape.fieldsJson();
            for (var en : sf.entrySet()) {
                f.add(en.getKey(), en.getValue());
            }
        }
        return f.toString();
    }

    /** 两个物品栈是不是同一件事（物品相同 ＋ 数量相同；不比 NBT，够这一版用）。 */
    private static boolean sameStack(ItemStack a, ItemStack b) {
        final ItemStack x = a == null ? ItemStack.EMPTY : a;
        final ItemStack y = b == null ? ItemStack.EMPTY : b;
        if (x.isEmpty() || y.isEmpty()) {
            return x.isEmpty() && y.isEmpty();
        }
        return x.getItem() == y.getItem() && x.getCount() == y.getCount();
    }

    /** 日志用："minecraft:stone x4"（空栈写 {@code (empty)}）。 */
    private static String describeStack(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "(empty)";
        }
        final ResourceLocation k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        return k + " x" + s.getCount();
    }

    /** 「保存并退出」：先保存，再关掉容器（关容器是服务端动作，客户端屏幕会跟着关）。 */
    public ShanhaiRecipeEditorOps.Result saveAndExit() {
        final ShanhaiRecipeEditorOps.Result r = save();
        if (player instanceof ServerPlayer sp) {
            sp.closeContainer();
            ShanhaiMod.LOGGER.info("{} workspace_save_and_exit id={} ok={}", PREFIX, recipeId, r.ok());
        }
        return r;
    }

    /**
     * 🆕 「保存并返回」（用户 2026-10-05 原话：
     * 「你这个保存并退出怎么是直接关闭 gui 了，应该是保存并退回吧，不然我编辑第二条的时候
     *   还要再打开面版」）。
     *
     * <p>语义 = 先保存，再回到<b>上一层</b>（编辑屏 ⇒ 该类型的配方列表），<b>不关</b>整个 GUI。
     * 关掉整个面板的那条路（{@link #saveAndExit()}）留着给命令行走，面板按钮不再用它。
     */
    public ShanhaiRecipeEditorOps.Result saveAndReturn() {
        final ShanhaiRecipeEditorOps.Result r = save();
        final boolean moved = back();          // EDIT ⇒ RECIPES（见 back()）
        ShanhaiMod.LOGGER.info("{} workspace_save_and_return id={} ok={} moved_back={} stage={}",
                PREFIX, recipeId, r.ok(), moved, stage);
        return r;
    }

    /** 「删除这条」：索引 + 原版两表 + 落盘，然后回到列表。 */
    public ShanhaiRecipeEditorOps.Result deleteSelected() {
        if (blank()) {
            return new ShanhaiRecipeEditorOps.Result(false, "客户端不可写", "client side", 0, 0, false, false, null);
        }
        // 🆕 非 GT 那一支
        if (vanillaType) {
            if (recipeId == null || vanillaView == null) {
                say("§c没有载入任何配方", "§8先回上一屏选一条");
                return new ShanhaiRecipeEditorOps.Result(false, "没有载入配方", "vanillaView == null",
                        0, 0, false, false, null);
            }
            final net.minecraft.resources.ResourceLocation keepType = typeId;
            final net.minecraft.resources.ResourceLocation typeOfRecipe = vanillaView.typeId;
            final ShanhaiRecipeEditorOps.Result vr =
                    ShanhaiVanillaRecipeOps.removeRecipe(server, recipeId, true);
            ShanhaiJeiBridge.broadcastVanillaRemoved(recipeId, typeOfRecipe);
            say((vr.ok() ? "§a" : "§c") + vr.message(), "§8" + vr.detail());
            recipes.remove(recipeId);
            recipeId = null;
            vanillaLive = null;
            vanillaView = null;
            vanillaShape = null;        // 🆕 阶段 2
            loadedRecipe = false;
            if (keepType != null) {
                recountFirstScreen("delete_or_restore");   // 🔴 第一屏跟着重算（用户实测：第二屏动了、第一屏不动）
                selectType(keepType);
                stage = Stage.RECIPES;
            }
            return vr;
        }
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (live == null) {
            say("§c这条配方已经不在了", "§8点「返回」再重新选一条");
            return new ShanhaiRecipeEditorOps.Result(false, "配方已不存在", "id=" + recipeId, 0, 0, false, false, null);
        }
        final ShanhaiRecipeEditorOps.Result r = ShanhaiRecipeEditorOps.removeRecipe(server, live, true);
        ShanhaiJeiBridge.broadcastRecipeRemoved(server, live);
        say((r.ok() ? "§a" : "§c") + r.message(), "§8" + r.detail());
        final ResourceLocation keepType = typeId;
        recipes.remove(recipeId);
        stage = Stage.RECIPES;
        recipeId = null;
        loadedRecipe = false;
        if (keepType != null) {
            // 列表已经变了（这条没了）⇒ 重新按类型拉一遍
            recountFirstScreen("delete_gt");       // 🔴 第一屏那句 (N 条) 当场跟着变
            selectType(keepType);
            stage = Stage.RECIPES;
        }
        return r;
    }

    /** 「恢复原样」：把这一条退回配方底本（覆盖层里那条也会被去掉）。 */
    public ShanhaiRecipeEditorOps.Result restoreSelected() {
        if (blank()) {
            return new ShanhaiRecipeEditorOps.Result(false, "客户端不可写", "client side", 0, 0, false, false, null);
        }
        // 🆕 非 GT 那一支
        if (vanillaType) {
            if (recipeId == null) {
                say("§c没有载入任何配方", "§8先回上一屏选一条");
                return new ShanhaiRecipeEditorOps.Result(false, "没有载入配方", "recipeId == null",
                        0, 0, false, false, null);
            }
            final net.minecraft.resources.ResourceLocation keep = recipeId;
            final ShanhaiRecipeEditorOps.Result vr =
                    ShanhaiVanillaRecipeOps.restoreOne(server, keep, true);
            ShanhaiMod.LOGGER.info("{} workspace_vanilla_restore id={} ok={}", PREFIX, keep, vr.ok());
            // 恢复之后表里那条已经换回底本原对象 ⇒ 重新取一次再读进缓冲
            final net.minecraft.world.item.crafting.Recipe<?> back =
                    ShanhaiVanillaRecipeOps.readFromTable(server, keep);
            if (back != null) {
                selectVanillaRecipe(back);
            }
            say((vr.ok() ? "§a" : "§c") + vr.message(), "§8" + vr.detail());
            return vr;
        }
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, recipeId);
        if (live == null) {
            say("§c这条配方已经不在了", "§8点「返回」再重新选一条");
            return new ShanhaiRecipeEditorOps.Result(false, "配方已不存在", "id=" + recipeId, 0, 0, false, false, null);
        }
        final ShanhaiRecipeEditorOps.Result r = ShanhaiRecipeEditorOps.restoreOne(server, live, true, true);
        ShanhaiMod.LOGGER.info("{} workspace_restore id={} ok={}", PREFIX, recipeId, r.ok());
        recountFirstScreen("restore_gt");     // 🔴 恢复原样之后第一屏也重算（不许只有第二屏动）
        final ResourceLocation keep = recipeId;
        if (keep != null) {
            selectRecipe(keep);
        }
        say((r.ok() ? "§a" : "§c") + r.message(), "§8" + r.detail());
        return r;
    }

    // ---------------------------------------------------------------- 🆕 B 组：额外条件（面板那一套）

    /** 条件列表最多显示几行（面板的控件数是固定的：LDLib 的控件表在 UI 建好之后不能再改）。 */
    public static final int COND_ROWS = 6;

    public com.google.gson.JsonArray conditionsJson() {
        return conditions;
    }

    public int conditionCount() {
        return conditions == null ? 0 : conditions.size();
    }

    public boolean condOpen() {
        return condOpen;
    }

    public int condSel() {
        return condSel;
    }

    public String condTypeKey() {
        return condTypeKey;
    }

    /** 🔴 第 5 轮：编辑控件里那个唯一的可编辑值 = 「档位」下拉选中的那一项（key）。 */
    public String condValue() {
        return condValue;
    }

    public boolean condReverse() {
        return condReverse;
    }

    /** 面板那一行按钮上的字（顺带把"有几条"写出来，用户一眼能看出有没有条件）。 */
    public String condButtonText() {
        if (blank()) {
            return "";
        }
        return "§e额外条件 §f" + conditionCount() + " §7条" + (condOpen ? " §8(正在编辑)" : "");
    }

    /**
     * 条件页标题 —— 第 5 轮按用户那句「**7：没看懂**」重写成了<b>他自己能照着做的一句话</b>。
     *
     * <p>规则（本轮纪律）：这一屏的每一行提示都必须是"照着做就能做成"的动作描述，
     * 不许出现「条目 / 取反 / 内部编号」这类他不知道指什么的词。
     */
    public String condHeaderText() {
        if (blank()) {
            return "";
        }
        return "§b§l额外条件（这台机器还要满足什么才肯开工）";
    }

    /** 条件页的说明行：把"怎么用"写成三步，不再让他猜。 */
    public String condHintText() {
        if (blank()) {
            return "";
        }
        return "§7① 点一条选中 → §7② 在下面选「档位」 → §7③ 点「保存并返回」才真的存进去";
    }

    /** 第 {@code row} 行的文字（服务端算好、推给客户端画）。 */
    public String conditionRowText(int row) {
        if (blank()) {
            return "";
        }
        if (row < 0 || row >= conditions.size()) {
            return "§8(空)";
        }
        final String mark = row == condSel ? "§a▶ " : "§8· ";
        final String sel = row == condSel ? " §7← 正在改这一条" : "";
        return mark + "§f" + ShanhaiRecipeConditions.describe(conditions.get(row)) + sel;
    }

    // ---------------------------------------------------------------- 档位（第 5 轮的核心）

    /**
     * 🔴 <b>档位候选 = 当前「类型」自己的取值表</b> —— 用户当场纠正的那一条。
     *
     * <p>用户原话（逐字）：
     * <blockquote>「挡位应该根据上面的类型变动指的是我要是选物质模块等级，那档位应该是17种物质模块」</blockquote>
     *
     * <p>这个列表被喂给 LDLib 的 {@code SelectorWidget.setCandidatesSupplier(...)}：
     * 服务端每 tick 调一次，<b>内容变了才</b> {@code writeUpdateInfo(4, …)} 推给客户端
     * （字节码实测：{@code detectAndSendChanges} 里先 {@code List.equals} 比一次，
     *  不同才 {@code setCandidates} ＋ 发包；客户端 {@code readUpdateInfo} 的 id=4 就是收这个表）。
     * ⇒ 「档位下拉随类型切换」是<b>走 LDLib 自己的通道</b>实现的，不需要重建控件表
     * （本面板的控件表在 UI 建好之后本来就不能改）。
     */
    public List<String> condValueCandidates() {
        final List<String> out = new ArrayList<>();
        for (ShanhaiRecipeConditions.Value v : ShanhaiRecipeConditions.valueTableOf(server, condTypeKey)) {
            // 🔴 界面文本一律过 ShanhaiLdlText.esc（裸 % 会触发 LDLib 的 Format error:）——
            //    本轮的模块中文名里没有 %，但这条纪律不能因为"这次恰好没有"就省掉。
            out.add(ShanhaiLdlText.esc(v.dropdown()));
        }
        if (out.isEmpty()) {
            // 空表不能当"没选中"用 —— LDLib 的下拉拿到空表会画出一个空白框。
            // 给一行明确说明（这一行同样会被推到客户端），并且这一行永远选不中（见 setCondValue）。
            out.add("（这个类型没有档位）");
        }
        return out;
    }

    /** 档位下拉当前该显示的那一行。 */
    public String condValueLabel() {
        if (condValue == null || condValue.isEmpty()) {
            return condValueCandidates().get(0);
        }
        return ShanhaiLdlText.esc(ShanhaiRecipeConditions.valueLabelOf(server, condTypeKey, condValue));
    }

    /** 档位那一行的 tooltip：把内部名给他（他说看不懂，所以只在 tooltip 里出现）。 */
    public String condValueHint() {
        if (condValue == null || condValue.isEmpty()) {
            return "先从上面的「类型」里挑一种，这里就会出现它能选的值";
        }
        return "内部名：" + condValue;
    }

    /** 这个类型有没有「档位」这一栏（没有 ⇒ 面板把那一行整个藏掉，而不是画个空框）。 */
    public boolean condHasValueColumn() {
        return ShanhaiRecipeConditions.hasValueColumn(condTypeKey);
    }

    /** 选中那条的读数行（大白话：正在改第几条、它现在是什么）。 */
    public String condEditorText() {
        if (blank()) {
            return "";
        }
        if (condSel < 0 || condSel >= conditions.size()) {
            return "§8还没选中任何一条 ⇒ 先点上面列表里的一行";
        }
        final String t = ShanhaiRecipeConditions.typeLabel(condTypeKey);
        final String n = "§7正在改第 §f" + (condSel + 1) + " §7条：§f" + t + " §7＝ §f";
        if (!ShanhaiRecipeConditions.isKnownType(condTypeKey)) {
            return "§7第 §f" + (condSel + 1) + " §7条是本版不认识的条件（§f" + condTypeKey
                    + "§7）⇒ 只能看，不能改。";
        }
        final String body = switch (ShanhaiRecipeConditions.formOf(condTypeKey)) {
            case CLEANROOM, MODULE_LEVEL -> condValueLabel();
            default -> condHasValueColumn()
                    ? condValueLabel()
                    : "§8这个类型不用选值（它只有「要 / 不要」两种情况）";
        };
        return n + body + reverseSuffix();
    }

    /** "要求不满足"那半句（大白话；原来叫"取反"，用户说看不懂）。 */
    private String reverseSuffix() {
        return condReverse ? "  §c（而且要求【不满足】这个条件）" : "";
    }

    /** 开关按钮上的字（两态都说人话）。 */
    public String condReverseButtonText() {
        return condReverse
                ? "§c要求【不满足】这个条件"
                : "§7要求【满足】这个条件";
    }

    /** 那个开关的 tooltip —— 把"取反"这层意思讲清楚，而不用"取反"这两个字。 */
    public String condReverseHintText() {
        return "默认就是「必须满足」。改成「不满足」= 这台机器【没有】这个条件时反而才肯开工（少用）";
    }

    /** 「＋新增 / 改类型」那个下拉的候选（标签数组，一律走 esc）。 */
    public List<String> condTypeLabels() {
        final List<String> out = new ArrayList<>();
        for (String k : ShanhaiRecipeConditions.knownTypeKeys()) {
            out.add(ShanhaiLdlText.esc(ShanhaiRecipeConditions.typeLabel(k)));
        }
        return out;
    }

    /** 与 {@link #condTypeLabels()} 一一对应的键数组（下标同源 ⇒ 不需要解析标签）。 */
    public List<String> condTypeKeys() {
        return ShanhaiRecipeConditions.knownTypeKeys();
    }

    /** 下拉当前值（标签形态）。 */
    public String condTypeLabel() {
        return ShanhaiLdlText.esc(ShanhaiRecipeConditions.typeLabel(condTypeKey));
    }

    /** 一次编辑动作的机器可判读数（面板底部那一行 + 日志共用）。 */
    public String condNote() {
        return blank() ? "" : condNote;
    }

    /** 这一份条件是从哪个来源读出来的（ledger / file / live）—— 给面板底部和日志读。 */
    public String condSource() {
        return blank() ? "" : condSource;
    }

    /**
     * 面板底部那一行：<b>这一份条件是"从哪儿读到的"</b>。
     *
     * <p>🔴 第 5 轮加这一行的理由就是 #6：上一轮"重开就没了"之所以只能靠人猜，
     * 是因为界面上没有任何读数说明"面板读的是哪一份"。现在它自己会说：
     * <pre>
     *   来源 台账(这一局刚改的) / 来源 存档文件(重启也还在) / 来源 机器上那条配方
     * </pre>
     * 用户看不懂英文 key，所以这里翻成大白话。
     */
    public String condSourceText() {
        if (blank()) {
            return "";
        }
        final String human = switch (condSource == null ? "" : condSource) {
            case "ledger" -> "§7条件来自：§f这一局刚改的（保存后就在里面）";
            case "file" -> "§7条件来自：§f存档里的改动记录（重启后仍然在）";
            case "table" -> "§7条件来自：§f当前生效的那一份（本局改过、还没写进文件）";
            case "live" -> "§7条件来自：§f机器上那条配方自带的（这一条还没有改动记录）";
            default -> "§8条件来源：" + condSource;
        };
        return human;
    }

    /**
     * 这个类型没有档位时，就地说清楚"为什么没有"，而不是留一个空下拉。
     */
    public String condNoValueText() {
        if (blank()) {
            return "";
        }
        if (!ShanhaiRecipeConditions.isKnownType(condTypeKey)) {
            return "§8这一条是本版不认识的条件 ⇒ 只能看，不能改";
        }
        return "§8「" + ShanhaiRecipeConditions.typeLabel(condTypeKey)
                + "」没有可选的档位（它只有「要 / 不要」两种情况）";
    }

    /**
     * 「＋」按钮：按当前选的类型往列表尾部加一条<b>带默认档位</b>的条件，并选中它。
     *
     * <p>🔴 第 5 轮：新增时<b>就把档位定下来</b>（该类型取值表的第一项）——
     * 原来造的是 {@code createDefault()} 的空壳（模块 id 是空串），
     * 而空 id 的门槛是 {@code UNRESOLVABLE_GATE} ⇒ 那样一条条件会让配方永远跑不起来。
     */
    public boolean addCondition() {
        if (blank()) {
            return false;
        }
        final List<ShanhaiRecipeConditions.Value> vs = ShanhaiRecipeConditions.valueTableOf(server, condTypeKey);
        final String first = vs.isEmpty() ? "" : vs.get(0).key();
        final RecipeCondition c = ShanhaiRecipeConditions.makeValueCondition(server, condTypeKey, first, false);
        if (c == null) {
            condNote = "新增失败：类型 " + condTypeKey + " 不在注册表里";
            ShanhaiMod.LOGGER.error("{} workspace_cond_add_failed type={} reason=not_in_registry",
                    PREFIX, condTypeKey);
            say("§c新增条件失败", "§8类型 " + condTypeKey + " 不在 GT 条件注册表里");
            return false;
        }
        ShanhaiRecipeConditions.add(conditions, c);
        condSel = conditions.size() - 1;
        condValue = first;
        loadCondEditor();
        condNote = "新增第 " + (condSel + 1) + " 条：" + ShanhaiRecipeConditions.typeLabel(condTypeKey)
                + (condHasValueColumn() ? "（" + condValueLabel() + "）" : "");
        ShanhaiMod.LOGGER.info("{} workspace_cond_add type={} index={} value={} now={}",
                PREFIX, condTypeKey, condSel, condValue, ShanhaiRecipeConditions.summary(conditions));
        touch();
        return true;
    }

    /** 「－」按钮：删掉选中那一条。 */
    public boolean removeCondition() {
        if (blank() || condSel < 0 || condSel >= conditions.size()) {
            condNote = "没有选中任何一行 ⇒ 删除没有执行";
            say("§7先点一行选中它", "§8条件列表里点一行（或右键）再删");
            return false;
        }
        final String was = ShanhaiRecipeConditions.describe(conditions.get(condSel));
        ShanhaiRecipeConditions.removeAt(conditions, condSel);
        condNote = "删除第 " + (condSel + 1) + " 条：" + was;
        ShanhaiMod.LOGGER.info("{} workspace_cond_remove index={} was={} now={}",
                PREFIX, condSel, was, ShanhaiRecipeConditions.summary(conditions));
        condSel = -1;
        touch();
        return true;
    }

    /**
     * 点某一行：{@code viaRightClick=false} = 左键（选中），{@code true} = 右键（编辑）。
     * 两者都落在这个 {@code condSel} 上、都打开下方那套编辑控件 —— 用户的口径是
     * 「右键可以编辑」，所以<b>右键的行为是"选中并进入编辑"</b>，日志里把来源记下来以便区分。
     */
    public boolean selectCondition(int row, boolean viaRightClick) {
        if (blank()) {
            return false;
        }
        if (row < 0 || row >= conditions.size()) {
            return false;
        }
        condSel = row;
        loadCondEditor();
        condNote = (viaRightClick ? "右键编辑" : "左键选中") + "第 " + (row + 1) + " 条";
        ShanhaiMod.LOGGER.info("{} workspace_cond_select index={} via={} type={} value={} reverse={}",
                PREFIX, row, viaRightClick ? "right" : "left", condTypeKey, condValue, condReverse);
        touch();
        return true;
    }

    /**
     * 「类型」下拉：作用于<b>选中那条</b>；没选中就只记着"新增时用哪种"。
     *
     * <p>🔴 第 5 轮起：改类型会<b>连带把档位重置成那个类型的第一项</b>（或给它默认值）。
     * 原来那句「档位下拉是一个不带类型上下文的固定列表」正是用户抱怨的根子。
     */
    public void setCondType(String label) {
        final int idx = condTypeLabels().indexOf(label);
        final List<String> keys = condTypeKeys();
        if (idx < 0 || idx >= keys.size()) {
            return;
        }
        condTypeKey = keys.get(idx);
        final List<ShanhaiRecipeConditions.Value> vs = ShanhaiRecipeConditions.valueTableOf(server, condTypeKey);
        condValue = vs.isEmpty() ? "" : vs.get(0).key();
        if (condSel >= 0 && condSel < conditions.size() && !blank()) {
            rebuildSelectedFromEditor();
            loadCondEditor();
            condNote = "第 " + (condSel + 1) + " 条改成类型 " + ShanhaiRecipeConditions.typeLabel(condTypeKey)
                    + (condHasValueColumn() ? "，档位已重置为「" + condValueLabel() + "」" : "");
        }
        touch();
    }

    /**
     * <b>「档位」下拉</b> —— 第 5 轮唯一那个可编辑控件。
     *
     * <p>候选列表随「类型」变（{@link #condValueCandidates()}），这里按<b>标签</b>反查 key。
     * 含 id 的旧标签（{@code 无菌超净间 §8(sterile_cleanroom)}）也仍然认 —— 面板与 session 版本错开时
     * （例如他进游戏时用的还是上一个 jar）不会静默什么都不做。
     */
    public void setCondValue(String label) {
        final List<ShanhaiRecipeConditions.Value> vs = ShanhaiRecipeConditions.valueTableOf(server, condTypeKey);
        if (vs.isEmpty() || label == null) {
            return;
        }
        for (ShanhaiRecipeConditions.Value v : vs) {
            if (v.dropdown().equals(label) || v.label().equals(label) || v.key().equals(label)) {
                condValue = v.key();
                rebuildSelectedFromEditor();
                touch();
                return;
            }
        }
        ShanhaiMod.LOGGER.warn("{} workspace_cond_value_unmatched type={} label={} (候选 {} 项，一格都没匹配上)",
                PREFIX, condTypeKey, label, vs.size());
    }

    /** 「要求【满足】/【不满足】」开关（两侧都会跑；只有服务端那份会被写盘）。 */
    public boolean toggleCondReverse() {
        condReverse = !condReverse;
        rebuildSelectedFromEditor();
        if (!blank()) {
            touch();
        }
        return condReverse;
    }

    /** 客户端拿来同步显示用（不推状态、不写盘）。 */
    public void setCondReverseLocal(boolean v) {
        condReverse = v;
    }

    /**
     * 把编辑控件里的值写回<b>选中那条</b>（改一下就立刻生效，没有"确定"按钮 ——
     * 因为这一屏的其它控件也是这个手感，多一个确认会让"我改了没生效"变成常态）。
     *
     * <p>🔴 第 5 轮：从"读三个分散的字段再拼"改成"<b>（类型 + 档位）</b>由
     * {@link ShanhaiRecipeConditions#makeValueCondition} 一处造出来" —— 上游只有一个入口，
     * 就没有"控件之间不同步"的缝。造不出来<b>如实留痕并且不动原来的那条</b>（不塞半成品）。
     */
    private void rebuildSelectedFromEditor() {
        if (blank() || condSel < 0 || condSel >= conditions.size()) {
            return;
        }
        final RecipeCondition c = ShanhaiRecipeConditions.makeValueCondition(
                server, condTypeKey, condValue, condReverse);
        if (c == null) {
            ShanhaiMod.LOGGER.warn("{} workspace_cond_rebuild_skipped index={} type={} value={} "
                            + "（类型不在注册表里 / 造不出来 ⇒ 这一条保持原样，绝不塞半成品）",
                    PREFIX, condSel, condTypeKey, condValue);
            return;
        }
        final JsonElement el = ShanhaiRecipeConditions.encodeOne(c);
        if (el == null) {
            ShanhaiMod.LOGGER.error("{} workspace_cond_rebuild_encode_failed index={} type={}",
                    PREFIX, condSel, condTypeKey);
            return;
        }
        conditions.set(condSel, el);
        condNote = "第 " + (condSel + 1) + " 条 = " + ShanhaiRecipeConditions.describe(el);
        ShanhaiMod.LOGGER.info("{} workspace_cond_edit index={} type={} value={} reverse={} json={}",
                PREFIX, condSel, condTypeKey, condValue, condReverse,
                ShanhaiRecipeConditions.canonical(el));
    }

    /** 打开 / 关闭条件页。 */
    public boolean toggleConditions() {
        if (blank()) {
            return false;
        }
        condOpen = !condOpen;
        condNote = condOpen ? "打开条件页（" + conditionCount() + " 条）" : "关闭条件页";
        if (condOpen) {
            // 🔴 打开那一拍把"档位那张表"整张打进日志：用户点单要的读数
            //    （类型 → 档位项数 → 前几项的名字；没有档位的类型也点名列出来）。
            ShanhaiMod.LOGGER.info("{} workspace_cond_values types={} table={}",
                    PREFIX, ShanhaiRecipeConditions.knownTypeKeys().size(),
                    ShanhaiRecipeConditions.valueTableSummary(server));
        }
        ShanhaiMod.LOGGER.info("{} workspace_cond_panel open={} conditions={} types={} source={}",
                PREFIX, condOpen, conditionCount(), ShanhaiRecipeConditions.summary(conditions), condSource);
        touch();
        return condOpen;
    }

    /**
     * 把选中那行的值载入编辑控件（选中/新增/改类型之后都要走它）。
     *
     * <p>🔴 第 5 轮：这里改读<b>一个</b>值 {@link #condValue}（＝档位下拉选中的那一项），
     * 而不再读"模块 id ＋ 数量 ＋ 取反"三件套。
     */
    private void loadCondEditor() {
        if (condSel < 0 || condSel >= conditions.size()) {
            return;
        }
        final JsonElement el = conditions.get(condSel);
        condTypeKey = ShanhaiRecipeConditions.typeKeyOf(el);
        if (condTypeKey.isEmpty() || !ShanhaiRecipeConditions.isKnownType(condTypeKey)) {
            // 未知条件：类型照实记下来（面板显示"未知条件"），值不假装能读
            condTypeKey = condTypeKey.isEmpty() ? "(没有 type)" : condTypeKey;
            condValue = "";
            condReverse = ShanhaiRecipeConditions.isReverse(el);
            return;
        }
        condReverse = ShanhaiRecipeConditions.isReverse(el);
        condValue = ShanhaiRecipeConditions.valueKeyOf(condTypeKey, el);
        if (condValue.isEmpty() && condHasValueColumn()) {
            // 老记录里那个值是空的（或写法认不出来）⇒ 落到第一项，让下拉永远有个确定的值
            final List<ShanhaiRecipeConditions.Value> vs =
                    ShanhaiRecipeConditions.valueTableOf(server, condTypeKey);
            condValue = vs.isEmpty() ? "" : vs.get(0).key();
        }
    }

    // ---------------------------------------------------------------- 文本（贴到控件上）

    public String headerText() {
        if (blank()) {
            return "";
        }
        switch (stage) {
            case TYPES:
                return "§7第一屏：§f选配方类型 §8（共 " + types.size() + " 种）";
            case RECIPES:
                if (vanillaType) {
                    return "§7第二屏：§f" + ShanhaiVanillaRecipeView.typeLabel(typeId)
                            + " §8· " + (typeId == null ? "?" : typeId) + " §7共 " + recipes.size() + " 条"
                            + " §8(原版配方)";
                }
                return "§7第二屏：§f" + ShanhaiRecipeEditorSession.typeName(
                        typeId == null ? null : typeId.getPath()).text()
                        + " §8· " + (typeId == null ? "?" : typeId) + " §7共 " + recipes.size() + " 条";
            case QUERY:
                final String qn = queryKind == null ? "" : queryKind.zh;
                return "§7查询结果：§f" + qn + " §8· §7共 §f" + queryResult.total() + " §7条 / §f"
                        + queryResult.groups().size() + " §7种配方种类";
            default:
                if (vanillaType && vanillaView != null) {
                    return "§7第三屏：§f" + ShanhaiRecipeEditorSession.shortId(recipeId) + " §8("
                            + vanillaView.typeLabel() + " · 原版配方)";
                }
                return "§7第三屏：§f" + ShanhaiRecipeEditorSession.shortId(recipeId) + " §8("
                        + (typeId == null ? "?" : ShanhaiRecipeEditorSession.typeName(typeId.getPath()).text()) + ")";
        }
    }

    /**
     * 标题下面那一行提示。
     *
     * <p>🆕 第 8 轮（队列 #6）：列表里那两屏（第一屏类型 / 第二屏卡片）末尾各加了<b>一句翻页加速</b>
     * 的说明 —— 用户点单的原话里有一条硬要求：「<b>必须让功能可发现</b>」（否则玩家不知道有这功能）。
     * 只写在 tooltip 里不够（要悬停才看得见），所以这里也写一行小字，
     * 两处用<b>同一个常量</b> {@link #pageJumpHint()} 拼，免得两处口径写岔。
     */
    public String hintText() {
        if (blank()) {
            return "";
        }
        if (stage == Stage.EDIT) {
            // 🆕 非 GT：那一行说明直接告诉他能改什么 / 为什么不能改（用户点单的"功能可发现"）
            if (vanillaType && vanillaNote != null && !vanillaNote.isEmpty()) {
                return vanillaNote;
            }
            return "§8左键从 JEI 拖入 · 右键删 · 中键改数量";
        }
        if (stage == Stage.TYPES) {
            return "§8第 " + (page + 1) + "/" + pageCount() + " 页 · 点一行进入下一步"
                    + " · 或者在下面用物品框 / 搜索框查" + pageJumpHint();
        }
        if (stage == Stage.QUERY) {
            // 🔴 2026-10-06：这一行原来写着「每张卡片右下角有『选择』与『选这类』」——
            //    **那是一句假话**（用户就是照着它去找「选这类」的）：卡片上只有一颗
            //    「选中」按钮，整张卡片与它是同一个动作（见 ShanhaiRecipeCardWidget#mouseClicked），
            //    「选这类」这颗按钮从来没存在过。用户截图里的话就是从这里来的。
            //    ⇒ 改成与实际一致，并把这颗真的存在的「新建配方」写上去。
            return "§8配方种类 " + (tabCount() == 0 ? 0 : tabIndex + 1) + "/" + tabCount()
                    + " · 第 " + (tabPage + 1) + "/" + tabPageCount() + " 排"
                    + " · §7点卡片进第三屏" + pageJumpHint()
                    + " · §7本种要加配方就点上面的「新建配方」";
        }
        return "§8第 " + (page + 1) + "/" + pageCount() + " 页 · 点一张卡片进下一步" + pageJumpHint();
    }

    /**
     * 🆕 第 8 轮（队列 #6）：翻页加速那一句提示（<b>只此一处拼接</b>，界面与 tooltip 共用）。
     *
     * <p>口径必须和 {@link #PAGE_STEP_SHIFT} / {@link #PAGE_STEP_CTRL} 一致 ——
     * 所以页数是从那两个常量<b>算出来的</b>，不是写死在句子里的。
     */
    public static String pageJumpHint() {
        return " §8· §7按住 §fShift§7/§fCtrl§7 点「上页/下页」= 一次翻 "
                + PAGE_STEP_SHIFT + "/" + PAGE_STEP_CTRL + " 页";
    }


    /** 第一列（输入）与第二列（输出）的分栏标题。 */
    public String inTitle() {
        if (blank()) {
            return "";
        }
        if (vanillaType) {
            // 🔴 第 12 刀：这一行原来写的是「(原版配方这一版只读)」—— 那是**阶段 1** 的口径。
            //    阶段 2 起输入就能改（拖入/右键/中键），第 12 刀起有序与无序共用同一张 3×3。
            //    用户截图点名过这句："标题还写着只读，但实际能改"。
            //    ⇒ 文案改成与实际一致，并且**在这里说清"位置有没有意义"**（两屏唯一真正的差别）。
            final boolean shapeless = vanillaShape != null
                    && vanillaShape.mode() == ShanhaiVanillaRecipeShape.Mode.LIST;
            return "§a输入 §7物品 " + io.countItem("inputs")
                    + (shapeless ? " §8(位置无所谓 · 拖入＝再加一样)" : " §8(拖入＝改/增 · 右键＝清空)");
        }
        return "§a输入 §7物品 " + io.countItem("inputs") + " · 流体 " + io.countFluid("inputs");
    }

    public String outTitle() {
        if (blank()) {
            return "";
        }
        if (vanillaType) {
            return "§c产物 §7物品 " + io.countItem("outputs") + " §8(拖进来 + 中键改数量)";
        }
        return "§c输出 §7物品 " + io.countItem("outputs") + " · 流体 " + io.countFluid("outputs");
    }

    public String numbersText() {
        if (blank()) {
            return "";
        }
        if (vanillaType) {
            final ShanhaiVanillaRecipeView v = vanillaView;
            if (v == null) {
                return "§7（没载入配方）";
            }
            final String kindZh = switch (v.kind) {
                case SHAPED -> "有形状合成";
                case SHAPELESS -> "无形状合成";
                case COOKING -> "烧炼";
                case STONECUTTING -> "切石机";
                case SMITHING_TRANSFORM -> "锻造台（变换）";
                case SMITHING_TRIM -> "锻造台（纹饰）";
                case SPECIAL -> "原版自定义合成";
                case UNSUPPORTED -> "未支持的类型";
            };
            final StringBuilder sb = new StringBuilder("§7类型 §f").append(kindZh)
                    .append(" §8(").append(v.typeId).append("§8)")
                    .append(" §7· 输入 §f").append(v.inputs.size()).append(" §7格");
            if (v.canEditCooking()) {
                sb.append(" §7· 烧炼 §f").append(pendingDuration).append(" §7t")
                        .append(" §7· 经验 §f").append(v.experience);
            }
            sb.append(v.canEditResult() ? " §8· 产物可改" : " §8· 产物不可改");
            return sb.toString();
        }
        final String v = tierIndex >= 0
                ? TIER_NAMES[tierIndex].split(" ")[1] + "V" : "?";
        final String name = tierIndex >= 0 ? TIER_NAMES[tierIndex].split(" ")[0] : "未定";
        return "§7耗时 §f" + pendingDuration + " §7t · §7电压 §f" + name + " §8(" + v + ")"
                + " §7× 电流 §f" + amperage + "§7A = §e耗能 §f" + pendingEut + " §7EU/t"
                + " §8(v×a 算出来的)";
    }

    /**
     * 🆕 第 7 轮（队列 #5）：编辑屏那一行<b>只读的「真实耗时」</b>。
     *
     * <h4>用户 2026-10-05 的精确口径（逐字）</h4>
     * <blockquote>「第2屏与第3屏都显示两条时间」<br>
     * 「第3屏：原始时间 显示·<b>可以改</b> / 真实时间 显示·<b>不可改</b>」<br>
     * 「原始时间 = 配方声明的那一条（第三屏现有那个「耗时(t)」输入框改的就是它）」<br>
     * 「真实时间 = 原始值 × gtlcore 的 durationMultiplier 之后的实际生效值」</blockquote>
     *
     * <p>所以：上面那个 {@code 耗时(t)} 输入框继续改<b>原始值</b>（一行代码没动），
     * 这一行把它换算之后的<b>实际值</b>显示出来。换算走
     * {@link ShanhaiRecipeDuration#toLive}（与"保存"那条路同一个函数 ⇒ 显示与生效不会分叉）。
     */
    public String realDurationText() {
        if (blank()) {
            return "";
        }
        if (vanillaType) {
            // 原版配方的时间就是它自己声明的那个数（没有 gtlcore 的 durationMultiplier 那一层）
            // ⇒ 如实说明"两者相同"，绝不套 GT 的换算表。
            final ShanhaiVanillaRecipeView v = vanillaView;
            if (v == null) {
                return "";
            }
            if (!v.canEditCooking()) {
                return "§8这个类型没有「时间」这个字段";
            }
            return "§7真实烧炼时间（只读）§f " + pendingDuration + " §7t §8(原版配方不做倍率换算，与上面一致)";
        }
        final int real;
        try {
            real = liveType == null ? pendingDuration
                    : ShanhaiRecipeDuration.toLive(liveType, pendingDuration);
        } catch (Throwable t) {
            return "§8真实耗时（只读）§7 算不出来：" + t.getClass().getSimpleName();
        }
        if (real == pendingDuration) {
            return "§7真实耗时（只读）§f " + real + " §7t §8(这个类型没被换算，两者相同)";
        }
        return "§7真实耗时（只读）§f " + real + " §7t §8(= 原始 " + pendingDuration
                + " × " + ratio(pendingDuration, real) + ")";
    }

    /** 「实际 ÷ 原始」这个乘数（三位小数），只用于那句说明。 */
    private static String ratio(int original, int actual) {
        if (original == 0) {
            return "?";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", (double) actual / (double) original);
    }

    /**
     * 🆕 第 7 轮（队列 #3）：<b>新建一条空配方</b>。
     *
     * <h4>用户原话</h4>
     * <blockquote>「第二屏加「新建配方」按钮」<br>
     * 「新建出来的配方要有合理默认值：类型 = 当前这一屏的类型 ／ 一个唯一可用的配方 id ／
     *   空的输入输出」<br>
     * 「🔴 id 撞了要能自己重试或报错，不许静默覆盖」</blockquote>
     *
     * <p>做法：<b>先找一个不撞的 id</b>（{@code shanhai:<类型路径>/new_recipe_1..N}，
     * 依次问"活索引里有没有 / 底本里有没有"），再造一份<b>空 IO</b> 的 GT 配方 JSON 交给
     * {@link ShanhaiRecipeEditorOps#addRecipeFromJson}（它用 GT 自己的反序列化器造对象 ⇒
     * 形状不对会当场失败，不会留一条机器读不懂的配方）。
     *
     * <p>落盘走覆盖层的 <b>{@code op=add}</b> 条目（那条通道脚本里本来就有，
     * 而且自带"id 已存在就拒收"的保护）。
     *
     * @return true = 建成并已经切到第三屏编辑它
     */
    public boolean newRecipe() {
        return newRecipe(true);
    }

    /**
     * 🆕 2026-10-06：<b>「新建配方」在哪几屏上成立</b> —— 这个决定的<b>唯一一处落点</b>。
     *
     * <h4>为什么必须独立成一个纯函数（用户报的那一条就是这个）</h4>
     * 用户原话：「从『搜索 / 获取途径』进去的类型页，<b>无法新增配方</b>」。
     * 真相是同一个决定被写在<b>两个</b>地方、而且都只认第二屏：
     * <pre>
     *   ① {@code ShanhaiRecipeEditorPanel#refreshVisibility} —— 按钮的【可见性】（stage == RECIPES）
     *   ② {@code ShanhaiRecipeEditorPanel#onNewRecipe}       —— 点击的【闸门】  （stage != RECIPES ⇒ return）
     *   ＋{@code ShanhaiRecipeEditorWorkspace#newRecipe}      —— 后端入口（stage != RECIPES ⇒ return）
     * </pre>
     * 三处各写一份 `stage == RECIPES`，于是查询结果屏（{@link Stage#QUERY}）上"按钮不可见、
     * 就算可见也点不动、就算点动了后端也不接"。⇒ 收成这一个纯函数：
     * <b>查询结果屏与第二屏在这一点上完全等价</b>（两者都是"一屏一种配方种类"的卡片屏，
     * 证据：{@code stageIsCards()} 一直是这么定义的、卡片与翻页那一排也一直按它走）。
     *
     * <p>⚠️ 输入栏屏与编辑屏<b>不</b>成立：那两屏没有"当前配方种类"这个量，
     * 硬开一个新建就必然要猜一个类型出来（那就是"点了之后建到别人头上"）。
     */
    public static boolean stageAllowsNewRecipe(Stage s) {
        return s == Stage.RECIPES || s == Stage.QUERY;
    }

    /**
     * 上一条判据的离线自检（<b>纯函数、不需要服务器</b>）。
     *
     * <p>判据 2 条正 + <b>3 条负对照</b>：没有负对照，"这两屏都成立"可能只是因为
     * 那个函数恒返回 true（本工程血规矩：检查器自己必须先被证明是对的）。
     */
    public static String newRecipeGateSelfTest() {
        final StringBuilder bad = new StringBuilder();
        int pass = 0;
        final int total = 5;
        if (stageAllowsNewRecipe(Stage.RECIPES)) {
            pass++;
        } else {
            bad.append(" 正对照失败：第二屏（配方列表）居然不许新建;");
        }
        if (stageAllowsNewRecipe(Stage.QUERY)) {
            pass++;
        } else {
            bad.append(" 正对照失败：查询结果屏不许新建（这一条正是用户报的那条）;");
        }
        if (!stageAllowsNewRecipe(Stage.TYPES)) {
            pass++;
        } else {
            bad.append(" 负对照A没响：类型列表屏也判成「可以新建」了;");
        }
        if (!stageAllowsNewRecipe(Stage.EDIT)) {
            pass++;
        } else {
            bad.append(" 负对照B没响：编辑屏也判成「可以新建」了;");
        }
        if (!stageAllowsNewRecipe(null)) {
            pass++;
        } else {
            bad.append(" 负对照C没响：null 也判成「可以新建」了;");
        }
        final String line = "recipe_new_recipe_gate_selftest " + pass + "/" + total
                + " PASS=" + (pass == total)
                + "（判据：第二屏与查询结果屏都能新建配方；类型列表屏/编辑屏/null 都不行；含 3 条负对照）";
        if (bad.length() > 0) {
            ShanhaiMod.LOGGER.error("[SHANHAI-EDITOR] recipe_new_recipe_gate_selftest FAILED: {}",
                    bad.toString());
        }
        return line;
    }

    /**
     * 🆕 2026-10-06：<b>「新建配方」要在哪个类型下建</b> —— 三条路共用的唯一一处口径。
     *
     * <h4>为什么必须单独一个方法</h4>
     * 用户报的是「从『搜索 / 获取途径』进去的类型页，无法新增配方」。真相不是"按钮画错了"，
     * 而是<b>"当前类型"这个量在查询结果屏上根本没人填</b>：
     * <pre>
     *   · 类型花名册那条路（第一屏点一行 ⇒ selectType ⇒ Stage.RECIPES）  ⇒ {@link #typeId} 是那个人点的类型
     *   · 文本搜索 / 获取途径（runQuery ⇒ Stage.QUERY）                    ⇒ {@link #typeId} 【没被赋值】
     *     （查询结果屏一屏只显示【一种】配方种类，那一行数字是 {@code 共 N 条 / M 种}，
     *       当前显示的那一种 = {@link ShanhaiRecipeQuery.Result#groups()} 的第 {@code tabIndex} 个）
     * </pre>
     * ⇒ 查询结果屏上的"当前类型"= <b>当前那一排标签里选中的那一组的类型 id</b>，
     *   而不是 {@code typeId}（它可能还是上一个类型的残留值，甚至是 null）。
     *
     * @return 当前这一屏要新建到哪个类型；{@code null} = 这一屏没有"当前类型"（类型列表屏 / 编辑屏）
     */
    private ResourceLocation newRecipeTargetType() {
        if (!stageAllowsNewRecipe(stage)) {
            return null;
        }
        if (stage == Stage.RECIPES) {
            return typeId;
        }
        // Stage.QUERY：当前显示的那一种配方种类
        final List<ShanhaiRecipeQuery.Group> gs = queryResult.groups();
        if (tabIndex < 0 || tabIndex >= gs.size()) {
            return null;
        }
        return gs.get(tabIndex).typeId();
    }

    /**
     * 🆕 第 8 轮：#4 的自检要在「被删光的那个类型」上真建一条，而<b>自检不许往
     * {@code config/shanhai/recipe_overrides.json} 里留东西</b>（那是用户的编辑台账）
     * ⇒ 把"落盘"这一步做成开关。
     *
     * <p>⚠️ 面板走的一定是 {@link #newRecipe()}（= {@code persist=true}），
     * 这条重载<b>只</b>给自检用；{@code persist=false} 时第 ④ 步整个跳过，
     * 其余（找 id / 造对象 / 进索引与原版表 / 打开编辑屏）<b>一行都不少</b>
     * —— 这样它验的才是真路径，而不是"另一条简化路"。
     */
    public boolean newRecipe(boolean persist) {
        if (blank()) {
            return false;
        }
        // 🔴🔴 2026-10-06（用户报：从「搜索 / 获取途径」进去的类型屏没有「新建配方」）：
        //    入口闸门原来是 `stage != Stage.RECIPES`，于是**查询结果屏（Stage.QUERY）上那一条路被堵死**
        //    （查询结果屏本身就是"一屏一种配方种类"，与第二屏是同一种东西）。
        //    ⇒ 闸门改成"当前这一屏有没有'当前类型'"这一个问题，由 newRecipeTargetType() 统一回答；
        //      三条路（类型花名册 / 文本搜索 / 获取途径）因此**共用同一个入口**，不可能再分叉。
        final ResourceLocation target = newRecipeTargetType();
        if (target == null) {
            return false;
        }
        if (!target.equals(typeId)) {
            // 把"当前类型"切到用户眼前这一种。⚠️ 只换类型上下文，不动 stage/page
            // （RECIPES 屏下 target == typeId ⇒ 这里一个字都不会动，已验收的那条路逐位不变）。
            selectTypeKeepStage(target);
        }
        // 🆕 第 12 刀：非 GT 那一支（工作台 / 熔炉 / 切石机…）
        if (vanillaType) {
            return newVanillaRecipe(persist);
        }
        final GTRecipeType type = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(typeId);
        if (type == null) {
            say("§c这个配方类型没有注册，建不了", "§8type=" + typeId);
            return false;
        }
        // ── ① 找一个唯一可用的 id（撞了就下一个，绝不复用）──
        ResourceLocation newId = null;
        int tries = 0;
        for (int i = 1; i <= 5000; i++) {
            tries = i;
            final ResourceLocation cand = new ResourceLocation("shanhai",
                    typeId.getPath() + "/new_recipe_" + i);
            if (ShanhaiRecipeReverseIndex.byId(server, cand) != null) {
                continue;
            }
            if (ShanhaiRecipeBase.pristine(cand) != null) {
                continue;
            }
            newId = cand;
            break;
        }
        if (newId == null) {
            say("§c找不到可用的新配方 id（试了 " + tries + " 个都撞了）",
                    "§8shanhai:" + typeId.getPath() + "/new_recipe_*");
            return false;
        }

        // ── ② 造一份空 IO 的 GT 配方 JSON（形状与 local/kubejs/export/recipes/** 一致）──
        //    🔴 冒烟第一版这里漏了 tickInputs.eu ⇒ 读数 add_recipe_applied eut=0
        //       （底本那份 JSON 里 data.euTier 有、可真实的 EU 一条都没有）。
        //       形状逐字抄自用户实例的导出文件
        //       local/kubejs/export/recipes/ad_astra/assembler/calorite_engine.json。
        final int dur = DEFAULT_NEW_DURATION;
        final long eut = DEFAULT_NEW_EUT;
        final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", typeId.toString());
        json.addProperty("duration", dur);
        final com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("euTier", com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(Math.abs(eut)));
        json.add("data", data);
        final com.google.gson.JsonObject euContent = new com.google.gson.JsonObject();
        euContent.addProperty("content", eut);
        euContent.addProperty("chance", 10000);
        euContent.addProperty("maxChance", 10000);
        euContent.addProperty("tierChanceBoost", 0);
        final com.google.gson.JsonArray euArr = new com.google.gson.JsonArray();
        euArr.add(euContent);
        final com.google.gson.JsonObject tickIn = new com.google.gson.JsonObject();
        tickIn.add("eu", euArr);
        json.add("tickInputs", tickIn);

        // 🔴🔴 2026-10-06（用户抓到 `index_rebuild_add_refused` ＋ 机器判据坐实）：
        //    **新建的那条必须带一个【占位输入】，否则它进不了 GT 索引 ⇒ JEI 永远拿不到它。**
        //
        //    取证（本轮判据 `new_recipe_enters_gt_index` 的原文读数）：
        //      新建 shanhai:space_ore_processor/new_recipe_1
        //        ⇒ GT 索引能按 id 读回 = **false**   ← 索引里真的没有它
        //        · 反查索引能读回 = true             （所以【编辑器自己】看得见，JEI 看不见）
        //    为什么：**GT 的配方索引树是按【输入】建键的**；一条零输入的配方 `lookup.addRecipe(r)`
        //    收下了、却没有任何键指向它 ⇒ 按 id 也读不回来 ⇒ 而 JEI 的 GT 插件正是从这棵树取配方
        //    ⇒ **界面怎么刷都拿不到** ✗（这才是"新增看不到"的第一层原因，界面层是第二层）
        //    ⇒ 修法照【非 GT 新建】那条既有纪律（那边早就是这样：占位物＝屏障，见 newVanillaRecipe
        //      的注释"新建时必须给一份形状合法、但生存里拿不到的占位物"）：
        //      **GT 新建也塞一个屏障占位输入**，用户进第三屏第一件事就是换成真输入 ✓
        // 🔴🔴 2026-10-06：**形状必须照抄导出文件**（唯一真值来源，逐字比对过）：
        //    local/kubejs/export/recipes/ad_astra/assembler/calorite_engine.json
        //      "inputs": { "item": [ { "content": { "type":"gtceu:sized", "count":1,
        //                                          "ingredient": { "item":"gtceu:naquadah_frame" } },
        //                              "chance":10000, "maxChance":10000, "tierChanceBoost":0 } ],
        //                  "fluid": [ { "content": { "amount":1296, "value":[{"fluid":"…"}] }, … } ] }
        //    ⚠️ 我第一版把 inputs 写成了**数组**、content 写成了数字 ⇒ GT 反序列化直接丢掉这一段
        //       ⇒ 等于还是"零输入" ⇒ 占位根本没生效（判据读数没变就是这个原因）。
        final com.google.gson.JsonObject inputs = new com.google.gson.JsonObject();
        final com.google.gson.JsonArray itemInputs = new com.google.gson.JsonArray();
        final com.google.gson.JsonObject phContent = new com.google.gson.JsonObject();
        phContent.addProperty("type", "gtceu:sized");
        phContent.addProperty("count", 1);
        final com.google.gson.JsonObject phIngredient = new com.google.gson.JsonObject();
        phIngredient.addProperty("item", "minecraft:barrier");
        phContent.add("ingredient", phIngredient);
        final com.google.gson.JsonObject phEntry = new com.google.gson.JsonObject();
        phEntry.add("content", phContent);
        phEntry.addProperty("chance", 10000);
        phEntry.addProperty("maxChance", 10000);
        phEntry.addProperty("tierChanceBoost", 0);
        itemInputs.add(phEntry);
        inputs.add("item", itemInputs);
        json.add("inputs", inputs);

        // 🔴🔴 2026-10-06（用户纠正："左键**原料**居然列出了这条新配方"）：**产物侧也必须给占位**。
        //    现象：左键【原料】= "这个物品怎么获得"（JEI 是按【输出】查的）却列出了我们的新配方，
        //    而用真正的【产物】去查反而找不到 ⇒ **"输出"那一侧挂上去的是输入物品** ✓✓
        //    一个假设解释两个现象 ⇒ 最可能是**这条配方的产物侧是空的**（我上一版只给了输入占位）
        //    ⇒ JEI 那边只能拿"有的那一侧"（输入）去建键 ⇒ 于是"原料能查到、产物查不到" ✓
        //    ⇒ 修法与【非 GT 新建】完全一致（那边产物就是屏障占位："生存里拿不到的占位物"）：
        //      **GT 新建也给一个屏障占位产物**，用户进第三屏第一件事就是换成真产物 ✓
        final com.google.gson.JsonObject outputs = new com.google.gson.JsonObject();
        final com.google.gson.JsonArray itemOutputs = new com.google.gson.JsonArray();
        final com.google.gson.JsonObject outContent = new com.google.gson.JsonObject();
        outContent.addProperty("type", "gtceu:sized");
        outContent.addProperty("count", 1);
        final com.google.gson.JsonObject outIngredient = new com.google.gson.JsonObject();
        outIngredient.addProperty("item", "minecraft:barrier");
        outContent.add("ingredient", outIngredient);
        final com.google.gson.JsonObject outEntry = new com.google.gson.JsonObject();
        outEntry.add("content", outContent);
        outEntry.addProperty("chance", 10000);
        outEntry.addProperty("maxChance", 10000);
        outEntry.addProperty("tierChanceBoost", 0);
        itemOutputs.add(outEntry);
        outputs.add("item", itemOutputs);
        json.add("outputs", outputs);

        // ── ③ 造对象并立刻生效 ──
        final GTRecipe made = ShanhaiRecipeEditorOps.addRecipeFromJson(server, newId, json);
        if (made == null) {
            say("§c新建失败（GT 反序列化没过）", "§8id=" + newId + " · 详见日志 add_recipe_parse_failed");
            return false;
        }
        // 原始耗时记账（与"改耗时"同一条口径：面板读的是原始值，落到 GTRecipe 上的是实际值）
        ShanhaiRecipeDuration.setCurrentOriginal(newId, dur);

        // 🔴🔴 <b>本轮（只修"卡几秒"与"JEI 不显示"两件）：新建成功这一拍【当场】发那条单条补丁。</b>
        //
        // 现场问题（读代码即可确认）：这条新建路径以前【一个包都不发】——
        // 它靠的是"每次保存都整表广播"才在客户端 JEI 里出现（所以用户看到的"新增有时能刷、
        // 有时不能刷"）。
        // 本轮把整表广播摘掉之后（那一发会让 JEI 全量重注册、卡 10.8~15.2 秒），
        // 新建这条就必须有自己的单条补丁：载荷里带 GT 自己的网络字节 ⇒ 客户端把这条
        // 【补进】JEI 那一页（客户端本来没有它 ⇒ 走 added 那条路，不是替换）。
        ShanhaiJeiBridge.broadcastRecipeChanged(server, made);

        // 🔴🔴 2026-10-05（用户睡前那条决定性发现："删除/添加之后，只要我再改任意别的配方，
        //    第一面就同步了"）⇒ 说明**"改"那条路会做的那件事，"增/删"没做**。
        //    那件事就是：**把新配方写回 `RecipeManager` 那张表**（保存路径里的 syncVanillaFromBase）。
        //    为什么第一屏的条数依赖它：第一屏的条数是 `buildTypeRows()` 从【反查索引】数出来的，
        //    而**反查索引的数据源就是 `RecipeManager.getRecipes()`**（见 ShanhaiRecipeReverseIndex
        //    类头第 2 节）⇒ 不写回表，索引重建也看不见这条新配方 ⇒ 条数永远差一。
        //    ⇒ 与保存那条路口径一致：**写完就同步写回表**（实测 ≈150~260 ms，一次新建一次）。
        ShanhaiRecipeEditorOps.syncVanillaFromBase(server);

        // ── ④ 落盘：op=add（id 已存在时覆盖层会自己拒收并打 STALE）──
        boolean persisted = false;
        String persistNote;
        if (!persist) {
            persistNote = "未落盘（persist=false：自检专用，config 一个字节都没动）";
        } else {
            try {
                final com.google.gson.JsonObject entry = ShanhaiRecipeOverrideStore.makeAddEntry(
                        ShanhaiRecipeOverrideStore.nextUid(), newId.toString(),
                        typeId.toString(), json);
                persisted = ShanhaiRecipeOverrideStore.upsert(entry) >= 0;
                persistNote = persisted ? "已写进 config/shanhai/recipe_overrides.json（op=add）"
                        : "落盘返回失败";
            } catch (Throwable t) {
                persistNote = "落盘抛异常：" + t;
            }
        }

        // ── ⑤ 刷新列表并切到第三屏编辑它 ──
        // 🔴 第一屏也要跟着重算（用户实测："第二面可以热更新了，但是第一面不行"）
        recountFirstScreen("new_recipe");
        selectTypeKeepStage(typeId);
        stage = Stage.RECIPES;
        page = 0;
        say("§a已新建配方 §f" + ShanhaiRecipeEditorSession.shortId(newId)
                        + " §7（空输入输出 · 原始耗时 " + dur + " t · ULV 1A）",
                "§8id=" + newId + " · " + persistNote);
        ShanhaiMod.LOGGER.info("{} workspace_new_recipe id={} type={} dur={} eut={} tries={} persisted={} note={}",
                PREFIX, newId, typeId, dur, eut, tries, persisted, persistNote);
        final boolean opened = selectRecipe(newId);
        if (!opened) {
            // 索引没跟上也要说清楚（不许静默停在一个空屏上）
            say("§c新建成功但立刻打开失败（索引没跟上）", "§8点「重读配方表」再进来");
            return false;
        }
        return true;
    }

    /** 新建配方的默认原始耗时（10 秒）。 */
    public static final int DEFAULT_NEW_DURATION = 200;
    /** 新建配方的默认耗能（ULV 8V × 1A）。 */
    public static final long DEFAULT_NEW_EUT = 8L;

    // ================================================================= 🆕 第 12 刀：新建一条【非 GT】配方
    //
    //   用户点单（第 12 刀任务书）：
    //     「本刀要补：新建一条非 GT 配方 ／ 删除一条非 GT 配方」
    //   与 GT 那一条的差别（三条，全都是结构性的）：
    //     ① 原版配方【不许空】—— ShapedRecipe.Serializer 会以 Invalid pattern 直接拒收一条
    //        "没有形状/没有产物"的配方（GT 的配方允许空 IO）⇒ 新建时必须给一份**形状合法、
    //        但生存里拿不到的占位物**（屏障）。这样"刚点的新建"物理上不可能被当成能用的配方。
    //     ② 原版配方的字段全 final ⇒ 后面每一次编辑都是"从底本重建一条同类新实例"
    //        （阶段 2 已经做好并验过）；重建的底本就是这里登记进去的那一条。
    //     ③ 1.20.1 里 crafting_shaped 与 crafting_shapeless 的 RecipeType **都是**
    //        `minecraft:crafting` ⇒ 类型那一步分不出有形状/无形状，本版默认建**有形状**的
    //        （写进报告的「待你确认」）。

    /**
     * 在当前这一屏的类型下建一条非 GT 配方：
     * <b>找一个不撞的 id</b>（{@code shanhai:<类型路径>/new_recipe_N}，依次查底本/活表/覆盖文件）
     * → 造一份形状合法的空壳 JSON（占位物＝屏障）→ 交给
     * {@link ShanhaiVanillaRecipeOps#addRecipeFromJson}（它用原版自己的序列化器造对象，
     * 形状不对当场失败、id 撞了当场失败）→ 落盘 {@code op=add} → 切到第三屏编辑它。
     *
     * @return true = 建成并已经切到第三屏编辑它
     */
    private boolean newVanillaRecipe(boolean persist) {
        final String serType = ShanhaiVanillaRecipeOps.newSerializerTypeFor(typeId);
        if (serType == null) {
            // 🔴 不硬塞：其余 13 个 mod 的 37 个类型的字段形状不是"物品进/物品出"那一套
            //    （ammo/weight、terminalA/terminalB、fluid_input…），塞一条模板进去只会造出
            //    一条各 mod 自己读不懂的配方。本版对它们保持【只读 ＋ 可见提示】。
            say("§e这个类型本版不支持新建配方",
                    "§8" + ShanhaiVanillaRecipeView.typeLabel(typeId) + "（" + typeId + "）的配方形状是那个 mod"
                            + "自己定的（不是「物品进/物品出」），硬塞一条模板会造出它自己读不懂的配方 ⇒ 本版只读。");
            ShanhaiMod.LOGGER.warn("{} workspace_new_vanilla_refused type={} reason=type_not_whitelisted "
                    + "(本版只给 minecraft 自己的那几种开新建)", PREFIX, typeId);
            return false;
        }
        final com.google.gson.JsonObject json = ShanhaiVanillaRecipeOps.newRecipeSkeleton(serType);
        if (json == null) {
            say("§c这个类型暂时建不了", "§8没有 " + serType + " 的空壳模板");
            return false;
        }
        // ── ① 找一个唯一可用的 id（撞了就下一个，绝不复用）──
        final ResourceLocation newId = ShanhaiVanillaRecipeOps.findFreeId(server, typeId);
        if (newId == null) {
            say("§c找不到可用的新配方 id（试了 5000 个都撞了）",
                    "§8shanhai:" + typeId.getPath() + "/new_recipe_*");
            return false;
        }
        // ── ② 造对象 ＋ 立刻生效 ＋（可选）落盘 ──
        final String[] note = new String[1];
        final net.minecraft.world.item.crafting.Recipe<?> made =
                ShanhaiVanillaRecipeOps.addRecipeFromJson(server, typeId, newId, json, persist, note);
        if (made == null) {
            say("§c新建失败（原版没接收这条配方）",
                    "§8id=" + newId + " · " + (note[0] == null ? "详见日志" : note[0]));
            return false;
        }
        // ── ③ 刷新列表并切到第三屏编辑它 ──
        //   selectTypeKeepStage 会重扫非 GT 索引（新那条在里面了），selectRecipe 再读进缓冲。
        selectTypeKeepStage(typeId);
        stage = Stage.RECIPES;
        page = 0;
        say("§a已新建配方 §f" + ShanhaiRecipeEditorSession.shortId(newId)
                        + " §7（" + ShanhaiVanillaRecipeView.typeLabel(typeId) + " · 占位物＝屏障，"
                        + "把输入与产物换成你要的再保存）",
                "§8id=" + newId + " · " + note[0]);
        ShanhaiMod.LOGGER.info("{} workspace_new_vanilla_recipe id={} type={} ser_type={} class={} note={}",
                PREFIX, newId, typeId, serType, made.getClass().getName(), note[0]);
        // 🔴 本轮：非 GT 的新建同样【当场】发单条补丁，而且必须带这条配方整条的字节
        //    （原因：客户端 JEI 那一页里本来没有它 ⇒ 只能靠字节补进去；只发字段补不进去）。
        try {
            ShanhaiJeiBridge.broadcastVanillaChanged(server, newId, typeId,
                    "{}", ShanhaiJeiBridge.encodeVanillaRecipe(made));
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} workspace_new_vanilla_jei_broadcast_failed id={} err={}",
                    PREFIX, newId, t.toString(), t);
        }
        final boolean opened = selectRecipe(newId);
        if (!opened) {
            say("§c新建成功但立刻打开失败（索引没跟上）", "§8点「重读配方表」再进来");
            return false;
        }
        return true;
    }

    /** 电压 × 电流 那一行（自检/日志判据用）。 */    public String energyCalcLine() {
        final long v = tierIndex >= 0 ? TIER_VOLTAGE[tierIndex] : 0L;
        return "voltage=" + v + " amps=" + amperage + " eut=" + pendingEut
                + " expected=" + Math.min(Integer.MAX_VALUE, v * (long) amperage)
                + " consistent=" + (pendingEut == (int) Math.max(0L, Math.min(Integer.MAX_VALUE, v * (long) amperage)));
    }

    /**
     * 🔴🔴 <b>2026-10-05（用户实测：新增之后"第二面可以热更新了，但是第一面不行"）：第一屏与第二屏一起重算。</b>
     *
     * <h4>根因（比"表版本没变"更准的一句）</h4>
     * 面板上那几个改配方的动作（新建 / 删除这条 / 恢复原样 / 保存）走完之后，各自都<b>自己重建了第二屏</b>
     * （典型：{@code newRecipe} 末尾的 {@code selectTypeKeepStage(typeId); stage = RECIPES;}），
     * <b>但从来没有重算过第一屏的类型行</b>；而第一屏那句 {@code (N 条)} 是<b>服务端算好推给客户端</b>的，
     * 推送闸还额外看 {@code numbersVersion}（见面板的 {@code detectAndSendChanges}：
     * 列表文字看 {@code version()}、数字文字看 {@code numbersVersion()}）。
     * ⇒ 两样（重算 + 涨版本号）缺一不可，所以用户看到的是"第二屏动了、第一屏纹丝不动" ✓
     *
     * <p>本方法把这两件事做全，供所有"改了配方"的动作在收尾处调用（一个口子，避免各处再漏）。
     */
    private void recountFirstScreen(String reason) {
        try {
            final int beforeRows = types.size();
            // 🔴🔴 关键一步（第一版漏了 ⇒ 判据抓出"条数 1622 -> 1622"、"再改一条别的才同步"）：
            //    ① `buildTypeRows()` 从【反查索引】数条，而索引是懒快照；
            //    ② **`ShanhaiRecipeReverseIndex.all()` 只是 `List.copyOf(RECIPES)`，它自己不调 `ensure()`**
            //       ⇒ 光调 `invalidate()` **没有任何人消费这个标志** ⇒ 数出来还是旧快照 ✗
            //    ③ 索引的**数据源是 `RecipeManager.getRecipes()`**（见该类头部第 2 节）⇒ 新建/删除若还没把
            //       那张表写回，索引重建也照样看不见它。
            //    ⇒ 这里必须【失效 ＋ 强制重建】两件一起做；"把表写回"由调用方先做（见 new_recipe 那处）。
            ShanhaiRecipeReverseIndex.invalidate();
            ShanhaiRecipeReverseIndex.ensure(server);
            buildTypeRows();
            numbersVersion++;      // ← 涨推送闸（不涨 ⇒ 面板一个字节都不推给客户端）
            touch();
            final int shown = typeId == null ? -1 : countOfType(typeId);
            ShanhaiMod.LOGGER.info("{} workspace_types_recounted reason={} rows={}->{} "
                            + "type={} count={} numbers_version={} "
                            + "（第一屏当场重算：新建/删除/恢复之后那句 (N 条) 不用点「重读配方表」）",
                    PREFIX, reason, beforeRows, types.size(), typeId, shown, numbersVersion);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} workspace_types_recount_failed reason={} err={}", PREFIX, reason, t.toString());
        }
    }

    /** 第一屏里这个类型的当前条数（判据用；没有这一行返回 -1）。 */
    public int countOfType(ResourceLocation id) {
        if (id == null) {
            return -1;
        }
        for (TypeRow t : types) {
            if (id.equals(t.id())) {
                return t.count();
            }
        }
        return -1;
    }

    /** 一行列表文字（服务端算好、推给客户端画）。 */
    public String rowText(int row) {
        if (blank()) {
            return "";
        }
        final int idx = page * ROWS + row;
        if (stage == Stage.TYPES) {
            if (idx < 0 || idx >= types.size()) {
                return "";
            }
            final TypeRow t = types.get(idx);
            return "§8· §f" + t.name() + " §7(" + t.count() + " 条) §8" + t.id();
        }
        if (stage == Stage.RECIPES) {
            if (idx < 0 || idx >= recipes.size()) {
                return "";
            }
            final ResourceLocation id = recipes.get(idx);
            if (vanillaType) {
                // 🆕 非 GT：这一行的"读数"换成它的产物与烧炼时间（GT 那两样在这里没有意义）
                final net.minecraft.world.item.crafting.Recipe<?> vr = vanillaFromIndexOrTable(id);
                if (vr == null) {
                    return "§8· §f" + ShanhaiRecipeEditorSession.shortId(id);
                }
                final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(vr);
                final String out = v.result.isEmpty() ? "§8(无产物)"
                        : "§f" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(v.result.getItem()).getPath() + " §7x" + v.result.getCount();
                return "§8· §f" + ShanhaiRecipeEditorSession.shortId(id) + " §8→ " + out
                        + (v.canEditCooking() ? " §7" + v.cookingTime + "t" : "")
                        + (v.editable() ? "" : " §e(不可编辑)");
            }
            final GTRecipe r = ShanhaiRecipeReverseIndex.byId(server, id);
            return "§8· §f" + ShanhaiRecipeEditorSession.shortId(id)
                    + (r == null ? "" : " §7d=" + r.duration + " §8EU/t=" + ShanhaiRecipeIoApply.euOf(r));
        }
        return "";
    }

    public boolean loadedRecipe() {
        return loadedRecipe;
    }

    // ---------------------------------------------------------------- 电压档位工具

    /** 由 EU/t 反查档位下标（找不到 = -1）。 */
    public static int tierOf(long eut) {
        final long v = Math.abs(eut);
        int best = -1;
        for (int i = 0; i < TIER_VOLTAGE.length; i++) {
            if (TIER_VOLTAGE[i] <= v) {
                best = i;
            }
        }
        return best;
    }

    public static int tierIndexByName(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < TIER_NAMES.length; i++) {
            if (TIER_NAMES[i].equals(name) || TIER_NAMES[i].startsWith(name + " ")) {
                return i;
            }
        }
        // 下拉里显示的是数字（见 tierLabel），这里再按数字比一次
        try {
            final int v = Integer.parseInt(name.trim());
            return tierOf(v);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 两侧同步

    /**
     * 服务端 → 客户端：把这一屏需要的状态写出去。
     *
     * @param includeNumbers 只在"刚载入一条配方"那一拍为 true（理由见类注释 §3）
     */
    public void writeState(FriendlyByteBuf buf, boolean includeNumbers) {
        buf.writeVarInt(stage.ordinal());
        buf.writeInt(page);
        buf.writeVarInt(pageCount());
        buf.writeInt(countCell);
        buf.writeVarInt(inPage);
        buf.writeVarInt(outPage);
        // 🆕 小窗那几项【每次都推】：它们很小，而且"不消耗"开关是服务端一侧翻的
        //    （见 togglePendingCatalyst），不随 numbersVersion 那一批走就会显示成旧值。
        buf.writeVarInt(pendingCount);
        buf.writeVarInt(pendingChance);
        buf.writeVarInt(pendingBoost);
        buf.writeBoolean(pendingCatalyst);
        // 🆕 B 组：条件那一屏的状态也【每次都推】（同样是"服务端一侧翻的开关 + 一张小表"）
        buf.writeUtf(conditions == null ? "[]" : conditions.toString());
        buf.writeVarInt(condSel);
        buf.writeBoolean(condOpen);
        buf.writeUtf(condTypeKey);
        // 🔴 第 5 轮：原来的"档位 / 模块 id / 数量"三个字段，现在合成【一个档位值】。
        //    协议两侧同在这个文件里、同一个 jar ⇒ 一起改；旧的两个槽位删掉，不再留着占位。
        buf.writeUtf(condValue == null ? "" : condValue);
        buf.writeBoolean(condReverse);
        buf.writeUtf(condSource == null ? "" : condSource);
        io.writeState(buf);
        // 🆕 第 7 轮：查询那一块。
        //    ⚠️ 物品槽用 writeItem —— 它的数量是 writeByte，但这里只关心"是哪个物品"，
        //       数量在卡片上另有 VarInt 通道（见 ShanhaiRecipeQuery#writeCard 的注释）。
        buf.writeItem(queryItem == null ? ItemStack.EMPTY : queryItem);
        buf.writeUtf(searchText == null ? "" : searchText);
        buf.writeVarInt(queryKind == null ? 0 : queryKind.ordinal());
        // 🔴🔴 2026-10-05 第 8 轮（P0）：这两行的**【顺序】必须与 readState 逐字对应**。
        //    原来是 write(note, total) 而 readState 读的是 (total, note) —— 两侧反了，
        //    后果是【客户端一整屏打不开】：
        //      readInitialData 在这里开始读错位 ⇒ 后面每一个字段全错位 ⇒ 抛异常；
        //      而这个异常被 Forge 的 NetworkEvent.Context.enqueueWork 吞掉
        //      （它把 Runnable 丢进 CompletableFuture 却不取结果 ⇒ 异常【不写日志】）⇒
        //      UIFactory.initClientUI 里那句 setScreen 永远走不到 ⇒
        //      用户看到的就是"敲 /shanhai edit 完全没反应"，日志里连一个 ERROR 都没有。
        //    ⇒ 纪律：本方法的字段顺序 = readState 的字段顺序，一一对应，改一边必须同时改另一边。
        //      （机器判据：自检 case=panel_sync_roundtrip_* ，含一条专门证明"顺序反了会被抓到"的负对照。）
        buf.writeUtf(queryResult.note() == null ? "" : queryResult.note());
        buf.writeVarInt(queryResult.total());
        buf.writeVarInt(tabCount());
        buf.writeVarInt(tabIndex);
        buf.writeVarInt(tabPage);
        buf.writeVarInt(tabPageCount());
        refreshTabs();
        buf.writeVarInt(tabs.size());
        for (ShanhaiRecipeQuery.Tab t : tabs) {
            ShanhaiRecipeQuery.writeTab(buf, t);
        }
        if (stage == Stage.QUERY || stage == Stage.RECIPES) {
            final List<ShanhaiRecipeQuery.Card> pageCards = buildCards();
            buf.writeVarInt(pageCards.size());
            for (ShanhaiRecipeQuery.Card c : pageCards) {
                ShanhaiRecipeQuery.writeCard(buf, c);
            }
        } else {
            buf.writeVarInt(0);
        }
        buf.writeBoolean(includeNumbers);
        if (includeNumbers) {
            buf.writeInt(pendingDuration);
            buf.writeInt(pendingEut);
            buf.writeInt(tierIndex);
            buf.writeInt(amperage);
        }
        // 🆕 阶段 2：非 GT 输入侧那几件。面板据此决定
        //    ① 画几个格子（GRID 恒 9 / LIST 画 N 个 / SINGLE 只画 1 个 / NONE 一个都不画）；
        //    ② 哪几颗按钮显出来（宽高 / 加删）；
        //    ③ 那两行说明文字写什么。
        // 🔴 顺带修掉一个【阶段 1 就存在】的错：`vanillaType` 从来没被推给客户端，
        //    而 refreshVisibility 是在客户端跑的 ⇒ "编辑原版配方时藏掉 GT 控件"那一条
        //    在客户端根本没生效（GT 的电压档/条件入口照样画在原版配方的第三屏上）。
        //    现在把它一起推下去。
        buf.writeBoolean(vanillaType);
        buf.writeVarInt(vanillaType && vanillaShape != null ? vanillaShape.mode().ordinal() : -1);
        buf.writeVarInt(vanillaType && vanillaShape != null ? vanillaShape.width() : 0);
        buf.writeVarInt(vanillaType && vanillaShape != null ? vanillaShape.height() : 0);
        buf.writeVarInt(vanillaType && vanillaShape != null ? vanillaShape.slotCount() : 0);
        buf.writeUtf(vanillaType && vanillaShape != null ? vanillaShape.titleText() : "");
        buf.writeUtf(vanillaType && vanillaShape != null ? vanillaShape.noteText() : "");
        buf.writeUtf(vanillaType && vanillaShape != null ? vanillaShape.hintText() : "");
    }

    /** 客户端：收服务端推下来的状态（只改自己这份的"显示态"）。 */
    public void readState(FriendlyByteBuf buf) {
        final int s = buf.readVarInt();
        stage = s >= 0 && s < Stage.values().length ? Stage.values()[s] : Stage.TYPES;
        page = buf.readInt();
        buf.readVarInt();                 // pageCount（客户端不单独存，行文字由服务端推）
        countCell = buf.readInt();
        inPage = buf.readVarInt();
        outPage = buf.readVarInt();
        pendingCount = buf.readVarInt();
        pendingChance = buf.readVarInt();
        pendingBoost = buf.readVarInt();
        pendingCatalyst = buf.readBoolean();
        // 🆕 B 组：条件表与编辑态
        final String condJson = buf.readUtf();
        try {
            final JsonElement el = com.google.gson.JsonParser.parseString(condJson);
            conditions = el != null && el.isJsonArray() ? el.getAsJsonArray() : new com.google.gson.JsonArray();
        } catch (Throwable t) {
            // 解析不出来 ⇒ 客户端显示空列表（画出来的每一行本来就是服务端推的文字，不造假数据）
            conditions = new com.google.gson.JsonArray();
        }
        condSel = buf.readVarInt();
        condOpen = buf.readBoolean();
        condTypeKey = buf.readUtf();
        condValue = buf.readUtf();
        condReverse = buf.readBoolean();
        condSource = buf.readUtf();
        io.readState(buf);
        // 🆕 第 7 轮：查询那一块
        queryItem = buf.readItem();
        searchText = buf.readUtf();
        final int qk = buf.readVarInt();
        queryKind = qk >= 0 && qk < ShanhaiRecipeQuery.Kind.values().length
                ? ShanhaiRecipeQuery.Kind.values()[qk] : ShanhaiRecipeQuery.Kind.NONE;
        // 🔴🔴 2026-10-05 第 8 轮（P0）：顺序 = writeState 的顺序：【先 note(Utf)、再 total(VarInt)】。
        //    这里原来是 `new Result(queryKind, List.of(), buf.readVarInt(), buf.readUtf())` ——
        //    参数从左到右求值 ⇒ 先读了 total、后读 note，与写侧【反了】。
        //    后果与修法见 writeState 里那一段注释（客户端整屏打不开、且不报错）。
        //    ⚠️ 再强调一次：**不要**把它写回"在一个 new 表达式里连环读"的紧凑写法 ——
        //    那种写法把顺序藏进参数表里，看不出来，正是这个 bug 的成因。
        final String qNote = buf.readUtf();
        final int qTotal = buf.readVarInt();
        queryResult = new ShanhaiRecipeQuery.Result(queryKind, List.of(), qTotal, qNote);
        buf.readVarInt();                       // tabCount（客户端只用标签那一份）
        tabIndex = buf.readVarInt();
        tabPage = buf.readVarInt();
        buf.readVarInt();                       // tabPageCount
        final int tabN = buf.readVarInt();
        final List<ShanhaiRecipeQuery.Tab> ts = new ArrayList<>(Math.max(0, tabN));
        for (int i = 0; i < tabN; i++) {
            ts.add(ShanhaiRecipeQuery.readTab(buf));
        }
        tabs = List.copyOf(ts);
        final int cardN = buf.readVarInt();
        final List<ShanhaiRecipeQuery.Card> cs = new ArrayList<>(Math.max(0, cardN));
        for (int i = 0; i < cardN; i++) {
            cs.add(ShanhaiRecipeQuery.readCard(buf));
        }
        cards = List.copyOf(cs);
        if (buf.readBoolean()) {
            pendingDuration = buf.readInt();
            pendingEut = buf.readInt();
            tierIndex = buf.readInt();
            amperage = buf.readInt();
            numbersVersion++;
        }
        // 🆕 阶段 2：非 GT 输入侧那几件（顺序与 writeState 末尾逐字对应）。
        vanillaType = buf.readBoolean();
        vanillaSyncMode = buf.readVarInt();
        vanillaSyncW = buf.readVarInt();
        vanillaSyncH = buf.readVarInt();
        vanillaSyncSlots = buf.readVarInt();
        vanillaSyncTitle = buf.readUtf();
        vanillaSyncNote = buf.readUtf();
        vanillaSyncHint = buf.readUtf();
    }

    // ---------------------------------------------------------------- 自动保存进度的读数（自检用）

    /** 给日志/自检用的一行摘要。 */
    public String statsLine() {
        return "stage=" + stage + " type=" + typeId + " recipe=" + recipeId + " page=" + page
                + " types=" + types.size() + " recipes=" + recipes.size()
                + " query=" + queryKind + " q_total=" + queryResult.total()
                + " q_groups=" + queryResult.groups().size() + " tab=" + tabIndex + "/" + tabCount()
                + " cards=" + cards.size()
                + " cells=" + io.summary();
    }

    public List<TypeRow> typeRows() {
        return List.copyOf(types);
    }

    public List<ResourceLocation> recipeRows() {
        return List.copyOf(recipes);
    }

    /**
     * 🆕 第 8 轮：查询结论那两个字段的读数（自检判据用 —— 它们正是 P0 那个"两侧顺序反了"的一对）。
     *
     * <p>写侧的顺序是【note 再 total】，读侧必须一样；把两者都暴露出来，
     * 自检就能把"服务端写的"与"客户端读回来的"逐字比一遍（见 {@code case=panel_sync_pair}）。
     */
    public String queryNote() {
        return queryResult == null || queryResult.note() == null ? "" : queryResult.note();
    }

    /** 见 {@link #queryNote()}。 */
    public int queryTotal() {
        return queryResult == null ? -1 : queryResult.total();
    }

    /** 🆕 2026-10-05：查询结果分了几组（自检判据用）。 */
    public int queryResultGroups() {
        return queryResult == null ? -1 : queryResult.groups().size();
    }

    /** 🆕 第 8 轮：编辑屏现在开着的是哪一条（自检判据用；客户端那份恒为 null）。 */
    public ResourceLocation currentRecipeId() {
        return recipeId;
    }

    /** 🆕 第 8 轮：当前选中的类型（自检判据用）。 */
    public ResourceLocation currentTypeId() {
        return typeId;
    }

    public ShanhaiIoTable ioTable() {
        return io;
    }
}
