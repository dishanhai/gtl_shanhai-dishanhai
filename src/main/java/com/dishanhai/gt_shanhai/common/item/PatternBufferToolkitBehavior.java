package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.HitResult;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MECraftPatternContainerPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * 样板总成工具箱：把 GTLCore 的「样板总成复制工具」（{@code gtlcore:me_pattern_buffer_copy}）与
 * 「样板总成剪切工具」（{@code gtlcore:me_pattern_buffer_cut}）合成一件，用模式切换取代换手拿两把。
 *
 * <p>相对那两把原版工具新增：</p>
 * <ul>
 * <li>支持 ME 样板核心（{@code gtceu:me_craft_pattern_container}）——上游两把工具只认样板总成；</li>
 * <li>复制模式右键多方块控制器 = 批量套用到该多方块内全部样板总成与样板核心；</li>
 * <li>右键空气打开剪贴板面板，能直接看到存了哪些样板，并就地切换模式 / 清空剪贴板。</li>
 * </ul>
 *
 * <p>剪贴板 NBT 与两把原版工具同构（见 {@link PatternBufferClipboard}），所以样板总成与样板核心之间
 * 可以互相复制。</p>
 */
public final class PatternBufferToolkitBehavior implements IItemUIFactory, IAddInformation {

    public static final PatternBufferToolkitBehavior INSTANCE = new PatternBufferToolkitBehavior();

    private static final String MODE_KEY = "ToolkitMode";
    /** 判定「右键的是空气」的射线距离，够覆盖原版 4.5 与常见拓展手长。 */
    private static final double PICK_REACH = 6.0D;

    private static final int SLOT_SIZE = 18;
    private static final int LEFT = 7;
    private static final int WIDTH = 176;
    private static final int PREVIEW_COLS = 9;
    /** 预览区可见行数，剩下的靠滚动条翻。 */
    private static final int PREVIEW_VISIBLE_ROWS = 5;
    private static final int PREVIEW_TOP = 42;
    private static final int SCROLL_BAR_WIDTH = 3;
    private static final int PREVIEW_WIDTH = (PREVIEW_COLS * SLOT_SIZE) + SCROLL_BAR_WIDTH;
    private static final int PREVIEW_HEIGHT = PREVIEW_VISIBLE_ROWS * SLOT_SIZE;
    private static final int INFO_Y = PREVIEW_TOP + PREVIEW_HEIGHT + 4;
    private static final int INV_TOP = INFO_Y + 14;
    private static final int HOTBAR_TOP = INV_TOP + (3 * SLOT_SIZE) + 4;
    private static final int HEIGHT = HOTBAR_TOP + SLOT_SIZE + 6;

    private PatternBufferToolkitBehavior() {}

    /** 工作模式。一件工具两套语义，靠这个位区分。 */
    public enum Mode {
        /** 潜行右键复制目标的样板与命名，右键套用（消耗空白样板）；右键控制器为批量套用。 */
        COPY(PatternBufferClipboard.COPY_KEY),
        /** 潜行右键剪切目标全部配置（含催化剂/共享槽/代理），右键粘贴，粘贴后剪贴板清空。 */
        CUT(PatternBufferClipboard.CUT_KEY);

        private final String clipboardKey;

        Mode(String clipboardKey) {
            this.clipboardKey = clipboardKey;
        }

        public String clipboardKey() {
            return clipboardKey;
        }

        public String translationKey() {
            return "gui.gt_shanhai.pattern_buffer_toolkit.mode." + name().toLowerCase(Locale.ROOT);
        }
    }

    public static Mode getMode(ItemStack tool) {
        CompoundTag tag = tool.getTag();
        if (tag == null) return Mode.COPY;
        int ordinal = tag.getInt(MODE_KEY);
        Mode[] modes = Mode.values();
        return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : Mode.COPY;
    }

    public static Mode cycleMode(ItemStack tool) {
        Mode next = Mode.values()[(getMode(tool).ordinal() + 1) % Mode.values().length];
        tool.getOrCreateTag().putInt(MODE_KEY, next.ordinal());
        return next;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (player.isShiftKeyDown()) {
            // 只在真对着空气时切模式。潜行右键落空的方块（非样板容器）也会走到这里，
            // 不做这个判定的话「复制失败」会顺手把模式改成剪切，是实打实的误操作源。
            if (player.pick(PICK_REACH, 0.0F, false).getType() != HitResult.Type.MISS) {
                return new InteractionResultHolder<>(InteractionResult.PASS, stack);
            }
            Mode mode = cycleMode(stack);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.gt_shanhai.pattern_buffer_toolkit.mode_switched",
                                Component.translatable(mode.translationKey())),
                        true);
            }
            return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(serverPlayer, usedHand);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }

    @Override
    public boolean sneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack itemStack, UseOnContext context) {
        InteractionResult result = handleBlockUse(itemStack, context);
        return result == InteractionResult.PASS
                ? InteractionResult.PASS
                : InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return handleBlockUse(context.getItemInHand(), context);
    }

    /**
     * 目标是 ME 样板总成或 ME 样板核心；复制模式非潜行点多方块控制器时走批量套用。
     * 够不着目标就返回 PASS，把交互还给机器自己（照常开它的 GUI）。
     */
    public static InteractionResult handleBlockUse(ItemStack tool, UseOnContext context) {
        MetaMachine machine = MetaMachine.getMachine(context.getLevel(), context.getClickedPos());
        if (machine == null) return InteractionResult.PASS;

        Mode mode = getMode(tool);
        boolean sneaking = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
        boolean batchTarget = mode == Mode.COPY && !sneaking && machine instanceof IMultiController;
        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return isDirectTarget(machine) || batchTarget ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        PatternBufferClipboard.Result result;
        String successKey;
        String failureKey;
        if (mode == Mode.COPY) {
            if (sneaking) {
                result = PatternBufferClipboard.copyFrom(machine, tool);
                successKey = "message.gt_shanhai.pattern_buffer_toolkit.copied";
                failureKey = "message.gt_shanhai.pattern_buffer_toolkit.copy_failed";
            } else if (batchTarget) {
                result = PatternBufferClipboard.applyCopyToMultiblock(
                        (IMultiController) machine, tool, serverPlayer);
                successKey = "message.gt_shanhai.pattern_buffer_toolkit.batch_applied";
                failureKey = "message.gt_shanhai.pattern_buffer_toolkit.apply_failed";
            } else {
                result = PatternBufferClipboard.applyCopy(machine, tool, serverPlayer);
                successKey = "message.gt_shanhai.pattern_buffer_toolkit.applied";
                failureKey = "message.gt_shanhai.pattern_buffer_toolkit.apply_failed";
            }
        } else if (sneaking) {
            result = PatternBufferClipboard.cutFrom(machine, tool);
            successKey = "message.gt_shanhai.pattern_buffer_toolkit.cut";
            failureKey = "message.gt_shanhai.pattern_buffer_toolkit.cut_failed";
        } else {
            result = PatternBufferClipboard.pasteCut(machine, tool);
            successKey = "message.gt_shanhai.pattern_buffer_toolkit.pasted";
            failureKey = "message.gt_shanhai.pattern_buffer_toolkit.paste_failed";
        }

        if (!result.supported()) return InteractionResult.PASS;
        serverPlayer.displayClientMessage(
                Component.translatable(result.changed() ? successKey : failureKey, result.count()), true);
        return InteractionResult.SUCCESS;
    }

    private static boolean isDirectTarget(MetaMachine machine) {
        return machine instanceof MEPatternBufferPartMachine
                || machine instanceof MECraftPatternContainerPartMachine;
    }

    // ------------------------------------------------------------------ 剪贴板面板

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        ItemStack tool = holder.getHeld();
        WidgetGroup group = new WidgetGroup(0, 0, WIDTH, HEIGHT);
        group.addWidget(new LabelWidget(LEFT, 6,
                () -> Component.translatable("item.gt_shanhai.pattern_buffer_toolkit").getString()));

        WidgetGroup panel = new WidgetGroup(0, 0, WIDTH, INV_TOP);
        rebuildPanel(panel, tool, holder);
        group.addWidget(panel);

        Inventory playerInv = player.getInventory();
        int heldSlot = holder.getHand() == InteractionHand.MAIN_HAND ? playerInv.selected : -1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + ((row + 1) * 9);
                group.addWidget(new SlotWidget(playerInv, index,
                        LEFT + (col * SLOT_SIZE), INV_TOP + (row * SLOT_SIZE), true, true)
                                .setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            boolean locked = col == heldSlot;
            group.addWidget(new SlotWidget(playerInv, col,
                    LEFT + (col * SLOT_SIZE), HOTBAR_TOP, !locked, !locked)
                            .setBackgroundTexture(GuiTextures.SLOT));
        }
        return new ModularUI(WIDTH, HEIGHT, holder, player).widget(group).background(GuiTextures.BACKGROUND);
    }

    /** 模式按钮 / 清空按钮 / 剪贴板预览是一体的：任一按钮点下去都得整块重建。 */
    private static void rebuildPanel(WidgetGroup panel, ItemStack tool,
                                     HeldItemUIFactory.HeldItemHolder holder) {
        panel.clearAllWidgets();
        Mode mode = getMode(tool);
        int stored = PatternBufferClipboard.patternCount(tool, mode.clipboardKey());

        panel.addWidget(new ButtonWidget(LEFT, 22, 90, 16,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(Component.translatable(mode.translationKey()).getString())),
                clickData -> {
                    cycleMode(tool);
                    holder.markAsDirty();
                    rebuildPanel(panel, tool, holder);
                }));
        panel.addWidget(new ButtonWidget(103, 22, 66, 16,
                new GuiTextureGroup(GuiTextures.BUTTON,
                        new TextTexture(Component.translatable(
                                "gui.gt_shanhai.pattern_buffer_toolkit.clear").getString())),
                clickData -> {
                    PatternBufferClipboard.clear(tool, getMode(tool).clipboardKey());
                    holder.markAsDirty();
                    rebuildPanel(panel, tool, holder);
                }));

        // 剪贴板动辄上千个样板，全铺成容器槽开面板就得整份同步。渲染量按配置封顶，超出的只报数字。
        int limit = Math.max(PREVIEW_COLS, DShanhaiConfig.COMMON.patternBufferToolkitPreviewLimit.get());
        List<ItemStack> stacks = PatternBufferClipboard.previewStacks(tool, mode.clipboardKey(), limit);
        int shown = stacks.size();

        // 纯展示：过滤器一律拒绝，槽位也不给取放，避免玩家把剪贴板当储物格用。
        ItemStackTransfer preview = new ItemStackTransfer(Math.max(1, shown));
        preview.setFilter(stack -> false);
        for (int index = 0; index < shown; index++) {
            preview.setStackInSlot(index, stacks.get(index));
        }

        DraggableScrollableWidgetGroup scroll = new DraggableScrollableWidgetGroup(
                LEFT, PREVIEW_TOP, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                        .setBackground(GuiTextures.DISPLAY)
                        .setYScrollBarWidth(SCROLL_BAR_WIDTH)
                        .setYBarStyle(null, ColorPattern.T_WHITE.rectTexture().setRadius(1));
        // 至少铺满可见区域，免得内容不足时背景一片空白看不出这是个格子区。
        int rows = Math.max(PREVIEW_VISIBLE_ROWS, ((shown + PREVIEW_COLS) - 1) / PREVIEW_COLS);
        for (int index = 0; index < rows * PREVIEW_COLS; index++) {
            int x = (index % PREVIEW_COLS) * SLOT_SIZE;
            int y = (index / PREVIEW_COLS) * SLOT_SIZE;
            if (index < shown) {
                scroll.addWidget(new SlotWidget(preview, index, x, y, false, false)
                        .setBackgroundTexture(GuiTextures.SLOT)
                        .setIngredientIO(IngredientIO.RENDER_ONLY));
            } else {
                scroll.addWidget(new ImageWidget(x, y, SLOT_SIZE, SLOT_SIZE, GuiTextures.SLOT));
            }
        }
        panel.addWidget(scroll);

        String name = PatternBufferClipboard.storedName(tool, mode.clipboardKey());
        String displayName = name.isEmpty() ? "-" : name;
        String infoKey = stored > shown
                ? "gui.gt_shanhai.pattern_buffer_toolkit.stored_truncated"
                : "gui.gt_shanhai.pattern_buffer_toolkit.stored";
        panel.addWidget(new LabelWidget(LEFT, INFO_Y, () -> Component.translatable(
                infoKey, stored, displayName, shown).getString()));
    }

    // ------------------------------------------------------------------ tooltip

    @Override
    public void appendHoverText(ItemStack itemStack, @Nullable Level level,
                                List<Component> list, TooltipFlag tooltipFlag) {
        Mode mode = getMode(itemStack);
        list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.mode",
                Component.translatable(mode.translationKey())));
        if (mode == Mode.COPY) {
            list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.copy_sneak"));
            list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.copy_apply"));
            list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.copy_batch"));
        } else {
            list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.cut_sneak"));
            list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.cut_paste"));
        }
        int stored = PatternBufferClipboard.patternCount(itemStack, mode.clipboardKey());
        if (stored > 0) {
            String name = PatternBufferClipboard.storedName(itemStack, mode.clipboardKey());
            list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.clipboard",
                    stored, name.isEmpty() ? "-" : name));
        } else {
            list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.clipboard_empty"));
        }
        list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.panel"));
        list.add(Component.translatable("tooltip.gt_shanhai.pattern_buffer_toolkit.targets"));
    }
}
