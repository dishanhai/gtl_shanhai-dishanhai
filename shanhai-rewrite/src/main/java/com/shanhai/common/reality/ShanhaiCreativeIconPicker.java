package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 图标选择器（覆盖在「自己新增物品」面板上的一层）。<b>交互逻辑照 FTB Quests 的
 * {@code SelectQuestObjectScreen}（数据库物品选择）那一套</b>，只借交互、不引依赖。
 *
 * <h2>照搬的四条交互（用户点单）</h2>
 * <ol>
 *   <li><b>顶部搜索框实时过滤</b> —— 中文显示名 或 id 都能搜（他平时按中文名找东西）；清空即回到全量；</li>
 *   <li><b>下面一大片物品网格</b> —— 每行 8 格、18px 图标、有翻页（见下"为什么是翻页不是滚动条"）、
 *       hover 出物品名 tooltip；</li>
 *   <li><b>点一格 = 选中</b>，被选的那格高亮；顶部有"当前已选"的预览格（<b>不显示数量</b>）；</li>
 *   <li><b>底部「接受 / 取消」</b> —— 只有「接受」才把图标写回当前槽位并立刻生效；「取消」什么都不改。</li>
 * </ol>
 *
 * <h2>🔴 为什么是【翻页】而不是滚动条</h2>
 * LDLib 有 {@code DraggableScrollableWidgetGroup}，但本工程既有界面的分页写法（配方编辑器）已经
 * 在真机上验过，而"滚动组里放 80 个自绘控件"没验过；本屏的判据是<b>"能不能选到东西"</b>，
 * 所以选低风险那条。用户原话是"有滚动条/翻页"（两种都行）。
 *
 * <h2>🔴 候选从哪里来（报告单独一节要的答案）</h2>
 * 数据源 = <b>{@code ForgeRegistries.ITEMS}</b>（全部已注册物品），<b>不是</b>创造栏那一批。
 * 理由：
 * <ul>
 *   <li>创造栏那一批是本 mod 自己列的（{@code ShanhaiCreativeModeTabs#fill}），里面有机器定义、
 *       流体桶这些"创造栏里才有"的东西；而<b>图标只能借已注册物品的贴图</b>，
 *       {@code ForgeRegistries.ITEMS} 才是"已注册物品"的完备集合；</li>
 *   <li>创造栏的 displayItems 是<b>缓存过的</b>（重建时机由我们控制），拿它当数据源会把
 *       "图标选择器的候选"绑到"创造栏什么时候重建"上 —— 平白多一条依赖。</li>
 * </ul>
 * 排除项：{@code minecraft:air}（不是东西）、<b>本 mod 的 128 个现实物品壳</b>
 * （它们的图标本身来自这张表，选它等于递归；报告里写明了）。
 * <b>未定义的槽位本来就不在候选里</b>（因为它们就是被排除的那 128 个壳），
 * 定义过的槽位也不在（同样排除）—— 这不违反第 ⑧ 条：第 ⑧ 条管的是"创造栏/JEI 里看不看得见"，
 * 而这里只管"能不能借它的图标"。
 *
 * <h2>🔴 为什么没有数量栏（报告单独一节要的答案）</h2>
 * 因为这里选的是<b>图标（一张贴图）</b>，不是"往槽位里塞一个物品栈"。
 * 数量属于 {@code ItemStack}，而占位物品的堆叠上限是<b>槽位自己的字段</b>（面板上那个「堆叠」输入框）。
 * 把数量放进图标选择器会让人以为"选 3 个钻石图标就给我 3 个钻石"，那是错的。
 */
public final class ShanhaiCreativeIconPicker extends WidgetGroup {

    /** 网格列数（用户点单：每行 8 格左右）。 */
    private static final int COLS = 8;
    /** 网格行数。 */
    private static final int ROWS = 10;
    /** 每格步进（18px 图标 + 2px 间隙）。 */
    private static final int STEP = 20;
    /** 每页格数。 */
    private static final int PAGE = COLS * ROWS;

    private static final int GRID_X = 6;
    private static final int GRID_Y = 66;

    private final ShanhaiCreativeItemsPanel panel;

    private final ShanhaiCreativeWidgets.IconCell[] cells = new ShanhaiCreativeWidgets.IconCell[PAGE];
    private final ItemStack[] pageStacks = new ItemStack[PAGE];

    /** 全量候选（第一次打开时才建）。 */
    private final List<ItemStack> all = new ArrayList<>();
    /** 过滤后的候选。 */
    private final List<ItemStack> filtered = new ArrayList<>();

    private String search = "";
    private int page;
    private int picked = -1;

    private TextFieldWidget searchField;
    private ShanhaiCreativeWidgets.Preview preview;
    private LabelWidget previewLabel;
    private LabelWidget countLabel;
    private LabelWidget emptyLabel;

    public ShanhaiCreativeIconPicker(ShanhaiCreativeItemsPanel panel) {
        super(0, 0, ShanhaiCreativeItemsPanel.W, ShanhaiCreativeItemsPanel.H);
        this.panel = panel;
        this.setBackground(GuiTextures.BACKGROUND);

        this.addWidget(new LabelWidget(6, 4, "§f选择图标 §7（点一格选中，再按「接受」）"));
        this.searchField = new TextFieldWidget(6, 20, 240, 16, () -> this.search, value -> {
            this.search = value == null ? "" : value;
            this.page = 0;
            this.picked = -1;
            this.refresh();
        });
        this.searchField.setMaxStringLength(48);
        this.addWidget(this.searchField);
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(250, 20, 60, 16, () -> "清除搜索", () -> {
            this.search = "";
            this.page = 0;
            this.picked = -1;
            if (this.searchField != null) {
                this.searchField.setCurrentString("");
            }
            this.refresh();
        }));

        this.addWidget(new LabelWidget(6, 44, "§7当前已选："));
        this.preview = new ShanhaiCreativeWidgets.Preview(76, 42);
        this.preview.stack(this::pickedStack);
        this.addWidget(this.preview);
        this.previewLabel = new LabelWidget(98, 46, this::pickedText);
        this.addWidget(this.previewLabel);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                final int j = r * COLS + c;
                final ShanhaiCreativeWidgets.IconCell cell = new ShanhaiCreativeWidgets.IconCell(
                        GRID_X + c * STEP, GRID_Y + r * STEP);
                cell.stack(() -> this.pageStacks[j]);
                cell.tooltip(() -> this.tooltipOf(j));
                cell.onClick(() -> this.click(j));
                this.addWidget(cell);
                this.cells[j] = cell;
            }
        }

        this.addWidget(new LabelWidget(176, 66, "§7提示：中文名或 id 都能搜"));
        this.countLabel = new LabelWidget(176, 84, this::countText);
        this.addWidget(this.countLabel);
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(176, 104, 74, 18,
                () -> "◀ 上一页", this::prevPage));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(254, 104, 74, 18,
                () -> "下一页 ▶", this::nextPage));
        this.emptyLabel = new LabelWidget(176, 130, this::emptyText);
        this.addWidget(this.emptyLabel);

        this.addWidget(new LabelWidget(6, 272, "§8只有按「接受」才会把图标写回槽位并立刻生效"));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(176, 268, 74, 18, () -> "§a接受", this::accept));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(254, 268, 74, 18, () -> "§c取消", this::cancel));

        this.setVisible(false);
        this.setActive(false);
    }

    // ------------------------------------------------------------------ 打开 / 关闭

    /** 打开选择器（客户端）。每次打开都清空搜索、回到第 1 页、没有选中项。 */
    public void open() {
        this.search = "";
        this.page = 0;
        this.picked = -1;
        if (this.searchField != null) {
            this.searchField.setCurrentString("");
        }
        this.ensureCandidates();
        this.refresh();
        this.setVisible(true);
        this.setActive(true);
        com.shanhai.ShanhaiMod.LOGGER.info("{} icon_picker_open candidates={}",
                ShanhaiRealityItemTable.PREFIX, this.all.size());
    }

    public void close() {
        this.setVisible(false);
        this.setActive(false);
    }

    /** 建立全量候选（只建一次）。 */
    private void ensureCandidates() {
        if (!this.all.isEmpty()) {
            return;
        }
        try {
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                if (item == null || item == Items.AIR) {
                    continue;
                }
                if (item instanceof com.shanhai.item.RealityShellItem) {
                    // 🔴 排除本 mod 的 128 个壳（图标来自同一张表，借它会递归）
                    continue;
                }
                this.all.add(new ItemStack(item));
            }
            this.all.sort(Comparator.comparing(s -> String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem()))));
            com.shanhai.ShanhaiMod.LOGGER.info("{} icon_picker_candidates total={}",
                    ShanhaiRealityItemTable.PREFIX, this.all.size());
        } catch (Throwable t) {
            com.shanhai.ShanhaiMod.LOGGER.error("{} icon_picker_candidates_failed err={}",
                    ShanhaiRealityItemTable.PREFIX, t.toString(), t);
        }
    }

    // ------------------------------------------------------------------ 过滤 / 分页

    private void refresh() {
        this.filtered.clear();
        final String q = this.search == null ? "" : this.search.trim().toLowerCase(Locale.ROOT);
        for (ItemStack s : this.all) {
            if (q.isEmpty() || idOf(s).toLowerCase(Locale.ROOT).contains(q)
                    || nameOf(s).toLowerCase(Locale.ROOT).contains(q)) {
                this.filtered.add(s);
            }
        }
        final int pages = this.pageCount();
        if (this.page >= pages) {
            this.page = pages - 1;
        }
        if (this.page < 0) {
            this.page = 0;
        }
        if (this.picked >= this.filtered.size()) {
            this.picked = -1;
        }
        for (int j = 0; j < PAGE; j++) {
            final int idx = this.page * PAGE + j;
            this.pageStacks[j] = idx < this.filtered.size() ? this.filtered.get(idx) : ItemStack.EMPTY;
            this.cells[j].selected(idx == this.picked && idx < this.filtered.size());
        }
    }

    private int pageCount() {
        return Math.max(1, (this.filtered.size() + PAGE - 1) / PAGE);
    }

    private void prevPage() {
        if (this.page > 0) {
            this.page--;
            this.picked = -1;
            this.refresh();
        }
    }

    private void nextPage() {
        if (this.page + 1 < this.pageCount()) {
            this.page++;
            this.picked = -1;
            this.refresh();
        }
    }

    private void click(int j) {
        final int idx = this.page * PAGE + j;
        if (idx >= this.filtered.size()) {
            return;                       // 空格子：点了没反应（不是"选了空气"）
        }
        this.picked = idx;
        for (int k = 0; k < PAGE; k++) {
            final int other = this.page * PAGE + k;
            this.cells[k].selected(other == this.picked);
        }
    }

    // ------------------------------------------------------------------ 接受 / 取消

    private void accept() {
        final ItemStack stack = this.pickedStack();
        if (stack == null || stack.isEmpty()) {
            this.close();
            return;
        }
        final ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            this.close();
            return;
        }
        com.shanhai.ShanhaiMod.LOGGER.info("{} icon_picker_accept item={}", ShanhaiRealityItemTable.PREFIX, id);
        this.panel.acceptIcon("item:" + id);
        this.close();
    }

    private void cancel() {
        com.shanhai.ShanhaiMod.LOGGER.info("{} icon_picker_cancel（什么都不改）", ShanhaiRealityItemTable.PREFIX);
        this.close();
    }

    // ------------------------------------------------------------------ 文本 / 取值

    private ItemStack pickedStack() {
        return this.picked >= 0 && this.picked < this.filtered.size()
                ? this.filtered.get(this.picked) : ItemStack.EMPTY;
    }

    private String pickedText() {
        final ItemStack s = this.pickedStack();
        if (s == null || s.isEmpty()) {
            return "§8（还没选）";
        }
        final String name = nameOf(s);
        return "§f" + (name.length() > 20 ? name.substring(0, 20) + "…" : name);
    }

    private String countText() {
        return "§7共 §f" + this.filtered.size() + " §7个 · 第 §f" + (this.page + 1)
                + "§7/§f" + pageCount() + " §7页";
    }

    private String emptyText() {
        return this.filtered.isEmpty() ? "§c没有匹配的物品" : "";
    }

    private List<net.minecraft.network.chat.Component> tooltipOf(int j) {
        final ItemStack s = this.pageStacks[j];
        if (s == null || s.isEmpty()) {
            return List.of();
        }
        return List.of(net.minecraft.network.chat.Component.literal("§f" + nameOf(s)),
                net.minecraft.network.chat.Component.literal("§8" + idOf(s)));
    }

    private static String idOf(ItemStack s) {
        final ResourceLocation id = s == null ? null : ForgeRegistries.ITEMS.getKey(s.getItem());
        return id == null ? "" : id.toString();
    }

    private static String nameOf(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return s.getHoverName().getString();
        } catch (Throwable t) {
            return idOf(s);
        }
    }

    /** 给面板的读数用：当前候选数。 */
    public int candidateCount() {
        return this.all.size();
    }

    /** 供面板/自检读的一行状态。 */
    public static String layoutLine() {
        return "icon_picker cols=" + COLS + " rows=" + ROWS + " per_page=" + PAGE
                + " cell=18 step=" + STEP + " search=name|id";
    }

    /** 一个永远返回空串的供应器（给"没有内容就不显示"的标签用）。 */
    static Supplier<String> blank() {
        return () -> "";
    }

    /**
     * 🔴 <b>整层吃掉点击</b>：选择器铺满整个面板。若不吞掉"落在它身上但没打中任何格子"的那一下，
     * 点击会穿到底层（槽位格 / 保存按钮 / 关闭按钮）—— 用户会看到"我在选择器里点了一下，
     * 后面的槽位怎么变了"。子控件照旧先收（{@code super} 里派发）。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }

    /**
     * 🔴 2026-10-17 修（用户报"搜索也没用"）：同 {@code ShanhaiCreativeItemsPanel#updateScreen}
     * —— LDLib 的 {@code TextFieldWidget} 的 {@code textResponder} 不一定在客户端这份上被调，
     * 所以 {@code this.search} 永远停在空串、过滤从不发生。这里每 tick 从控件【现取】文本。
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.searchField == null) {
            return;
        }
        final String live = this.searchField.getCurrentString();
        if (live != null && !live.equals(this.search)) {
            this.search = live;
            this.page = 0;
            this.picked = -1;
            this.refresh();
            com.shanhai.ShanhaiMod.LOGGER.info("{} icon_picker_search q=\"{}\" hits={}",
                    ShanhaiRealityItemTable.PREFIX, this.search, this.filtered.size());
        }
    }
}
