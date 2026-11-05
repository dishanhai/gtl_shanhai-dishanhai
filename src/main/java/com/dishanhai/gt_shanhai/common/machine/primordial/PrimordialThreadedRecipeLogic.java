package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.mixin.PrimordialRecipeThreadStateAccessor;
import com.dishanhai.gt_shanhai.api.machine.RecipeThreadSnapshot;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadPriority;
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
    private boolean cloudAdmissionBlocked;
    private List<GTRecipe> cloudReservedRecipes = List.of();
    private List<PrimordialThreadScheduler.Lane<Frame>> cloudDisplayLanes = List.of();
    private boolean cloudThreadsFull;
    private LinkedHashSet<PrimordialThreadRecipeIdentity.Identity> matchingRecipeSources;
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
        return priority == null ? lookup.get() : new LinkedHashSet<>(priority.select(lookup));
    }

    protected long estimateThreadRecipeTicks(GTRecipe recipe) {
        return Math.max(1, getLimited().getLimitedDuration());
    }

    protected abstract long getUnreservedTotalParallelLimit();

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
        for (GTRecipe recipe : cloudReservedRecipes) {
            if (!(recipe instanceof IWirelessGTRecipe wireless)) continue;
            BigInteger eut = wireless.getWirelessEuTickInputs();
            if (eut != null && (consumer ? eut.signum() < 0 : eut.signum() > 0)) used = used.add(eut.abs());
        }
        // 按已啟動批次的真實 EU/t 預留功率，避免「無限執行緒」將整數功率除為零。
        return fullPower.subtract(used).max(BigInteger.ZERO);
    }

    protected final boolean isThreadRecipeLookupAllowed() { return !tickingThreads || admission; }
    public final int getActiveRecipeThreadCount() { return activeRecipeThreads; }
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
                    && frame.progress < frame.duration && frame.recipe != null) running++;
        }
        return new RecipeThreadSnapshot(getRecipeThreadCapacity(), lanes.size(), running,
                enabled, nativeDrainRequested(), getUnreservedTotalParallelLimit(), displayReserved(),
                continuousTicks, completedThreadBatches,
                !cloudDisplayLanes.isEmpty() ? cloudThreadsFull
                        : threads.admissionShare(getUnreservedTotalParallelLimit(), getRecipeThreadCapacity()) <= 0L);
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
        data.putInt("active", lanes.size());
        data.putLong("capacity", getRecipeThreadCapacity());
        data.putLong("reserved", displayReserved());
        data.putLong("parallelCapacity", getUnreservedTotalParallelLimit());
        data.putBoolean("draining", nativeDrainRequested());
        data.putInt("offset", offset);
        BigInteger consumption = BigInteger.ZERO, production = BigInteger.ZERO;
        int waiting = 0;
        int running = 0;
        for (var lane : lanes) {
            Frame frame = lane.task();
            if (enabled && (!available || frame.status == Status.WAITING || frame.pendingSetup)) waiting++;
            if (available && frame.status == Status.WORKING && !frame.pendingSetup
                    && frame.progress < frame.duration && frame.recipe != null) running++;
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
            row.putLong("reserved", lane.reserved());
            row.putInt("progress", frame.progress);
            row.putInt("duration", frame.duration);
            row.putInt("remainingTicks", Math.max(0, frame.duration - frame.progress));
            row.putString("status", available ? frame.status.name() : enabled ? "WAITING" : "SUSPEND");
            if (frame.recipe != null) {
                PrimordialThreadRecipeIdentity.appendDisplay(row, frame.recipe);
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
        ListTag outputs = new ListTag();
        int total = 0;
        for (var ingredient : RecipeHelper.getOutputContents(recipe, ItemRecipeCapability.CAP)) {
            var stacks = ingredient.getItems();
            if (stacks.length == 0 || stacks[0].isEmpty()) continue;
            total++;
            if (outputs.size() >= 4) continue;
            CompoundTag output = new CompoundTag();
            output.put("item", stacks[0].copyWithCount(1).save(new CompoundTag()));
            output.putLong("amount", ingredient instanceof LongIngredient large
                    ? large.getActualAmount() : stacks[0].getCount());
            outputs.add(output);
        }
        for (var fluid : RecipeHelper.getOutputFluids(recipe)) {
            if (fluid.isEmpty()) continue;
            total++;
            if (outputs.size() >= 4) continue;
            CompoundTag output = new CompoundTag();
            output.put("fluid", fluid.saveToTag(new CompoundTag()));
            output.putLong("amount", fluid.getAmount());
            outputs.add(output);
        }
        row.put("outputs", outputs);
        row.putInt("moreOutputs", total - outputs.size());
    }

    public final long getRecipeThreadCapacity() {
        long configured = getMachine() instanceof PrimordialOmegaEngineModuleBase module
                ? module.getParallelRecipeThreadCount()
                : getMachine() instanceof PrimordialOmegaEngineMachine host
                ? host.getParallelRecipeThreadCount() : 1L;
        configured = com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadSources.capacity(machine, configured);
        return Math.min(Math.max(0L, getUnreservedTotalParallelLimit()), configured);
    }

    protected final List<GTRecipe> getRunningThreadRecipes() {
        return threads == null ? List.of()
                : displayLanes().stream().map(lane -> lane.task().recipe).toList();
    }

    private List<PrimordialThreadScheduler.Lane<Frame>> displayLanes() {
        return cloudDisplayLanes.isEmpty() ? threads.lanes() : cloudDisplayLanes;
    }

    private long displayReserved() {
        return cloudDisplayLanes.isEmpty() ? threads.reserved() : reservedThreadParallel;
    }

    public final void onThreadInputsChanged() {
        invalidateLookupSetCache();
        if (threads != null) threads.requestSearch();
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

    /** Optional adapters drain paid local lanes before taking exclusive ownership. */
    public final boolean prepareForCloudExecution(boolean requested) {
        importLegacyBatch();
        cloudAdmissionBlocked = requested;
        if (!requested) cloudDisplayLanes = List.of();
        return threads.lanes().isEmpty();
    }

    public final long getCloudParallelCapacity() { return getUnreservedTotalParallelLimit(); }
    public final boolean isCloudDrainRequested() { return nativeDrainRequested(); }
    public final boolean isCloudExecutionAvailable() {
        return threads.isEnabled() && !isSuspend()
                && (!(getMachine() instanceof IMultiController controller) || controller.isFormed())
                && (!(getMachine() instanceof PrimordialOmegaEngineModuleBase module) || module.canModuleWork());
    }

    public final boolean checkCloudCandidate(GTRecipe recipe) {
        if (!isCloudRecipeSelected(recipe)) return false;
        if (isLock() && getLockRecipe() != null
                && (!getLockRecipe().id.equals(recipe.id) || getLockRecipe().recipeType != recipe.recipeType)) return false;
        return checkRecipe(recipe);
    }

    public final boolean isCloudRecipeSelected(GTRecipe recipe) {
        return recipe != null && recipe.recipeType != null && getMachine().isRecipeTypeSelected(recipe.recipeType);
    }

    /** The snapshot is owned by the cloud order, never inserted into local persisted lanes. */
    public record CloudBatch(GTRecipe recipe, CompoundTag snapshot, int progress, int duration) {}

    public final CloudBatch prepareCloudBatch(long share, List<GTRecipe> reservedRecipes) {
        if (!threads.lanes().isEmpty() || share <= 0 || isSuspend() || nativeDrainRequested()) return null;
        Frame previous = capture();
        tickingThreads = admission = true;
        allowance = share;
        cloudReservedRecipes = reservedRecipes;
        try {
            apply(new Frame());
            findAndHandleRecipe();
            return lastRecipe == null || duration <= 0 ? null : cloudBatch(capture());
        } finally {
            admission = tickingThreads = false;
            allowance = 0;
            cloudReservedRecipes = List.of();
            apply(previous);
        }
    }

    public final CloudBatch advanceCloudBatch(CompoundTag snapshot) {
        return advanceCloudFrame(Frame.load(snapshot));
    }

    private CloudBatch advanceCloudFrame(Frame frame) {
        if (!threads.lanes().isEmpty()) throw new IllegalStateException("Cloud execution still has paid local lanes");
        Frame previous = capture();
        drainRequested = nativeDrainRequested();
        tickingThreads = true;
        try {
            if (advanceFrame(frame)) return null;
            return cloudBatch(frame);
        } finally {
            tickingThreads = false;
            apply(previous);
        }
    }

    private CloudBatch cloudBatch(Frame frame) {
        return new CloudBatch(frame.recipe, frame.save(), frame.progress, frame.duration);
    }

    public static CloudBatch restoreCloudBatch(CompoundTag snapshot) {
        Frame frame = Frame.load(snapshot);
        return new CloudBatch(frame.recipe, snapshot, frame.progress, frame.duration);
    }

    public final void projectCloudBatch(CloudBatch batch, int active, long reserved,
            List<CloudBatch> batches, List<Long> reservations, boolean full) {
        Status previousStatus = getStatus();
        var display = new java.util.ArrayList<PrimordialThreadScheduler.Lane<Frame>>(batches.size());
        for (int i = 0; i < batches.size(); i++) display.add(new PrimordialThreadScheduler.Lane<>(
                i, reservations.get(i), Frame.load(batches.get(i).snapshot)));
        cloudDisplayLanes = List.copyOf(display);
        cloudThreadsFull = full;
        if (batch == null) apply(new Frame()); else apply(Frame.load(batch.snapshot));
        activeRecipeThreads = active;
        reservedThreadParallel = reserved;
        totalContinuousRunningTime = continuousTicks;
        Status aggregate = getStatus();
        if (nativeDrainRequested() && active == 0) {
            setNativeDrain(false);
            threads.setEnabled(false);
            aggregate = Status.SUSPEND;
        }
        access().shanhai$threadStatus(previousStatus);
        super.setStatus(aggregate);
    }

    private boolean advanceFrame(Frame frame) {
        apply(frame);
        if (pendingSetup) {
            setupRecipe(lastRecipe);
            if (pendingSetup) { captureInto(frame); return false; }
        }
        int before = progress;
        if (progress < duration) handleRecipeWorking();
        if (progress > before) continuousTicks++;
        if (fuelTime > 0) fuelTime--;
        if (progress >= duration) {
            if (!canFinishThreadBatch(lastRecipe)) {
                setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_output"));
            } else {
                setNativeDrain(false);
                try { onRecipeFinish(); }
                finally { setNativeDrain(drainRequested); }
                completedThreadBatches++;
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

        Status previousStatus = getStatus();
        drainRequested = nativeDrainRequested();
        tickingThreads = true;
        try {
            threads.tick(getMachine().getOffsetTimer(), getUnreservedTotalParallelLimit(), getRecipeThreadCapacity(),
                    new PrimordialThreadScheduler.Runner<>() {
                        public boolean advance(Frame frame) {
                            return advanceFrame(frame);
                        }
                        public Frame start(long share) {
                            if (drainRequested || cloudAdmissionBlocked) return null;
                            apply(new Frame());
                            allowance = share;
                            admission = true;
                            priorityInputHandled = false;
                            priority = Math.min(getUnreservedTotalParallelLimit(), getRecipeThreadCapacity()) > 1L
                                    ? new RecipeThreadPriority<>(PrimordialThreadedRecipeLogic.this::estimateThreadRecipeTicks,
                                            recipe -> recipe.duration) : null;
                            try {
                                do {
                                    findAndHandleRecipe();
                                    if (lastRecipe != null || priorityInputHandled) break;
                                } while (priority != null && priority.retry());
                                return lastRecipe != null && duration > 0 ? capture() : null;
                            } finally {
                                priority = null;
                                priorityInputHandled = false;
                                admission = false;
                                allowance = 0L;
                            }
                        }
                    });
        } finally {
            admission = false;
            tickingThreads = false;
            project(previousStatus);
        }
    }

    private void project(Status previousStatus) {
        activeRecipeThreads = threads.lanes().size();
        reservedThreadParallel = threads.reserved();
        Frame display = threads.lanes().stream().map(PrimordialThreadScheduler.Lane::task)
                .filter(frame -> frame.status == Status.WORKING).findFirst()
                .orElse(threads.lanes().isEmpty() ? new Frame() : threads.lanes().getFirst().task());
        apply(display);
        totalContinuousRunningTime = continuousTicks;
        Status aggregate = !threads.isEnabled() || drainRequested && threads.lanes().isEmpty()
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
            CompoundTag data = lane.task().save();
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
            restored.restore(data.getLong("index"), data.getLong("reserved"), Frame.load(data));
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
        int progress, duration, fuel, maxFuel;
        long continuous;
        Status status = Status.IDLE;
        boolean active, dirty, pendingSetup;
        Component reason;
        RecipeResult recipeStatus, workingStatus;
        final Map<RecipeCapability<?>, Object2IntMap<?>> chances = new HashMap<>();

        CompoundTag save() {
            CompoundTag data = new CompoundTag();
            GTRecipePayload payload = new GTRecipePayload();
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

        static Frame load(CompoundTag data) {
            Frame frame = new Frame();
            GTRecipePayload payload = new GTRecipePayload();
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
