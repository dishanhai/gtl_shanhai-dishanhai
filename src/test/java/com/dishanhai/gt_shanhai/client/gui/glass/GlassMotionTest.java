package com.dishanhai.gt_shanhai.client.gui.glass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlassMotionTest {

    @Test
    void speedPeaksInTheMiddleAndVanishesAtBothEnds() {
        assertEquals(0f, GlassMotion.speed(0f), 1e-6f);
        assertEquals(1f, GlassMotion.speed(0.5f), 1e-6f);
        assertEquals(0f, GlassMotion.speed(1f), 1e-6f);
    }

    @Test
    void speedIsSymmetricAroundTheMiddle() {
        assertEquals(0.75f, GlassMotion.speed(0.25f), 1e-6f);
        assertEquals(GlassMotion.speed(0.25f), GlassMotion.speed(0.75f), 1e-6f);
    }

    @Test
    void speedClampsOutsideTheUnitRange() {
        assertEquals(0f, GlassMotion.speed(-1f), 1e-6f);
        assertEquals(0f, GlassMotion.speed(2f), 1e-6f);
    }

    @Test
    void pageSlideBlursHorizontallyOnly() {
        GlassMotion.Sample s = GlassMotion.pageSlide(0.5f);
        assertEquals(1f, s.getStrength(), 1e-6f);
        assertEquals(GlassMotion.MOTION_RADIUS_VIRTUAL, s.getRadiusVirtual(), 1e-6f);
        assertEquals(1f, s.getAxisX(), 0f);
        assertEquals(0f, s.getAxisY(), 0f);
        assertTrue(s.isActive());
    }

    @Test
    void aRestingPageRequestsNoBlur() {
        assertFalse(GlassMotion.pageSlide(0f).isActive());
        assertFalse(GlassMotion.pageSlide(1f).isActive());
    }
}
