package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality.*;
import static org.junit.jupiter.api.Assertions.*;

class KerrSamplingTest {
    @Test
    void closeApproachCapsBothPassesBeforeAnyGpuTimingIsAvailable() {
        for (double area : new double[]{800 * 560, 2560 * 1600, 3840 * 2160}) {
            for (Quality quality : new Quality[]{ADAPTIVE, LOW}) {
                var plan = KerrSampling.plan(quality, quality.scale, ADAPTIVE.scale, area, 1, 4.7);
                assertTrue(plan.traceScale() * plan.traceScale() * area <= 90_001);
                assertTrue(plan.transferScale() * plan.transferScale() * area <= 120_001);
                assertTrue(plan.transferScale() > 0);
                assertEquals(2, plan.subpixels());
            }
        }
    }

    @Test
    void adaptiveRefinementTracksThePilotWithoutAResolutionFloor() {
        double area = 2560 * 1600;
        var initial = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, ADAPTIVE.scale, area, 1, 4.7);
        var reduced = KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, .13, area, 1, 4.7);
        assertTrue(reduced.traceScale() < initial.traceScale() * .30);
        assertTrue(reduced.transferScale() < initial.transferScale() * .5);
        assertTrue(reduced.transferScale() > reduced.traceScale());
    }

    @Test
    void smallDistantEffectsAndExplicitQualityKeepNativeRefinement() {
        assertEquals(new KerrSampling(ADAPTIVE.scale, 0, 4),
                KerrSampling.plan(ADAPTIVE, ADAPTIVE.scale, ADAPTIVE.scale, 60_000, .1, 26));
        for (Quality quality : new Quality[]{MEDIUM, HIGH, ULTRA}) {
            assertEquals(new KerrSampling(quality.scale, 0, 4),
                    KerrSampling.plan(quality, quality.scale, .13, 2560 * 1600, 1, 4.7));
        }
    }
}
