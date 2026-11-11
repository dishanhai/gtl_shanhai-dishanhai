package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeManagerDualTableSourceTest {

    @Test
    void vanillaRebuildWritesByTypeAndByName() throws Exception {
        String table = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeTable.java"));
        String rebuild = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeRebuild.java"));

        assertTrue(table.contains("recipesMap"));
        assertTrue(table.contains("byNameMap"));
        assertTrue(table.contains("recipesField().set"));
        assertTrue(table.contains("byNameField().set"));
        assertTrue(table.contains("Collections.unmodifiableMap(new LinkedHashMap<>("));
        assertTrue(rebuild.contains("RecipeRebuildService.buildCanonical"));
        assertTrue(rebuild.contains("ShanhaiVanillaRecipeTable.replaceType"));
    }

    @Test
    void nativeRecipeBufferReadOnlyRefreshesAfterOnlineTableChanges() throws Exception {
        String cache = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/mixin/MERecipePatternBufferTableRevisionMixin.java"));
        String refresh = cache.substring(cache.indexOf("private void gtShanhai$refreshBeforeReading("));

        assertTrue(refresh.contains("!gtShanhai$refreshingPatterns"));
        assertTrue(refresh.contains("gtShanhai$observedTableVersion != ShanhaiRecipeTableHook.tableVersion()"));
    }
}
