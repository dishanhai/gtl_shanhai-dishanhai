// Author: dishanhai
package com.dishanhai.gt_shanhai.client.renderer.hostfx;

/** 类星体的实时光学近似，长度以非旋转黑洞的表观阴影半径为单位。 */
final class GuixuQuasarOptics {
    private static final double SCHWARZSCHILD_RADIUS = 2 / (3 * Math.sqrt(3));
    static final double INNER_EDGE = 3 * SCHWARZSCHILD_RADIUS;
    private static final double PEAK_FLUX = rawFlux(36.0 / 49);
    private static final double JET_SPEED = .84;

    private GuixuQuasarOptics() {}

    static double diskFlux(double radius) {
        return radius <= INNER_EDGE ? 0 : rawFlux(INNER_EDGE / radius) / PEAK_FLUX;
    }

    private static double rawFlux(double inverseRadius) {
        // 零力矩薄盘的径向通量，内缘为零，远离内缘后按 r^-3 衰减。
        return inverseRadius * inverseRadius * inverseRadius * (1 - Math.sqrt(inverseRadius));
    }

    static double angularSpeed(double radius) {
        double relativeRadius = radius / INNER_EDGE;
        return 2.35 / (relativeRadius * Math.sqrt(relativeRadius));
    }

    static double diskShift(double radius, double alignment) {
        double beta = Math.sqrt(SCHWARZSCHILD_RADIUS / (2 * (radius - SCHWARZSCHILD_RADIUS)));
        return Math.sqrt(1 - SCHWARZSCHILD_RADIUS / radius) * doppler(beta, alignment);
    }

    static double jetShift(double alignment) {
        return doppler(JET_SPEED, alignment);
    }

    private static double doppler(double beta, double alignment) {
        return Math.sqrt(1 - beta * beta) / (1 - beta * Math.clamp(alignment, -1, 1));
    }

    static float beamedLight(double emission, double frequencyShift) {
        // 由 Iν/ν³ 不变量估算增亮，再压缩至游戏的亮度范围。
        return (float) (1 - Math.exp(-emission * frequencyShift * frequencyShift * frequencyShift));
    }

    static double arcWidth(double inclinationCosine, double skyHeight) {
        // 将远侧盘面在阴影上、下的重像近似为两条弧，正视盘面时合入细光子环。
        return (1 - inclinationCosine * inclinationCosine) * skyHeight * skyHeight
                * (1.40 + .82 * inclinationCosine * skyHeight);
    }
}
