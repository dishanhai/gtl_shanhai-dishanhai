package com.dishanhai.gt_shanhai.api.whitehole;

/** Exterior geometry. Lengths in a geometry sample are in instantaneous GM/c². */
@FunctionalInterface
public interface SpacetimeModel {
    double G = 6.67430e-11;
    double C = 299_792_458.0;
    double C_SQUARED = C * C;
    double VACUUM_PERMITTIVITY = 8.8541878188e-12;

    Geometry geometry(double dimensionlessSpin, double dimensionlessCharge);

    static double gravitationalRadiusMetres(double massKg) {
        checkMass(massKg);
        return massKg * (G / C_SQUARED);
    }

    static double gravitationalTimeSeconds(double massKg) {
        return gravitationalRadiusMetres(massKg) / C;
    }

    static double angularMomentumSi(double massKg, double dimensionlessSpin) {
        checkUnitInterval(dimensionlessSpin, "spin");
        return dimensionlessSpin * massKg * (C * gravitationalRadiusMetres(massKg));
    }

    static double dimensionlessSpin(double massKg, double angularMomentumSi) {
        double spin = angularMomentumSi / angularMomentumSi(massKg, 1);
        checkUnitInterval(spin, "spin");
        return spin;
    }

    static double chargeCoulombs(double massKg, double dimensionlessCharge) {
        checkMass(massKg);
        checkUnitInterval(dimensionlessCharge, "charge");
        return dimensionlessCharge * massKg * Math.sqrt(4 * Math.PI * VACUUM_PERMITTIVITY * G);
    }

    static double kerrOuterHorizon(double spin) {
        checkUnitInterval(spin, "spin");
        double magnitude = Math.abs(spin);
        return 1 + Math.sqrt((1 - magnitude) * (1 + magnitude));
    }

    static double kerrEquatorialPhotonOrbit(double spin, int direction) {
        checkUnitInterval(spin, "spin");
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("direction must be ±1");
        if (spin == 0) return 3;
        return 2 * (1 + Math.cos(2 * Math.acos(-spin * direction) / 3));
    }

    static void checkMass(double value) {
        // Leave enough exponent margin for SI conversions including J ∝ M².
        if (!(value >= 1e-100 && value <= 1e100)) {
            throw new IllegalArgumentException("mass outside the supported SI range [1e-100, 1e100] kg");
        }
    }

    private static void checkUnitInterval(double value, String name) {
        if (!(Math.abs(value) <= 1)) throw new IllegalArgumentException(name + " outside [-1, 1]");
    }

    /**
     * Positive and negative refer to the sign of azimuthal angular motion.
     * A spinning exterior has a photon region, with these two equatorial limits.
     */
    record Geometry(HorizonModel horizon, double positivePhotonOrbitRg, double negativePhotonOrbitRg) {
        public Geometry {
            if (horizon == null || !validOrbit(positivePhotonOrbitRg, horizon)
                    || !validOrbit(negativePhotonOrbitRg, horizon)) {
                throw new IllegalArgumentException("invalid exterior characteristic radii");
            }
        }

        private static boolean validOrbit(double radius, HorizonModel horizon) {
            return Double.isFinite(radius) && radius >= horizon.radiusRg();
        }
    }
}
