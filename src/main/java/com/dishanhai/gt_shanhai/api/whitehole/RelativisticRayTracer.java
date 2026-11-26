package com.dishanhai.gt_shanhai.api.whitehole;

/** Double-precision reference integrators for spherically symmetric exterior rays. */
public final class RelativisticRayTracer {
    private RelativisticRayTracer() {}

    public static double closestApproachRg(double impactRg) {
        return 1 / turningInverseRadius(impactRg);
    }

    /**
     * Integrates dφ/du = [b⁻² − u² + 2u³]⁻¹ᐟ², with u = GM/(rc²).
     * Substituting u = u_turn(1 − s²) makes the square-root turning point regular.
     */
    public static double schwarzschildDeflectionRadians(double impactRg, int segments) {
        if (segments < 2 || segments > 1_048_576 || (segments & 1) != 0) {
            throw new IllegalArgumentException("quadrature needs an even number of segments in [2, 1048576]");
        }
        double turningPoint = turningInverseRadius(impactRg);
        double sum = integrand(turningPoint, 0) + integrand(turningPoint, 1);
        for (int i = 1; i < segments; i++) {
            sum += ((i & 1) == 0 ? 2 : 4) * integrand(turningPoint, (double) i / segments);
        }
        return 2 * sum / (3 * segments);
    }

    private static double turningInverseRadius(double impactRg) {
        if (!Double.isFinite(impactRg) || impactRg <= StandardSpacetime.SCHWARZSCHILD_CRITICAL_IMPACT_RG) {
            throw new IllegalArgumentException("scattering requires a finite impact parameter above the critical value");
        }
        double target = 1 / impactRg;
        double lo = target;
        double hi = Math.min(1.0 / 3, target * Math.sqrt(3));
        for (int i = 0; i < 64; i++) {
            double midpoint = (lo + hi) * .5;
            if (midpoint == lo || midpoint == hi) break;
            if (midpoint * Math.sqrt(1 - 2 * midpoint) < target) lo = midpoint;
            else hi = midpoint;
        }
        return lo;
    }

    private static double integrand(double turningPoint, double s) {
        double x = 1 - s * s;
        double delta = 2 * turningPoint * (1 + x + x * x);
        double flat = Math.sqrt(1 + x);
        double curved = Math.sqrt(1 + x - delta);
        // Evaluate the difference from the flat-space integrand without subtracting close values.
        return 2 * delta / (flat * curved * (flat + curved));
    }

    /**
     * Advances an equatorial null ray in the outgoing Vaidya chart (u, r, φ). Coordinates and
     * their derivatives use fixed reference GM/c² and GM/c³ units. The caller controls the
     * affine step budget and may verify accuracy with {@link #vaidyaNullHamiltonian}.
     */
    public static VaidyaRay advanceOutgoingVaidya(VaidyaRay initial, VaidyaMass mass,
                                                  double affineInterval, int steps) {
        if (!(affineInterval > 0) || !Double.isFinite(affineInterval) || steps < 1 || steps > 1_048_576) {
            throw new IllegalArgumentException("invalid Vaidya integration interval or step count");
        }
        VaidyaRay ray = initial;
        double h = affineInterval / steps;
        for (int i = 0; i < steps; i++) {
            VaidyaDerivative a = flow(ray, mass);
            VaidyaDerivative b = flow(move(ray, a, h * .5), mass);
            VaidyaDerivative c = flow(move(ray, b, h * .5), mass);
            VaidyaDerivative d = flow(move(ray, c, h), mass);
            ray = move(ray, new VaidyaDerivative(
                    rk4Average(a.u, b.u, c.u, d.u), rk4Average(a.r, b.r, c.r, d.r),
                    rk4Average(a.phi, b.phi, c.phi, d.phi), rk4Average(a.pu, b.pu, c.pu, d.pu),
                    rk4Average(a.pr, b.pr, c.pr, d.pr)), h);
        }
        return ray;
    }

    public static double vaidyaNullHamiltonian(VaidyaRay ray, VaidyaMass mass) {
        double f = -StandardSpacetime.outgoingVaidyaGuu(ray.radiusRg, mass.sample(ray.retardedTime).massFraction);
        double angular = ray.angularMomentum / ray.radiusRg;
        return -ray.timeMomentum * ray.radialMomentum
                + .5 * (f * ray.radialMomentum * ray.radialMomentum + angular * angular);
    }

    private static VaidyaDerivative flow(VaidyaRay ray, VaidyaMass mass) {
        VaidyaMass.Sample sample = mass.sample(ray.retardedTime);
        double inverseRadius = 1 / ray.radiusRg;
        double massOverRadius = sample.massFraction * inverseRadius;
        double f = 1 - 2 * massOverRadius;
        double angularVelocity = ray.angularMomentum * inverseRadius * inverseRadius;
        double radialSquared = ray.radialMomentum * ray.radialMomentum;
        // Hamiltonian H = -p_u p_r + [f p_r² + p_φ²/r²]/2. There is no division by f.
        return new VaidyaDerivative(-ray.radialMomentum, -ray.timeMomentum + f * ray.radialMomentum,
                angularVelocity, sample.derivative * inverseRadius * radialSquared,
                ray.angularMomentum * angularVelocity * inverseRadius - massOverRadius * inverseRadius * radialSquared);
    }

    private static VaidyaRay move(VaidyaRay ray, VaidyaDerivative derivative, double step) {
        return new VaidyaRay(ray.retardedTime + step * derivative.u, ray.radiusRg + step * derivative.r,
                ray.azimuth + step * derivative.phi, ray.timeMomentum + step * derivative.pu,
                ray.radialMomentum + step * derivative.pr, ray.angularMomentum);
    }

    private static double rk4Average(double a, double b, double c, double d) {
        return (a + 2 * b + 2 * c + d) / 6;
    }

    public record VaidyaRay(double retardedTime, double radiusRg, double azimuth, double timeMomentum,
                            double radialMomentum, double angularMomentum) {
        public VaidyaRay {
            if (!(radiusRg >= 1e-12) || !Double.isFinite(radiusRg) || !Double.isFinite(retardedTime)
                    || !Double.isFinite(azimuth) || !Double.isFinite(timeMomentum)
                    || !Double.isFinite(radialMomentum) || !Double.isFinite(angularMomentum)) {
                throw new IllegalArgumentException("non-finite ray state or radius below integration range");
            }
        }
    }

    @FunctionalInterface
    public interface VaidyaMass {
        Sample sample(double retardedTime);

        /** M(u)/M_ref and its derivative with respect to normalized retarded time. */
        record Sample(double massFraction, double derivative) {
            public Sample {
                if (!(massFraction > 0 && massFraction <= 1 && derivative <= 0) || !Double.isFinite(derivative)) {
                    throw new IllegalArgumentException("outgoing Vaidya requires positive, nonincreasing Bondi mass");
                }
            }
        }
    }

    private record VaidyaDerivative(double u, double r, double phi, double pu, double pr) {}
}
