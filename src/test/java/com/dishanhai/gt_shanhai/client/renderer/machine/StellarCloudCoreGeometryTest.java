package com.dishanhai.gt_shanhai.client.renderer.machine;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：雲端核心的幾何、動畫與四朝向錨點，無需 OpenGL 或遊戲世界。 */
class StellarCloudCoreGeometryTest {
    private Class<?> geometry() {
        return assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.client.renderer.machine.StellarCloudCoreGeometry"));
    }
    private List<?> frame(double seconds) throws Exception {
        return (List<?>) geometry().getMethod("frame", double.class).invoke(null, seconds);
    }
    private double coordinate(Object point, String axis) throws Exception {
        return (double) point.getClass().getMethod(axis).invoke(point);
    }
    @Test void coreRingsAndMotesHaveABoundedMeshAtAllTimes() throws Exception {
        for (double seconds : new double[]{0, .25, 123.45, 1e9}) {
            var quads = frame(seconds);
            assertTrue(quads.size() >= 400 && quads.size() <= 800);
            for (Object quad : quads) {
                for (String vertex : List.of("a", "b", "c", "d")) {
                    Object point = quad.getClass().getMethod(vertex).invoke(quad);
                    for (String axis : List.of("x", "y", "z")) {
                        double value = coordinate(point, axis);
                        assertTrue(Double.isFinite(value));
                        assertTrue(Math.abs(value) <= (axis.equals("y") ? 3.05 : 2.2));
                    }
                }
                float alpha = (float) quad.getClass().getMethod("alpha").invoke(quad);
                assertTrue(alpha > 0 && alpha <= 1);
            }
        }
    }
    @Test void animationIsDeterministicAndMovesBetweenFrames() throws Exception {
        assertEquals(frame(1.25), frame(1.25));
        assertNotEquals(frame(1.25), frame(1.75));
        assertEquals(frame(1.25).size(), frame(1.75).size());
    }
    @Test void animationDoesNotJumpAtTheOneHourBoundary() throws Exception {
        var before = frame(3599.99);
        var after = frame(3600.01);
        for (int i = 0; i < before.size(); i++) {
            Object a = before.get(i).getClass().getMethod("a").invoke(before.get(i));
            Object b = after.get(i).getClass().getMethod("a").invoke(after.get(i));
            double squared = 0;
            for (String axis : List.of("x", "y", "z")) {
                squared += Math.pow(coordinate(a, axis) - coordinate(b, axis), 2);
            }
            assertTrue(squared < .06 * .06, "旋轉環不得在整點重置相位");
        }
    }
    @Test void allHorizontalFacingsAnchorEightBlocksBehindAndThreeBlocksAboveController() throws Exception {
        for (int[] back : new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
            Object anchor = geometry().getMethod("anchor", int.class, int.class).invoke(null, back[0], back[1]);
            assertEquals(.5 + back[0]*8, coordinate(anchor, "x"));
            assertEquals(3.5, coordinate(anchor, "y"));
            assertEquals(.5 + back[1]*8, coordinate(anchor, "z"));
        }
    }
    @Test void rendererRequiresFormationAndReusesTheDepthTestedAdditiveLayer() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/renderer/machine/StellarCloudLibraryRenderer.java"));
        assertTrue(source.contains("!machine.isFormed()"));
        assertTrue(source.contains("HostFxDraw.beamLayer()"));
        assertTrue(source.contains("StellarCloudCoreGeometry.anchor("));
        assertTrue(source.contains("getOpposite()"));
        assertTrue(source.contains("pushPose()") && source.contains("popPose()"));
        assertFalse(source.contains("disableDepthTest"));
        assertFalse(source.contains("setBlock"));
    }
}
