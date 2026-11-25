package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.GuixuClientConfig;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Background;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;

import java.util.EnumMap;
import java.util.Locale;

/** Public client API. Overrides last until reset or client restart. */
public final class KerrControls {
    private static final EnumMap<Parameter, Double> overrides = new EnumMap<>(Parameter.class);
    private static final double[] current = new double[Parameter.values().length];
    private static Quality qualityOverride;
    private static Background backgroundOverride;
    private static Boolean reverseOverride;
    private static long previousTime;
    private static double simulationTime;
    private static double distanceRatio = 1;
    private static double inclinationWeight;
    private static final double[] skyWeights = {1, 0, 0};
    private static double diskFade = 1;
    private static boolean reverseDisk;
    private static double renderScale, tolerance, stepBudget;

    private KerrControls() {}

    public static synchronized void set(Parameter parameter, double value) {
        if (!Double.isFinite(value) || value < parameter.min || value > parameter.max) {
            throw new IllegalArgumentException(parameter + " outside configured range");
        }
        overrides.put(parameter, value);
    }

    public static synchronized void follow(Parameter parameter) { overrides.remove(parameter); }
    public static synchronized void setQuality(Quality quality) { qualityOverride = quality; }
    public static synchronized void setBackground(Background background) { backgroundOverride = background; }
    public static synchronized void setReverseDisk(boolean reverse) { reverseOverride = reverse; }

    public static synchronized void reset() {
        overrides.clear();
        qualityOverride = null;
        backgroundOverride = null;
        reverseOverride = null;
    }

    private static double target(Parameter parameter) {
        Double override = overrides.get(parameter);
        if (override != null) return override;
        if (parameter == Parameter.SPIN && BlackHoleTuning.hasHostCenter()) {
            return BlackHoleTuning.kerrParameters().spin();
        }
        return GuixuClientConfig.kerr(parameter);
    }

    public static synchronized double requested(Parameter parameter) { return target(parameter); }

    static synchronized Frame sample(long nanos, double worldSeconds, double worldDistance, double referenceRs) {
        double delta = (nanos - previousTime) * 1e-9;
        boolean fresh = previousTime == 0 || !(delta > 0 && delta < 1);
        double blend = fresh ? 1 : -Math.expm1(-delta / 0.15);
        for (Parameter parameter : Parameter.values()) {
            if (parameter == Parameter.DISTANCE || parameter == Parameter.INCLINATION) continue;
            double value = target(parameter);
            int index = parameter.ordinal();
            double difference = value - current[index];
            if (parameter == Parameter.AZIMUTH) difference = Math.IEEEremainder(difference, 360);
            if (fresh || Math.abs(difference) < 1e-6) {
                current[index] = value;
            } else {
                current[index] += difference * blend;
            }
        }
        double naturalDistance = Math.max(1e-8, 2 * worldDistance / (referenceRs * current[Parameter.MASS.ordinal()]));
        double distance = target(Parameter.DISTANCE);
        distanceRatio = smooth(distanceRatio, distance == 0 ? 1 : distance / naturalDistance, blend);
        current[Parameter.DISTANCE.ordinal()] = naturalDistance * distanceRatio;
        double inclination = target(Parameter.INCLINATION);
        if (inclination >= 0) {
            current[Parameter.INCLINATION.ordinal()] = smooth(current[Parameter.INCLINATION.ordinal()], inclination,
                    inclinationWeight < .001 ? 1 : blend);
        }
        inclinationWeight = smooth(inclinationWeight, inclination >= 0 ? 1 : 0, blend);

        Quality quality = requestedQuality();
        renderScale = smooth(renderScale, quality.scale, blend);
        tolerance = smooth(tolerance, quality.tolerance, blend);
        stepBudget = smooth(stepBudget, quality.steps, blend);
        int backgroundIndex = requestedBackground().ordinal();
        for (int i = 0; i < skyWeights.length; i++) {
            skyWeights[i] = smooth(skyWeights[i], i == backgroundIndex ? 1 : 0, blend);
        }
        boolean requestedReverse = requestedReverse();
        if (fresh) {
            diskFade = 1;
            reverseDisk = requestedReverse;
        } else if (reverseDisk != requestedReverse) {
            diskFade = Math.max(0, diskFade - delta / .16);
            if (diskFade == 0) reverseDisk = requestedReverse;
        } else {
            diskFade = Math.min(1, diskFade + delta / .16);
        }
        double rate = current[Parameter.TIME_SCALE.ordinal()];
        if (previousTime == 0) simulationTime = 0;
        if (!fresh && !Double.isNaN(lastWorldSeconds)) {
            simulationTime += Math.clamp(worldSeconds - lastWorldSeconds, 0.0, 0.1) * rate;
        }
        lastWorldSeconds = worldSeconds;
        previousTime = nanos;
        return new Frame(current.clone(), simulationTime, quality, reverseDisk, inclinationWeight,
                renderScale, tolerance, (int) stepBudget, (float) diskFade, (float) skyWeights[0],
                (float) skyWeights[1], (float) skyWeights[2]);
    }

    private static double smooth(double from, double to, double blend) {
        return Math.abs(from - to) < 1e-7 || blend == 1 ? to : from + (to - from) * blend;
    }

    private static double lastWorldSeconds = Double.NaN;

    static synchronized String describe() {
        var geometry = new KerrGeometry(previousTime == 0 ? target(Parameter.SPIN) : current[Parameter.SPIN.ordinal()]);
        return String.format(Locale.ROOT, "a*=%.5f  r+=%.5f M  r-=%.5f M  A=%.5f M²  ΩH=%.5f/M  ISCO(+/-)=%.4f/%.4f M",
                geometry.spin(), geometry.outerHorizon(), geometry.innerHorizon(), geometry.horizonArea(),
                geometry.horizonAngularVelocity(), geometry.isco(1), geometry.isco(-1));
    }

    static synchronized Quality requestedQuality() {
        return qualityOverride == null ? GuixuClientConfig.kerrQuality() : qualityOverride;
    }

    static synchronized Background requestedBackground() {
        return backgroundOverride == null ? GuixuClientConfig.kerrBackground() : backgroundOverride;
    }

    static synchronized boolean requestedReverse() {
        return reverseOverride == null ? GuixuClientConfig.kerrReverseDisk() : reverseOverride;
    }

    record Frame(double[] values, double clock, Quality quality, boolean reverse, double inclinationWeight,
                 double renderScale, double tolerance, int steps, float diskFade,
                 float worldSky, float starsSky, float darkSky) {
        double value(Parameter parameter) { return values[parameter.ordinal()]; }
        float f(Parameter parameter) { return (float) value(parameter); }
        KerrGeometry geometry() { return new KerrGeometry(value(Parameter.SPIN)); }
    }
}
