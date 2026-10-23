package com.dishanhai.gt_shanhai.common.item.generator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：動畫數學邊界，無需啟動客戶端。 */
class PatternGeneratorMotionTest {
    @Test void staggerIsMonotonicAndBounded() {
        long previous = 0;
        for (int i = 0; i < 2000; i++) {
            long delay = PatternGeneratorMotion.staggerDelay(i);
            assertTrue(delay >= previous && delay <= 240);
            previous = delay;
        }
        assertEquals(0, PatternGeneratorMotion.staggerDelay(-1));
    }

    @Test void blurIsFiniteSymmetricAndStopsWithTheGrid() {
        assertEquals(0f, PatternGeneratorMotion.scrollBlur(0));
        assertEquals(0f, PatternGeneratorMotion.scrollBlur(Float.NaN));
        for (float speed : new float[]{1, 100, 1000, 100000, Float.POSITIVE_INFINITY}) {
            float strength = PatternGeneratorMotion.scrollBlur(speed);
            assertTrue(Float.isFinite(strength) && strength >= 0 && strength <= 1);
            assertEquals(strength, PatternGeneratorMotion.scrollBlur(-speed));
        }
    }
}
