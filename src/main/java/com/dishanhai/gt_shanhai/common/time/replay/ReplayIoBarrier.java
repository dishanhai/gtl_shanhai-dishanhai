// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Waits for every required storage worker; exceptions and timeouts fail closed. */
public final class ReplayIoBarrier {
    private ReplayIoBarrier() {}
    public static boolean drain(List<? extends CompletableFuture<?>> futures, long timeoutMillis) {
        if (futures == null || futures.isEmpty() || timeoutMillis <= 0) return false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            for (CompletableFuture<?> future : futures) {
                if (future == null) return false;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                future.get(remaining, TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failed) {
            return false;
        }
    }
}
