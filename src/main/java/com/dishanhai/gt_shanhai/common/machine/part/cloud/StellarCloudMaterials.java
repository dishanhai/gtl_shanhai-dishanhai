package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 山海：虛擬在場量不屬於實料；退料載體交付成功後才扣除來源快照。 */
public final class StellarCloudMaterials {
    private StellarCloudMaterials() {}
    public static <K> boolean hasPhysical(Object2LongMap<K> inventory, Object2LongMap<K> virtual, K legacyCircuit) {
        for (Object2LongMap.Entry<K> entry : inventory.object2LongEntrySet()) {
            long target = Math.max(0, virtual.getLong(entry.getKey()));
            if (Objects.equals(legacyCircuit, entry.getKey()) && !virtual.containsKey(entry.getKey())) target = Math.max(1, target);
            if (entry.getLongValue() > target) return true;
        }
        return false;
    }
    public static <K> boolean deliverRefund(Map<K, Long> buffer, Function<Map<K, BigInteger>, Boolean> sink) {
        Map<K, BigInteger> snapshot = new LinkedHashMap<>();
        buffer.forEach((key, amount) -> { if (amount != null && amount > 0) snapshot.put(key, BigInteger.valueOf(amount)); });
        if (snapshot.isEmpty()) return true;
        if (!Boolean.TRUE.equals(sink.apply(Map.copyOf(snapshot)))) return false;
        snapshot.forEach((key, amount) -> buffer.computeIfPresent(key, (ignored, current) -> {
            long remaining = current - amount.longValueExact(); return remaining > 0 ? remaining : null;
        }));
        return true;
    }
}
