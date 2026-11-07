package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.mixin.PrimordialRecipeThreadStateAccessor;
import com.dishanhai.gt_shanhai.api.machine.RecipeThreadSnapshot;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadPriority;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadRecipeIdentity.Identity;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.syncdata.GTRecipePayload;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/** 山海：原初批次的多线程并行。所有庫存及能源交易仍在伺服器執行緒執行。 */
public abstract class PrimordialThreadedRecipeLogic extends SelectableRecipeTypeSetRecipeLogic
        implements com.dishanhai.gt_shanhai.api.machine.IRecipeThreadDisplay {
    private static final String SAVE_KEY = "sh_primordial_recipe_threads";
    private static final ManagedFieldHolder THREAD_FIELDS =
            new ManagedFieldHolder(PrimordialThreadedRecipeLogic.class, MANAGED_FIELD_HOLDER);
    private static final Frame EMPTY_FRAME = new Frame();
    private PrimordialThreadScheduler<Frame> threads = new PrimordialThreadScheduler<>();
    private boolean initialized;
    private boolean tickingThreads;
    private boolean admission;
    private boolean pendingSetup;
    private boolean drainRequested;
    private long allowance;
    private long continuousTicks;
    private long completedThreadBatches;
    private RecipeThreadPriority<GTRecipe> priority;
    private boolean priorityInputHandled;
    private LinkedHashSet<PrimordialThreadRecipeIdentity.Identity> matchingRecipeSources;
    private Map<Identity, Long> recipePoolReservations;
    private Map<GTRecipe, Identity> recipePoolIdentities;
    private Set<GTRecipe> admissionCandidates;
    @DescSynced private int activeRecipeThreads;
    @DescSynced private long reservedThreadParallel;

    protected PrimordialThreadedRecipeLogic(SelectableRecipeTypeSetMachine machine) { super(machine); }
    protected PrimordialThreadedRecipeLogic(SelectableRecipeTypeSetMachine machine,
                                             Predicate<IRecipeLogicMachine> beforeWorking) {
        super(machine, beforeWorking);
    }

    @Override public ManagedFieldHolder getFieldHolder() { return THREAD_FIELDS; }
    public final Set<GTRecipe> priorityCandidates(Set<GTRecipe> candidates) {
        return priorityCandidates(() -> candidates);
    }

    public final Set<GTRecipe> priorityCandidates(java.util.function.Supplier<Set<GTRecipe>> lookup) {
        if (priority == null) return lookup.get();
        do {
            List<GTRecipe> selected = priority.select(lookup);
            if (selected.isEmpty() || recipePoolReservations == null
                    || getThreadRecipePoolRemaining(selected.getFirst()) > 0L) return new LinkedHashSet<>(selected);
        } while (priority.retry());
        return Set.of();
    }

    protected final Set<GTRecipe> threadCandidates(java.util.function.Supplier<Set<GTRecipe>> lookup) {
        if (!admission) return lookup.get();
        if (admissionCandidates == null) admissionCandidates = new LinkedHashSet<>(lookup.get());
        return admissionCandidates;
    }

    protected long estimateThreadRecipeTicks(GTRecipe recipe) {
        return Math.max(1, getLimited().getLimitedDuration());
    }

    protected abstract long getUnreservedTotalParallelLimit();

    /** 只有能在最終批次提供實際並行數的配方路徑才可啟用。 */
    protected boolean tracksActualParallel() { return false; }

    public final boolean usesIndependentRecipePools() {
        return getMachine() instanceof PrimordialOmegaEngineModuleBase module
                && tracksActualParallel() && module.hasParallelOverdriver();
    }

    protected final long getThreadRecipePoolRemaining(GTRecipe recipe) {
        return Long.MAX_VALUE - recipePoolReserved(recipe);
    }

    private long recipePoolReserved(GTRecipe recipe) {
        if (recipePoolReservations == null) return 0L;
        if (recipePoolIdentities == null) recipePoolIdentities = new java.util.IdentityHashMap<>();
        Identity source = recipePoolIdentities.computeIfAbsent(recipe, PrimordialThreadRecipeIdentity::fromRecipe);
        if (source == null) return Long.MAX_VALUE;
        long unknown = recipePoolReservations.getOrDefault(null, 0L);
        long known = recipePoolReservations.getOrDefault(source, 0L);
        return unknown + Math.min(known, Long.MAX_VALUE - unknown);
    }

    private Map<Identity, Long> collectRecipePoolReservations() {
        Map<Identity, Long> reserved = new HashMap<>();
        for (var lane : threads.lanes()) {
            reserveRecipePool(reserved, lane.task(), lane.reserved());
        }
        return reserved;
    }

    private void reserveRecipePool(Map<Identity, Long> reserved, Frame frame, long amount) {
        if (frame.sourceReservations != null) {
            frame.sourceReservations.forEach((source, parallel) ->
                    reserved.merge(source, parallel, (a, b) -> a + Math.min(b, Long.MAX_VALUE - a)));
            return;
        }
        var sources = PrimordialThreadRecipeIdentity.sourcesOf(frame.recipe);
        if (sources.isEmpty()) {
            // 舊版沒有保存來源時，保守占用每個池，直到該批結算。
            sources = java.util.Collections.singletonList(PrimordialThreadRecipeIdentity.fromRecipe(frame.origin));
        }
        // 舊跨配方批次沒有逐配方數量，先用原批次預留保護各來源。
        for (Identity source : sources) {
            reserved.merge(source, amount, (a, b) -> a + Math.min(b, Long.MAX_VALUE - a));
        }
    }

    protected final long getBaseParallelCapacity() { return super.getTotalParallelLimit(); }

    @Override protected long getTotalParallelLimit() {
        long total = getUnreservedTotalParallelLimit();
        return admission ? Math.min(total, allowance) : total;
    }

    /** 特殊 AE 提取器也必須使用相同份額，不得先取走整個訂單。 */
    protected final long getThreadParallelAllowance() {
        return admission ? allowance : getUnreservedTotalParallelLimit();
    }

    protected final BigInteger getThreadPowerBudget(BigInteger fullPower, boolean consumer) {
        if (!admission) return fullPower;
        BigInteger used = BigInteger.ZERO;
        for (var lane : threads.lanes()) {
            Frame frame = lane.task();
            if (frame.progress >= frame.duration || !(frame.recipe instanceof IWirelessGTRecipe wireless)) continue;
            BigInteger eut = wireless.getWirelessEuTickInputs();
            if (eut != null && (consumer ? eut.signum() < 0 : eut.signum() > 0)) used = used.add(eut.abs());
        }
        // 按已啟動批次的真實 EU/t 預留功率，避免「無限執行緒」將整數功率除為零。
        return fullPower.subtract(used).max(BigInteger.ZERO);
    }

    protected final boolean isThreadRecipeLookupAllowed() { return !tickingThreads || admission; }
    public final int getActiveRecipeThreadCount() { return activeRecipeThreads; }
    @Override public final int getRecipeExecutionBatchCount() { return displayLanes().size(); }
    @Override public final boolean hasMergedRecipeBatches() {
        return displayLanes().stream().anyMatch(lane -> lane.threadCount() > 1);
    }
    public final boolean isRecipeThreadDisplayAvailable() { return true; }
    public final long getReservedThreadParallel() { return reservedThreadParallel; }

    public final RecipeThreadSnapshot getRecipeThreadSnapshot() {
        var lanes = displayLanes();
        boolean enabled = threads.isEnabled() && !isSuspend();
        boolean available = enabled
                && (!(getMachine() instanceof IMultiController controller) || controller.isFormed());
        int running = 0;
        for (var lane : lanes) {
            Frame frame = lane.task();
            if (available && frame.status == Status.WORKING && !frame.pendingSetup
                    && frame.progress < frame.duration && frame.recipe != null) running += frame.batchCount;
        }
        long capacity = getUnreservedTotalParallelLimit();
        long configuredThreads = getConfiguredRecipeThreadCapacity();
        boolean independentPools = usesIndependentRecipePools();
        return new RecipeThreadSnapshot(Math.min(Math.max(0L, capacity), configuredThreads),
                threads.activeThreads(), running,
                enabled, nativeDrainRequested(), independentPools ? 0L : capacity, displayReserved(),
                continuousTicks, completedThreadBatches,
                threads.admissionShare(capacity, configuredThreads, tracksActualParallel(), independentPools) <= 0L);
    }

    /** Jade 唯讀快照：不切換 frame、不查配方、不交易庫存，僅分頁傳送有限的批次。 */
    public final CompoundTag getRecipeThreadDisplay(int first, int maximum) {
        CompoundTag data = new CompoundTag();
        var lanes = displayLanes();
        int limit = Math.max(0, Math.min(8, maximum));
        int offset = Math.max(0, Math.min(first, Math.max(0, lanes.size() - 1)));
        boolean enabled = threads.isEnabled() && !isSuspend();
        boolean available = enabled
                && (!(getMachine() instanceof IMultiController controller) || controller.isFormed())
                && (!(getMachine() instanceof PrimordialOmegaEngineModuleBase module) || module.canModuleWork());
        data.putBoolean("enabled", enabled);
        data.putInt("active", threads.activeThreads());
        data.putInt("executionBatches", lanes.size());
        data.putLong("capacity", getRecipeThreadCapacity());
        data.putLong("reserved", displayReserved());
        data.putLong("parallelCapacity", getUnreservedTotalParallelLimit());
        data.putBoolean("independentRecipePools", usesIndependentRecipePools());
        data.putBoolean("draining", nativeDrainRequested());
        data.putInt("offset", offset);
        BigInteger consumption = BigInteger.ZERO, production = BigInteger.ZERO;
        int waiting = 0;
        int running = 0;
        for (var lane : lanes) {
            Frame frame = lane.task();
            if (enabled && (!available || frame.status == Status.WAITING || frame.pendingSetup)) waiting += frame.batchCount;
            if (available && frame.status == Status.WORKING && !frame.pendingSetup
                    && frame.progress < frame.duration && frame.recipe != null) running += frame.batchCount;
            if (!available || frame.status != Status.WORKING || frame.pendingSetup
                    || frame.progress >= frame.duration || frame.recipe == null) continue;
            if (frame.recipe instanceof IWirelessGTRecipe wireless) {
                BigInteger eut = wireless.getWirelessEuTickInputs();
                if (eut == null) continue;
                if (eut.signum() < 0) consumption = consumption.subtract(eut);
                else production = production.add(eut);
            } else {
                consumption = consumption.add(BigInteger.valueOf(RecipeHelper.getInputEUt(frame.recipe)).max(BigInteger.ZERO));
                production = production.add(BigInteger.valueOf(RecipeHelper.getOutputEUt(frame.recipe)).max(BigInteger.ZERO));
            }
        }
        data.putInt("waiting", waiting);
        data.putInt("running", running);
        data.putString("consumption", formatThreadEnergy(consumption));
        data.putString("production", formatThreadEnergy(production));
        ListTag rows = new ListTag();
        for (int i = offset; i < lanes.size() && i - offset < limit; i++) {
            var lane = lanes.get(i);
            Frame frame = lane.task();
            CompoundTag row = new CompoundTag();
            row.putLong("index", lane.index());
            row.putInt("mergedBatches", frame.batchCount);
            row.putLong("reserved", lane.reserved());
            row.putInt("progress", frame.progress);
            row.putInt("duration", frame.duration);
            row.putInt("remainingTicks", Math.max(0, frame.duration - frame.progress));
            row.putString("status", available ? frame.status.name() : enabled ? "WAITING" : "SUSPEND");
            if (frame.recipe != null) {
                PrimordialThreadRecipeIdentity.appendDisplay(row, frame.recipe);
                if (frame.sourceReservations != null) {
                    for (Tag element : row.getList("recipes", Tag.TAG_COMPOUND)) {
                        CompoundTag source = (CompoundTag) element;
                        Long reserved = frame.sourceReservations.get(new Identity(
                                source.getString("recipeType"), source.getString("recipeId")));
                        if (reserved != null) source.putLong("reserved", reserved);
                    }
                }
                BigInteger batchConsumption = BigInteger.ZERO;
                BigInteger batchProduction = BigInteger.ZERO;
                if (available && frame.status == Status.WORKING && !frame.pendingSetup
                        && frame.progress < frame.duration) {
                    if (frame.recipe instanceof IWirelessGTRecipe wireless) {
                        BigInteger eut = wireless.getWirelessEuTickInputs();
                        if (eut != null) {
                            if (eut.signum() < 0) batchConsumption = eut.abs();
                            else batchProduction = eut;
                        }
                    } else {
                        batchConsumption = BigInteger.valueOf(RecipeHelper.getInputEUt(frame.recipe))
                                .max(BigInteger.ZERO);
                        batchProduction = BigInteger.valueOf(RecipeHelper.getOutputEUt(frame.recipe))
                                .max(BigInteger.ZERO);
                    }
                }
                row.putString("consumption", formatThreadEnergy(batchConsumption));
                row.putString("production", formatThreadEnergy(batchProduction));
            }
            Component reason = enabled && !available
                    ? Component.translatable("gt_shanhai.primordial.threads.waiting_machine")
                    : enabled ? frame.reason : null;
            if (reason != null) row.putString("reason", Component.Serializer.toJson(reason));
            appendThreadOutputs(row, frame.recipe);
            rows.add(row);
        }
        data.put("threads", rows);
        return data;
    }

    public static String formatThreadEnergy(BigInteger value) {
        if (value.bitLength() <= 256) return value.toString();
        int bits = value.bitLength();
        double log = Math.log10(value.shiftRight(bits - 53).doubleValue()) + (bits - 53) * Math.log10(2);
        int exponent = (int) Math.floor(log);
        return String.format(java.util.Locale.ROOT, "%.3fE%d", Math.pow(10, log - exponent), exponent);
    }

    public static void appendThreadOutputs(CompoundTag row, GTRecipe recipe) {
        if (recipe == null || recipe.outputs.isEmpty()) return;
        boolean merged = row.getInt("mergedBatches") > 1;
        ListTag outputs = new ListTag();
        int total = 0;
        for (var ingredient : RecipeHelper.getOutputContents(recipe, ItemRecipeCapability.CAP)) {
            var stacks = ingredient.getItems();
            if (stacks.length == 0 || stacks[0].isEmpty()) continue;
            total++;
            if (!merged && outputs.size() >= 4) continue;
            CompoundTag output = new CompoundTag();
            output.put("item", stacks[0].copyWithCount(1).save(new CompoundTag()));
            output.putLong("amount", ingredient instanceof LongIngredient large
                    ? large.getActualAmount() : stacks[0].getCount());
            outputs.add(output);
        }
        for (var fluid : RecipeHelper.getOutputFluids(recipe)) {
            if (fluid.isEmpty()) continue;
            total++;
            if (!merged && outputs.size() >= 4) continue;
            CompoundTag output = new CompoundTag();
            output.put("fluid", (merged ? fluid.copy(1L) : fluid).saveToTag(new CompoundTag()));
            output.putLong("amount", fluid.getAmount());
            outputs.add(output);
        }
        row.put("outputs", merged ? groupThreadOutputs(outputs) : outputs);
        if (!merged) row.putInt("moreOutputs", total - outputs.size());
    }

    static ListTag groupThreadOutputs(ListTag outputs) {
        Map<CompoundTag, BigInteger> totals = new LinkedHashMap<>();
        for (Tag element : outputs) {
            CompoundTag identity = ((CompoundTag) element).copy();
            long amount = identity.getLong("amount");
            identity.remove("amount");
            totals.merge(identity, BigInteger.valueOf(amount), BigInteger::add);
        }
        ListTag grouped = new ListTag();
        totals.forEach((identity, amount) -> {
            CompoundTag output = identity.copy();
            if (amount.bitLength() <= 63) output.putLong("amount", amount.longValue());
            else output.putString("amountExact", amount.toString());
            grouped.add(output);
        });
        return grouped;
    }

    public final long getRecipeThreadCapacity() {
        return Math.min(Math.max(0L, getUnreservedTotalParallelLimit()), getConfiguredRecipeThreadCapacity());
    }

    private long getConfiguredRecipeThreadCapacity() {
        if (getMachine() instanceof PrimordialOmegaEngineModuleBase module && module.hasParallelOverdriver()) {
            return PrimordialOmegaEngineModuleBase.OVERDRIVER_THREAD_CAPACITY;
        }
        long configured = getMachine() instanceof PrimordialOmegaEngineModuleBase module
                ? module.getParallelRecipeThreadCount()
                : getMachine() instanceof PrimordialOmegaEngineMachine host
                ? host.getParallelRecipeThreadCount() : 1L;
        return com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadSources.capacity(machine, configured);
    }

    protected final List<GTRecipe> getRunningThreadRecipes() {
        return threads == null ? List.of()
                : displayLanes().stream().map(lane -> lane.task().recipe).toList();
    }

    /** 山海：唯讀在途引用，包含暫停、缺電及等待產物交付的批次。 */
    public final boolean hasRunningRecipeMatching(Predicate<GTRecipe> predicate) {
        if (threads == null) return false;
        for (var lane : displayLanes()) {
            Frame frame = lane.task();
            GTRecipe running = frame.recipe != null ? frame.recipe : frame.origin;
            if (running != null && predicate.test(running)) return true;
        }
        return false;
    }

    private List<PrimordialThreadScheduler.Lane<Frame>> displayLanes() {
        return threads.lanes();
    }

    private long displayReserved() {
        return threads.reserved();
    }

    public final void onThreadInputsChanged() {
        invalidateLookupSetCache();
        if (threads != null) threads.requestSearch();
    }

    @Override
    protected final void onRecipeTypeSearchYield() {
        threads.requestSearch();
    }

    @Override public void onRecipeTypeSelectionChanged() {
        super.onRecipeTypeSelectionChanged();
        if (threads != null) threads.requestSearch();
    }

    @Override public void updateTickSubscription() {
        if (tickingThreads) return; // 本 tick 的狀態切換及扣料通知不能自我喚醒。
        if (threads != null) threads.requestRetry();
        super.updateTickSubscription();
    }

    @Override protected GTRecipe getGTRecipe() {
        return isThreadRecipeLookupAllowed() ? super.getGTRecipe() : null;
    }

    @Override public void findAndHandleRecipe() {
        if (!isThreadRecipeLookupAllowed()) return;
        lastRecipe = null;
        setRecipeStatus(null);
        var previousSources = matchingRecipeSources;
        matchingRecipeSources = new LinkedHashSet<>();
        try {
            GTRecipe match = getGTRecipe();
            // 原初建構最終配方時已真實扣料；即使總輸出暫時不夠，也必須保留這份批次。
            if (match != null) {
                PrimordialThreadRecipeIdentity.store(match, matchingRecipeSources);
                setupRecipe(match);
            }
        } finally {
            matchingRecipeSources = previousSources;
        }
    }

    /** 只在本邏輯當次查找內記錄真實扣料成功的配方，不讀取其他執行緒或新樣板。 */
    public final void onThreadRecipeInputHandled(GTRecipe recipe) {
        if (matchingRecipeSources == null) return;
        if (priority != null) priorityInputHandled = true;
        var identity = PrimordialThreadRecipeIdentity.fromRecipe(recipe);
        if (identity != null) matchingRecipeSources.add(identity);
    }

    @Override public void setupRecipe(GTRecipe recipe) {
        pendingSetup = false;
        setupThreadRecipe(recipe);
        if (tickingThreads && (lastRecipe == null || duration <= 0 || isIdle())) {
            // getGTRecipe 已扣料。啟動條件拒絕時保留同一快照，不能把它當作尋配方失敗。
            lastRecipe = recipe;
            progress = 0;
            duration = Math.max(1, recipe.duration);
            pendingSetup = true;
            setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_setup"));
        }
    }

    protected void setupThreadRecipe(GTRecipe recipe) { super.setupRecipe(recipe); }

    private boolean advanceFrame(Frame frame) {
        apply(frame);
        if (pendingSetup) {
            setupRecipe(lastRecipe);
            if (pendingSetup) { captureInto(frame); return false; }
        }
        int before = progress;
        if (progress < duration) handleRecipeWorking();
        if (progress > before) continuousTicks += frame.batchCount;
        if (fuelTime > 0) fuelTime--;
        if (progress >= duration) {
            if (!canFinishThreadBatch(lastRecipe)) {
                setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_output"));
            } else {
                setNativeDrain(false);
                try { onRecipeFinish(); }
                finally { setNativeDrain(drainRequested); }
                completedThreadBatches += frame.batchCount;
                lastRecipe = null;
                duration = progress = 0;
            }
        }
        captureInto(frame);
        return lastRecipe == null;
    }

    @Override public void setStatus(Status status) {
        if (!tickingThreads) { super.setStatus(status); return; }
        access().shanhai$threadStatus(status);
        if (status != Status.WAITING) access().shanhai$threadWaitingReason(null);
    }

    @Override public void interruptRecipe() {
        if (!tickingThreads) { super.interruptRecipe(); return; }
        // 批次輸入已扣除，暫時不可工作時不可清掉該批次。
        setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_machine"));
    }

    private PrimordialRecipeThreadStateAccessor access() {
        return (PrimordialRecipeThreadStateAccessor) this;
    }

    private void importLegacyBatch() {
        if (initialized) return;
        initialized = true;
        threads.setEnabled(!isSuspend());
        if (lastRecipe != null && duration > 0) {
            threads.restore(0L, Math.max(1L, getUnreservedTotalParallelLimit()), capture());
        }
    }

    @Override public void serverTick() {
        importLegacyBatch();
        if (!threads.isEnabled() || isSuspend()) return;
        if (getMachine() instanceof IMultiController controller && !controller.isFormed()) return;
        if (getMachine() instanceof PrimordialOmegaEngineModuleBase module && !module.canModuleWork()) return;

        long now = getMachine().getOffsetTimer();
        drainRequested = nativeDrainRequested();
        Status previousStatus = getStatus();
        if (threads.activeThreads() == 0 && previousStatus == Status.IDLE
                && !drainRequested && !threads.isSearchDue(now)) return;
        boolean independentPools = usesIndependentRecipePools();
        tickingThreads = true;
        try {
            threads.tick(now, getUnreservedTotalParallelLimit(),
                    getConfiguredRecipeThreadCapacity(),
                    tracksActualParallel(), independentPools,
                    new PrimordialThreadScheduler.Runner<>() {
                        public boolean advance(Frame frame) {
                            return advanceFrame(frame);
                        }

                        @Override public long reservation(Frame frame, long share) {
                            long actual = frame.recipe.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY);
                            long reserved = actual > 0L ? Math.min(actual, share) : share;
                            Map<Identity, Long> sources = new HashMap<>();
                            reserveRecipePool(sources, frame, reserved);
                            frame.sourceReservations = sources;
                            if (recipePoolReservations != null) reserveRecipePool(recipePoolReservations, frame, reserved);
                            return reserved;
                        }

                        @Override public int admissionLimit() { return tracksActualParallel() ? 96 : 1; }

                        @Override public boolean merge(Frame existing, Frame incoming) {
                            if (existing.batchCount >= 4 || existing.progress != 0 || incoming.progress != 0
                                    || existing.duration != incoming.duration || existing.pendingSetup || incoming.pendingSetup
                                    || existing.status != Status.WORKING || incoming.status != Status.WORKING
                                    || existing.fuel != 0 || incoming.fuel != 0
                                    || !existing.chances.isEmpty() || !incoming.chances.isEmpty()) return false;
                            GTRecipe combined = PrimordialThreadBatchMerge.merge(existing.recipe, incoming.recipe);
                            if (combined == null || !canFinishThreadBatch(combined)) return false;
                            Map<Identity, Long> sources = new HashMap<>();
                            reserveRecipePool(sources, existing,
                                    existing.recipe.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY));
                            reserveRecipePool(sources, incoming,
                                    incoming.recipe.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY));
                            existing.recipe = combined;
                            existing.sourceReservations = sources;
                            existing.batchCount += incoming.batchCount;
                            return true;
                        }

                        public Frame start(long share) {
                            if (drainRequested) return null;
                            apply(EMPTY_FRAME);
                            allowance = share;
                            admission = true;
                            priorityInputHandled = false;
                            if (independentPools && recipePoolReservations == null) recipePoolReservations = collectRecipePoolReservations();
                            if (priority == null) priority = getRecipeThreadCapacity() > 1L
                                    ? new RecipeThreadPriority<>(PrimordialThreadedRecipeLogic.this::estimateThreadRecipeTicks,
                                            recipe -> recipe.duration, PrimordialThreadedRecipeLogic.this::recipePoolReserved) : null;
                            try {
                                do {
                                    findAndHandleRecipe();
                                    if (lastRecipe != null || priorityInputHandled) break;
                                } while (priority != null && priority.retry());
                                Frame frame = lastRecipe != null && duration > 0 ? capture() : null;
                                if (frame != null && priority != null) priority.nextBatch();
                                return frame;
                            } finally {
                                priorityInputHandled = false;
                                admission = false;
                                allowance = 0L;
                            }
                        }
                    });
        } finally {
            admission = false;
            priority = null;
            admissionCandidates = null;
            recipePoolReservations = null;
            recipePoolIdentities = null;
            tickingThreads = false;
            project(previousStatus);
        }
    }

    private void project(Status previousStatus) {
        activeRecipeThreads = threads.activeThreads();
        reservedThreadParallel = threads.reserved();
        var lanes = threads.lanes();
        Frame display = lanes.isEmpty() ? EMPTY_FRAME : lanes.getFirst().task();
        for (int i = 1; display.status != Status.WORKING && i < lanes.size(); i++) {
            Frame frame = lanes.get(i).task();
            if (frame.status == Status.WORKING) display = frame;
        }
        apply(display);
        totalContinuousRunningTime = continuousTicks;
        Status aggregate = !threads.isEnabled() || drainRequested && lanes.isEmpty()
                ? Status.SUSPEND : display.status;
        if (aggregate == Status.SUSPEND) {
            drainRequested = false;
            setNativeDrain(false);
            threads.setEnabled(false);
        }
        access().shanhai$threadStatus(previousStatus);
        super.setStatus(aggregate);
    }

    protected boolean canFinishThreadBatch(GTRecipe recipe) {
        return RecipeRunnerHelper.matchRecipeOutput(machine, recipe);
    }

    private boolean nativeDrainRequested() {
        return machine instanceof ISuspendableMachine suspendable
                ? suspendable.gtlcore$isSuspendAfterFinish() : drainRequested;
    }

    private void setNativeDrain(boolean requested) {
        if (machine instanceof ISuspendableMachine suspendable) suspendable.gtlcore$setSuspendAfterFinish(requested);
    }

    @Override public void setWorkingEnabled(boolean enabled) {
        drainRequested = false;
        setNativeDrain(false);
        if (threads != null) {
            importLegacyBatch();
            threads.setEnabled(enabled);
            threads.requestSearch();
        }
        super.setWorkingEnabled(enabled);
    }

    @Override public void inValid() {
        importLegacyBatch();
        Status previous = getStatus();
        for (var lane : displayLanes()) { apply(lane.task()); super.inValid(); }
        project(previous);
    }

    @Override public void resetRecipeLogic() {
        if (threads == null) { super.resetRecipeLogic(); return; }
        // 結構失效不蒸發已扣料批次；重新成型後接續。
        inValid();
    }

    public final void saveRecipeThreads(CompoundTag tag) {
        importLegacyBatch();
        ListTag list = new ListTag();
        for (var lane : threads.lanes()) {
            CompoundTag data = lane.task().save(threadRecipePayload());
            data.putLong("index", lane.index());
            data.putLong("reserved", lane.reserved());
            list.add(data);
        }
        tag.put(SAVE_KEY, list);
        tag.putBoolean("sh_recipe_threads_drain", nativeDrainRequested());
        tag.putBoolean("sh_recipe_threads_enabled", threads.isEnabled());
        tag.putLong("sh_recipe_threads_continuous", continuousTicks);
    }

    public final void loadRecipeThreads(CompoundTag tag) {
        if (!tag.contains(SAVE_KEY, Tag.TAG_LIST)) return;
        var restored = new PrimordialThreadScheduler<Frame>();
        for (Tag element : tag.getList(SAVE_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag data = (CompoundTag) element;
            Frame frame = Frame.load(data, threadRecipePayload());
            restored.restore(data.getLong("index"), data.getLong("reserved"), frame, frame.batchCount);
        }
        threads = restored;
        initialized = true; // 空清單也具權威，不能把 native 顯示投影重複當成一批。
        threads.setEnabled(tag.contains("sh_recipe_threads_enabled")
                ? tag.getBoolean("sh_recipe_threads_enabled") : !isSuspend());
        drainRequested = tag.getBoolean("sh_recipe_threads_drain");
        setNativeDrain(drainRequested);
        continuousTicks = tag.getLong("sh_recipe_threads_continuous");
        project(getStatus());
    }

    private Frame capture() {
        Frame frame = new Frame();
        captureInto(frame);
        return frame;
    }

    protected GTRecipePayload threadRecipePayload() { return new GTRecipePayload(); }

    private void captureInto(Frame frame) {
        frame.recipe = lastRecipe;
        frame.origin = lastOriginRecipe;
        frame.progress = progress; frame.duration = duration;
        frame.fuel = fuelTime; frame.maxFuel = fuelMaxTime;
        frame.continuous = totalContinuousRunningTime;
        frame.status = getStatus(); frame.active = access().shanhai$threadActive();
        frame.reason = access().shanhai$threadWaitingReason();
        frame.dirty = recipeDirty;
        frame.pendingSetup = pendingSetup;
        frame.recipeStatus = getRecipeStatus(); frame.workingStatus = getWorkingStatus();
        frame.chances.clear(); frame.chances.putAll(chanceCaches);
    }

    private void apply(Frame frame) {
        lastRecipe = frame.recipe; lastOriginRecipe = frame.origin;
        progress = frame.progress; duration = frame.duration;
        fuelTime = frame.fuel; fuelMaxTime = frame.maxFuel;
        totalContinuousRunningTime = frame.continuous;
        recipeDirty = frame.dirty; lastFailedMatches = null;
        pendingSetup = frame.pendingSetup;
        access().shanhai$threadStatus(frame.status);
        access().shanhai$threadActive(frame.active);
        access().shanhai$threadWaitingReason(frame.reason);
        setRecipeStatus(frame.recipeStatus); setWorkingStatus(frame.workingStatus);
        chanceCaches.clear(); chanceCaches.putAll(frame.chances);
    }

    private static final class Frame {
        GTRecipe recipe, origin;
        int batchCount = 1;
        Map<Identity, Long> sourceReservations;
        int progress, duration, fuel, maxFuel;
        long continuous;
        Status status = Status.IDLE;
        boolean active, dirty, pendingSetup;
        Component reason;
        RecipeResult recipeStatus, workingStatus;
        final Map<RecipeCapability<?>, Object2IntMap<?>> chances = new HashMap<>();

        CompoundTag save(GTRecipePayload payload) {
            CompoundTag data = new CompoundTag();
            payload.setPayload(recipe);
            data.put("recipe", java.util.Objects.requireNonNull(payload.serializeNBT()));
            if (recipe.data.contains(PrimordialThreadRecipeIdentity.KEY)) {
                data.put(PrimordialThreadRecipeIdentity.KEY, recipe.data.getList(PrimordialThreadRecipeIdentity.KEY, 10).copy());
            }
            if (origin != null) {
                payload.setPayload(origin);
                data.put("origin", java.util.Objects.requireNonNull(payload.serializeNBT()));
            }
            data.putBoolean("dirty", dirty);
            data.putInt("mergedBatches", batchCount);
            if (sourceReservations != null && !sourceReservations.containsKey(null)) {
                ListTag sources = new ListTag();
                sourceReservations.forEach((identity, parallel) -> {
                    CompoundTag source = identity.save();
                    source.putLong("reserved", parallel);
                    sources.add(source);
                });
                data.put("sourceReservations", sources);
            }
            data.putBoolean("pendingSetup", pendingSetup);
            data.putBoolean("wireless", recipe instanceof IWirelessGTRecipe);
            if (recipe instanceof IWirelessGTRecipe wireless && wireless.getWirelessEuTickInputs() != null) {
                data.putString("wirelessEuTick", wireless.getWirelessEuTickInputs().toString());
            }
            data.putInt("progress", progress); data.putInt("duration", duration);
            data.putInt("fuel", fuel); data.putInt("maxFuel", maxFuel);
            data.putLong("continuous", continuous);
            data.putString("status", status.name());
            if (reason != null) data.putString("reason", Component.Serializer.toJson(reason));
            return data;
        }

        static Frame load(CompoundTag data, GTRecipePayload payload) {
            Frame frame = new Frame();
            frame.batchCount = Math.clamp(data.getInt("mergedBatches"), 1, 4);
            if (data.contains("sourceReservations", Tag.TAG_LIST)) {
                frame.sourceReservations = new HashMap<>();
                for (Tag element : data.getList("sourceReservations", Tag.TAG_COMPOUND)) {
                    CompoundTag source = (CompoundTag) element;
                    frame.sourceReservations.put(new Identity(source.getString("recipeType"), source.getString("recipeId")),
                            Math.max(0L, source.getLong("reserved")));
                }
            }
            payload.deserializeNBT(data.get("recipe"));
            frame.recipe = java.util.Objects.requireNonNull(payload.getPayload(), "Missing paid recipe thread snapshot");
            if (data.contains(PrimordialThreadRecipeIdentity.KEY)) {
                frame.recipe.data = frame.recipe.data.copy();
                frame.recipe.data.put(PrimordialThreadRecipeIdentity.KEY, data.getList(PrimordialThreadRecipeIdentity.KEY, 10).copy());
            }
            if (data.getBoolean("wireless") && !(frame.recipe instanceof IWirelessGTRecipe)) {
                GTRecipe original = frame.recipe;
                frame.recipe = new WirelessGTRecipe(original, data.contains("wirelessEuTick")
                        ? new BigInteger(data.getString("wirelessEuTick")) : BigInteger.ZERO);
                RecipeExtensionCopier.copy(original, frame.recipe);
            }
            if (data.contains("origin")) {
                payload.deserializeNBT(data.get("origin"));
                frame.origin = payload.getPayload();
            }
            frame.dirty = data.getBoolean("dirty");
            frame.pendingSetup = data.getBoolean("pendingSetup");
            frame.duration = Math.max(1, data.getInt("duration"));
            frame.progress = Math.max(0, Math.min(frame.duration, data.getInt("progress")));
            frame.fuel = data.getInt("fuel"); frame.maxFuel = data.getInt("maxFuel");
            frame.continuous = data.getLong("continuous");
            frame.status = Status.valueOf(data.getString("status")); frame.active = true;
            if (data.contains("reason")) frame.reason = Component.Serializer.fromJson(data.getString("reason"));
            return frame;
        }
    }
}
