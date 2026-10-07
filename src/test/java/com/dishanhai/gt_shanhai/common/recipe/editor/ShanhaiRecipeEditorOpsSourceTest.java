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

    @Test
    void linkedEntryKeepsTheSourceRecipeAndCoexistFlag() throws Exception {
        Path file = Files.createTempFile("shanhai-recipe-linked", ".json");
        Files.delete(file);
        ShanhaiRecipeOverrideStore store = new ShanhaiRecipeOverrideStore(file);
        ShanhaiRecipeBase opened = ShanhaiRecipeBase.simple(
                "gtceu:assembler", "gtceu:foo", 100, 32L, 1);

        store.putLinked(opened, "fp", "gtceu:foo_copy", "gtceu:foo", true, "test");

        ShanhaiRecipeOverrideStore.Entry entry = store.find("gtceu:assembler", "gtceu:foo_copy").orElseThrow();
        assertEquals("gtceu:foo", entry.sourceRecipeId());
        assertTrue(entry.keepOriginal());
        assertTrue(store.find("gtceu:assembler", "gtceu:foo").isEmpty());
    }
}
