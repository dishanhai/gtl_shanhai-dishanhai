package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/** Kerr quantities in units of GM/c² and GM/c³, unless the method explicitly returns SI units. */
public record KerrGeometry(double spin) {
    public static final double G = 6.67430e-11;
    public static final double C = 299_792_458;

    public KerrGeometry {
        if (!Double.isFinite(spin) || Math.abs(spin) > 1) {
            throw new IllegalArgumentException("a* outside [-1, 1]");
        }
    }

    public static KerrGeometry fromAngularMomentum(double massKg, double angularMomentum) {
        return new KerrGeometry(angularMomentum / (massKg * (gravitationalRadiusMetres(massKg) * C)));
    }

    public static double gravitationalRadiusMetres(double massKg) {
        if (!(massKg > 0) || !Double.isFinite(massKg)) {
            throw new IllegalArgumentException("mass must be finite and positive");
        }
        return massKg * (G / (C * C));
    }

    public double outerHorizon() {
        return 1 + horizonGap();
    }

    public double innerHorizon() {
        return spin * spin / outerHorizon();
    }

    private double horizonGap() {
        double absoluteSpin = Math.abs(spin);
        return Math.sqrt((1 - absoluteSpin) * (1 + absoluteSpin));
    }

    public double horizonArea() {
        return 8 * Math.PI * outerHorizon();
    }

    public double horizonAngularVelocity() {
        return spin / (2 * outerHorizon());
    }

    public double horizonAngularVelocityPerSecond(double massKg) {
        return horizonAngularVelocity() * C / gravitationalRadiusMetres(massKg);
    }

    public double ergosphere(double cosTheta) {
        double axialSpin = spin * cosTheta;
        return 1 + Math.sqrt((1 - axialSpin) * (1 + axialSpin));
    }

    public double sigma(double r, double cosTheta) {
        return r * r + spin * spin * cosTheta * cosTheta;
    }

    public double delta(double r) {
        return (r - outerHorizon()) * (r - innerHorizon());
    }

    public double azimuthFactor(double r, double cosTheta) {
        double radialFactor = r * r + spin * spin;
        return radialFactor * radialFactor - spin * spin * delta(r) * (1 - cosTheta * cosTheta);
    }

    public double dragging(double r, double cosTheta) {
        return 2 * spin * r / azimuthFactor(r, cosTheta);
    }

    /** Proper time per unit coordinate time for a ZAMO outside the horizon. */
    public double zamoLapse(double r, double cosTheta) {
        return Math.sqrt(sigma(r, cosTheta) * delta(r) / azimuthFactor(r, cosTheta));
    }

    /** The sign of the circular orbit's angular velocity is independent of the hole spin. */
    public double isco(int direction) {
        double relativeSpin = relativeSpin(direction);
        double z1 = 1 + Math.cbrt((1 - spin) * (1 + spin)) * (Math.cbrt(1 + spin) + Math.cbrt(1 - spin));
        double z2 = Math.sqrt(3 * spin * spin + z1 * z1);
        return 3 + z2 - Math.copySign(Math.sqrt(Math.max(0, (3 - z1) * (3 + z1 + 2 * z2))), relativeSpin);
    }

    public double equatorialPhotonOrbit(int direction) {
        return 2 * (1 + Math.cos(2 * Math.acos(-relativeSpin(direction)) / 3));
    }

    public double circularAngularVelocity(double r, int direction) {
        return direction / (r * Math.sqrt(r) + relativeSpin(direction));
    }

    /** Proper time per unit coordinate time on a circular equatorial timelike orbit. */
    public double circularLapse(double r, int direction) {
        double relativeSpin = relativeSpin(direction);
        double root = Math.sqrt(r);
        double r32 = r * root;
        double offset = root - 1;
        return Math.sqrt(r32 * (offset * offset * (root + 2) + 2 * (relativeSpin - 1))) / (r32 + relativeSpin);
    }

    private double relativeSpin(int direction) {
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("orbit direction must be +1 or -1");
        return spin * direction;
    }

    /** Constants Lz/E and Q/E² obtained from the radial double-root conditions. */
    public PhotonOrbit sphericalPhotonOrbit(double r) {
        if (spin == 0 || r <= outerHorizon()) {
            throw new IllegalArgumentException("spherical Kerr photon orbit requires nonzero spin and r > r+");
        }
        double r2 = r * r;
        double a2 = spin * spin;
        double distance = r - 1;
        double l = (r2 * (r - 3) + a2 * (r + 1)) / (-spin * distance);
        double q = r2 * r * (4 * a2 - r * (r - 3) * (r - 3)) / (a2 * distance * distance);
        return new PhotonOrbit(l, q);
    }

    /** A point lies in the exterior photon region if polar motion is allowed at the radial double root. */
    public boolean inPhotonRegion(double r, double cosTheta) {
        if (spin == 0) return r == 3;
        if (r <= outerHorizon()) return false;
        var orbit = sphericalPhotonOrbit(r);
        double c2 = cosTheta * cosTheta;
        double polar = orbit.carterConstant + spin * spin * c2;
        if (c2 == 1) return Math.abs(orbit.axialMomentum) < 1e-10 && polar >= 0;
        return (1 - c2) * polar - orbit.axialMomentum * orbit.axialMomentum * c2 >= -1e-10;
    }

    public record PhotonOrbit(double axialMomentum, double carterConstant) {}

    /** Outgoing Cartesian Kerr–Schild radial coordinate. The positive branch is exterior to the ring. */
    public double radialCoordinate(double x, double y, double z) {
        double a2 = spin * spin;
        double b = x * x + y * y + z * z - a2;
        double discriminant = Math.hypot(b, 2 * spin * z);
        double r2 = b >= 0 ? (b + discriminant) / 2 : 2 * a2 * z * z / (discriminant - b);
        return Math.sqrt(r2);
    }
}
