package com.dishanhai.gt_shanhai.client.renderer.blackhole;

// Author: dishanhai
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntegrateBlackHoleTest {
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
