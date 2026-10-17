package com.dishanhai.gt_shanhai.integration.enhancedcore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EnhancedCore RetryableRecipeLogic（通用加工厂-plus 等）重写搜索后，
 * 宿主拥有但当前没选中的星律样板仍必须能走虚拟执行链，且不打开作弊破限。
 */
class EnhancedCoreRetryableVirtualSourceTest {

    private static final Path RUNNER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "NativeVirtualRecipeRunner.java");
    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "EnhancedCoreRetryableVirtualMixin.java");
    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.enhancedcore.mixin.json");

    @Test
    void retryableSearchUsesHostAdmissionForOwnedInactiveTypes() throws IOException {
        String runner = Files.readString(RUNNER);
        String mixin = Files.readString(MIXIN);
        String config = Files.readString(CONFIG);

        assertTrue(runner.contains("collectNativeVirtualRecipes"),
                "必须从星律样板槽收集虚拟配方，不能只靠当前模式的查找表");
        assertTrue(runner.contains("checkMatchedRecipeAvailable"),
                "必须走机器自己的准入，保留电量、溢出和输出检查");
        assertTrue(runner.contains("beginOwnedInactiveType"),
                "准入和 setup 期间 getRecipeType 必须回报未选中的宿主类型");
        assertTrue(runner.contains("SelectableRecipeTypeSetMachine"),
                "选择集机器自己逐个选中类型搜索，不能在这里再放开");
        assertFalse(runner.contains("beginVirtualExecution"),
                "未选中类型的正常虚拟执行不能顺手打开作弊破限");
        assertTrue(mixin.contains("RetryableRecipeLogic"));
        assertTrue(mixin.contains("tryStartOwnedInactive"));
        assertTrue(mixin.contains("ownedInactiveCandidate"));
        assertTrue(mixin.contains("findAndHandleRecipe"));
        assertTrue(config.contains("EnhancedCoreRetryableVirtualMixin"));
        assertTrue(config.contains("\"required\": false"));
    }
}
