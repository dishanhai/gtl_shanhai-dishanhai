package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrimordialThreadSchedulerTest {
    @Test void readonlyAdmissionShareIncludesDowngradedReservationsAndRoundRemainders() {
        var scheduler = new PrimordialThreadScheduler<String>();
        scheduler.restore(0, 25, "a"); scheduler.restore(1, 25, "b"); scheduler.restore(2, 25, "c");
        assertEquals(15, scheduler.admissionShare(90, 4));
        assertEquals(25, scheduler.admissionShare(100, 4));
        assertEquals(3, scheduler.lanes().size()); assertEquals(75, scheduler.reserved());
    }

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

    @Test void fullySuppliedThreadsShareTheAvailableParallelEqually() {
        var actual = startAvailableParallels(64L, 4L, 64L, 64L, 64L, 64L);
        assertEquals(List.of(16L, 16L, 16L, 16L),
                actual.lanes().stream().map(PrimordialThreadScheduler.Lane::reserved).toList());
        assertEquals(64L, actual.reserved());
        assertEquals(4, actual.activeThreads());
    }

    @Test void undersuppliedThreadsReserveTheirAvailableParallelAndLeaveTheRestForOtherThreads() {
        var actual = startAvailableParallels(64L, 4L, 3L, 5L, 64L, 64L);
        assertEquals(List.of(3L, 5L, 28L, 28L),
                actual.lanes().stream().map(PrimordialThreadScheduler.Lane::reserved).toList());
        assertEquals(64L, actual.reserved());
        assertEquals(4, actual.activeThreads());
    }

    @Test void parallelBelowTheThreadCountStartsOnlyThreadsWithAnAvailableParallel() {
        var actual = startAvailableParallels(2L, 4L, 64L, 64L, 64L, 64L);
        assertEquals(List.of(1L, 1L),
                actual.lanes().stream().map(PrimordialThreadScheduler.Lane::reserved).toList());
        assertEquals(2L, actual.reserved());
        assertEquals(2, actual.activeThreads());
    }

    @Test void anUndersuppliedSingleThreadDoesNotCreateExtraThreadsFromUnusedParallel() {
        var actual = startAvailableParallels(64L, 1L, 3L, 64L, 64L);
        assertEquals(List.of(3L),
                actual.lanes().stream().map(PrimordialThreadScheduler.Lane::reserved).toList());
        assertEquals(3L, actual.reserved());
        assertEquals(1, actual.activeThreads());
    }

    @Test void untrackedParallelShortageUsesTheRemainderWithoutChangingPaidReservations() {
        scheduler.restore(0L, 32L, new Task("paid", 100));
        inputs.add(new Task("remainder", 100));

        tick(0L, 40L, 4L);

        assertEquals(List.of(32L, 8L),
                scheduler.lanes().stream().map(PrimordialThreadScheduler.Lane::reserved).toList());
        assertEquals(40L, scheduler.reserved());
        assertEquals(2, scheduler.activeThreads());
        assertEquals(1, searches);
        assertEquals(99, scheduler.lanes().getFirst().task().remaining);
    }

    @Test void aSinglePaidThreadCannotUseItsRemainderToCreateAnotherThread() {
        scheduler.restore(0L, 3L, new Task("paid", 100));
        inputs.add(new Task("extra", 100));

        tick(0L, 64L, 1L);

        assertEquals(1, scheduler.activeThreads());
        assertEquals(3L, scheduler.reserved());
        assertEquals(0, searches);
        assertEquals(1, inputs.size());
    }

    @Test void emptyLanesShareOneLazySearchAndNoisyNotificationCannotBypassTheCadence() {
        tick(0, 64, 64);
        for (int t = 1; t < 20; t++) tick(t, 64, 64);
        assertEquals(1, searches);
        tick(20, 64, 64);
        assertEquals(2, searches);
        inputs.add(new Task("push", 40));
        scheduler.requestRetry();
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

    @Test void explicitPushBypassesFailedSearchBackoffWhileExistingBatchKeepsRunning() {
        inputs.add(new Task("long", 100));
        tick(0, 64, 4);
        tick(1, 64, 4); // 無其他配方，進入失敗退避。
        inputs.add(new Task("pushed", 2));
        scheduler.requestSearch();
        tick(2, 64, 4);
        assertEquals(List.of("long", "pushed"),
                scheduler.lanes().stream().map(lane -> lane.task().id).toList());
        assertEquals(98, scheduler.lanes().getFirst().task().remaining);
        assertEquals(3, searches);
        scheduler.requestSearch();
        tick(2, 64, 4);
        assertEquals(3, searches, "同 tick 最多接單一次");
        tick(3, 64, 4);
        tick(4, 64, 4);
        assertEquals(List.of("pushed"), completed);
    }

    @Test void hugeThreadCountsAllocateOnlyRunningLanesAndNeverOverflow() {
        inputs.add(new Task("A", 100));
        tick(0, 64L, Long.MAX_VALUE);
        assertEquals(1, scheduler.lanes().size());
        assertEquals(1L, scheduler.reserved());
        assertEquals(Long.MAX_VALUE, PrimordialThreadScheduler.threadCount(Long.MAX_VALUE));
        assertEquals(1L, PrimordialThreadScheduler.threadCount(0));
        assertEquals(4L, PrimordialThreadScheduler.threadCount(3));
    }

    @Test void unlimitedThreadsCanUseTheRemainingUnlimitedParallelBudget() {
        scheduler.restore(0L, 64L, new Task("paid", 100));

        assertEquals(Long.MAX_VALUE - 64L, scheduler.admissionShare(Long.MAX_VALUE, Long.MAX_VALUE, true));
        assertEquals(64L, scheduler.admissionShare(128L, Long.MAX_VALUE, true));

        scheduler.restore(1L, Long.MAX_VALUE - 64L, new Task("full", 100));
        assertEquals(0L, scheduler.admissionShare(Long.MAX_VALUE, Long.MAX_VALUE, true));
    }

    @Test void defaultUnlimitedSchedulerKeepsIndependentSlotsWithoutActualParallelData() {
        inputs.add(new Task("first", 100));
        inputs.add(new Task("second", 100));

        tick(0, Long.MAX_VALUE, Long.MAX_VALUE);
        tick(1, Long.MAX_VALUE, Long.MAX_VALUE);

        assertEquals(2, scheduler.lanes().size());
        assertEquals(2L, scheduler.reserved());
    }

    @Test void reservationsRecoverFromSaturationAsIndependentBatchesFinish() {
        scheduler.restore(0, 7, new Task("survivor", 3));
        scheduler.restore(1, Long.MAX_VALUE, new Task("huge", 2));
        scheduler.restore(2, 1, new Task("small", 1));

        assertEquals(Long.MAX_VALUE, scheduler.reserved());
        tick(0, 0, 4);
        assertEquals(Long.MAX_VALUE, scheduler.reserved());
        tick(1, 0, 4);
        assertEquals(7L, scheduler.reserved());
        assertEquals(3L, scheduler.admissionShare(10, 2, true));
        tick(2, 0, 4);
        assertEquals(0L, scheduler.reserved());
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

    private static PrimordialThreadScheduler<Long> startAvailableParallels(
            long capacity, long threads, long... available) {
        var actual = new PrimordialThreadScheduler<Long>();
        var pending = new ArrayDeque<Long>();
        for (long parallel : available) pending.add(parallel);
        actual.tick(0L, capacity, threads, true, new PrimordialThreadScheduler.Runner<>() {
            @Override public boolean advance(Long parallel) { return false; }
            @Override public Long start(long allowance) {
                return pending.isEmpty() ? null : Math.min(pending.removeFirst(), allowance);
            }
            @Override public long reservation(Long parallel, long allowance) { return parallel; }
            @Override public int admissionLimit() { return 96; }
        });
        return actual;
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
