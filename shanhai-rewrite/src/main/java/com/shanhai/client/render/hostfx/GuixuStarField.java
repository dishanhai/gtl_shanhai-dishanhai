package com.shanhai.client.render.hostfx;

/**
 * 山海 · <b>以世界時間取樣的星辰吸入軌跡</b>（作者 dishanhai，2026-11-22 移植进本工程）。
 *
 * <h2>来源与"纯移植"口径</h2>
 * 逐行照抄他的 {@code com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuStarField}
 * （{@code temp/upstream-bump/src/GuixuStarField.java}）。他的类注释逐字：
 * 「{@code 山海：以世界時間取樣的星辰吸入軌跡，無粒子實體或跨世界狀態。}」
 *
 * <h2>六组数量（他的常量，一个都没改）</h2>
 * <table border="1">
 *   <tr><th>组</th><th>常量</th><th>值</th><th>采样入口</th><th>说明</th></tr>
 *   <tr><td>吸入星</td><td>{@link #PARTICLE_COUNT}</td><td>192</td><td>{@link #sample}</td>
 *       <td>从 24~44 格半径螺旋落向视界；拖尾 = 相位差 0.004~0.01</td></tr>
 *   <tr><td>背景星</td><td>{@link #SKY_COUNT}</td><td>512</td><td>{@link #sampleSky}</td>
 *       <td>半径 76~104 的球壳，做了 0.48 rad 的平面倾斜（星带，不是均匀壳）</td></tr>
 *   <tr><td>轨道尘</td><td>{@link #ORBIT_COUNT}</td><td>64</td><td>{@link #sampleOrbit}</td>
 *       <td>半径 70~82，上下起伏 ±8~13 格</td></tr>
 *   <tr><td>流星</td><td>{@link #METEOR_COUNT}</td><td>8</td><td>{@link #sampleMeteor}</td>
 *       <td>只在周期前 16% 出现，其余时间返回 {@code INACTIVE}（alpha=0，可整条跳过）</td></tr>
 *   <tr><td>体积星</td><td>{@link #VOLUME_COUNT}</td><td>384</td><td>{@link #sampleVolume}</td>
 *       <td>只在黑洞近旁（半径 = {@link #environmentRadius}，最大 30.4 格）</td></tr>
 *   <tr><td>星尘</td><td>{@link #DUST_COUNT}</td><td>96</td><td>{@link #sampleDust}</td>
 *       <td>极低 alpha（0.025~0.06），没有实心雾面</td></tr>
 * </table>
 *
 * <h2>两条硬语义（照抄）</h2>
 * <ol>
 *   <li><b>全部是按世界时间取的纯函数</b>：{@link #sample} 只看 {@code (index, seconds, surfaceRadius)}，
 *       不建粒子实体、不存跨世界状态 ⇒ 存档里没有这些星，暂停时自然冻结；</li>
 *   <li><b>{@link #hiddenByShadow} 剔除"投影落在黑洞阴影里"的星</b>：环境星点与星尘不许把黑洞的
 *       投影阴影填亮；黑洞停用时返回 false（不留人造缺口）。</li>
 * </ol>
 *
 * <h2>为什么总量够小</h2>
 * 六组合计 <b>192+512+64+8+384+96 = 1256</b> 颗 ⇒ 每颗一个公告板四边形 = <b>1256 quads 上限</b>
 * （渲染侧还会按 {@link #skyCount}/{@link #volumeCount}/{@link #dustCount} 的距离降级再减）。
 *
 * <h2>零 Minecraft 依赖</h2>
 * 只有 {@code java.lang} + 一个自造的 {@code SEEDS} 表 ⇒ 可脱机单编、离线探针直接对
 * "同一 index 同一 seconds 必得同一 Sample"、"流星周期外必 INACTIVE" 下断言。
 */
public final class GuixuStarField {

    /** 吸入星数量（他的值）。 */
    public static final int PARTICLE_COUNT = 192;
    /** 背景星数量（他的值）。 */
    public static final int SKY_COUNT = 512;
    /** 轨道尘数量（他的值）。 */
    public static final int ORBIT_COUNT = 64;
    /** 流星数量（他的值）。 */
    public static final int METEOR_COUNT = 8;
    /** 体积星数量（他的值）。 */
    public static final int VOLUME_COUNT = 384;
    /** 星尘数量（他的值）。 */
    public static final int DUST_COUNT = 96;
    private static final double TAU = Math.PI * 2.0;
    // 山海：只快取不變的種子，不保留動畫、世界或主機狀態。
    private static final double[][] SEEDS = buildSeeds();
    private static final Sample INACTIVE = new Sample(0, 0, 0, 0, 0, 0, 0, 0, false);

    private GuixuStarField() {
    }

    /**
     * 一颗星的一帧采样：{@code head} = 当前位置、{@code tail} = 拖尾末端（同一段轨迹的前一点）。
     *
     * @param size  世界尺寸（格，边长）
     * @param alpha 亮度（0 = 这一帧不画）
     * @param warm  暖白（他的语义：每 11 颗有 1 颗暖色）
     */
    public record Sample(double headX, double headY, double headZ,
                         double tailX, double tailY, double tailZ,
                         float size, float alpha, boolean warm) {

        /** 这一帧到底画不画（alpha 极小的直接跳过 —— 渲染侧的唯一判据）。 */
        public boolean visible() {
            return alpha > 0.004F;
        }
    }

    /** 吸入星：{@code index} 的周期 = 10 + seed×8 秒（他的 10.0 + seed(index,1)*8.0）。 */
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

    /** 背景星这一帧画几颗：降级态 <b>96</b>；否则 256 → 512 随密度线性。 */
    public static int skyCount(float density, boolean degraded) {
        if (degraded) {
            return 96;
        }
        return 256 + (int) (Math.max(0.0F, Math.min(1.0F, density)) * (SKY_COUNT - 256));
    }

    /** 新增環境限於視界近旁；最大 30.4 格，小於半徑 48 格的機器內環。 */
    public static double environmentRadius(double horizon) {
        return 4 + 2.2 * Math.max(.8, Math.min(12, horizon));
    }

    /** 山海：只在黑洞近旁填入三維星空，均勻分層緯度避免又變成一條薄環。 */
    public static Sample sampleVolume(int index, double seconds, double horizon) {
        double extent = environmentRadius(horizon);
        double inner = Math.max(.8, Math.min(12, horizon)) * 1.5 + 1;
        double radius = inner + (extent * .82 - inner) * Math.pow(seed(index, 41), .75);
        double vertical = 1 - 2 * (Math.floorMod(index * 149, VOLUME_COUNT) + .5) / VOLUME_COUNT;
        double angle = seed(index, 42) * TAU + seconds * .003;
        double radial = radius * Math.sqrt(Math.max(0, 1 - vertical * vertical));
        Point p = new Point(Math.cos(angle) * radial, radius * vertical, Math.sin(angle) * radial);
        double twinkle = .5 + .5 * Math.sin(seconds * (.35 + seed(index, 43) * .65) + seed(index, 44) * TAU);
        float size = (float) (((index % 31 == 0 ? .25 : .11) + seed(index, 45) * .12) * extent / 68);
        return ambient(p, p, size, (float) (.30 + .48 * twinkle), index % 9 == 0);
    }

    /** 少量半透明星塵口袋，沒有實心霧面，也不向視界內填光。 */
    public static Sample sampleDust(int index, double seconds, double horizon) {
        double extent = environmentRadius(horizon);
        double inner = Math.max(.8, Math.min(12, horizon)) * 1.5 + 1;
        double radius = inner + (extent * .74 - inner) * seed(index, 51);
        double angle = seed(index, 52) * TAU + seconds * .006;
        double vertical = (seed(index, 53) * 2 - 1) * .75;
        double radial = radius * Math.sqrt(1 - vertical * vertical);
        Point p = new Point(Math.cos(angle) * radial, radius * vertical, Math.sin(angle) * radial);
        double pulse = .5 + .5 * Math.sin(seconds * .17 + seed(index, 54) * TAU);
        return ambient(p, p, (float) ((.45 + seed(index, 55) * .75) * extent / 30.4), (float) (.025 + .035 * pulse), false);
    }

    /** 体积星这一帧画几颗：降级态 <b>64</b>；否则 192 → 384 随密度线性。 */
    public static int volumeCount(float density, boolean degraded) {
        return degraded ? 64 : 192 + Math.round(Math.max(0, Math.min(1, density)) * (VOLUME_COUNT - 192));
    }

    /** 星尘这一帧画几颗：降级态 <b>0</b>（整组关掉）；否则 48 → 96。 */
    public static int dustCount(float density, boolean degraded) {
        return degraded ? 0 : 48 + Math.round(Math.max(0, Math.min(1, density)) * (DUST_COUNT - 48));
    }

    /**
     * 環境星點與星塵不能把黑洞的投影陰影填亮；停用黑洞時不留人造缺口。
     *
     * @param camera 相机在<b>同一帧</b>（环心本地）的坐标，3 个 float
     * @param horizon 黑洞表面半径（格，环心本地）；{@code <= 0} = 黑洞停用 ⇒ 一律不剔
     */
    public static boolean hiddenByShadow(float[] camera, Sample star, double horizon) {
        if (!(horizon > 0) || camera == null || camera.length < 3) {
            return false;
        }
        double distanceSquared = camera[0] * camera[0] + camera[1] * camera[1] + camera[2] * camera[2];
        if (distanceSquared <= horizon * horizon) {
            return true;
        }
        double dx = star.headX - camera[0], dy = star.headY - camera[1], dz = star.headZ - camera[2];
        double toward = -(dx * camera[0] + dy * camera[1] + dz * camera[2]);
        if (toward <= 0) {
            return false;
        }
        double rayLengthSquared = dx * dx + dy * dy + dz * dz;
        double shadow = horizon + star.size * 2.6;
        return distanceSquared - toward * toward / Math.max(1e-10, rayLengthSquared) < shadow * shadow;
    }

    private static Sample ambient(Point head, Point tail, float size, float alpha, boolean warm) {
        return new Sample(head.x, head.y, head.z, tail.x, tail.y, tail.z, size, alpha, warm);
    }

    private record Point(double x, double y, double z) {
    }

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
        double[][] values = new double[SKY_COUNT][56];
        for (int i = 0; i < values.length; i++) {
            for (int salt = 0; salt < values[i].length; salt++) {
                values[i][salt] = computeSeed(i, salt);
            }
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
