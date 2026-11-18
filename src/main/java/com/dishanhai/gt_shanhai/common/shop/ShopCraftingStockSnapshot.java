package com.dishanhai.gt_shanhai.common.shop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongBiFunction;

/** One planning attempt's frozen stock; never a reservation or a commit authorization. */
public final class ShopCraftingStockSnapshot {
    private ShopCraftingStockSnapshot() {}

    /** Invoke on the owning server thread. Each positive cached key is queried exactly once. */
    public static <K> Map<K, Long> capture(Map<K, Long> cached, ToLongBiFunction<K, Long> extractable) {
        Map<K, Long> snapshot = new LinkedHashMap<>();
        cached.forEach((key, count) -> {
            if (count <= 0) return;
            long available = Math.min(count, Math.max(0, extractable.applyAsLong(key, count)));
            if (available > 0) snapshot.put(key, available);
        });
        return Map.copyOf(snapshot);
    }
}
