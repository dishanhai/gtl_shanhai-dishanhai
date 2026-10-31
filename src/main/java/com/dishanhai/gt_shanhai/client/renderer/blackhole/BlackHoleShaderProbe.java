package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/**
 * 山海重构 · 黑洞一级透镜的 <b>「这一帧到底画出了哪几件」判定器 + 着色器数学的 Java 版</b>。
 *
 * <h2>0. 🔴 为什么必须存在（2026-10-24 深夜，用户实机一句「黑洞光看到透镜了，那黑洞呢」）</h2>
 * 那一轮的日志是<b>全绿</b>的：
 * <pre>
 *   [SHANHAI-BH] path=lens-after-composite stage=GameRenderer@renderLevel:TAIL
 *                shaderpack=false depth=ok owner=lens dist=76.9 screenR=82.1 draw=yes
 * </pre>
 * 路径对、尺寸对（82 px 够大）、`draw=yes`，<b>但画面里只有背景被弯折，没有黑盘 / 光子环 / 吸积盘</b>。
 * 根因是 Java 侧喂给着色器的一个 uniform 口径错了（`uBhView` 该给<b>相机空间</b>，实际给了世界空间差值），
 * 于是着色器算出来的碰撞参数 `b` 完全不对 ⇒ 黑盘/环/盘的判据全部落空，<b>只剩 warp 那一层</b>。
 *
 * <p>**为什么当时的离线图看不出来**：出图器（`EveningPreviewPng`）自己另写了一遍透镜数学，
 * 那遍是对的 ⇒ 图很漂亮，游戏里却是空心的。这正是"预览器会替你把错误藏起来"的经典形态。
 *
 * <p>⇒ 本类做两件事，<b>一件事一份代码</b>：
 * <ol>
 *   <li>{@link #shade} —— 与 {@code blackhole.fsh} 逐行同款的<b>单像素着色</b>。
 *       <b>离线出图器直接调它</b>（不再自己写一遍），所以"图里有的"和"游戏里算的"是同一份数学；</li>
 *   <li>{@link #parts} —— 在 rect 内采样网格，<b>真的去算</b>黑盘/光子环/吸积盘/光晕/扭曲各自是否产生非零贡献，
 *       输出一个位掩码进日志（{@code parts=warp|core|ring|disk|glow}）。
 *       这样"日志说画了"和"屏幕上真有"就不会再脱钩。</li>
 * </ol>
 *
 * <h2>1. 与 fsh 的契约</h2>
 * 常数（{@code B_CRIT}/{@code DISK_INNER}/{@code DISK_OUTER}/{@code RING_WIDTH}/{@code RING_GAIN}/
 * {@code HALO_WIDTH}/{@code HALO_GAIN}）与 {@code blackhole.fsh} 顶部<b>逐字同值</b>；
 * 偏折角多项式与 {@link BlackHoleLensMath#deflectAngle} 同款。改任何一处必须三处一起改
 * （fsh / 本类 / {@code BlackHoleLensMath}），并在这里记一行。
 *
 * <h2>2. 纯逻辑</h2>
 * 零 MC、零 GL、零 JOML —— 所以能在没有游戏的情况下跑（{@code temp/host-fx/probe}）。
 */
public final class BlackHoleShaderProbe {

    private BlackHoleShaderProbe() {}

    // ---- 位掩码（日志 parts= 的词表）----
    /** 背景扭曲（warp）。 */
    public static final int PART_WARP = 1;
    /** 黑盘（光子被吞 ⇒ 纯黑）。 */
    public static final int PART_CORE = 1 << 1;
    /** 光子环（bn ≈ b_crit 的解析高斯亮环）。 */
    public static final int PART_RING = 1 << 2;
    /** 赤道吸积盘。 */
    public static final int PART_DISK = 1 << 3;
    /** 外圈光晕（光子环的宽高斯）。 */
    public static final int PART_GLOW = 1 << 4;
    /** 用户口径要求的四件（缺一件就算没画全）。 */
    public static final int PART_REQUIRED = PART_CORE | PART_RING | PART_DISK | PART_GLOW;

    // ---- 物理常数（与 blackhole.fsh 逐字同值）----
    public static final double B_CRIT = 2.5980762D;
    public static final double DISK_INNER = 3.0D;
    public static final double DISK_OUTER = 11.0D;
    public static final double RING_WIDTH = 0.075D;
    public static final double RING_GAIN = 2.90D;
    public static final double HALO_WIDTH = 0.42D;
    public static final double HALO_GAIN = 0.48D;
    /** 边缘淡出起点比例（与 {@code blackhole.fsh} 的 {@code EDGE_FADE_INNER} 同值）。 */
    public static final double EDGE_FADE_INNER = 0.86D;

    /** 一帧的全部输入（与 `blackhole.json` 的 uniform 一一对应，单位也一致）。 */
    public record Params(float[] proj, float[] bhView, float[] diskNormal,
                         int screenW, int screenH,
                         double rectX0, double rectY0, double rectW, double rectH,
                         double lensRadiusPx, double holePxX, double holePxY,
                         double rs, double tanHalfFov,
                         double strength, double diskGain, double ringGain, double time) {}

    /** 背景采样器：{@code (px, py)} = 屏幕像素（左下原点），返回 rgb（0..1）。 */
    public interface BgSampler {
        double[] sample(double px, double py);
    }

    /** 把位掩码翻成日志串（{@code warp|core|ring|disk|glow}，空 = {@code none}）。 */
    public static String describe(int parts) {
        if (parts == 0) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder(32);
        append(sb, parts, PART_WARP, "warp");
        append(sb, parts, PART_CORE, "core");
        append(sb, parts, PART_RING, "ring");
        append(sb, parts, PART_DISK, "disk");
        append(sb, parts, PART_GLOW, "glow");
        return sb.toString();
    }

    private static void append(StringBuilder sb, int parts, int bit, String name) {
        if ((parts & bit) == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('|');
        }
        sb.append(name);
    }

    // ==========================================================================================
    //  单像素着色（与 blackhole.fsh 逐行同款）
    // ==========================================================================================

    /**
     * 一个像素的最终颜色（对应 {@code blackhole.fsh} 的 {@code main()}）。
     *
     * @param bg 背景采样器（{@code fsh} 里是 {@code texture(Sampler0, uv)}；离线图里是程序化星空）
     * @return {@code {r,g,b}}，各 0..1
     */
    public static double[] shade(Params p, BgSampler bg, double px, double py) {
        return shadeImpl(p, bg, px, py, true);
    }

    /**
     * <b>改前</b>的口径（没有边缘淡出）：<b>只给对照出图用</b>，游戏里永不调用。
     *
     * <p>2026-10-25 用户「左右有明显的分割线」的复现需要它：改前的盘/环是"直接加"，
     * 所以矩形一被像素预算收小，盘就被切成一条直边。
     */
    public static double[] shadeLegacy(Params p, BgSampler bg, double px, double py) {
        return shadeImpl(p, bg, px, py, false);
    }

    private static double[] shadeImpl(Params p, BgSampler bg, double px, double py, boolean fadeOn) {
        final double ndcX = px / p.screenW() * 2.0D - 1.0D;
        final double ndcY = py / p.screenH() * 2.0D - 1.0D;
        final double aspect = (double) p.screenW() / (double) p.screenH();
        final double[] dir = normalize(ndcX * p.tanHalfFov() * aspect, ndcY * p.tanHalfFov(), -1.0D);

        // ② 射线 ↔ 黑洞几何
        final double[] bh = {p.bhView()[0], p.bhView()[1], p.bhView()[2]};
        final double tca = dot(bh, dir);
        final double b = impact(bh, dir);
        final double rs = Math.max(p.rs(), 1.0e-4D);
        final double bn = b / rs;

        // ③ 弯折
        final double alpha = deflectAngle(bn) * Math.max(p.strength(), 0.0D);
        final double[] perp = {bh[0] - dir[0] * tca, bh[1] - dir[1] * tca, bh[2] - dir[2] * tca};
        final double perpLen = len(perp);
        double[] dirBent = dir;
        if (perpLen > 1.0e-6D) {
            final double ca = Math.cos(alpha);
            final double sa = Math.sin(alpha);
            dirBent = normalize(dir[0] * ca + perp[0] / perpLen * sa,
                    dir[1] * ca + perp[1] / perpLen * sa,
                    dir[2] * ca + perp[2] / perpLen * sa);
        }

        // ④ 背景（warp 掩码 + 采样点搬移）
        final double dHole = Math.hypot(px - p.holePxX(), py - p.holePxY())
                / Math.max(p.lensRadiusPx(), 1.0D);
        final double maskRadius = 1.0D - smoothstep(0.70D, 1.0D, dHole);
        // 边缘淡出：与 fsh 的 maskFade 同款（v4 起把盘/环/光晕也一起乘上）
        final double maskFade = fadeOn
                ? 1.0D - smoothstep(EDGE_FADE_INNER, 1.0D, dHole) : 1.0D;
        final double edgePx = Math.min(
                Math.min(px - p.rectX0(), p.rectX0() + p.rectW() - px),
                Math.min(py - p.rectY0(), p.rectY0() + p.rectH() - py));
        final double maskEdge = smoothstep(0.0D,
                Math.max(4.0D, p.lensRadiusPx() * 0.10D), edgePx);
        final double warp = maskRadius * maskEdge * maskFade;

        double sampleX = px;
        double sampleY = py;
        if (dirBent[2] < -1.0e-4D) {
            final double bentNdcX = dirBent[0] / (-dirBent[2]) / (p.tanHalfFov() * aspect);
            final double bentNdcY = dirBent[1] / (-dirBent[2]) / p.tanHalfFov();
            final double bentPx = (bentNdcX * 0.5D + 0.5D) * p.screenW();
            final double bentPy = (bentNdcY * 0.5D + 0.5D) * p.screenH();
            sampleX = px + (bentPx - px) * warp;
            sampleY = py + (bentPy - py) * warp;
        }
        final double[] c = bg.sample(sampleX, sampleY);
        double r = c[0];
        double g = c[1];
        double bl = c[2];

        // ⑤ 吸积盘（🔴 乘边缘淡出：fsh 改前是直接加 ⇒ 矩形边界处一条硬边）
        final double[] disk = diskShade(p, dirBent, bh, rs, px, py);
        r += disk[0] * maskFade;
        g += disk[1] * maskFade;
        bl += disk[2] * maskFade;

        // ⑥ 黑影 + 光子环 + 光晕
        //   fwidth 在 Java 侧取不到 ⇒ 用"1 像素对应的 bn 变化量"近似（报告 §7 已标注）
        final double aa = Math.max(pxDeltaBn(p, bh, rs, px, py), 1.0e-4D) * 1.25D;
        final double surfaceDetail = BlackHoleSurfaceWaves.detail(aa / 1.25 / B_CRIT);
        final double surfaceAngle = Math.atan2(py - p.holePxY(), px - p.holePxX() + 1e-6);
        final double waveRadius = surfaceRadius(surfaceAngle, p.time(), surfaceDetail);
        final double shadowMask = 1.0D - smoothstep(waveRadius - aa, waveRadius + aa, bn);
        final double surfaceRho = bn / waveRadius;
        final double phase = surfacePhase(surfaceRho, surfaceAngle, p.time());
        final double angleX = Math.atan2(py - p.holePxY(), px + 1 - p.holePxX() + 1e-6);
        final double angleY = Math.atan2(py + 1 - p.holePxY(), px - p.holePxX() + 1e-6);
        final double phaseX = surfacePhase(bnAtPixel(p, px + 1, py)
                / surfaceRadius(angleX, p.time(), surfaceDetail), angleX, p.time());
        final double phaseY = surfacePhase(bnAtPixel(p, px, py + 1)
                / surfaceRadius(angleY, p.time(), surfaceDetail), angleY, p.time());
        final double width = Math.max(0.075, (Math.abs(phaseX - phase) + Math.abs(phaseY - phase)) * 0.55);
        final double ripple = 0.80 * Math.exp(-sq(Math.sin(phase * 0.5) / width))
                * surfaceDetail * shadowMask * BlackHoleSurfaceWaves.envelope(surfaceRho) * maskFade;
        final double ringWidth = Math.max(RING_WIDTH, aa);
        final double ringCore = Math.exp(-sq((bn - waveRadius) / ringWidth)) * RING_WIDTH / ringWidth;
        final double ringHalo = Math.exp(-sq((bn - waveRadius) / HALO_WIDTH)) * HALO_GAIN;
        final double ring = (ringCore * RING_GAIN + ringHalo) * Math.max(p.ringGain(), 0.0D)
                * maskFade * (1.0D - shadowMask);
        r = r * (1.0D - shadowMask) + ring + ripple;
        g = g * (1.0D - shadowMask) + ring + ripple;
        bl = bl * (1.0D - shadowMask) + ring + ripple;

        return new double[]{r, g, bl};
    }

    /** 吸积盘那一段（与 fsh §⑤ 同构；噪声用确定性函数，离线与运行时同值）。 */
    private static double[] diskShade(Params p, double[] dirBent, double[] bh, double rs, double px, double py) {
        final double[] n = normalize(p.diskNormal()[0], p.diskNormal()[1], p.diskNormal()[2]);
        final double denom = dot(dirBent, n);
        if (Math.abs(denom) <= 1.0e-4D) {
            return new double[3];
        }
        final double tHit = dot(bh, n) / denom;
        if (tHit <= 0.0D) {
            return new double[3];
        }
        final double hx = dirBent[0] * tHit - bh[0];
        final double hy = dirBent[1] * tHit - bh[1];
        final double hz = dirBent[2] * tHit - bh[2];
        final double rn = Math.sqrt(hx * hx + hy * hy + hz * hz) / rs;
        if (rn <= DISK_INNER || rn >= DISK_OUTER) {
            return new double[3];
        }
        final double band = smoothstep(DISK_INNER, DISK_INNER * 1.12D, rn)
                * (1.0D - smoothstep(DISK_OUTER * 0.72D, DISK_OUTER, rn));
        final double[] coord = diskNoiseCoord(p, dirBent, bh, rs);
        final double[] coordX = diskNoiseCoord(p, bentRay(p, bh, rs, px + 1, py), bh, rs);
        final double[] coordY = diskNoiseCoord(p, bentRay(p, bh, rs, px, py + 1), bh, rs);
        final double footprint = Math.max(Math.hypot(coordX[0] - coord[0], coordX[1] - coord[1]),
                Math.hypot(coordY[0] - coord[0], coordY[1] - coord[1]));
        final double turb = fbm(coord[0], coord[1], footprint);
        final double shade = band * (0.35D + 1.30D * turb) * 1.15D;
        final double beta = clamp(0.62D / Math.sqrt(Math.max(rn, 1.0D)), 0.0D, 0.80D);
        // 速度方向 ≈ 切向（用 n × rel 近似，与 fsh 同款）
        final double[] vel = normalize(
                n[1] * hz - n[2] * hy, n[2] * hx - n[0] * hz, n[0] * hy - n[1] * hx);
        final double cosT = -(vel[0] * dirBent[0] + vel[1] * dirBent[1] + vel[2] * dirBent[2]);
        final double beam = 1.0D / Math.pow(Math.max(0.35D, 1.0D - beta * cosT), 3.0D);
        final double[] cHot = {1.00D, 0.97D, 0.92D};
        final double[] cMid = {1.00D, 0.62D, 0.24D};
        final double[] cLow = {0.62D, 0.13D, 0.05D};
        final double t1 = smoothstep(DISK_INNER * 1.05D, DISK_INNER * 3.0D, rn);
        final double t2 = smoothstep(DISK_INNER * 3.0D, DISK_OUTER, rn);
        final double gain = shade * beam * Math.max(p.diskGain(), 0.0D);
        return new double[]{
                lerp(lerp(cHot[0], cMid[0], t1), cLow[0], t2) * gain,
                lerp(lerp(cHot[1], cMid[1], t1), cLow[1], t2) * gain,
                lerp(lerp(cHot[2], cMid[2], t1), cLow[2], t2) * gain};
    }

    /**
     * 屏幕像素 {@code (px,py)} 上那条射线的碰撞参数 {@code bn = b / Rs}（相机空间口径）。
     *
     * <p>它是"黑盘/环/盘会不会出现"的唯一判据：{@code bn < B_CRIT} 才有黑盘。
     * 2026-10-24 深夜的事故里，{@code uBhView} 被错喂成世界空间差值 ⇒ 这个值在<b>画面中心</b>
     * 都会是几十（本该 ≈0）⇒ 四件全部落空、只剩 warp。现在运行时自检就盯这一个数。
     */
    public static double bnAtPixel(Params p, double px, double py) {
        final double aspect = (double) p.screenW() / (double) p.screenH();
        final double ndcX = px / p.screenW() * 2.0D - 1.0D;
        final double ndcY = py / p.screenH() * 2.0D - 1.0D;
        final double[] dir = normalize(ndcX * p.tanHalfFov() * aspect, ndcY * p.tanHalfFov(), -1.0D);
        final double[] bh = {p.bhView()[0], p.bhView()[1], p.bhView()[2]};
        return impact(bh, dir) / Math.max(p.rs(), 1.0e-4D);
    }

    /**
     * 某个像素上的<b>背景采样位移</b>（像素）= {@code |采样点 − 自己| × warp}。
     *
     * <p>用户 2026-10-25 的判据就是拿它比的：「取边界一圈采样点，位移应 &lt; 中心值的 1%」。
     * 它是"矩形边界会不会看出硬边"的直接读数：位移 0 = 该像素输出与底下那层<b>逐像素相同</b>。
     */
    public static double warpDisplacementPx(Params p, double px, double py) {
        final double aspect = (double) p.screenW() / (double) p.screenH();
        final double ndcX = px / p.screenW() * 2.0D - 1.0D;
        final double ndcY = py / p.screenH() * 2.0D - 1.0D;
        final double[] dir = normalize(ndcX * p.tanHalfFov() * aspect, ndcY * p.tanHalfFov(), -1.0D);
        final double[] bh = {p.bhView()[0], p.bhView()[1], p.bhView()[2]};
        final double tca = dot(bh, dir);
        final double b = impact(bh, dir);
        final double bn = b / Math.max(p.rs(), 1.0e-4D);
        final double alpha = deflectAngle(bn) * Math.max(p.strength(), 0.0D);
        final double[] perp = {bh[0] - dir[0] * tca, bh[1] - dir[1] * tca, bh[2] - dir[2] * tca};
        final double perpLen = len(perp);
        if (perpLen <= 1.0e-6D) {
            return 0.0D;
        }
        final double ca = Math.cos(alpha);
        final double sa = Math.sin(alpha);
        final double[] bent = normalize(dir[0] * ca + perp[0] / perpLen * sa,
                dir[1] * ca + perp[1] / perpLen * sa,
                dir[2] * ca + perp[2] / perpLen * sa);
        final double dHole = Math.hypot(px - p.holePxX(), py - p.holePxY())
                / Math.max(p.lensRadiusPx(), 1.0D);
        final double maskRadius = 1.0D - smoothstep(0.70D, 1.0D, dHole);
        final double edgePx = Math.min(
                Math.min(px - p.rectX0(), p.rectX0() + p.rectW() - px),
                Math.min(py - p.rectY0(), p.rectY0() + p.rectH() - py));
        final double maskEdge = smoothstep(0.0D, Math.max(4.0D, p.lensRadiusPx() * 0.10D), edgePx);
        final double maskFade = 1.0D - smoothstep(EDGE_FADE_INNER, 1.0D, dHole);
        final double warp = maskRadius * maskEdge * maskFade;
        if (bent[2] >= -1.0e-4D) {
            return 0.0D;
        }
        final double bentNdcX = bent[0] / (-bent[2]) / (p.tanHalfFov() * aspect);
        final double bentNdcY = bent[1] / (-bent[2]) / p.tanHalfFov();
        final double bentPx = (bentNdcX * 0.5D + 0.5D) * p.screenW();
        final double bentPy = (bentNdcY * 0.5D + 0.5D) * p.screenH();
        return Math.hypot(bentPx - px, bentPy - py) * warp;
    }

    /** 黑洞中心那个像素上的 {@code bn}（自检用；正常应 ≈ 0，远小于 {@link #B_CRIT}）。 */
    public static double bnAtHoleCenter(Params p) {
        return bnAtPixel(p, p.holePxX(), p.holePxY());
    }

    /** 相邻像素之间 {@code bn} 的变化量（替代 GLSL 的 {@code fwidth(bn)}）。 */
    private static double pxDeltaBn(Params p, double[] bh, double rs, double px, double py) {
        final double aspect = (double) p.screenW() / (double) p.screenH();
        final double bn0 = bnAt(p, bh, rs, px, py, aspect);
        final double bn1 = bnAt(p, bh, rs, px + 1.0D, py, aspect);
        final double bn2 = bnAt(p, bh, rs, px, py + 1.0D, aspect);
        return Math.abs(bn1 - bn0) + Math.abs(bn2 - bn0);
    }

    private static double bnAt(Params p, double[] bh, double rs, double px, double py, double aspect) {
        final double ndcX = px / p.screenW() * 2.0D - 1.0D;
        final double ndcY = py / p.screenH() * 2.0D - 1.0D;
        final double[] dir = normalize(ndcX * p.tanHalfFov() * aspect, ndcY * p.tanHalfFov(), -1.0D);
        return impact(bh, dir) / rs;
    }

    // ==========================================================================================
    //  parts 位掩码：在 rect 内采样，看哪几件真的会有非零贡献
    // ==========================================================================================

    /**
     * 在 rect 内按 {@code n×n} 网格采样，返回 {@link #PART_WARP} 等位掩码。
     *
     * <p>判据都是"这一件自己的阈值"（不是颜色）：
     * <ul>
     *   <li>{@code core}：存在像素 {@code bn < B_CRIT}（黑影遮住背景）；</li>
     *   <li>{@code ring}：存在像素 {@code |bn−B_CRIT| < RING_WIDTH}（高斯亮环的半功率内）；</li>
     *   <li>{@code glow}：存在像素 {@code |bn−B_CRIT| < HALO_WIDTH}；</li>
     *   <li>{@code disk}：存在像素的弯折射线与盘面相交且 {@code rn ∈ (3, 11)}；</li>
     *   <li>{@code warp}：{@code strength > 0} 且 rect 里有像素 {@code dHole < 1}（掩码非零）。</li>
     * </ul>
     *
     * @param samples 每轴采样数（建议 64；越大越不容易漏掉细环 —— 光子环只有 0.075 Rs 宽）
     */
    public static int parts(Params p, int samples) {
        final int n = Math.max(8, samples);
        final double aspect = (double) p.screenW() / (double) p.screenH();
        final double[] bh = {p.bhView()[0], p.bhView()[1], p.bhView()[2]};
        final double rs = Math.max(p.rs(), 1.0e-4D);
        int parts = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final double px = p.rectX0() + p.rectW() * (i + 0.5D) / n;
                final double py = p.rectY0() + p.rectH() * (j + 0.5D) / n;
                final double dHole = Math.hypot(px - p.holePxX(), py - p.holePxY())
                        / Math.max(p.lensRadiusPx(), 1.0D);
                if (p.strength() > 0.0D && dHole < 1.0D) {
                    parts |= PART_WARP;
                }
                final double ndcX = px / p.screenW() * 2.0D - 1.0D;
                final double ndcY = py / p.screenH() * 2.0D - 1.0D;
                final double[] dir = normalize(ndcX * p.tanHalfFov() * aspect,
                        ndcY * p.tanHalfFov(), -1.0D);
                final double tca = dot(bh, dir);
                final double bn = impact(bh, dir) / rs;
                if (bn < B_CRIT) {
                    parts |= PART_CORE;
                }
                if (Math.abs(bn - B_CRIT) < RING_WIDTH) {
                    parts |= PART_RING;
                }
                if (Math.abs(bn - B_CRIT) < HALO_WIDTH) {
                    parts |= PART_GLOW;
                }
                if ((parts & PART_DISK) == 0 && diskHits(p, dir, bh, rs, tca)) {
                    parts |= PART_DISK;
                }
                if ((parts & PART_REQUIRED) == PART_REQUIRED) {
                    return parts;                    // 四件齐了，不用再采
                }
            }
        }
        return parts;
    }

    /** 该像素的弯折射线是否真的与盘面相交并落在盘环内（与 {@link #diskShade} 同一判据）。 */
    private static boolean diskHits(Params p, double[] dir, double[] bh, double rs, double tca) {
        final double[] n = normalize(p.diskNormal()[0], p.diskNormal()[1], p.diskNormal()[2]);
        // 与 shade 里一样先弯折
        final double bn = impact(bh, dir) / rs;
        final double alpha = deflectAngle(bn) * Math.max(p.strength(), 0.0D);
        final double[] perp = {bh[0] - dir[0] * tca, bh[1] - dir[1] * tca, bh[2] - dir[2] * tca};
        final double perpLen = len(perp);
        double[] bent = dir;
        if (perpLen > 1.0e-6D) {
            final double ca = Math.cos(alpha);
            final double sa = Math.sin(alpha);
            bent = normalize(dir[0] * ca + perp[0] / perpLen * sa,
                    dir[1] * ca + perp[1] / perpLen * sa,
                    dir[2] * ca + perp[2] / perpLen * sa);
        }
        final double denom = dot(bent, n);
        if (Math.abs(denom) <= 1.0e-4D) {
            return false;
        }
        final double tHit = dot(bh, n) / denom;
        if (tHit <= 0.0D) {
            return false;
        }
        final double hx = bent[0] * tHit - bh[0];
        final double hy = bent[1] * tHit - bh[1];
        final double hz = bent[2] * tHit - bh[2];
        final double rn = Math.sqrt(hx * hx + hy * hy + hz * hz) / rs;
        return rn > DISK_INNER && rn < DISK_OUTER;
    }

    // ==========================================================================================
    //  小工具（与 fsh 同款）
    // ==========================================================================================

    /** 偏折角（与 fsh {@code deflectAngle} / {@link BlackHoleLensMath#deflectAngle} 同款）。 */
    public static double deflectAngle(double bn) {
        final double t = 1.0D / Math.max(bn, 0.35D);
        return 2.0D * t + 2.2D * t * t + 1.6D * t * t * t;
    }

    static double smoothstep(double a, double b, double x) {
        if (a == b) {
            return x < a ? 0.0D : 1.0D;
        }
        final double t = Math.max(0.0D, Math.min(1.0D, (x - a) / (b - a)));
        return t * t * (3.0D - 2.0D * t);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double[] normalize(double x, double y, double z) {
        final double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0e-12D) {
            return new double[]{0.0D, 0.0D, 0.0D};
        }
        return new double[]{x / l, y / l, z / l};
    }

    // ---- 与 fsh 同款的便宜 value noise（三抽样 fbm）----
    private static double hash21(double x, double y) {
        double px = fract(x * 123.34D);
        double py = fract(y * 456.21D);
        final double d = px + py + px * 45.32D + py * 45.32D;
        px += d;
        py += d;
        return fract(px * py);
    }

    private static double fract(double v) {
        return v - Math.floor(v);
    }

    private static double vnoise(double x, double y) {
        final double ix = Math.floor(x);
        final double iy = Math.floor(y);
        final double fx = x - ix;
        final double fy = y - iy;
        final double ux = fx * fx * (3.0D - 2.0D * fx);
        final double uy = fy * fy * (3.0D - 2.0D * fy);
        final double a = hash21(ix, iy);
        final double b = hash21(ix + 1.0D, iy);
        final double c = hash21(ix, iy + 1.0D);
        final double d = hash21(ix + 1.0D, iy + 1.0D);
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uy);
    }

    private static double fbm(double x, double y, double footprint) {
        return 0.55D * lerp(vnoise(x, y), 0.5D, smoothstep(0.35D, 1.20D, footprint))
                + 0.30D * lerp(vnoise(x * 2.03D + 11.3D, y * 2.03D + 11.3D), 0.5D,
                        smoothstep(0.35D, 1.20D, footprint * 2.03D))
                + 0.15D * lerp(vnoise(x * 4.07D + 27.1D, y * 4.07D + 27.1D), 0.5D,
                        smoothstep(0.35D, 1.20D, footprint * 4.07D));
    }

    private static double impact(double[] bh, double[] dir) {
        return Math.hypot(Math.hypot(bh[1] * dir[2] - bh[2] * dir[1],
                bh[2] * dir[0] - bh[0] * dir[2]), bh[0] * dir[1] - bh[1] * dir[0]);
    }

    private static double surfaceRadius(double angle, double time, double detail) {
        return B_CRIT * BlackHoleSurfaceWaves.radiusScale(angle, time, detail);
    }

    private static double surfacePhase(double radius, double angle, double time) {
        return BlackHoleSurfaceWaves.phase(radius, angle, time);
    }

    private static double[] bentRay(Params p, double[] bh, double rs, double px, double py) {
        double[] dir = normalize((px / p.screenW() * 2 - 1) * p.tanHalfFov() * p.screenW() / p.screenH(),
                (py / p.screenH() * 2 - 1) * p.tanHalfFov(), -1);
        double tca = dot(bh, dir);
        double[] perp = {bh[0] - dir[0] * tca, bh[1] - dir[1] * tca, bh[2] - dir[2] * tca};
        double length = len(perp);
        if (length <= 1e-6) return dir;
        double alpha = deflectAngle(impact(bh, dir) / rs) * Math.max(p.strength(), 0);
        return normalize(dir[0] * Math.cos(alpha) + perp[0] / length * Math.sin(alpha),
                dir[1] * Math.cos(alpha) + perp[1] / length * Math.sin(alpha),
                dir[2] * Math.cos(alpha) + perp[2] / length * Math.sin(alpha));
    }

    /** 與 GLSL 共用盤面座標；CPU 以鄰接像素近似 GPU 的四像素導數。 */
    private static double[] diskNoiseCoord(Params p, double[] ray, double[] bh, double rs) {
        double[] n = normalize(p.diskNormal()[0], p.diskNormal()[1], p.diskNormal()[2]);
        double[] x = Math.abs(n[1]) < 0.9 ? normalize(-n[2], 0, n[0]) : normalize(0, n[2], -n[1]);
        double[] z = {n[1] * x[2] - n[2] * x[1], n[2] * x[0] - n[0] * x[2], n[0] * x[1] - n[1] * x[0]};
        double denom = dot(ray, n);
        if (Math.abs(denom) < 1e-4) denom = denom < 0 ? -1e-4 : 1e-4;
        double hit = dot(bh, n) / denom;
        double[] rel = {ray[0] * hit - bh[0], ray[1] * hit - bh[1], ray[2] * hit - bh[2]};
        double radius = len(rel) / rs;
        double angle = Math.atan2(dot(rel, z), dot(rel, x));
        double spin = p.time() * 2.6 / Math.pow(Math.max(radius, 1), 1.5);
        return new double[]{Math.min(radius, 64) * 1.35 + p.time() * 0.45, Math.sin(angle + spin) * 2.6};
    }
}
