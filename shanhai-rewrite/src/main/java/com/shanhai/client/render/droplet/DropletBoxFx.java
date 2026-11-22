package com.shanhai.client.render.droplet;

/**
 * 水滴的<b>蓝色线框盒</b>（穿墙可见）—— 2026-11-24 换掉原来那套"高亮外壳两遍"。
 *
 * <h2>一、为什么换（用户原话）</h2>
 * 「<b>水滴高亮如果不行那就不做高亮了，改成蓝色的框，就和驾驶的框一样</b>」。
 * 于是：不再画随形外壳（那东西在低模 + 逐顶点 alpha 下天生不可靠，见
 * {@code DropletShellFx} 的旧推导），改成<b>实体自己碰撞箱的 12 条棱</b>。
 *
 * <h2>二、线宽为什么必须在<b>屏幕空间</b>恒定</h2>
 * 世界空间的管子/细长方体在远处会变成亚像素 ⇒ "等于没画"。
 * 这里用"准星拉线"同一个思路：每条棱在屏幕上是<b>一个四边形</b>，
 * 它的宽度由像素数给定，再按该端点的<b>深度</b>换算回世界单位：
 * <pre>
 *   depth = −(p − cam)·back            // back = 相机 +Z 轴（世界）
 *   Δworld = right·(sx·depth/fPx) + up·(−sy·depth/fPx)
 *   fPx = P11 ·(帧缓冲高度/2)           // 由投影矩阵反推，FOV 改了也自动对
 * </pre>
 * ⇒ 任何距离的屏幕上宽度都是 {@code 2·halfWidthPx} 像素（本实现取 2.0 px ⇒ 判据 ≥1.3 px）。
 *
 * <h2>三、12 条棱</h2>
 * 盒子 = 实体自己的 {@code getBoundingBox()}（3.0 × 1.5 × 1.5 那套旋转后投影成的轴对齐盒，
 * 随 tick 更新）。8 个角 → 12 条棱，每条 1 个四边形 ⇒ <b>每盒 12 quads</b>。
 *
 * <p>零 Minecraft 依赖（只吃 6 个 double + 相机基向量）⇒ 离线预览探针跑的是同一份代码。
 */
public final class DropletBoxFx {

    /** 盒子有 12 条棱。 */
    public static final int EDGES = 12;
    /** 每个盒子的顶点数（12 棱 × 4 顶点）。 */
    public static final int VERTS_PER_BOX = EDGES * 4;
    /** 顶点流步长：pos3 + rgba4（与 {@link DropletMesh#STREAM_STRIDE} 同一套）。 */
    public static final int STRIDE = DropletMesh.STREAM_STRIDE;

    /** 顶点流复用缓冲（一个盒子）。 */
    public static final float[] SCRATCH = new float[VERTS_PER_BOX * STRIDE];

    /** 线宽（屏幕像素，总宽）—— 判据：任何距离 ≥1.3 px。 */
    public static final float LINE_WIDTH_PX = 2.0F;

    /** 端点离相机太近（越过近平面）就整条棱不画（避免屏幕上炸开一条长线）。 */
    private static final double MIN_DEPTH = 0.05D;

    /** 棱到角的索引表：8 个角按 (x,y,z) 的二进制编号，0=min、1=max。 */
    private static final int[] EDGE = {
            0, 1, 0, 2, 0, 4,        // 从 min 角出发的三条
            7, 6, 7, 5, 7, 3,        // 从 max 角出发的三条
            1, 3, 1, 5, 2, 3,        // 剩下 6 条（x/y/z 各一对）
            2, 6, 4, 5, 4, 6
    };

    private DropletBoxFx() {}

    /**
     * 把一个轴对齐盒子的 12 条棱写成世界坐标顶点流。
     *
     * @param box         6 个 double：{@code minX,minY,minZ,maxX,maxY,maxZ}（世界）
     * @param camX/camY/camZ 相机位置（世界）
     * @param right       相机"右"轴（世界，单位向量；= 视图矩阵第 0 行）
     * @param up          相机"上"轴（世界，单位向量；= 视图矩阵第 1 行）
     * @param back        相机"后"轴（世界，单位向量；= 视图矩阵第 2 行）
     * @param fPx         焦距（像素）= 投影矩阵 P11 ×（帧缓冲高度 / 2）
     * @param halfWidthPx 线宽的一半（屏幕像素）
     * @return 写入的顶点数（0 = 整盒都在相机后 / 退化）
     */
    public static int buildBox(double[] box, double camX, double camY, double camZ,
                               double[] right, double[] up, double[] back,
                               double fPx, float halfWidthPx,
                               float r, float g, float b, float a) {
        if (fPx <= 1.0E-6D || halfWidthPx <= 0.0F) {
            return 0;
        }
        // 8 个角：编号 i 的第 0/1/2 位分别对应 x/y/z 取 min/max
        final double[] cx = new double[8];
        final double[] cy = new double[8];
        final double[] cz = new double[8];
        for (int i = 0; i < 8; i++) {
            cx[i] = ((i & 1) == 0) ? box[0] : box[3];
            cy[i] = ((i & 2) == 0) ? box[1] : box[4];
            cz[i] = ((i & 4) == 0) ? box[2] : box[5];
        }
        // 屏幕坐标（像素）与深度
        final double[] sx = new double[8];
        final double[] sy = new double[8];
        final double[] depth = new double[8];
        for (int i = 0; i < 8; i++) {
            final double dx = cx[i] - camX;
            final double dy = cy[i] - camY;
            final double dz = cz[i] - camZ;
            // 相机坐标系（x=右, y=上, z=后 ⇒ 前方 depth = −z）
            final double px = dx * right[0] + dy * right[1] + dz * right[2];
            final double py = dx * up[0] + dy * up[1] + dz * up[2];
            final double pz = dx * back[0] + dy * back[1] + dz * back[2];
            depth[i] = -pz;
            if (depth[i] < MIN_DEPTH) {
                depth[i] = MIN_DEPTH;
            }
            sx[i] = px / depth[i] * fPx;
            sy[i] = -py / depth[i] * fPx;
        }
        int n = 0;
        for (int e = 0; e < EDGES; e++) {
            final int ia = EDGE[e * 2];
            final int ib = EDGE[e * 2 + 1];
            double dx = sx[ib] - sx[ia];
            double dy = sy[ib] - sy[ia];
            final double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 1.0E-6D) {
                continue;                       // 这条棱在这个角度退化成一点
            }
            // 屏幕空间的单位法向
            final double nx = -dy / len;
            final double ny = dx / len;
            // 每个端点按自己的深度把"像素偏移"换算成世界偏移
            final double wa = halfWidthPx * depth[ia] / fPx;
            final double wb = halfWidthPx * depth[ib] / fPx;
            final double ax = right[0] * (nx * wa) + up[0] * (-ny * wa);
            final double ay = right[1] * (nx * wa) + up[1] * (-ny * wa);
            final double az = right[2] * (nx * wa) + up[2] * (-ny * wa);
            final double bx = right[0] * (nx * wb) + up[0] * (-ny * wb);
            final double by = right[1] * (nx * wb) + up[1] * (-ny * wb);
            final double bz = right[2] * (nx * wb) + up[2] * (-ny * wb);
            n = put(n, cx[ia] + ax, cy[ia] + ay, cz[ia] + az, r, g, b, a);
            n = put(n, cx[ia] - ax, cy[ia] - ay, cz[ia] - az, r, g, b, a);
            n = put(n, cx[ib] - bx, cy[ib] - by, cz[ib] - bz, r, g, b, a);
            n = put(n, cx[ib] + bx, cy[ib] + by, cz[ib] + bz, r, g, b, a);
        }
        return n;
    }

    /**
     * 从 4×4 视图矩阵里取三根基（世界坐标）。
     *
     * <p>JOML 的 {@code Matrix4f#get(float[])} 是<b>列主序</b>：{@code m[列*4+行]}。
     * 视图矩阵把世界映射到相机空间，它的<b>第 0/1/2 行</b>就是相机的 右/上/后 轴在世界里的分量
     * ⇒ {@code right = (m[0], m[4], m[8])}、{@code up = (m[1], m[5], m[9])}、{@code back = (m[2], m[6], m[10])}。
     */
    public static void cameraAxes(float[] view, double[] right, double[] up, double[] back) {
        right[0] = view[0];
        right[1] = view[4];
        right[2] = view[8];
        up[0] = view[1];
        up[1] = view[5];
        up[2] = view[9];
        back[0] = view[2];
        back[1] = view[6];
        back[2] = view[10];
    }

    private static int put(int n, double x, double y, double z,
                           float r, float g, float b, float a) {
        final int o = n * STRIDE;
        SCRATCH[o] = (float) x;
        SCRATCH[o + 1] = (float) y;
        SCRATCH[o + 2] = (float) z;
        SCRATCH[o + 3] = r;
        SCRATCH[o + 4] = g;
        SCRATCH[o + 5] = b;
        SCRATCH[o + 6] = a;
        return n + 1;
    }
}
