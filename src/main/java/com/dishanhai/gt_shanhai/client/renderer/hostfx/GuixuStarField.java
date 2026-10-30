package com.dishanhai.gt_shanhai.client.renderer.hostfx;

/** 山海：以世界時間取樣的星辰吸入軌跡，無粒子實體或跨世界狀態。 */
public final class GuixuStarField {
    public static final int PARTICLE_COUNT = 192;
    public static final int SKY_COUNT = 512;
    public static final int ORBIT_COUNT = 64;
    public static final int METEOR_COUNT = 8;
    private static final double TAU = Math.PI * 2.0;
    // 山海：只快取不變的種子，不保留動畫、世界或主機狀態。
    private static final double[][] SEEDS = buildSeeds();
    private static final Sample INACTIVE = new Sample(0, 0, 0, 0, 0, 0, 0, 0, false);

    private GuixuStarField() {}

    public record Sample(double headX, double headY, double headZ,
                         double tailX, double tailY, double tailZ,
                         float size, float alpha, boolean warm) {}

    public static Sample sample(int index, double seconds, double surfaceRadius) {
        double cycle = seconds / (10.0 + seed(index, 1) * 8.0) + index * 0.6180339887498949;
        return sampleAtPhase(index, cycle - Math.floor(cycle), surfaceRadius);
    }

    public static Sample sampleAtPhase(int index, double phase, double surfaceRadius) {
        double p = Math.max(0.0, Math.min(1.0, phase));
        Point head = position(index, p, surfaceRadius);
        Point tail = position(index, Math.max(0.0, p - 0.004 - p * 0.006), surfaceRadius);
        double fade = Math.min(1.0, p / 0.08) * Math.min(1.0, (1.0 - p) / 0.06);
        double twinkle = 0.84 + 0.16 * Math.sin(p * TAU * 7.0 + seed(index, 2) * TAU);
        return new Sample(head.x, head.y, head.z, tail.x, tail.y, tail.z,
                (float) (0.09 + seed(index, 3) * 0.10), (float) (fade * twinkle), index % 11 == 0);
    }

    /** 山海：外圍星帶與球殼，位置緩慢漂移，亮度各自閃爍。 */
    public static Sample sampleSky(int index, double seconds) {
        double radius = 76.0 + seed(index, 11) * 28.0;
        double vertical = (seed(index, 12) * 2.0 - 1.0) * (index % 3 == 0 ? 1.0 : 0.12);
        double angle = seed(index, 13) * TAU + seconds * 0.0012;
        double radial = radius * Math.sqrt(1.0 - vertical * vertical);
        double planeZ = Math.sin(angle) * radial;
        double height = vertical * radius;
        double tilt = 0.48;
        Point point = new Point(Math.cos(angle) * radial,
                height * Math.cos(tilt) + planeZ * Math.sin(tilt),
                planeZ * Math.cos(tilt) - height * Math.sin(tilt));
        double twinkle = 0.5 + 0.5 * Math.sin(seconds * (0.5 + seed(index, 14)) + seed(index, 15) * TAU);
        float size = (float) (index % 17 == 0 ? 0.30 + seed(index, 16) * 0.18 : 0.12 + seed(index, 16) * 0.12);
        return ambient(point, point, size, (float) (0.42 + twinkle * 0.48), index % 13 == 0);
    }

    /** 外環之外的星塵，沿上下起伏的軌道掠過結構。 */
    public static Sample sampleOrbit(int index, double seconds) {
        double angle = seed(index, 21) * TAU + seconds * (0.025 + seed(index, 22) * 0.02);
        double radius = 70.0 + seed(index, 23) * 12.0;
        Point head = orbitPosition(index, angle, radius);
        Point tail = orbitPosition(index, angle - 0.04, radius);
        return ambient(head, tail, (float) (0.10 + seed(index, 24) * 0.10),
                (float) (0.60 + 0.20 * Math.sin(seconds * 0.8 + index)), index % 9 == 0);
    }

    private static Point orbitPosition(int index, double angle, double radius) {
        return new Point(Math.cos(angle) * radius,
                Math.sin(angle * 2.0 + seed(index, 25) * TAU) * 8.0 + (seed(index, 26) - 0.5) * 10.0,
                Math.sin(angle) * radius);
    }

    /** 每條流星只在週期的前 16% 出現，其餘時間不產生頂點。 */
    public static Sample sampleMeteor(int index, double seconds) {
        double cycle = seconds / (10.0 + seed(index, 31) * 10.0) + seed(index, 32);
        double phase = cycle - Math.floor(cycle);
        return phase >= 0.16 ? INACTIVE : sampleMeteorAtPhase(index, phase / 0.16);
    }

    public static Sample sampleMeteorAtPhase(int index, double phase) {
        double p = Math.max(0.0, Math.min(1.0, phase));
        double radius = 90.0 + seed(index, 33) * 15.0;
        double angle = seed(index, 34) * TAU + p * 0.65;
        double height = (seed(index, 35) - 0.5) * 70.0 - p * 30.0;
        Point head = new Point(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
        Point tail = new Point(Math.cos(angle - 0.11) * radius, height + 4.0, Math.sin(angle - 0.11) * radius);
        float alpha = p <= 0.0 || p >= 1.0 ? 0.0F : (float) Math.pow(Math.sin(Math.PI * p), 2.0);
        return ambient(head, tail, (float) (0.22 + seed(index, 36) * 0.16), alpha, index % 4 == 0);
    }

    public static int skyCount(float density, boolean degraded) {
        if (degraded) return 96;
        return 256 + (int) (Math.max(0.0F, Math.min(1.0F, density)) * (SKY_COUNT - 256));
    }

    private static Sample ambient(Point head, Point tail, float size, float alpha, boolean warm) {
        return new Sample(head.x, head.y, head.z, tail.x, tail.y, tail.z, size, alpha, warm);
    }

    private record Point(double x, double y, double z) {}

    private static Point position(int index, double phase, double surfaceRadius) {
        double remaining = 1.0 - phase;
        double span = 24.0 + seed(index, 4) * 20.0;
        // 次方小於 1：越靠近視界，徑向收束越快；高度同時收斂到吸積盤。
        double radius = Math.max(0.8, surfaceRadius) + 0.06 + span * Math.pow(remaining, 0.6);
        double angle = seed(index, 5) * TAU + Math.pow(phase, 1.4) * (2.0 + seed(index, 6)) * TAU;
        double height = (seed(index, 7) * 2.0 - 1.0) * span * 0.5 * Math.pow(remaining, 1.6);
        return new Point(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    private static double seed(int index, int salt) {
        return index >= 0 && index < SEEDS.length && salt >= 0 && salt < SEEDS[index].length
                ? SEEDS[index][salt] : computeSeed(index, salt);
    }

    private static double[][] buildSeeds() {
        double[][] values = new double[SKY_COUNT][37];
        for (int i = 0; i < values.length; i++) for (int salt = 0; salt < values[i].length; salt++) {
            values[i][salt] = computeSeed(i, salt);
        }
        return values;
    }

    private static double computeSeed(int index, int salt) {
        long value = (index + 1L) * 0x9E3779B97F4A7C15L + salt * 0x632BE59BD9B4E019L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
