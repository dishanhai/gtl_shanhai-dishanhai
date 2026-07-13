package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeLookupPerformanceSourceTest {

    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path PATTERN_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "PatternRecipeTypeHelper.java");
    private static final Path MODIFIER_API = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "DShanhaiRecipeModifierAPI.java");

    @Test
    void cachesStellarPatternRecipeInferenceUntilPatternOrRecipeRevisionChanges() throws IOException {
        String source = Files.readString(SEARCH_HELPER);

        assertTrue(source.contains("getMarkedRecipeCached(buffer, access, slot)"),
                "星律 active/首配路径应共用每槽样板配方缓存");
        assertTrue(source.contains("DShanhaiRecipeModifierAPI.getPatternCacheRevision()"),
                "运行期配方规则变化后必须失效样板推断缓存");
        assertEquals(1, occurrences(source, "access.gtShanhai$getPatternRecipe(slot)"),
                "昂贵的样板反推只能保留在缓存未命中的加载路径");
    }

    @Test
    void avoidsPerCandidateDebugFileLoggingInPatternLookupHotPath() throws IOException {
        String patternHelper = Files.readString(PATTERN_HELPER);
        String modifierApi = Files.readString(MODIFIER_API);

        assertFalse(patternHelper.contains("LOG.debug(\"[inferRecipe] inputs="),
                "样板反推不得为每次匹配拼接并写入调试详情");
        assertFalse(modifierApi.contains("LOG.debug(\"[StripByType]"),
                "Branch 遍历不得为每个剥离候选写调试日志");
        assertFalse(modifierApi.contains("LOG.debug(\"[ReplaceByType]"),
                "Branch 遍历不得为每个替换候选写调试日志");
    }

    private static int occurrences(String source, String target) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(target, from)) >= 0) {
            count++;
            from += target.length();
        }
        return count;
    }
}
