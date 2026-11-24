package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuBlackHoleFallbackWaveTest {
    @Test void thickerFallbackDiskGlowsMoreWhenHeated() {
        var cold = HostFxGeometry.holeDiskQuads(5, 3, 0);
        var hot = HostFxGeometry.holeDiskQuads(5, 3, 1);
        assertEquals(cold.size(), hot.size());
        boolean above = false, below = false;
        for (int i = 0; i < cold.size(); i++) {
            assertTrue(hot.get(i).a() > cold.get(i).a());
            for (int corner = 1; corner < 12; corner += 3) {
                above |= hot.get(i).xyz()[corner] > 1.1F;
                below |= hot.get(i).xyz()[corner] < -1.1F;
            }
        }
        assertTrue(above && below);
    }

    @Test void accretionParticlesSpiralThroughTheInnerEdgeBeforeTheNextHeatPulse() {
        double radius = 5;
        var initial = GuixuStarField.accretionPosition(0, 0, radius);
        var midway = GuixuStarField.accretionPosition(0, .5, radius);
        var end = GuixuStarField.accretionPosition(0, 1, radius);
        assertTrue(Math.hypot(initial.x(), initial.z()) > Math.hypot(midway.x(), midway.z()));
        assertTrue(Math.hypot(midway.x(), midway.z()) > radius);
        assertTrue(Math.hypot(end.x(), end.z()) < radius);
        assertEquals(0, end.y(), 1e-6);
        assertTrue(Math.abs(initial.x() * midway.z() - initial.z() * midway.x()) > 1);
        double arrival = 0;
        for (int tick = 1; tick < 160; tick++) {
            double time = tick / 20.0;
            if (GuixuStarField.accretionPhase(0, time) < GuixuStarField.accretionPhase(0, time - .05)) {
                arrival = time;
                break;
            }
        }
        assertTrue(arrival > 0);
        assertTrue(GuixuStarField.accretionFeed(arrival, 1) > GuixuStarField.accretionFeed(arrival - .05, 1));
    }

    @Test void stellarFeedStartsAfterTheFirstDebrisReturnsAndEndsAfterInfall() {
        assertEquals(0, GuixuStellarEnd.accretionFeed(8));
        assertTrue(GuixuStellarEnd.accretionFeed(30) > 0);
        assertEquals(0, GuixuStellarEnd.accretionFeed(GuixuStellarEnd.LAST_CAPTURE_SECONDS + 6));
    }

    @Test void photonRingMovesEvenWhenTheShadowIsTooSmallForFineWaves() {
        int shift = HostFxGeometry.HOLE_SEGMENTS / 4;
        var first = HostFxGeometry.holeHaloQuads(0, 0, 180, 5, 0, 0);
        var next = HostFxGeometry.holeHaloQuads(0, 0, 180, 5, 1.25F, 0);
        float atStart = first.getFirst().a();
        assertTrue(atStart - next.getFirst().a() > .2F);
        assertEquals(atStart, next.get(shift).a(), 1e-5);
    }

    private Method waves() throws Exception {
        return HostFxGeometry.class.getMethod("holeSurfaceWaveQuads", float.class, float.class, float.class,
                float.class, float.class, float.class);
    }

    @SuppressWarnings("unchecked")
    private List<HostFxGeometry.Quad> sample(float x, float y, float z, float time, float pixels) throws Exception {
        return (List<HostFxGeometry.Quad>) waves().invoke(null, x, y, z, 5F, time, pixels);
    }

    @Test void whiteWaveEdgesMoveAndStayOutsideTheBlackCenter() throws Exception {
        var first = sample(0, 0, 80, 0, 100);
        var next = sample(0, 0, 80, 3, 100);
        assertFalse(first.isEmpty());
        assertTrue(first.size() <= HostFxGeometry.HOLE_SEGMENTS * 12);
        assertFalse(java.util.Arrays.equals(first.getFirst().xyz(), next.getFirst().xyz()));
        for (var quad : first) {
            assertEquals(1, quad.r());assertEquals(quad.r(), quad.g());assertEquals(quad.g(), quad.b());
            assertTrue(quad.a() > 0 && quad.a() <= .8F);
            for (int i = 0; i < 12; i += 3) {
                double radius = Math.hypot(quad.xyz()[i], quad.xyz()[i + 1]);
                assertTrue(radius > 5 * .55 && radius < 5, "Only the outer surface may carry white crests");
            }
        }
    }

    @Test void tinyHolesAndCameraAtTheCenterHaveNoWaveGeometry() throws Exception {
        assertTrue(sample(0, 0, 80, 0, 8).isEmpty());
        assertTrue(sample(0, 0, 0, 0, 100).isEmpty());
    }

    @Test void cameraDirectionsAndLongTimesKeepAllCoordinatesFinite() throws Exception {
        for (float[] camera : new float[][]{{0, 80, 0}, {0, -80, 0}, {40, 20, -80}, {-80, 0, 0}})
            for (float time : new float[]{0, 3, 7.3919826F, 1_000_000})
                for (var quad : sample(camera[0], camera[1], camera[2], time, 100))
                    for (float coordinate : quad.xyz()) assertTrue(Float.isFinite(coordinate));
    }

    @Test void fallbackDiscClosesItsCenterAndIsActuallyBlack() {
        for (var quad : HostFxGeometry.holeDiscQuads(0, 0, 80, 5)) {
            assertEquals(0, quad.r());assertEquals(0, quad.g());assertEquals(0, quad.b());
            assertEquals(0, quad.xyz()[0]);assertEquals(0, quad.xyz()[1]);
        }
    }
}
