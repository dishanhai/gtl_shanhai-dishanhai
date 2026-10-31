package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuAccretionRingTest {
    @Test void replacementIsThinAndStaysBetweenTheStructureBands() {
        final int[] count = {0};
        HostFxBeamRingGeom.emitTube((x, y, z, nx, ny, nz, u, v, alpha, angle) -> {
            assertTrue(Math.abs(y) <= .46F, "The replacement must not be a six-block light tube");
            assertTrue(Math.hypot(x, z) >= 50.99 && Math.hypot(x, z) <= 57.01);
            assertTrue(Float.isFinite(x + y + z + alpha));
            count[0]++;
        }, 1, 1, 0, 0);
        assertTrue(count[0] > 0 && count[0] <= 192 * 16 * 6);
    }

    private float[] shade(float radius, float angle, float time, float footprint) throws Exception {
        Class<?> flow = Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuAccretionFlow");
        float[] rgba = new float[4];
        flow.getMethod("shade", float.class, float.class, float.class, float.class,
                float.class, float.class, float.class, float[].class)
                .invoke(null, radius, angle, time, footprint, 0F, 80F, 120F, rgba);
        return rgba;
    }

    @Test void gasHasSoftEdgesWarmColorAndUnevenMovingBrightness() throws Exception {
        assertEquals(0, shade(51, 0, 0, 0)[3]);
        assertEquals(0, shade(57, 0, 0, 0)[3]);
        float min = 1, max = 0, movement = 0;
        for (int i = 0; i < 80; i++) {
            float angle = (float) (i * Math.PI * 2 / 80);
            float[] first = shade(53, angle, 0, .01F), next = shade(53, angle, 3, .01F);
            assertTrue(first[0] >= first[1] && first[1] >= first[2], "No saturated blue tube");
            assertTrue(first[3] >= 0 && first[3] <= .65F, "No opaque white inner pass");
            min = Math.min(min, first[3]);max = Math.max(max, first[3]);
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

    @Test void streamCrestsAlwaysAdvectInwardsRatherThanOutwards() {
        for (double s = .05; s < 1; s += .1)
            for (double angle = -Math.PI; angle < Math.PI; angle += .2)
                for (double time : new double[]{0, 3, 15}) {
                    double phase = GuixuAccretionFlow.phase(s, angle, time);
                    double radialDerivative = (GuixuAccretionFlow.phase(s + .001, angle, time) - phase) / .001;
                    double timeDerivative = (GuixuAccretionFlow.phase(s, angle, time + .001) - phase) / .001;
                    assertTrue(-timeDerivative / radialDerivative < 0, "Constant-phase gas must move inwards");
                }
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
