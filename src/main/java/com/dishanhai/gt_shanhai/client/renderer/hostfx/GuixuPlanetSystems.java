package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStellarEnd.BodyKind;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStellarEnd.Star;
import static com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStellarEnd.ease;

/** 山海：行星卫星、星环和独立小行星带的演出轨道。 */
final class GuixuPlanetSystems {
    // 图集最后一行是风化层、表面冰壳和碳质碎石。
    static final int ROCK_TILE = 16;
    static final int ICE_TILE = 17;
    static final int ASTEROID_TILE = 18;
    private static final List<Moon> ROCKY_MOONS = List.of(new Moon(2.55, .23F, .24, 1.1, -5, ROCK_TILE));
    private static final List<Moon> GAS_MOONS = List.of(
            new Moon(2.48, .16F, .07, 0, -8, ROCK_TILE),
            new Moon(3.14, .20F, .11, 2.1, -6.5, ICE_TILE),
            new Moon(3.84, .13F, .23, 4.35, -5, ROCK_TILE));
    private static final List<Moon> RINGED_MOONS = List.of(
            new Moon(2.77, .11F, .04, 3.9, -8, ICE_TILE),
            new Moon(3.39, .19F, .06, 1.2, -6.5, ROCK_TILE),
            new Moon(4.04, .15F, .18, 5.4, -5, ICE_TILE));
    private static final List<RingBand> DUST_RINGS = List.of(
            new RingBand(1.33F, 0), new RingBand(1.42F, .09F), new RingBand(1.56F, .26F),
            new RingBand(1.67F, 0), new RingBand(1.73F, 0), new RingBand(1.79F, .15F),
            new RingBand(1.93F, .08F), new RingBand(2.12F, 0));
    private static final List<RingBand> ICE_RINGS = List.of(
            new RingBand(1.28F, 0), new RingBand(1.37F, .27F), new RingBand(1.48F, .41F),
            new RingBand(1.58F, .72F), new RingBand(1.69F, .43F), new RingBand(1.77F, .76F),
            new RingBand(1.82F, 0), new RingBand(1.92F, 0), new RingBand(1.97F, .48F),
            new RingBand(2.08F, .63F), new RingBand(2.2F, .32F), new RingBand(2.25F, 0),
            new RingBand(2.29F, 0), new RingBand(2.32F, .30F), new RingBand(2.37F, .14F),
            new RingBand(2.47F, 0));
    private static final List<Asteroid> ASTEROIDS = createAsteroids();

    private GuixuPlanetSystems() {}

    static List<Moon> moons(BodyKind kind) {
        return switch (kind) {
            case ROCKY -> ROCKY_MOONS;
            case GAS_GIANT -> GAS_MOONS;
            case RINGED_GIANT -> RINGED_MOONS;
            default -> List.of();
        };
    }

    static List<RingBand> rings(BodyKind kind) {
        return switch (kind) {
            case GAS_GIANT -> DUST_RINGS;
            case RINGED_GIANT -> ICE_RINGS;
            default -> List.of();
        };
    }

    static List<Asteroid> asteroids() {
        return ASTEROIDS;
    }

    private static List<Asteroid> createAsteroids() {
        var random = new SplittableRandom(0x47584955);
        var belt = new ArrayList<Asteroid>(44);
        for (int i = 0; i < 44; i++) {
            double orbit = random.nextDouble(56, 75);
            double phase = Math.TAU * (i + random.nextDouble()) / 44;
            double eccentricity = random.nextDouble(.025, .15);
            double inclination = random.nextDouble(.11, .36);
            float radius = (float) random.nextDouble(.22, i % 4 == 0 ? 1.08 : .58);
            double sinkBeforeQuasar = (74 - orbit) * .62 + random.nextDouble(2.2, 8);
            belt.add(new Asteroid(orbit, eccentricity, inclination, phase, radius, sinkBeforeQuasar));
        }
        return List.copyOf(belt);
    }

    static double orbitSpeed(double orbit) {
        return 3.9 / (orbit * Math.sqrt(orbit));
    }

    static float planetTilt(Star star) {
        return .47F + (float) Math.cos(star.phase() * 2) * .31F;
    }

    static void planetCenter(Star planet, float horizon, Vector3d result) {
        var position = GuixuStellarEnd.bodyPosition(planet, horizon);
        result.set(position.x(), position.y(), position.z());
    }

    private static void fall(Vector3d position, double progress, float horizon, float radius) {
        double distance = position.length();
        double contact = horizon + radius;
        double orbit = contact + Math.max(0, distance - contact) * (1 - progress);
        position.rotateY(-progress * (1.2 + 2.4 * progress)).mul(orbit / distance);
    }

    record Moon(double orbit, float size, double inclination, double phase, double sink, int tile) {
        double angle(double seconds, double parentPhase) {
            return seconds * orbitSpeed(orbit) + phase + parentPhase;
        }

        float sample(Star planet, double seconds, float horizon, Vector3d center, Vector3d result) {
            if (planet.solidRadius() <= 0 || planet.captureAge() >= sink) return 0;
            // 从完整的母星半径推算卫星轨道。母星缩小后，卫星仍按自己的轨道被拉走。
            double parentRadius = planet.solidRadius() / (1 - planet.body());
            double angle = angle(seconds, planet.phase());
            result.set(Math.cos(angle), 0, Math.sin(angle)).rotateX(inclination)
                    .rotateZ(planetTilt(planet)).rotateY(planet.phase())
                    .mul(orbit * parentRadius).add(center);
            double progress = ease(sink - 10, sink, planet.captureAge());
            float radius = (float) (size * parentRadius * (1 - ease(.73, 1, progress)));
            fall(result, progress, horizon, radius);
            return radius;
        }
    }

    record Asteroid(double orbit, double eccentricity, double inclination, double phase,
                    float size, double sinkBeforeQuasar) {
        float sample(double seconds, float horizon, Vector3d result) {
            double capture = GuixuStellarEnd.QUASAR_ENTRY_SECONDS - sinkBeforeQuasar;
            if (seconds <= 0 || seconds >= capture) return 0;
            double angle = seconds * orbitSpeed(orbit / 10) + phase;
            double distance = orbit * (1 - eccentricity * eccentricity) / (1 + eccentricity * Math.cos(angle - phase));
            result.set(Math.cos(angle) * distance, 0, Math.sin(angle) * distance).rotateZ(inclination);
            double progress = ease(capture - 19, capture, seconds);
            float radius = (float) (size * ease(0, 2.5, seconds) * (1 - ease(.81, 1, progress)));
            fall(result, progress, horizon, radius);
            return radius;
        }
    }

    record RingBand(float radius, float opacity) {}
}
