package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JEIRecipeManagerLifecycleSourceTest {
    @Test
    void newRecipeManagerClearsThePreviousManagersDeduplicationState() throws Exception {
        String mixin = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/mixin/JEIRecipeListMixin.java"));
        assertTrue(mixin.contains("@Inject(method = \"<init>\", at = @At(\"RETURN\"), remap = false)"));
        assertTrue(mixin.contains("JEIRecipeCache.clearAll();"));
    }
}
