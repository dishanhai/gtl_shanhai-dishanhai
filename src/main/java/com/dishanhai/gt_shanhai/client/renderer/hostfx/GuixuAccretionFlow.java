package com.dishanhai.gt_shanhai.client.renderer.hostfx;

// Author: dishanhai
/** 山海：外緣吸積流的純運算模型；CPU 回落與離線驗證共用，GLSL 使用相同公式。 */
public final class GuixuAccretionFlow {
    private GuixuAccretionFlow() {}
    public static final float INNER_RADIUS = 51F;
    public static final float OUTER_RADIUS = 57F;
    public static final float HALF_HEIGHT = .45F;

    public static float height(float s, float angle, float seconds) {
        return (float) (HALF_HEIGHT * Math.sin(Math.PI * s)
                * (.7 * Math.sin(2 * angle - .12 * seconds) + .3 * Math.sin(5 * angle + .09 * seconds)));
    }

    /** 正的時間項讓同一波峰向半徑較小處移動；整數角頻率不產生 atan 接縫。 */
    public static double phase(double s, double angle, double seconds) {
        return 18 * s + 2 * angle + .85 * seconds + .7 * Math.sin(3 * angle - .2 * seconds)
                + 1.3 * Math.sin(4 * angle + 5 * s - .31 * seconds);
    }

    /** footprint 為每像素的歸一化徑向寬度；呼叫方重用陣列，逐頂點不配置物件。 */
    public static void shade(float radius, float angle, float seconds, float footprint,
                             float camX, float camY, float camZ, float[] rgba) {
        double s = (radius - INNER_RADIUS) / (OUTER_RADIUS - INNER_RADIUS);
        double edge = smooth(0, .13, s) * (1 - smooth(.68, 1, s));
        double width = Math.max(0, footprint) * 28, filter = Math.exp(-.5 * width * width);
        double ridge = Math.pow(.5 + .5 * Math.sin(phase(s, angle, seconds)), 4);
        ridge = .2734375 + (ridge - .2734375) * filter;
        double secondary = .5 + .5 * Math.sin(11 * s - 3 * angle + .6 * seconds);
        secondary = .5 + (secondary - .5) * filter;
        double clumpWave = .5 + .5 * Math.sin(5 * angle + 7 * s - .37 * seconds
                + .8 * Math.sin(2 * angle + 4 * s + .23 * seconds));
        double clumps = .18 + .82 * (.375 + (clumpWave * clumpWave - .375) * filter);
        double vx = camX - radius * Math.cos(angle), vy = camY, vz = camZ - radius * Math.sin(angle);
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double approach = length > 1e-5 ? (-Math.sin(angle) * vx + Math.cos(angle) * vz) / length : 0;
        double brightness = .78 + .22 * approach, heat = 1 - smooth(.10, .85, s);
        rgba[0] = 1;
        rgba[1] = (float) (.60 + .35 * heat);
        rgba[2] = (float) (.34 + .54 * heat);
        rgba[3] = (float) Math.min(.62, .95 * edge * (.06 + .78 * ridge + .16 * secondary) * clumps * brightness);
    }

    private static double smooth(double lo, double hi, double x) {
        double t = Math.max(0, Math.min(1, (x - lo) / (hi - lo)));
        return t * t * (3 - 2 * t);
    }
}
