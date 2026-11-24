package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/** 山海：主渲染探針與降級幾何共用的黑色表面白邊波浪；常數對齊 guixu_blackhole.fsh。 */
public final class BlackHoleSurfaceWaves {
    private static final double TAU = Math.PI * 2;
    private static final double RADIAL_FREQUENCY = 39.2699082;
    private static final double RADIAL_SPEED = 2.50;

    private BlackHoleSurfaceWaves() {}

    public static double detail(double pixelWidth) {
        return 1 - smooth(0.014, 0.055, pixelWidth);
    }

    public static double radiusScale(double angle, double time, double detail) {
        return 1 + detail * (0.012 * Math.sin(angle * 3 + time * 0.5)
                + 0.006 * Math.sin(angle * 5 - time * 0.37));
    }

    private static double angularPhase(double angle, double time) {
        return 1.50 * Math.sin(angle * 3 - time * 0.3) + 0.65 * Math.sin(angle * 5 + time * 0.2);
    }

    public static double phase(double radius, double angle, double time) {
        return (radius - 0.62) * RADIAL_FREQUENCY + angularPhase(angle, time) - time * RADIAL_SPEED;
    }

    /** phase = 2pi*n 的解析波峰；取模只替換進出黑色遮罩的波峰索引。 */
    public static double crestRadius(int wave, double angle, double time) {
        double radialPhase = (time * RADIAL_SPEED) % TAU;
        if (radialPhase < 0) radialPhase += TAU;
        return 0.62 + (TAU * wave - angularPhase(angle, time) + radialPhase) / RADIAL_FREQUENCY;
    }

    public static double envelope(double radius) {
        return smooth(0.60, 0.67, radius) * (1 - smooth(0.92, 0.98, radius));
    }

    public static double photonFlow(double angle, double time) {
        double phase = angle - time * TAU / 5;
        return .40 + .94 * smooth(-.55, 1, Math.cos(phase)) + .25 * smooth(.10, 1, Math.cos(2 * phase + 1.4));
    }

    private static double smooth(double low, double high, double value) {
        double t = Math.max(0, Math.min(1, (value - low) / (high - low)));
        return t * t * (3 - 2 * t);
    }
}
