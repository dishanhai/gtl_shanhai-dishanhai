package com.dishanhai.gt_shanhai.client.renderer.machine;

/**
 * 原始终焉引擎三环的转角。与 {@link AbstractRingRenderer} 用的是同一个数。
 * <p>
 * 环 0 是大环，450 刻转一圈，最慢。环 2 是小环，150 刻转一圈，最快。
 * 环 1 反向（顺时针与逆时针相反）。停机时渲染器不转，角度按 0 处理；
 * 开机后的角度就是这里的返回值。
 * <p>
 * 环网格在转之前铺在局部 XZ 平面上，厚度沿局部 Y。渲染时先按朝向绕世界 Y 转，
 * 再绕网格 X 转这个角度。光束本地坐标再绕 Y 差 90 度，所以光束点
 * {@code (bx, by, bz)} 到环盘的距离是 {@code by·cos(θ) - bx·sin(θ)}，
 * 环面上的半径是 {@code hypot(bz, by·sin(θ) + bx·cos(θ))}。
 */
final class PrimordialEngineRingOffset {

    static final int COUNT = 3;

    /** 大环，转得最慢。 */
    static final int LARGE = 0;

    /** 大、中、小环的环带半径。取自环结构方块角点，两侧各留 1.5 格。 */
    private static final float[] RADIUS_MIN = {58.4f, 50.4f, 42.0f};
    private static final float[] RADIUS_MAX = {65.6f, 58.0f, 50.1f};

    /** 环盘半厚度，已含方块自身的一格。 */
    private static final float[] HALF_THICKNESS = {11.0f, 13.0f, 15.0f};

    private static final float FEATHER = 8.0f;

    private PrimordialEngineRingOffset() {}

    static float angleDegrees(int ringIndex, float tick) {
        float direction = ringIndex == 1 ? -1.0f : 1.0f;
        float speed = 0.4f + ringIndex * 0.4f;
        return ((tick * speed * 2.0f) * direction + ringIndex * 120.0f) % 360.0f;
    }

    static float halfThickness(int ringIndex) {
        return HALF_THICKNESS[ringIndex];
    }

    static float bandInner(int ringIndex, float planetRadius) {
        return RADIUS_MIN[ringIndex] - planetRadius;
    }

    static float bandOuter(int ringIndex, float planetRadius) {
        return RADIUS_MAX[ringIndex] + planetRadius;
    }

    static float planeDistance(float beamX, float beamY, float angleDegrees) {
        double radians = Math.toRadians(angleDegrees);
        return (float) (beamY * Math.cos(radians) - beamX * Math.sin(radians));
    }

    static float ringRadial(float beamX, float beamY, float beamZ, float angleDegrees) {
        double radians = Math.toRadians(angleDegrees);
        double inPlane = beamY * Math.sin(radians) + beamX * Math.cos(radians);
        return (float) Math.hypot(beamZ, inPlane);
    }

    /** 0 = 离这条环的半径带还远，1 = 已经落在带内。 */
    static float bandProximity(float radial, int ringIndex, float planetRadius) {
        float inner = RADIUS_MIN[ringIndex] - planetRadius;
        float outer = RADIUS_MAX[ringIndex] + planetRadius;
        if (radial >= inner && radial <= outer) return 1.0f;
        if (radial < inner) {
            float t = (radial - (inner - FEATHER)) / FEATHER;
            return t <= 0.0f ? 0.0f : t;
        }
        float t = ((outer + FEATHER) - radial) / FEATHER;
        return t <= 0.0f ? 0.0f : t;
    }

    static boolean clearsRing(float beamX, float beamY, float beamZ, int ringIndex, float angleDegrees,
                              float planetRadius) {
        float radial = ringRadial(beamX, beamY, beamZ, angleDegrees);
        if (radial < RADIUS_MIN[ringIndex] - planetRadius || radial > RADIUS_MAX[ringIndex] + planetRadius) {
            return true;
        }
        return Math.abs(planeDistance(beamX, beamY, angleDegrees)) + 0.05f
                >= HALF_THICKNESS[ringIndex] + planetRadius;
    }
}
