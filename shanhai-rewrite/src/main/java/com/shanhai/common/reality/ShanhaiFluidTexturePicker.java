package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 「流体 still / flowing 贴图选择器」——盖在流体页上的一层。
 *
 * <h2>🔴 交互范式是<b>照抄</b>{@link ShanhaiCreativeIconPicker} 的（用户点单：别新造一套）</h2>
 * <ol>
 *   <li>顶部搜索框实时过滤（中文名 或 id 都能搜）；</li>
 *   <li>下面 8 列 × 10 行 = 80 格的网格 ＋ <b>翻页</b>（不引入滚动条：本工程既有分页写法在真机上验过）；</li>
 *   <li>点一格 = 选中，被选那格高亮；左上"当前已选"有预览（<b>带当前 tint</b>，所见即所得）；</li>
 *   <li>底部「接受 / 取消」——只有「接受」才写回当前流体槽位并立刻生效。</li>
 * </ol>
 *
 * <h2>🔴 候选从哪来（报告专门要的一条）</h2>
 * <b>{@code ForgeRegistries.FLUIDS} 现列</b>，逐个问
 * {@code IClientFluidTypeExtensions#getStillTexture()} / {@code getFlowingTexture()}，
 * 按 still+flow 去重、按 id 排序。
 * 这样做的三个理由：
 * <ul>
 *   <li>用户要求"从<b>现成</b>的流体贴图里选，比如本工程那 25 个"——
 *       本工程那 25 个、原版水/岩浆、以及<b>任何其它模组</b>注册的流体都自动在列表里；</li>
 *   <li><b>绝不写死</b>成"只有我们 25 个"（上游的配方编辑器也在做"从 ForgeRegistries.FLUIDS 现列"，
 *       两边口径一致才不会出现"配方里能选、贴图里选不到"）；</li>
 *   <li>贴图路径就是 {@code FluidStack} 渲染用的那一条 ⇒ 选到的贴图<b>一定</b>是能画的那个。</li>
 * </ul>
 *
 * <h2>只在客户端建候选</h2>
 * 本层的 {@link #open()} 只会被客户端点击触发；{@code IClientFluidTypeExtensions} 是客户端接口，
 * 与 {@code ShanhaiIOWidget#drawFluidSprite} 同一条口径（服务端那份控件树只对齐容器，不建候选）。
 */
final class ShanhaiFluidTexturePicker extends WidgetGroup {

    /** 网格列数 / 行数 / 步进 / 每页格数（与图标选择器同一个尺寸）。 */
    private static final int COLS = 8;
    private static final int ROWS = 10;
    private static final int STEP = 20;
    private static final int PAGE = COLS * ROWS;
    private static final int GRID_X = 6;
    private static final int GRID_Y = 66;

    /** 一个候选：一套 still+flow（来自某个已注册流体）。 */
    record Candidate(String id, String name, String still, String flow) {

        String key() {
            return this.still + '|' + this.flow;
        }
    }

    private final ShanhaiFluidEditorPage page;

    private final ShanhaiFluidWidgets.FluidCell[] cells = new ShanhaiFluidWidgets.FluidCell[PAGE];
    private final Candidate[] pageItems = new Candidate[PAGE];

    /** 全量候选（第一次打开时才建）。 */
    private final List<Candidate> all = new ArrayList<>();
    /** 过滤后的候选。 */
    private final List<Candidate> filtered = new ArrayList<>();

    private String search = "";
    private int pageIndex;
    private int picked = -1;

    private TextFieldWidget searchField;
    private ShanhaiFluidWidgets.FluidSwatch preview;
    private LabelWidget previewLabel;
    private LabelWidget countLabel;
    private LabelWidget emptyLabel;

    ShanhaiFluidTexturePicker(ShanhaiFluidEditorPage page) {
        super(0, 0, ShanhaiFluidEditorPage.W, ShanhaiFluidEditorPage.H);
        this.page = page;
        this.setBackground(GuiTextures.BACKGROUND);

        this.addWidget(new LabelWidget(6, 4, "§f选择流体贴图 §7（点一格选中，再按「接受」）"));
        this.searchField = new TextFieldWidget(6, 20, 240, 16, () -> this.search, value -> {
            this.search = value == null ? "" : value;
            this.pageIndex = 0;
            this.picked = -1;
            refresh();
        });
        this.searchField.setMaxStringLength(48);
        this.addWidget(this.searchField);
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(250, 20, 60, 16, () -> "清除搜索", () -> {
            this.search = "";
            this.pageIndex = 0;
            this.picked = -1;
            if (this.searchField != null) {
                this.searchField.setCurrentString("");
            }
            refresh();
        }));

        this.addWidget(new LabelWidget(6, 44, "§7当前已选："));
        this.preview = new ShanhaiFluidWidgets.FluidSwatch(76, 42, 18);
        this.preview.still(() -> {
            final Candidate c = pickedItem();
            return c == null ? null : ResourceLocation.tryParse(c.still());
        });
        this.preview.tint(this.page::currentTint);
        this.addWidget(this.preview);
        this.previewLabel = new LabelWidget(98, 46, "");
        this.addWidget(this.previewLabel);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                final int j = r * COLS + c;
                final ShanhaiFluidWidgets.FluidCell cell = new ShanhaiFluidWidgets.FluidCell(
                        GRID_X + c * STEP, GRID_Y + r * STEP);
                cell.still(() -> {
                    final Candidate item = this.pageItems[j];
                    return item == null ? null : ResourceLocation.tryParse(item.still());
                });
                cell.tint(() -> ShanhaiRealityFluidTable.DEFAULT_TINT);
                cell.tooltip(() -> tooltipOf(j));
                cell.onClick(() -> click(j));
                this.addWidget(cell);
                this.cells[j] = cell;
            }
        }

        this.addWidget(new LabelWidget(176, 66, "§7提示：中文名 / 流体 id 都能搜"));
        this.countLabel = new LabelWidget(176, 84, "");
        this.addWidget(this.countLabel);
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(176, 104, 74, 18,
                () -> "◀ 上一页", () -> turnPage(-1)));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(254, 104, 74, 18,
                () -> "下一页 ▶", () -> turnPage(1)));
        this.emptyLabel = new LabelWidget(176, 130, "");
        this.addWidget(this.emptyLabel);

        this.addWidget(new LabelWidget(6, 270, "§8只有按「接受」才会把贴图写回槽位并立刻生效"));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(176, 266, 74, 18, () -> "§a接受", this::accept));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(254, 266, 74, 18, () -> "§c取消", this::cancel));

        this.setVisible(false);
        this.setActive(false);
    }

    // ------------------------------------------------------------------ 打开 / 关闭

    /** 打开选择器（客户端）。每次打开都清空搜索、回到第 1 页、没有选中项。 */
    void open() {
        this.search = "";
        this.pageIndex = 0;
        this.picked = -1;
        if (this.searchField != null) {
            this.searchField.setCurrentString("");
        }
        ensureCandidates();
        refresh();
        this.setVisible(true);
        this.setActive(true);
        com.shanhai.ShanhaiMod.LOGGER.info("{} fluid_texture_picker_open candidates={}",
                ShanhaiRealityFluidTable.PREFIX, this.all.size());
    }

    void close() {
        this.setVisible(false);
        this.setActive(false);
    }

    /** 建立全量候选（只建一次；之后想刷新就关掉重开——贴图集合在运行期不会变）。 */
    private void ensureCandidates() {
        if (!this.all.isEmpty()) {
            return;
        }
        try {
            final java.util.LinkedHashMap<String, Candidate> byKey = new java.util.LinkedHashMap<>();
            for (Fluid fluid : ForgeRegistries.FLUIDS.getValues()) {
                if (fluid == null) {
                    continue;
                }
                final ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
                if (id == null) {
                    continue;
                }
                ResourceLocation still = null;
                ResourceLocation flow = null;
                try {
                    final net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions ext =
                            net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
                    still = ext.getStillTexture();
                    flow = ext.getFlowingTexture();
                } catch (Throwable ignored) {
                    // 没有客户端扩展（或该流体没有贴图）⇒ 跳过这一条，不是错误
                }
                if (still == null) {
                    continue;
                }
                String name = id.toString();
                try {
                    name = fluid.getFluidType().getDescription().getString();
                } catch (Throwable ignored) {
                    // 拿不到显示名 ⇒ 用 id（与面板其它地方同一口径）
                }
                final Candidate c = new Candidate(id.toString(), name, still.toString(),
                        flow == null ? still.toString() : flow.toString());
                byKey.putIfAbsent(c.key(), c);
            }
            this.all.addAll(byKey.values());
            this.all.sort(Comparator.comparing(Candidate::id));
            com.shanhai.ShanhaiMod.LOGGER.info("{} fluid_texture_candidates total={}",
                    ShanhaiRealityFluidTable.PREFIX, this.all.size());
        } catch (Throwable t) {
            com.shanhai.ShanhaiMod.LOGGER.error("{} fluid_texture_candidates_failed err={}",
                    ShanhaiRealityFluidTable.PREFIX, t.toString());
        }
    }

    // ------------------------------------------------------------------ 过滤 / 分页

    private void refresh() {
        this.filtered.clear();
        final String q = this.search == null ? "" : this.search.trim().toLowerCase(Locale.ROOT);
        for (Candidate c : this.all) {
            if (q.isEmpty()
                    || c.id().toLowerCase(Locale.ROOT).contains(q)
                    || c.name().toLowerCase(Locale.ROOT).contains(q)
                    || c.still().toLowerCase(Locale.ROOT).contains(q)) {
                this.filtered.add(c);
            }
        }
        final int pages = pageCount();
        if (this.pageIndex >= pages) {
            this.pageIndex = pages - 1;
        }
        if (this.pageIndex < 0) {
            this.pageIndex = 0;
        }
        if (this.picked >= this.filtered.size()) {
            this.picked = -1;
        }
        for (int j = 0; j < PAGE; j++) {
            final int idx = this.pageIndex * PAGE + j;
            this.pageItems[j] = idx < this.filtered.size() ? this.filtered.get(idx) : null;
            this.cells[j].selected(idx == this.picked && idx < this.filtered.size());
        }
        if (this.countLabel != null) {
            this.countLabel.setText("§7共 §f" + this.filtered.size() + " §7套 · 第 §f" + (this.pageIndex + 1)
                    + "§7/§f" + pages + " §7页");
        }
        if (this.emptyLabel != null) {
            this.emptyLabel.setText(this.filtered.isEmpty() ? "§c没有匹配的流体贴图" : "");
        }
        if (this.previewLabel != null) {
            final Candidate c = pickedItem();
            this.previewLabel.setText(c == null ? "§8（还没选）"
                    : "§f" + trim(c.name(), 20) + " §8· " + trim(c.still(), 28));
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private int pageCount() {
        return Math.max(1, (this.filtered.size() + PAGE - 1) / PAGE);
    }

    private void turnPage(int delta) {
        final int next = this.pageIndex + delta;
        if (next < 0 || next >= pageCount()) {
            return;
        }
        this.pageIndex = next;
        this.picked = -1;
        refresh();
    }

    private void click(int j) {
        final int idx = this.pageIndex * PAGE + j;
        if (idx >= this.filtered.size()) {
            return;                       // 空格子：点了没反应（不是"选了空气"）
        }
        this.picked = idx;
        for (int k = 0; k < PAGE; k++) {
            this.cells[k].selected(this.pageIndex * PAGE + k == this.picked);
        }
        if (this.previewLabel != null) {
            final Candidate c = pickedItem();
            this.previewLabel.setText(c == null ? "§8（还没选）"
                    : "§f" + trim(c.name(), 20) + " §8· " + trim(c.still(), 28));
        }
    }

    private Candidate pickedItem() {
        return this.picked >= 0 && this.picked < this.filtered.size()
                ? this.filtered.get(this.picked) : null;
    }

    // ------------------------------------------------------------------ 接受 / 取消

    private void accept() {
        final Candidate c = pickedItem();
        if (c == null) {
            close();
            return;
        }
        com.shanhai.ShanhaiMod.LOGGER.info("{} fluid_texture_accept still={} flow={}",
                ShanhaiRealityFluidTable.PREFIX, c.still(), c.flow());
        this.page.acceptTexture(c.still(), c.flow());
        close();
    }

    private void cancel() {
        com.shanhai.ShanhaiMod.LOGGER.info("{} fluid_texture_cancel（什么都不改）",
                ShanhaiRealityFluidTable.PREFIX);
        close();
    }

    // ------------------------------------------------------------------ 文本 / 其它

    private List<Component> tooltipOf(int j) {
        final Candidate c = this.pageItems[j];
        if (c == null) {
            return List.of();
        }
        return List.of(Component.literal("§f" + c.name()),
                Component.literal("§8" + c.id()),
                Component.literal("§7still §8" + c.still()),
                Component.literal("§7flow  §8" + c.flow()));
    }

    /**
     * 🔴 整层吃掉点击：选择器铺满整个页面。若不吞掉"落在它身上但没打中任何格子"的那一下，
     * 点击会穿到底层（槽位格 / 保存按钮）—— 与 {@link ShanhaiCreativeIconPicker} 同一条教训。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        return isMouseOverElement(mouseX, mouseY);
    }

    /**
     * 🔴 每 tick 从控件【现取】文本 —— LDLib 的 {@code textResponder} 不一定在客户端这份被调，
     * 这是 {@link ShanhaiCreativeIconPicker} 上已经实锤过的同一个坑。
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
            this.pageIndex = 0;
            this.picked = -1;
            refresh();
            com.shanhai.ShanhaiMod.LOGGER.info("{} fluid_texture_search q=\"{}\" hits={}",
                    ShanhaiRealityFluidTable.PREFIX, this.search, this.filtered.size());
        }
    }

    /** 供自检/命令读的一行版式读数。 */
    static String layoutLine() {
        return "fluid_texture_picker cols=" + COLS + " rows=" + ROWS + " per_page=" + PAGE
                + " cell=18 step=" + STEP + " search=name|id|still source=ForgeRegistries.FLUIDS";
    }
}
