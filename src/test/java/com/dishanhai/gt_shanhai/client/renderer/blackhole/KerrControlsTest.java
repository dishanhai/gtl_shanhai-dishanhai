package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Background;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter.*;
import static org.junit.jupiter.api.Assertions.*;

class KerrControlsTest {
    @Test
    void whiteOpticsAndKerrOverridesAreIndependent() {
        try {
            KerrControls.set(EXPOSURE, .5);
            KerrControls.setQuality(Quality.LOW);
            KerrControls.setBackground(Background.DARK);
            assertEquals(Quality.ADAPTIVE, KerrControls.WHITE.requestedQuality());
            assertEquals(Background.WORLD, KerrControls.WHITE.requestedBackground(false));
            assertEquals(Background.STARS, KerrControls.WHITE.requestedBackground(true));
            assertEquals(EXPOSURE.initial, KerrControls.WHITE.requested(EXPOSURE));
            KerrControls.WHITE.set(EXPOSURE, 2.2);
            KerrControls.WHITE.setQuality(Quality.HIGH);
            assertEquals(.5, KerrControls.requested(EXPOSURE));
            assertEquals(Quality.LOW, KerrControls.KERR.requestedQuality());
            KerrControls.reset();
            assertEquals(2.2, KerrControls.WHITE.requested(EXPOSURE));
            assertEquals(Quality.HIGH, KerrControls.WHITE.requestedQuality());
        } finally {
            KerrControls.reset();
            KerrControls.WHITE.reset();
        }
    }

    @Test
    void formedHostAndMovieShareTheCinematicBackgroundAndCompositor() {
        var host = new BlackHoleLensRenderer.Frame();
        host.mode = BlackHoleTuning.RenderMode.WHITE_HOLE;
        host.white = WhiteHoleRenderState.Frame.NONE;
        var movie = new BlackHoleLensRenderer.Frame();
        movie.mode = host.mode;
        movie.white = host.white;
        movie.whitePreview = true;
        movie.whiteDemo = true;
        assertTrue(host.usesWhiteWorldPrimary());
        assertTrue(movie.usesWhiteWorldPrimary());

        try {
            long start = System.nanoTime();
            var optics = KerrControls.WHITE;
            var hostSky = optics.sample(start, 0, 25, 2, host.usesCinematicWhite(), .72);
            var movieSky = optics.sample(start + 20_000_000, 0, 25, 2, movie.usesCinematicWhite(), .72);
            assertEquals(1f, hostSky.starsSky());
            assertEquals(hostSky.starsSky(), movieSky.starsSky());
            assertEquals(.72, hostSky.geometry().spin());
            assertEquals(hostSky.geometry().spin(), movieSky.geometry().spin());
            optics.setBackground(Background.DARK);
            optics.set(SPIN, .25);
            hostSky = optics.sample(start + 2_000_000_000, 0, 25, 2, host.usesCinematicWhite(), .72);
            movieSky = optics.sample(start + 2_020_000_000, 0, 25, 2, movie.usesCinematicWhite(), .72);
            assertEquals(1f, hostSky.darkSky());
            assertEquals(hostSky.darkSky(), movieSky.darkSky());
            assertEquals(.25, hostSky.geometry().spin());
            assertEquals(hostSky.geometry().spin(), movieSky.geometry().spin());
        } finally {
            KerrControls.WHITE.reset();
        }
    }

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
