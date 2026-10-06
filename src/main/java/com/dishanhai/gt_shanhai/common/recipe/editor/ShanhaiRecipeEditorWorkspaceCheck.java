package com.dishanhai.gt_shanhai.common.recipe.editor;

/**
 * Offline checks for the paging contract shared by the holographic editor.
 */
public final class ShanhaiRecipeEditorWorkspaceCheck {

    private ShanhaiRecipeEditorWorkspaceCheck() {}

    public record Report(int passed, int total, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    public static Report selfCheck() {
        int passed = 0;
        int total = 0;
        String failure = null;

        total++;
        if (ShanhaiRecipeEditorWorkspace.pageCountOf(0) == 1) passed++;
        else failure = "empty section must still have one page";

        total++;
        if (ShanhaiRecipeEditorWorkspace.pageCountOf(32) == 1) passed++;
        else if (failure == null) failure = "32 cells must fit one page";

        total++;
        if (ShanhaiRecipeEditorWorkspace.pageCountOf(33) == 2) passed++;
        else if (failure == null) failure = "33 cells must require two pages";

        return new Report(passed, total, failure);
    }
}
