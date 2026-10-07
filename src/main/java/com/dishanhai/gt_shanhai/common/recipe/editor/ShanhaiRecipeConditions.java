package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every GTCEu and GTLCore recipe condition is a {@link RecipeConditionType}
 * in {@link GTRegistries#RECIPE_CONDITIONS}. Add, remove and labels go through
 * that registry and {@link RecipeCondition#CODEC}; individual conditions are not named here.
 */
public final class ShanhaiRecipeConditions {

    private static final String MARK = " · ";
    private static final Set<String> GENERIC_FIELDS = Set.of(
            "level", "type", "name", "data", "value", "count", "id", "min", "max", "reverse");

    /** One editable leaf inside a condition codec object. Path uses {@code a.b[0].c}. */
    public static final class Parameter {
        public final String path;
        public final String value;

        public Parameter(String path, String value) {
            this.path = path == null ? "" : path;
            this.value = value == null ? "" : value;
        }
    }

    private ShanhaiRecipeConditions() {}

    public static List<String> choices() {
        List<String> keys = new ArrayList<>(GTRegistries.RECIPE_CONDITIONS.keys());
        Collections.sort(keys);
        List<String> choices = new ArrayList<>(keys.size());
        for (String key : keys) choices.add(choice(key));
        return choices;
    }

    public static String choice(String key) {
        RecipeCondition created = create(key);
        if (created == null || created.getTooltips() == null) return key == null ? "" : key;
        String tip = created.getTooltips().getString();
        if (tip == null || tip.isEmpty() || key == null || key.isEmpty()) return key == null ? "" : key;
        return tip.replace("%", "%%") + MARK + key;
    }

    public static List<Parameter> parameters(JsonElement element) {
        List<Parameter> found = new ArrayList<>();
        if (element != null && element.isJsonObject()) walk(element.getAsJsonObject(), "", found);
        return found;
    }

    /**
     * Writes one codec leaf and keeps the previous JSON when the condition codec rejects it.
     * Numbers and booleans stay their original JSON kind.
     */
    public static JsonElement withParameter(JsonElement source, String path, String raw) {
        if (source == null || !source.isJsonObject() || path == null || path.isEmpty()) return source;
        JsonElement copy = source.deepCopy();
        if (!write(copy, tokens(path), raw)) return source;
        if (RecipeCondition.CODEC.parse(JsonOps.INSTANCE, copy).result().isEmpty()) return source;
        return copy;
    }

    /** Registry ids that can fill this field. Dimension markers and matching builtin registries included. */
    public static List<String> parameterChoices(String path, String current) {
        String field = fieldName(path);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if ("true".equals(current) || "false".equals(current)) {
            keys.add("true");
            keys.add("false");
        }
        if (field.length() >= 4 && !GENERIC_FIELDS.contains(field)) {
            collectGt(field, keys);
            collectBuiltin(field, keys);
        }
        if (field.contains("dimension")) collectLevels(keys);
        if (current != null && !current.isEmpty()) keys.add(current);
        return new ArrayList<>(keys);
    }

    public static String keyOfChoice(String choice) {
        if (choice == null) return "";
        int mark = choice.lastIndexOf(MARK);
        return mark >= 0 ? choice.substring(mark + MARK.length()) : choice.trim();
    }

    public static JsonElement defaultJson(String choice) {
        RecipeCondition created = create(keyOfChoice(choice));
        if (created == null) return null;
        return RecipeCondition.CODEC.encodeStart(JsonOps.INSTANCE, created).result().orElse(null);
    }

    /** Same shape as the duration line: unchanged, or {@code 需要研究 → 无}. */
    public static String diffLine(JsonArray before, JsonArray after) {
        String left = join(labels(before));
        String right = join(labels(after));
        if (left.equals(right)) return "§7条件 §8未改";
        if (left.isEmpty()) left = "无";
        if (right.isEmpty()) right = "无";
        return "§7条件 §6" + left + " §f→ §a" + right;
    }

    private static List<String> labels(JsonArray array) {
        List<String> labels = new ArrayList<>();
        if (array == null) return labels;
        for (JsonElement element : array) labels.add(label(element));
        return labels;
    }

    private static String join(List<String> labels) {
        StringBuilder line = new StringBuilder();
        for (String label : labels) {
            if (label == null || label.isEmpty()) continue;
            if (line.length() > 0) line.append("、");
            line.append(label);
        }
        return line.toString();
    }

    public static String label(JsonElement element) {
        if (element == null || element.isJsonNull()) return "未知条件";
        try {
            RecipeCondition condition = RecipeCondition.CODEC.parse(JsonOps.INSTANCE, element)
                    .result().orElse(null);
            if (condition != null && condition.getTooltips() != null) {
                String tip = condition.getTooltips().getString();
                if (tip != null && !tip.isEmpty()) {
                    return condition.isReverse() ? "非 " + tip : tip;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the type id carried in the codec object.
        }
        if (element.isJsonObject() && element.getAsJsonObject().has("type")
                && element.getAsJsonObject().get("type").isJsonPrimitive()) {
            return element.getAsJsonObject().get("type").getAsString();
        }
        return "未知条件";
    }

    private static RecipeCondition create(String key) {
        if (key == null || key.isEmpty()) return null;
        RecipeConditionType<?> type = GTRegistries.RECIPE_CONDITIONS.get(key);
        if (type == null || type.factory == null) return null;
        try {
            return type.factory.createDefault();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void walk(JsonObject object, String prefix, List<Parameter> found) {
        for (var entry : object.entrySet()) {
            if (prefix.isEmpty() && "type".equals(entry.getKey())) continue;
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            collect(path, entry.getValue(), found);
        }
    }

    private static void collect(String path, JsonElement value, List<Parameter> found) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonPrimitive()) {
            found.add(new Parameter(path, value.getAsString()));
            return;
        }
        if (value.isJsonObject()) {
            walk(value.getAsJsonObject(), path, found);
            return;
        }
        if (!value.isJsonArray()) return;
        JsonArray array = value.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) collect(path + "[" + i + "]", array.get(i), found);
    }

    private static boolean write(JsonElement node, List<String> tokens, String raw) {
        if (tokens.isEmpty()) return false;
        JsonElement current = node;
        for (int i = 0; i < tokens.size() - 1; i++) {
            current = child(current, tokens.get(i));
            if (current == null) return false;
        }
        String leaf = tokens.get(tokens.size() - 1);
        if (leaf.startsWith("[")) {
            if (current == null || !current.isJsonArray()) return false;
            int index = indexOf(leaf);
            JsonArray array = current.getAsJsonArray();
            if (index < 0 || index >= array.size() || !array.get(index).isJsonPrimitive()) return false;
            array.set(index, primitiveLike(array.get(index).getAsJsonPrimitive(), raw));
            return true;
        }
        if (current == null || !current.isJsonObject()) return false;
        JsonObject object = current.getAsJsonObject();
        if (!object.has(leaf) || !object.get(leaf).isJsonPrimitive()) return false;
        object.add(leaf, primitiveLike(object.get(leaf).getAsJsonPrimitive(), raw));
        return true;
    }

    private static JsonElement child(JsonElement node, String token) {
        if (token.startsWith("[")) {
            if (node == null || !node.isJsonArray()) return null;
            int index = indexOf(token);
            JsonArray array = node.getAsJsonArray();
            return index < 0 || index >= array.size() ? null : array.get(index);
        }
        if (node == null || !node.isJsonObject() || !node.getAsJsonObject().has(token)) return null;
        return node.getAsJsonObject().get(token);
    }

    private static JsonPrimitive primitiveLike(JsonPrimitive previous, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (previous.isBoolean()) return new JsonPrimitive(Boolean.parseBoolean(text));
        if (previous.isNumber()) {
            try {
                if (text.contains(".")) return new JsonPrimitive(Double.parseDouble(text));
                return new JsonPrimitive(Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return previous;
            }
        }
        return new JsonPrimitive(text);
    }

    private static List<String> tokens(String path) {
        List<String> tokens = new ArrayList<>();
        int index = 0;
        while (index < path.length()) {
            if (path.charAt(index) == '.') index++;
            if (index >= path.length()) break;
            if (path.charAt(index) == '[') {
                int end = path.indexOf(']', index);
                if (end < 0) break;
                tokens.add(path.substring(index, end + 1));
                index = end + 1;
            } else {
                int start = index;
                while (index < path.length() && path.charAt(index) != '.' && path.charAt(index) != '[') index++;
                tokens.add(path.substring(start, index));
            }
        }
        return tokens;
    }

    private static int indexOf(String token) {
        try {
            return Integer.parseInt(token.substring(1, token.length() - 1));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String fieldName(String path) {
        if (path == null || path.isEmpty()) return "";
        int dot = path.lastIndexOf('.');
        String field = dot >= 0 ? path.substring(dot + 1) : path;
        int bracket = field.indexOf('[');
        if (bracket >= 0) field = field.substring(0, bracket);
        return field;
    }

    private static void collectGt(String field, Set<String> keys) {
        try {
            for (java.lang.reflect.Field declared : GTRegistries.class.getFields()) {
                if (!Modifier.isStatic(declared.getModifiers())) continue;
                Object value = declared.get(null);
                if (!(value instanceof com.gregtechceu.gtceu.api.registry.GTRegistry<?, ?> registry)) continue;
                String name = declared.getName().toLowerCase();
                if (name.contains("dimension_type")) continue;
                if (!name.equals(field) && !name.startsWith(field + "_") && !name.endsWith("_" + field)) continue;
                for (Object key : registry.keys()) if (key != null) keys.add(String.valueOf(key));
            }
        } catch (IllegalAccessException ignored) {
            // A registry that cannot be read simply contributes no candidates.
        }
    }

    private static void collectBuiltin(String field, Set<String> keys) {
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            ResourceLocation id = registry.key().location();
            String path = id.getPath();
            if (path.contains("dimension_type")) continue;
            if (!path.equals(field) && !path.endsWith("/" + field) && !path.endsWith("_" + field)) continue;
            for (ResourceLocation key : registry.keySet()) keys.add(key.toString());
        }
    }

    private static void collectLevels(Set<String> keys) {
        keys.add("minecraft:overworld");
        keys.add("minecraft:the_nether");
        keys.add("minecraft:the_end");
        try {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection == null) return;
            for (var level : connection.levels()) {
                if (level != null && level.location() != null) keys.add(level.location().toString());
            }
        } catch (Throwable ignored) {
            // Dedicated server has no client connection; markers and the three vanilla ids remain.
        }
    }
}
