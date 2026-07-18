package com.dishanhai.gt_shanhai.common.compat.eaep;

import appeng.helpers.patternprovider.PatternContainer;

import com.dishanhai.gt_shanhai.common.item.WildcardPatternRecipeTypeBinding;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.client.gui.scaled.PinyinSearchBridge;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EaepProviderRecipeTypeBridge {

    private static final String EAEP_RECIPE_TYPE_NAMES = "extendedae_plus/recipe_type_names.json";
    private static final Gson GSON = new Gson();
    private static final ThreadLocal<List<List<String>>> INCOMING_PROVIDER_RECIPE_TYPES = new ThreadLocal<>();
    private static Map<String, String> recipeTypeNameCache = Collections.emptyMap();
    private static long recipeTypeNameCacheTime = Long.MIN_VALUE;

    private EaepProviderRecipeTypeBridge() {
    }

    public static List<String> collectProviderRecipeTypeIds(PatternContainer container) {
        if (!(container instanceof RecipeTypePatternBufferPartMachine buffer)) {
            return List.of();
        }
        List<GTRecipeType> types = WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(buffer.getControllers());
        if (types.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(types.size());
        for (GTRecipeType type : types) {
            if (type != null && type.registryName != null) {
                result.add(type.registryName.toString());
            }
        }
        return result;
    }

    public static void setIncomingProviderRecipeTypes(List<List<String>> recipeTypeIds) {
        INCOMING_PROVIDER_RECIPE_TYPES.set(copyNested(recipeTypeIds));
    }

    public static List<List<String>> consumeIncomingProviderRecipeTypes() {
        List<List<String>> result = INCOMING_PROVIDER_RECIPE_TYPES.get();
        INCOMING_PROVIDER_RECIPE_TYPES.remove();
        return result == null ? List.of() : result;
    }

    public static void clearIncomingProviderRecipeTypes() {
        INCOMING_PROVIDER_RECIPE_TYPES.remove();
    }

    public static Map<String, Set<String>> buildRecipeTypeMapByProviderName(
            List<String> names, List<List<String>> providerRecipeTypeIds) {
        if (names == null || providerRecipeTypeIds == null || providerRecipeTypeIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new HashMap<>();
        int size = Math.min(names.size(), providerRecipeTypeIds.size());
        for (int i = 0; i < size; i++) {
            String name = deserializeComponentName(names.get(i));
            if (name == null || name.isBlank()) {
                continue;
            }
            List<String> typeIds = providerRecipeTypeIds.get(i);
            if (typeIds == null || typeIds.isEmpty()) {
                continue;
            }
            result.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).addAll(typeIds);
        }
        return result;
    }

    public static void sortProvidersByRecipeTypeMatch(
            List<Long> ids,
            List<String> names,
            List<Integer> totalSlots,
            List<Integer> counts,
            Map<String, Set<String>> recipeTypesByName,
            String query) {
        if (ids == null || names == null || totalSlots == null || counts == null
                || recipeTypesByName == null || recipeTypesByName.isEmpty()
                || query == null || query.trim().isEmpty() || ids.size() <= 1) {
            return;
        }
        List<Integer> indices = new ArrayList<>(names.size());
        boolean hasMatch = false;
        for (int i = 0; i < names.size(); i++) {
            indices.add(i);
            if (providerMatchesRecipeType(names.get(i), recipeTypesByName, query)) {
                hasMatch = true;
            }
        }
        if (!hasMatch) {
            return;
        }
        indices.sort((left, right) -> {
            int leftScore = providerRecipeTypeMatchScore(names.get(left), recipeTypesByName, query);
            int rightScore = providerRecipeTypeMatchScore(names.get(right), recipeTypesByName, query);
            if (leftScore != rightScore) {
                return Integer.compare(rightScore, leftScore);
            }
            return Integer.compare(left, right);
        });
        reorder(ids, indices);
        reorder(names, indices);
        reorder(totalSlots, indices);
        reorder(counts, indices);
    }

    public static String buildProviderRecipeTypeHint(
            String providerName, Map<String, Set<String>> recipeTypesByName, String query, int maxTypes) {
        String text = buildProviderRecipeTypeText(providerName, recipeTypesByName, query, maxTypes);
        return text.isEmpty() ? "" : " [" + text + "]";
    }

    public static String buildProviderRecipeTypeText(
            String providerName, Map<String, Set<String>> recipeTypesByName, String query, int maxTypes) {
        if (providerName == null || recipeTypesByName == null || recipeTypesByName.isEmpty() || maxTypes <= 0) {
            return "";
        }
        Set<String> typeIds = recipeTypesByName.get(providerName);
        if (typeIds == null || typeIds.isEmpty()) {
            return "";
        }
        String normalizedQuery = normalize(query);
        Map<String, String> mappings = loadRecipeTypeNameMappings();
        List<String> preferred = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String typeId : typeIds) {
            String displayName = displayRecipeType(typeId, mappings);
            if (displayName.isEmpty()) {
                continue;
            }
            if (!normalizedQuery.isEmpty() && recipeTypeMatchesQuery(typeId, normalizedQuery, mappings)) {
                preferred.add(displayName);
            } else {
                others.add(displayName);
            }
        }
        List<String> ordered = new ArrayList<>(preferred.size() + others.size());
        ordered.addAll(preferred);
        ordered.addAll(others);
        if (ordered.isEmpty()) {
            return "";
        }
        List<String> visible = ordered.size() > maxTypes ? ordered.subList(0, maxTypes) : ordered;
        return String.join("/", visible);
    }

    public static boolean providerMatchesRecipeType(
            String providerName, Map<String, Set<String>> recipeTypesByName, String query) {
        Set<String> typeIds = recipeTypesByName.get(providerName);
        if (typeIds == null || typeIds.isEmpty()) {
            return false;
        }
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return false;
        }
        Map<String, String> mappings = loadRecipeTypeNameMappings();
        for (String typeId : typeIds) {
            if (recipeTypeMatchScore(typeId, normalizedQuery, mappings) > 0) {
                return true;
            }
        }
        return false;
    }

    private static int providerRecipeTypeMatchScore(
            String providerName, Map<String, Set<String>> recipeTypesByName, String query) {
        Set<String> typeIds = recipeTypesByName.get(providerName);
        if (typeIds == null || typeIds.isEmpty()) {
            return 0;
        }
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return 0;
        }
        Map<String, String> mappings = loadRecipeTypeNameMappings();
        int bestScore = 0;
        for (String typeId : typeIds) {
            bestScore = Math.max(bestScore, recipeTypeMatchScore(typeId, normalizedQuery, mappings));
        }
        return bestScore;
    }

    private static boolean recipeTypeMatchesQuery(String typeId, String query, Map<String, String> mappings) {
        return recipeTypeMatchScore(typeId, query, mappings) > 0;
    }

    private static int recipeTypeMatchScore(String typeId, String query, Map<String, String> mappings) {
        if (typeId == null || typeId.isBlank() || query == null || query.isBlank()) {
            return 0;
        }
        String id = normalize(typeId);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        int bestScore = Math.max(tokenMatchScore(id, query), tokenMatchScore(path, query));
        String mappedById = mappings.get(id);
        bestScore = Math.max(bestScore, tokenMatchScore(mappedById, query));
        String mappedByPath = mappings.get(path);
        return Math.max(bestScore, tokenMatchScore(mappedByPath, query));
    }

    private static String displayRecipeType(String typeId, Map<String, String> mappings) {
        if (typeId == null || typeId.isBlank()) {
            return "";
        }
        String id = normalize(typeId);
        String mapped = mappings.get(id);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        mapped = mappings.get(path);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        return path;
    }

    private static boolean tokenMatches(String token, String query) {
        return tokenMatchScore(token, query) > 0;
    }

    private static int tokenMatchScore(String token, String query) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        String normalized = normalize(token);
        if (normalized.equals(query)) {
            return 100;
        }
        if (PinyinSearchBridge.contains(token, query)) {
            return 90;
        }
        if (normalized.contains(query)) {
            return 60;
        }
        if (query.contains(normalized)) {
            return 20;
        }
        return 0;
    }

    private static synchronized Map<String, String> loadRecipeTypeNameMappings() {
        try {
            Path cfgPath = FMLPaths.CONFIGDIR.get().resolve(EAEP_RECIPE_TYPE_NAMES);
            long modified = Files.exists(cfgPath, LinkOption.NOFOLLOW_LINKS)
                    ? Files.getLastModifiedTime(cfgPath).toMillis() : Long.MIN_VALUE;
            if (modified == recipeTypeNameCacheTime) {
                return recipeTypeNameCache;
            }
            recipeTypeNameCacheTime = modified;
            if (modified == Long.MIN_VALUE) {
                recipeTypeNameCache = Collections.emptyMap();
                return recipeTypeNameCache;
            }
            JsonObject obj = GSON.fromJson(Files.readString(cfgPath), JsonObject.class);
            if (obj == null) {
                recipeTypeNameCache = Collections.emptyMap();
                return recipeTypeNameCache;
            }
            Map<String, String> loaded = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()) {
                    loaded.put(normalize(entry.getKey()), normalize(value.getAsString()));
                }
            }
            recipeTypeNameCache = loaded;
            return recipeTypeNameCache;
        } catch (Throwable ignored) {
            recipeTypeNameCache = Collections.emptyMap();
            return recipeTypeNameCache;
        }
    }

    private static String deserializeComponentName(String name) {
        if (name == null) {
            return "";
        }
        try {
            if (name.startsWith("{") || name.startsWith("\"")) {
                Component component = Component.Serializer.fromJson(name);
                if (component != null) {
                    return component.getString();
                }
            }
        } catch (Throwable ignored) {
        }
        return name;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<List<String>> copyNested(List<List<String>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<List<String>> result = new ArrayList<>(source.size());
        for (List<String> item : source) {
            result.add(item == null || item.isEmpty() ? List.of() : List.copyOf(item));
        }
        return result;
    }

    private static <T> void reorder(List<T> list, List<Integer> indices) {
        List<T> sorted = new ArrayList<>(list.size());
        for (Integer index : indices) {
            sorted.add(list.get(index));
        }
        list.clear();
        list.addAll(sorted);
    }
}
