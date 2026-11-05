package com.dishanhai.gt_shanhai.common.machine.thread;

// Author: dishanhai
import com.dishanhai.gt_shanhai.mixin.PrimordialRecipeThreadStateAccessor;
import com.dishanhai.gt_shanhai.mixin.RecipeThreadStateAccessor;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.syncdata.GTRecipePayload;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real adapter ticks with only world, native transactions and Mixin field access isolated. */
class NativeRecipeThreadRuntimeTest {
    private static final Unsafe UNSAFE = unsafe();
    private static final long STATUS = offset("status"), ACTIVE = offset("isActive"), REASON = offset("waitingReason");

    @Test void longestCandidateStartsAloneAndShortRecipeFinishesOnAnotherLane() throws Exception {
        Probe p = probe(); p.runtime.threads = 2; p.usePriority = true;
        p.queue("fast", 2); p.queue("slow", 100);
        p.runtime.tickAt(0);
        assertEquals(1, p.inputs); assertEquals(1, p.pending.size());
        assertEquals("fast", p.pending.peek().id.getPath());
        p.runtime.tickAt(1); assertEquals(2, p.inputs);
        p.runtime.tickAt(2); p.runtime.tickAt(3);
        assertEquals(List.of("fast"), p.finished);
        assertEquals(1, p.runtime.getActiveRecipeThreadCount());
    }

    @Test void unstartableLongCandidateDoesNotBlockShortRecipeOrConsumeItsInputs() throws Exception {
        Probe p = probe(); p.runtime.threads = 2; p.usePriority = true; p.blockedRecipe = "slow";
        p.queue("fast", 2); p.queue("slow", 100);
        p.runtime.tickAt(0);
        assertEquals(1, p.inputs); assertEquals(1, p.pending.size());
        assertEquals("slow", p.pending.peek().id.getPath());
        p.runtime.tickAt(1); p.runtime.tickAt(2);
        assertEquals(List.of("fast"), p.finished);
    }

    @Test void singleLaneKeepsTheOriginalNativeCandidateOrder() throws Exception {
        Probe p = probe(); p.runtime.threads = 1; p.usePriority = true;
        p.queue("fast", 2); p.queue("slow", 100);
        p.runtime.tickAt(0);
        assertEquals("slow", p.pending.peek().id.getPath());
        assertEquals(1, p.inputs);
    }

    @Test void allUnstartableVirtualCandidatesFallBackToAnOrdinarySnapshot() throws Exception {
        Probe p = probe(); p.runtime.threads = 2;
        GTRecipe virtual = recipe("virtual", 100), blocked = recipe("blocked", 80), ready = recipe("ready", 2);
        int[] virtualAttempts = {0}, ordinaryAttempts = {0};
        p.lookupAction = () -> {
            if (p.runtime.tryVirtualCandidates(java.util.Set.of(virtual), candidate -> { virtualAttempts[0]++; return false; })) return;
            var chosen = p.runtime.priorityCandidates(new java.util.LinkedHashSet<>(List.of(ready, blocked)));
            if (chosen.isEmpty()) return;
            ordinaryAttempts[0]++;
            if (chosen.iterator().next() != ready) return;
            p.inputs++; p.runtime.recordPaidRecipe(ready); p.setupRecipe(ready);
        };
        p.runtime.tickAt(0);
        assertEquals(1, virtualAttempts[0]); assertEquals(2, ordinaryAttempts[0]);
        assertEquals(1, p.inputs); assertEquals(1, p.runtime.getActiveRecipeThreadCount());
        var outsideAdmission = java.util.Set.of(virtual, ready);
        assertSame(outsideAdmission, p.runtime.priorityCandidates(outsideAdmission));
    }

    @Test void aRunnableSecondVirtualCandidateStillPrecedesOrdinaryRecipes() throws Exception {
        Probe p = probe(); p.runtime.threads = 2;
        GTRecipe blocked = recipe("virtual_slow", 100), ready = recipe("virtual_ready", 20);
        List<String> attempts = new ArrayList<>();
        p.lookupAction = () -> {
            boolean handled = p.runtime.tryVirtualCandidates(new java.util.LinkedHashSet<>(List.of(ready, blocked)), candidate -> {
                attempts.add(candidate.id.getPath());
                if (candidate == blocked) return false;
                p.inputs++; p.runtime.recordPaidRecipe(candidate); p.setupRecipe(candidate); return true;
            });
            if (!handled) fail("普通搜尋不能越過可執行的第二個虛擬候選");
        };
        p.runtime.tickAt(0);
        assertEquals(List.of("virtual_slow", "virtual_ready"), attempts);
        assertEquals(1, p.inputs);
    }

    @Test void ordinarySearchSeesAllCandidatesBeforeNativeFirstMatchReduction() throws Exception {
        Probe p = probe(); p.runtime.threads = 2;
        GTRecipe fast = recipe("fast", 2), slow = recipe("slow", 100);
        int[] searches = {0};
        p.lookupAction = () -> {
            var iterator = p.runtime.prioritySearch(() -> { searches[0]++; return List.of(fast, slow).iterator(); });
            assertNotNull(iterator);
            assertSame(slow, iterator.next()); assertFalse(iterator.hasNext());
            p.inputs++; p.runtime.recordPaidRecipe(slow); p.setupRecipe(slow);
        };
        p.runtime.tickAt(0);
        assertEquals(1, searches[0]); assertEquals(1, p.inputs);
    }

    @Test void emptyOrdinarySearchLeavesTheNativeCustomRunnerFallbackAvailable() throws Exception {
        Probe p = probe(); p.runtime.threads = 2;
        p.lookupAction = () -> assertNull(p.runtime.prioritySearch(java.util.Collections::emptyIterator));
        p.runtime.tickAt(0);
        assertEquals(0, p.inputs);
        assertEquals(0, p.runtime.getActiveRecipeThreadCount());
    }

    @Test void stellarSnapshotCountsAllAdvancingThreadsAndOnlyFinishedBatchesWithoutTransactions() throws Exception {
        Probe p = probe(); p.runtime.threads = 2;
        p.queue("slow", 100); p.runtime.tickAt(0);
        p.queue("fast", 2); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(1);
        p.runtime.tickAt(2);
        var state = p.runtime.getRecipeThreadSnapshot();
        assertEquals(2, state.capacity()); assertEquals(2, state.active());
        assertEquals(2, state.running()); assertTrue(state.threadsFull());
        assertEquals(64, state.parallelCapacity()); assertEquals(64, state.reservedParallel());
        assertEquals(3, state.successfulTicks()); assertEquals(0, state.completedBatches());
        int inputs = p.inputs, setups = p.setupCalls;
        assertEquals(state, p.runtime.getRecipeThreadSnapshot());
        assertEquals(inputs, p.inputs); assertEquals(setups, p.setupCalls);
        p.outputAvailable = false; p.runtime.tickAt(3);
        assertEquals(0, p.runtime.getRecipeThreadSnapshot().completedBatches());
        p.outputAvailable = true; p.runtime.tickAt(4);
        state = p.runtime.getRecipeThreadSnapshot();
        assertEquals(1, state.completedBatches()); assertEquals(1, state.active());
        assertFalse(state.threadsFull());
        p.setWorkingEnabled(false);
        assertFalse(p.runtime.getRecipeThreadSnapshot().enabled());
        assertEquals(0, p.runtime.getRecipeThreadSnapshot().running());
    }

    @Test void stellarSnapshotUsesTheSchedulersActualAdmissionGateAfterDowngrade() throws Exception {
        Probe p = probe(); p.runtime.parallel = 100;
        p.queue("a", 100); p.runtime.tickAt(0);
        p.queue("b", 100); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(1);
        p.queue("c", 100); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(2);
        p.runtime.parallel = 90;
        p.queue("waiting", 1); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(3);
        var state = p.runtime.getRecipeThreadSnapshot();
        assertEquals(4, state.capacity()); assertEquals(3, state.active());
        assertEquals(75, state.reservedParallel()); assertEquals(90, state.parallelCapacity());
        assertFalse(state.threadsFull()); assertFalse(state.parallelFull());
        assertTrue(state.admissionBlocked()); assertEquals(3, p.inputs);
    }

    @Test void independentBatchesShareOneMachineWorkingCallbackAndCannotAdvanceTwiceInOneTick() throws Exception {
        Probe p = probe();
        p.queue("slow", 100); p.runtime.tickAt(0);
        p.queue("fast", 2); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(1);
        p.runtime.tickAt(2); p.runtime.tickAt(2); p.runtime.tickAt(3);
        assertEquals(List.of("fast"), p.finished);
        assertEquals(2, p.inputs);
        assertEquals(3, p.machineWorkingCalls);
        assertEquals(3, p.getRecipeThreadRows().getList("threads", 10).getCompound(0).getInt("progress"));
        assertEquals(List.of(16L, 16L), p.shares);
        assertEquals(2, p.blockedRenewals);
    }

    @Test void paidBuilderResultSurvivesOutputGateAndRefusedSetupWithoutPayingAgain() throws Exception {
        Probe p = probe();
        p.outputAvailable = false; p.queue("paid", 2); p.runtime.tickAt(0);
        assertEquals(1, p.inputs); assertEquals(1, p.runtime.getActiveRecipeThreadCount());
        p.refuseSetup = true; p.runtime.tickAt(1);
        assertEquals(1, p.inputs); assertEquals(0, p.getProgress());
        p.refuseSetup = false; p.runtime.tickAt(2); p.runtime.tickAt(3);
        assertTrue(p.finished.isEmpty());
        assertEquals(2, p.getProgress());
        assertEquals("WAITING", p.getRecipeThreadRows().getList("threads", 10).getCompound(0).getString("status"));
        p.outputAvailable = true; p.runtime.tickAt(4);
        assertEquals(List.of("paid"), p.finished); assertEquals(1, p.inputs);
    }

    @Test void standardSetupPaysOnceAfterApprovalAndRetainsNativeVirtualCallbacks() throws Exception {
        Probe p = probe(); p.payInLookup = false;
        p.queue("standard", 2); p.runtime.tickAt(0); p.runtime.tickAt(1); p.runtime.tickAt(2);
        assertEquals(1, p.inputs); assertEquals(1, p.setupCalls);
        assertEquals(List.of("standard"), p.finished);
        assertEquals(2, p.blockedRenewals);
    }

    @Test void sourceRemovalSoftStopAndStructureFailureRetainEveryPaidBatch() throws Exception {
        Probe p = probe(); p.queue("one", 2); p.runtime.tickAt(0);
        p.queue("two", 3); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(1);
        p.runtime.source = false; p.runtime.threads = 1; p.runtime.available = false;
        p.resetRecipeLogic(); p.runtime.tickAt(2);
        assertEquals(2, p.runtime.getActiveRecipeThreadCount());
        p.runtime.available = true; p.setWorkingEnabled(false); p.runtime.tickAt(3);
        assertTrue(p.finished.isEmpty());
        p.setWorkingEnabled(true); p.drain = true;
        p.queue("forbidden", 1);
        p.runtime.tickAt(4); p.runtime.tickAt(5); p.runtime.tickAt(6);
        assertEquals(List.of("one", "two"), p.finished);
        assertEquals(2, p.inputs); assertEquals(1, p.pending.size()); assertTrue(p.isSuspend());
    }

    @Test void saveAndLoadPreserveProgressWirelessSignAndEmptyListAuthority() throws Exception {
        Probe p = probe();
        p.pending.add(new WirelessGTRecipe(recipe("wireless", 5), BigInteger.valueOf(-37L)));
        p.runtime.tickAt(0); p.runtime.tickAt(1);
        CompoundTag saved = new CompoundTag(); p.runtime.save(saved);
        Probe restored = probe(); restored.runtime.load(saved);
        assertEquals(1, restored.getProgress());
        assertEquals("37", restored.getRecipeThreadRows().getString("consumption"));
        for (int t = 2; t <= 5; t++) restored.runtime.tickAt(t);
        assertEquals(List.of("wireless"), restored.finished); assertEquals(0, restored.inputs);
        CompoundTag empty = new CompoundTag(); restored.runtime.save(empty);
        p.runtime.load(empty);
        assertEquals(0, p.runtime.getActiveRecipeThreadCount());
    }

    @Test void capacityDowngradeDoesNotStartAnExtraOrderUntilReservationsFit() throws Exception {
        Probe p = probe(); p.queue("first", 20); p.runtime.tickAt(0);
        p.queue("second", 20); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(1);
        p.runtime.parallel = 8; p.runtime.threads = 1;
        p.queue("wait", 1); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(2);
        assertEquals(2, p.inputs);
        assertEquals(32L, p.getRecipeThreadRows().getLong("reserved"));
    }

    @Test void customPlannerCannotPayMoreThanItsRemainingLongShare() throws Exception {
        Probe p = probe();
        p.runtime.parallel = 64000000000L;
        long share = p.runtime.parallel / 4L;
        List<Boolean> decisions = new ArrayList<>();
        p.lookupAction = () -> {
            try {
                GTRecipe first = recipe("first_candidate", 20); first.parallels = 10;
                GTRecipe second = recipe("second_candidate", 20); second.parallels = 10;
                // Use an int-sized fixture for the final payment guard while testing long allowance separately.
                assertEquals(share, NativeRecipeThreadRuntime.limitParallel(Long.MAX_VALUE));
                assertNull(NativeRecipeThreadRuntime.inputDecision(p.machine, first));
                NativeRecipeThreadRuntime.recordInput(p.machine, first);
                assertEquals(share - 10L, NativeRecipeThreadRuntime.limitParallel(Long.MAX_VALUE));
                second.parallels = Integer.MAX_VALUE;
                assertNull(NativeRecipeThreadRuntime.inputDecision(p.machine, second));
                NativeRecipeThreadRuntime.recordInput(p.machine, second);
                assertEquals(share - 10L - Integer.MAX_VALUE, NativeRecipeThreadRuntime.limitParallel(Long.MAX_VALUE));
                decisions.add(true);
            } catch (Exception error) { throw new AssertionError(error); }
        };
        p.runtime.tickAt(0);
        assertEquals(List.of(true), decisions);

        Probe small = probe();
        small.lookupAction = () -> {
            try {
                GTRecipe first = recipe("first", 20); first.parallels = 10;
                GTRecipe second = recipe("over", 20); second.parallels = 10;
                NativeRecipeThreadRuntime.recordInput(small.machine, first);
                assertEquals(Boolean.FALSE, NativeRecipeThreadRuntime.inputDecision(small.machine, second));
                assertEquals(6L, NativeRecipeThreadRuntime.limitParallel(Long.MAX_VALUE));
            } catch (Exception error) { throw new AssertionError(error); }
        };
        small.runtime.tickAt(0);
    }

    @Test void wirelessReservationsIncludePendingPaidFramesAndKeepDirectionsSeparate() throws Exception {
        Probe p = probe(); p.refuseSetup = true;
        p.pending.add(new WirelessGTRecipe(recipe("consumer", 20), BigInteger.valueOf(-30L)));
        p.runtime.tickAt(0);
        p.lookupAction = () -> {
            assertEquals(BigInteger.valueOf(400L), NativeRecipeThreadRuntime.admissionWirelessBudget(BigInteger.valueOf(1000L), true));
            assertEquals(BigInteger.valueOf(1000L), NativeRecipeThreadRuntime.admissionWirelessBudget(BigInteger.valueOf(1000L), false));
        };
        p.runtime.onThreadInputsChanged(); p.runtime.tickAt(1);
    }

    @Test void delayedSettlementKeepsSimulationUnpaidAndUsesItsOwnPersistedLaneShare() throws Exception {
        Probe p = probe(); p.payAtFinish = true;
        p.queue("delayed_a", 3); p.runtime.tickAt(0);
        p.queue("delayed_b", 2); p.runtime.onThreadInputsChanged(); p.runtime.tickAt(1);
        assertEquals(0, p.inputs, "Native beforeWork only simulates conversion inputs");
        CompoundTag saved = new CompoundTag(); p.runtime.save(saved);
        Probe restored = probe(); restored.payAtFinish = true; restored.runtime.load(saved);
        restored.runtime.parallel = 8; restored.runtime.threads = 1;
        restored.runtime.tickAt(2); restored.runtime.tickAt(3);
        assertEquals(List.of("delayed_a", "delayed_b"), restored.finished);
        assertEquals(List.of(16L, 16L), restored.settledShares);
        assertEquals(2, restored.inputs);
    }

    @Test void replayingPaidSetupSkipsInputAndNativeFinishCannotTakeAnyNewInputs() throws Exception {
        Probe p = probe(); p.outputAvailable = false; p.queue("paid", 1);
        p.runtime.tickAt(0); p.outputAvailable = true; p.checkInputGates = true;
        p.runtime.tickAt(1);
        assertEquals(1, p.inputs);
        assertTrue(p.sawPaidReplay);
        assertTrue(p.sawFinishInputRejection);
    }

    private static class TestRuntime extends NativeRecipeThreadRuntime {
        long now, parallel = 64, threads = 4;
        boolean source = true, available = true;
        TestRuntime(Probe logic) { super(logic); }
        @Override protected boolean hasSource() { return source; }
        @Override protected long parallelCapacity() { return parallel; }
        @Override protected long capacity() { return Math.min(parallel, threads); }
        @Override protected long timer() { return now; }
        @Override protected boolean available() { return available; }
        @Override protected boolean canFinish(GTRecipe recipe) { return ((Probe) logic).outputAvailable; }
        // The production GTRecipePayload in the dependency jar invokes SRG Minecraft names.
        // Isolate only that codec; real Frame persistence and scheduler restore still execute.
        @Override protected GTRecipePayload recipePayload() {
            return new GTRecipePayload() {
                @Override public Tag serializeNBT() {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("id", getPayload().id.getPath()); tag.putInt("duration", getPayload().duration);
                    return tag;
                }
                @Override public void deserializeNBT(Tag value) {
                    CompoundTag tag = (CompoundTag) value;
                    try { setPayload(recipe(tag.getString("id"), tag.getInt("duration"))); }
                    catch (Exception error) { throw new AssertionError(error); }
                }
            };
        }
        void tickAt(long time) { now = time; assertTrue(tick()); }
    }

    private static class Probe extends RecipeLogic implements RecipeThreadStateAccessor,
            PrimordialRecipeThreadStateAccessor, NativeRecipeThreadHost {
        TestRuntime runtime;
        Runnable lookupAction;
        ArrayDeque<GTRecipe> pending;
        List<String> finished;
        List<Long> shares;
        List<Long> settledShares;
        boolean outputAvailable = true, refuseSetup, payInLookup = true, drain, payAtFinish;
        boolean checkInputGates, sawPaidReplay, sawFinishInputRejection;
        boolean usePriority;
        String blockedRecipe;
        int inputs, setupCalls, blockedRenewals, machineWorkingCalls;
        Probe() { super(null); }
        @Override public NativeRecipeThreadRuntime shanhai$recipeThreads() { return runtime; }
        @Override public void findAndHandleRecipe() {
            if (runtime.isFinishing()) { blockedRenewals++; return; }
            if (lookupAction != null) { lookupAction.run(); return; }
            GTRecipe recipe;
            if (usePriority) {
                recipe = runtime.priorityCandidates(new java.util.LinkedHashSet<>(pending)).stream().findFirst().orElse(null);
                if (recipe != null && recipe.id.getPath().equals(blockedRecipe)) return;
                pending.remove(recipe);
            } else recipe = pending.poll();
            if (recipe == null) return;
            shares.add(runtime.allowance(Long.MAX_VALUE));
            if (payInLookup && !payAtFinish) { inputs++; runtime.recordPaidRecipe(recipe); }
            if (outputAvailable || !payInLookup) setupRecipe(recipe);
        }
        @Override public void setupRecipe(GTRecipe recipe) {
            if (runtime.isFinishing()) { blockedRenewals++; return; }
            setupCalls++;
            if (checkInputGates) {
                assertEquals(Boolean.TRUE, NativeRecipeThreadRuntime.inputDecision(machine, recipe));
                sawPaidReplay = true;
            }
            if (refuseSetup) { duration = progress = 0; setStatus(Status.IDLE); return; }
            if (!payInLookup && !payAtFinish) inputs++;
            lastRecipe = recipe; duration = recipe.duration; progress = 0; setStatus(Status.WORKING);
        }
        @Override public void handleRecipeWorking() {
            if (runtime.onWorking(machine)) { progress++; setStatus(Status.WORKING); }
        }
        @Override public void onRecipeFinish() {
            if (checkInputGates) {
                assertEquals(Boolean.FALSE, NativeRecipeThreadRuntime.inputDecision(machine, lastRecipe));
                sawFinishInputRejection = true;
            }
            if (payAtFinish) {
                inputs++;
                settledShares.add(NativeRecipeThreadRuntime.transactionParallel(machine, 64L));
            }
            finished.add(lastRecipe.id.getPath());
            findAndHandleRecipe();
            setupRecipe(lastRecipe);
            setStatus(Status.IDLE); duration = progress = 0;
        }
        @Override public void setStatus(Status value) {
            if (!runtime.interceptStatus(value)) shanhai$threadStatus(value);
        }
        @Override public void updateTickSubscription() {}
        @Override public void setWaiting(Component reason) { setStatus(Status.WAITING); shanhai$threadWaitingReason(reason); }
        @Override public void setWorkingEnabled(boolean enabled) { runtime.setEnabled(enabled); super.setWorkingEnabled(enabled); }
        @Override public void resetRecipeLogic() { if (!runtime.preserveInvalidation()) super.resetRecipeLogic(); }
        @Override public void inValid() {}
        @Override public void shanhai$recipe(GTRecipe value) { lastRecipe = value; }
        @Override public void shanhai$origin(GTRecipe value) { lastOriginRecipe = value; }
        @Override public void shanhai$progress(int value) { progress = value; }
        @Override public void shanhai$duration(int value) { duration = value; }
        @Override public void shanhai$fuel(int value) { fuelTime = value; }
        @Override public void shanhai$maxFuel(int value) { fuelMaxTime = value; }
        @Override public void shanhai$dirty(boolean value) { recipeDirty = value; }
        @Override public void shanhai$continuous(long value) { totalContinuousRunningTime = value; }
        @Override public OCParams shanhai$ocParams() { return ocParams; }
        @Override public void shanhai$ocParams(OCParams value) { ocParams = value; }
        @Override public OCResult shanhai$ocResult() { return ocResult; }
        @Override public void shanhai$ocResult(OCResult value) { ocResult = value; }
        @Override public void shanhai$threadStatus(Status value) { UNSAFE.putObject(this, STATUS, value); }
        @Override public boolean shanhai$threadActive() { return UNSAFE.getBoolean(this, ACTIVE); }
        @Override public void shanhai$threadActive(boolean value) { UNSAFE.putBoolean(this, ACTIVE, value); }
        @Override public Component shanhai$threadWaitingReason() { return (Component) UNSAFE.getObject(this, REASON); }
        @Override public void shanhai$threadWaitingReason(Component value) { UNSAFE.putObject(this, REASON, value); }
        CompoundTag getRecipeThreadRows() { return runtime.getRecipeThreadDisplay(0, 8); }
        void queue(String name, int ticks) throws Exception { pending.add(recipe(name, ticks)); }
    }

    private static Probe probe() throws Exception {
        Probe p = (Probe) UNSAFE.allocateInstance(Probe.class);
        p.runtime = new TestRuntime(p); p.pending = new ArrayDeque<>();
        p.finished = new ArrayList<>(); p.shares = new ArrayList<>(); p.settledShares = new ArrayList<>();
        p.outputAvailable = p.payInLookup = true;
        p.shanhai$threadStatus(RecipeLogic.Status.IDLE);
        IRecipeLogicMachine machine = (IRecipeLogicMachine) Proxy.newProxyInstance(
                NativeRecipeThreadRuntimeTest.class.getClassLoader(),
                new Class<?>[]{IRecipeLogicMachine.class, ISuspendableMachine.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "onWorking" -> { p.machineWorkingCalls++; yield true; }
                        case "gtlcore$isSuspendAfterFinish" -> p.drain;
                        case "gtlcore$setSuspendAfterFinish" -> { p.drain = (boolean) args[0]; yield null; }
                        case "getRecipeLogic" -> p;
                        case "isRecipeLogicAvailable" -> true;
                        default -> method.getReturnType() == boolean.class ? false
                                : method.getReturnType() == int.class ? 0
                                : method.getReturnType() == long.class ? 0L : null;
                    };
                });
        set("machine", p, machine); set("chanceCaches", p, new IdentityHashMap<>());
        p.shanhai$ocParams(new OCParams()); p.shanhai$ocResult(new OCResult());
        return p;
    }

    private static GTRecipe recipe(String name, int ticks) throws Exception {
        GTRecipeType type = (GTRecipeType) UNSAFE.allocateInstance(GTRecipeType.class);
        UNSAFE.putObject(type, UNSAFE.objectFieldOffset(GTRecipeType.class.getField("registryName")),
                new ResourceLocation("gt_shanhai", name));
        return new GTRecipe(type, new ResourceLocation("gt_shanhai", name), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), new CompoundTag(), ticks, false);
    }
    private static void set(String name, Object target, Object value) throws Exception {
        Field field = RecipeLogic.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static long offset(String name) {
        try { return UNSAFE.objectFieldOffset(RecipeLogic.class.getDeclaredField(name)); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static Unsafe unsafe() {
        try { Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true); return (Unsafe) field.get(null); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
}
