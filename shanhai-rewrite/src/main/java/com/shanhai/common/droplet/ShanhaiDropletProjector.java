package com.shanhai.common.droplet;

/**
 * 世界 → 屏幕的<b>投影核</b>（同样是零 MC 依赖：只用 {@code double} 与 {@code double[]}）。
 *
 * <h2>一、为什么自己算投影，而不在 3D 阶段画线</h2>
 * 目标标记（绿框）与准星圈最终都是<b>屏幕空间</b>的 2D 图元：
 * <ul>
 *   <li>绿框 = 世界空间包围盒的 12 条边，投影到屏幕后逐条画虚线；</li>
 *   <li>标签 = 2D 文本；</li>
 *   <li>准星圈 = 屏幕上以准星为心的圆。</li>
 * </ul>
 * 如果放到 3D 阶段画，就要插进水滴本体那条线的世界渲染管线（另一条线正在改那片目录），
 * 还要自己管深度/混合状态。改成"3D 阶段只抓一份投影矩阵 + 相机位姿，2D 阶段自己投影"以后：
 * ① 与那条线零冲突；② 文本天然无深度问题；③ 投影数学是纯函数，可以离线断言。
 *
 * <h2>二、坐标系约定（与游戏一致）</h2>
 * <ul>
 *   <li>世界：+X 东、+Y 上、+Z 南。</li>
 *   <li>相机（view）空间：+X 右、+Y 上、<b>−Z 前方</b>（所以"在相机前面"= {@code z < −NEAR}）。</li>
 *   <li>屏幕：左上角原点，+X 右、+Y 下（与 {@code GuiGraphics} 一致）。</li>
 * </ul>
 * 列主序 {@code double[16]}（与 JOML {@code Matrix4f.get(float[])} 的顺序一致），下标 = 列×4 + 行。
 *
 * <h2>三、近平面裁剪为什么必须有</h2>
 * 目标可能贴到镜头上（驾驶时 1 格/tick 冲过去）：8 个角里总有几个落在相机背后，
 * 直接投影会得到"镜像"的假点（w ≤ 0），框会横穿整个屏幕。所以每条边先在 view 空间对
 * {@code z = −NEAR} 做参数裁剪，裁完再投影；两端都在背后就整条丢掉。
 */
public final class ShanhaiDropletProjector {

    /** 近平面（格）。与游戏投影的近平面同量级；只用于裁剪，不影响屏幕坐标（屏幕坐标用 FOV 闭式解）。 */
    public static final double NEAR = 0.05D;

    /**
     * view 矩阵：把世界点变到相机空间（+X 右、+Y 上、−Z 前）。
     *
     * <h4>三根基向量（由原版朝向约定推出来的，不是试出来的）</h4>
     * 原版 {@code Vec3.directionFromRotation(pitch, yaw)} 给出前向量
     * {@code F = (−sin yaw·cos pitch, −sin pitch, cos yaw·cos pitch)}
     * （{@code yaw = 0} 面朝 <b>+Z</b>、{@code pitch = −90} 仰头看 <b>+Y</b>）。于是相机三轴为
     * <pre>
     *   右 = normalize(cross(F, (0,1,0))) = (−cos yaw,            0,          −sin yaw)
     *   上 = cross(右, F)                 = (−sin yaw·sin pitch,  cos pitch,   cos yaw·sin pitch)
     *   后 = −F                           = ( sin yaw·cos pitch,  sin pitch,  −cos yaw·cos pitch)
     * </pre>
     * 相机空间坐标 = {@code (dot(右,p), dot(上,p), dot(后,p))}，再把平移量写进第 3 列。
     *
     * <p>⚠️ 这三根轴由离线断言逐条钉住（{@code yaw=0} 时"+Z 的前方 ⇒ z=−10"、"{@code −X} 的右手边 ⇒ x=+10"、
     * "{@code pitch=−90} 仰头时 +Y ⇒ z=−10"）。第一版写成 {@code Ry(yaw+180)·Rx(pitch)} 是错的，
     * 被这三条断言当场抓出来（俯仰/右向符号反了）。
     *
     * @param yawDeg 相机 yaw（度，原版口径）
     * @param pitchDeg 相机 pitch（度，原版口径，低头为正、仰头为负）
     * @param camX 相机世界 X
     * @param camY 相机世界 Y
     * @param camZ 相机世界 Z
     * @return 列主序 4×4 view 矩阵（double[16]）
     */
    public static double[] viewMatrix(float yawDeg, float pitchDeg, double camX, double camY, double camZ) {
        final double yaw = Math.toRadians((double) yawDeg);
        final double pitch = Math.toRadians((double) pitchDeg);
        final double sinYaw = Math.sin(yaw);
        final double cosYaw = Math.cos(yaw);
        final double sinPitch = Math.sin(pitch);
        final double cosPitch = Math.cos(pitch);

        // 右 / 上 / 后（各行）
        final double r00 = -cosYaw;
        final double r01 = 0.0D;
        final double r02 = -sinYaw;
        final double r10 = -sinYaw * sinPitch;
        final double r11 = cosPitch;
        final double r12 = cosYaw * sinPitch;
        final double r20 = sinYaw * cosPitch;
        final double r21 = sinPitch;
        final double r22 = -cosYaw * cosPitch;

        final double[] m = new double[16];
        // 第 0 列
        m[0] = r00;
        m[1] = r10;
        m[2] = r20;
        m[3] = 0.0D;
        // 第 1 列
        m[4] = r01;
        m[5] = r11;
        m[6] = r21;
        m[7] = 0.0D;
        // 第 2 列
        m[8] = r02;
        m[9] = r12;
        m[10] = r22;
        m[11] = 0.0D;
        // 第 3 列 = 平移 −R·cam
        m[12] = -(r00 * camX + r01 * camY + r02 * camZ);
        m[13] = -(r10 * camX + r11 * camY + r12 * camZ);
        m[14] = -(r20 * camX + r21 * camY + r22 * camZ);
        m[15] = 1.0D;
        return m;
    }

    /** 世界点 → view 空间 {@code {x, y, z}}（前向 = −z）。 */
    public static double[] toView(double[] view, double x, double y, double z) {
        return new double[] {
            view[0] * x + view[4] * y + view[8] * z + view[12],
            view[1] * x + view[5] * y + view[9] * z + view[13],
            view[2] * x + view[6] * y + view[10] * z + view[14]
        };
    }

    /**
     * 标准透视投影矩阵（列主序），<b>只用于离线断言</b>（证明"闭式屏幕公式"与"真矩阵乘"同源）。
     *
     * @param tanHalfFovY {@code tan(fovY/2)}
     * @param aspect 宽高比 {@code W/H}
     * @param near 近平面
     * @param far 远平面
     */
    public static double[] projectionMatrix(double tanHalfFovY, double aspect, double near, double far) {
        final double[] m = new double[16];
        m[0] = 1.0D / (aspect * tanHalfFovY);
        m[5] = 1.0D / tanHalfFovY;
        m[10] = -(far + near) / (far - near);
        m[11] = -1.0D;
        m[14] = -(2.0D * far * near) / (far - near);
        m[15] = 0.0D;
        return m;
    }

    /**
     * view 空间点 → 屏幕像素 {@code {px, py}}（左上原点）；在相机背后（{@code z >= −NEAR}）⇒ {@code null}。
     *
     * <p>这就是 {@link #projectionMatrix} 那条矩阵乘的闭式解：
     * {@code ndcX = x / (aspect·tan(fov/2)·(−z))}、{@code ndcY = y / (tan(fov/2)·(−z))}，
     * 再线性映射到像素。两种写法在断言里互相验证。
     */
    public static double[] viewToScreen(double tanHalfFovY, double aspect,
                                        int screenW, int screenH, double[] v) {
        if (v == null || v.length < 3) {
            return null;
        }
        final double depth = -v[2];
        if (depth < NEAR) {
            return null;
        }
        final double ndcX = v[0] / (aspect * tanHalfFovY * depth);
        final double ndcY = v[1] / (tanHalfFovY * depth);
        final double px = (ndcX * 0.5D + 0.5D) * (double) screenW;
        final double py = (0.5D - ndcY * 0.5D) * (double) screenH;
        return new double[] { px, py };
    }

    /**
     * 线段对近平面的裁剪（view 空间）。
     *
     * @return 裁剪后的两个端点；整条在近平面之后 ⇒ {@code null}
     */
    public static double[][] clipSegmentToNear(double[] a, double[] b) {
        if (a == null || b == null) {
            return null;
        }
        final boolean aIn = a[2] <= -NEAR;
        final boolean bIn = b[2] <= -NEAR;
        if (aIn && bIn) {
            return new double[][] { a, b };
        }
        if (!aIn && !bIn) {
            return null;
        }
        final double[] inside = aIn ? a : b;
        final double[] outside = aIn ? b : a;
        final double dz = inside[2] - outside[2];
        if (Math.abs(dz) < 1.0E-9D) {
            return null;
        }
        final double t = (-NEAR - outside[2]) / dz;
        final double[] cut = new double[] {
            outside[0] + (inside[0] - outside[0]) * t,
            outside[1] + (inside[1] - outside[1]) * t,
            -NEAR
        };
        return aIn ? new double[][] { a, cut } : new double[][] { cut, b };
    }

    /**
     * 轴对齐盒的 12 条边：按 3 位编码（bit0 = X 取上界、bit1 = Y 取上界、bit2 = Z 取上界）返回端点下标对。
     *
     * <p>只列出"只差一位"的组合 ⇒ 每个盒面 4 条边、共 12 条，既不漏也不重复。
     */
    public static final int[][] AABB_EDGES = {
        { 0, 1 }, { 2, 3 }, { 4, 5 }, { 6, 7 },     // 沿 X
        { 0, 2 }, { 1, 3 }, { 4, 6 }, { 5, 7 },     // 沿 Y
        { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 }      // 沿 Z
    };

    /**
     * 轴对齐盒的 8 个角（世界坐标），顺序与 {@link #AABB_EDGES} 的编码一致：
     * 下标 = {@code (xHi?1:0) | (yHi?2:0) | (zHi?4:0)}。
     */
    public static double[][] corners(double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ) {
        final double[][] c = new double[8][3];
        for (int i = 0; i < 8; i++) {
            c[i][0] = (i & 1) != 0 ? maxX : minX;
            c[i][1] = (i & 2) != 0 ? maxY : minY;
            c[i][2] = (i & 4) != 0 ? maxZ : minZ;
        }
        return c;
    }

    private ShanhaiDropletProjector() {}
}
