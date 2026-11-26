package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality.*;
import static org.junit.jupiter.api.Assertions.*;

class KerrSamplingTest {
    @Test
    void detailGridAlignsWithScreenPixelsAndOnlyRunsWhenItAddsResolution() {
        var low = new KerrSampling(.13, .22, 2);
        var core = low.diskDetail(512.4, 320.7, 58.1, 1024, 640);
        assertEquals(224, core.side());
        assertEquals(core.side(), core.samples());
        assertEquals(400, core.x());
        assertEquals(208, core.y());
        assertEquals(1f, core.weight());
        assertNull(low.diskDetail(-200, 320, 58.1, 1024, 640));
        assertNotNull(low.diskDetail(-20, 320, 58.1, 1024, 640));
        assertNull(low.diskDetail(512, 320, 450, 1024, 640));
        assertNull(new KerrSampling(1, 0, 4).diskDetail(512, 320, 58.1, 1024, 640));
    }
    @Test
    void onlyBlackHoleCompatibilityBypassesTheGeodesicIntegrator() {
        for (Quality quality : Quality.values()) {
            assertEquals(quality == COMPATIBILITY ? 1f : 0f, KerrSampling.exteriorWeight(quality));
        }
    }

    @Test
    void worldBorderDoesNotQuantizeSmallSidewaysCameraOffsets() {
        float[] view = {1, 0, 1, 0, 0, 1, 0, 0, -1, 0, 1, 0, 0, 0, 0, 1};
        float[] local = new float[3], remote = new float[3];
        BlackHoleLensMath.toView(view, new double[]{4_000_000.03125, 0, 4_000_000},
                new double[]{0, 0, 0}, local);
        BlackHoleLensMath.toView(view, new double[]{29_000_000.03125, 0, 29_000_000},
                new double[]{25_000_000, 0, 25_000_000}, remote);
        assertEquals(.03125f, local[0]);
        assertArrayEquals(local, remote);
    }

    @Test
    void closeApproachCapsBothPassesBeforeAnyGpuTimingIsAvailable() {
        for (double area : new double[]{800 * 560, 2560 * 1600, 3840 * 2160}) {
            for (Quality quality : new Quality[]{ADAPTIVE, LOW}) {
                var plan = KerrSampling.plan(quality, quality.scale, ADAPTIVE.scale, area, 1, 4.7, 320);
                int traceBudget = quality == ADAPTIVE ? 60_000 : 90_000;
                assertTrue(plan.traceScale() * plan.traceScale() * area <= traceBudget + 1);
                assertTrue(plan.transferScale() * plan.transferScale() * area <= 120_001);
                assertTrue(plan.transferScale() > 0);
                assertEquals(2, plan.subpixels());
            }
        }
    }

    @Test
    void fullScreenApproachReducesWorkBeforeReachingTheInnerDisk() {
        double area = 2560 * 1600;
        var plan = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, ADAPTIVE.scale, area, 1, 14, 320);
        assertTrue(plan.traceScale() * plan.traceScale() * area <= 207_000);
        assertTrue(plan.transferScale() * plan.transferScale() * area <= 242_000);
        assertEquals(2, plan.subpixels());
    }

    @Test
    void fullScreenAtAnObliqueAngleKeepsTheBudgetOfTheActualAngularSize() {
        double area = 2560 * 1600;
        double shadow = KerrSampling.shadowFraction(40, .60);
        var plan = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, ADAPTIVE.scale, area, shadow, 40, 2817);
        assertEquals(.54, shadow, .01);
        assertTrue(plan.traceScale() * plan.traceScale() * area < 165_000);
    }

    @Test
    void adaptiveRefinementTracksThePilotWithoutAResolutionFloor() {
        double area = 2560 * 1600;
        var initial = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, ADAPTIVE.scale, area, 1, 4.7, 320);
        var reduced = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, .13, area, 1, 4.7, 320);
        assertTrue(reduced.traceScale() < initial.traceScale() * .30);
        assertTrue(reduced.transferScale() < initial.transferScale() * .5);
        assertTrue(reduced.transferScale() > reduced.traceScale());
    }

    @Test
    void closeEffectsAndExplicitQualityKeepTheirExistingPlan() {
        assertEquals(new KerrSampling(ADAPTIVE.scale, 0, 4),
                KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, ADAPTIVE.scale, 60_000, .1, 26, 32));
        for (Quality quality : new Quality[]{MEDIUM, HIGH, ULTRA}) {
            assertEquals(new KerrSampling(quality.scale, 0, 4),
                    KerrSampling.plan(quality, quality.scale, .13, 2560 * 1600, 1, 4.7, 320));
        }
    }

    @Test
    void smallDistantEffectsUseNativeTraceWhenAffordable() {
        assertEquals(new KerrSampling(1, 0, 4), distant(60_000, 100, 28, ADAPTIVE.scale));
        assertEquals(ADAPTIVE.scale, distant(60_000, 100, 96, ADAPTIVE.scale).traceScale());
        double transition = distant(60_000, 56, 28, ADAPTIVE.scale).traceScale();
        assertTrue(transition > ADAPTIVE.scale && transition < 1);
    }

    @Test
    void distantDetailRespectsAreaAndGpuBudget() {
        var full = distant(310_000, 100, 28, ADAPTIVE.scale);
        var reduced = distant(310_000, 100, 28, .22);
        assertTrue(full.traceScale() > ADAPTIVE.scale && full.traceScale() < 1);
        assertEquals(full.traceScale() * .5, reduced.traceScale(), 1e-8);
        assertEquals(0, reduced.transferScale());
    }

    private static KerrSampling distant(double area, double distance, double radius, double gpuScale) {
        return KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, gpuScale, area, .1, distance, radius);
    }
}
