package com.shanhai.common.saveedit;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor.ShanhaiLdlText;
import com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditFix;
import com.shanhai.common.saveedit.audit.ShanhaiSaveEditAuditScan;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.shanhai.common.saveedit.ShanhaiSaveEditLayout.*;

/**
 * 「存档直改」面板（<b>七页共存</b>：① 玩家 ② 世界(level.dat) ③ 方块(region) ④ 实体(entities)
 * ⑤ 成就 ⑥ 统计 ⑦ 存档体检(白名单之外一律算异常)）。
 *
 * <h2>一、版式（W=400 × H=232；坐标是<b>面板内</b>绝对坐标）</h2>
 * <pre>
 *   y=6    [①玩家][②世界][③方块][④实体][⑤成就][⑥统计][⑦其他]  [读当前值 76] [确认 96]
 *   y=28   第 N 页的说明一行（客户端自己知道的字）
 *   ── ① 玩家 ─────────────────────────────────────────────────
 *   y=42   [写入 64]（= 暂存，不落盘）
 *   y=66/78/90  摘要三行（服务端权威字面串）
 *   y=112/132/152/172  血量/等级/饥饿/饱和 ‖ 模式/维度 ‖ X/Y/Z/yaw/pitch ‖ 清空槽位
 *   ── ② 世界（level.dat，21 个字段，两列）────────────────────────
 *   y=46 + 15*i   每行 = 字段名 + 当前值 → [新值]
 *   ── ③ 方块（region）────────────────────────────────────────
 *   y=46   维度[..] X[..] Y[..] Z[..]
 *   y=66   方块[....] 状态[....]
 *   y=86   当前：<服务端读到的方块>
 *   y=106  [读当前方块][清空输入][BE：不动]
 *   y=126  BE NBT[.......................................]
 *   y=148.. 三行提示
 *   y=186  当前BE：<服务端读到的那条>
 *   ── ⑦ 存档体检（白名单之外一律算异常）──────────────────────────
 *   y=42   搜索[..............196] [重新扫描 68] [删除选中项 92]
 *   y=62   共 N 条 · 文件 a · 目录 b · 文本 c · 二进制 d
 *   y=74 + 16*i   7 行可点的异常条目（选中那条带 ▶；点一行 = 选中它）
 *   y=188  [◀上一页] 第 N/M 页 [下一页▶] 共 X 条 · 本页 a-b
 *   y=206  选中：路径 · 大小 · 修改时间 · 文本/二进制 · 预览 · 可删
 *   y=220  §7状态 §f<一行>
 * </pre>
 * 🔴 每一行"长度不可控"的字都先过 {@link #fit(String, int)}：LDLib 的 {@code LabelWidget}
 * 不换行也不裁（{@code LabelWidget.java:187-201}），超宽会画到面板外面。
 *
 * <h2>二、七页怎么共存</h2>
 * 七页的控件<b>全都建出来</b>，只按 {@link ShanhaiSaveEditFactory.Holder#page()} 切换
 * {@code setVisible}。这么做的理由：
 * <ul>
 *   <li>LDLib 的 {@code WidgetGroup#drawWidgetsBackground} 与 {@code #mouseClicked} 都会先查
 *       {@code isVisible()}（字节码实证），所以隐藏页既不画也点不到；</li>
 *   <li>控件树在两侧始终一致 ⇒ 控件 id 一一对应（客户端动作才能落到服务端同一个控件上）。
 *       若按页增删控件，两侧一旦有一步不同步，整块界面就会错位。</li>
 * </ul>
 *
 * <h2>三、🔴 两侧各跑一次</h2>
 * LDLib 的 {@code ButtonWidget#mouseClicked} 先在客户端跑一次、再在服务端
 * {@code handleClientAction} 跑一次。本面板用 {@code this.remote} 区分：
 * 只有<b>服务端那份</b>才读盘/写盘；客户端那次只做"自己也知道"的事（切页、切 BE 模式、
 * 以及把服务端推来的字显示出来）。
 *
 * <h2>四、"写入"（暂存）与"确认"</h2>
 * 与一期同一条口径：任何输入都<b>不落盘</b>，落盘只发生在「确认修改并重载」之后的停服窗口内，
 * 七页一起写（{@link ShanhaiSaveEditWindow}）。
 */
public final class ShanhaiSaveEditPanel extends WidgetGroup {

    // ================================================================ 版式
    // 🔴 全部数字都在 ShanhaiSaveEditLayout（纯 JDK、离线可判）：TAB_* / READ_* / CONFIRM_* /
    //    STATUS_* / ROW_* / LV_* / RG_* / EN_* / LP_* / NEW_LINES / PAGE_* 都由文件顶部的
    //    static import 拿进来，**面板这一层不再各写一份坐标**（写两份就一定会有一天对不上）。
    //    这样做的两个理由见那个类的注释：① 越界/重叠能离线判；② 面板类离线加载不了
    //    （继承 LDLib 的 WidgetGroup，一加载就触发它的静态初始化，要一个本机 mapped jar 里没有的 SRG 方法）。

    // ================================================================ 字段

    private final ShanhaiSaveEditFactory.Holder holder;
    private final transient Player player;
    /** 客户端那份 = true（服务端那份 = false）。 */
    private final boolean remote;

    /** 七页的控件清单（只切 setVisible，不增删控件）。 */
    private final List<List<Widget>> pageWidgets = new ArrayList<>();

    // 第 ① 页
    private TextFieldWidget healthField, xpField, foodField, satField, modeField, dimField;
    private TextFieldWidget xField, yField, zField, yawField, pitchField;
    /**
     * 第 ① 页「物品栏 41 格 / 末影箱」覆盖层（模态；<b>不进</b> {@code pageWidgets}）。
     *
     * <p>为什么 41 格不直接画在页①：页①唯一的大空带只有 {@code y=188..220}（32 px 高），
     * 而 41 格约 198×72 —— 放不下。所以照第 ④⑤⑥ 页的范式走覆盖层，页①只留一个入口按钮。
     */
    private ShanhaiSaveEditInventoryOverlay invOverlay;

    // 第 ② 页（level.dat）
    private final Map<String, FillField> levelFields = new LinkedHashMap<>();

    // 第 ③ 页（区块）
    private FillField regionDimField, regionXField, regionYField, regionZField;
    private FillField regionBlockField, regionStateField, regionBeField;

    // 第 ④ 页（实体）
    /**
     * 第 ④ 页的两个输入框（第五期：只留维度 + 搜索；x/y/z 与"按类型 / 按 UUID"两行已经去掉）。
     *
     * <p>🔴 这两个框<b>没有</b> textSupplier —— LDLib 的 {@code updateScreen} 只在
     * {@code isClientSideWidget && textSupplier != null} 时覆盖文本，挂了 supplier 会冲掉玩家正在输入的字。
     * 回填只走"初始数据 + {@link FillField#pushText(String)}"这条通道。
     */
    private FillField entityDimField, entitySearchField;
    /** 7 行可点的实体（字由服务端 push，点某一行 ⇒ 打开覆盖层）。 */
    private final EntityRowButton[] entityRows = new EntityRowButton[EN_ROWS];
    /** 实体详情覆盖层（模态；不随切页显隐，所以不进 {@code pageWidgets}）。 */
    private ShanhaiSaveEditEntityOverlay entityOverlay;
    /**
     * 「列不出实体」的原因打进聊天栏时的不刷屏闸门（同文本 30 秒不重发、两轮 ≥2 秒；空 notes 不发）。
     *
     * <p>粒度：每份面板一个（<b>不是</b> static）—— 两个玩家各开各的面板时，A 的「列出」不会把
     * B 那条原因挡掉。纯 JDK 那一层是 {@link ShanhaiSaveEditNotesChat}（可离线判）。
     */
    private final ShanhaiSaveEditNotesChat.Throttle notesChatThrottle =
            new ShanhaiSaveEditNotesChat.Throttle();

    // 第 ⑤ 页（成就）
    /**
     * 第 ⑤ 页的搜索框（S2b：这一页也可以"列出 → 翻页 → 点一行"了）。
     *
     * <p>🔴 这个框<b>没有</b> textSupplier —— LDLib 的 {@code updateScreen} 只在
     * {@code isClientSideWidget && textSupplier != null} 时覆盖文本，挂了 supplier 会冲掉玩家正在输入的字。
     */
    private FillField advFilterField;
    /** 7 行可点的成就（字由服务端 push 原料，客户端现拼中文名）。 */
    private final AdvancementRowButton[] advRows = new AdvancementRowButton[LP_ROWS];
    /** 成就详情覆盖层（模态；不随切页显隐，所以不进 {@code pageWidgets}）。 */
    private ShanhaiSaveEditAdvancementOverlay advOverlay;
    /** 上一次「列出」的读数（<b>只有服务端那份有</b>）。 */
    private ShanhaiAdvancementEdit.Listing advListing;
    /** 过滤之后的<b>全量</b>列表（服务端；翻页在它上面切一段）。 */
    private List<ShanhaiAdvancementEdit.Entry> advAll = List.of();
    /**
     * 游戏里全部成就 id（服务端查 {@code AdvancementManager}）。
     *
     * <p>{@code null} = <b>查不到</b>（没有服务器/异常）⇒ 行上<b>不</b>声称"未注册"，
     * 让语言表那一层去回退成〔无译名〕；空表 = 游戏里确实一条都没有。
     */
    private List<String> advRegistryIds;
    /** 当前第几页（0 起；服务端权威）。 */
    private int advPage;
    /** 概况 / 页码 / 本页计数（服务端算好的字面串，靠 LabelWidget push 给客户端）。 */
    private String advSummary = "", advPageText = "", advPageCountText = "";

    // 第 ⑥ 页（统计）
    /**
     * 第 ⑥ 页的"分类"框 —— 它<b>就是</b>数据层的 filter 参数
     * （{@code ShanhaiStatsEdit.listFile(file, category, max)} 的第二个参数）。同样没有 textSupplier。
     */
    private FillField statsFilterField;
    /** 7 行可点的统计（字由服务端 push 原料，客户端现拼中文名）。 */
    private final StatsRowButton[] statsRows = new StatsRowButton[LP_ROWS];
    /** 统计详情覆盖层（模态）。 */
    private ShanhaiSaveEditStatsOverlay statsOverlay;
    /** 上一次「列出」的读数（只有服务端那份有）。 */
    private ShanhaiStatsEdit.Listing statsListing;
    /** 当前第几页（0 起；服务端权威）。 */
    private int statsPage;
    /** 概况 / 页码 / 本页计数（服务端算好的字面串）。 */
    private String statsSummary = "", statsPageText = "", statsPageCountText = "";

    // 第 ⑦ 页（存档体检 · 白名单之外一律算异常）
    /** 搜索框（子串过滤相对路径；🔴 没有 textSupplier —— 免得冲掉玩家正在输入的字）。 */
    private FillField auditFilterField;
    /** 7 行可点的异常条目（字由服务端 push；点某一行 = 选中它）。 */
    private final AuditRowButton[] auditRows = new AuditRowButton[AU_ROWS];
    /** 「删除选中项」（🔴 不弹二次确认；删前进回收目录可撤回）。没选中/不可删时变灰。 */
    private AuditButton auditDropButton;
    /** 上一次扫描的结果（<b>只有服务端那份有</b>；客户端靠 LabelWidget / 按钮 push 看字）。 */
    private ShanhaiSaveEditAuditScan.Report auditReport;
    /** 过滤之后的<b>全量</b>清单（服务端；翻页在它上面切一段）。 */
    private List<ShanhaiSaveEditAuditScan.Item> auditAll = List.of();
    /** 当前选中第几条（0 起，指 {@link #auditAll} 的下标；服务端权威）。 */
    private int auditSelected;
    /** 当前第几页（0 起；服务端权威）。 */
    private int auditPage;
    /** 概况 / 页码 / 本页计数 / 选中行（服务端算好的字面串，靠 LabelWidget push 给客户端）。 */
    private String auditSummary = "", auditPageText = "", auditPageCountText = "", auditSelLine = "";

    /**
     * 🔴 「有暂存时要关面板」的二次确认层（用户点名的<b>唯一</b>一类确认弹窗）。
     *
     * <p>它<b>常驻</b>在控件树里（不是"弹出来才存在"）：因为 ESC 必须在"还没弹层"的那一刻就被它拦住
     * （原版 ESC 会直接 {@code onClose()}）。在场时它自己判断"有没有暂存"：
     * 有 ⇒ 弹层并吃掉 ESC/Enter；没有 ⇒ 放行，让原版照常关。
     * 见 {@link ShanhaiSaveEditCloseConfirmOverlay}（类注释里有 LDLib/原版的 javap 证据）。
     */
    private ShanhaiSaveEditCloseConfirmOverlay closeConfirm;

    /** 那一行"面板自己在屏幕哪里"的读数只写一次（见 {@link #updateScreen()}）。 */
    private boolean absLogged;

    private ShanhaiSaveEditPanel(ShanhaiSaveEditFactory.Holder holder, Player player) {
        super(0, 0, W, H);
        this.holder = holder == null
                ? new ShanhaiSaveEditFactory.Holder(true, null, ShanhaiSaveEditFactory.STATUS_NO_READ)
                : holder;
        this.player = player;
        this.remote = this.holder.isRemote();
        this.setBackground(GuiTextures.BACKGROUND);
        for (int i = 0; i < PAGE_COUNT; i++) {
            this.pageWidgets.add(new ArrayList<>());
        }

        // ── 全局：七个页签 + 两个动作按钮（位置/宽度全部由 PAGE_COUNT 算出来）
        for (int i = 0; i < PAGE_COUNT; i++) {
            final int page = i;
            this.addWidget(button(TAB_X0 + i * TAB_STEP, TAB_Y, TAB_W, TAB_H,
                    () -> (this.holder.page() == page ? "§e" : "§f") + tabName(page),
                    () -> this.showPage(page)));
        }
        this.addWidget(button(READ_X, TAB_Y, READ_W, TAB_H, "读当前值", this::onRead));
        this.addWidget(button(CONFIRM_X, TAB_Y, CONFIRM_W, TAB_H, "确认修改并重载", this::onConfirm));

        this.buildPlayerPage();
        this.buildLevelPage();
        this.buildRegionPage();
        this.buildEntityPage();
        this.buildAdvancementPage();
        this.buildStatsPage();
        this.buildAuditPage();

        // ── 状态行（前缀客户端独有；值那一半是服务端权威字面串）
        this.addWidget(new LabelWidget(STATUS_LBL_X, STATUS_Y,
                "§7状态").setClientSideWidget());
        this.addWidget(new LabelWidget(STATUS_VAL_X, STATUS_Y,
                ShanhaiLdlText.sup(this::statusValueText)));

        // 🔴 三个覆盖层必须<b>最后</b>加入：LDLib 按加入顺序画（后加的画在上面）。
        //    它们铺满 400×232，如果先进来，状态行那两行字会画在覆盖层的按钮上面。
        //    三者互斥（打开一个就关掉另外两个），所以彼此的顺序无所谓。
        //    S2c：第 ① 页的物品栏覆盖层也走同一条规矩（第四个，最后加）。
        if (this.entityOverlay != null) {
            this.addWidget(this.entityOverlay);
        }
        if (this.advOverlay != null) {
            this.addWidget(this.advOverlay);
        }
        if (this.statsOverlay != null) {
            this.addWidget(this.statsOverlay);
        }
        if (this.invOverlay != null) {
            this.addWidget(this.invOverlay);
        }
        // 🔴 关闭确认层<b>最后</b>加入：WidgetGroup 的 mouseClicked/keyPressed 是<b>倒序</b>遍历
        //    （字节码实测：size()-1 → 0），所以最后一个加进来的第一个收到事件 ——
        //    正好是"ESC 要在这里被拦、点击要在这里被吃"的那一层。
        this.closeConfirm = new ShanhaiSaveEditCloseConfirmOverlay(this, this.holder, this.remote);
        this.addWidget(this.closeConfirm);
        // 第 ④ 页的两行"还没列"提示（服务端算好的字面串，靠 LabelWidget push 给客户端）
        this.holder.setEntitySummary(ShanhaiSaveEditEntityUi.summaryLine(null));
        this.holder.setEntityResidueNote(ShanhaiSaveEditEntityUi.residueNote(null));
        // 第 ⑤⑥ 页的"还没列"三行（同样由服务端算、靠 LabelWidget push）
        this.advSummary = ShanhaiSaveEditAdvancementUi.summaryLine(null, 0, "");
        this.advPageText = ShanhaiSaveEditAdvancementUi.pageText(0, 0);
        this.advPageCountText = ShanhaiSaveEditAdvancementUi.pageCountText(0, 0);
        this.statsSummary = ShanhaiSaveEditStatsUi.summaryLine(null, "");
        this.statsPageText = ShanhaiSaveEditStatsUi.pageText(0, 0);
        this.statsPageCountText = ShanhaiSaveEditStatsUi.pageCountText(0, 0);
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX,
                ShanhaiSaveEditEntityOverlay.layoutLine());
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX,
                ShanhaiSaveEditAdvancementOverlay.layoutLine());
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX,
                ShanhaiSaveEditStatsOverlay.layoutLine());
        // S2c：物品栏覆盖层的读数（格子数 / 开关 / 四段槽位区间）打进日志，事后能对账
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX,
                ShanhaiSaveEditInventoryOverlay.layoutLine());
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX,
                ShanhaiSaveEditInventoryUi.layoutLine());
        ShanhaiMod.LOGGER.info("{} inv_segments {}", ShanhaiSaveEditFlow.PREFIX,
                ShanhaiSaveEditInventoryUi.segmentsLine());
        // 关闭确认层的读数（方框几何 / 三个被屏蔽的键码 / 只认鼠标）
        ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX,
                ShanhaiSaveEditCloseConfirmOverlay.layoutLine());

        this.showPage(this.holder.page());
    }

    /** 建面板（两侧各建一份；结构完全一致 ⇒ 控件 id 一致 ⇒ 客户端动作能对上服务端控件）。 */
    public static ShanhaiSaveEditPanel create(ShanhaiSaveEditFactory.Holder holder, Player player) {
        return new ShanhaiSaveEditPanel(holder, player);
    }

    // ================================================================ 位置读数（🔴 判据用）

    /**
     * 一行可核对的<b>真实</b>位置读数（只写一次；客户端那份）。
     *
     * <h2>为什么要它</h2>
     * LDLib 1.0.33 的坐标是 {@code 绝对位置 = 父组位置(parentPosition) + 自身位置(selfPosition)}
     * （{@code Widget#recomputePosition} 字节码：{@code parentPosition.add(selfPosition) → position}），而
     * {@code ModularUI#updateScreenSize} 会把 {@code mainGroup} 的 parentPosition 设成
     * {@code (guiLeft, guiTop) = ((屏宽-400)/2, (屏高-232)/2)}（字节码实测）。所以：
     * <pre>
     *   面板自己的 position      = (guiLeft, guiTop)          ← 这一行日志的两个数就是它
     *   面板内的控件/覆盖层      = position 再 + (自身 x,y)     ← 「面板内坐标」是相对这个左上角的
     *   手写 graphics.fill(...)  <b>不会</b>自动加这个偏移（{@code WidgetGroup#drawWidgetsBackground}
     *                           只是把同一组 mouseX/mouseY 原样交给子控件，不 pushPose/translate）
     * </pre>
     * ⇒ 判"到底在哪"只有两种数：① 本行（面板左上角在屏幕的坐标）；② 覆盖层自己那行
     * {@code inv_overlay_abs} / {@code close_confirm_abs}（它们的绝对位置 + 格子/方框的屏幕坐标）。
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.absLogged || !this.remote) {
            return;
        }
        this.absLogged = true;
        ShanhaiMod.LOGGER.info("{} panel_abs self=({},{}) abs=({},{}) size={}x{} "
                        + "overlays_self=(0,0) so_overlay_coord==panel_coord",
                ShanhaiSaveEditFlow.PREFIX, getSelfPositionX(), getSelfPositionY(),
                getPositionX(), getPositionY(), ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H);
    }

    // ================================================================ 页面切换

    /** 把某一页的控件加入本页清单。 */
    private <T extends Widget> T page(int page, T widget) {
        this.pageWidgets.get(page).add(widget);
        this.addWidget(widget);
        widget.setVisible(false);
        return widget;
    }

    /** 切页（两侧各跑一次：客户端那次只改自己的可见性，服务端那次也一样）。 */
    private void showPage(int page) {
        final int p = Math.max(0, Math.min(PAGE_COUNT - 1, page));
        this.holder.setPage(p);
        for (int i = 0; i < PAGE_COUNT; i++) {
            for (Widget w : this.pageWidgets.get(i)) {
                w.setVisible(i == p);
            }
        }
        // 覆盖层是模态层、不随切页显隐 ⇒ 离开自己那一页时必须自己收起来（否则它会一直盖着别的页）
        if (p != PAGE_ENTITY && this.entityOverlay != null && this.entityOverlay.isOpen()) {
            this.entityOverlay.close();
        }
        if (p != PAGE_ADVANCEMENT && this.advOverlay != null && this.advOverlay.isOpen()) {
            this.advOverlay.close();
        }
        if (p != PAGE_STATS && this.statsOverlay != null && this.statsOverlay.isOpen()) {
            this.statsOverlay.close();
        }
        if (p != PAGE_PLAYER && this.invOverlay != null && this.invOverlay.isOpen()) {
            this.invOverlay.close();
        }
    }

    /** 页签上的字（🔴 单一口径在 {@link ShanhaiSaveEditLayout#tabName(int)}，这里只做转发）。 */
    static String tabName(int page) {
        return ShanhaiSaveEditLayout.tabName(page);
    }

    // ================================================================ 第 ① 页（玩家）

    private void buildPlayerPage() {
        final int p = PAGE_PLAYER;
        page(p, new LabelWidget(6, SUB_Y,
                fit("§71 玩家数据：改磁盘上的玩家存档；留空＝不改；点右上「确认」才真落盘", SUB_BUDGET_PX))
                .setClientSideWidget());
        page(p, button(STAGE_X, STAGE_Y, STAGE_W, BTN_H, "写入(暂存)", this::onStage));

        // 三行摘要（服务端权威的字面串，靠 LabelWidget 的 update-info 推过来）
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            page(p, new LabelWidget(SUM_X, SUM_Y0 + i * SUM_STEP,
                    ShanhaiLdlText.sup(() -> fit(this.holder.summary(idx), SUMMARY_BUDGET_PX))));
        }

        // 输入格（留空 = 不改，所以一律不预填）
        this.healthField = page(p, field(A_HEALTH_FLD, ROW_A_Y, A_HEALTH_W, 8));
        this.xpField = page(p, field(A_XP_FLD, ROW_A_Y, A_XP_W, 8));
        this.foodField = page(p, field(A_FOOD_FLD, ROW_A_Y, A_FOOD_W, 4));
        this.satField = page(p, field(A_SAT_FLD, ROW_A_Y, A_SAT_W, 8));
        this.modeField = page(p, field(B_MODE_FLD, ROW_B_Y, B_MODE_W, 2));
        this.dimField = page(p, field(B_DIM_FLD, ROW_B_Y, B_DIM_W, 64));
        this.xField = page(p, field(C_X_FLD, ROW_C_Y, C_X_W, 16));
        this.yField = page(p, field(C_Y_FLD, ROW_C_Y, C_Y_W, 16));
        this.zField = page(p, field(C_Z_FLD, ROW_C_Y, C_Z_W, 16));
        this.yawField = page(p, field(C_YAW_FLD, ROW_C_Y, C_YAW_W, 12));
        this.pitchField = page(p, field(C_PITCH_FLD, ROW_C_Y, C_PITCH_W, 12));
        // 🔴 S2c：「清空槽位」输入框（64,172 176×16）换成「打开背包格」按钮 —— 1 换 1，
        //    所以页① 的控件数还是 29（见 ShanhaiSaveEditLayout.PAGE1_WIDGETS 的注释）。
        //    "清空某一格"这件事现在由覆盖层里的「删除这一格」按格做（更不容易误伤）。
        page(p, button(D_CLEAR_FLD, ROW_D_Y, D_CLEAR_W, FIELD_H, "打开背包格（41）",
                this::onInventoryOpen));

        page(p, label(A_HEALTH_LBL, ROW_A_Y + 4, "血量"));
        page(p, label(A_XP_LBL, ROW_A_Y + 4, "等级"));
        page(p, label(A_FOOD_LBL, ROW_A_Y + 4, "饥饿"));
        page(p, label(A_SAT_LBL, ROW_A_Y + 4, "饱和"));

        page(p, label(B_MODE_LBL, ROW_B_Y + 4, "模式"));
        page(p, label(B_DIM_LBL, ROW_B_Y + 4, "维度"));

        page(p, label(C_X_LBL, ROW_C_Y + 4, "X"));
        page(p, label(C_Y_LBL, ROW_C_Y + 4, "Y"));
        page(p, label(C_Z_LBL, ROW_C_Y + 4, "Z"));
        page(p, label(C_YAW_LBL, ROW_C_Y + 4, "yaw"));
        page(p, label(C_PITCH_LBL, ROW_C_Y + 4, "pitch"));

        page(p, label(D_CLEAR_LBL, ROW_D_Y + 4, "物品栏"));
        page(p, new LabelWidget(D_CLEAR_HINT, ROW_D_Y + 4,
                fit("§8格子：点一下就能删 / 改数量 / 替换", W - D_CLEAR_HINT - 8)).setClientSideWidget());

        // 🔴 覆盖层是模态层：不进 pageWidgets（切页不许把它带出来）；
        //    真正 addWidget 在构造函数的最后（ORDER 决定谁画在上面）。
        this.invOverlay = new ShanhaiSaveEditInventoryOverlay(this, this.remote);
    }

    // ================================================================ 第 ② 页（level.dat）

    private void buildLevelPage() {
        final int p = PAGE_LEVEL;
        page(p, new LabelWidget(6, SUB_Y,
                fit("§72 世界文件 level.dat：每项都写「当前 → 新值」；留空＝不改；只有会被写回的字段才收",
                        SUB_BUDGET_PX)).setClientSideWidget());
        final List<ShanhaiLevelDataSpec.Field> fields = ShanhaiLevelDataSpec.FIELDS;
        for (int i = 0; i < fields.size(); i++) {
            final ShanhaiLevelDataSpec.Field f = fields.get(i);
            final int col = i / 11;
            final int row = i % 11;
            final int y = LV_ROW_Y0 + row * LV_ROW_STEP;
            final int lblX = col == 0 ? LV_A_LBL : LV_B_LBL;
            final int curX = col == 0 ? LV_A_CUR : LV_B_CUR;
            final int fldX = col == 0 ? LV_A_FLD : LV_B_FLD;
            page(p, label(lblX, y + 3, f.label()));
            // 当前值：服务端权威（读盘后 push 过来）；客户端那份先显示 "-"
            page(p, new LabelWidget(curX, y + 3,
                    ShanhaiLdlText.sup(() -> fit("§8" + orDash(this.holder.levelCurrent(f.id())) + " §7→",
                            CURRENT_BUDGET_PX))));
            final FillField edit = page(p, new FillField(fldX, y, col == 0 ? LV_A_W : LV_B_W, LV_FIELD_H,
                    null, null));
            edit.setMaxStringLength(64);
            this.levelFields.put(f.id(), edit);
        }
    }

    // ================================================================ 第 ③ 页（区块）

    private void buildRegionPage() {
        final int p = PAGE_REGION;
        page(p, new LabelWidget(6, SUB_Y,
                fit("§73 区块方块：维度 + x/y/z → 改一个方块（或一条方块实体）；只碰目标区块", SUB_BUDGET_PX))
                .setClientSideWidget());
        // 第 1 行：维度 + X/Y/Z
        page(p, label(6, RG_ROW1_Y + 4, "维度"));
        this.regionDimField = page(p, fillField(32, RG_ROW1_Y, 116, 64));
        page(p, label(152, RG_ROW1_Y + 4, "X"));
        this.regionXField = page(p, fillField(164, RG_ROW1_Y, 36, 12));
        page(p, label(204, RG_ROW1_Y + 4, "Y"));
        this.regionYField = page(p, fillField(216, RG_ROW1_Y, 36, 12));
        page(p, label(256, RG_ROW1_Y + 4, "Z"));
        this.regionZField = page(p, fillField(268, RG_ROW1_Y, 36, 12));
        // 第 2 行：方块 + 状态
        page(p, label(6, RG_ROW2_Y + 4, "方块"));
        this.regionBlockField = page(p, fillField(32, RG_ROW2_Y, 148, 96));
        page(p, label(184, RG_ROW2_Y + 4, "状态"));
        this.regionStateField = page(p, fillField(212, RG_ROW2_Y, 156, 96));
        // 第 3 行：当前方块（服务端读盘后 push）
        page(p, label(6, RG_ROW3_Y + 4, "当前"));
        page(p, new LabelWidget(40, RG_ROW3_Y + 4,
                ShanhaiLdlText.sup(() -> fit(this.holder.blockCurrent(), REGION_CURRENT_BUDGET_PX))));
        // 第 4 行：三个按钮
        page(p, button(6, RG_BTN_Y, 86, FIELD_H, "读当前方块", this::onReadRegion));
        page(p, button(96, RG_BTN_Y, 64, FIELD_H, "清空输入", this::onClearRegionFields));
        page(p, button(164, RG_BTN_Y, 80, FIELD_H, this::beModeText, this::onCycleBe));
        // 第 5 行：方块实体 NBT
        page(p, label(6, RG_ROW5_Y + 4, "BE NBT"));
        this.regionBeField = page(p, fillField(50, RG_ROW5_Y, 336, 512));
        // 提示三行
        page(p, new LabelWidget(6, RG_HINT_Y0,
                fit("§8· 留空＝不改；「方块」填 minecraft:stone 或 minecraft:oak_log[axis=y]", SUB_BUDGET_PX))
                .setClientSideWidget());
        page(p, new LabelWidget(6, RG_HINT_Y0 + RG_HINT_STEP,
                fit("§8· BE 模式按钮：不动 → 清空 → 替换（替换时用上面那行的 NBT）", SUB_BUDGET_PX))
                .setClientSideWidget());
        page(p, new LabelWidget(6, RG_HINT_Y0 + RG_HINT_STEP * 2,
                fit("§8· 没生成的区块会被拒绝；写盘前备份整个 .mca（在 temp\\saveedit-backup）",
                        SUB_BUDGET_PX)).setClientSideWidget());
        // 当前方块实体
        page(p, label(6, RG_BE_Y + 4, "当前BE"));
        page(p, new LabelWidget(56, RG_BE_Y + 4,
                ShanhaiLdlText.sup(() -> fit(this.holder.blockEntityCurrent(), REGION_CURRENT_BUDGET_PX))));
    }

    // ================================================================ 第 ④ 页（实体）

    private void buildEntityPage() {
        final int p = PAGE_ENTITY;
        // 🔴 控件<b>照槽位表建</b>（ShanhaiSaveEditLayout#entitySlots）：几何与"这一页到底几个控件"
        //    只有槽位表一个来源，面板这一层不再各写一份坐标/次数。
        for (Slot slot : entitySlots()) {
            page(p, entityWidget(slot));
        }
        // 🔴 覆盖层是模态层：不进 pageWidgets（切页不许把它带出来）；本方法只<b>建</b>它，
        //    真正 addWidget 在构造函数的最后（ORDER 决定谁画在上面）。
        this.entityOverlay = new ShanhaiSaveEditEntityOverlay(this, this.remote);
    }

    /** 照一个槽位造第 ④ 页的控件（{@code key} 决定它是谁、要挂什么动作）。 */
    private Widget entityWidget(Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "subtitle":
                // 说明与"落盘只在停服窗口里"合并成一行（原来底部那行提示现在让给翻页）
                return new LabelWidget(x, y, fit(ShanhaiSaveEditEntityUi.hintLine(), w)).setClientSideWidget();
            case "dim.label":
                return label(x, y, "维度");
            case "dim.field":
                this.entityDimField = fillField(x, y, w, 64);
                this.entityDimField.setCurrentString(this.currentDimension());
                return this.entityDimField;
            case "residue.button":
                return button(x, y, w, h, this::residueButtonText, this::onCycleResidue);
            case "residue.note":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.holder.entityResidueNote(), w)));
            case "search.label":
                return label(x, y, "搜索");
            case "search.field":
                this.entitySearchField = fillField(x, y, w, 96);
                return this.entitySearchField;
            case "list.button":
                return button(x, y, w, h, "列出", this::onListEntities);
            case "clear.button":
                return button(x, y, w, h, "清空", this::onClearEntityFilters);
            case "summary.label":
                return label(x, y, "概况");
            case "summary.value":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.holder.entitySummary(), w)));
            case "row": {
                final int idx = slot.index();
                // 行数据的那一格由面板建、交给按钮持有（super(...) 里不许引用 this / 实例字段）；
                // 客户端还要靠它自己拼字（类型名必须在客户端查语言表，见 Ui#typeLabel）
                final EntityRowButton.Cell cell = new EntityRowButton.Cell(idx);
                final EntityRowButton row = new EntityRowButton(x, y, w, h, cell,
                        ShanhaiSaveEditEntityUi.REGISTRY_NAMER, () -> this.onClickEntityRow(idx));
                this.entityRows[idx] = row;
                return row;
            }
            case "page.prev":
                return button(x, y, w, h, "◀上一页", () -> this.onEntityPage(-1));
            case "page.label":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.holder.entityPageText(), w)));
            case "page.next":
                return button(x, y, w, h, "下一页▶", () -> this.onEntityPage(1));
            case "page.count":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.holder.entityPageCountText(), w)));
            default:
                throw new IllegalStateException("第 ④ 页的槽位没被处理：" + slot.key());
        }
    }

    /** 玩家当前所在维度（两侧各算一次；拿不到 ⇒ 主世界）。 */
    private String currentDimension() {
        try {
            if (this.player != null && this.player.level() != null) {
                return this.player.level().dimension().location().toString();
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} panel_dimension_probe_failed err={}",
                    ShanhaiSaveEditFlow.PREFIX, t.toString());
        }
        return ShanhaiSaveEditDimension.OVERWORLD;
    }

    // ================================================================ 第 ⑤ 页（成就）

    private void buildAdvancementPage() {
        final int p = PAGE_ADVANCEMENT;
        // 🔴 控件<b>照槽位表建</b>（ShanhaiSaveEditLayout#advancementSlots）：几何与"这一页到底几个控件"
        //    只有槽位表一个来源，面板这一层不再各写一份坐标/次数。
        for (Slot slot : advancementSlots()) {
            page(p, advancementWidget(slot));
        }
        // 🔴 覆盖层是模态层：不进 pageWidgets（切页不许把它带出来）；
        //    真正 addWidget 在构造函数的最后（ORDER 决定谁画在上面）。
        this.advOverlay = new ShanhaiSaveEditAdvancementOverlay(this, this.remote);
    }

    /** 照一个槽位造第 ⑤ 页的控件（{@code key} 决定它是谁、要挂什么动作）。 */
    private Widget advancementWidget(Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "subtitle":
                return new LabelWidget(x, y, fit(ShanhaiSaveEditAdvancementUi.hintLine(), w))
                        .setClientSideWidget();
            case "filter.label":
                return new LabelWidget(x, y, "搜索").setClientSideWidget()
                        .setHoverTooltips(Component.literal("§e搜索§7：按成就 id 的子串过滤"
                                + "（例如 story/mine_block）。标题只有客户端查得到，服务端匹配不了标题"));
            case "filter.field":
                this.advFilterField = fillField(x, y, w, 96);
                return this.advFilterField;
            case "list.button":
                return button(x, y, w, h, "列出", this::onListAdvancements);
            case "clear.button":
                return button(x, y, w, h, "清空", this::onClearAdvancementFilter);
            case "summary.label":
                return label(x, y, "概况");
            case "summary.value":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.advSummary, w)));
            case "row": {
                final int idx = slot.index();
                // 行数据的那一格由面板建、交给按钮持有（super(...) 里不许引用 this / 实例字段）；
                // 客户端还要靠它自己拼字（成就标题必须在客户端查语言表，见 Ui#titleLabel）
                final AdvancementRowButton.Cell cell = new AdvancementRowButton.Cell(idx);
                final AdvancementRowButton row = new AdvancementRowButton(x, y, w, h, cell,
                        ShanhaiSaveEditAdvancementUi.LANG_SOURCE, () -> this.onClickAdvancementRow(idx));
                this.advRows[idx] = row;
                return row;
            }
            case "page.prev":
                return button(x, y, w, h, "◀上一页", () -> this.onAdvancementPage(-1));
            case "page.label":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.advPageText, w)));
            case "page.next":
                return button(x, y, w, h, "下一页▶", () -> this.onAdvancementPage(1));
            case "page.count":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.advPageCountText, w)));
            case "hint":
                return new LabelWidget(x, y, fit(ShanhaiSaveEditAdvancementUi.footerLine(), w))
                        .setClientSideWidget();
            default:
                throw new IllegalStateException("第 ⑤ 页的槽位没被处理：" + slot.key());
        }
    }

    // ================================================================ 第 ⑥ 页（统计）

    private void buildStatsPage() {
        final int p = PAGE_STATS;
        for (Slot slot : statsSlots()) {
            page(p, statsWidget(slot));
        }
        this.statsOverlay = new ShanhaiSaveEditStatsOverlay(this, this.remote);
    }

    /** 照一个槽位造第 ⑥ 页的控件（与第 ⑤ 页逐格同构，只有过滤框的语义不同）。 */
    private Widget statsWidget(Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "subtitle":
                return new LabelWidget(x, y, fit(ShanhaiSaveEditStatsUi.hintLine(), w))
                        .setClientSideWidget();
            case "filter.label":
                return new LabelWidget(x, y, "分类").setClientSideWidget()
                        .setHoverTooltips(Component.literal("§e分类§7：这一格直接喂给数据层的 filter"
                                + "（listFile 的第二个参数）—— 只列这个分类；留空 = minecraft:custom"));
            case "filter.field":
                this.statsFilterField = fillField(x, y, w, 64);
                this.statsFilterField.setCurrentString(ShanhaiStatsEdit.DEFAULT_CATEGORY);
                return this.statsFilterField;
            case "list.button":
                return button(x, y, w, h, "列出", this::onListStats);
            case "clear.button":
                return button(x, y, w, h, "清空", this::onClearStatsFilter);
            case "summary.label":
                return label(x, y, "概况");
            case "summary.value":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.statsSummary, w)));
            case "row": {
                final int idx = slot.index();
                final StatsRowButton.Cell cell = new StatsRowButton.Cell(idx);
                final StatsRowButton row = new StatsRowButton(x, y, w, h, cell,
                        ShanhaiSaveEditStatsUi.STAT_NAMER, () -> this.onClickStatsRow(idx));
                this.statsRows[idx] = row;
                return row;
            }
            case "page.prev":
                return button(x, y, w, h, "◀上一页", () -> this.onStatsPage(-1));
            case "page.label":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.statsPageText, w)));
            case "page.next":
                return button(x, y, w, h, "下一页▶", () -> this.onStatsPage(1));
            case "page.count":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> fit(this.statsPageCountText, w)));
            case "hint":
                return new LabelWidget(x, y, fit(ShanhaiSaveEditStatsUi.footerLine(), w))
                        .setClientSideWidget();
            default:
                throw new IllegalStateException("第 ⑥ 页的槽位没被处理：" + slot.key());
        }
    }

    // ================================================================ 全局按钮

    /** 「读当前值」：按当前页决定读什么（只读，不写盘）。 */
    private void onRead() {
        switch (this.holder.page()) {
            case PAGE_LEVEL -> this.readLevelDat();
            case PAGE_REGION -> this.onReadRegion();
            case PAGE_ENTITY -> this.onListEntities();
            case PAGE_ADVANCEMENT -> this.onListAdvancements();
            case PAGE_STATS -> this.onListStats();
            case PAGE_AUDIT -> this.onRescanAudit();
            default -> this.readPlayerData();
        }
    }

    /** ① 读 playerdata 摘要（服务端那份才做）。 */
    private void readPlayerData() {
        if (this.remote || !(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_FAILED + "拿不到存档路径");
            return;
        }
        final ShanhaiPlayerDataSnapshot snap = ShanhaiPlayerDataEditor.summarize(ctx.playerDataFile);
        this.holder.setSummary(snap);
        this.holder.setStatus(snap.present
                ? ShanhaiSaveEditFactory.STATUS_READ_OK + " · " + snap.bytes + " 字节"
                : ShanhaiSaveEditFactory.STATUS_READ_OK + "（没读到：" + snap.error + "）");
        ShanhaiMod.LOGGER.info("{} panel_read_player player={} file={} present={} {}",
                ShanhaiSaveEditFlow.PREFIX, serverPlayer.getName().getString(),
                ctx.playerDataFile.getFileName(), snap.present, snap.joined());
    }

    /** ② 读 level.dat 摘要（服务端那份才做；一个字节都不写）。 */
    private void readLevelDat() {
        if (this.remote || !(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_FAILED + "拿不到存档路径");
            return;
        }
        final java.nio.file.Path levelDat = ShanhaiLevelDataEditor.levelDatOf(ctx.worldDir);
        final ShanhaiLevelDataSnapshot snap = ShanhaiLevelDataEditor.summarize(levelDat);
        this.holder.setLevelSnapshot(snap);
        this.holder.setStatus((snap.present() ? "已读世界字段" : "没读到 level.dat")
                + " · " + ShanhaiLevelDataSpec.FIELDS.size() + " 个可改字段"
                + (snap.present() ? " · " + snap.bytes() + " 字节" : "（" + snap.error() + "）"));
        ShanhaiMod.LOGGER.info("{} panel_read_level file={} present={} {} why_count={}",
                ShanhaiSaveEditFlow.PREFIX, levelDat, snap.present(), snap.joined(),
                ShanhaiLevelDataSpec.FIELDS.size());
        // 把"为什么它能活过重开"打进日志（每项一条，事后可对账）
        for (ShanhaiLevelDataSpec.Field f : ShanhaiLevelDataSpec.FIELDS) {
            ShanhaiMod.LOGGER.info("{} level_field id={} kind={} why={}",
                    ShanhaiSaveEditFlow.PREFIX, f.id(), f.kind(), f.why());
        }
        ShanhaiMod.LOGGER.info("{} panel_read_level_player_debug name={} dim_debug={}",
                ShanhaiSaveEditFlow.PREFIX, serverPlayer.getName().getString(),
                ShanhaiLevelDataSnapshot.difficultyName(snap.value("Data.Difficulty")));
    }

    /** ③ 读当前方块（服务端读盘 + 回填输入框）。 */
    private void onReadRegion() {
        if (this.remote) {
            // 客户端那次只把"状态行"留着（值由服务端推）；不读盘
            return;
        }
        if (!(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_FAILED + "拿不到存档路径");
            return;
        }
        final int[] xyz = new int[3];
        final String err = this.readRegionCoords(xyz);
        if (err != null) {
            this.holder.setStatus("§c" + err);
            this.holder.setBlockCurrent("§c" + err);
            return;
        }
        final String dim = text(this.regionDimField).isEmpty()
                ? ShanhaiRegionEdit.DIM_OVERWORLD : text(this.regionDimField);
        final ShanhaiRegionEdit.Request req = new ShanhaiRegionEdit.Request(dim, xyz[0], xyz[1], xyz[2],
                "", "", ShanhaiRegionEdit.BE_NONE, "", false);
        final ShanhaiRegionEdit.Current cur = ShanhaiRegionEdit.readCurrent(ctx.worldDir, req);
        this.holder.setBlockCurrent(cur.display());
        this.holder.setBlockEntityCurrent(cur.ok() && cur.blockEntity() != null && !cur.blockEntity().isEmpty()
                ? "§f" + cur.blockEntity() : "§8（没有）");
        this.holder.setStatus(cur.ok() ? "已读当前方块：" + cur.block() : "读失败：" + cur.error());
        // 回填：维度/坐标/方块/状态（服务端推给客户端那份控件）
        this.pushText(this.regionDimField, ShanhaiRegionEdit.normalizeDimension(dim));
        this.pushText(this.regionXField, String.valueOf(xyz[0]));
        this.pushText(this.regionYField, String.valueOf(xyz[1]));
        this.pushText(this.regionZField, String.valueOf(xyz[2]));
        if (cur.ok()) {
            try {
                final ShanhaiChunkEditor.State st = ShanhaiChunkEditor.State.parse(cur.block());
                this.pushText(this.regionBlockField, st.name());
                this.pushText(this.regionStateField, propsText(st));
            } catch (IllegalArgumentException iae) {
                ShanhaiMod.LOGGER.warn("{} panel_read_region parse_state_failed block={} err={}",
                        ShanhaiSaveEditFlow.PREFIX, cur.block(), iae.toString());
            }
        }
        ShanhaiMod.LOGGER.info("{} panel_read_region player={} {} result={}",
                ShanhaiSaveEditFlow.PREFIX, serverPlayer.getName().getString(),
                ShanhaiRegionEdit.locate(ctx.worldDir, req), cur.line());
    }

    /** 把文字推给客户端那份控件（{@code TextFieldWidget.readUpdateInfo(1)} 认的就是这个包）。 */
    private void pushText(TextFieldWidget field, String value) {
        if (field instanceof FillField f && !this.remote) {
            f.pushText(value == null ? "" : value);
        } else if (field != null && !this.remote) {
            field.setCurrentString(value == null ? "" : value);
        }
    }

    /** 清空第 ③ 页的输入（方块/状态/BE NBT；维度与坐标保留）。 */
    private void onClearRegionFields() {
        for (TextFieldWidget f : new TextFieldWidget[] {this.regionBlockField, this.regionStateField,
                this.regionBeField}) {
            if (f != null) {
                f.setCurrentString("");     // 客户端那份会直接改 EditBox（isRemote() 为真）
            }
        }
        if (!this.remote) {
            this.pushText(this.regionBlockField, "");
            this.pushText(this.regionStateField, "");
            this.pushText(this.regionBeField, "");
            this.holder.setStatus("已清空方块/状态/BE NBT（维度与坐标保留）");
        }
    }

    /** BE 模式按钮上的字（两侧各自读自己的 holder）。 */
    private String beModeText() {
        final String mode = this.holder.beAction();
        return "BE：" + (ShanhaiRegionEdit.BE_CLEAR.equals(mode) ? "§e清空"
                : ShanhaiRegionEdit.BE_REPLACE.equals(mode) ? "§a替换" : "§7不动");
    }

    /** 切 BE 模式：不动 → 清空 → 替换 → 不动。 */
    private void onCycleBe() {
        final String now = this.holder.beAction();
        final String next = ShanhaiRegionEdit.BE_NONE.equals(now) ? ShanhaiRegionEdit.BE_CLEAR
                : ShanhaiRegionEdit.BE_CLEAR.equals(now) ? ShanhaiRegionEdit.BE_REPLACE
                : ShanhaiRegionEdit.BE_NONE;
        this.holder.setBeAction(next);
        if (!this.remote) {
            this.holder.setStatus("BE 动作 = " + beModeText().replaceAll("§.", ""));
            ShanhaiMod.LOGGER.info("{} panel_be_mode player={} mode={}",
                    ShanhaiSaveEditFlow.PREFIX,
                    this.player == null ? "?" : this.player.getName().getString(), next);
        }
    }

    // ================================================================ 第 ④ 页按钮（实体）

    /** 这一份是不是"服务端那份"（只有它才读盘/写盘）。 */
    private boolean isServerSide() {
        return !this.remote && this.player instanceof ServerPlayer;
    }

    /** 拿存档路径快照（拿不到就写状态行并返回 {@code null}）。 */
    private ShanhaiSaveEditRunContext ctxOrStatus() {
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_FAILED + "拿不到存档路径");
        }
        return ctx;
    }

    /** 第 ④ 页里"维度"那个框归一化后的值（不认识 ⇒ {@code null}，调用方必须拒绝）。 */
    private String entityDimension() {
        return ShanhaiSaveEditDimension.normalize(text(this.entityDimField));
    }

    /**
     * 「列出」：服务端按<b>维度</b>扫 {@code entities/*.mca}（不用填坐标），把 7 行可点的字与概况推给客户端。
     *
     * <p>🔴 搜索走数据层的 {@code filter} 参数（<b>不在 UI 层过滤全量结果</b>）：数据层才有
     * "uuid 允许省掉连字符""同时匹配 id / uuid / CustomName"这套口径，UI 层再实现一份必然对不上。
     */
    private void onListEntities() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = ctxOrStatus();
        if (ctx == null) {
            return;
        }
        final String rawDim = text(this.entityDimField);
        final String dim = ShanhaiSaveEditDimension.normalize(rawDim);
        if (dim == null) {
            this.holder.setStatus("§c维度写法不认识：" + rawDim + "（如 minecraft:overworld / the_nether / ns:path）");
            this.holder.setEntityListing(null);
            this.holder.setEntitySummary(ShanhaiSaveEditEntityUi.summaryLine(null));
            this.holder.setEntityResidueNote(ShanhaiSaveEditEntityUi.residueNote(null));
            this.holder.setEntityPage(0);
            this.holder.setEntityPageText(ShanhaiSaveEditEntityUi.pageText(0, 0, EN_ROWS));
            this.holder.setEntityPageCountText(ShanhaiSaveEditEntityUi.pageCountText(0, 0, EN_ROWS));
            this.pushEntityRows(null);
            return;
        }
        // 换维度 / 换搜索词 / 按「列出」⇒ 回到第 1 页（否则会在一个不属于新条件的页号上）
        this.holder.setEntityPage(0);
        this.listEntityPage(ctx.worldDir, dim);
    }

    /**
     * 按当前 page / 维度 / 搜索 / 残留开关，扫一次盘并把"这一页"推给客户端。
     *
     * <p>数据层只支持"取前 N 条"，所以第 {@code p} 页的做法是取前 {@code (p+1)×7} 条再切最后一段
     * （见 {@link ShanhaiSaveEditEntityUi#fetchLimit(int, int, int)} 与 {@code pageRows}）——
     * 这样既不用改数据层，也不会漏行。
     */
    private void listEntityPage(java.nio.file.Path worldDir, String dim) {
        final String filter = text(this.entitySearchField);
        final boolean includeResidue = this.holder.entityIncludeResidue();
        // 先用上一轮知道的匹配数把页号夹进合法范围（第一次列的时候还是 0，夹不动）
        final int known = this.holder.entityListing() == null ? 0 : this.holder.entityListing().matched();
        final int page = ShanhaiSaveEditEntityUi.clampPage(this.holder.entityPage(), known, EN_ROWS);
        this.holder.setEntityPage(page);
        final int limit = (page + 1) * EN_ROWS;
        final ShanhaiEntityEdit.DimensionListing listing =
                ShanhaiEntityEdit.listDimension(worldDir, dim, filter, limit, includeResidue);
        this.holder.setEntityListing(listing);
        final int matched = listing.ok() ? listing.matched() : 0;
        // matched 拿到了 ⇒ 再夹一次（上一轮可能不知道真实页数）
        final int fixedPage = ShanhaiSaveEditEntityUi.clampPage(page, matched, EN_ROWS);
        this.holder.setEntityPage(fixedPage);
        this.holder.setEntitySummary(ShanhaiSaveEditEntityUi.summaryLine(listing));
        this.holder.setEntityResidueNote(ShanhaiSaveEditEntityUi.residueNote(listing));
        this.holder.setEntityPageText(ShanhaiSaveEditEntityUi.pageText(fixedPage, matched, EN_ROWS));
        this.holder.setEntityPageCountText(ShanhaiSaveEditEntityUi.pageCountText(fixedPage, matched, EN_ROWS));
        this.pushEntityRows(listing);
        // 归一化后的维度回填（客户端那份也看到同一个值；与第 ③ 页同一条通道）
        this.pushText(this.entityDimField, dim);
        this.holder.setStatus(listing.ok()
                ? "已列出实体：" + listing.total() + " 个（匹配 " + listing.matched() + " · "
                + ShanhaiSaveEditEntityUi.pageText(fixedPage, matched, EN_ROWS) + " · "
                + (includeResidue ? "含残留" : "折叠残留 " + listing.residueHidden()) + "）"
                : "列不出来：" + listing.error());
        ShanhaiMod.LOGGER.info("{} panel_entity_list player={} page={} include_residue={} {}",
                ShanhaiSaveEditFlow.PREFIX, ctxPlayerName(), fixedPage + 1, includeResidue, listing.line());
        // 原因也顺手打到聊天栏（用户原话："可以写，打在聊天栏里面，但是要写的简要一点，不要刷屏"）。
        // 🔴 上面那行日志、详情覆盖层、列表/翻页/搜索一个字都不动；这里只多一条"发给本人"的通道：
        //    行数与字数由纯 JDK 的 ShanhaiSaveEditNotesChat 定死（≤2 行 · 每行 ≤60 字 · 没原因不发）。
        final List<String> notesChat = ShanhaiSaveEditNotesChat.linesOf(listing.notes());
        if (!notesChat.isEmpty() && this.player instanceof ServerPlayer notesChatTo
                && this.notesChatThrottle.allow(notesChat, System.currentTimeMillis())) {
            for (String notesChatLine : notesChat) {
                ShanhaiSaveEditBridge.reportToPlayer(notesChatTo, notesChatLine);
            }
        }
    }

    /** 「◀上一页 / 下一页▶」：服务端换页并重推（客户端那份只等字，不动状态）。 */
    private void onEntityPage(int delta) {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        final ShanhaiEntityEdit.DimensionListing listing = this.holder.entityListing();
        if (ctx == null || listing == null || !listing.ok()) {
            this.holder.setStatus("§c先点一次「列出」再翻页");
            return;
        }
        final String dim = ShanhaiSaveEditDimension.normalize(text(this.entityDimField));
        if (dim == null) {
            this.holder.setStatus("§c维度写法不认识（先点「列出」）");
            return;
        }
        final int want = ShanhaiSaveEditEntityUi.clampPage(
                this.holder.entityPage() + delta, listing.matched(), EN_ROWS);
        if (want == this.holder.entityPage()) {
            this.holder.setStatus("已经是" + (delta < 0 ? "第 1 页" : "最后一页")
                    + "（" + ShanhaiSaveEditEntityUi.pageText(want, listing.matched(), EN_ROWS) + "）");
            return;
        }
        this.holder.setEntityPage(want);
        this.listEntityPage(ctx.worldDir, dim);
        ShanhaiMod.LOGGER.info("{} panel_entity_page player={} page={}/{}",
                ShanhaiSaveEditFlow.PREFIX, ctxPlayerName(), want + 1,
                ShanhaiSaveEditEntityUi.pageCount(listing.matched(), EN_ROWS));
    }

    /** 把"这一页"的 7 行原料推给客户端那份（客户端自己拼字；{@code null} = 全清空）。 */
    private void pushEntityRows(ShanhaiEntityEdit.DimensionListing listing) {
        final List<ShanhaiEntityEdit.Row> rows = ShanhaiSaveEditEntityUi.pageRows(
                listing, this.holder.entityPage(), EN_ROWS);
        for (int i = 0; i < this.entityRows.length; i++) {
            final EntityRowButton row = this.entityRows[i];
            if (row == null) {
                continue;
            }
            if (i >= rows.size()) {
                row.clearRow();
                continue;
            }
            final ShanhaiEntityEdit.Row r = rows.get(i);
            row.pushRow(true, r.id(), r.x(), r.y(), r.z(), r.uuid(), r.residue());
        }
    }

    /** 残留开关上的字（两侧各读自己的 holder：点一下两侧都翻）。 */
    private String residueButtonText() {
        return "残留：" + (this.holder.entityIncludeResidue() ? "§a显示" : "§7折叠");
    }

    /** 切"折叠 / 显示"视觉残留（默认折叠）。已经列过一次的话，切完立刻按新口径重列一遍。 */
    private void onCycleResidue() {
        final boolean next = !this.holder.entityIncludeResidue();
        this.holder.setEntityIncludeResidue(next);
        if (this.remote) {
            return;
        }
        final String what = "视觉残留 = " + (next ? "显示（连残留一起列）" : "折叠（默认）");
        ShanhaiMod.LOGGER.info("{} panel_entity_residue player={} include={}",
                ShanhaiSaveEditFlow.PREFIX, ctxPlayerName(), next);
        if (this.holder.entityListing() != null) {
            this.onListEntities();          // 重列（它会自己写状态行），再补一句"为什么变了"
            this.holder.setStatus(what + " · 已按新口径重列");
        } else {
            this.holder.setStatus(what + " · 点「列出」才扫盘");
        }
    }

    /** 「清空」：清搜索框与列表（维度保留）。 */
    private void onClearEntityFilters() {
        if (this.entitySearchField != null) {
            this.entitySearchField.setCurrentString("");
        }
        if (this.remote) {
            return;
        }
        this.pushText(this.entitySearchField, "");
        this.holder.setEntityListing(null);
        this.holder.setEntitySummary(ShanhaiSaveEditEntityUi.summaryLine(null));
        this.holder.setEntityResidueNote(ShanhaiSaveEditEntityUi.residueNote(null));
        this.holder.setEntityPage(0);
        this.holder.setEntityPageText(ShanhaiSaveEditEntityUi.pageText(0, 0, EN_ROWS));
        this.holder.setEntityPageCountText(ShanhaiSaveEditEntityUi.pageCountText(0, 0, EN_ROWS));
        this.pushEntityRows(null);
        this.holder.setStatus("已清空搜索与列表（维度保留）");
    }

    /**
     * 点了第 {@code index} 行：进详情。
     *
     * <p>两侧各跑一次：客户端那份先把覆盖层弹出来（字随后由服务端推），服务端那份读盘并灌读数。
     */
    private void onClickEntityRow(int index) {
        if (this.entityOverlay == null) {
            return;
        }
        if (!isServerSide()) {
            // 客户端：先把覆盖层弹出来（标题先用本侧那行的原料现拼，服务端随后把读数推过来）
            final EntityRowButton.Cell cell = index >= 0 && index < this.entityRows.length
                    && this.entityRows[index] != null ? this.entityRows[index].cell() : null;
            this.entityOverlay.openPlaceholder(cell == null || !cell.present
                    ? "" : ShanhaiSaveEditEntityUi.typeShort(cell.typeId, ShanhaiSaveEditEntityUi.REGISTRY_NAMER));
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        final ShanhaiEntityEdit.DimensionListing listing = this.holder.entityListing();
        final List<ShanhaiEntityEdit.Row> pageRows = ShanhaiSaveEditEntityUi.pageRows(
                listing, this.holder.entityPage(), EN_ROWS);
        if (ctx == null || listing == null || !listing.ok()
                || index < 0 || index >= pageRows.size()) {
            this.holder.setStatus("§c先点一次「列出」（这一行的列表已经过期了）");
            this.entityOverlay.openPlaceholder("");
            this.entityOverlay.model().setFeedback("§c这一行已经过期：重新点一次「列出」");
            return;
        }
        final ShanhaiEntityEdit.Row row = pageRows.get(index);
        final ShanhaiEntityEdit.Detail detail =
                ShanhaiEntityEdit.read(ctx.worldDir, listing.dimension(), row.uuid());
        final int chunkEntities = this.countChunkEntities(detail);
        this.entityOverlay.openDetail(detail, chunkEntities);
        this.holder.setStatus(detail.ok()
                ? "读了 " + detail.id() + " 的 10 项 · 点「写入」直接写（dangers 会列出来）"
                : "读不出来：" + detail.error());
        ShanhaiMod.LOGGER.info("{} panel_entity_open player={} dim={} uuid={} chunk_entities={} ok={} {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, listing.dimension(), row.uuid(), chunkEntities,
                detail.ok(), detail.line());
    }

    /**
     * 这条实体所在区块<b>现在</b>有几条实体（删除预期的"before"）。
     *
     * <p>为什么另外算一遍而不是数列表：列出来的是<b>过滤 + 折叠残留之后</b>的行，
     * 拿它当"这个区块一共几条"会在搜索时给出假的预期。这里只读<b>这一个文件</b>、
     * 且 {@code maxEntries=5000}（要的就是全量条目），一个字节都不写。
     *
     * @return {@code -1} = 算不出来（覆盖层会如实说"算不出来"，绝不编一个数字）
     */
    private int countChunkEntities(ShanhaiEntityEdit.Detail detail) {
        if (detail == null || !detail.ok() || detail.hit() == null) {
            return -1;
        }
        try {
            final ShanhaiEntityEdit.Listing listing = ShanhaiEntityEdit.listFile(detail.hit().path(),
                    detail.hit().regionX(), detail.hit().regionZ(), 5000);
            if (!listing.ok()) {
                return -1;
            }
            int n = 0;
            for (ShanhaiEntityEdit.Entry e : listing.entries()) {
                if (e.slot() == detail.hit().slot()) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} panel_entity_chunk_count_failed uuid={} err={}",
                    ShanhaiSaveEditFlow.PREFIX, detail.uuid(), t.toString());
            return -1;
        }
    }

    /** 覆盖层「取消」：什么都不改。 */
    void onOverlayCancel() {
        if (this.entityOverlay != null) {
            this.entityOverlay.close();
        }
        if (!this.remote) {
            this.holder.setStatus("已取消（一个字节都没写）");
        }
    }

    /**
     * 覆盖层「写入」：<b>一次点击就是一次真做</b>（先算预演当读数与拒绝理由，预演过了就直接写）。
     *
     * <p>真写走 {@link ShanhaiSaveEditEntityUi#writeGated}：那一步自带
     * {@link ShanhaiSaveEditFlow#tryApply(String)} 闸门 —— 停服窗口没开就<b>一个字节都不写</b>，
     * 并如实回一句为什么（窗口没开 ⇒ 暂存，等「确认修改并重载」）。
     *
     * <p>🔴 <b>没有"先预演、再点一次才真写"这一步</b>：用户 2026-11-15 的原话
     * 「除非我指定需要二次确认，否则都不加」⇒ 原来那个 {@code model.setArm(...)} 三态机已删除。
     * 预演仍然算（它给的是"将改什么 / 有哪些 danger / 为什么被拒"），只是<b>不再拦着不让写</b>。
     */
    void onOverlayWrite() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditEntityUi.Model model = this.entityOverlay.model();
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            model.setFeedback("§c拿不到存档路径（先点一次「列出」）");
            return;
        }
        final String blocked = model.writeBlockedReason();
        if (blocked != null) {
            model.setFeedback("§c" + blocked);
            return;
        }
        model.setValues(this.entityOverlay.readInputs());
        final ShanhaiEntityEdit.Patch patch;
        try {
            patch = model.patch();
        } catch (IllegalArgumentException iae) {
            model.setFeedback("§c" + iae.getMessage());
            return;
        }
        final ShanhaiSaveEditEntityUi.Preview preview =
                ShanhaiSaveEditEntityUi.preview(model.detail(), patch);
        model.setPreview(preview);
        // 预演这一条 log 保留（它是"这次要改什么、有没有 danger"的唯一凭据），但不再拦路
        ShanhaiMod.LOGGER.info("{} panel_entity_write_preview player={} uuid={} keys={} ok={} dangers={}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, model.detail().uuid(), patch.keys(),
                preview.ok(), preview.dangers());
        if (!preview.ok()) {
            return;                      // 预演不过 ⇒ 一个字节都不写（拒绝理由已在覆盖层红字里）
        }
        model.setFeedback("§e正在写…");
        final String stamp = ShanhaiSaveEditStorage.stamp(System.currentTimeMillis());
        final ShanhaiSaveEditEntityUi.Apply apply = ShanhaiSaveEditEntityUi.writeGated(
                ctx.worldDir, model.detail().dimension(), model.detail().uuid(), patch,
                ctx.backupRoot, stamp, ShanhaiSaveEditFlow.current());
        if (!apply.permitted()) {
            // 窗口没开（正常游戏里的情形）⇒ 暂存，等「确认修改并重载」在停服窗口里执行
            // （ShanhaiSaveEditWindow 的 ⑤b 段）。这条路是真的会落盘的。
            this.holder.setStagedEntityPatch(patch, model.detail().dimension(), model.detail().uuid());
            final String staged = "§e已暂存（点右上「确认修改并重载」才真落盘）：改 "
                    + patch.keys() + " §8@ " + ShanhaiSaveEditEntityUi.typeLabel(model.detail().id(),
                    ShanhaiSaveEditEntityUi.REGISTRY_NAMER);
            model.setFeedback(staged);
            this.holder.setStatus(staged);
            ShanhaiMod.LOGGER.info("{} panel_entity_write_staged player={} uuid={} keys={} {} disk_written=0 (窗口未开)",
                    ShanhaiSaveEditFlow.PREFIX, ctx.playerName, model.detail().uuid(), patch.keys(),
                    model.detail().dimension());
            return;
        }
        final String line = ShanhaiSaveEditEntityUi.writeResultLine(apply.write());
        model.setFeedback(line);
        this.holder.setStatus(line);
        ShanhaiMod.LOGGER.info("{} panel_entity_write player={} uuid={} keys={} ok={} {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, model.detail().uuid(), patch.keys(),
                apply.ok(), apply.write() == null ? "-" : apply.write().line());
        if (apply.ok()) {
            this.refreshEntityAfterWrite(model.detail().dimension(), model.detail().uuid());
        }
    }

    /**
     * 覆盖层「删除这条」：<b>一次点击就是一次真做</b>（"预期"那一段仍然算，只用来给玩家看
     * "该区块实体 4 → 3"，不再当"再点一次"的闸）。
     *
     * <p>动手时两条路：
     * <ol>
     *   <li>停服窗口正开着 ⇒ {@link ShanhaiSaveEditEntityUi#deleteGated} 直接删（带闸）；</li>
     *   <li>窗口没开（正常游戏里的情形）⇒ 走<b>第四期已经验过的"暂存 + 确认"通道</b>：
     *       把请求塞进 {@code holder.stagedEntity}，点「确认修改并重载」之后由落盘窗口删。
     *       —— 这条路是真的会落盘的，所以不能把"暂存"说成"已删"。</li>
     * </ol>
     * 🔴 没有"再点一次「删除这条」才动手"（用户 2026-11-15：「除非我指定需要二次确认，否则都不加」）。
     */
    void onOverlayDelete() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditEntityUi.Model model = this.entityOverlay.model();
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            model.setFeedback("§c拿不到存档路径（先点一次「列出」）");
            return;
        }
        final String blocked = model.deleteBlockedReason();
        if (blocked != null) {
            model.setFeedback("§c" + blocked);
            return;
        }
        if (ShanhaiEntityEdit.PLAYER_TYPE.equals(model.detail().id())) {
            model.setFeedback("§c玩家实体一律不删（playerdata/<uuid>.dat 才是权威副本）");
            return;
        }
        final String expect = model.expectation();
        model.setFeedback("删除：" + expect);
        this.holder.setStatus("删除：" + expect);
        ShanhaiMod.LOGGER.info("{} panel_entity_delete_preview uuid={} expect=\"{}\" disk_written=0",
                ShanhaiSaveEditFlow.PREFIX, model.detail().uuid(), stripColors(expect));
        final String stamp = ShanhaiSaveEditStorage.stamp(System.currentTimeMillis());
        final ShanhaiSaveEditEntityUi.Apply apply = ShanhaiSaveEditEntityUi.deleteGated(
                ctx.worldDir, model.detail().dimension(), model.detail().uuid(),
                ctx.backupRoot, stamp, ShanhaiSaveEditFlow.current());
        if (apply.permitted()) {
            final String line = ShanhaiSaveEditEntityUi.deleteResultLine(model.detail().dimension(), apply.write());
            model.setFeedback(line);
            this.holder.setStatus(line);
            ShanhaiMod.LOGGER.info("{} panel_entity_delete player={} uuid={} ok={} {}",
                    ShanhaiSaveEditFlow.PREFIX, ctx.playerName, model.detail().uuid(), apply.ok(),
                    apply.write() == null ? "-" : apply.write().line());
            if (apply.ok()) {
                this.entityOverlay.close();
                this.onListEntities();
                this.holder.setStatus(line);
            }
            return;
        }
        // 窗口没开 ⇒ 暂存（这条路会真的落盘：走「确认修改并重载」）
        final ShanhaiEntityEdit.Hit hit = model.detail().hit();
        if (hit == null) {
            model.setFeedback("§c这条实体没有定位信息（hit=null），不敢暂存");
            return;
        }
        // 🆕 2026-11-20：把"暂存那一刻的身份"一起记进请求（实体 id ＋ Pos 三位原值）。
        //    判据（实机）：退出世界写盘时那条实体会被重写、UUID 变了 ⇒ 落盘阶段按 UUID 找不到，
        //    只能靠「同区块 ＋ 同 id ＋ 同 Pos」兜底（见 ShanhaiEntityEdit.Anchor）。
        final ShanhaiEntityEdit.Request req = new ShanhaiEntityEdit.Request(
                model.detail().dimension(), hit.regionX(), hit.regionZ(), "", model.detail().uuid(),
                new ShanhaiEntityEdit.Anchor(hit.id(), hit.x(), hit.y(), hit.z()));
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            model.setFeedback("§c" + iae.getMessage());
            return;
        }
        // 🔴 先把要显示的字取出来：下面 close() 会把模型清空（detail 变 null）
        final String deletedId = model.detail().id();
        final String deletedUuid = model.detail().uuid();
        this.holder.setStagedEntity(req);
        this.entityOverlay.close();
        this.onListEntities();
        this.holder.setStatus("§e已暂存（点右上「确认修改并重载」才真落盘）：删 "
                + deletedId + " §8" + deletedUuid + " §e· " + stripColors(expect));
        ShanhaiMod.LOGGER.info("{} panel_entity_delete_staged player={} {} expect=\"{}\" disk_written=0 (窗口未开)",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, req.line(), stripColors(expect));
    }

    /** 写完/删完把覆盖层与列表刷新一次（读数可能变了）。 */
    private void refreshEntityAfterWrite(String dimension, String uuid) {
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            return;
        }
        final String status = this.holder.status();
        final ShanhaiEntityEdit.Detail detail = ShanhaiEntityEdit.read(ctx.worldDir, dimension, uuid);
        this.entityOverlay.openDetail(detail, this.countChunkEntities(detail));
        this.onListEntities();
        this.holder.setStatus(status);
    }

    /** 去掉 {@code §x} 颜色码（日志/聊天栏里用）。 */
    private static String stripColors(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }

    // ================================================================ 第 ⑤ 页按钮（成就）

    /**
     * 「列出」：服务端读盘 + 查游戏里的成就定义，把<b>第 1 页的 7 行</b>推给客户端。
     *
     * <p>S2b 改版：原来是把一大段只读文字塞进 7 个标签；现在照第 ④ 页换成
     * <b>7 行可点的按钮 + 翻页 + 点一行进详情</b>。
     */
    private void onListAdvancements() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = ctxOrStatus();
        if (ctx == null) {
            return;
        }
        // 换搜索词 / 按「列出」⇒ 回到第 1 页（否则会停在一个不属于新条件的页号上）
        this.advPage = 0;
        this.advRegistryIds = this.advancementRegistryIds();
        this.listAdvancementPage(ctx);
    }

    /**
     * 按当前 page / 搜索词扫一次盘，并把"这一页"推给客户端。
     *
     * <p>数据层只支持"取前 N 条"（{@code listFile(file, knownIds, maxEntries)} 没有 offset 参数），
     * 所以这一页的做法是：<b>取前 LIST_CAP 条 → 在内存里过滤 → 切出第 page 段</b>。
     * 过滤之后下标会变，所以切段必须在过滤后的列表上做（{@link ShanhaiSaveEditLayout#pageFrom}）。
     */
    private void listAdvancementPage(ShanhaiSaveEditRunContext ctx) {
        final String filter = text(this.advFilterField);
        final java.nio.file.Path file = ShanhaiAdvancementEdit.fileOf(ctx.worldDir, ctx.playerUuid);
        final ShanhaiAdvancementEdit.Listing listing = ShanhaiAdvancementEdit.listFile(file,
                this.advRegistryIds, ShanhaiSaveEditAdvancementUi.LIST_CAP);
        this.advListing = listing;
        this.advAll = ShanhaiSaveEditAdvancementUi.filter(
                listing.ok() ? listing.entries() : List.of(), filter);
        final int matched = this.advAll.size();
        final int page = ShanhaiSaveEditLayout.clampPage(this.advPage, matched, LP_ROWS);
        this.advPage = page;
        this.advSummary = ShanhaiSaveEditAdvancementUi.summaryLine(listing, matched, filter);
        this.advPageText = ShanhaiSaveEditAdvancementUi.pageText(page, matched);
        this.advPageCountText = ShanhaiSaveEditAdvancementUi.pageCountText(page, matched);
        this.pushAdvancementRows();
        this.holder.setStatus(listing.ok()
                ? "已列出成就：文件里 " + listing.present() + " 条（匹配 " + matched + " · "
                + ShanhaiSaveEditAdvancementUi.pageText(page, matched) + "）"
                : "列不出来：" + listing.error());
        ShanhaiMod.LOGGER.info("{} panel_advancement_list player={} page={} filter=\"{}\" known={} {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, page + 1, filter,
                this.advRegistryIds == null ? "<unavailable>" : this.advRegistryIds.size(), listing.line());
    }

    /** 「◀上一页 / 下一页▶」：服务端换页并重推（客户端那份只等字，不动状态）。 */
    private void onAdvancementPage(int delta) {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.holder.setStatus("§c先点一次「列出」再翻页");
            return;
        }
        if (this.advListing == null || !this.advListing.ok()) {
            this.holder.setStatus("§c先点一次「列出」再翻页");
            return;
        }
        final int matched = this.advAll.size();
        final int want = ShanhaiSaveEditLayout.clampPage(this.advPage + delta, matched, LP_ROWS);
        if (want == this.advPage) {
            this.holder.setStatus("已经是" + (delta < 0 ? "第 1 页" : "最后一页")
                    + "（" + ShanhaiSaveEditAdvancementUi.pageText(want, matched) + "）");
            return;
        }
        this.advPage = want;
        this.listAdvancementPage(ctx);
        ShanhaiMod.LOGGER.info("{} panel_advancement_page player={} page={}/{}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, want + 1,
                ShanhaiSaveEditLayout.pageCount(matched, LP_ROWS));
    }

    /** 把"这一页"的 7 行原料推给客户端那份（客户端自己拼中文名；{@code null} = 全清空）。 */
    private void pushAdvancementRows() {
        final List<ShanhaiAdvancementEdit.Entry> rows =
                ShanhaiSaveEditAdvancementUi.pageRows(this.advAll, this.advPage);
        for (int i = 0; i < this.advRows.length; i++) {
            final AdvancementRowButton row = this.advRows[i];
            if (row == null) {
                continue;
            }
            if (i >= rows.size()) {
                row.clearRow();
                continue;
            }
            final ShanhaiAdvancementEdit.Entry e = rows.get(i);
            row.pushRow(e.present(), e.id(), this.advKnown(e.id()), e.done(), e.criteria());
        }
    }

    /** 游戏里有没有这条成就（{@code advRegistryIds == null} ⇒ 查不到，不声称"未注册"）。 */
    private boolean advKnown(String id) {
        return this.advRegistryIds == null || (id != null && this.advRegistryIds.contains(id));
    }

    /** 「清空」：清搜索框与列表（回到第 1 页）。 */
    private void onClearAdvancementFilter() {
        if (this.advFilterField != null) {
            this.advFilterField.setCurrentString("");
        }
        if (this.remote) {
            return;
        }
        this.pushText(this.advFilterField, "");
        this.advPage = 0;
        this.advListing = null;
        this.advAll = List.of();
        this.advSummary = ShanhaiSaveEditAdvancementUi.summaryLine(null, 0, "");
        this.advPageText = ShanhaiSaveEditAdvancementUi.pageText(0, 0);
        this.advPageCountText = ShanhaiSaveEditAdvancementUi.pageCountText(0, 0);
        this.pushAdvancementRows();
        this.holder.setStatus("已清空搜索与列表");
    }

    /**
     * 点了第 {@code index} 行：进详情。
     *
     * <p>两侧各跑一次：客户端那份先把覆盖层弹出来（字随后由服务端推），服务端那份读盘
     * 并把这条件名（{@code AdvancementManager}）灌进模型。
     */
    private void onClickAdvancementRow(int index) {
        if (this.advOverlay == null) {
            return;
        }
        if (!isServerSide()) {
            this.advOverlay.openPlaceholder();
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        final List<ShanhaiAdvancementEdit.Entry> pageRows =
                ShanhaiSaveEditAdvancementUi.pageRows(this.advAll, this.advPage);
        if (ctx == null || index < 0 || index >= pageRows.size()) {
            this.holder.setStatus("§c先点一次「列出」（这一行的列表已经过期了）");
            this.advOverlay.openPlaceholder();
            this.advOverlay.model().setFeedback("§c这一行已经过期：重新点一次「列出」");
            return;
        }
        final ShanhaiAdvancementEdit.Entry e = pageRows.get(index);
        final ShanhaiSaveEditAdvancementUi.Detail detail = ShanhaiSaveEditAdvancementUi.describe(
                e, this.advKnown(e.id()), this::criteriaOfAdvancement, this.advAll.size());
        this.advOverlay.openDetail(detail);
        this.holder.setStatus(detail.canGrant()
                ? "读了 " + detail.key() + " 的 " + detail.criteria().size()
                + " 条条件 · 点「写入（给）」直接写"
                : "读了 " + detail.key() + "：游戏里查不到它的定义 ⇒ 只能撤，不能给");
        ShanhaiMod.LOGGER.info("{} panel_advancement_open player={} id={} known={} criteria={} {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, detail.key(), detail.known(),
                detail.criteria().size(), detail.present() ? "present" : "absent");
    }

    /**
     * 覆盖层「写入（给）」：<b>一次点击就是一次真做</b>（预演只当读数与拒绝理由用，过了就直接写）；
     * 窗口没开就<b>暂存</b>进 holder，等「确认修改并重载」在停服窗口里落盘。
     *
     * <p>🔴 没有"再点一次"（用户 2026-11-15：「除非我指定需要二次确认，否则都不加」）。
     */
    void onAdvancementOverlayGrant() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditAdvancementUi.Model model = this.advOverlay.model();
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null || model.detail() == null) {
            model.setFeedback("§c还没选中一条成就（先点一行）");
            return;
        }
        final ShanhaiAdvancementEdit.Request req = new ShanhaiAdvancementEdit.Request(
                ShanhaiAdvancementEdit.ACTION_GRANT, model.detail().key(), model.detail().criteria());
        this.runAdvancementAction(ctx, model, req);
    }

    /** 覆盖层「撤成就」：同 {@link #onAdvancementOverlayGrant()}，但动作是"整条删"。 */
    void onAdvancementOverlayRevoke() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditAdvancementUi.Model model = this.advOverlay.model();
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null || model.detail() == null) {
            model.setFeedback("§c还没选中一条成就（先点一行）");
            return;
        }
        final ShanhaiAdvancementEdit.Request req = new ShanhaiAdvancementEdit.Request(
                ShanhaiAdvancementEdit.ACTION_REVOKE, model.detail().key(), List.of());
        this.runAdvancementAction(ctx, model, req);
    }

    /** 「预演 → 带闸写 / 暂存」这一整套（给 / 撤共用，免得两条路各写一份）。 */
    private void runAdvancementAction(ShanhaiSaveEditRunContext ctx,
                                      ShanhaiSaveEditAdvancementUi.Model model,
                                      ShanhaiAdvancementEdit.Request req) {
        final java.nio.file.Path file = ShanhaiAdvancementEdit.fileOf(ctx.worldDir, ctx.playerUuid);
        final ShanhaiSaveEditAdvancementUi.Preview preview =
                ShanhaiSaveEditAdvancementUi.preview(file, req);
        model.setPreview(preview);
        final String action = req.isGrant() ? "给" : "撤";
        if (!preview.ok()) {
            return;                      // 预演不过 ⇒ 一个字节都不写
        }
        model.setFeedback("§e正在" + action + "…");
        final String stamp = ShanhaiSaveEditStorage.stamp(System.currentTimeMillis());
        final ShanhaiSaveEditAdvancementUi.Apply apply = ShanhaiSaveEditAdvancementUi.writeGated(
                ctx.worldDir, ctx.playerUuid, req, ctx.backupRoot, stamp, ShanhaiSaveEditFlow.current());
        if (!apply.permitted()) {
            // 窗口没开（正常游戏里的情形）⇒ 暂存，等「确认修改并重载」在停服窗口里执行
            // （ShanhaiSaveEditWindow 的 ⑥ 段）。这条路是真的会落盘的。
            this.holder.setStagedAdvancement(req);
            final String staged = "§e已暂存（点右上「确认修改并重载」才真落盘）：" + action + " "
                    + req.id() + " §8" + ShanhaiAdvancementEdit.explainThreePieces(req)
                    + " · " + stripColors(ShanhaiSaveEditAdvancementUi.windowClosed(ShanhaiSaveEditFlow.current()));
            model.setFeedback(staged);
            this.holder.setStatus(staged);
            ShanhaiMod.LOGGER.info("{} panel_advancement_staged player={} action={} {} disk_written=0 (窗口未开)",
                    ShanhaiSaveEditFlow.PREFIX, ctx.playerName, action, req.line());
            return;
        }
        final String line = apply.ok()
                ? "§a已写：" + action + " " + req.id() + " §8(" + preview.changed().size() + " 处改动，已回读校验)"
                : "§c没写进去：" + (apply.write() == null || apply.write().error() == null
                        ? "（没给原因）" : apply.write().error());
        model.setFeedback(line);
        this.holder.setStatus(line);
        ShanhaiMod.LOGGER.info("{} panel_advancement_write player={} action={} ok={} {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, action, apply.ok(),
                apply.write() == null ? "-" : apply.write().line());
        if (apply.ok()) {
            this.refreshAdvancementAfterWrite(req.id(), line);
        }
    }

    /** 写完把列表与覆盖层刷新一次（读数可能变了）。 */
    private void refreshAdvancementAfterWrite(String id, String feedback) {
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            return;
        }
        this.listAdvancementPage(ctx);
        ShanhaiAdvancementEdit.Entry found = null;
        for (ShanhaiAdvancementEdit.Entry e : this.advAll) {
            if (e.id() != null && e.id().equals(id)) {
                found = e;
                break;
            }
        }
        if (found == null) {
            this.advOverlay.close();
            this.holder.setStatus(feedback);
            return;
        }
        this.advOverlay.openDetail(ShanhaiSaveEditAdvancementUi.describe(found, this.advKnown(id),
                this::criteriaOfAdvancement, this.advAll.size()));
        this.advOverlay.model().setFeedback(feedback);
        this.holder.setStatus(feedback);
    }

    /** 覆盖层「取消」：什么都不改。 */
    void onAdvancementOverlayCancel() {
        if (this.advOverlay != null) {
            this.advOverlay.close();
        }
        if (!this.remote) {
            this.holder.setStatus("已取消（一个字节都没写）");
        }
    }

    /**
     * 游戏里全部成就 id。
     *
     * @return {@code null} = <b>查不到</b>（没有服务器 / 抛了）；调用方必须把它当"不知道"，
     *         而不是"一条都没有"（后者会让每一条都显示〔未注册〕）
     */
    private List<String> advancementRegistryIds() {
        try {
            if (!(this.player instanceof ServerPlayer sp)) {
                return null;
            }
            final net.minecraft.server.MinecraftServer server = sp.getServer();
            if (server == null) {
                return null;
            }
            final List<String> out = new ArrayList<>();
            for (net.minecraft.advancements.Advancement a : server.getAdvancements().getAllAdvancements()) {
                if (a != null && a.getId() != null) {
                    out.add(a.getId().toString());
                }
            }
            java.util.Collections.sort(out);
            return out;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} advancement_registry_failed err={}", ShanhaiSaveEditFlow.PREFIX, t.toString());
            return null;
        }
    }

    /**
     * 查一条成就定义的全部条件名（这就是"游戏里显示未完成"的根治点：criteria 必须写满）。
     *
     * @return 空表 = 游戏里没有这条成就（面板会拒绝，并说明原因）
     */
    private List<String> criteriaOfAdvancement(String id) {
        try {
            final ServerPlayer sp = (ServerPlayer) this.player;
            final net.minecraft.server.MinecraftServer server = sp.getServer();
            if (server == null) {
                return List.of();
            }
            final ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                return List.of();
            }
            final net.minecraft.advancements.Advancement adv = server.getAdvancements().getAdvancement(rl);
            if (adv == null) {
                return List.of();
            }
            final List<String> out = new ArrayList<>(adv.getCriteria().keySet());
            java.util.Collections.sort(out);
            return out;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} advancement_lookup_failed id={} err={}",
                    ShanhaiSaveEditFlow.PREFIX, id, t.toString());
            return List.of();
        }
    }

    // ================================================================ 第 ⑥ 页按钮（统计）

    /** 「列出」：只列一个分类（数据层的 filter），留空 = {@code minecraft:custom}。 */
    private void onListStats() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = ctxOrStatus();
        if (ctx == null) {
            return;
        }
        this.statsPage = 0;
        this.listStatsPage(ctx);
    }

    /** 按当前 page / 分类扫一次盘，把"这一页"推给客户端（翻页在数据层已过滤好的列表上切一段）。 */
    private void listStatsPage(ShanhaiSaveEditRunContext ctx) {
        final String cat = text(this.statsFilterField);
        final java.nio.file.Path file = ShanhaiStatsEdit.fileOf(ctx.worldDir, ctx.playerUuid);
        final ShanhaiStatsEdit.Listing listing = ShanhaiStatsEdit.listFile(file,
                cat.isEmpty() ? ShanhaiStatsEdit.DEFAULT_CATEGORY : cat,
                ShanhaiSaveEditStatsUi.LIST_CAP);
        this.statsListing = listing;
        final List<ShanhaiStatsEdit.Entry> entries = listing.ok() ? listing.entries() : List.of();
        final int matched = entries.size();
        final int page = ShanhaiSaveEditLayout.clampPage(this.statsPage, matched, LP_ROWS);
        this.statsPage = page;
        this.statsSummary = ShanhaiSaveEditStatsUi.summaryLine(listing, cat);
        this.statsPageText = ShanhaiSaveEditStatsUi.pageText(page, matched);
        this.statsPageCountText = ShanhaiSaveEditStatsUi.pageCountText(page, matched);
        this.pushStatsRows();
        this.holder.setStatus(listing.ok()
                ? "已列出统计：" + listing.count() + " 项（" + listing.category() + " · "
                + ShanhaiSaveEditStatsUi.pageText(page, matched) + "）"
                : "列不出来：" + listing.error());
        ShanhaiMod.LOGGER.info("{} panel_stats_list player={} page={} filter=\"{}\" {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, page + 1, cat, listing.line());
    }

    /** 「◀上一页 / 下一页▶」（统计）：服务端换页并重推。 */
    private void onStatsPage(int delta) {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null || this.statsListing == null || !this.statsListing.ok()) {
            this.holder.setStatus("§c先点一次「列出」再翻页");
            return;
        }
        final int matched = this.statsListing.entries() == null ? 0 : this.statsListing.entries().size();
        final int want = ShanhaiSaveEditLayout.clampPage(this.statsPage + delta, matched, LP_ROWS);
        if (want == this.statsPage) {
            this.holder.setStatus("已经是" + (delta < 0 ? "第 1 页" : "最后一页")
                    + "（" + ShanhaiSaveEditStatsUi.pageText(want, matched) + "）");
            return;
        }
        this.statsPage = want;
        this.listStatsPage(ctx);
        ShanhaiMod.LOGGER.info("{} panel_stats_page player={} page={}/{}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, want + 1,
                ShanhaiSaveEditLayout.pageCount(matched, LP_ROWS));
    }

    /** 把"这一页"的 7 行原料推给客户端那份（客户端自己拼中文名；空 = 全清空）。 */
    private void pushStatsRows() {
        final List<ShanhaiStatsEdit.Entry> all =
                this.statsListing == null || !this.statsListing.ok() || this.statsListing.entries() == null
                        ? List.of() : this.statsListing.entries();
        final List<ShanhaiStatsEdit.Entry> rows = ShanhaiSaveEditStatsUi.pageRows(all, this.statsPage);
        for (int i = 0; i < this.statsRows.length; i++) {
            final StatsRowButton row = this.statsRows[i];
            if (row == null) {
                continue;
            }
            if (i >= rows.size()) {
                row.clearRow();
                continue;
            }
            final ShanhaiStatsEdit.Entry e = rows.get(i);
            row.pushRow(true, e.category(), e.key(), e.value());
        }
    }

    /** 「清空」：清分类框与列表（回到第 1 页）。 */
    private void onClearStatsFilter() {
        if (this.statsFilterField != null) {
            this.statsFilterField.setCurrentString("");
        }
        if (this.remote) {
            return;
        }
        this.pushText(this.statsFilterField, "");
        this.statsPage = 0;
        this.statsListing = null;
        this.statsSummary = ShanhaiSaveEditStatsUi.summaryLine(null, "");
        this.statsPageText = ShanhaiSaveEditStatsUi.pageText(0, 0);
        this.statsPageCountText = ShanhaiSaveEditStatsUi.pageCountText(0, 0);
        this.pushStatsRows();
        this.holder.setStatus("已清空分类与列表（留空 = minecraft:custom）");
    }

    /**
     * 点了第 {@code index} 行：进详情（两侧各跑一次：客户端先弹覆盖层，服务端灌读数）。
     */
    private void onClickStatsRow(int index) {
        if (this.statsOverlay == null) {
            return;
        }
        if (!isServerSide()) {
            this.statsOverlay.openPlaceholder();
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        final List<ShanhaiStatsEdit.Entry> all =
                this.statsListing == null || !this.statsListing.ok() || this.statsListing.entries() == null
                        ? List.of() : this.statsListing.entries();
        final List<ShanhaiStatsEdit.Entry> pageRows = ShanhaiSaveEditStatsUi.pageRows(all, this.statsPage);
        if (ctx == null || index < 0 || index >= pageRows.size()) {
            this.holder.setStatus("§c先点一次「列出」（这一行的列表已经过期了）");
            this.statsOverlay.openPlaceholder();
            this.statsOverlay.model().setFeedback("§c这一行已经过期：重新点一次「列出」");
            return;
        }
        final ShanhaiStatsEdit.Entry e = pageRows.get(index);
        final ShanhaiSaveEditStatsUi.Detail detail = ShanhaiSaveEditStatsUi.describe(e, all.size());
        this.statsOverlay.openDetail(detail);
        this.holder.setStatus("读了 " + detail.cat() + " / " + detail.statKey() + " = " + detail.value()
                + " · 填新值后点「写入」直接写");
        ShanhaiMod.LOGGER.info("{} panel_stats_open player={} cat={} key={} value={}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, detail.cat(), detail.statKey(), detail.value());
    }

    /**
     * 覆盖层「写入」：<b>一次点击就是一次真做</b>（预演只当读数与拒绝理由用，过了就直接写）；
     * 窗口没开就暂存进 holder（等「确认修改并重载」在停服窗口里由 ⑦ 段落盘）。
     *
     * <p>🔴 没有"再点一次"（用户 2026-11-15：「除非我指定需要二次确认，否则都不加」）。
     */
    void onStatsOverlayWrite() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditStatsUi.Model model = this.statsOverlay.model();
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null || model.detail() == null) {
            model.setFeedback("§c还没选中一条统计（先点一行）");
            return;
        }
        model.setNewValue(this.statsOverlay.readValue());
        final boolean clear = ShanhaiStatsEdit.ACTION_CLEAR.equals(model.action());
        final Long value = clear ? null : ShanhaiStatsEdit.parseValue(model.newValue());
        if (!clear && value == null) {
            model.setFeedback("§c「设值」要填一个非负整数（现在是「" + model.newValue() + "」）");
            return;
        }
        final ShanhaiStatsEdit.Request req = new ShanhaiStatsEdit.Request(model.detail().cat(),
                model.detail().statKey(), clear ? ShanhaiStatsEdit.ACTION_CLEAR : ShanhaiStatsEdit.ACTION_SET,
                value);
        final java.nio.file.Path file = ShanhaiStatsEdit.fileOf(ctx.worldDir, ctx.playerUuid);
        final ShanhaiSaveEditStatsUi.Preview preview = ShanhaiSaveEditStatsUi.preview(file, req);
        model.setPreview(preview);
        if (!preview.ok()) {
            return;                      // 预演不过 ⇒ 一个字节都不写
        }
        model.setFeedback("§e正在写…");
        final String stamp = ShanhaiSaveEditStorage.stamp(System.currentTimeMillis());
        final ShanhaiSaveEditStatsUi.Apply apply = ShanhaiSaveEditStatsUi.writeGated(
                ctx.worldDir, ctx.playerUuid, req, ctx.backupRoot, stamp, ShanhaiSaveEditFlow.current());
        if (!apply.permitted()) {
            this.holder.setStagedStats(req);
            final String staged = "§e已暂存（点右上「确认修改并重载」才真落盘）：" + req.line()
                    + " · " + stripColors(ShanhaiSaveEditStatsUi.windowClosed(ShanhaiSaveEditFlow.current()));
            model.setFeedback(staged);
            this.holder.setStatus(staged);
            ShanhaiMod.LOGGER.info("{} panel_stats_staged player={} {} disk_written=0 (窗口未开)",
                    ShanhaiSaveEditFlow.PREFIX, ctx.playerName, req.line());
            return;
        }
        final String line = apply.ok()
                ? "§a已写：" + req.cat() + " / " + req.statKey() + (clear ? "（整条删）" : " = " + req.value())
                + " §8(" + preview.changed().size() + " 处改动，已回读校验)"
                : "§c没写进去：" + (apply.write() == null || apply.write().error() == null
                        ? "（没给原因）" : apply.write().error());
        model.setFeedback(line);
        this.holder.setStatus(line);
        ShanhaiMod.LOGGER.info("{} panel_stats_write player={} ok={} {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, apply.ok(),
                apply.write() == null ? "-" : apply.write().line());
        if (apply.ok()) {
            this.refreshStatsAfterWrite(req.statKey(), line);
        }
    }

    /** 覆盖层「动作：设值 / 清零」：两侧各翻自己那份（点一下两侧都翻）。 */
    void onStatsOverlayCycleAction() {
        if (this.statsOverlay == null) {
            return;
        }
        final String now = this.statsOverlay.model().cycleAction();
        if (!this.remote) {
            this.holder.setStatus("统计动作 = "
                    + (ShanhaiStatsEdit.ACTION_CLEAR.equals(now) ? "清零（整条删掉）" : "设值（写成一个整数）"));
            ShanhaiMod.LOGGER.info("{} panel_stats_mode player={} mode={}",
                    ShanhaiSaveEditFlow.PREFIX, ctxPlayerName(), now);
        }
    }

    /** 覆盖层「取消」：什么都不改。 */
    void onStatsOverlayCancel() {
        if (this.statsOverlay != null) {
            this.statsOverlay.close();
        }
        if (!this.remote) {
            this.holder.setStatus("已取消（一个字节都没写）");
        }
    }

    /** 写完把列表与覆盖层刷新一次（读数可能变了）。 */
    private void refreshStatsAfterWrite(String key, String feedback) {
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            return;
        }
        this.listStatsPage(ctx);
        final List<ShanhaiStatsEdit.Entry> all =
                this.statsListing == null || this.statsListing.entries() == null
                        ? List.of() : this.statsListing.entries();
        ShanhaiStatsEdit.Entry found = null;
        for (ShanhaiStatsEdit.Entry e : all) {
            if (key != null && key.equals(e.key())) {
                found = e;
                break;
            }
        }
        if (found == null) {
            this.statsOverlay.close();
            this.holder.setStatus(feedback);
            return;
        }
        this.statsOverlay.openDetail(ShanhaiSaveEditStatsUi.describe(found, all.size()));
        this.statsOverlay.model().setFeedback(feedback);
        this.holder.setStatus(feedback);
    }

    /** 玩家名（日志用；拿不到 ⇒ {@code ?}）。 */
    private String ctxPlayerName() {
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx != null && ctx.playerName != null && !ctx.playerName.isEmpty()) {
            return ctx.playerName;
        }
        return this.player == null ? "?" : this.player.getName().getString();
    }

    // ================================================================ 第 ⑦ 页（存档体检 · 白名单之外）

    /**
     * 第 ⑦ 页：骨架照第 ④⑤⑥ 页（7 行列表 + 翻页 + 搜索），动作只剩两个：
     * 「重新扫描」（只读）与「删除选中项」（🔴 不弹二次确认；删前进回收目录，可撤回）。
     *
     * <p>🔴 判据只立在世界数据布局本身：<b>白名单之外的一切</b>（文件与目录）都是异常数据，
     * 可逐条删除。这一页<b>不认</b>任何具体模组名 / 实体 id / 文件名 / 字段名。
     */
    private void buildAuditPage() {
        final int p = PAGE_AUDIT;
        for (Slot slot : auditSlots()) {
            page(p, auditWidget(slot));
        }
    }

    /** 照一个槽位造第 ⑦ 页的控件（{@code key} 决定它是谁、要挂什么动作）。 */
    private Widget auditWidget(Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "subtitle":
                return new LabelWidget(x, y,
                        fit("§77 存档体检：白名单之外的文件/目录都算异常，可逐条删除", w))
                        .setClientSideWidget()
                        .setHoverTooltips(Component.literal("§e判据§7：只认原版世界布局（level.dat / region / "
                                + "entities / poi / data / datapacks / playerdata / advancements / stats / "
                                + "dimensions / DIM-1 / DIM1 / server-resources）。此外的一切 —— 不管它叫什么名字"
                                + " —— 都列出来并可删"));
            case "filter.label":
                return new LabelWidget(x, y, "搜索").setClientSideWidget()
                        .setHoverTooltips(Component.literal("§e搜索§7：按相对路径的子串过滤"
                                + "（例如 omnimob / somemod / weird）"));
            case "filter.field":
                this.auditFilterField = fillField(x, y, w, 96);
                return this.auditFilterField;
            case "rescan.button":
                return button(x, y, w, h, "重新扫描", this::onRescanAudit);
            case "drop.button":
                this.auditDropButton = new AuditButton(x, y, w, h, "删除选中项", this::onStageAuditDrop);
                return this.auditDropButton;
            case "summary.label":
                return label(x, y, "概况");
            case "summary.value":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(() -> fit(this.auditSummary, w)));
            case "row": {
                final int idx = slot.index();
                final AuditRowButton.Cell cell = new AuditRowButton.Cell(idx);
                final AuditRowButton row = new AuditRowButton(x, y, w, h, cell,
                        () -> this.onClickAuditRow(idx));
                this.auditRows[idx] = row;
                return row;
            }
            case "page.prev":
                return button(x, y, w, h, "◀上一页", () -> this.onAuditPage(-1));
            case "page.label":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(() -> fit(this.auditPageText, w)));
            case "page.next":
                return button(x, y, w, h, "下一页▶", () -> this.onAuditPage(1));
            case "page.count":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(() -> fit(this.auditPageCountText, w)));
            case "selected":
                return new LabelWidget(x, y, ShanhaiLdlText.sup(() -> fit(this.auditSelLine, w)));
            default:
                throw new IllegalStateException("第 ⑦ 页的槽位没被处理：" + slot.key());
        }
    }

    /**
     * 「重新扫描」：服务端只读扫一遍<b>白名单之外的一切</b>（文件 + 目录），结果推给客户端。
     *
     * <p>目录只列它自己一条（展开/删除时按整棵子树算），所以清单长度 ≈ 异常根的个数。
     */
    private void onRescanAudit() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = ctxOrStatus();
        if (ctx == null) {
            return;
        }
        final ShanhaiSaveEditAuditScan.Report report;
        try {
            report = ShanhaiSaveEditAuditScan.scan(ctx.worldDir);
        } catch (Throwable t) {
            this.holder.setStatus("§c体检扫描失败：" + t);
            ShanhaiMod.LOGGER.warn("{} panel_audit_scan_failed player={} err={}",
                    ShanhaiSaveEditFlow.PREFIX, ctx.playerName, t.toString(), t);
            return;
        }
        this.auditReport = report;
        this.auditAll = report.filter(text(this.auditFilterField));
        this.auditPage = 0;
        this.auditSelected = 0;
        renderAuditPage();
        ShanhaiMod.LOGGER.info("{} panel_audit_scan player={} filter=\"{}\" {}",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, text(this.auditFilterField),
                report.summaryLine());
        for (String line : report.logLines(50)) {
            ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX, line);
        }
        this.holder.setStatus("已扫描：" + report.panelSummary()
                + "（选一条 →「删除选中项」→「确认修改并重载」才真删）");
    }

    /** 「◀上一页 / 下一页▶」：服务端换页并重推。 */
    private void onAuditPage(int delta) {
        if (!isServerSide()) {
            return;
        }
        if (this.auditAll.isEmpty()) {
            this.holder.setStatus("§c先点一次「重新扫描」再翻页");
            return;
        }
        final int want = ShanhaiSaveEditLayout.clampPage(this.auditPage + delta, this.auditAll.size(), AU_ROWS);
        if (want == this.auditPage) {
            this.holder.setStatus("已经是" + (delta < 0 ? "第 1 页" : "最后一页")
                    + "（共 " + this.auditAll.size() + " 条）");
            return;
        }
        this.auditPage = want;
        renderAuditPage();
    }

    /** 点了第 {@code index} 行：选中它（客户端那次什么都不做，等服务端推字）。 */
    private void onClickAuditRow(int index) {
        if (!isServerSide()) {
            return;
        }
        final int abs = this.auditPage * AU_ROWS + index;
        if (abs < 0 || abs >= this.auditAll.size()) {
            this.holder.setStatus("§c这一行已经过期：先点一次「重新扫描」");
            return;
        }
        this.auditSelected = abs;
        renderAuditPage();
    }

    /**
     * 把"扫描结果 + 选中项 + 当前页"渲染成 概况 / 7 行 / 翻页 / 选中行，并把「删除选中项」的
     * 可用状态推给客户端。
     *
     * <p>🔴 全在服务端算：客户端那份只有被 push 的字面串（它没有扫描结果）。
     */
    private void renderAuditPage() {
        final int total = this.auditAll.size();
        final int pages = ShanhaiSaveEditLayout.pageCount(total, AU_ROWS);
        this.auditPage = ShanhaiSaveEditLayout.clampPage(this.auditPage, total, AU_ROWS);
        this.auditSelected = total <= 0 ? 0 : Math.max(0, Math.min(total - 1, this.auditSelected));
        final int from = Math.min(total, this.auditPage * AU_ROWS);
        final List<ShanhaiSaveEditAuditScan.Item> rows = auditPageRows();
        final int to = from + rows.size();
        // 7 行：选中那条带 ▶（行字整串由服务端算，客户端只负责画）
        for (int i = 0; i < this.auditRows.length; i++) {
            final AuditRowButton row = this.auditRows[i];
            if (row == null) {
                continue;
            }
            if (i >= rows.size()) {
                row.clearRow();
                continue;
            }
            final int abs = from + i;
            row.pushRow((abs == this.auditSelected ? "§e▶ " : "§8  ") + rows.get(i).rowText(abs));
        }
        // 概况：共 N 条 · 文件 a · 目录 b · 文本 c · 二进制 d（截断时带「已截断」）
        String summary = this.auditReport == null
                ? "§8还没扫描（点「重新扫描」）" : this.auditReport.panelSummary();
        final String filter = text(this.auditFilterField);
        if (this.auditReport != null && !filter.isEmpty()) {
            summary += " §8· 搜索「" + filter + "」命中 " + total + " 条";
        }
        this.auditSummary = summary
                + (total > AU_ROWS ? " §8(第 " + (from + 1) + "-" + to + " 条)" : "");
        this.auditPageText = "第 " + (this.auditPage + 1) + "/" + pages + " 页";
        this.auditPageCountText = "共 " + total + " 条 · 本页 " + (total <= 0 ? "0-0" : (from + 1) + "-" + to);
        final ShanhaiSaveEditAuditScan.Item sel = total <= 0 ? null : this.auditAll.get(this.auditSelected);
        this.auditSelLine = sel == null
                ? "§8没有选中项（点一行选中它，再点「删除选中项」）"
                : "§7" + sel.detailText(this.auditSelected, total);
        pushAuditDropActive(sel != null && sel.deletable());
    }

    /** 服务端把「删除选中项」能不能点推给客户端（灰按钮 + tooltip）。 */
    private void pushAuditDropActive(boolean can) {
        if (this.auditDropButton == null) {
            return;
        }
        this.auditDropButton.pushActive(can);
    }

    /** 当前这一页的 7 条原料（服务端）。 */
    private List<ShanhaiSaveEditAuditScan.Item> auditPageRows() {
        final int from = Math.min(this.auditAll.size(), this.auditPage * AU_ROWS);
        final List<ShanhaiSaveEditAuditScan.Item> out = new ArrayList<>();
        for (int i = from; i < Math.min(this.auditAll.size(), from + AU_ROWS); i++) {
            out.add(this.auditAll.get(i));
        }
        return out;
    }

    /**
     * 「删除选中项」：只<b>暂存</b>，真落盘在「确认修改并重载」之后的停服窗口里
     * （{@link ShanhaiSaveEditWindow} 第 ⑧ 段：复制到回收目录 → 删 → 回读）。
     *
     * <p>🔴 不弹二次确认（用户明确讨厌自加的确认弹窗）：可撤回靠回收目录，不靠弹窗。
     */
    private void onStageAuditDrop() {
        if (!isServerSide()) {
            return;
        }
        final ShanhaiSaveEditAuditScan.Item item = this.auditAll.isEmpty() ? null
                : this.auditAll.get(Math.max(0, Math.min(this.auditAll.size() - 1, this.auditSelected)));
        if (item == null) {
            this.holder.setStatus("§c先在清单里点一条（或者先点「重新扫描」）");
            return;
        }
        if (!item.deletable()) {
            this.holder.setStatus("§c这条不是普通文件/目录（符号链接或特殊文件），不动它：" + item.rel());
            return;
        }
        final ShanhaiSaveEditAuditFix.Request req = new ShanhaiSaveEditAuditFix.Request(item.rel());
        try {
            req.validate();
        } catch (IllegalArgumentException iae) {
            this.holder.setStatus("§c" + iae.getMessage());
            return;
        }
        this.holder.setStagedAudit(req);
        this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_STAGED + "删除「" + item.rel() + "」（"
                + item.sizeText() + "）· 点「确认修改并重载」才真删，删前进回收目录可撤回");
        ShanhaiMod.LOGGER.info("{} panel_staged_audit player={} {} kind={} bytes={} disk_written=0 (窗口未开)",
                ShanhaiSaveEditFlow.PREFIX, ctxPlayerName(), req.line(),
                item.directory() ? "dir" : "file", item.bytes());
    }

    // ================================================================ 第 ① 页：物品栏覆盖层（41 格 / 末影箱）

    /**
     * 页①「打开背包格」：两侧各跑一次。
     *
     * <p>客户端那次<b>先把覆盖层弹出来</b>（此时还没有内容），服务端那次<b>读盘</b>
     * （{@link ShanhaiSaveEditInventoryUi#read} → {@code readInventory}）再把 41 格推过来
     * —— 与第 ④⑤⑥ 页"点一行先弹层、服务端随后灌读数"完全同一条路子。
     */
    void onInventoryOpen() {
        if (this.invOverlay == null) {
            return;
        }
        if (!isServerSide()) {
            this.invOverlay.openPlaceholder();
            this.invOverlay.model().setFeedback("正在读背包…");
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.invOverlay.openWith(null);
            this.invOverlay.model().setFeedback("§c拿不到存档路径：读不了 playerdata");
            return;
        }
        final ShanhaiSaveEditInventoryUi.Grid data =
                ShanhaiSaveEditInventoryUi.read(ctx.playerDataFile, false);
        this.invOverlay.openWith(data);
        this.invOverlay.clearCount();
        this.invOverlay.model().setStagedLine(
                ShanhaiSaveEditInventoryUi.stagedLine(this.holder.staged(), false));
        this.invOverlay.model().setStagedSlots(
                ShanhaiSaveEditInventoryUi.stagedSlots(this.holder.staged(), false));
        this.invOverlay.pushStaged();
        this.invOverlay.model().setFeedback(data.used() == 0
                ? "读了背包 41 格：这一侧是空的（点一格可以「替换」放东西）"
                : "读了背包 41 格 · 有东西 " + data.used() + " 格 · 点一格再选动作");
        ShanhaiMod.LOGGER.info("{} panel_inv_open player={} file={} {} disk_written=0",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, ctx.playerDataFile.getFileName(), data.line());
    }

    /**
     * 覆盖层里点了第 {@code index} 格。
     *
     * <p>两侧都记（客户端那份负责"看起来选中了"，服务端那份决定"动作能不能做"）；
     * 客户端那一下会走 {@code writeClientAction} 到服务端，所以两边选的是同一格。
     */
    void onInventoryCell(int index) {
        if (this.invOverlay == null) {
            return;
        }
        this.invOverlay.model().select(index);
        if (!isServerSide()) {
            return;
        }
        this.invOverlay.model().setFeedback("选中了 " + ShanhaiSaveEditInventoryUi.cellName(
                this.invOverlay.model().ender(), index) + "（删 / 改数量 / 替换都作用于这一格）");
    }

    /** 客户端在选物器里点了一格：id 由客户端给出（服务端没有语言表，"第几格"这种口径对不上）。 */
    void onInventoryPickCell(String id) {
        if (this.invOverlay == null) {
            return;
        }
        this.invOverlay.model().setPendingPickId(id);
        if (!isServerSide()) {
            return;
        }
        this.invOverlay.model().setFeedback("已选：" + id + " · 按「接受」写进这一格");
    }

    /** 🔴 暂时把玩家的编辑对象拿过来（没有就新建一个并挂进 holder）。 */
    private ShanhaiPlayerDataEdit stagedEdit() {
        ShanhaiPlayerDataEdit edit = this.holder.staged();
        if (edit == null) {
            edit = new ShanhaiPlayerDataEdit();
            this.holder.setStaged(edit);
        }
        return edit;
    }

    /**
     * 先在<b>一次性探针</b>上试这条补丁，通过了才写到真正暂存的那份上。
     *
     * <p>为什么：{@link ShanhaiPlayerDataEdit} 的 {@code putPatch} 会把"槽号非法 / 数量越界 /
     * 注册名形状不对"记进 {@code edit.errors}，而 {@code applyPlayerOnly} 见到非空 errors 就
     * <b>一个字节都不写</b> —— 一条坏补丁会把整份暂存毒掉。分开试一遍就不会。
     */
    private boolean stageSafely(java.util.function.Consumer<ShanhaiPlayerDataEdit> op) {
        final ShanhaiPlayerDataEdit probe = new ShanhaiPlayerDataEdit();
        op.accept(probe);
        if (!probe.errors.isEmpty()) {
            this.invOverlay.model().setFeedback("§c" + String.join("／", probe.errors));
            return false;
        }
        op.accept(stagedEdit());
        return true;
    }

    /** 暂存成功之后：刷新"已暂存"那一行 + 红点，并把状态行/日志都写清楚（**没有写盘**）。 */
    private void afterStage(String what) {
        final ShanhaiSaveEditInventoryUi.Model model = this.invOverlay.model();
        final ShanhaiPlayerDataEdit edit = this.holder.staged();
        model.setStagedLine(ShanhaiSaveEditInventoryUi.stagedLine(edit, model.ender()));
        model.setStagedSlots(ShanhaiSaveEditInventoryUi.stagedSlots(edit, model.ender()));
        this.invOverlay.pushStaged();
        final String line = "§e已暂存（点右上「确认修改并重载」才真落盘）：" + what;
        model.setFeedback(line);
        this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_STAGED + what);
        ShanhaiMod.LOGGER.info("{} panel_inv_staged player={} side={} what={} staged=\"{}\" disk_written=0 (窗口未开)",
                ShanhaiSaveEditFlow.PREFIX, ctxPlayerName(), model.modeName(), what,
                ShanhaiSaveEditInventoryUi.stagedLine(edit, model.ender()));
    }

    /** 覆盖层「删除这一格」：只把这一格的补丁塞进暂存（其余 40 格一个字节都不碰）。 */
    void onInventoryDelete() {
        if (!isServerSide() || this.invOverlay == null) {
            return;
        }
        final ShanhaiSaveEditInventoryUi.Model model = this.invOverlay.model();
        final int index = model.selected();
        if (index < 0) {
            model.setFeedback("§c先点一格再按「删除这一格」");
            return;
        }
        final boolean ender = model.ender();
        final int slot = ShanhaiSaveEditInventoryUi.slotAt(ender, index);
        final ShanhaiSaveEditInventoryUi.Cell cell = model.cell(index);
        if (cell == null || cell.empty()) {
            model.setFeedback("§c这一格本来就是空的，没有可删的");
            return;
        }
        if (!stageSafely(edit -> ShanhaiSaveEditInventoryUi.stageRemove(edit, ender, slot))) {
            return;
        }
        afterStage("删 " + ShanhaiSaveEditInventoryUi.cellName(ender, index)
                + "（" + cell.id() + "×" + cell.count() + "）");
    }

    /** 覆盖层「改成这个数量」：只改那一格的 {@code Count}（其余键一个字节都不动）。 */
    void onInventorySetCount() {
        if (!isServerSide() || this.invOverlay == null) {
            return;
        }
        final ShanhaiSaveEditInventoryUi.Model model = this.invOverlay.model();
        final int index = model.selected();
        if (index < 0) {
            model.setFeedback("§c先点一格再改数量");
            return;
        }
        final boolean ender = model.ender();
        final ShanhaiSaveEditInventoryUi.Cell cell = model.cell(index);
        if (cell == null || cell.empty()) {
            model.setFeedback("§c这一格是空的：空格子只能「替换」（数据层也拒绝给空格改数量）");
            return;
        }
        final String text = this.invOverlay.readCount();
        final String problem = ShanhaiSaveEditInventoryUi.countProblem(text);
        if (problem != null) {
            model.setFeedback("§c" + problem);
            return;
        }
        final int count = ShanhaiSaveEditInventoryUi.parseCount(text, cell.count());
        final int slot = ShanhaiSaveEditInventoryUi.slotAt(ender, index);
        if (!stageSafely(edit -> ShanhaiSaveEditInventoryUi.stageSetCount(edit, ender, slot, count))) {
            return;
        }
        afterStage(ShanhaiSaveEditInventoryUi.cellName(ender, index) + " 数量 " + cell.count() + " → " + count);
    }

    /** 覆盖层「替换：用手上那件」：把玩家主手那件原样写进这一格（连 tag 一起）。 */
    void onInventoryReplaceHand() {
        if (!isServerSide() || this.invOverlay == null) {
            return;
        }
        final ShanhaiSaveEditInventoryUi.Model model = this.invOverlay.model();
        final int index = model.selected();
        if (index < 0) {
            model.setFeedback("§c先点一格再替换");
            return;
        }
        final ItemStack hand = this.player == null ? ItemStack.EMPTY : this.player.getMainHandItem();
        if (hand == null || hand.isEmpty()) {
            model.setFeedback("§c手上是空的：先拿一件东西在手上，再按这个按钮");
            return;
        }
        final ResourceLocation id = ForgeRegistries.ITEMS.getKey(hand.getItem());
        if (id == null) {
            model.setFeedback("§c手上这件没有注册名，不敢写进存档");
            return;
        }
        final int count = countFromField(hand.getCount());
        if (count < 0) {
            return;                             // countFromField 已经写好反馈
        }
        final boolean ender = model.ender();
        final int slot = ShanhaiSaveEditInventoryUi.slotAt(ender, index);
        final byte[] tag = tagBytesOf(hand);
        if (!stageSafely(edit -> ShanhaiSaveEditInventoryUi.stageReplace(edit, ender, slot,
                id.toString(), count, tag))) {
            return;
        }
        afterStage(ShanhaiSaveEditInventoryUi.cellName(ender, index) + " 换成手上那件 "
                + id + "×" + count + (tag == null ? "" : "（带 tag）"));
    }

    /** 覆盖层选物器「接受」：把列表里选中的那个 id 写进这一格（tag 为空）。 */
    void onInventoryPickAccept() {
        if (!isServerSide() || this.invOverlay == null) {
            return;
        }
        final ShanhaiSaveEditInventoryUi.Model model = this.invOverlay.model();
        final int index = model.selected();
        if (index < 0) {
            model.setFeedback("§c先点一格再选物品");
            return;
        }
        final String id = model.pendingPickId();
        if (id.isEmpty()) {
            model.setFeedback("§c还没在列表里选物品（点一格选中再按「接受」）");
            return;
        }
        if (!ShanhaiPlayerDataEdit.isResourceLocation(id)) {
            model.setFeedback("§c\"" + id + "\" 不是合法注册名");
            return;
        }
        final int count = countFromField(1);
        if (count < 0) {
            return;
        }
        final boolean ender = model.ender();
        final int slot = ShanhaiSaveEditInventoryUi.slotAt(ender, index);
        if (!stageSafely(edit -> ShanhaiSaveEditInventoryUi.stageReplace(edit, ender, slot, id, count, null))) {
            return;
        }
        afterStage(ShanhaiSaveEditInventoryUi.cellName(ender, index) + " 换成 " + id + "×" + count);
    }

    /**
     * 左下角开关：换数据源（背包 ⇄ 末影箱）。
     *
     * <p>🔴 这就是"末影箱"的全部实现：切过去之后服务端<b>重新读另一个键</b>
     * （{@code readEnderItems} 而不是 {@code readInventory}），再把 27 格推回来；
     * 版式/格子控件一行都不变。
     */
    void onInventoryToggleMode() {
        if (this.invOverlay == null) {
            return;
        }
        final boolean ender = this.invOverlay.model().toggleMode();     // 两侧各翻自己那份
        if (!isServerSide()) {
            this.invOverlay.model().setFeedback("切到「" + (ender ? ShanhaiSaveEditInventoryUi.MODE_ENDER
                    : ShanhaiSaveEditInventoryUi.MODE_BACKPACK) + "」…");
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.invOverlay.model().setFeedback("§c拿不到存档路径：读不了另一侧");
            return;
        }
        final ShanhaiSaveEditInventoryUi.Grid data =
                ShanhaiSaveEditInventoryUi.read(ctx.playerDataFile, ender);
        this.invOverlay.applyGrid(data);
        this.invOverlay.clearCount();
        this.invOverlay.model().setStagedLine(
                ShanhaiSaveEditInventoryUi.stagedLine(this.holder.staged(), ender));
        this.invOverlay.model().setStagedSlots(
                ShanhaiSaveEditInventoryUi.stagedSlots(this.holder.staged(), ender));
        this.invOverlay.pushStaged();
        this.invOverlay.model().setFeedback("切到「" + this.invOverlay.model().modeName() + "」：读了 "
                + data.size() + " 格（有东西 " + data.used() + " 格）");
        ShanhaiMod.LOGGER.info("{} panel_inv_toggle player={} side={} {} disk_written=0",
                ShanhaiSaveEditFlow.PREFIX, ctx.playerName, this.invOverlay.model().modeName(), data.line());
    }

    /** 覆盖层「关闭」：什么都不改（暂存的那份留着，等「确认修改并重载」）。 */
    void onInventoryClose() {
        if (this.invOverlay != null) {
            this.invOverlay.close();
        }
        if (isServerSide()) {
            this.holder.setStatus("物品栏覆盖层已关闭（暂存的改动还在，点「确认修改并重载」才落盘）");
        }
    }

    /**
     * 「数量」格子里的字 ⇒ 数量（<b>留空 ⇒ {@code fallback}</b>；填了但不合法 ⇒ 写反馈并返回 -1）。
     */
    private int countFromField(int fallback) {
        final String text = this.invOverlay == null ? "" : this.invOverlay.readCount();
        if (text.isEmpty()) {
            return fallback;
        }
        final String problem = ShanhaiSaveEditInventoryUi.countProblem(text);
        if (problem != null) {
            this.invOverlay.model().setFeedback("§c" + problem);
            return -1;
        }
        return ShanhaiSaveEditInventoryUi.parseCount(text, fallback);
    }

    /**
     * 物品的 {@code tag} ⇒ NBT 字节（{@code null} = 这件东西没有 tag）。
     *
     * <p>🔴 走 {@link ShanhaiPlayerDataEditor#encodeTagBytes}（就是数据层
     * {@code decodeTagBytes} 的逆运算），<b>不</b>经 {@code ItemStack#save}：
     * 1.20.1 的 {@code ItemStack#save} 会按注册表重写 {@code id}、而且<b>根本不写 {@code Slot}</b>。
     */
    private static byte[] tagBytesOf(ItemStack stack) {
        try {
            final CompoundTag tag = stack.getTag();
            return tag == null ? null : ShanhaiPlayerDataEditor.encodeTagBytes(tag);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} panel_inv_tag_encode_failed err={}",
                    ShanhaiSaveEditFlow.PREFIX, t.toString());
            return null;
        }
    }

    /**
     * 把"上一次已经暂存的逐格补丁"并进这一份 edit。
     *
     * <p>为什么必须并：页① 的「写入(暂存)」会用 12 个输入框重新 parse 出一个<b>新</b>的
     * {@link ShanhaiPlayerDataEdit}，而物品栏覆盖层暂存的补丁在 {@code holder.staged()} 里
     * —— 不并的话"先删一格、再填血量、再点写入"就会把删的那一格<b>悄悄丢掉</b>。
     *
     * <p>🔴 S3 起用 {@code put}（不是 {@code putIfAbsent}）：同一格先后给两次，
     * <b>后改的盖前改的</b>（"先删、又改成改数量"是正常操作）。
     */
    private void mergeStagedSlots(ShanhaiPlayerDataEdit target) {
        final ShanhaiPlayerDataEdit staged = this.holder.staged();
        if (target == null || staged == null || staged == target || !staged.hasSlotPatches()) {
            return;
        }
        for (Map.Entry<Integer, ShanhaiPlayerDataEdit.SlotPatch> e : staged.inventoryPatches.entrySet()) {
            target.inventoryPatches.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<Integer, ShanhaiPlayerDataEdit.SlotPatch> e : staged.enderPatches.entrySet()) {
            target.enderPatches.put(e.getKey(), e.getValue());
        }
    }

    /**
     * 把这次 parse 出来的那份<b>并进</b>已经暂存的那一份（同类字段"后改的盖前改的"）。
     *
     * <p>为什么要并（用户原话：「我要暂存一直存到玩家关闭这个存档修改面板」）：
     * 「写入」是<b>累积</b>语义 —— "先暂存血量、再暂存饥饿"两条都要在；
     * 而"先填 20、又改成 40"只留 40。12 个框留空 = <b>这一项不动</b>（不是"清掉之前暂存的"）。
     *
     * @return 并完之后那一份（就是 {@code holder} 里那一个对象；没有旧的就返回 {@code fresh}）
     */
    private ShanhaiPlayerDataEdit mergeIntoStaged(ShanhaiPlayerDataEdit fresh) {
        final ShanhaiPlayerDataEdit staged = this.holder.staged();
        if (staged == null || staged == fresh) {
            return fresh;
        }
        if (fresh.health != null) {
            staged.health = fresh.health;
        }
        if (fresh.xpLevel != null) {
            staged.xpLevel = fresh.xpLevel;
        }
        if (fresh.foodLevel != null) {
            staged.foodLevel = fresh.foodLevel;
        }
        if (fresh.saturation != null) {
            staged.saturation = fresh.saturation;
        }
        if (fresh.gameMode != null) {
            staged.gameMode = fresh.gameMode;
        }
        if (fresh.dimension != null && !fresh.dimension.isEmpty()) {
            staged.dimension = fresh.dimension;
        }
        if (fresh.x != null) {
            staged.x = fresh.x;
        }
        if (fresh.y != null) {
            staged.y = fresh.y;
        }
        if (fresh.z != null) {
            staged.z = fresh.z;
        }
        if (fresh.yaw != null) {
            staged.yaw = fresh.yaw;
        }
        if (fresh.pitch != null) {
            staged.pitch = fresh.pitch;
        }
        if (fresh.clearSlots != null && fresh.clearSlots.length > 0) {
            staged.clearSlots = unionSlots(staged.clearSlots, fresh.clearSlots);
        }
        // 逐格补丁：新的盖旧的（同格后改的盖前改的）
        for (Map.Entry<Integer, ShanhaiPlayerDataEdit.SlotPatch> e : fresh.inventoryPatches.entrySet()) {
            staged.inventoryPatches.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<Integer, ShanhaiPlayerDataEdit.SlotPatch> e : fresh.enderPatches.entrySet()) {
            staged.enderPatches.put(e.getKey(), e.getValue());
        }
        return staged;
    }

    /** 槽号并集（升序、去重）。 */
    private static int[] unionSlots(int[] a, int[] b) {
        final java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        if (a != null) {
            for (int v : a) {
                set.add(v);
            }
        }
        if (b != null) {
            for (int v : b) {
                set.add(v);
            }
        }
        final int[] out = new int[set.size()];
        int i = 0;
        for (Integer v : set) {
            out[i++] = v;
        }
        return out;
    }

    // ================================================================ 第 ① 页按钮

    /** 「写入」：解析输入框 → <b>暂存</b>（🔴 不落盘；暂存活到"关闭面板"为止）。 */
    private void onStage() {
        if (this.remote || !(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final ShanhaiPlayerDataEdit fresh = this.parsePlayerFields();
        if (!fresh.errors.isEmpty()) {
            this.holder.setStatus("输入有问题：" + String.join("／", fresh.errors));
            return;
        }
        if (!fresh.hasPlayerFields()) {
            // 🔴 12 个框全空 ⇒ 只提示"没填要改的值"。
            //    <b>绝不动</b>已经暂存的逐格补丁（旧实现在这里 setStaged(null)，会把删的那一格悄悄清掉）。
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_NO_CHANGE
                    + "（已暂存的物品栏逐格改动不会被清掉）");
            return;
        }
        // S2c：把物品栏覆盖层暂存的那几张逐格补丁并回来（否则"先删一格、再填血量、再写入"会丢掉删的那格）
        this.mergeStagedSlots(fresh);
        // 再并进已经暂存的那一份（累积语义；后改的盖前改的）
        final ShanhaiPlayerDataEdit merged = this.mergeIntoStaged(fresh);
        this.holder.setStaged(merged);
        this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_STAGED + merged.describe());
        ShanhaiMod.LOGGER.info("{} panel_staged player={} staged=\"{}\" breakdown={} disk_written=0 (窗口未开)",
                ShanhaiSaveEditFlow.PREFIX, serverPlayer.getName().getString(), merged.describe(),
                ShanhaiSaveEditFactory.stagedBreakdown(this.holder));
    }

    /** 「确认修改并重载」：把七页的改动合成一个请求，走 save → stop → 窗口内改盘 → 直接重开。 */
    private void onConfirm() {
        // 🔴 确认流程一开始就把「关闭确认层」关掉、并让它忽略当前这一下点击。
        //    理由：这一层是"有暂存时关面板"的唯一确认（用户点名的），它平时常驻、asking==true 时
        //    整层吃点击。确认流程一旦启动，面板马上会被加载屏换掉 —— 此刻还挂着一个"要丢弃暂存吗"
        //    的弹层既没有意义，又可能在下一 tick 抢走点击。两侧各关自己那份。
        if (this.closeConfirm != null) {
            this.closeConfirm.cancelAsk("panel_confirm");
        }
        if (this.remote || !(this.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final ShanhaiSaveEditRunContext ctx = this.holder.ctx();
        if (ctx == null) {
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_FAILED + "拿不到存档路径");
            return;
        }
        // ── ① 玩家页
        ShanhaiPlayerDataEdit edit = this.parsePlayerFields();
        if (!edit.errors.isEmpty()) {
            this.holder.setStatus("输入有问题：" + String.join("／", edit.errors));
            return;
        }
        // 暂存那一份也要参与：输入框现在填的盖掉暂存的，暂存里没在框里的保持
        // （旧实现是"框空了才回退到暂存"，累积语义下必须两边都要）
        if (this.holder.staged() != null) {
            edit = this.mergeIntoStaged(edit);
        }
        // S2c：物品栏覆盖层暂存的逐格补丁也要参与这一次落盘
        this.mergeStagedSlots(edit);
        // ── ② 世界页
        final ShanhaiLevelDataEdit levelEdit = ShanhaiLevelDataEdit.parse(this.levelRaw());
        if (!levelEdit.errors.isEmpty()) {
            this.holder.setStatus("世界页有问题：" + String.join("／", levelEdit.errors));
            serverPlayer.displayClientMessage(Component.literal("§c[存档直改] 世界页："
                    + String.join("／", levelEdit.errors)), false);
            return;
        }
        if (!levelEdit.isEmpty()) {
            edit.levelEdit = levelEdit;
        }
        // ── ③ 方块页（填了坐标/方块/BE 才算）
        final ShanhaiRegionEdit.Request regionReq;
        try {
            regionReq = this.buildRegionRequest();
        } catch (IllegalArgumentException iae) {
            this.holder.setStatus("§c方块页：" + iae.getMessage());
            serverPlayer.displayClientMessage(Component.literal("§c[存档直改] 方块页："
                    + iae.getMessage()), false);
            return;
        }
        if (regionReq != null) {
            edit.regionRequest = regionReq;
        }
        // ── ④⑤⑥⑦ 页：暂存是<b>累积容器</b>（活到"关闭面板"）⇒ 这次要落的清单整批交出去，
        //    停服窗口里由 ShanhaiSaveEditWindow 逐条落盘（每一条各自 备份 + 原子写 + 回读；
        //    任一条失败就停手并报"已写几条"）。
        //    🔴 先清空再灌：`edit` 有可能<b>就是</b> holder 里那一份（上面 mergeIntoStaged 的返回值），
        //       不清就会在第二次点「确认」时把同一批请求灌两遍。
        edit.entityRequests.clear();
        edit.advancementRequests.clear();
        edit.statsRequests.clear();
        edit.auditRequests.clear();
        edit.entityPatchTargets.clear();
        edit.entityRequests.addAll(this.holder.stagedEntityRequests());
        edit.advancementRequests.addAll(this.holder.stagedAdvancementRequests());
        edit.statsRequests.addAll(this.holder.stagedStatsRequests());
        edit.auditRequests.addAll(this.holder.stagedAuditRequests());
        // ⑤b 第 ④ 页覆盖层「写入」那 10 项（"改"与"删"是两条：删走 entityRequests，改走这里）
        for (ShanhaiSaveEditFactory.Holder.EntityPatch entry : this.holder.stagedEntityPatchEntries()) {
            edit.entityPatchTargets.add(new ShanhaiPlayerDataEdit.EntityPatchTarget(
                    entry.dimension(), entry.uuid(), entry.patch()));
        }
        edit.absorbExtraErrors();
        if (!edit.errors.isEmpty()) {
            this.holder.setStatus("输入有问题：" + String.join("／", edit.errors));
            return;
        }
        if (edit.isEmpty()) {
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_NO_CHANGE
                    + "（第 ④~⑦ 页要先按「暂存 / 删…」才算）");
            return;
        }
        ShanhaiMod.LOGGER.info("{} panel_confirm player={} staged_items={} {} "
                        + "player=({}) entity={} advancement={} stats={} audit={} entity_patch={} level={} region={}",
                ShanhaiSaveEditFlow.PREFIX, serverPlayer.getName().getString(),
                ShanhaiSaveEditFactory.stagedCount(this.holder),
                ShanhaiSaveEditFactory.stagedBreakdown(this.holder),
                edit.hasPlayerFields(), edit.entityRequests.size(), edit.advancementRequests.size(),
                edit.statsRequests.size(), edit.auditRequests.size(), edit.entityPatchTargets.size(),
                edit.hasLevel(), edit.hasRegion());
        final String refused;
        try {
            refused = ShanhaiSaveEditBridge.confirm(serverPlayer, ctx, edit, this.holder);
        } catch (Throwable t) {
            // 🔴 兜底：Bridge 里已经在 begin() 之后整段 try 住了，这里再兜一次是为了"面板这一侧
            //    也绝不留锁"。现场（2026-11-15 14:13:03.744）：Bridge 里 confirm_edit 那句
            //    edit.describe() 抛 NPE，只有 mkButton 那层把它吞成 panel_button_failed，
            //    于是 flow 一直 active ⇒ 之后每一次确认都是 STATUS_BUSY。
            ShanhaiSaveEditFlow.releaseIfActive("panel_confirm_threw");
            final String line = ShanhaiSaveEditFlow.takeNotify();
            if (line != null && !line.isEmpty()) {
                ShanhaiMod.LOGGER.info("{} {}", ShanhaiSaveEditFlow.PREFIX, line);
            }
            ShanhaiMod.LOGGER.error("{} panel_confirm_threw err={}",
                    ShanhaiSaveEditFlow.PREFIX, t.toString(), t);
            this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_FAILED + "确认流程出错（" + t
                    + "），一个字节都没改");
            serverPlayer.displayClientMessage(Component.literal(
                    "§c[存档直改] 确认流程出错，一个字节都没改（看日志）"), false);
            return;
        }
        if (refused != null) {
            this.holder.setStatus(refused);
            serverPlayer.displayClientMessage(Component.literal("§c[存档直改] " + refused), false);
            return;
        }
        // 🔴 交出去之后立刻清暂存：这批改动已经整份进了 edit（`edit` 自己持着清单），
        //    而面板在世界重开后会重建 —— 留着会让"再点一次确认"重复落盘同一批。
        this.holder.clearStaged();
        this.holder.setStatus(ShanhaiSaveEditFactory.STATUS_RUNNING);
    }

    // ================================================================ 读输入框

    /** 第 ① 页 12 个输入框 → {@link ShanhaiPlayerDataEdit}。 */
    private ShanhaiPlayerDataEdit parsePlayerFields() {
        final Map<String, String> raw = new LinkedHashMap<>();
        raw.put("health", text(this.healthField));
        raw.put("xp", text(this.xpField));
        raw.put("food", text(this.foodField));
        raw.put("saturation", text(this.satField));
        raw.put("gamemode", text(this.modeField));
        raw.put("dimension", text(this.dimField));
        raw.put("x", text(this.xField));
        raw.put("y", text(this.yField));
        raw.put("z", text(this.zField));
        raw.put("yaw", text(this.yawField));
        raw.put("pitch", text(this.pitchField));
        // S2c：「清空槽位」那个输入框已经换成「打开背包格」按钮 ⇒ 这一项不再有界面入口
        //       （"清空某一格"由覆盖层里按格「删除这一格」做）。数据层的 clearSlots 原样保留，
        //       这里传空串就是"这一项没填"，与一期"留空＝不清"完全等价。
        raw.put("clear", "");
        return ShanhaiPlayerDataEdit.parse(raw);
    }

    /** 第 ② 页 21 个输入框 → 原始文本表（{@link ShanhaiLevelDataEdit#parse} 的输入）。 */
    private Map<String, String> levelRaw() {
        final Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, FillField> e : this.levelFields.entrySet()) {
            raw.put(e.getKey(), text(e.getValue()));
        }
        return raw;
    }

    /** 读第 ③ 页的 x/y/z（返回错误信息；成功返回 {@code null} 并填进 {@code out}）。 */
    private String readRegionCoords(int[] out) {
        final String xs = text(this.regionXField);
        final String ys = text(this.regionYField);
        final String zs = text(this.regionZField);
        if (xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) {
            return "X / Y / Z 三个都要填";
        }
        try {
            out[0] = Integer.parseInt(xs);
            out[1] = Integer.parseInt(ys);
            out[2] = Integer.parseInt(zs);
            return null;
        } catch (NumberFormatException nfe) {
            return "X / Y / Z 必须是整数（现在是 " + xs + " / " + ys + " / " + zs + "）";
        }
    }

    /**
     * 第 ③ 页 → {@link ShanhaiRegionEdit.Request}。
     *
     * @return {@code null} = 这一页什么都没填（也没有 BE 动作）
     * @throws IllegalArgumentException 这一页填了东西但不合法（<b>必须中断确认</b>，
     *                                  不许"报个状态然后把别的页悄悄写进去"）
     */
    private ShanhaiRegionEdit.Request buildRegionRequest() {
        final String dimRaw = text(this.regionDimField);
        final String dim = dimRaw.isEmpty() ? ShanhaiRegionEdit.DIM_OVERWORLD : dimRaw;
        final String blockId = text(this.regionBlockField);
        final String state = text(this.regionStateField);
        final String beNbt = text(this.regionBeField);
        final String beAction = this.holder.beAction();
        final boolean wantsBlock = !blockId.isEmpty();
        final boolean wantsBe = !ShanhaiRegionEdit.BE_NONE.equals(beAction);
        if (!wantsBlock && !wantsBe) {
            return null;                    // 什么都没填 ⇒ 这一页不参与
        }
        final int[] xyz = new int[3];
        final String coordErr = this.readRegionCoords(xyz);
        if (coordErr != null) {
            throw new IllegalArgumentException(coordErr);
        }
        if (ShanhaiRegionEdit.normalizeDimension(dim) == null) {
            throw new IllegalArgumentException("维度写法不认识：" + dim);
        }
        boolean blockHasBe = false;
        if (wantsBlock) {
            final ShanhaiChunkEditor.State st;
            try {
                st = ShanhaiRegionEdit.parseState(blockId, state);
            } catch (IllegalArgumentException iae) {
                throw new IllegalArgumentException("方块状态不合法：" + iae.getMessage());
            }
            if (!this.remote) {
                final BlockState blockState = lookupBlockState(st);
                if (blockState == null) {
                    throw new IllegalArgumentException("没有这个方块（或属性不对）：" + st);
                }
                blockHasBe = blockState.hasBlockEntity();
            }
        }
        if (wantsBe && ShanhaiRegionEdit.BE_REPLACE.equals(beAction)) {
            if (beNbt.isEmpty()) {
                throw new IllegalArgumentException("BE 模式是「替换」，但 BE NBT 是空的");
            }
            if (!this.remote) {
                try {
                    final net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.TagParser.parseTag(beNbt);
                    final String id = tag == null ? "" : tag.getString("id");
                    if (!ShanhaiChunkEditor.State.isBlockId(id)) {
                        throw new IllegalArgumentException("BE NBT 里的 id 不是合法的方块实体 id：「" + id + "」");
                    }
                    final ResourceLocation rl = new ResourceLocation(id);
                    if (!ForgeRegistries.BLOCK_ENTITY_TYPES.containsKey(rl)) {
                        throw new IllegalArgumentException("没有这个方块实体类型：" + id);
                    }
                } catch (IllegalArgumentException iae) {
                    throw iae;
                } catch (Throwable t) {
                    throw new IllegalArgumentException("BE NBT 解析失败：" + t.getMessage());
                }
            }
        }
        return new ShanhaiRegionEdit.Request(dim, xyz[0], xyz[1], xyz[2], blockId, state,
                wantsBe ? beAction : ShanhaiRegionEdit.BE_NONE, beNbt, blockHasBe);
    }

    /** 游戏内把 {@code id[props]} 解析成 {@link BlockState}（离线判据不走这条）。 */
    private static BlockState lookupBlockState(ShanhaiChunkEditor.State st) {
        try {
            final String serialized = st.toString().startsWith("minecraft:")
                    || st.toString().contains(":") ? st.toString() : "minecraft:" + st.toString();
            final var result = net.minecraft.commands.arguments.blocks.BlockStateParser
                    .parseForBlock(BuiltInRegistries.BLOCK.asLookup(), serialized, false);
            return result == null ? null : result.blockState();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 属性的 {@code k=v,k=v} 文本。 */
    private static String propsText(ShanhaiChunkEditor.State st) {
        if (st.props() == null || st.props().isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(st.props()).entrySet()) {
            sb.append(sb.length() == 0 ? "" : ",").append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String text(TextFieldWidget field) {
        final String s = field == null ? null : field.getCurrentString();
        return s == null ? "" : s.trim();
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    // ================================================================ 文本

    /**
     * 状态行<b>值</b>那一半。
     *
     * <p>🔴 只返回服务端权威的字面串；<b>不许</b>在这里出现 {@code Component.translatable}。
     * 永不返回 {@code null}（{@code LabelWidget.detectAndSendChanges} 会对 null NPE）。
     */
    private String statusValueText() {
        final String s = this.holder.status();
        final String shown = s == null || s.isEmpty() ? ShanhaiSaveEditFactory.STATUS_NO_READ : s;
        return ShanhaiLdlText.esc(fit("§f" + shown, STATUS_BUDGET_PX));
    }

    /**
     * 按<b>显示宽度</b>截断（中文 9 px/字、ASCII 6 px/字；{@code §x} 这种颜色码不计宽也不切断）。
     *
     * <p>与 {@code ShanhaiRealityInputPanel#fit} 同一套算法，多了一条"跳过 {@code §} 颜色码"
     * —— 因为本面板的摘要里 §-code 很多，按字符数硬截会把 {@code §} 和它的字截成两半。
     */
    static String fit(String text, int maxPx) {
        if (text == null) {
            return "";
        }
        int px = 0;
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i += 2;                  // 颜色码：不计宽、也不会被从中间截断
                continue;
            }
            final int w = c < 0x2E80 ? 6 : 9;
            if (px + w > maxPx) {
                return text.substring(0, i) + "…";
            }
            px += w;
            i++;
        }
        return text;
    }

    /** lang 键 → 当前语言下的字（客户端现算；服务端算出的是英文，只用于"两侧结构一致"）。 */
    private static String lang(String key) {
        try {
            return Component.translatable(key).getString();
        } catch (Throwable t) {
            return key;
        }
    }

    // ================================================================ 控件工厂

    /** ⚠️ 返回 {@code Widget}：{@code setClientSideWidget()} 的返回类型是 {@code Widget}，不是 {@code LabelWidget}。 */
    private static Widget label(int x, int y, String langKey) {
        return new LabelWidget(x, y, lang(langKey)).setClientSideWidget();
    }

    private TextFieldWidget field(int x, int y, int w, int maxChars) {
        final TextFieldWidget f = new TextFieldWidget(x, y, w, FIELD_H, null, null);
        f.setMaxStringLength(maxChars);
        return f;
    }

    /** 能推字的输入框（第 ③ 页与第 ② 页都用它；见 {@link FillField}）。 */
    private FillField fillField(int x, int y, int w, int maxChars) {
        final FillField f = new FillField(x, y, w, FIELD_H, null, null);
        f.setMaxStringLength(maxChars);
        return f;
    }

    /** 带文字的按钮。 */
    private ButtonWidget button(int x, int y, int w, int h, String text, Runnable action) {
        final String fixed = ShanhaiLdlText.esc(text);
        return button(x, y, w, h, () -> fixed, action);
    }

    /**
     * 带文字（动态供应）的按钮。
     *
     * <p>用 {@code TextTexture(Supplier<String>)} 而不是 {@code TextTexture(String)}：与工程既有
     * 面板同一条铁律（{@code TextTexture(String)} 那个重载在某些 LDLib 版本里让 {@code textSupplier}
     * 留在 null，服务端 tick 时 NPE）。
     *
     * <p>🔴 这个重载是 {@code static} 的：覆盖层（{@link ShanhaiSaveEditEntityOverlay}）也要建按钮，
     * 两处各写一份"必须传 Supplier"的规则迟早会有一处写错。
     */
    static ButtonWidget mkButton(int x, int y, int w, int h, Supplier<String> text, Runnable action) {
        return new ButtonWidget(x, y, w, h,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture((Supplier<String>) () -> ShanhaiLdlText.esc(text.get()))),
                clickData -> {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        ShanhaiMod.LOGGER.warn("{} panel_button_failed err={}",
                                ShanhaiSaveEditFlow.PREFIX, t.toString(), t);
                    }
                });
    }

    private ButtonWidget button(int x, int y, int w, int h, Supplier<String> text, Runnable action) {
        return mkButton(x, y, w, h, text, action);
    }

    /**
     * 能把文字<b>推给客户端</b>的输入框。
     *
     * <p>为什么需要它：LDLib 的 {@code TextFieldWidget} 只在自己有 {@code textSupplier} 时
     * 才会把值推给客户端（{@code detectAndSendChanges} 里 {@code writeUpdateInfo(1, …)}）；
     * 而没有 supplier 的输入框全是"客户端 → 服务端"单向。要给"读当前值回填"用，只能：
     * 服务端 {@code setCurrentString} + 手动 {@code writeUpdateInfo(1, buf.writeUtf(value))}
     * —— 客户端 {@code readUpdateInfo(1)} 认的就是这个包（字节码实证）。
     */
    static final class FillField extends TextFieldWidget {

        FillField(int x, int y, int w, int h, Supplier<String> text, java.util.function.Consumer<String> respond) {
            super(x, y, w, h, text, respond);
        }

        /** 🔴 只在服务端调：把 {@code text} 同时写到本侧控件与客户端那份。 */
        void pushText(String text) {
            final String v = text == null ? "" : text;
            setCurrentString(v);
            writeUpdateInfo(1, buf -> buf.writeUtf(v));
        }
    }

    /** 面板上的输入框键（日志/自检用）。 */
    public static List<String> levelFieldIds() {
        return new ArrayList<>(ShanhaiLevelDataSpec.ids());
    }

    /**
     * 第 ④ 页<b>一行的可点按钮</b>：点它 ⇒ 打开实体详情覆盖层。
     *
     * <h2>为什么不是 {@code LabelWidget}</h2>
     * 原来那 7 行是 {@code LabelWidget}（只看得见、点不了），玩家只能再手打 uuid。
     * 换成按钮之后，"看到哪一行就点哪一行"。
     *
     * <h2>🔴 为什么推的是"原料"而不是拼好的字</h2>
     * 行首那个名字必须<b>中文在前</b>（「运输矿车」，不是 {@code chest_minecart}），而
     * <b>名字只有客户端查得到</b>（服务端没有语言表）。所以：服务端用 update-info 78 把
     * 类型注册名 / 坐标 / uuid / 是否残留这<b>几样原料</b>推过来，客户端在 {@code TextTexture} 的
     * supplier 里用 {@link ShanhaiSaveEditEntityUi#rowText} <b>现拼</b>（每帧一次，查表很便宜）。
     * 这与 {@link FillField#pushText(String)} / {@link AuditButton#pushActive(boolean)} 是同一条通道。
     */
    static final class EntityRowButton extends ButtonWidget {

        /** 本条 update-info 的 id（1 被 {@code TextFieldWidget} 用掉、77 被 {@link AuditButton} 用掉）。 */
        static final int UPDATE_ROW = 78;

        /**
         * 这一行的<b>原料</b>（可变；由面板建好递进来）。
         *
         * <p>🔴 为什么是外部传进来的对象而不是本类字段：{@code super(...)} 里那个 supplier 在
         * <b>父类构造完成之前</b>就建好了，那时既不许引用 {@code this}、也不许读实例字段。
         */
        static final class Cell {
            final int index;
            boolean present;
            String typeId = "";
            String uuid = "";
            double x, y, z;
            boolean residue;

            Cell(int index) {
                this.index = index;
            }
        }

        private final Cell cell;

        EntityRowButton(int x, int y, int w, int h, Cell cell,
                        ShanhaiSaveEditEntityUi.Namer namer, Runnable action) {
            super(x, y, w, h,
                    new GuiTextureGroup(GuiTextures.BUTTON,
                            new TextTexture((Supplier<String>) () -> ShanhaiLdlText.esc(
                                    fit(cell.present
                                            ? ShanhaiSaveEditEntityUi.rowText(cell.index, cell.typeId,
                                            cell.x, cell.y, cell.z, cell.uuid, cell.residue, namer)
                                            : "", w - 8)))),
                    clickData -> {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            ShanhaiMod.LOGGER.warn("{} panel_row_click_failed err={}",
                                    ShanhaiSaveEditFlow.PREFIX, t.toString(), t);
                        }
                    });
            this.cell = cell;
        }

        /** 🔴 只在服务端调：把这一行的原料同步到客户端那份（客户端自己拼字）。 */
        void pushRow(boolean present, String typeId, double x, double y, double z,
                     String uuid, boolean residue) {
            this.cell.present = present;
            this.cell.typeId = typeId == null ? "" : typeId;
            this.cell.x = x;
            this.cell.y = y;
            this.cell.z = z;
            this.cell.uuid = uuid == null ? "" : uuid;
            this.cell.residue = residue;
            writeUpdateInfo(UPDATE_ROW, buf -> {
                buf.writeBoolean(this.cell.present);
                buf.writeUtf(this.cell.typeId);
                buf.writeDouble(this.cell.x);
                buf.writeDouble(this.cell.y);
                buf.writeDouble(this.cell.z);
                buf.writeUtf(this.cell.uuid);
                buf.writeBoolean(this.cell.residue);
            });
        }

        /** 清空这一行（空行点了没反应）。 */
        void clearRow() {
            pushRow(false, "", 0.0D, 0.0D, 0.0D, "", false);
        }

        /** 本侧的原料（客户端点行时先拿它当覆盖层标题的料）。 */
        Cell cell() {
            return this.cell;
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == UPDATE_ROW) {
                this.cell.present = buffer.readBoolean();
                this.cell.typeId = buffer.readUtf();
                this.cell.x = buffer.readDouble();
                this.cell.y = buffer.readDouble();
                this.cell.z = buffer.readDouble();
                this.cell.uuid = buffer.readUtf();
                this.cell.residue = buffer.readBoolean();
                return;
            }
            super.readUpdateInfo(id, buffer);
        }
    }

    /**
     * 第 ⑤ 页<b>一行的可点按钮</b>：点它 ⇒ 打开成就详情覆盖层。
     *
     * <h2>为什么是按钮而不是 {@code LabelWidget}</h2>
     * 与 {@link EntityRowButton} 同一条理由：玩家要"看到哪一行就点哪一行"，而不是再手打成就 id。
     *
     * <h2>🔴 为什么推的是"原料"而不是拼好的字</h2>
     * 行首那个名字必须<b>中文在前</b>（「石器时代」，不是 {@code story/mine_block}），而
     * <b>名字只有客户端查得到</b>（服务端没有语言表）。所以服务端用 update-info 81 把
     * 成就 id / 在不在游戏里 / 完成状态 / criteria 条数这<b>几样原料</b>推过来，
     * 客户端在 {@code TextTexture} 的 supplier 里用
     * {@link ShanhaiSaveEditAdvancementUi#rowText(int, String, boolean, boolean, int, boolean,
     * ShanhaiSaveEditAdvancementUi.TitleSource)} <b>现拼</b>（每帧一次，查表很便宜）。
     */
    static final class AdvancementRowButton extends ButtonWidget {

        /** 本条 update-info 的 id（1 被 {@code TextFieldWidget} 用掉、77/78 被第 ⑦/④ 页用掉、79/80 覆盖层）。 */
        static final int UPDATE_ROW = 81;

        /** 这一行的<b>原料</b>（可变；由面板建好递进来；{@code super(...)} 里不许引用 this）。 */
        static final class Cell {
            final int index;
            boolean present;
            String id = "";
            boolean known;
            boolean done;
            int criteria;

            Cell(int index) {
                this.index = index;
            }

            boolean hasRow() {
                return !this.id.isEmpty();
            }
        }

        private final Cell cell;

        AdvancementRowButton(int x, int y, int w, int h, Cell cell,
                             ShanhaiSaveEditAdvancementUi.TitleSource src, Runnable action) {
            super(x, y, w, h,
                    new GuiTextureGroup(GuiTextures.BUTTON,
                            new TextTexture((Supplier<String>) () -> ShanhaiLdlText.esc(
                                    fit(cell.hasRow()
                                            ? ShanhaiSaveEditAdvancementUi.rowText(cell.index, cell.id,
                                            cell.present, cell.done, cell.criteria, cell.known, src)
                                            : "", w - 8)))),
                    clickData -> {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            ShanhaiMod.LOGGER.warn("{} panel_adv_row_click_failed err={}",
                                    ShanhaiSaveEditFlow.PREFIX, t.toString());
                        }
                    });
            this.cell = cell;
        }

        /** 🔴 只在服务端调：把这一行的原料同步到客户端那份（客户端自己拼字）。 */
        void pushRow(boolean present, String id, boolean known, boolean done, int criteria) {
            this.cell.present = present;
            this.cell.id = id == null ? "" : id;
            this.cell.known = known;
            this.cell.done = done;
            this.cell.criteria = criteria;
            writeUpdateInfo(UPDATE_ROW, buf -> {
                buf.writeBoolean(this.cell.present);
                buf.writeUtf(this.cell.id);
                buf.writeBoolean(this.cell.known);
                buf.writeBoolean(this.cell.done);
                buf.writeVarInt(this.cell.criteria);
            });
        }

        /** 清空这一行（空行点了没反应）。 */
        void clearRow() {
            pushRow(false, "", false, false, 0);
        }

        /** 本侧的原料（判据/调试用）。 */
        Cell cell() {
            return this.cell;
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == UPDATE_ROW) {
                this.cell.present = buffer.readBoolean();
                this.cell.id = buffer.readUtf();
                this.cell.known = buffer.readBoolean();
                this.cell.done = buffer.readBoolean();
                this.cell.criteria = buffer.readVarInt();
                return;
            }
            super.readUpdateInfo(id, buffer);
        }
    }

    /**
     * 第 ⑥ 页<b>一行的可点按钮</b>：点它 ⇒ 打开统计详情覆盖层。
     *
     * <p>与 {@link AdvancementRowButton} 同一套做法（服务端推原料 82：分类 / 键 / 值，
     * 客户端用 {@link ShanhaiSaveEditStatsUi#rowText(int, String, String, long,
     * ShanhaiSaveEditStatsUi.Namer)} 现拼「跳跃次数」这种中文名）。
     */
    static final class StatsRowButton extends ButtonWidget {

        /** 本条 update-info 的 id（与其它控件的号错开）。 */
        static final int UPDATE_ROW = 82;

        /** 这一行的原料（可变；由面板建好递进来）。 */
        static final class Cell {
            final int index;
            boolean present;
            String category = "";
            String key = "";
            long value;

            Cell(int index) {
                this.index = index;
            }

            boolean hasRow() {
                return !this.key.isEmpty();
            }
        }

        private final Cell cell;

        StatsRowButton(int x, int y, int w, int h, Cell cell,
                       ShanhaiSaveEditStatsUi.Namer namer, Runnable action) {
            super(x, y, w, h,
                    new GuiTextureGroup(GuiTextures.BUTTON,
                            new TextTexture((Supplier<String>) () -> ShanhaiLdlText.esc(
                                    fit(cell.hasRow()
                                            ? ShanhaiSaveEditStatsUi.rowText(cell.index, cell.category,
                                            cell.key, cell.value, namer)
                                            : "", w - 8)))),
                    clickData -> {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            ShanhaiMod.LOGGER.warn("{} panel_stats_row_click_failed err={}",
                                    ShanhaiSaveEditFlow.PREFIX, t.toString());
                        }
                    });
            this.cell = cell;
        }

        /** 🔴 只在服务端调：把这一行的原料同步到客户端那份（客户端自己拼字）。 */
        void pushRow(boolean present, String category, String key, long value) {
            this.cell.present = present;
            this.cell.category = category == null ? "" : category;
            this.cell.key = key == null ? "" : key;
            this.cell.value = value;
            writeUpdateInfo(UPDATE_ROW, buf -> {
                buf.writeBoolean(this.cell.present);
                buf.writeUtf(this.cell.category);
                buf.writeUtf(this.cell.key);
                buf.writeVarLong(this.cell.value);
            });
        }

        /** 清空这一行（空行点了没反应）。 */
        void clearRow() {
            pushRow(false, "", "", 0L);
        }

        Cell cell() {
            return this.cell;
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == UPDATE_ROW) {
                this.cell.present = buffer.readBoolean();
                this.cell.category = buffer.readUtf();
                this.cell.key = buffer.readUtf();
                this.cell.value = buffer.readVarLong();
                return;
            }
            super.readUpdateInfo(id, buffer);
        }
    }

    /**
     * 第 ⑦ 页<b>一行可点的异常</b>：点它 = 选中它（再点「删除选中项」才暂存删除）。
     *
     * <h2>为什么是按钮而不是 {@code LabelWidget}</h2>
     * 与 {@link EntityRowButton} 同一条理由：玩家要"看到哪一行就点哪一行"。
     *
     * <h2>🔴 为什么这一页推的是整串字，而 ④⑤⑥ 页推"原料"</h2>
     * ④⑤⑥ 的行首要中文名，而名字只有客户端查得到（语言表 / 注册表），所以只能推原料、客户端现拼。
     * 体检页的行是<b>路径 + 大小 + 文本|二进制</b>，全是服务端算出来的死字，直接推整串最简单
     * —— 与 {@link FillField#pushText(String)} 同一条通道（{@code writeUpdateInfo} → {@code readUpdateInfo}）。
     */
    static final class AuditRowButton extends ButtonWidget {

        /**
         * 本条 update-info 的 id。
         *
         * <p>已占用的号（同一份代码里的约定，别撞）：1 = TextFieldWidget / FillField.pushText、
         * 77 = {@link AuditButton}、78/81/82 = ④⑤⑥ 页的行、79/80 = 实体覆盖层、83/84 = 成就覆盖层、
         * 85/86 = 统计覆盖层、87/88/89 = 物品栏覆盖层、93 = 关闭确认层 ⇒ 这里用 90。
         */
        static final int UPDATE_ROW = 90;

        /** 这一行的字（可变；由面板建好递进来 —— {@code super(...)} 里不许引用 this）。 */
        static final class Cell {
            final int index;
            String line = "";

            Cell(int index) {
                this.index = index;
            }

            boolean hasRow() {
                return !this.line.isEmpty();
            }
        }

        private final Cell cell;

        AuditRowButton(int x, int y, int w, int h, Cell cell, Runnable action) {
            super(x, y, w, h,
                    new GuiTextureGroup(GuiTextures.BUTTON,
                            new TextTexture((Supplier<String>) () -> ShanhaiLdlText.esc(
                                    fit(cell.hasRow() ? cell.line : "", w - 8)))),
                    clickData -> {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            ShanhaiMod.LOGGER.warn("{} panel_audit_row_click_failed err={}",
                                    ShanhaiSaveEditFlow.PREFIX, t.toString());
                        }
                    });
            this.cell = cell;
        }

        /** 🔴 只在服务端调：把这一行的字同步到客户端那份。 */
        void pushRow(String line) {
            this.cell.line = line == null ? "" : line;
            writeUpdateInfo(UPDATE_ROW, buf -> buf.writeUtf(this.cell.line));
        }

        /** 清空这一行（空行点了没反应）。 */
        void clearRow() {
            pushRow("");
        }

        /** 本侧的原料（判据/调试用）。 */
        Cell cell() {
            return this.cell;
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == UPDATE_ROW) {
                this.cell.line = buffer.readUtf();
                return;
            }
            super.readUpdateInfo(id, buffer);
        }
    }

    /**
     * 能<b>变灰 + 换 tooltip</b>的按钮（体检页「删除选中项」用）。
     *
     * <h2>为什么需要它</h2>
     * {@code Widget#setActive} 与 {@code setHoverTooltips} 都<b>只作用于本侧</b>
     * （{@code Widget#detectAndSendChanges} 是空实现 —— 字节码实证），而"有没有选中一条可删的"
     * <b>只有服务端知道</b>（扫描是服务端读盘做的）。所以必须自己开一条 update-info 通道把它推过去，
     * 与 {@link FillField#pushText(String)} 同一条路（{@code writeUpdateInfo} → 客户端
     * {@code readUpdateInfo}）。
     *
     * <h2>推送时机</h2>
     * 每次「重新扫描 / 翻页 / 点一行」之后由服务端 {@link #pushActive(boolean)} 一次。
     * 面板刚打开时是<b>灰</b>的（还没有扫描结果，也确实没有可删的东西）。
     */
    static final class AuditButton extends ButtonWidget {

        /** 本条 update-info 的 id（1 已被 {@code TextFieldWidget} 的内部同步用掉，这里错开）。 */
        static final int UPDATE_ACTIVE = 77;
        /** 可点时悬停说明。 */
        static final String TIP_ON = "把选中的这条从存档里删掉（🔴 不弹二次确认；删前先复制到 "
                + "config\\shanhai\\audit-trash\\<存档名>-<时间戳>\\，可撤回）";
        /** 灰掉时悬停说明。 */
        static final String TIP_OFF = "灰着 = 还没选中（点上面一行）或这条不可删（符号链接/特殊文件）";

        AuditButton(int x, int y, int w, int h, String text, Runnable action) {
            super(x, y, w, h,
                    new GuiTextureGroup(GuiTextures.BUTTON,
                            new TextTexture((Supplier<String>) () -> ShanhaiLdlText.esc(text))),
                    clickData -> {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            ShanhaiMod.LOGGER.warn("{} panel_button_failed err={}",
                                    ShanhaiSaveEditFlow.PREFIX, t.toString(), t);
                        }
                    });
            setActive(false);                  // 还没扫描 ⇒ 没有可删的东西（服务端 render 时再 push）
            applyTooltip();
        }

        /** 🔴 只在服务端调：把"能不能点"同步到客户端那份。 */
        void pushActive(boolean active) {
            setActive(active);
            applyTooltip();
            writeUpdateInfo(UPDATE_ACTIVE, buf -> buf.writeBoolean(active));
        }

        @Override
        public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == UPDATE_ACTIVE) {
                setActive(buffer.readBoolean());
                applyTooltip();
                return;
            }
            super.readUpdateInfo(id, buffer);
        }

        private void applyTooltip() {
            setHoverTooltips(Component.literal(isActive() ? TIP_ON : TIP_OFF));
        }
    }

}
