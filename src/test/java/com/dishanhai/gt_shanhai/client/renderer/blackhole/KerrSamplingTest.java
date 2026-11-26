package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality.*;
import static org.junit.jupiter.api.Assertions.*;

class KerrSamplingTest {
    @Test
    void directCacheDoesNotOscillateAroundSingleQualityBoundary() {
        assertEquals(2, KerrSampling.whiteDirectDivisor(ADAPTIVE, 480_000, 1));
        assertEquals(2, KerrSampling.whiteDirectDivisor(ADAPTIVE, 470_000, 2));
        assertEquals(1, KerrSampling.whiteDirectDivisor(ADAPTIVE, 390_000, 2));
        assertEquals(2, KerrSampling.whiteDirectDivisor(HIGH, 2_050_000, 2));
        assertEquals(1, KerrSampling.whiteDirectDivisor(HIGH, 1_600_000, 2));
    }

    @Test
    void whiteHostReuseIsBoundedInFramesAndWallTime() {
        assertTrue(KerrSampling.whiteHostCanReuse(0, 0));
        assertTrue(KerrSampling.whiteHostCanReuse(55_000_000, 2));
        assertFalse(KerrSampling.whiteHostCanReuse(57_000_000, 0));
        assertFalse(KerrSampling.whiteHostCanReuse(17_000_000, 3));
        assertFalse(KerrSampling.whiteHostCanReuse(-1, 0));
    }

    @Test
    void whiteHostNearPilotAndDirectImageHaveSeparateBudgets() {
        double area = 2560.0 * 1600;
        var plan = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, ADAPTIVE.scale, area, 1, 17, 200, 1);
        var close = plan.forWhiteHost(ADAPTIVE, area, 17);
        assertTrue(close.traceScale() < plan.traceScale());
        assertEquals(48_000, area * close.traceScale() * close.traceScale(), 1);
        var zoomed = plan.forWhiteHost(ADAPTIVE, area, 60);
        assertEquals(48_000, area * zoomed.traceScale() * zoomed.traceScale(), 1);
        assertEquals(plan.traceScale(), plan.forWhiteHost(ADAPTIVE, 160 * 160, 60).traceScale());
        assertSame(plan, plan.forWhiteHost(HIGH, area, 17));
        assertEquals(4, KerrSampling.whiteDirectDivisor(ADAPTIVE, area));
        assertEquals(2, KerrSampling.whiteDirectDivisor(LOW, 1280 * 720));
        assertEquals(2, KerrSampling.whiteDirectDivisor(HIGH, area));
        assertEquals(1, KerrSampling.whiteDirectDivisor(HIGH, 1920 * 1080));
        assertEquals(2, KerrSampling.whiteDirectDivisor(HIGH, 3840 * 2160));
        assertEquals(3, KerrSampling.whiteDirectDivisor(HIGH, 5120 * 2160));
        assertEquals(1, KerrSampling.whiteDirectDivisor(ADAPTIVE, 160 * 160));
        assertEquals(864, KerrSampling.whiteDirectExtent(2560, 3));
        assertEquals(864, KerrSampling.whiteDirectExtent(2552, 3));
    }

    @Test
    void detailGridAlignsWithScreenPixelsAndOnlyRunsWhenItAddsResolution() {
        var low = new KerrSampling(.13, .22, 2);
        var core = low.diskDetail(512.4, 320.7, 58.1, 1024, 640, 36);
        assertEquals(224, core.side());
        assertEquals(core.side(), core.samples());
        assertEquals(400, core.x());
        assertEquals(208, core.y());
        assertEquals(1f, core.weight());
        assertNull(low.diskDetail(-200, 320, 58.1, 1024, 640, 36));
        assertNotNull(low.diskDetail(-20, 320, 58.1, 1024, 640, 36));
        assertNull(low.diskDetail(512, 320, 450, 1024, 640, 5));
        assertNull(new KerrSampling(1, 0, 4).diskDetail(512, 320, 58.1, 1024, 640, 36));

        var large = low.diskDetail(1430, 670, 270, 2560, 1600, 22);
        assertEquals(384, large.samples());
        assertTrue(large.weight() > .9);
        var close = low.diskDetail(1430, 670, 270, 2560, 1600, 11);
        assertTrue(close.samples() > large.samples() && close.samples() <= 448);
        assertNull(low.diskDetail(1430, 670, 270, 2560, 1600, 5));
    }
    @Test
    void onlyBlackHoleCompatibilityBypassesTheGeodesicIntegrator() {
        for (Quality quality : Quality.values()) {
            assertEquals(quality == COMPATIBILITY ? 1f : 0f, KerrSampling.exteriorWeight(quality, 0));
            assertEquals(0f, KerrSampling.exteriorWeight(quality, 1));
        }
    }

    @Test
    void whiteVolumeUsesItsOwnAdaptiveBudgetWithoutOverridingManualQuality() {
        double area = 2560.0 * 1600;
        double shadow = KerrSampling.shadowFraction(46, .60);
        var black = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, .27, area, shadow, 46, 150);
        var white = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, .27, area, shadow, 46, 150, 1);
        assertTrue(white.traceScale() > black.traceScale());
        assertTrue(white.transferScale() > black.transferScale());
        assertTrue(KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, .13, area, shadow, 46, 150, 1).traceScale()
                < white.traceScale());
        assertEquals(HIGH.scale, KerrSampling.plan(HIGH, HIGH.scale, .27, area, shadow, 46, 150, 1).traceScale());
    }
    @Test
    void coarseCachedRaysStayOnOnePhaseAndKeepAGrazingContour() {
        assertEquals(0, KerrSampling.blackHoleJitter(.20, 30));
        assertEquals(0, KerrSampling.blackHoleJitter(0, 14));
        assertEquals(.5, KerrSampling.blackHoleJitter(0, 22));
        assertEquals(1, KerrSampling.blackHoleJitter(0, 28));
        assertEquals(0, KerrSampling.criticalStripWidth(630, 800, 5));
        assertEquals(640, KerrSampling.blackHoleCriticalStripWidth(630, 800, 5, true));
        assertEquals(0, KerrSampling.blackHoleCriticalStripWidth(630, 800, 3, true));
        assertEquals(11f, KerrSampling.blackHoleCriticalStripSpan(.20, 5));
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
