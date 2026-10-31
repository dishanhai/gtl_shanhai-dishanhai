package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuNebulaFieldTest {
    @Test void infallSpiralsBridgeTheOuterNebulaAndConfiguredHorizon() throws Exception {
        var infall = type().getMethod("sampleInfall", int.class, double.class, double.class);
        for (double horizon : new double[]{1, 5, 12}) for (int arm = 0; arm < 2; arm++) {
            double previous = 0;
            for (int step = 0; step <= 24; step++) {
                Object knot = infall.invoke(null, arm * 25 + step, 20D, horizon);
                double radius = Math.sqrt(Math.pow(value(knot, "x"), 2) + Math.pow(value(knot, "y"), 2)
                        + Math.pow(value(knot, "z"), 2));
                assertTrue(radius > previous, "The stream must continuously connect the horizon to the cloud");
                if (step == 0) assertTrue(radius >= horizon && radius <= horizon * 1.3);
                if (step == 24) assertEquals(76, radius, 1e-4);
                assertTrue(value(knot, "width") < radius * 0.45, "The cloud must taper before the horizon");
                assertEquals(1, Math.sqrt(Math.pow(value(knot, "tangentX"), 2)
                        + Math.pow(value(knot, "tangentY"), 2) + Math.pow(value(knot, "tangentZ"), 2)), 1e-5);
                previous = radius;
            }
        }
    }

    @Test void infallDensityTravelsInwardAndIsContinuousAcrossItsCycle() throws Exception {
        var density = type().getMethod("infallDensity", double.class, double.class);
        double first = (double) density.invoke(null, 0.7D, 0D);
        assertEquals(first, (double) density.invoke(null, 0.5D, 4D), 1e-6,
                "A gas crest must move 0.2 of the stream toward the core in four seconds");
        assertEquals(density.invoke(null, 0.4D, 0D), density.invoke(null, 0.4D, 20D));
        assertEquals((double) density.invoke(null, 0.4D, 19.999999),
                (double) density.invoke(null, 0.4D, 20.000001), 1e-5);
    }

    private Class<?> type() throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuNebulaField");
    }

    private Object sample(int index, double seconds) throws Exception {
        return type().getMethod("sample", int.class, double.class).invoke(null, index, seconds);
    }

    private double value(Object patch, String name) throws Exception {
        return ((Number) patch.getClass().getMethod(name).invoke(patch)).doubleValue();
    }

    @Test void compactCloudsKeepBroadGasBandsWithoutThePreviousOversizedRadius() throws Exception {
        int count = type().getField("PATCH_COUNT").getInt(null);
        assertTrue(count <= 48, "Keep the previous geometry budget");
        double low = 0, high = 0, outerRadius = 0, widest = 0;
        for (int i = 0; i < count; i++) {
            Object cloud = sample(i, 20.0);
            double x = value(cloud, "x"), y = value(cloud, "y"), z = value(cloud, "z");
            double radius = Math.sqrt(x * x + y * y + z * z);
            assertTrue(radius >= 50 && radius <= 136);
            outerRadius = Math.max(outerRadius, radius);
            widest = Math.max(widest, value(cloud, "width"));
            assertTrue(value(cloud, "alpha") >= 0 && value(cloud, "alpha") <= 1);
            low = Math.min(low, y);
            high = Math.max(high, y);
            for (String color : new String[]{"red", "green", "blue"}) {
                assertTrue(value(cloud, color) > 0 && value(cloud, color) <= 1);
            }
        }
        assertTrue(outerRadius > 120 && outerRadius < 136);
        assertTrue(widest > 25 && widest < 34, "Retain gas-band width at the smaller scale");
        assertTrue(low > -35 && high < 35, "The nebula should only lean slightly");
    }

    @Test void twoCloudLayersHaveActualVerticalSeparationAndGentleTimeVaryingInclination() throws Exception {
        var layered = type().getMethod("sample", int.class, double.class, int.class);
        var tilt = type().getMethod("tiltDegrees", double.class);
        assertNotEquals(tilt.invoke(null, 0D), tilt.invoke(null, 40D));
        for (double time : new double[]{0, 20, 60, 1000}) {
            assertTrue((double) tilt.invoke(null, time) >= 4 && (double) tilt.invoke(null, time) <= 8);
            for (int index : new int[]{3, 10, 20, 30, 40}) {
                Object lower = layered.invoke(null, index, time, 0), upper = layered.invoke(null, index, time, 1);
                assertTrue(value(upper, "y") - value(lower, "y") > 8, "Thickness must be geometry, not opacity");
            }
        }
    }

    @Test void cloudSpineDeformsInsteadOfOnlyRigidlyRotatingAStaticMaterial() throws Exception {
        Object a = sample(12, 0), b = sample(12, 15);
        double radiusA = Math.hypot(value(a, "x"), value(a, "z"));
        double radiusB = Math.hypot(value(b, "x"), value(b, "z"));
        assertTrue(Math.abs(radiusA - radiusB) > 0.2);
        var phase = type().getMethod("materialPhase", double.class);
        double first = ((Number) phase.invoke(null, 12D)).doubleValue();
        assertEquals(first, ((Number) phase.invoke(null, 92D)).doubleValue(), 1e-5);
        assertNotEquals(first, ((Number) phase.invoke(null, 22D)).doubleValue());
        assertTrue(((Number) phase.invoke(null, 1_000_000_000.25)).doubleValue() < Math.PI * 2);
    }

    @Test void sideViewHasRealCloudCrossSectionsRatherThanTwoFlatStackedLines() throws Exception {
        var layered = type().getMethod("sample", int.class, double.class, int.class);
        for (double time : new double[]{0, 20, 60}) for (int index : new int[]{6, 12, 18, 31, 37, 43}) {
            Object flat = layered.invoke(null, index, time, 0), body = layered.invoke(null, index, time, 1);
            double dot = value(flat, "sideX") * value(body, "sideX") + value(flat, "sideY") * value(body, "sideY")
                    + value(flat, "sideZ") * value(body, "sideZ");
            assertEquals(0, dot, 0.12, "The two moving sheets should cross rather than remain parallel");
            assertTrue(Math.abs(value(body, "sideY")) > 0.85);
            assertTrue(2 * Math.abs(value(body, "sideY")) * value(body, "width") > 28);
        }
    }

    @Test void adjacentRibbonSectionsShareNodesAndHaveTaperedTips() throws Exception {
        int segments = type().getField("SEGMENTS_PER_ARM").getInt(null);
        for (int arm = 0; arm < 2; arm++) {
            int start = arm * (segments + 1);
            assertEquals(0, value(sample(start, 0), "alpha"), 1e-6);
            assertEquals(0, value(sample(start + segments, 0), "alpha"), 1e-6);
            for (int i = 0; i < segments; i++) {
                Object a = sample(start + i, 0), b = sample(start + i + 1, 0);
                double distance = 0;
                for (String axis : new String[]{"x", "y", "z"}) {
                    double delta = value(b, axis) - value(a, axis);
                    distance += delta * delta;
                }
                assertTrue(distance > 1 && distance < 42 * 42);
                double tangentLength = 0;
                for (String axis : new String[]{"tangentX", "tangentY", "tangentZ"}) {
                    double component = value(a, axis);
                    tangentLength += component * component;
                }
                assertEquals(1, tangentLength, 1e-5);
                assertTrue(value(b, "u") > value(a, "u"));
            }
        }
    }

    @Test void cloudSamplingIsDeterministicAndSmoothAtLongTimes() throws Exception {
        for (double time : new double[]{0, 30.5, 1_000_000_000.25}) {
            Object first = sample(17, time);
            assertEquals(first, sample(17, time));
            for (String axis : new String[]{"x", "y", "z", "tangentX"}) assertTrue(Double.isFinite(value(first, axis)));
        }
        Object a = sample(17, 20), b = sample(17, 20.016);
        assertTrue(Math.abs(value(a, "x") - value(b, "x")) < 0.1);
        assertNotEquals(a, sample(17, 40));
    }

    @Test void worldSpaceCloudSectionsAreContinuousAndPerpendicularToTheSpine() throws Exception {
        for (double seconds : new double[]{0, 30.5, 1_000_000_000.25}) {
            for (int stride : new int[]{1, 3}) for (int arm = 0; arm < 2; arm++) {
                var previous = new org.joml.Vector3f();
                for (int step = 0; step <= 24; step += stride) {
                    Object node = sample(arm * 25 + step, seconds);
                    var side = new org.joml.Vector3f((float) value(node, "sideX"),
                            (float) value(node, "sideY"), (float) value(node, "sideZ"));
                    assertTrue(side.isFinite());
                    assertEquals(1, side.length(), 1e-5);
                    assertEquals(0, side.x * value(node, "tangentX") + side.y * value(node, "tangentY")
                            + side.z * value(node, "tangentZ"), 1e-5);
                    assertTrue(previous.dot(side) >= -1e-5, "Adjacent cross sections must not swap their edges");
                    previous.set(side);
                }
            }
        }
    }

    @Test void disabledCloudsHaveZeroBudgetAndDegradedCloudsHaveLessWork() throws Exception {
        var count = type().getMethod("patchCount", boolean.class, boolean.class);
        assertEquals(0, count.invoke(null, false, false));
        assertEquals(0, count.invoke(null, false, true));
        int full = (int) count.invoke(null, true, false);
        int degraded = (int) count.invoke(null, true, true);
        assertTrue(degraded > 0 && degraded < full && full <= 96);
    }

    @Test void ribbonTextureHasFineColoredFilamentsAndSoftTransverseEdges() throws Exception {
        var path = java.nio.file.Path.of("src/main/resources/assets/gt_shanhai/textures/effect/guixu_nebula.png");
        assertTrue(java.nio.file.Files.isRegularFile(path));
        var texture = javax.imageio.ImageIO.read(path.toFile());
        assertEquals(1024, texture.getWidth());
        assertEquals(256, texture.getHeight());
        int highestAlpha = 0, colored = 0, wisps = 0;
        for (int y = 0; y < texture.getHeight(); y++) for (int x = 0; x < texture.getWidth(); x++) {
            int rgba = texture.getRGB(x, y), alpha = rgba >>> 24;
            if (y == 0 || y == 255) assertEquals(0, alpha);
            highestAlpha = Math.max(highestAlpha, alpha);
            if (alpha > 32 && ((rgba >>> 16) & 255) != (rgba & 255)) colored++;
            if (alpha > 130) wisps++;
        }
        assertTrue(highestAlpha > 180 && highestAlpha < 255);
        assertTrue(colored > 30_000, "Cloud color belongs in the texture, not a uniform pale blob");
        assertTrue(wisps > 500, "Bright filaments must survive additive rendering");
    }

    @Test void disabledRenderersReturnBeforeAccessingGraphicsResources() throws Exception {
        Class<?> pose = Class.forName("com.mojang.blaze3d.vertex.PoseStack");
        Class<?> nebula = Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuNebulaRenderer");
        nebula.getMethod("render", pose, float[].class, double.class, float.class, boolean.class, boolean.class)
                .invoke(null, null, null, 0D, 1F, false, false);
        nebula.getMethod("render", pose, float[].class, double.class, float.class, boolean.class, boolean.class,
                        boolean.class, double.class)
                .invoke(null, null, null, 0D, 1F, false, false, true, 5D);
        Class<?> stars = Class.forName("com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarRenderer");
        stars.getMethod("render", pose, float[].class, double.class, double.class,
                        float.class, float.class, float.class, boolean.class, boolean.class)
                .invoke(null, null, null, 0D, 5D, 1F, 1F, 0F, false, false);
    }
}
