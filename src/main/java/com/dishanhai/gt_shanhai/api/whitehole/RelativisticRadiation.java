package com.dishanhai.gt_shanhai.api.whitehole;

public final class RelativisticRadiation {
    private RelativisticRadiation() {}

    public static double lorentzFactor(double beta) {
        if (!(Math.abs(beta) < 1)) throw new IllegalArgumentException("|v/c| must be less than 1");
        double magnitude = Math.abs(beta);
        return 1 / Math.sqrt((1 - magnitude) * (1 + magnitude));
    }

    /** Doppler factor for the photon direction in the local static frame. */
    public static double dopplerFactor(double beta, double directionCosine) {
        if (!(Math.abs(directionCosine) <= 1)) throw new IllegalArgumentException("direction cosine outside [-1, 1]");
        return 1 / (lorentzFactor(beta) * (1 - beta * directionCosine));
    }

    /** Static Schwarzschild emitter and observer, both outside r = 2 GM/c². */
    public static double schwarzschildFrequencyRatio(double emitterRadiusRg, double observerRadiusRg) {
        if (!(emitterRadiusRg > 2 && observerRadiusRg > 2)) {
            throw new IllegalArgumentException("static measurement requires radii outside the horizon");
        }
        return Math.sqrt(schwarzschildLapseSquared(emitterRadiusRg) / schwarzschildLapseSquared(observerRadiusRg));
    }

    private static double schwarzschildLapseSquared(double radiusRg) {
        return Double.isInfinite(radiusRg) ? 1 : (radiusRg - 2) / radiusRg;
    }

    /** Monochromatic vacuum transfer using the invariant Iν/ν³. */
    public static double observedSpecificIntensity(double emitted, double frequencyRatio) {
        if (!(emitted >= 0 && frequencyRatio > 0) || !Double.isFinite(emitted) || !Double.isFinite(frequencyRatio)) {
            throw new IllegalArgumentException("invalid intensity or frequency ratio");
        }
        return emitted * frequencyRatio * frequencyRatio * frequencyRatio;
    }
}
