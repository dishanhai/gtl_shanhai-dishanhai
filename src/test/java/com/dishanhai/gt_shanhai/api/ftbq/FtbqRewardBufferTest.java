package com.dishanhai.gt_shanhai.api.ftbq;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FtbqRewardBufferTest {
    @Test
    void sharedCapacityRetainsRemainderAndRetriesWithoutDuplicating() {
        var buffer = new FtbqRewardBuffer<String>();
        buffer.add("iron", 64);
        buffer.add("gold", 64);
        AtomicLong capacity = new AtomicLong(80);
        long first = buffer.flush((key, amount) -> {
            long accepted = Math.min(capacity.get(), amount);
            capacity.addAndGet(-accepted);
            return accepted;
        }, () -> {});
        assertEquals(80, first);
        assertEquals(Map.of("gold", 48L), buffer.snapshot());
        capacity.set(100);
        assertEquals(48, buffer.flush((key, amount) -> amount, () -> {}));
        assertTrue(buffer.isEmpty());
    }

    @Test
    void fullNetworkDoesNotChangeOrDirtyOutbox() {
        var buffer = new FtbqRewardBuffer<String>();
        buffer.add("iron", 64);
        assertEquals(0, buffer.flush((key, amount) -> 0, () -> fail("No mutation")));
        assertEquals(Map.of("iron", 64L), buffer.snapshot());
    }

    @Test
    void duplicateStacksShareOneLongAmount() {
        var buffer = new FtbqRewardBuffer<String>();
        buffer.add("iron", Integer.MAX_VALUE);
        buffer.add("iron", Integer.MAX_VALUE);
        assertEquals(2L * Integer.MAX_VALUE, buffer.flush((key, amount) -> amount, () -> {}));
    }

    @Test
    void snapshotRoundTripRestoresOnlyUndeliveredItems() {
        var buffer = new FtbqRewardBuffer<String>();
        buffer.add("iron", 64);
        buffer.flush((key, amount) -> 12, () -> {});
        var restored = new FtbqRewardBuffer<String>();
        buffer.snapshot().forEach(restored::add);
        assertEquals(52, restored.flush((key, amount) -> amount, () -> {}));
        assertTrue(restored.isEmpty());
    }

    @Test
    void storageFailurePreservesUndeliveredRemainder() {
        var buffer = new FtbqRewardBuffer<String>();
        buffer.add("iron", 64);
        buffer.add("gold", 32);
        assertThrows(IllegalStateException.class, () -> buffer.flush((key, amount) -> {
            if (key.equals("gold")) throw new IllegalStateException("offline");
            return amount;
        }, () -> {}));
        assertEquals(Map.of("gold", 32L), buffer.snapshot());
    }

    @Test
    void invalidAmountsCannotCorruptOutbox() {
        var buffer = new FtbqRewardBuffer<String>();
        assertThrows(IllegalArgumentException.class, () -> buffer.add("iron", 0));
        buffer.add("iron", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> buffer.add("iron", 1));
        assertEquals(Long.MAX_VALUE, buffer.snapshot().get("iron"));
    }
}
