package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopLimitSavedDataTest {

    private ShopEntry entry(long serverUses) {
        return new ShopEntry(new ResourceLocation("minecraft", "stone"), 1,
                ShopEntry.DEFAULT_CATEGORY, null,
                ShopCost.singleCoin(new ResourceLocation("minecraft", "diamond"), 1L), "", serverUses);
    }

    @Test
    void newSaveUsesServerBaselineEvenWhenCatalogStillContainsAnotherSaveBalance() {
        ShopEntry entry = entry(20L);
        ShopLimitSavedData first = new ShopLimitSavedData();
        first.applyTo(entry);
        entry.consumeUses(20L);
        first.set(entry.getStableId(), entry.getRemainingUses());

        ShopLimitSavedData second = new ShopLimitSavedData();
        second.applyTo(entry);
        assertEquals(20L, entry.getRemainingUses());
        assertEquals(20L, second.get(entry.getStableId()).longValue());
        first.applyTo(entry);
        assertEquals(0L, entry.getRemainingUses());
        assertEquals(20L, entry.getServerUses());
    }

    @Test
    void legacySavedZeroSurvivesNbtRoundTripAndInitialization() {
        ShopEntry entry = entry(20L);
        ShopLimitSavedData data = new ShopLimitSavedData();
        data.set(entry.getStableId(), 0L);

        ShopLimitSavedData restored = ShopLimitSavedData.load(data.save(new CompoundTag()));
        restored.applyTo(entry);
        assertEquals(0L, entry.getRemainingUses());
        assertEquals(20L, entry.getServerUses());
    }

    @Test
    void savedUnlimitedIsNotReinitializedByFiniteServerLimit() {
        ShopEntry entry = entry(20L);
        ShopLimitSavedData data = new ShopLimitSavedData();
        data.set(entry.getStableId(), -1L);
        ShopLimitSavedData restored = ShopLimitSavedData.load(data.save(new CompoundTag()));
        restored.applyTo(entry);
        assertEquals(-1L, entry.getRemainingUses());
        assertEquals(20L, entry.getServerUses());
    }

    @Test
    void resetTouchesOnlyTheCurrentSave() {
        ShopEntry entry = entry(20L);
        ShopLimitSavedData first = new ShopLimitSavedData();
        ShopLimitSavedData second = new ShopLimitSavedData();
        first.set(entry.getStableId(), 0L);
        second.set(entry.getStableId(), 4L);
        first.reset(entry);
        assertEquals(20L, first.get(entry.getStableId()).longValue());
        assertEquals(4L, second.get(entry.getStableId()).longValue());
        assertEquals(20L, entry.getRemainingUses());
        assertEquals(20L, entry.getServerUses());
    }

    @Test
    void resetAlsoRestoresUnlimitedServerBaseline() {
        ShopEntry entry = entry(-1L);
        ShopLimitSavedData data = new ShopLimitSavedData();
        data.set(entry.getStableId(), 3L);
        data.applyTo(entry);
        data.reset(entry);
        assertEquals(-1L, entry.getRemainingUses());
        assertEquals(-1L, data.get(entry.getStableId()).longValue());
    }
}
