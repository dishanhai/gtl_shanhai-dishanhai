package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StellarRecoveryQueueTest {
    @Test void rejectedDeliveryRemainsAndSuccessIsNotRetried() {
        StellarRecoveryQueue<String> queue = new StellarRecoveryQueue<>();
        UUID id = UUID.randomUUID();
        queue.put(id, "receipt");
        assertEquals(0, queue.retry(1, (key, value) -> false));
        assertEquals("receipt", queue.get(id));
        AtomicInteger calls = new AtomicInteger();
        assertEquals(1, queue.retry(1, (key, value) -> { calls.incrementAndGet(); return true; }));
        assertEquals(0, queue.retry(1, (key, value) -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, calls.get());
        assertNull(queue.get(id));
    }

    @Test void failingReceiptDoesNotStarveTheNextReceipt() {
        StellarRecoveryQueue<String> queue = new StellarRecoveryQueue<>();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        queue.put(first, "blocked");
        queue.put(second, "ready");
        assertEquals(0, queue.retry(1, (key, value) -> { throw new IllegalStateException(); }));
        assertEquals(1, queue.retry(1, (key, value) -> true));
        assertEquals("blocked", queue.get(first));
        assertNull(queue.get(second));
    }
}
