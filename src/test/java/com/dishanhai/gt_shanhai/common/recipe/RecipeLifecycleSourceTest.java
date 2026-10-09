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
        assertTrue(mod.contains("RecipeRebuildService.rebuildVanillaManager"));
        assertTrue(mod.contains("reapplyPersistedLookupRules(\"server-started\")"));
        assertTrue(mod.contains("ServerStartedEvent"));
        assertTrue(plugin.contains("ShanhaiRecipeQuery"));
        assertTrue(plugin.contains("ShanhaiRecipeEditorOps"));
        assertTrue(command.contains("配方编辑"));
        assertTrue(command.contains("自检"));
    }
}
