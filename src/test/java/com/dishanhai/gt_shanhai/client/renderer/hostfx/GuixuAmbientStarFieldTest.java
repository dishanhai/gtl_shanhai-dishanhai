package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：外圍星空、星塵環及流星的無世界取樣契約。 */
class GuixuAmbientStarFieldTest {
    private Class<?> type() throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarField");
    }

    private Object sample(String method, int index, double time) throws Exception {
        return type().getMethod(method, int.class, double.class).invoke(null, index, time);
    }

    private double value(Object sample, String field) throws Exception {
        return ((Number) sample.getClass().getMethod(field).invoke(sample)).doubleValue();
    }

    private double radius(Object sample) throws Exception {
        double x = value(sample, "headX"), y = value(sample, "headY"), z = value(sample, "headZ");
        return Math.sqrt(x * x + y * y + z * z);
    }

    @Test void skySurroundsTheOuterStructureAndStaysInsideABoundedShell() throws Exception {
        int count = type().getField("SKY_COUNT").getInt(null);
        assertTrue(count >= 512 && count <= 1024);
        double lowest = 0, highest = 0;
        for (int i = 0; i < count; i++) {
            Object star = sample("sampleSky", i, 12.5);
            assertTrue(radius(star) >= 76.0 && radius(star) <= 110.0);
            assertTrue(value(star, "alpha") > 0 && value(star, "alpha") <= 1);
            lowest = Math.min(lowest, value(star, "headY"));
            highest = Math.max(highest, value(star, "headY"));
        }
        assertTrue(lowest < -50 && highest > 50, "星空應包圍結構上下方");
    }

    @Test void skyAndOrbitSamplingAreDeterministicAndFiniteAtLongTimes() throws Exception {
        for (String method : new String[]{"sampleSky", "sampleOrbit", "sampleMeteor"}) {
            for (double time : new double[]{0, 13.5, 1_000_000_000.25}) {
                Object first = sample(method, 7, time);
                assertEquals(first, sample(method, 7, time));
                for (String axis : new String[]{"headX", "headY", "headZ", "tailX", "tailY", "tailZ"}) {
                    assertTrue(Double.isFinite(value(first, axis)));
                }
            }
        }
        assertNotEquals(sample("sampleOrbit", 7, 0), sample("sampleOrbit", 7, 20));
    }

    @Test void orbitDustStaysOutsideTheMainRingWithANonzeroTrail() throws Exception {
        int count = type().getField("ORBIT_COUNT").getInt(null);
        for (int i = 0; i < count; i++) {
            Object star = sample("sampleOrbit", i, 30);
            assertTrue(radius(star) > 64 && radius(star) < 90);
            double dx = value(star, "headX") - value(star, "tailX");
            double dz = value(star, "headZ") - value(star, "tailZ");
            assertTrue(Math.hypot(dx, dz) > 0.5);
        }
    }

    @Test void meteorsFadeAtRecyclingAndAreIntermittent() throws Exception {
        for (int i = 0; i < type().getField("METEOR_COUNT").getInt(null); i++) {
            assertEquals(0.0, value(sample("sampleMeteorAtPhase", i, 0), "alpha"));
            assertEquals(0.0, value(sample("sampleMeteorAtPhase", i, 1), "alpha"));
            assertTrue(value(sample("sampleMeteorAtPhase", i, 0.5), "alpha") > 0.9);
        }
        int active = 0, inactive = 0;
        for (int time = 0; time < 100; time++) {
            if (value(sample("sampleMeteor", 0, time), "alpha") > 0) active++;
            else inactive++;
        }
        assertTrue(active > 0 && inactive > active);
    }

    @Test void degradedSkyHasAnExplicitLowerDrawBudget() throws Exception {
        var count = type().getMethod("skyCount", float.class, boolean.class);
        int full = (int) count.invoke(null, 1F, false);
        int idle = (int) count.invoke(null, 0.25F, false);
        int degraded = (int) count.invoke(null, 1F, true);
        assertTrue(full <= 512 && full > idle && idle >= 256);
        assertTrue(degraded >= 96 && degraded < idle);
        assertEquals(full, count.invoke(null, 2F, false));
    }
}
