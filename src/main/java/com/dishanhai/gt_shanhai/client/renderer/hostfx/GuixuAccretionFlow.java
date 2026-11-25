package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
/** 山海：外緣吸積流的純運算模型；CPU 回落與離線驗證共用，GLSL 使用相同公式。 */
public final class GuixuAccretionFlow {
    private GuixuAccretionFlow() {}
    public static final float INNER_RADIUS = 51F;
    public static final float OUTER_RADIUS = 57F;
    public static final float HALF_HEIGHT = .45F;
    private static final double WARP_PERIOD = 2 * Math.PI;

    public static float height(float s, float angle, float seconds) {
        return heightAtPhase(s, angle, warpPhase(seconds));
    }

    static float warpPhase(double seconds) {
        return (float) ((seconds * .03) % WARP_PERIOD);
    }

    static float flowClock(double seconds) {
        return (float) (seconds - Math.floor(seconds / 24) * 24);
    }

    static float heightAtPhase(float s, float angle, float phase) {
        return (float) (HALF_HEIGHT * Math.sin(Math.PI * s)
                * (.7 * Math.sin(2 * angle - 4 * phase) + .3 * Math.sin(5 * angle + 3 * phase)));
    }

    /** 開普勒差速旋轉；單位 rad/s，與盤面切線 (-sin(a), 0, cos(a)) 同向。 */
    public static double angularSpeed(double s) {
        double radius = INNER_RADIUS + (OUTER_RADIUS - INNER_RADIUS) * s;
        return angularSpeedForInverseRadius(INNER_RADIUS / radius);
    }

    private static double angularSpeedForInverseRadius(double inverseRadius) {
        return .72 * inverseRadius * Math.sqrt(inverseRadius);
    }

    /** 氣體以公轉為主，隨流座標中的正時間項只造成緩慢內漂；角頻率均為整數。 */
    public static double phase(double s, double angle, double seconds) {
        double orbit = angle - angularSpeed(s) * seconds;
        return phase(s, orbit, seconds, warp(s, orbit, seconds));
    }

    private static double phase(double s, double orbit, double seconds, double warp) {
        return 22 * s + 3 * orbit + .16 * seconds + .65 * Math.sin(warp);
    }

    private static double warp(double s, double orbit, double seconds) {
        return 4 * orbit + 5 * s + .11 * seconds;
    }

    /** footprint 為每像素的歸一化徑向寬度；呼叫方重用陣列，逐頂點不配置物件。 */
    public static void shade(float radius, float angle, float seconds, float footprint,
                             float camX, float camY, float camZ, float[] rgba) {
        float s = (radius - INNER_RADIUS) / (OUTER_RADIUS - INNER_RADIUS);
        shadeAt(s, angle, flowClock(seconds), footprint,
                camX - radius * (float) Math.cos(angle), camY - height(s, angle, seconds),
                camZ - radius * (float) Math.sin(angle), rgba);
    }

    static void shadeAt(float s, float angle, float clock, float footprint, float vx, float vy, float vz, float[] rgba) {
        double radius = INNER_RADIUS + (OUTER_RADIUS - INNER_RADIUS) * s;
        // 兩組 24 秒壽命的湍流錯開半週期淡入淡出，避免多年世界的剪切把盤面濾成靜止光環。
        // 基準角速度 π/4 在 24 秒內恰好三圈，因此整段取模也不會在週期邊界跳動。
        double age = (clock + 12) % 24;
        double weight = .5 - .5 * Math.cos(clock * Math.PI / 12);
        double inverseRadius = INNER_RADIUS / radius;
        double omega = angularSpeedForInverseRadius(inverseRadius);
        double density = weight * density(s, angle, clock, clock, footprint, omega, radius)
                + (1 - weight) * density(s, angle, clock, age, footprint, omega, radius);
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double approach = length > 1e-5 ? (-Math.sin(angle) * vx + Math.cos(angle) * vz) / length : 0;
        // 視覺近似：D^3 多普勒增亮與徑向熱輻射衰減；不宣稱為 GRMHD／黑體光譜解算。
        double beta = .32 * Math.sqrt(inverseRadius);
        double doppler = Math.sqrt(1 - beta * beta) / (1 - beta * approach);
        double emissivity = inverseRadius * inverseRadius * inverseRadius * (1 - .55 * s);
        double heat = Math.max(0, Math.min(1, 1 - smooth(.08, .92, s) + .16 * (doppler - 1)));
        double edge = smooth(0, .15, s) * (1 - smooth(.73, 1, s));
        double core = 1 - smooth(.045, .27, Math.abs(s - .34));
        // 保留方位上的低频亮节，细流纹在远处被滤掉时仍能看到公转。
        double cycle = clock * Math.PI / 12;
        double wave = .5 + .5 * Math.cos(5 * angle - 13 * cycle + .32 * Math.sin(2 * angle + cycle));
        double pulse = wave * wave;
        double skirt = 1 - core;
        rgba[0] = (float) ((.52 + .02 * pulse) * skirt + core);
        rgba[1] = (float) ((.42 + .10 * pulse) * skirt + (.68 + .29 * heat) * core);
        rgba[2] = (float) ((.78 + .10 * pulse) * skirt + (.57 + .38 * heat) * core);
        rgba[3] = (float) Math.min(.70, 1.40 * edge * (.50 + .50 * core) * (.58 + .42 * pulse)
                * emissivity * density * doppler * doppler * doppler);
    }

    private static double density(double s, double angle, double clock, double seconds, float footprint,
                                  double omega, double radius) {
        double orbit = angle - Math.PI / 4 * (clock - seconds) - omega * seconds;
        // 差速剪切會隨時間拉細流紋，回落路徑同樣在低於像素寬度時濾掉細節。
        double shear = 1.5 * omega * seconds * (OUTER_RADIUS - INNER_RADIUS) / radius;
        double warp = warp(s, orbit, seconds);
        double radialPhase = 22 + 3 * shear + .65 * Math.cos(warp) * (4 * shear + 5);
        double width = Math.max(0, footprint) * Math.max(Math.abs(radialPhase), Math.abs(37 + 5 * shear));
        double filter = Math.exp(-.5 * width * width);
        double ridge = .5 + .5 * Math.sin(phase(s, orbit, seconds, warp));
        double ridgeSquared = ridge * ridge;
        ridge = .2734375 + (ridgeSquared * ridgeSquared - .2734375) * filter;
        double secondary = .5 + .5 * Math.sin(37 * s + 5 * orbit + .23 * seconds);
        secondary = .5 + (secondary - .5) * filter;
        double clumpPhase = 7 * orbit + 9 * s + .18 * seconds + .8 * Math.sin(3 * orbit + 4 * s + .07 * seconds);
        double clumpWidth = Math.max(0, footprint) * (9 + 7 * shear + .8 * (4 + 3 * shear));
        double clumpFilter = Math.exp(-.5 * clumpWidth * clumpWidth);
        double clumpWave = .5 + .5 * Math.sin(clumpPhase);
        double clumps = .32 + .68 * (.375 + (clumpWave * clumpWave - .375) * clumpFilter);
        return (.10 + .72 * ridge + .18 * secondary) * clumps;
    }

    private static double smooth(double lo, double hi, double x) {
        double t = Math.max(0, Math.min(1, (x - lo) / (hi - lo)));
        return t * t * (3 - 2 * t);
    }
}
