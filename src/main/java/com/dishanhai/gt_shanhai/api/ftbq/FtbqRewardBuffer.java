package com.dishanhai.gt_shanhai.api.ftbq;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongBiFunction;

/** Claimed rewards waiting for real storage insertion. Simulation does not reserve capacity. */
public final class FtbqRewardBuffer<K> {
    private final Map<K, Long> pending = new LinkedHashMap<>();

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public Map<K, Long> snapshot() {
        return new LinkedHashMap<>(pending);
    }

    public void clear() {
        pending.clear();
    }

    public void add(K key, long amount) {
        if (key == null || amount <= 0L) throw new IllegalArgumentException("Invalid reward amount/key");
        pending.merge(key, amount, Math::addExact);
    }

    public long flush(ToLongBiFunction<K, Long> insert, Runnable changed) {
        long insertedTotal = 0L;
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long amount = entry.getValue();
            long inserted = insert.applyAsLong(entry.getKey(), amount);
            if (inserted < 0L || inserted > amount) throw new IllegalStateException("Invalid storage insertion result");
            if (inserted == 0L) continue;
            if (inserted == amount) iterator.remove();
            else entry.setValue(amount - inserted);
            changed.run();
            insertedTotal = Math.addExact(insertedTotal, inserted);
        }
        return insertedTotal;
    }
}
