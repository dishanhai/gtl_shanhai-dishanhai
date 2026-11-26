package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Background;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter.*;
import static org.junit.jupiter.api.Assertions.*;

class KerrControlsTest {
    @Test
    void runtimeChangesInterpolateAndFollowResumesAtThePhysicalCamera() {
        long start = System.nanoTime();
        try {
            KerrControls.set(SPIN, -1);
            KerrControls.set(AZIMUTH, 179);
            KerrControls.setBackground(Background.WORLD);
            KerrControls.setQuality(Quality.LOW);
            var first = KerrControls.sample(start, 10, 26, 2);
            assertEquals(-1, first.value(SPIN));
            assertEquals(26, first.value(DISTANCE));

            KerrControls.set(SPIN, 1);
            KerrControls.set(AZIMUTH, -179);
            KerrControls.set(DISTANCE, 12);
            KerrControls.set(INCLINATION, 75);
            KerrControls.setBackground(Background.STARS);
            KerrControls.setQuality(Quality.ULTRA);
            double transitionBound = KerrControls.horizonRadiusBound(26, 2);
            var transition = KerrControls.sample(start + 30_000_000, 10.03, 26, 2);
            assertTrue(transition.value(SPIN) > -1 && transition.value(SPIN) < 1);
            assertTrue(transition.value(AZIMUTH) > 179 && transition.value(AZIMUTH) < 181);
            assertTrue(transition.value(DISTANCE) > 12 && transition.value(DISTANCE) < 26);
            assertTrue(transitionBound >= 52 / transition.value(DISTANCE));
            assertTrue(transition.inclinationWeight() > 0 && transition.inclinationWeight() < 1);
            assertTrue(transition.renderScale() > Quality.LOW.scale && transition.renderScale() < Quality.ULTRA.scale);
            assertTrue(transition.worldSky() > 0 && transition.starsSky() > 0);
            assertEquals(1f, transition.worldSky() + transition.starsSky(), 1e-6f);

            KerrControls.follow(DISTANCE);
            double followingBound = KerrControls.horizonRadiusBound(26, 2);
            var following = KerrControls.sample(start + 60_000_000, 10.06, 26, 2);
            assertTrue(following.value(DISTANCE) > transition.value(DISTANCE) && following.value(DISTANCE) < 26);
            assertTrue(followingBound >= 52 / following.value(DISTANCE));
            assertThrows(IllegalArgumentException.class, () -> KerrControls.set(SPIN, 1.01));
        } finally {
            KerrControls.reset();
        }
    }
}
