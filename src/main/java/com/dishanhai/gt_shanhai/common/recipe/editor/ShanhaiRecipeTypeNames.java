package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the original GTCEu recipe-type name while retaining its full key.
 *
 * <p>Resolution order mirrors GTL-EH: active language table first, then the
 * zh_cn resource from the owning mod, then a readable path fallback.</p>
 */
public final class ShanhaiRecipeTypeNames {

    private static final Map<String, Map<String, String>> ZH_CACHE = new ConcurrentHashMap<>();

    private ShanhaiRecipeTypeNames() {}

    public static String display(ResourceLocation id) {
        if (id == null) return "未知配方类型";
        String key = id.getNamespace() + "." + id.getPath();
        String live = stripStyle(Component.translatable(key).getString());
        if (!live.isBlank() && !live.equals(key)) {
            return live + "（" + id + "）";
        }

        String zh = lookupZh(id);
        if (!zh.isBlank()) {
            return zh + "（" + id + "）";
        }
        return id.getPath().replace('_', ' ') + "（" + id + "）";
    }

    private static String lookupZh(ResourceLocation id) {
        Map<String, String> values = ZH_CACHE.computeIfAbsent(
                id.getNamespace(), ShanhaiRecipeTypeNames::loadZh);
        String[] candidates = {
                id.getNamespace() + "." + id.getPath(),
                id.getNamespace() + ".recipe_type." + id.getPath(),
                "recipe_type." + id.getPath(),
                id.getNamespace() + ".recipe_type." + id.getNamespace() + "." + id.getPath()
        };
        for (String candidate : candidates) {
            String value = values.get(candidate);
            if (value != null && !value.isBlank()) return stripStyle(value);
        }
        return "";
    }

    private static Map<String, String> loadZh(String namespace) {
        String resource = "assets/" + namespace + "/lang/zh_cn.json";
        try (InputStream stream = ShanhaiRecipeTypeNames.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) return Map.of();
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> values = new ConcurrentHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    values.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return Map.copyOf(values);
        } catch (RuntimeException | java.io.IOException ignored) {
            return Map.of();
        }
    }

    private static String stripStyle(String value) {
        if (value == null || value.indexOf('§') < 0) return value == null ? "" : value;
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '§' && i + 1 < value.length()) {
                i++;
                continue;
            }
            result.append(current);
        }
        return result.toString();
    }
}
