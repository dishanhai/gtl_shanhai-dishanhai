package com.dishanhai.gt_shanhai.common.shop;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopEnergyGoodsTest {
    private ShopEntry energyEntry(List<ShopEntry.GoodsStack> goods) {
        return new ShopEntry(goods, ShopEntry.DEFAULT_CATEGORY, new ShopCost(BigInteger.ZERO, null, null),
                "", -1, null, ShopEntry.RewardMode.NONE, null, false, "", "", "", "", ShopEntry.RewardMode.RANDOM);
    }

    @Test
    void energyIsBuyOnlyAndMixedOrMalformedGoodsAreRejected() {
        ShopEntry.GoodsStack energy = ShopEntry.GoodsStack.ofEnergy(BigInteger.TEN);
        ShopEntry entry = energyEntry(List.of(energy));
        assertTrue(entry.isValid());
        assertTrue(entry.allowsBuy());
        assertFalse(entry.allowsSell());
        assertFalse(energyEntry(List.of(energy, energy)).isValid());
        assertFalse(energyEntry(List.of(ShopEntry.GoodsStack.of(ShopEntry.GoodsStack.ENERGY_ID, 1, null))).isValid());
    }
    @Test
    void rejectsInvalidAmountsAndTreatsMalformedDataAsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> ShopEntry.GoodsStack.ofEnergy(BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ShopEntry.GoodsStack.ofEnergy(BigInteger.valueOf(-1)));
        assertThrows(IllegalArgumentException.class, () -> ShopEntry.GoodsStack.ofEnergy(BigInteger.TEN.pow(256)));
        assertEquals(BigInteger.ZERO, ShopEntry.GoodsStack.of(ShopEntry.GoodsStack.ENERGY_ID, 1, null).energyAmount());
    }
    @Test
    void energySurvivesCatalogJsonWithoutTruncation() {
        BigInteger amount = new BigInteger("123456789012345678901234567890");
        ShopEntry original = new ShopEntry(List.of(ShopEntry.GoodsStack.ofEnergy(amount)),
                ShopEntry.DEFAULT_CATEGORY, ShopCost.singleCoin(new net.minecraft.resources.ResourceLocation("minecraft:diamond"), 1),
                "", -1, null, ShopEntry.RewardMode.NONE, null, false, "", "", "", "", ShopEntry.RewardMode.RANDOM);
        ShopEntry decoded = ShopEntryJsonCodec.fromJson(ShopEntryJsonCodec.toJson(original));
        assertNotNull(decoded);
        assertTrue(decoded.getGoodsList().get(0).isEnergy());
        assertEquals(amount, decoded.getGoodsList().get(0).energyAmount());
        assertEquals(amount.multiply(BigInteger.valueOf(Long.MAX_VALUE)),
                decoded.getGoodsList().get(0).energyAmount().multiply(BigInteger.valueOf(Long.MAX_VALUE)));
    }
}
