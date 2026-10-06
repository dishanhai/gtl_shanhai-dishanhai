package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single runtime entry for rebuilding a recipe type from immutable originals.
 */
public final class RecipeRebuildService {

    public enum RebuildReason {
        STARTUP,
        RELOAD,
        RULE_CHANGED,
        EDITOR_COMMIT,
        TOGGLE_CHANGED
    }

    public record RebuildReport(
            String recipeTypeId,
            int captured,
            int kept,
            int dropped,
            int replaced,
            long revision) {}

    private RecipeRebuildService() {}

    public static RebuildReport rebuildType(String recipeTypeId, RebuildReason reason) {
        GTRecipeType type = resolveType(recipeTypeId);
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) {
            return new RebuildReport(recipeTypeId, 0, 0, 0, 0,
                    DShanhaiRecipeModifierAPI.getPatternCacheRevision());
        }

        List<GTRecipe> originals = ensureOriginals(recipeTypeId, type);
        List<GTRecipe> rebuilt = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int dropped = 0;
        int replaced = 0;

        for (GTRecipe original : originals) {
            if (original == null) continue;
            String id = recipeId(original);
            if (!id.isEmpty() && !ids.add(id)) {
                dropped++;
                continue;
            }
            if (!id.isEmpty() && !DShanhaiRecipeModifierAPI.isRecipeEnabled(id)) {
                dropped++;
                continue;
            }
            GTRecipe canonical = buildCanonical(recipeTypeId, original);
            if (canonical == null) {
                dropped++;
                continue;
            }
            if (!sameShape(original, canonical)) replaced++;
            rebuilt.add(canonical);
        }

        DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch(
                "recipe-rebuild:" + reason.name().toLowerCase(), () -> {
                    boolean previous = DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.get();
                    DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(true);
                    try {
                        var lookup = type.getLookup();
                        lookup.removeAllRecipes();
                        for (GTRecipe recipe : rebuilt) lookup.addRecipe(recipe);
                        DShanhaiRecipeModifierAPI.invalidateRecipeCaches(
                                "recipe-rebuild:" + reason.name().toLowerCase(),
                                Set.of(recipeTypeId));
                    } finally {
                        DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(previous);
                    }
                });

        return new RebuildReport(
                recipeTypeId,
                originals.size(),
                rebuilt.size(),
                dropped,
                replaced,
                DShanhaiRecipeModifierAPI.getPatternCacheRevision());
    }

    public static List<RebuildReport> rebuildAll(RebuildReason reason) {
        List<RebuildReport> reports = new ArrayList<>();
        Set<String> typeIds = new LinkedHashSet<>(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds());
        DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch(
                "recipe-rebuild-all:" + reason.name().toLowerCase(), () -> {
                    for (String typeId : typeIds) reports.add(rebuildType(typeId, reason));
                });
        return reports;
    }

    public static GTRecipe buildCanonical(String recipeTypeId, GTRecipe original) {
        if (original == null) return null;
        GTRecipe copy = original.copy();
        DShanhaiRecipeModifierAPI.applyStripByType(copy);
        DShanhaiRecipeModifierAPI.applyReplaceByType(copy);
        if (DShanhaiRecipeModifierAPI.isDeletedByRuntimeRule(recipeTypeId, copy)) return null;
        return copy;
    }

    private static GTRecipeType resolveType(String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) return null;
        return GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
    }

    private static List<GTRecipe> ensureOriginals(String recipeTypeId, GTRecipeType type) {
        if (RecipeOriginalSnapshotStore.hasSnapshot(recipeTypeId)) {
            return RecipeOriginalSnapshotStore.copiesOf(recipeTypeId);
        }
        List<GTRecipe> captured = new ArrayList<>();
        DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.set(true);
        try {
            type.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
                if (recipe != null) {
                    RecipeOriginalSnapshotStore.capture(recipeTypeId, recipe);
                    captured.add(recipe);
                }
            });
        } finally {
            DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.set(false);
        }
        return captured.isEmpty() ? List.of() : RecipeOriginalSnapshotStore.copiesOf(recipeTypeId);
    }

    private static String recipeId(GTRecipe recipe) {
        return recipe.getId() == null ? "" : recipe.getId().toString();
    }

    private static boolean sameShape(GTRecipe first, GTRecipe second) {
        return first.duration == second.duration
                && first.inputs.size() == second.inputs.size()
                && first.outputs.size() == second.outputs.size()
                && first.tickInputs.size() == second.tickInputs.size()
                && first.tickOutputs.size() == second.tickOutputs.size();
    }
}
