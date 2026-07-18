package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientShopCatalogStateTest {

    @Test
    void revisionAndRequestIdRejectStaleOrDuplicateChunks() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog$State");
        Object state = type.getConstructor().newInstance();
        Method apply = type.getMethod("applyManifest", ShopCatalogManifest.class);
        Method request = type.getMethod("beginRequest", int.class);
        Method accept = type.getMethod("accept", long.class, long.class, int.class);
        ShopCatalogManifest revisionOne = manifest(1L);

        apply.invoke(state, revisionOne);
        long requestId = (long) request.invoke(state, 3);
        assertTrue(requestId > 0L);
        assertFalse((boolean) accept.invoke(state, 1L, requestId + 1L, 3));
        assertTrue((boolean) accept.invoke(state, 1L, requestId, 3));
        assertFalse((long) request.invoke(state, 3) > 0L);

        apply.invoke(state, revisionOne);
        assertFalse((long) request.invoke(state, 3) > 0L);

        apply.invoke(state, manifest(2L));
        assertTrue((long) request.invoke(state, 3) > 0L);
        assertFalse((boolean) accept.invoke(state, 1L, requestId, 3));
    }

    @Test
    void evictedChunkCanBeRequestedAgain() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog$State");
        Object state = type.getConstructor().newInstance();
        Method apply = type.getMethod("applyManifest", ShopCatalogManifest.class);
        Method request = type.getMethod("beginRequest", int.class);
        Method accept = type.getMethod("accept", long.class, long.class, int.class);
        Method forget = type.getMethod("forgetChunk", int.class);
        apply.invoke(state, manifest(1L));
        long requestId = (long) request.invoke(state, 3);
        assertTrue((boolean) accept.invoke(state, 1L, requestId, 3));

        forget.invoke(state, 3);

        assertTrue((long) request.invoke(state, 3) > 0L);
    }

    @Test
    void acceptsOnlyCurrentAbsoluteRemainingUsesWithoutIncreasing() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog$State");
        Object state = type.getConstructor().newInstance();
        Method applyManifest = type.getMethod("applyManifest", ShopCatalogManifest.class);
        Method applyRemaining = type.getMethod(
                "applyRemainingUses", long.class, long.class, long.class);
        Method remaining = type.getMethod("remainingUses", long.class);
        applyManifest.invoke(state, manifest(10L));

        assertFalse((boolean) applyRemaining.invoke(state, 9L, 0L, 8L));
        assertFalse((boolean) applyRemaining.invoke(state, 10L, -1L, 8L));
        assertFalse((boolean) applyRemaining.invoke(state, 10L, 0L, -1L));
        assertTrue((boolean) applyRemaining.invoke(state, 10L, 0L, 8L));
        assertFalse((boolean) applyRemaining.invoke(state, 10L, 0L, 9L));
        assertFalse((boolean) applyRemaining.invoke(state, 10L, 0L, 8L));
        assertTrue((boolean) applyRemaining.invoke(state, 10L, 0L, 3L));
        assertEquals(3L, remaining.invoke(state, 0L));
    }

    @Test
    void remainingStateCanPrecedeChunkAndSurvivesForgetUntilRevisionChanges() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog$State");
        Object state = type.getConstructor().newInstance();
        Method applyManifest = type.getMethod("applyManifest", ShopCatalogManifest.class);
        Method applyRemaining = type.getMethod(
                "applyRemainingUses", long.class, long.class, long.class);
        Method remaining = type.getMethod("remainingUses", long.class);
        Method request = type.getMethod("beginRequest", int.class);
        Method accept = type.getMethod("accept", long.class, long.class, int.class);
        Method forget = type.getMethod("forgetChunk", int.class);
        applyManifest.invoke(state, manifest(20L));

        assertTrue((boolean) applyRemaining.invoke(state, 20L, 0L, 4L));
        long requestId = (long) request.invoke(state, 3);
        assertTrue((boolean) accept.invoke(state, 20L, requestId, 3));
        forget.invoke(state, 3);
        assertEquals(4L, remaining.invoke(state, 0L));

        assertFalse((boolean) applyManifest.invoke(state, manifest(20L)));
        assertEquals(4L, remaining.invoke(state, 0L));
        assertTrue((boolean) applyManifest.invoke(state, manifest(21L)));
        assertEquals(-1L, remaining.invoke(state, 0L));
    }

    @Test
    void sameRevisionReadyChangesPreserveRemainingState() throws Exception {
        Class<?> type = Class.forName(
                "com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog$State");
        Object state = type.getConstructor().newInstance();
        Method applyManifest = type.getMethod("applyManifest", ShopCatalogManifest.class);
        Method applyRemaining = type.getMethod(
                "applyRemainingUses", long.class, long.class, long.class);
        Method remaining = type.getMethod("remainingUses", long.class);
        applyManifest.invoke(state, manifest(30L, true));
        assertTrue((boolean) applyRemaining.invoke(state, 30L, 0L, 4L));

        assertTrue((boolean) applyManifest.invoke(state, manifest(30L, false)));
        assertEquals(4L, remaining.invoke(state, 0L));
        assertTrue((boolean) applyManifest.invoke(state, manifest(30L, true)));
        assertEquals(4L, remaining.invoke(state, 0L));
    }

    private static ShopCatalogManifest manifest(long revision) {
        return manifest(revision, true);
    }

    private static ShopCatalogManifest manifest(long revision, boolean ready) {
        return new ShopCatalogManifest(revision, ready, List.of(
                new ShopCatalogManifest.Stub(0L, "杂货", "", "", "", false,
                        3, "", "", List.of("minecraft:stone"), "stable-0")), java.util.Map.of());
    }
}
