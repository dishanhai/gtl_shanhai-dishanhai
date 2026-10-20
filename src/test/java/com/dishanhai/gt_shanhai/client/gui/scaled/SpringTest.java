package com.dishanhai.gt_shanhai.client.gui.scaled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringTest {

    private static long run(Spring s, long from, int frames) {
        long now = from;
        for (int i = 0; i < frames; i++) {
            now += 16L;
            s.update(now);
        }
        return now;
    }

    @Test
    void startsSettledAtItsInitialValue() {
        Spring s = new Spring(Spring.PANEL);
        assertTrue(s.settled());
        assertEquals(0f, s.value());
        assertEquals(0f, s.target());
        assertEquals(0.4f, new Spring(Spring.PANEL, 0.4f).value());
    }

    @Test
    void openingSpringStartsAtZeroAndRunsTowardOne() {
        Spring s = Spring.opening(Spring.PANEL);
        assertEquals(0f, s.value());
        assertEquals(1f, s.target());
        assertFalse(s.settled());
        s.update(System.currentTimeMillis() + 16L);
        assertTrue(s.value() > 0f);
    }

    @Test
    void convergesToTargetAndSettlesExactly() {
        Spring s = new Spring(Spring.PANEL);
        s.setTarget(1f, 1_000L);
        assertFalse(s.settled());
        run(s, 1_000L, 60);
        assertTrue(s.settled());
        assertEquals(1f, s.value());
    }

    @Test
    void criticalDampingNeverOvershoots() {
        Spring s = new Spring(Spring.HOVER);
        s.setTarget(1f, 0L);
        long now = 0L;
        for (int i = 0; i < 80; i++) {
            now += 16L;
            s.update(now);
            assertTrue(s.value() <= 1.0005f, "damping 1.0 不应超调：" + s.value());
        }
    }

    @Test
    void lightDampingOvershootsLessThanTwoThousandths() {
        Spring s = new Spring(Spring.PANEL);
        s.setTarget(1f, 0L);
        long now = 0L;
        float max = 0f;
        for (int i = 0; i < 80; i++) {
            now += 16L;
            s.update(now);
            max = Math.max(max, s.value());
        }
        assertTrue(max <= 1.002f, "damping 0.92 超调应 ≤0.2%：" + max);
    }

    @Test
    void retargetKeepsValueAndVelocityInsteadOfRestarting() {
        Spring s = new Spring(Spring.PANEL);
        s.setTarget(1f, 0L);
        long now = run(s, 0L, 5);
        float before = s.value();
        assertTrue(before > 0.05f && before < 0.95f, "应该在途中：" + before);

        s.setTarget(0f, now);
        assertEquals(before, s.value(), 0f, "改目标那一刻 value 不能跳");

        now += 16L;
        s.update(now);
        // 若是“从头重播”，一帧后会离 before 很远；连续接续只走一小步
        assertTrue(Math.abs(s.value() - before) < 0.1f, "一帧位移过大：" + before + " -> " + s.value());
        run(s, now, 60);
        assertEquals(0f, s.value());
        assertTrue(s.settled());
    }

    @Test
    void delayedTargetHoldsUntilItsStartTime() {
        Spring s = new Spring(Spring.STAGGER);
        s.setTargetAfter(1f, 1_000L, 100L);
        assertFalse(s.settled(), "有挂起的目标时不能算静止");
        s.update(1_050L);
        assertEquals(0f, s.value());
        s.update(1_099L);
        assertEquals(0f, s.value());
        run(s, 1_100L, 40);
        assertEquals(1f, s.value());
        assertTrue(s.settled());
    }

    @Test
    void zeroDelayIsAnOrdinarySetTarget() {
        Spring s = new Spring(Spring.STAGGER);
        s.setTargetAfter(1f, 0L, 0L);
        s.update(16L);
        assertTrue(s.value() > 0f);
    }

    @Test
    void snapJumpsAndStaysPut() {
        Spring s = new Spring(Spring.SCROLL);
        s.setTarget(100f, 0L);
        run(s, 0L, 3);
        s.snap(7f);
        assertEquals(7f, s.value());
        assertEquals(7f, s.target());
        assertTrue(s.settled());
        s.update(10_000L);
        assertEquals(7f, s.value());
    }

    @Test
    void snapCancelsAPendingDelayedTarget() {
        Spring s = new Spring(Spring.STAGGER);
        s.setTargetAfter(1f, 0L, 100L);
        s.snap(0f);
        assertTrue(s.settled());
        s.update(500L);
        assertEquals(0f, s.value());
    }

    @Test
    void ignoresNonFiniteTargets() {
        Spring s = new Spring(Spring.PANEL, 0.5f);
        s.setTarget(Float.NaN, 0L);
        s.setTarget(Float.POSITIVE_INFINITY, 0L);
        assertEquals(0.5f, s.target());
        assertTrue(s.settled());
    }

    @Test
    void longFramesAreClampedSoTheSpringDoesNotBlowUp() {
        Spring s = new Spring(Spring.PANEL);
        s.setTarget(1f, 0L);
        s.update(60_000L);
        assertTrue(Float.isFinite(s.value()));
        assertTrue(s.value() >= 0f && s.value() <= 1.01f);
    }

    @Test
    void pixelPresetsSettleOnTheExactPixel() {
        Spring s = new Spring(Spring.SCROLL);
        s.setTarget(360f, 0L);
        run(s, 0L, 120);
        assertTrue(s.settled());
        assertEquals(360f, s.value());
    }
}
