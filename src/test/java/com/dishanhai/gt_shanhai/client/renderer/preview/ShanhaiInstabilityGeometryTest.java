package com.dishanhai.gt_shanhai.client.renderer.preview;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：驗證全結構覆蓋、取樣真實表面、動態與頂點預算。 */
class ShanhaiInstabilityGeometryTest {
    private static final Path MB = Path.of("src/main/resources/assets/gt_shanhai/multiblock/nyarlathoteps_tentacle.mb");

    private ShanhaiInstabilityGeometry geometry() throws Exception {
        return ShanhaiInstabilityGeometry.fromMb(Files.readString(MB));
    }

    @Test
    void samplesRealExposedBlocksAcrossBothTentaclesAndFullDepth() throws Exception {
        var geometry = geometry();
        var nodes = geometry.nodes();
        assertTrue(nodes.size() > 150);
        assertTrue(nodes.size() <= ShanhaiInstabilityGeometry.MAX_NODES);
        var rows = Files.readAllLines(MB).stream().map(s -> s.split(",", -1)).toList();
        var positions = new HashSet<String>();
        for (var n : nodes) {
            int x = n.x() + 82, y = n.y() + 24, z = n.z() + 48;
            assertNotEquals(' ', rows.get(z)[y].charAt(x));
            assertNotEquals('a', rows.get(z)[y].charAt(x));
            assertEquals(rows.get(z)[y].charAt(x), n.symbol());
            assertEquals(1, Math.abs(n.nx()) + Math.abs(n.ny()) + Math.abs(n.nz()));
            int ox = x + n.nx(), oy = y + n.ny(), oz = z + n.nz();
            assertTrue(ox < 0 || ox >= 165 || oy < 0 || oy >= 48 || oz < 0 || oz >= 49
                    || rows.get(oz)[oy].charAt(ox) == ' ');
            assertTrue(positions.add(x + ":" + y + ":" + z));
        }
        assertTrue(nodes.stream().anyMatch(n -> n.x() <= -78));
        assertTrue(nodes.stream().anyMatch(n -> n.x() >= 78));
        assertTrue(nodes.stream().anyMatch(n -> n.y() <= -20));
        assertTrue(nodes.stream().anyMatch(n -> n.y() >= 20));
        assertTrue(nodes.stream().anyMatch(n -> n.z() <= -46));
        assertTrue(nodes.stream().anyMatch(n -> n.z() >= -2));
        for (var link : geometry.links()) {
            var a = nodes.get(link.from());
            var b = nodes.get(link.to());
            double distance = Math.sqrt(Math.pow(a.x()-b.x(), 2) + Math.pow(a.y()-b.y(), 2) + Math.pow(a.z()-b.z(), 2));
            assertTrue(distance >= 3 && distance <= 18, "Keep arcs local to the structure");
        }
    }

    @Test
    void animationIsDeterministicChangesOverTimeAndKeepsHardBudgets() throws Exception {
        var geometry = geometry();
        assertEquals(geometry.frame(1.25, 42L, i -> true), geometry.frame(1.25, 42L, i -> true));
        assertNotEquals(geometry.frame(1.25, 42L, i -> true), geometry.frame(1.75, 42L, i -> true));
        boolean sawArcs = false;
        for (int step = 0; step < 80; step++) {
            var frame = geometry.frame(step * 0.125, 42L, i -> true);
            assertFalse(frame.quads().isEmpty());
            assertTrue(frame.quads().size() <= ShanhaiInstabilityGeometry.MAX_QUADS);
            assertTrue(frame.arcs() <= ShanhaiInstabilityGeometry.MAX_ARCS);
            sawArcs |= frame.arcs() > 0;
            for (var q : frame.quads()) {
                assertTrue(q.alpha() > 0 && q.alpha() <= 0.65f);
                assertTrue(q.red() >= 0 && q.red() <= 1);
                assertTrue(q.green() >= 0 && q.green() <= 1);
                assertTrue(q.blue() >= 0 && q.blue() <= 1);
                for (var p : new ShanhaiInstabilityGeometry.Point[]{q.a(), q.b(), q.c(), q.d()}) {
                    assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()));
                    assertTrue(p.x() > -86 && p.x() < 86 && p.y() > -28 && p.y() < 28 && p.z() > -52 && p.z() < 4);
                }
            }
        }
        assertTrue(sawArcs);
    }

    @Test
    void absentStructureProducesNoFloatingEffectsAndSingleNodeCannotEmitArcs() throws Exception {
        var geometry = geometry();
        assertTrue(geometry.frame(2, 0, i -> false).quads().isEmpty());
        var one = geometry.frame(2, 0, i -> i == 0);
        assertFalse(one.quads().isEmpty());
        assertEquals(0, one.arcs());
    }

    @Test
    void malformedMbIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShanhaiInstabilityGeometry.fromMb("bad"));
    }
}
