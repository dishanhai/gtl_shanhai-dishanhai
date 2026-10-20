package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeLifecycleSourceTest {

    @Test
    void startupUsesOneRebuildEntryAndKubeJsExposesBackendBindings() throws Exception {
        String mod = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiMod.java"));
        String plugin = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiKubeJSPlugin.java"));
        String command = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/command/ShanhaiRecipeEditorCommand.java"));

        assertTrue(mod.contains("RecipeRebuildService.rebuildAll"));
        // 啟動期只留一次全量重建：禁用狀態先批次套用，再由 rebuildAll 統一寫回雙表
        assertTrue(mod.contains("runRuleBatch"));
        assertTrue(mod.contains("reapplyPersistedLookupRules(\"server-started\")"));
        assertTrue(mod.contains("ServerStartedEvent"));
        // 單次重建後不應再各自補一次原版表重建（每輪都會重掃全部配方）
        assertTrue(!mod.contains("rebuildVanillaManager"));
        assertTrue(plugin.contains("ShanhaiRecipeQuery"));
        assertTrue(plugin.contains("ShanhaiRecipeEditorOps"));
        assertTrue(command.contains("配方编辑"));
        assertTrue(command.contains("自检"));
    }
}
