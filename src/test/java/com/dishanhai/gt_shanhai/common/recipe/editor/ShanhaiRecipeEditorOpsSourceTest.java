package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiRecipeEditorOpsSourceTest {

    @Test
    void staleFingerprintIsRejectedWithoutWritingOverride() throws Exception {
        Path file = Files.createTempFile("shanhai-recipe-overrides", ".json");
        ShanhaiRecipeOverrideStore store = new ShanhaiRecipeOverrideStore(file);
        ShanhaiRecipeEditorOps ops = new ShanhaiRecipeEditorOps(store);
        ShanhaiRecipeBase base = ShanhaiRecipeBase.simple("gtceu:assembler", "dishanhai:test", 100, 32L, 1);

        ShanhaiRecipeEditorOps.Result result = ops.commit(
                new ShanhaiRecipeEditorOps.Edit(base, "stale-fingerprint"));

        assertEquals(ShanhaiRecipeEditorOps.Result.Status.CONFLICT, result.status());
        assertTrue(store.find("dishanhai:test").isEmpty());
    }
}
