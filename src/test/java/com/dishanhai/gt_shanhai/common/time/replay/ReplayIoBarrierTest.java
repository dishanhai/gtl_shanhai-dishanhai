// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayIoBarrierTest {
    @Test void acceptsOnlyWhenAllWorkersCompleteSuccessfully() {
        assertTrue(ReplayIoBarrier.drain(List.of(CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(null)), 1000));
        assertFalse(ReplayIoBarrier.drain(List.of(CompletableFuture.failedFuture(new RuntimeException())), 1000));
        assertFalse(ReplayIoBarrier.drain(List.of(new CompletableFuture<>()), 10));
        assertFalse(ReplayIoBarrier.drain(List.of(), 1000));
    }
}
