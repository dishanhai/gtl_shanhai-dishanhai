package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：以真正執行緒驗證非同步資料重載，無需初始化遊戲。 */
class ShanhaiLoadingAsyncTest {
    @Test void initializationAndApplyRunInOrderOffCallerWithItsContextLoader() throws Exception {
        Thread caller = Thread.currentThread();
        ClassLoader loader = caller.getContextClassLoader();
        List<Integer> order = new ArrayList<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        var future = ShanhaiLoadingAsync.start((prepare, apply) -> {
            worker.set(Thread.currentThread());
            assertNotSame(caller, worker.get());
            assertSame(loader, worker.get().getContextClassLoader());
            assertTrue(worker.get().isDaemon());
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (int i = 0; i < 32; i++) {
                int value = i;
                chain = chain.thenRunAsync(() -> {
                    assertSame(worker.get(), Thread.currentThread()); order.add(value);
                }, apply);
            }
            return chain.thenApply(ignored -> List.copyOf(order));
        }, Runnable::run, task -> fail("must not run data apply on caller executor"), true);
        assertEquals(java.util.stream.IntStream.range(0, 32).boxed().toList(), future.get(5, TimeUnit.SECONDS));
    }

    @Test void slowInitializerReturnsFutureWithoutBlockingCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        try {
            var result = ShanhaiLoadingAsync.start((p, a) -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                return CompletableFuture.completedFuture(7);
            }, Runnable::run, Runnable::run, true);
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(result.isDone());
            release.countDown(); assertEquals(7, result.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @Test void failurePropagatesOnceWithoutRetryingMutatingLoad() {
        RuntimeException failure = new RuntimeException("invalid data pack");
        AtomicInteger calls = new AtomicInteger();
        var result = ShanhaiLoadingAsync.start((p, a) -> { calls.incrementAndGet(); throw failure; },
                Runnable::run, Runnable::run, true);
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(1, calls.get());
    }

    @Test void compatibilityModeKeepsExecutorsThreadAndFutureIdentity() {
        Executor prepare = Runnable::run, apply = Runnable::run;
        Thread caller = Thread.currentThread();
        var expected = CompletableFuture.completedFuture("unchanged");
        assertSame(expected, ShanhaiLoadingAsync.start((p, a) -> {
            assertSame(prepare, p); assertSame(apply, a); assertSame(caller, Thread.currentThread());
            return expected;
        }, prepare, apply, false));
    }

    @Test void workerCanAwaitMainThreadWorkWhileCallerPumpsQueue() throws Exception {
        BlockingQueue<Runnable> mainTasks = new LinkedBlockingQueue<>();
        Thread caller = Thread.currentThread();
        var result = ShanhaiLoadingAsync.start((p, a) -> CompletableFuture.supplyAsync(() -> {
            var callback = new CompletableFuture<Integer>();
            mainTasks.add(() -> { assertSame(caller, Thread.currentThread()); callback.complete(42); });
            return callback.join();
        }, a), Runnable::run, mainTasks::add, true);
        Runnable callback = mainTasks.poll(5, TimeUnit.SECONDS); assertNotNull(callback);
        callback.run(); assertEquals(42, result.get(5, TimeUnit.SECONDS));
    }

    @Test void cancellingWaiterDoesNotStrandInFlightPipelineOrItsCleanup() throws Exception {
        var trigger = new CompletableFuture<Void>();
        var initialized = new CountDownLatch(1);
        var pipeline = new AtomicReference<CompletableFuture<Void>>();
        var result = ShanhaiLoadingAsync.start((p, a) -> {
            var original = trigger.thenRunAsync(() -> {}, a);
            pipeline.set(original); initialized.countDown(); return original;
        }, Runnable::run, Runnable::run, true);
        assertTrue(initialized.await(5, TimeUnit.SECONDS));
        assertTrue(result.cancel(false)); trigger.complete(null);
        assertDoesNotThrow(() -> pipeline.get().get(5, TimeUnit.SECONDS));
        assertTrue(result.isCancelled());
    }
}
