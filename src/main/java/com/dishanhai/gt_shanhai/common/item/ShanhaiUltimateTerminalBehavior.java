package com.dishanhai.gt_shanhai.common.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiChamberClassifier;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiModuleClassifier;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStructureExecutor;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStructureBuildHeightValidator;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStructurePlan;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStructurePlanner;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalAeBinding;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalCraftingManager;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalMaterialService;
import com.dishanhai.gt_shanhai.network.ShanhaiStructureHighlightPacket;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.hepdd.gtmthings.api.gui.widget.TerminalInputWidget;
import com.lowdragmc.lowdraglib.gui.editor.ColorPattern;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.util.Lazy;
import org.gtlcore.gtlcore.api.gui.BlockMapSelectorWidget;
import org.gtlcore.gtlcore.api.gui.ExtendLabelWidget;
import org.gtlcore.gtlcore.common.block.BlockMap;
import org.jetbrains.annotations.Nullable;

public final class ShanhaiUltimateTerminalBehavior implements IItemUIFactory, IAddInformation {

    public static final ShanhaiUltimateTerminalBehavior INSTANCE = new ShanhaiUltimateTerminalBehavior();
    private static final int MAX_HIGHLIGHTS = 256;
    private static final int MAX_DETAIL_LINES = 12;
    private static final int MODULE_HIGHLIGHT_COLOR = 0x00FFFF;

    private final ShanhaiTerminalMaterialService materials = new ShanhaiTerminalMaterialService();
    private final ShanhaiStructureExecutor executor = new ShanhaiStructureExecutor(materials);

    private ShanhaiUltimateTerminalBehavior() {}

    @Override
    public InteractionResult onItemUseFirst(ItemStack itemStack, UseOnContext context) {
        return handleUseOn(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return InteractionResult.PASS;
    }

    private InteractionResult handleUseOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        ItemStack terminal = context.getItemInHand();
        if (player.isShiftKeyDown()
                && ShanhaiTerminalAeBinding.bind(serverPlayer, terminal, context.getClickedPos())) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "§a[山海终端] 已绑定 AE 网络节点 " + context.getClickedPos().toShortString()));
            ShanhaiTerminalCraftingManager.clear(terminal);
            return InteractionResult.SUCCESS;
        }

        ShanhaiTerminalAeBinding.Context ae = resolveAeContext(serverPlayer, terminal);
        ShanhaiStructurePlan plan;
        try {
            plan = scanPlan(serverPlayer, terminal, context.getClickedPos(), ae);
        } catch (RuntimeException e) {
            serverPlayer.sendSystemMessage(Component.literal("§c[山海终端] 结构读取失败: " + e.getMessage()));
            return InteractionResult.FAIL;
        }
        if (plan == null) {
            if (ShanhaiUltimateTerminalConfig.isDismantleMode(terminal)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[山海终端] 拆解模式请右击对应多方块控制器；控制器不存在时无法识别结构范围"));
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }

        if (serverPlayer.isCreative()) {
            if (rejectOutOfBuildHeight(serverPlayer, terminal, plan)) {
                return InteractionResult.FAIL;
            }
            ShanhaiTerminalCraftingManager.clear(terminal);
            ShanhaiStructureExecutor.Result result = executor.executeCreative(serverPlayer, plan, ae);
            serverPlayer.sendSystemMessage(Component.literal(
                    (result.success() ? "§a" : "§c") + "[山海终端] " + result.message()
                            + "，变更 " + result.changed() + " 处"));
            return result.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }

        if (player.isShiftKeyDown()) {
            if (rejectOutOfBuildHeight(serverPlayer, terminal, plan)) {
                return InteractionResult.FAIL;
            }
            if (!ShanhaiTerminalCraftingManager.consumeBuildConfirmation(serverPlayer, terminal, plan)) {
                serverPlayer.sendSystemMessage(Component.literal("§e[山海终端] 请先普通右击扫描并准备施工计划"));
                return InteractionResult.SUCCESS;
            }
            ShanhaiStructureExecutor.Result result = executor.execute(serverPlayer, plan, ae);
            serverPlayer.sendSystemMessage(Component.literal(
                    (result.success() ? "§a" : "§c") + "[山海终端] " + result.message()
                            + "，变更 " + result.changed() + " 处"));
            return result.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }

        if (ShanhaiUltimateTerminalConfig.isDismantleMode(terminal)) {
            ShanhaiStructureExecutor.Result result = executor.dismantle(serverPlayer, plan, ae);
            serverPlayer.sendSystemMessage(Component.literal(
                    (result.success() ? "§a" : "§c") + "[山海终端] " + result.message()
                            + "，回收 " + result.changed() + " 处"));
            return result.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }

        if (rejectOutOfBuildHeight(serverPlayer, terminal, plan)) {
            return InteractionResult.FAIL;
        }

        ShanhaiTerminalCraftingManager.Phase phase = ShanhaiTerminalCraftingManager.phase(terminal);
        if (phase == ShanhaiTerminalCraftingManager.Phase.CALCULATING
                || phase == ShanhaiTerminalCraftingManager.Phase.RETRY_CALCULATING) {
            serverPlayer.sendSystemMessage(Component.literal("§e[山海终端] AE 合成方案仍在计算"));
            return InteractionResult.SUCCESS;
        }
        if (phase == ShanhaiTerminalCraftingManager.Phase.READY_TO_SUBMIT) {
            if (!ShanhaiTerminalCraftingManager.confirmSubmit(serverPlayer, terminal, plan, ae)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[山海终端] 下单确认失败，结构或绑定已变化"));
            }
            return InteractionResult.SUCCESS;
        }
        if (phase == ShanhaiTerminalCraftingManager.Phase.SUBMITTED) {
            if (!ShanhaiTerminalCraftingManager.refreshBuildReadiness(
                    serverPlayer, terminal, plan, ae, materials)) {
                serverPlayer.sendSystemMessage(Component.literal("§e[山海终端] AE 材料尚未全部到齐"));
            }
            return InteractionResult.SUCCESS;
        }
        if (phase == ShanhaiTerminalCraftingManager.Phase.READY_TO_BUILD) {
            serverPlayer.sendSystemMessage(Component.literal("§a[山海终端] 潜行右击同一控制器确认施工"));
            return InteractionResult.SUCCESS;
        }

        var preflight = materials.preflight(plan, serverPlayer, ae);
        if (preflight.success()) {
            boolean hasReplacement = plan.entries().stream()
                    .anyMatch(entry -> entry.kind() == ShanhaiStructurePlan.Kind.REPLACE
                            || entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE);
            if (hasReplacement) {
                ShanhaiTerminalCraftingManager.armDirectBuild(serverPlayer, terminal, plan);
                serverPlayer.sendSystemMessage(Component.literal("§e[山海终端] 替换计划已准备；潜行右击确认施工"));
                return InteractionResult.SUCCESS;
            }
            ShanhaiStructureExecutor.Result result = executor.execute(serverPlayer, plan, ae);
            serverPlayer.sendSystemMessage(Component.literal(
                    (result.success() ? "§a" : "§c") + "[山海终端] " + result.message()
                            + "，变更 " + result.changed() + " 处"));
            return result.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }

        if (!ShanhaiUltimateTerminalConfig.isAeMode(terminal)) {
            serverPlayer.sendSystemMessage(Component.literal("§c[山海终端] 材料不足；开启 AE 模式可使用网络库存并下单补齐"));
            return InteractionResult.SUCCESS;
        }
        if (ae == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c[山海终端] 绑定的 AE 节点离线或无效"));
            return InteractionResult.SUCCESS;
        }
        if (!ShanhaiTerminalCraftingManager.begin(serverPlayer, terminal, plan, ae, materials)) {
            serverPlayer.sendSystemMessage(Component.literal("§c[山海终端] 没有可提交的材料合成请求"));
        }
        return InteractionResult.SUCCESS;
    }

    public boolean scanOnly(ServerPlayer player, ItemStack terminal, BlockPos pos) {
        ShanhaiTerminalAeBinding.Context ae = resolveAeContext(player, terminal);
        try {
            ShanhaiStructurePlan plan = scanPlan(player, terminal, pos, ae);
            if (plan == null) return false;
            if (ShanhaiUltimateTerminalConfig.isModuleCheckMode(terminal)) {
                reportModulePositions(player, plan);
            } else {
                reportPlan(player, plan);
            }
        } catch (RuntimeException e) {
            player.sendSystemMessage(Component.literal("§c[山海终端] 结构读取失败: " + e.getMessage()));
        }
        return true;
    }

    private ShanhaiStructurePlan scanPlan(ServerPlayer player, ItemStack terminal, BlockPos pos,
                                          ShanhaiTerminalAeBinding.Context ae) {
        MetaMachine machine = MetaMachine.getMachine(player.level(), pos);
        if (!(machine instanceof IMultiController controller)) return null;
        return ShanhaiStructurePlanner.scan(controller, terminal,
                materials.prioritizer(player, ae));
    }

    private ShanhaiTerminalAeBinding.Context resolveAeContext(ServerPlayer player, ItemStack terminal) {
        if (!ShanhaiUltimateTerminalConfig.isAeMode(terminal)) return null;
        return ShanhaiTerminalAeBinding.resolve(player, terminal);
    }

    private boolean rejectOutOfBuildHeight(ServerPlayer player, ItemStack terminal,
                                           ShanhaiStructurePlan plan) {
        if (player.getServer() == null) return false;
        ServerLevel level = player.getServer().getLevel(plan.target().dimension());
        if (level == null) return false;
        ShanhaiStructureBuildHeightValidator.Result result =
                ShanhaiStructureBuildHeightValidator.validateForGtceu(
                        plan, level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (result.valid()) return false;

        ShanhaiTerminalCraftingManager.clear(terminal);
        if (level.getMaxBuildHeight() > result.maxBuildHeight() && result.upperLimitExceeded()) {
            player.sendSystemMessage(Component.translatable(
                    "message.gt_shanhai.ultimate_terminal.gtceu_height_exceeded",
                    plan.target().pos().toShortString(), level.getMaxBuildHeight() - 1,
                    result.maxBuildY(), result.violationCount(),
                    result.firstViolation().toShortString()));
        } else {
            player.sendSystemMessage(Component.translatable(
                    "message.gt_shanhai.ultimate_terminal.build_height_exceeded",
                    plan.target().pos().toShortString(), result.minBuildHeight(), result.maxBuildY(),
                    result.violationCount(), result.firstViolation().toShortString()));
        }
        return true;
    }

    private void reportPlan(ServerPlayer player, ShanhaiStructurePlan plan) {
        long place = count(plan, ShanhaiStructurePlan.Kind.PLACE);
        long replace = count(plan, ShanhaiStructurePlan.Kind.REPLACE)
                + count(plan, ShanhaiStructurePlan.Kind.FORCE_REPLACE);
        long blocked = count(plan, ShanhaiStructurePlan.Kind.BLOCKED);
        long manual = count(plan, ShanhaiStructurePlan.Kind.MANUAL);
        long chambers = plan.entries().stream().filter(ShanhaiStructurePlan.Entry::chamberCapable).count();
        player.sendSystemMessage(Component.literal("§b[山海终端] 缺失 §f" + place
                + " §b| 替换 §f" + replace + " §b| 阻塞 §f" + blocked
                + " §b| 手动 §f" + manual + " §b| 可放仓室 §f" + chambers));

        int lines = 0;
        long expiry = System.currentTimeMillis() + 15000;
        List<ShanhaiStructureHighlightPacket.Marker> highlights = new ArrayList<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            boolean chamberHint = entry.kind() == ShanhaiStructurePlan.Kind.CHAMBER_HINT
                    || entry.chamberCapable();
            boolean actionable = entry.kind() == ShanhaiStructurePlan.Kind.PLACE
                    || entry.kind() == ShanhaiStructurePlan.Kind.REPLACE
                    || entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE
                    || entry.kind() == ShanhaiStructurePlan.Kind.BLOCKED
                    || entry.kind() == ShanhaiStructurePlan.Kind.MANUAL;
            if (!actionable && !chamberHint) continue;
            Optional<ShanhaiChamberClassifier.Selection> chamber = chamberHint
                    ? ShanhaiChamberClassifier.firstCandidate(entry.candidates()) : Optional.empty();
            int color = highlightColor(entry.kind(), chamber);
            if (highlights.size() < MAX_HIGHLIGHTS) {
                highlights.add(new ShanhaiStructureHighlightPacket.Marker(entry.pos(), color));
            }
            if (lines++ >= MAX_DETAIL_LINES) continue;
            String desired = chamber.map(selection -> selection.candidate().getHoverName().getString())
                    .orElseGet(() -> entry.desired().isEmpty()
                            ? "手动选择" : entry.desired().getHoverName().getString());
            MutableComponent prefix = chamber
                    .map(selection -> Component.translatable(selection.type().translationKey()))
                    .orElseGet(() -> Component.literal(entry.kind().name()));
            prefix.withStyle(style -> style.withColor(TextColor.fromRgb(color)))
                    .append(Component.literal(" @ " + entry.pos().toShortString() + " ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(desired).withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(prefix);
        }
        ShanhaiStructureHighlightPacket.sendTo(
                player, plan.target().dimension(), expiry, highlights);
    }

    private void reportModulePositions(ServerPlayer player, ShanhaiStructurePlan plan) {
        MetaMachine targetMachine = MetaMachine.getMachine(player.level(), plan.target().pos());
        LinkedHashSet<BlockPos> modulePositions = new LinkedHashSet<>(
                ShanhaiModuleClassifier.hostModulePositions(targetMachine));
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (!ShanhaiModuleClassifier.isModulePosition(entry.candidates())) continue;
            modulePositions.add(entry.pos());
        }

        List<ShanhaiStructureHighlightPacket.Marker> highlights = new ArrayList<>();
        for (BlockPos pos : modulePositions) {
            if (highlights.size() < MAX_HIGHLIGHTS) {
                highlights.add(new ShanhaiStructureHighlightPacket.Marker(
                        pos, MODULE_HIGHLIGHT_COLOR));
            }
        }

        if (modulePositions.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "message.gt_shanhai.ultimate_terminal.module_positions_none"));
            return;
        }
        player.sendSystemMessage(Component.translatable(
                "message.gt_shanhai.ultimate_terminal.module_positions", modulePositions.size()));
        ShanhaiStructureHighlightPacket.sendTo(player, plan.target().dimension(),
                System.currentTimeMillis() + 15000, highlights);
    }

    private static long count(ShanhaiStructurePlan plan, ShanhaiStructurePlan.Kind kind) {
        return plan.entries().stream().filter(entry -> entry.kind() == kind).count();
    }

    private static int highlightColor(ShanhaiStructurePlan.Kind kind,
                                      Optional<ShanhaiChamberClassifier.Selection> chamber) {
        if (chamber.isPresent()) {
            ShanhaiChamberClassifier.Selection selection = chamber.get();
            return selection.type().color();
        }
        return switch (kind) {
            case PLACE -> 0x55FF55;
            case REPLACE -> 0xFFAA00;
            case FORCE_REPLACE -> 0xFF5500;
            case BLOCKED -> 0xFF3333;
            case MANUAL -> 0xFFFFFF;
            case CHAMBER_HINT, SATISFIED -> 0xFFFFFF;
        };
    }

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder heldItemHolder, Player player) {
        ItemStack terminal = heldItemHolder.getHeld();
        return new ModularUI(200, 176, heldItemHolder, player).widget(createSettings(terminal));
    }

    private Widget createSettings(ItemStack terminal) {
        WidgetGroup root = new WidgetGroup(0, 0, 200, 136);
        DraggableScrollableWidgetGroup settings = new DraggableScrollableWidgetGroup(4, 4, 192, 128)
                .setBackground(GuiTextures.DISPLAY)
                .setYScrollBarWidth(2)
                .setYBarStyle((IGuiTexture) null, ColorPattern.T_WHITE.rectTexture().setRadius(1));
        settings.addWidget(new ExtendLabelWidget(54, 8,
                Component.translatable("gui.gt_shanhai.ultimate_terminal.title")));
        settings.addWidget(new ExtendLabelWidget(14, 26,
                Component.translatable("gui.gt_shanhai.ultimate_terminal.tier_blocks")));
        settings.addWidget(new ExtendLabelWidget(14, 46,
                Component.translatable("gui.gt_shanhai.ultimate_terminal.repeat")));
        settings.addWidget(new TerminalInputWidget(140, 45, 36, 12,
                () -> ShanhaiUltimateTerminalConfig.getRepeatCount(terminal),
                value -> ShanhaiUltimateTerminalConfig.setRepeatCount(terminal, value))
                .setMin(0).setMax(648));
        settings.addWidget(toggle(terminal, 63, "gui.gt_shanhai.ultimate_terminal.mirror",
                ShanhaiUltimateTerminalConfig.isMirrored(terminal),
                value -> ShanhaiUltimateTerminalConfig.setMirrored(terminal, value)));
        settings.addWidget(toggle(terminal, 83, "gui.gt_shanhai.ultimate_terminal.replace",
                ShanhaiUltimateTerminalConfig.isReplaceMode(terminal),
                value -> ShanhaiUltimateTerminalConfig.setReplaceMode(terminal, value)));
        settings.addWidget(toggle(terminal, 103, "gui.gt_shanhai.ultimate_terminal.absolute_replace",
                ShanhaiUltimateTerminalConfig.isAbsoluteReplaceMode(terminal),
                value -> ShanhaiUltimateTerminalConfig.setAbsoluteReplaceMode(terminal, value)));
        settings.addWidget(toggle(terminal, 123, "gui.gt_shanhai.ultimate_terminal.ae_mode",
                ShanhaiUltimateTerminalConfig.isAeMode(terminal), value -> {
                    ShanhaiUltimateTerminalConfig.setAeMode(terminal, value);
                    if (!value) ShanhaiTerminalCraftingManager.clear(terminal);
                }));
        settings.addWidget(toggle(terminal, 143, "gui.gt_shanhai.ultimate_terminal.no_chamber",
                ShanhaiUltimateTerminalConfig.isNoChamberMode(terminal),
                value -> ShanhaiUltimateTerminalConfig.setNoChamberMode(terminal, value)));
        settings.addWidget(toggle(terminal, 163, "gui.gt_shanhai.ultimate_terminal.dismantle",
                ShanhaiUltimateTerminalConfig.isDismantleMode(terminal),
                value -> ShanhaiUltimateTerminalConfig.setDismantleMode(terminal, value)));
        settings.addWidget(toggle(terminal, 183, "gui.gt_shanhai.ultimate_terminal.module_check",
                ShanhaiUltimateTerminalConfig.isModuleCheckMode(terminal),
                value -> ShanhaiUltimateTerminalConfig.setModuleCheckMode(terminal, value)));

        ExtendLabelWidget replacement = new ExtendLabelWidget(47, 26, replacementLabel(terminal));
        BlockMapSelectorWidget selector = new BlockMapSelectorWidget(
                root.getSizeHeight() + 4, settings.getSizeWidth(), (family, tier) -> {
            if (family == null || tier == null) return;
            ShanhaiUltimateTerminalConfig.setReplacement(terminal, family, tier);
            replacement.setComponent(replacementLabel(terminal));
        });
        initializeReplacementSelector(selector, terminal);
        settings.addWidget(new SwitchWidget(14, 26, 30, 16,
                (click, open) -> selector.showType(open))
                .setHoverTooltips(Component.translatable("gui.gt_shanhai.ultimate_terminal.replacement")));
        settings.addWidget(replacement);
        root.addWidget(settings).addWidget(selector)
                .setBackground(GuiTextures.BACKGROUND_INVERSE);
        return root;
    }

    private Widget toggle(ItemStack terminal, int y, String key, boolean initial,
                          java.util.function.Consumer<Boolean> setter) {
        WidgetGroup row = new WidgetGroup(0, y, 180, 18);
        row.addWidget(new ExtendLabelWidget(14, 3, Component.translatable(key)));
        row.addWidget(new SwitchWidget(140, 0, 36, 14,
                (click, pressed) -> setter.accept(pressed))
                .setPressed(initial)
                .setTexture(new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("OFF")),
                        new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture("ON"))));
        return row;
    }

    private Component replacementLabel(ItemStack terminal) {
        String family = ShanhaiUltimateTerminalConfig.getReplacementFamily(terminal);
        Lazy<Block[]> lazyBlocks = BlockMap.tierBlockMap.get(family);
        if (family.isEmpty() || lazyBlocks == null) {
            return Component.translatable("gui.gt_shanhai.ultimate_terminal.replacement_none");
        }
        Block[] blocks = lazyBlocks.get();
        if (blocks.length == 0) {
            return Component.translatable("gui.gt_shanhai.ultimate_terminal.replacement_none");
        }
        int tier = Math.min(ShanhaiUltimateTerminalConfig.getReplacementTier(terminal), blocks.length - 1);
        try {
            return Component.literal("(")
                    .append(BlockMapSelectorWidget.getBlock(family))
                    .append(Component.literal(" : "))
                    .append(blocks[tier].getName())
                    .append(Component.literal(")"));
        } catch (IllegalStateException ignored) {
            return Component.translatable("gui.gt_shanhai.ultimate_terminal.replacement_none");
        }
    }

    private void initializeReplacementSelector(BlockMapSelectorWidget selector, ItemStack terminal) {
        String family = ShanhaiUltimateTerminalConfig.getReplacementFamily(terminal);
        Lazy<Block[]> lazyBlocks = BlockMap.tierBlockMap.get(family);
        if (family.isEmpty() || lazyBlocks == null || lazyBlocks.get().length == 0) return;

        ItemStack selectorState = terminal.copy();
        CompoundTag selectorTag = selectorState.getOrCreateTag();
        selectorTag.putString("blocks", family);
        selectorTag.putInt("Tier", Math.min(
                ShanhaiUltimateTerminalConfig.getReplacementTier(terminal), lazyBlocks.get().length - 1));
        selector.setInit(selectorState);
    }

    @Override
    public void appendHoverText(ItemStack itemStack, @Nullable Level level,
                                List<Component> list, TooltipFlag tooltipFlag) {
        GlobalPos bound = ShanhaiUltimateTerminalConfig.getBoundAe(itemStack);
        list.add(Component.translatable("tooltip.gt_shanhai.ultimate_terminal.0"));
        list.add(Component.translatable("tooltip.gt_shanhai.ultimate_terminal.1"));
        list.add(bound == null
                ? Component.translatable("tooltip.gt_shanhai.ultimate_terminal.unbound")
                : Component.translatable("tooltip.gt_shanhai.ultimate_terminal.bound",
                        bound.dimension().location(), bound.pos().toShortString()));
    }
}
