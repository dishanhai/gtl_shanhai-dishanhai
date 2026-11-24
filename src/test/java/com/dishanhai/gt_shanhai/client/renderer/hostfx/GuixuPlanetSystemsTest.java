package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStellarEnd.BodyKind.*;
import static org.junit.jupiter.api.Assertions.*;

class GuixuPlanetSystemsTest {
    @Test
    void moonsOrbitAllPlanetKindsAndInfallBeforeTheirParents() {
        var point = new Vector3d();
        var fallingCenter = new Vector3d();
        var planetKinds = EnumSet.noneOf(GuixuStellarEnd.BodyKind.class);
        for (int i = 0; i < GuixuStellarEnd.STAR_COUNT; i += 4) {
            var planet = GuixuStellarEnd.star(i, 3, 45);
            if (planet.kind() != ROCKY && !planet.kind().hasPlanetaryRings()) continue;
            planetKinds.add(planet.kind());
            var moons = GuixuPlanetSystems.moons(planet.kind());
            assertEquals(planet.kind() == ROCKY ? 1 : 3, moons.size());
            var center = new Vector3d();
            GuixuPlanetSystems.planetCenter(planet, 15, center);
            for (var moon : moons) {
                float radius = moon.sample(planet, 3, 15, center, point);
                assertTrue(radius > .15F);
                assertTrue(point.distance(center) > planet.solidRadius() + radius);
                if (planet.kind().hasPlanetaryRings()) {
                    assertTrue(point.distance(center) - radius >
                            planet.solidRadius() * GuixuPlanetSystems.rings(planet.kind()).getLast().radius());
                }
                var initial = new Vector3d(point);
                moon.sample(planet, 4, 15, center, point);
                assertTrue(initial.distance(point) > .5);
                assertEquals(initial.distance(center), point.distance(center), 1e-5);
                double impact = 3 - planet.captureAge();
                var during = GuixuStellarEnd.star(i, impact - 9, 45);
                GuixuPlanetSystems.planetCenter(during, 15, fallingCenter);
                radius = moon.sample(during, impact - 9, 15, fallingCenter, point);
                assertTrue(radius > 0);
                assertTrue(point.length() >= 15 + radius - 1e-5);
                var late = GuixuStellarEnd.star(i, impact - 1, 45);
                assertTrue(late.solidRadius() > 0);
                assertEquals(0, moon.sample(late, impact - 1, 15, fallingCenter, point));
            }
        }
        assertEquals(EnumSet.of(ROCKY, GAS_GIANT, RINGED_GIANT), planetKinds);
        assertTrue(GuixuPlanetSystems.moons(STAR).isEmpty());
    }

    @Test
    void beltStaggersTheInfallAndClearsBeforeQuasar() {
        var rocks = GuixuPlanetSystems.asteroids();
        var point = new Vector3d();
        assertTrue(rocks.size() >= 32);
        assertTrue(GuixuPlanetSystems.orbitSpeed(1.3) > GuixuPlanetSystems.orbitSpeed(2.3));
        int fallen = 0;
        for (var rock : rocks) {
            float radius = rock.sample(3, 15, point);
            assertTrue(radius > .1F);
            assertTrue(point.length() > 42);
            var initial = new Vector3d(point);
            rock.sample(6, 15, point);
            assertTrue(point.distance(initial) > .5);
            for (int time = 10; time < 44; time++) {
                radius = rock.sample(time, 15, point);
                if (radius > 0) assertTrue(point.length() >= 15 + radius - 1e-5);
            }
            if (rock.sample(34, 15, point) == 0) fallen++;
            assertEquals(0, rock.sample(GuixuStellarEnd.QUASAR_ENTRY_SECONDS, 15, point));
        }
        assertTrue(fallen > 0 && fallen < rocks.size());
    }

    @Test
    void giantRingsHaveDistinctProfilesAndDarkGaps() {
        var gas = GuixuPlanetSystems.rings(GAS_GIANT);
        var ringed = GuixuPlanetSystems.rings(RINGED_GIANT);
        assertTrue(gas.getLast().radius() < ringed.getLast().radius());
        assertTrue(gas.stream().mapToDouble(GuixuPlanetSystems.RingBand::opacity).max().orElseThrow()
                < ringed.stream().mapToDouble(GuixuPlanetSystems.RingBand::opacity).max().orElseThrow());
        assertTrue(gas.subList(1, gas.size() - 1).stream().anyMatch(band -> band.opacity() == 0));
        assertTrue(ringed.subList(1, ringed.size() - 1).stream().anyMatch(band -> band.opacity() == 0));
        assertFalse(ROCKY.hasPlanetaryRings());
    }
}
