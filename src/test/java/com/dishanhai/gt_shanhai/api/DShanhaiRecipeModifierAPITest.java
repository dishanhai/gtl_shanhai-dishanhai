package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DShanhaiRecipeModifierAPITest {

    @Test
    void batchPatternCacheInvalidationRefreshesOwnersOnlyOnce() {
        TestPatternCacheOwner owner = new TestPatternCacheOwner();
        DShanhaiRecipeModifierAPI.registerPatternCacheOwner(owner);
        long revision = DShanhaiRecipeModifierAPI.getPatternCacheRevision();

        try {
            DShanhaiRecipeModifierAPI.runPatternCacheInvalidationBatch("test-batch", () -> {
                DShanhaiRecipeModifierAPI.invalidatePatternCaches("first");
                DShanhaiRecipeModifierAPI.invalidatePatternCaches("second");
            });

            assertEquals(1, owner.refreshCalls);
            assertEquals(revision + 1L, DShanhaiRecipeModifierAPI.getPatternCacheRevision());
        } finally {
            DShanhaiRecipeModifierAPI.unregisterPatternCacheOwner(owner);
        }
    }

    @Test
    void runtimeRulePresenceSeparatesStripAndReplaceRules() throws Exception {
        String typeId = "gt_shanhai:test_rule_presence";
        Map<String, Object> stripRules = ruleMap("STRIP_RULES");
        Map<String, Object> replaceRules = ruleMap("REPLACE_RULES");
        Map<String, Object> oldStrip = new LinkedHashMap<>(stripRules);
        Map<String, Object> oldReplace = new LinkedHashMap<>(replaceRules);

        try {
            stripRules.clear();
            replaceRules.clear();

            assertFalse(DShanhaiRecipeModifierAPI.hasRuntimeStripRules(typeId));
            assertFalse(DShanhaiRecipeModifierAPI.hasRuntimeStripOrReplaceRules(typeId));

            stripRules.put(typeId, java.util.List.of(new DShanhaiRecipeModifierAPI.StripEntry("minecraft:stone", true, false, "")));
            assertTrue(DShanhaiRecipeModifierAPI.hasRuntimeStripRules(typeId));
            assertTrue(DShanhaiRecipeModifierAPI.hasRuntimeStripOrReplaceRules(typeId));

            stripRules.clear();
            replaceRules.put(typeId, java.util.List.of(new DShanhaiRecipeModifierAPI.ReplaceEntry("minecraft:stone", "minecraft:dirt", false, false, "")));
            assertFalse(DShanhaiRecipeModifierAPI.hasRuntimeStripRules(typeId));
            assertTrue(DShanhaiRecipeModifierAPI.hasRuntimeStripOrReplaceRules(typeId));
        } finally {
            stripRules.clear();
            stripRules.putAll(oldStrip);
            replaceRules.clear();
            replaceRules.putAll(oldReplace);
        }
    }

    private static final class TestPatternCacheOwner {
        @SuppressWarnings("unused")
        private final boolean[] cacheRecipe = new boolean[] { true };

        private int refreshCalls;

        @SuppressWarnings("unused")
        private void refreshAllByProduct() {
            refreshCalls++;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ruleMap(String fieldName) throws Exception {
        Field field = DShanhaiRecipeModifierAPI.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(null);
    }
}
