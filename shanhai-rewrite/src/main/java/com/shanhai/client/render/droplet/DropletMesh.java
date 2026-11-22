package com.shanhai.client.render.droplet;

/**
 * 水滴的<b>几何烘焙</b>：本体网格（一次性预烘）+ 推进环 / 尾迹的逐帧顶点流。
 *
 * <h2>一、坐标系（全文最重要的一条）</h2>
 * 所有顶点都在<b>本体坐标系</b>里，轴是 <b>局部 +Z = 运动方向（头）</b>：
 * <ul>
 *   <li>{@code +z} ⇒ 头（前），{@code -z} ⇒ 尾；</li>
 *   <li>{@code x = r·cosθ}（右）、{@code y = r·sinθ}（上），{@code r = DropletProfile.radiusAtX(z)}；</li>
 *   <li>{@code |z ≤ 1.5|}、{@code r ≤ 0.75} ⇒ 包围盒 <b>1.5 × 1.5 × 3.0</b>，中心在原点，
 *       与 {@code ShanhaiDropletEntity#makeBoundingBox()}（{@code ±0.75·right ±0.75·up ±1.5·forward}）
 *       <b>逐项一致</b>。</li>
 * </ul>
 * 轴向为什么放 +Z 而不是 v3 的 +X：实体的 {@code forward = 局部 +Z}（{@code makeBoundingBox} 的原话），
 * 放 +Z 之后渲染器只需要做一次"基变换"（right/up/forward），<b>不需要</b>任何欧拉角拼装
 * —— 拼装顺序/符号搞错是本工程历史上最容易出事的地方（参见 {@code makeBoundingBox} 的推导）。
 *
 * <h2>二、为什么"预烘"</h2>
 * 本体网格只与轮廓参数有关，与实体姿态<b>无关</b> ⇒ 类加载时烘一次（{@link #BODY}），
 * 之后每帧只做"基变换 + 着色"，<b>不再生成网格</b>。
 * 逐帧变化的两件（推进环、尾迹）写进<b>复用的 float 数组</b>（{@link #RING_SCRATCH} /
 * {@link #TRAIL_SCRATCH}），<b>每帧零 new</b>。
 *
 * <h2>三、顶点流格式</h2>
 * {@code STREAM_STRIDE = 7}：{@code x,y,z, r,g,b,a}。环与尾迹是<b>自发光</b>（加性混合，
 * 不参与 {@link DropletLook} 的环境着色）⇒ 颜色在烘焙时就定死。
 *
 * <h2>四、🔴 2026-11-24 尾迹/推进环改成"世界空间"</h2>
 * 用户原话：「<b>轨迹和推进环我希望是那种真正的轨迹的意思，就是已经走过的路的轨迹不会变了，
 * 不要水滴转向轨迹跟着转向，这很离谱</b>」。
 * <ul>
 *   <li>尾迹：不再由"尾尖 + 当前朝向"推出来，而是由 {@code ShanhaiDropletTrail} 每 client tick
 *       采样的<b>世界坐标路点</b>连成两条朝向相机的薄带（{@link #buildTrail}）；</li>
 *   <li>推进环：发射那一刻的圆心与平面基被存下来（{@link #ringInto} 只吃这份定好的位姿）。</li>
 * </ul>
 * 这两条都只保证"写进世界空间后不再被旋转"，几何本身仍然是顶点色、无贴图。
 *
 * <p>本类零 Minecraft 依赖（探针可不带游戏 jar 直接跑）。
 */
public final class DropletMesh {

    private DropletMesh() {}

    // ------------------------------------------------------------------ 分辨率（= 性能预算的来源）

    /** 本体轴向分段（48 ⇒ 与最大宽度所在环正好对齐：{@code 48·(0.75/3)·2 = 24}）。 */
    public static final int BODY_RINGS = 48;
    /** 本体周向分段（必须能被 4 整除：极值点正好落在网格顶点上 ⇒ 包围盒精确）。 */
    public static final int BODY_SEGS = 24;
    /**
     * 高亮外壳（低模：只用来看轮廓，不需要密）。
     *
     * <p>两条整除约束（都不是审美，是为了<b>包围盒精确</b>）：
     * 周向能被 4 整除 ⇒ 极值点落在顶点上；轴向能被 3 整除 ⇒ 取样参数 {@code s=2/3}
     * 正好落在最大宽度接缝上（见 {@link #axisAt}）。
     */
    public static final int SHELL_RINGS = 27;
    public static final int SHELL_SEGS = 16;
    /** 外壳放大系数（1.03 ⇒ 外扩 0.0225 格，比像素尺度略大，保证透过方块也看得见一圈）。 */
    public static final float SHELL_SCALE = 1.03F;

    /** 顶点流步长：pos3 + rgba4。 */
    public static final int STREAM_STRIDE = 7;

    // ------------------------------------------------------------------ 烘焙结果载体

    /** 一份预烘网格（只读；顶点共享 ⇒ 平滑着色）。 */
    public static final class Baked {
        /** 顶点位置：3 float/顶点（本体坐标系）。 */
        public final float[] pos;
        /** 顶点法线：3 float/顶点（单位向量，本体坐标系）。 */
        public final float[] nrm;
        /** 四边形索引：4 int/四边形（逆时针 = 朝外，与 {@code cull BACK} 配套）。 */
        public final int[] quad;
        public final int rings;
        public final int segs;

        Baked(int rings, int segs) {
            this.rings = rings;
            this.segs = segs;
            final int verts = (rings + 1) * segs;
            this.pos = new float[verts * 3];
            this.nrm = new float[verts * 3];
            this.quad = new int[rings * segs * 4];
        }

        public int vertexCount() {
            return (this.rings + 1) * this.segs;
        }

        public int quadCount() {
            return this.rings * this.segs;
        }

        public int triangleCount() {
            return quadCount() * 2;
        }

        /** 包围盒：{@code [minX,minY,minZ, maxX,maxY,maxZ]}（本体坐标系）。 */
        public float[] bbox() {
            final float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                    -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < this.pos.length; i += 3) {
                for (int a = 0; a < 3; a++) {
                    final float v = this.pos[i + a];
                    if (v < out[a]) {
                        out[a] = v;
                    }
                    if (v > out[3 + a]) {
                        out[3 + a] = v;
                    }
                }
            }
            return out;
        }
    }

    /**
     * 沿轴取 {@code n} 个截面的<b>直径</b>（= 2r），用来验证"沿轴单调、只有一个极大"。
     *
     * <p>取样点从尾端（{@code z=−len/2}）到头端，<b>不含</b>两端点（那里 r 恒为 0，
     * 会把"单调"判据污染成"先增后减再归零"）。返回长度 = {@code n}，
     * 第 i 个点的归一化轴向位置（<b>从尾端量</b>）{@code t = (i+0.5)/n}。
     */
    public static double[] sectionDiameters(DropletProfile p, int n) {
        final double[] out = new double[n];
        final double half = p.halfLen();
        for (int i = 0; i < n; i++) {
            final double u = (i + 0.5D) / n;              // 0..1，0 = 尾端
            final double axis = -half + 2.0D * half * u;  // 局部 z
            out[i] = 2.0D * p.radiusAtX(axis);
        }
        return out;
    }

    /**
     * 一棵网格第 {@code ring} 个环的轴向位置（本体坐标系 z）。
     *
     * <p>🔴 <b>为什么不是均匀取样</b>：轮廓函数 {@code R(x)} 一个字节没改（还是 v3 那条解析曲线），
     * 变的只是<b>在哪里取点</b>。头端是半球，极点的半径变化率无穷大 ⇒ 均匀取 48 环时
     * 最后一环 {@code r=0.30} 直接跳到极点 {@code r=0}（3 像素内掉 0.3）⇒ 侧看会像被"削平"的圆盘
     * （2026-11-23 预览里一眼就是这个形状）。尾部收尖同理。
     * <p>所以取样参数用余弦：{@code u=(1−cos(πs))/2} ⇒ <b>两端密、中段疏</b>
     * （中段是准圆柱，曲率最低，本来就不需要密）。{@code rings=48} 时
     * {@code s=2/3} 正好落在 {@code u=0.75}（= 最大宽度接缝 {@code x=XJ}）⇒
     * <b>最大宽度仍是一个顶点</b>，包围盒依旧是精确的 1.5 × 1.5 × 3.0。
     */
    public static double axisAt(DropletProfile p, int ring, int rings) {
        final double s = (double) ring / rings;
        final double u = 0.5D * (1.0D - Math.cos(Math.PI * s));
        return -p.halfLen() + 2.0D * p.halfLen() * u;
    }

    // ------------------------------------------------------------------ 烘焙：本体 / 外壳

    /** 游戏内用的本体网格（类加载时烘一次）。 */
    public static final Baked BODY = bakeBody(DropletProfile.V3, BODY_RINGS, BODY_SEGS, 1.0F);

    /**
     * 低模外壳（同一份轮廓放大 {@link #SHELL_SCALE}）——<b>2026-11-24 起暂时没有使用者</b>。
     *
     * <p>原本给"穿墙高亮外壳两遍"用；用户改口径（「改成蓝色的框，就和驾驶的框一样」）之后，
     * 高亮走 {@link DropletBoxFx} 的 12 条棱，本网格就没在画了。保留是因为：
     * ① 烘一次只占约 40 KB；② 以后若真要做"逐像素法线"的描边（着色器）方案，这份网格是起点。
     * <b>不许把它当成"还在用"的东西</b>去查渲染路径。
     */
    public static final Baked SHELL = bakeBody(DropletProfile.V3, SHELL_RINGS, SHELL_SEGS, SHELL_SCALE);

    /**
     * 烘一份回转体网格。
     *
     * @param p     轮廓
     * @param rings 轴向分段
     * @param segs  周向分段
     * @param scale 整体缩放（本体 1.0，外壳 1.03）
     */
    public static Baked bakeBody(DropletProfile p, int rings, int segs, float scale) {
        if (rings < 1 || segs < 3) {
            throw new IllegalArgumentException("rings/segs 太小: " + rings + "/" + segs);
        }
        final Baked m = new Baked(rings, segs);
        for (int i = 0; i <= rings; i++) {
            final double axis = axisAt(p, i, rings);
            final double r = p.radiusAtX(axis) * scale;
            final double slope = p.slopeAtX(axis);      // dR/d(轴向)
            for (int j = 0; j < segs; j++) {
                final double th = 2.0D * Math.PI * j / segs;
                final double ca = Math.cos(th);
                final double sa = Math.sin(th);
                final int k = (i * segs + j) * 3;
                m.pos[k] = (float) (r * ca);
                m.pos[k + 1] = (float) (r * sa);
                m.pos[k + 2] = (float) (axis * scale);
                // 法线 ∝ (cosθ, sinθ, −dR/da)：由 ∂P/∂θ × ∂P/∂a 推出来（见类注释）
                double nx = ca;
                double ny = sa;
                double nz = -slope;
                final double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1e-9D) {
                    nx /= nl;
                    ny /= nl;
                    nz /= nl;
                } else {
                    nx = 0.0D;
                    ny = 0.0D;
                    nz = 1.0D;          // 退化点（极点）：法线朝轴向，等价于"最亮的那一圈"不出错
                }
                m.nrm[k] = (float) nx;
                m.nrm[k + 1] = (float) ny;
                m.nrm[k + 2] = (float) nz;
            }
        }
        int q = 0;
        for (int i = 0; i < rings; i++) {
            final int base = i * segs;
            final int baseNext = (i + 1) * segs;
            for (int j = 0; j < segs; j++) {
                final int j1 = (j + 1) % segs;
                // 逆时针（朝外）：先 +θ 再 +轴向 ⇒ 与 ∂θ×∂a 同向
                m.quad[q++] = base + j;
                m.quad[q++] = base + j1;
                m.quad[q++] = baseNext + j1;
                m.quad[q++] = baseNext + j;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ 逐帧：世界空间推进环

    /** 单个环的周向分段。 */
    public static final int RING_SEGS = 40;
    /** 同时存活的环数上限（1 秒 1 个 × 寿命 2 s ⇒ 2 个；留 1 个余量给"刚出生"那一帧）。 */
    public static final int RING_SLOTS = 3;
    /** 环的顶点流容量：2 遍 × 3 环 × 40 段 × 4 顶点。 */
    public static final int RING_CAPACITY = 2 * RING_SLOTS * RING_SEGS * 4;

    /** 环的顶点流复用缓冲（容量见 {@link #RING_CAPACITY}）。 */
    public static final float[] RING_SCRATCH = new float[RING_CAPACITY * STREAM_STRIDE];

    /**
     * 把<b>一个环</b>写进世界空间的顶点流（覆盖式：从 {@code n} 之后继续追加，返回新的 {@code n}）。
     *
     * <p>🔴 2026-11-24：环从"每帧按实体当前朝向现算"改成<b>发射那一刻的世界位姿固定下来</b>
     * ⇒ 这个函数只接受一份<b>已经定好的</b>圆心 + 平面基，不再读实体的 yaw/pitch。
     * 圆心/基由 {@code ShanhaiDropletTrail} 在发射时存下，之后水滴转头与它无关。
     *
     * @param right 环平面内的第一根基（世界，单位向量，{@code [0..2]}）
     * @param up    环平面内的第二根基（世界，单位向量，与 right 正交，{@code [0..2]}）
     * @param zBias 沿环法线的极小后移（避免与本体/其它环共面闪烁，格）
     */
    public static int ringInto(float[] out, int n,
                               double cx, double cy, double cz,
                               float[] right, float[] up,
                               double radius, double thickness,
                               float r, float g, float b, float a, double zBias,
                               float[] normal) {
        final double rIn = Math.max(0.0D, radius - thickness * 0.5D);
        final double rOut = radius + thickness * 0.5D;
        final double ox = cx + normal[0] * zBias;
        final double oy = cy + normal[1] * zBias;
        final double oz = cz + normal[2] * zBias;
        for (int j = 0; j < RING_SEGS; j++) {
            final double t0 = 2.0D * Math.PI * j / RING_SEGS;
            final double t1 = 2.0D * Math.PI * (j + 1) / RING_SEGS;
            final double c0 = Math.cos(t0);
            final double s0 = Math.sin(t0);
            final double c1 = Math.cos(t1);
            final double s1 = Math.sin(t1);
            // 一圈四顶点：内0 → 外0 → 外1 → 内1（双面画，绕序不敏感）
            n = putStream(out, n, ox + (right[0] * c0 + up[0] * s0) * rIn,
                    oy + (right[1] * c0 + up[1] * s0) * rIn,
                    oz + (right[2] * c0 + up[2] * s0) * rIn, r, g, b, a);
            n = putStream(out, n, ox + (right[0] * c0 + up[0] * s0) * rOut,
                    oy + (right[1] * c0 + up[1] * s0) * rOut,
                    oz + (right[2] * c0 + up[2] * s0) * rOut, r, g, b, a);
            n = putStream(out, n, ox + (right[0] * c1 + up[0] * s1) * rOut,
                    oy + (right[1] * c1 + up[1] * s1) * rOut,
                    oz + (right[2] * c1 + up[2] * s1) * rOut, r, g, b, a);
            n = putStream(out, n, ox + (right[0] * c1 + up[0] * s1) * rIn,
                    oy + (right[1] * c1 + up[1] * s1) * rIn,
                    oz + (right[2] * c1 + up[2] * s1) * rIn, r, g, b, a);
        }
        return n;
    }

    // ------------------------------------------------------------------ 逐帧：世界空间尾迹

    /** 一条尾迹最多几段（路点上限见 {@link DropletTrailBuffer#POINTS} ⇒ 47 段）。 */
    public static final int TRAIL_SEGS_MAX = DropletTrailBuffer.POINTS - 1;
    /** 尾迹的顶点流容量：2 层（芯 + 光晕）× 47 段 × 4 顶点。 */
    public static final int TRAIL_CAPACITY = 2 * TRAIL_SEGS_MAX * 4;

    /** 尾迹的顶点流复用缓冲（容量见 {@link #TRAIL_CAPACITY}）。 */
    public static final float[] TRAIL_SCRATCH = new float[TRAIL_CAPACITY * STREAM_STRIDE];

    /** 侧向量退化判据（{@code |tangent × toCam|} 小于它就换一根参考轴）。 */
    private static final double SIDE_EPS = 1.0E-4D;

    /** 每点侧向量暂存：3 double/点（最多 48 点）。 */
    private static final double[] SIDES = new double[3 * (TRAIL_SEGS_MAX + 1)];
    /** 每点切线暂存：3 double/点。 */
    private static final double[] TANGENTS = new double[3 * (TRAIL_SEGS_MAX + 1)];

    /**
     * 由<b>世界空间路点</b>生成两条<b>朝向相机的薄带</b>（芯 + 光晕）。
     *
     * <h4>为什么用"朝向相机的带"而不是管子</h4>
     * 判据只关心<b>屏幕上的宽度</b>（≥1.3 px，且沿轴恒定）。带子的侧向量取
     * {@code normalize(tangent × toCam)} ⇒ 它永远正对相机、宽度在世界单位里恒定
     * ⇒ 屏幕宽度的唯一变化来自透视，与"看哪一侧"无关。管子（已有实现）除了贵，
     * 在侧视时会把近/远两个壁的 alpha 叠加成两倍亮度，反而更难调。
     *
     * <h4>为什么"半径沿轴恒定"是这一版的核心</h4>
     * 老实现把衰减做成了"变粗"（0.045 → 0.62），实机看到的是扇形扩散。
     * 现在半径是常量（{@link DropletProfile#beamRadiusCore()}/{@link DropletProfile#beamRadiusHalo()} 两个常量），
     * 衰减<b>只</b>发生在 alpha 上。
     *
     * @param px/py/pz 路点世界坐标，<b>下标 0 = 最老</b>，{@code n-1 = 最新}（紧贴水滴）
     * @param cost     每点的归一化代价 {@code u∈[0,1]}（1 = 最老、该淡成 0）
     * @return 写入的顶点数
     */
    public static int buildTrail(float[] out,
                                 double[] px, double[] py, double[] pz, float[] cost, int n,
                                 double camX, double camY, double camZ) {
        if (n < 2 || n > TRAIL_SEGS_MAX + 1) {
            return 0;
        }
        // ① 每点的切线（中心差分；两端用单侧差分）
        for (int i = 0; i < n; i++) {
            final int a = i == 0 ? 0 : i - 1;
            final int b = i == n - 1 ? n - 1 : i + 1;
            double tx = px[b] - px[a];
            double ty = py[b] - py[a];
            double tz = pz[b] - pz[a];
            final double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > SIDE_EPS) {
                tx /= tl;
                ty /= tl;
                tz /= tl;
            } else {
                tx = 0.0D;
                ty = 0.0D;
                tz = 1.0D;
            }
            TANGENTS[i * 3] = tx;
            TANGENTS[i * 3 + 1] = ty;
            TANGENTS[i * 3 + 2] = tz;
        }
        // ② 每点的侧向量 = normalize(tangent × toCam)；退化时依次换参考轴
        for (int i = 0; i < n; i++) {
            final double tx = TANGENTS[i * 3];
            final double ty = TANGENTS[i * 3 + 1];
            final double tz = TANGENTS[i * 3 + 2];
            double vx = camX - px[i];
            double vy = camY - py[i];
            double vz = camZ - pz[i];
            final double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vl > SIDE_EPS) {
                vx /= vl;
                vy /= vl;
                vz /= vl;
            } else {
                vx = 0.0D;
                vy = 0.0D;
                vz = 1.0D;
            }
            double sx = ty * vz - tz * vy;
            double sy = tz * vx - tx * vz;
            double sz = tx * vy - ty * vx;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < SIDE_EPS) {
                // 视线正好沿轨迹：换 (0,1,0) 作参考轴
                sx = ty * 0.0D - tz * 1.0D;
                sy = tz * 0.0D - tx * 0.0D;
                sz = tx * 1.0D - ty * 0.0D;
                sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl < SIDE_EPS) {
                    // 切线正好竖直：换 (1,0,0)
                    sx = ty * 0.0D - tz * 0.0D;
                    sy = tz * 1.0D - tx * 0.0D;
                    sz = tx * 0.0D - ty * 1.0D;
                    sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
                    if (sl < SIDE_EPS) {
                        sx = 1.0D;
                        sy = 0.0D;
                        sz = 0.0D;
                        sl = 1.0D;
                    }
                }
            }
            SIDES[i * 3] = sx / sl;
            SIDES[i * 3 + 1] = sy / sl;
            SIDES[i * 3 + 2] = sz / sl;
        }
        // ③ 两层：芯（细亮）+ 光晕（两倍宽、暗）
        int nOut = 0;
        for (int layer = 0; layer < 2; layer++) {
            final double radius = layer == 0
                    ? DropletProfile.beamRadiusCore() : DropletProfile.beamRadiusHalo();
            final double peak = layer == 0
                    ? DropletProfile.BEAM_A_MAX : DropletProfile.BEAM_HALO_A_MAX;
            final float[] rgb = layer == 0
                    ? DropletProfile.BEAM_CORE_RGB : DropletProfile.BEAM_HALO_RGB;
            for (int i = 0; i < n - 1; i++) {
                final float a0 = (float) DropletProfile.beamAlphaAt(cost[i], peak);
                final float a1 = (float) DropletProfile.beamAlphaAt(cost[i + 1], peak);
                if (a0 <= 1.0E-4F && a1 <= 1.0E-4F) {
                    continue;   // 这一段已经淡到看不见：不占顶点
                }
                final double s0x = SIDES[i * 3] * radius;
                final double s0y = SIDES[i * 3 + 1] * radius;
                final double s0z = SIDES[i * 3 + 2] * radius;
                final double s1x = SIDES[(i + 1) * 3] * radius;
                final double s1y = SIDES[(i + 1) * 3 + 1] * radius;
                final double s1z = SIDES[(i + 1) * 3 + 2] * radius;
                nOut = putStream(out, nOut, px[i] + s0x, py[i] + s0y, pz[i] + s0z,
                        rgb[0], rgb[1], rgb[2], a0);
                nOut = putStream(out, nOut, px[i] - s0x, py[i] - s0y, pz[i] - s0z,
                        rgb[0], rgb[1], rgb[2], a0);
                nOut = putStream(out, nOut, px[i + 1] - s1x, py[i + 1] - s1y, pz[i + 1] - s1z,
                        rgb[0], rgb[1], rgb[2], a1);
                nOut = putStream(out, nOut, px[i + 1] + s1x, py[i + 1] + s1y, pz[i + 1] + s1z,
                        rgb[0], rgb[1], rgb[2], a1);
            }
        }
        return nOut;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 由 {@code yaw/pitch} 算三根基向量（<b>与 {@code ShanhaiDropletEntity#makeBoundingBox()} 逐字相同</b>）：
     * {@code forward=(-sinY·cosP, -sinP, cosY·cosP)}、{@code right=(cosY, 0, sinY)}、{@code up=forward×right}。
     *
     * <p>放在本类（零 MC 依赖）而不是渲染器里：世界空间的推进环要在<b>发射那一刻</b>把基存下来，
     * 而存下来的只是 {@code yaw/pitch} 两个 float ⇒ 需要一个不依赖渲染上下文的换算函数。
     *
     * @param out {@code [0..2]=right、[3..5]=up、[6..8]=forward}
     */
    public static void basis(float yaw, float pitch, float[] out) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(pitch);
        final double sinYaw = Math.sin(yawRad);
        final double cosYaw = Math.cos(yawRad);
        final double sinPitch = Math.sin(pitchRad);
        final double cosPitch = Math.cos(pitchRad);
        final double fx = -sinYaw * cosPitch;
        final double fy = -sinPitch;
        final double fz = cosYaw * cosPitch;
        final double rx = cosYaw;
        final double ry = 0.0D;
        final double rz = sinYaw;
        out[0] = (float) rx;
        out[1] = (float) ry;
        out[2] = (float) rz;
        out[3] = (float) (fy * rz - fz * ry);
        out[4] = (float) (fz * rx - fx * rz);
        out[5] = (float) (fx * ry - fy * rx);
        out[6] = (float) fx;
        out[7] = (float) fy;
        out[8] = (float) fz;
    }

    private static int putStream(float[] out, int n, double x, double y, double z,
                                 float r, float g, float b, float a) {
        final int o = n * STREAM_STRIDE;
        out[o] = (float) x;
        out[o + 1] = (float) y;
        out[o + 2] = (float) z;
        out[o + 3] = r;
        out[o + 4] = g;
        out[o + 5] = b;
        out[o + 6] = a;
        return n + 1;
    }
}
