package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;
import com.dishanhai.gt_shanhai.network.ShanhaiTerminalReportPacket;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class StellarPatternStuckNotifier {

    private static final double NEARBY_RADIUS = 500.0D;
    private static final double NEARBY_RADIUS_SQUARED = NEARBY_RADIUS * NEARBY_RADIUS;
    private static final String PREFIX = "gui.gt_shanhai.ultimate_terminal.report.";
    // 每台星律同一 tick 的多個槽合成一份報告，避免洗版及擠出客戶端報告快取。
    private static final Map<BatchKey, Batch> PENDING = new LinkedHashMap<>();

    private record BatchKey(ResourceLocation dimension, long pos) {}

    private static final class Batch {
        private final ResourceKey<Level> dimension;
        private final BlockPos pos;
        private final Map<Integer, StellarPatternStuckReport.Entry> entries = new LinkedHashMap<>();
        private final Set<Integer> aePlayerIds = new HashSet<>();

        private Batch(ServerLevel level, BlockPos pos) {
            dimension = level.dimension();
            this.pos = pos.immutable();
        }
    }

    private StellarPatternStuckNotifier() {}

    public static void notifyStuck(ServerLevel level, BlockPos pos, int slot, String slotLabel,
            @Nullable Integer aePlayerId, String reason, String stuckInputs, String stuckOutputs,
            List<String> hostRecipeTypeIds, String patternRecipeTypeId, String patternRecipeId,
            StellarPatternStuckReport.Cause cause) {
        StellarPatternStuckReport.Entry entry = new StellarPatternStuckReport.Entry(slot, slotLabel,
                reason, stuckInputs, stuckOutputs, hostRecipeTypeIds, patternRecipeTypeId, patternRecipeId, cause);
        if (MetaMachine.getMachine(level, pos) instanceof RecipeTypePatternBufferPartMachine machine) {
            machine.gtShanhai$rememberStuckReport(slot, entry);
        }
        Batch batch = PENDING.computeIfAbsent(new BatchKey(level.dimension().location(), pos.asLong()),
                ignored -> new Batch(level, pos));
        batch.entries.put(slot, entry);
        if (aePlayerId != null) batch.aePlayerIds.add(aePlayerId);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        List<Batch> batches = List.copyOf(PENDING.values());
        PENDING.clear();
        for (Batch batch : batches) {
            ServerLevel level = event.getServer().getLevel(batch.dimension);
            if (level != null) notifyBatch(level, batch);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private static void notifyBatch(ServerLevel level, Batch batch) {
        MinecraftServer server = level.getServer();
        BlockPos pos = batch.pos;
        ShanhaiTerminalReport report = StellarPatternStuckReport.build(
                batch.dimension.location(), pos, batch.entries.values());
        Set<UUID> sent = new HashSet<>();
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) > NEARBY_RADIUS_SQUARED) continue;
            if (sent.add(player.getUUID())) {
                send(player, level, pos, "附近 " + (int) NEARBY_RADIUS + " 格", report);
            }
        }

        for (Integer aePlayerId : batch.aePlayerIds) {
            ServerPlayer player = IPlayerRegistry.getConnected(server, aePlayerId);
            if (player != null && sent.add(player.getUUID())) {
                send(player, level, pos, "AE 下单玩家", report);
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

    private static void send(ServerPlayer player, ServerLevel level, BlockPos pos,
            String route, ShanhaiTerminalReport report) {
        ShanhaiTerminalReportPacket.sendTo(player, report);
        Component link = Component.translatable(PREFIX + "link_details").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        ShanhaiTerminalReport.CHAT_COMMAND + report.id()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable(PREFIX + "link_details_hint"))));
        int color = report.categories().stream().mapToInt(ShanhaiTerminalReport.Category::color)
                .filter(value -> value == 0xF07878 || value == 0xF0C878).findFirst().orElse(0x78C8F0);
        player.sendSystemMessage(Component.translatable(PREFIX + "notification_stellar_group", route, report.lines().size())
                .withStyle(style -> style.withColor(color)).append(" ").append(coordinateLink(level, pos))
                .append(" ").append(link));
    }

    public static String broadcastBody(int slot, String slotLabel, String reason, String stuckInputs,
            String stuckOutputs, List<String> hostRecipeTypeIds, String patternRecipeTypeId,
            String patternRecipeId) {
        String text = "槽位 " + displaySlot(slot, slotLabel)
                + " 样板ID: " + oneLine(patternRecipeId)
                + " 样板类型: " + oneLine(patternRecipeTypeId)
                + " 原因: " + oneLine(reason)
                + " | 原料: " + oneLine(stuckInputs)
                + " | 输出: " + oneLine(stuckOutputs)
                + " | 主机配方: " + oneLine(formatList(hostRecipeTypeIds));
        return text.length() <= 220 ? text : text.substring(0, 220);
    }

    private static String displaySlot(int slot, String slotLabel) {
        if (slotLabel != null && !slotLabel.isBlank()) return slotLabel;
        return String.valueOf(slot + 1);
    }

    private static String oneLine(String text) {
        if (text == null || text.isBlank()) return "<none>";
        return text.replace('\n', ' ').replace('\r', ' ');
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
