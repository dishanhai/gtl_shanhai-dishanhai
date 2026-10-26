package com.dishanhai.gt_shanhai.client.renderer.blackhole;

/**
 * 山海重构 · 黑洞渲染件的 <b>世界空间 → 屏幕数学</b>（纯逻辑：零 MC / 零 GL，只用 {@code float[16]}）。
 *
 * <h2>1. 为什么用裸 {@code float[16]} 而不是 {@code org.joml.Matrix4f}</h2>
 * 这样它能<b>离线跑</b>（{@code javac + java}，不装游戏、不碰 GL），而渲染侧只要
 * {@code Matrix4f.get(float[])} 拷一次。矩阵<b>列主序</b>（与 GL / JOML 落内存的顺序一致），
 * 下标约定 {@code m[row + 4 * col]}。
 *
 * <h2>2. 🔴 这一条是整件事最容易踩的坑：事件矩阵里<b>没有</b> {@code −camPos}</h2>
 * {@code RenderLevelStageEvent#getPoseStack()} 给的矩阵<b>只含相机旋转</b>。取证（不是推断）：
 * <pre>
 *   ① {@code GameRenderer.renderLevel} 里只对 poseStack 施加 Axis.XP/YP 旋转（+ Forge 的 roll），
 *      相机坐标是【另外传】给 {@code LevelRenderer.prepareCullFrustum(poseStack, camPos, proj)} 的；
 *   ② {@code Frustum} 的 AABB 测试自己减相机坐标（{@code Frustum.m_113006_} 第一行
 *      {@code float f = (float)(x - this.f_112996_)}，{@code f_112996_} 由 {@code prepare(camX,camY,camZ)} 赋值）
 *      ⇒ 传进 Frustum 的 modelview 里【不可能】已经含平移；
 *   ③ {@code LevelRenderer.renderLevel} 内部每个物体自己减 {@code d0/d1/d2}（= camPos），
 *      并且在 {@code m_172993_} 里<b>从不</b> translate poseStack（区块偏移走 {@code ModelOffset} uniform）。
 * </pre>
 * ⇒ <b>世界坐标必须先减 cameraPos，再乘事件矩阵</b>。{@link #toView} 就是这一步，
 * 而且它是<b>唯一</b>一处做这件事的地方（其余全用它的输出），避免出现"两个地方各减一次"。
 * 本仓库里已有同类结论的注释：{@code client/holo/ShanhaiHoloSurroundView} §1（同一结论的 javap 取证）。
 *
 * <h2>3. 屏幕坐标约定</h2>
 * <b>像素、原点在左下</b>（与 {@code gl_FragCoord}、{@code glCopyTexSubImage2D} 一致）。
 * ⇒ 全链路（摆 rect / 拷背景 / 着色器里算 mask）只有一套坐标系，不需要任何 y 翻转。
 *
 * <h2>4. 半径怎么来（不许猜 FOV）</h2>
 * 不用问 {@code GameRenderer.getFov}：直接<b>从事件给的投影矩阵反解</b>
 * {@code tan(fovY/2) = 1 / proj[5]}（{@code proj[5]} = m11 = row1col1 = 1/tan(half)）。
 * 这样疾跑改 FOV、望远镜、药水效果全部自动跟随 —— 不需要知道它们的存在。
 * 于是「半径 R 格的球在距离 d 处占多少像素」= {@code R * (H/2) / (tan * d)}。
 */
public final class BlackHoleLensMath {

    /**
     * 临界碰撞参数（= 光子被吞掉的边界），单位 = 史瓦西半径 Rs。
     *
     * <p>史瓦西时空：{@code b_crit = 3*sqrt(3)/2 * Rs ≈ 2.5980762 Rs}（光子球 {@code 1.5 Rs}）。
     * 这是本件「黑盘半径」的唯一来源：屏幕上半径 = {@code B_CRIT * rsPx}。
     */
    public static final double B_CRIT = 2.598076211353316;

    /** 光子球半径 / Rs（只用于文档与自检；着色器里不直接用）。 */
    public static final double PHOTON_SPHERE = 1.5;

    /** 偏折角公式里的下限（避免 bn→0 时数值爆掉；被吞掉的那一侧本来就画成黑的）。 */
    private static final double DEFLECT_BN_FLOOR = 0.35;

    private BlackHoleLensMath() {}

    // ------------------------------------------------------------------ 半径的两个口径

    /**
     * 反算：要让 <b>可见表面半径 = {@code holeRadiusBlocks} 格</b>，视界半径 {@code rsBlocks} 该是多少。
     *
     * <h2>为什么需要（2026-10-25 第二十改）</h2>
     * 屏幕上黑盘半径的唯一口径是 {@code B_CRIT × rsBlocks}（见 {@link #B_CRIT}）。
     * 而归墟视界枢核的机器字段 {@code hostBlackHoleRadiusBlocks} 是<b>玩家口径的"几格"</b>
     * —— 那是<b>表面半径</b>，不是 {@code rsBlocks}。两个口径之间就差这一个除法：
     * <pre>
     *   rsBlocks = 表面半径 / B_CRIT
     *   ⇒ B_CRIT × rsBlocks == 表面半径（逐位回得去，离线探针直接量这个等式）
     * </pre>
     * 🔴 不许在这里再乘一次 {@code RS_BLOCKS}：{@code RS_BLOCKS} 是"默认倍率 2.0 格"那一套，
     * 它与机器字段是<b>两套来源</b> —— 主机在用时只认机器字段（{@code BlackHoleLensRenderer.rsBlocks()}）。
     *
     * @param holeRadiusBlocks 可见表面半径（格）
     * @return 对应视界半径（格）
     */
    public static double rsBlocksForHoleRadius(double holeRadiusBlocks) {
        return holeRadiusBlocks / B_CRIT;
    }

    // ------------------------------------------------------------------ 变换

    /**
     * 世界坐标 → <b>相机空间</b>（view space）：{@code out = R · (world − camPos)}。
     *
     * <p>R = 事件给的 poseStack 矩阵（<b>只有旋转</b>，见类注释 §2）。相机在原点、朝 −z。
     *
     * @param view  列主序 4×4（事件 poseStack）
     * @param world 世界坐标（方块）
     * @param cam   相机世界坐标（{@code camera.getPosition()}，已含 partialTick 插值）
     * @param out3  出口长度 ≥3
     */
    public static void toView(float[] view, double[] world, double[] cam, float[] out3) {
        final float x = (float) (world[0] - cam[0]);
        final float y = (float) (world[1] - cam[1]);
        final float z = (float) (world[2] - cam[2]);
        out3[0] = view[0] * x + view[4] * y + view[8] * z;
        out3[1] = view[1] * x + view[5] * y + view[9] * z;
        out3[2] = view[2] * x + view[6] * y + view[10] * z;
    }

    /** {@code tan(fovY/2)}，从投影矩阵反解（{@code 1/m11}）。无效矩阵返回 {@code NaN}。 */
    public static double tanHalfFovY(float[] proj) {
        final float m11 = proj[5];
        if (!Float.isFinite(m11) || m11 <= 0.0f) {
            return Double.NaN;
        }
        return 1.0 / (double) m11;
    }

    /**
     * 相机空间点 → 屏幕像素（左下原点）+ NDC 深度。
     *
     * @param out3 出口：{@code [pxX, pxY, ndcZ]}
     * @return {@code false} = 该点在相机背后/贴在近平面上（此时 out3 无意义）
     */
    public static boolean projectToScreen(float[] proj, float[] viewPos,
                                          int screenW, int screenH, double[] out3) {
        final float x = viewPos[0];
        final float y = viewPos[1];
        final float z = viewPos[2];
        final float cx = proj[0] * x + proj[4] * y + proj[8] * z + proj[12];
        final float cy = proj[1] * x + proj[5] * y + proj[9] * z + proj[13];
        final float cz = proj[2] * x + proj[6] * y + proj[10] * z + proj[14];
        final float cw = proj[3] * x + proj[7] * y + proj[11] * z + proj[15];
        if (!(cw > 1e-6f) || !Float.isFinite(cw)) {
            return false;
        }
        final double ndcX = cx / cw;
        final double ndcY = cy / cw;
        final double ndcZ = cz / cw;
        out3[0] = (ndcX * 0.5 + 0.5) * screenW;
        out3[1] = (ndcY * 0.5 + 0.5) * screenH;
        out3[2] = ndcZ;
        return true;
    }

    /**
     * 世界半径 → 屏幕像素半径。
     *
     * <p>透视投影下，距离 d 处一个与视线垂直的半径 R 覆盖的<b>半高</b>比例 = {@code R/(d·tan)}，
     * 而半高 = {@code H/2} 像素 ⇒ 上式。用 {@code forwardDist = −viewZ}（不是欧氏距离）才对：
     * 偏到画面边缘时欧氏距离更大，但像素尺度只由 z 决定。
     */
    public static double screenRadiusPx(double worldRadius, double forwardDist,
                                       double tanHalfFovY, int screenH) {
        if (!(forwardDist > 1e-4) || !(tanHalfFovY > 1e-4)) {
            return 0.0;
        }
        return worldRadius * (screenH * 0.5) / (tanHalfFovY * forwardDist);
    }

    // ------------------------------------------------------------------ 矩形 / 预算

    /**
     * 以屏幕点 {@code (cx,cy)} 为中心、半边长 {@code halfPx} 的正方形，裁到画面内，
     * 再按像素预算 {@code maxPixels} 等比缩小（缩小以中心为锚点，不改变中心）。
     *
     * <p>为什么是<b>正方形</b>：透镜是圆对称的；正方形是它最小的外包矩形，
     * 而拷贝区域必须是矩形 ⇒ 正方形就是这个形状下最省的矩形。
     *
     * @param maxPixels 面积上限（= 这一帧允许为黑洞花掉的像素数）
     */
    public static Rect rectAround(double cx, double cy, double halfPx,
                                  int screenW, int screenH, double maxPixels) {
        double half = Math.max(halfPx, 0.0);
        Rect r = clamp(half, cx, cy, screenW, screenH);
        if (maxPixels > 0.0 && r.w * r.h > maxPixels) {
            final double scale = Math.sqrt(maxPixels / (r.w * r.h));
            half *= scale;
            r = clamp(half, cx, cy, screenW, screenH);
        }
        return r;
    }

    private static Rect clamp(double half, double cx, double cy, int screenW, int screenH) {
        double x0 = Math.max(0.0, cx - half);
        double y0 = Math.max(0.0, cy - half);
        double x1 = Math.min(screenW, cx + half);
        double y1 = Math.min(screenH, cy + half);
        double w = Math.max(0.0, x1 - x0);
        double h = Math.max(0.0, y1 - y0);
        return new Rect(x0, y0, w, h, Math.min(w, h) * 0.5);
    }

    // ------------------------------------------------------------------ 物理

    /**
     * 偏折角（弧度）关于碰撞参数 {@code bn = b/Rs} 的函数 —— <b>与 {@code blackhole.fsh} 逐字同款</b>。
     *
     * <p>展开式 {@code α = 2t + 2.2t² + 1.6t³}（t = Rs/b）：
     * <ul>
     *   <li>首项 {@code 2t = 2Rs/b} 就是弱场（爱因斯坦）偏折角 {@code 4GM/(c²b)}；</li>
     *   <li>后两项是近场的<b>经验高阶项</b>：真解在 {@code b → b_crit} 时发散（绕光子球），
     *       解析多项式不可能发散，所以这里用低阶多项式把它<b>压成一个大的有限角</b>
     *       （{@code bn = b_crit} 时约 1.18 rad ≈ 68°），靠"被吞掉就画黑"来接住奇点。</li>
     * </ul>
     * ⚠️ <b>诚实标注</b>：这是<b>解析近似的偏折律</b>，不是逐像素积分零测地线。
     * 想要物理级精度就得做测地线积分（每像素多次步进），那是另一个成本档（见报告 §7）。
     */
    public static double deflectAngle(double bn) {
        final double t = 1.0 / Math.max(bn, DEFLECT_BN_FLOOR);
        return 2.0 * t + 2.2 * t * t + 1.6 * t * t * t;
    }

    /** 屏幕矩形（像素，左下原点）。{@code half} = min(w,h)/2，即真正的透镜半径。 */
    public static final class Rect {
        public final double x0;
        public final double y0;
        public final double w;
        public final double h;
        public final double half;

        public Rect(double x0, double y0, double w, double h, double half) {
            this.x0 = x0;
            this.y0 = y0;
            this.w = w;
            this.h = h;
            this.half = half;
        }

        public double centerX() {
            return x0 + w * 0.5;
        }

        public double centerY() {
            return y0 + h * 0.5;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "Rect[%.1f,%.1f %.1fx%.1f half=%.1f]", x0, y0, w, h, half);
        }
    }
}
