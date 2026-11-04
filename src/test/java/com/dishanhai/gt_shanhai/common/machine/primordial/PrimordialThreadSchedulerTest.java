package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrimordialThreadSchedulerTest {
    private final PrimordialThreadScheduler<Task> scheduler = new PrimordialThreadScheduler<>();
    private final ArrayDeque<Task> inputs = new ArrayDeque<>();
    private final List<String> completed = new ArrayList<>();
    private int searches;
    private final PrimordialThreadScheduler.Runner<Task> runner = new PrimordialThreadScheduler.Runner<>() {
        public boolean advance(Task task) {
            if (--task.remaining > 0) return false;
            completed.add(task.id);
            return true;
        }
        public Task start(long allowance) {
            searches++;
            return inputs.poll();
        }
    };

    @Test void laterShortBatchCompletesWhileTheFirstIsStillRunning() {
        inputs.add(new Task("A", 100));
        tick(0, 64, 4);
        inputs.add(new Task("B", 2));
        scheduler.requestSearch();
        tick(5, 64, 4);
        tick(6, 64, 4);
        tick(7, 64, 4);
        assertEquals(List.of("B"), completed);
        assertEquals(1, scheduler.lanes().size());
        assertEquals("A", scheduler.lanes().getFirst().task().id);
        assertEquals(16, scheduler.lanes().getFirst().reserved());
    }

    @Test void reservationsPartitionCapacityExactlyIncludingTheRemainder() {
        for (int i = 0; i < 4; i++) inputs.add(new Task("" + i, 100));
        for (int t = 0; t <= 15; t += 5) tick(t, 65, 4);
        assertEquals(List.of(17L, 16L, 16L, 16L),
                scheduler.lanes().stream().map(PrimordialThreadScheduler.Lane::reserved).toList());
        assertEquals(65L, scheduler.reserved());
        int before = searches;
        scheduler.requestSearch();
        tick(20, 65, 4);
        assertEquals(before, searches, "額度滿時不可查配方");
    }

    @Test void emptyLanesShareOneLazySearchAndNotificationCannotBypassTheCadence() {
        tick(0, 64, 64);
        for (int t = 1; t < 20; t++) tick(t, 64, 64);
        assertEquals(1, searches);
        tick(20, 64, 64);
        assertEquals(2, searches);
        inputs.add(new Task("push", 40));
        scheduler.requestSearch();
        tick(21, 64, 64);
        assertEquals(2, searches);
        tick(25, 64, 64);
        assertEquals(3, searches);
        assertEquals(1, scheduler.lanes().size());
    }

    @Test void removingThreadsDoesNotCancelOrOverbookPaidBatches() {
        inputs.add(new Task("A", 100));
        inputs.add(new Task("B", 100));
        tick(0, 64, 4);
        tick(5, 64, 4);
        inputs.add(new Task("C", 100));
        scheduler.requestSearch();
        tick(10, 64, 1);
        assertEquals(2, scheduler.lanes().size());
        assertEquals(2, searches);
        assertTrue(scheduler.lanes().stream().allMatch(lane -> lane.task().remaining < 100));
    }

    @Test void hugeThreadCountsAllocateOnlyRunningLanesAndNeverOverflow() {
        inputs.add(new Task("A", 100));
        tick(0, Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(1, scheduler.lanes().size());
        assertEquals(1L, scheduler.reserved());
        assertEquals(Long.MAX_VALUE, PrimordialThreadScheduler.threadCount(Long.MAX_VALUE));
        assertEquals(1L, PrimordialThreadScheduler.threadCount(0));
        assertEquals(4L, PrimordialThreadScheduler.threadCount(3));
    }

    @Test void zeroCapacityDoesNotSearchOrAllocate() {
        inputs.add(new Task("A", 100));
        tick(0, 0, 4);
        assertEquals(0, searches);
        assertTrue(scheduler.lanes().isEmpty());
    }

    @Test void disablingFreezesPaidBatchesAndInputRequestsUntilExplicitResume() {
        Task first = new Task("A", 100);
        inputs.add(first);
        tick(0, 64, 4);
        scheduler.setEnabled(false);
        scheduler.requestSearch();
        inputs.add(new Task("B", 2));
        tick(5, 64, 4);
        assertEquals(100, first.remaining);
        assertEquals(1, searches);
        assertFalse(scheduler.isEnabled());
        scheduler.setEnabled(true);
        tick(10, 64, 4);
        assertEquals(99, first.remaining);
        assertEquals(2, searches);
    }

    private void tick(long now, long capacity, long threads) {
        scheduler.tick(now, capacity, threads, runner);
    }
    private static final class Task {
        final String id;
        int remaining;
        Task(String id, int remaining) { this.id = id; this.remaining = remaining; }
    }
}
