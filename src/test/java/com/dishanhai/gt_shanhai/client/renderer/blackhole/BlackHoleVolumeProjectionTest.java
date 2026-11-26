package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlackHoleVolumeProjectionTest {
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final float[] PROJECTION = new Matrix4f()
            .perspective((float) Math.toRadians(60), (float) WIDTH / HEIGHT, .05f, 128f).get(new float[16]);

    @Test
    void projectsVisibleRimWhenCenterLeavesViewport() {
        var rect = BlackHoleLensMath.projectVolume(PROJECTION, new float[]{9, 0, -10}, 2.5, WIDTH, HEIGHT);
        assertNotNull(rect);
        assertTrue(rect.x0 > WIDTH / 2.0);
        assertEquals(WIDTH, rect.x0 + rect.w);
    }

    @Test
    void retainsVolumeIntersectingCameraPlaneButRejectsVolumesBehindCamera() {
        var rect = BlackHoleLensMath.projectVolume(PROJECTION, new float[]{0, 0, .25f}, 1, WIDTH, HEIGHT);
        assertNotNull(rect);
        assertEquals(WIDTH, rect.w);
        assertEquals(HEIGHT, rect.h);
        assertNull(BlackHoleLensMath.projectVolume(PROJECTION, new float[]{0, 0, 4}, 1, WIDTH, HEIGHT));
    }

    @Test
    void rejectsVolumeOutsideTheSideFrustum() {
        assertNull(BlackHoleLensMath.projectVolume(PROJECTION, new float[]{18, 0, -10}, 2, WIDTH, HEIGHT));
    }
}
