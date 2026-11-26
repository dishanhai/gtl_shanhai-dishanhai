package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
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

    @Test void accretionTailCrossesTheViewWithItsHeadOffscreen() {
        double seconds = 3.05;
        double phase = GuixuStarField.accretionPhase(0, seconds);
        var tail = GuixuStarField.accretionPosition(0, phase - .19, 6);
        var head = GuixuStarField.accretionPosition(0, phase, 6);
        var rotation = GuixuStarRenderer.diskRotation(Direction.EAST);
        var center = rotation.transform(new Vector3f((float) tail.x(), (float) tail.y(), (float) tail.z()));
        var tip = rotation.transform(new Vector3f((float) head.x(), (float) head.y(), (float) head.z()));
        var clip = new Matrix4f().scaling(2).translate(-center.x, -center.y, -center.z);
        assertFalse(HostFxVisibility.isVisible(clip, tip.x, tip.y, tip.z, .3F));
        int[] count = {0};
        var vertices = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("endVertex")) count[0]++;
                    return proxy;
                });
        GuixuStarRenderer.drawAccretionTrails(vertices, new Matrix4f(), clip, new float[]{0, 0, 20},
                Direction.EAST, seconds, 6, 1, .5F, 800, 560);
        assertTrue(count[0] >= 8 && count[0] < 64);
    }

    @Test void accretionRibbonHasSharedTransverseEdges() {
        record Point(float x, float y, float z) {}
        var points = new ArrayList<Point>();
        var vertices = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("vertex") && args.length == 4) {
                        points.add(new Point((float) args[1], (float) args[2], (float) args[3]));
                    }
                    return proxy;
                });
        GuixuStarRenderer.drawAccretionTrails(vertices, new Matrix4f(), new Matrix4f().scaling(.025f),
                new float[]{0, 0, 20}, Direction.EAST, 3.05, 6, 1, .5F, 800, 560);
        int segments = (points.size() - 4) / 8; // The last four vertices are the head glint.
        assertTrue(segments >= 8);
        for (int i = 0; i < segments - 1; i++) {
            int start = i * 8, next = start + 8;
            assertEquals(points.get(start + 1), points.get(next));
            assertEquals(points.get(start + 2), points.get(next + 3));
            assertEquals(points.get(start + 5), points.get(next + 4));
        }
    }
}
