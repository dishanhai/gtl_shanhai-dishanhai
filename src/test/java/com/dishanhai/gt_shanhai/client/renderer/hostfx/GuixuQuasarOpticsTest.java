// Author: dishanhai
package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuixuQuasarOpticsTest {
    @Test
    void thinDiskPeaksOutsideItsInnerEdgeAndCoolsOutward() {
        double inner = GuixuQuasarOptics.INNER_EDGE;
        double peak = GuixuQuasarOptics.diskFlux(inner * 49 / 36);
        assertEquals(0, GuixuQuasarOptics.diskFlux(inner));
        assertTrue(peak > GuixuQuasarOptics.diskFlux(inner * 1.1));
        assertTrue(peak > GuixuQuasarOptics.diskFlux(inner * 2));
        assertTrue(GuixuQuasarOptics.diskFlux(inner * 2) > GuixuQuasarOptics.diskFlux(inner * 3));
        assertTrue(GuixuQuasarOptics.angularSpeed(inner * 1.1) > GuixuQuasarOptics.angularSpeed(inner * 3));
    }

    @Test
    void receivedFrequencyRisesTowardTheApproachingSide() {
        double radius = GuixuQuasarOptics.INNER_EDGE * 1.4;
        double approaching = GuixuQuasarOptics.diskShift(radius, 1);
        double transverse = GuixuQuasarOptics.diskShift(radius, 0);
        double receding = GuixuQuasarOptics.diskShift(radius, -1);
        assertTrue(approaching > 1 && transverse < 1);
        assertTrue(approaching > transverse && transverse > receding);
        assertTrue(GuixuQuasarOptics.jetShift(1) > GuixuQuasarOptics.jetShift(-1) * 5);
        assertEquals(0, GuixuQuasarOptics.beamedLight(0, GuixuQuasarOptics.jetShift(1)));
    }

    @Test
    void lensedDiskWrapsAboveAndBelowTheShadowAsItTilts() {
        assertEquals(0, GuixuQuasarOptics.arcWidth(1, 1));
        assertEquals(0, GuixuQuasarOptics.arcWidth(0, 0));
        assertEquals(GuixuQuasarOptics.arcWidth(0, 1), GuixuQuasarOptics.arcWidth(0, -1), 1e-6);
        assertTrue(GuixuQuasarOptics.arcWidth(.25, 1) > GuixuQuasarOptics.arcWidth(.25, -1));
        assertEquals(GuixuQuasarOptics.arcWidth(.25, 1), GuixuQuasarOptics.arcWidth(-.25, -1), 1e-6);
    }
}
