package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：tooltip 換目標或消失後重播，更新內容與移動滑鼠不重播。 */
class ShopTooltipMotionTest {
    @Test
    void sameTargetContinuesButAnotherTargetRestarts() {
        ShopTooltipMotion motion = new ShopTooltipMotion();
        assertEquals(0f, motion.show("first", 1000L, false));
        motion.endFrame();
        float middle = motion.show(new String("first"), 1075L, false);
        assertTrue(middle > 0f && middle < 1f);
        motion.endFrame();
        assertEquals(1f, motion.show("first", 1300L, false));
        motion.endFrame();
        assertEquals(0f, motion.show("second", 1301L, false));
    }

    @Test
    void frameWithoutTooltipAndScreenResetBothRestartTheEntrance() {
        ShopTooltipMotion motion = new ShopTooltipMotion();
        motion.show("item", 1000L, false);
        motion.endFrame();
        motion.endFrame();
        assertEquals(0f, motion.show("item", 2000L, false));
        motion.reset();
        assertEquals(0f, motion.show("item", 2500L, false));
    }

    @Test
    void reducedMotionIsImmediateAndDoesNotReplayWhenDisabled() {
        ShopTooltipMotion motion = new ShopTooltipMotion();
        assertEquals(1f, motion.show("item", 1000L, true));
        motion.endFrame();
        assertEquals(1f, motion.show("item", 1010L, false));
    }

    @Test
    void clockMovingBackwardsCannotProduceNegativeOpacity() {
        ShopTooltipMotion motion = new ShopTooltipMotion();
        motion.show("item", 1000L, false);
        motion.endFrame();
        assertEquals(0f, motion.show("item", 900L, false));
    }
}
