package com.dishanhai.gt_shanhai.client;

import appeng.api.stacks.AEKey;

import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingStatus;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** 客户端按悬浮物品缓存量子 CPU 阻塞状态，避免 tooltip 每帧发包。 */
public final class QuantumCraftingStatusClientCache {

    private static final long REQUEST_INTERVAL_NANOS = 1_000_000_000L;
    private static final Map<AEKey, QuantumCraftingStatus> CACHE = new HashMap<>();
    private static final Map<AEKey, Long> LAST_REQUEST_NANOS = new HashMap<>();

    private QuantumCraftingStatusClientCache() {}

    public static boolean shouldRequest(@Nullable AEKey key) {
        if (key == null) return false;
        long now = System.nanoTime();
        Long last = LAST_REQUEST_NANOS.get(key);
        if (last != null && now - last < REQUEST_INTERVAL_NANOS) return false;
        LAST_REQUEST_NANOS.put(key, now);
        return true;
    }

    public static void put(AEKey key, QuantumCraftingStatus status) {
        CACHE.put(key, status);
    }

    @Nullable
    public static QuantumCraftingStatus get(@Nullable AEKey key) {
        return key == null ? null : CACHE.get(key);
    }
}
