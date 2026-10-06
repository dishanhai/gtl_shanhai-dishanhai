package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorValidation;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

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
        ShanhaiRecipeOverrideStore overrides = defaultOverrideStore();
        Map<String, ShanhaiRecipeOverrideStore.Entry> overridesByRecipe =
                overrides.entriesForType(recipeTypeId);
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
            GTRecipe canonical = buildCanonical(recipeTypeId, original, overridesByRecipe);
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
                        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        if (server != null) {
                            com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiVanillaRecipeTable
                                    .replaceType(server, recipeTypeId, rebuilt);
                        }
                        com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeReverseIndex.invalidate();
                        DShanhaiRecipeModifierAPI.invalidateRecipeCaches(
                                "recipe-rebuild:" + reason.name().toLowerCase(),
                                Set.of(recipeTypeId));
                    } finally {
                        DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(previous);
                    }
                });

        com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll(Set.of(recipeTypeId));
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
        typeIds.addAll(overrideTypeIds());
        DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch(
                "recipe-rebuild-all:" + reason.name().toLowerCase(), () -> {
                    for (String typeId : typeIds) reports.add(rebuildType(typeId, reason));
                });
        return reports;
    }

    public static GTRecipe buildCanonical(String recipeTypeId, GTRecipe original) {
        return buildCanonical(
                recipeTypeId,
                original,
                defaultOverrideStore().entriesForType(recipeTypeId));
    }

    public static List<GTRecipe> buildCanonicalList(
            String recipeTypeId, Iterable<? extends GTRecipe> originals) {
        if (originals == null) return List.of();
        Map<String, ShanhaiRecipeOverrideStore.Entry> overrides =
                defaultOverrideStore().entriesForType(recipeTypeId);
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe original : originals) {
            GTRecipe canonical = buildCanonical(recipeTypeId, original, overrides);
            if (canonical != null) result.add(canonical);
        }
        return List.copyOf(result);
    }

    private static GTRecipe buildCanonical(
            String recipeTypeId, GTRecipe original,
            Map<String, ShanhaiRecipeOverrideStore.Entry> overrides) {
        if (original == null) return null;
        GTRecipe copy = original.copy();
        DShanhaiRecipeModifierAPI.applyStripByType(copy);
        DShanhaiRecipeModifierAPI.applyReplaceByType(copy);
        if (DShanhaiRecipeModifierAPI.isDeletedByRuntimeRule(recipeTypeId, copy)) return null;
        if (copy.getId() == null || overrides == null) return copy;
        ShanhaiRecipeOverrideStore.Entry entry = overrides.get(copy.getId().toString());
        if (entry == null) return copy;
        ShanhaiRecipeBase originalBase = ShanhaiRecipeBase.from(original);
        ShanhaiRecipeBase override = ShanhaiRecipeBase.inheritLegacyDetails(
                entry.payload(), originalBase, entry.baseFingerprint());
        if (override == null
                || !recipeTypeId.equals(override.recipeTypeId())
                || !copy.getId().toString().equals(override.recipeId())
                || ShanhaiRecipeEditorValidation.validateBase(override) != null) {
            return copy;
        }
        GTRecipe edited = override.toGtRecipe(copy);
        copy.inputs.clear();
        copy.inputs.putAll(edited.inputs);
        copy.outputs.clear();
        copy.outputs.putAll(edited.outputs);
        copy.tickInputs.clear();
        copy.tickInputs.putAll(edited.tickInputs);
        copy.tickOutputs.clear();
        copy.tickOutputs.putAll(edited.tickOutputs);
        copy.conditions.clear();
        copy.conditions.addAll(edited.conditions);
        copy.duration = edited.duration;
        copy.data = edited.data;
        return copy;
    }

    public static Set<String> overrideTypeIds() {
        return defaultOverrideStore().typeIds();
    }

    private static ShanhaiRecipeOverrideStore defaultOverrideStore() {
        return new ShanhaiRecipeOverrideStore(FMLPaths.GAMEDIR.get()
                .resolve("config/gt_shanhai/recipe_overrides.json"));
    }

    public static void rebuildVanillaManager(MinecraftServer server, Set<String> typeIds) {
        com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiVanillaRecipeRebuild
                .rebuild(server, typeIds);
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

    public static List<GTRecipe> originalsOfType(String recipeTypeId) {
        GTRecipeType type = resolveType(recipeTypeId);
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) {
            return List.of();
        }
        return ensureOriginals(recipeTypeId, type);
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
