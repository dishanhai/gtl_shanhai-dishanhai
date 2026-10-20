package com.dishanhai.gt_shanhai.client.gui.scaled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiRenderUtilMotionTest {

    @Test
    void shopMotionUsesOneHundredFiftyMillisecondsAndThirtyTwoPixelSlide() {
        assertEquals(150L, GuiRenderUtil.MOTION_MS);
        assertEquals(32f, GuiRenderUtil.TAB_SLIDE_DIST);
        assertEquals(1f, GuiRenderUtil.popAnimProgress(0L, GuiRenderUtil.MOTION_MS));
        assertEquals(0f, GuiRenderUtil.easeOut(0f));
        assertEquals(1f, GuiRenderUtil.easeOut(1f));
        assertEquals(0.75f, GuiRenderUtil.easeOut(0.5f), 0.0001f);
        assertEquals(32f, GuiRenderUtil.slideOffsetX(1, 0f));
        assertEquals(-32f, GuiRenderUtil.slideOffsetX(-1, 0f));
        assertEquals(0f, GuiRenderUtil.slideOffsetX(1, 1f));
        float t = GuiRenderUtil.popAnimProgress(System.currentTimeMillis(), GuiRenderUtil.MOTION_MS);
        assertTrue(t >= 0f && t <= 1f);
    }

    @Test
    void popScaleMatchesTheCurveTheOverlayPanelUses() {
        assertEquals(0.94f, GuiRenderUtil.popScale(0f), 0.0001f);
        assertEquals(1f, GuiRenderUtil.popScale(1f), 0.0001f);
        assertEquals(1f, GuiRenderUtil.popScale(2f), 0.0001f);
        // ease-out：一半时间已经走过大半，0.94 + 0.06 * 0.75
        assertEquals(0.985f, GuiRenderUtil.popScale(0.5f), 0.0001f);
    }

    @Test
    void scaleRectAroundShrinksTowardTheCenterAndNeverGrows() {
        // 弹入的面板缩在自己中心：200x100 @ (50,40) 按 0.94 缩 → 188x94，四边各收进 6/3 px
        assertArrayEquals(new int[]{56, 43, 188, 94}, GuiRenderUtil.scaleRectAround(50, 40, 200, 100, 0.94f));
        // 落位后原样返回
        assertArrayEquals(new int[]{50, 40, 200, 100}, GuiRenderUtil.scaleRectAround(50, 40, 200, 100, 1f));
        // 退化矩形不崩
        assertArrayEquals(new int[]{5, 5, 0, 0}, GuiRenderUtil.scaleRectAround(5, 5, 0, 0, 0.94f));
        // 取整只会让洞更小：结果始终落在原矩形里
        int[] r = GuiRenderUtil.scaleRectAround(3, 7, 51, 33, 0.94f);
        assertTrue(r[0] >= 3 && r[1] >= 7 && r[0] + r[2] <= 54 && r[1] + r[3] <= 40);
    }

    @Test
    void flightSpringUsesFixedResponseAndDampingThenSettles() {
        assertEquals(0.22, GuiRenderUtil.FluidSpring.RESPONSE_S);
        assertEquals(0.92, GuiRenderUtil.FluidSpring.DAMPING_FRACTION);

        GuiRenderUtil.FluidSpring spring = new GuiRenderUtil.FluidSpring();
        spring.start(1_000L);
        assertTrue(!spring.settled());
        assertEquals(0f, spring.value(), 0.0001f);

        long now = 1_000L;
        for (int i = 0; i < 30; i++) {
            now += 16L;
            spring.update(now);
            assertTrue(spring.value() >= 0f);
            assertTrue(spring.value() <= 1.002f, "damping 0.92 不应该甩过头");
        }
        assertTrue(spring.value() > 0.9f, "0.48s 内应该基本落位");

        spring.update(now + 2_000L);
        assertTrue(spring.settled());
        assertEquals(1f, spring.value(), 0.0001f);
    }

    @Test
    void flightResponseGrowsWithDistanceAndStaysUnderTheCap() {
        assertEquals(0.16, GuiRenderUtil.flightResponseForDistance(0.0), 1e-9);
        assertEquals(0.24, GuiRenderUtil.flightResponseForDistance(400.0), 1e-9);
        assertEquals(0.32, GuiRenderUtil.flightResponseForDistance(800.0), 1e-9);
        assertEquals(0.32, GuiRenderUtil.flightResponseForDistance(5_000.0), 1e-9);
        assertEquals(0.16, GuiRenderUtil.flightResponseForDistance(-50.0), 1e-9);
    }
}
