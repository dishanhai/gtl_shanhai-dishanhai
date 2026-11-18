package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StellarOverflowBufferTest {
    @Test void overflowIsPreservedAndOnlyAcceptedAmountIsRemoved() {
        Map<String, BigInteger> overflow = new HashMap<>();
        long stored = StellarOverflowBuffer.store(overflow, "iron", Long.MAX_VALUE - 2, 5);
        assertEquals(Long.MAX_VALUE, stored);
        assertEquals(BigInteger.valueOf(3), overflow.get("iron"));
        assertEquals(2, StellarOverflowBuffer.flush(overflow, 1, (key, amount) -> 2));
        assertEquals(BigInteger.ONE, overflow.get("iron"));
        assertEquals(0, StellarOverflowBuffer.flush(overflow, 1, (key, amount) -> 0));
        assertEquals(BigInteger.ONE, overflow.get("iron"));
        assertEquals(1, StellarOverflowBuffer.flush(overflow, 1, (key, amount) -> amount));
        assertTrue(overflow.isEmpty());
    }

    @Test void blockedKeyDoesNotStarveOtherKeys() {
        Map<String, BigInteger> overflow = new java.util.LinkedHashMap<>();
        overflow.put("blocked", BigInteger.ONE);
        overflow.put("ready", BigInteger.ONE);
        assertEquals(0, StellarOverflowBuffer.flush(overflow, 1, (key, amount) -> 0));
        assertEquals(1, StellarOverflowBuffer.flush(overflow, 1, (key, amount) -> amount));
        assertEquals(BigInteger.ONE, overflow.get("blocked"));
        assertFalse(overflow.containsKey("ready"));
    }
}
