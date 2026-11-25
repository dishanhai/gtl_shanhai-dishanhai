package com.dishanhai.gt_shanhai.client.renderer.hostfx;

/** 山海：小傾角、雙層厚度的動態星雲；固定模板，不持有世界或粒子。 */
public final class GuixuNebulaField {
    public static final int SEGMENTS_PER_ARM = 24;
    public static final int LAYER_COUNT = 2;
    public static final int PATCH_COUNT = 48;
    // 外层中心半径 <= 130、高度 <= 11、截面半宽 <= 30，倾斜不增加向量长度。
    static final float BOUNDS_RADIUS = 162;
    private static final int KNOT_COUNT = 2 * (SEGMENTS_PER_ARM + 1);
    private static final double TAU = Math.PI * 2;
    private static final Template[][] CLOUDS = new Template[LAYER_COUNT][KNOT_COUNT];
    private static final InfallTemplate[] INFALL = new InfallTemplate[KNOT_COUNT];

    static {
        buildTemplates();
    }

    private GuixuNebulaField() {}

    private record Phase(double sin, double cos) {
        static Phase at(double angle) {
            return new Phase(Math.sin(angle), Math.cos(angle));
        }

        double sinWith(Phase offset) {
            return sin * offset.cos + cos * offset.sin;
        }

        double cosWith(Phase offset) {
            return cos * offset.cos - sin * offset.sin;
        }
    }

    private record Template(Phase angle, Phase radial, Phase height, Phase widthPhase,
                            double radius, float width, float u, float alpha, float red, float green) {}

    private record InfallTemplate(Phase angle, Phase density, double t, double width,
                                  float u, double alpha, float green, float blue) {}

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
        return fallbackMaterialU(u, materialPhase(seconds));
    }

    static float fallbackMaterialU(float u, float phase) {
        double coordinate = (u * 2 - phase / Math.PI) * 0.5;
        double fraction = coordinate - Math.floor(coordinate);
        return (float) (1 - Math.abs(fraction * 2 - 1));
    }

    public static Knot sample(int index, double seconds) {
        return sample(index, seconds, 0);
    }

    /** 向內傳播的寬密度峰；20 秒無縫循環，不將整條雲帶重生或瞬移。 */
    public static double infallDensity(double t, double seconds) {
        double phase = TAU * (t * 2 + (seconds % 20) / 10);
        return densityFromCosine(Math.cos(phase));
    }

    private static double densityFromCosine(double cosine) {
        double crest = 0.5 + 0.5 * cosine;
        return 0.52 + 0.48 * crest * crest;
    }

    /** 外端与原星云重叠，内端随玩家设置的视界半径收窄。 */
    public static Knot sampleInfall(int index, double seconds, double horizon) {
        return new Frame(seconds, horizon).sampleInfall(index, new Sample()).snapshot();
    }

    public static Knot sample(int index, double seconds, int layer) {
        if (layer < 0 || layer >= LAYER_COUNT) throw new IllegalArgumentException("nebula layer: " + layer);
        return new Frame(seconds, 1).sample(index, layer, new Sample()).snapshot();
    }

    /** 各节点只合成相位。每帧的时间三角函数与倾角由整台主机的雾层共用。 */
    static final class Frame {
        private final Phase spin, radial, height, width, density, tilt;
        private final double inner;

        Frame(double seconds, double horizon) {
            spin = Phase.at(seconds * 0.0012);
            radial = Phase.at(seconds * 0.12);
            height = Phase.at(-seconds * 0.09);
            width = Phase.at(seconds * 0.13);
            density = Phase.at(TAU * (seconds % 20) / 10);
            tilt = Phase.at(Math.toRadians(tiltDegrees(seconds)));
            inner = Math.max(0.8, Math.min(12, horizon)) * 1.16;
        }

        Sample sample(int index, int layer, Sample result) {
            Template base = CLOUDS[layer][index];
            double radius = base.radius + 2 * radial.sinWith(base.radial);
            double dr = 70 + 16 * radial.cosWith(base.radial);
            double offset = (layer == 0 ? -8 : 8) + 3 * height.sinWith(base.height);
            double dh = 27 * height.cosWith(base.height);
            result.setGeometry(tilt, spin, base.angle, radius, dr, 3.7, offset, dh);
            result.width = base.width * (float) (0.96 + 0.04 * width.sinWith(base.widthPhase));
            if (layer == 1) {
                // 上层截面沿脊线竖起，侧视仍能看到云体高度。
                double nx = result.tangentY * result.sideZ - result.tangentZ * result.sideY;
                double ny = result.tangentZ * result.sideX - result.tangentX * result.sideZ;
                result.sideZ = result.tangentX * result.sideY - result.tangentY * result.sideX;
                result.sideX = nx;
                result.sideY = ny;
                result.width *= 0.72F;
            }
            result.u = base.u;
            result.alpha = base.alpha;
            result.red = base.red;
            result.green = base.green;
            result.blue = 1F;
            return result;
        }

        Sample sampleInfall(int index, Sample result) {
            InfallTemplate base = INFALL[index];
            double dr = 76 - inner;
            double radius = inner + dr * base.t;
            result.setGeometry(tilt, spin, base.angle, radius, dr, 5.8, 0, 0);
            result.width = (float) Math.min(radius * 0.30, base.width);
            result.u = base.u;
            result.alpha = (float) (base.alpha * densityFromCosine(density.cosWith(base.density)));
            result.red = 1F;
            result.green = base.green;
            result.blue = base.blue;
            return result;
        }
    }

    static class Sample {
        double x, y, z;
        double tangentX, tangentY, tangentZ;
        double sideX, sideY, sideZ;
        float width, u, alpha, red, green, blue;

        private void setGeometry(Phase tilt, Phase spin, Phase angle, double radius, double dr,
                                 double angleRate, double height, double dh) {
            double cosine = spin.cosWith(angle), sine = spin.sinWith(angle);
            double planeZ = radius * sine;
            x = radius * cosine;
            y = height * tilt.cos + planeZ * tilt.sin;
            z = planeZ * tilt.cos - height * tilt.sin;
            double dx = dr * cosine - planeZ * angleRate;
            double dp = dr * sine + x * angleRate;
            double planarLengthSquared = dx * dx + dp * dp;
            double tangentScale = 1 / Math.sqrt(planarLengthSquared + dh * dh);
            tangentX = dx * tangentScale;
            tangentY = (dh * tilt.cos + dp * tilt.sin) * tangentScale;
            tangentZ = (dp * tilt.cos - dh * tilt.sin) * tangentScale;
            double sideScale = 1 / Math.sqrt(planarLengthSquared);
            sideX = dp * sideScale;
            sideY = -tilt.sin * dx * sideScale;
            sideZ = -tilt.cos * dx * sideScale;
        }

        private Knot snapshot() {
            return new Knot(x, y, z, tangentX, tangentY, tangentZ, sideX, sideY, sideZ,
                    width, u, alpha, red, green, blue);
        }
    }

    private static void buildTemplates() {
        for (int arm = 0; arm < 2; arm++) for (int step = 0; step <= SEGMENTS_PER_ARM; step++) {
            int index = arm * (SEGMENTS_PER_ARM + 1) + step;
            double t = (double) step / SEGMENTS_PER_ARM;
            double armAngle = arm * Math.PI + 0.35;
            float u = (float) t + arm * 0.11F;
            float width = (float) (16 + 12 * Math.sin(Math.PI * t) + 2 * Math.sin(5 * Math.PI * t + arm * 0.7));
            float alpha = (float) (smooth(t / 0.13) * smooth((1 - t) / 0.18)) * 0.64F;
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                CLOUDS[layer][index] = new Template(Phase.at(armAngle + t * 3.7 + layer * 0.045),
                        Phase.at(t * 8 + arm + layer * 0.9), Phase.at(t * 9 + arm + layer * 0.8),
                        Phase.at(t * 7 + layer * 0.8), 58 + 70 * t, width, u + layer * 0.37F, alpha,
                        arm == 0 ? 1F : 0.74F, arm == 0 ? 0.9F : 1F);
            }
            INFALL[index] = new InfallTemplate(Phase.at(armAngle + (76.0 - 58.0) / 70.0 * 3.7 + (t - 1) * 5.8),
                    Phase.at(TAU * t * 2), t, 0.32 + 10 * Math.pow(t, 1.2), u,
                    smooth(t / 0.07) * smooth((1 - t) / 0.25) * 0.72,
                    (float) (0.66 + t * 0.24), (float) (0.45 + t * 0.55));
        }
    }

    private static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }
}
