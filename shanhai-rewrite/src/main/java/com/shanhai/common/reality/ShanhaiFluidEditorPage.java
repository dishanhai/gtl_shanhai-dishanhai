package com.shanhai.common.reality;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 🆕 2026-11-22：<b>「现实流体」页</b>（工作室面板的第 ④ 页）。
 *
 * <h2>用户原话与它落成什么</h2>
 * 「顺便帮我给物品编辑器添加修改流体的功能，我发现它现在居然不能添加流体」＋ 追问确认「是现实物品」。
 * ⇒ 这一页给 32 个占位流体（{@code shanhai:reality_fluid_1..32}）改三项：
 * <b>中文名 / 颜色 tint / still(+flowing) 贴图</b>，三项<b>全部热生效</b>（不用 /reload、不用重启）。
 *
 * <h2>一屏长什么样（340×300，坐标相对本页组；页签那 20px 由外层容器负责）</h2>
 * <pre>
 *   y=2      标题
 *   y=18..96 32 个流体格（8 列 × 4 行，18px，step 20）＋ 右侧预览/读数
 *   y=104    当前槽位读数（槽位号 + id + 未命名/已命名）
 *   y=120    名称输入框        [40..330]
 *   y=138    颜色（#RRGGBB）[40..104] ＋ 6 个预设色块 [108..214] ＋ 读数
 *   y=156    贴图（选择贴图… ＋ 当前 still 路径）
 *   y=178    流动贴图读数
 *   y=196    说明（名字留空 = 显示 id）
 *   y=212    说明（第一版不做桶 / 方块 / JEI 条目 / 世界生成）
 *   y=232    保存 / 清空该槽 / 关闭 ＋ 状态行
 * </pre>
 *
 * <h2>🔴 编辑状态在客户端，落盘在服务端（与「新增」页同一条口径）</h2>
 * 客户端那份存"正在编辑的缓冲区"，点「保存」把整包字段发一条 C2S
 * （{@link ShanhaiCreativeItemsNetwork#requestFluidSave}）；服务端校验 + 写
 * {@code config/shanhai/creative_fluids.json} + 广播整表。
 * 服务端那份页组只用来对齐容器（按钮回调一律 {@code !remote ⇒ return}）。
 *
 * <h2>🔴 格子画的是流体自己的贴图 × tint</h2>
 * 不是物品图标（用户点名要求）。实现见 {@link ShanhaiFluidWidgets#drawFluid}：
 * {@code GuiGraphics#blit(ResourceLocation…)} 直绑 still 贴图，再用
 * {@code RenderSystem.setShaderColor(r,g,b,1)} 乘上 tint。
 */
final class ShanhaiFluidEditorPage extends WidgetGroup {

    static final int W = ShanhaiCreativeItemsPanel.W;
    static final int H = ShanhaiCreativeItemsPanel.H;

    private static final int GRID_COLS = 8;
    private static final int GRID_ROWS = 4;
    private static final int CELL = 18;
    private static final int STEP = 20;
    private static final int GRID_X = 6;
    private static final int GRID_Y = 18;

    /** 6 个预设色（白 / 黑 / 红 / 绿 / 蓝 / 金）。 */
    private static final int[] PRESET_TINTS = {
            0xFFFFFFFF, 0xFF1A1A1A, 0xFFE74C3C, 0xFF2ECC71, 0xFF3498DB, 0xFFF1C40F
    };

    private final Player player;
    private final boolean remote;

    // ---- 编辑缓冲区（客户端权威；保存时整包发给服务端）----
    private int editSlot;
    private String editName = "";
    private String editStill = ShanhaiRealityFluidTable.DEFAULT_STILL;
    private String editFlow = ShanhaiRealityFluidTable.DEFAULT_FLOW;
    private String editTintHex = "#FFFFFF";

    private String statusText = "§7选一个流体格，改名字/颜色/贴图，然后点保存";

    private final ShanhaiFluidWidgets.FluidCell[] cells =
            new ShanhaiFluidWidgets.FluidCell[ShanhaiRealityFluidTable.SLOT_COUNT];

    private TextFieldWidget nameField;
    private TextFieldWidget tintField;
    private ShanhaiFluidWidgets.FluidSwatch preview;
    private LabelWidget slotLabel;
    private LabelWidget nameLabel;
    private LabelWidget stillLabel;
    private LabelWidget flowLabel;
    private LabelWidget tintLabel;
    private LabelWidget statusLabel;
    private ShanhaiFluidTexturePicker picker;
    private int seenVersion = -1;

    ShanhaiFluidEditorPage(boolean remote, Player player) {
        super(0, 0, W, H);
        this.remote = remote;
        this.player = player;
        this.setBackground(GuiTextures.BACKGROUND);

        loadSlot(0);

        this.addWidget(new LabelWidget(6, 2,
                "§f现实流体 §7· 32 个占位流体（名字 / 颜色 / 贴图，改了立刻生效）"));

        // ---- 32 个流体格 ----
        for (int i = 0; i < ShanhaiRealityFluidTable.SLOT_COUNT; i++) {
            final int slot = i;
            final ShanhaiFluidWidgets.FluidCell cell = new ShanhaiFluidWidgets.FluidCell(
                    GRID_X + (i % GRID_COLS) * STEP, GRID_Y + (i / GRID_COLS) * STEP);
            cell.still(() -> ShanhaiRealityFluidTable.stillTexture(slot));
            cell.tint(() -> ShanhaiRealityFluidTable.tint(slot));
            cell.tooltip(() -> tooltipOf(slot));
            cell.onClick(() -> selectSlot(slot));
            this.addWidget(cell);
            this.cells[i] = cell;
        }

        // ---- 右侧：预览 + 读数 ----
        this.preview = new ShanhaiFluidWidgets.FluidSwatch(176, 18, 32);
        this.preview.still(() -> this.currentStillLocation());
        this.preview.tint(this::currentTint);
        this.addWidget(this.preview);
        this.nameLabel = new LabelWidget(212, 20, "");
        this.addWidget(this.nameLabel);
        this.stillLabel = new LabelWidget(176, 56, "");
        this.addWidget(this.stillLabel);
        this.flowLabel = new LabelWidget(176, 70, "");
        this.addWidget(this.flowLabel);

        // ---- 当前槽位读数 ----
        this.slotLabel = new LabelWidget(6, 104, "");
        this.addWidget(this.slotLabel);

        // ---- 名称 ----
        this.addWidget(new LabelWidget(6, 124, "§7名称"));
        this.nameField = new TextFieldWidget(40, 120, 290, 16, () -> this.editName, value -> {
            this.editName = value == null ? "" : value;
        });
        this.nameField.setMaxStringLength(ShanhaiRealityFluidTable.MAX_NAME_RAW_CHARS);
        this.addWidget(this.nameField);

        // ---- 颜色 ----
        this.addWidget(new LabelWidget(6, 142, "§7颜色"));
        this.tintField = new TextFieldWidget(40, 138, 64, 16, () -> this.editTintHex, value -> {
            // 真值以保存时现取为准（LDLib 的 textResponder 在客户端这份不一定被调）
        });
        this.tintField.setMaxStringLength(9);
        this.tintField.setCurrentString(this.editTintHex);
        this.addWidget(this.tintField);
        for (int i = 0; i < PRESET_TINTS.length; i++) {
            final int rgb = PRESET_TINTS[i];
            this.addWidget(new ShanhaiFluidWidgets.ColorButton(108 + i * 18, 138, 16, 16, rgb,
                    () -> applyPresetTint(rgb)));
        }
        this.tintLabel = new LabelWidget(220, 142, "");
        this.addWidget(this.tintLabel);

        // ---- 贴图 ----
        this.addWidget(new LabelWidget(6, 160, "§7贴图"));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(40, 156, 110, 16,
                () -> "§f选择贴图…", this::openPicker));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(154, 156, 80, 16,
                () -> "§f取手上流体", this::takeHeldFluid));
        this.addWidget(new LabelWidget(238, 160, "§8（still + flow 一起换）"));

        // ---- 说明 ----
        this.addWidget(new LabelWidget(6, 196,
                "§8名字留空 = 未命名（显示 shanhai:reality_fluid_N）· 颜色纯白 = 不染色"));
        this.addWidget(new LabelWidget(6, 210,
                "§8第一版不做：桶（舀/倒）/ 流体方块 / JEI 独立条目 / 世界生成 —— 要桶请说一声"));

        // ---- 底部 ----
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(6, 232, 70, 18,
                () -> "§a保存", () -> save("§a已保存")));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(80, 232, 80, 18,
                () -> "§e清空该槽", this::clearSlot));
        this.addWidget(new ShanhaiCreativeWidgets.TextButton(164, 232, 60, 18,
                () -> "§f关闭", this::closePanel));
        this.statusLabel = new LabelWidget(6, 256, this.statusText);
        this.addWidget(this.statusLabel);
        this.addWidget(new LabelWidget(6, 274,
                "§8保存后：格子、预览、流体名与颜色立刻变（不用 /reload，也不用重启）"));

        // ---- 贴图选择器（覆盖层，最后加 = 画在最上面；默认隐藏）----
        this.picker = new ShanhaiFluidTexturePicker(this);
        this.addWidget(this.picker);

        this.seenVersion = ShanhaiRealityFluidTable.version();
        refreshCells();
        refreshInfo();
    }

    // ------------------------------------------------------------------ 槽位

    /** 把表里这一格的真实值装进编辑缓冲区（没动过 = 默认）。 */
    private void loadSlot(int slot) {
        this.editSlot = Math.max(0, Math.min(slot, ShanhaiRealityFluidTable.SLOT_COUNT - 1));
        this.editName = ShanhaiRealityFluidTable.nameOf(this.editSlot);
        this.editStill = ShanhaiRealityFluidTable.stillOf(this.editSlot);
        this.editFlow = ShanhaiRealityFluidTable.flowOf(this.editSlot);
        this.editTintHex = ShanhaiRealityFluidTable.tintHex(ShanhaiRealityFluidTable.tint(this.editSlot));
    }

    private void selectSlot(int slot) {
        loadSlot(slot);
        if (this.nameField != null) {
            this.nameField.setCurrentString(this.editName);
        }
        if (this.tintField != null) {
            this.tintField.setCurrentString(this.editTintHex);
        }
        this.statusText = "§7已选中流体 §f" + (slot + 1) + " §8("
                + (ShanhaiRealityFluidTable.isNamed(slot) ? "§a已命名" : "§8未命名") + "§8)";
        refreshCells();
        refreshInfo();
        com.shanhai.ShanhaiMod.LOGGER.info("{} fluid_panel_select slot={} named={} still={} tint={}",
                ShanhaiRealityFluidTable.PREFIX, slot + 1, ShanhaiRealityFluidTable.isNamed(slot),
                this.editStill, this.editTintHex);
    }

    private void refreshCells() {
        for (int i = 0; i < this.cells.length; i++) {
            final ShanhaiFluidWidgets.FluidCell cell = this.cells[i];
            if (cell == null) {
                continue;
            }
            cell.selected(i == this.editSlot);
            cell.dim(!ShanhaiRealityFluidTable.isNamed(i));
        }
    }

    /** 右侧/底部的读数（LabelWidget 的 supplier 不逐帧重取 ⇒ 一律 setText 现写）。 */
    private void refreshInfo() {
        if (this.slotLabel != null) {
            this.slotLabel.setText("§7流体 §f" + (this.editSlot + 1) + " §8· shanhai:reality_fluid_"
                    + (this.editSlot + 1) + " §8· "
                    + (ShanhaiRealityFluidTable.isNamed(this.editSlot) ? "§a已命名" : "§8未命名"));
        }
        if (this.nameLabel != null) {
            final String shown = ShanhaiRealityFluidTable.displayName(this.editSlot);
            this.nameLabel.setText("§f" + (shown.length() > 22 ? shown.substring(0, 22) + "…" : shown));
        }
        if (this.stillLabel != null) {
            this.stillLabel.setText("§7still §8" + shortPath(this.editStill));
        }
        if (this.flowLabel != null) {
            this.flowLabel.setText("§7flow  §8" + shortPath(this.editFlow));
        }
        if (this.tintLabel != null) {
            this.tintLabel.setText("§8" + this.editTintHex + " §7（" + (isWhite(this.currentTint())
                    ? "不染色" : "染色") + "）");
        }
        if (this.statusLabel != null) {
            this.statusLabel.setText(this.statusText == null ? "" : this.statusText);
        }
    }

    private static String shortPath(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    // ------------------------------------------------------------------ 颜色

    private static boolean isWhite(int argb) {
        return (argb & 0xFFFFFF) == 0xFFFFFF;
    }

    /** 当前颜色（从文本框现取；解析不出 = 纯白）。选择器的预览也用它。 */
    int currentTint() {
        final String live = this.tintField == null ? this.editTintHex : this.tintField.getCurrentString();
        return ShanhaiRealityFluidTable.parseTint(live == null ? this.editTintHex : live);
    }

    /** 当前 still 贴图（从缓冲区现取；解析不了 = 默认水）。 */
    private net.minecraft.resources.ResourceLocation currentStillLocation() {
        net.minecraft.resources.ResourceLocation rl =
                net.minecraft.resources.ResourceLocation.tryParse(this.editStill == null ? "" : this.editStill);
        return rl == null
                ? net.minecraft.resources.ResourceLocation.tryParse(ShanhaiRealityFluidTable.DEFAULT_STILL)
                : rl;
    }

    private void applyPresetTint(int rgb) {
        this.editTintHex = ShanhaiRealityFluidTable.tintHex(ShanhaiRealityFluidTable.normalizeTint(rgb));
        if (this.tintField != null) {
            this.tintField.setCurrentString(this.editTintHex);
        }
        this.statusText = "§7颜色：§f" + this.editTintHex + " §8（点「保存」生效）";
        refreshInfo();
    }

    // ------------------------------------------------------------------ 贴图

    private void openPicker() {
        if (this.picker != null) {
            this.picker.open();
        }
    }

    /** 选择器按了「接受」⇒ 写回当前槽位<b>并立刻生效</b>（= 走一次保存）。 */
    void acceptTexture(String still, String flow) {
        this.editStill = ShanhaiRealityFluidTable.sanitizeTexture(still,
                ShanhaiRealityFluidTable.DEFAULT_STILL);
        this.editFlow = ShanhaiRealityFluidTable.sanitizeTexture(flow, this.editStill);
        refreshInfo();
        save("§a贴图已应用");
    }

    /** 「取手上流体」：主手里是一桶流体 ⇒ 直接采用它的 still/flow（只借贴图，不消耗桶）。 */
    private void takeHeldFluid() {
        if (!this.remote) {
            return;
        }
        final ItemStack held = this.player == null ? ItemStack.EMPTY
                : this.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held == null || held.isEmpty()) {
            this.statusText = "§c手上没有物品";
            refreshInfo();
            return;
        }
        String still = null;
        String flow = null;
        try {
            final net.minecraftforge.fluids.FluidStack fs =
                    net.minecraftforge.fluids.FluidUtil.getFluidContained(held).orElse(null);
            if (fs != null && !fs.isEmpty()) {
                final net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions ext =
                        net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fs.getFluid());
                final net.minecraft.resources.ResourceLocation s = ext.getStillTexture();
                final net.minecraft.resources.ResourceLocation f = ext.getFlowingTexture();
                still = s == null ? null : s.toString();
                flow = f == null ? still : f.toString();
            }
        } catch (Throwable t) {
            still = null;
        }
        if (still == null) {
            this.statusText = "§c手上这件东西里没有流体（拿一桶流体再点）";
            refreshInfo();
            return;
        }
        this.editStill = still;
        this.editFlow = flow;
        refreshInfo();
        save("§a已采用手上流体的贴图");
    }

    // ------------------------------------------------------------------ 保存 / 清空 / 关闭

    private void save(String note) {
        if (!this.remote) {
            return;      // 服务端那份只用来对齐容器（唯一入口是 C2S 消息）
        }
        // 🔴 保存这一刻再从控件现取一次（不依赖 updateScreen 的时序）
        final String liveName = this.nameField == null ? null : this.nameField.getCurrentString();
        if (liveName != null) {
            this.editName = liveName;
        }
        final String liveHex = this.tintField == null ? null : this.tintField.getCurrentString();
        if (liveHex != null) {
            this.editTintHex = liveHex;
        }
        this.editTintHex = ShanhaiRealityFluidTable.tintHex(
                ShanhaiRealityFluidTable.parseTint(this.editTintHex));
        if (this.tintField != null) {
            this.tintField.setCurrentString(this.editTintHex);
        }
        this.editName = ShanhaiNameCaps.cap(this.editName,
                ShanhaiRealityFluidTable.MAX_NAME_CHARS, ShanhaiRealityFluidTable.MAX_NAME_RAW_CHARS);
        if (this.nameField != null) {
            this.nameField.setCurrentString(this.editName);
        }
        final int tint = ShanhaiRealityFluidTable.parseTint(this.editTintHex);
        com.shanhai.ShanhaiMod.LOGGER.info(
                "{} fluid_save_live slot={} name_len={} name_visible={} tint={} still={} flow={}",
                ShanhaiRealityFluidTable.PREFIX, this.editSlot + 1, this.editName.length(),
                ShanhaiNameCaps.visibleLength(this.editName), this.editTintHex,
                this.editStill, this.editFlow);
        ShanhaiCreativeItemsNetwork.requestFluidSave(this.editSlot, this.editName,
                this.editStill, this.editFlow, tint);
        this.statusText = note + " §8→ 流体 " + (this.editSlot + 1);
        refreshInfo();
    }

    /** 清空这一格 ⇒ 退回默认（无名字 + 纯白 + 水贴图）；流体本身还在。 */
    private void clearSlot() {
        this.editName = "";
        this.editStill = ShanhaiRealityFluidTable.DEFAULT_STILL;
        this.editFlow = ShanhaiRealityFluidTable.DEFAULT_FLOW;
        this.editTintHex = "#FFFFFF";
        if (this.nameField != null) {
            this.nameField.setCurrentString("");
        }
        if (this.tintField != null) {
            this.tintField.setCurrentString(this.editTintHex);
        }
        if (!this.remote) {
            refreshInfo();
            return;
        }
        ShanhaiCreativeItemsNetwork.requestFluidSave(this.editSlot, "",
                ShanhaiRealityFluidTable.DEFAULT_STILL, ShanhaiRealityFluidTable.DEFAULT_FLOW,
                ShanhaiRealityFluidTable.DEFAULT_TINT);
        this.statusText = "§e已清空流体 " + (this.editSlot + 1) + " §8（流体还在，只是退回默认外观）";
        refreshInfo();
    }

    private void closePanel() {
        if (!this.remote || this.player == null) {
            return;
        }
        this.player.closeContainer();
    }

    // ------------------------------------------------------------------ 生命周期

    /** 关掉本页的覆盖层（外层「页签容器」切页时调）。 */
    void closeOverlays() {
        if (this.picker != null) {
            this.picker.close();
        }
    }

    /** 本页的覆盖层有没有开着。 */
    boolean anyOverlayOpen() {
        return this.picker != null && this.picker.isVisible();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.remote) {
            return;
        }
        // 与「新增」页同一条教训：LDLib 的 textResponder 不一定在客户端这份被调 ⇒ 每 tick 现取
        if (this.nameField != null) {
            final String live = this.nameField.getCurrentString();
            if (live != null && !live.equals(this.editName)) {
                this.editName = live;
            }
        }
        if (this.tintField != null) {
            final String live = this.tintField.getCurrentString();
            if (live != null && !live.equals(this.editTintHex)) {
                this.editTintHex = live;
                refreshInfo();
            }
        }
        // 表被别人改了（服务端广播整表）⇒ 格子与读数跟着变
        final int v = ShanhaiRealityFluidTable.version();
        if (v != this.seenVersion) {
            this.seenVersion = v;
            this.editStill = ShanhaiRealityFluidTable.stillOf(this.editSlot);
            this.editFlow = ShanhaiRealityFluidTable.flowOf(this.editSlot);
            refreshCells();
            refreshInfo();
        }
    }

    /** 供命令 / 自检读的一行版式读数。 */
    static String layoutLine() {
        return "fluid_page " + W + "x" + H + " grid=" + GRID_COLS + "x" + GRID_ROWS + " cell=" + CELL
                + " step=" + STEP + " slots=" + ShanhaiRealityFluidTable.SLOT_COUNT
                + " edit=client_side save=network(S2C sync) cell_paint=still_texture*tint";
    }

    /** 格子的 tooltip（也是"这一格是什么"的唯一说明）。 */
    private static List<net.minecraft.network.chat.Component> tooltipOf(int slot) {
        final boolean named = ShanhaiRealityFluidTable.isNamed(slot);
        return List.of(
                net.minecraft.network.chat.Component.literal("§f流体 " + (slot + 1) + " §8· "
                        + (named ? "§a已命名" : "§8未命名")),
                net.minecraft.network.chat.Component.literal("§7" + ShanhaiRealityFluidTable.displayName(slot)),
                net.minecraft.network.chat.Component.literal("§8shanhai:reality_fluid_" + (slot + 1)),
                net.minecraft.network.chat.Component.literal("§7颜色 " + ShanhaiRealityFluidTable.tintHex(
                        ShanhaiRealityFluidTable.tint(slot))),
                net.minecraft.network.chat.Component.literal("§7点一下编辑这一格"));
    }
}
