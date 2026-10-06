package com.dishanhai.gt_shanhai.common.recipe.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShanhaiRecipeEditorAnimationTest {

    @Test
    void stageTransitionInterpolatesThenSettles() {
        ShanhaiRecipeEditorAnimation animation = new ShanhaiRecipeEditorAnimation();
        animation.reset(0, 0L);
        animation.advance(2, 100L);

        float middle = animation.progress(100L + ShanhaiRecipeEditorAnimation.DURATION_MS / 2);
        assertTrue(middle > 0.49f && middle < 0.51f);

        animation.advance(2, 100L + ShanhaiRecipeEditorAnimation.DURATION_MS);
        assertFalse(animation.moving(
                100L + ShanhaiRecipeEditorAnimation.DURATION_MS));
        assertEquals(2, animation.currentStage());
    }
}
