package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogRateLimitStateTest {

    @Test
    void staleManifestAllowsOncePerRevisionPairAtExactBoundary() throws Exception {
        Object state = newState();
        long start = 1_000_000_000L;

        assertTrue(allowManifest(state, 10L, 9L, start));
        assertFalse(allowManifest(state, 10L, 9L, start + 499_999_999L));
        assertTrue(allowManifest(state, 10L, 9L, start + 500_000_000L));
        assertFalse(allowManifest(state, 10L, 9L, start + 500_000_000L));
    }

    @Test
    void revisionPairChangesAndClockRollbackAllowImmediately() throws Exception {
        Object state = newState();
        long start = 1_000_000_000L;

        assertTrue(allowManifest(state, 10L, 9L, start));
        assertFalse(allowManifest(state, 10L, 9L, start + 1L));
        assertTrue(allowManifest(state, 11L, 9L, start + 1L));
        assertTrue(allowManifest(state, 11L, 8L, start + 1L));
        assertFalse(allowManifest(state, 11L, 8L, start + 2L));
        assertTrue(allowManifest(state, 11L, 8L, 5L));
        assertFalse(allowManifest(state, 11L, 8L, 6L));
    }

    @Test
    void chunkWindowAllowsExactlyOneHundredTwentyEightRequestsPerSecond() throws Exception {
        Object state = newState();
        Method allowChunk = state.getClass().getMethod("allowChunk", long.class);
        long start = 2_000_000_000L;

        for (int i = 0; i < 128; i++) {
            assertTrue((boolean) allowChunk.invoke(state, start));
        }
        assertFalse((boolean) allowChunk.invoke(state, start));
        assertFalse((boolean) allowChunk.invoke(state, start + 999_999_999L));
        assertTrue((boolean) allowChunk.invoke(state, start + 1_000_000_000L));
        assertTrue((boolean) allowChunk.invoke(state, start - 1L));
    }

    private static Object newState() throws Exception {
        return Class.forName(
                "com.dishanhai.gt_shanhai.network.ShopCatalogManifestPacket$RateLimitState")
                .getConstructor().newInstance();
    }

    private static boolean allowManifest(Object state, long serverRevision,
                                         long clientRevision, long nowNanos) throws Exception {
        Method method = state.getClass().getMethod(
                "allowManifest", long.class, long.class, long.class);
        return (boolean) method.invoke(state, serverRevision, clientRevision, nowNanos);
    }
}
