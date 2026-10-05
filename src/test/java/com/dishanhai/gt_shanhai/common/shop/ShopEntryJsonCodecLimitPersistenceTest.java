package com.dishanhai.gt_shanhai.common.shop;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShopEntryJsonCodecLimitPersistenceTest {

    private ShopEntry entry(long limit) {
        return new ShopEntry(new ResourceLocation("minecraft", "stone"), 1,
                ShopEntry.DEFAULT_CATEGORY, null,
                ShopCost.singleCoin(new ResourceLocation("minecraft", "diamond"), 1L), "test limit", limit);
    }

    @Test
    void payloadSeparatesSaveRemainingFromServerLimit() {
        ShopEntry entry = entry(20L);
        entry.consumeUses(7L);

        JsonObject payload = JsonParser.parseString(ShopEntryJsonCodec.toPayload(entry)).getAsJsonObject();
        assertEquals(20L, payload.get("limit").getAsLong());
        assertTrue(payload.has("remainingUses"), "Payload must carry the save balance separately");
        assertEquals(13L, payload.get("remainingUses").getAsLong());
        assertFalse(ShopEntryJsonCodec.toJson(entry).has("remainingUses"));
    }

    @Test
    void unlimitedSaveDoesNotEraseFiniteServerLimit() {
        ShopEntry entry = entry(20L);
        entry.overrideRemainingUses(-1L);

        assertEquals(-1L, entry.getRemainingUses());
        assertEquals(20L, ShopEntryJsonCodec.toJson(entry).get("limit").getAsLong());
    }

    @Test
    void finiteSaveCanOverrideUnlimitedServerLimit() {
        ShopEntry entry = entry(-1L);
        entry.overrideRemainingUses(3L);

        assertEquals(3L, entry.clampByUses(8L));
        assertEquals(-1L, entry.getConfiguredRemainingUses());
        assertFalse(ShopEntryJsonCodec.toJson(entry).has("limit"));
    }

    @Test
    void payloadDecodingRestoresSaveBalanceButConfigDecodingUsesServerBaseline() {
        ShopEntry entry = entry(20L);
        entry.consumeUses(7L);
        ShopEntry client = ShopEntryJsonCodec.fromPayload(ShopEntryJsonCodec.toPayload(entry));
        ShopEntry config = ShopEntryJsonCodec.fromJson(ShopEntryJsonCodec.toJson(entry));
        assertNotNull(client);
        assertNotNull(config);
        assertEquals(20L, client.getServerUses());
        assertEquals(13L, client.getRemainingUses());
        assertEquals(20L, config.getServerUses());
        assertEquals(20L, config.getRemainingUses());
    }

    @Test
    void legacyLimitIsClassifiedAsServerUses() {
        JsonObject legacy = JsonParser.parseString(
                "{\"goods\":\"minecraft:stone\",\"limit\":20,\"currency\":\"minecraft:diamond\",\"price\":1}")
                .getAsJsonObject();
        ShopEntry entry = ShopEntryJsonCodec.fromJson(legacy);
        assertNotNull(entry);
        assertEquals(20L, entry.getServerUses());
        assertEquals(20L, entry.getRemainingUses());
    }

    @Test
    void consumedRemainingUsesDoesNotLeakIntoSerializedLimit() {
        ShopEntry entry = new ShopEntry(
                new ResourceLocation("minecraft", "stone"),
                1,
                ShopEntry.DEFAULT_CATEGORY,
                null,
                ShopCost.singleCoin(new ResourceLocation("minecraft", "diamond"), 1L),
                "test limit",
                5L);

        entry.consumeUses(2L);

        JsonObject json = ShopEntryJsonCodec.toJson(entry);
        assertEquals(3L, entry.getRemainingUses());
        assertEquals(5L, json.get("limit").getAsLong());
    }
}
