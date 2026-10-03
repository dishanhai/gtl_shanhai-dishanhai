package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.fml.ModList;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

/** Counts the currently loaded ShanHai data-pack recipes, including the generated cache pack. */
public final class DShanhaiJsonRecipeStats {
    private DShanhaiJsonRecipeStats() {}

    public record TypeCounts(long success, long failed, long disabled) {
        public long total() {
            return success + failed + disabled;
        }
    }

    public record Snapshot(long success, long failed, long disabled, Map<String, TypeCounts> byType) {
        public long total() {
            return success + failed + disabled;
        }
    }

    public static Snapshot scan(MinecraftServer server) {
        if (server == null) return new Snapshot(0, 0, 0, Map.of());

        Map<String, long[]> counts = new LinkedHashMap<>();
        JsonObject config = DShanhaiRecipeEnabledCondition.readConfig();
        boolean includeCache = DShanhaiRecipeCache.isCacheValid();
        for (Map.Entry<ResourceLocation, Resource> entry : server.getResourceManager()
                .listResources("recipes", id -> id.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation file = entry.getKey();
            boolean bundled = GTDishanhaiMod.MOD_ID.equals(file.getNamespace())
                    || isForeignNamespaceResource(file, entry.getValue());
            boolean cached = includeCache && DShanhaiRecipePackFinder.PACK_ID.equals(entry.getValue().sourcePackId());
            if (!bundled && !cached) continue;

            String path = file.getPath();
            ResourceLocation recipeId = new ResourceLocation(file.getNamespace(),
                    path.substring("recipes/".length(), path.length() - ".json".length()));
            JsonObject json;
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement element = JsonParser.parseReader(reader);
                json = element.getAsJsonObject();
            } catch (Exception e) {
                GTDishanhaiMod.LOGGER.warn("Unable to read ShanHai recipe JSON: {}", file, e);
                add(counts, "unknown", 1);
                continue;
            }

            String rawType = json.has("type") && json.get("type").isJsonPrimitive()
                    ? json.get("type").getAsString() : "unknown";
            String type = rawType;
            if (type.startsWith("gtceu:")) type = type.substring("gtceu:".length());
            if (server.getRecipeManager().byKey(recipeId).isPresent()
                    || isLoadedInGtRecipeLookup(recipeId, rawType)) {
                add(counts, type, 0);
            } else if (isDisabled(json, config)) {
                add(counts, type, 2);
            } else {
                GTDishanhaiMod.LOGGER.warn("[DRE] JSON 配方未在 RecipeManager/GTCEu lookup 找到: {} [{}]", recipeId, rawType);
                add(counts, type, 1);
            }
        }

        Map<String, TypeCounts> byType = new LinkedHashMap<>();
        long success = 0, failed = 0, disabled = 0;
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            long[] values = entry.getValue();
            byType.put(entry.getKey(), new TypeCounts(values[0], values[1], values[2]));
            success += values[0];
            failed += values[1];
            disabled += values[2];
        }
        return new Snapshot(success, failed, disabled, byType);
    }

    private static void add(Map<String, long[]> counts, String type, int status) {
        counts.computeIfAbsent(type, key -> new long[3])[status]++;
    }

    private static boolean isForeignNamespaceResource(ResourceLocation file, Resource resource) {
        if (!"gtceu".equals(file.getNamespace())) return false;
        String sourcePack = resource.sourcePackId();
        return sourcePack != null && sourcePack.contains(GTDishanhaiMod.MOD_ID);
    }

    /**
     * GTCEu recipes are indexed by GTRecipeType lookup trees.  KubeJS may warn while
     * trying to reinterpret their native fluid JSON, even though GTCEu loaded them
     * successfully into this lookup.  Use that authoritative index as a fallback.
     */
    private static boolean isLoadedInGtRecipeLookup(ResourceLocation recipeId, String typeId) {
        if (typeId == null || typeId.indexOf(':') < 0) return false;
        try {
            GTRecipeType type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(typeId));
            if (type == null || type.getLookup() == null || type.getLookup().getLookup() == null) return false;
            return type.getLookup().getLookup().getRecipes(false)
                    .anyMatch(recipe -> recipeId.equals(recipe.getId()));
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.debug("Unable to inspect GTCEu recipe lookup for {}: {}", recipeId, e.getMessage());
            return false;
        }
    }

    static boolean isDisabled(JsonObject json, JsonObject config) {
        if (!json.has("conditions") || !json.get("conditions").isJsonArray()) return false;
        for (JsonElement element : json.getAsJsonArray("conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();
            if (!condition.has("type")) continue;
            String conditionType = condition.get("type").getAsString();
            if ("forge:mod_loaded".equals(conditionType) && condition.has("modid")
                    && !ModList.get().isLoaded(condition.get("modid").getAsString())) return true;
            if (!DShanhaiRecipeEnabledCondition.ID.toString().equals(conditionType)) continue;
            try {
                if (!DShanhaiRecipeEnabledCondition.Serializer.INSTANCE.read(condition).isEnabled(config)) return true;
            } catch (Exception e) {
                GTDishanhaiMod.LOGGER.warn("Invalid ShanHai recipe_enabled condition: {}", condition, e);
            }
        }
        return false;
    }
}
