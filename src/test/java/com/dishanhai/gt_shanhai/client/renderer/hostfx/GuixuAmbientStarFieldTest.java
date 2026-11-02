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

    @Test void interiorStarVolumeFillsEveryDirectionWithoutEnteringTheHorizon() throws Exception {
        int count = type().getField("VOLUME_COUNT").getInt(null);
        assertTrue(count >= 256 && count <= 512);
        var sample = type().getMethod("sampleVolume", int.class, double.class, double.class);
        for (double horizon : new double[]{.8, 3, 12}) {
            int[] octants = new int[8];
            int interior = 0;
            for (int i = 0; i < count; i++) {
                Object star = sample.invoke(null, i, 12.5, horizon);
                assertTrue(radius(star) > horizon * 1.4 && radius(star) + value(star, "size") * 2.6 < 4 + 2.2 * horizon,
                        "New stars must remain inside the local black-hole environment");
                if (radius(star) < 45) interior++;
                int octant = (value(star, "headX") > 0 ? 1 : 0) | (value(star, "headY") > 0 ? 2 : 0)
                        | (value(star, "headZ") > 0 ? 4 : 0);
                octants[octant]++;
                assertTrue(value(star, "alpha") >= .2 && value(star, "alpha") <= .85);
                assertEquals(star, sample.invoke(null, i, 12.5, horizon));
            }
            for (int visible : octants) assertTrue(visible >= 20, "Fill the interior, not just another thin ring");
            if (horizon <= 12) assertTrue(interior >= 64, "A useful population must fill the central gap");
        }
    }

    @Test void interiorDustRemainsFaintBoundedAndStableAtLongTimes() throws Exception {
        int count = type().getField("DUST_COUNT").getInt(null);
        assertTrue(count > 0 && count <= 128);
        var sample = type().getMethod("sampleDust", int.class, double.class, double.class);
        for (int i = 0; i < count; i++) for (double time : new double[]{0, 20, 1_000_000_000.25}) {
            Object dust = sample.invoke(null, i, time, 12D);
            assertTrue(radius(dust) > 17 && radius(dust) + value(dust, "size") < 30.4);
            assertTrue(value(dust, "alpha") > 0 && value(dust, "alpha") <= .065, "No opaque or overexposed fog");
            assertEquals(dust, sample.invoke(null, i, time, 12D));
            for (String axis : new String[]{"headX", "headY", "headZ"}) assertTrue(Double.isFinite(value(dust, axis)));
        }
    }

    @Test void interiorPopulationsHaveExplicitReducedBudgets() throws Exception {
        for (String method : new String[]{"volumeCount", "dustCount"}) {
            var count = type().getMethod(method, float.class, boolean.class);
            int full = (int) count.invoke(null, 1F, false), idle = (int) count.invoke(null, .25F, false);
            int degraded = (int) count.invoke(null, 1F, true);
            assertTrue(full > idle && idle > degraded);
            assertEquals(full, count.invoke(null, 2F, false));
        }
        assertEquals(0, type().getMethod("dustCount", float.class, boolean.class).invoke(null, 1F, true));
    }

    @Test void ambientLightNeverPaintsOverTheProjectedBlackHoleShadow() throws Exception {
        var method = type().getMethod("hiddenByShadow", float[].class, GuixuStarField.Sample.class, double.class);
        var aligned = new GuixuStarField.Sample(0,0,30,0,0,30,.1F,1,false);
        var beside = new GuixuStarField.Sample(24,0,30,24,0,30,.1F,1,false);
        var behindCamera = new GuixuStarField.Sample(0,0,80,0,0,80,.1F,1,false);
        float[] camera = {0,0,60};
        assertEquals(true, method.invoke(null,camera,aligned,10D));
        assertEquals(false, method.invoke(null,camera,beside,10D));
        assertEquals(false, method.invoke(null,camera,behindCamera,10D));
        assertEquals(false, method.invoke(null,camera,aligned,0D));
        assertEquals(true, method.invoke(null,new float[]{0,0,5},beside,10D));
    }
}
