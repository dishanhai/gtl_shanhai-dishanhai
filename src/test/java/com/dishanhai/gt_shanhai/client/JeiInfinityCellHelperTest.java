package com.dishanhai.gt_shanhai.client;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class JeiInfinityCellHelperTest {

    @Test
    void buildsItemInfinityRecordTag() {
        JeiCopyShortcutHelper.InfinityCellTarget target = new JeiCopyShortcutHelper.InfinityCellTarget("minecraft:stick", false);
        CompoundTag tag = JeiCopyShortcutHelper.buildInfinityCellTag(target);

        assertNotNull(tag);
        assertEquals("ae2:i", tag.getCompound("record").getString("#c"));
        assertEquals("minecraft:stick", tag.getCompound("record").getString("id"));
    }

    @Test
    void buildsFluidInfinityRecordTag() {
        JeiCopyShortcutHelper.InfinityCellTarget target = new JeiCopyShortcutHelper.InfinityCellTarget("minecraft:water", true);

        assertEquals("ae2:f", JeiCopyShortcutHelper.buildInfinityCellTag(target).getCompound("record").getString("#c"));
        assertEquals("minecraft:water", JeiCopyShortcutHelper.buildInfinityCellTag(target).getCompound("record").getString("id"));
    }

    @Test
    void returnsNullForUnsupportedIngredient() {
        JeiCopyShortcutHelper.InfinityCellTarget target = JeiCopyShortcutHelper.resolveInfinityCellTarget(
                typedIngredient("not-supported"));

        assertNull(target);
    }

    private static ITypedIngredient<?> typedIngredient(final Object ingredient) {
        return new ITypedIngredient<Object>() {
            @Override
            public IIngredientType<Object> getType() {
                return null;
            }

            @Override
            public Object getIngredient() {
                return ingredient;
            }
        };
    }
}
