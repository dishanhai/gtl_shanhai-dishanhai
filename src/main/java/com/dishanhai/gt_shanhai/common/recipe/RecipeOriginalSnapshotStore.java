package com.dishanhai.gt_shanhai.common.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;

/**
 * Stores the first unmodified copy of each runtime recipe.
 *
 * <p>Rule application must always start from this store. Returning fresh copies prevents
 * strip/replace/delete operations from accumulating across rebuilds.</p>
 */
public final class RecipeOriginalSnapshotStore {

    private static final Map<String, LinkedHashMap<String, GTRecipe>> SNAPSHOTS = new LinkedHashMap<>();
    private static final Map<GTRecipe, String> ANONYMOUS_KEYS = new IdentityHashMap<>();
    private static long anonymousSequence;

    private RecipeOriginalSnapshotStore() {}

    public static synchronized void capture(String recipeTypeId, GTRecipe recipe) {
        if (recipeTypeId == null || recipeTypeId.isEmpty() || recipe == null) return;
        LinkedHashMap<String, GTRecipe> byId =
                SNAPSHOTS.computeIfAbsent(recipeTypeId, key -> new LinkedHashMap<>());
        String id = stableKey(recipe);
        if (!id.startsWith("@anonymous:") && byId.containsKey(id)) return;
        byId.putIfAbsent(id, recipe.copy());
    }

    public static synchronized GTRecipe copyOf(String recipeTypeId, String recipeId) {
        if (recipeTypeId == null || recipeId == null) return null;
        Map<String, GTRecipe> byId = SNAPSHOTS.get(recipeTypeId);
        if (byId == null) return null;
        GTRecipe recipe = byId.get(recipeId);
        return recipe == null ? null : recipe.copy();
    }

    public static synchronized List<GTRecipe> copiesOf(String recipeTypeId) {
        Map<String, GTRecipe> byId = SNAPSHOTS.get(recipeTypeId);
        if (byId == null || byId.isEmpty()) return Collections.emptyList();
        List<GTRecipe> copies = new ArrayList<>(byId.size());
        for (GTRecipe recipe : byId.values()) {
            if (recipe != null) copies.add(recipe.copy());
        }
        return copies;
    }

    public static synchronized Set<String> ids(String recipeTypeId) {
        Map<String, GTRecipe> byId = SNAPSHOTS.get(recipeTypeId);
        if (byId == null || byId.isEmpty()) return Collections.emptySet();
        Set<String> ids = new LinkedHashSet<>();
        for (String id : byId.keySet()) {
            if (!id.startsWith("@anonymous:")) ids.add(id);
        }
        return Collections.unmodifiableSet(ids);
    }

    public static synchronized boolean hasSnapshot(String recipeTypeId) {
        Map<String, GTRecipe> byId = SNAPSHOTS.get(recipeTypeId);
        return byId != null && !byId.isEmpty();
    }

    public static synchronized Set<String> typeIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(SNAPSHOTS.keySet()));
    }

    public static synchronized void clear(String recipeTypeId) {
        if (recipeTypeId != null) {
            Map<String, GTRecipe> removed = SNAPSHOTS.remove(recipeTypeId);
            if (removed != null) {
                for (GTRecipe recipe : removed.values()) {
                    ANONYMOUS_KEYS.values().removeIf(id -> id.startsWith("@anonymous:")
                            && removed.containsKey(id));
                }
            }
        }
    }

    public static synchronized void clearAll() {
        SNAPSHOTS.clear();
        ANONYMOUS_KEYS.clear();
        anonymousSequence = 0L;
    }

    private static String stableKey(GTRecipe recipe) {
        if (recipe.getId() != null) return recipe.getId().toString();
        String existing = ANONYMOUS_KEYS.get(recipe);
        if (existing != null) return existing;
        String created = "@anonymous:" + (++anonymousSequence);
        ANONYMOUS_KEYS.put(recipe, created);
        return created;
    }
}
