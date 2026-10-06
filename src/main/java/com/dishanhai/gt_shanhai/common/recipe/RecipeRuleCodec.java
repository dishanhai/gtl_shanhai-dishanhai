package com.dishanhai.gt_shanhai.common.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class RecipeRuleCodec {

    private RecipeRuleCodec() {}

    public static JsonObject toJson(RecipeRuleState state) {
        JsonObject root = new JsonObject();
        JsonObject strip = new JsonObject();
        for (Map.Entry<String, java.util.List<RecipeRuleState.StripRule>> entry
                : state.stripRules().entrySet()) {
            JsonArray values = new JsonArray();
            for (RecipeRuleState.StripRule rule : entry.getValue()) {
                JsonObject value = new JsonObject();
                value.addProperty("item", rule.targetItem());
                value.addProperty("input", rule.input());
                value.addProperty("fluid", rule.fluid());
                value.addProperty("recipeId", rule.recipeId());
                values.add(value);
            }
            strip.add(entry.getKey(), values);
        }
        root.add("strip", strip);

        JsonObject replace = new JsonObject();
        for (Map.Entry<String, java.util.List<RecipeRuleState.ReplaceRule>> entry
                : state.replaceRules().entrySet()) {
            JsonArray values = new JsonArray();
            for (RecipeRuleState.ReplaceRule rule : entry.getValue()) {
                JsonObject value = new JsonObject();
                value.addProperty("old", rule.oldItem());
                value.addProperty("new", rule.newItem());
                value.addProperty("oldFluid", rule.oldFluid());
                value.addProperty("newFluid", rule.newFluid());
                value.addProperty("recipeId", rule.recipeId());
                value.addProperty("count", rule.count());
                value.addProperty("circuit", rule.circuitNumber());
                values.add(value);
            }
            replace.add(entry.getKey(), values);
        }
        root.add("replace", replace);

        JsonObject delete = new JsonObject();
        for (Map.Entry<String, java.util.List<RecipeRuleState.DeleteRule>> entry
                : state.deleteRules().entrySet()) {
            JsonArray values = new JsonArray();
            for (RecipeRuleState.DeleteRule rule : entry.getValue()) {
                values.add(rule.recipeRegex());
            }
            delete.add(entry.getKey(), values);
        }
        root.add("delete", delete);

        JsonObject toggles = new JsonObject();
        for (Map.Entry<String, Boolean> entry : state.toggles().entrySet()) {
            toggles.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("toggles", toggles);

        JsonArray presets = new JsonArray();
        for (String preset : state.activePresets()) presets.add(preset);
        root.add("activePresets", presets);
        return root;
    }

    public static RecipeRuleState fromJson(JsonObject root) {
        RecipeRuleState.Builder builder = RecipeRuleState.builder();
        if (root == null) return builder.build();

        JsonObject strip = object(root, "strip");
        for (Map.Entry<String, JsonElement> entry : strip.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                JsonObject rule = element.getAsJsonObject();
                builder.addStrip(
                        entry.getKey(),
                        string(rule, "item", ""),
                        bool(rule, "input", true),
                        bool(rule, "fluid", false),
                        string(rule, "recipeId", ""));
            }
        }

        JsonObject replace = object(root, "replace");
        for (Map.Entry<String, JsonElement> entry : replace.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                JsonObject rule = element.getAsJsonObject();
                builder.addReplace(
                        entry.getKey(),
                        string(rule, "old", ""),
                        string(rule, "new", ""),
                        bool(rule, "oldFluid", false),
                        bool(rule, "newFluid", false),
                        string(rule, "recipeId", ""),
                        integer(rule, "count", 0),
                        integer(rule, "circuit", -1));
            }
        }

        JsonObject delete = object(root, "delete");
        for (Map.Entry<String, JsonElement> entry : delete.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                builder.addDelete(entry.getKey(), element.getAsString());
            }
        }

        JsonObject toggles = object(root, "toggles");
        for (Map.Entry<String, JsonElement> entry : toggles.entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isBoolean()) {
                builder.setToggle(entry.getKey(), entry.getValue().getAsBoolean());
            }
        }

        JsonElement presets = root.get("activePresets");
        if (presets != null && presets.isJsonArray()) {
            for (JsonElement element : presets.getAsJsonArray()) {
                builder.addActivePreset(element.getAsString());
            }
        }
        return builder.build();
    }

    public static RecipeRuleState read(Path path) throws java.io.IOException {
        return fromJson(com.google.gson.JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject());
    }

    public static void write(Path path, RecipeRuleState state) throws java.io.IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(toJson(state)), StandardCharsets.UTF_8);
    }

    private static JsonObject object(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsBoolean();
    }

    private static int integer(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsInt();
    }
}
