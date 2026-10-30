package com.dishanhai.gt_shanhai.client.renderer.blackhole;

// Author: dishanhai
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntegrateBlackHoleTest {
    @Test
    void horizonCannotExposeDiskLightAndSubpixelNoiseIsFiltered() throws Exception {
        String shader = Files.readString(Path.of("src/main/resources/assets/gt_shanhai/shaders/core/guixu_blackhole.fsh"));
        assertTrue(shader.contains("length(cross(bh, dir))"), "Avoid cancellation when looking toward the core");
        assertTrue(shader.contains("diskCol * (1.0 - shadowMask)"), "Captured rays must not show small duplicate disks");
        assertTrue(shader.contains("dFdx(p)") && shader.contains("dFdy(p)"), "Filter noise at the actual pixel footprint");
        assertTrue(shader.contains("max(RING_WIDTH, aa)"), "Keep the photon ring resolved at distance");
    }

    private static Class<?> helper(String name) throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.renderer.blackhole." + name);
    }

    @Test
    void teleportRoundTripAndDimensionSwitchInvalidateTheCameraFrame() throws Exception {
        Class<?> type = helper("BlackHoleCameraGuard");
        Object guard = type.getConstructor().newInstance();
        var update = type.getMethod("update", double.class, double.class, double.class, String.class);
        assertNull(update.invoke(guard, 0D, 64D, 0D, "minecraft:overworld"));
        assertNull(update.invoke(guard, 1D, 64D, 0D, "minecraft:overworld"));
        assertEquals("teleport", update.invoke(guard, 1000D, 64D, 0D, "minecraft:overworld"));
        assertEquals("teleport", update.invoke(guard, 1D, 64D, 0D, "minecraft:overworld"));
        assertEquals("dimension", update.invoke(guard, 1D, 64D, 0D, "minecraft:the_nether"));
        assertEquals(3L, type.getMethod("rebases").invoke(guard));
        type.getMethod("forget").invoke(guard);
        assertNull(update.invoke(guard, -2000D, 64D, 0D, "minecraft:overworld"));
    }

    @Test
    void ordinaryMovementMeasuresEachFrameInsteadOfAccumulatingDistance() throws Exception {
        Class<?> type = helper("BlackHoleCameraGuard");
        Object guard = type.getConstructor().newInstance();
        var update = type.getMethod("update", double.class, double.class, double.class, String.class);
        for (int frame = 0; frame < 200; frame++) {
            assertNull(update.invoke(guard, (double) frame, 64D, 0D, "minecraft:overworld"),
                    "one block per frame must not be classified as a teleport at frame " + frame);
        }
        assertEquals(0L, type.getMethod("rebases").invoke(guard));
        assertEquals("teleport", update.invoke(guard, 500D, 64D, 0D, "minecraft:overworld"));
    }

    @Test
    void fallbackRetriesUseBackoffAndNeverResurrectDisabledRendering() throws Exception {
        Class<?> type = helper("BlackHoleLevelRetry");
        Object retry = type.getConstructor().newInstance();
        var tick = type.getMethod("tick", int.class);
        for (int frame = 1; frame < 60; frame++) assertEquals(false, tick.invoke(retry, 2));
        assertEquals(true, tick.invoke(retry, 2));
        assertEquals(120, type.getMethod("waitFrames").invoke(retry));
        for (int frame = 1; frame < 120; frame++) assertEquals(false, tick.invoke(retry, 2));
        assertEquals(true, tick.invoke(retry, 2));
        assertEquals(240, type.getMethod("waitFrames").invoke(retry));
        assertEquals(false, tick.invoke(retry, 3));
        assertEquals(false, type.getMethod("armed").invoke(retry));
        type.getMethod("forceNext").invoke(retry);
        assertEquals(true, tick.invoke(retry, 2));
        assertEquals(false, tick.invoke(retry, 1));
    }

    @Test
    void hostRadiusAndLensOverridesAreTransientAndClearable() throws Exception {
        Class<?> type = helper("BlackHoleTuning");
        try {
            type.getMethod("setHostRadiusBlocks", float.class).invoke(null, 12F);
            assertEquals(true, type.getMethod("hasHostRadiusBlocks").invoke(null));
            assertEquals(12F, type.getMethod("hostRadiusBlocks").invoke(null));
            type.getMethod("setHostLensEnabled", boolean.class).invoke(null, false);
            assertEquals(false, type.getMethod("hostLensEnabled").invoke(null));
        } finally {
            type.getMethod("clearHostRadiusBlocks").invoke(null);
            type.getMethod("clearHostLensEnabled").invoke(null);
        }
        assertEquals(false, type.getMethod("hasHostRadiusBlocks").invoke(null));
        assertEquals(false, type.getMethod("hasHostLensEnabled").invoke(null));
    }

    @Test
    void hostCentersStopAtChunkRangeAndWorldChangesWithoutAControllerRenderCallback() throws Exception {
        Class<?> type = helper("BlackHoleTuning");
        Object world = new Object(), otherWorld = new Object();
        var bind = type.getMethod("setHostCenter", Object.class, long.class, double[].class);
        var allowed = type.getMethod("hostCenterInRange", Object.class, double.class, double.class, int.class);
        try {
            bind.invoke(null, world, 123L, new double[]{143.5, 80, 0.5});
            assertEquals(true, type.getMethod("hasHostCenter").invoke(null));
            assertEquals(123L, type.getMethod("hostCenterControllerPos").invoke(null));
            assertEquals(true, allowed.invoke(null, world, 0D, 0D, 8));
            assertEquals(false, allowed.invoke(null, world, -16D, 0D, 8));
            assertEquals(false, allowed.invoke(null, otherWorld, 0D, 0D, 8));
            type.getMethod("clearHostCenter").invoke(null);
            assertEquals(false, type.getMethod("hasCenterOverride").invoke(null));
        } finally {
            type.getMethod("setCenter", double[].class).invoke(null, (Object) null);
        }
    }

    @Test
    void manualCentersAreNotRemovedByHostCleanupAndNegativeChunkCoordinatesAreFloored() throws Exception {
        Class<?> type = helper("BlackHoleTuning");
        var within = type.getMethod("withinChunkRenderDistance", double.class, double.class,
                double.class, double.class, int.class);
        assertEquals(true, within.invoke(null, -0.1D, 0D, 127.5D, 0.5D, 8));
        assertEquals(false, within.invoke(null, -0.1D, 0D, 128.5D, 0.5D, 8));
        try {
            type.getMethod("setHostCenter", Object.class, long.class, double[].class)
                    .invoke(null, new Object(), 0L, new double[]{10.5, 80, 10.5});
            type.getMethod("setCenter", double[].class).invoke(null, (Object) new double[]{10.5, 80, 10.5});
            type.getMethod("clearHostCenter").invoke(null);
            assertEquals(false, type.getMethod("hasHostCenter").invoke(null));
            assertArrayEquals(new double[]{10.5, 80, 10.5}, (double[]) type.getMethod("centerOverride").invoke(null));
        } finally {
            type.getMethod("setCenter", double[].class).invoke(null, (Object) null);
        }
    }

    @Test
    void outOfRangeControllersCannotClearAnotherHostsCenter() throws Exception {
        Class<?> type = helper("BlackHoleTuning");
        Object world = new Object();
        var clear = type.getMethod("clearHostCenter", Object.class, long.class);
        try {
            type.getMethod("setHostCenter", Object.class, long.class, double[].class)
                    .invoke(null, world, 123L, new double[]{10.5, 80, 10.5});
            assertEquals(false, clear.invoke(null, world, 456L));
            assertEquals(false, clear.invoke(null, new Object(), 123L));
            assertEquals(true, type.getMethod("hasHostCenter").invoke(null));
            assertEquals(true, clear.invoke(null, world, 123L));
            assertEquals(false, type.getMethod("hasHostCenter").invoke(null));
        } finally {
            type.getMethod("setCenter", double[].class).invoke(null, (Object) null);
        }
    }

    @Test
    void insideWindowContractsStayFiniteAcrossTheHorizon() throws Exception {
        Class<?> type = helper("BlackHoleInsideSpec");
        var depth = type.getMethod("depthOf", double.class, double.class);
        assertEquals(0D, depth.invoke(null, 20D, 10D));
        assertEquals(1D, depth.invoke(null, 0D, 10D));
        var radius = type.getMethod("windowRadius", double.class);
        double outer = (double) radius.invoke(null, 0D);
        double inner = (double) radius.invoke(null, 1D);
        assertTrue(Double.isFinite(outer) && Double.isFinite(inner));
        assertTrue(inner > 0D && outer > inner);
    }

    @Test
    void guixuShaderResourcesUseTheirOwnNamespaceAndInsideUniformContract() throws Exception {
        Path root = Path.of("src/main/resources/assets/gt_shanhai/shaders/core");
        String lens = Files.readString(root.resolve("guixu_blackhole.json"));
        String inside = Files.readString(root.resolve("guixu_blackhole_inside.json"));
        assertTrue(lens.contains("gt_shanhai:guixu_blackhole"));
        assertTrue(inside.contains("gt_shanhai:guixu_blackhole_inside"));
        for (String uniform : (String[]) helper("BlackHoleInsideSpec").getField("UNIFORMS").get(null)) {
            assertTrue(inside.contains("\"" + uniform + "\""), uniform);
        }
        assertTrue(Files.exists(root.resolve("guixu_blackhole_inside.fsh")));
        assertEquals("guixu_blackhole_inside", helper("BlackHoleInsideSpec").getField("SHADER_ID").get(null));
    }
}
