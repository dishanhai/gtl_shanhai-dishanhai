package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

/** Server orbit calculations shared with the black hole renderer. */
public final class GuixuKerrOrbit {
    public static final int INITIAL_SPIN = 20;
    public static final int STEPS = 12;
    public static final int STEP_TICKS = 10;

    public enum Track { INNER, OUTER }
    public enum Product { PREFORM, CORE, LAMINATE, FRACTURED_CORE }

    private GuixuKerrOrbit() {}

    public static int accrete(int spin, Track track) {
        return Math.clamp(spin + (track == Track.INNER ? 12 : -9), 0, 100);
    }

    public static float radius(Track track, int spin) {
        float fraction = Math.clamp(spin, 0, 100) / 100F;
        return track == Track.INNER ? 3.65F - 1.70F * fraction : 3.65F + 1.85F * fraction;
    }

    static boolean canProcess(GuixuContainmentField field, boolean locked) {
        return !locked && field != null && switch (field.status()) {
            case CONTAINED, HYPERSTABLE -> true;
            default -> false;
        };
    }

    record Batch(Track track, int steps, float exposure, float deviation) {
        static Batch start(Track track) {
            return new Batch(track, 0, 0, 0);
        }

        Batch advance(int spin, float stability) {
            return advance(spin, stability, 1);
        }

        Batch forecast(int spin, float stability) {
            return advance(spin, stability, STEPS - steps);
        }

        private Batch advance(int spin, float stability, int count) {
            if (complete()) return this;
            float distance = radius(track, spin);
            float separation = Math.abs(distance - radius(track, 0));
            float stress = (100 - Math.clamp(stability, 0, 100)) / 100;
            float sensitivity = track == Track.INNER ? 3.65F / distance : 0.25F;
            return new Batch(track, steps + count, exposure + count * separation, deviation + count * stress * sensitivity);
        }

        boolean complete() {
            return steps >= STEPS;
        }

        Product product() {
            if (steps == 0 || exposure / steps < 0.85F) return Product.PREFORM;
            if (track == Track.OUTER) return recoveryMargin() > 0 ? Product.LAMINATE : Product.PREFORM;
            return recoveryMargin() > 0 ? Product.CORE : Product.FRACTURED_CORE;
        }

        float recoveryMargin() {
            float tolerance = track == Track.INNER ? .3F : .22F;
            return steps == 0 ? tolerance : tolerance - deviation / steps;
        }
    }
}
