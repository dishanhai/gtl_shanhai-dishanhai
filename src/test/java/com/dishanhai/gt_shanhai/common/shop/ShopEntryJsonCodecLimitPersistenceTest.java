package com.dishanhai.gt_shanhai.common.shop;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopEntryJsonCodecLimitPersistenceTest {

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
