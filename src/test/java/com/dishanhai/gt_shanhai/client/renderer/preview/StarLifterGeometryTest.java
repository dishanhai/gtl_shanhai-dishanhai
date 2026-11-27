package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

final class StarLifterGeometryTest {
    @Test
    void thickRingsKeepIndependentRadialClearanceAllTheWayAround() {
        assertEquals(4, StarLifterGeometry.RINGS.size());
        double previous = StarLifterGeometry.STAR_RADIUS;
        for (int i = 0; i < StarLifterGeometry.RINGS.size(); i++) {
            var ring = StarLifterGeometry.RINGS.get(i);
            assertTrue(ring.width() >= 0.10 && ring.height() >= 0.09);
            assertTrue(ring.innerLimit() - previous > 0.01);
            var surfaces = EnumSet.noneOf(StarLifterGeometry.Surface.class);
            StarLifterGeometry.ring(i, (surface, a, b, c, d) -> {
                surfaces.add(surface);
                for (var vertex : new StarLifterGeometry.Point[]{a, b, c, d}) {
                    double radius = Math.hypot(vertex.x(), vertex.z());
                    assertTrue(radius >= ring.innerLimit() && radius <= ring.outerLimit());
                    assertTrue(Math.abs(vertex.y()) <= ring.height() * 0.583);
                }
                assertTrue(triangleSquaredArea(a, b, c) > 0);
            });
            assertTrue(surfaces.containsAll(EnumSet.of(StarLifterGeometry.Surface.BEAM,
                    StarLifterGeometry.Surface.ARMOR, StarLifterGeometry.Surface.CERAMIC,
                    StarLifterGeometry.Surface.SOCKET, StarLifterGeometry.Surface.CONDUIT)));
            assertTrue(surfaces.contains(i == 0 ? StarLifterGeometry.Surface.EXTRACTOR : StarLifterGeometry.Surface.MAGNET));
            previous = Math.hypot(ring.outerLimit(), ring.height() * 0.583);
        }
    }

    @Test
    void innerAxesTurnIndependentlyWhileOuterRimTurnsClockwise() {
        double time = 91_000_017.125;
        for (var ring : StarLifterGeometry.RINGS.subList(0, StarLifterGeometry.OUTER_RING)) {
            assertEquals(ring.spatialDegrees(time), ring.spatialDegrees(time + Math.abs(ring.spatialPeriod())), 0.0001);
            assertEquals(180.0 / ring.spatialPeriod(),
                    Math.IEEEremainder(ring.spatialDegrees(time + 0.5) - ring.spatialDegrees(time), 360.0), 0.0001);
            assertEquals(0.0f, ring.rimDegrees(time));
        }
        var outer = StarLifterGeometry.RINGS.get(StarLifterGeometry.OUTER_RING);
        assertEquals(0.0f, outer.spatialDegrees(time));
        assertTrue(outer.rimDegrees(1) < outer.rimDegrees(0));
        double wrapTick = Math.abs(outer.rimPeriod());
        assertEquals(360.0 / outer.rimPeriod(),
                Math.IEEEremainder(outer.rimDegrees(wrapTick) - outer.rimDegrees(wrapTick - 1),
                        360.0), 0.0001);
    }

    @Test
    void outerStaysInXZAndInnerRingNormalsTurnAroundHorizontalAxes() {
        for (int i = 0; i < StarLifterGeometry.RINGS.size(); i++) {
            var ring = StarLifterGeometry.RINGS.get(i);
            var start = ring.orientation(0).transform(new Vector3f(0, 1, 0));
            var later = ring.orientation(300).transform(new Vector3f(0, 1, 0));
            if (i < StarLifterGeometry.OUTER_RING) {
                assertEquals(0.0, start.y, 1e-6);
                double azimuth = Math.toRadians(ring.axisAzimuth());
                var axis = new Vector3f((float) Math.cos(azimuth), 0.0f, (float) Math.sin(azimuth));
                assertEquals(start.dot(axis), later.dot(axis), 1e-6);
                assertTrue(Math.abs(later.y) > 0.25f);
            } else {
                var up = new Vector3f(0, 1, 0);
                assertEquals(0.0, up.distance(start), 1e-6);
                assertEquals(0.0, up.distance(later), 1e-6);
            }
        }
        var outer = StarLifterGeometry.RINGS.get(StarLifterGeometry.OUTER_RING);
        var rightEdge = outer.orientation(300).transform(new Vector3f(1, 0, 0));
        assertEquals(0.0, rightEdge.y, 1e-6);
        assertTrue(rightEdge.z > 0.0f, "The right edge moves down clockwise from +Y");
    }

    @Test
    void starQuadsAreNonDegenerateAndFaceOutward() {
        StarLifterGeometry.star((surface, a, b, c, d) -> {
            assertEquals(StarLifterGeometry.Surface.STELLAR, surface);
            for (var p : new StarLifterGeometry.Point[]{a, b, c, d}) {
                assertEquals(StarLifterGeometry.STAR_RADIUS, Math.sqrt(p.x()*p.x() + p.y()*p.y() + p.z()*p.z()), 1e-10);
            }
            var n = normal(a, b, c);
            assertTrue(n[0]*a.x() + n[1]*a.y() + n[2]*a.z() > 0.0);
        });
    }

    @Test
    void everyPlasmaStreamTargetsAnInwardExtractorFace() {
        int[] hits = new int[StarLifterGeometry.COLLECTOR_COUNT];
        double radius = StarLifterGeometry.collectorFaceRadius();
        StarLifterGeometry.ring(StarLifterGeometry.COLLECTOR, (surface, a, b, c, d) -> {
            if (surface != StarLifterGeometry.Surface.EXTRACTOR) return;
            for (var point : new StarLifterGeometry.Point[]{a, b, c, d}) {
                if (Math.abs(Math.hypot(point.x(), point.z()) - radius) > 1e-9) return;
            }
            double angle = Math.atan2(a.z() + b.z() + c.z() + d.z(), a.x() + b.x() + c.x() + d.x());
            for (int i = 0; i < hits.length; i++) {
                double delta = Math.IEEEremainder(angle - StarLifterGeometry.collectorFaceAngle(i), 2 * Math.PI);
                if (Math.abs(delta) < 1e-9) hits[i]++;
            }
        });
        for (int hit : hits) assertEquals(1, hit);
    }

    private static double triangleSquaredArea(StarLifterGeometry.Point a, StarLifterGeometry.Point b,
                                               StarLifterGeometry.Point c) {
        double[] normal = normal(a, b, c);
        return normal[0]*normal[0] + normal[1]*normal[1] + normal[2]*normal[2];
    }

    private static double[] normal(StarLifterGeometry.Point a, StarLifterGeometry.Point b,
                                   StarLifterGeometry.Point c) {
        double ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z();
        double vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();
        return new double[]{uy*vz - uz*vy, uz*vx - ux*vz, ux*vy - uy*vx};
    }
}
