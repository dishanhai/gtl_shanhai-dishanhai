package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KerrGeometryTest {
    @Test
    void schwarzschildLimit() {
        var metric = new KerrGeometry(0);
        assertEquals(2, metric.outerHorizon());
        assertEquals(0, metric.innerHorizon());
        assertEquals(16 * Math.PI, metric.horizonArea());
        assertEquals(2, metric.ergosphere(.7));
        assertEquals(0, metric.horizonAngularVelocity());
        assertEquals(3, metric.equatorialPhotonOrbit(1), 1e-14);
        assertEquals(6, metric.isco(-1), 1e-14);
        assertEquals(Math.sqrt(1 - 2.0 / 9), metric.zamoLapse(9, .7), 1e-14);
        assertEquals(Math.sqrt(1 - 3.0 / 9), metric.circularLapse(9, 1), 1e-14);
        assertEquals(1.0 / 27, metric.circularAngularVelocity(9, 1), 1e-14);
    }

    @Test
    void extremeLimitAndDraggingAtTheHorizon() {
        for (double spin : new double[]{-1, -.99999999, -.7, 0, .3, .99999999, 1}) {
            var metric = new KerrGeometry(spin);
            assertEquals(spin * spin, metric.outerHorizon() * metric.innerHorizon(), 1e-14);
            assertEquals(metric.outerHorizon(), metric.ergosphere(1), 1e-14);
            assertEquals(2, metric.ergosphere(0), 1e-14);
            assertEquals(metric.horizonAngularVelocity(), metric.dragging(metric.outerHorizon(), .56), 1e-14);
            assertEquals(0, metric.zamoLapse(metric.outerHorizon(), .56));
            assertEquals(2 * spin, 1e12 * metric.dragging(1e4, .56), 2e-7);
        }
        assertEquals(1, new KerrGeometry(1).isco(1), 1e-14);
        assertEquals(9, new KerrGeometry(1).isco(-1), 1e-14);
        assertEquals(1, new KerrGeometry(-1).outerHorizon());
        assertEquals(1, new KerrGeometry(-1).innerHorizon());
    }

    @Test
    void spinReflectionAndPhysicalUnits() {
        double mass = 8e30;
        double rg = KerrGeometry.gravitationalRadiusMetres(mass);
        var forward = KerrGeometry.fromAngularMomentum(mass, .85 * mass * rg * KerrGeometry.C);
        var reverse = new KerrGeometry(-forward.spin());
        assertEquals(.85, forward.spin(), 1e-14);
        assertEquals(forward.outerHorizon(), reverse.outerHorizon());
        assertEquals(forward.isco(1), reverse.isco(-1));
        assertEquals(-forward.horizonAngularVelocityPerSecond(mass), reverse.horizonAngularVelocityPerSecond(mass));
        assertEquals(-forward.dragging(6, .2), reverse.dragging(6, .2));
        assertThrows(IllegalArgumentException.class, () -> new KerrGeometry(1.001));
    }

    @Test
    void sphericalPhotonOrbitsHaveDoubleRadialRoots() {
        var metric = new KerrGeometry(.91);
        for (double r : new double[]{2.1, 2.43, 2.7, 3.05, 3.55}) {
            var orbit = metric.sphericalPhotonOrbit(r);
            double p = r * r + metric.spin() * (metric.spin() - orbit.axialMomentum());
            double k = orbit.carterConstant() + Math.pow(orbit.axialMomentum() - metric.spin(), 2);
            assertTrue(orbit.carterConstant() > 0);
            assertTrue(metric.inPhotonRegion(r, 0));
            assertEquals(0, p * p - metric.delta(r) * k, 1e-11);
            assertEquals(0, 4 * r * p - (2 * r - 2) * k, 1e-11);
        }
        assertFalse(metric.inPhotonRegion(5, 0));
        assertFalse(metric.inPhotonRegion(metric.outerHorizon(), 0));
    }

    @Test
    void outgoingKerrSchildRadiusInvertsAnOblateSpheroid() {
        for (double spin : new double[]{-1, -.5, 0, .5, 1}) {
            var metric = new KerrGeometry(spin);
            for (double r : new double[]{.01, .5, 1, 5, 500}) {
                for (double polar : new double[]{0, 0.4, Math.PI / 2, 2.6}) {
                    double cyl = Math.hypot(r, spin) * Math.sin(polar);
                    assertEquals(r, metric.radialCoordinate(cyl * .6, cyl * .8, r * Math.cos(polar)), 1e-12);
                }
            }
        }
    }
}
