package com.dishanhai.gt_shanhai.config;

/** Client-side controls for the Guixu Kerr renderer. Distance is measured in GM/c². */
public final class KerrRenderConfig {
    public enum Parameter {
        MASS(0.1, 10, 1),
        SPIN(-1, 1, 0.65),
        TILT(0, 180, 32),
        AZIMUTH(-180, 180, 20),
        DISTANCE(0, 500, 0),
        INCLINATION(-1, 180, -1),
        ACCRETION(0, 8, 1),
        THICKNESS(0.004, 0.24, 0.055),
        TEMPERATURE(1000, 100000, 9400),
        EMISSIVITY(0, 5, 0.38),
        TIME_SCALE(0, 300, 16),
        EXPOSURE(0.04, 12, 1.15),
        BLOOM(0, 2, 0.18),
        ACCURACY(0.1, 10, 1),
        STEPS(0, 768, 0);

        public final double min, max, initial;

        Parameter(double min, double max, double initial) {
            this.min = min;
            this.max = max;
            this.initial = initial;
        }
    }

    public enum Background { WORLD, STARS, DARK }

    public enum Quality {
        ULTRA(1.0, 0.000018, 640),
        HIGH(0.72, 0.000060, 480),
        MEDIUM(0.52, 0.00016, 384),
        LOW(0.35, 0.00040, 320),
        ADAPTIVE(0.44, 0.00022, 384);

        public final double scale, tolerance;
        public final int steps;

        Quality(double scale, double tolerance, int steps) {
            this.scale = scale;
            this.tolerance = tolerance;
            this.steps = steps;
        }
    }

    private KerrRenderConfig() {}
}
