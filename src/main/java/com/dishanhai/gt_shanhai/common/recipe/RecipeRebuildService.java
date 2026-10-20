package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.mojang.datafixers.util.Either;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorValidation;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeFingerprint;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
        return rebuildType(recipeTypeId, reason, true);
    }

    /**
     * @param notify {@code false} 用於批次重建：由外層 {@link #rebuildAll} 收尾時只發一個同步包。
     */
    public static RebuildReport rebuildType(String recipeTypeId, RebuildReason reason, boolean notify) {
        GTRecipeType type = resolveType(recipeTypeId);
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) {
            return new RebuildReport(recipeTypeId, 0, 0, 0, 0,
                    DShanhaiRecipeModifierAPI.getPatternCacheRevision());
        }

        List<GTRecipe> originals = ensureOriginals(recipeTypeId, type);
        ShanhaiRecipeOverrideStore overrides = defaultOverrideStore();
        Map<String, ShanhaiRecipeOverrideStore.Entry> overridesByRecipe =
                overrides.entriesForType(recipeTypeId);
        if (reason == RebuildReason.STARTUP || reason == RebuildReason.RELOAD) {
            overridesByRecipe = withoutSuperseded(
                    recipeTypeId, originals, overridesByRecipe, overrides);
        }
        List<GTRecipe> rebuilt = assemble(recipeTypeId, originals, overridesByRecipe);
        Map<String, GTRecipe> rebuiltById = new LinkedHashMap<>();
        for (GTRecipe recipe : rebuilt) {
            if (recipe != null && recipe.getId() != null) {
                rebuiltById.putIfAbsent(recipe.getId().toString(), recipe);
            }
        }
        int dropped = 0;
        int replaced = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (GTRecipe original : originals) {
            if (original == null) continue;
            String id = recipeId(original);
            if (!id.isEmpty() && !seen.add(id)) {
                dropped++;
                continue;
            }
            GTRecipe canonical = id.isEmpty() ? null : rebuiltById.get(id);
            if (canonical == null) {
                dropped++;
                continue;
            }
            if (!sameShape(original, canonical)) replaced++;
        }

        DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch(
                "recipe-rebuild:" + reason.name().toLowerCase(), () -> {
                    boolean previous = DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.get();
                    DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(true);
                    try {
                        var lookup = type.getLookup();
                        lookup.removeAllRecipes();
                        for (GTRecipe recipe : rebuilt) lookup.addRecipe(recipe);
                        // 本次写回的副本才是 canonical：以本次集合整体替换，不再依赖历史残留
                        DShanhaiRecipeModifierAPI.replaceCanonicalLookupRecipes(recipeTypeId, rebuilt);
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

        if (notify && reason != RebuildReason.EDITOR_COMMIT) {
            notifyTypes(Set.of(recipeTypeId));
        }
        return new RebuildReport(
                recipeTypeId,
                originals.size(),
                rebuilt.size(),
                dropped,
                replaced,
                DShanhaiRecipeModifierAPI.getPatternCacheRevision());
    }

    /**
     * 同步受影響類型。
     *
     * <p>有覆寫的類型改為帶 {@code recipeUpdates} 的定向同步——覆寫檔只存在伺服器，
     * 只發類型 id 時客戶端會用本地 lookup 重建 wrapper，看不到 override 的結果。
     * 沒有覆寫的類型合併成一個全量刷新包，避免逐 type 發包。</p>
     */
    public static void notifyTypes(Set<String> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) return;
        Set<String> plain = new LinkedHashSet<>();
        for (String typeId : typeIds) {
            Set<String> overrideIds = DShanhaiRecipeModifierAPI.overrideRecipeIdsFor(typeId);
            if (overrideIds.isEmpty()) {
                plain.add(typeId);
            } else {
                com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncRecipesToAll(typeId, overrideIds);
            }
        }
        if (!plain.isEmpty()) {
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll(plain);
        }
    }

    /**
     * Writes only the edited recipe ids back into {@code GTRecipeLookup}.
     * A full {@code removeAllRecipes} rebuilds every ingredient node of the type;
     * an editor commit changes one or two ids.
     */
    public static RebuildReport refreshLookupEntries(String recipeTypeId, Set<String> recipeIds) {
        GTRecipeType type = resolveType(recipeTypeId);
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null
                || recipeIds == null || recipeIds.isEmpty()) {
            return new RebuildReport(recipeTypeId, 0, 0, 0, 0,
                    DShanhaiRecipeModifierAPI.getPatternCacheRevision());
        }
        Map<String, ShanhaiRecipeOverrideStore.Entry> overrides =
                defaultOverrideStore().entriesForType(recipeTypeId);
        Set<String> disabled = new LinkedHashSet<>(DShanhaiRecipeModifierAPI.getDisabledRecipeIds());
        Map<String, GTRecipe> live = new LinkedHashMap<>();
        Set<String> removed = new LinkedHashSet<>();
        for (String recipeId : recipeIds) {
            if (recipeId == null || recipeId.isEmpty()) continue;
            GTRecipe canonical = liveLookupRecipe(recipeTypeId, recipeId, overrides, disabled);
            if (canonical == null || canonical.getId() == null) {
                removed.add(recipeId);
            } else {
                live.put(canonical.getId().toString(), canonical);
            }
        }

        DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch(
                "recipe-refresh:editor-commit", () -> {
                    boolean previous = DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.get();
                    DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(true);
                    try {
                        var lookup = type.getLookup();
                        if (!unlinkRecipes(lookup.getLookup(), live.keySet(), removed)) {
                            lookup.removeAllRecipes();
                            List<GTRecipe> rebuilt =
                                    assemble(recipeTypeId, ensureOriginals(recipeTypeId, type), overrides);
                            for (GTRecipe recipe : rebuilt) lookup.addRecipe(recipe);
                            DShanhaiRecipeModifierAPI.replaceCanonicalLookupRecipes(recipeTypeId, rebuilt);
                        } else {
                            for (String recipeId : live.keySet()) {
                                DShanhaiRecipeModifierAPI.unmarkCanonicalLookupRecipe(recipeTypeId, recipeId);
                            }
                            for (String recipeId : removed) {
                                DShanhaiRecipeModifierAPI.unmarkCanonicalLookupRecipe(recipeTypeId, recipeId);
                            }
                            for (GTRecipe recipe : live.values()) lookup.addRecipe(recipe);
                            DShanhaiRecipeModifierAPI.markCanonicalLookupRecipes(recipeTypeId, live.values());
                        }
                        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                        if (server != null) {
                            Map<ResourceLocation, Recipe<?>> upserts = new LinkedHashMap<>();
                            Set<ResourceLocation> removals = new LinkedHashSet<>();
                            for (String recipeId : removed) {
                                ResourceLocation id = ResourceLocation.tryParse(recipeId);
                                if (id != null) removals.add(id);
                            }
                            for (GTRecipe recipe : live.values()) {
                                if (recipe.getId() != null) upserts.put(recipe.getId(), recipe);
                            }
                            com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiVanillaRecipeTable
                                    .replaceEntries(server, recipeTypeId, upserts, removals);
                        }
                        com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeReverseIndex.invalidate();
                        DShanhaiRecipeModifierAPI.invalidateRecipeCaches(
                                "recipe-refresh:editor-commit", Set.of(recipeTypeId));
                    } finally {
                        DShanhaiRecipeModifierAPI.SUPPRESS_LOOKUP_RECIPE_MODIFIERS.set(previous);
                    }
                });
        return new RebuildReport(
                recipeTypeId,
                recipeIds.size(),
                live.size(),
                removed.size(),
                live.size(),
                DShanhaiRecipeModifierAPI.getPatternCacheRevision());
    }

    public static List<RebuildReport> rebuildAll(RebuildReason reason) {
        List<RebuildReport> reports = new ArrayList<>();
        Set<String> typeIds = allTypeIds();
        DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch(
                "recipe-rebuild-all:" + reason.name().toLowerCase(), () -> {
                    for (String typeId : typeIds) reports.add(rebuildType(typeId, reason, false));
                });
        // 批次內逐 type 發包會造成封包風暴；完成後只通知一次受影響類型
        if (!typeIds.isEmpty()) notifyTypes(typeIds);
        return reports;
    }

    /** 有規則或有覆寫的全部配方類型。 */
    public static Set<String> allTypeIds() {
        Set<String> typeIds = new LinkedHashSet<>(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds());
        typeIds.addAll(overrideTypeIds());
        return typeIds;
    }

    public static GTRecipe buildCanonical(String recipeTypeId, GTRecipe original) {
        return buildCanonical(
                recipeTypeId,
                original,
                defaultOverrideStore().entriesForType(recipeTypeId));
    }

    public static List<GTRecipe> buildCanonicalList(String recipeTypeId, Iterable<? extends GTRecipe> originals) {
        if (originals == null) return List.of();
        Map<String, ShanhaiRecipeOverrideStore.Entry> overrides =
                defaultOverrideStore().entriesForType(recipeTypeId);
        return List.copyOf(assemble(recipeTypeId, originals, overrides));
    }

    /** Snapshot recipe after overrides, or an added recipe that is not in the snapshot. */
    public static GTRecipe editableOf(String recipeTypeId, String recipeId) {
        if (recipeTypeId == null || recipeId == null || recipeId.isEmpty()) return null;
        GTRecipe snapshot = RecipeOriginalSnapshotStore.copyOf(recipeTypeId, recipeId);
        if (snapshot != null) return buildCanonical(recipeTypeId, snapshot);
        ShanhaiRecipeOverrideStore.Entry entry =
                defaultOverrideStore().entriesForType(recipeTypeId).get(recipeId);
        if (entry == null) return null;
        if (entry.created()) return materializeCreated(recipeTypeId, entry);
        if (entry.sourceRecipeId().equals(entry.recipeId())) return null;
        return materializeAddition(recipeTypeId, entry);
    }

    /**
     * Types that have never been rebuilt are absent from the snapshot. JEI still shows
     * those recipes, so capture the live match before declaring it missing.
     * Returns the type id the snapshot was stored under.
     */
    public static String captureLive(String recipeTypeId, String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return null;
        String scanned = scanLookup(recipeTypeId, recipeId);
        if (scanned != null) return scanned;
        String fromManager = captureFromManager(recipeId);
        if (fromManager != null) return fromManager;
        if (recipeTypeId != null && !recipeTypeId.isEmpty()) {
            for (GTRecipeType type : GTRegistries.RECIPE_TYPES) {
                if (type == null || type.registryName == null) continue;
                String typeId = type.registryName.toString();
                if (typeId.equals(recipeTypeId)) continue;
                scanned = scanLookup(typeId, recipeId);
                if (scanned != null) return scanned;
            }
        }
        return null;
    }

    private static String scanLookup(String recipeTypeId, String recipeId) {
        GTRecipeType type = resolveType(recipeTypeId);
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) return null;
        GTRecipe[] found = new GTRecipe[1];
        DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.set(true);
        try {
            type.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
                if (found[0] != null || recipe == null || recipe.getId() == null) return;
                if (recipeId.equals(recipe.getId().toString())) found[0] = recipe;
            });
        } finally {
            DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.set(false);
        }
        if (found[0] == null) return null;
        String actualType = found[0].recipeType != null && found[0].recipeType.registryName != null
                ? found[0].recipeType.registryName.toString() : recipeTypeId;
        RecipeOriginalSnapshotStore.capture(actualType, found[0]);
        return editableOf(actualType, recipeId) == null ? null : actualType;
    }

    private static String captureFromManager(String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (id == null || server == null) return null;
        net.minecraft.world.item.crafting.Recipe<?> recipe =
                server.getRecipeManager().byKey(id).orElse(null);
        if (!(recipe instanceof GTRecipe gt) || gt.getId() == null
                || gt.recipeType == null || gt.recipeType.registryName == null) {
            return null;
        }
        String actualType = gt.recipeType.registryName.toString();
        if (!recipeId.equals(gt.getId().toString())) return null;
        String scanned = scanLookup(actualType, recipeId);
        if (scanned != null) return scanned;
        RecipeOriginalSnapshotStore.capture(actualType, gt);
        return editableOf(actualType, recipeId) == null ? null : actualType;
    }

    /** A recipe assembled only from an editor payload, with no snapshot template. */
    public static GTRecipe materializeFresh(String recipeTypeId, ShanhaiRecipeBase base) {
        if (base == null) return null;
        GTRecipeType type = resolveType(recipeTypeId);
        ResourceLocation id = ResourceLocation.tryParse(base.recipeId());
        if (type == null || id == null) return null;
        GTRecipe shell = new GTRecipe(
                type,
                id,
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new CompoundTag(),
                Math.max(1, base.duration()),
                false);
        GTRecipe edited = base.toGtRecipe(shell);
        if (edited == null) return null;
        edited.setId(id);
        return edited;
    }

    private static List<GTRecipe> assemble(
            String recipeTypeId,
            Iterable<? extends GTRecipe> originals,
            Map<String, ShanhaiRecipeOverrideStore.Entry> overrides) {
        Map<String, ShanhaiRecipeOverrideStore.Entry> safe = overrides == null ? Map.of() : overrides;
        Set<String> removedSources = new LinkedHashSet<>();
        for (ShanhaiRecipeOverrideStore.Entry entry : safe.values()) {
            if (entry == null || entry.keepOriginal()) continue;
            if (!entry.sourceRecipeId().equals(entry.recipeId())) {
                removedSources.add(entry.sourceRecipeId());
            }
        }
        Set<String> disabled = new LinkedHashSet<>(DShanhaiRecipeModifierAPI.getDisabledRecipeIds());
        List<GTRecipe> rebuilt = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        if (originals != null) {
            for (GTRecipe original : originals) {
                if (original == null) continue;
                String id = recipeId(original);
                if (!id.isEmpty() && !ids.add(id)) continue;
                if (!id.isEmpty() && (disabled.contains(id) || removedSources.contains(id))) continue;
                GTRecipe canonical = buildCanonical(recipeTypeId, original, safe);
                if (canonical == null) continue;
                rebuilt.add(canonical);
            }
        }
        for (ShanhaiRecipeOverrideStore.Entry entry : safe.values()) {
            if (entry == null || entry.recipeId().isEmpty() || ids.contains(entry.recipeId())) continue;
            GTRecipe added;
            if (entry.created()) {
                added = materializeCreated(recipeTypeId, entry);
            } else if (entry.sourceRecipeId().equals(entry.recipeId())) {
                continue;
            } else {
                added = materializeAddition(recipeTypeId, entry);
            }
            if (added == null || added.getId() == null) continue;
            if (!ids.add(added.getId().toString())) continue;
            rebuilt.add(added);
        }
        return rebuilt;
    }

    private static GTRecipe materializeCreated(
            String recipeTypeId, ShanhaiRecipeOverrideStore.Entry entry) {
        ShanhaiRecipeBase override = ShanhaiRecipeBase.fromPayload(entry.payload());
        if (override == null
                || !recipeTypeId.equals(override.recipeTypeId())
                || !entry.recipeId().equals(override.recipeId())
                || ShanhaiRecipeEditorValidation.validateBase(override) != null) {
            return null;
        }
        return materializeFresh(recipeTypeId, override);
    }

    private static GTRecipe materializeAddition(
            String recipeTypeId, ShanhaiRecipeOverrideStore.Entry entry) {
        GTRecipe template = RecipeOriginalSnapshotStore.copyOf(recipeTypeId, entry.sourceRecipeId());
        if (template == null) return null;
        ShanhaiRecipeBase originalBase = ShanhaiRecipeBase.from(template);
        ShanhaiRecipeBase override = ShanhaiRecipeBase.inheritLegacyDetails(
                entry.payload(), originalBase, entry.baseFingerprint());
        if (override == null
                || !recipeTypeId.equals(override.recipeTypeId())
                || ShanhaiRecipeEditorValidation.validateBase(override) != null) {
            return null;
        }
        GTRecipe edited = override.toGtRecipe(template);
        if (edited == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(entry.recipeId());
        if (id == null) return null;
        edited.setId(id);
        return edited;
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

    /** 该类型在覆写档里出现过的配方 id，用于定向同步。 */
    public static Set<String> overrideRecipeIdsFor(String recipeTypeId) {
        return DShanhaiRecipeModifierAPI.overrideRecipeIdsFor(recipeTypeId);
    }

    private static ShanhaiRecipeOverrideStore defaultOverrideStore() {
        return new ShanhaiRecipeOverrideStore(
                com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.ensureParent(
                        com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.recipeOverrides()));
    }

    public static void rebuildVanillaManager(MinecraftServer server, Set<String> typeIds) {
        com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiVanillaRecipeRebuild
                .rebuild(server, typeIds);
    }

    private static GTRecipeType resolveType(String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) return null;
        return GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
    }

    /**
     * A stored modifier annotation is stale once the datapack recipe is no longer
     * the base it was written against. Applying it again would keep the old edit
     * beside the new jar JSON. An empty fingerprint is kept: there is no base to compare.
     */
    static boolean shouldRetireOverride(
            boolean created,
            String baseFingerprint,
            boolean datapackHasRecipeId,
            boolean datapackHasSource,
            String loadedSourceFingerprint,
            String legacySourceFingerprint) {
        if (created) return datapackHasRecipeId;
        if (!datapackHasSource) return false;
        if (baseFingerprint == null || baseFingerprint.isEmpty()) return false;
        if (baseFingerprint.equals(loadedSourceFingerprint)) return false;
        if (baseFingerprint.equals(legacySourceFingerprint)) return false;
        return true;
    }

    private static Map<String, ShanhaiRecipeOverrideStore.Entry> withoutSuperseded(
            String recipeTypeId,
            List<GTRecipe> originals,
            Map<String, ShanhaiRecipeOverrideStore.Entry> overrides,
            ShanhaiRecipeOverrideStore store) {
        if (overrides == null || overrides.isEmpty()) {
            return overrides == null ? Map.of() : overrides;
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        Map<String, String> legacy = new LinkedHashMap<>();
        if (originals != null) {
            for (GTRecipe original : originals) rememberFingerprint(original, loaded, legacy);
        }
        Map<String, ShanhaiRecipeOverrideStore.Entry> kept = new LinkedHashMap<>();
        for (Map.Entry<String, ShanhaiRecipeOverrideStore.Entry> item : overrides.entrySet()) {
            ShanhaiRecipeOverrideStore.Entry entry = item.getValue();
            if (entry == null || entry.recipeId() == null || entry.recipeId().isEmpty()) continue;
            String sourceId = entry.sourceRecipeId();
            boolean retire = shouldRetireOverride(
                    entry.created(),
                    entry.baseFingerprint(),
                    loaded.containsKey(entry.recipeId()),
                    sourceId != null && loaded.containsKey(sourceId),
                    sourceId == null ? null : loaded.get(sourceId),
                    sourceId == null ? null : legacy.get(sourceId));
            if (!retire) {
                kept.put(item.getKey(), entry);
                continue;
            }
            try {
                store.remove(recipeTypeId, entry.recipeId());
            } catch (IOException e) {
                GTDishanhaiMod.LOGGER.warn("[配方修改器] 無法移除過期標註: {}", entry.recipeId(), e);
            }
            GTDishanhaiMod.LOGGER.warn(
                    "[配方修改器] 資料包已有新配方，移除 config 標註，避免和 jar JSON 並行衝突: {}",
                    entry.recipeId());
        }
        return kept;
    }

    private static void rememberFingerprint(
            GTRecipe original, Map<String, String> loaded, Map<String, String> legacy) {
        if (original == null || original.getId() == null) return;
        try {
            ShanhaiRecipeBase base = ShanhaiRecipeBase.from(original);
            if (base == null) return;
            String id = original.getId().toString();
            loaded.put(id, ShanhaiRecipeFingerprint.of(base));
            legacy.put(id, ShanhaiRecipeFingerprint.legacySnapshotFingerprint(base));
        } catch (RuntimeException ignored) {
            // A recipe that cannot be fingerprinted is left under its annotation.
        }
    }

    private static GTRecipe liveLookupRecipe(
            String recipeTypeId,
            String recipeId,
            Map<String, ShanhaiRecipeOverrideStore.Entry> overrides,
            Set<String> disabled) {
        if (recipeId == null || recipeId.isEmpty()) return null;
        if (disabled != null && disabled.contains(recipeId)) return null;
        if (overrides != null) {
            for (ShanhaiRecipeOverrideStore.Entry entry : overrides.values()) {
                if (entry == null || entry.keepOriginal()) continue;
                if (recipeId.equals(entry.sourceRecipeId()) && !recipeId.equals(entry.recipeId())) {
                    return null;
                }
            }
        }
        return editableOf(recipeTypeId, recipeId);
    }

    private static final Field BRANCH_NODES = branchField("nodes");
    private static final Field BRANCH_SPECIAL_NODES = branchField("specialNodes");

    private static Field branchField(String name) {
        try {
            Field field = Branch.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** @return false when the branch shape is not the one this patch knows */
    private static boolean unlinkRecipes(Branch root, Set<String> liveIds, Set<String> removedIds) {
        if (root == null || BRANCH_NODES == null || BRANCH_SPECIAL_NODES == null) return false;
        Set<String> ids = new LinkedHashSet<>();
        if (liveIds != null) ids.addAll(liveIds);
        if (removedIds != null) ids.addAll(removedIds);
        if (ids.isEmpty()) return true;
        try {
            unlinkBranch(root, ids);
            return true;
        } catch (IllegalAccessException e) {
            GTDishanhaiMod.LOGGER.warn("[配方修改器] 無法增量更新配方層，改為整類重建", e);
            return false;
        }
    }

    private static void unlinkBranch(Branch branch, Set<String> ids) throws IllegalAccessException {
        unlinkMap(BRANCH_NODES.get(branch), ids);
        unlinkMap(BRANCH_SPECIAL_NODES.get(branch), ids);
    }

    @SuppressWarnings("unchecked")
    private static void unlinkMap(Object raw, Set<String> ids) throws IllegalAccessException {
        if (!(raw instanceof Map<?, ?> nodes) || nodes.isEmpty()) return;
        Iterator<Map.Entry<Object, Object>> iterator =
                ((Map<Object, Object>) nodes).entrySet().iterator();
        while (iterator.hasNext()) {
            Object value = iterator.next().getValue();
            if (!(value instanceof Either<?, ?> either)) continue;
            if (either.left().isPresent()) {
                Object recipe = either.left().get();
                if (recipe instanceof GTRecipe gt
                        && gt.getId() != null
                        && ids.contains(gt.getId().toString())) {
                    iterator.remove();
                }
                continue;
            }
            if (either.right().isPresent() && either.right().get() instanceof Branch child) {
                unlinkBranch(child, ids);
                if (child.isEmptyBranch()) iterator.remove();
            }
        }
    }

    private static List<GTRecipe> ensureOriginals(String recipeTypeId, GTRecipeType type) {
        if (RecipeOriginalSnapshotStore.hasSnapshot(recipeTypeId)) {
            return RecipeOriginalSnapshotStore.copiesOf(recipeTypeId);
        }
        captureManagerOriginals(recipeTypeId);
        if (!RecipeOriginalSnapshotStore.hasSnapshot(recipeTypeId)) {
            captureLookupOriginals(recipeTypeId, type);
        }
        return RecipeOriginalSnapshotStore.hasSnapshot(recipeTypeId)
                ? RecipeOriginalSnapshotStore.copiesOf(recipeTypeId)
                : List.of();
    }

    /**
     * Lookup indexing keeps one recipe per input key and drops the rest.
     * Rebuilding a type from that pruned lookup, then writing the result back,
     * deletes the datapack recipes that lost the conflict. The vanilla table
     * still has every loaded recipe, so the first snapshot starts there.
     */
    private static void captureManagerOriginals(String recipeTypeId) {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (Recipe<?> recipe : com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiVanillaRecipeTable
                .read(server, recipeTypeId).values()) {
            if (recipe instanceof GTRecipe gt) {
                RecipeOriginalSnapshotStore.capture(recipeTypeId, gt);
            }
        }
    }

    private static void captureLookupOriginals(String recipeTypeId, GTRecipeType type) {
        if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) return;
        DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.set(true);
        try {
            type.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
                if (recipe != null) RecipeOriginalSnapshotStore.capture(recipeTypeId, recipe);
            });
        } finally {
            DShanhaiRecipeModifierAPI.SUPPRESS_GET_RECIPES_STRIP.set(false);
        }
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
