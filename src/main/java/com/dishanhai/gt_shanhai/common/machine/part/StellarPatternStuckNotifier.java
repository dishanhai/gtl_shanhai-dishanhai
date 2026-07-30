package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

public final class StellarPatternStuckNotifier {

    private static final double NEARBY_RADIUS = 500.0D;
    private static final double NEARBY_RADIUS_SQUARED = NEARBY_RADIUS * NEARBY_RADIUS;

    private StellarPatternStuckNotifier() {}

    public static void notifyStuck(ServerLevel level, BlockPos pos, int slot, @Nullable Integer aePlayerId,
            String reason, String stuckInputs, String stuckOutputs, List<String> hostRecipeTypeIds,
            String patternRecipeTypeId) {
        MinecraftServer server = level.getServer();
        Set<UUID> sent = new HashSet<>();
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) > NEARBY_RADIUS_SQUARED) continue;
            if (sent.add(player.getUUID())) {
                send(player, level, pos, slot, "附近 " + (int) NEARBY_RADIUS + " 格", reason,
                        stuckInputs, stuckOutputs, hostRecipeTypeIds, patternRecipeTypeId);
            }
        }

        if (aePlayerId != null) {
            ServerPlayer player = IPlayerRegistry.getConnected(server, aePlayerId);
            if (player != null && sent.add(player.getUUID())) {
                send(player, level, pos, slot, "AE 下单玩家", reason,
                        stuckInputs, stuckOutputs, hostRecipeTypeIds, patternRecipeTypeId);
            }
        }
    }

    public static String describePatternInputs(@Nullable IPatternDetails patternDetails) {
        if (patternDetails == null) return "<unknown>";
        StringJoiner joiner = new StringJoiner("; ");
        for (IPatternDetails.IInput input : patternDetails.getInputs()) {
            if (input == null) continue;
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length == 0) continue;
            StringJoiner alternatives = new StringJoiner(" / ");
            for (GenericStack stack : possibleInputs) {
                alternatives.add(formatGenericStack(stack, input.getMultiplier()));
            }
            joiner.add(alternatives.toString());
        }
        String text = joiner.toString();
        return text.isBlank() ? "<empty>" : text;
    }

    public static String describeStuckInputs(Object2LongMap<AEItemKey> itemSnapshot,
            Object2LongMap<AEFluidKey> fluidSnapshot) {
        StringJoiner joiner = new StringJoiner("; ");
        if (itemSnapshot != null) {
            for (Object2LongMap.Entry<AEItemKey> entry : itemSnapshot.object2LongEntrySet()) {
                joiner.add(formatKey(entry.getKey(), entry.getLongValue()));
            }
        }
        if (fluidSnapshot != null) {
            for (Object2LongMap.Entry<AEFluidKey> entry : fluidSnapshot.object2LongEntrySet()) {
                joiner.add(formatKey(entry.getKey(), entry.getLongValue()));
            }
        }
        String text = joiner.toString();
        return text.isBlank() ? "<empty>" : text;
    }

    public static String describePatternOutputs(@Nullable IPatternDetails patternDetails) {
        if (patternDetails == null) return "<unknown>";
        StringJoiner joiner = new StringJoiner("; ");
        for (GenericStack stack : patternDetails.getOutputs()) {
            joiner.add(formatGenericStack(stack, 1L));
        }
        String text = joiner.toString();
        return text.isBlank() ? "<empty>" : text;
    }

    private static void send(ServerPlayer player, ServerLevel level, BlockPos pos, int slot, String route,
            String reason, String stuckInputs, String stuckOutputs, List<String> hostRecipeTypeIds,
            String patternRecipeTypeId) {
        Component coordinate = coordinateLink(level, pos);
        player.sendSystemMessage(Component.literal("§c[星律样板告警] §f")
                .append(Component.literal(route).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" §c检测到样板槽卡死："))
                .append(coordinate));
        player.sendSystemMessage(Component.literal("§7槽位: §f" + (slot + 1)
                + " §7原因: §f" + reason));
        player.sendSystemMessage(Component.literal("§7卡死原料: §f" + stuckInputs));
        player.sendSystemMessage(Component.literal("§7卡死输出: §f" + stuckOutputs));
        player.sendSystemMessage(Component.literal("§7主机配方类型: §f"
                + formatList(hostRecipeTypeIds)
                + " §7样板配方类型: §f" + patternRecipeTypeId));
    }

    private static Component coordinateLink(ServerLevel level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        String label = "[" + dimension + " " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
        String command = "/shanhai stellar_tp " + dimension + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("点击传送到该星律样板总成"))));
    }

    private static String formatList(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) return "<none>";
        return String.join(", ", values);
    }

    private static String formatGenericStack(@Nullable GenericStack stack, long multiplier) {
        if (stack == null || stack.what() == null) return "<null>";
        AEKey key = stack.what();
        long amount = stack.amount() * Math.max(1L, multiplier);
        return formatKey(key, amount);
    }

    private static String formatKey(AEKey key, long amount) {
        return key.getDisplayName().getString() + " x " + amount + " (" + key.getId() + ")";
    }
}
