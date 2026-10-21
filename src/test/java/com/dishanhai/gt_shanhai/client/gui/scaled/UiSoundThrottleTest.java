package com.dishanhai.gt_shanhai.client.gui.scaled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiSoundThrottleTest {

    @Test
    void firstPlayPassesAndRepeatsInsideTheGapAreDropped() {
        UiSoundThrottle t = new UiSoundThrottle();
        assertTrue(t.allow("click", 40L, 1_000L));
        assertFalse(t.allow("click", 40L, 1_020L));
        assertFalse(t.allow("click", 40L, 1_039L));
        assertTrue(t.allow("click", 40L, 1_040L), "刚好等于间隔时放行");
    }

    @Test
    void droppedPlaysDoNotPushTheWindowBack() {
        UiSoundThrottle t = new UiSoundThrottle();
        assertTrue(t.allow("step", 45L, 0L));
        assertFalse(t.allow("step", 45L, 30L));
        assertTrue(t.allow("step", 45L, 45L), "被拦下的那次不能重置计时");
    }

    @Test
    void channelsAreIndependent() {
        UiSoundThrottle t = new UiSoundThrottle();
        assertTrue(t.allow("click", 40L, 0L));
        assertTrue(t.allow("fail", 150L, 5L));
        assertFalse(t.allow("fail", 150L, 100L));
        assertTrue(t.allow("click", 40L, 100L));
    }

    @Test
    void resetForgetsEveryChannel() {
        UiSoundThrottle t = new UiSoundThrottle();
        assertTrue(t.allow("click", 1_000L, 0L));
        t.reset();
        assertTrue(t.allow("click", 1_000L, 1L));
    }
}
