package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：字形只提交可見的一面，正反文字與圖形共用座標。 */
class GuixuArTextFaceTest {
    @Test void eachCameraSideSelectsExactlyOneFace() throws Exception {
        var select = assertDoesNotThrow(() -> HostFxGlassArPanel.class.getMethod("visibleTextFace", float.class));
        assertEquals(0, select.invoke(null, HostFxGlassArPanel.FACE_LOCAL_X + 3));
        assertEquals(1, select.invoke(null, HostFxGlassArPanel.FACE_LOCAL_X - 3));
        assertEquals(0, select.invoke(null, HostFxGlassArPanel.FACE_LOCAL_X));
    }

    @Test void capturedFaceCannotChangeWhenTheNextHostUpdatesTheReadingState() throws Exception {
        var state = new HostFxGlassArModel.State();
        var face = assertDoesNotThrow(() -> state.getClass().getField("textFace"));
        face.setInt(state, 0);
        var model = HostFxGlassArModel.build(state);
        face.setInt(state, 1);
        assertEquals(0, model.getClass().getField("textFace").getInt(model));
        assertEquals(1, HostFxGlassArModel.build(state).getClass().getField("textFace")
                .getInt(HostFxGlassArModel.build(state)));
    }

    @Test void neitherTextFaceLiesOnTheGlassSurface() {
        assertTrue(HostFxGlassArPanel.textPlaneX(0) < HostFxGlassArPanel.FACE_LOCAL_X);
        assertTrue(HostFxGlassArPanel.textPlaneX(1) > HostFxGlassArPanel.FACE_LOCAL_X);
    }

    @Test void bothSidesStartAtTheirOwnWallEdgeAndGeometryStaysBehindText() throws Exception {
        var z = assertDoesNotThrow(() -> HostFxGlassArPanel.class.getMethod("faceOriginZ", int.class));
        var yaw = assertDoesNotThrow(() -> HostFxGlassArPanel.class.getMethod("faceYaw", int.class));
        var geom = assertDoesNotThrow(() -> HostFxGlassArPanel.class.getMethod("geomPlaneX", int.class));
        assertEquals(HostFxGlassArPanel.WALL_Z1, (Float) z.invoke(null, 0));
        assertEquals(HostFxGlassArPanel.WALL_Z0, (Float) z.invoke(null, 1));
        assertEquals(90F, (Float) yaw.invoke(null, 0));
        assertEquals(-90F, (Float) yaw.invoke(null, 1));
        assertTrue((Float) geom.invoke(null, 0) < HostFxGlassArPanel.textPlaneX(0));
        assertTrue((Float) geom.invoke(null, 1) > HostFxGlassArPanel.textPlaneX(1));
    }

    @Test void rendererHasOneGlyphLoopAndPositionsTheFaceBeforeRotating() throws Exception {
        String text = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/hostfx/HostFxGlassArText.java"));
        String draw = text.substring(text.indexOf("static int drawTexts("), text.indexOf("private static com.mojang", text.indexOf("static int drawTexts(")));
        assertFalse(draw.contains("for (int face"), "同一觀察點不可同時提交正反字形");
        assertTrue(draw.contains("final int face = model.textFace"));
        assertTrue(draw.indexOf("textPlaneX(face)") < draw.indexOf("mulPose("));
        assertTrue(draw.contains("HostFxGlassArPanel.faceOriginZ(face)"));
        String geom = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/hostfx/HostFxGlassArDraw.java"));
        assertTrue(geom.contains("HostFxGlassArPanel.faceOriginZ(model.textFace)"));
    }

    @Test void faceSeparationMovesAlongTheWallNormalInsteadOfOnePixelAcrossTheGlyph() {
        float scale = HostFxGlassArPanel.PIXEL;
        var oldBase = new org.joml.Matrix4f().translate(HostFxGlassArPanel.textPlaneX(0),
                HostFxGlassArPanel.WALL_Y1, HostFxGlassArPanel.WALL_Z1).rotateY((float) Math.PI / 2);
        var oldFront = new org.joml.Matrix4f(oldBase).scale(scale, -scale, -scale)
                .transformPosition(100, 30, 0, new org.joml.Vector3f());
        var oldBack = new org.joml.Matrix4f(oldBase)
                .translate(HostFxGlassArPanel.TEXT_OFFSET, 0, 0).scale(scale, -scale, scale)
                .transformPosition(100, 30, 0, new org.joml.Vector3f());
        assertEquals(0, oldBack.x - oldFront.x, 0.00001F);
        assertEquals(-scale, oldBack.z - oldFront.z, 0.00001F, "舊路徑會橫向偏移一像素");

        var points = new org.joml.Vector3f[2];
        for (int face = 0; face < 2; face++) {
            points[face] = new org.joml.Matrix4f().translate(HostFxGlassArPanel.textPlaneX(face),
                            HostFxGlassArPanel.WALL_Y1, HostFxGlassArPanel.faceOriginZ(face))
                    .rotateY((float) Math.toRadians(HostFxGlassArPanel.faceYaw(face))).scale(scale, -scale, -scale)
                    .transformPosition(HostFxGlassArPanel.px(HostFxGlassArPanel.WALL_W / 2), 30, 0,
                            new org.joml.Vector3f());
        }
        assertEquals(2 * HostFxGlassArPanel.TEXT_OFFSET, points[1].x - points[0].x, 0.00001F);
        assertEquals(points[0].z, points[1].z, 0.00001F);
        assertEquals(points[0].y, points[1].y, 0.00001F);
    }
}
