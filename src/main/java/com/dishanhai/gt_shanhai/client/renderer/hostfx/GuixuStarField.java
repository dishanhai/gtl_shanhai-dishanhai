package com.dishanhai.gt_shanhai.client.renderer.hostfx;

/** 山海：以世界時間取樣的星辰吸入軌跡，無粒子實體或跨世界狀態。 */
public final class GuixuStarField {
    public static final int PARTICLE_COUNT = 192;
    private static final double TAU = Math.PI * 2.0;

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
        long value = (index + 1L) * 0x9E3779B97F4A7C15L + salt * 0x632BE59BD9B4E019L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
