package com.dishanhai.gt_shanhai.client.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ShopJeiProductCatalogTest {

    @Test
    void productIdentityUsesItemIdAndExactNbt() {
        CompoundTag firstTag = new CompoundTag();
        firstTag.putString("variant", "first");
        CompoundTag otherTag = new CompoundTag();
        otherTag.putString("variant", "second");
        ResourceLocation itemId = new ResourceLocation("minecraft", "paper");

        ShopJeiProductCatalog.StackIdentity firstVariant =
                new ShopJeiProductCatalog.StackIdentity(itemId, firstTag);
        ShopJeiProductCatalog.StackIdentity sameVariant =
                new ShopJeiProductCatalog.StackIdentity(itemId, firstTag.copy());
        ShopJeiProductCatalog.StackIdentity otherVariant =
                new ShopJeiProductCatalog.StackIdentity(itemId, otherTag);

        assertEquals(firstVariant, sameVariant);
        assertNotEquals(firstVariant, otherVariant);
        firstTag.putString("variant", "mutated");
        assertEquals(firstVariant, sameVariant);
    }
}
