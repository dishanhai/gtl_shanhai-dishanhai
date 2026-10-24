package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Position;
import com.shanhai.item.RealityShellItem;
import com.shanhai.item.ShanhaiItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 「自己新增物品」主面板（<b>普通 GUI</b>，用户明确说不做全息版）。
 *
 * <h2>版式（340×300，和配方编辑器同宽，理由同它：别盖住左右两列 JEI）</h2>
 * <pre>
 *   y=4     标题
 *   y=20..164   128 个槽位格（16 列 × 8 行，18px，按槽位号顺序）
 *   y=168   当前槽位读数（槽位号 + id）
 *   y=184   名称输入框       [40..330]
 *   y=202   堆叠 IntInput    [40..140]  ＋ 稀有度按钮 [190..300]
 *   y=226   提示输入框       [40..330]
 *   y=246   选择图标… / 取手上物品的图标 / 图标预览 + 读数
 *   y=268   保存 / 清空该槽 / 关闭 ＋ 状态行
 * </pre>
 *
 * <h2>🔴 编辑状态全在客户端（服务端那份面板只用来对齐容器）</h2>
 * 面板两侧各建一份（{@code UIFactory.createUITemplate} 两侧都跑）。客户端那份存着"正在编辑的
 * 缓冲区"，点「保存」时把整包字段发给服务端（{@link ShanhaiCreativeItemsNetwork#requestSave}），
 * 服务端校验 + 落盘 + 广播整表。这样：
 * <ul>
 *   <li>不需要依赖 {@code TextFieldWidget} 的同步语义（那套只在真机上验过，语义没取证）；</li>
 *   <li>服务端那份面板永远不会"半懂不懂地"改表 —— 唯一入口就是那条 C2S 消息。</li>
 * </ul>
 *
 * <h2>热生效（第 ③ 条：不打 /reload、不按 F3+T）</h2>
 * <ol>
 *   <li>服务端 {@code apply()} 改内存表 + 写 {@code config/shanhai/creative_items.json}；</li>
 *   <li>广播整表（S2C）⇒ 客户端换表 + 版本号 +1；</li>
 *   <li>客户端 {@code ShanhaiRealityClientRefresh} 在同一 tick 之后：
 *       重建本 mod 创造栏内容（{@code CreativeModeTab#buildContents}）+ 把新增/变化的物品
 *       增量塞进 JEI（{@code IIngredientManager#addIngredientsAtRuntime}）；</li>
 *   <li>图标：模型层每次渲染现读表（版本号只用来作废它自己的解析缓存）⇒ 立刻换图。</li>
 * </ol>
 */
public final class ShanhaiCreativeItemsPanel extends WidgetGroup {

    public static final int W = 340;
    public static final int H = 300;

    private static final int GRID_COLS = 16;
    private static final int GRID_ROWS = 8;
    private static final int CELL = 18;
    private static final int GRID_X = 6;
    private static final int GRID_Y = 20;

    private final ShanhaiCreativeItemsFactory.Holder holder;
    private final Player player;

    /** 这个实例在哪一侧（客户端 true）—— 由持有者决定，不查全局态。 */
    private final boolean remote;

    // ---- 编辑缓冲区（客户端权威；保存时整包发给服务端）----
    private int editSlot;
    private String editName = "";
    private String editIcon = "";
    private int editStack = ShanhaiRealityItemTable.DEFAULT_STACK;
    private String editRarity = "common";
    /**
     * 提示（多行，用 {@code \n} 分隔）。编辑器 {@link ShanhaiCreativeLoreEditor} 要读它 ⇒ 包内可见。
     */
    String editLore = "";

    private String statusText = "§7选一个槽位，填名字，然后点保存";

    private final ShanhaiCreativeWidgets.IconCell[] cells =
            new ShanhaiCreativeWidgets.IconCell[ShanhaiRealityItemTable.SLOT_COUNT];

    private TextFieldWidget nameField;
    /**
     * 🔴 2026-10-17 换掉 GTCEu 的 {@code IntInputWidget}：它的文本框是 private 字段、值只能靠
     * {@code Consumer} 回调进来，而 LDLib 的文本框回调在客户端这份上不一定被调（实测
     * 156 次保存全是 {@code stack=64}）⇒ 改成我们自己的「文本框 ＋ ＋/－/64 按钮」，
     * 每次保存都从文本框里现取（与名字/提示同一套口径）。
     */
    private TextFieldWidget stackField;
    private ShanhaiCreativeWidgets.TextButton rarityButton;
    private ShanhaiCreativeWidgets.Preview iconPreview;
    private ShanhaiCreativeLoreEditor loreEditor;
    private ShanhaiCreativeIconPicker picker;
    private int seenVersion = -1;

    public ShanhaiCreativeItemsPanel(ShanhaiCreativeItemsFactory.Holder holder, Player player) {
        super(0, 0, W, H);
        this.holder = holder;
        this.player = player;
        this.remote = holder != null && holder.isRemote();
        this.setBackground(GuiTextures.BACKGROUND);

        // 初始停在 1 号槽位（缓冲区按表里的真实值装一次）
        loadSlot(0);

        this.addWidget(new LabelWidget(6, 4,
                "§f自己新增物品 §7· 现实物品槽位 1–128（只有填了名字的才会出现）"));

        // ---- 128 个槽位格 ----
        for (int i = 0; i < ShanhaiRealityItemTable.SLOT_COUNT; i++) {
            final int slot = i;
            final ShanhaiCreativeWidgets.IconCell cell = new ShanhaiCreativeWidgets.IconCell(
                    GRID_X + (i % GRID_COLS) * CELL, GRID_Y + (i / GRID_COLS) * CELL);
            cell.stack(() -> stackOf(slot));
            cell.tooltip(() -> tooltipOf(slot));
            cell.onClick(() -> {
                selectSlot(slot);
            });
            this.addWidget(cell);
            this.cells[i] = cell;
        }

        // ---- 当前槽位读数 ----
        final LabelWidget slotLabel = new LabelWidget(6, 168, this::slotLine);
        slotLabel.setClientSideWidget();
        this.addWidget(slotLabel);

        // ---- 名称 ----
        this.addWidget(new LabelWidget(6, 186, "§7名称"));
        this.nameField = new TextFieldWidget(40, 184, 290, 16, () -> this.editName, value -> {
            this.editName = value == null ? "" : value;
        });
        this.nameField.setMaxStringLength(ShanhaiRealityItemTable.MAX_NAME_CHARS);
        this.addWidget(this.nameField);

        // ---- 堆叠 + 稀有度 ----
        this.addWidget(new LabelWidget(6, 206, "§7堆叠"));
        this.stackField = new TextFieldWidget(40, 202, 66, 16, () -> Integer.toString(this.editStack),
                value -> {
                    // 回调照留（有些环境会走），但真值以保存时现取的文本框内容为准
                    final int v = parseStack(value);
                    if (v > 0) {
                        this.editStack = v;
                    }
                });
        this.stackField.setMaxStringLength(10);
        this.stackField.setCurrentString(Integer.toString(this.editStack));
        this.addWidget(this.stackField);
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(108, 202, 16, 16, () -> "§f+",
                () -> bumpStack(1)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(126, 202, 16, 16, () -> "§f-",
                () -> bumpStack(-1)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(146, 202, 22, 16, () -> "§f64",
                () -> setStackValue(ShanhaiRealityItemTable.DEFAULT_STACK)));
        this.addWidget(new LabelWidget(176, 206, "§7稀有度"));
        this.rarityButton = new ShanhaiCreativeWidgets.TextButton(212, 202, 88, 18,
                () -> "§f" + RealityShellItem.rarityZh(this.editRarity), this::cycleRarity);
        this.addWidget(this.rarityButton);

        // ---- 提示（多行：点「编辑提示…」到独立一层里逐行填）----
        this.addWidget(new LabelWidget(6, 228, "§7提示"));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(40, 226, 110, 16,
                () -> "§f编辑提示…", () -> {
                    if (this.loreEditor != null) {
                        this.loreEditor.open();
                    }
                }));
        final LabelWidget loreSummary = new LabelWidget(156, 230, this::loreSummary);
        loreSummary.setClientSideWidget();
        this.addWidget(loreSummary);

        // ---- 图标 ----
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(6, 246, 80, 18,
                () -> "§f选择图标…", this::openPicker));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(90, 246, 96, 18,
                () -> "§f取手上物品的图标", this::takeHeldIcon));
        this.iconPreview = new ShanhaiCreativeWidgets.Preview(192, 246);
        this.iconPreview.stack(this::iconPreviewStack);
        this.addWidget(this.iconPreview);
        final LabelWidget iconLabel = new LabelWidget(214, 251, this::iconLine);
        iconLabel.setClientSideWidget();
        this.addWidget(iconLabel);

        // ---- 底部 ----
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(6, 268, 70, 18,
                () -> "§a保存", () -> save("§a已保存")));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(80, 268, 80, 18,
                () -> "§e清空该槽", this::clearSlot));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(164, 268, 60, 18,
                () -> "§f关闭", this::closePanel));
        final LabelWidget status = new LabelWidget(232, 272, this::statusLine);
        status.setClientSideWidget();
        this.addWidget(status);

        // ---- 提示编辑器（覆盖层）＋ 图标选择器（覆盖层，最后加 = 画在最上面；默认隐藏）----
        this.loreEditor = new ShanhaiCreativeLoreEditor(this);
        this.addWidget(this.loreEditor);
        this.picker = new ShanhaiCreativeIconPicker(this);
        this.addWidget(this.picker);

        this.seenVersion = ShanhaiRealityItemTable.version();
        refreshCells();
    }

    // ------------------------------------------------------------------ 槽位选择 / 编辑缓冲

    /** 把表里这一格的真实值装进编辑缓冲区（未定义 = 空名字 + 默认堆叠）。 */
    private void loadSlot(int slot) {
        this.editSlot = Math.max(0, Math.min(slot, ShanhaiRealityItemTable.SLOT_COUNT - 1));
        final ShanhaiRealityItemTable.Entry e = ShanhaiRealityItemTable.entry(this.editSlot);
        this.editName = e == null ? "" : e.name();
        this.editIcon = e == null ? "" : e.icon();
        this.editStack = e == null ? ShanhaiRealityItemTable.DEFAULT_STACK : e.maxStack();
        this.editRarity = e == null ? "common" : e.rarity();
        this.editLore = e == null ? "" : e.lore();
    }

    /** 点格子选槽位（客户端）。 */
    private void selectSlot(int slot) {
        loadSlot(slot);
        if (this.nameField != null) {
            this.nameField.setCurrentString(this.editName);
        }
        this.statusText = "§7已选中槽位 §f" + (slot + 1) + " §8(" + definedWord(slot) + ")";
        refreshCells();
        com.shanhai.ShanhaiMod.LOGGER.info("{} panel_select slot={} defined={} name_len={}",
                ShanhaiRealityItemTable.PREFIX, slot + 1, ShanhaiRealityItemTable.isDefined(slot),
                this.editName == null ? 0 : this.editName.length());
    }

    private void refreshCells() {
        for (int i = 0; i < this.cells.length; i++) {
            final ShanhaiCreativeWidgets.IconCell cell = this.cells[i];
            if (cell == null) {
                continue;
            }
            cell.selected(i == this.editSlot);
            cell.dim(!ShanhaiRealityItemTable.isDefined(i));
        }
    }

    // ------------------------------------------------------------------ 图标

    private void openPicker() {
        if (this.picker != null) {
            this.picker.open();
        }
    }

    /** 选择器按了「接受」⇒ 写回当前槽位<b>并立刻生效</b>（= 走一次保存）。 */
    void acceptIcon(String icon) {
        this.editIcon = ShanhaiRealityItemTable.sanitizeIcon(icon);
        save("§a图标已应用");
    }

    /** 提示编辑器按了「接受」⇒ 写回当前槽位<b>并立刻生效</b>（= 走一次保存）。 */
    void acceptLore(String lore) {
        String v = lore == null ? "" : lore.trim();
        if (v.length() > ShanhaiRealityItemTable.MAX_LORE_CHARS) {
            v = v.substring(0, ShanhaiRealityItemTable.MAX_LORE_CHARS);
        }
        this.editLore = v;
        com.shanhai.ShanhaiMod.LOGGER.info("{} lore_accept slot={} lines={} chars={}",
                ShanhaiRealityItemTable.PREFIX, this.editSlot + 1,
                v.isEmpty() ? 0 : v.split("\n", -1).length, v.length());
        save(v.isEmpty() ? "§e提示已清空" : "§a提示已保存");
    }

    /** 面板上那行提示摘要（"（空）" 或 "3 行 · 第一行…"）。 */
    private String loreSummary() {
        final String lore = this.editLore == null ? "" : this.editLore;
        if (lore.isBlank()) {
            return "§8（空）";
        }
        final String[] parts = lore.split("\\R", -1);
        int gated = 0;
        String first = "";
        for (String raw : parts) {
            String line = raw;
            if (line.startsWith(com.shanhai.item.RealityShellItem.TAG_SHIFT)) {
                gated++;
                line = line.substring(com.shanhai.item.RealityShellItem.TAG_SHIFT.length());
            } else if (line.startsWith(com.shanhai.item.RealityShellItem.TAG_CTRL)) {
                gated++;
                line = line.substring(com.shanhai.item.RealityShellItem.TAG_CTRL.length());
            }
            if (first.isEmpty() && !line.isBlank()) {
                first = line;
            }
        }
        final String head = first.length() > 22 ? first.substring(0, 22) + "…" : first;
        return "§8" + parts.length + " 行" + (gated > 0 ? "（" + gated + " 带条件）" : "") + " §7" + head;
    }

    /** 「取手上物品的图标」：拿着某件东西点一下就直接采用它。 */
    private void takeHeldIcon() {
        if (!this.remote) {
            return;
        }
        final ItemStack held = this.player == null ? ItemStack.EMPTY
                : this.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held == null || held.isEmpty()) {
            this.statusText = "§c手上没有物品";
            return;
        }
        if (held.getItem() instanceof RealityShellItem) {
            this.statusText = "§c不能借现实物品自己的图标（会递归）";
            return;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(held.getItem());
        if (id == null) {
            this.statusText = "§c这个物品没有 id（注册表异常）";
            return;
        }
        this.editIcon = "item:" + id;
        save("§a已采用手上物品的图标：" + id);
    }

    private ItemStack iconPreviewStack() {
        final String icon = ShanhaiRealityItemTable.sanitizeIcon(this.editIcon);
        final String id = icon.startsWith("item:") ? icon.substring("item:".length()) : "";
        final ResourceLocation rl = id.isEmpty() ? null : ResourceLocation.tryParse(id);
        if (rl != null) {
            final Item it = BuiltInRegistries.ITEM.get(rl);
            if (it != null && it != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(it);
            }
        }
        return stackOf(this.editSlot);
    }

    private String iconLine() {
        final String icon = ShanhaiRealityItemTable.sanitizeIcon(this.editIcon);
        if (icon.isEmpty()) {
            return "§8兜底色块 #" + Math.floorMod(this.editSlot,
                    ShanhaiRealityItemTable.BUILTIN_ICON_COUNT);
        }
        return "§8" + (icon.length() > 22 ? icon.substring(0, 22) + "…" : icon);
    }

    // ------------------------------------------------------------------ 稀有度 / 堆叠

    private void cycleRarity() {
        final String[] names = ShanhaiRealityItemTable.RARITY_NAMES;
        int idx = 0;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(ShanhaiRealityItemTable.sanitizeRarity(this.editRarity))) {
                idx = i;
                break;
            }
        }
        this.editRarity = names[(idx + 1) % names.length];
        this.statusText = "§7稀有度：§f" + RealityShellItem.rarityZh(this.editRarity);
    }

    // ------------------------------------------------------------------ 堆叠

    /** 文本 → 合法堆叠数（非法/越界一律回落到 1..64 区间内的最近值；解析不出返回 0）。 */
    private static int parseStack(String text) {
        if (text == null) {
            return 0;
        }
        final String t = text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            final int v = Integer.parseInt(t);
            return Math.max(ShanhaiRealityItemTable.MIN_STACK,
                    Math.min(v, ShanhaiRealityItemTable.MAX_STACK));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 把文本框里【此刻】的内容算成堆叠数（保存时用这一份；解析不出就沿用缓冲值）。 */
    private int liveStack() {
        if (this.stackField == null) {
            return this.editStack;
        }
        final int v = parseStack(this.stackField.getCurrentString());
        return v > 0 ? v : this.editStack;
    }

    private void setStackValue(int value) {
        this.editStack = Math.max(ShanhaiRealityItemTable.MIN_STACK,
                Math.min(value, ShanhaiRealityItemTable.MAX_STACK));
        if (this.stackField != null) {
            this.stackField.setCurrentString(Integer.toString(this.editStack));
        }
    }

    private void bumpStack(int delta) {
        setStackValue(liveStack() + delta);
    }

    // ------------------------------------------------------------------ 保存 / 清空 / 关闭

    /** 把整包字段发给服务端（服务端才是落盘那一侧）。 */
    private void save(String note) {
        if (!this.remote) {
            return;      // 服务端那份面板不做这件事（唯一入口是 C2S 消息）
        }
        // 🔴 2026-10-17：保存这一刻再从控件现取一次（不依赖 updateScreen 的时序/responder）。
        final String liveName = this.nameField == null ? null : this.nameField.getCurrentString();
        if (liveName != null) {
            this.editName = liveName;
        }
        // 堆叠同理：文本框里此刻是什么就存什么（用户报"改堆叠数没用"就是这条读不到）
        final int fieldStack = this.stackField == null ? -1
                : parseStack(this.stackField.getCurrentString());
        if (fieldStack > 0) {
            this.editStack = fieldStack;
        }
        com.shanhai.ShanhaiMod.LOGGER.info(
                "{} save_live slot={} field_name_len={} edit_name_len={} edit_lore_len={} field_stack={} edit_stack={}",
                ShanhaiRealityItemTable.PREFIX, this.editSlot + 1,
                liveName == null ? -1 : liveName.length(),
                this.editName == null ? -1 : this.editName.length(),
                this.editLore == null ? -1 : this.editLore.length(),
                this.stackField == null ? "?" : this.stackField.getCurrentString(),
                this.editStack);
        ShanhaiCreativeItemsNetwork.requestSave(this.editSlot, this.editName, this.editIcon,
                this.editStack, this.editRarity, this.editLore);
        this.statusText = (this.editStack > ShanhaiRealityItemTable.DEFAULT_STACK
                ? "§c⚠ >64 可能出现未知 bug §r" : "") + note + " §8→ 槽位 " + (this.editSlot + 1);
    }

    /** 清空这一格的名字 ⇒ 退回默认壳（不删物品）。 */
    private void clearSlot() {
        this.editName = "";
        this.editIcon = "";
        this.editStack = ShanhaiRealityItemTable.DEFAULT_STACK;
        this.editRarity = "common";
        this.editLore = "";
        if (this.nameField != null) {
            this.nameField.setCurrentString("");
        }
        if (!this.remote) {
            return;
        }
        ShanhaiCreativeItemsNetwork.requestSave(this.editSlot, "", "", ShanhaiRealityItemTable.DEFAULT_STACK,
                "common", "");
        this.statusText = "§e已清空槽位 " + (this.editSlot + 1) + " §8（物品还在，只是退回默认壳）";
    }

    private void closePanel() {
        if (!this.remote) {
            return;
        }
        if (this.player != null) {
            this.player.closeContainer();
        }
    }

    // ------------------------------------------------------------------ 文本

    private String slotLine() {
        return "§7槽位 §f" + (this.editSlot + 1) + " §8· shanhai:reality_item_" + (this.editSlot + 1)
                + " §8· " + definedWord(this.editSlot);
    }

    private String statusLine() {
        return this.statusText == null ? "" : this.statusText;
    }

    private String definedWord(int slot) {
        return ShanhaiRealityItemTable.isDefined(slot) ? "§a已定义" : "§8未定义";
    }

    private ItemStack stackOf(int slot) {
        final Item it = ShanhaiItems.realityItemValue(slot);
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    private List<Component> tooltipOf(int slot) {
        final boolean defined = ShanhaiRealityItemTable.isDefined(slot);
        return List.of(
                Component.literal("§f槽位 " + (slot + 1) + " §8· " + (defined ? "§a已定义" : "§8未定义")),
                Component.literal("§7" + ShanhaiRealityItemTable.displayName(slot)),
                Component.literal("§8shanhai:reality_item_" + (slot + 1)),
                Component.literal("§7点一下编辑这一格"));
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 🆕 2026-10-17：<b>关掉本页的两层覆盖（提示编辑器 / 图标选择器）</b>。
     *
     * <p>只给外层「三页签容器」在切页时调用 —— 它自己一行行为都不改：原来没有任何路径会调它，
     * 两个覆盖层开关的写法与原来逐字一致。
     */
    public void closeOverlays() {
        if (this.loreEditor != null) {
            this.loreEditor.close();
        }
        if (this.picker != null) {
            this.picker.close();
        }
    }

    /** 本页的两个覆盖层有没有一个是开着的（切页时外层用它决定要不要拦一下）。 */
    public boolean anyOverlayOpen() {
        return (this.loreEditor != null && this.loreEditor.isVisible())
                || (this.picker != null && this.picker.isVisible());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.remote) {
            return;
        }
        // 🔴 2026-10-17 修（用户报"点保存根本没用"）：LDLib 的 TextFieldWidget 把输入先走
        // client→server 同步、textResponder 不一定在【客户端这份】上被调 ⇒ this.editName 永远是空
        // （日志实测 `save_sent slot=1 name_len=0`，而屏幕上名字是打出来的）。
        // ⇒ 客户端每 tick 从控件里【现取】文本，保存时读的就是屏幕上看到的内容。
        if (this.nameField != null) {
            final String liveName = this.nameField.getCurrentString();
            if (liveName != null && !liveName.equals(this.editName)) {
                this.editName = liveName;
            }
        }
        // 堆叠框同样每 tick 现取（＋/－ 按钮已经直接改缓冲，这里兜"手打数字"那条路）
        if (this.stackField != null) {
            final int live = parseStack(this.stackField.getCurrentString());
            if (live > 0 && live != this.editStack) {
                this.editStack = live;
            }
        }
        // 表被别人改了（服务端广播整表）⇒ 槽位格子的"已定义/未定义"要跟着变
        final int v = ShanhaiRealityItemTable.version();
        if (v != this.seenVersion) {
            this.seenVersion = v;
            refreshCells();
        }
    }

    /** 供命令 / 自检读的一行版式读数。 */
    public static String layoutLine() {
        return "panel " + W + "x" + H + " grid=" + GRID_COLS + "x" + GRID_ROWS + " cell=" + CELL
                + " slots=" + ShanhaiRealityItemTable.SLOT_COUNT
                + " edit=client_side save=network(S2C sync)";
    }
}
