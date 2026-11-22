package com.shanhai.client.render.hostfx;

import java.util.ArrayList;
import java.util.List;

/**
 * 归墟视界枢核 · <b>四件移植里"能离线跑"的那一半几何核</b>（2026-11-22 新增）。
 *
 * <h2>本类放什么、不画什么</h2>
 * 只做<b>纯数学 → 四边形</b>，一个 GL/RenderType 调用都没有（真正的提交在 {@code HostFxExtraLayers}
 * 与 {@code HostFxHoleRimWaves}）。这样"公式对不对"可以脱机判，不必进游戏：
 * <ul>
 *   <li>① 外缘吸积流：{@link #diskVertices} —— 环带网格 + {@link GuixuAccretionFlow#shade} 逐顶点上色；</li>
 *   <li>② 黑洞白边波浪：{@link #rimArcs} —— {@link BlackHoleSurfaceWaves#crestRadius} 解出的波峰弧；</li>
 *   <li>③ 星辰场：{@link #starQuads} —— {@link GuixuStarField} 六组采样 → 公告板四边形；</li>
 *   <li>④ 结构闪电的四边形在 {@link GuixuAccretionLightning#frame} 里（同一个"纯"口径）。</li>
 * </ul>
 *
 * <h2>三个"我们的取值"（他的原文没有，必须写出来）</h2>
 * <table border="1">
 *   <tr><th>项</th><th>取值</th><th>为什么</th></tr>
 *   <tr><td>吸积流网格</td><td>{@link #DISK_ANGULAR}=160 × {@link #DISK_RINGS}=24 = <b>3840</b> quads</td>
 *       <td>他的 CPU 回落按"每像素归一化径向宽度"过滤（{@code footprint}）。我们一个四边形 = 1/24 的
 *           径向跨度 ⇒ {@code footprint} 取 <b>1/24</b>（= 自己网格的 Nyquist 宽度），
 *           24 环刚好能分辨他 {@code 22} 频率的流纹（4 采样/周期）；再密就只是白烧顶点。</td></tr>
 *   <tr><td>星辰颜色</td><td>暖 {0.90,0.68,0.40} / 冷 {0.45,0.62,0.92}</td>
 *       <td>{@code GuixuStarField.Sample} 只给一个 {@code warm} 布尔，<b>没给 RGB</b>
 *           ⇒ 这两组是我们按"暖白/冷蓝"取的，进游戏可改。</td></tr>
 *   <tr><td>白边波浪的条宽</td><td>半宽 {@link #RIM_HALF_RHO}=0.012（归一化半径）＋ 3 倍宽的淡辉</td>
 *       <td>他的 shader 用 {@code fwidth} 逐像素抗锯齿，波峰细到 0.0006（亚像素）；
 *           CPU 没有逐像素 AA ⇒ 取 0.012 才在屏幕上读得出线（盘半径 4.3 格时约 0.05 格宽）。
 *           离线出图 {@code temp/migrate-fx/preview/preview-rim-waves.png} 就是按这个值看的。</td></tr>
 * </table>
 *
 * <h2>★ 预算读数（每帧）</h2>
 * <pre>
 *   ① 吸积流   3840 quads / 15360 顶点（固定）
 *   ② 白边波浪 ≤ {@link #MAX_RIM_QUADS} = 3072（实测 ~15 波 × 72~96 段 × 2 条 = 2160~2880）
 *   ③ 星辰场   ≤ {@link #STAR_MAX_QUADS} = 1256（= 192+512+64+8+384+96，他的常量总和）
 *   ④ 结构闪电 ≤ 12 条 × 6 段 × 2 带 = 144 quads
 * </pre>
 *
 * <h2>零 Minecraft 依赖（除 {@link HostFxGeometry} 那份数据载体）</h2>
 * 只 import {@code java.util} + {@link HostFxGeometry}（它只用到 {@code BlockPos}/{@code Direction}
 * 两个纯数据类型，离线探针给两份 30 行桩即可）⇒ 见 {@code temp/migrate-fx/probe/}。
 */
public final class HostFxExtraGeom {

    private HostFxExtraGeom() {
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ① 外缘吸积流（他的 GuixuAccretionFlow 的 CPU 网格化）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 角向分段（一圈几段）。 */
    public static final int DISK_ANGULAR = 160;
    /** 径向分段（51→57 格切几圈）。 */
    public static final int DISK_RINGS = 24;
    /** 每个顶点 7 个 float：x,y,z,r,g,b,a。 */
    public static final int DISK_VERTEX_STRIDE = 7;
    /** 一帧的四边形数（固定）。 */
    public static final int DISK_QUAD_COUNT = DISK_ANGULAR * DISK_RINGS;
    /** 逐顶点上色的复用缓冲（渲染线程独占；离线探针各用各的进程）。 */
    private static final float[] DISK_BUFFER =
            new float[DISK_QUAD_COUNT * 4 * DISK_VERTEX_STRIDE];

    /**
     * 把一帧的外缘吸积流写进复用缓冲（<b>环心本地坐标</b>，4 顶点/四边形，顶点带自己的颜色）。
     *
     * <p>footprint = {@code 1/DISK_RINGS}：这是我们自己网格的径向分辨率，等价于他"低于像素宽度时滤掉细节"
     * 那条回落纪律（见类注释）。
     *
     * @param seconds         世界时间（秒）—— 他的公式里到处用它（湍流 24 秒双相位、开普勒剪切）
     * @param camX,camY,camZ  相机在<b>环心本地</b>帧里的位置（多普勒增亮要用）
     * @return 写进去的 <b>float 数</b>（= {@link #DISK_QUAD_COUNT} × 4 顶点 × {@link #DISK_VERTEX_STRIDE}）
     */
    public static int diskVertices(float seconds, float camX, float camY, float camZ) {
        final float footprint = 1.0F / DISK_RINGS;
        final float[] rgba = new float[4];
        final double step = Math.PI * 2.0D / DISK_ANGULAR;
        int out = 0;
        for (int ring = 0; ring < DISK_RINGS; ring++) {
            final float s0 = (float) ring / DISK_RINGS;
            final float s1 = (float) (ring + 1) / DISK_RINGS;
            final float r0 = GuixuAccretionFlow.INNER_RADIUS
                    + (GuixuAccretionFlow.OUTER_RADIUS - GuixuAccretionFlow.INNER_RADIUS) * s0;
            final float r1 = GuixuAccretionFlow.INNER_RADIUS
                    + (GuixuAccretionFlow.OUTER_RADIUS - GuixuAccretionFlow.INNER_RADIUS) * s1;
            for (int seg = 0; seg < DISK_ANGULAR; seg++) {
                final float a0 = (float) (step * seg);
                final float a1 = (float) (step * (seg + 1));
                // 逆时针一圈：内 0 / 内 1 / 外 1 / 外 0（两个三角的正反都不重要 —— 加性层关剔除）
                out = diskVertex(out, r0, s0, a0, seconds, footprint, camX, camY, camZ, rgba);
                out = diskVertex(out, r1, s1, a0, seconds, footprint, camX, camY, camZ, rgba);
                out = diskVertex(out, r1, s1, a1, seconds, footprint, camX, camY, camZ, rgba);
                out = diskVertex(out, r0, s0, a1, seconds, footprint, camX, camY, camZ, rgba);
            }
        }
        return out;
    }

    private static int diskVertex(int out, float radius, float s, float angle, float seconds,
                                  float footprint, float camX, float camY, float camZ, float[] rgba) {
        GuixuAccretionFlow.shade(radius, angle, seconds, footprint, camX, camY, camZ, rgba);
        final float y = GuixuAccretionFlow.height(s, angle, seconds);
        DISK_BUFFER[out] = (float) (radius * Math.cos(angle));
        DISK_BUFFER[out + 1] = y;
        DISK_BUFFER[out + 2] = (float) (radius * Math.sin(angle));
        DISK_BUFFER[out + 3] = rgba[0];
        DISK_BUFFER[out + 4] = rgba[1];
        DISK_BUFFER[out + 5] = rgba[2];
        DISK_BUFFER[out + 6] = rgba[3];
        return out + DISK_VERTEX_STRIDE;
    }

    /** 复用缓冲（只读；{@link #diskVertices} 的产出就在这里）。 */
    public static float[] diskBuffer() {
        return DISK_BUFFER;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ② 黑洞边缘白边波浪（他的 BlackHoleSurfaceWaves 的 CPU 弧线化）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 波峰条带半宽的<b>下限</b>（归一化盘半径）—— 我们的取值。
     *
     * <h2>2026-11-22：为什么是 0.016（不是 0.012）</h2>
     * 屏幕厚度换算（逐项，{@code surfaceR} = 黑盘半径 5.196 格、{@code pxRho} = 每像素跨过的归一化盘半径）：
     * <pre>
     *   每像素格数 bp = 2 · 距离 · tan(fov/2) / 视口高度           （fov 70° ⇒ tan35°=0.7002）
     *   pxRho         = bp / surfaceR
     *   屏幕厚度 px   = 2 · half · surfaceR / bp = 2 · half / pxRho
     * </pre>
     * 旧值 0.012 在 1080p / fov70 下：距离 20 格 ⇒ 3.0 px；40 格 ⇒ 1.5 px；60 格 ⇒ 1.0 px；
     * 80 格 ⇒ 0.75 px（亚像素 ⇒ 会变成点虚线）。⇒ 抬到 <b>0.016</b>，并把像素地板抬到 <b>1.6 px/侧</b>：
     * <pre>
     *   厚度 px = 2 · clamp(1.6 · pxRho, 0.016, 0.09) / pxRho ≥ 2 × 1.6 = <b>3.2 px</b>（任何距离）
     *   距离 20 格 ⇒ 3.0→4.0 px   40 格 ⇒ 1.5→3.2 px   60 格 ⇒ 1.0→3.2 px   80 格 ⇒ 0.75→3.2 px
     * </pre>
     * 依据：他的 shader 里 {@code waveWidth = max(0.075, fwidth(wavePhase) * 0.55)} ——
     * 那个 {@code max} 就是"宽度不许低于像素尺度"的地板，我们做的是同一个东西（只加粗、不改亮度）。
     */
    public static final float RIM_HALF_RHO = 0.016F;
    /** 每侧至少覆盖几个像素（像素地板；2×1.6 ⇒ 任何距离下屏幕厚度 ≥ 3.2 px）。 */
    public static final float RIM_MIN_PIXELS = 1.6F;
    /** 屏幕厚度的<b>断言下限</b>（px）；{@code stats()} 与自检都按这条判。 */
    public static final double RIM_MIN_THICKNESS_PX = 2.0D * RIM_MIN_PIXELS;
    /** 半宽上限（防"很远还在加粗"）；到 detail=0 时按像素地板最多 1.6×0.055≈0.088。 */
    public static final float RIM_HALF_RHO_MAX = 0.09F;
    /** 上一帧实际用的半宽（归一化盘半径；进 draw 日志与离线读数）。 */
    private static double rimHalfWidthRho = RIM_HALF_RHO;
    /** 上一帧的"每像素归一化盘半径"（{@code rimArcs} 的入参，进日志用）。 */
    private static double rimLastPixelRho = 0.02D;
    /** 外面那圈淡辉的半宽倍数。 */
    public static final float RIM_GLOW_SCALE = 3.0F;
    /** 外面那圈淡辉的相对亮度。 */
    public static final float RIM_GLOW_ALPHA = 0.30F;
    /** 他的合成增益（fsh:335 的 {@code 0.80}）。 */
    public static final float RIM_GAIN = 0.80F;
    /** 角向分段下限（细节掉到 0 时用它）。 */
    public static final int RIM_ANGULAR_MIN = 48;
    /** 角向分段上限（满细节时用它）。 */
    public static final int RIM_ANGULAR_MAX = 96;
    /** 一帧白边波浪的四边形上限（护栏；超过就本帧截断）。 */
    public static final int MAX_RIM_QUADS = 3072;
    /** 波峰序号搜索范围（够覆盖 0.60..0.98 那段；多出来的被包络丢掉）。 */
    private static final int RIM_WAVE_MIN = -4;
    private static final int RIM_WAVE_MAX = 20;

    /**
     * 一帧的白边波浪弧线（<b>世界坐标</b>；调用方在 {@code AFTER_LEVEL} 用相机帧提交）。
     *
     * @param seconds         世界时间（秒）
     * @param pixelWidthRho   每像素跨过的归一化盘半径（= 每像素格数 ÷ 盘半径）；{@link BlackHoleSurfaceWaves#detail}
     * @param surfaceR        黑洞可见黑盘半径（格）—— {@code surfaceRho = 1.0} 就是它
     * @param cx,cy,cz        黑洞中心的世界坐标
     * @param camX,camY,camZ  相机的世界坐标（决定公告板朝向与"哪一条边朝外"）
     */
    public static List<HostFxGeometry.Quad> rimArcs(double seconds, double pixelWidthRho, float surfaceR,
                                                    double cx, double cy, double cz,
                                                    double camX, double camY, double camZ) {
        final double detail = BlackHoleSurfaceWaves.detail(pixelWidthRho);
        if (!(surfaceR > 0.0F) || detail < 0.02D) {
            return List.of();
        }
        // 公告板基：dir = 洞心 → 相机
        double dx = camX - cx, dy = camY - cy, dz = camZ - cz;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1.0e-4D)) {
            return List.of();
        }
        dx /= len;
        dy /= len;
        dz /= len;
        double rx = -dz, rz = dx;                     // cross((0,1,0), dir) 的 xz 分量
        final double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl > 1.0e-4D) {
            rx /= rl;
            rz /= rl;
        } else {
            rx = 1.0D;                                // 正对天顶：任取一条水平轴
            rz = 0.0D;
        }
        final double ux = dy * rz - dz * 0.0D;        // up = cross(dir, right)
        final double uy = dz * rx - dx * rz;
        final double uz = dx * 0.0D - dy * rx;
        final int angular = Math.max(8, Math.round(
                (RIM_ANGULAR_MIN + (RIM_ANGULAR_MAX - RIM_ANGULAR_MIN) * (float) detail) / 8.0F) * 8);
        // 🔴 像素地板：他 shader 的 waveWidth = max(0.075, fwidth*0.55) 在 CPU 这一侧的等价物
        //    （见 RIM_MIN_PIXELS 的注释；只加粗、不改亮度）
        final double half = Math.min(RIM_HALF_RHO_MAX,
                Math.max(RIM_HALF_RHO, RIM_MIN_PIXELS * pixelWidthRho));
        rimHalfWidthRho = half;
        rimLastPixelRho = pixelWidthRho;
        final List<HostFxGeometry.Quad> out = new ArrayList<>(512);
        final double step = Math.PI * 2.0D / angular;
        for (int wave = RIM_WAVE_MIN; wave <= RIM_WAVE_MAX; wave++) {
            for (int seg = 0; seg < angular; seg++) {
                if (out.size() >= MAX_RIM_QUADS) {
                    return out;
                }
                // 🔴 相邻段首尾相接：本段的 a1 就是下一段的 a0，且 rho/wobble 都由同一个 a 算出
                //    ⇒ 共享顶点的 12 个 float 逐位相同 ⇒ GPU 上是一条连续带（不是"段与段之间留缝"）。
                final double a0 = step * seg;
                final double a1 = step * (seg + 1);
                final double rho0 = BlackHoleSurfaceWaves.crestRadius(wave, a0, seconds);
                final double rho1 = BlackHoleSurfaceWaves.crestRadius(wave, a1, seconds);
                final double env = 0.5D * (BlackHoleSurfaceWaves.envelope(rho0)
                        + BlackHoleSurfaceWaves.envelope(rho1));
                if (env < 0.02D) {
                    continue;
                }
                // 他的黑面半径本身在抖（0.012/0.006 两阶），弧线因此也跟着抖
                final double wob0 = BlackHoleSurfaceWaves.radiusScale(a0, seconds, detail);
                final double wob1 = BlackHoleSurfaceWaves.radiusScale(a1, seconds, detail);
                final double base0 = surfaceR * wob0;
                final double base1 = surfaceR * wob1;
                final float alpha = (float) (RIM_GAIN * env * detail);
                addArc(out, cx, cy, cz, rx, 0.0D, rz, ux, uy, uz,
                        a0, a1, base0 * (rho0 - half), base1 * (rho1 - half),
                        base0 * (rho0 + half), base1 * (rho1 + half), alpha);
                if (out.size() < MAX_RIM_QUADS) {
                    addArc(out, cx, cy, cz, rx, 0.0D, rz, ux, uy, uz,
                            a0, a1,
                            base0 * (rho0 - half * RIM_GLOW_SCALE),
                            base1 * (rho1 - half * RIM_GLOW_SCALE),
                            base0 * (rho0 + half * RIM_GLOW_SCALE),
                            base1 * (rho1 + half * RIM_GLOW_SCALE),
                            alpha * RIM_GLOW_ALPHA);
                }
            }
        }
        return out;
    }

    /** 上一帧实际用的波峰半宽（归一化盘半径）—— 日志/离线读数用。 */
    public static double rimHalfWidthRho() {
        return rimHalfWidthRho;
    }

    /** 上一帧的"每像素归一化盘半径"（{@code rimArcs} 的入参）。 */
    public static double rimLastPixelRho() {
        return rimLastPixelRho;
    }

    /**
     * 上一帧波峰条带在屏幕上的<b>厚度（像素）</b>：{@code 2 × half / pxRho}。
     *
     * <p>这是"人眼能不能看见"的唯一几何判据（提交了多少 quads 说明不了这件事）。
     */
    public static double rimThicknessPx() {
        return rimLastPixelRho > 0.0D ? 2.0D * rimHalfWidthRho / rimLastPixelRho : 0.0D;
    }

    /** 一段弧带（内半径 rI / 外半径 rO 两条同心弧之间），{@code right}/{@code up} 是公告板基。 */
    private static void addArc(List<HostFxGeometry.Quad> out,
                               double cx, double cy, double cz,
                               double rightX, double rightY, double rightZ,
                               double upX, double upY, double upZ,
                               double a0, double a1,
                               double rI0, double rI1, double rO0, double rO1, float alpha) {
        final double c0 = Math.cos(a0), s0 = Math.sin(a0);
        final double c1 = Math.cos(a1), s1 = Math.sin(a1);
        out.add(new HostFxGeometry.Quad(new float[]{
                (float) (cx + rightX * c0 * rI0 + upX * s0 * rI0),
                (float) (cy + rightY * c0 * rI0 + upY * s0 * rI0),
                (float) (cz + rightZ * c0 * rI0 + upZ * s0 * rI0),
                (float) (cx + rightX * c1 * rI1 + upX * s1 * rI1),
                (float) (cy + rightY * c1 * rI1 + upY * s1 * rI1),
                (float) (cz + rightZ * c1 * rI1 + upZ * s1 * rI1),
                (float) (cx + rightX * c1 * rO1 + upX * s1 * rO1),
                (float) (cy + rightY * c1 * rO1 + upY * s1 * rO1),
                (float) (cz + rightZ * c1 * rO1 + upZ * s1 * rO1),
                (float) (cx + rightX * c0 * rO0 + upX * s0 * rO0),
                (float) (cy + rightY * c0 * rO0 + upY * s0 * rO0),
                (float) (cz + rightZ * c0 * rO0 + upZ * s0 * rO0),
        }, 1.0F, 1.0F, 1.0F, alpha));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  ③ 星辰场（他的 GuixuStarField 六组采样 → 公告板四边形）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 星辰场一帧的四边形上限 = 他六组常量的总和。 */
    public static final int STAR_MAX_QUADS = GuixuStarField.PARTICLE_COUNT + GuixuStarField.SKY_COUNT
            + GuixuStarField.ORBIT_COUNT + GuixuStarField.METEOR_COUNT
            + GuixuStarField.VOLUME_COUNT + GuixuStarField.DUST_COUNT;
    /** 暖星颜色（我方取值，见类注释）。 */
    public static final float[] STAR_WARM_RGB = {0.90F, 0.68F, 0.40F};
    /** 冷星颜色（我方取值）。 */
    public static final float[] STAR_COOL_RGB = {0.45F, 0.62F, 0.92F};
    /** 星点最小世界尺寸（格）—— 小于它时屏幕上看不见，统一抬到它。 */
    public static final float STAR_MIN_SIZE = 0.045F;

    /**
     * 一帧的星辰场四边形（<b>环心本地坐标</b>，公告板朝向相机）。
     *
     * @param seconds      世界时间（秒）—— 全部采样只读它
     * @param surfaceR     黑洞表面半径（格，{@link GuixuStarField#hiddenByShadow} 的 horizon）
     * @param camX/Y/Z     相机在环心本地帧里的位置
     * @param density      0..1（{@link GuixuStarField#skyCount}/{@code volumeCount}/{@code dustCount} 的密度）
     * @param degraded     降级态（远距/低配）：背景星 96、体积星 64、星尘 0
     */
    public static List<HostFxGeometry.Quad> starQuads(double seconds, float surfaceR,
                                                      float camX, float camY, float camZ,
                                                      float density, boolean degraded) {
        final float[][] basis = HostFxGeometry.billboardBasis(camX, camY, camZ);
        if (basis == null) {
            return List.of();
        }
        final float[] camera = {camX, camY, camZ};
        final double horizon = surfaceR;
        final List<HostFxGeometry.Quad> out = new ArrayList<>(STAR_MAX_QUADS);
        // ① 吸入星：192 颗，不做阴影剔除（它们本来就是往洞里掉的）
        for (int i = 0; i < GuixuStarField.PARTICLE_COUNT && out.size() < STAR_MAX_QUADS; i++) {
            addStar(out, GuixuStarField.sample(i, seconds, surfaceR), basis);
        }
        // ② 背景星：256..512（降级 96）
        final int sky = Math.min(GuixuStarField.SKY_COUNT, GuixuStarField.skyCount(density, degraded));
        for (int i = 0; i < sky && out.size() < STAR_MAX_QUADS; i++) {
            final GuixuStarField.Sample s = GuixuStarField.sampleSky(i, seconds);
            if (!GuixuStarField.hiddenByShadow(camera, s, horizon)) {
                addStar(out, s, basis);
            }
        }
        // ③ 轨道尘：64 颗
        for (int i = 0; i < GuixuStarField.ORBIT_COUNT && out.size() < STAR_MAX_QUADS; i++) {
            final GuixuStarField.Sample s = GuixuStarField.sampleOrbit(i, seconds);
            if (!GuixuStarField.hiddenByShadow(camera, s, horizon)) {
                addStar(out, s, basis);
            }
        }
        // ④ 流星：8 条，周期外他的 INACTIVE 直接 alpha=0
        for (int i = 0; i < GuixuStarField.METEOR_COUNT && out.size() < STAR_MAX_QUADS; i++) {
            final GuixuStarField.Sample s = GuixuStarField.sampleMeteor(i, seconds);
            if (s.visible()) {
                addStar(out, s, basis);
            }
        }
        // ⑤ 体积星：192..384（降级 64）—— 只在黑洞近旁，且必须躲开黑洞阴影
        final int volume = Math.min(GuixuStarField.VOLUME_COUNT,
                GuixuStarField.volumeCount(density, degraded));
        for (int i = 0; i < volume && out.size() < STAR_MAX_QUADS; i++) {
            final GuixuStarField.Sample s = GuixuStarField.sampleVolume(i, seconds, horizon);
            if (!GuixuStarField.hiddenByShadow(camera, s, horizon)) {
                addStar(out, s, basis);
            }
        }
        // ⑥ 星尘：48..96（降级 0）
        final int dust = Math.min(GuixuStarField.DUST_COUNT,
                GuixuStarField.dustCount(density, degraded));
        for (int i = 0; i < dust && out.size() < STAR_MAX_QUADS; i++) {
            final GuixuStarField.Sample s = GuixuStarField.sampleDust(i, seconds, horizon);
            if (!GuixuStarField.hiddenByShadow(camera, s, horizon)) {
                addStar(out, s, basis);
            }
        }
        return out;
    }

    /** 一颗星 → 一个公告板四边形（head→tail 有长度就画成细带，等长就画成小方点）。 */
    private static void addStar(List<HostFxGeometry.Quad> out, GuixuStarField.Sample s, float[][] basis) {
        if (!s.visible()) {
            return;
        }
        final float[] right = basis[0];
        final float[] up = basis[1];
        final float[] rgb = s.warm() ? STAR_WARM_RGB : STAR_COOL_RGB;
        final float size = Math.max(STAR_MIN_SIZE, s.size());
        final double dx = s.headX() - s.tailX();
        final double dy = s.headY() - s.tailY();
        final double dz = s.headZ() - s.tailZ();
        if (dx * dx + dy * dy + dz * dz < 1.0e-8D) {
            // 点状：以 head 为中心的小方点（边长 = 2 × size；四个角按 "角-角-角-角" 顺序，不是菱形）
            out.add(new HostFxGeometry.Quad(new float[]{
                    (float) s.headX() - right[0] * size - up[0] * size,
                    (float) s.headY() - right[1] * size - up[1] * size,
                    (float) s.headZ() - right[2] * size - up[2] * size,
                    (float) s.headX() + right[0] * size - up[0] * size,
                    (float) s.headY() + right[1] * size - up[1] * size,
                    (float) s.headZ() + right[2] * size - up[2] * size,
                    (float) s.headX() + right[0] * size + up[0] * size,
                    (float) s.headY() + right[1] * size + up[1] * size,
                    (float) s.headZ() + right[2] * size + up[2] * size,
                    (float) s.headX() - right[0] * size + up[0] * size,
                    (float) s.headY() - right[1] * size + up[1] * size,
                    (float) s.headZ() - right[2] * size + up[2] * size,
            }, rgb[0], rgb[1], rgb[2], s.alpha()));
            return;
        }
        // 拖尾：head→tail 一条细带（宽度 = size，沿 right 展开）
        out.add(new HostFxGeometry.Quad(new float[]{
                (float) s.tailX() - right[0] * size, (float) s.tailY() - right[1] * size,
                (float) s.tailZ() - right[2] * size,
                (float) s.tailX() + right[0] * size, (float) s.tailY() + right[1] * size,
                (float) s.tailZ() + right[2] * size,
                (float) s.headX() + right[0] * size, (float) s.headY() + right[1] * size,
                (float) s.headZ() + right[2] * size,
                (float) s.headX() - right[0] * size, (float) s.headY() - right[1] * size,
                (float) s.headZ() - right[2] * size,
        }, rgb[0], rgb[1], rgb[2], s.alpha()));
    }
}
