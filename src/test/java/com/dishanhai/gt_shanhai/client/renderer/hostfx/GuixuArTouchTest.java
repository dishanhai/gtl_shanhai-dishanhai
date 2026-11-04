package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：觸控與繪圖共面，鎖頁不能凍結機器讀數。 */
class GuixuArTouchTest {
    @Test void heldAttackTriggersOnlyOnceUntilReleased() {
        var press = new HostFxGlassArTouch.Press();
        assertTrue(press.begin());
        for (int tick = 0; tick < 40; tick++) assertFalse(press.begin());
        press.release();
        assertTrue(press.begin());
    }

    @Test void lockedModulePageStillRefreshesItsRuntimeData() {
        var state = new HostFxGlassArModel.State();
        state.moduleSlots = java.util.stream.IntStream.range(0, 10).toArray();
        state.modProgressPct = new byte[10];
        state.modulePages.step(1, 0, 2);
        state.modulePageTimeMillis = 90_000;
        state.modProgressPct[7] = 12;
        assertEquals("12%", HostFxGlassArModel.build(state).text(HostFxGlassArModel.Z_MODLIST, 202).text);
        state.modProgressPct[7] = 78;
        state.modulePageTimeMillis = 120_000;
        assertEquals("78%", HostFxGlassArModel.build(state).text(HostFxGlassArModel.Z_MODLIST, 202).text);
        assertTrue(HostFxGlassArModel.build(state).text(HostFxGlassArModel.Z_MODLIST, 6).text.contains("已锁定"));
        assertEquals(0, state.infoPages.page(120_000, 4), "模組鎖頁不影響資訊輪播");
        var otherHost = new HostFxGlassArModel.State();
        assertFalse(otherHost.modulePages.locked());
    }

    @Test void roomSideDisplayIsInFrontOfTheWholeGlassBlock() {
        assertTrue(HostFxGlassArPanel.textPlaneX(0) > HostFxGlassArPanel.FACE_LOCAL_X + 1);
        assertTrue(HostFxGlassArPanel.geomPlaneX(0) > HostFxGlassArPanel.FACE_LOCAL_X + 1);
        assertTrue(HostFxGlassArPanel.textPlaneX(1) < HostFxGlassArPanel.FACE_LOCAL_X);
        assertTrue(HostFxGlassArPanel.geomPlaneX(1) < HostFxGlassArPanel.FACE_LOCAL_X);
    }

    @Test void lockingKeepsCurrentPageAcrossAutomaticPageBoundaries() {
        var pages = new HostFxGlassArTouch.Pages();
        assertEquals(1, pages.page(15_000, 4));
        pages.toggle(15_000, 4);
        assertTrue(pages.locked());
        assertEquals(1, pages.page(55_000, 4));
        pages.toggle(55_000, 4);
        assertFalse(pages.locked());
        assertEquals(1, pages.page(55_000, 4));
        assertEquals(2, pages.page(65_000, 4));
    }

    @Test void manualPagingLocksWrapsAndClampsWhenModulesDisappear() {
        var pages = new HostFxGlassArTouch.Pages();
        pages.step(-1, 0, 6);
        assertTrue(pages.locked());
        assertEquals(5, pages.page(80_000, 6));
        assertEquals(1, pages.page(80_000, 2));
        pages.step(1, 80_000, 2);
        assertEquals(0, pages.page(90_000, 2));
        assertEquals(0, pages.page(90_000, 0));
    }

    @Test void rayHitsTheRenderedFaceBeforeGlassAndRejectsBackwardsOrOutOfReach() {
        double x = HostFxGlassArPanel.textPlaneX(0);
        double y = HostFxGlassArPanel.WALL_Y1 - 1;
        double z = HostFxGlassArPanel.WALL_Z1 - 5;
        var hit = HostFxGlassArTouch.raycast(x + 2, y, z, -1, 0, 0, 5);
        assertNotNull(hit);
        assertEquals(2, hit.distance(), 0.0001);
        assertEquals(5, hit.u(), 0.0001);
        assertEquals(1, hit.v(), 0.0001);
        assertNull(HostFxGlassArTouch.raycast(x + 2, y, z, 1, 0, 0, 5));
        assertNull(HostFxGlassArTouch.raycast(x + 2, y, z, -1, 0, 0, 1));
        assertNull(HostFxGlassArTouch.raycast(x + 2, y, z, 0, 1, 0, 5));
        assertNull(HostFxGlassArTouch.raycast(x + 2, y + 8, z, -1, 0, 0, 5));
    }

    @Test void reverseFaceMirrorsTheSamePixelCoordinates() {
        double x = HostFxGlassArPanel.textPlaneX(1);
        var hit = HostFxGlassArTouch.raycast(x - 2, HostFxGlassArPanel.WALL_Y1 - 1,
                HostFxGlassArPanel.WALL_Z0 + 5, 1, 0, 0, 5);
        assertNotNull(hit);
        assertEquals(5, hit.u(), 0.0001);
        assertEquals(1, hit.v(), 0.0001);
    }

    @Test void controlsUseExactlyTheDrawnButtonRectangles() {
        for (int section = 0; section < 2; section++) {
            for (int button = 0; button < 3; button++) {
                var rect = HostFxGlassArTouch.button(section, button);
                assertEquals(section * 3 + button,
                        HostFxGlassArTouch.buttonAt(rect.u + rect.w / 2, rect.v + rect.h / 2));
                assertTrue(rect.v1() <= HostFxGlassArPanel.WALL_H);
            }
        }
        assertEquals(-1, HostFxGlassArTouch.buttonAt(0, 0));
    }

    @Test void allFourMachineFacingsMapWorldRaysOntoTheSameButton() {
        var controller = new net.minecraft.core.BlockPos(-30, 86, -125);
        var button = HostFxGlassArTouch.button(1, 1);
        float u = button.u + button.w / 2;
        float v = button.v + button.h / 2;
        for (var facing : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.SOUTH}) {
            float x = HostFxGlassArPanel.textPlaneX(0) + 2;
            float y = HostFxGlassArPanel.WALL_Y1 - v;
            float z = HostFxGlassArPanel.WALL_Z1 - u;
            var center = HostFxGeometry.ringCenterInControllerFrame(facing);
            // 使用正式 renderer 的 pivot 矩陣：環心先旋轉，再加世界半格。
            var pose = new org.joml.Matrix4f().translate(controller.getX() + center[0] + 0.5F,
                            controller.getY() + center[1] + 0.5F, controller.getZ() + center[2] + 0.5F)
                    .rotateY((float) Math.toRadians(HostFxGeometry.yawDegrees(facing)));
            var world = pose.transformPosition(x, y, z, new org.joml.Vector3f());
            var ahead = pose.transformPosition(x - 1, y, z, new org.joml.Vector3f());
            var local = HostFxGeometry.cameraInRingLocal(facing,
                    world.x - controller.getX(), world.y - controller.getY(), world.z - controller.getZ());
            var next = HostFxGeometry.cameraInRingLocal(facing,
                    ahead.x - controller.getX(), ahead.y - controller.getY(), ahead.z - controller.getZ());
            var hit = HostFxGlassArTouch.raycast(local[0], local[1], local[2],
                    next[0] - local[0], next[1] - local[1], next[2] - local[2], 5);
            assertNotNull(hit, facing.toString());
            assertEquals(4, HostFxGlassArTouch.buttonAt(hit.u(), hit.v()), facing.toString());
        }
    }
}
