package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class ShanhaiPatternModifierMetadataTest {

    @Test
    void keepsReplacementEncodingAndCopiesEveryOtherTopLevelField() {
        CompoundTag sourceTag = new CompoundTag();
        sourceTag.put("in", list("old-input"));
        sourceTag.put("out", list("old-output"));
        sourceTag.putString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE, "gtceu:large_chemical_reactor");
        sourceTag.putBoolean("gt_shanhai_recipe_type_authoritative", true);
        sourceTag.putString("third_party_metadata", "keep-me");

        CompoundTag replacementTag = new CompoundTag();
        replacementTag.put("in", list("new-input"));
        replacementTag.put("out", list("new-output"));

        ShanhaiPatternModifier.copyPatternMetadata(sourceTag, replacementTag);

        assertEquals("new-input", replacementTag.getList("in", 8).getString(0));
        assertEquals("new-output", replacementTag.getList("out", 8).getString(0));
        assertEquals("gtceu:large_chemical_reactor",
                replacementTag.getString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE));
        assertTrue(replacementTag.getBoolean("gt_shanhai_recipe_type_authoritative"));
        assertEquals("keep-me", replacementTag.getString("third_party_metadata"));
    }

    private static ListTag list(String value) {
        ListTag list = new ListTag();
        list.add(StringTag.valueOf(value));
        return list;
    }
}
