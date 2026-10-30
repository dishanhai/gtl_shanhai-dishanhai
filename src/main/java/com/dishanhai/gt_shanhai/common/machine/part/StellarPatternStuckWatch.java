package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.api.PatternIdentityMatcher;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.common.item.RecipeTypeSharedSearchSets;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternRecipeTypeBinding;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.forge.PrimordialCoinForgeModule;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.forge.CoinForgeMintGate;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class StellarPatternStuckWatch {

    private static final Map<SlotKey, Watch> PENDING = new HashMap<>();
    private static final int TICKS_PER_SECOND = 20;

    private StellarPatternStuckWatch() {}

    public static void schedule(RecipeTypePatternBufferPartMachine machine, int slot, IPatternDetails patternDetails,
            Object2LongMap<AEItemKey> itemInventory, Object2LongMap<AEFluidKey> fluidInventory,
            List<String> hostRecipeTypeIds, String patternRecipeTypeId, String patternRecipeId,
            @Nullable Integer aePlayerId) {
        if (!(machine.getLevel() instanceof ServerLevel level) || patternDetails == null || slot < 0) return;

        Snapshot snapshot = new Snapshot(copyItemSnapshot(itemInventory), copyFluidSnapshot(fluidInventory));
        if (snapshot.items.isEmpty() && snapshot.fluids.isEmpty()) return;

        int delaySeconds = DShanhaiConfig.COMMON.recipeTypePatternStuckWarningSeconds.get();
        long dueTick = (long) level.getServer().getTickCount() + (long) delaySeconds * TICKS_PER_SECOND;
        Watch watch = new Watch(level.dimension(), machine.getPos().immutable(), slot, dueTick, snapshot,
                List.copyOf(hostRecipeTypeIds),
                patternRecipeTypeId == null || patternRecipeTypeId.isBlank() ? "<none>" : patternRecipeTypeId,
                patternRecipeId == null || patternRecipeId.isBlank() ? "<none>" : patternRecipeId,
                patternDetails, aePlayerId);
        long now = level.getServer().getTickCount();
        // 只讀便宜的進度基線，不在推樣板路徑掃描候選配方或格式化詳情。
        for (IMultiController controller : machine.getControllers()) {
            if (controller.self() instanceof IRecipeLogicMachine host) {
                RecipeLogic logic = host.getRecipeLogic();
                var policy = new StellarPatternWaitPolicy();
                policy.observe(now, watch.timeout, -1L, 0L, 0L, false, false,
                        logic.getProgress(), logic.getTotalContinuousRunningTime(), logic.getDuration(), logic.getLastRecipe());
                watch.hostProgress.put(controller.self(), policy);
            }
        }

        synchronized (PENDING) {
            PENDING.put(new SlotKey(watch.dimension.location(), watch.pos.asLong(), slot), watch);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        List<Watch> due = new ArrayList<>();
        synchronized (PENDING) {
            Iterator<Watch> iterator = PENDING.values().iterator();
            while (iterator.hasNext()) {
                Watch watch = iterator.next();
                if (watch.dueTick <= now) {
                    iterator.remove();
                    due.add(watch);
                }
            }
        }
        Map<MachineKey, MachineAssessment> assessments = new HashMap<>();
        for (Watch watch : due) {
            process(server, watch, assessments);
        }
    }

    private static void process(MinecraftServer server, Watch watch,
                                Map<MachineKey, MachineAssessment> assessments) {
        ServerLevel level = server.getLevel(watch.dimension);
        if (level == null || !level.hasChunkAt(watch.pos)) return;

        if (!(MetaMachine.getMachine(level, watch.pos) instanceof RecipeTypePatternBufferPartMachine machine)) return;
        Integer currentSlot = machine.getSlotIndexForPattern(watch.patternDetails);
        if (currentSlot == null || currentSlot != watch.slot) return;
        Snapshot snapshot = watch.snapshot;
        Object2LongMap<AEItemKey> currentItems = machine.gtShanhai$getSlotItemInventory(watch.slot);
        Object2LongMap<AEFluidKey> currentFluids = machine.gtShanhai$getSlotFluidInventory(watch.slot);
        if (!stillContainsAll(snapshot.items, currentItems)
                || !stillContainsAll(snapshot.fluids, currentFluids)) {
            machine.gtShanhai$clearPatternSlotRuntimeWarning(watch.slot);
            return;
        }

        long now = server.getTickCount();
        MachineAssessment assessment = assessments.computeIfAbsent(new MachineKey(watch.dimension, watch.pos),
                ignored -> collectParallelAssessment(machine));
        boolean waiting = evaluateParallelWait(machine, watch, assessment, now);
        // 明確的權限拒絕不能繼續沿用其他配方忙碌時延長的等待期限。
        if (!waiting && !watch.mintRestrictions.isEmpty()) watch.warningTick = Math.min(watch.warningTick, watch.initialWarningTick);
        if (waiting || now < watch.warningTick) {
            watch.dueTick = now + Math.min(TICKS_PER_SECOND, watch.timeout);
            synchronized (PENDING) {
                PENDING.putIfAbsent(new SlotKey(watch.dimension.location(), watch.pos.asLong(), watch.slot), watch);
            }
            return;
        }

        machine.gtShanhai$setPatternSlotStuckWarning(watch.slot, snapshot.items, snapshot.fluids);
        machine.gtShanhai$logStuckDiagnosis(watch.slot);
        String reason = collectStuckReason(machine, watch.slot) + "；并行评估: " + watch.parallelAssessment;
        if (!watch.mintRestrictions.isEmpty()) reason += "；铸币限制: " + String.join(" | ", watch.mintRestrictions.values());
        int slot = watch.slot;
        String slotLabel = machine.gtShanhai$describeStuckSlot(slot);
        String stuckInputs = StellarPatternStuckNotifier.describeStuckInputs(snapshot.items, snapshot.fluids);
        String stuckOutputs = StellarPatternStuckNotifier.describePatternOutputs(watch.patternDetails);
        machine.gtShanhai$rememberStuckBroadcast(watch.slot, StellarPatternStuckNotifier.broadcastBody(
                watch.slot, slotLabel, reason, stuckInputs, stuckOutputs,
                watch.hostRecipeTypeIds, watch.patternRecipeTypeId, watch.patternRecipeId));
        StellarPatternStuckNotifier.notifyStuck(level, watch.pos, watch.slot, slotLabel, watch.aePlayerId,
                reason, stuckInputs, stuckOutputs, watch.hostRecipeTypeIds,
                watch.patternRecipeTypeId, watch.patternRecipeId, classifyCause(machine, watch, assessment));
    }

    private static StellarPatternStuckReport.Cause classifyCause(RecipeTypePatternBufferPartMachine machine,
                                                                Watch watch, MachineAssessment assessment) {
        if (!machine.gtShanhai$fullStrongMatchReason(watch.slot).isEmpty())
            return StellarPatternStuckReport.Cause.STRONG_MATCH;
        if (!watch.mintRestrictions.isEmpty()) return StellarPatternStuckReport.Cause.COIN_MINT_LOCKED;
        boolean found = false, allPaused = true, failed = false;
        for (HostAssessment host : assessment.hosts) {
            if (!supportsType(host.types, watch.patternRecipeTypeId, assessment.onlyHost)) continue;
            found = true;
            boolean paused = host.machine instanceof IRecipeLogicMachine logic && !logic.isWorkingEnabled();
            allPaused &= paused;
            failed |= host.failed && !paused;
        }
        if (!found) return StellarPatternStuckReport.Cause.NO_HOST;
        if (failed) return StellarPatternStuckReport.Cause.HOST_FAILURE;
        return allPaused ? StellarPatternStuckReport.Cause.PAUSED : StellarPatternStuckReport.Cause.UNCONSUMED;
    }

    private static MachineAssessment collectParallelAssessment(RecipeTypePatternBufferPartMachine machine) {
        int[] activeSlots = machine.gtShanhai$getActiveSlots();
        List<String> queuedTypes = new ArrayList<>(activeSlots.length);
        for (int slot : activeSlots) queuedTypes.add(machine.gtShanhai$getPatternRecipeTypeId(slot));
        List<HostAssessment> hosts = new ArrayList<>();
        for (IMultiController controller : machine.getControllers()) {
            if (!(controller.self() instanceof IRecipeLogicMachine host) || !controller.isFormed()) continue;
            RecipeLogic logic = host.getRecipeLogic();
            List<String> types = new ArrayList<>();
            for (var type : WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(List.of(controller))) {
                if (type != null && type.registryName != null) types.add(type.registryName.toString());
            }
            if (host instanceof SelectableRecipeTypeSetMachine selectable) {
                types.removeIf(type -> {
                    for (var selected : selectable.getSelectedRecipeTypes()) {
                        if (selected != null && selected.registryName != null
                                && RecipeTypeSharedSearchSets.isShared(type, selected.registryName.toString())) return false;
                    }
                    return true;
                });
            }
            long capacity = -1L;
            long threads = 1L;
            if (logic instanceof SelectableRecipeTypeSetRecipeLogic selectable) {
                capacity = selectable.gtShanhai$getStuckWatchParallelLimit();
                threads = selectable.getMultipleThreads();
            } else if (logic instanceof GTLAddMultipleRecipesLogic multiple) {
                threads = multiple.getMultipleThreads();
                capacity = StellarPatternWaitPolicy.multiply(multiple.getParallel().getMaxParallel(), threads);
            } else if (logic instanceof MultipleRecipesLogic multiple) {
                threads = 64L;
                capacity = StellarPatternWaitPolicy.multiply(multiple.getParallel().getMaxParallel(), threads);
            } else if (host instanceof ParallelMachine parallel) {
                capacity = parallel.getMaxParallel();
            }
            GTRecipe batch = logic.getLastRecipe();
            long allocated = 0L;
            if (host.isActive() && batch != null) {
                allocated = logic instanceof SelectableRecipeTypeSetRecipeLogic selectable
                        ? selectable.gtShanhai$getLastAllocatedParallel()
                        : logic instanceof StellarPatternParallelState observed
                                ? observed.gtShanhai$getObservedAllocatedParallel() : -1L;
            }
            long queued = 0L;
            for (String type : queuedTypes) {
                if (supportsType(types, type, machine.getControllers().size() == 1)) queued++;
            }
            boolean failed = !host.isWorkingEnabled();
            if (host instanceof PrimordialOmegaEngineModuleBase module) {
                String error = module.getModuleConditionError();
                failed |= error != null && !error.isBlank();
            }
            if (logic instanceof IRecipeStatus status) {
                RecipeResult work = status.getWorkingStatus();
                failed |= work != null && !work.isSuccess();
            }
            String capacityLabel = logic instanceof PrimordialModuleRecipeLogic ? "并发配方容量" : "总并行";
            hosts.add(new HostAssessment(controller.self(), types, capacityLabel, capacity, threads, allocated, queued,
                    logic.isWorking() && host.isWorkingEnabled(), failed,
                    logic.getProgress(), logic.getDuration(), logic.getTotalContinuousRunningTime(), batch));
        }
        return new MachineAssessment(hosts, machine.getControllers().size() == 1);
    }

    private static boolean supportsType(List<String> types, String type, boolean onlyHost) {
        if (type == null || type.isBlank() || "<none>".equals(type)) return onlyHost;
        for (String candidate : types) if (RecipeTypeSharedSearchSets.isShared(candidate, type)) return true;
        return false;
    }

    private static boolean evaluateParallelWait(RecipeTypePatternBufferPartMachine machine, Watch watch,
                                                MachineAssessment assessment, long now) {
        boolean waiting = false;
        boolean strongFailure = !machine.gtShanhai$strongMatchReason(watch.slot).isEmpty();
        watch.mintRestrictions.clear();
        GTRecipe mintRecipe = null;
        boolean resolvedMintRecipe = false;
        List<String> details = new ArrayList<>();
        for (HostAssessment host : assessment.hosts) {
            if (!supportsType(host.types, watch.patternRecipeTypeId, assessment.onlyHost)) continue;
            boolean mintFailure = false;
            if (host.machine instanceof PrimordialCoinForgeModule forge) {
                if (!resolvedMintRecipe) {
                    mintRecipe = PatternRecipeTypeHelper.resolveRecipeForPattern(machine.getLevel(),
                            watch.patternRecipeTypeId, watch.patternRecipeId);
                    if (mintRecipe != null && !PatternIdentityMatcher.matches(mintRecipe, watch.patternDetails)) mintRecipe = null;
                    resolvedMintRecipe = true;
                }
                String restriction = forge.getRecipeLogic().describeMintRestriction(mintRecipe, watch.patternDetails);
                mintFailure = !restriction.isEmpty();
                if (mintFailure) watch.mintRestrictions.put(host.machine, host.machine.getPos().toShortString() + " " + restriction);
            }
            var policy = watch.hostProgress.computeIfAbsent(host.machine, ignored -> new StellarPatternWaitPolicy());
            boolean deferred = policy.observe(now, watch.timeout, host.capacity, host.allocated, host.queued,
                    host.working, host.failed || strongFailure || mintFailure, host.progress, host.successfulTicks, host.duration, host.batch);
            waiting |= deferred;
            if (deferred) watch.warningTick = Math.max(watch.warningTick, policy.progressDeadline(watch.timeout));
            details.add(host.machine.getPos().toShortString() + " " + host.capacityLabel + "="
                    + (host.capacity > 0L ? host.capacity : "未知") + " 线程倍率=" + host.threads
                    + " 本轮分配=" + (host.allocated >= 0L ? host.allocated : "未知") + " 等待槽=" + host.queued
                    + " 进度=" + host.progress + "/" + host.duration
                    + (deferred ? " 等待并行" : "未满足并行等待条件"));
        }
        watch.parallelAssessment = details.isEmpty() ? "无可用的对应配方主机" : String.join(" | ", details);
        return waiting;
    }

    private record MachineKey(ResourceKey<Level> dimension, BlockPos pos) {}
    private record MachineAssessment(List<HostAssessment> hosts, boolean onlyHost) {}
    private record HostAssessment(MetaMachine machine, List<String> types, String capacityLabel, long capacity, long threads,
                                  long allocated, long queued, boolean working, boolean failed,
                                  int progress, int duration, long successfulTicks, GTRecipe batch) {}

    public static String currentHostStatus(RecipeTypePatternBufferPartMachine machine) {
        if (machine == null) return "";
        List<String> statuses = collectHostStatuses(machine);
        if (statuses.isEmpty()) return "";
        return String.join(" | ", statuses);
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

    private static String collectStuckReason(RecipeTypePatternBufferPartMachine machine, int slot) {
        String strong = machine.gtShanhai$fullStrongMatchReason(slot);
        String base = "原料仍停留在星律槽位，"
                + DShanhaiConfig.COMMON.recipeTypePatternStuckWarningSeconds.get()
                + " 秒内未被主机取用";
        if (strong != null && !strong.isEmpty()) {
            base = strong + "；" + base;
        }
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
            appendRecipeLogicState(result, logicMachine);
            if (logicMachine instanceof PrimordialOmegaEngineModuleBase module) {
                appendPlain(result, "挂载", module.getModuleConditionError());
            }
            if (logicMachine instanceof PrimordialCoinForgeModule forge) {
                appendPlain(result, "铸币限制", "物质模块 Lv." + forge.getModuleLevel()
                        + "；铸币权限: " + CoinForgeMintGate.permissionText(forge.getModuleLevel())
                        + "；铸币基础并行（已折算）: " + forge.getCurrentParallel()
                        + "；每配方有效上限: " + forge.getRecipeLogic().getMintParallelLimit()
                        + (forge.hasParallelOverdriver() ? "（寰宇超限器不受 1/4 限制）" : "（正常并行的 1/4）"));
            }
            if (logicMachine.getRecipeLogic() instanceof IRecipeStatus status) {
                appendStatus(result, "配方", status.getRecipeStatus());
                appendStatus(result, "工作", status.getWorkingStatus());
            }
        }
        return result;
    }

    private static void appendRecipeLogicState(List<String> result, IRecipeLogicMachine logicMachine) {
        if (!logicMachine.isWorkingEnabled()) {
            result.add("工作=已暂停工作");
        } else if (!logicMachine.isActive()) {
            result.add("工作=未运行");
        }
    }

    private static void appendStatus(List<String> result, String label, @Nullable RecipeResult status) {
        if (status == null || status.isSuccess() || status.reason() == null) return;
        appendPlain(result, label, status.reason().getString());
    }

    private static void appendPlain(List<String> result, String label, @Nullable String text) {
        if (text == null || text.isBlank()) return;
        result.add(label + "=" + text.replace('\n', ' '));
    }

    private static final class Snapshot {
        private final Object2LongMap<AEItemKey> items;
        private final Object2LongMap<AEFluidKey> fluids;

        private Snapshot(Object2LongMap<AEItemKey> items, Object2LongMap<AEFluidKey> fluids) {
            this.items = items;
            this.fluids = fluids;
        }
    }

    static final class SlotKey {
        private final ResourceLocation dimension;
        private final long pos;
        private final int slot;

        SlotKey(ResourceLocation dimension, long pos, int slot) {
            this.dimension = dimension;
            this.pos = pos;
            this.slot = slot;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SlotKey key && slot == key.slot && pos == key.pos
                    && dimension.equals(key.dimension);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * dimension.hashCode() + Long.hashCode(pos)) + slot;
        }
    }

    private static final class Watch {
        private final ResourceKey<Level> dimension;
        private final BlockPos pos;
        private final int slot;
        private long dueTick;
        private long warningTick;
        private final long initialWarningTick;
        private final long timeout;
        private final Map<MetaMachine, StellarPatternWaitPolicy> hostProgress = new HashMap<>();
        private final Map<MetaMachine, String> mintRestrictions = new HashMap<>();
        private String parallelAssessment = "";
        private final Snapshot snapshot;
        private final List<String> hostRecipeTypeIds;
        private final String patternRecipeTypeId;
        private final String patternRecipeId;
        private final IPatternDetails patternDetails;
        @Nullable
        private final Integer aePlayerId;

        private Watch(ResourceKey<Level> dimension, BlockPos pos, int slot, long dueTick, Snapshot snapshot,
                List<String> hostRecipeTypeIds, String patternRecipeTypeId, String patternRecipeId,
                IPatternDetails patternDetails, @Nullable Integer aePlayerId) {
            this.dimension = dimension;
            this.pos = pos;
            this.slot = slot;
            this.timeout = (long) DShanhaiConfig.COMMON.recipeTypePatternStuckWarningSeconds.get() * TICKS_PER_SECOND;
            this.warningTick = dueTick;
            this.initialWarningTick = dueTick;
            this.dueTick = dueTick - timeout + Math.min(TICKS_PER_SECOND, timeout);
            this.snapshot = snapshot;
            this.hostRecipeTypeIds = hostRecipeTypeIds;
            this.patternRecipeTypeId = patternRecipeTypeId;
            this.patternRecipeId = patternRecipeId;
            this.patternDetails = patternDetails;
            this.aePlayerId = aePlayerId;
        }
    }
}
