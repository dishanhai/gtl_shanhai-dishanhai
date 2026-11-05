package com.dishanhai.gt_shanhai.common.machine.thread;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecipeThreadPriorityTest {
    record Job(String id, long ticks, int originalTicks) {}

    @Test void longestEstimateGetsItsOwnBatchWithoutChangingTheLookup() {
        Job fast = new Job("fast", 10, 1000), slow = new Job("slow", 100, 100);
        var source = new ArrayList<>(List.of(fast, slow));
        var priority = new RecipeThreadPriority<Job>(Job::ticks, Job::originalTicks);
        assertEquals(List.of(slow), priority.select(source));
        assertEquals(List.of(fast, slow), source);
    }

    @Test void failedLongRecipeFallsBackToTheNextCandidate() {
        Job a = new Job("a", 100, 100), b = new Job("b", 20, 20);
        var priority = new RecipeThreadPriority<Job>(Job::ticks, Job::originalTicks);
        assertEquals(List.of(a), priority.select(List.of(b, a)));
        assertTrue(priority.retry());
        assertEquals(List.of(b), priority.select(List.of(b, a)));
        assertFalse(priority.retry());
    }

    @Test void equalFixedTimeUsesOriginalDurationThenPreservesSourceOrder() {
        Job a = new Job("a", 20, 100), b = new Job("b", 20, 200), c = new Job("c", 20, 200);
        var priority = new RecipeThreadPriority<Job>(Job::ticks, Job::originalTicks);
        assertEquals(List.of(b), priority.select(List.of(a, b, c)));
        assertTrue(priority.retry()); assertEquals(List.of(c), priority.select(List.of(a, b, c)));
    }

    @Test void estimationIsOncePerCandidatePerAdmissionAndRetriesUseTheSnapshot() {
        var calls = new AtomicInteger();
        var priority = new RecipeThreadPriority<Integer>(i -> { calls.incrementAndGet(); return i; }, i -> i);
        assertEquals(List.of(3), priority.select(List.of(1, 3, 2)));
        assertTrue(priority.retry());
        assertEquals(List.of(2), priority.select(List.of(1, 3, 2)));
        assertEquals(3, calls.get());
    }

    @Test void emptyCandidatesDoNotRetry() {
        var priority = new RecipeThreadPriority<Integer>(i -> i, i -> i);
        assertTrue(priority.select(List.of()).isEmpty());
        assertFalse(priority.retry());
    }

    @Test void retryDoesNotRebuildTheNativeLookupSnapshot() {
        var searches = new AtomicInteger();
        var priority = new RecipeThreadPriority<Integer>(i -> i, i -> i);
        java.util.function.Supplier<List<Integer>> lookup = () -> { searches.incrementAndGet(); return List.of(1, 3, 2); };
        assertEquals(List.of(3), priority.select(lookup));
        assertTrue(priority.retry()); assertEquals(List.of(2), priority.select(lookup));
        assertTrue(priority.retry()); assertEquals(List.of(1), priority.select(lookup));
        assertEquals(1, searches.get());
    }

    @Test void energyEstimateUsesPowerAndParallelWithoutOverflow() {
        assertEquals(200, RecipeThreadPriority.energyTicks(1000, 4, 20, 20));
        assertEquals(20, RecipeThreadPriority.energyTicks(1, 1, 100, 20));
        assertEquals(Integer.MAX_VALUE, RecipeThreadPriority.energyTicks(Double.MAX_VALUE, Long.MAX_VALUE, 1, 20));
    }

    @Test void standardOverclockCanReverseTheOriginalDurationOrder() throws Exception {
        // 隔離 GTValues 的 Minecraft 靜態初始化，仍執行原生標準超頻邏輯。
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var previous = com.gregtechceu.gtceu.config.ConfigHolder.INSTANCE;
        if (previous == null) {
            var config = (com.gregtechceu.gtceu.config.ConfigHolder) unsafe.allocateInstance(com.gregtechceu.gtceu.config.ConfigHolder.class);
            config.machines = (com.gregtechceu.gtceu.config.ConfigHolder.MachineConfigs)
                    unsafe.allocateInstance(com.gregtechceu.gtceu.config.ConfigHolder.MachineConfigs.class);
            config.machines.overclockDivisor = 2;
            com.gregtechceu.gtceu.config.ConfigHolder.INSTANCE = config;
        }
        try {
            long originallySlow = RecipeThreadPriority.standardTicks(160, 8, 2048, 4);
            long originallyFast = RecipeThreadPriority.standardTicks(80, 2048, 2048, 0);
            assertTrue(originallySlow < originallyFast);
            assertEquals(80, originallyFast);
        } finally { com.gregtechceu.gtceu.config.ConfigHolder.INSTANCE = previous; }
    }
}
