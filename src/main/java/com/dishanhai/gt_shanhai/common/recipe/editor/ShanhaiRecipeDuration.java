package com.dishanhai.gt_shanhai.common.recipe.editor;

public record ShanhaiRecipeDuration(int ticks, long eut) {

    public ShanhaiRecipeDuration {
        if (ticks < 1) ticks = 1;
    }
}
