package com.dishanhai.gt_shanhai.common.recipe;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

/**
 * Stores the first unmodified copy of each runtime recipe.
 *
 * <p>Rule application must always start from this store. Returning fresh copies prevents
 * strip/replace/delete operations from accumulating across rebuilds.</p>
 */
public final class RecipeOriginalSnapshotStore {

    private static final Map<String, LinkedHashMap<String, GTRecipe>> SNAPSHOTS = new LinkedHashMap<>();
    private static final Set<String> COMPLETE_TYPES = new LinkedHashSet<>();
    private static final ThreadLocal<Map<String, LinkedHashMap<String, GTRecipe>>> LOADING = new ThreadLocal<>();
    private static final Map<RecipeManager, Map<String, LinkedHashMap<String, GTRecipe>>> LOADED = new WeakHashMap<>();
    private static WeakReference<RecipeManager> activeManager = new WeakReference<>(null);
    private static final Map<GTRecipe, String> ANONYMOUS_KEYS = new IdentityHashMap<>();
    private static long anonymousSequence;

    private RecipeOriginalSnapshotStore() {}

    public static synchronized void capture(String recipeTypeId, GTRecipe recipe) {
        if (recipeTypeId == null || recipeTypeId.isEmpty() || recipe == null) return;
        Map<String, LinkedHashMap<String, GTRecipe>> loading = LOADING.get();
        LinkedHashMap<String, GTRecipe> byId = (loading != null ? loading : SNAPSHOTS)
                .computeIfAbsent(recipeTypeId, key -> new LinkedHashMap<>());
        String id = stableKey(recipe);
        if (!id.startsWith("@anonymous:") && byId.containsKey(id)) return;
        byId.putIfAbsent(id, recipe.copy());
    }

    public static synchronized void captureComplete(String recipeTypeId, Iterable<? extends GTRecipe> originals) {
        clear(recipeTypeId);
        for (GTRecipe recipe : originals) capture(recipeTypeId, recipe);
        COMPLETE_TYPES.add(recipeTypeId);
    }

    public static void beginLoading() {
        LOADING.set(new LinkedHashMap<>());
    }

    public static synchronized void endLoading(RecipeManager manager, boolean loaded) {
        if (loaded) LOADED.put(manager, LOADING.get());
        LOADING.remove();
    }

    /** Adopt only a manager that has been installed on the server. */
    public static synchronized void activate(RecipeManager manager) {
        if (manager == null) return;
        Map<String, LinkedHashMap<String, GTRecipe>> originals = LOADED.remove(manager);
        if (originals == null && activeManager.get() == manager) return;
        clearAll();
        if (originals != null) SNAPSHOTS.putAll(originals);
        activeManager = new WeakReference<>(manager);
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
        return COMPLETE_TYPES.contains(recipeTypeId);
    }

    public static synchronized Set<String> typeIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(SNAPSHOTS.keySet()));
    }

    public static synchronized void clear(String recipeTypeId) {
        if (recipeTypeId != null) {
            COMPLETE_TYPES.remove(recipeTypeId);
            Map<String, GTRecipe> removed = SNAPSHOTS.remove(recipeTypeId);
            if (removed != null) {
                ANONYMOUS_KEYS.values().removeIf(removed::containsKey);
            }
        }
    }

    public static synchronized void clearAll() {
        SNAPSHOTS.clear();
        COMPLETE_TYPES.clear();
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
