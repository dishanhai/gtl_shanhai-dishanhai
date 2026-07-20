package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ShanhaiPatternModifierIntegrationSourceTest {

    private static final Path BEHAVIOR = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiPatternTestBehavior.java");
    private static final Path MODIFIER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiPatternModifier.java");
    private static final Path PATTERN_BUFFER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");
    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void debugToolOwnsAllProviderPatternModifierSettings() throws Exception {
        String source = Files.readString(BEHAVIOR);

        assertTrue(source.contains("KEY_MODIFIER_PATTERN_MULTIPLIER"));
        assertTrue(source.contains("KEY_MODIFIER_PATTERN_DIVISOR"));
        assertTrue(source.contains("KEY_MODIFIER_OUTPUT_MULTIPLIER"));
        assertTrue(source.contains("KEY_MODIFIER_OUTPUT_DIVISOR"));
        assertTrue(source.contains("KEY_MODIFIER_MAX_ITEM"));
        assertTrue(source.contains("KEY_MODIFIER_MAX_FLUID"));
        assertTrue(source.contains("KEY_MODIFIER_APPLIED_TIMES"));
        assertTrue(source.contains("ShanhaiPatternModifier.modifyInventory"));
    }

    @Test
    void replacementPatternKeepsEveryNonEncodingTopLevelField() throws Exception {
        String source = Files.readString(MODIFIER);

        assertTrue(source.contains("copyPatternMetadata(current, replacement)"));
        assertTrue(source.contains("sourceTag.getAllKeys()"));
        assertTrue(source.contains("AE_INPUTS_KEY.equals(key) || AE_OUTPUTS_KEY.equals(key)"));
        assertTrue(source.contains("replacementTag.put(key, value.copy())"));
        assertTrue(source.contains("PatternRecipeTypeHelper.readRecipeTypeId(source)"));
        assertTrue(source.contains("PatternRecipeTypeHelper.readRecipeTypeId(replacement)"));
    }

    @Test
    void inPlaceModificationEmulatesRemovalAndReinsertionToRefreshPatternBuffers() throws Exception {
        String source = Files.readString(MODIFIER);

        int clear = source.indexOf("inventory.setItemDirect(slot, ItemStack.EMPTY)");
        int replace = source.indexOf("inventory.setItemDirect(slot, replacement)");
        int restore = source.indexOf("inventory.setItemDirect(slot, source)");

        assertTrue(clear >= 0, "修改槽内样板前必须先触发一次空槽刷新");
        assertTrue(clear < replace, "新样板必须在空槽通知完成后重新写入");
        assertTrue(restore > replace, "写回异常时必须恢复原样板，不能丢失槽内容");
    }

    @Test
    void stellarPatternChangeInvalidatesOnlyTheChangedSlotOrderState() throws Exception {
        String bufferSource = Files.readString(PATTERN_BUFFER);
        String helperSource = Files.readString(SEARCH_HELPER);

        int parentRefresh = bufferSource.indexOf("super.onPatternChange(index)");
        int slotReset = bufferSource.indexOf("RecipeTypePatternSearchHelper.clearPatternSlotState(this, index)");

        assertTrue(parentRefresh >= 0, "必须先保留 GTLCore 原生样板槽刷新");
        assertTrue(slotReset > parentRefresh, "GTLCore 刷新后必须同步失效山海单槽订单状态");
        assertTrue(helperSource.contains("budgets[slot] = -1L"), "样板变化必须重置该槽虚拟供料预算");
        assertTrue(helperSource.contains("PATTERN_PEEK_CACHE.get(buffer)"), "必须失效该槽普通推断缓存");
        assertTrue(helperSource.contains("MARKED_RECIPE_CACHE.get(buffer)"), "必须失效该槽星律推断缓存");
    }

    @Test
    void oldGtlcorePatternModifierMixinIsNoLongerRegistered() throws Exception {
        String config = Files.readString(MIXIN_CONFIG);

        assertFalse(config.contains("PatternModifierMixins"));
    }
}
