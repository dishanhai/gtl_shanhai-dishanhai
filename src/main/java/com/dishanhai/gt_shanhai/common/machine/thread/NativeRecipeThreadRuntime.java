package com.dishanhai.gt_shanhai.common.machine.thread;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.IRecipeThreadDisplay;
import com.dishanhai.gt_shanhai.api.machine.part.IRecipeThreadSource;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadRecipeIdentity;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadScheduler;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadedRecipeLogic;
import com.dishanhai.gt_shanhai.mixin.PrimordialRecipeThreadStateAccessor;
import com.dishanhai.gt_shanhai.mixin.RecipeThreadStateAccessor;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IOverclockMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.syncdata.GTRecipePayload;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleRecipesLogic;
import com.gtladd.gtladditions.api.machine.logic.MutableRecipesLogic;
import com.gtladd.gtladditions.api.machine.IThreadModifierMachine;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ConversationMachine;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.common.machine.trait.MultipleRecipesLogic;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Cooperatively multiplexes the original logic instance. Every recipe search, modifier,
 * input transaction, working hook and finish callback is dispatched virtually to that instance.
 * The context is scoped to a server-thread call; no inventory work runs on a worker thread.
 */
public class NativeRecipeThreadRuntime implements IRecipeThreadDisplay {
    private static final String SAVE_KEY = "sh_native_recipe_threads";
    private static final ThreadLocal<NativeRecipeThreadRuntime> CONTEXT = new ThreadLocal<>();
    protected final RecipeLogic logic;
    private PrimordialThreadScheduler<Frame> scheduler = new PrimordialThreadScheduler<>();
    private boolean initialized, ticking, admitting, finishing, drain, invalidating, replayingPaid;
    private long allowance, admittedParallel, currentShare, lastTick = Long.MIN_VALUE, continuous;
    private BigInteger tickInput = BigInteger.ZERO, tickOutput = BigInteger.ZERO;
    private int sharedFuel, sharedMaxFuel;
    private boolean workedThisTick, workingResult, waitedThisTick;
    private GTRecipe paidRecipe;
    private final LinkedHashSet<PrimordialThreadRecipeIdentity.Identity> sources = new LinkedHashSet<>();
    private final LinkedHashSet<RecipeCapability<?>> chanceCapabilities = new LinkedHashSet<>();

    public NativeRecipeThreadRuntime(RecipeLogic logic) { this.logic = logic; }

    public static NativeRecipeThreadRuntime of(RecipeLogic logic) {
        return logic instanceof PrimordialThreadedRecipeLogic ? null
                : logic instanceof NativeRecipeThreadHost host ? host.shanhai$recipeThreads() : null;
    }

    /** Only a hatch (or already persisted paid batches) activates native multiplexing. */
    protected boolean hasSource() {
        // These native loops mirror module status / run a manual generator cycle and never own a GTRecipe batch.
        String type = logic.getClass().getName();
        if (type.equals("com.gtladd.gtladditions.common.machine.multiblock.controller.PlanetaryIonisationConvergenceTower$Companion$PICTRecipeLogic")
                || type.equals("com.gtladd.gtladditions.common.machine.multiblock.controller.bs.BiosphereIIIController$BSRecipeLogic")) return false;
        if (!(logic.machine instanceof IMultiController controller) || !controller.isFormed()) return false;
        return controller.getParts().stream().anyMatch(IRecipeThreadSource.class::isInstance);
    }

    public boolean isManaged() { return initialized || hasSource(); }
    public boolean isTicking() { return ticking; }
    public boolean isFinishing() { return finishing; }
    public boolean isAdmitting() { return admitting; }
    public long allowance(long nativeLimit) {
        return admitting ? Math.min(nativeLimit, Math.max(0L, allowance - admittedParallel)) : nativeLimit;
    }
    public static long limitParallel(long nativeLimit) {
        var runtime = CONTEXT.get();
        return runtime == null ? nativeLimit : runtime.allowance(nativeLimit);
    }

    protected long parallelCapacity() {
        if (logic.machine instanceof ConversationMachine conversation) return Math.max(0L, conversation.getParallel());
        if (logic instanceof GTLAddMultipleRecipesLogic multiple) {
            return RecipeThreadBudget.multiply(multiple.getParallel().getMaxParallel(), multiple.getMultipleThreads());
        }
        if (logic instanceof MultipleRecipesLogic multiple) {
            long threads = logic.machine instanceof IThreadModifierMachine machine
                    ? Math.min(Integer.MAX_VALUE, 64L + machine.getAdditionalThread()) : 64L;
            return RecipeThreadBudget.multiply(multiple.getParallel().getMaxParallel(), threads);
        }
        if (logic instanceof MutableRecipesLogic<?> mutable && mutable.isMultipleRecipeMode()
                && logic.machine instanceof ParallelMachine parallel) {
            return RecipeThreadBudget.multiply(parallel.getMaxParallel(), mutable.getMultipleThreads());
        }
        if (logic.machine instanceof ParallelMachine parallel) return Math.max(1L, parallel.getMaxParallel());
        long total = 1L;
        if (logic.machine instanceof IMultiController controller) {
            for (var part : controller.getParts()) {
                if (part instanceof IParallelHatch parallel) total = Math.max(total, parallel.getCurrentParallel());
            }
        }
        return total;
    }

    protected long capacity() { return Math.min(parallelCapacity(), RecipeThreadSources.capacity(logic.machine, 1L)); }
    protected long timer() { return logic.getMachine().getOffsetTimer(); }
    protected boolean available() {
        return logic.machine.isRecipeLogicAvailable()
                && (!(logic.machine instanceof IMultiController controller) || controller.isFormed());
    }
    private RecipeThreadStateAccessor state() { return (RecipeThreadStateAccessor) logic; }
    private PrimordialRecipeThreadStateAccessor status() { return (PrimordialRecipeThreadStateAccessor) logic; }

    private void initialize() {
        if (initialized) return;
        initialized = true;
        chanceCapabilities.addAll(logic.getChanceCaches().keySet());
        scheduler.setEnabled(!logic.isSuspend());
        sharedFuel = logic.getFuelTime(); sharedMaxFuel = logic.getFuelMaxTime();
        continuous = logic.getTotalContinuousRunningTime();
        if (logic.getLastRecipe() != null && logic.getDuration() > 0) {
            currentShare = Math.max(1L, parallelCapacity());
            scheduler.restore(0L, Math.max(1L, parallelCapacity()), capture(false));
        }
    }

    /** Returns true when the native tick has been handled, including a paused machine. */
    public boolean tick() {
        if (!isManaged() || ticking) return false;
        initialize();
        if (lastTick == timer()) return true;
        lastTick = timer();
        if (!scheduler.isEnabled() || logic.isSuspend() || !available()) return true;
        RecipeLogic.Status previous = logic.getStatus();
        drain = nativeDrain();
        workedThisTick = waitedThisTick = false;
        tickInput = tickOutput = BigInteger.ZERO;
        ticking = true;
        var previousContext = CONTEXT.get();
        CONTEXT.set(this);
        try {
            scheduler.tick(timer(), parallelCapacity(), capacity(), new PrimordialThreadScheduler.Runner<>() {
                @Override public boolean advance(Frame frame) {
                    apply(frame);
                    if (frame.pending) {
                        replayingPaid = true;
                        try { logic.setupRecipe(frame.recipe); }
                        finally { replayingPaid = false; }
                        if (logic.getDuration() <= 0 || logic.isIdle()) {
                            apply(frame);
                            logic.setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_setup"));
                            frame.reason = status().shanhai$threadWaitingReason();
                            frame.status = RecipeLogic.Status.WAITING;
                            return false;
                        }
                        frame.pending = false;
                    }
                    int before = logic.getProgress();
                    if (logic.getProgress() < logic.getDuration()) {
                        if (claimTickPower(frame.recipe)) logic.handleRecipeWorking();
                        else logic.setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_machine"));
                    }
                    if (logic.getProgress() > before) continuous++;
                    if (logic.getProgress() >= logic.getDuration()) {
                        if (!canFinish(frame.recipe)) {
                            logic.setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_output"));
                        } else {
                            finishing = true;
                            // Native GTL finish checks this before attempting a paid builder lookup.
                            setNativeDrain(true);
                            try { logic.onRecipeFinish(); }
                            finally { finishing = false; setNativeDrain(drain); }
                            clearCurrent();
                        }
                    }
                    captureInto(frame);
                    return frame.recipe == null;
                }

                @Override public Frame start(long share) {
                    if (drain) return null;
                    apply(emptyFrame());
                    sources.clear(); paidRecipe = null;
                    currentShare = allowance = share; admittedParallel = 0L; admitting = true;
                    try {
                        logic.findAndHandleRecipe();
                        if (logic.getLastRecipe() != null && logic.getDuration() > 0) return capture(false);
                        if (paidRecipe == null) return null;
                        // GTL builders have already paid before findAndHandleRecipe checks output.
                        state().shanhai$recipe(paidRecipe);
                        state().shanhai$progress(0);
                        state().shanhai$duration(Math.max(1, paidRecipe.duration));
                        logic.setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_setup"));
                        return capture(true);
                    } finally {
                        admitting = false; allowance = admittedParallel = 0L; paidRecipe = null; sources.clear();
                    }
                }
            });
            if (sharedFuel > 0) sharedFuel--;
        } finally {
            CONTEXT.set(previousContext);
            ticking = admitting = finishing = false;
            project(previous);
        }
        return true;
    }

    protected boolean canFinish(GTRecipe recipe) { return RecipeRunnerHelper.matchRecipeOutput(logic.machine, recipe); }

    /** Native codec boundary, kept separate from batch ordering and progress persistence. */
    protected GTRecipePayload recipePayload() { return new GTRecipePayload(); }

    private static long inputEUt(GTRecipe recipe) {
        return recipe.tickInputs.isEmpty() ? 0L : RecipeHelper.getInputEUt(recipe);
    }

    private static long outputEUt(GTRecipe recipe) {
        return recipe.tickOutputs.isEmpty() ? 0L : RecipeHelper.getOutputEUt(recipe);
    }

    public void recordPaidRecipe(GTRecipe recipe) {
        if (!admitting || recipe == null) return;
        PrimordialThreadRecipeIdentity.store(recipe, sources);
        paidRecipe = recipe;
    }

    public static void recordInput(IRecipeLogicMachine machine, GTRecipe recipe) {
        var runtime = CONTEXT.get();
        if (runtime == null || !runtime.admitting || runtime.logic.machine != machine || runtime.skipPaidInput()) return;
        long count = recipeParallel(recipe);
        runtime.admittedParallel = Math.min(runtime.allowance,
                runtime.admittedParallel + Math.min(count, runtime.allowance - runtime.admittedParallel));
        var identity = PrimordialThreadRecipeIdentity.fromRecipe(recipe);
        if (identity != null) runtime.sources.add(identity);
    }

    private static long recipeParallel(GTRecipe recipe) {
        long real = recipe instanceof IGTRecipe extension ? extension.getRealParallels() : 1L;
        return Math.max(Math.max(1L, real), recipe.parallels);
    }

    public boolean skipPaidInput() { return replayingPaid || admitting && paidRecipe != null; }

    public static Boolean inputDecision(IRecipeLogicMachine machine, GTRecipe recipe) {
        var runtime = CONTEXT.get();
        if (runtime == null || runtime.logic.machine != machine) return null;
        if (runtime.skipPaidInput()) return Boolean.TRUE;
        if (runtime.finishing) return Boolean.FALSE;
        if (runtime.admitting && recipeParallel(recipe) > runtime.allowance(Long.MAX_VALUE)) return Boolean.FALSE;
        return null;
    }

    /** Delayed native transactions (the conversion machine) still use their original settlement callback. */
    public static long transactionParallel(Object machine, long nativeParallel) {
        var runtime = CONTEXT.get();
        return runtime != null && runtime.logic.machine == machine && runtime.ticking
                ? Math.min(Math.max(0L, nativeParallel), runtime.currentShare) : nativeParallel;
    }

    /** Keep machine-level per-tick effects at one invocation even with many batches. */
    public boolean onWorking(IRecipeLogicMachine machine) {
        if (!ticking) return machine.onWorking();
        if (!workedThisTick) { workedThisTick = true; workingResult = machine.onWorking(); }
        return workingResult;
    }

    public void onWaiting(IRecipeLogicMachine machine) {
        if (!ticking || !waitedThisTick) { waitedThisTick = true; machine.onWaiting(); }
    }

    public boolean interceptStatus(RecipeLogic.Status next) {
        if (!ticking) return false;
        status().shanhai$threadStatus(next);
        if (next != RecipeLogic.Status.WAITING) status().shanhai$threadWaitingReason(null);
        return true;
    }

    public boolean interceptInterrupt() {
        if (!ticking) return false;
        logic.setWaiting(Component.translatable("gt_shanhai.primordial.threads.waiting_machine"));
        return true;
    }

    public boolean preserveInvalidation() {
        if (!isManaged() || invalidating || ticking) return false;
        initialize();
        var previous = logic.getStatus();
        invalidating = true;
        try {
            for (var lane : scheduler.lanes()) { apply(lane.task()); logic.inValid(); }
        } finally { invalidating = false; project(previous); }
        return true;
    }

    public void setEnabled(boolean enabled) {
        if (!isManaged() || ticking) return;
        initialize(); drain = false; setNativeDrain(false);
        scheduler.setEnabled(enabled); scheduler.requestSearch();
    }

    public void retry() { if (!ticking) scheduler.requestRetry(); }
    @Override public void onThreadInputsChanged() {
        scheduler.requestSearch();
        logic.updateTickSubscription();
    }
    @Override public int getActiveRecipeThreadCount() { return scheduler.lanes().size(); }
    @Override public boolean isRecipeThreadDisplayAvailable() { return hasSource() || !scheduler.lanes().isEmpty(); }

    private boolean nativeDrain() {
        return logic.machine instanceof ISuspendableMachine machine ? machine.gtlcore$isSuspendAfterFinish() : drain;
    }
    private void setNativeDrain(boolean value) {
        if (logic.machine instanceof ISuspendableMachine machine) machine.gtlcore$setSuspendAfterFinish(value);
    }

    /** Admission voltage only: active batches still tick through native energy handlers. */
    public long remainingVoltage(long nativeVoltage) { return remainingVoltage(nativeVoltage, true); }

    public long remainingVoltage(long nativeVoltage, boolean consumer) {
        if (!admitting) return nativeVoltage;
        BigInteger remaining = BigInteger.valueOf(Math.max(0L, nativeVoltage));
        for (var lane : scheduler.lanes()) {
            Frame frame = lane.task();
            if (frame.progress < frame.duration && !(frame.recipe instanceof IWirelessGTRecipe)) {
                remaining = remaining.subtract(BigInteger.valueOf(consumer ? inputEUt(frame.recipe)
                        : outputEUt(frame.recipe)).max(BigInteger.ZERO));
            }
        }
        return remaining.max(BigInteger.ZERO).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    public static long admissionVoltage(Object machine, long voltage) {
        return admissionVoltage(machine, voltage, true);
    }

    public static long admissionVoltage(Object machine, long voltage, boolean consumer) {
        var runtime = CONTEXT.get();
        return runtime != null && runtime.logic.machine == machine ? runtime.remainingVoltage(voltage, consumer) : voltage;
    }

    /** The wireless API returns stored EU, so reserve remaining batch energy, not EU/t. */
    public static BigInteger admissionWirelessBudget(BigInteger nativeBudget, boolean consumer) {
        var runtime = CONTEXT.get();
        if (runtime == null || !runtime.admitting) return nativeBudget;
        var reservations = runtime.scheduler.lanes().stream().map(PrimordialThreadScheduler.Lane::task)
                .filter(frame -> frame.progress < frame.duration)
                .filter(frame -> frame.recipe instanceof IWirelessGTRecipe)
                .map(frame -> {
                    BigInteger eut = ((IWirelessGTRecipe) frame.recipe).getWirelessEuTickInputs();
                    return eut == null ? BigInteger.ZERO
                            : eut.multiply(BigInteger.valueOf(Math.max(0L, (long) frame.duration - frame.progress)));
                }).toList();
        return RecipeThreadBudget.remaining(nativeBudget, reservations, consumer);
    }

    public boolean canStart(GTRecipe recipe) {
        if (!admitting || recipe instanceof IWirelessGTRecipe || paidRecipe != null) return true;
        if (logic.machine instanceof IOverclockMachine machine) {
            long voltage = machine.getOverclockVoltage();
            return inputEUt(recipe) <= remainingVoltage(voltage, true)
                    && outputEUt(recipe) <= remainingVoltage(voltage, false);
        }
        return true;
    }

    /** A subclass can build a paid snapshot before our adapter sees it; still never exceed physical tick IO. */
    private boolean claimTickPower(GTRecipe recipe) {
        if (recipe instanceof IWirelessGTRecipe || !(logic.machine instanceof IOverclockMachine machine)) return true;
        BigInteger capacity = BigInteger.valueOf(Math.max(0L, machine.getOverclockVoltage()));
        BigInteger input = BigInteger.valueOf(Math.max(0L, inputEUt(recipe)));
        BigInteger output = BigInteger.valueOf(Math.max(0L, outputEUt(recipe)));
        if (tickInput.add(input).compareTo(capacity) > 0 || tickOutput.add(output).compareTo(capacity) > 0) return false;
        tickInput = tickInput.add(input); tickOutput = tickOutput.add(output);
        return true;
    }

    private void clearCurrent() {
        state().shanhai$recipe(null); state().shanhai$origin(null);
        state().shanhai$progress(0); state().shanhai$duration(0);
        status().shanhai$threadActive(false); status().shanhai$threadStatus(RecipeLogic.Status.IDLE);
    }

    private void project(RecipeLogic.Status previous) {
        Frame display = scheduler.lanes().stream().map(PrimordialThreadScheduler.Lane::task)
                .filter(frame -> frame.status == RecipeLogic.Status.WORKING).findFirst()
                .orElse(scheduler.lanes().isEmpty() ? emptyFrame() : scheduler.lanes().getFirst().task());
        apply(display);
        state().shanhai$fuel(sharedFuel); state().shanhai$maxFuel(sharedMaxFuel);
        state().shanhai$continuous(continuous);
        RecipeLogic.Status aggregate = scheduler.isEnabled() ? display.status : RecipeLogic.Status.SUSPEND;
        if (drain && scheduler.lanes().isEmpty()) {
            aggregate = RecipeLogic.Status.SUSPEND;
            scheduler.setEnabled(false); drain = false; setNativeDrain(false);
        }
        status().shanhai$threadStatus(previous);
        logic.setStatus(aggregate);
    }

    private Frame capture(boolean pending) {
        Frame frame = new Frame(); captureInto(frame); frame.pending = pending; return frame;
    }

    private Frame emptyFrame() {
        Frame frame = new Frame();
        for (RecipeCapability<?> cap : chanceCapabilities) frame.chances.put(cap, cap.makeChanceCache());
        return frame;
    }
    private void captureInto(Frame frame) {
        frame.recipe = logic.getLastRecipe(); frame.origin = logic.getLastOriginRecipe();
        frame.progress = logic.getProgress(); frame.duration = logic.getDuration();
        frame.status = logic.getStatus(); frame.reason = status().shanhai$threadWaitingReason();
        frame.active = status().shanhai$threadActive(); frame.dirty = logic.isRecipeDirty();
        frame.reservation = currentShare;
        frame.ocParams = state().shanhai$ocParams(); frame.ocResult = state().shanhai$ocResult();
        sharedFuel = logic.getFuelTime(); sharedMaxFuel = logic.getFuelMaxTime();
        frame.chances.clear();
        logic.getChanceCaches().forEach((cap, cache) -> frame.chances.put(cap, cloneCache(cap, cache)));
        if (logic instanceof IRecipeStatus recipeStatus) {
            frame.recipeStatus = recipeStatus.getRecipeStatus(); frame.workingStatus = recipeStatus.getWorkingStatus();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object2IntMap<?> cloneCache(RecipeCapability cap, Object2IntMap cache) {
        Object2IntMap result = cap.makeChanceCache(); result.putAll(cache); return result;
    }

    private void apply(Frame frame) {
        currentShare = frame.reservation;
        state().shanhai$recipe(frame.recipe); state().shanhai$origin(frame.origin);
        state().shanhai$progress(frame.progress); state().shanhai$duration(frame.duration);
        state().shanhai$fuel(sharedFuel); state().shanhai$maxFuel(sharedMaxFuel);
        state().shanhai$dirty(frame.dirty); state().shanhai$continuous(continuous);
        state().shanhai$ocParams(frame.ocParams); state().shanhai$ocResult(frame.ocResult);
        status().shanhai$threadStatus(frame.status); status().shanhai$threadActive(frame.active);
        status().shanhai$threadWaitingReason(frame.reason);
        logic.lastFailedMatches = null;
        logic.getChanceCaches().clear();
        frame.chances.forEach((cap, cache) -> logic.getChanceCaches().put(cap, cloneCache(cap, cache)));
        if (logic instanceof IRecipeStatus recipeStatus) {
            recipeStatus.setRecipeStatus(frame.recipeStatus); recipeStatus.setWorkingStatus(frame.workingStatus);
        }
    }

    @Override public CompoundTag getRecipeThreadDisplay(int first, int maximum) {
        CompoundTag data = new CompoundTag();
        var lanes = scheduler.lanes();
        boolean enabled = scheduler.isEnabled() && !logic.isSuspend();
        boolean available = enabled && available();
        int offset = Math.max(0, Math.min(first, Math.max(0, lanes.size() - 1)));
        int limit = Math.max(0, Math.min(8, maximum));
        data.putBoolean("enabled", enabled); data.putInt("active", lanes.size());
        data.putLong("capacity", capacity()); data.putLong("parallelCapacity", parallelCapacity());
        data.putLong("reserved", scheduler.reserved()); data.putBoolean("draining", nativeDrain());
        data.putInt("offset", offset);
        int running = 0, waiting = 0;
        BigInteger consumption = BigInteger.ZERO, production = BigInteger.ZERO;
        ListTag rows = new ListTag();
        for (int i = 0; i < lanes.size(); i++) {
            var lane = lanes.get(i); Frame frame = lane.task();
            boolean working = available && !frame.pending && frame.status == RecipeLogic.Status.WORKING
                    && frame.progress < frame.duration;
            if (working) running++;
            if (enabled && (!available || frame.pending || frame.status == RecipeLogic.Status.WAITING)) waiting++;
            BigInteger input = BigInteger.ZERO, output = BigInteger.ZERO;
            if (working && frame.recipe instanceof IWirelessGTRecipe wireless) {
                BigInteger value = wireless.getWirelessEuTickInputs();
                if (value != null && value.signum() < 0) input = value.abs();
                else if (value != null) output = value;
            } else if (working && frame.recipe != null) {
                input = BigInteger.valueOf(inputEUt(frame.recipe)).max(BigInteger.ZERO);
                output = BigInteger.valueOf(outputEUt(frame.recipe)).max(BigInteger.ZERO);
            }
            consumption = consumption.add(input); production = production.add(output);
            if (i < offset || i - offset >= limit) continue;
            CompoundTag row = new CompoundTag();
            row.putLong("index", lane.index()); row.putLong("reserved", lane.reserved());
            row.putInt("progress", frame.progress); row.putInt("duration", frame.duration);
            row.putInt("remainingTicks", Math.max(0, frame.duration - frame.progress));
            row.putString("status", available ? frame.status.name() : enabled ? "WAITING" : "SUSPEND");
            row.putString("consumption", PrimordialThreadedRecipeLogic.formatThreadEnergy(input));
            row.putString("production", PrimordialThreadedRecipeLogic.formatThreadEnergy(output));
            PrimordialThreadRecipeIdentity.appendDisplay(row, frame.recipe);
            PrimordialThreadedRecipeLogic.appendThreadOutputs(row, frame.recipe);
            Component reason = enabled && !available
                    ? Component.translatable("gt_shanhai.primordial.threads.waiting_machine") : enabled ? frame.reason : null;
            if (reason != null) row.putString("reason", Component.Serializer.toJson(reason));
            rows.add(row);
        }
        data.putInt("running", running); data.putInt("waiting", waiting); data.put("threads", rows);
        data.putString("consumption", PrimordialThreadedRecipeLogic.formatThreadEnergy(consumption));
        data.putString("production", PrimordialThreadedRecipeLogic.formatThreadEnergy(production));
        return data;
    }

    public void save(CompoundTag tag) {
        if (!isManaged()) return;
        initialize();
        ListTag list = new ListTag();
        for (var lane : scheduler.lanes()) {
            CompoundTag data = lane.task().save(recipePayload());
            data.putLong("index", lane.index()); data.putLong("reserved", lane.reserved()); list.add(data);
        }
        tag.put(SAVE_KEY, list); tag.putBoolean("sh_native_threads_enabled", scheduler.isEnabled());
        tag.putBoolean("sh_native_threads_drain", nativeDrain()); tag.putLong("sh_native_threads_continuous", continuous);
        tag.putInt("sh_native_threads_fuel", sharedFuel); tag.putInt("sh_native_threads_max_fuel", sharedMaxFuel);
    }

    public void load(CompoundTag tag) {
        if (!tag.contains(SAVE_KEY, Tag.TAG_LIST)) return;
        var restored = new PrimordialThreadScheduler<Frame>();
        for (Tag element : tag.getList(SAVE_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag data = (CompoundTag) element;
            restored.restore(data.getLong("index"), data.getLong("reserved"), Frame.load(data, recipePayload()));
        }
        scheduler = restored; initialized = true; lastTick = Long.MIN_VALUE;
        chanceCapabilities.addAll(logic.getChanceCaches().keySet());
        scheduler.setEnabled(tag.getBoolean("sh_native_threads_enabled"));
        drain = tag.getBoolean("sh_native_threads_drain"); setNativeDrain(drain);
        continuous = tag.getLong("sh_native_threads_continuous");
        sharedFuel = tag.getInt("sh_native_threads_fuel"); sharedMaxFuel = tag.getInt("sh_native_threads_max_fuel");
        project(logic.getStatus());
    }

    static final class Frame {
        GTRecipe recipe, origin;
        int progress, duration;
        long reservation;
        boolean active, dirty, pending;
        RecipeLogic.Status status = RecipeLogic.Status.IDLE;
        Component reason;
        OCParams ocParams = new OCParams(); OCResult ocResult = new OCResult();
        RecipeResult recipeStatus, workingStatus;
        final Map<RecipeCapability<?>, Object2IntMap<?>> chances = new HashMap<>();

        CompoundTag save(GTRecipePayload payload) {
            CompoundTag data = new CompoundTag();
            payload.setPayload(recipe);
            data.put("recipe", java.util.Objects.requireNonNull(payload.serializeNBT()));
            if (origin != null) { payload.setPayload(origin); data.put("origin", java.util.Objects.requireNonNull(payload.serializeNBT())); }
            if (recipe instanceof IWirelessGTRecipe wireless && wireless.getWirelessEuTickInputs() != null) {
                data.putString("wirelessEut", wireless.getWirelessEuTickInputs().toString());
            }
            data.put("sources", recipe.data.getList(PrimordialThreadRecipeIdentity.KEY, 10).copy());
            data.putInt("progress", progress); data.putInt("duration", duration);
            data.putBoolean("pending", pending); data.putBoolean("dirty", dirty); data.putBoolean("active", active);
            data.putString("status", status.name());
            if (reason != null) data.putString("reason", Component.Serializer.toJson(reason));
            CompoundTag chance = new CompoundTag();
            chances.forEach((cap, cache) -> saveChances(chance, cap, cache));
            data.put("chances", chance);
            return data;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static void saveChances(CompoundTag result, RecipeCapability cap, Object2IntMap cache) {
            ListTag list = new ListTag();
            for (Object entryObject : cache.object2IntEntrySet()) {
                Object2IntMap.Entry entry = (Object2IntMap.Entry) entryObject;
                CompoundTag data = new CompoundTag();
                data.put("entry", cap.serializer.toNbtGeneric(cap.of(entry.getKey())));
                data.putInt("value", entry.getIntValue()); list.add(data);
            }
            result.put(cap.name, list);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static Frame load(CompoundTag data, GTRecipePayload payload) {
            Frame frame = new Frame();
            payload.deserializeNBT(data.get("recipe"));
            frame.recipe = java.util.Objects.requireNonNull(payload.getPayload(), "Missing paid native recipe thread");
            if (data.contains("wirelessEut")) {
                GTRecipe original = frame.recipe;
                frame.recipe = new WirelessGTRecipe(original, new BigInteger(data.getString("wirelessEut")));
                RecipeExtensionCopier.copy(original, frame.recipe);
            }
            frame.recipe.data = frame.recipe.data.copy();
            frame.recipe.data.put(PrimordialThreadRecipeIdentity.KEY, data.getList("sources", 10).copy());
            if (data.contains("origin")) { payload.deserializeNBT(data.get("origin")); frame.origin = payload.getPayload(); }
            frame.duration = Math.max(1, data.getInt("duration"));
            frame.reservation = Math.max(0L, data.getLong("reserved"));
            frame.progress = Math.max(0, Math.min(frame.duration, data.getInt("progress")));
            frame.pending = data.getBoolean("pending"); frame.dirty = data.getBoolean("dirty");
            frame.active = data.getBoolean("active"); frame.status = RecipeLogic.Status.valueOf(data.getString("status"));
            if (data.contains("reason")) frame.reason = Component.Serializer.fromJson(data.getString("reason"));
            CompoundTag chances = data.getCompound("chances");
            for (String key : chances.getAllKeys()) {
                RecipeCapability cap = com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_CAPABILITIES.get(key);
                if (cap == null) continue;
                Object2IntMap cache = cap.makeChanceCache();
                for (Tag value : chances.getList(key, Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) value;
                    cache.put(cap.serializer.fromNbt(entry.get("entry")), entry.getInt("value"));
                }
                frame.chances.put(cap, cache);
            }
            return frame;
        }
    }
}
