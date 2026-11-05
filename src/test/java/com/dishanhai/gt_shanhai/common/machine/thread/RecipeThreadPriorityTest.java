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

    @Test void energyEstimateUsesPowerAndParallelWithoutOverflow() {
        assertEquals(200, RecipeThreadPriority.energyTicks(1000, 4, 20, 20));
        assertEquals(20, RecipeThreadPriority.energyTicks(1, 1, 100, 20));
        assertEquals(Integer.MAX_VALUE, RecipeThreadPriority.energyTicks(Double.MAX_VALUE, Long.MAX_VALUE, 1, 20));
    }
}
