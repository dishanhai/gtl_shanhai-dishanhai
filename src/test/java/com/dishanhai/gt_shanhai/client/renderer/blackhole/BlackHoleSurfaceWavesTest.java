package com.dishanhai.gt_shanhai.client.renderer.blackhole;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlackHoleSurfaceWavesTest {
    @Test void fallbackCrestInversionMatchesTheMainSurfacePhaseAtLongAndNegativeTimes() {
        for (double time : new double[]{-20, 0, 3, Math.PI * 2 / .85, 1_000_000})
            for (double angle : new double[]{-Math.PI, -.7, 0, 1.2, Math.PI})
                for (int wave = -1; wave <= 2; wave++) {
                    double radius = BlackHoleSurfaceWaves.crestRadius(wave, angle, time);
                    assertEquals(0, Math.sin(BlackHoleSurfaceWaves.phase(radius, angle, time) * .5), 1e-8);
                }
    }

    @Test void lowDetailRemovesSurfaceDisplacementAndTheCenterHasNoWhiteEnvelope() {
        assertEquals(0, BlackHoleSurfaceWaves.detail(.1));
        assertEquals(1, BlackHoleSurfaceWaves.detail(.001));
        assertEquals(1, BlackHoleSurfaceWaves.radiusScale(1, 3, 0));
        assertEquals(0, BlackHoleSurfaceWaves.envelope(.55));
        assertEquals(0, BlackHoleSurfaceWaves.envelope(1));
        assertEquals(1, BlackHoleSurfaceWaves.envelope(.8));
    }
}
