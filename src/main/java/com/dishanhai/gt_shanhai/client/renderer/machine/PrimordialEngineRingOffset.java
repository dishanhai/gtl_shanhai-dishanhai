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

    /**
     * 实心环核的表面。环结构里铺满整圈的只有中间几层，表面大约在 3 格。
     * 再往外只剩绕光束（网格角 180°）的一撮支架，不是整圈厚盘。
     */
    private static final float[] CORE = {3.0f, 3.0f, 3.0f};

    /** 支架尖端的表面。过了这里，这一侧再也没有方块。 */
    private static final float[] TIP = {10.0f, 12.0f, 14.0f};

    /** 支架半张角，按离环核表面的距离取样。距离越远，支架越窄。 */
    private static final float[][] WEDGE_AT = {
            {4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f, 10.0f},
            {4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f, 10.0f, 11.0f, 12.0f},
            {4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f, 10.0f, 11.0f, 12.0f, 13.0f, 14.0f}
    };

    private static final float[][] WEDGE_HALF = {
            {18.85f, 14.15f, 10.3f, 7.4f, 5.55f, 3.7f, 1.85f},
            {38.65f, 30.15f, 23.0f, 17.25f, 12.85f, 9.55f, 7.45f, 5.35f, 3.2f},
            {68.85f, 56.95f, 46.8f, 35.65f, 26.55f, 20.4f, 14.95f, 11.3f, 8.85f, 6.35f, 3.8f}
    };

    /** 环盘半厚度，已含方块自身的一格。法线兜底仍用支架尖端外的老余量。 */
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

    /**
     * 环带里大部分角度是空的。只有环核（约 ±3 格）和光束一侧的支架算挡住。
     * 支架随离核的距离变窄，空区可以直接穿，不必绕到支架尖端外面。
     */
    static boolean clearsRing(float beamX, float beamY, float beamZ, int ringIndex, float angleDegrees,
                              float planetRadius) {
        float radial = ringRadial(beamX, beamY, beamZ, angleDegrees);
        if (radial < RADIUS_MIN[ringIndex] - planetRadius || radial > RADIUS_MAX[ringIndex] + planetRadius) {
            return true;
        }
        float opened = Math.abs(planeDistance(beamX, beamY, angleDegrees)) - planetRadius;
        if (opened >= TIP[ringIndex] - 0.05f) {
            return true;
        }
        if (opened <= CORE[ringIndex]) {
            return false;
        }
        double radians = Math.toRadians(angleDegrees);
        float inPlane = (float) (beamY * Math.sin(radians) + beamX * Math.cos(radians));
        double mesh = Math.toDegrees(Math.atan2(-inPlane, beamZ));
        double deviation = Math.abs(Math.abs(mesh) - 180.0);
        double margin = Math.toDegrees(Math.atan((planetRadius + 0.5) / Math.max(radial, 1.0f)));
        return deviation > wedgeHalfDegrees(ringIndex, opened) + margin;
    }

    /** 这个离核距离上，支架还占多大的半角。 */
    private static float wedgeHalfDegrees(int ringIndex, float opened) {
        float[] at = WEDGE_AT[ringIndex];
        float[] half = WEDGE_HALF[ringIndex];
        if (opened <= at[0]) {
            return half[0];
        }
        int last = at.length - 1;
        if (opened >= at[last]) {
            return half[last];
        }
        for (int i = 1; i < at.length; i++) {
            if (opened <= at[i]) {
                float t = (opened - at[i - 1]) / (at[i] - at[i - 1]);
                return half[i - 1] + (half[i] - half[i - 1]) * t;
            }
        }
        return half[last];
    }
}
