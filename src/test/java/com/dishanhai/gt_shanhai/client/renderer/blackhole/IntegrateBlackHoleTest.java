package com.dishanhai.gt_shanhai.client.renderer.blackhole;

// Author: dishanhai
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleTuning.RenderMode.*;

class IntegrateBlackHoleTest {
    @Test
    void hostWhiteHoleOverrideMatchesOnlyTheRequestedWorldAndController() {
        Object world = new Object();
        Object anotherWorld = new Object();
        long controller = 48L;
        try {
            BlackHoleTuning.setHostCenter(world, controller, new double[]{0.5, 64.5, 0.5});
            assertEquals(KERR, BlackHoleTuning.hostRenderMode(world, controller, false, false));
            assertEquals(LEGACY, BlackHoleTuning.hostRenderMode(world, controller, true, false));
            assertEquals(WHITE_HOLE, BlackHoleTuning.hostRenderMode(world, controller, false, true));
            BlackHoleTuning.forceHostWhiteHole(world, controller);
            assertEquals(WHITE_HOLE, BlackHoleTuning.hostRenderMode(world, controller, true, false));
            assertEquals(LEGACY, BlackHoleTuning.hostRenderMode(anotherWorld, controller, true, false));
            assertTrue(BlackHoleTuning.isHostWhiteHoleForced(world, controller));
            assertFalse(BlackHoleTuning.isHostWhiteHoleForced(world, controller + 1));
            assertFalse(BlackHoleTuning.isHostWhiteHoleForced(anotherWorld, controller));

            BlackHoleTuning.clearHostCenter(world, controller);
            BlackHoleTuning.setHostCenter(world, controller, new double[]{0.5, 64.5, 0.5});
            assertTrue(BlackHoleTuning.isHostWhiteHoleForced(world, controller));
            BlackHoleTuning.clearHostWhiteHoleOverride();
            assertEquals(LEGACY, BlackHoleTuning.hostRenderMode(world, controller, true, false));
            assertTrue(BlackHoleTuning.hasHostCenter());
            assertFalse(BlackHoleTuning.isHostWhiteHoleForced(world, controller));
        } finally {
            BlackHoleTuning.clearHostWhiteHoleOverride();
            BlackHoleTuning.clearHostCenter(world, controller);
        }
    }

    @Test
    void approachingTheVisibleShadowKeepsTheExteriorAndPreservesItsDrawArea() {
        for (double rs : new double[]{0.4, 2, 4.6}) {
            double shadow = BlackHoleLensSpec.shadowRadiusBlocks(rs);
            assertEquals(shadow, BlackHoleLensSpec.insideThresholdBlocks(rs));
            assertTrue(BlackHoleLensSpec.insideRatioOf(shadow * 1.02, rs) > 1);
            assertTrue(BlackHoleLensSpec.insideRatioOf(shadow * .99, rs) < 1);
        }
        double pixels = 2560D * 1600D * .45D;
        var near = BlackHoleLensMath.rectWithShadow(1280, 800, 1300, 1100, 2560, 1600, pixels);
        assertEquals(2200, near.w);
        assertEquals(1600, near.h);
        assertEquals(256, BlackHoleLensMath.copyStart((int) near.x0, (int) (near.x0 + near.w), 2048, 1280));
        assertEquals(0, BlackHoleLensMath.copyStart(0, 2560, 2048, 24));
        assertEquals(512, BlackHoleLensMath.copyStart(0, 2560, 2048, 2536));
    }

    @Test
    void unresolvedSurfaceWavesFadeOutInsteadOfDrawingDenseWhiteRings() {
        var p = new BlackHoleShaderProbe.Params(new float[16], new float[]{0, 0, -240},
                new float[]{0, .82F, .57F}, 768, 768, 0, 0, 768, 768, 376,
                384, 384, .4, Math.tan(Math.toRadians(48) / 2), .42, 0, 0, 3);
        for (double x = 381; x <= 387; x += .25) {
            assertArrayEquals(new double[3], BlackHoleShaderProbe.shade(p,
                    (px, py) -> new double[3], x, 384), 1e-9);
        }
    }

    @Test
    void surfaceWavesHaveAnimatedWhiteEdgesAndKeepTheirBlackInterior() {
        double energy = 0, movement = 0;
        for (double time : new double[]{0, 3}) {
            var p = new BlackHoleShaderProbe.Params(new float[16], new float[]{0, 0, -24},
                    new float[]{0, .82F, .57F}, 768, 768, 0, 0, 768, 768, 376,
                    384, 384, 2, Math.tan(Math.toRadians(48) / 2), .42, 0, 0, time);
            double radius = 384 / p.tanHalfFov() * 2 * BlackHoleShaderProbe.B_CRIT / 24;
            for (int offset = (int) (radius * .66); offset < radius * .90; offset++) {
                double[] color = BlackHoleShaderProbe.shade(p, (x, y) -> new double[3], 384 + offset, 384);
                assertEquals(color[0], color[1], 1e-9);
                assertEquals(color[0], color[2], 1e-9);
                assertTrue(color[0] >= 0 && color[0] <= .8, "White edges must retain the black surface");
                energy += color[0];
                movement += color[0] * offset * (time == 0 ? -1 : 1);
            }
        }
        assertTrue(energy > .5, "The surface needs visible white wave crests");
        assertTrue(Math.abs(movement) > .1, "The wave crests must move over time");
    }

    @Test
    void offlineProbeKeepsCapturedRaysBlackAcrossTimeAndDistance() {
        for (float distance : new float[]{24, 80, 240}) for (double time : new double[]{0, 3, 20}) {
            var params = new BlackHoleShaderProbe.Params(new float[16], new float[]{0, 0, -distance},
                    new float[]{0, .82F, .57F}, 768, 768, 0, 0, 768, 768, 376,
                    384, 384, 2, Math.tan(Math.toRadians(48) / 2), .42, .62, 1, time);
            for (double offset : new double[]{0, .2, .5}) {
                double radius = 384 / params.tanHalfFov() * 2 * BlackHoleShaderProbe.B_CRIT / distance;
                double[] color = BlackHoleShaderProbe.shade(params, (x, y) -> new double[]{1, 1, 1},
                        384 + radius * offset, 384);
                assertArrayEquals(new double[3], color, 1e-9);
            }
        }
    }

    @Test
    void captureAndDiskRadianceAreProducedByGeodesicsBeforeCompositing() throws Exception {
        String shader = Files.readString(Path.of("src/main/resources/assets/gt_shanhai/shaders/core/guixu_blackhole.fsh"));
        String trace = Files.readString(Path.of("src/main/resources/assets/gt_shanhai/shaders/include/guixu_kerr.glsl"));
        assertTrue(trace.contains("m.r <= captureRadius"));
        assertTrue(trace.contains("transferDisk(y, next, k1, k4, pt, stepSize, light)"));
        assertTrue(shader.contains("float transmitted = light.a * (1.0 - captured)"));
        assertTrue(shader.contains("mix(1.0, transmitted, edge * (1.0 - .80 * whiteWorld))"));
        assertTrue(shader.contains("texture(RayLight"));
        assertFalse(shader.contains("ringCore"));
        assertFalse(shader.contains("surfaceRipple"));
    }

    @Test
    void onlyTheExteriorLensWithAnEnabledDepthGateCopiesDepth() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/"
                + "blackhole/BlackHoleLensRenderer.java"));
        String backgroundCopy = source.substring(source.indexOf("private static boolean copyBackground("),
                source.indexOf("private static void drainGlErrors("));
        assertFalse(backgroundCopy.contains("copyDepthRect("), "The interior shares the color copy");
        assertTrue(source.contains("copyScene = copyWorld || (whiteWorld && depthOk)"));
        int gatedCopy = source.indexOf("if (copyScene && depthOk && (whiteWorld || BlackHoleTuning.depthGate()) && ensureDepthTexture()) {");
        assertTrue(gatedCopy >= 0, "Only valid world depth may be copied for lens sampling");
        assertTrue(source.substring(gatedCopy, source.indexOf('}', gatedCopy))
                .contains("copyDepthRect(f.copyX0, f.copyY0, f.copyW, f.copyH);"));
    }

    @Test
    void exteriorLensAppliesPremultipliedBlendingForPixelsOutsideTheBackgroundCopy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/"
                + "blackhole/BlackHoleLensRenderer.java"));
        int apply = source.indexOf("public void apply()", source.indexOf("class ExteriorShader"));
        assertTrue(apply >= 0);
        String body = source.substring(apply, source.indexOf("\n        }", apply));
        int applySharedState = body.indexOf("super.apply();");
        int blend = body.indexOf("RenderSystem.enableBlend();");
        assertTrue(applySharedState >= 0 && blend > applySharedState);
        assertTrue(body.indexOf("RenderSystem.blendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);") > blend);
    }

    @Test
    void disabledLensUsesTheAnimatedExteriorWithoutCopyingTheBackground() throws Exception {
        Path renderer = Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/blackhole/BlackHoleLensRenderer.java");
        Path shaders = Path.of("src/main/resources/assets/gt_shanhai/shaders/core");
        String source = Files.readString(renderer);
        String fragment = Files.readString(shaders.resolve("guixu_blackhole.fsh"));
        String program = Files.readString(shaders.resolve("guixu_blackhole.json"));
        assertTrue(source.contains("drawExterior(g, f, depthOk, sampleBackground)"));
        assertTrue(source.contains("copyWorld = sampleBackground && !f.whiteDemo && (f.legacyBlackHole || f.kerr.worldSky() > 0.0F)"));
        assertTrue(source.contains("copyScene && !ensureBgTexture("));
        assertTrue(source.contains("copyScene && !copyBackground(g, main, f, copyWorld)"));
        assertTrue(source.contains("shader.setSampler(\"SceneColor\", copyWorld ? bgTexId : null)"));
        assertTrue(source.contains("\"uSampleBackground\").set(sampleBackground ? 1.0F : 0.0F)"));
        assertTrue(fragment.contains("if (uSampleBackground < 0.5)"));
        assertTrue(fragment.contains("float coverage = 1.0 - transmitted * (1.0 - skyCoverage);"));
        assertTrue(fragment.contains("vec4(mix(displayEncode(emission) * edge, scene, skyCoverage), coverage)"));
        assertTrue(program.contains("\"srcrgb\": \"srcalpha\""));
        assertTrue(program.contains("\"dstrgb\": \"1-srcalpha\""));
        assertTrue(program.contains("\"name\": \"uSampleBackground\""));
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
