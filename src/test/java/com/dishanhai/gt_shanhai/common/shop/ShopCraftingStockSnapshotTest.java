package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ShopCraftingStockSnapshotTest {
    @Test void eachPositiveKeyIsQueriedOnceAndAllLaterReadsAreLocal() {
        var calls = new AtomicInteger();
        var cached = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 1000; i++) cached.put("key" + i, 100L);
        var snapshot = ShopCraftingStockSnapshot.capture(cached, (key, amount) -> {
            calls.incrementAndGet();
            assertEquals(100L, amount);
            return 80L;
        });
        for (int plan = 0; plan < 256; plan++) {
            for (int read = 0; read < 1000; read++) {
                assertEquals(70L, ShopAutoCraftAmounts.remainingStock(snapshot.get("key" + read), 10));
            }
        }
        assertEquals(1000, calls.get(), "One session snapshot, not one network query per search/plan");
    }

    @Test void snapshotDoesNotFollowLiveChangesAndANewAttemptRefreshesIt() {
        var live = new LinkedHashMap<>(Map.of("item:nbtA", 30L, "item:nbtB", 40L, "fluid:water", 1000L));
        var first = ShopCraftingStockSnapshot.capture(live, (key, amount) -> amount);
        live.put("item:nbtA", 2L);
        assertEquals(30L, first.get("item:nbtA"));
        assertEquals(2L, ShopCraftingStockSnapshot.capture(live, (key, amount) -> amount).get("item:nbtA"));
        assertEquals(40L, first.get("item:nbtB"));
        assertEquals(1000L, first.get("fluid:water"));
        assertThrows(UnsupportedOperationException.class, () -> first.put("extra", 1L));
    }

    @Test void boundedQueriesRespectPermissionsZeroAndLongMax() {
        var calls = new AtomicInteger();
        var snapshot = ShopCraftingStockSnapshot.capture(Map.of("zero", 0L, "negative", -1L,
                "denied", 5L, "large", Long.MAX_VALUE, "overshoot", 10L), (key, amount) -> {
            calls.incrementAndGet();
            return key.equals("denied") ? -1 : Long.MAX_VALUE;
        });
        assertEquals(3, calls.get());
        assertEquals(Map.of("large", Long.MAX_VALUE, "overshoot", 10L), snapshot);
        assertEquals(0L, ShopAutoCraftAmounts.remainingStock(Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
