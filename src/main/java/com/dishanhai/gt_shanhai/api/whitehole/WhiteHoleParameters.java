package com.dishanhai.gt_shanhai.api.whitehole;

import java.util.Objects;

/** Inputs in SI except spin, normalized charge, and outflow speed as a fraction of c. */
public record WhiteHoleParameters(
        SpacetimeModel spacetime,
        RadiationModel radiation,
        double referenceMassKg,
        double minimumMassFraction,
        double dimensionlessSpin,
        double dimensionlessCharge,
        double luminosityWatts,
        double restMassFlowKgPerSecond,
        double outflowBeta,
        double plasmaTemperatureKelvin,
        long seed) {

    public static final double NOMINAL_LUMINOSITY_WATTS = 2e36;

    public WhiteHoleParameters {
        Objects.requireNonNull(spacetime, "spacetime");
        Objects.requireNonNull(radiation, "radiation");
        SpacetimeModel.checkMass(referenceMassKg);
        requireRange(minimumMassFraction, .000001, 1, "minimum mass fraction");
        requireRange(dimensionlessSpin, -1, 1, "spin");
        requireRange(dimensionlessCharge, -1, 1, "charge");
        requireRange(luminosityWatts, 0, 1e115, "luminosity");
        requireRange(restMassFlowKgPerSecond, 0, 1e100, "rest mass flow");
        requireRange(outflowBeta, 0, .999999, "outflow speed");
        requireRange(plasmaTemperatureKelvin, 1, 1e15, "temperature");
        Objects.requireNonNull(spacetime.geometry(dimensionlessSpin, dimensionlessCharge), "geometry");
    }

    public static WhiteHoleParameters defaults(long seed) {
        return new WhiteHoleParameters(StandardSpacetime.KERR, RadiationModel.Profile.CONSTANT,
                8e30, .001, .65, 0, NOMINAL_LUMINOSITY_WATTS, 4e17, .42, 30_000, seed);
    }

    public WhiteHoleParameters withModel(SpacetimeModel model, double spin, double charge) {
        return new WhiteHoleParameters(model, radiation, referenceMassKg, minimumMassFraction, spin, charge,
                luminosityWatts, restMassFlowKgPerSecond, outflowBeta, plasmaTemperatureKelvin, seed);
    }

    public WhiteHoleParameters withDimensionlessSpin(double spin) {
        return withModel(spacetime, spin, dimensionlessCharge);
    }

    public WhiteHoleParameters withEmission(RadiationModel model, double luminosity, double restMassFlow,
                                            double beta, double temperature) {
        return new WhiteHoleParameters(spacetime, model, referenceMassKg, minimumMassFraction, dimensionlessSpin,
                dimensionlessCharge, luminosity, restMassFlow, beta, temperature, seed);
    }

    public WhiteHoleParameters withMass(double massKg, double remnantFraction) {
        return new WhiteHoleParameters(spacetime, radiation, massKg, remnantFraction, dimensionlessSpin,
                dimensionlessCharge, luminosityWatts, restMassFlowKgPerSecond, outflowBeta, plasmaTemperatureKelvin, seed);
    }

    public WhiteHoleParameters withSeed(long newSeed) {
        return new WhiteHoleParameters(spacetime, radiation, referenceMassKg, minimumMassFraction, dimensionlessSpin,
                dimensionlessCharge, luminosityWatts, restMassFlowKgPerSecond, outflowBeta, plasmaTemperatureKelvin, newSeed);
    }

    static void requireRange(double value, double min, double max, String name) {
        if (!(value >= min && value <= max)) throw new IllegalArgumentException(name + " outside [" + min + ", " + max + "]");
    }
}
