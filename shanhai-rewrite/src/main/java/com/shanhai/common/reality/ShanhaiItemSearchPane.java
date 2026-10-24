package com.shanhai.common.reality;

import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 🆕 2026-10-17：<b>「搜物品 ＋ 结果列表」那一块</b>（「修改」页与「删除」页共用同一份控件逻辑）。
 *
 * <h2>它是什么、不是什么</h2>
 * <ul>
 *   <li><b>不是</b> WidgetGroup：它只把控件<b>直接</b>塞进调用方的页组里。
 *       LDLib 的坐标是 {@code 绝对 = 父组位置 + 自身位置}（{@code Widget#recomputePosition}），
 *       多套一层组就多一处"位置什么时候重算"的未知数；直接加进页组最稳。</li>
 *   <li><b>是</b> 一份独立的状态：搜索词 / 当前页 / 选中项 / 过滤结果。两个页各自 {@code new} 一个
 *       ⇒ 切页时各页的搜索词和选中项<b>互不影响</b>（用户验收第 4 条要的就是这个）。</li>
 * </ul>
 *
 * <h2>🔴 三条本工程踩过的规矩（照抄注释里的既有写法）</h2>
 * <ol>
 *   <li>{@code LabelWidget} 的 supplier <b>不逐帧重取</b> ⇒ 计数/页码一律 {@code setText} 现写；</li>
 *   <li>{@code TextFieldWidget} 的 {@code textResponder} <b>不一定在客户端这份上被调</b>
 *       ⇒ 每 tick 用 {@link TextFieldWidget#getCurrentString()} 现取（见 {@link #tick()}）；</li>
 *   <li><b>可见控件数 = 数据条数</b>：翻页/过滤后没数据的行 {@code setVisible(false)}，不是留个空壳。</li>
 * </ol>
 */
final class ShanhaiItemSearchPane {

    /** 一页几行。 */
    static final int ROWS = 5;

    /** 列表块的固定版式（相对页组）。 */
    static final int SEARCH_Y = 16;
    static final int LIST_Y = 36;
    static final int PAGER_Y = LIST_Y + ROWS * ShanhaiCreativeWidgets.ItemRow.ROW_H + 2;

    /** 全局候选（客户端建一次，两个页共用 —— 建表要遍历上万件物品，别建两遍）。 */
    private static List<ItemStack> CACHE;

    /** 选中回调（收到的一定是"注册表里真有这件物品"的 id）。 */
    interface Pick {
        void onPick(ResourceLocation id);
    }

    private final boolean remote;
    private final Pick pick;
    private final ShanhaiCreativeWidgets.ItemRow[] rows = new ShanhaiCreativeWidgets.ItemRow[ROWS];

    private final List<ItemStack> filtered = new ArrayList<>();
    private String search = "";
    private int page;
    /** 选中项在 {@link #filtered} 里的下标（-1 = 没选）。 */
    private int picked = -1;

    private TextFieldWidget searchField;
    private LabelWidget countLabel;

    ShanhaiItemSearchPane(boolean remote, Pick pick) {
        this.remote = remote;
        this.pick = pick;
    }

    // ------------------------------------------------------------------ 装配

    /** 把控件加进 page（坐标按本类的固定版式，y 都是相对页组）。 */
    void install(WidgetGroup page) {
        page.addWidget(new LabelWidget(6, SEARCH_Y - 12, "§7搜索（按物品 id 子串，不分大小写）"));
        this.searchField = new TextFieldWidget(6, SEARCH_Y, 236, 16, () -> this.search, value -> {
            // 回调照留（有些环境会走），真值以 tick() 里现取的为准
        });
        this.searchField.setMaxStringLength(64);
        page.addWidget(this.searchField);
        page.addWidget(new ShanhaiCreativeWidgets.TextButton(246, SEARCH_Y, 88, 16,
                () -> "§f清除搜索", this::clearSearch));

        for (int i = 0; i < ROWS; i++) {
            final int row = i;
            final ShanhaiCreativeWidgets.ItemRow item = new ShanhaiCreativeWidgets.ItemRow(
                    6, LIST_Y + i * ShanhaiCreativeWidgets.ItemRow.ROW_H, 328);
            item.stack(() -> stackAt(row));
            item.title(() -> titleAt(row));
            item.sub(() -> idAt(row));
            item.onClick(() -> click(row));
            page.addWidget(item);
            this.rows[i] = item;
        }

        page.addWidget(new ShanhaiCreativeWidgets.TextButton(6, PAGER_Y, 74, 14,
                () -> "◀ 上一页", () -> turnPage(-1)));
        page.addWidget(new ShanhaiCreativeWidgets.TextButton(84, PAGER_Y, 74, 14,
                () -> "下一页 ▶", () -> turnPage(1)));
        this.countLabel = new LabelWidget(164, PAGER_Y + 3, "§8共 0 个");
        page.addWidget(this.countLabel);

        this.refresh();
    }

    // ------------------------------------------------------------------ 数据

    /** 全量候选（id 字典序）。排除 {@code minecraft:air}。 */
    private static List<ItemStack> candidates() {
        if (CACHE != null) {
            return CACHE;
        }
        final List<ItemStack> out = new ArrayList<>();
        try {
            for (Item item : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues()) {
                if (item == null || item == Items.AIR) {
                    continue;
                }
                out.add(new ItemStack(item));
            }
            out.sort(Comparator.comparing(s -> String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()))));
        } catch (Throwable t) {
            com.shanhai.ShanhaiMod.LOGGER.error("[SHANHAI-ITEMEDIT] search_candidates_failed err={}",
                    t.toString(), t);
        }
        CACHE = out;
        return out;
    }

    private void refresh() {
        if (!this.remote) {
            return;      // 服务端那份页组只用来对齐容器，不建这张表
        }
        this.filtered.clear();
        final String q = this.search == null ? "" : this.search.trim().toLowerCase(Locale.ROOT);
        for (ItemStack s : candidates()) {
            if (q.isEmpty() || idOf(s).toLowerCase(Locale.ROOT).contains(q)) {
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
        for (int i = 0; i < ROWS; i++) {
            final int idx = this.page * ROWS + i;
            final boolean on = idx < this.filtered.size();
            this.rows[i].setVisible(on);
            this.rows[i].selected(on && idx == this.picked);
            this.rows[i].dim(on && isReserved(this.filtered.get(idx)));
        }
        if (this.countLabel != null) {
            this.countLabel.setText("§8共 §f" + this.filtered.size() + " §8个 · 第 §f" + (this.page + 1)
                    + "§8/§f" + pages + " §8页");
        }
    }

    private int pageCount() {
        return Math.max(1, (this.filtered.size() + ROWS - 1) / ROWS);
    }

    private void turnPage(int delta) {
        this.page += delta;
        this.refresh();
    }

    private void clearSearch() {
        this.search = "";
        this.page = 0;
        this.picked = -1;
        if (this.searchField != null) {
            this.searchField.setCurrentString("");
        }
        this.refresh();
    }

    private void click(int row) {
        final int idx = this.page * ROWS + row;
        if (idx < 0 || idx >= this.filtered.size()) {
            return;
        }
        this.picked = idx;
        for (int i = 0; i < ROWS; i++) {
            this.rows[i].selected(this.page * ROWS + i == this.picked);
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(this.filtered.get(idx).getItem());
        if (id != null && this.pick != null) {
            this.pick.onPick(id);
        }
    }

    /**
     * 每 tick 由页组调：从搜索框<b>现取</b>文本（不依赖 {@code textResponder}）。
     */
    void tick() {
        if (!this.remote || this.searchField == null) {
            return;
        }
        final String live = this.searchField.getCurrentString();
        if (live != null && !live.equals(this.search)) {
            this.search = live;
            this.page = 0;
            this.picked = -1;
            this.refresh();
            com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-ITEMEDIT] search q=\"{}\" hits={}",
                    this.search, this.filtered.size());
        }
    }

    // ------------------------------------------------------------------ 取值

    private ItemStack stackAt(int row) {
        final int idx = this.page * ROWS + row;
        return idx < this.filtered.size() ? this.filtered.get(idx) : ItemStack.EMPTY;
    }

    private String titleAt(int row) {
        final ItemStack s = stackAt(row);
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return s.getHoverName().getString();
        } catch (Throwable t) {
            return idOf(s);
        }
    }

    private String idAt(int row) {
        final ItemStack s = stackAt(row);
        return s == null || s.isEmpty() ? "" : idOf(s);
    }

    static String idOf(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return id == null ? "" : id.toString();
    }

    /** 界面侧也要认得出这 128 个壳（后端已经拒，界面给灰字提示）。 */
    static boolean isReserved(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return com.shanhai.common.itemedit.ShanhaiItemOverrideTable.isReserved(
                BuiltInRegistries.ITEM.getKey(s.getItem()));
    }

    /** 页组读数（自检/日志用）。 */
    static String layoutLine() {
        return "item_search rows=" + ROWS + " row_h=" + ShanhaiCreativeWidgets.ItemRow.ROW_H
                + " search_y=" + SEARCH_Y + " list_y=" + LIST_Y + " pager_y=" + PAGER_Y
                + " filter=id_contains(case_insensitive)";
    }
}
