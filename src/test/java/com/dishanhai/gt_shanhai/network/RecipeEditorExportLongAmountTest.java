package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeJsExport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorExportLongAmountTest {

    @Test
    void tenThousandSizedSlotsBecomeLongIncludingCatalysts() {
        JsonObject recipe = JsonParser.parseString("""
                {
                  "inputs": {"item": [
                    {"content": {"type": "gtceu:sized", "count": 1, "ingredient": {"item": "dishanhai:wzcz2"}}, "chance": 0},
                    {"content": {"type": "gtceu:sized", "count": 10000, "ingredient": {"item": "gtceu:platinum_group_sludge_dust"}}}
                  ]},
                  "outputs": {"item": [
                    {"content": {"type": "gtceu:sized", "count": 10000, "ingredient": {"item": "gtceu:osmium_dust"}}}
                  ]}
                }
                """).getAsJsonObject();

        RecipeEditorExportPacket.writeLongItemAmounts(recipe);

        JsonObject catalyst = recipe.getAsJsonObject("inputs").getAsJsonArray("item")
                .get(0).getAsJsonObject().getAsJsonObject("content");
        JsonObject sludge = recipe.getAsJsonObject("inputs").getAsJsonArray("item")
                .get(1).getAsJsonObject().getAsJsonObject("content");
        JsonObject output = recipe.getAsJsonObject("outputs").getAsJsonArray("item")
                .get(0).getAsJsonObject().getAsJsonObject("content");
        assertEquals("gtceu:long", catalyst.get("type").getAsString());
        assertEquals(1L, catalyst.get("actualAmount").getAsLong());
        assertFalse(catalyst.has("count"));
        assertEquals(0, recipe.getAsJsonObject("inputs").getAsJsonArray("item")
                .get(0).getAsJsonObject().get("chance").getAsInt());
        assertEquals("gtceu:long", sludge.get("type").getAsString());
        assertEquals(10000L, sludge.get("actualAmount").getAsLong());
        assertEquals("gtceu:long", output.get("type").getAsString());
        assertEquals(10000L, output.get("actualAmount").getAsLong());
    }

    @Test
    void smallSizedRecipesStaySized() {
        JsonObject recipe = JsonParser.parseString("""
                {"inputs": {"item": [
                  {"content": {"type": "gtceu:sized", "count": 64, "ingredient": {"item": "minecraft:stone"}}}
                ]}}
                """).getAsJsonObject();

        RecipeEditorExportPacket.writeLongItemAmounts(recipe);

        JsonObject content = recipe.getAsJsonObject("inputs").getAsJsonArray("item")
                .get(0).getAsJsonObject().getAsJsonObject("content");
        assertEquals("gtceu:sized", content.get("type").getAsString());
        assertEquals(64, content.get("count").getAsInt());
        assertFalse(content.has("actualAmount"));
    }

    @Test
    void exportWritesNoteIntoRecipeConditions() {
        JsonObject recipe = JsonParser.parseString("""
                {"recipeConditions": [{"type": "cleanroom", "cleanroom": "cleanroom"}]}
                """).getAsJsonObject();
        JsonArray editor = JsonParser.parseString("""
                [
                  {"type": "cleanroom", "cleanroom": "cleanroom"},
                  {"type": "recipe_note", "text": "槽位溢出放不下"}
                ]
                """).getAsJsonArray();

        RecipeEditorExportPacket.writeEditorConditions(recipe, editor);

        JsonArray written = recipe.getAsJsonArray("recipeConditions");
        assertEquals(2, written.size());
        assertEquals("cleanroom", written.get(0).getAsJsonObject().get("type").getAsString());
        JsonObject note = written.get(1).getAsJsonObject();
        assertEquals("recipe_note", note.get("type").getAsString());
        assertEquals("槽位溢出放不下", note.get("text").getAsString());
        assertFalse(recipe.has("conditions"));
    }

    @Test
    void exportWritesChosenDefaultEnabled() {
        JsonObject recipe = new JsonObject();
        ResourceLocation id = new ResourceLocation("gt_shanhai", "assembler/assembler_maintenance_hatch");

        RecipeEditorExportPacket.writeEnabledCondition(recipe, id, false);

        JsonObject created = recipe.getAsJsonArray("conditions").get(0).getAsJsonObject();
        assertEquals("gt_shanhai:recipe_enabled", created.get("type").getAsString());
        assertEquals("gt_shanhai:assembler/assembler_maintenance_hatch", created.get("recipeId").getAsString());
        assertFalse(created.get("defaultEnabled").getAsBoolean());

        RecipeEditorExportPacket.writeEnabledCondition(recipe, id, true);
        assertTrue(recipe.getAsJsonArray("conditions").get(0).getAsJsonObject()
                .get("defaultEnabled").getAsBoolean());
        assertEquals(1, recipe.getAsJsonArray("conditions").size());

        JsonObject payload = new JsonObject();
        payload.addProperty("defaultEnabled", false);
        assertFalse(RecipeEditorExportPacket.defaultEnabledOf(payload));
        assertTrue(RecipeEditorExportPacket.defaultEnabledOf(new JsonObject()));
    }

    @Test
    void enabledConditionKeepsTheFolder() {
        ResourceLocation id = new ResourceLocation("gt_shanhai", "distort/wzcz2_palladium_dust");
        assertEquals("gt_shanhai:distort/wzcz2_palladium_dust", ShanhaiRecipeJsExport.enabledRecipeId(id));
        assertTrue(ShanhaiRecipeJsExport.enabledRecipeId(id).contains("distort/"));
    }
}
