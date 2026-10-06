package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.google.gson.JsonArray;

public final class ShanhaiRecipeConditions {

    private ShanhaiRecipeConditions() {}

    public static JsonArray copy(JsonArray source) {
        return source == null ? new JsonArray() : source.deepCopy();
    }
}
