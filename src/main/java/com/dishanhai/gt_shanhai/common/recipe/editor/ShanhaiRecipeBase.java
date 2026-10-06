package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.resources.ResourceLocation;

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
        this.recipeTypeId = recipeTypeId == null ? "" : recipeTypeId;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.duration = Math.max(1, duration);
        this.eut = eut;
        this.inputs = inputs == null ? new JsonObject() : inputs.deepCopy();
        this.outputs = outputs == null ? new JsonObject() : outputs.deepCopy();
        this.tickInputs = tickInputs == null ? new JsonObject() : tickInputs.deepCopy();
        this.conditions = conditions == null ? new JsonArray() : conditions.deepCopy();
    }

    public static ShanhaiRecipeBase simple(
            String recipeTypeId, String recipeId, int duration, long eut, int itemAmount) {
        JsonObject inputs = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("count", itemAmount);
        item.addProperty("item", "minecraft:stone");
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
                new JsonArray());
    }

    public GTRecipe toGtRecipe(GTRecipe original) {
        if (original == null) return null;
        GTRecipe copy = original.copy();
        copy.data = original.data.copy();
        copy.duration = duration;
        ShanhaiRecipeIoApply.applyTable(copy, "inputs", inputs);
        ShanhaiRecipeIoApply.applyTable(copy, "outputs", outputs);
        ShanhaiRecipeIoApply.applyTable(copy, "tickInputs", tickInputs);
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

    public JsonArray conditions() {
        return conditions.deepCopy();
    }

    public ShanhaiRecipeBase withDuration(int value) {
        return new ShanhaiRecipeBase(recipeTypeId, recipeId, value, eut, inputs, outputs, tickInputs, conditions);
    }

    public ShanhaiRecipeBase withItemAmount(int amount) {
        JsonObject copy = inputs();
        if (copy.has("item") && copy.get("item").isJsonArray() && copy.getAsJsonArray("item").size() > 0) {
            JsonObject first = copy.getAsJsonArray("item").get(0).getAsJsonObject();
            first.addProperty("count", amount);
        }
        return new ShanhaiRecipeBase(recipeTypeId, recipeId, duration, eut, copy, outputs, tickInputs, conditions);
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
        payload.add("conditions", conditions());
        return payload;
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
