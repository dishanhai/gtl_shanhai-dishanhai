package com.dishanhai.gt_shanhai.client.renderer.preview;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry.*;

/** 山海：黑核遮擋、全結構啟用條件、時間動態與固定幾何預算。 */
class ShanhaiBlackSunGeometryTest {
    private ShanhaiInstabilityGeometry geometry() throws Exception {
        return ShanhaiInstabilityGeometry.fromMb(Files.readString(Path.of(
                "src/main/resources/assets/gt_shanhai/multiblock/nyarlathoteps_tentacle.mb")));
    }

    @Test
    void blackCoreIsOpaqueSphericalAndSeparateFromEmissiveCorona() throws Exception {
        var frame = ShanhaiBlackSunGeometry.frame(1, 42, geometry().nodes(), i -> true);
        assertFalse(frame.opaque().isEmpty(), "A black core needs opaque geometry, additive black is invisible");
        assertTrue(frame.glow().size() > 2000, "Corona, force-field hoops and helices remain visible in a full view");
        for (var q : frame.opaque()) {
            assertEquals(0, q.red()); assertEquals(0, q.green()); assertEquals(0, q.blue());
            assertEquals(1, q.alpha());
            for (var p : new Point[]{q.a(), q.b(), q.c(), q.d()}) {
                var c = ShanhaiBlackSunGeometry.CENTER;
                assertEquals(ShanhaiBlackSunGeometry.RADIUS,
                        Math.sqrt(Math.pow(p.x()-c.x(), 2)+Math.pow(p.y()-c.y(), 2)+Math.pow(p.z()-c.z(), 2)), 1e-8);
                assertTrue(p.y() > 23, "Core floats clear of the structure top");
            }
        }
    }

    @Test
    void absentPartialOrOneSidedStructureCannotSummonBlackSun() throws Exception {
        var nodes = geometry().nodes();
        for (java.util.function.IntPredicate present : new java.util.function.IntPredicate[]{
                i -> false, i -> i == 0, i -> nodes.get(i).x() < 0}) {
            var frame = ShanhaiBlackSunGeometry.frame(2, 0, nodes, present);
            assertTrue(frame.opaque().isEmpty()); assertTrue(frame.glow().isEmpty());
            assertEquals(0, frame.streams());
        }
    }

    @Test
    void fullStructureHasAnimatedBoundedInfallAndShockwaves() throws Exception {
        var nodes = geometry().nodes();
        assertEquals(ShanhaiBlackSunGeometry.frame(1, 42, nodes, i -> true),
                ShanhaiBlackSunGeometry.frame(1, 42, nodes, i -> true));
        assertNotEquals(ShanhaiBlackSunGeometry.frame(1, 42, nodes, i -> true),
                ShanhaiBlackSunGeometry.frame(1.25, 42, nodes, i -> true));
        for (int step = 0; step < 160; step++) {
            var frame = ShanhaiBlackSunGeometry.frame(step * 0.125, 42, nodes, i -> true);
            assertTrue(frame.streams() > 0 && frame.streams() <= ShanhaiBlackSunGeometry.MAX_STREAMS);
            assertTrue(frame.opaque().size()+frame.glow().size() <= ShanhaiBlackSunGeometry.MAX_QUADS);
            assertTrue(frame.glow().stream().anyMatch(q -> Math.abs(q.a().x()) > 45), "Effects reach the tentacles");
            Stream.concat(frame.opaque().stream(), frame.glow().stream()).forEach(q -> {
                assertTrue(q.alpha() > 0 && q.alpha() <= 1);
                for (float color : new float[]{q.red(), q.green(), q.blue()}) assertTrue(color >= 0 && color <= 1);
                for (var p : new Point[]{q.a(), q.b(), q.c(), q.d()}) {
                    assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()));
                    assertTrue(Math.abs(p.x()) < 96 && p.y() > -32 && p.y() < 60 && p.z() > -60 && p.z() < 12);
                }
            });
        }
        assertThrows(IllegalArgumentException.class,
                () -> ShanhaiBlackSunGeometry.frame(Double.NaN, 0, nodes, i -> true));
    }
}
