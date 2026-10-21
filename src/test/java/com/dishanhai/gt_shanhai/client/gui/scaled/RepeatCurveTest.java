package com.dishanhai.gt_shanhai.client.gui.scaled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatCurveTest {

    private static final RepeatCurve C = RepeatCurve.STEPPER;

    @Test
    void nothingRepeatsDuringTheInitialDelay() {
        assertEquals(0, C.firesBy(0L));
        assertEquals(0, C.firesBy(399L));
        assertEquals(1, C.firesBy(400L));
    }

    @Test
    void fireTimeZeroIsThePressItself() {
        assertEquals(0L, C.fireTimeMs(0));
        assertEquals(400L, C.fireTimeMs(1));
        assertEquals(520L, C.fireTimeMs(2));
    }

    @Test
    void intervalsShrinkMonotonicallyDownToTheFloor() {
        assertEquals(120L, C.intervalAt(2));
        long prev = Long.MAX_VALUE;
        for (int n = 2; n < 80; n++) {
            long i = C.intervalAt(n);
            assertTrue(i <= prev, "第 " + n + " 次间隔变长了：" + prev + " -> " + i);
            assertTrue(i >= 30L, "间隔低于下限：" + i);
            prev = i;
        }
        assertEquals(30L, C.intervalAt(79));
    }

    @Test
    void firesByIsTheInverseOfFireTime() {
        for (int n = 1; n <= 300; n++) {
            long t = C.fireTimeMs(n);
            assertEquals(n, C.firesBy(t), "第 " + n + " 次应在 " + t + "ms 触发");
            assertEquals(n - 1, C.firesBy(t - 1L));
        }
    }

    @Test
    void longHoldsAreBoundedByTheFloorRate() {
        int n = C.firesBy(10_000L);
        assertTrue(n <= 10_000 / 30 + 1, "十秒连发次数超过下限速率：" + n);
        assertTrue(n > 250, "加速后应接近每 30ms 一次：" + n);
        assertEquals(C.firesBy(10_000L), C.firesBy(10_000L), "必须是时间的纯函数");
    }

    @Test
    void negativeHeldTimeFiresNothing() {
        assertEquals(0, C.firesBy(-50L));
    }

    @Test
    void rejectsNonsenseParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RepeatCurve(-1L, 120L, 0.85, 30L));
        assertThrows(IllegalArgumentException.class, () -> new RepeatCurve(400L, 20L, 0.85, 30L));
        assertThrows(IllegalArgumentException.class, () -> new RepeatCurve(400L, 120L, 1.5, 30L));
        assertThrows(IllegalArgumentException.class, () -> new RepeatCurve(400L, 120L, 0.85, 0L));
    }
}
