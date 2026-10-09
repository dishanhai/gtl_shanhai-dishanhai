package com.dishanhai.gt_shanhai.common.recipe.editor;

/**
 * One-shot target carried by the editor UI sync. Empty values mean a normal open.
 */
public final class ShanhaiRecipeEditorLaunch {

    private static String typeId = "";
    private static String recipeId = "";

    private ShanhaiRecipeEditorLaunch() {}

    public static void arm(String type, String id) {
        typeId = type == null ? "" : type;
        recipeId = id == null ? "" : id;
    }

    /** Returns the armed target once, or null when this open has no recipe. */
    public static String[] consume() {
        String type = typeId;
        String id = recipeId;
        typeId = "";
        recipeId = "";
        if (type.isEmpty() || id.isEmpty()) return null;
        return new String[] { type, id };
    }
}
