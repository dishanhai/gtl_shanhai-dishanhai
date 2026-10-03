package com.dishanhai.gt_shanhai.common.recipe;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class DShanhaiRecipeEnabledCondition implements ICondition {
    public static final ResourceLocation ID = new ResourceLocation(GTDishanhaiMod.MOD_ID, "recipe_enabled");
    private static final String CONFIG_PATH = "kubejs/data/shanhai_recipe_load_config.json";

    private final ResourceLocation recipeId;
    private final boolean defaultEnabled;

    public DShanhaiRecipeEnabledCondition(ResourceLocation recipeId, boolean defaultEnabled) {
        this.recipeId = Objects.requireNonNull(recipeId, "recipeId");
        this.defaultEnabled = defaultEnabled;
    }

    @Override
    public ResourceLocation getID() {
        return ID;
    }

    @Override
    public boolean test(IContext context) {
        return isEnabled(recipeId, defaultEnabled, readConfig());
    }

    static boolean isEnabled(ResourceLocation recipeId, boolean defaultEnabled, JsonObject config) {
        String path = recipeId.getPath();
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        for (String key : new String[]{recipeId.toString(), path, leaf}) {
            if (!config.has(key)) continue;
            JsonElement value = config.get(key);
            if (!value.isJsonPrimitive()) continue;
            if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
            if (value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString().trim();
                if ("true".equalsIgnoreCase(text)) return true;
                if ("false".equalsIgnoreCase(text)) return false;
            }
        }
        return defaultEnabled;
    }

    private static JsonObject readConfig() {
        Path path = FMLPaths.GAMEDIR.get().resolve(CONFIG_PATH);
        if (!Files.isRegularFile(path)) return new JsonObject();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            GTDishanhaiMod.LOGGER.warn("Recipe load config is not a JSON object: {}", path);
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.warn("Unable to read recipe load config: {}", path, e);
        }
        return new JsonObject();
    }

    public static final class Serializer implements IConditionSerializer<DShanhaiRecipeEnabledCondition> {
        public static final Serializer INSTANCE = new Serializer();

        private Serializer() {}

        @Override
        public ResourceLocation getID() {
            return ID;
        }

        @Override
        public void write(JsonObject json, DShanhaiRecipeEnabledCondition condition) {
            json.addProperty("recipeId", condition.recipeId.toString());
            json.addProperty("defaultEnabled", condition.defaultEnabled);
        }

        @Override
        public DShanhaiRecipeEnabledCondition read(JsonObject json) {
            return new DShanhaiRecipeEnabledCondition(
                    new ResourceLocation(GsonHelper.getAsString(json, "recipeId")),
                    GsonHelper.getAsBoolean(json, "defaultEnabled", true));
        }
    }
}
