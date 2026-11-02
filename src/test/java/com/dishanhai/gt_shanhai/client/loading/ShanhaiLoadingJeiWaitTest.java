package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ShanhaiLoadingJeiWaitTest {
    @Test void pendingIndexGetsRepeatedPumpSlicesWithoutResubmittingTask() throws Exception {
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var pumps = new AtomicInteger();
        var task = ForkJoinTask.adapt((Callable<Integer>) () -> {
            calls.incrementAndGet();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("pump did not release worker");
            return 42;
        });
        try (var pool = new ForkJoinPool(1)) {
            pool.execute(task);
            assertEquals(42, ShanhaiLoadingAsync.await(task, () -> {
                if (pumps.incrementAndGet() >= 2) release.countDown();
            }));
            assertTrue(pumps.get() >= 2); assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void completedCancelledAndFailedTasksKeepGetSemantics() throws Exception {
        var done = ForkJoinTask.adapt((Callable<Integer>) () -> 7); done.invoke();
        assertEquals(7, ShanhaiLoadingAsync.await(done, () -> fail("completed task should not pump")));
        var cancelled = ForkJoinTask.adapt(() -> {}); cancelled.cancel(false);
        assertThrows(CancellationException.class, () -> ShanhaiLoadingAsync.await(cancelled, () -> fail()));
        var failed = ForkJoinTask.adapt(() -> { throw new IllegalArgumentException("bad index"); });
        failed.quietlyInvoke();
        assertThrows(ExecutionException.class, () -> ShanhaiLoadingAsync.await(failed, () -> fail()));
    }

    @Test void interruptionIsPropagatedWithoutWrappingOrResubmission() {
        var pending = ForkJoinTask.adapt(() -> {});
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> ShanhaiLoadingAsync.await(pending, () -> {}));
        } finally { Thread.interrupted(); }
    }
}
