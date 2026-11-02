package com.dishanhai.gt_shanhai.client.renderer.preview;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：正式通道幾何的流向、預算與閘門；不需要遊戲／OpenGL 世界。 */
class ShanhaiNyarlathotepLinkGeometryTest {
    private Class<?> geometry() throws Exception {
        assertTrue(Files.exists(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/preview/ShanhaiNyarlathotepLinkGeometry.java")),
                "Missing two-ended portal geometry");
        return Class.forName("com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiNyarlathotepLinkGeometry");
    }
    private ShanhaiInstabilityGeometry.Point point(String method, boolean guixu, int channel, double progress) throws Exception {
        Class<?> type = geometry();
        if (method.equals("portalCenter")) return (ShanhaiInstabilityGeometry.Point)
                type.getMethod(method, boolean.class, int.class, double.class).invoke(null, guixu, channel, 9.0);
        return (ShanhaiInstabilityGeometry.Point) type.getMethod(method,
                boolean.class, int.class, double.class, double.class, double.class)
                .invoke(null, guixu, channel, progress, 2.5, 9.0);
    }
    private Object frame(double seconds, boolean guixu, boolean linked, boolean degraded) throws Exception {
        return geometry().getMethod("frame", double.class, long.class, boolean.class,
                boolean.class, boolean.class, double.class).invoke(null, seconds, 123L, guixu, linked, degraded, 9.0);
    }
    @SuppressWarnings("unchecked")
    private List<ShanhaiInstabilityGeometry.Quad> quads(Object frame, String name) throws Exception {
        return (List<ShanhaiInstabilityGeometry.Quad>) frame.getClass().getMethod(name).invoke(frame);
    }
    private double distance(ShanhaiInstabilityGeometry.Point a, ShanhaiInstabilityGeometry.Point b) {
        return Math.sqrt(Math.pow(a.x()-b.x(), 2)+Math.pow(a.y()-b.y(), 2)+Math.pow(a.z()-b.z(), 2));
    }
    @Test void portalsHaveDoubleSizeAndGuixuPortalsStayFartherOutsideTheHorizon() throws Exception {
        for (boolean guixu : new boolean[]{false, true}) {
            var portal = point("portalCenter", guixu, 0, 0);
            var disk = quads(frame(2, guixu, true, false), "opaque");
            assertEquals(5.1, distance(portal, disk.getFirst().b()), 1e-9,
                    "Portal short radius must double from 2.55 to 5.1 blocks");
            double longest = disk.subList(0, 32).stream()
                    .mapToDouble(q -> distance(portal, q.b())).max().orElseThrow();
            assertEquals(8.6, longest, 1e-9);
            if (guixu) assertEquals(51, distance(portal,
                    new ShanhaiInstabilityGeometry.Point(0, 0, 0)), 1e-9,
                    "At a nine-block horizon the new portal center is 3R+24, previously 2R+12");
        }
    }
    @Test void materialLeavesBlackSunAndEntersTheNyarlathotepPortals() throws Exception {
        for (int channel=0; channel<3; channel++) {
            var portal = point("portalCenter", false, channel, 0);
            assertEquals(0, distance(portal, point("materialPosition", false, channel, 1)), 1e-9);
            assertTrue(distance(portal, point("materialPosition", false, channel, 0)) > 10);
            assertTrue(distance(ShanhaiBlackSunGeometry.CENTER, point("materialPosition", false, channel, 0)) >= 9);
        }
    }
    @Test void materialEmergesFromGuixuPortalsAndFallsBelowTheHorizon() throws Exception {
        for (int channel=0; channel<3; channel++) {
            var portal = point("portalCenter", true, channel, 0);
            assertEquals(0, distance(portal, point("materialPosition", true, channel, 0)), 1e-9);
            var end = point("materialPosition", true, channel, 1);
            assertTrue(Math.sqrt(end.x()*end.x()+end.y()*end.y()+end.z()*end.z()) < 9);
        }
    }
    @Test void unlinkedMachinesNeverSubmitPortalOrMaterialGeometry() throws Exception {
        for (boolean guixu : new boolean[]{false,true}) {
            Object frame = frame(2,guixu,false,false);
            assertTrue(quads(frame,"opaque").isEmpty());
            assertTrue(quads(frame,"glow").isEmpty());
        }
    }
    @Test void geometryIsFiniteDynamicAndHasFixedNormalAndDegradedBudgets() throws Exception {
        int cap = geometry().getField("MAX_QUADS").getInt(null);
        for (boolean guixu : new boolean[]{false,true}) {
            var first = frame(2,guixu,true,false);
            var later = frame(2.4,guixu,true,false);
            var reduced = frame(2,guixu,true,true);
            assertNotEquals(quads(first,"glow"),quads(later,"glow"));
            assertTrue(quads(reduced,"glow").size()<quads(first,"glow").size());
            assertTrue(quads(first,"glow").size()+quads(first,"opaque").size()<=cap);
            for (String layer : new String[]{"opaque","glow"}) for(var quad : quads(first,layer)) {
                for(var p : List.of(quad.a(),quad.b(),quad.c(),quad.d()))
                    assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()));
                assertTrue(quad.alpha()>=0 && quad.alpha()<=1);
            }
        }
    }
    @Test void standaloneHostPortalGlowsSurviveWithoutAnyRenderedNyarlathotepController() throws Exception {
        String renderer=Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/preview/ShanhaiNyarlathotepInstabilityRenderer.java"));
        assertTrue(renderer.contains("WORLD_GLOW.isEmpty() && WORLD_LINK_GLOW.isEmpty()"),
                "Cross-dimension host must flush even when no Nyarlathotep BER runs in this world");
        assertTrue(renderer.contains("for (var batch : WORLD_LINK_GLOW)"));
        assertTrue(renderer.contains("batch.world() != client.level"),"Dimension change must not replay old batches");
        String host=Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/renderer/hostfx/GuixuHostFxRenderer.java"));
        int portal=host.indexOf("var linkedMatter");
        int pivot=host.lastIndexOf("HostFxRingTransforms.pivot(poseStack, cx, cy, cz, facing)",portal);
        int pop=host.indexOf("poseStack.popPose()",pivot);
        assertTrue(pivot>=0 && portal>pivot && portal<pop,"Portal must use the existing orientation and cell-center pivot");
        assertTrue(host.contains("host.isNyarlathotepLinkReady() && BlackHoleTuning.enabled() && blackHoleVisible"));
    }
}
