package com.dishanhai.gt_shanhai.api.whitehole;

/** Deterministic time curve applied to configured radiation and matter rates. */
@FunctionalInterface
public interface RadiationModel {
    double multiplier(double seconds);

    enum Profile implements RadiationModel {
        CONSTANT,
        EXPONENTIAL,
        PERIODIC,
        TRANSIENT,
        MULTISTAGE,
        QUANTUM_TRANSITION;

        @Override
        public double multiplier(double seconds) {
            return switch (this) {
                case CONSTANT -> 1;
                case EXPONENTIAL -> Math.exp(-seconds / 24);
                case PERIODIC -> .68 + .32 * Math.cos((2 * Math.PI / 5) * seconds);
                case TRANSIENT -> .18 + 2.5 * pulse(seconds, .6, 1.8);
                case MULTISTAGE -> (.35 + .65 * smoothstep(0, 3, seconds))
                        * (.7 * Math.exp(-seconds / 16) + .3 * Math.exp(-seconds / 70));
                case QUANTUM_TRANSITION -> .10 + 4 * pulse(seconds - 2, .65, 2.5);
            };
        }
    }

    static double pulse(double age, double riseSeconds, double decaySeconds) {
        return smoothstep(0, riseSeconds, age) * Math.exp(-Math.max(0, age - riseSeconds) / decaySeconds);
    }

    private static double smoothstep(double lo, double hi, double value) {
        double t = Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
        return t * t * (3 - 2 * t);
    }
}
