package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import com.dishanhai.gt_shanhai.config.GuixuClientConfig;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Background;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Parameter;
import com.dishanhai.gt_shanhai.config.KerrRenderConfig.Quality;

import java.util.EnumMap;
import java.util.Locale;

/** Public client API. Overrides last until reset or client restart. */
public final class KerrControls {
    static final Optics KERR = new Optics(false);
    static final Optics WHITE = new Optics(true);

    private KerrControls() {}

    public static void set(Parameter parameter, double value) { KERR.set(parameter, value); }
    public static void follow(Parameter parameter) { KERR.follow(parameter); }
    public static void setQuality(Quality quality) { KERR.setQuality(quality); }
    public static void setBackground(Background background) { KERR.setBackground(background); }
    public static void setReverseDisk(boolean reverse) { KERR.setReverseDisk(reverse); }
    public static void reset() { KERR.reset(); }
    public static double requested(Parameter parameter) { return KERR.requested(parameter); }

    public static double horizonRadiusBound(double worldDistance, double referenceRs) {
        return KERR.horizonRadiusBound(worldDistance, referenceRs);
    }

    static Frame sample(long nanos, double worldSeconds, double worldDistance, double referenceRs) {
        return KERR.sample(nanos, worldSeconds, worldDistance, referenceRs);
    }

    static String describe() { return KERR.describe(); }

    /** Optical controls and smoothing state owned by a single render mode. */
    static final class Optics {
        private final boolean white;
        private final EnumMap<Parameter, Double> overrides = new EnumMap<>(Parameter.class);
        private final double[] current = new double[Parameter.values().length];
        private Quality qualityOverride;
        private Background backgroundOverride;
        private Boolean reverseOverride;
        private long previousTime;
        private double simulationTime;
        private double distanceRatio = 1;
        private double inclinationWeight;
        private final double[] skyWeights = {1, 0, 0};
        private double diskFade = 1;
        private boolean reverseDisk;
        private double renderScale, tolerance, stepBudget;

        private Optics(boolean white) { this.white = white; }

        synchronized void set(Parameter parameter, double value) {
            if (!Double.isFinite(value) || value < parameter.min || value > parameter.max) {
                throw new IllegalArgumentException(parameter + " outside configured range");
            }
            overrides.put(parameter, value);
        }

        synchronized void follow(Parameter parameter) { overrides.remove(parameter); }
        synchronized void setQuality(Quality quality) { qualityOverride = quality; }
        synchronized void setBackground(Background background) { backgroundOverride = background; }
        synchronized void setReverseDisk(boolean reverse) { reverseOverride = reverse; }

        synchronized void reset() {
            overrides.clear();
            qualityOverride = null;
            backgroundOverride = null;
            reverseOverride = null;
        }

        synchronized void suspend() { lastWorldSeconds = Double.NaN; }

        private double target(Parameter parameter) {
            Double override = overrides.get(parameter);
            if (override != null) return override;
            if (parameter == Parameter.SPIN && BlackHoleTuning.hasHostCenter()) {
                return BlackHoleTuning.kerrParameters().spin();
            }
            if (!white) return GuixuClientConfig.kerr(parameter);
            return parameter == Parameter.BLOOM ? .09 : parameter.initial;
        }

        synchronized double requested(Parameter parameter) { return target(parameter); }

        /** World-space radius guaranteed to enclose the black-hole horizon during control changes. */
        synchronized double horizonRadiusBound(double worldDistance, double referenceRs) {
            // The equatorial Kerr horizon radius is at most one Schwarzschild radius.
            // Bound the smoothed mass and distance ratio before the lens samples this frame.
            double mass = Math.max(target(Parameter.MASS), current[Parameter.MASS.ordinal()]);
            double smoothedBound = referenceRs * mass / Math.min(1, distanceRatio);
            double distance = target(Parameter.DISTANCE);
            return distance == 0 ? smoothedBound : Math.max(smoothedBound, 2 * worldDistance / distance);
        }

        synchronized Frame sample(long nanos, double worldSeconds, double worldDistance, double referenceRs) {
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

        private double smooth(double from, double to, double blend) {
            return Math.abs(from - to) < 1e-7 || blend == 1 ? to : from + (to - from) * blend;
        }

        private double lastWorldSeconds = Double.NaN;

        synchronized String describe() {
            var geometry = new KerrGeometry(previousTime == 0 ? target(Parameter.SPIN) : current[Parameter.SPIN.ordinal()]);
            return String.format(Locale.ROOT, "a*=%.5f  r+=%.5f M  r-=%.5f M  A=%.5f M²  ΩH=%.5f/M  ISCO(+/-)=%.4f/%.4f M",
                    geometry.spin(), geometry.outerHorizon(), geometry.innerHorizon(), geometry.horizonArea(),
                    geometry.horizonAngularVelocity(), geometry.isco(1), geometry.isco(-1));
        }

        synchronized Quality requestedQuality() {
            return qualityOverride != null ? qualityOverride : white ? Quality.ADAPTIVE : GuixuClientConfig.kerrQuality();
        }

        synchronized Background requestedBackground() {
            return backgroundOverride != null ? backgroundOverride : white ? Background.WORLD : GuixuClientConfig.kerrBackground();
        }

        synchronized boolean requestedReverse() {
            return reverseOverride != null ? reverseOverride : !white && GuixuClientConfig.kerrReverseDisk();
        }
    }

    record Frame(double[] values, double clock, Quality quality, boolean reverse, double inclinationWeight,
                 double renderScale, double tolerance, int steps, float diskFade,
                 float worldSky, float starsSky, float darkSky) {
        double value(Parameter parameter) { return values[parameter.ordinal()]; }
        float f(Parameter parameter) { return (float) value(parameter); }
        KerrGeometry geometry() { return new KerrGeometry(value(Parameter.SPIN)); }
    }
}
