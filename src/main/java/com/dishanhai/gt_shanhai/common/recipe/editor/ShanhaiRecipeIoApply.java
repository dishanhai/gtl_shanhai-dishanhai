package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ShanhaiRecipeIoApply {

    public static final String K_ITEM = "item";
    public static final String K_FLUID = "fluid";
    public static final String K_EU = "eu";

    private ShanhaiRecipeIoApply() {}

    public static JsonObject tableJson(GTRecipe recipe, String which) {
        JsonObject result = new JsonObject();
        if (recipe == null) return result;
        Map<RecipeCapability<?>, List<Content>> table = tableOf(recipe, which);
        if (table == null) return result;
        addCapability(result, table, ItemRecipeCapability.CAP, K_ITEM);
        addCapability(result, table, FluidRecipeCapability.CAP, K_FLUID);
        if ("tickInputs".equals(which) || "tickOutputs".equals(which)) {
            addCapability(result, table, EURecipeCapability.CAP, K_EU);
        }
        return result;
    }

    public static int applyTable(GTRecipe recipe, String which, JsonObject json) {
        if (recipe == null || json == null) return -1;
        Map<RecipeCapability<?>, List<Content>> table = tableOf(recipe, which);
        if (table == null) return -1;
        table.clear();
        int written = put(table, ItemRecipeCapability.CAP, json, K_ITEM);
        written += put(table, FluidRecipeCapability.CAP, json, K_FLUID);
        if ("tickInputs".equals(which) || "tickOutputs".equals(which)) {
            written += put(table, EURecipeCapability.CAP, json, K_EU);
        }
        return written;
    }

    public static long euOf(GTRecipe recipe) {
        if (recipe == null) return 0L;
        long input = RecipeHelper.getInputEUt(recipe);
        return input != 0L ? input : RecipeHelper.getOutputEUt(recipe);
    }

    public static boolean applyEut(GTRecipe recipe, long eut) {
        if (recipe == null) return false;
        if (RecipeHelper.getInputEUt(recipe) != 0L) {
            RecipeHelper.setInputEUt(recipe, eut);
        } else if (RecipeHelper.getOutputEUt(recipe) != 0L) {
            RecipeHelper.setOutputEUt(recipe, eut);
        } else {
            List<Content> list = new ArrayList<>();
            list.add(new Content(eut, 10000, 10000, 0, null, null));
            recipe.tickInputs.put(EURecipeCapability.CAP, list);
        }
        recipe.data = recipe.data.copy();
        recipe.data.putInt("euTier", com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(Math.abs(eut)));
        return true;
    }

    private static Map<RecipeCapability<?>, List<Content>> tableOf(GTRecipe recipe, String which) {
        return switch (which) {
            case "inputs" -> recipe.inputs;
            case "outputs" -> recipe.outputs;
            case "tickInputs" -> recipe.tickInputs;
            case "tickOutputs" -> recipe.tickOutputs;
            default -> null;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addCapability(
            JsonObject result,
            Map<RecipeCapability<?>, List<Content>> table,
            RecipeCapability capability,
            String key) {
        List<Content> contents = table.get(capability);
        if (contents == null || contents.isEmpty()) return;
        JsonArray array = new JsonArray();
        Codec<Content> codec = Content.codec(capability);
        for (Content content : contents) {
            codec.encodeStart(JsonOps.INSTANCE, content).result().ifPresent(array::add);
        }
        if (array.size() > 0) result.add(key, array);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int put(
            Map<RecipeCapability<?>, List<Content>> table,
            RecipeCapability capability,
            JsonObject json,
            String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) return 0;
        List<Content> contents = new ArrayList<>();
        Codec<Content> codec = Content.codec(capability);
        for (JsonElement element : value.getAsJsonArray()) {
            codec.parse(JsonOps.INSTANCE, element).result().ifPresent(contents::add);
        }
        if (!contents.isEmpty()) table.put(capability, contents);
        return contents.size();
    }
}
