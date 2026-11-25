package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleLensSpec;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuixuBlackHoleJetTest {

    @Test
    void rotatingTheMachinePreservesTheDiskNormalAndMovingTheCameraSwapsTheBrightJet() {
        var axis = new Vector3f(BlackHoleLensSpec.diskNormal());

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            float yaw = (float) Math.toRadians(HostFxGeometry.yawDegrees(facing));
            for (int cameraPole : new int[]{-1, 1}) {
                var camera = new Vector3f(axis).mul(cameraPole * 50).rotateY(-yaw);
                var capture = new JetCapture(axis);
                Matrix4f model = new Matrix4f().rotateY(yaw);
                GuixuStarRenderer.drawJets(capture.vertices(), model, new Matrix4f().scaling(.02F).mul(model),
                        new float[]{camera.x, camera.y, camera.z}, facing, 3.0, 5.0, 1.0F);
                assertTrue(capture.count[0] > 0, "Each jet must emit vertices");
                assertEquals(capture.count[0], capture.count[1]);
                for (int pole = 0; pole < 2; pole++) {
                    Vector3f center = capture.centers[pole].div(capture.count[pole]);
                    assertEquals(0, new Vector3f(center).cross(axis).length(), 1e-4,
                            "Both lobes must lie on the disk normal at " + facing);
                    assertEquals(pole == 0 ? -19.5F : 19.5F, center.dot(axis), 1e-4);
                }
                int near = cameraPole > 0 ? 1 : 0;
                assertTrue(capture.light[near] > capture.light[1 - near] * 2,
                        "Approaching jet must be brighter");
            }
        }
    }

    @Test
    void closeViewSubmitsOnlyIntersectingJetSectionsIncludingGrazingFacets() {
        var axis = new Vector3f(BlackHoleLensSpec.diskNormal());
        var worldToJet = GuixuStarRenderer.diskRotation(Direction.EAST).conjugate();
        var camera = new float[]{0, 0, 0};
        var onePole = new JetCapture(axis);
        var clip = new Matrix4f().perspective(1.2F, 1, .05F, 64)
                .lookAt(0, 0, 0, 0, 1, 0, 0, 0, -1).rotate(worldToJet);
        GuixuStarRenderer.drawJets(onePole.vertices(), new Matrix4f(), clip, camera, Direction.EAST, 3, 5, 1);
        assertEquals(0, onePole.count[0]);
        assertEquals(640, onePole.count[1]);

        var grazing = new JetCapture(axis);
        clip.setOrtho(.2F, .5F, 9, 10, -2, 2).rotate(worldToJet);
        GuixuStarRenderer.drawJets(grazing.vertices(), new Matrix4f(), clip, camera, Direction.EAST, 3, 5, 1);
        assertEquals(0, grazing.count[0]);
        assertEquals(64, grazing.count[1]);
    }

    private static final class JetCapture {
        final Vector3f axis;
        final Vector3f point = new Vector3f();
        final Vector3f[] centers = {new Vector3f(), new Vector3f()};
        final int[] count = new int[2];
        final double[] light = new double[2];
        float alpha;

        JetCapture(Vector3f axis) {
            this.axis = axis;
        }

        VertexConsumer vertices() {
            return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                    new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "vertex" -> ((Matrix4f) args[0]).transformPosition(
                                    (float) args[1], (float) args[2], (float) args[3], point);
                            case "color" -> alpha = (float) args[3];
                            case "endVertex" -> {
                                int pole = point.dot(axis) > 0 ? 1 : 0;
                                centers[pole].add(point);
                                count[pole]++;
                                light[pole] += alpha;
                            }
                            default -> throw new AssertionError(method);
                        }
                        return proxy;
                    });
        }
    }
}
