package com.dishanhai.gt_shanhai.client.renderer.hostfx;

/** 山海：小傾角、雙層厚度的動態星雲；固定模板，不持有世界或粒子。 */
public final class GuixuNebulaField {
    public static final int SEGMENTS_PER_ARM = 24;
    public static final int LAYER_COUNT = 2;
    public static final int PATCH_COUNT = 48;
    private static final double TAU = Math.PI * 2;
    private static final Template[] TEMPLATES = buildTemplates();

    private GuixuNebulaField() {}

    private record Template(double t, double angle, int arm, float width, float envelope) {}

    /** width 是半寬；切線與截面在世界座標中一致，轉頭不會翻面。 */
    public record Knot(double x, double y, double z, double tangentX, double tangentY, double tangentZ,
                       double sideX, double sideY, double sideZ,
                       float width, float u, float alpha, float red, float green, float blue) {}

    public static int patchCount(boolean enabled, boolean degraded) {
        return !enabled ? 0 : degraded ? 16 : PATCH_COUNT;
    }

    public static double tiltDegrees(double seconds) {
        return 6 + 2 * Math.sin(seconds * 0.018);
    }

    /** 80 秒無縫週期；傳入 shader 前限制範圍，長時間遊玩仍保留小數精度。 */
    public static float materialPhase(double seconds) {
        double cycle = seconds % 80;
        return (float) ((cycle < 0 ? cycle + 80 : cycle) * TAU / 80);
    }

    /** shader 不可用時的程式 UV 流動；鏡像取樣不暴露原材質左右接縫。 */
    public static float fallbackU(float u, double seconds) {
        double coordinate = (u * 2 - materialPhase(seconds) / Math.PI) * 0.5;
        double fraction = coordinate - Math.floor(coordinate);
        return (float) (1 - Math.abs(fraction * 2 - 1));
    }

    public static Knot sample(int index, double seconds) {
        return sample(index, seconds, 0);
    }

    /** 向內傳播的寬密度峰；20 秒無縫循環，不將整條雲帶重生或瞬移。 */
    public static double infallDensity(double t, double seconds) {
        double phase = TAU * (t * 2 + (seconds % 20) / 10);
        return 0.52 + 0.48 * Math.pow(0.5 + 0.5 * Math.cos(phase), 2);
    }

    /** 山海：外端與原星雲重疊，內端隨玩家設定的視界半徑收窄；仍共用固定節點。 */
    public static Knot sampleInfall(int index, double seconds, double horizon) {
        Template base = TEMPLATES[index];
        double t = base.t;
        double inner = Math.max(0.8, Math.min(12, horizon)) * 1.16;
        double radius = inner + (76 - inner) * t;
        double angle = base.arm * Math.PI + 0.35 + t * 5.8 + seconds * 0.025;
        double cosine = Math.cos(angle), sine = Math.sin(angle);
        double dr = 76 - inner;
        double dx = dr * cosine - radius * sine * 5.8;
        double dp = dr * sine + radius * cosine * 5.8;
        double tilt = Math.toRadians(tiltDegrees(seconds));
        double ct = Math.cos(tilt), st = Math.sin(tilt);
        double dy = dp * st, dz = dp * ct;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double sx = ct * dz + st * dy, sy = -st * dx, sz = -ct * dx;
        double sideLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
        float width = (float) Math.min(radius * 0.30, 0.32 + 10 * Math.pow(t, 1.2));
        float alpha = (float) (smooth(t / 0.07) * smooth((1 - t) / 0.25) * infallDensity(t, seconds) * 0.72);
        return new Knot(radius * cosine, radius * sine * st, radius * sine * ct,
                dx / length, dy / length, dz / length, sx / sideLength, sy / sideLength, sz / sideLength,
                width, (float) t + base.arm * 0.11F, alpha, 1F, (float) (0.66 + t * 0.24), (float) (0.45 + t * 0.55));
    }

    public static Knot sample(int index, double seconds, int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) throw new IllegalArgumentException("nebula layer: " + layer);
        Template base = TEMPLATES[index];
        double t = base.t;
        double angle = base.angle + seconds * 0.0012 + layer * 0.045;
        double radialPhase = t * 8 + seconds * 0.12 + base.arm + layer * 0.9;
        double radius = 58 + 70 * t + 2 * Math.sin(radialPhase);
        double dr = 70 + 16 * Math.cos(radialPhase);
        double cosine = Math.cos(angle), sine = Math.sin(angle);
        double heightPhase = t * 9 - seconds * 0.09 + base.arm + layer * 0.8;
        double height = (layer == 0 ? -8 : 8) + 3 * Math.sin(heightPhase);
        double dh = 27 * Math.cos(heightPhase);
        double tilt = Math.toRadians(tiltDegrees(seconds));
        double ct = Math.cos(tilt), st = Math.sin(tilt);
        double planeZ = radius * sine;
        double dx = dr * cosine - radius * sine * 3.7;
        double dp = dr * sine + radius * cosine * 3.7;
        double dy = dh * ct + dp * st, dz = dp * ct - dh * st;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double sx = ct * dz + st * dy, sy = -st * dx, sz = -ct * dx;
        double sideLength = Math.sqrt(sx * sx + sy * sy + sz * sz);
        float width = base.width * (float) (0.96 + 0.04 * Math.sin(t * 7 + seconds * 0.13 + layer * 0.8));
        sx /= sideLength;
        sy /= sideLength;
        sz /= sideLength;
        if (layer == 1) {
            // 一層橫向雲帶＋一層沿切線豎起的雲體。側看仍有實際高度，不只是兩條平行薄片。
            double nx = (dy * sz - dz * sy) / length;
            double ny = (dz * sx - dx * sz) / length;
            double nz = (dx * sy - dy * sx) / length;
            sx = nx;
            sy = ny;
            sz = nz;
            width *= 0.72F;
        }
        return new Knot(radius * cosine, height * ct + planeZ * st, planeZ * ct - height * st,
                dx / length, dy / length, dz / length, sx, sy, sz,
                width, (float) t + base.arm * 0.11F + layer * 0.37F, base.envelope * 0.64F,
                base.arm == 0 ? 1F : 0.74F, base.arm == 0 ? 0.9F : 1F, 1F);
    }

    private static Template[] buildTemplates() {
        Template[] values = new Template[2 * (SEGMENTS_PER_ARM + 1)];
        for (int arm = 0; arm < 2; arm++) for (int step = 0; step <= SEGMENTS_PER_ARM; step++) {
            double t = (double) step / SEGMENTS_PER_ARM;
            values[arm * (SEGMENTS_PER_ARM + 1) + step] = new Template(t, arm * Math.PI + 0.35 + t * 3.7,
                    arm, (float) (16 + 12 * Math.sin(Math.PI * t) + 2 * Math.sin(5 * Math.PI * t + arm * 0.7)),
                    (float) (smooth(t / 0.13) * smooth((1 - t) / 0.18)));
        }
        return values;
    }

    private static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }
}
