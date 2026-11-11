package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.api.machine.part.IRecipeThreadSource;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.PrimordialOmegaVoidInductionArmatureLogic;
import com.dishanhai.gt_shanhai.mixin.PrimordialRecipeThreadStateAccessor;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.machine.trait.MachineTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：執行真實 base.serverTick；只隔離世界、Mixin 欄位及庫存／能源邊界。 */
class PrimordialThreadedRecipeLogicTest {
    private static final Unsafe UNSAFE = unsafe();
    private static final long STATUS = offset(RecipeLogic.class, "status");
    private static final long ACTIVE = offset(RecipeLogic.class, "isActive");
    private static final long REASON = offset(RecipeLogic.class, "waitingReason");

    @Test void cloudLeaseObservationIncludesSuspendedLanesWithoutBlockingUnrelatedRecipes() throws Exception {
        Probe logic = probe(); logic.queue("cloud", 100); tick(logic, 0);
        var method = PrimordialThreadedRecipeLogic.class.getMethod("hasRunningRecipeMatching", java.util.function.Predicate.class);
        java.util.function.Predicate<GTRecipe> cloud = recipe -> recipe.id.getPath().equals("cloud");
        java.util.function.Predicate<GTRecipe> unrelated = recipe -> recipe.id.getPath().equals("unrelated");
        assertEquals(true, method.invoke(logic, cloud));
        assertEquals(false, method.invoke(logic, unrelated));
        logic.setWorkingEnabled(false);
        assertEquals(true, method.invoke(logic, cloud));
        var field = PrimordialThreadedRecipeLogic.class.getDeclaredField("threads");
        field.setAccessible(true);
        Object frame = ((PrimordialThreadScheduler<?>) field.get(logic)).lanes().getFirst().task();
        var origin = frame.getClass().getDeclaredField("origin"); origin.setAccessible(true);
        logic.queue("unrelated", 100); origin.set(frame, logic.pending.remove());
        assertEquals(false, method.invoke(logic, unrelated), "有效執行配方不能被舊 origin 的身份覆蓋");
    }

    @Test void priorityStartsTheLongestSourceAloneAndLeavesShortWorkForTheNextLane() throws Exception {
        Probe logic = probe(); logic.usePriority = true;
        logic.queue("short", 2); logic.queue("long", 100);
        tick(logic, 0);
        assertEquals(1, logic.inputTransactions);
        assertEquals("short", logic.pending.peek().id.getPath());
        tick(logic, 1); tick(logic, 2); tick(logic, 3);
        assertEquals(2, logic.inputTransactions);
        assertEquals(List.of("short"), logic.finished);
    }

    @Test void prioritySkipsARejectedLongSourceBeforeAnyInputTransaction() throws Exception {
        Probe logic = probe(); logic.usePriority = true; logic.blockedRecipe = "long";
        logic.queue("short", 2); logic.queue("long", 100);
        tick(logic, 0);
        assertEquals(1, logic.inputTransactions);
        assertEquals("long", logic.pending.peek().id.getPath());
        assertEquals(1, logic.getActiveRecipeThreadCount());
    }

    @Test void stellarSnapshotObservesAllBatchProgressAndCountsOnlyReleasedBatches() throws Exception {
        Probe logic = probe();
        logic.queue("long", 100); tick(logic, 0);
        logic.queue("short", 2); logic.wake(); tick(logic, 1); tick(logic, 2);
        var state = logic.getRecipeThreadSnapshot();
        assertEquals(4, state.capacity()); assertEquals(2, state.active());
        assertEquals(2, state.running()); assertFalse(state.threadsFull());
        assertEquals(64, state.parallelCapacity()); assertEquals(32, state.reservedParallel());
        assertEquals(3, state.successfulTicks()); assertEquals(0, state.completedBatches());
        int inputs = logic.inputTransactions, lookups = logic.lookups;
        assertEquals(state, logic.getRecipeThreadSnapshot());
        assertEquals(inputs, logic.inputTransactions); assertEquals(lookups, logic.lookups);
        logic.outputAvailable = false; tick(logic, 3);
        assertEquals(0, logic.getRecipeThreadSnapshot().completedBatches());
        logic.outputAvailable = true; tick(logic, 4);
        state = logic.getRecipeThreadSnapshot();
        assertEquals(1, state.completedBatches()); assertEquals(1, state.active());
        logic.setWorkingEnabled(false);
        assertFalse(logic.getRecipeThreadSnapshot().enabled());
        assertEquals(0, logic.getRecipeThreadSnapshot().running());
    }

    @Test void laterBatchStartsAndFinishesWithoutWaitingForTheFirstBatch() throws Exception {
        Probe logic = probe();
        logic.queue("long", 100);
        tick(logic, 0);
        logic.queue("short", 2);
        logic.wake();
        tick(logic, 5);
        tick(logic, 6);
        tick(logic, 7);

        assertEquals(List.of("short"), logic.finished);
        assertEquals(List.of("long"), logic.running());
        assertEquals(List.of(16L, 16L), logic.admittedShares);
        assertEquals(2, logic.inputTransactions);
        assertEquals(Map.of("long", 3, "short", 2), logic.energyTicks);
        assertEquals(1, logic.getActiveRecipeThreadCount());
        assertEquals(16L, logic.getReservedThreadParallel());
    }

    @ParameterizedTest
    @ValueSource(longs = {64L, Long.MAX_VALUE / 1000L})
    void unlimitedSharedPoolReservesTheActualParallelOfTwentyBatches(long parallelPerBatch) throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = Long.MAX_VALUE;
        logic.module.configuredThreads = Long.MAX_VALUE;
        logic.useActualParallel = true;
        for (int i = 0; i < 20; i++) {
            GTRecipe batch = new WirelessGTRecipe(recipe("parallel_batch_" + i, 20), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, parallelPerBatch);
            logic.pending.add(batch);
            tick(logic, i);
        }

        assertEquals(20, logic.getActiveRecipeThreadCount());
        assertEquals(Long.MAX_VALUE, logic.admittedShares.getFirst());
        assertEquals(Long.MAX_VALUE - 19L * parallelPerBatch, logic.admittedShares.getLast());
        assertEquals(20L * parallelPerBatch, logic.getReservedThreadParallel());
        assertEquals(parallelPerBatch, display(logic).getList("threads", 10).getCompound(0).getLong("reserved"));
        assertFalse(logic.getRecipeThreadSnapshot().threadsFull());
    }

    @Test void overdriverCanStartThreeDifferentFullPoolsAtTheSameTime() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = Long.MAX_VALUE;
        logic.module.configuredThreads = Long.MAX_VALUE;
        logic.module.overdriver = true;
        logic.useActualParallel = true;
        for (String id : List.of("sulfur", "iron", "copper")) {
            var batch = new WirelessGTRecipe(recipe(id, 20), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE);
            logic.pending.add(batch);
        }

        tick(logic, 0); tick(logic, 1); tick(logic, 2);

        assertEquals(List.of("sulfur", "iron", "copper"), logic.running());
        assertEquals(List.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), logic.admittedShares);
        assertFalse(logic.getRecipeThreadSnapshot().admissionBlocked());
        assertFalse(logic.getRecipeThreadSnapshot().parallelFull());
        assertTrue(display(logic).getBoolean("independentRecipePools"));
    }

    @Test void overdriverStopsAtNinetySixEvenWithAStrongerAttachedThreadSource() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = Long.MAX_VALUE;
        logic.module.configuredThreads = 96L;
        logic.module.attached = List.of(sourcePart(4097L));
        logic.module.overdriver = true;
        logic.useActualParallel = true;
        for (int i = 0; i < 97; i++) {
            var batch = new WirelessGTRecipe(recipe("recipe_" + i, 200), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE);
            logic.pending.add(batch);
            tick(logic, i);
        }

        assertEquals(96L, logic.getRecipeThreadCapacity());
        assertEquals(96, logic.getActiveRecipeThreadCount());
        assertEquals(1, logic.pending.size());
        assertTrue(logic.getRecipeThreadSnapshot().threadsFull());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void shortRecipesFillAllNinetySixSlotsFromOneLookup(int duration) throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = Long.MAX_VALUE;
        logic.module.configuredThreads = 96;
        logic.module.overdriver = true;
        logic.useActualParallel = true;
        logic.usePriority = true;
        for (int i = 0; i < 97; i++) {
            var batch = new WirelessGTRecipe(recipe("short_pool_" + i, duration), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE);
            logic.pending.add(batch);
        }

        tick(logic, 0);

        assertEquals(96, logic.getActiveRecipeThreadCount());
        assertEquals(96, logic.inputTransactions);
        assertEquals(1, logic.candidateLookups);
        assertEquals(1, logic.pending.size());
        assertTrue(logic.energyTicks.isEmpty(), "新批次從下一 tick 開始工作");
        for (int i = 1; i < duration; i++) tick(logic, i);
        assertEquals(96, logic.getActiveRecipeThreadCount());
        assertTrue(logic.energyTicks.values().stream().allMatch(ticks -> ticks == duration - 1));
        assertEquals(1, logic.candidateLookups);
        tick(logic, duration);
        assertEquals(96, logic.finished.size());
        assertEquals(1, logic.getActiveRecipeThreadCount());
    }

    @Test void twentyTwoEligiblePoolsDoNotDuplicateWorkToFillNinetySixSlots() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = Long.MAX_VALUE;
        logic.module.configuredThreads = 96;
        logic.module.overdriver = true;
        logic.useActualParallel = true;
        logic.usePriority = true;
        for (int i = 0; i < 22; i++) {
            var batch = new WirelessGTRecipe(recipe("overworld_" + i, 20), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE);
            logic.pending.add(batch);
        }
        tick(logic, 0);
        assertEquals(22, logic.getActiveRecipeThreadCount());
        assertEquals(22, logic.inputTransactions);
        assertEquals(1, logic.candidateLookups);
        tick(logic, 1);
        tick(logic, 2);
        assertEquals(2, logic.candidateLookups, "候選用盡後保留失敗退避");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameRecipeMergesAtMostFourPaidBatchesAndKeepsLogicalThreadAccounting(boolean overdriver) throws Exception {
        Probe logic = probe();
        logic.module.configuredThreads = 5;
        logic.module.overdriver = overdriver;
        logic.useActualParallel = true;
        logic.usePriority = true;
        GTRecipe source = recipe("mergeable", 20);
        for (int i = 0; i < 5; i++) {
            var batch = new WirelessGTRecipe(source.copy(), BigInteger.valueOf(-3));
            batch.data = batch.data.copy();
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, 4);
            logic.pending.add(batch);
        }
        tick(logic, 0);
        assertEquals(5, logic.inputTransactions);
        assertEquals(5, logic.getActiveRecipeThreadCount());
        assertEquals(2, logic.getRecipeExecutionBatchCount());
        assertEquals(20, logic.getReservedThreadParallel());
        assertEquals(!overdriver, logic.getRecipeThreadSnapshot().threadsFull());
        var view = display(logic);
        assertEquals(5, view.getInt("running"));
        assertEquals("15", view.getString("consumption"));
        assertEquals(4, view.getList("threads", 10).getCompound(0).getInt("mergedBatches"));
        assertEquals(1, view.getList("threads", 10).getCompound(1).getInt("mergedBatches"));
        tick(logic, 1);
        assertEquals(2, logic.energyTicks.get("mergeable"));
        assertEquals(5, logic.getRecipeThreadSnapshot().successfulTicks());
        CompoundTag saved = new CompoundTag();
        logic.saveRecipeThreads(saved);
        Probe restored = probe();
        restored.module.configuredThreads = 5;
        restored.module.overdriver = overdriver;
        restored.useActualParallel = true;
        restored.loadRecipeThreads(saved);
        assertEquals(5, restored.getActiveRecipeThreadCount());
        assertEquals(2, restored.getRecipeExecutionBatchCount());
        assertEquals(20, restored.getReservedThreadParallel());
        assertEquals("15", display(restored).getString("consumption"));
        tick(restored, 2);
        assertEquals(0, restored.inputTransactions, "讀檔只繼續已扣料合併批次");
        assertEquals(2, restored.energyTicks.get("mergeable"));
        logic.outputAvailable = false;
        for (int i = 2; i <= 20; i++) tick(logic, i);
        assertEquals(5, logic.getActiveRecipeThreadCount());
        assertEquals(20, logic.getReservedThreadParallel());
        logic.outputAvailable = true;
        tick(logic, 21);
        assertEquals(0, logic.getActiveRecipeThreadCount());
        assertEquals(5, logic.getRecipeThreadSnapshot().completedBatches());
    }

    @Test void sameRecipeWithDifferentProgressKeepsSeparateExecutionBatches() throws Exception {
        Probe logic = probe();
        logic.useActualParallel = true;
        GTRecipe source = recipe("different_progress", 20);
        for (int i = 0; i < 2; i++) {
            var batch = new WirelessGTRecipe(source.copy(), BigInteger.valueOf(-3));
            batch.data = batch.data.copy();
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, 4);
            logic.pending.add(batch);
            logic.wake();
            tick(logic, i);
        }
        assertEquals(2, logic.getRecipeExecutionBatchCount());
        assertEquals(1, display(logic).getList("threads", 10).getCompound(0).getInt("progress"));
        assertEquals(0, display(logic).getList("threads", 10).getCompound(1).getInt("progress"));
    }

    @Test void groupedOutputsKeepAllVariantsAndSumBeyondLongMaxValue() {
        ListTag outputs = new ListTag();
        for (int variant = 0; variant < 6; variant++) {
            CompoundTag item = new CompoundTag();
            item.putString("id", "minecraft:raw_gold");
            CompoundTag tag = new CompoundTag();
            tag.putInt("variant", variant);
            item.put("tag", tag);
            CompoundTag output = new CompoundTag();
            output.put("item", item);
            output.putLong("amount", Long.MAX_VALUE);
            outputs.add(output);
        }
        outputs.add(outputs.getCompound(0).copy());
        ListTag grouped = PrimordialThreadedRecipeLogic.groupThreadOutputs(outputs);
        assertEquals(6, grouped.size());
        assertEquals("18446744073709551614", grouped.getCompound(0).getString("amountExact"));
        assertEquals(5, grouped.getCompound(5).getCompound("item").getCompound("tag").getInt("variant"));
        assertEquals(Long.MAX_VALUE, outputs.getCompound(0).getLong("amount"));
    }

    @Test void crossRecipeMergesPreserveEachFullPoolAcrossSaveAndRestore() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = Long.MAX_VALUE;
        logic.module.overdriver = true;
        logic.useActualParallel = logic.usePriority = true;
        GTRecipe wrapper = recipe("paid_wrapper", 20);
        for (int i = 0; i < 96; i++) {
            GTRecipe source = recipe("source_" + i, 20);
            var batch = new WirelessGTRecipe(wrapper.copy(), BigInteger.valueOf(-7));
            batch.data = batch.data.copy();
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE);
            logic.pending.add(source);
            logic.preparedBatches.put(source, batch);
        }
        tick(logic, 0);
        assertEquals(96, logic.getActiveRecipeThreadCount());
        assertEquals(24, logic.getRecipeExecutionBatchCount());
        assertTrue(logic.hasMergedRecipeBatches());
        assertEquals(1, logic.candidateLookups);
        var row = display(logic).getList("threads", 10).getCompound(0);
        assertEquals(4, row.getInt("mergedBatches"));
        assertEquals(4, row.getList("recipes", 10).size());
        for (var element : row.getList("recipes", 10)) {
            assertEquals(Long.MAX_VALUE, ((CompoundTag) element).getLong("reserved"));
        }
        CompoundTag saved = new CompoundTag();
        logic.saveRecipeThreads(saved);
        Probe restored = probe();
        restored.parallelCapacity = Long.MAX_VALUE;
        restored.module.overdriver = true;
        restored.useActualParallel = restored.usePriority = true;
        restored.loadRecipeThreads(saved);
        assertEquals(96, restored.getActiveRecipeThreadCount());
        assertEquals(24, restored.getRecipeExecutionBatchCount());
        assertEquals("672", display(restored).getString("consumption"));
        // 釋放一個無關組的四個槽，滿池配方仍不得重複扣料。
        var schedulerField = PrimordialThreadedRecipeLogic.class.getDeclaredField("threads");
        schedulerField.setAccessible(true);
        var scheduler = (PrimordialThreadScheduler<?>) schedulerField.get(restored);
        Object other = scheduler.lanes().getLast().task();
        Field duration = other.getClass().getDeclaredField("duration");
        duration.setAccessible(true);
        duration.setInt(other, 1);
        for (String id : List.of("source_0", "new_source")) {
            var batch = new WirelessGTRecipe(recipe(id, 20), BigInteger.valueOf(-1));
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE);
            restored.pending.add(batch);
        }
        tick(restored, 1);
        assertEquals(1, restored.inputTransactions);
        assertEquals("source_0", restored.pending.getFirst().id.getPath());
        assertEquals(93, restored.getActiveRecipeThreadCount());
        assertEquals(4, restored.getRecipeThreadSnapshot().completedBatches());
        assertEquals(24, restored.energyTicks.get("paid_wrapper"));
    }

    @Test void overdriverPrefersUnusedRecipeAndOnlyReusesTheSamePoolRemainder() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = Long.MAX_VALUE;
        logic.module.configuredThreads = Long.MAX_VALUE;
        logic.module.overdriver = true;
        logic.useActualParallel = true;
        logic.usePriority = true;
        var first = new WirelessGTRecipe(recipe("sulfur", 4), BigInteger.ZERO);
        first.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE - 64L);
        logic.pending.add(first);
        tick(logic, 0);

        for (String id : List.of("sulfur", "sulfur", "copper")) {
            var batch = new WirelessGTRecipe(recipe(id, id.equals("sulfur") ? 100 : 20), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Long.MAX_VALUE);
            logic.pending.add(batch);
        }
        tick(logic, 1);
        tick(logic, 2);
        tick(logic, 3);

        assertEquals(List.of("sulfur", "copper", "sulfur"), logic.running());
        assertEquals(List.of(Long.MAX_VALUE, Long.MAX_VALUE, 64L), logic.admittedShares);
        assertEquals(1, logic.pending.size(), "同一配方池用滿後不得再扣料");

        tick(logic, 4);
        assertTrue(logic.pending.isEmpty(), "已結算的額度可在同 tick 接單");
        assertEquals(Long.MAX_VALUE - 64L, logic.admittedShares.getLast());
    }

    @Test void capacityReductionAdmitsTheRemainingParallelWithoutActualParallelTracking() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = 128L;
        logic.queue("paid", 100);
        tick(logic, 0L);
        assertEquals(32L, logic.getReservedThreadParallel());

        logic.parallelCapacity = 40L;
        logic.queue("remainder", 100);
        logic.wake();
        tick(logic, 1L);

        assertEquals(List.of(32L, 8L), logic.admittedShares);
        assertEquals(40L, logic.getReservedThreadParallel());
        assertEquals(2, logic.inputTransactions);
        assertEquals(2, logic.getActiveRecipeThreadCount());
        assertEquals(List.of("paid", "remainder"), logic.running());
        assertTrue(logic.finished.isEmpty());
        assertTrue(logic.getRecipeThreadSnapshot().parallelFull());
    }

    @Test void limitedThreadsRedistributeUnusedParallelToTheRemainingSlots() throws Exception {
        Probe logic = probe();
        logic.useActualParallel = true;
        long[] actual = {4L, 20L, 20L, 20L};
        for (int i = 0; i < actual.length; i++) {
            GTRecipe batch = new WirelessGTRecipe(recipe("finite_share_" + i, 20), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, actual[i]);
            logic.pending.add(batch);
            tick(logic, i);
            if (i == 0) {
                assertEquals(4L, logic.getRecipeThreadSnapshot().reservedParallel());
            }
        }

        assertEquals(List.of(16L, 20L, 20L, 20L), logic.admittedShares);
        assertEquals(64L, logic.getReservedThreadParallel());
        assertEquals(4L, display(logic).getList("threads", 10).getCompound(0).getLong("reserved"));
        assertTrue(logic.getRecipeThreadSnapshot().threadsFull());
    }

    @Test void sharedPoolDistributesAdmissionsBetweenLongAndShortRecipesInTheSameTick() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = 1920L;
        logic.module.configuredThreads = 262145L;
        logic.useActualParallel = logic.usePriority = true;
        for (String id : List.of("slow", "fast")) {
            for (int i = 0; i < 96; i++) {
                var batch = new WirelessGTRecipe(recipe(id, id.equals("slow") ? 1000 : 200), BigInteger.ZERO);
                batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, 1L);
                logic.pending.add(batch);
            }
        }

        tick(logic, 0L);

        assertEquals(48L, logic.pending.stream().filter(batch -> batch.id.getPath().equals("slow")).count());
        assertEquals(48L, logic.pending.stream().filter(batch -> batch.id.getPath().equals("fast")).count());
        assertEquals(96, logic.getActiveRecipeThreadCount());
        assertEquals(96L, logic.getReservedThreadParallel());
        assertEquals(96, logic.inputTransactions);
        assertEquals(1, logic.candidateLookups);
        assertTrue(logic.energyTicks.isEmpty());
    }

    @Test void sharedPoolKeepsSourceOccupancyWhenPrioritiesAreRebuiltOnTheNextTick() throws Exception {
        Probe logic = probe();
        logic.parallelCapacity = 1920L;
        logic.module.configuredThreads = 262145L;
        logic.useActualParallel = logic.usePriority = true;
        for (int i = 0; i < 192; i++) {
            var batch = new WirelessGTRecipe(recipe("slow", 1000), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, 1L);
            logic.pending.add(batch);
        }
        tick(logic, 0L);
        assertEquals(96, logic.getActiveRecipeThreadCount());
        for (int i = 0; i < 96; i++) {
            var batch = new WirelessGTRecipe(recipe("fast", 200), BigInteger.ZERO);
            batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, 1L);
            logic.pending.add(batch);
        }

        tick(logic, 1L);

        assertEquals(96, logic.pending.size());
        assertTrue(logic.pending.stream().allMatch(batch -> batch.id.getPath().equals("slow")));
        assertEquals(192, logic.getActiveRecipeThreadCount());
        assertEquals(192L, logic.getReservedThreadParallel());
        assertEquals(192, logic.inputTransactions);
        assertEquals(2, logic.candidateLookups);
        assertTrue(logic.finished.isEmpty());
    }

    @Test void sharedPoolWithoutActualParallelTrackingPrefersAnUnusedRecipeOnTheNextTick() throws Exception {
        Probe logic = probe();
        logic.usePriority = true;
        logic.queue("slow", 1000);
        logic.queue("slow", 1000);
        logic.queue("fast", 200);

        tick(logic, 0L);
        tick(logic, 1L);

        assertEquals(List.of("slow", "fast"), logic.running());
        assertEquals(List.of(16L, 16L), logic.admittedShares);
        assertEquals(32L, logic.getReservedThreadParallel());
        assertEquals(2, logic.inputTransactions);
        assertEquals("slow", logic.pending.getFirst().id.getPath());
    }

    @Test void sharedPoolSourceRankingDoesNotRejectAnOtherwiseRunnableUnidentifiedRecipe() throws Exception {
        Probe logic = probe();
        logic.usePriority = true;
        GTRecipe untagged = recipe("untagged", 20);
        UNSAFE.putObject(untagged.recipeType, UNSAFE.objectFieldOffset(GTRecipeType.class.getField("registryName")),
                new ResourceLocation("gtceu", "dummy"));
        assertNull(PrimordialThreadRecipeIdentity.fromRecipe(untagged));
        logic.pending.add(untagged);

        tick(logic, 0L);

        assertEquals(1, logic.inputTransactions);
        assertEquals(1, logic.getActiveRecipeThreadCount());
        assertEquals(List.of("untagged"), logic.running());
        assertEquals(16L, logic.getReservedThreadParallel());
    }

    @Test void unlimitedThreadSourceCanUseTheRemainingFiniteMachineParallel() throws Exception {
        Probe logic = probe();
        logic.useActualParallel = true;
        logic.module.configuredThreads = Long.MAX_VALUE;
        GTRecipe batch = new WirelessGTRecipe(recipe("finite_machine", 20), BigInteger.ZERO);
        batch.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, 32L);
        logic.pending.add(batch);

        tick(logic, 0);

        assertEquals(64L, logic.getRecipeThreadSnapshot().capacity());
        assertEquals(List.of(64L), logic.admittedShares);
        assertEquals(32L, logic.getReservedThreadParallel());
    }

    @Test void nativeFinishIsBlockedButCentralAdmissionMayStartTheNextBatchInTheSameTick() throws Exception {
        Probe logic = probe();
        logic.queue("first", 1);
        tick(logic, 0);
        logic.queue("later", 10);
        tick(logic, 1);

        assertEquals(List.of("first"), logic.finished);
        assertEquals(1, logic.blockedFinishLookups);
        assertEquals(2, logic.lookups);
        assertEquals(2, logic.inputTransactions);
        assertTrue(logic.pending.isEmpty());
        tick(logic, 5);
        assertEquals(List.of("later"), logic.running());
        assertEquals(2, logic.inputTransactions);
    }

    @Test void pushedMatchStartsOnNextTickEvenAfterAnEmptyLookup() throws Exception {
        Probe logic = probe();
        logic.queue("long", 100);
        tick(logic, 0);
        tick(logic, 1);
        logic.queue("pushed", 2);
        logic.wake();
        tick(logic, 2);
        assertEquals(List.of("long", "pushed"), logic.running());
        assertEquals(2, logic.inputTransactions);
        assertEquals(Map.of("long", 2), logic.energyTicks);
        tick(logic, 3);
        tick(logic, 4);
        assertEquals(List.of("pushed"), logic.finished);
        assertEquals(List.of("long"), logic.running());
    }

    @Test void displaySnapshotIncludesIndependentProgressAndSeparatesEnergyDirections() throws Exception {
        Probe logic = probe();
        logic.pending.add(new WirelessGTRecipe(recipe("consumer", 100), BigInteger.TEN.negate()));
        tick(logic, 0);
        logic.pending.add(new WirelessGTRecipe(recipe("generator", 20), BigInteger.valueOf(7L)));
        logic.wake();
        tick(logic, 1);
        tick(logic, 2);
        int transactions = logic.inputTransactions;
        int lookups = logic.lookups;
        CompoundTag view = display(logic);
        assertEquals(2, view.getInt("active"));
        assertEquals(4L, view.getLong("capacity"));
        assertEquals(32L, view.getLong("reserved"));
        assertEquals("10", view.getString("consumption"));
        assertEquals("7", view.getString("production"));
        ListTag rows = view.getList("threads", 10);
        assertEquals(2, rows.size());
        assertEquals(2, rows.getCompound(0).getInt("progress"));
        assertEquals(1, rows.getCompound(1).getInt("progress"));
        assertEquals(100, rows.getCompound(0).getInt("duration"));
        assertEquals(20, rows.getCompound(1).getInt("duration"));
        assertEquals("gt_shanhai:consumer", rows.getCompound(0).getString("recipeType"));
        assertEquals(16L, rows.getCompound(0).getLong("reserved"));
        assertEquals("10", rows.getCompound(0).getString("consumption"));
        assertEquals("0", rows.getCompound(0).getString("production"));
        assertEquals("gt_shanhai:generator", rows.getCompound(1).getString("recipe"));
        assertEquals("0", rows.getCompound(1).getString("consumption"));
        assertEquals("7", rows.getCompound(1).getString("production"));
        assertEquals(19, rows.getCompound(1).getInt("remainingTicks"));
        assertEquals(transactions, logic.inputTransactions);
        assertEquals(lookups, logic.lookups, "Jade 讀取不得尋配方或扣料");

        logic.setWorkingEnabled(false);
        view = display(logic);
        assertFalse(view.getBoolean("enabled"));
        assertEquals(2, view.getInt("active"));
        assertEquals("0", view.getString("consumption"));
        assertEquals("0", view.getString("production"));
    }

    @Test void blockedCompletedThreadIsVisibleWithoutReportingEnergyConsumption() throws Exception {
        Probe logic = probe();
        logic.pending.add(new WirelessGTRecipe(recipe("blocked", 1), BigInteger.TEN));
        tick(logic, 0);
        logic.outputAvailable = false;
        tick(logic, 1);
        CompoundTag view = display(logic);
        assertEquals(1, view.getInt("waiting"));
        assertEquals("0", view.getString("consumption"));
        assertEquals("WAITING", view.getList("threads", 10).getCompound(0).getString("status"));
        assertFalse(view.getList("threads", 10).getCompound(0).getString("reason").isEmpty());
    }

    @Test void sourceRecipeIdentityIsFrozenSeparatelyForEveryAcceptedThread() throws Exception {
        Probe logic = probe();
        GTRecipe first = recipe("real_first", 100);
        first.data.putString("gt_shanhai_recipe_id", "gt_shanhai:photon_matrix_etch/wafer/ulpic_wafer_6");
        first.data.putString("gt_shanhai_recipe_type", "gtceu:photon_matrix_etch");
        logic.sourceRecipes = List.of(first);
        logic.pending.add(new WirelessGTRecipe(recipe("merged_first", 100), BigInteger.ZERO));
        tick(logic, 0);
        GTRecipe second = recipe("real_second", 100);
        logic.sourceRecipes = List.of(second);
        logic.pending.add(new WirelessGTRecipe(recipe("merged_second", 100), BigInteger.ZERO));
        logic.wake();
        tick(logic, 1);
        first.data.putString("gt_shanhai_recipe_id", "gt_shanhai:later_pattern");
        var rows = display(logic).getList("threads", 10);
        assertEquals("gt_shanhai:photon_matrix_etch/wafer/ulpic_wafer_6",
                rows.getCompound(0).getList("recipes", 10).getCompound(0).getString("recipeId"));
        assertEquals("gt_shanhai:real_second",
                rows.getCompound(1).getList("recipes", 10).getCompound(0).getString("recipeId"));
        assertEquals(2, logic.inputTransactions);
    }

    @Test void nativeInputObserverRecordsSuccessOnlyDuringThisMachinesMatchingScope() throws Exception {
        Probe logic = probe();
        var sources = new java.util.LinkedHashSet<PrimordialThreadRecipeIdentity.Identity>();
        set(PrimordialThreadedRecipeLogic.class, "matchingRecipeSources", logic, sources);
        var method = com.dishanhai.gt_shanhai.mixin.StellarPatternInputParallelObservationMixin.class
                .getDeclaredMethod("gtShanhai$observeConsumedParallel",
                        com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine.class, GTRecipe.class,
                        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable.class);
        method.setAccessible(true);
        GTRecipe source = recipe("actual_input", 100);
        method.invoke(null, logic.module, source,
                new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<>("test", false, Boolean.FALSE));
        assertTrue(sources.isEmpty());
        method.invoke(null, logic.module, source,
                new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<>("test", false, Boolean.TRUE));
        assertEquals(Set.of(new PrimordialThreadRecipeIdentity.Identity("gt_shanhai:actual_input",
                "gt_shanhai:actual_input")), sources);
        set(PrimordialThreadedRecipeLogic.class, "matchingRecipeSources", logic, null);
        method.invoke(null, logic.module, recipe("outside_scope", 100),
                new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<>("test", false, Boolean.TRUE));
        assertEquals(1, sources.size());
    }

    private static CompoundTag display(Probe logic) throws Exception {
        return logic.getRecipeThreadDisplay(0, 8);
    }

    @Test void displayPagesBoundRowsButStillSumEveryRunningThread() throws Exception {
        Probe logic = probe();
        logic.module.configuredThreads = 16;
        for (int i = 0; i < 12; i++) {
            logic.pending.add(new WirelessGTRecipe(recipe("task_" + i, 1000), BigInteger.TEN.negate()));
            tick(logic, i);
        }
        CompoundTag view = logic.getRecipeThreadDisplay(8, Integer.MAX_VALUE);
        assertEquals(12, view.getInt("active"));
        assertEquals("120", view.getString("consumption"));
        assertEquals(4, view.getList("threads", 10).size());
        assertEquals(8L, view.getList("threads", 10).getCompound(0).getLong("index"));
        assertEquals(8, logic.getRecipeThreadDisplay(0, Integer.MAX_VALUE).getList("threads", 10).size());
    }

    @Test void hugeEnergySnapshotStaysBoundedAndLocaleIndependent() throws Exception {
        Probe logic = probe();
        logic.pending.add(new WirelessGTRecipe(recipe("huge", 100), BigInteger.ONE.shiftLeft(10000).negate()));
        tick(logic, 0);
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            String energy = display(logic).getString("consumption");
            assertTrue(energy.length() < 24);
            assertTrue(energy.matches("[0-9]+\\.[0-9]+E[0-9]+"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test void unavailableMachineReportsAllRetainedThreadsWaitingWithTheSameReason() throws Exception {
        Probe logic = probe();
        logic.queue("retained", 100);
        tick(logic, 0);
        logic.module.formed = false;
        tick(logic, 1);
        CompoundTag view = display(logic);
        assertEquals(1, view.getInt("waiting"));
        assertEquals("0", view.getString("consumption"));
        CompoundTag row = view.getList("threads", 10).getCompound(0);
        assertEquals("WAITING", row.getString("status"));
        assertTrue(row.getString("reason").contains("gt_shanhai.primordial.threads.waiting_machine"));
        assertEquals(1, logic.inputTransactions);
        assertTrue(logic.energyTicks.isEmpty());
    }

    @Test void successfulSingleThreadOneTickRecipesKeepOneBatchPerTickThroughput() throws Exception {
        Probe logic = probe();
        logic.module.configuredThreads = 1L;
        logic.queue("first", 1);
        logic.queue("second", 1);
        logic.queue("third", 1);

        tick(logic, 0);
        assertEquals(1, logic.inputTransactions);
        assertEquals(1, logic.lookups);
        assertTrue(logic.finished.isEmpty());

        tick(logic, 1);
        assertEquals(2, logic.inputTransactions);
        assertEquals(2, logic.lookups);
        assertEquals(List.of("first"), logic.finished);

        tick(logic, 2);
        assertEquals(3, logic.inputTransactions);
        assertEquals(3, logic.lookups, "成功接續每 tick 仍只有一次集中配方查找");
        assertEquals(List.of("first", "second"), logic.finished);
        assertEquals(List.of("third"), logic.running());
        assertEquals(2, logic.blockedFinishLookups, "native 完成回呼始終不得自行續批");
        assertEquals(List.of(64L, 64L, 64L), logic.admittedShares);
    }

    @Test void completedBlockedBatchKeepsItsInputsAndDoesNotPayEnergyAgain() throws Exception {
        Probe logic = probe();
        logic.queue("blocked", 1);
        tick(logic, 0);
        logic.outputAvailable = false;
        tick(logic, 1);
        tick(logic, 2);
        tick(logic, 3);

        assertTrue(logic.finished.isEmpty());
        assertEquals(List.of("blocked"), logic.running());
        assertEquals(Map.of("blocked", 1), logic.energyTicks);
        assertEquals(1, logic.inputTransactions);
        logic.outputAvailable = true;
        tick(logic, 4);
        assertEquals(List.of("blocked"), logic.finished);
        assertTrue(logic.running().isEmpty());
        assertEquals(Map.of("blocked", 1), logic.energyTicks);
    }

    @Test void allIdleThreadsUseOneLazyLookupAndFullCapacityDoesNotSearch() throws Exception {
        Probe logic = probe();
        tick(logic, 0);
        for (int tick = 1; tick < 20; tick++) tick(logic, tick);
        assertEquals(1, logic.lookups);
        for (int i = 0; i < 4; i++) logic.queue("task_" + i, 100);
        logic.wake();
        for (int tick = 20; tick <= 35; tick += 5) tick(logic, tick);
        assertEquals(5, logic.lookups);
        assertEquals(4, logic.getActiveRecipeThreadCount());
        assertEquals(64L, logic.getReservedThreadParallel());
        logic.queue("waiting", 10);
        logic.wake();
        tick(logic, 40);
        assertEquals(5, logic.lookups);
        assertEquals(1, logic.pending.size());
    }

    @Test void idleBackoffSkipsCapacityAndStillPollsForUnannouncedInputs() throws Exception {
        Probe logic = probe();
        tick(logic, 0);
        int capacityChecks = logic.capacityChecks;
        logic.queue("ae_input", 10);
        for (int now = 1; now < 20; now++) tick(logic, now);
        assertEquals(capacityChecks, logic.capacityChecks);
        assertEquals(1, logic.lookups);

        tick(logic, 20);
        assertEquals(2, logic.lookups);
        assertEquals(List.of("ae_input"), logic.running());
    }

    @Test void idleBackoffStillAppliesNativeDrain() throws Exception {
        Probe logic = probe();
        tick(logic, 0);
        logic.module.gtlcore$setSuspendAfterFinish(true);
        tick(logic, 1);
        assertFalse(logic.isWorkingEnabled());
        assertFalse(logic.module.gtlcore$isSuspendAfterFinish());
        assertEquals(1, logic.lookups);
    }

    @Test void continuousWarehouseUpdatesKeepWorkingCandidatesPastTwentyTicks() throws Exception {
        Probe logic = probe();
        logic.queue("working", 100);
        tick(logic, 0);
        var cache = seedLookupCache(logic);
        var notify = SelectableRecipeTypeSetRecipeLogic.class.getDeclaredMethod("noteNoisyHandlerContentsChanged");
        notify.setAccessible(true);
        for (int now = 0; now <= 199; now++) {
            logic.module.now = now;
            notify.invoke(logic);
            assertNotNull(cache.get(logic), "连续数量通知不应提前清除加工中的候选，tick " + now);
        }

        logic.module.now = 200;
        logic.module.lookupTypes = new GTRecipeType[] {null};
        notify.invoke(logic);
        assertNull(cache.get(logic), "选择器变化必须立即清除候选");
    }

    @Test void freshWarehouseEventAndIdleMachineStillInvalidateStaleCandidates() throws Exception {
        Probe logic = probe();
        logic.queue("working", 100);
        tick(logic, 0);
        var cache = seedLookupCache(logic);
        var notify = SelectableRecipeTypeSetRecipeLogic.class.getDeclaredMethod("noteNoisyHandlerContentsChanged");
        notify.setAccessible(true);
        logic.module.now = 0;
        notify.invoke(logic);
        logic.module.now = 25;
        notify.invoke(logic);
        assertNull(cache.get(logic), "安静后的新通知可刷新老候选");

        Probe idle = probe();
        seedLookupCache(idle);
        for (int now = 0; now <= 20; now++) {
            idle.module.now = now;
            notify.invoke(idle);
        }
        assertNull(cache.get(idle), "空闲时连续通知仍会刷新老候选");
    }

    private static Field seedLookupCache(Probe logic) throws Exception {
        var owner = SelectableRecipeTypeSetRecipeLogic.class;
        var cache = owner.getDeclaredField("cachedLookupRecipes");
        cache.setAccessible(true);
        cache.set(logic, Set.of(recipe("cached", 100)));
        set(owner, "cachedLookupTick", logic, 0L);
        set(owner, "lastContentNotifyTick", logic, Long.MIN_VALUE);
        return cache;
    }

    @Test void pauseAndResumePreserveProgressAndDoNotConsumeInputsTwice() throws Exception {
        Probe logic = probe();
        logic.queue("paid", 30);
        tick(logic, 0);
        tick(logic, 1);
        logic.setWorkingEnabled(false);
        tick(logic, 5);
        tick(logic, 20);
        assertFalse(logic.isWorkingEnabled());
        assertEquals(Map.of("paid", 1), logic.energyTicks);
        assertEquals(1, logic.inputTransactions);

        logic.setWorkingEnabled(true);
        tick(logic, 21);
        assertEquals(Map.of("paid", 2), logic.energyTicks);
        assertEquals(1, logic.inputTransactions);
        assertEquals(List.of("paid"), logic.running());
    }

    @Test void structureInvalidationAndUnloadDoNotReenableAPausedMachine() throws Exception {
        Probe logic = probe();
        logic.queue("paid", 30);
        tick(logic, 0);
        logic.setWorkingEnabled(false);
        logic.module.formed = false;
        logic.resetRecipeLogic();
        logic.inValid();
        assertFalse(logic.isWorkingEnabled());
        assertEquals(List.of("paid"), logic.running());
        logic.module.formed = true;
        tick(logic, 5);
        assertTrue(logic.energyTicks.isEmpty());
        assertEquals(1, logic.inputTransactions);
        logic.setWorkingEnabled(true);
        tick(logic, 6);
        assertEquals(Map.of("paid", 1), logic.energyTicks);
    }

    @Test void authoritativeEmptySnapshotDoesNotImportNativeProjectionOrEnableTheMachine() throws Exception {
        Probe logic = probe();
        logic.seedLegacy("native_projection", 30, 7);
        logic.setWorkingEnabled(false);
        CompoundTag saved = new CompoundTag();
        saved.put("sh_primordial_recipe_threads", new ListTag());
        saved.putBoolean("sh_recipe_threads_enabled", false);
        logic.loadRecipeThreads(saved);
        assertFalse(logic.isWorkingEnabled());
        assertTrue(logic.running().isEmpty());
        logic.setWorkingEnabled(true);
        tick(logic, 0);
        assertTrue(logic.running().isEmpty());
        assertEquals(0, logic.inputTransactions);
    }

    @Test void legacyPaidBatchIsImportedOnceAndContinuesAfterTheStructureReturns() throws Exception {
        Probe logic = probe();
        logic.seedLegacy("legacy", 30, 7);
        logic.module.formed = false;
        tick(logic, 0);
        tick(logic, 1);
        assertEquals(List.of("legacy"), logic.running());
        assertTrue(logic.energyTicks.isEmpty());
        logic.module.formed = true;
        tick(logic, 5);
        assertEquals(8, logic.getProgress());
        assertEquals(1, logic.getActiveRecipeThreadCount());
        assertEquals(0, logic.inputTransactions);
        assertEquals(Map.of("legacy", 1), logic.energyTicks);
    }

    @Test void transientInterruptRetainsThePaidBatchForTheNextTick() throws Exception {
        Probe logic = probe();
        logic.queue("paid", 30);
        tick(logic, 0);
        logic.failWorking = true;
        tick(logic, 1);
        assertTrue(logic.isWaiting());
        assertEquals(List.of("paid"), logic.running());
        assertTrue(logic.energyTicks.isEmpty());
        logic.failWorking = false;
        tick(logic, 2);
        assertEquals(Map.of("paid", 1), logic.energyTicks);
        assertEquals(1, logic.inputTransactions);
    }

    @Test void zeroPointSpecialLookupCannotBypassTheCentralAdmissionGate() throws Exception {
        ZeroPointProbe logic = (ZeroPointProbe) UNSAFE.allocateInstance(ZeroPointProbe.class);
        logic.module = probe().module;
        GTRecipe expected = recipe("zero_point", 20);
        logic.candidates = Set.of(expected);
        set(PrimordialThreadedRecipeLogic.class, "tickingThreads", logic, true);
        set(PrimordialThreadedRecipeLogic.class, "admission", logic, false);

        assertNull(logic.getGTRecipe());
        assertEquals(0, logic.beforeChecks);
        assertEquals(0, logic.lookups);

        set(PrimordialThreadedRecipeLogic.class, "admission", logic, true);
        assertSame(expected, logic.getGTRecipe());
        assertEquals(1, logic.beforeChecks);
        assertEquals(1, logic.lookups);
    }

    @Test void admissionReservesOnlyPowerAlreadyUsedInTheSameDirection() throws Exception {
        Probe logic = probe();
        logic.pending.add(new WirelessGTRecipe(recipe("consumer", 100), BigInteger.TEN.negate()));
        tick(logic, 0);
        logic.pending.add(new WirelessGTRecipe(recipe("generator", 100), BigInteger.valueOf(7L)));
        logic.wake();
        tick(logic, 5);
        assertEquals(2, logic.getActiveRecipeThreadCount());

        assertEquals(BigInteger.valueOf(100L), logic.powerBudget(100L, true));
        assertEquals(BigInteger.valueOf(100L), logic.powerBudget(100L, false));
        set(PrimordialThreadedRecipeLogic.class, "admission", logic, true);
        assertEquals(BigInteger.valueOf(90L), logic.powerBudget(100L, true));
        assertEquals(BigInteger.valueOf(93L), logic.powerBudget(100L, false));
        assertEquals(BigInteger.ZERO, logic.powerBudget(5L, true));
        assertEquals(BigInteger.ZERO, logic.powerBudget(6L, false));
    }

    @Test void completedBatchWaitingForOutputDoesNotReserveWirelessPower() throws Exception {
        Probe logic = probe();
        logic.pending.add(new WirelessGTRecipe(recipe("completed_consumer", 1), BigInteger.TEN));
        tick(logic, 0);
        logic.outputAvailable = false;
        tick(logic, 1);
        assertEquals(1, logic.getActiveRecipeThreadCount());
        assertTrue(logic.finished.isEmpty());

        set(PrimordialThreadedRecipeLogic.class, "admission", logic, true);
        assertEquals(BigInteger.valueOf(100L), logic.powerBudget(100L, true));
        assertEquals(BigInteger.valueOf(100L), logic.powerBudget(100L, false));
    }

    @Test void emptyThreadSnapshotRoundTripsWorkingEnabledInBothDirections() throws Exception {
        Probe source = probe();
        source.setWorkingEnabled(false);
        CompoundTag disabled = new CompoundTag();
        source.saveRecipeThreads(disabled);
        assertTrue(disabled.getList("sh_primordial_recipe_threads", 10).isEmpty());
        assertFalse(disabled.getBoolean("sh_recipe_threads_enabled"));

        Probe restored = probe();
        restored.loadRecipeThreads(disabled);
        assertFalse(restored.isWorkingEnabled());
        restored.setWorkingEnabled(true);
        CompoundTag enabled = new CompoundTag();
        restored.saveRecipeThreads(enabled);

        source.loadRecipeThreads(enabled);
        assertTrue(source.isWorkingEnabled());
        assertTrue(source.running().isEmpty());
        assertEquals(0, source.inputTransactions);
    }

    @Test void drainKeepsTheNativeFlagUntilEveryPaidBatchFinishes() throws Exception {
        Probe logic = probe();
        logic.queue("first", 2);
        tick(logic, 0);
        logic.queue("second", 5);
        tick(logic, 1);
        logic.queue("must_wait", 1);
        logic.module.gtlcore$setSuspendAfterFinish(true);

        tick(logic, 2);
        assertEquals(List.of("first"), logic.finished);
        assertTrue(logic.module.gtlcore$isSuspendAfterFinish(), "首批完成不能消耗全機等待停機旗標");
        assertEquals(2, logic.inputTransactions);
        assertEquals(List.of("second"), logic.running());

        for (int tick = 3; tick <= 6; tick++) tick(logic, tick);
        assertEquals(List.of("first", "second"), logic.finished);
        assertFalse(logic.module.gtlcore$isSuspendAfterFinish());
        assertFalse(logic.isWorkingEnabled());
        assertTrue(logic.running().isEmpty());
        assertEquals(2, logic.inputTransactions);
        assertEquals(1, logic.pending.size());
    }

    @Test void clearingTheNativeDrainFlagCancelsTheStopAndAllowsNewOrders() throws Exception {
        Probe logic = probe();
        logic.queue("first", 2);
        tick(logic, 0);
        logic.queue("second", 5);
        tick(logic, 1);
        logic.queue("after_cancel", 1);
        logic.module.gtlcore$setSuspendAfterFinish(true);
        tick(logic, 2);
        assertTrue(logic.module.gtlcore$isSuspendAfterFinish());
        assertEquals(2, logic.inputTransactions);

        logic.module.gtlcore$setSuspendAfterFinish(false);
        for (int tick = 3; tick <= 8; tick++) tick(logic, tick);
        assertTrue(logic.isWorkingEnabled());
        assertFalse(logic.module.gtlcore$isSuspendAfterFinish());
        assertEquals(3, logic.inputTransactions);
        assertTrue(logic.pending.isEmpty());
        assertTrue(logic.finished.contains("after_cancel"));
    }

    @Test void refusedSetupRetainsThePaidRecipeAndRetriesWithoutTakingInputsAgain() throws Exception {
        Probe logic = probe();
        logic.queue("already_paid", 10);
        logic.refuseSetup = true;
        tick(logic, 0);

        assertEquals(1, logic.getActiveRecipeThreadCount());
        assertTrue(logic.isWaiting());
        assertEquals(List.of("already_paid"), logic.running());
        assertEquals(1, logic.inputTransactions);
        assertEquals(1, logic.setupAttempts);
        assertTrue(logic.energyTicks.isEmpty());

        tick(logic, 1);
        assertEquals(2, logic.setupAttempts);
        assertEquals(1, logic.inputTransactions);
        assertTrue(logic.energyTicks.isEmpty());
        assertTrue(logic.finished.isEmpty());

        logic.refuseSetup = false;
        tick(logic, 2);
        assertEquals(3, logic.setupAttempts);
        assertEquals(Map.of("already_paid", 1), logic.energyTicks);
        assertEquals(1, logic.inputTransactions);
        for (int tick = 3; tick <= 14; tick++) tick(logic, tick);
        assertEquals(List.of("already_paid"), logic.finished);
        assertEquals(Map.of("already_paid", 10), logic.energyTicks);
        assertEquals(1, logic.inputTransactions);
    }

    @Test void attachedCapacityUsesTheStrongestSourceAndKeepsTheWholeMachineParallelLimit() throws Exception {
        Probe logic = probe();
        logic.module.attached = List.of(sourcePart(10L), sourcePart(7L));
        assertEquals(10L, logic.getRecipeThreadCapacity());
        logic.module.attached = List.of(sourcePart(Long.MAX_VALUE));
        assertEquals(64L, logic.getRecipeThreadCapacity(), "執行緒容量不能增加整機並行");
        logic.module.attached = List.of(sourcePart(1L));
        assertEquals(4L, logic.getRecipeThreadCapacity(), "關閉附屬能力保留原初內建來源");
    }

    private static IMultiPart sourcePart(long capacity) {
        return (IMultiPart) java.lang.reflect.Proxy.newProxyInstance(
                PrimordialThreadedRecipeLogicTest.class.getClassLoader(),
                new Class<?>[]{IMultiPart.class, IRecipeThreadSource.class},
                (proxy, method, arguments) -> method.getName().equals("getRecipeThreadCapacity") ? capacity : null);
    }

    private static void tick(Probe logic, long now) {
        logic.module.now = now;
        logic.serverTick();
    }

    private static Probe probe() throws Exception {
        Module module = (Module) UNSAFE.allocateInstance(Module.class);
        Probe logic = (Probe) UNSAFE.allocateInstance(Probe.class);
        module.formed = true;
        module.configuredThreads = 4L;
        module.logic = logic;
        logic.module = module;
        logic.pending = new ArrayDeque<>();
        logic.preparedBatches = new IdentityHashMap<>();
        logic.finished = new ArrayList<>();
        logic.admittedShares = new ArrayList<>();
        logic.energyTicks = new HashMap<>();
        logic.parallelCapacity = 64L;
        logic.outputAvailable = true;
        set(RecipeLogic.class, "machine", logic, module);
        set(RecipeLogic.class, "chanceCaches", logic, new IdentityHashMap<>());
        set(SelectableRecipeTypeSetRecipeLogic.class, "perTypeLookupCache", logic, new HashMap<>());
        set(PrimordialThreadedRecipeLogic.class, "threads", logic, new PrimordialThreadScheduler<>());
        logic.shanhai$threadStatus(RecipeLogic.Status.IDLE);
        logic.setWorkingEnabled(true);
        return logic;
    }

    private static class Module extends PrimordialOmegaEngineModuleBase implements ISuspendableMachine {
        long now;
        long configuredThreads;
        boolean formed;
        boolean overdriver;
        boolean suspendAfterFinish;
        GTRecipeType[] lookupTypes;
        List<IMultiPart> attached;
        Probe logic;
        int statusNotifications;
        RecipeLogic.Status lastBeforeStatus, lastAfterStatus;
        Module() { super(null); }
        @Override public long getOffsetTimer() { return now; }
        @Override public boolean isFormed() { return formed; }
        @Override public List<IMultiPart> getParts() { return attached == null ? List.of() : attached; }
        @Override public List<MachineTrait> getTraits() { return List.of(); }
        @Override public GTRecipeType[] getRecipeTypes() {
            return lookupTypes == null ? new GTRecipeType[0] : lookupTypes;
        }
        @Override protected boolean canModuleWork() { return formed; }
        @Override public long getParallelRecipeThreadCount() { return configuredThreads; }
        @Override public boolean hasParallelOverdriver() { return overdriver; }
        @Override public Probe getRecipeLogic() { return logic; }
        @Override public void notifyStatusChanged(RecipeLogic.Status before, RecipeLogic.Status after) {
            statusNotifications++;
            lastBeforeStatus = before;
            lastAfterStatus = after;
        }
        @Override public void onWaiting() {}
        @Override public boolean gtlcore$isSuspendAfterFinish() { return suspendAfterFinish; }
        @Override public void gtlcore$setSuspendAfterFinish(boolean value) { suspendAfterFinish = value; }
    }

    private static class Probe extends PrimordialThreadedRecipeLogic implements PrimordialRecipeThreadStateAccessor {
        Module module;
        ArrayDeque<GTRecipe> pending;
        List<GTRecipe> sourceRecipes;
        List<String> finished;
        List<Long> admittedShares;
        Map<String, Integer> energyTicks;
        long parallelCapacity;
        boolean outputAvailable, failWorking, refuseSetup;
        boolean usePriority, useActualParallel;
        String blockedRecipe;
        int lookups, candidateLookups, inputTransactions, blockedFinishLookups, setupAttempts, capacityChecks;
        RecipeResult recipeResult, workingResult;
        Map<GTRecipe, GTRecipe> preparedBatches;
        Probe() { super(null); }

        @Override public SelectableRecipeTypeSetMachine getMachine() { return module; }
        @Override protected long getUnreservedTotalParallelLimit() {
            capacityChecks++;
            return parallelCapacity;
        }
        @Override protected boolean tracksActualParallel() { return useActualParallel; }
        @Override public void updateTickSubscription() {}
        // 隔離依賴 jar 的 SRG codec，仍執行真實 Frame NBT 與排程還原。
        @Override protected com.gregtechceu.gtceu.syncdata.GTRecipePayload threadRecipePayload() {
            return new com.gregtechceu.gtceu.syncdata.GTRecipePayload() {
                @Override public net.minecraft.nbt.Tag serializeNBT() {
                    CompoundTag tag = new CompoundTag();
                    tag.putString("id", getPayload().id.getPath());
                    tag.putInt("duration", getPayload().duration);
                    tag.put("data", getPayload().data.copy());
                    return tag;
                }
                @Override public void deserializeNBT(net.minecraft.nbt.Tag value) {
                    CompoundTag tag = (CompoundTag) value;
                    try {
                        GTRecipe decoded = recipe(tag.getString("id"), tag.getInt("duration"));
                        decoded.data = tag.getCompound("data").copy();
                        setPayload(decoded);
                    } catch (Exception error) { throw new AssertionError(error); }
                }
            };
        }
        @Override protected long estimateThreadRecipeTicks(GTRecipe recipe) { return 20L; }
        @Override protected boolean canFinishThreadBatch(GTRecipe recipe) { return outputAvailable; }
        @Override public void setRecipeStatus(RecipeResult result) { recipeResult = result; }
        @Override public RecipeResult getRecipeStatus() { return recipeResult; }
        @Override public void setWorkingStatus(RecipeResult result) { workingResult = result; }
        @Override public RecipeResult getWorkingStatus() { return workingResult; }

        @Override protected GTRecipe getGTRecipe() {
            if (!isThreadRecipeLookupAllowed()) {
                blockedFinishLookups++;
                return null;
            }
            lookups++;
            GTRecipe next = usePriority
                    ? priorityCandidates(() -> {
                        candidateLookups++;
                        return new java.util.LinkedHashSet<>(pending);
                    }).stream().findFirst().orElse(null)
                    : pending.peek();
            if (usePriority && next != null) {
                GTRecipe candidate = next;
                next = pending.stream().filter(batch -> batch.id.equals(candidate.id)).findFirst().orElse(null);
            }
            if (next == null || usePriority && next.id.getPath().equals(blockedRecipe)) return null;
            long share = module.overdriver ? getThreadRecipePoolRemaining(next) : getThreadParallelAllowance();
            if (share <= 0L) return null;
            pending.remove(next);
            GTRecipe paid = preparedBatches.getOrDefault(next, next);
            if (module.overdriver) {
                long actual = paid.data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY);
                paid.data.putLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY, Math.min(actual, share));
            }
            inputTransactions++;
            admittedShares.add(share);
            for (GTRecipe source : sourceRecipes == null ? List.of(next) : sourceRecipes) {
                onThreadRecipeInputHandled(source);
            }
            return paid;
        }

        @Override protected void setupThreadRecipe(GTRecipe recipe) {
            setupAttempts++;
            if (refuseSetup) {
                setStatus(Status.IDLE);
                duration = progress = 0;
                return;
            }
            lastRecipe = recipe;
            duration = recipe.duration;
            progress = 0;
            shanhai$threadActive(true);
            setStatus(Status.WORKING);
        }

        @Override public void handleRecipeWorking() {
            if (failWorking) { interruptRecipe(); return; }
            energyTicks.merge(lastRecipe.id.getPath(), 1, Integer::sum);
            progress++;
            totalContinuousRunningTime++;
            setStatus(Status.WORKING);
        }

        @Override public void onRecipeFinish() {
            finished.add(lastRecipe.id.getPath());
            assertNull(getGTRecipe(), "完成回呼必須經過集中 admission gate");
            // 模擬 GTLAdd 原生完成回呼會消耗 machine 級等待停機旗標。
            if (module.gtlcore$isSuspendAfterFinish()) {
                module.gtlcore$setSuspendAfterFinish(false);
                setStatus(Status.SUSPEND);
            } else {
                setStatus(Status.IDLE);
            }
            progress = duration = 0;
        }

        @Override public void shanhai$threadStatus(Status status) { UNSAFE.putObject(this, STATUS, status); }
        @Override public boolean shanhai$threadActive() { return UNSAFE.getBoolean(this, ACTIVE); }
        @Override public void shanhai$threadActive(boolean active) { UNSAFE.putBoolean(this, ACTIVE, active); }
        @Override public Component shanhai$threadWaitingReason() { return (Component) UNSAFE.getObject(this, REASON); }
        @Override public void shanhai$threadWaitingReason(Component reason) { UNSAFE.putObject(this, REASON, reason); }

        void queue(String id, int ticks) throws Exception { pending.add(recipe(id, ticks)); }
        void wake() { onThreadInputsChanged(); }
        List<String> running() { return getRunningThreadRecipes().stream().map(recipe -> recipe.id.getPath()).toList(); }
        BigInteger powerBudget(long fullPower, boolean consumer) {
            return getThreadPowerBudget(BigInteger.valueOf(fullPower), consumer);
        }
        void seedLegacy(String id, int ticks, int elapsed) throws Exception {
            // native persisted 欄位先還原，尚未執行新調度器的舊存檔匯入。
            set(PrimordialThreadedRecipeLogic.class, "initialized", this, false);
            lastRecipe = recipe(id, ticks);
            duration = ticks;
            progress = elapsed;
            shanhai$threadStatus(Status.WORKING);
            shanhai$threadActive(true);
        }
    }

    private static GTRecipe recipe(String id, int ticks) throws Exception {
        GTRecipeType type = (GTRecipeType) UNSAFE.allocateInstance(GTRecipeType.class);
        UNSAFE.putObject(type, UNSAFE.objectFieldOffset(GTRecipeType.class.getField("registryName")),
                new ResourceLocation("gt_shanhai", id));
        return new TestRecipe(type, id, ticks);
    }

    private static class TestRecipe extends GTRecipe {
        TestRecipe(GTRecipeType type, String id, int ticks) {
            super(type, new ResourceLocation("gt_shanhai", id), new HashMap<>(), new HashMap<>(),
                    new HashMap<>(), new HashMap<>(), Map.of(), Map.of(), Map.of(), Map.of(),
                    List.of(), List.of(), new CompoundTag(), ticks, false);
        }
        // 重成型測試驗證 frame 保留，不載入依賴遊戲映射的原生能力 IO。
        @Override public void postWorking(IRecipeCapabilityHolder holder) {}
    }

    private static class ZeroPointProbe extends PrimordialOmegaVoidInductionArmatureLogic {
        Module module;
        Set<GTRecipe> candidates;
        int beforeChecks, lookups;
        ZeroPointProbe() { super(null); }
        @Override public SelectableRecipeTypeSetMachine getMachine() { return module; }
        @Override protected boolean checkBeforeWorking() { beforeChecks++; return true; }
        @Override protected Set<GTRecipe> lookupRecipeIterator() { lookups++; return candidates; }
    }

    private static void set(Class<?> owner, String name, Object target, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static long offset(Class<?> owner, String name) {
        try { return UNSAFE.objectFieldOffset(owner.getDeclaredField(name)); }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
}
