package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Server-side validation shared by the editor packet and the persistence layer.
 *
 * <p>The editor is a privileged tool, but its payload still arrives from a
 * client. Validation therefore happens before touching the override file or a
 * live recipe lookup.</p>
 */
public final class ShanhaiRecipeEditorValidation {

    public static final int MAX_PAYLOAD_CHARS = 32767;
    public static final int MAX_CONTENT_ENTRIES = 65536;
    public static final int MAX_CONDITIONS = 256;

    private ShanhaiRecipeEditorValidation() {}

    public static String validateBase(ShanhaiRecipeBase base) {
        if (base == null) return "invalid-edit";
        if (ResourceLocation.tryParse(base.recipeTypeId()) == null) return "invalid-recipe-type";
        if (ResourceLocation.tryParse(base.recipeId()) == null) return "invalid-recipe-id";
        if (base.duration() < 1) return "invalid-duration";
        String inputError = validateTable(base.inputs(), false);
        if (inputError != null) return inputError;
        String outputError = validateTable(base.outputs(), false);
        if (outputError != null) return outputError;
        String tickInputError = validateTable(base.tickInputs(), true);
        if (tickInputError != null) return tickInputError;
        String tickOutputError = validateTable(base.tickOutputs(), true);
        if (tickOutputError != null) return tickOutputError;
        JsonArray conditions = base.conditions();
        if (conditions.size() > MAX_CONDITIONS) return "too-many-conditions";
        for (JsonElement element : conditions) {
            if (RecipeCondition.CODEC.parse(JsonOps.INSTANCE, element).result().isEmpty()) {
                return "invalid-recipe-condition";
            }
        }
        return null;
    }

    public static String validateTable(JsonObject table, boolean tick) {
        if (table == null) return "missing-recipe-table";
        for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
            String key = entry.getKey();
            if (!"item".equals(key) && !"fluid".equals(key) && !(tick && "eu".equals(key))) {
                return "unsupported-recipe-capability:" + key;
            }
            if (!entry.getValue().isJsonArray()) return "recipe-capability-not-array:" + key;
            JsonArray values = entry.getValue().getAsJsonArray();
            if (values.size() > MAX_CONTENT_ENTRIES) return "too-many-content-entries:" + key;
            Codec<Content> codec = codec(key);
            for (JsonElement value : values) {
                if (codec.parse(JsonOps.INSTANCE, value).result().isEmpty()) {
                    return "invalid-content:" + key;
                }
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Codec<Content> codec(String key) {
        if ("item".equals(key)) return (Codec) Content.codec(ItemRecipeCapability.CAP);
        if ("fluid".equals(key)) return (Codec) Content.codec(FluidRecipeCapability.CAP);
        return (Codec) Content.codec(EURecipeCapability.CAP);
    }
}
