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
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;
import com.dishanhai.gt_shanhai.network.ShanhaiTerminalReportPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiStructureHighlightPacket;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IInteractionItem;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

public final class ShanhaiUltimateTerminalBehavior implements IInteractionItem, IAddInformation {

    public static final ShanhaiUltimateTerminalBehavior INSTANCE = new ShanhaiUltimateTerminalBehavior();
    private static final int MAX_HIGHLIGHTS = 256;
    private static final int MAX_DETAIL_LINES = 3;
    private static final int MODULE_HIGHLIGHT_COLOR = 0x00FFFF;

    private final ShanhaiTerminalMaterialService materials = new ShanhaiTerminalMaterialService();
    private final ShanhaiStructureExecutor executor = new ShanhaiStructureExecutor(materials);

    private ShanhaiUltimateTerminalBehavior() {}

    /** 設定介面是純客戶端玻璃 Screen；設定經 {@code UltimateTerminalConfigPacket} 寫回伺服器上的手持物。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.dishanhai.gt_shanhai.client.gui.terminal.UltimateTerminalScreenOpener.open(hand));
        }
        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

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

        // 山海：操作模式優先於遊戲模式，拆解不能落入創造建造或潛行施工確認。
        if (ShanhaiUltimateTerminalConfig.isDismantleMode(terminal)) {
            ShanhaiTerminalCraftingManager.clear(terminal);
            ShanhaiStructureExecutor.Result result = executor.dismantle(serverPlayer, plan, ae);
            serverPlayer.sendSystemMessage(Component.literal(
                    (result.success() ? "§a" : "§c") + "[山海终端] " + result.message()
                            + "，回收 " + result.changed() + " 处"));
            sendBuildReport(serverPlayer, plan, result);
            return result.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
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
            sendBuildReport(serverPlayer, plan, result);
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
            sendBuildReport(serverPlayer, plan, result);
            return result.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }

        if (rejectOutOfBuildHeight(serverPlayer, terminal, plan)) {
            return InteractionResult.FAIL;
        }

        if (ae != null && ShanhaiTerminalCraftingManager.confirmSubmit(
                serverPlayer, terminal, plan, ae)) {
            return InteractionResult.SUCCESS;
        }
        requestMissingOrders(serverPlayer, terminal, plan, ae);

        ShanhaiTerminalCraftingManager.Phase phase = ShanhaiTerminalCraftingManager.phase(terminal);
        if (phase == ShanhaiTerminalCraftingManager.Phase.READY_TO_BUILD) {
            serverPlayer.sendSystemMessage(Component.literal("§a[山海终端] 潜行右击同一控制器确认施工"));
            return InteractionResult.SUCCESS;
        }

        boolean hasReplacement = plan.entries().stream()
                .anyMatch(entry -> entry.kind() == ShanhaiStructurePlan.Kind.REPLACE
                        || entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE);
        var preflight = materials.preflight(plan, serverPlayer, ae);
        if (hasReplacement) {
            ShanhaiTerminalCraftingManager.armDirectBuild(serverPlayer, terminal, plan);
            serverPlayer.sendSystemMessage(Component.literal(preflight.success()
                    ? "§e[山海终端] 替换计划已准备；潜行右击确认施工"
                    : "§e[山海终端] 部分施工计划已准备；潜行右击会按现有材料施工，缺材料位置跳过"));
            return InteractionResult.SUCCESS;
        }

        ShanhaiStructureExecutor.Result result = executor.execute(serverPlayer, plan, ae);
        serverPlayer.sendSystemMessage(Component.literal(
                (result.success() ? "§a" : "§c") + "[山海终端] " + result.message()
                        + "，变更 " + result.changed() + " 处"));
        sendBuildReport(serverPlayer, plan, result);
        return result.success() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
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

    private void requestMissingOrders(ServerPlayer player, ItemStack terminal, ShanhaiStructurePlan plan,
                                      ShanhaiTerminalAeBinding.Context ae) {
        if (!ShanhaiUltimateTerminalConfig.isAeMode(terminal)
                || !ShanhaiUltimateTerminalConfig.isOrderMode(terminal)) return;
        if (ae == null) {
            if (!materials.preflight(plan, player, null).success()) {
                player.sendSystemMessage(Component.literal(
                        "§e[山海终端] AE 网络未绑定或离线，无法下单缺口"));
            }
            return;
        }
        ShanhaiTerminalCraftingManager.begin(player, terminal, plan, ae, materials);
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

        // 聊天栏只放前几条；完整条目走报告封包，由聊天连结开启界面查看。
        ShanhaiTerminalReport.Builder report = new ShanhaiTerminalReport.Builder(
                ShanhaiTerminalReport.Type.SCAN, plan.target().pos(),
                (int) place, (int) replace, (int) blocked, (int) manual, (int) chambers);
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
            Component desired = chamber.map(selection -> selection.candidate().getHoverName())
                    .orElseGet(() -> entry.desired().isEmpty()
                            ? Component.translatable("message.gt_shanhai.ultimate_terminal.manual_choice")
                            : entry.desired().getHoverName());
            // 仓室位置：同一位置所有仓室候选按类型分组，报告按类型完整列出具体仓室。
            var groups = chamberHint
                    ? ShanhaiChamberClassifier.classifyAll(entry.candidates()) : null;
            if (groups == null || groups.isEmpty()) {
                report.add(entry.pos(),
                        chamber.map(selection -> selection.type().name()).orElseGet(() -> entry.kind().name()),
                        chamber.map(selection -> (Component) Component.translatable(selection.type().translationKey()))
                                .orElseGet(() -> Component.translatable(
                                        "gui.gt_shanhai.ultimate_terminal.report.kind."
                                                + entry.kind().name().toLowerCase(java.util.Locale.ROOT))),
                        color, desired, entry.desired().isEmpty() ? List.of() : List.of(entry.desired()));
            } else {
                groups.forEach((type, stacks) -> report.add(entry.pos(), type.name(),
                        Component.translatable(type.translationKey()), type.color(), joinNames(stacks), stacks));
            }
            if (lines++ >= MAX_DETAIL_LINES) continue;
            MutableComponent prefix = chamber
                    .map(selection -> Component.translatable(selection.type().translationKey()))
                    .orElseGet(() -> Component.literal(entry.kind().name()));
            prefix.withStyle(style -> style.withColor(TextColor.fromRgb(color)))
                    .append(Component.literal(" @ " + entry.pos().toShortString() + " ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(desired.copy().withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(prefix);
        }
        ShanhaiStructureHighlightPacket.sendTo(
                player, plan.target().dimension(), expiry, highlights);
        sendReportLink(player, report, Math.min(lines, MAX_DETAIL_LINES), lines);
    }

    private static Component joinNames(List<ItemStack> stacks) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < stacks.size(); i++) {
            if (i > 0) joined.append(Component.literal("、"));
            joined.append(stacks.get(i).getHoverName());
        }
        return joined;
    }

    /** 报告有条目没能放进聊天栏时：先送完整报告，再发一行带点击连结的提示。 */
    private static void sendReportLink(ServerPlayer player, ShanhaiTerminalReport.Builder report, int shown, int total) {
        if (total <= shown) return;
        sendReportLink(player, report.build(), Component.translatable(
                "message.gt_shanhai.ultimate_terminal.report_more", total - shown),
                "message.gt_shanhai.ultimate_terminal.report_link");
    }

    public static void sendReportLink(ServerPlayer player, ShanhaiTerminalReport built, MutableComponent prefix, String linkKey) {
        ShanhaiTerminalReportPacket.sendTo(player, built);
        player.sendSystemMessage(prefix
                .append(Component.translatable(linkKey)
                        .withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                        ShanhaiTerminalReport.CHAT_COMMAND + built.id()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.translatable("message.gt_shanhai.ultimate_terminal.report_hover"))))));
    }

    /** 施工／拆解结果：聊天栏保留一行摘要，逐位置明细走报告封包，点击连结开启施工报告界面。 */
    private static void sendBuildReport(ServerPlayer player, ShanhaiStructurePlan plan, ShanhaiStructureExecutor.Result result) {
        if (result.details().isEmpty()) return;
        java.util.EnumMap<ShanhaiStructureExecutor.Outcome, Integer> counts =
                new java.util.EnumMap<>(ShanhaiStructureExecutor.Outcome.class);
        for (var detail : result.details()) counts.merge(detail.outcome(), 1, Integer::sum);
        int skipped = counts.getOrDefault(ShanhaiStructureExecutor.Outcome.SKIPPED, 0);
        int dismantled = counts.getOrDefault(ShanhaiStructureExecutor.Outcome.DISMANTLED, 0);
        int changed = counts.getOrDefault(ShanhaiStructureExecutor.Outcome.PLACED, 0)
                + counts.getOrDefault(ShanhaiStructureExecutor.Outcome.REPLACED, 0);
        ShanhaiTerminalReport.Builder report = new ShanhaiTerminalReport.Builder(
                ShanhaiTerminalReport.Type.BUILD, plan.target().pos(), changed, skipped, dismantled)
                .summary(Component.literal(result.message()).withStyle(
                        result.success() ? ChatFormatting.GREEN : ChatFormatting.RED));
        // 缺材料跳过的最先列出（最需要处理），其次替换、放置、拆解。
        for (ShanhaiStructureExecutor.Outcome outcome : new ShanhaiStructureExecutor.Outcome[]{
                ShanhaiStructureExecutor.Outcome.SKIPPED, ShanhaiStructureExecutor.Outcome.REPLACED,
                ShanhaiStructureExecutor.Outcome.PLACED, ShanhaiStructureExecutor.Outcome.DISMANTLED}) {
            int color = switch (outcome) {
                case SKIPPED -> 0xFF5555;
                case REPLACED -> 0xFFAA00;
                case PLACED -> 0x55FF55;
                case DISMANTLED -> 0x55FFFF;
            };
            for (var detail : result.details()) {
                if (detail.outcome() != outcome) continue;
                report.add(detail.pos(), outcome.name(),
                        Component.translatable("gui.gt_shanhai.ultimate_terminal.report.outcome."
                                + outcome.name().toLowerCase(java.util.Locale.ROOT)),
                        color, detail.name(), detail.stack().isEmpty() ? List.of() : List.of(detail.stack()));
            }
        }
        sendReportLink(player, report.build(), Component.translatable(
                "message.gt_shanhai.ultimate_terminal.build_report_more"),
                "message.gt_shanhai.ultimate_terminal.build_report_link");
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
        ShanhaiTerminalReport.Builder report = new ShanhaiTerminalReport.Builder(
                ShanhaiTerminalReport.Type.MODULES, plan.target().pos(), modulePositions.size());
        for (BlockPos pos : modulePositions) {
            if (highlights.size() < MAX_HIGHLIGHTS) {
                highlights.add(new ShanhaiStructureHighlightPacket.Marker(
                        pos, MODULE_HIGHLIGHT_COLOR));
            }
            report.add(pos, "MODULE",
                    Component.translatable("gui.gt_shanhai.ultimate_terminal.report.kind.module"),
                    MODULE_HIGHLIGHT_COLOR, player.level().getBlockState(pos).getBlock().getName(),
                    List.of(new ItemStack(player.level().getBlockState(pos).getBlock())));
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
        sendReportLink(player, report, 0, modulePositions.size());
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
    public void appendHoverText(ItemStack itemStack, @Nullable Level level,
                                List<Component> list, TooltipFlag tooltipFlag) {
        GlobalPos bound = ShanhaiUltimateTerminalConfig.getBoundAe(itemStack);
        list.add(Component.translatable("tooltip.gt_shanhai.ultimate_terminal.0"));
        list.add(Component.translatable("tooltip.gt_shanhai.ultimate_terminal.1"));
        list.add(Component.translatable("tooltip.gt_shanhai.ultimate_terminal.2"));
        list.add(bound == null
                ? Component.translatable("tooltip.gt_shanhai.ultimate_terminal.unbound")
                : Component.translatable("tooltip.gt_shanhai.ultimate_terminal.bound",
                        bound.dimension().location(), bound.pos().toShortString()));
    }
}
