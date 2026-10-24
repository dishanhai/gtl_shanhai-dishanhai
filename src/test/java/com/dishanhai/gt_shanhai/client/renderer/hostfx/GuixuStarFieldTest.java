package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuixuStarFieldTest {
    private Object sample(int index, double phase, double radius) throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarField")
                .getMethod("sampleAtPhase", int.class, double.class, double.class)
                .invoke(null, index, phase, radius);
    }

    private double value(Object sample, String field) throws Exception {
        return ((Number) sample.getClass().getMethod(field).invoke(sample)).doubleValue();
    }

    private double radius(Object sample, String prefix) throws Exception {
        double x = value(sample, prefix + "X"), z = value(sample, prefix + "Z");
        return Math.hypot(x, z);
    }

    @Test
    void starsSpiralInwardAndTheirTailsRemainBehind() throws Exception {
        for (int i = 0; i < 192; i++) {
            Object early = sample(i, 0.15, 3.0);
            Object middle = sample(i, 0.5, 3.0);
            Object late = sample(i, 0.85, 3.0);
            assertTrue(radius(early, "head") > radius(middle, "head"));
            assertTrue(radius(middle, "head") > radius(late, "head"));
            assertTrue(radius(late, "tail") > radius(late, "head"));
            double earlySpeed = radius(sample(i, 0.15, 3.0), "head") - radius(sample(i, 0.16, 3.0), "head");
            double lateSpeed = radius(sample(i, 0.85, 3.0), "head") - radius(sample(i, 0.86, 3.0), "head");
            assertTrue(lateSpeed > earlySpeed, "infall must accelerate");
            double cross = value(early, "headX") * value(middle, "headZ")
                    - value(early, "headZ") * value(middle, "headX");
            assertTrue(Math.abs(cross) > 0.1, "motion must include an orbit");
        }
    }

    @Test
    void starsFadeBeforeRecyclingAndStayOutsideTheHorizon() throws Exception {
        for (double horizon : new double[]{0.8, 3.0, 24.0}) {
            for (int i = 0; i < 192; i++) {
                assertEquals(0.0, value(sample(i, 0.0, horizon), "alpha"));
                assertEquals(0.0, value(sample(i, 1.0, horizon), "alpha"));
                for (double phase : new double[]{0.01, 0.3, 0.8, 0.999}) {
                    Object point = sample(i, phase, horizon);
                    assertTrue(radius(point, "head") >= horizon);
                    assertTrue(value(point, "alpha") > 0.0 && value(point, "alpha") <= 1.0);
                    assertTrue(Double.isFinite(value(point, "headY")));
                }
            }
        }
    }

    @Test
    void samplingIsDeterministicAndTimeWrapDoesNotAccumulateParticles() throws Exception {
        Class<?> type = Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarField");
        assertEquals(192, type.getField("PARTICLE_COUNT").getInt(null));
        var method = type.getMethod("sample", int.class, double.class, double.class);
        for (double time : new double[]{0, 13.5, 1_000_000_000.25}) {
            Object first = method.invoke(null, 17, time, 3.0);
            assertEquals(first, method.invoke(null, 17, time, 3.0));
            assertTrue(Double.isFinite(value(first, "headX")));
            assertTrue(radius(first, "head") >= 3.0);
        }
    }
}
