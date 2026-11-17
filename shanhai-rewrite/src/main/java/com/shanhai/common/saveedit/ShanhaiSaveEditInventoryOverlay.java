package com.shanhai.common.saveedit;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.shanhai.common.recipe.editor.ShanhaiLdlText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 第 ① 页的<b>「物品栏 41 格 + 末影箱」覆盖层</b>（铺满 400×232、压在整页之上，模态）。
 *
 * <h2>一、为什么是覆盖层</h2>
 * 用户原话：「物品栏要画成格子，点格子做删除/替换」。41 格按 18 px 方格摆出来约 198×72，
 * 而第 ① 页唯一的大空带只有 32 px 高 ⇒ <b>塞不进页①</b>。所以照第 ④⑤⑥ 页已经验收过的范式：
 * 页面只留一个入口按钮（「打开背包格」），格子在覆盖层里画。
 *
 * <h2>二、🔴 背包与末影箱共用<b>同一片</b>格子区</h2>
 * 用户原话：「末影箱可以做成左下角一个开关，切换末影箱的内容和玩家本体物品栏，
 * 这样可以少画一套格子」。所以：
 * <ul>
 *   <li>只有一个 {@link CellGrid}（自绘 41/27 格），数据源由
 *       {@link ShanhaiSaveEditInventoryUi.Model#ender()} 决定；</li>
 *   <li>几何只有一张表 {@link ShanhaiSaveEditLayout#inventoryCellRects(boolean)}；
 *       末影箱 27 格复用主背包那块 3×9；</li>
 *   <li>开关就是左下角那一个按钮，切换之后服务端<b>重新读另一个键</b>
 *       （{@link ShanhaiSaveEditInventoryUi#read(java.nio.file.Path, boolean)} →
 *       {@code readEnderItems} / {@code readInventory}）再把格子推回来。</li>
 * </ul>
 *
 * <h2>三、这一层只管画 + 判命中</h2>
 * 状态机、格号↔槽位映射、数量校验、补丁构造、名字回退、选物器过滤全在纯 JDK 的
 * {@link ShanhaiSaveEditInventoryUi} 里（那样离线判据才能驱动"切末影箱→删一格→只少一格"）。
 *
 * <h2>四、🔴 格子为什么不是 41 个控件</h2>
 * 一格一个 {@code Widget} 的话：41 个控件只为了画 41 个 18 px 方格，而且"末影箱要复用同一片格子"
 * 又得再写一套。这里用<b>一个自绘控件 + 一张矩形表</b>（{@link ShanhaiSaveEditLayout#inventoryCellRects}）：
 * 画的时候逐格画、点的时候逐格判命中，几何仍然由版式表唯一给出、仍然被
 * {@link ShanhaiSaveEditLayout#layoutProblems()} 逐格断言。
 *
 * <h2>五、🔴 这些输入框<b>没有</b> textSupplier</h2>
 * LDLib 的 {@code updateScreen} 只在 {@code isClientSideWidget && textSupplier != null} 时覆盖文本
 * —— 给玩家正在打字的框挂 supplier，会把他刚敲的字每 tick 冲掉一次（第 ④⑤⑥ 页踩过的同一个坑）。
 * 所以「数量」「搜索」两格只用初始数据通道 + {@link #updateScreen()} 里<b>只读</b>地轮询。
 *
 * <h2>六、物品图标只在客户端画</h2>
 * {@code drawInBackground} / {@code mouseClicked} 只由客户端界面调用，服务端那份控件树只用于
 * 容器对齐。因此这里出现 {@link Minecraft} / {@link GuiGraphics} 不会让专用服务端出问题
 * （JVM 惰性解析，方法体在服务端永远不执行）。
 */
public final class ShanhaiSaveEditInventoryOverlay extends WidgetGroup {

    /** 本条 update-info 的 id：**开 / 关**（与第 ④⑤⑥ 页三条覆盖层的号错开）。 */
    static final int UPDATE_OPEN = 87;
    /** 本条 update-info 的 id：**这一侧的格子内容**（一格一行，见 {@code Grid#encode()}）。 */
    static final int UPDATE_GRID = 88;
    /** 本条 update-info 的 id：**已暂存的槽号**（界面给格子打红点用）。 */
    static final int UPDATE_STAGED = 89;

    /** 客户端 → 服务端的动作：点了第 N 格。 */
    static final int ACT_CELL = 1;
    /** 客户端 → 服务端的动作：选物器里选中了某个 id（"接受"时服务端就用它）。 */
    static final int ACT_PICK_CELL = 2;

    /** 空格子的格子底色 / 边框（与 {@code IconCell} 同一套颜色）。 */
    private static final int CELL_FRAME = 0xFF6E6E6E;
    private static final int CELL_FRAME_HOVER = 0xFFBFBFBF;
    private static final int CELL_FRAME_SELECTED = 0xFFFFD54A;
    private static final int CELL_BG = 0xFF2B2B2B;
    private static final int CELL_EMPTY_DOT = 0xFF4A4A4A;
    private static final int STAGED_DOT = 0xFFFF5555;

    private final ShanhaiSaveEditPanel panel;
    /** 本侧是不是客户端那份（只有服务端那份才读盘/推格子）。 */
    private final boolean remote;

    private final ShanhaiSaveEditInventoryUi.Model model = new ShanhaiSaveEditInventoryUi.Model();

    /** 主层控件（选物器打开时一起藏起来）。 */
    private final List<Widget> mainWidgets = new ArrayList<>();
    /** 选物器控件。 */
    private final List<Widget> pickWidgets = new ArrayList<>();

    /** 41 格 / 27 格的自绘格子区。 */
    private CellGrid grid;
    /** 选物器的 48 格自绘格子区。 */
    private PickGrid pickGrid;

    private LabelWidget title;
    private LabelWidget subtitle;
    private LabelWidget selLabel;
    private LabelWidget itemLabel;
    private LabelWidget modeNote;
    private final LabelWidget[] notes = new LabelWidget[ShanhaiSaveEditLayout.IV_NOTE_Y.length];

    /** 「数量」输入框（🔴 没有 textSupplier）。 */
    private TextFieldWidget countField;
    /** 选物器搜索框（🔴 没有 textSupplier；靠 {@link #updateScreen()} 只读轮询）。 */
    private TextFieldWidget pickSearch;

    /** 选物器的<b>全量</b>候选（只有客户端会建：服务端没有语言表，也没必要建）。 */
    private final List<String> pickAll = new ArrayList<>();
    /** 过滤之后的候选（客户端）。 */
    private final List<String> pickFiltered = new ArrayList<>();
    /** 「这一层到底画在哪」的读数只写一次（见 {@link #logAbsOnce()}）。 */
    private boolean absLogged;

    public ShanhaiSaveEditInventoryOverlay(ShanhaiSaveEditPanel panel, boolean remote) {
        super(0, 0, ShanhaiSaveEditLayout.W, ShanhaiSaveEditLayout.H);
        this.panel = panel;
        this.remote = remote;
        this.setBackground(GuiTextures.BACKGROUND);

        // ① 先建自绘格子区（先加 ⇒ 后判：按钮永远比格子先收点击）
        //    🔴 外框必须盖住<b>整片</b>格子区（含侧边那一竖排的副手格），否则那一格在
        //    控件外框之外 → 某些 LDLib 版本会先按外框过滤掉点击（"副手那一格点不动"）。
        this.grid = new CellGrid(ShanhaiSaveEditLayout.IV_SIDE_X, ShanhaiSaveEditLayout.IV_ROW_Y0,
                ShanhaiSaveEditLayout.IV_MAIN_X + (ShanhaiSaveEditLayout.IV_COLS - 1)
                        * ShanhaiSaveEditLayout.IV_STEP + ShanhaiSaveEditLayout.IV_CELL
                        - ShanhaiSaveEditLayout.IV_SIDE_X,
                ShanhaiSaveEditLayout.IV_OFFHAND_Y + ShanhaiSaveEditLayout.IV_CELL
                        - ShanhaiSaveEditLayout.IV_ROW_Y0);
        this.addWidget(this.grid);

        // ② 主层控件（照槽位表建：几何与"到底几个控件"只有槽位表一个来源）
        for (ShanhaiSaveEditLayout.Slot slot : ShanhaiSaveEditLayout.inventoryOverlaySlots()) {
            final Widget w = build(slot);
            this.addWidget(w);
            this.mainWidgets.add(w);
        }

        // ③ 选物器那 48 格（先加，理由同 ①）
        this.pickGrid = new PickGrid(ShanhaiSaveEditLayout.IV_PICK_GRID_X,
                ShanhaiSaveEditLayout.IV_PICK_GRID_Y,
                ShanhaiSaveEditInventoryUi.PICK_COLS * ShanhaiSaveEditLayout.IV_STEP - 2,
                ShanhaiSaveEditInventoryUi.PICK_ROWS * ShanhaiSaveEditLayout.IV_STEP - 2);
        this.addWidget(this.pickGrid);

        // ④ 选物器的控件
        for (ShanhaiSaveEditLayout.Slot slot : ShanhaiSaveEditLayout.inventoryPickerSlots()) {
            final Widget w = buildPick(slot);
            this.addWidget(w);
            this.pickWidgets.add(w);
        }

        this.setVisible(false);
        this.setActive(false);
        this.syncVisibility();
    }

    // ================================================================== 建控件

    /** 照一个槽位造主层的控件（🔴 每个槽位必须<b>恰好</b>产出一个控件）。 */
    private Widget build(ShanhaiSaveEditLayout.Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "title": {
                // 🔴 标题是"客户端自己画"的（服务端算不出中文名/格数那句要实时跟着开关走）
                final LabelWidget t = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(this.titleText(), w));
                t.setClientSideWidget();        // ⚠️ 返回类型是 Widget，所以要分两步
                this.title = t;
                return t;
            }
            case "subtitle":
                return new LabelWidget(x, y, ShanhaiSaveEditPanel.fit(
                        "§7点一格 → 删 / 改数量 / 替换；左下角开关换「背包 / 末影箱」", w))
                        .setClientSideWidget();
            case "note": {
                // 三行"服务端推字"：概况 / 已暂存 / 反馈（LabelWidget 的 update-info 通道）
                final int idx = slot.index();
                this.notes[idx] = new LabelWidget(x, y, ShanhaiLdlText.sup(
                        () -> ShanhaiSaveEditPanel.fit(this.noteText(idx), w)));
                return this.notes[idx];
            }
            case "sel.label": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(this.selText(), w));
                l.setClientSideWidget();
                this.selLabel = l;
                return l;
            }
            case "item.label": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(this.itemText(), w));
                l.setClientSideWidget();
                this.itemLabel = l;
                return l;
            }
            case "delete.button":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "§c删除这一格",
                        this.panel::onInventoryDelete);
            case "count.field": {
                final TextFieldWidget f = new TextFieldWidget(x, y, w, h, null, null);
                f.setMaxStringLength(2);
                f.setHoverTooltips(Component.literal("§e数量§7：填 "
                        + ShanhaiPlayerDataEdit.COUNT_MIN + ".." + ShanhaiPlayerDataEdit.COUNT_MAX
                        + "（改数量 / 替换都看这一格；空格子只能替换）"));
                this.countField = f;
                return f;
            }
            case "count.button":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "改成这个量",
                        this.panel::onInventorySetCount);
            case "hand.button":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "替换：用手上那件",
                        this.panel::onInventoryReplaceHand);
            case "pick.button":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "替换：从列表选",
                        this::onOpenPicker);
            case "deselect.button":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "取消选中",
                        this::onDeselect);
            case "switch":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, this::switchText,
                        this.panel::onInventoryToggleMode);
            case "mode.note": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(this.modeNoteText(), w));
                l.setClientSideWidget();
                this.modeNote = l;
                return l;
            }
            case "close":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "关闭", this.panel::onInventoryClose);
            default:
                throw new IllegalStateException("物品栏覆盖层槽位没被处理：" + slot.key());
        }
    }

    /** 照一个槽位造选物器的控件。 */
    private Widget buildPick(ShanhaiSaveEditLayout.Slot slot) {
        final int x = slot.x();
        final int y = slot.y();
        final int w = slot.w();
        final int h = slot.h();
        switch (slot.key()) {
            case "pick.title": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(
                        "§f选物品 §7（搜中文名或 id；点一格选中，再按「接受」）", w));
                l.setClientSideWidget();
                return l;
            }
            case "pick.subtitle": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(
                        "§7候选 = 全部已注册物品（本模组的 128 个现实物品壳不在里面）", w));
                l.setClientSideWidget();
                return l;
            }
            case "pick.search": {
                final TextFieldWidget f = new TextFieldWidget(x, y, w, h, null, null);
                f.setMaxStringLength(48);
                f.setHoverTooltips(Component.literal("§e搜索§7：中文名或注册名都能搜；清空 = 回到全量"));
                this.pickSearch = f;
                return f;
            }
            case "pick.clear":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "清除搜索", this::onClearSearch);
            case "pick.sel": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(this.pickedText(), w));
                l.setClientSideWidget();
                return l;
            }
            case "pick.count": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(this.pickCountText(), w));
                l.setClientSideWidget();
                return l;
            }
            case "pick.prev":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "◀ 上一页", () -> this.onPickPage(-1));
            case "pick.next":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "下一页 ▶", () -> this.onPickPage(1));
            case "pick.accept":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "§a接受", this.panel::onInventoryPickAccept);
            case "pick.cancel":
                return ShanhaiSaveEditPanel.mkButton(x, y, w, h, () -> "§c取消", this::onCancelPicker);
            case "pick.note": {
                final LabelWidget l = new LabelWidget(x, y, () -> ShanhaiSaveEditPanel.fit(
                        "§8只有按「接受」才会把选中的物品写进这一格（数量用主界面的「数量」框）", w));
                l.setClientSideWidget();
                return l;
            }
            default:
                throw new IllegalStateException("选物器槽位没被处理：" + slot.key());
        }
    }

    // ================================================================== 打开 / 关闭

    /** 打开（两侧各跑一次）：只清状态，格子内容等读盘/推送。 */
    public void open() {
        this.model.open();
        this.refreshPick();
        this.syncVisibility();
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
        logAbsOnce();
    }

    /**
     * 服务端读完盘之后调：把这一侧（背包 / 末影箱）的格子灌进模型并推给客户端。
     *
     * <p>🔴 两侧都走这一条：服务端自己那份模型也要有内容（它要用槽号做暂存/校验），
     * 客户端那份靠 {@link #UPDATE_GRID} 拿到同一份。
     */
    public void openWith(ShanhaiSaveEditInventoryUi.Grid data) {
        this.model.open();
        this.model.setGrid(data);
        this.model.setGridLine(data == null ? "" : data.line());
        this.refreshPick();
        this.syncVisibility();
        this.setVisible(true);
        this.setActive(true);
        pushOpen(true);
        pushGrid();
        pushStaged();
        logAbsOnce();
    }

    /** 只换格子内容（服务端切开关 / 重读之后调；不重开覆盖层）。 */
    public void applyGrid(ShanhaiSaveEditInventoryUi.Grid data) {
        this.model.setGrid(data);
        this.model.setGridLine(data == null ? "" : data.line());
        pushGrid();
    }

    /** 关掉（两侧各跑一次）。 */
    public void close() {
        this.model.close();
        this.refreshPick();
        this.syncVisibility();
        this.setVisible(false);
        this.setActive(false);
        pushOpen(false);
    }

    /** 客户端在"按了入口按钮"的瞬间先把覆盖层弹出来（此时还没有内容）。 */
    public void openPlaceholder() {
        open();
    }

    /** 🔴 只在服务端调：把"开着 / 关着"推给客户端那份。 */
    private void pushOpen(boolean open) {
        if (this.remote) {
            return;
        }
        writeUpdateInfo(UPDATE_OPEN, buf -> buf.writeBoolean(open));
    }

    /** 🔴 只在服务端调：把这一侧的格子内容推给客户端。 */
    private void pushGrid() {
        if (this.remote) {
            return;
        }
        final String blob = this.model.grid().encode();
        writeUpdateInfo(UPDATE_GRID, buf -> buf.writeUtf(blob));
    }

    /** 🔴 只在服务端调：把"已暂存的槽号"推给客户端（界面给格子打红点用）。 */
    public void pushStaged() {
        if (this.remote) {
            return;
        }
        final int[] slots = this.model.stagedSlots();
        writeUpdateInfo(UPDATE_STAGED, buf -> {
            buf.writeVarInt(slots.length);
            for (int s : slots) {
                buf.writeVarInt(s);
            }
        });
    }

    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == UPDATE_OPEN) {
            final boolean open = buffer.readBoolean();
            if (!open) {
                this.model.close();
                this.refreshPick();
            }
            this.setVisible(open);
            this.setActive(open);
            this.syncVisibility();
            return;
        }
        if (id == UPDATE_GRID) {
            final ShanhaiSaveEditInventoryUi.Grid g =
                    ShanhaiSaveEditInventoryUi.Grid.decode(buffer.readUtf());
            this.model.setGrid(g);
            this.model.setGridLine(g.line());
            return;
        }
        if (id == UPDATE_STAGED) {
            final int n = buffer.readVarInt();
            final int[] slots = new int[n];
            for (int i = 0; i < n; i++) {
                slots[i] = buffer.readVarInt();
            }
            this.model.setStagedSlots(slots);
            return;
        }
        super.readUpdateInfo(id, buffer);
    }

    /** 按"选物器开没开"调一次显隐（两侧各调）。 */
    private void syncVisibility() {
        final boolean picking = this.model.picking();
        for (Widget w : this.mainWidgets) {
            w.setVisible(!picking);
        }
        if (this.grid != null) {
            this.grid.setVisible(!picking);
        }
        for (Widget w : this.pickWidgets) {
            w.setVisible(picking);
        }
        if (this.pickGrid != null) {
            this.pickGrid.setVisible(picking);
        }
    }

    // ================================================================== 选物器（客户端）

    /** 「取消选中」（两侧各清自己那份；<b>不动</b>任何已经暂存的改动）。 */
    private void onDeselect() {
        this.model.clearSelection();
    }

    private void onOpenPicker() {
        this.model.openPicker();
        this.refreshPick();
        if (this.pickSearch != null) {
            this.pickSearch.setCurrentString("");
        }
        this.syncVisibility();
        this.model.setFeedback("选物器：搜中文名或 id，点一格选中，再按「接受」");
    }

    private void onCancelPicker() {
        this.model.closePicker();
        this.syncVisibility();
    }

    private void onClearSearch() {
        if (this.pickSearch != null) {
            this.pickSearch.setCurrentString("");
        }
        this.model.setPickQuery("");
        this.refreshPick();
    }

    private void onPickPage(int delta) {
        this.model.setPickPage(this.model.pickPage() + delta, this.pickFiltered.size());
        this.refreshPick();
    }

    /**
     * 重建全量候选（<b>只在客户端</b>；第一次打开选物器时建一次）。
     *
     * <p>数据源 = {@code ForgeRegistries.ITEMS}（全部已注册物品），与
     * {@code ShanhaiCreativeIconPicker#ensureCandidates} 同一条口径；排除 {@code minecraft:air}
     * 与本模组的 128 个现实物品壳（它们的图标来自同一张表，选它等于递归）。
     */
    private void ensureCandidates() {
        if (!this.pickAll.isEmpty()) {
            return;
        }
        try {
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                if (item == null || item == Items.AIR
                        || item instanceof com.shanhai.item.RealityShellItem) {
                    continue;
                }
                final ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) {
                    this.pickAll.add(id.toString());
                }
            }
            this.pickAll.sort(Comparator.naturalOrder());
        } catch (Throwable t) {
            com.shanhai.ShanhaiMod.LOGGER.warn("{} inv_pick_candidates_failed err={}",
                    ShanhaiSaveEditFlow.PREFIX, t.toString());
        }
    }

    /**
     * 重算过滤表（<b>只有客户端</b>做：服务端没有语言表，过滤结果本来就不该由它决定）。
     *
     * <p>⚠️ {@code this.remote} = <b>本侧是客户端</b>（与第 ④⑤⑥ 页三个覆盖层同一条约定），
     * 所以这里是"不是客户端就直接退出"——写反过一次，结果是客户端永远给出空列表。
     */
    private void refreshPick() {
        if (!this.remote) {
            this.pickFiltered.clear();
            return;
        }
        if (this.model.picking()) {
            ensureCandidates();
            this.pickFiltered.clear();
            this.pickFiltered.addAll(ShanhaiSaveEditInventoryUi.pickFilter(
                    this.pickAll, this.model.pickQuery(), ShanhaiSaveEditInventoryUi.ITEM_NAMER));
        } else {
            this.pickFiltered.clear();
        }
        this.model.setPickPage(this.model.pickPage(), this.pickFiltered.size());
    }

    /**
     * 🔴 每 tick 从<b>控件现取</b>搜索框的字（LDLib 的 {@code TextFieldWidget} 的 responder
     * 不一定在客户端这份上被调 —— 与 {@code ShanhaiCreativeIconPicker#updateScreen} 同一个坑）。
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.remote || this.pickSearch == null || !this.model.picking()) {
            return;
        }
        final String live = this.pickSearch.getCurrentString();
        if (live != null && !live.equals(this.model.pickQuery())) {
            this.model.setPickQuery(live);
            this.refreshPick();
        }
    }

    // ================================================================== 给面板/控件读

    public ShanhaiSaveEditInventoryUi.Model model() {
        return this.model;
    }

    /** 「数量」格子里的字（服务端提交前取一次）。 */
    public String readCount() {
        final String s = this.countField == null ? null : this.countField.getCurrentString();
        return s == null ? "" : s.trim();
    }

    /** 清空「数量」格子（打开/切档时用；服务端那份不用 push —— 文本方向是"客户端 → 服务端"）。 */
    public void clearCount() {
        if (this.countField != null) {
            this.countField.setCurrentString("");
        }
    }

    /** 覆盖层开着吗（切页时用）。 */
    public boolean isOpen() {
        return this.model.opened();
    }

    /** 客户端点了选物器里的一格（把 id 记住 + 同步给服务端）。 */
    void pickCell(String id) {
        this.model.setPendingPickId(id);
        this.model.setFeedback("已选：" + id + " · 按「接受」写进这一格");
    }

    // ================================================================== 文本（客户端现拼）

    private String titleText() {
        return "§f物品栏 · §e" + this.model.modeName() + "§f " + this.model.cellCount() + " 格";
    }

    private String noteText(int index) {
        switch (index) {
            case 0:
                return this.model.gridLine();
            case 1:
                return this.model.stagedLine();
            default:
                return this.model.feedback();
        }
    }

    private String selText() {
        if (this.model.selected() < 0) {
            return "§7选中：§8（还没选格子）";
        }
        return "§7选中：§f" + ShanhaiSaveEditInventoryUi.cellName(this.model.ender(), this.model.selected())
                + " §8[Slot " + this.model.selectedSlot() + "]";
    }

    private String itemText() {
        return ShanhaiSaveEditInventoryUi.cellLine(this.model.ender(), this.model.selected(),
                this.model.grid(), ShanhaiSaveEditInventoryUi.ITEM_NAMER);
    }

    private String modeNoteText() {
        return "§8现在是「" + this.model.modeName() + "」这一侧（" + this.model.cellCount() + " 格）";
    }

    private String switchText() {
        // 按钮上写"按了会变成什么"，比写"我现在是什么"更少歧义（与第 ③ 页 BE 按钮同一条口径）
        return "§e开关：切到" + (this.model.ender()
                ? ShanhaiSaveEditInventoryUi.MODE_BACKPACK : ShanhaiSaveEditInventoryUi.MODE_ENDER);
    }

    private String pickedText() {
        final String id = this.model.pendingPickId();
        return id.isEmpty() ? "§7已选：§8（还没选）"
                : "§7已选：§f" + ShanhaiSaveEditInventoryUi.itemLabel(
                        id, ShanhaiSaveEditInventoryUi.ITEM_NAMER);
    }

    private String pickCountText() {
        return "§7共 §f" + this.pickFiltered.size() + " §7个 · 第 §f"
                + (this.model.pickPage() + 1) + "§7/§f"
                + ShanhaiSaveEditInventoryUi.pickPageCount(this.pickFiltered.size()) + " §7页";
    }

    // ================================================================== 命中 / 画

    /**
     * 鼠标落在第几格（背包 41 / 末影箱 27；没落在任何一格 ⇒ -1）。
     *
     * <p>🔴 传进来的是<b>屏幕</b>坐标，而 {@link ShanhaiSaveEditLayout#inventoryCellRects(boolean)}
     * 给的是<b>本层坐标</b>（本层 self=(0,0) ⇒ 数值上等于面板内坐标）。所以要减掉本层的绝对位置：
     * <pre>
     *   WidgetGroup#mouseClicked :50-56   —— 子控件收到的是<b>同一个</b> mouseX/mouseY（不减去父组位置）
     *   Widget#isMouseOverElement :0-34   —— 拿 getPosition()（绝对）比
     *   ⇒ 判命中必须自己把屏幕坐标换算成本层坐标，否则"点在屏幕中间"会被判成"没点中任何格"
     *     （改之前就是这个 bug 的另一半：格子画在左上角，点也点不中）。
     * </pre>
     */
    int cellAt(double mouseX, double mouseY) {
        final double mx = mouseX - getPositionX();
        final double my = mouseY - getPositionY();
        final List<ShanhaiSaveEditLayout.Rect> rects =
                ShanhaiSaveEditLayout.inventoryCellRects(this.model.ender());
        for (int i = 0; i < rects.size(); i++) {
            final ShanhaiSaveEditLayout.Rect r = rects.get(i);
            if (mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom()) {
                return i;
            }
        }
        return -1;
    }

    /** 把 id 变成一个能画的 {@link ItemStack}（客户端；注册表里没有 ⇒ 空栈，格子照样画边框）。 */
    private static ItemStack stackOf(ShanhaiSaveEditInventoryUi.Cell cell) {
        if (cell == null || cell.empty()) {
            return ItemStack.EMPTY;
        }
        try {
            final ResourceLocation id = ResourceLocation.tryParse(cell.id());
            final Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(item, Math.max(1, Math.min(64, cell.count())));
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /** 画一格（边框 + 图标 + 数量 + 已暂存的红点）。 */
    private void drawCell(GuiGraphics graphics, ShanhaiSaveEditLayout.Rect r, int index, boolean selected,
                          boolean hover, ShanhaiSaveEditInventoryUi.Cell cell, boolean staged) {
        final int x = r.x();
        final int y = r.y();
        final int size = ShanhaiSaveEditLayout.IV_CELL;
        graphics.fill(x, y, x + size, y + size,
                selected ? CELL_FRAME_SELECTED : (hover ? CELL_FRAME_HOVER : CELL_FRAME));
        graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, CELL_BG);
        final ItemStack stack = stackOf(cell);
        if (!stack.isEmpty()) {
            // 与既有 IO 格同一写法（先置白 shader、抬 z）——见 ShanhaiQuerySlotWidget 的注释
            DrawerHelper.drawItemStack(graphics, stack, x + 1, y + 1, -1, null);
            if (cell != null && cell.count() > 1) {
                final Minecraft mc = Minecraft.getInstance();
                final String text = String.valueOf(cell.count());
                graphics.drawString(mc.font, text, x + size - 1 - mc.font.width(text), y + size - 9,
                        0xFFFFFFFF, true);
            }
        } else {
            graphics.fill(x + 5, y + 5, x + size - 5, y + size - 5, CELL_EMPTY_DOT);
        }
        if (staged) {
            graphics.fill(x + 2, y + 2, x + 6, y + 6, STAGED_DOT);
        }
    }

    /** 41 格 / 27 格的自绘格子区（一次画完；点一下 = 选中那一格）。 */
    private final class CellGrid extends Widget {

        CellGrid(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // 🔴 手写 fill 必须自己套上本层（覆盖层）的绝对位置：LDLib 不会替你平移 pose
            //    （WidgetGroup#drawWidgetsBackground 只逐个调子控件的 drawInBackground，
            //     不 pushPose/translate，mouseX/mouseY 也原样传下来；证据见
            //     ShanhaiSaveEditCloseConfirmOverlay#drawInBackground 的注释）。
            //    graphics.fill 的 x/y 是屏幕坐标 ⇒ 不加偏移就画在 GUI 原点（左上角、压住 JEI）。
            final int ox = ShanhaiSaveEditInventoryOverlay.this.getPositionX();
            final int oy = ShanhaiSaveEditInventoryOverlay.this.getPositionY();
            final double mx = mouseX - ox;
            final double my = mouseY - oy;
            final List<ShanhaiSaveEditLayout.Rect> rects =
                    ShanhaiSaveEditLayout.inventoryCellRects(ShanhaiSaveEditInventoryOverlay.this.model.ender());
            graphics.pose().pushPose();
            graphics.pose().translate((float) ox, (float) oy, 0.0f);
            for (int i = 0; i < rects.size(); i++) {
                final ShanhaiSaveEditLayout.Rect r = rects.get(i);
                final boolean hover = mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom();
                final ShanhaiSaveEditInventoryUi.Cell cell =
                        ShanhaiSaveEditInventoryOverlay.this.model.cell(i);
                drawCell(graphics, r, i,
                        ShanhaiSaveEditInventoryOverlay.this.model.selected() == i, hover, cell,
                        cell != null && ShanhaiSaveEditInventoryOverlay.this.model.stagedSlot(cell.slot()));
            }
            graphics.pose().popPose();
        }

        @Override
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final int index = cellAt(mouseX, mouseY);
            if (index >= 0) {
                tooltipTexts.clear();
                final ShanhaiSaveEditInventoryUi.Cell cell =
                        ShanhaiSaveEditInventoryOverlay.this.model.cell(index);
                tooltipTexts.add(Component.literal("§e"
                        + ShanhaiSaveEditInventoryUi.cellName(
                        ShanhaiSaveEditInventoryOverlay.this.model.ender(), index)
                        + "§7 [Slot " + ShanhaiSaveEditInventoryUi.slotAt(
                        ShanhaiSaveEditInventoryOverlay.this.model.ender(), index) + "]"));
                if (cell == null || cell.empty()) {
                    tooltipTexts.add(Component.literal("§8" + ShanhaiSaveEditInventoryUi.EMPTY_TEXT
                            + "（只能「替换」）"));
                } else {
                    tooltipTexts.add(Component.literal("§f"
                            + ShanhaiSaveEditInventoryUi.itemLabel(cell.id(),
                            ShanhaiSaveEditInventoryUi.ITEM_NAMER)));
                    tooltipTexts.add(Component.literal("§7数量 " + cell.count()));
                }
                if (cell != null && ShanhaiSaveEditInventoryOverlay.this.model.stagedSlot(cell.slot())) {
                    tooltipTexts.add(Component.literal("§c这一格已经暂存了改动（点右上「确认修改并重载」才落盘）"));
                }
            }
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            final int index = cellAt(mouseX, mouseY);
            if (index < 0) {
                return false;
            }
            playButtonClickSound();
            if (isRemote()) {
                // 🔴 一行可核对的判据：点中了第几格 + 屏幕坐标 / 换算后的本层坐标 / 本层绝对位置。
                //    改之前 cellAt 直接拿屏幕坐标比本层矩形 ⇒ 屏幕中间点格子永远"没点中"（这条日志不会出现）。
                //    判"居中了没有、点得中不中"就看这一行：rel 必须落在 0..400 × 0..232 之内。
                com.shanhai.ShanhaiMod.LOGGER.info(
                        "{} inv_cell_click index={} screen=({},{}) rel=({},{}) abs=({},{})",
                        ShanhaiSaveEditFlow.PREFIX, index, (int) mouseX, (int) mouseY,
                        (int) (mouseX - ShanhaiSaveEditInventoryOverlay.this.getPositionX()),
                        (int) (mouseY - ShanhaiSaveEditInventoryOverlay.this.getPositionY()),
                        ShanhaiSaveEditInventoryOverlay.this.getPositionX(),
                        ShanhaiSaveEditInventoryOverlay.this.getPositionY());
                ShanhaiSaveEditInventoryOverlay.this.model.select(index);
                writeClientAction(ACT_CELL, buf -> buf.writeVarInt(index));
            } else {
                ShanhaiSaveEditInventoryOverlay.this.panel.onInventoryCell(index);
            }
            return true;
        }

        @Override
        public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == ACT_CELL) {
                ShanhaiSaveEditInventoryOverlay.this.panel.onInventoryCell(buffer.readVarInt());
                return;
            }
            super.handleClientAction(id, buffer);
        }
    }

    /** 选物器的 48 格自绘格子区（只画；点一下 = 选中那个 id 并同步给服务端）。 */
    private final class PickGrid extends Widget {

        PickGrid(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        private int indexAt(double mouseX, double mouseY) {
            // 同 cellAt：传进来的是屏幕坐标，pickerCellRects 是本层坐标 ⇒ 先减本层绝对位置
            final double mx = mouseX - ShanhaiSaveEditInventoryOverlay.this.getPositionX();
            final double my = mouseY - ShanhaiSaveEditInventoryOverlay.this.getPositionY();
            final List<ShanhaiSaveEditLayout.Rect> rects = ShanhaiSaveEditLayout.pickerCellRects();
            for (int i = 0; i < rects.size(); i++) {
                final ShanhaiSaveEditLayout.Rect r = rects.get(i);
                if (mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom()) {
                    return i;
                }
            }
            return -1;
        }

        private String idAt(int indexOnPage) {
            if (indexOnPage < 0) {
                return "";
            }
            final List<String> page = ShanhaiSaveEditInventoryUi.pickPage(pickFiltered, model.pickPage());
            return indexOnPage < page.size() ? page.get(indexOnPage) : "";
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // 同 CellGrid：手写 fill 要自己套上本层绝对位置（LDLib 不会替我们 translate pose）
            final int ox = ShanhaiSaveEditInventoryOverlay.this.getPositionX();
            final int oy = ShanhaiSaveEditInventoryOverlay.this.getPositionY();
            final double mx = mouseX - ox;
            final double my = mouseY - oy;
            final List<ShanhaiSaveEditLayout.Rect> rects = ShanhaiSaveEditLayout.pickerCellRects();
            final List<String> page = ShanhaiSaveEditInventoryUi.pickPage(pickFiltered, model.pickPage());
            final String picked = model.pendingPickId();
            graphics.pose().pushPose();
            graphics.pose().translate((float) ox, (float) oy, 0.0f);
            for (int i = 0; i < rects.size(); i++) {
                final ShanhaiSaveEditLayout.Rect r = rects.get(i);
                final boolean hover = mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom();
                final String id = i < page.size() ? page.get(i) : "";
                final ShanhaiSaveEditInventoryUi.Cell cell = id.isEmpty()
                        ? null : new ShanhaiSaveEditInventoryUi.Cell(-1, id, 1);
                drawCell(graphics, r, i, !id.isEmpty() && id.equals(picked), hover, cell, false);
            }
            graphics.pose().popPose();
        }

        @Override
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            final String id = idAt(indexAt(mouseX, mouseY));
            if (!id.isEmpty()) {
                tooltipTexts.clear();
                tooltipTexts.add(Component.literal("§f"
                        + ShanhaiSaveEditInventoryUi.itemLabel(id, ShanhaiSaveEditInventoryUi.ITEM_NAMER)));
                tooltipTexts.add(Component.literal("§8" + id));
            }
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }
            final String id = idAt(indexAt(mouseX, mouseY));
            if (id.isEmpty()) {
                return false;
            }
            playButtonClickSound();
            pickCell(id);
            if (isRemote()) {
                writeClientAction(ACT_PICK_CELL, buf -> buf.writeUtf(id));
            }
            return true;
        }

        @Override
        public void handleClientAction(int id, FriendlyByteBuf buffer) {
            if (id == ACT_PICK_CELL) {
                ShanhaiSaveEditInventoryOverlay.this.panel.onInventoryPickCell(buffer.readUtf());
                return;
            }
            super.handleClientAction(id, buffer);
        }
    }

    /**
     * 🔴 <b>整层吃掉点击</b>（与 {@link ShanhaiSaveEditEntityOverlay#mouseClicked} 同一条理由）：
     * 落在覆盖层身上、但没打中任何子控件的那些点击，绝不能穿到底下的页签 / 「确认修改并重载」。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }

    /**
     * 一行可核对的位置读数（只在客户端、只写一次）。
     *
     * <p>它回答"到底画在哪"：① 这一层与面板左上角重合（{@code self=(0,0)}，绝对位置 = 面板的
     * 绝对位置）；② 41 格的第一格<b>在屏幕上</b>的坐标 = 本层绝对位置 + 格子在面板内的坐标
     * （后者来自 {@link ShanhaiSaveEditLayout#inventoryCellRects(boolean)}，纯 JDK 可离线判）。
     */
    private void logAbsOnce() {
        if (this.absLogged || !this.remote) {
            return;
        }
        this.absLogged = true;
        final List<ShanhaiSaveEditLayout.Rect> rects = ShanhaiSaveEditLayout.inventoryCellRects(false);
        final ShanhaiSaveEditLayout.Rect first = rects.isEmpty() ? null : rects.get(0);
        com.shanhai.ShanhaiMod.LOGGER.info("{} inv_overlay_abs self=({},{}) abs=({},{}) parent_abs={} "
                        + "cell0_rel=({},{}) cell0_abs=({},{}) cells={} draw=pose_translate",
                ShanhaiSaveEditFlow.PREFIX, getSelfPositionX(), getSelfPositionY(),
                getPositionX(), getPositionY(),
                getParent() == null ? "-" : (getParent().getPositionX() + "," + getParent().getPositionY()),
                first == null ? -1 : first.x(), first == null ? -1 : first.y(),
                first == null ? -1 : getPositionX() + first.x(),
                first == null ? -1 : getPositionY() + first.y(),
                rects.size());
    }

    /** 给判据/日志用的一行读数（不依赖任何实例状态）。 */
    public static String layoutLine() {
        return "inv_overlay slots=" + ShanhaiSaveEditLayout.inventoryOverlaySlots().size()
                + " cells=" + ShanhaiSaveEditLayout.inventoryCellRects(false).size()
                + "/" + ShanhaiSaveEditLayout.inventoryCellRects(true).size()
                + " picker_slots=" + ShanhaiSaveEditLayout.inventoryPickerSlots().size()
                + " picker_cells=" + ShanhaiSaveEditLayout.pickerCellRects().size()
                + " textfields=2 textSupplier_on_fields=0 slotWidget=0"
                // 坐标口径（给"到底画在哪"用）：本层 self=(0,0) ⇒ 面板内坐标 == 本层坐标；
                // 手写 fill 现在会 pose.translate(本层绝对位置)。运行时读数见 inv_overlay_abs。
                + " coord=self(0,0)+panel_abs cells_rel=layout draw=pose_translate";
    }

    /** 反查用：某一格在屏幕上的位置（判据/日志用，不依赖实例）。 */
    public static String cellRectLine(boolean ender, int index) {
        final List<ShanhaiSaveEditLayout.Rect> rects = ShanhaiSaveEditLayout.inventoryCellRects(ender);
        if (index < 0 || index >= rects.size()) {
            return "-";
        }
        final ShanhaiSaveEditLayout.Rect r = rects.get(index);
        return ShanhaiSaveEditInventoryUi.cellName(ender, index) + "@Slot"
                + ShanhaiSaveEditInventoryUi.slotAt(ender, index) + "=(" + r.x() + "," + r.y() + ")";
    }
}
