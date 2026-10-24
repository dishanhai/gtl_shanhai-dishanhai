package com.dishanhai.gt_shanhai.client.renderer.blackhole;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · <b>洞内视角的纯几何</b>（零 MC / 零 GL / 零 JOML）。
 *
 * <h2>为什么要单独一个纯类</h2>
 * 用户 2026-10-25：「飞到黑洞里面、往外看的时候，透镜不渲染了」。
 * 处置是新增一条"洞内视角"的路（见 {@code BlackHoleLensRenderer#drawInside}）。
 * <b>上一轮的教训</b>是"预览器自己另写一遍数学 ⇒ 图里对、游戏里错"，
 * 所以洞内这几层也做成<b>一份纯几何</b>：渲染器把它提交成顶点，
 * 离线出图器（{@code temp/host-fx/probe}）把它逐四边形光栅化 —— <b>同一份数据</b>。
 *
 * <h2>坐标约定</h2>
 * <b>(u,v)</b>：{@code v ∈ [−1,1]} 对应屏幕高度，{@code u ∈ [−aspect, aspect]} 对应宽度
 * ⇒ 同一个半径在 u/v 上是一个<b>圆</b>，不会因为宽银幕被拉成椭圆。
 * 转 NDC 只差一次 {@code ndcX = u/aspect}。
 *
 * <h2>四层（顺序 = 绘制顺序）</h2>
 * <ol>
 *   <li><b>暗穹顶</b>：整屏压暗（alpha 混合）；</li>
 *   <li><b>视界环</b>：贴屏幕边缘的一圈冷白蓝光晕（加性）；</li>
 *   <li><b>赤道盘带</b>：吸积盘从内侧可见 —— 盘平面过洞心，它在屏幕上的像是该平面与成像面的
 *       <b>交线</b>（由相机空间的盘法线算出），画成三段不同宽度的带（加性）；</li>
 *   <li><b>中心微光</b>：屏幕正中一小团（加性）。</li>
 * </ol>
 */
public final class BlackHoleInsideGeometry {

    private BlackHoleInsideGeometry() {}

    /** 一个四边形：(u,v) 四角 + 颜色 + 亮度（alpha）+ 是否加性。 */
    public record Quad(double[] uv8, float r, float g, float b, float a, boolean additive) {
        public Quad {
            if (uv8 == null || uv8.length != 8) {
                throw new IllegalArgumentException("uv8 必须是 8 个 double（4 顶点 × u,v）");
            }
        }
    }

    /** 暗穹顶的 alpha（洞内把世界压暗到还剩 20%）。 */
    public static final float DOME_ALPHA = 0.80F;

    /**
     * 生成洞内视角的全部四边形。
     *
     * @param aspect        屏幕宽/高
     * @param insideRatio   {@code dist / 可见黑盘半径}（0 = 正在球心，1 = 刚进球面）
     * @param diskNormalCam 吸积盘法线在<b>相机空间</b>里的方向（已归一化；null/零向量 ⇒ 不画盘带）
     * @param seconds       渲染时间（秒），只用来做轻微呼吸
     * @param includeDome   是否包含暗穹顶（离线出"只有光效"的图时给 false）
     */
    public static List<Quad> quads(double aspect, double insideRatio, float[] diskNormalCam,
                                   float seconds, boolean includeDome) {
        final List<Quad> out = new ArrayList<>(160);
        final double t = seconds;

        // ---- ① 暗穹顶（略大于屏幕，避免边缘露出）----
        if (includeDome) {
            out.add(new Quad(new double[]{
                    -aspect - 0.02D, -1.02D,
                    aspect + 0.02D, -1.02D,
                    aspect + 0.02D, 1.02D,
                    -aspect - 0.02D, 1.02D},
                    0.012F, 0.016F, 0.026F, DOME_ALPHA, false));
        }

        // ---- ② 视界环 ----
        final double ringR = 0.93D + 0.09D * clamp01(insideRatio);
        final float ringA = (float) (0.42D + 0.14D * Math.sin(t * 1.7D));
        final int seg = 72;
        for (int i = 0; i < seg; i++) {
            final double a0 = Math.PI * 2.0D * i / seg;
            final double a1 = Math.PI * 2.0D * (i + 1) / seg;
            // 三圈同心带 ⇒ 内外都有渐变，不再是一条"平口甜甜圈"
            final double[][] bands = {{-0.085D, -0.030D, 0.45D}, {-0.030D, 0.030D, 1.00D},
                    {0.030D, 0.095D, 0.38D}};
            for (double[] bnd : bands) {
                final double r0 = ringR + bnd[0];
                final double r1 = ringR + bnd[1];
                out.add(new Quad(new double[]{
                        r0 * Math.cos(a0), r0 * Math.sin(a0),
                        r1 * Math.cos(a0), r1 * Math.sin(a0),
                        r1 * Math.cos(a1), r1 * Math.sin(a1),
                        r0 * Math.cos(a1), r0 * Math.sin(a1)},
                        0.62F, 0.78F, 1.00F, (float) (ringA * bnd[2]), true));
            }
        }

        // ---- ③ 赤道盘带 ----
        //  像素射线（相机空间）= normalize(ndcX·tan·aspect, ndcY·tan, −1)；
        //  该像素落在盘平面上 ⇔ dot(dir, N) = 0 ⇔ ndcX·(tan·aspect·Nx) + ndcY·(tan·Ny) = Nz
        //  换成 (u,v)（ndcX = u/aspect）⇒ a2·u + b2·v = c2
        if (diskNormalCam != null) {
            final double tan = 0.70D;
            final double a2 = tan * diskNormalCam[0];
            final double b2 = tan * diskNormalCam[1];
            final double c2 = diskNormalCam[2];
            final double len2 = Math.hypot(a2, b2);
            if (len2 > 0.05D) {
                final double px0 = a2 * c2 / (len2 * len2);
                final double py0 = b2 * c2 / (len2 * len2);
                final double dx = -b2 / len2;
                final double dy = a2 / len2;
                final double halfLen = Math.hypot(aspect, 1.0D) * 1.4D;
                // 5 段宽度递减 + 亮度递增 ⇒ 叠成一条有渐变的光带（3 段时边缘能看出台阶）
                final double[] widths = {0.190D, 0.150D, 0.105D, 0.062D, 0.028D};
                final float[][] cols = {
                        {1.00F, 0.50F, 0.13F}, {1.00F, 0.58F, 0.18F}, {1.00F, 0.70F, 0.28F},
                        {1.00F, 0.86F, 0.55F}, {1.00F, 0.96F, 0.82F}};
                final float[] alphas = {
                        (float) (0.055D + 0.012D * Math.sin(t * 2.1D)),
                        (float) (0.075D + 0.015D * Math.sin(t * 2.1D + 0.35D)),
                        (float) (0.105D + 0.020D * Math.sin(t * 2.1D + 0.7D)),
                        (float) (0.150D + 0.025D * Math.sin(t * 2.1D + 1.05D)),
                        (float) (0.230D + 0.030D * Math.sin(t * 2.1D + 1.4D))};
                for (int k = 0; k < widths.length; k++) {
                    final double w = widths[k];
                    final double nx = -dy;
                    final double ny = dx;
                    out.add(new Quad(new double[]{
                            px0 + dx * halfLen + nx * w, py0 + dy * halfLen + ny * w,
                            px0 - dx * halfLen + nx * w, py0 - dy * halfLen + ny * w,
                            px0 - dx * halfLen - nx * w, py0 - dy * halfLen - ny * w,
                            px0 + dx * halfLen - nx * w, py0 + dy * halfLen - ny * w},
                            cols[k][0], cols[k][1], cols[k][2], alphas[k], true));
                }
            }
        }

        // ---- ④ 中心微光 ----
        final int gseg = 28;
        final double glowR = 0.085D + 0.015D * Math.sin(t * 1.3D);
        for (int i = 0; i < gseg; i++) {
            final double a0 = Math.PI * 2.0D * i / gseg;
            final double a1 = Math.PI * 2.0D * (i + 1) / gseg;
            out.add(new Quad(new double[]{
                    0.0D, 0.0D,
                    glowR * Math.cos(a1), glowR * Math.sin(a1),
                    glowR * Math.cos(a0), glowR * Math.sin(a0),
                    0.0D, 0.0D},
                    1.00F, 0.86F, 0.66F, 0.20F, true));
        }
        return out;
    }

    /** 盘带是否会出现（{@code diskNormalCam} 与视线夹角太小 ⇒ 交线不存在）。 */
    public static boolean hasDiskBand(float[] diskNormalCam) {
        if (diskNormalCam == null) {
            return false;
        }
        return Math.hypot(0.70D * diskNormalCam[0], 0.70D * diskNormalCam[1]) > 0.05D;
    }

    private static double clamp01(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
    }
}
