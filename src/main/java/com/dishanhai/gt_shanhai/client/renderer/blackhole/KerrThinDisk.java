package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/** Page–Thorne zero-torque flux in units of 3 Ṁ/(8πM²), with G=c=1. */
final class KerrThinDisk {
    // Sample sqrt(flux) on a grid quadratic in distance from ISCO. This resolves
    // the quadratic onset of flux without doing quadrature in every ray segment.
    private final float[] profile = new float[128];
    private double lastSpin = Double.NaN, lastOuter;

    float[] profile(KerrGeometry geometry, int direction, double outer) {
        double spin = direction * geometry.spin();
        if (spin == lastSpin && outer == lastOuter) return profile;
        double inner = geometry.isco(direction);
        double previous = Math.sqrt(inner);
        double integral = 0;
        // At exactly extremal prograde spin the one-sided flux limit is 2/9.
        profile[0] = spin == 1 ? (float) Math.sqrt(2.0 / 9) : 0f;
        for (int i = 1; i < profile.length; i++) {
            double u = (double) i / (profile.length - 1);
            double r = inner + (outer - inner) * u * u;
            double x = Math.sqrt(r);
            double half = (x - previous) * .5;
            double mid = previous + half;
            double offset = half / Math.sqrt(3);
            integral += half * (integrand(mid - offset, spin) + integrand(mid + offset, spin));
            profile[i] = (float) Math.sqrt(integral / (r * r * orbitDenominator(x, spin)));
            previous = x;
        }
        lastSpin = spin;
        lastOuter = outer;
        return profile;
    }

    private static double orbitDenominator(double x, double spin) {
        double gap = x - 1;
        return gap * gap * (x + 2) + 2 * (spin - 1);
    }

    // (E - ΩL) dL/dx, x = sqrt(r/M). Factor around the repeated root at a=1.
    private static double integrand(double x, double spin) {
        double gap = x - 1;
        double numerator = gap * gap * gap * (x + 3) + (spin - 1) * (8 * x - 3 * (spin + 1));
        return numerator / (x * orbitDenominator(x, spin));
    }
}
