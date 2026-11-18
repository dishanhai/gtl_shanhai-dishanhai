package com.dishanhai.gt_shanhai.common.machine.part;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Pending receipts remain queued until the delivery operation confirms success. */
public final class StellarRecoveryQueue<T> {
    private final Map<UUID, T> pending = new LinkedHashMap<>();

    public void put(UUID id, T receipt) { pending.put(id, receipt); }
    public T get(UUID id) { return pending.get(id); }
    public Map<UUID, T> snapshot() { return Map.copyOf(pending); }

    public int retry(int budget, BiPredicate<UUID, T> deliver) {
        int delivered = 0;
        if (budget <= 0) return 0;
        var ids = new ArrayList<UUID>(Math.min(budget, pending.size()));
        for (UUID id : pending.keySet()) {
            if (ids.size() >= budget) break;
            ids.add(id);
        }
        for (UUID id : ids) {
            T receipt = pending.remove(id);
            try {
                if (deliver.test(id, receipt)) delivered++;
                else pending.put(id, receipt);
            } catch (RuntimeException failure) {
                pending.put(id, receipt);
            }
        }
        return delivered;
    }
}
