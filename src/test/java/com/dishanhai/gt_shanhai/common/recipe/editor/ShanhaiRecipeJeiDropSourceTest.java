package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiRecipeJeiDropSourceTest {

    @Test
    void jeiDropAndClearMutateTheClientDraftUsedByCommit() throws Exception {
        String ioWidget = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiIOWidget.java"));
        String editor = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorWidget.java"));

        assertTrue(ioWidget.contains("applyClientDraft(accepted, current)"));
        assertTrue(ioWidget.contains("current.clear();"));
        assertTrue(ioWidget.contains("target.setItem(stack, stack.getCount())"));
        assertTrue(ioWidget.contains("target.setFluid(fluid)"));
        assertFalse(ioWidget.contains("writeClientAction"));
        assertTrue(editor.contains("ioTable.json(\"inputs\")"));
    }
}
