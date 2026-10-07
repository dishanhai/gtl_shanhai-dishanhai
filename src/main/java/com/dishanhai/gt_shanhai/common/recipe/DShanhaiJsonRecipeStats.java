package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Counts the currently loaded ShanHai data-pack recipes, including the generated cache pack. */
public final class DShanhaiJsonRecipeStats {
    private DShanhaiJsonRecipeStats() {}

    public record TypeCounts(long success, long failed, long disabled) {
        public long total() {
            return success + failed + disabled;
        }
    }

    public record Failure(String recipeId, String type, String hint) {}

    public record Snapshot(long success, long failed, long disabled, Map<String, TypeCounts> byType,
                           List<Failure> failures) {
        public Snapshot {
            if (failures == null) failures = List.of();
        }

        public long total() {
            return success + failed + disabled;
        }
    }

    public static Snapshot scan(MinecraftServer server) {
        if (server == null) return new Snapshot(0, 0, 0, Map.of(), List.of());

        Map<String, long[]> counts = new LinkedHashMap<>();
        List<Failure> failures = new ArrayList<>();
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
                String hint = "JSON 無法讀取: " + shortMessage(e);
                GTDishanhaiMod.LOGGER.warn("[DRE] JSON 配方載入失敗: {} {}", recipeId, hint);
                add(counts, "unknown", 1);
                failures.add(new Failure(recipeId.toString(), "unknown", hint));
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
                String hint = explainLoadFailure(json, rawType);
                GTDishanhaiMod.LOGGER.warn("[DRE] JSON 配方載入失敗: {} [{}] {}", recipeId, rawType, hint);
                add(counts, type, 1);
                failures.add(new Failure(recipeId.toString(), rawType, hint));
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
        return new Snapshot(success, failed, disabled, byType, List.copyOf(failures));
    }

    /**
     * 配方沒進 RecipeManager 時，從 JSON 裡找出未註冊的物品、流體和配方類型。
     * 查詢由呼叫端提供，方便在沒有註冊表的測試裡驗證提示文案。
     */
    static String explainLoadFailure(JsonObject json, String rawType,
                                     Predicate<String> itemExists,
                                     Predicate<String> fluidExists,
                                     Predicate<String> recipeTypeExists) {
        Set<String> unknownItems = new LinkedHashSet<>();
        Set<String> unknownFluids = new LinkedHashSet<>();
        if (json != null) collectMissing(json, itemExists, fluidExists, unknownItems, unknownFluids);
        StringBuilder hint = new StringBuilder();
        appendIds(hint, "未知物品", unknownItems);
        appendIds(hint, "未知流體", unknownFluids);
        if (rawType != null && rawType.indexOf(':') >= 0
                && recipeTypeExists != null && !recipeTypeExists.test(rawType)) {
            appendPart(hint, "未知配方類型 " + rawType);
        }
        return hint.length() == 0 ? "已寫入資料包，但沒有進入配方表" : hint.toString();
    }

    private static String explainLoadFailure(JsonObject json, String rawType) {
        return explainLoadFailure(json, rawType,
                DShanhaiJsonRecipeStats::itemExists,
                DShanhaiJsonRecipeStats::fluidExists,
                DShanhaiJsonRecipeStats::recipeTypeExists);
    }

    private static void collectMissing(JsonElement element,
                                       Predicate<String> itemExists,
                                       Predicate<String> fluidExists,
                                       Set<String> unknownItems,
                                       Set<String> unknownFluids) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectMissing(child, itemExists, fluidExists, unknownItems, unknownFluids);
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        takeIds(object.get("item"), itemExists, unknownItems);
        takeIds(object.get("fluid"), fluidExists, unknownFluids);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonArray() || value.isJsonObject()) {
                collectMissing(value, itemExists, fluidExists, unknownItems, unknownFluids);
            }
        }
    }

    private static void takeIds(JsonElement element, Predicate<String> exists, Set<String> missing) {
        if (element == null || element.isJsonNull() || exists == null) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) takeIds(child, exists, missing);
            return;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return;
        String id = stripCountPrefix(element.getAsString());
        if (id.indexOf(':') < 0) return;
        if (!exists.test(id)) missing.add(id);
    }

    static String stripCountPrefix(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        int marker = trimmed.indexOf('x');
        if (marker <= 0 || marker >= trimmed.length() - 2 || trimmed.charAt(marker + 1) != ' ') return trimmed;
        for (int i = 0; i < marker; i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return trimmed;
        }
        return trimmed.substring(marker + 2).trim();
    }

    private static void appendIds(StringBuilder hint, String label, Set<String> ids) {
        if (ids.isEmpty()) return;
        StringBuilder part = new StringBuilder(label);
        int shown = 0;
        for (String id : ids) {
            if (shown == 4) {
                part.append(" 等");
                break;
            }
            part.append(shown == 0 ? ' ' : ',');
            if (shown > 0) part.append(' ');
            part.append(id);
            shown++;
        }
        appendPart(hint, part.toString());
    }

    private static void appendPart(StringBuilder hint, String part) {
        if (hint.length() > 0) hint.append('；');
        hint.append(part);
    }

    private static boolean itemExists(String id) {
        return registryContains(id, parsed -> ForgeRegistries.ITEMS.containsKey(parsed));
    }

    private static boolean fluidExists(String id) {
        return registryContains(id, parsed -> ForgeRegistries.FLUIDS.containsKey(parsed));
    }

    private static boolean registryContains(String id, Predicate<ResourceLocation> contains) {
        try {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            return parsed != null && contains.test(parsed);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean recipeTypeExists(String typeId) {
        if (typeId == null || !typeId.startsWith("gtceu:")) return true;
        try {
            ResourceLocation parsed = ResourceLocation.tryParse(typeId);
            return parsed != null && GTRegistries.RECIPE_TYPES.get(parsed) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
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
