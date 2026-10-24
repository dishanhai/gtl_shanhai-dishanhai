package com.shanhai.client.render.hostfx;

import com.shanhai.machine.host.GuixuHostGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 归墟视界枢核 · <b>纯几何</b>（不碰渲染状态、不碰 PoseStack —— 离线探针与预览图能直接调）。
 *
 * <h2>🔴 2026-10-24 大改：内环与飞船<b>不再画四边形</b>，改成"真方块跟着转"</h2>
 * 口径来自用户：「内环和飞船的渲染都可以是直接让方块开始转动，就像伪神的那三个环的渲染一样」。
 * 参照物是 gtladditions 的伪神之锻炉：<b>先把该结构段的真方块藏成 AIR，再把它们的方块模型烘成静态
 * {@code VertexBuffer}，每帧换一个 PoseStack 重画一遍</b>（取证见 {@code temp/host-fx/报告-伪神渲染取证.md}）。
 * 于是本类只剩三件事：
 * <ol>
 *   <li>结构参数与藏块清单（{@link #hideTargets()}）；</li>
 *   <li>世界坐标 ↔ 环心本地坐标的换算（烘焙用，纯算术）；</li>
 *   <li>唯一保留的自绘特效：<b>6 条光束</b>（4 条提取 + 黑洞上下 2 条）的"管"几何。</li>
 * </ol>
 * 烘焙与提交分别在 {@link HostFxBaked} / {@link HostFxRingGroups} / {@link HostFxDraw}。
 *
 * <h2>坐标口径</h2>
 * <pre>
 *   生成器坐标（结构模板坐标）：环心格 (0, RING_Y=10, 0)，控制器格 (72, 21, 0)，机器正位朝西
 *   环心本地坐标：生成器坐标 − 环心格 − CELL_CENTER(0.5)
 *                 ⇒ 环心那一格的<b>中心</b> = (0,0,0)，每格占 [n−0.5, n+0.5]³，环面（格中心）= y 0
 *   控制器相对坐标：rotate(朝向, 生成器坐标 − 控制器坐标)
 * </pre>
 * ⚠️ 那半格（{@link #CELL_CENTER}）不是凑数：它是"刚体转 90° 之后还落在格子上"的充要条件，
 * 详见 {@link #CELL_CENTER} 的注释。
 */
public final class HostFxGeometry {

    private HostFxGeometry() {}

    // ---------------- 生成器参数（逐字对齐 gen5.mjs 的 P{}） ----------------
    /** 环带所在高度（生成器 {@code ringY}）。 */
    public static final int RING_Y = 10;
    /** 内环半径（生成器 {@code innerR}）。 */
    public static final double INNER_R = 48.0D;
    /**
     * 内环半径容差：生成器 {@code gen5.mjs} ③ 段写的是 <b>{@code d <= 1.1}</b>
     * （{@code const d = Math.abs(Math.hypot(x,z) - P.innerR); if (d <= 1.1)}）。
     *
     * <p>🔴 2026-10-25 修：这里原来是 <b>0.9</b>，比生成器<b>窄 0.2 格</b> ⇒
     * {@code 0.9 < d ≤ 1.1} 那一圈<b>真方块从来没被藏</b>，
     * 用户看到的就是「内环那一圈仍露出成串的天蓝/白色方块」（{@code C.inner = 0x9fb3d6} = mv 机壳淡蓝）。
     * 现在与生成器逐字一致：{@code 1.1}。
     */
    public static final double INNER_TOLERANCE = 1.1D;
    /** 环带上下轨偏移（生成器 {@code ringTrackDy}）。 */
    public static final int RING_TRACK_DY = 3;
    /** 飞船轨道半径（生成器 {@code shipRadialCenter}）。 */
    public static final double SHIP_RADIAL_CENTER = 63.0D;
    /** 飞船切向半宽（生成器 {@code shipTangentialHalf}）。 */
    public static final int SHIP_TANGENTIAL_HALF = 3;
    /** 飞船径向半宽（生成器 {@code shipRadialHalf}）。 */
    public static final int SHIP_RADIAL_HALF = 2;
    /** 飞船船体最大高度（生成器 {@code shipHullTop}）。 */
    public static final int SHIP_HULL_TOP = 3;
    /** 飞船甲板高度（生成器 {@code shipDeckTop}）。 */
    public static final int SHIP_DECK_TOP = 4;
    /** 控制器在生成器坐标里的位置（模板坐标）。 */
    public static final int CONTROLLER_X = 72;
    public static final int CONTROLLER_Y = 21;
    public static final int CONTROLLER_Z = 0;

    /** 环心在"控制器相对、未旋转"坐标里的位置（= 生成器环心 − 控制器）。 */
    public static final float RING_CENTER_CTRL_X = -CONTROLLER_X;
    public static final float RING_CENTER_CTRL_Y = RING_Y - CONTROLLER_Y;
    public static final float RING_CENTER_CTRL_Z = -CONTROLLER_Z;

    /** 内环带转多快（度/秒）。 */
    public static final float RING_DEG_PER_SEC = 12.0F;

    /**
     * 飞船绕环多快（度/秒，<b>上组</b>；下组取 {@link #SHIP_DOWN_SIGN} 反向）。
     *
     * <p>🔴 2026-10-25 二次修正（用户实机看完后当场否掉了"与内环同速"那版）：
     * 原话「这个飞船啊，<b>它是要在外环外侧飞的，就原本好好的，你这一改就不好了</b>」
     * ⇒ <b>撤销</b>上一轮"飞船与内环同角速度、同枢轴"的改动：
     * <ul>
     *   <li>角速度回到 <b>+9°/s（上组） / −9°/s（下组）</b>，与内环 12°/s <b>故意不同</b>
     *       （上下两组对转是原设计，不是 bug）；</li>
     *   <li>轨道半径回到<b>外环外侧</b> {@link #SHIP_ORBIT_R} = 63 格（生成器 {@code shipRadialCenter}）。</li>
     * </ul>
     * ⚠️ 于是「飞船角速度 == 内环角速度」那条断言<b>作废</b>，已被
     * {@code RingPreviewPng} 里的新读数取代（上舰 +9、下舰 −9、两者差 18°/s、半径 63）。
     */
    public static final float SHIP_DEG_PER_SEC = 9.0F;

    /** 上组飞船的转向符号（+1 = 与内环同向）。 */
    public static final int SHIP_UP_SIGN = 1;
    /** 下组飞船的转向符号（−1 = 反向）—— 原设计就是上下对转。 */
    public static final int SHIP_DOWN_SIGN = -1;


    // ---------------- 6 条光束（唯一保留的自绘特效） ----------------

    /**
     * 每条光束沿轴向切几段 —— 参数来自同族 mod 秘法涡流 {@code LightSpike} 的实测值：<b>32 段</b>
     * （不是伪神光束的 10 段；32 段才能让"五次贝塞尔收细"看起来平滑）。
     */
    public static final int BEAM_SEGMENTS = 32;
    /** 横截面几等分 —— 同源实测值：<b>12 段圆</b>（32 × 12 = 每条 384 quads）。 */
    public static final int BEAM_FACETS = 12;
    /** 极轴光束的长度（格）= {@link #POLAR_LENGTH}。径向 4 条的长度<b>不是常数</b>：它由"臂根 47 − 黑洞表面"算出。 */
    public static final float BEAM_LENGTH = 12.0F;
    /** 峰值直径（格）：0.5 ⇒ 峰值半径 0.25。 */
    public static final float BEAM_PEAK_DIAMETER = 0.5F;
    /** 峰值半径。 */
    public static final float BEAM_PEAK_RADIUS = BEAM_PEAK_DIAMETER * 0.5F;
    /** 纯顶点色 (180,220,255)：无贴图，观感全靠这个色 + 下面的亮度包络。 */
    public static final float[] BEAM_COLOR = {180.0F / 255.0F, 220.0F / 255.0F, 255.0F / 255.0F};
    /** 半径沿长度的<b>五次贝塞尔</b>控制半径（只给"形状"；峰值由 {@link #TAPER_PEAK} 归一到 {@link #BEAM_PEAK_RADIUS}）。 */
    public static final float[] BEAM_TAPER = {0.025F, 0.20F, 0.3125F, 0.306F, 0.15F, 0.025F};

    /**
     * {@link #BEAM_TAPER} 那条贝塞尔的<b>形状峰值</b>（类初始化时采样 10001 点求出，实测 ≈ 0.24953）。
     *
     * <p>为什么要它：用户口径是"<b>半径峰值 0.25 格</b>"。直接拿控制点凑出来的曲线峰值是 0.2495，
     * 写成"≈0.25"就是含糊其辞。这里把形状<b>归一</b>再乘 {@link #BEAM_PEAK_RADIUS}
     * ⇒ {@link #taperRadius} 的峰值严格等于 0.25（离线探针 §5 直接量最大值，贴原始读数）。
     */
    private static final float TAPER_PEAK = maxTaper();

    private static float maxTaper() {
        float peak = 0.0F;
        for (int i = 0; i <= 10000; i++) {
            peak = Math.max(peak, taperShape(i / 10000.0F));
        }
        return peak;
    }
    /**
     * 4 条径向光柱的<b>起点半径</b> = 内环上 4 个连接臂的<b>根部</b>。
     *
     * <p>生成器 {@code gen5.mjs} ④ 段辐条的第一圈就是 {@code t = P.innerR - 1 = 47} 的 3×3 法兰
     * （{@code C.flange} = hv 机壳），那就是"臂根"。光柱从这里出发、<b>径向朝环心</b>射向黑洞表面。
     */
    public static final float ARM_ROOT_R = (float) INNER_R - 1.0F;      // = 47

    /**
     * 黑洞表面（黑盘 / 光子环外缘）半径的默认值（格）。
     *
     * <p>口径来自 {@code BlackHoleLensRenderer}：黑盘屏幕上半径 = {@code B_CRIT × rsBlocks}，
     * 而 {@code rsBlocks = RS_BLOCKS × BlackHoleTuning.scale()}，{@code B_CRIT = 3√3/2 ≈ 2.598}。
     * <p>🔴 2026-10-25：{@code RS_BLOCKS 1.0 → 2.0}（用户「黑洞有点太小了」）⇒ 默认可见半径
     * {@code 2.598 × 2.0 = 5.196 格}（改前 2.598 格）。这里只是<b>离线预览/探针的默认值</b>，
     * 游戏里 {@code GuixuHostFxRenderer} 每帧传的是运行期实测
     * {@code BlackHoleFrameStats.lastRsBlocks() × B_CRIT}（跟着 {@code /shanhai bh scale} 走）。
     */
    public static final float HOLE_SURFACE_R = 5.196F;

    /** 极轴光束（黑洞上下 2 条）的长度（格）：从黑洞表面往外 {@value} 格。 */
    public static final float POLAR_LENGTH = 12.0F;

    /**
     * 4 条径向光柱离环面的高度（格）。
     *
     * <p>🔴 2026-10-25（父代理约束③）：原来是 {@code 0.0}（正好在环面高度 = 黑洞赤道面），
     * 于是光柱末端与<b>吸积盘共面</b> —— 近侧视时会被橙色吸积带整段盖住。
     * 抬 {@value} 格之后光柱从吸积盘上方掠过，末端仍然收在洞面（进洞那一段自然被黑盘挡住）。
     */
    public static final float RADIAL_BEAM_LIFT = 0.6F;

    /** 探针口径：任何自绘图元的边长上限（"禁止体素式大立方体"的机器判据）。 */
    public static final float EDGE_MAX = 2.0F;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  数据载体
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 一个四边形：4 顶点 × XYZ = 12 个 float，外加一个加性发光色（alpha = 亮度）。 */
    public record Quad(float[] xyz, float r, float g, float b, float a) {
        public Quad {
            if (xyz == null || xyz.length != 12) {
                throw new IllegalArgumentException("一个 quad 必须是 12 个 float（4 顶点 × XYZ），收到 "
                        + (xyz == null ? "null" : xyz.length));
            }
        }

        /** 包围盒尺寸（dx, dy, dz）。 */
        public float[] bbox() {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < 12; i += 3) {
                minX = Math.min(minX, xyz[i]);
                maxX = Math.max(maxX, xyz[i]);
                minY = Math.min(minY, xyz[i + 1]);
                maxY = Math.max(maxY, xyz[i + 1]);
                minZ = Math.min(minZ, xyz[i + 2]);
                maxZ = Math.max(maxZ, xyz[i + 2]);
            }
            return new float[]{maxX - minX, maxY - minY, maxZ - minZ};
        }

        /** 四条边的长度。 */
        public float[] edges() {
            final float[] e = new float[4];
            for (int k = 0; k < 4; k++) {
                final int i = k * 3;
                final int j = ((k + 1) % 4) * 3;
                final float dx = xyz[j] - xyz[i];
                final float dy = xyz[j + 1] - xyz[i + 1];
                final float dz = xyz[j + 2] - xyz[i + 2];
                e[k] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            return e;
        }

        /** 最长边。 */
        public float maxEdge() {
            final float[] e = edges();
            float m = 0.0F;
            for (float v : e) {
                m = Math.max(m, v);
            }
            return m;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  朝向（与我们自己的旋转旋向一致；正位 = 朝西 = 0°）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 把"生成器正位（朝西）"转到机器真实朝向所需的 <b>绕 Y 轴角度</b>。
     *
     * <p>判据：{@code Axis.YP.rotationDegrees(yaw)} 作用在 (x,z) 上，必须与
     * {@link GuixuHostGeometry#rotate(Direction, BlockPos)} 逐项相等 ——
     * 离线探针 {@code RingProbe} §3 会把四个朝向的整数点逐点比对，不是"看着像"。
     * 旋向实测：{@code NORTH} 把 (1,0) 转到 (0,1) ⇒ yaw = 270°。
     */
    public static float yawDegrees(Direction facing) {
        return switch (facing) {
            case NORTH -> 270.0F;
            case SOUTH -> 90.0F;
            case EAST -> 180.0F;
            default -> 0.0F;                 // WEST（正位）/ UP / DOWN
        };
    }

    /** 把一个角点从"控制器相对"转回"生成器相对"（{@link #rotateCorner} 的逆，矩阵转置）。 */
    public static float[] unrotateCorner(Direction facing, float offX, float offY, float offZ) {
        final int fx = facing.getStepX();
        final int fz = facing.getStepZ();
        return new float[]{
                -fx * offX - fz * offZ,
                offY,
                fz * offX - fx * offZ,
        };
    }

    /** 把一个角点从"生成器相对"转到"控制器相对"（与 {@link GuixuHostGeometry#rotate} 同一套轴）。 */
    public static float[] rotateCorner(Direction facing, float offX, float offY, float offZ) {
        final int fx = facing.getStepX();
        final int fz = facing.getStepZ();
        final int backX = -fx;
        final int backZ = -fz;
        final int sideX = fz;
        final int sideZ = -fx;
        return new float[]{
                backX * offX + sideX * offZ,
                offY,
                backZ * offX + sideZ * offZ,
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  世界坐标 ↔ 环心本地坐标（烘焙与探针共用这一份，纯算术）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 🔴 <b>「格中心在整数上」的那半格平移 = 这个坐标系能对得上的唯一原因。</b>
     *
     * <p>本地坐标的定义（2026-10-24 定稿）：
     * <pre>
     *   本地 = 生成器坐标 − 环心格(0, RING_Y, 0) − {@link #CELL_CENTER}
     *   ⇒ 环心那一格的<b>中心</b> = 本地 (0,0,0)；任何一格占 [n−0.5, n+0.5]³（n 为整数）。
     * </pre>
     *
     * <p>为什么必须是"格中心"而不是"格最小角"：
     * 我们的结构放置用的是 {@link GuixuHostGeometry#rotate}（<b>按格索引旋转</b>），
     * 而渲染是"绕一条竖轴把这个几何体<b>刚体</b>转 90°/270°"。
     * 若旋转中心落在<b>格最小角</b>上，绕它转 90° 会把一块 1×1×1 的立方体映射到<b>隔壁那一格</b>
     * （因为 8 个角点 (0,0,0)…(1,1,1) 被 permute 成 (−1,0,0)…(0,1,1)），
     * 于是 NORTH/EAST/SOUTH 三个朝向的环会整体错开整整 1 格（离线探针②实测 1.0000）。
     * 把本地原点放到<b>格中心</b>之后，{@code R(±0.5, ±0.5, ±0.5) = (±0.5, ±0.5, ±0.5)}（90° 的 4 个朝向都成立）
     * ⇒ 立方体转 90° 仍然精确落回格子，四个朝向的烘焙顶点与原真方块<b>逐格重合</b>。
     * 伪神之锻炉也是这个口径（{@code centerX = size / 2.0} ⇒ 奇数尺寸时正好是格中心）。
     */
    public static final float CELL_CENTER = 0.5F;

    /**
     * 世界方块坐标 → <b>环心本地坐标</b>（烘焙/探针共用）。
     *
     * <p>顺序是反的：先把世界坐标变成"控制器相对"，再用旋转矩阵的转置转回生成器方向，
     * 最后减掉环心（生成器 (0, {@link #RING_Y}, 0)）与 {@link #CELL_CENTER}。
     * 返回 {@code {lx, ly, lz}}：环心格中心 = (0,0,0)，每格占 [n−0.5, n+0.5]³。
     */
    public static float[] worldToLocal(BlockPos controllerPos, Direction facing, BlockPos world) {
        final int dx = world.getX() - controllerPos.getX();
        final int dy = world.getY() - controllerPos.getY();
        final int dz = world.getZ() - controllerPos.getZ();
        final int fx = facing.getStepX();
        final int fz = facing.getStepZ();
        // 反旋转（M^T；det=1 时等于逆矩阵）⇒ 得到"生成器坐标 − 控制器坐标"
        final int gx = -fx * dx - fz * dz;
        final int gz = fz * dx - fx * dz;
        return new float[]{
                CONTROLLER_X + gx - CELL_CENTER,            // 环心格中心 x = 0
                CONTROLLER_Y + dy - RING_Y - CELL_CENTER,   // 环面（格中心）y = 0
                CONTROLLER_Z + gz - CELL_CENTER,            // 环心格中心 z = 0
        };
    }

    /**
     * 环心本地坐标 → 世界坐标（{@link #worldToLocal} 的逆；探针用它做"顶点覆盖原真块 ±0.01"）。
     *
     * @return {@code {worldX, worldY, worldZ}}（浮点，未取整）
     */
    public static double[] localToWorld(BlockPos controllerPos, Direction facing,
                                        float lx, float ly, float lz) {
        final float offX = lx - CONTROLLER_X + CELL_CENTER;
        final float offY = ly + RING_Y - CONTROLLER_Y + CELL_CENTER;
        final float offZ = lz - CONTROLLER_Z + CELL_CENTER;
        final float[] r = rotateCorner(facing, offX, offY, offZ);
        return new double[]{
                controllerPos.getX() + (double) r[0],
                controllerPos.getY() + (double) r[1],
                controllerPos.getZ() + (double) r[2],
        };
    }

    /**
     * 相机位置 → <b>环心本地坐标</b>（光束横截面要"跟着相机转"，必须在同一套本地帧里算角度）。
     *
     * @param relX 相机世界坐标 − 控制器世界坐标（三轴同理）
     */
    public static float[] cameraInRingLocal(Direction facing, double relX, double relY, double relZ) {
        final float[] u = unrotateCorner(facing, (float) relX, (float) relY, (float) relZ);
        return new float[]{
                u[0] - RING_CENTER_CTRL_X - CELL_CENTER,
                u[1] - RING_CENTER_CTRL_Y - CELL_CENTER,
                u[2] - RING_CENTER_CTRL_Z - CELL_CENTER,
        };
    }

    /** 环心格在"控制器相对、已旋转"坐标里的位置（整数格偏移；<b>旋转轴</b>在它 +{@link #CELL_CENTER} 处）。 */
    public static float[] ringCenterInControllerFrame(Direction facing) {
        return rotateCorner(facing, RING_CENTER_CTRL_X, RING_CENTER_CTRL_Y, RING_CENTER_CTRL_Z);
    }

    /**
     * 环心那一格的方块坐标（控制器坐标 + 旋转后的偏移）。
     *
     * <p>⚠️ 这是<b>格坐标</b>（整数）不是旋转轴：烘好的三组绕的是穿过 {@code centerWorld + CELL_CENTER}
     * 的那条竖线（见 {@link #CELL_CENTER}），喂黑洞时也要用 {@code +CELL_CENTER}（三个轴）。
     */
    public static BlockPos centerWorld(BlockPos controllerPos, Direction facing) {
        return controllerPos.offset(GuixuHostGeometry.rotate(facing,
                new BlockPos(-CONTROLLER_X, RING_Y - CONTROLLER_Y, -CONTROLLER_Z)));
    }

    /**
     * <b>生成器坐标 → 世界坐标</b>（含四朝向旋转）：控制器相对 = {@code rotate(朝向, 生成器 − 控制器)}。
     *
     * <p>🔴 2026-10-24 修：{@link HostFxHide#hide} 原来直接把生成器坐标当成控制器相对偏移喂给
     * {@code GuixuHostGeometry.rotate}，<b>少减了一次控制器坐标 (72, 21, 0)</b> ⇒
     * 真正被写成 AIR 的格子整体偏了 {@code (-72, -11, 0)}（环根本没被藏，别处却被挖了洞）。
     * 现在藏块、烘焙、探针三处都只走这一个换算。
     */
    public static BlockPos worldFromGenerator(BlockPos controllerPos, Direction facing, int gx, int gy, int gz) {
        return controllerPos.offset(GuixuHostGeometry.rotate(facing,
                new BlockPos(gx - CONTROLLER_X, gy - CONTROLLER_Y, gz - CONTROLLER_Z)));
    }

    /** 环心本地坐标 → 世界方块坐标（探针/日志用；与 {@link #worldToLocal} 严格互逆）。 */
    public static BlockPos worldFromLocal(BlockPos controllerPos, Direction facing, float lx, float ly, float lz) {
        final double[] w = localToWorld(controllerPos, facing, lx, ly, lz);
        return new BlockPos((int) Math.round(w[0]), (int) Math.round(w[1]), (int) Math.round(w[2]));
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  6 条光束的"管"几何（纯数学；渲染状态由 HostFxDraw 负责）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 生成 6 条光束的全部四边形（<b>环心本地坐标</b>，交给 {@code HostFxDraw#drawBeams} 套 pose 后绘制）。
     *
     * <h2>🔴 2026-10-25 改几何：4 条从"内外环之间"改成"臂根 → 黑洞表面"</h2>
     * 用户原话：「我的黑洞呢？以及那四条链接黑洞的光柱呢？」「四条链接黑洞的光柱」。
     * 旧几何是 {@code r = 48 → 60}（内环外缘射到外环内缘）的<b>水平提取光束</b>，
     * 一条都没连到中心 ⇒ 用户看到的是"四条贴着内环外侧、跟黑洞无关的柱子"。
     * <p>现在：
     * <ul>
     *   <li><b>4 条径向</b>：起点 = {@link #ARM_ROOT_R}（= 47，内环上 4 个连接臂的根部，间隔 90°），
     *       终点 = {@code holeSurfaceR}（黑洞表面，默认 {@link #HOLE_SURFACE_R} 2.6 格），
     *       走<b>环面高度</b>（本地 y = 0）⇒ 4 条光柱在环平面里汇入黑洞；</li>
     *   <li><b>2 条极轴</b>：从黑洞表面沿 ±Y 往外 {@link #POLAR_LENGTH} 格（也从洞面出发，不再是悬空的）。</li>
     * </ul>
     * 手法不变（同族 mod 秘法涡流 {@code LightSpike} 的实测参数）：圆柱壳环带，
     * 沿长度 {@link #BEAM_SEGMENTS} 段 × 圆周 {@link #BEAM_FACETS} 段 = 每条 384 quads；
     * 半径沿长度走一条<b>五次贝塞尔</b>（{@link #taperRadius}，峰值直径 0.5）；
     * 亮度包络 {@code 1 − 0.4·|2t−1|}；纯顶点色 {@link #BEAM_COLOR}（180,220,255）、无贴图。
     * 横截面的起始角跟着相机走（{@code phi}），所以任何角度都是一根有厚度的细管。
     *
     * @param camLocalX 相机在环心本地坐标里的位置（{@link #cameraInRingLocal}）
     */
    public static List<Quad> buildBeamQuads(float camLocalX, float camLocalY, float camLocalZ) {
        return buildBeamQuads(camLocalX, camLocalY, camLocalZ, HOLE_SURFACE_R);
    }

    /**
     * 同上，但黑洞表面半径由调用方给（渲染器每帧传运行期实测值）。
     *
     * @param holeSurfaceR 黑洞黑盘半径（格）；会被夹到 {@code [0.6, ARM_ROOT_R − 0.5]}，
     *                     防止配置把 scale 调得离谱时"光柱反向"或"穿过环"
     */
    public static List<Quad> buildBeamQuads(float camLocalX, float camLocalY, float camLocalZ,
                                            float holeSurfaceR) {
        final float tipR = Math.max(0.6F, Math.min(holeSurfaceR, ARM_ROOT_R - 0.5F));
        final List<Quad> out = new ArrayList<>(beamQuadCount());
        // 4 条径向光柱：🔴 2026-10-24 傍晚 —— 起点从"臂根 r=47"改成【解压机的圆孔（喷口）】。
        //   用户原话：「4 条光束从它的圆孔里发射」。
        //   起点<b>不是手填坐标</b>：mdNozzle(k) 由 MD_CENTER_R / MD_TILT_DEG / MD_NOZZLE_LOCAL_Y
        //   三个常量按"rotateX(tilt) → rotateY(yaw) → +center"算出来（与 drawMatterDecompressors
        //   的 pose 链逐项对应）。判据：beamEndpoints(k) 的前三维与 mdNozzle(k) 完全相等（见自检）。
        //   终点不变：黑洞表面 r=tipR、环面抬高 RADIAL_BEAM_LIFT。
        for (int k = 0; k < 4; k++) {
            final float[] nz = mdNozzle(k);
            final double ang = Math.toRadians(MD_AZIMUTH_DEG[Math.floorMod(k, MD_COUNT)]);
            final float cos = (float) Math.cos(ang);
            final float sin = (float) Math.sin(ang);
            addBeamShell(out,
                    nz[0], nz[1], nz[2],
                    cos * tipR, RADIAL_BEAM_LIFT, sin * tipR,
                    camLocalX, camLocalY, camLocalZ);
        }
        // 黑洞上下两条：从洞面沿 ±Y 往外
        for (int sign = -1; sign <= 1; sign += 2) {
            addBeamShell(out,
                    0.0F, sign * tipR, 0.0F,
                    0.0F, sign * (tipR + POLAR_LENGTH), 0.0F,
                    camLocalX, camLocalY, camLocalZ);
        }
        return out;
    }

    /**
     * 每根光柱的两端（环心本地坐标）—— <b>离线预览与探针的唯一口径</b>：
     * {@code [i][0..2]} = 起点，{@code [i][3..5]} = 终点；0..3 = 4 条径向（0°/90°/180°/270°），4..5 = 下/上极轴。
     */
    public static float[][] beamEndpoints(float holeSurfaceR) {
        final float tipR = Math.max(0.6F, Math.min(holeSurfaceR, ARM_ROOT_R - 0.5F));
        final float[][] e = new float[6][6];
        for (int k = 0; k < 4; k++) {
            final float[] nz = mdNozzle(k);
            final double ang = Math.toRadians(MD_AZIMUTH_DEG[Math.floorMod(k, MD_COUNT)]);
            final float cos = (float) Math.cos(ang);
            final float sin = (float) Math.sin(ang);
            // 起点 = 喷口（与 buildBeamQuads 用同一个 mdNozzle ⇒ 两者不可能不一致）
            e[k] = new float[]{nz[0], nz[1], nz[2],
                    cos * tipR, RADIAL_BEAM_LIFT, sin * tipR};
        }
        e[4] = new float[]{0.0F, -tipR, 0.0F, 0.0F, -(tipR + POLAR_LENGTH), 0.0F};
        e[5] = new float[]{0.0F, tipR, 0.0F, 0.0F, tipR + POLAR_LENGTH, 0.0F};
        return e;
    }

    /**
     * 半径沿长度的五次贝塞尔曲线（控制半径 = {@link #BEAM_TAPER}），峰值<b>严格</b> = {@link #BEAM_PEAK_RADIUS}。
     *
     * <p>{@code B(t) = Σ C(5,i)·(1−t)^(5−i)·t^i·R_i}，再除以形状峰值 {@link #TAPER_PEAK} 归一、乘半径峰值。
     * 两端收到 ≈0.025 格（收尖、不封口）。公开给离线探针，用来量"半径峰值是不是 0.25 / 直径有没有超 0.5"。
     */
    public static float taperRadius(float t) {
        return BEAM_PEAK_RADIUS * (taperShape(t) / TAPER_PEAK);
    }

    /** 未归一的贝塞尔形状（峰值 = {@link #TAPER_PEAK}）。 */
    private static float taperShape(float t) {
        final float x = Math.max(0.0F, Math.min(1.0F, t));
        final float u = 1.0F - x;
        final float u2 = u * u;
        final float u3 = u2 * u;
        final float u4 = u3 * u;
        final float u5 = u4 * u;
        final float t2 = x * x;
        final float t3 = t2 * x;
        final float t4 = t3 * x;
        final float t5 = t4 * x;
        return u5 * BEAM_TAPER[0]
                + 5.0F * u4 * x * BEAM_TAPER[1]
                + 10.0F * u3 * t2 * BEAM_TAPER[2]
                + 10.0F * u2 * t3 * BEAM_TAPER[3]
                + 5.0F * u * t4 * BEAM_TAPER[4]
                + t5 * BEAM_TAPER[5];
    }

    /** 亮度包络 {@code 1 − 0.4·|2t−1|}：两端 0.6、中间 1.0（两端暗、中间亮）。 */
    public static float taperBrightness(float t) {
        return 1.0F - 0.4F * Math.abs(2.0F * t - 1.0F);
    }

    /**
     * 一根圆柱壳（沿 A→B 切 {@link #BEAM_SEGMENTS} 段、每段 {@link #BEAM_FACETS} 个四边形）。
     *
     * <p>横截面基向量 {@code u/v} 由轴向挑一组正交向量得到；起始角
     * {@code phi = atan2(相机在 v 上的分量, 相机在 u 上的分量)} 让第 0 片正对相机。
     * 半径取 {@link #taperRadius}、亮度（alpha）取 {@link #taperBrightness}。
     */
    private static void addBeamShell(List<Quad> out,
                                     float ax, float ay, float az, float bx, float by, float bz,
                                     float camX, float camY, float camZ) {
        final float dx = bx - ax;
        final float dy = by - ay;
        final float dz = bz - az;
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4F) {
            return;
        }
        final float nx = dx / len;
        final float ny = dy / len;
        final float nz = dz / len;
        // 挑一个和轴向不平行的参考向量，构造正交基 (u, v)
        float refX = 0.0F;
        float refY = 1.0F;
        float refZ = 0.0F;
        if (Math.abs(ny) > 0.9F) {
            refX = 1.0F;
            refY = 0.0F;
            refZ = 0.0F;
        }
        float ux = refY * nz - refZ * ny;
        float uy = refZ * nx - refX * nz;
        float uz = refX * ny - refY * nx;
        final float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul;
        uy /= ul;
        uz /= ul;
        final float vx = ny * uz - nz * uy;
        final float vy = nz * ux - nx * uz;
        final float vz = nx * uy - ny * ux;
        // 相机在该截面平面里的方位角（用光束中点当参考点）
        final float mx = (ax + bx) * 0.5F;
        final float my = (ay + by) * 0.5F;
        final float mz = (az + bz) * 0.5F;
        final float cu = (camX - mx) * ux + (camY - my) * uy + (camZ - mz) * uz;
        final float cv = (camX - mx) * vx + (camY - my) * vy + (camZ - mz) * vz;
        final float phi = (float) Math.atan2(cv, cu);
        final float step = (float) (Math.PI * 2.0D / BEAM_FACETS);
        for (int seg = 0; seg < BEAM_SEGMENTS; seg++) {
            final float t0 = (float) seg / BEAM_SEGMENTS;
            final float t1 = (float) (seg + 1) / BEAM_SEGMENTS;
            final float r0 = taperRadius(t0);
            final float r1 = taperRadius(t1);
            final float a0 = taperBrightness(t0);
            final float a1 = taperBrightness(t1);
            final float c0x = ax + nx * len * t0;
            final float c0y = ay + ny * len * t0;
            final float c0z = az + nz * len * t0;
            final float c1x = ax + nx * len * t1;
            final float c1y = ay + ny * len * t1;
            final float c1z = az + nz * len * t1;
            for (int f = 0; f < BEAM_FACETS; f++) {
                final float th0 = phi + step * f;
                final float th1 = phi + step * (f + 1);
                final float cd0 = (float) Math.cos(th0);
                final float sd0 = (float) Math.sin(th0);
                final float cd1 = (float) Math.cos(th1);
                final float sd1 = (float) Math.sin(th1);
                final float o00x = ux * cd0 + vx * sd0;
                final float o00y = uy * cd0 + vy * sd0;
                final float o00z = uz * cd0 + vz * sd0;
                final float o01x = ux * cd1 + vx * sd1;
                final float o01y = uy * cd1 + vy * sd1;
                final float o01z = uz * cd1 + vz * sd1;
                // 四边形：环 0 角 0 → 环 0 角 1 → 环 1 角 1 → 环 1 角 0
                out.add(new Quad(new float[]{
                        c0x + o00x * r0, c0y + o00y * r0, c0z + o00z * r0,
                        c0x + o01x * r0, c0y + o01y * r0, c0z + o01z * r0,
                        c1x + o01x * r1, c1y + o01y * r1, c1z + o01z * r1,
                        c1x + o00x * r1, c1y + o00y * r1, c1z + o00z * r1,
                }, BEAM_COLOR[0], BEAM_COLOR[1], BEAM_COLOR[2], (a0 + a1) * 0.5F));
            }
        }
    }

    /** 一帧自绘图元数（日志/预算用）。 */
    public static int beamQuadCount() {
        return 6 * BEAM_SEGMENTS * BEAM_FACETS;
    }

    /** 预算说明（{@code draw} 日志里直接打这一行）。 */
    public static String budget() {
        return "光束 6 条（4 条径向：臂根 r=" + (int) ARM_ROOT_R + " → 黑洞表面 + 上下 2 条极轴）× "
                + BEAM_SEGMENTS + " 段 × " + BEAM_FACETS
                + " 片圆 = " + beamQuadCount() + " 四边形 / " + (beamQuadCount() * 4) + " 顶点"
                + "（五次贝塞尔、峰值直径 " + BEAM_PEAK_DIAMETER + "，加性混合、不写深度、无贴图）";
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  需要"藏起来"的真方块（内环带 + 8 艘飞船）—— 烘焙的输入就是同一份台账
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 需要隐藏的方块位置（生成器坐标，未旋转）。
     *
     * <p>包含三类（= "要转的三组"的<b>全部</b>真方块）：
     * <ol>
     *   <li><b>内环带</b> —— 与生成器 ③ 段<b>逐字一致</b>：{@code |hypot(x,z) − 48| ≤ 1.1} 取格，
     *       并在 {@code (x+z) % 4 == 0} 的位置连上下各一格（发光肋）；</li>
     *   <li><b>4 条辐条（连接臂）</b> —— 生成器 ④ 段，见 {@link #armCells()}。
     *       🔴 2026-10-25 新增：这一批<b>以前完全没进台账</b>，就是用户说的
     *       「内环/连接臂那一圈仍露出成串的天蓝/白色方块」（臂根 8 格 hv 白机壳 ×4、
     *       中脊 rib、尖端 3×3 的 sea_lantern 天蓝 ×8，共 240 格）；</li>
     *   <li><b>8 艘飞船</b> —— 与生成器 {@code shipShape} 同一规则（4 个方位 × 上下），
     *       逐格复现，因为它们要"沿环转动"，真方块必须让位。</li>
     * </ol>
     *
     * <p>⚠️ 这些位置由 {@link HostFxHide} 写成 AIR（客户端），未成型时按台账写回；
     * 判据见 {@link HostFxHideAudit#report()}：{@code 台账格数 == 要转的格数、漏藏 = 0}。
     */
    public static List<BlockPos> hideTargets() {
        final List<BlockPos> out = new ArrayList<>(4096);
        // ① 内环带
        final int lim = (int) Math.ceil(INNER_R) + 2;
        for (int x = -lim; x <= lim; x++) {
            for (int z = -lim; z <= lim; z++) {
                if (Math.abs(Math.hypot(x, z) - INNER_R) > INNER_TOLERANCE) {
                    continue;
                }
                out.add(new BlockPos(x, RING_Y, z));
                if (((x + z) & 3) == 0) {
                    out.add(new BlockPos(x, RING_Y + 1, z));
                    out.add(new BlockPos(x, RING_Y - 1, z));
                }
            }
        }
        // ② 4 条辐条（连接臂）—— 以前漏的就是这一批
        out.addAll(armCells());
        // ③ 8 艘飞船（逐格复现 shipShape）
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            final int dx = d[0];
            final int dz = d[1];
            final int tx = -dz;
            final int tz = dx;
            for (int up = 1; up >= 0; up--) {
                for (int u = -SHIP_TANGENTIAL_HALF; u <= SHIP_TANGENTIAL_HALF; u++) {
                    final int a = Math.abs(u);
                    final int limR = a >= 3 ? 1 : SHIP_RADIAL_HALF;
                    final int limV = a >= 3 ? 1 : (a == 2 ? 2 : SHIP_HULL_TOP);
                    for (int r = -limR; r <= limR; r++) {
                        for (int v = 0; v <= limV; v++) {
                            out.add(shipCell(dx, dz, tx, tz, u, r, v, up == 1));
                        }
                    }
                }
                // 甲板 3×3
                for (int u = -1; u <= 1; u++) {
                    for (int r = -1; r <= 1; r++) {
                        out.add(shipCell(dx, dz, tx, tz, u, r, SHIP_DECK_TOP, up == 1));
                    }
                }
                // 左右吊舱
                out.add(shipCell(dx, dz, tx, tz, -SHIP_TANGENTIAL_HALF, 0, 1, up == 1));
                out.add(shipCell(dx, dz, tx, tz, SHIP_TANGENTIAL_HALF, 0, 1, up == 1));
            }
        }
        return out;
    }

    // ---------------- 4 条辐条（= 连接臂）：生成器 ④ 段逐格复现 ----------------

    /** 辐条格子总数（4 个方向 × (臂根 8 + 斜撑 40 + 中脊 4 + 尖端 8)） = 240。 */
    public static final int ARM_CELLS_PER_DIR = 60;

    /**
     * 4 条辐条的<b>全部</b>格子（生成器坐标）。
     *
     * <p>逐字复现 {@code gen5.mjs} ④ 段：
     * <pre>
     *   for (const [dx,dz] of [[1,0],[-1,0],[0,1],[0,-1]]) {
     *     const at = (t,u,v,c) =&gt; (dx !== 0 ? put(dx*t, ringY+v, u, c) : put(u, ringY+v, dz*t, c))
     *     u,v ∈ [-1,1] 去掉 (0,0)   → at(innerR-1, u, v)         // 臂根 3×3 法兰（hv 机壳）
     *     [su,sv] ∈ 四个对角        → at(innerR-1+k, su·(1+0.42k), sv·(1+0.42k))，k=0..9   // 斜撑
     *     t = innerR+5 .. innerR+8  → at(t, 0, 0)                // 中脊
     *     u,v ∈ [-1,1] 去掉 (0,0)   → at(innerR+9, u, v)         // 尖端 3×3 天蓝海晶灯
     *   }
     * </pre>
     */
    public static List<BlockPos> armCells() {
        final List<BlockPos> out = new ArrayList<>(ARM_CELLS_PER_DIR * 4);
        final int t0 = (int) INNER_R - 1;                 // 47 = 臂根
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            final int dx = d[0];
            final int dz = d[1];
            for (int u = -1; u <= 1; u++) {
                for (int v = -1; v <= 1; v++) {
                    if (u == 0 && v == 0) {
                        continue;
                    }
                    out.add(armAt(dx, dz, t0, u, v));
                }
            }
            for (int[] s : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                for (int k = 0; k <= 9; k++) {
                    final int lat = (int) Math.round(s[0] * (1.0D + k * 0.42D));
                    final int up = (int) Math.round(s[1] * (1.0D + k * 0.42D));
                    out.add(armAt(dx, dz, t0 + k, lat, up));
                }
            }
            for (int t = (int) INNER_R + 5; t <= (int) INNER_R + 8; t++) {
                out.add(armAt(dx, dz, t, 0, 0));
            }
            for (int u = -1; u <= 1; u++) {
                for (int v = -1; v <= 1; v++) {
                    if (u == 0 && v == 0) {
                        continue;
                    }
                    out.add(armAt(dx, dz, (int) INNER_R + 9, u, v));
                }
            }
        }
        return out;
    }

    /** 生成器 {@code at(t,u,v)} 的 Java 版本：{@code dx≠0 ⇒ (dx·t, ringY+v, u)}，否则 {@code (u, ringY+v, dz·t)}。 */
    private static BlockPos armAt(int dx, int dz, int t, int lat, int up) {
        return dx != 0 ? new BlockPos(dx * t, RING_Y + up, lat) : new BlockPos(lat, RING_Y + up, dz * t);
    }

    /** {@code shipShape} 的局部坐标 {@code (u,r,v)} → 生成器坐标（与 gen5.mjs 逐字一致）。 */
    private static BlockPos shipCell(int dx, int dz, int tx, int tz, int u, int r, int v, boolean up) {
        final double rad = SHIP_RADIAL_CENTER + r;
        final int x = (int) Math.round(dx * rad + tx * u);
        final int z = (int) Math.round(dz * rad + tz * u);
        final int y = up ? (RING_Y + RING_TRACK_DY + 1 + v) : (RING_Y - RING_TRACK_DY - 1 - v);
        return new BlockPos(x, y, z);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  8 艘飞船：🔴 2026-10-24 傍晚【已回到"真方块跟着转"】—— 这里不再有任何自绘模型
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话（2026-10-24 傍晚）：「**8 艘飞船不需要你自己画，就用原来的方块就行**」。
     *
     * 上一轮（2026-10-25）这里有一整套 21 件/艘的自绘 shipBoxes 模型 + `SHIP_MAX_T/R/V` 判据 +
     * `shipParts()/shipBoxesLocal()/shipCenter()/shipYawDeg()`。那套东西<b>整批删除</b>：
     *   · 真值本来就是生成器 `gen5.mjs` 的 `shipShape`（每艘 111 格，4 方位 × 上下 = 888 格），
     *     这些格已经在 `hideTargets()` 里、已被写成 AIR ⇒ 只要<b>按台账烘焙</b>就能原样转起来
     *     （与内环带同一套 {@link HostFxBaked} 机制），根本不需要另画一个模型；
     *   · 位置/朝向/尺寸都回到原样：生成器里真飞船占 rad 61..65（`shipRadialCenter = 63`）、
     *     高度 5 层，就是用户确认的「外环外侧 r=63」；
     *   · 角速度仍是上组 +9°/s、下组 −9°/s（{@link #shipOrbitDeg}，这条用户确认过、保留）。
     *
     * ⚠️ 别再把它改回自绘：那会同时让"藏了 888 格真方块"白藏、并且尺寸对不上原始造型。
     */

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  内环带的「加厚箍」与「配重脊」（纯渲染；结构 .bin 一格不动）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 一个<b>轴对齐</b>小方盒：中心 + 三个半长 + 一个纯顶点色。
     *
     * <p>环的「加厚箍 / 配重脊」用它（{@link #ringGirdleBoxes} / {@link #ringSpineBoxes}）。
     * ⚠️ 2026-10-24 傍晚：飞船的自绘模型已经整批撤掉（改真方块烘焙），但<b>这个 record 保留</b>
     * —— 环附加件仍在用，删掉会连带编译不过。
     */
    public record Box(float x, float y, float z, float hx, float hy, float hz,
                      float red, float green, float blue) {}

    /**
     * 加厚箍：内环上下各一圈连续小方块（半径 {@link #INNER_R}，本地 y = ±1.55）。
     *
     * <h2>为什么加这个（2026-10-25 父代理给的伪神三环实测基准）</h2>
     * 伪神三个环的真实尺寸是 <b>3–4 格厚、5 层高</b>（127×29×127 / 111×23×111 / 94×27×95，
     * 非空方块 6044/6004/6286，方块中心半径 ≈62/54/46）；我们的内环是
     * 「<b>1 格厚 + 3 层 + 每 4 格一根发光肋</b>」，从结构上就比它单薄。
     * <p>而结构 {@code .bin} 与多方块图案<b>不许动</b>，所以这里在<b>渲染侧</b>补两圈箍，
     * 把环带的视觉厚度从 3 层顶到 ≈5 层（1.55±0.34 ⇒ 1.21..1.89，正好压住 ±1 的发光肋）。
     */
    public static List<Box> ringGirdleBoxes() {
        final int n = 96;
        final List<Box> out = new ArrayList<>(n * 2);
        for (int i = 0; i < n; i++) {
            final double a = Math.PI * 2.0D * i / n;
            final float x = (float) (INNER_R * Math.cos(a));
            final float z = (float) (INNER_R * Math.sin(a));
            for (int sy = -1; sy <= 1; sy += 2) {
                out.add(new Box(x, sy * 1.55F, z, 0.30F, 0.34F, 0.30F, 0.55F, 0.62F, 0.72F));
            }
        }
        return out;
    }

    /**
     * 配重脊：内环在本地 <b>−X</b>（方位 180°）那一段压一块实心重物 —— 对应伪神每个环上那段
     * 「只原地翻转、不绕圈」的脊（他们的环绕 X 轴，脊正好落在 X 轴上）。
     *
     * <p>⚠️ <b>语义差异要写明</b>：我们的环绕 <b>Y</b> 轴自转，环上任何一点都会绕圈，
     * 所以这条脊<b>会跟着转</b>；它在这里的作用是"打破均匀细环、让转动看得出来"（配重感），
     * 而不是伪神那种"钉在轴上的静止配重"。
     */
    public static List<Box> ringSpineBoxes() {
        final List<Box> out = new ArrayList<>(20);
        for (int iy = -2; iy <= 2; iy++) {
            for (int it = -2; it <= 2; it++) {
                if (Math.abs(iy) == 2 && Math.abs(it) == 2) {
                    continue;                       // 四角削掉，边缘不方
                }
                out.add(new Box(-(float) INNER_R, iy * 0.95F, it * 0.95F,
                        0.55F, 0.46F, 0.46F, 1.00F, 0.82F, 0.55F));
            }
        }
        return out;
    }

    /** 环带附加件总数（日志用）。 */
    public static int ringExtraBoxCount() {
        return ringGirdleBoxes().size() + ringSpineBoxes().size();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  4 座「物质解压机」—— 替换 4 处辐条方块堆（用户 2026-10-25 口径）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话（2026-10-25，两条追加要求）：
     *   「那个物质解压机是要放在我画红框的 4 个地方的……渲染的时候你可以把那堆方块隐藏了，
     *     然后换上物质解压机的渲染」
     *   「然后你那 4 座物质解压机做得精细一点啊，还可以适当地改编，让它更好地契合内环的结构」
     *
     * 4 处 = 4 条辐条（连接臂）的方块堆，实测（temp/host-fx/probe/SpokeEnds.java，离线量测）：
     *   每团 60 格（去重 56 格：4 个根法兰格与斜撑 k=0 重合）、格中心 x 47..57 / y 5..15 / z −5..5，
     *   **方块外沿包围盒 11.0 × 11.0 × 11.0 格**，团心 = (52.0, 10.0, 0)（四个方位各转 90°），
     *   径向 47..57 格。⇒ 机器尺寸照这个包围盒定：不许超过它（用户口径）。
     */

    /** 4 座机器的方位角（度）：与辐条 4 个方向（+X / −X / +Z / −Z）一一对应。 */
    public static final float[] MD_AZIMUTH_DEG = {0.0F, 180.0F, 90.0F, 270.0F};

    /**
     * 机器轴线在环面上的落点半径（格）。
     *
     * <p>实测团心 = 52.0（辐条团的外沿包围盒 46.5..57.5 的中点正好也是 52.0）。
     * 取 <b>52.5</b>：内倾之后底座会往外挪 ≈0.6 格，落在辐条端面（r = 57 那圈 3×3 格构）内侧，
     * 而机器<b>最外侧</b>仍只有 56.9 ⇒ 整座不超出红框（57.5）。
     */
    public static final float MD_CENTER_R = 52.5F;

    /** 机器轴线在环面上的高度（格）：环面（生成器 y = {@link #RING_Y} 的格中心）= 0。 */
    public static final float MD_CENTER_Y = 0.0F;

    /**
     * <b>姿态</b>：机器绕自己的横向轴转 {@value} 度 ⇒ <b>轴线沿径向、喷口朝环心（黑洞）</b>。
     *
     * <h2>🔴 2026-10-24 傍晚用户口径（"横过来"）</h2>
     * 用户原话：「<b>物质解压机要「横过来」、口朝着黑洞；4 条光束从它的圆孔里发射</b>」。
     * <p>{@code -90°} 不是拍出来的数：本地 +Y 是塔的向上轴（顶端那颗 {@link #MD_NOZZLE_LOCAL_Y}
     * 高的亮核 = 圆孔/喷口），绕本地 +X 转 −90° 之后 +Y 精确落在 <b>−Z</b> 上，
     * 而 −Z 就是"径向朝环心"⇒ 轴线沿径向、喷口正对黑洞，一步到位。
     * <p>为什么是 −90 而不是 +90：+90 会把喷口转到 +Z（朝外，背对黑洞）。
     * <p>为什么仍然保留"从常量算"：{@link #mdNozzle(int)} 走的是同一套
     * rotateX(tilt) → rotateY(yaw) → +center 变换，改 {@code MD_TILT_DEG} 时喷口会跟着走，
     * 不需要任何手填坐标（判据：{@link #selfCheckNozzleBeam()}）。
     *
     * <p>为什么不照抄群星的正立塔（用户：「可以按我们的结构做朝向与姿态改编……」）：
     * <ol>
     *   <li><b>底座坐得住</b>：内倾之后底座正好压在辐条端面（r≈56.5）上，塔身朝环心方向探出，
     *       视觉上"长在辐条上"而不是"浮在环外"；</li>
     *   <li><b>接上现成的 4 条径向光柱</b>：光柱的起点是臂根 r = {@link #ARM_ROOT_R}；
     *       机器内倾之后<b>内侧面</b>让到 r≈49 以外、顶口朝内 ⇒ 光柱看起来是从这台机器里抽出来的；</li>
     *   <li><b>和环的语汇一致</b>：环是水平的、辐条是径向的，竖塔+内倾既不挡环面，也不和环同向。</li>
     * </ol>
     * 负角 = 绕本地 +X 轴负向转 ⇒ 本地 +Y（塔的向上轴）朝 −Z（环心方向）偏。
     */
    public static final float MD_TILT_DEG = -90.0F;

    /** 单座盒数上限（用户口径：每座 40~80 件；仍单件 ≤1 格）。 */
    public static final int MD_MAX_BOXES = 80;
    /** 单座盒数下限（用户口径：不要退化成 21 件那种"小箱子"）。 */
    public static final int MD_MIN_BOXES = 40;

    /** 材质色（>1 的通道画的时候会被夹到 1）。 */
    // 🔴 2026-10-25 自看后调过一次对比度：第一版机壳 0.63 与箍 0.80 太接近 ⇒ 自看时"三道箍"糊在鼓身上，
    //    现在把机壳压暗、箍提到近白、收口压更暗，三者一眼分得开（这是"剪影能读出鼓+箍"的前提）。
    private static final float[] MD_SHELL = {0.53F, 0.58F, 0.67F};        // 鼓身机壳（压暗）
    private static final float[] MD_SHELL_DARK = {0.38F, 0.43F, 0.51F};   // 收口 / 侧舱外壳（更暗）
    private static final float[] MD_STEEL = {0.93F, 0.96F, 1.00F};        // 钢箍（近白，凸出鼓面）
    private static final float[] MD_LIGHT = {1.00F, 0.62F, 0.20F};        // 橙色光带（提亮一点）
    private static final float[] MD_CORE = {1.00F, 0.90F, 0.64F};         // 顶口亮核 / 鼓心
    private static final float[] MD_POD = {0.70F, 0.58F, 0.48F};          // 侧面外挂小舱（暖钢）

    /**
     * 机器的一个分件：轴对齐小方盒 <b>+ 一个绕 Y 的偏航</b>（度）。
     *
     * <h2>🔴 为什么必须有 yaw（2026-10-25 自看之后才发现的硬限制）</h2>
     * 只用"轴对齐"的方盒去围一圈，{@code n} 件之间的接触点只有<b>一个角</b> ⇒
     * 每两件之间留一个菱形洞：12 件 @R=1.9 的鼓身在近距离看是<b>一张华夫饼</b>（第一版出图实测），
     * 完全不像"鼓"。把每件绕圆心转成<b>切向</b>（{@code yaw = 90° − 方位角}）之后，
     * 相邻件是"边对边"，弦长 1.17 对件宽 1.00 ⇒ 只留 0.17 的竖缝，鼓身是闭合的。
     * <p>仍然是同一个图元（{@code emitBox} 的 6 个面）+ 一次 {@code Axis.YP} 旋转，
     * <b>没有</b>引入贴图、没有裸 GL —— 与飞船/环附加件的渲染路径完全同构。
     */
    public record MdBox(float x, float y, float z, float hx, float hy, float hz, float yawDeg,
                        float red, float green, float blue) {}

    /**
     * 单座「物质解压机」分件表（<b>塔自己的一帧</b>，还没套方位/内倾）。
     *
     * <p>塔本地轴系：<b>+X = 横向、+Y = 向上、+Z = 径向朝外</b>（远离环心那一侧）。
     * 每个分件的半长 ≤ 0.5 ⇒ <b>单件 ≤ 1 格</b>（{@link #mdBox} 里当场校验）。
     *
     * <h2>造型（学群星那台的语汇，但按我们的尺度重排）</h2>
     * 群星那台（{@code temp/stellaris-decompressor/png/geometry_iso.png}，反编译 mesh 渲染）读出来是
     * <b>「竖直鼓身 + 一排钢箍 + 卡在箍缝里的橙光带 + 顶盖收口 + 侧面外挂舱」</b>。逐条落地：
     * <ol>
     *   <li><b>鼓身 2 段</b>（各 10 件切向薄壳，R=1.75，闭合）—— 段与段之间留 1.7 格的"腰"；</li>
     *   <li><b>钢箍 3 道</b>（各 10 件，R=2.30，比鼓面凸出 0.33 格）—— 剪影上三道亮箍；</li>
     *   <li><b>橙光带 2 段</b>（各 8 件，R=2.05，夹在箍与鼓之间）—— 群星那台的橙光正是卡在缝里的；</li>
     *   <li><b>顶部收口</b>（6 件暗钢 + 中央亮核，模拟它的八角顶盖 + 烟囱）；</li>
     *   <li><b>底部收口</b>（5 件更宽的暗钢支座）；</li>
     *   <li><b>2 个侧面外挂小舱</b>（横向 ±X，贴在下段鼓身外壁上）。</li>
     * </ol>
     */
    public static List<MdBox> matterDecompressorBoxes() {
        final List<MdBox> out = new ArrayList<>(MD_MAX_BOXES);
        // ① 底部收口：4 件更宽的暗钢支座（琴键状，读作"卡爪"而不是一圈墙）
        mdRing(out, 4, 2.10F, -4.10F, 45.0F, 0.50F, 0.30F, 0.46F, MD_SHELL_DARK);
        // ② 鼓身 3 段（各 10 件切向薄壳 @ R=1.75：弦 1.08 对件宽 1.00 ⇒ 只留 0.08 竖缝 = 闭合的鼓；
        //    单件高 1.0 格是硬上限的一半，所以"分段"就靠 3 段 × 1.0 格摞起来）
        mdRing(out, 10, 1.75F, -2.60F, 15.0F, 0.50F, 0.50F, 0.22F, MD_SHELL);
        mdRing(out, 10, 1.75F, -0.90F, 15.0F, 0.50F, 0.50F, 0.22F, MD_SHELL);
        mdRing(out, 10, 1.75F, 0.80F, 15.0F, 0.50F, 0.50F, 0.22F, MD_SHELL);
        // ③ 钢箍 3 道（各 9 件 @ R=2.30：鼓面 1.97 ⇒ 箍外沿 2.72，凸出 0.75 格，剪影三道亮箍）
        mdRing(out, 9, 2.30F, -3.40F, 0.0F, 0.44F, 0.24F, 0.42F, MD_STEEL);
        mdRing(out, 9, 2.30F, 0.00F, 20.0F, 0.44F, 0.24F, 0.42F, MD_STEEL);
        mdRing(out, 9, 2.30F, 2.45F, 0.0F, 0.44F, 0.24F, 0.42F, MD_STEEL);
        // ④ 橙色光带 2 段（各 6 件 @ R=2.05：卡在鼓面与箍之间；⚠️ 6 件在 60° 步进下留 1.2 格的缝
        //    ⇒ 读作"分段光带"而不是连续发光环。要做到连续得 10 件/段 ⇒ 总件数 88（超 80 预算），
        //    已在报告「没做到」里列出，等你点头再加。）
        mdRing(out, 6, 2.05F, -1.70F, 30.0F, 0.50F, 0.22F, 0.26F, MD_LIGHT);
        mdRing(out, 6, 2.05F, 1.70F, 30.0F, 0.50F, 0.22F, 0.26F, MD_LIGHT);
        // ⑤ 顶部收口：4 件暗钢 + 顶口亮核（群星那台的八角顶盖 + 中央烟囱）
        mdRing(out, 4, 2.10F, 3.20F, 45.0F, 0.50F, 0.28F, 0.46F, MD_SHELL_DARK);
        mdBox(out, 0.0F, 3.70F, 0.0F, 0.26F, 0.30F, 0.26F, 0.0F, MD_CORE);
        // ⑥ 两个侧面外挂小舱（横向 ±X，坐在下段鼓身外壁上）
        for (int sx = -1; sx <= 1; sx += 2) {
            mdBox(out, sx * 2.45F, -2.60F, 0.0F, 0.44F, 0.50F, 0.50F, 0.0F, MD_POD);
        }
        return out;
    }

    /** 往分件表里加一件（<b>单件 ≤1 格的唯一闸门</b>：半长超过 0.5 直接抛）。 */
    private static void mdBox(List<MdBox> out, float x, float y, float z,
                              float hx, float hy, float hz, float yawDeg, float[] color) {
        if (hx > 0.5F + 1.0E-6F || hy > 0.5F + 1.0E-6F || hz > 0.5F + 1.0E-6F) {
            throw new IllegalStateException("单件必须 ≤1 格（半长 ≤0.5），收到 "
                    + hx + " / " + hy + " / " + hz);
        }
        out.add(new MdBox(x, y, z, hx, hy, hz, yawDeg, color[0], color[1], color[2]));
    }

    /**
     * 一圈 {@code n} 件小方盒（水平面 xz 上的圆，半径 {@code radius}，高 {@code y}）。
     *
     * <p>每件的<b>偏航都朝切向</b>（{@code yaw = 90° − 方位角}，与 {@link #shipYawDeg} 同一口径），
     * 于是"件宽"沿圆周方向、相邻件边对边 —— 这是鼓身能闭合的唯一原因。
     * {@code hx} = 切向半宽、{@code hz} = 径向半厚。
     */
    private static void mdRing(List<MdBox> out, int n, float radius, float y, float phaseDeg,
                               float hx, float hy, float hz, float[] color) {
        for (int i = 0; i < n; i++) {
            final double aDeg = phaseDeg + 360.0D * i / n;
            final double a = Math.toRadians(aDeg);
            mdBox(out, (float) (radius * Math.cos(a)), y, (float) (radius * Math.sin(a)),
                    hx, hy, hz, (float) (90.0D - aDeg), color);
        }
    }

    /**
     * 单座「物质解压机」分件表（<b>塔自己的一帧</b>，还没套方位/内倾）。
     *
     * <p>塔本地轴系：<b>+X = 横向、+Y = 向上、+Z = 径向朝外</b>（远离环心那一侧）。
     * 所有分件都是轴对齐小方盒，半长全部 ≤ 0.5 ⇒ <b>单件 ≤ 1 格</b>（{@link #mdBox} 里当场校验）。
     *
     * <h2>造型（学群星那台的语汇，但按我们的尺度重排）</h2>
     * 群星那台（{@code temp/stellaris-decompressor/png/geometry_iso.png}，反编译 mesh 渲染）读出来是
     * <b>「竖直鼓身 + 上下一排钢箍 + 一道宽橙光带 + 顶盖收口 + 侧面外挂舱/浮环」</b>。这里逐条落地：
     * <ol>
     *   <li><b>鼓身 2 段</b>（各 10 件一圈，R 2.36/2.26 微收）—— 两段之间就是腰箍的位置，
     *       看起来是"分段鼓身"而不是一整根柱子；</li>
     *   <li><b>钢箍 3 道</b>（下 / 腰 / 上，各 8 件，R 3.22~3.28，比鼓身外径大 0.8~1.0 格）——
     *       箍凸出鼓身，从任何角度看都有一圈亮边；三道箍的相位错开 22.5°，缝不对齐，整体像笼；</li>
     *   <li><b>橙光带 2 段</b>（各 6 件，夹在两段鼓身的上/下沿）—— 群星那台的橙光是<b>卡在箍与鼓身之间的缝里</b>，
     *       所以这里也做成"从缝里漏出来的环"；</li>
     *   <li><b>顶部收口</b>：一圈 8 件暗钢 + 十字盘 + 顶口亮核 —— 群星的顶盖是八角盘 + 中央烟囱；</li>
     *   <li><b>底部收口</b>：一圈 8 件更宽的暗钢当底座（坐在辐条端面上）；</li>
     *   <li><b>2 个侧面外挂小舱</b>：横向 ±X 各一个，直接贴在鼓身外壁上，舱口一颗橙灯。</li>
     * </ol>
     *
     * <p>实测：{@link #mdBoxCount()} 件；包围盒见 {@link #mdModelBounds()}（塔本地）。
     */

    /** 单座分件数（自检/日志用）。 */
    public static int mdBoxCount() {
        return matterDecompressorBoxes().size();
    }

    /** 4 座的方盒总数（日志用）。 */
    public static int mdBoxTotal() {
        return MD_COUNT * mdBoxCount();
    }

    /** 4 座（= 4 条辐条端部）。 */
    public static final int MD_COUNT = 4;

    /**
     * 单座在塔本地坐标里的<b>轴对齐包围盒</b> = {@code {xMin,xMax,yMin,yMax,zMin,zMax}}（自检用）。
     *
     * <p>⚠️ 每件都带偏航，所以不能直接用 {@code x ± hx}：要把 8 个角点各自绕 Y 转
     * {@link MdBox#yawDeg()} 再取极值（这是"尺寸到底多大"的唯一口径）。
     */
    public static float[] mdModelBounds() {
        float x0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y0 = Float.MAX_VALUE;
        float y1 = -Float.MAX_VALUE;
        float z0 = Float.MAX_VALUE;
        float z1 = -Float.MAX_VALUE;
        for (MdBox b : matterDecompressorBoxes()) {
            final double th = Math.toRadians(b.yawDeg());
            final float c = (float) Math.cos(th);
            final float s = (float) Math.sin(th);
            for (int ix = 0; ix < 2; ix++) {
                for (int iy = 0; iy < 2; iy++) {
                    for (int iz = 0; iz < 2; iz++) {
                        final float lx = (ix == 0 ? -b.hx() : b.hx());
                        final float lz = (iz == 0 ? -b.hz() : b.hz());
                        final float wx = b.x() + lx * c + lz * s;
                        final float wz = b.z() - lx * s + lz * c;
                        final float wy = b.y() + (iy == 0 ? -b.hy() : b.hy());
                        x0 = Math.min(x0, wx);
                        x1 = Math.max(x1, wx);
                        y0 = Math.min(y0, wy);
                        y1 = Math.max(y1, wy);
                        z0 = Math.min(z0, wz);
                        z1 = Math.max(z1, wz);
                    }
                }
            }
        }
        return new float[]{x0, x1, y0, y1, z0, z1};
    }

    /** 单座所有分件的最大半长（判据：必须 ≤ 0.5 ⇒ 单件 ≤ 1 格）。 */
    public static float mdMaxHalf() {
        float m = 0.0F;
        for (MdBox b : matterDecompressorBoxes()) {
            m = Math.max(m, Math.max(b.hx(), Math.max(b.hy(), b.hz())));
        }
        return m;
    }

    /**
     * 第 {@code i} 座机器的<b>轴线落点</b>（环心本地坐标）。
     *
     * <p>{@code i = 0..3} 对应方位 {@link #MD_AZIMUTH_DEG}（+X / −X / +Z / −Z）。
     */
    public static float[] mdCenter(int i) {
        final double a = Math.toRadians(MD_AZIMUTH_DEG[Math.floorMod(i, MD_COUNT)]);
        return new float[]{(float) (MD_CENTER_R * Math.cos(a)), MD_CENTER_Y,
                (float) (MD_CENTER_R * Math.sin(a))};
    }

    /**
     * 第 {@code i} 座的偏航角（度）：把塔本地 <b>+Z（径向朝外）</b>转到它的方位上。
     *
     * <p>与 {@link #shipYawDeg} 同一口径：{@code Axis.YP.rotationDegrees(θ)} 把 +Z 映射到
     * {@code (sinθ, 0, cosθ)}；要它等于方位 {@code (cos a, 0, sin a)} ⇒ {@code θ = 90° − a}。
     */
    public static float mdYawDeg(int i) {
        return 90.0F - MD_AZIMUTH_DEG[Math.floorMod(i, MD_COUNT)];
    }

    /**
     * 塔本地 <b>+Y 上「圆孔（喷口）」到轴线落点的距离</b>（格）。
     *
     * <p>= {@link #matterDecompressorBoxes()} 里那颗顶口亮核 {@code MD_CORE} 的中心高度
     * （{@code mdBox(out, 0.0F, 3.70F, 0.0F, …)}）。<b>这是"喷口位置"的唯一来源</b>：
     * 4 条径向光柱的起点必须由它算出，不许手填坐标（用户 2026-10-24 傍晚明确要求）。
     *
     * <p>改这里时喷口与光柱会<b>一起</b>动（{@link #mdNozzle(int)} 与
     * {@link #buildBeamQuads} 用的是同一个函数），不会出现"机器挪了、光柱还在原地"。
     */
    public static final float MD_NOZZLE_LOCAL_Y = 3.70F;

    /**
     * 第 {@code i} 座机器的<b>圆孔（喷口）中心</b>在环心本地坐标里的位置。
     *
     * <p>逐项复现 {@code HostFxDraw#drawMatterDecompressors} 的 pose 链：
     * <pre>
     *   translate(mdCenter(i))
     *   rotateY(mdYawDeg(i))
     *   rotateX(MD_TILT_DEG)          ← 2026-10-24 傍晚改成 −90°（"横过来、口朝黑洞"）
     *   → 塔本地 (0, MD_NOZZLE_LOCAL_Y, 0)
     * </pre>
     * ⇒ 这里用 <b>JOML 的同一套语义</b>（{@code Matrix4f.rotateX/rotateY}，右乘）逐项算，
     * 而不是写死 {@code MD_CENTER_R − MD_NOZZLE_LOCAL_Y}。好处：改倾角/改口高时，
     * 喷口位置<b>跟着走</b>，不会变成另一个数。
     *
     * <p>当前参数下的读数（{@code MD_TILT_DEG = −90}）：
     * 半径 = {@code MD_CENTER_R − MD_NOZZLE_LOCAL_Y = 52.5 − 3.70 = 48.80} 格，
     * 高度 = {@code MD_CENTER_Y = 0}（正好在环面上）⇒ 4 条光柱从 48.80 格处
     * 水平射向黑洞表面，方向就是该机器的方位角。
     */
    public static float[] mdNozzle(int i) {
        final int idx = Math.floorMod(i, MD_COUNT);
        final double a = Math.toRadians(MD_AZIMUTH_DEG[idx]);
        final double tilt = Math.toRadians(MD_TILT_DEG);
        // 塔本地 (0, NOZ, 0) → rotateX(tilt)：y' = NOZ·cos, z' = NOZ·sin
        final float yRotX = (float) (MD_NOZZLE_LOCAL_Y * Math.cos(tilt));
        final float zRotX = (float) (MD_NOZZLE_LOCAL_Y * Math.sin(tilt));
        // → rotateY(yaw)：x = z'·sin(yaw)，z = z'·cos(yaw)（y 不变）
        final double yaw = Math.toRadians(mdYawDeg(idx));
        final float x = (float) (zRotX * Math.sin(yaw));
        final float z = (float) (zRotX * Math.cos(yaw));
        final float[] c = mdCenter(idx);
        return new float[]{c[0] + x, c[1] + yRotX, c[2] + z};
    }

    /** 喷口到环心的<b>水平距离</b>（格）—— 4 条光柱的起点半径，日志/自检用。 */
    public static float mdNozzleRadius() {
        final float[] n = mdNozzle(0);
        return (float) Math.hypot(n[0], n[2]);
    }

    /**
     * <b>自检：4 条径向光柱的起点 == 4 座机器的喷口中心</b>（用户要求的断言）。
     *
     * <p>为什么这条必须存在：用户明确要求「把'光柱起点 = 喷口中心'写成断言」。
     * 它比较的是 {@code beamEndpoints()}（渲染用的<b>唯一</b>取值口径）与
     * {@link #mdNozzle(int)}，容差 {@code 1e-5} 格（float 直传，不需要更松）。
     *
     * @return {@code null} = 通过；否则是一行人话（哪一条对不上、差多少）
     */
    public static String selfCheckNozzleBeam() {
        final float[][] ends = beamEndpoints(HOLE_SURFACE_R);
        for (int k = 0; k < MD_COUNT; k++) {
            final float[] nz = mdNozzle(k);
            for (int axis = 0; axis < 3; axis++) {
                final float d = ends[k][axis] - nz[axis];
                if (Math.abs(d) > 1.0E-5F) {
                    return "光柱#" + k + " 起点轴" + axis + " 与喷口差 " + d
                            + "（beam=" + ends[k][axis] + " nozzle=" + nz[axis] + "）";
                }
            }
            // 末端必须仍在黑洞表面那一档：径向距离 == tipR（用户口径"末端偏差 0.000000"）
            final float tipR = Math.max(0.6F, Math.min(HOLE_SURFACE_R, ARM_ROOT_R - 0.5F));
            final float endR = (float) Math.hypot(ends[k][3], ends[k][5]);
            if (Math.abs(endR - tipR) > 1.0E-5F) {
                return "光柱#" + k + " 末端径向距离 " + endR + " ≠ 黑洞表面 " + tipR;
            }
        }
        return null;
    }

    /** 一行摘要（渲染器 {@code draw} 日志用）。 */
    public static String mdSummary() {
        final float[] mb = mdModelBounds();
        return "解压机 4×" + mdBoxCount() + " 件（上限 " + MD_MAX_BOXES + "，单件最大半长 "
                + String.format(java.util.Locale.ROOT, "%.2f", mdMaxHalf()) + " 格）"
                + " 塔本地包围盒 " + String.format(java.util.Locale.ROOT, "%.2f × %.2f × %.2f",
                mb[1] - mb[0], mb[3] - mb[2], mb[5] - mb[4])
                + " 格（横置 " + MD_TILT_DEG + "°，轴线 r=" + MD_CENTER_R
                + " 格，喷口 r=" + String.format(java.util.Locale.ROOT, "%.2f", mdNozzleRadius())
                + " 格、朝环心；光柱起点 == 喷口：" + (selfCheckNozzleBeam() == null) + "）";
    }

    /**
     * 飞船角（度）：<b>上组 +9°/s、下组 −9°/s</b>（两者本来就不同、也不同向）。
     *
     * <p>🔴 2026-10-25 二次修正：上一轮那条"{@code shipOrbitDeg(seconds) == ringDeg(seconds)}"的断言
     * <b>已作废</b>（用户「原本好好的，你这一改就不好了」）。新判据是：
     * <pre>
     *   shipOrbitDeg(t, true)  == +SHIP_DEG_PER_SEC × t
     *   shipOrbitDeg(t, false) == −SHIP_DEG_PER_SEC × t
     *   |上舰 − 下舰|         == 18°/s（逐秒）
     *   SHIP_ORBIT_R          == 63 格（生成器 shipRadialCenter = 外环外侧）
     * </pre>
     */
    public static float ringDeg(float seconds) {
        return seconds * RING_DEG_PER_SEC;
    }

    /** 第 {@code up} 组飞船的公转角（度），见 {@link #ringDeg} 的判据。 */
    public static float shipOrbitDeg(float seconds, boolean up) {
        return seconds * SHIP_DEG_PER_SEC * (up ? SHIP_UP_SIGN : SHIP_DOWN_SIGN);
    }


    /**
     * 环带与飞船之间的分组边界（格中心坐标）。
     *
     * <p>本地坐标是"格中心在整数上"（{@link #CELL_CENTER}）：
     * <ul>
     *   <li>内环带三层 = 本地 y ∈ {−1.5, −0.5, +0.5}（生成器 y = 9/10/11）；</li>
     *   <li>上飞船最低一层 = 本地 y = +3.5（生成器 y = 14），下飞船最高一层 = −3.5；</li>
     *   <li>中间空白带 (0.5, 3.5) 的中点 = <b>2.0</b> ⇒ 就拿它当分界。</li>
     * </ul>
     * ⚠️ 2026-10-24 修：原来是 {@code RING_TRACK_DY + 0.5 = 3.5} 当阈值，在"格最小角"口径下刚好，
     * 换成格中心口径后上飞船的最底一层（3.5）会被判成环带 ⇒ 那一层以 12°/s 转、其余以 9°/s 转
     * （飞船会被撕成两层）。现在阈值 2.0 落在空白带正中。
     */
    public static final float GROUP_SPLIT_Y = 2.0F;

    /**
     * 三个旋转分组的归属（<b>探针与烘焙共用</b>）：
     * 环面上下 {@link #GROUP_SPLIT_Y} 格以内算内环带，往上算上飞船、往下算下飞船。
     *
     * @param localY 环心本地 y（格中心在整数上）
     * @return 0 = 内环带，1 = 上飞船，2 = 下飞船
     */
    public static int groupOf(float localY) {
        if (localY > GROUP_SPLIT_Y) {
            return 1;
        }
        if (localY < -GROUP_SPLIT_Y) {
            return 2;
        }
        return 0;
    }

    /** 分组名（日志用）。 */
    public static String groupName(int group) {
        return switch (group) {
            case 1 -> "上飞船";
            case 2 -> "下飞船";
            default -> "内环带";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  黑洞兜底几何（纯数学；渲染在 HostFxBlackHole，离线预览共用这一份）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /** 一圈分几段（黑洞兜底几何与预览共用）。 */
    public static final int HOLE_SEGMENTS = 48;

    /**
     * 公告板基：{@code dir} = 洞心指向相机（归一化），{@code right}/{@code up} 与之正交。
     *
     * @param camX,camY,camZ 相机在环心本地帧里的位置（洞心 = 原点）
     * @return {@code {right[3], up[3], dir[3]}}；相机与洞心重合时返回 null
     */
    public static float[][] billboardBasis(float camX, float camY, float camZ) {
        double dx = camX;
        double dy = camY;
        double dz = camZ;
        final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1.0e-4D)) {
            return null;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        double rx = -dz;
        double rz = dx;
        final double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl > 1.0e-4D) {
            rx /= rl;
            rz /= rl;
        } else {
            rx = 1.0D;
            rz = 0.0D;
        }
        final float[] right = {(float) rx, 0.0F, (float) rz};
        final float[] up = {
                (float) (right[1] * dz - right[2] * dy),
                (float) (right[2] * dx - right[0] * dz),
                (float) (right[0] * dy - right[1] * dx),
        };
        return new float[][]{right, up, {(float) dx, (float) dy, (float) dz}};
    }

    /** 公告板上的一个环带 {@code [r0,r1]}（{@code an0→an1} 透明度渐变）。 */
    private static void addBillboardRing(List<Quad> out, float[][] basis, float r0, float r1,
                                         float zOff, float red, float green, float blue,
                                         float an0, float an1) {
        final float[] right = basis[0];
        final float[] up = basis[1];
        final float[] dir = basis[2];
        final float step = (float) (Math.PI * 2.0D / HOLE_SEGMENTS);
        for (int i = 0; i < HOLE_SEGMENTS; i++) {
            final float a0 = step * i;
            final float a1 = step * (i + 1);
            final float ca0 = (float) Math.cos(a0);
            final float sa0 = (float) Math.sin(a0);
            final float ca1 = (float) Math.cos(a1);
            final float sa1 = (float) Math.sin(a1);
            out.add(new Quad(new float[]{
                    bpx(right, up, dir, ca0, sa0, r0, zOff), bpy(right, up, dir, ca0, sa0, r0, zOff),
                    bpz(right, up, dir, ca0, sa0, r0, zOff),
                    bpx(right, up, dir, ca1, sa1, r0, zOff), bpy(right, up, dir, ca1, sa1, r0, zOff),
                    bpz(right, up, dir, ca1, sa1, r0, zOff),
                    bpx(right, up, dir, ca1, sa1, r1, zOff), bpy(right, up, dir, ca1, sa1, r1, zOff),
                    bpz(right, up, dir, ca1, sa1, r1, zOff),
                    bpx(right, up, dir, ca0, sa0, r1, zOff), bpy(right, up, dir, ca0, sa0, r1, zOff),
                    bpz(right, up, dir, ca0, sa0, r1, zOff),
            }, red, green, blue, (an0 + an1) * 0.5F));
        }
    }

    private static float bpx(float[] right, float[] up, float[] dir, float ct, float st, float r, float z) {
        return (right[0] * ct + up[0] * st) * r + dir[0] * z;
    }

    private static float bpy(float[] right, float[] up, float[] dir, float ct, float st, float r, float z) {
        return (right[1] * ct + up[1] * st) * r + dir[1] * z;
    }

    private static float bpz(float[] right, float[] up, float[] dir, float ct, float st, float r, float z) {
        return (right[2] * ct + up[2] * st) * r + dir[2] * z;
    }

    /** 黑洞<b>黑盘</b>（不透明）：正对相机的圆盘，半径 0.02→{@code surfaceR}。 */
    public static List<Quad> holeDiscQuads(float camX, float camY, float camZ, float surfaceR) {
        final List<Quad> out = new ArrayList<>(HOLE_SEGMENTS);
        final float[][] basis = billboardBasis(camX, camY, camZ);
        if (basis == null) {
            return out;
        }
        addBillboardRing(out, basis, 0.02F, surfaceR, 0.02F, 0.020F, 0.024F, 0.032F, 1.0F, 1.0F);
        return out;
    }

    /** 黑洞<b>光子环 + 引力光晕</b>（加性、公告板）。 */
    public static List<Quad> holeHaloQuads(float camX, float camY, float camZ, float surfaceR) {
        final List<Quad> out = new ArrayList<>(HOLE_SEGMENTS * 2);
        final float[][] basis = billboardBasis(camX, camY, camZ);
        if (basis == null) {
            return out;
        }
        addBillboardRing(out, basis, surfaceR * 1.00F, surfaceR * 1.22F, 0.02F,
                1.00F, 0.88F, 0.66F, 0.95F, 0.55F);
        addBillboardRing(out, basis, surfaceR * 1.24F, surfaceR * 2.10F, 0.02F,
                1.00F, 0.58F, 0.30F, 0.34F, 0.0F);
        return out;
    }

    /** 黑洞<b>吸积盘</b>（加性）：环平面上的环带 {@code [1.15R, 2.55R]}，随时间缓慢自转。 */
    public static List<Quad> holeDiskQuads(float surfaceR, float seconds) {
        final List<Quad> out = new ArrayList<>(HOLE_SEGMENTS);
        final float step = (float) (Math.PI * 2.0D / HOLE_SEGMENTS);
        final float spin = (float) Math.toRadians(seconds * 24.0F % 360.0F);
        final float r0 = surfaceR * 1.15F;
        final float r1 = surfaceR * 2.55F;
        final float y = 0.06F;
        for (int i = 0; i < HOLE_SEGMENTS; i++) {
            final float a0 = spin + step * i;
            final float a1 = spin + step * (i + 1);
            final float ca0 = (float) Math.cos(a0);
            final float sa0 = (float) Math.sin(a0);
            final float ca1 = (float) Math.cos(a1);
            final float sa1 = (float) Math.sin(a1);
            out.add(new Quad(new float[]{
                    ca0 * r0, y, sa0 * r0,
                    ca1 * r0, y, sa1 * r0,
                    ca1 * r1, y, sa1 * r1,
                    ca0 * r1, y, sa0 * r1,
            }, 1.00F, 0.72F, 0.42F, 0.40F));
        }
        return out;
    }

    /** 黑洞兜底几何一帧的四边形总数（日志/预算用）。 */
    public static int holeQuadCount() {
        return HOLE_SEGMENTS * 4;
    }

    /** 绕 Y 轴把方向向量转 {@code deg} 度（与 {@code Axis.YP.rotationDegrees} 同一旋向）。 */
    public static float[] rotateYDeg(float deg, float x, float y, float z) {
        final double th = Math.toRadians(deg);
        final float c = (float) Math.cos(th);
        final float s = (float) Math.sin(th);
        return new float[]{c * x + s * z, y, -s * x + c * z};
    }
}
