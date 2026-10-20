package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 複查報告 P1-01：編輯器改的溫度／資料欄位必須進持久化 payload，
 * 但不得改變指紋語義（既有覆寫檔的 baseFingerprint 不能因此全部失效）。
 */
class ShanhaiRecipeBasePayloadPersistenceTest {

    private static ShanhaiRecipeBase base(int blastTemp, JsonObject recipeData) {
        JsonObject inputs = new JsonObject();
        JsonArray items = new JsonArray();
        JsonObject content = new JsonObject();
        content.addProperty("type", "gtceu:sized");
        content.addProperty("count", 1);
        JsonObject item = new JsonObject();
        item.add("content", content);
        item.addProperty("chance", 10000);
        item.addProperty("maxChance", 10000);
        item.addProperty("tierChanceBoost", 0);
        items.add(item);
        inputs.add("item", items);
        return new ShanhaiRecipeBase(
                "gtceu:electrical_blast_furnace",
                "gtceu:test/steel",
                200,
                120L,
                inputs,
                new JsonObject(),
                new JsonObject(),
                new JsonObject(),
                new JsonArray(),
                blastTemp,
                recipeData);
    }

    @Test
    void payloadCarriesBlastTempAndRecipeData() {
        JsonObject data = new JsonObject();
        data.addProperty("SCTier", 3);
        ShanhaiRecipeBase original = base(1800, data);

        JsonObject payload = original.payloadJson();
        assertTrue(payload.has("blastTemp"), "blastTemp 必須寫入覆寫 payload");
        assertEquals(1800, payload.get("blastTemp").getAsInt());
        assertTrue(payload.has("recipeData"), "recipeData 必須寫入覆寫 payload");
        assertEquals(3, payload.getAsJsonObject("recipeData").get("SCTier").getAsInt());
    }

    @Test
    void payloadRoundTripKeepsTemperatureAndData() {
        JsonObject data = new JsonObject();
        data.addProperty("SCTier", 2);
        ShanhaiRecipeBase original = base(2000, data);

        ShanhaiRecipeBase restored = ShanhaiRecipeBase.fromPayload(original.payloadJson());
        assertEquals(2000, restored.blastTemp());
        assertEquals(2, restored.recipeData().get("SCTier").getAsInt());
        assertEquals(original, restored);
    }

    @Test
    void withCopiesKeepTemperatureAndData() {
        JsonObject data = new JsonObject();
        data.addProperty("SCTier", 4);
        ShanhaiRecipeBase original = base(2100, data);

        assertEquals(2100, original.withDuration(50).blastTemp());
        assertEquals(4, original.withDuration(50).recipeData().get("SCTier").getAsInt());
        assertEquals(2100, original.withItemAmount(2).blastTemp());
        assertEquals(2100, original.withRecipeId("gtceu:test/steel2").blastTemp());
        assertEquals(4, original.withRecipeTypeId("gtceu:alloy_blast_smelter")
                .recipeData().get("SCTier").getAsInt());
    }

    @Test
    void fingerprintIgnoresTemperatureSoExistingOverridesStayValid() {
        ShanhaiRecipeBase cold = base(1800, new JsonObject());
        ShanhaiRecipeBase hot = base(3000, new JsonObject());

        assertEquals(ShanhaiRecipeFingerprint.of(cold), ShanhaiRecipeFingerprint.of(hot),
                "溫度不屬於基準指紋，改了溫度不該讓覆寫變成衝突");
        assertNotEquals(ShanhaiRecipeFingerprint.of(cold),
                ShanhaiRecipeFingerprint.of(cold.withDuration(400)));
    }
}
