package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static ShopCatalogManifest manifest(long revision) {
        return new ShopCatalogManifest(revision, true, List.of(
                new ShopCatalogManifest.Stub(0L, "杂货", "", false,
                        3, "", "", List.of("minecraft:stone"))));
    }
}
