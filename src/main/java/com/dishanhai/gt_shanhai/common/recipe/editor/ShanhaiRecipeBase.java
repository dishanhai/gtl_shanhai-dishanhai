package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client-neutral recipe edit model. JSON fields use GTCEu's own content codec shape.
 */
public final class ShanhaiRecipeBase {

    private final String recipeTypeId;
    private final String recipeId;
    private final int duration;
    private final long eut;
    private final JsonObject inputs;
    private final JsonObject outputs;
    private final JsonObject tickInputs;
    private final JsonObject tickOutputs;
    private final JsonArray conditions;

    public ShanhaiRecipeBase(
            String recipeTypeId,
            String recipeId,
            int duration,
            long eut,
            JsonObject inputs,
            JsonObject outputs,
            JsonObject tickInputs,
            JsonArray conditions) {
        this(recipeTypeId, recipeId, duration, eut, inputs, outputs, tickInputs,
                new JsonObject(), conditions);
    }

    public ShanhaiRecipeBase(
            String recipeTypeId,
            String recipeId,
            int duration,
            long eut,
            JsonObject inputs,
            JsonObject outputs,
            JsonObject tickInputs,
            JsonObject tickOutputs,
            JsonArray conditions) {
        this.recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.duration = Math.max(1, duration);
        this.eut = eut;
        this.inputs = inputs == null ? new JsonObject() : inputs.deepCopy();
        this.outputs = outputs == null ? new JsonObject() : outputs.deepCopy();
        this.tickInputs = tickInputs == null ? new JsonObject() : tickInputs.deepCopy();
        this.tickOutputs = tickOutputs == null ? new JsonObject() : tickOutputs.deepCopy();
        this.conditions = conditions == null ? new JsonArray() : conditions.deepCopy();
    }

    public static ShanhaiRecipeBase simple(
            String recipeTypeId, String recipeId, int duration, long eut, int itemAmount) {
        JsonObject inputs = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject content = new JsonObject();
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("item", "minecraft:stone");
        content.addProperty("type", "gtceu:sized");
        content.addProperty("count", itemAmount);
        content.add("ingredient", ingredient);
        JsonObject item = new JsonObject();
        item.add("content", content);
        item.addProperty("chance", 10000);
        item.addProperty("maxChance", 10000);
        item.addProperty("tierChanceBoost", 0);
        items.add(item);
        inputs.add("item", items);
        return new ShanhaiRecipeBase(
                recipeTypeId, recipeId, duration, eut, inputs, new JsonObject(), new JsonObject(), new JsonArray());
    }

    public static ShanhaiRecipeBase from(GTRecipe recipe) {
        if (recipe == null) return null;
        String typeId = recipe.recipeType == null || recipe.recipeType.registryName == null
                ? "" : recipe.recipeType.registryName.toString();
        String id = recipe.getId() == null ? "" : recipe.getId().toString();
        return new ShanhaiRecipeBase(
                typeId,
                id,
                recipe.duration,
                ShanhaiRecipeIoApply.euOf(recipe),
                ShanhaiRecipeIoApply.tableJson(recipe, "inputs"),
                ShanhaiRecipeIoApply.tableJson(recipe, "outputs"),
                ShanhaiRecipeIoApply.tableJson(recipe, "tickInputs"),
                ShanhaiRecipeIoApply.tableJson(recipe, "tickOutputs"),
                conditionsJson(recipe));
    }

    public static ShanhaiRecipeBase fromPayload(JsonObject payload) {
        if (payload == null
                || !payload.has("recipeTypeId")
                || !payload.has("recipeId")
                || !payload.has("duration")
                || !payload.has("inputs")
                || !payload.has("outputs")) {
            return null;
        }
        return new ShanhaiRecipeBase(
                payload.get("recipeTypeId").getAsString(),
                payload.get("recipeId").getAsString(),
                payload.get("duration").getAsInt(),
                payload.has("eut") ? payload.get("eut").getAsLong() : 0L,
                payload.getAsJsonObject("inputs"),
                payload.getAsJsonObject("outputs"),
                payload.has("tickInputs") ? payload.getAsJsonObject("tickInputs") : new JsonObject(),
                payload.has("tickOutputs") ? payload.getAsJsonObject("tickOutputs") : new JsonObject(),
                payload.has("conditions") ? payload.getAsJsonArray("conditions") : new JsonArray());
    }

    public static ShanhaiRecipeBase inheritLegacyDetails(
            JsonObject payload, ShanhaiRecipeBase original, String baseFingerprint) {
        ShanhaiRecipeBase edited = fromPayload(payload);
        if (edited == null || original == null
                || !ShanhaiRecipeFingerprint.legacySnapshotFingerprint(original)
                        .equals(baseFingerprint)) {
            return edited;
        }
        JsonObject restoredTickOutputs = payload.has("tickOutputs")
                ? edited.tickOutputs() : original.tickOutputs();
        JsonArray restoredConditions = edited.conditions();
        if (restoredConditions.isEmpty() && !original.conditions().isEmpty()) {
            restoredConditions = original.conditions();
        }
        return new ShanhaiRecipeBase(
                edited.recipeTypeId(),
                edited.recipeId(),
                edited.duration(),
                edited.eut(),
                edited.inputs(),
                edited.outputs(),
                edited.tickInputs(),
                restoredTickOutputs,
                restoredConditions);
    }

    public GTRecipe toGtRecipe(GTRecipe original) {
        if (original == null) return null;
        GTRecipe copy = original.copy();
        copy.data = original.data.copy();
        copy.duration = duration;
        ShanhaiRecipeIoApply.applyTable(copy, "inputs", inputs);
        ShanhaiRecipeIoApply.applyTable(copy, "outputs", outputs);
        ShanhaiRecipeIoApply.applyTable(copy, "tickInputs", tickInputs);
        ShanhaiRecipeIoApply.applyTable(copy, "tickOutputs", tickOutputs);
        List<RecipeCondition> parsedConditions = new ArrayList<>();
        for (JsonElement element : conditions) {
            RecipeCondition.CODEC.parse(JsonOps.INSTANCE, element)
                    .result().ifPresent(parsedConditions::add);
        }
        copy.conditions.clear();
        copy.conditions.addAll(parsedConditions);
        if (eut != 0L) ShanhaiRecipeIoApply.applyEut(copy, eut);
        return copy;
    }

    public String recipeTypeId() {
        return recipeTypeId;
    }

    public String recipeId() {
        return recipeId;
    }

    public int duration() {
        return duration;
    }

    public long eut() {
        return eut;
    }

    public JsonObject inputs() {
        return inputs.deepCopy();
    }

    public JsonObject outputs() {
        return outputs.deepCopy();
    }

    public JsonObject tickInputs() {
        return tickInputs.deepCopy();
    }

    public JsonObject tickOutputs() {
        return tickOutputs.deepCopy();
    }

    public JsonArray conditions() {
        return conditions.deepCopy();
    }

    public ShanhaiRecipeBase withDuration(int value) {
        return new ShanhaiRecipeBase(recipeTypeId, recipeId, value, eut,
                inputs, outputs, tickInputs, tickOutputs, conditions);
    }

    public ShanhaiRecipeBase withItemAmount(int amount) {
        JsonObject copy = inputs();
        if (copy.has("item") && copy.get("item").isJsonArray() && copy.getAsJsonArray("item").size() > 0) {
            JsonObject first = copy.getAsJsonArray("item").get(0).getAsJsonObject();
            first.addProperty("count", amount);
        }
        return new ShanhaiRecipeBase(recipeTypeId, recipeId, duration, eut,
                copy, outputs, tickInputs, tickOutputs, conditions);
    }

    public JsonObject payloadJson() {
        JsonObject payload = new JsonObject();
        payload.addProperty("recipeTypeId", recipeTypeId);
        payload.addProperty("recipeId", recipeId);
        payload.addProperty("duration", duration);
        payload.addProperty("eut", eut);
        payload.add("inputs", inputs());
        payload.add("outputs", outputs());
        payload.add("tickInputs", tickInputs());
        payload.add("tickOutputs", tickOutputs());
        payload.add("conditions", conditions());
        return payload;
    }

    private static JsonArray conditionsJson(GTRecipe recipe) {
        JsonArray result = new JsonArray();
        if (recipe == null || recipe.conditions == null) return result;
        for (RecipeCondition condition : recipe.conditions) {
            if (condition == null) continue;
            RecipeCondition.CODEC.encodeStart(JsonOps.INSTANCE, condition)
                    .result().ifPresent(result::add);
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ShanhaiRecipeBase that)) return false;
        return duration == that.duration
                && eut == that.eut
                && Objects.equals(recipeTypeId, that.recipeTypeId)
                && Objects.equals(recipeId, that.recipeId)
                && payloadJson().equals(that.payloadJson());
    }

    @Override
    public int hashCode() {
        return payloadJson().toString().hashCode();
    }
}
