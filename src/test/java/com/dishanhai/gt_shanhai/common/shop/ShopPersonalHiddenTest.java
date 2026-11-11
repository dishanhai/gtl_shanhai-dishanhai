package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopPersonalHiddenTest {
    @Test
    void hiddenIdsPersistPerPlayerAndCanBeRestoredIndependently() {
        var first = new CompoundTag();
        var second = new CompoundTag();
        first.putInt("another_setting", 23);

        ShopPersonalHidden.update(first, List.of("apple", "pear", "pear"), true);
        ShopPersonalHidden.update(second, List.of("orange"), true);
        var reloaded = first.copy();
        assertEquals(Set.of("apple", "pear"), ShopPersonalHidden.read(reloaded));
        assertEquals(Set.of("orange"), ShopPersonalHidden.read(second));

        ShopPersonalHidden.update(reloaded, List.of("pear"), false);
        assertEquals(Set.of("apple"), ShopPersonalHidden.read(reloaded));
        assertEquals(Set.of("orange"), ShopPersonalHidden.read(second));
        assertEquals(23, reloaded.getInt("another_setting"));
    }
}
