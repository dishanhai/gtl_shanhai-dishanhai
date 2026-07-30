package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class StellarPatternStuckWatch {

    private static final List<Watch> PENDING = new ArrayList<>();
    private static final int TICKS_PER_SECOND = 20;

    private StellarPatternStuckWatch() {}

    public static void schedule(RecipeTypePatternBufferPartMachine machine, int slot, IPatternDetails patternDetails,
            Object2LongMap<AEItemKey> itemInventory, Object2LongMap<AEFluidKey> fluidInventory,
            List<String> hostRecipeTypeIds, String patternRecipeTypeId, @Nullable Integer aePlayerId) {
        if (!(machine.getLevel() instanceof ServerLevel level) || patternDetails == null || slot < 0) return;

        Snapshot snapshot = new Snapshot(copyItemSnapshot(itemInventory), copyFluidSnapshot(fluidInventory));
        if (snapshot.items.isEmpty() && snapshot.fluids.isEmpty()) return;

        int delaySeconds = DShanhaiConfig.COMMON.recipeTypePatternStuckWarningSeconds.get();
        long dueTick = (long) level.getServer().getTickCount() + (long) delaySeconds * TICKS_PER_SECOND;
        Watch watch = new Watch(level.dimension(), machine.getPos().immutable(), slot, dueTick, snapshot,
                List.copyOf(hostRecipeTypeIds),
                patternRecipeTypeId == null || patternRecipeTypeId.isBlank() ? "<none>" : patternRecipeTypeId,
                StellarPatternStuckNotifier.describeStuckInputs(snapshot.items, snapshot.fluids),
                StellarPatternStuckNotifier.describePatternOutputs(patternDetails),
                aePlayerId);

        synchronized (PENDING) {
            PENDING.removeIf(existing -> existing.sameSlot(watch));
            PENDING.add(watch);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        List<Watch> due = new ArrayList<>();
        synchronized (PENDING) {
            Iterator<Watch> iterator = PENDING.iterator();
            while (iterator.hasNext()) {
                Watch watch = iterator.next();
                if (watch.dueTick <= now) {
                    iterator.remove();
                    due.add(watch);
                }
            }
        }
        for (Watch watch : due) {
            process(server, watch);
        }
    }

    private static void process(MinecraftServer server, Watch watch) {
        ServerLevel level = server.getLevel(watch.dimension);
        if (level == null || !level.hasChunkAt(watch.pos)) return;

        if (!(MetaMachine.getMachine(level, watch.pos) instanceof RecipeTypePatternBufferPartMachine machine)) return;
        Snapshot snapshot = watch.snapshot;
        Object2LongMap<AEItemKey> currentItems = machine.gtShanhai$getSlotItemInventory(watch.slot);
        Object2LongMap<AEFluidKey> currentFluids = machine.gtShanhai$getSlotFluidInventory(watch.slot);
        if (!stillContainsAll(snapshot.items, currentItems)
                || !stillContainsAll(snapshot.fluids, currentFluids)) {
            return;
        }

        machine.gtShanhai$setPatternSlotWarning(watch.slot, true);
        StellarPatternStuckNotifier.notifyStuck(level, watch.pos, watch.slot, watch.aePlayerId,
                collectStuckReason(machine),
                watch.stuckInputs, watch.stuckOutputs, watch.hostRecipeTypeIds, watch.patternRecipeTypeId);
    }

    private static Object2LongMap<AEItemKey> copyItemSnapshot(@Nullable Object2LongMap<AEItemKey> source) {
        Object2LongOpenHashMap<AEItemKey> result = new Object2LongOpenHashMap<>();
        if (source == null) return result;
        for (Object2LongMap.Entry<AEItemKey> entry : source.object2LongEntrySet()) {
            if (entry.getLongValue() > 0L) {
                result.put(entry.getKey(), entry.getLongValue());
            }
        }
        return result;
    }

    private static Object2LongMap<AEFluidKey> copyFluidSnapshot(@Nullable Object2LongMap<AEFluidKey> source) {
        Object2LongOpenHashMap<AEFluidKey> result = new Object2LongOpenHashMap<>();
        if (source == null) return result;
        for (Object2LongMap.Entry<AEFluidKey> entry : source.object2LongEntrySet()) {
            if (entry.getLongValue() > 0L) {
                result.put(entry.getKey(), entry.getLongValue());
            }
        }
        return result;
    }

    private static <T> boolean stillContainsAll(Object2LongMap<T> snapshot, @Nullable Object2LongMap<T> current) {
        if (snapshot.isEmpty()) return true;
        if (current == null) return false;
        for (Object2LongMap.Entry<T> entry : snapshot.object2LongEntrySet()) {
            if (current.getLong(entry.getKey()) < entry.getLongValue()) {
                return false;
            }
        }
        return true;
    }

    private static String collectStuckReason(RecipeTypePatternBufferPartMachine machine) {
        String base = "原料仍停留在星律槽位，"
                + DShanhaiConfig.COMMON.recipeTypePatternStuckWarningSeconds.get()
                + " 秒内未被主机取用";
        List<String> statuses = collectHostStatuses(machine);
        if (statuses.isEmpty()) {
            return base + "；未抓到主机 GTCEu/GTLCore 状态";
        }
        return base + "；主机状态: " + String.join(" | ", statuses);
    }

    private static List<String> collectHostStatuses(RecipeTypePatternBufferPartMachine machine) {
        List<String> result = new ArrayList<>();
        for (IMultiController controller : machine.getControllers()) {
            if (!(controller.self() instanceof IRecipeLogicMachine logicMachine)) continue;
            if (!(logicMachine.getRecipeLogic() instanceof IRecipeStatus status)) continue;
            appendStatus(result, "配方", status.getRecipeStatus());
            appendStatus(result, "工作", status.getWorkingStatus());
        }
        return result;
    }

    private static void appendStatus(List<String> result, String label, @Nullable RecipeResult status) {
        if (status == null || status.isSuccess() || status.reason() == null) return;
        String text = status.reason().getString();
        if (text == null || text.isBlank()) return;
        result.add(label + "=" + text);
    }

    private static final class Snapshot {
        private final Object2LongMap<AEItemKey> items;
        private final Object2LongMap<AEFluidKey> fluids;

        private Snapshot(Object2LongMap<AEItemKey> items, Object2LongMap<AEFluidKey> fluids) {
            this.items = items;
            this.fluids = fluids;
        }
    }

    private static final class Watch {
        private final ResourceKey<Level> dimension;
        private final BlockPos pos;
        private final int slot;
        private final long dueTick;
        private final Snapshot snapshot;
        private final List<String> hostRecipeTypeIds;
        private final String patternRecipeTypeId;
        private final String stuckInputs;
        private final String stuckOutputs;
        @Nullable
        private final Integer aePlayerId;

        private Watch(ResourceKey<Level> dimension, BlockPos pos, int slot, long dueTick, Snapshot snapshot,
                List<String> hostRecipeTypeIds, String patternRecipeTypeId, String stuckInputs, String stuckOutputs,
                @Nullable Integer aePlayerId) {
            this.dimension = dimension;
            this.pos = pos;
            this.slot = slot;
            this.dueTick = dueTick;
            this.snapshot = snapshot;
            this.hostRecipeTypeIds = hostRecipeTypeIds;
            this.patternRecipeTypeId = patternRecipeTypeId;
            this.stuckInputs = stuckInputs;
            this.stuckOutputs = stuckOutputs;
            this.aePlayerId = aePlayerId;
        }

        private boolean sameSlot(Watch other) {
            return this.slot == other.slot
                    && this.pos.equals(other.pos)
                    && this.dimension.equals(other.dimension);
        }
    }
}
