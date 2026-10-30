package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuFxVisibilityTest {
    private boolean visible(Matrix4f clip, float x, float y, float z, float radius) throws Exception {
        return (boolean) Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxVisibility")
                .getMethod("isVisible", Matrix4f.class, float.class, float.class, float.class, float.class)
                .invoke(null, clip, x, y, z, radius);
    }

    @Test void offscreenObjectsAreRejectedButIntersectingCloudEdgesAreKept() throws Exception {
        var clip = new Matrix4f();
        assertTrue(visible(clip, 0, 0, 0, 0.1F));
        assertFalse(visible(clip, 2, 0, 0, 0.1F));
        assertFalse(visible(clip, 0, 0, -2, 0.1F));
        assertTrue(visible(clip, 1.1F, 0, 0, 0.2F));
    }

    @Test void perspectiveAndLocalHostTransformsAreHandledWithoutADistanceCutoff() throws Exception {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 1.7F, 0.05F, 384F);
        assertTrue(visible(projection, 0, 0, -50, 1));
        assertFalse(visible(projection, 0, 0, 50, 1));
        assertFalse(visible(projection, 200, 0, -50, 1));
        assertFalse(visible(projection, 0, 0, -600, 1));
        var local = new Matrix4f(projection).translate(0, 0, -50);
        assertTrue(visible(local, 0, 0, 0, 1));
        assertFalse(visible(local, 300, 0, 0, 1));
    }

    @Test void outerEffectsHaveLowerBudgets() {
        assertTrue(GuixuStarField.SKY_COUNT <= 512);
        assertTrue(GuixuStarField.ORBIT_COUNT <= 64);
        assertTrue(GuixuStarField.METEOR_COUNT <= 8);
        assertTrue(GuixuStarField.skyCount(1, true) <= 96);
        assertTrue(GuixuNebulaField.PATCH_COUNT <= 48);
        assertTrue(GuixuNebulaField.patchCount(true, true) <= 16);
    }
}
