package com.dishanhai.gt_shanhai.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Partition a complete lookup snapshot without confusing the packet limit with recipe deletion. */
public final class RecipeSyncBatcher {
    private RecipeSyncBatcher() {}

    public record Batch<T>(Map<String, T> updates, List<String> removed) {
        public Batch {
            updates = Map.copyOf(updates);
            removed = List.copyOf(removed);
        }
    }

    public static <T> List<Batch<T>> partition(Collection<String> requested, Map<String, T> available, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Batch limit must be positive");
        List<Batch<T>> batches = new ArrayList<>();
        Map<String, T> updates = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        for (String id : new LinkedHashSet<>(requested)) {
            if (id == null || id.isEmpty()) continue;
            T recipe = available.get(id);
            if (recipe == null) removed.add(id);
            else updates.put(id, recipe);
            if (updates.size() + removed.size() == limit) {
                batches.add(new Batch<>(updates, removed));
                updates.clear();
                removed.clear();
            }
        }
        if (!updates.isEmpty() || !removed.isEmpty()) batches.add(new Batch<>(updates, removed));
        return List.copyOf(batches);
    }
}
