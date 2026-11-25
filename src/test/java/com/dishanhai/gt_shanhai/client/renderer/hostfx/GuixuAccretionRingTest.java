package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuAccretionRingTest {
    @Test void replacementIsThinAndStaysBetweenTheStructureBands() {
        verifyMesh(HostFxBeamRingGeom.SEGMENTS, HostFxBeamRingGeom.RADIAL_CELLS);
        verifyMesh(HostFxBeamRingGeom.FALLBACK_SEGMENTS, HostFxBeamRingGeom.FALLBACK_RADIAL_CELLS);
    }

    private static void verifyMesh(int segments, int radialCells) {
        var mesh = HostFxBeamRingGeom.mesh(segments, radialCells);
        var knots = mesh.knots();
        for (var knot : knots) {
            double radius = Math.hypot(knot.x(), knot.z());
            assertEquals(51 + knot.radial() * 6, radius, .00001);
            for (float time : new float[]{0, 3, 10_000}) {
                float y = GuixuAccretionFlow.height(knot.radial(), knot.angle(), time);
                assertTrue(Math.abs(y) <= .45F);
            }
        }
        for (int i = 0; i < mesh.columnSize(); i++) {
            var first = knots[i];
            var last = knots[knots.length - mesh.columnSize() + i];
            assertEquals(first.x(), last.x());
            assertEquals(first.z(), last.z());
            assertEquals(first.radial(), last.radial());
            assertEquals(2 * Math.PI, last.angle() - first.angle(), .000001);
        }
        final int[] quads = {0};
        mesh.forEachQuad((a, b, c, d) -> {
            assertEquals(b, a + 1);
            assertEquals(c, d + 1);
            assertEquals(d, a + mesh.columnSize());
            assertTrue(c < knots.length);
            quads[0]++;
        });
        assertEquals(segments * radialCells, quads[0]);
    }

    private static float[] shade(float radius, float angle, float time, float footprint) {
        float[] rgba = new float[4];
        GuixuAccretionFlow.shade(radius, angle, time, footprint, 0F, 80F, 120F, rgba);
        return rgba;
    }

    @Test void gasHasSoftEdgesWarmCoreCoolHaloAndUnevenMovingBrightness() {
        assertEquals(0, shade(51, 0, 0, 0)[3]);
        assertEquals(0, shade(57, 0, 0, 0)[3]);
        float min = 1, max = 0, movement = 0;
        for (int i = 0; i < 80; i++) {
            float angle = (float) (i * Math.PI * 2 / 80);
            float[] first = shade(53, angle, 0, .01F), next = shade(53, angle, 3, .01F);
            assertTrue(first[0] > first[1] && first[1] > first[2]);
            float[] halo = shade(55.8F, angle, 0, .01F);
            assertTrue(halo[2] > halo[0] && halo[0] > halo[1]);
            assertTrue(first[3] >= 0 && first[3] <= .70F);
            min = Math.min(min, first[3]);
            max = Math.max(max, first[3]);
            movement += Math.abs(first[3] - next[3]);
        }
        assertTrue(max - min > .15F, "Material must have bright streams and darker gaps");
        assertTrue(movement > 1, "Gas must flow rather than remain a static lamp");
    }

    @Test void angularSeamAndDistantSamplesRemainStable() throws Exception {
        for (float t : new float[]{0, 3, 10_000}) {
            float[] a = shade(54, (float) -Math.PI, t, .01F);
            float[] b = shade(54, (float) Math.PI, t, .01F);
            assertArrayEquals(a, b, .001F);
            for (float width : new float[]{.001F, .05F, .5F, 2F})
                for (float value : shade(54, 1, t, width)) assertTrue(Float.isFinite(value));
        }
    }

    @Test void gasOrbitsFasterInsideAndDriftsInwardsInItsRotatingFrame() throws Exception {
        var speed = GuixuAccretionFlow.class.getMethod("angularSpeed", double.class);
        double inner = (double) speed.invoke(null, 0D), outer = (double) speed.invoke(null, 1D);
        assertTrue(inner > outer && outer > 0, "Prograde Keplerian differential rotation");
        assertEquals(Math.pow(57D / 51, 1.5), inner / outer, .00001);
        for (double s = .05; s < 1; s += .1)
            for (double angle = -Math.PI; angle < Math.PI; angle += .2)
                for (double time : new double[]{0, 3, 15}) {
                    double phase = GuixuAccretionFlow.phase(s, angle, time);
                    double radialDerivative = (GuixuAccretionFlow.phase(s + .001, angle, time) - phase) / .001;
                    double timeDerivative = (GuixuAccretionFlow.phase(s, angle, time + .001) - phase) / .001;
                    double angularDerivative = (GuixuAccretionFlow.phase(s, angle + .001, time) - phase) / .001;
                    double drift = -(timeDerivative + (double) speed.invoke(null, s) * angularDerivative) / radialDerivative;
                    assertTrue(drift < 0 && drift > -.03, "Orbital motion dominates slow inward drift");
                }
    }

    @Test void approachingGasIsBrighterAndHotterThanRecedingGas() {
        float[] approaching = new float[4], receding = new float[4];
        for (int i = 0; i < 30; i++) {
            GuixuAccretionFlow.shade(53, 0, i * .1F, 0, 53, 20, 120, approaching);
            GuixuAccretionFlow.shade(53, 0, i * .1F, 0, 53, 20, -120, receding);
            assertTrue(approaching[3] > receding[3] * 2, "Doppler beaming follows the camera");
            assertTrue(approaching[2] > receding[2], "Approaching emission has a higher color temperature");
        }
    }

    @Test void broadPulsesStillMoveWhenFineFilamentsAreSubpixel() {
        float[] first = new float[4], second = new float[4];
        float min = 1, max = 0, movement = 0;
        for (int i = 0; i < 80; i++) {
            float angle = (float) (i * Math.PI * 2 / 80);
            GuixuAccretionFlow.shade(53, angle, 0, 1, 0, 120, 0, first);
            GuixuAccretionFlow.shade(53, angle, 1, 1, 0, 120, 0, second);
            min = Math.min(min, first[3]);
            max = Math.max(max, first[3]);
            movement += Math.abs(first[3] - second[3]);
        }
        assertTrue(max > min * 1.5F);
        assertTrue(movement > 1);
    }

    @Test void oldWorldsKeepMovingFilamentsAndTurbulenceRenewalHasNoJump() throws Exception {
        for (float time : new float[]{10_000, 1_000_000}) {
            float movement = 0;
            for (int i = 0; i < 80; i++) {
                float angle = (float) (i * Math.PI * 2 / 80);
                movement += Math.abs(shade(53, angle, time, .01F)[3] - shade(53, angle, time + 1, .01F)[3]);
            }
            assertTrue(movement > .6F, "Differential shear must not erase all detail in old worlds");
        }
        for (float time : new float[]{12, 24, 36, 48})
            assertArrayEquals(shade(53, 1, time - .0001F, .01F), shade(53, 1, time + .0001F, .01F), .001F);
    }

    @Test void apparentSubpixelWidthRemovesFineRadialVariation() throws Exception {
        float near = 0, distant = 0;
        for (int i = 0; i < 50; i++) {
            float r = 52 + i * .06F;
            near += Math.abs(shade(r, 1, 0, 0)[3] - shade(r + .1F, 1, 0, 0)[3]);
            distant += Math.abs(shade(r, 1, 0, 1)[3] - shade(r + .1F, 1, 0, 1)[3]);
        }
        assertTrue(distant < near * .4F, "Fine structure must fade before it turns into moire");
        assertNull(HostFxBeamRingGeom.selfCheck());
    }
}
