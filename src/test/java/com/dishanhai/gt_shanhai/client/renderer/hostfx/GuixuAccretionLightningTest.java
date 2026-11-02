package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
import com.dishanhai.gt_shanhai.client.renderer.preview.ShanhaiInstabilityGeometry;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.IntPredicate;
import static org.junit.jupiter.api.Assertions.*;

class GuixuAccretionLightningTest {
    private String[][] slices() {
        return StructureResourceLoader.INSTANCE.loadShapeInfoSlices("multiblock/guixu_vision_core.bin",
                "guixu_vision_core", (col, aisle, row, symbol) -> symbol);
    }

    private Object model() throws Exception {
        return GuixuAccretionLightning.class.getMethod("fromSlices", String[][].class, int.class, int.class, int.class)
                .invoke(null, slices(), -65, 67, -65);
    }

    @SuppressWarnings("unchecked")
    private List<ShanhaiInstabilityGeometry.Node> nodes(Object model) throws Exception {
        return (List<ShanhaiInstabilityGeometry.Node>) model.getClass().getMethod("nodes").invoke(model);
    }

    private ShanhaiInstabilityGeometry.Frame frame(Object model, double seconds, long seed, IntPredicate present) throws Exception {
        return (ShanhaiInstabilityGeometry.Frame) model.getClass().getMethod("frame", double.class, long.class, IntPredicate.class)
                .invoke(model, seconds, seed, present);
    }

    @Test void lightningIsDeterministicButChangesWithTimeAndHostPosition() throws Exception {
        Object model = model();
        assertEquals(frame(model, 1.25, 42, i -> true), frame(model, 1.25, 42, i -> true));
        assertNotEquals(frame(model, 1.25, 42, i -> true), frame(model, 1.3, 42, i -> true));
        assertNotEquals(frame(model, 1.25, 42, i -> true), frame(model, 1.25, 43, i -> true));
    }

    @Test void samplesExposedRealStructureAcrossTheRingPillarsAndStation() throws Exception {
        var nodes = nodes(model());
        var slices = slices();
        assertTrue(nodes.size() > 60 && nodes.size() <= 256);
        assertTrue(nodes.stream().anyMatch(n -> n.x() < -60));
        assertTrue(nodes.stream().anyMatch(n -> n.x() > 75));
        assertTrue(nodes.stream().anyMatch(n -> n.y() < -60));
        assertTrue(nodes.stream().anyMatch(n -> n.y() > 60));
        assertTrue(nodes.stream().anyMatch(n -> n.z() < -60));
        assertTrue(nodes.stream().anyMatch(n -> n.z() > 60));
        for (var n : nodes) {
            int x = n.x() + 65, y = 67 - n.y(), z = n.z() + 65;
            assertEquals(n.symbol(), slices[z][x].charAt(y));
            assertEquals(1, Math.abs(n.nx()) + Math.abs(n.ny()) + Math.abs(n.nz()));
            int ox = x + n.nx(), oy = y - n.ny(), oz = z + n.nz();
            assertTrue(ox < 0 || ox >= 148 || oy < 0 || oy >= 135 || oz < 0 || oz >= 131
                    || " MNP".indexOf(slices[oz][ox].charAt(oy)) >= 0, "Samples must be on exposed faces");
        }
    }

    @Test void absentStructureAndSingleRemainingSampleProduceNoFloatingArcs() throws Exception {
        var model = model();
        for (int step = 0; step < 40; step++) {
            assertTrue(frame(model, step * .125, 42, i -> false).quads().isEmpty());
            assertTrue(frame(model, step * .125, 42, i -> i == 0).quads().isEmpty());
        }
    }

    @Test void missingEitherEndpointDisablesThatArcAndMovingGroupsCannotCreateLongBridges() throws Exception {
        var model = GuixuAccretionLightning.fromSlices(new String[][]{
                {"A", " ", " ", " ", " ", " ", " ", " ", " ", " ", " ", " ", "A"}}, 0, 0, 0);
        boolean sawArc = false;
        for (int i = 0; i < 40; i++) {
            double time = i * .125;
            sawArc |= frame(model, time, 42, n -> true).arcs() > 0;
            assertTrue(frame(model, time, 42, n -> n == 0).quads().isEmpty());
            assertTrue(frame(model, time, 42, n -> n == 1).quads().isEmpty());
            var moved = model.frame(time, 42, n -> true,
                    n -> new ShanhaiInstabilityGeometry.Point(n * 50, .55, 0));
            assertTrue(moved.quads().isEmpty());
        }
        assertTrue(sawArc);
    }

    @Test void localStructureArcsRespectTheGeometryBudget() throws Exception {
        var model = model();
        int peak = 0;
        for (int i = 0; i < 80; i++) {
            var frame = frame(model, i * .125, 42, node -> true);
            peak = Math.max(peak, frame.arcs());
            assertTrue(frame.arcs() <= 12);
            assertEquals(frame.arcs() * 32, frame.quads().size(), "Same crossed glow/core strips as Nyarlathotep");
            for (var q : frame.quads()) {
                assertTrue(q.alpha() > 0 && q.alpha() <= .65F);
                assertEquals(1, q.blue());
                assertTrue(q.red() >= .5F && q.green() >= .25F);
                for (var p : new ShanhaiInstabilityGeometry.Point[]{q.a(), q.b(), q.c(), q.d()}) {
                    assertTrue(Double.isFinite(p.x() + p.y() + p.z()));
                    assertTrue(p.x() > -70 && p.x() < 87 && p.y() > -72 && p.y() < 72 && Math.abs(p.z()) < 70);
                }
            }
        }
        assertTrue(peak > 0, "Lightning must actually be emitted");
    }
}
