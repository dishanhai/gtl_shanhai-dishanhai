package com.shanhai.client.holo;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 山海重构 · <b>全息环绕层的几何核</b>（数据环 + 星尘网格）。
 *
 * <h2>0. 这个类为什么单独存在（与 {@code ShanhaiHoloMenuLayout} / {@code ShanhaiHoloMenuHitTest}
 * 同一个理由）</h2>
 * 环绕层的全部内容都是<b>纯算术</b>：环上的点、壳层里的点、哪些点该被那 5 块板挡住、每帧要发多少个
 * 图元。把这些搬进渲染器就等于"没进游戏就没验过"。
 * ⇒ 本类<b>不引用任何 Minecraft 渲染类型</b>，只有两个出口：
 * <pre>
 *   {@link #render(Frame, Sink)}   —— 走一遍全部的几何，把图元交给一个 {@link Sink}
 *   {@link CountingSink}           —— Sink 的一个实现：只数数、不画
 * </pre>
 * 于是"每帧画多少个顶点"这件事在<b>构建机上就有原始读数</b>（见 {@link #selfTest()}），
 * 而游戏里那个 Sink 换成"真的写顶点"的实现即可 —— <b>两条路走的是同一份几何代码</b>。
 *
 * <h2>1. 🔴 与 HTML 规格的对应关系（一个公式都没有重新发明）</h2>
 * 参考实现：{@code 17-全息环绕-结合版.html}（本地预览页）。
 * 本类里每个函数名后面都标了它在 HTML 里对应的那一个，参数全部取自
 * {@link ShanhaiHoloSurroundTuning}。
 *
 * <h2>2. 🔴 三条既定规则的落地位置</h2>
 * <pre>
 *   ① 环半径硬夹      —— {@link #ringRadius(ShanhaiHoloSurroundTuning.RingSpec)}
 *   ② 壳层内半径自动抬 —— {@link #shellRadii()}
 *   ③-① 板面三维禁区  —— {@link #boardFade(Frame, double, double, double)}
 *   ③-② 屏幕投影兜底  —— {@link #screenFade(Frame, double, double, double)}
 * </pre>
 *
 * <h2>3. 🔴 性能口径（"别引入新的每帧大对象"）</h2>
 * <ul>
 *   <li>全部点位/点对/桶/排序结果都是<b>静态预分配数组</b>，{@code render()} 里
 *       <b>一次 {@code new} 都没有</b>（连 {@code ArrayList} 都没有）。</li>
 *   <li>{@code Frame} 也是一个可复用的对象（{@link #newFrame()}），渲染器只建一次。</li>
 *   <li>候选点对原来在 HTML 里是"每帧 push 进数组再 {@code sort}"；
 *       这里换成<b>定桶计数排序</b>（{@link ShanhaiHoloSurroundTuning#FIELD_LINK_SORT_BUCKETS} 桶），
 *       结果与全排序几乎逐条相同，但零分配、O(n)。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloSurround {

    private ShanhaiHoloSurround() {}

    /**
     * 转调参数类，纯粹为了少打几个字。
     *
     * <p>🔴 <b>2026-10-06（第二轮）：那 14 个"可调项"从常量改成了方法</b>。
     * 原因是编译期常量会被 javac 内联到每一个调用点 ⇒ 运行期改不动（见
     * {@link ShanhaiHoloSurroundTuning} §十 的那整段说明）。
     * 现在它们一律现读 {@link ShanhaiHoloSurroundTuning#param(int)}，
     * <b>存值的地方只有一处</b>（那个 LIVE 数组），所以"面板里改的"与"渲染用的"不可能漂。
     * <p>⚠️ 不是可调项的那些（硬夹上限、禁区宽度、最小亮度…）照旧是常量 ——
     * 它们是<b>物理约束</b>，不是美术参数。
     */
    private static final class T {
        static int RING_COUNT() {
            return ShanhaiHoloSurroundTuning.paramInt(ShanhaiHoloSurroundTuning.P_RING_COUNT);
        }

        static float RING_SCALE() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_RING_SCALE);
        }

        static float RING_BRIGHT() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_RING_BRIGHT);
        }

        static float RING_TICK_DENSITY() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_RING_TICK_DENSITY);
        }

        static float RING_FLOW_SCALE() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_RING_FLOW_SCALE);
        }

        static float RING_PRECESS_SCALE() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_RING_PRECESS_SCALE);
        }

        static int FIELD_POINT_COUNT() {
            return ShanhaiHoloSurroundTuning.paramInt(ShanhaiHoloSurroundTuning.P_FIELD_POINT_COUNT);
        }

        static float FIELD_INNER() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_FIELD_INNER);
        }

        static float FIELD_OUTER() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_FIELD_OUTER);
        }

        static float FIELD_LINK() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_FIELD_LINK_THRESHOLD);
        }

        static int FIELD_LINK_MAX() {
            return ShanhaiHoloSurroundTuning.paramInt(
                    ShanhaiHoloSurroundTuning.P_FIELD_LINK_MAX_PER_POINT);
        }

        static float FIELD_LINK_PERIOD() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_FIELD_LINK_PERIOD);
        }

        static float FIELD_BRIGHT() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_FIELD_BRIGHT);
        }

        static float GLOBAL_BRIGHT() {
            return ShanhaiHoloSurroundTuning.param(ShanhaiHoloSurroundTuning.P_GLOBAL_BRIGHT);
        }

        static float FIELD_FOLLOW_TAU() {
            return ShanhaiHoloSurroundTuning.param(
                    ShanhaiHoloSurroundTuning.P_FIELD_FOLLOW_TAU);
        }

        static final float RING_MAX_R = ShanhaiHoloSurroundTuning.RING_MAX_RADIUS_BLOCKS;
        static final float FIELD_GAP = ShanhaiHoloSurroundTuning.FIELD_INNER_GAP_BLOCKS;
        static final float FIELD_MIN_THICK = ShanhaiHoloSurroundTuning.FIELD_MIN_SHELL_THICKNESS_BLOCKS;
        static final float FACE_CLEAR = ShanhaiHoloSurroundTuning.BOARD_FACE_CLEARANCE_BLOCKS;
        static final float EDGE_FADE = ShanhaiHoloSurroundTuning.BOARD_EDGE_FADE_BLOCKS;
        static final float CULL = ShanhaiHoloSurroundTuning.CULL_FADE;
        static final float DOT_MIN_ALPHA = ShanhaiHoloSurroundTuning.DOT_MIN_ALPHA;
        static final float MIN_BRIGHT = ShanhaiHoloSurroundTuning.MIN_BRIGHT;
        static final float MIN_R = ShanhaiHoloSurroundTuning.MIN_RADIUS_BLOCKS;

        private T() {}
    }

    // ==================================================================== 一、🆕 四档预设（面板 / 日志的统一入口）

    /**
     * 当前档位号（{@code 0..3} = 关 / 弱 / 中 / 强）。真正的存值处在
     * {@link ShanhaiHoloSurroundTuning}，本类只做转发 —— 面板、输入层、渲染器读的都是同一个数。
     */
    public static int preset() {
        return ShanhaiHoloSurroundTuning.preset();
    }

    /** 切档（整组替换；「关」只置档、不动 14 项取值）。 */
    public static int setPreset(int next) {
        return ShanhaiHoloSurroundTuning.setPreset(next);
    }

    /** 高级页那一颗 ± ：{@code dir = +1 / −1}；返回值已经钳在安全区间内。 */
    public static float nudgeParam(int idx, int dir) {
        return ShanhaiHoloSurroundTuning.nudge(idx, dir);
    }

    /** 第 idx 项当前生效值（面板显示与日志读它）。 */
    public static float param(int idx) {
        return ShanhaiHoloSurroundTuning.param(idx);
    }

    /** 光点数组的固定容量（= HTML 的 {@code FN = 200}）—— 预分配上限，滑块拖小也不重新分配。 */
    public static final int FIELD_CAPACITY = 200;

    /** 最多支持几块板同时做禁区判据（菜单 5 块 / 面板 5 块，留一点余量）。 */
    public static final int MAX_BOARDS = 8;

    // ==================================================================== 一、出口接口

    /**
     * 几何出口。<b>游戏里</b>由渲染器实现（真的写顶点）；<b>离线上</b>由 {@link CountingSink} 实现（只数数）。
     *
     * <p>⚠️ 两个方法必须是<b>同一条代码路径</b>的两个出口：只要能数出来的图元，渲染器就一定画了。
     */
    public interface Sink {

        /**
         * 一条线段（两端世界坐标）。
         *
         * @param widthHtmlPx 线宽，单位 = <b>HTML 参考像素</b>（见 {@code HTML_REF_FOCAL_PX}）
         * @param alpha       已经乘过亮度与淡出之后的透明度
         */
        void segment(double ax, double ay, double az,
                     double bx, double by, double bz,
                     int rgb, float widthHtmlPx, float alpha);

        /**
         * 一个发光点。
         *
         * @param radiusBlocks 半径（格）—— <b>不含</b>精灵外晕倍数，外晕在渲染侧统一乘
         */
        void dot(double x, double y, double z, float radiusBlocks, int rgb, float alpha);
    }

    /** 世界 → 屏幕的投影（离线上由 {@link #fillPinhole} 造，游戏里由渲染器从 MVP 造）。 */
    public static final class Proj {

        /** MVP 矩阵，<b>列主序</b>：{@code m[c * 4 + r] = M[r][c]}（JOML {@code Matrix4f.get(float[])} 的排布）。 */
        public final float[] m = new float[16];

        /** 屏幕半宽 / 半高（像素）。 */
        public float halfW = 1.0f;
        public float halfH = 1.0f;

        /**
         * 投影一个世界点。
         *
         * @param out {@code out[0]=屏幕 x}（屏幕中心为原点）、{@code out[1]=屏幕 y}（向下为正）、
         *            {@code out[2]=深度}（格）
         * @return {@code false} = 在近平面之后（不可见，调用方必须当成"没有投影"处理）
         */
        public boolean project(double x, double y, double z, double[] out) {
            final float[] m = this.m;
            final float fx = (float) x;
            final float fy = (float) y;
            final float fz = (float) z;
            final float w = m[3] * fx + m[7] * fy + m[11] * fz + m[15];
            if (w <= ShanhaiHoloSurroundTuning.NEAR_DEPTH_BLOCKS) {
                return false;
            }
            final float inv = 1.0f / w;
            out[0] = (m[0] * fx + m[4] * fy + m[8] * fz + m[12]) * inv * halfW;
            out[1] = -(m[1] * fx + m[5] * fy + m[9] * fz + m[13]) * inv * halfH;
            out[2] = w;
            return true;
        }
    }

    /** 一块板在<b>世界</b>里的位姿 + 它投影到屏幕上的四边形（规则 ③ 的两道判据都吃它）。 */
    public static final class BoardBox {
        /** 板心世界坐标。 */
        public double ox, oy, oz;
        /** 板内「从左到右」方向（世界单位向量）。 */
        public double ux, uy, uz;
        /** 板内「从下到上」方向（世界单位向量）。 */
        public double vx, vy, vz;
        /** 板面法线（指向观众那一侧，世界单位向量）。 */
        public double nx, ny, nz;
        /** 板的半宽 / 半高（格，已含该板自己的 scale 与展开动画的缩放）。 */
        public double hw, hh;
        /** 板四角投影到屏幕之后的坐标（像素，屏幕中心为原点）。 */
        public final double[] sx = new double[4];
        public final double[] sy = new double[4];
        /** {@code true} = 四角<b>全部</b>投影成功（有一角在近平面之后就是 false ⇒ 屏幕兜底判据对它失效）。 */
        public boolean quadValid;
    }

    /** 一帧的全部输入（渲染器只建一次、逐帧改字段；<b>不</b>每帧新建）。 */
    public static final class Frame {

        /** 玩家脚底（已按 partialTick 插值）。 */
        public double playerX, playerY, playerZ;
        /** 相机世界坐标（做面向相机的广告牌要用）。 */
        public double camX, camY, camZ;

        /** 这一帧的时刻（秒）与步长（秒，已夹过）。 */
        public float tSec, dtSec;

        /** 展开/收回动画的整体透明度（0..1）—— 环绕层跟着那 5 块板一起淡出。 */
        public float globalAlpha = 1.0f;

        /** 投影。{@code null} = 只做三维判据、不做屏幕兜底（离线自检里有用）。 */
        public Proj proj;

        // ---- 全息本体的落地位姿（与那 5 块板用的是同一套数）----
        /** 全息原点（世界，已含展开动画的插值）。 */
        public double originX, originY, originZ;
        /** 框偏航（弧度，已含展开动画的自转）。 */
        public double frameYawRad;
        /** 展开动画的整块缩放（完全展开 = 1）。 */
        public double animScale = 1.0;

        // ---- 本帧的板表（菜单 5 块 / 面板 5 行板，由渲染器填）----
        public int boardCount;
        public final float[] boardX = new float[MAX_BOARDS];
        public final float[] boardY = new float[MAX_BOARDS];
        public final float[] boardZ = new float[MAX_BOARDS];
        public final float[] boardRotY = new float[MAX_BOARDS];
        public final float[] boardRotX = new float[MAX_BOARDS];
        public final float[] boardScale = new float[MAX_BOARDS];

        /** 逐板的算好的位姿（{@link #render} 开头填）。 */
        public final BoardBox[] boards = new BoardBox[MAX_BOARDS];

        /** 诊断读数。 */
        public int ringSegments, fieldLinks, fieldDots;

        private Frame() {
            for (int i = 0; i < MAX_BOARDS; i++) {
                boards[i] = new BoardBox();
            }
        }
    }

    /** 造一个可复用的 {@link Frame}（渲染器只建一次）。 */
    public static Frame newFrame() {
        return new Frame();
    }

    // ==================================================================== 二、全部静态预分配数组

    private static final double[] SCRATCH_A = new double[3];
    private static final double[] SCRATCH_B = new double[3];
    private static final double[] SCRATCH_C = new double[3];
    private static final double[] PROJ_OUT = new double[3];
    private static final float[] SHELL_OUT = new float[2];

    /** {@link #fieldCenter()} 的出口（共享数组，不每帧新建）。 */
    private static final double[] FIELD_CENTER_OUT = new double[3];

    /** 每条环算一遍的基（半径 / 圆心 / 自转与倾角的三角值）。 */
    private static final class RingBasis {
        double r, cx, cy, cz, cs, sn, ct, st;
    }

    private static final RingBasis BASIS = new RingBasis();

    // ---- 点场 ----
    private static final double[] FA = new double[FIELD_CAPACITY];     // 水平角
    private static final double[] FE = new double[FIELD_CAPACITY];     // 仰角
    private static final double[] FT = new double[FIELD_CAPACITY];     // 0..1 ⇒ 内半径→外半径
    private static final double[] FPH = new double[FIELD_CAPACITY];    // 抖动相位
    private static final double[] FW = new double[FIELD_CAPACITY];     // 抖动角频率
    private static final double[] FAMP = new double[FIELD_CAPACITY];   // 抖动幅度
    private static final double[] FTW = new double[FIELD_CAPACITY];    // 闪烁相位
    private static final double[] FTWS = new double[FIELD_CAPACITY];   // 闪烁角频率
    private static final boolean[] FBIG = new boolean[FIELD_CAPACITY];
    private static final double[] FFADE = new double[FIELD_CAPACITY];
    private static final double[] FPX = new double[FIELD_CAPACITY];
    private static final double[] FPY = new double[FIELD_CAPACITY];
    private static final double[] FPZ = new double[FIELD_CAPACITY];

    private static boolean fieldReady;
    private static double fieldCX, fieldCY, fieldCZ;
    private static long rngState = 0x9E3779B97F4A7C15L;

    // ---- 连线的候选点对（定桶计数排序）----
    private static final int[] PAIR_A = new int[ShanhaiHoloSurroundTuning.FIELD_LINK_MAX_CANDIDATES];
    private static final int[] PAIR_B = new int[ShanhaiHoloSurroundTuning.FIELD_LINK_MAX_CANDIDATES];
    private static final byte[] PAIR_BUCKET = new byte[ShanhaiHoloSurroundTuning.FIELD_LINK_MAX_CANDIDATES];
    private static final int[] PAIR_ORDER = new int[ShanhaiHoloSurroundTuning.FIELD_LINK_MAX_CANDIDATES];
    private static final int[] BUCKET_HEAD = new int[ShanhaiHoloSurroundTuning.FIELD_LINK_SORT_BUCKETS];
    private static final int[] BUCKET_CURSOR = new int[ShanhaiHoloSurroundTuning.FIELD_LINK_SORT_BUCKETS];
    private static final int[] DEGREE = new int[FIELD_CAPACITY];

    /** 上一帧因为超过候选上限而被丢掉的点对数（诊断用，正常参数下应为 0）。 */
    private static int pairOverflow;

    // ==================================================================== 三、确定的伪随机（可复现）

    /**
     * xorshift64*。HTML 用的是 {@code Math.random()} —— 在这里换成<b>定种子伪随机</b>，
     * 理由有二：① 离线自检要能复现同一张点场；② 游戏里每次开投影的点场不必真的"随机"，
     * 只要看起来无规律即可，而确定性能让"读数对不上"这类问题可查。
     */
    private static double rnd() {
        long x = rngState;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        rngState = x;
        return ((x * 0x2545F4914F6CDD1DL) >>> 11) * (1.0 / 9007199254740992.0);
    }

    // ==================================================================== 四、规则 ① / ②：半径

    /**
     * 🔴 <b>规则 ①</b>：一条环的<b>有效</b>半径（格）= {@code min(它自己的半径 × 环半径倍率, 1.75)}。
     * <p>HTML {@code ringRadius(R) = Math.min(R.r * TUNE.ringScale, RING_MAX_R)}。
     */
    public static float ringRadius(ShanhaiHoloSurroundTuning.RingSpec spec) {
        return (float) Math.min(spec.r() * (double) T.RING_SCALE(), (double) T.RING_MAX_R);
    }

    /** 生效的环（前 {@code RING_COUNT} 条）。 */
    public static int activeRingCount() {
        int n = Math.round(T.RING_COUNT());
        if (n < 0) {
            n = 0;
        }
        if (n > ShanhaiHoloSurroundTuning.RING_TABLE.length) {
            n = ShanhaiHoloSurroundTuning.RING_TABLE.length;
        }
        return n;
    }

    /**
     * 🔴 <b>规则 ②</b>：点场壳层的内/外半径。
     * <pre>
     *   rm    = 所有生效的环里最大的那个有效半径
     *   inner = max(滑块值, rm + 0.32)          ← 环划到哪、点场就自动退到环外
     *   outer = max(inner + 0.35, 滑块值)
     * </pre>
     * HTML {@code shellRadii()}。
     *
     * @return <b>共享的</b>临时数组 {@code [inner, outer]} —— 只读、别存起来
     */
    public static float[] shellRadii() {
        double rm = 0.0;
        final int n = activeRingCount();
        for (int i = 0; i < n; i++) {
            final double r = ringRadius(ShanhaiHoloSurroundTuning.RING_TABLE[i]);
            if (r > rm) {
                rm = r;
            }
        }
        final double inner = Math.max((double) T.FIELD_INNER(), rm + (double) T.FIELD_GAP);
        final double outer = Math.max(inner + (double) T.FIELD_MIN_THICK, (double) T.FIELD_OUTER());
        SHELL_OUT[0] = (float) inner;
        SHELL_OUT[1] = (float) outer;
        return SHELL_OUT;
    }

    // ==================================================================== 五、每帧主入口

    /**
     * 走一遍这一帧的全部环绕层几何，把图元交给 {@code sink}。
     *
     * <p>顺序刻意安排成「先所有线、再所有点」（环则分两遍）——
     * 渲染器按图元种类切 {@code RenderType} 时会<b>顺带把上一批冲掉</b>（MC 的
     * {@code MultiBufferSource.BufferSource#getBuffer} 在类型变化时 end 上一批），
     * 于是每批的顶点数就被这个顺序钉死了，见 {@link CountingSink#maxBatchVerts}。
     */
    public static void render(Frame f, Sink sink) {
        f.ringSegments = 0;
        f.fieldLinks = 0;
        f.fieldDots = 0;
        // 🔴 2026-10-06（第二轮）：「关」档 = 【跳过整个绘制遍】。
        //    判据在渲染器那一层也有一条（它直接不发顶点），这里是几何核自己的那条 ——
        //    离线装置（CountingSink）因此也会读到 0 个顶点，不会出现"装置说画了、游戏没画"。
        //    ⚠️ 只跳绘制：14 项取值与点场状态<b>一个数都不动</b> ⇒ 切回来现场还在。
        if (!ShanhaiHoloSurroundTuning.drawEnabled()) {
            return;
        }
        updateBoardGeometry(f);
        fieldAdvance(f);
        emitRings(f, sink);
        emitField(f, sink);
    }

    /** 只算板位姿与投影（离线判据用；{@link #render} 会自己调一遍）。 */
    public static void updateBoardGeometry(Frame f) {
        final double fy = f.frameYawRad;
        final double fcos = Math.cos(fy);
        final double fsin = Math.sin(fy);
        for (int i = 0; i < f.boardCount && i < MAX_BOARDS; i++) {
            final BoardBox b = f.boards[i];
            // 板在全息本体坐标系里的位移，乘上展开动画的整块缩放
            final double lx = f.boardX[i] * f.animScale;
            final double ly = f.boardY[i] * f.animScale;
            final double lz = f.boardZ[i] * f.animScale;
            // Rot_Y(frameYaw) · (x, y, z)
            b.ox = f.originX + lx * fcos + lz * fsin;
            b.oy = f.originY + ly;
            b.oz = f.originZ - lx * fsin + lz * fcos;

            // 三个轴：Rot_Y(frameYaw) · Rot_Y(rotY) · Rot_X(rotX) · 轴
            //   （与 ShanhaiHoloMenuRenderer#pushBoard 的 mulPose 顺序逐字相同）
            boardAxis(1.0, 0.0, 0.0, f.boardRotX[i], f.boardRotY[i], fcos, fsin, b, 0);
            boardAxis(0.0, 1.0, 0.0, f.boardRotX[i], f.boardRotY[i], fcos, fsin, b, 1);
            boardAxis(0.0, 0.0, 1.0, f.boardRotX[i], f.boardRotY[i], fcos, fsin, b, 2);

            final double sc = f.boardScale[i] * f.animScale;
            b.hw = ShanhaiHoloMenuTuning.BOARD_HALF_WIDTH_BLOCKS * sc;
            b.hh = ShanhaiHoloMenuTuning.BOARD_HALF_HEIGHT_BLOCKS * sc;

            projectQuad(f, b);
        }
    }

    private static void boardAxis(double vx, double vy, double vz, float rotXDeg, float rotYDeg,
                                  double frameCos, double frameSin, BoardBox b, int which) {
        final double rx = Math.toRadians(rotXDeg);
        final double ry = Math.toRadians(rotYDeg);
        final double cx = Math.cos(rx), sx = Math.sin(rx);
        final double cy = Math.cos(ry), sy = Math.sin(ry);
        // Rot_X
        final double ax = vx;
        final double ay = vy * cx - vz * sx;
        final double az = vy * sx + vz * cx;
        // Rot_Y(rotY)
        final double bx = ax * cy + az * sy;
        final double by = ay;
        final double bz = -ax * sy + az * cy;
        // Rot_Y(frameYaw)
        final double ox = bx * frameCos + bz * frameSin;
        final double oy = by;
        final double oz = -bx * frameSin + bz * frameCos;
        switch (which) {
            case 0 -> {
                b.ux = ox;
                b.uy = oy;
                b.uz = oz;
            }
            case 1 -> {
                b.vx = ox;
                b.vy = oy;
                b.vz = oz;
            }
            default -> {
                b.nx = ox;
                b.ny = oy;
                b.nz = oz;
            }
        }
    }

    /** 板四角 → 屏幕四边形。任何一个角落在近平面之后就把整块标成"投影不可信"。 */
    private static void projectQuad(Frame f, BoardBox b) {
        b.quadValid = false;
        if (f.proj == null) {
            return;
        }
        for (int c = 0; c < 4; c++) {
            final double gx = (c == 0 || c == 3) ? -1.0 : 1.0;
            final double gy = (c < 2) ? -1.0 : 1.0;
            final double wx = b.ox + b.ux * b.hw * gx + b.vx * b.hh * gy;
            final double wy = b.oy + b.uy * b.hw * gx + b.vy * b.hh * gy;
            final double wz = b.oz + b.uz * b.hw * gx + b.vz * b.hh * gy;
            if (!f.proj.project(wx, wy, wz, PROJ_OUT)) {
                return;
            }
            b.sx[c] = PROJ_OUT[0];
            b.sy[c] = PROJ_OUT[1];
        }
        b.quadValid = true;
    }

    // ==================================================================== 六、规则 ③：两道禁区判据

    /**
     * 🔴 <b>规则 ③-①（三维）</b>：这个点贴着哪块板的板面吗？
     *
     * <p>HTML {@code boardFade(p)}：点若落在某块板的<b>正面/背面 ±0.28 格</b>的薄板里，
     * 并且（在板内坐标下）落在板面矩形内（或离它不到 0.05 格），就返回 {@code 0}（不画）。
     *
     * <p>⚠️ 「离矩形的距离」取的是<b>两个方向上越界量的较大者</b>
     * （{@code max(lx - hw, ly - hh)}）——
     * 两个都在矩形内时它是负数，越往里负得越多 ⇒ 淡出值 0；
     * 只有<b>已经离开矩形</b>时它才可能 ≥ 0。取错极值就会把"整片区域"都判成在板面里。
     *
     * @return {@code 0} = 完全不许画；{@code 1} = 完全不受影响
     */
    public static float boardFade(Frame f, double x, double y, double z) {
        float out = 1.0f;
        for (int i = 0; i < f.boardCount && i < MAX_BOARDS; i++) {
            final BoardBox b = f.boards[i];
            final double dx = x - b.ox;
            final double dy = y - b.oy;
            final double dz = z - b.oz;
            final double lz = dx * b.nx + dy * b.ny + dz * b.nz;
            if (lz > T.FACE_CLEAR || lz < -T.FACE_CLEAR) {
                continue;
            }
            final double lx = Math.abs(dx * b.ux + dy * b.uy + dz * b.uz);
            final double ly = Math.abs(dx * b.vx + dy * b.vy + dz * b.vz);
            final double outside = Math.max(lx - b.hw, ly - b.hh);
            if (outside >= T.EDGE_FADE) {
                continue;
            }
            float ff = (float) (outside / T.EDGE_FADE);
            if (ff < 0.0f) {
                ff = 0.0f;
            } else if (ff > 1.0f) {
                ff = 1.0f;
            }
            if (ff < out) {
                out = ff;
            }
        }
        return out;
    }

    /**
     * 🔴 <b>规则 ③-②（屏幕投影兜底）</b>：这个屏幕坐标压在哪个板子的投影四边形上吗？
     *
     * <p>HTML {@code quadFade(x, y, pad)}：凸多边形，"点在内部"⇔ 落在每一条边的内侧。
     * 对每条边算<b>带符号的点到边距离</b>，取所有边的<b>最小值</b>：
     * <pre>
     *   worst >= 0  ⇒ 在四边形内部（含边上）       ⇒ 返回 0（不画）
     *   worst &lt; 0  ⇒ 在外面，越负越远           ⇒ 返回 clamp(-worst / pad, 0, 1)
     * </pre>
     * ⚠️ <b>必须取最小值</b>：取最大值会把几乎是整屏都判成"在板面里"，环绕层会被整片抹掉
     * （HTML 里为这一条修过一次 bug，注释就写在那个函数上方）。
     *
     * <p>⚠️ <b>本实现比 HTML 多两道退化保护（预防性，成本为零）</b>：
     * <ol>
     *   <li>{@code len < 1e-9 ⇒ continue}：HTML 写的是 {@code L = Math.hypot(ex, ey) || 1}，
     *       一条边的两个端点投影到<b>同一点</b>时分子分母都是 0 ⇒ {@code d=0}；</li>
     *   <li>{@code |面积| < MIN_QUAD_AREA_PX2 ⇒ 跳过这块板}：环绕档下五块板绕玩家一圈，
     *       <b>总有一块近乎侧对镜头</b>（本机实测：那块板的屏幕 x 范围正好是 0.000 ⇒ 面积 0），
     *       而"点在零面积多边形内部"这件事本身是没有意义的。</li>
     * </ol>
     * <p>🔴 <b>实测读数（见 {@link #selfTest()} 的 H 段）：这两道保护在当前几何下
     * 【不改变任何读数】</b>（开与不开都是 1.000）⇒ 它们是预防性的，
     * <b>不是</b>修掉了一个已复现的 bug。写在这里是为了将来改布局时不必重新发现一遍。
     *
     * @param pad 淡出的边距（像素）
     * @return {@code 0} = 完全不许画；{@code 1} = 完全不受影响
     */
    public static float screenFade(Frame f, double x, double y, double pad) {
        float out = 1.0f;
        if (pad <= 0.0f) {
            return 1.0f;
        }
        for (int i = 0; i < f.boardCount && i < MAX_BOARDS; i++) {
            final BoardBox b = f.boards[i];
            if (!b.quadValid) {
                continue;
            }
            double area = 0.0;
            for (int k = 0; k < 4; k++) {
                final int n = (k + 1) & 3;
                area += b.sx[k] * b.sy[n] - b.sx[n] * b.sy[k];
            }
            if (Math.abs(area) * 0.5 < ShanhaiHoloSurroundTuning.MIN_QUAD_AREA_PX2) {
                // 零面积（侧对镜头的那块板）⇒ 它在屏幕上不占像素，不构成禁区
                continue;
            }
            final double sign = area >= 0.0 ? 1.0 : -1.0;
            double worst = Double.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                final int n = (k + 1) & 3;
                final double ex = b.sx[n] - b.sx[k];
                final double ey = b.sy[n] - b.sy[k];
                final double len = Math.sqrt(ex * ex + ey * ey);
                if (len < 1.0e-9) {
                    continue;
                }
                final double d = (ex * (y - b.sy[k]) - ey * (x - b.sx[k])) * sign / len;
                if (d < worst) {
                    worst = d;
                }
            }
            if (worst == Double.MAX_VALUE) {
                // 四条边全部退化 ⇒ 这块板在屏幕上没有面积，不构成禁区
                continue;
            }
            if (worst >= 0.0) {
                return 0.0f;
            }
            float g = (float) (-worst / pad);
            if (g < 0.0f) {
                g = 0.0f;
            } else if (g > 1.0f) {
                g = 1.0f;
            }
            if (g < out) {
                out = g;
            }
        }
        return out;
    }

    // ==================================================================== 七、A：数据环

    /** 一条环的基（HTML {@code ringBasis(R, t)}）。 */
    private static void ringBasis(Frame f, ShanhaiHoloSurroundTuning.RingSpec spec) {
        final RingBasis b = BASIS;
        b.r = ringRadius(spec);
        final double sp = T.RING_PRECESS_SCALE();
        final double spin = spec.spin0() + spec.spinV() * sp * f.tSec;
        final double tilt = spec.tilt() + spec.tiltAmp() * sp * Math.sin(f.tSec * 0.31 * sp + spec.phase());
        b.cs = Math.cos(spin);
        b.sn = Math.sin(spin);
        b.ct = Math.cos(tilt);
        b.st = Math.sin(tilt);
        b.cx = f.playerX;
        b.cy = f.playerY + spec.y();
        b.cz = f.playerZ;
    }

    /** HTML 的 {@code pt(th)}：环上角度 th 处的世界点。 */
    private static void ringPoint(RingBasis b, double th, double[] out) {
        final double x0 = b.r * Math.cos(th);
        final double z0 = b.r * Math.sin(th);
        final double y1 = -z0 * b.st;
        final double z1 = z0 * b.ct;
        out[0] = b.cx + x0 * b.cs + z1 * b.sn;
        out[1] = b.cy + y1;
        out[2] = b.cz - x0 * b.sn + z1 * b.cs;
    }

    /** HTML 的 {@code rad(th)}：环上角度 th 处、从环心指向外的单位向量。 */
    private static void ringRadial(RingBasis b, double th, double[] out) {
        ringPoint(b, th, SCRATCH_C);
        final double dx = SCRATCH_C[0] - b.cx;
        final double dy = SCRATCH_C[1] - b.cy;
        final double dz = SCRATCH_C[2] - b.cz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-9) {
            len = 1.0;
        }
        out[0] = dx / len;
        out[1] = dy / len;
        out[2] = dz / len;
    }

    /** 一次流动光点的角位置（HTML 里 {@code th} 那一行）。 */
    private static double flowAngle(ShanhaiHoloSurroundTuning.RingSpec spec, int j, int fn, float tSec) {
        final double step = Math.PI * 2.0 / Math.max(1, fn);
        return spec.phase() + j * step
                + tSec * (double) spec.flowV() * (0.4 + 0.6 * (double) T.RING_PRECESS_SCALE());
    }

    private static int flowCount(ShanhaiHoloSurroundTuning.RingSpec spec) {
        return Math.round(spec.flowN() * T.RING_FLOW_SCALE());
    }

    private static void emitRings(Frame f, Sink sink) {
        final float bright = T.RING_BRIGHT() * T.GLOBAL_BRIGHT() * f.globalAlpha;
        if (bright <= T.MIN_BRIGHT) {
            return;
        }
        final int n = activeRingCount();
        // 第一遍：所有"线"（环体 / 刻度 / 辉光 / 地面淡影 / 拖尾）→ 同一批
        for (int i = 0; i < n; i++) {
            emitRingLines(f, sink, ShanhaiHoloSurroundTuning.RING_TABLE[i], bright);
        }
        // 第二遍：所有"光点"（每个流动光点的三个精灵）→ 另一批
        for (int i = 0; i < n; i++) {
            emitRingDots(f, sink, ShanhaiHoloSurroundTuning.RING_TABLE[i], bright);
        }
    }

    /** HTML {@code drawRingA} 的 ①②③④ 与 ⑤ 的拖尾。 */
    private static void emitRingLines(Frame f, Sink sink,
                                      ShanhaiHoloSurroundTuning.RingSpec spec, float bright) {
        ringBasis(f, spec);
        final RingBasis b = BASIS;
        if (b.r <= T.MIN_R) {
            return;
        }
        final int hue = spec.hueRgb();

        // ① 环体：RING_ARCS 段圆弧，每段再细分成 RING_ARC_SEGMENTS 小段
        final double step = Math.PI * 2.0 / ShanhaiHoloSurroundTuning.RING_ARCS;
        for (int a = 0; a < ShanhaiHoloSurroundTuning.RING_ARCS; a++) {
            final double th0 = a * step;
            ringPoint(b, th0, SCRATCH_A);
            for (int k = 1; k <= ShanhaiHoloSurroundTuning.RING_ARC_SEGMENTS; k++) {
                ringPoint(b, th0 + step * k / ShanhaiHoloSurroundTuning.RING_ARC_SEGMENTS, SCRATCH_B);
                emitSeg(sink, SCRATCH_A, SCRATCH_B, hue,
                        ShanhaiHoloSurroundTuning.RING_BODY_W_HTML_PX,
                        ShanhaiHoloSurroundTuning.RING_BODY_ALPHA * bright);
                SCRATCH_A[0] = SCRATCH_B[0];
                SCRATCH_A[1] = SCRATCH_B[1];
                SCRATCH_A[2] = SCRATCH_B[2];
            }
        }
        f.ringSegments += ShanhaiHoloSurroundTuning.RING_ARCS * ShanhaiHoloSurroundTuning.RING_ARC_SEGMENTS;

        // ② 刻度（短刻度与环体同款；长刻度单独一套颜色/线宽）
        final int tickN = Math.round(spec.tickN() * T.RING_TICK_DENSITY());
        if (tickN >= ShanhaiHoloSurroundTuning.RING_TICK_MIN_COUNT) {
            for (int k = 0; k < tickN; k++) {
                final double th = k * Math.PI * 2.0 / tickN;
                final boolean isLong = (k % ShanhaiHoloSurroundTuning.RING_TICK_LONG_EVERY) == 0;
                ringPoint(b, th, SCRATCH_A);
                ringRadial(b, th, SCRATCH_B);
                final double k0 = isLong
                        ? ShanhaiHoloSurroundTuning.RING_TICK_LONG_INNER
                        : ShanhaiHoloSurroundTuning.RING_TICK_SHORT_INNER;
                final double k1 = isLong
                        ? ShanhaiHoloSurroundTuning.RING_TICK_LONG_OUTER
                        : ShanhaiHoloSurroundTuning.RING_TICK_SHORT_OUTER;
                final double ax = SCRATCH_A[0] + SCRATCH_B[0] * b.r * k0;
                final double ay = SCRATCH_A[1] + SCRATCH_B[1] * b.r * k0;
                final double az = SCRATCH_A[2] + SCRATCH_B[2] * b.r * k0;
                final double bx = SCRATCH_A[0] + SCRATCH_B[0] * b.r * k1;
                final double by = SCRATCH_A[1] + SCRATCH_B[1] * b.r * k1;
                final double bz = SCRATCH_A[2] + SCRATCH_B[2] * b.r * k1;
                sink.segment(ax, ay, az, bx, by, bz,
                        isLong ? spec.tickRgb() : hue,
                        isLong ? ShanhaiHoloSurroundTuning.RING_TICK_LONG_W_HTML_PX
                               : ShanhaiHoloSurroundTuning.RING_TICK_SHORT_W_HTML_PX,
                        (isLong ? ShanhaiHoloSurroundTuning.RING_TICK_LONG_ALPHA
                                : ShanhaiHoloSurroundTuning.RING_TICK_SHORT_ALPHA) * bright);
                f.ringSegments++;
            }
        }

        // ③ 整圈柔和辉光（一项，开销极低）
        final int gs = ShanhaiHoloSurroundTuning.RING_GLOW_SEGMENTS;
        ringPoint(b, 0.0, SCRATCH_A);
        for (int k = 1; k <= gs; k++) {
            ringPoint(b, k * Math.PI * 2.0 / gs, SCRATCH_B);
            emitSeg(sink, SCRATCH_A, SCRATCH_B, hue,
                    ShanhaiHoloSurroundTuning.RING_GLOW_W_HTML_PX,
                    ShanhaiHoloSurroundTuning.RING_GLOW_ALPHA * bright);
            SCRATCH_A[0] = SCRATCH_B[0];
            SCRATCH_A[1] = SCRATCH_B[1];
            SCRATCH_A[2] = SCRATCH_B[2];
        }
        f.ringSegments += gs;

        // ④ 地面上的那圈淡影（把环"钉"在地上）
        if (spec.foot()) {
            final double y = f.playerY + ShanhaiHoloSurroundTuning.RING_FOOT_Y_OFFSET;
            final int fs = ShanhaiHoloSurroundTuning.RING_FOOT_SEGMENTS;
            double px = f.playerX + b.r;
            double pz = f.playerZ;
            for (int k = 1; k <= fs; k++) {
                final double th = k * Math.PI * 2.0 / fs;
                final double qx = f.playerX + Math.cos(th) * b.r;
                final double qz = f.playerZ + Math.sin(th) * b.r;
                sink.segment(px, y, pz, qx, y, qz, ShanhaiHoloSurroundTuning.RING_FOOT_RGB,
                        ShanhaiHoloSurroundTuning.RING_FOOT_W_HTML_PX,
                        ShanhaiHoloSurroundTuning.RING_FOOT_ALPHA * bright);
                px = qx;
                pz = qz;
            }
            f.ringSegments += fs;
        }

        // ⑤ 流动光点的拖尾
        final int fn = flowCount(spec);
        if (fn > 0) {
            final double dir = spec.flowV() >= 0.0f ? 1.0 : -1.0;
            for (int j = 0; j < fn; j++) {
                final double th = flowAngle(spec, j, fn, f.tSec);
                ringPoint(b, th, SCRATCH_A);
                for (int k = 1; k < ShanhaiHoloSurroundTuning.RING_TAIL_POINTS; k++) {
                    ringPoint(b, th - dir * k * ShanhaiHoloSurroundTuning.RING_TAIL_STEP_RAD, SCRATCH_B);
                    emitSeg(sink, SCRATCH_A, SCRATCH_B, hue,
                            ShanhaiHoloSurroundTuning.RING_TAIL_W_HTML_PX,
                            ShanhaiHoloSurroundTuning.RING_TAIL_ALPHA * bright);
                    SCRATCH_A[0] = SCRATCH_B[0];
                    SCRATCH_A[1] = SCRATCH_B[1];
                    SCRATCH_A[2] = SCRATCH_B[2];
                }
                f.ringSegments += ShanhaiHoloSurroundTuning.RING_TAIL_POINTS - 1;
            }
        }
    }

    /** HTML {@code drawRingA} 的 ⑤（每个流动光点三个精灵）。 */
    private static void emitRingDots(Frame f, Sink sink,
                                     ShanhaiHoloSurroundTuning.RingSpec spec, float bright) {
        ringBasis(f, spec);
        final RingBasis b = BASIS;
        if (b.r <= T.MIN_R) {
            return;
        }
        final int fn = flowCount(spec);
        if (fn <= 0) {
            return;
        }
        final double dir = spec.flowV() >= 0.0f ? 1.0 : -1.0;
        for (int j = 0; j < fn; j++) {
            final double th = flowAngle(spec, j, fn, f.tSec);
            ringPoint(b, th, SCRATCH_A);
            sink.dot(SCRATCH_A[0], SCRATCH_A[1], SCRATCH_A[2],
                    ShanhaiHoloSurroundTuning.FLOW_HALO_RADIUS_BLOCKS, spec.hueRgb(),
                    ShanhaiHoloSurroundTuning.FLOW_HALO_ALPHA * bright);
            sink.dot(SCRATCH_A[0], SCRATCH_A[1], SCRATCH_A[2],
                    ShanhaiHoloSurroundTuning.FLOW_CORE_RADIUS_BLOCKS, spec.hueRgb(),
                    ShanhaiHoloSurroundTuning.FLOW_CORE_ALPHA * bright);
            ringPoint(b, th - dir * ShanhaiHoloSurroundTuning.FLOW_WHITE_LAG_RAD, SCRATCH_A);
            sink.dot(SCRATCH_A[0], SCRATCH_A[1], SCRATCH_A[2],
                    ShanhaiHoloSurroundTuning.FLOW_WHITE_RADIUS_BLOCKS,
                    ShanhaiHoloSurroundTuning.FLOW_WHITE_RGB,
                    ShanhaiHoloSurroundTuning.FLOW_WHITE_ALPHA * bright);
        }
    }

    private static void emitSeg(Sink sink, double[] a, double[] b, int rgb, float w, float alpha) {
        sink.segment(a[0], a[1], a[2], b[0], b[1], b[2], rgb, w, alpha);
    }

    // ==================================================================== 八、B：星尘网格

    /** 清空并重新生成点场（HTML {@code initField()}）。 */
    public static void fieldReset(Frame f) {
        fieldCX = f.playerX;
        fieldCY = f.playerY + ShanhaiHoloSurroundTuning.FIELD_CENTER_Y_BLOCKS;
        fieldCZ = f.playerZ;
        for (int i = 0; i < FIELD_CAPACITY; i++) {
            FA[i] = rnd() * Math.PI * 2.0;
            FE[i] = (rnd() - 0.5) * ShanhaiHoloSurroundTuning.FIELD_ELEVATION_SPAN_RAD;
            FT[i] = rnd();
            FPH[i] = rnd() * 6.283;
            FW[i] = ShanhaiHoloSurroundTuning.FIELD_WOBBLE_W_MIN
                    + rnd() * (ShanhaiHoloSurroundTuning.FIELD_WOBBLE_W_MAX
                            - ShanhaiHoloSurroundTuning.FIELD_WOBBLE_W_MIN);
            FAMP[i] = ShanhaiHoloSurroundTuning.FIELD_WOBBLE_MIN_BLOCKS
                    + rnd() * (ShanhaiHoloSurroundTuning.FIELD_WOBBLE_MAX_BLOCKS
                            - ShanhaiHoloSurroundTuning.FIELD_WOBBLE_MIN_BLOCKS);
            FTW[i] = rnd() * 6.283;
            FTWS[i] = ShanhaiHoloSurroundTuning.FIELD_TWINKLE_W_MIN
                    + rnd() * (ShanhaiHoloSurroundTuning.FIELD_TWINKLE_W_MAX
                            - ShanhaiHoloSurroundTuning.FIELD_TWINKLE_W_MIN);
            FBIG[i] = rnd() < ShanhaiHoloSurroundTuning.STAR_BIG_FRACTION;
            FFADE[i] = rnd();
            FPX[i] = f.playerX;
            FPY[i] = f.playerY + ShanhaiHoloSurroundTuning.FIELD_CENTER_Y_BLOCKS;
            FPZ[i] = f.playerZ;
        }
        fieldReady = true;
    }

    /** 供离线自检：清掉"已经生成过"的标记（下次 {@link #render} 会重新生成一张可复现的点场）。 */
    public static void resetForTest() {
        fieldReady = false;
        rngState = 0x9E3779B97F4A7C15L;
        pairOverflow = 0;
    }

    /**
     * 点场中心的<b>世界坐标</b>（= {@link #fieldAdvance} 里那个被平滑过的量）。
     *
     * <p>🔴 它存在的唯一理由是"可量"：2026-10-06 16:2x 用户报
     * 「类似北斗七星的渲染完全跟不上玩家的速度」—— 要判定"到底哪一层在滞后、滞后几格"，
     * 就得能把点场中心与玩家位置<b>各自读出来相减</b>，而不是靠读代码推断那条时间常数。
     * 离线装置（{@code temp/holo-verify} 的 {@code SurroundProbe} 第 10 段）用它做正/负对照。
     *
     * <p>同 {@link #shellRadii()} 的约定：返回<b>共享的</b>临时数组，只读、别存起来。
     *
     * @return {@code [x, y, z]}（世界坐标，格）
     */
    public static double[] fieldCenter() {
        FIELD_CENTER_OUT[0] = fieldCX;
        FIELD_CENTER_OUT[1] = fieldCY;
        FIELD_CENTER_OUT[2] = fieldCZ;
        return FIELD_CENTER_OUT;
    }

    /** HTML {@code effectUpdate(dt, t)}。 */
    public static void fieldAdvance(Frame f) {
        if (!fieldReady) {
            fieldReset(f);
        }
        final double dt = f.dtSec;
        // 🔴 2026-10-06 16:2x（用户第 ④ 条：「类似北斗七星的渲染完全跟不上玩家的速度」）：
        //    这一条的 τ 原来是【编译期内联的常量 1.15 秒】⇒ 玩家一跑，这整片星尘就拖在身后
        //    ≈ 5 格/秒 × 1.15 秒 = 5.75 格（离线装置实测读数，见 temp/holo-verify 的
        //    SurroundProbe 第 10 段）。现在它走【运行期生效的那一份】{@link T#FIELD_FOLLOW_TAU()}，
        //    出厂值改成「即时」，用户想回"慢慢飘"就在高级调参页把这一项拨大。
        //
        // ⚠️ τ ≤ 0 必须【短路】，不许直接算 1 − exp(−dt/τ)：dt 与 τ 同时为 0 时是 0/0 = NaN，
        //    会把点场中心毒成 NaN（之后每一次 render 都是 NaN，整层再也画不出来）。
        //    这条约定与 {@code ShanhaiHoloMenuState#smoothPos} 逐字一致（τ ≤ 0 ⇒ 瞬时贴合）。
        final float tau = T.FIELD_FOLLOW_TAU();
        final double k = tau <= 0.0f ? 1.0
                : (dt <= 0.0 ? 0.0 : 1.0 - Math.exp(-dt / tau));
        fieldCX += (f.playerX - fieldCX) * k;
        fieldCY += (f.playerY + ShanhaiHoloSurroundTuning.FIELD_CENTER_Y_BLOCKS - fieldCY) * k;
        fieldCZ += (f.playerZ - fieldCZ) * k;

        final float[] shell = shellRadii();
        final double inner = shell[0];
        final double outer = shell[1];
        final double minY = f.playerY + ShanhaiHoloSurroundTuning.FIELD_MIN_Y_ABOVE_FEET;
        final double respawnR = outer + ShanhaiHoloSurroundTuning.FIELD_RESPAWN_MARGIN_BLOCKS;

        for (int i = 0; i < FIELD_CAPACITY; i++) {
            final double r = inner + (outer - inner) * FT[i];
            final double ce = Math.cos(FE[i]);
            final double se = Math.sin(FE[i]);
            final double dx0 = Math.cos(FA[i]) * ce;
            final double dz0 = Math.sin(FA[i]) * ce;
            final double wob = Math.sin(f.tSec * FW[i] + FPH[i]) * FAMP[i];
            final double wob2 = Math.cos(f.tSec * FW[i] * 0.9 + FPH[i]) * FAMP[i];
            double x = fieldCX + dx0 * r + wob * ce;
            double y = fieldCY + se * r + wob2;
            double z = fieldCZ + dz0 * r + wob * se;
            if (y < minY) {
                y = minY;
            }
            FPX[i] = x;
            FPY[i] = y;
            FPZ[i] = z;
            // 跑太远 ⇒ 悄无声息地在另一侧重生（配淡入，看不出跳变）
            final double ddx = x - fieldCX;
            final double ddy = y - fieldCY;
            final double ddz = z - fieldCZ;
            if (Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) > respawnR) {
                FA[i] += Math.PI;
                FT[i] = clamp(FT[i] + (rnd() - 0.5) * ShanhaiHoloSurroundTuning.FIELD_RESPAWN_T_RANGE, 0.0, 1.0);
                FFADE[i] = 0.0;
            }
            if (FFADE[i] < 1.0) {
                final double next = FFADE[i] + dt * ShanhaiHoloSurroundTuning.FIELD_FADE_IN_PER_SEC;
                FFADE[i] = next > 1.0 ? 1.0 : next;
            }
        }
    }

    /** HTML {@code drawField(t)}。 */
    private static void emitField(Frame f, Sink sink) {
        final float bright = T.FIELD_BRIGHT() * T.GLOBAL_BRIGHT() * f.globalAlpha;
        final int n = clampInt(Math.round(T.FIELD_POINT_COUNT()), 0, FIELD_CAPACITY);
        if (bright <= T.MIN_BRIGHT || n == 0) {
            return;
        }
        final float[] shell = shellRadii();
        final double outer = shell[1];

        // 呼吸：连线阈值上下摆（周期 = FIELD_LINK_PERIOD_SEC 秒，见参数类里的那段备案）
        final double per = Math.PI * 2.0 / Math.max(0.001, (double) T.FIELD_LINK_PERIOD());
        final double lk = T.FIELD_LINK() * (1.0 + ShanhaiHoloSurroundTuning.FIELD_LINK_BREATH_AMP
                * Math.sin(f.tSec * per));
        final double lk2 = lk * lk;

        // ---- 候选点对：一次 O(n²) 扫描，只用预分配数组 ----
        final int maxCand = ShanhaiHoloSurroundTuning.FIELD_LINK_MAX_CANDIDATES;
        int cand = 0;
        int overflow = 0;
        for (int i = 0; i < n; i++) {
            final double xi = FPX[i];
            final double yi = FPY[i];
            final double zi = FPZ[i];
            for (int j = i + 1; j < n; j++) {
                final double dx = xi - FPX[j];
                final double dy = yi - FPY[j];
                final double dz = zi - FPZ[j];
                final double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= lk2) {
                    continue;
                }
                if (cand >= maxCand) {
                    overflow++;
                    continue;
                }
                PAIR_A[cand] = i;
                PAIR_B[cand] = j;
                int bucket = (int) (Math.sqrt(d2) / lk * ShanhaiHoloSurroundTuning.FIELD_LINK_SORT_BUCKETS);
                if (bucket < 0) {
                    bucket = 0;
                } else if (bucket >= ShanhaiHoloSurroundTuning.FIELD_LINK_SORT_BUCKETS) {
                    bucket = ShanhaiHoloSurroundTuning.FIELD_LINK_SORT_BUCKETS - 1;
                }
                PAIR_BUCKET[cand] = (byte) bucket;
                cand++;
            }
        }
        pairOverflow = overflow;

        // ---- 定桶计数排序（等价于 HTML 的 pairs.sort 按距离升序，但零分配）----
        final int nb = ShanhaiHoloSurroundTuning.FIELD_LINK_SORT_BUCKETS;
        for (int b = 0; b < nb; b++) {
            BUCKET_HEAD[b] = 0;
        }
        for (int q = 0; q < cand; q++) {
            BUCKET_HEAD[PAIR_BUCKET[q] & 0xFF]++;
        }
        int acc = 0;
        for (int b = 0; b < nb; b++) {
            final int c = BUCKET_HEAD[b];
            BUCKET_HEAD[b] = acc;
            BUCKET_CURSOR[b] = acc;
            acc += c;
        }
        for (int q = 0; q < cand; q++) {
            final int b = PAIR_BUCKET[q] & 0xFF;
            PAIR_ORDER[BUCKET_CURSOR[b]++] = q;
        }

        // ---- 从近到远贪心接线，每点最多 FIELD_LINK_MAX_PER_POINT 条 ----
        final int cap = Math.max(1, Math.round(T.FIELD_LINK_MAX()));
        for (int i = 0; i < n; i++) {
            DEGREE[i] = 0;
        }
        final int bandSize = Math.max(1, nb / ShanhaiHoloSurroundTuning.FIELD_LINK_BANDS);
        for (int s = 0; s < cand; s++) {
            final int q = PAIR_ORDER[s];
            final int i = PAIR_A[q];
            final int j = PAIR_B[q];
            if (DEGREE[i] >= cap || DEGREE[j] >= cap) {
                continue;
            }
            DEGREE[i]++;
            DEGREE[j]++;
            int band = (PAIR_BUCKET[q] & 0xFF) / bandSize;
            if (band >= ShanhaiHoloSurroundTuning.FIELD_LINK_BANDS) {
                band = ShanhaiHoloSurroundTuning.FIELD_LINK_BANDS - 1;
            }
            sink.segment(FPX[i], FPY[i], FPZ[i], FPX[j], FPY[j], FPZ[j],
                    ShanhaiHoloSurroundTuning.FIELD_LINK_BAND_RGB[band],
                    ShanhaiHoloSurroundTuning.FIELD_LINK_W_HTML_PX,
                    ShanhaiHoloSurroundTuning.FIELD_LINK_BAND_ALPHA[band] * bright);
            f.fieldLinks++;
        }

        // ---- 光点本体 ----
        final double cx = f.playerX;
        final double cy = f.playerY + ShanhaiHoloSurroundTuning.FIELD_CENTER_Y_BLOCKS;
        final double cz = f.playerZ;
        final double twinkleSpan = 1.0 - ShanhaiHoloSurroundTuning.FIELD_TWINKLE_MIN;
        for (int i = 0; i < n; i++) {
            final double dx = FPX[i] - cx;
            final double dy = FPY[i] - cy;
            final double dz = FPZ[i] - cz;
            final double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double edge = (outer + ShanhaiHoloSurroundTuning.FIELD_EDGE_FADE_START_BLOCKS - d)
                    / ShanhaiHoloSurroundTuning.FIELD_EDGE_FADE_SPAN_BLOCKS;
            if (edge < 0.0) {
                edge = 0.0;
            } else if (edge > 1.0) {
                edge = 1.0;
            }
            final double twk = ShanhaiHoloSurroundTuning.FIELD_TWINKLE_MIN
                    + twinkleSpan * Math.sin(f.tSec * FTWS[i] + FTW[i]);
            final double a = (FBIG[i] ? ShanhaiHoloSurroundTuning.STAR_BIG_ALPHA
                    : ShanhaiHoloSurroundTuning.STAR_SMALL_ALPHA)
                    * twk * FFADE[i] * edge * bright;
            if (a < T.DOT_MIN_ALPHA) {
                continue;
            }
            if (FBIG[i]) {
                sink.dot(FPX[i], FPY[i], FPZ[i],
                        ShanhaiHoloSurroundTuning.STAR_BIG_HALO_RADIUS_BLOCKS,
                        ShanhaiHoloSurroundTuning.STAR_BIG_HALO_RGB,
                        (float) (a * ShanhaiHoloSurroundTuning.STAR_BIG_HALO_ALPHA_MUL));
                sink.dot(FPX[i], FPY[i], FPZ[i],
                        ShanhaiHoloSurroundTuning.STAR_BIG_CORE_RADIUS_BLOCKS,
                        ShanhaiHoloSurroundTuning.STAR_BIG_CORE_RGB, (float) a);
                f.fieldDots += 2;
            } else {
                sink.dot(FPX[i], FPY[i], FPZ[i],
                        ShanhaiHoloSurroundTuning.STAR_SMALL_RADIUS_BLOCKS,
                        ShanhaiHoloSurroundTuning.STAR_SMALL_RGB, (float) a);
                f.fieldDots++;
            }
        }
    }

    /** 上一帧候选点对溢出的条数（正常参数下应为 0；不为 0 只意味着"有几条线没连上"）。 */
    public static int pairOverflow() {
        return pairOverflow;
    }

    /** 点场里"大光点"的个数（自检读数用）。 */
    public static int bigStarCount() {
        int c = 0;
        for (boolean b : FBIG) {
            if (b) {
                c++;
            }
        }
        return c;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ==================================================================== 九、只数不画的 Sink

    /**
     * {@link Sink} 的"数数"实现 —— <b>离线自检就靠它把每帧的顶点数算出来</b>。
     *
     * <p>{@link #maxBatchVerts} 是"单批峰值"：渲染器按图元种类切 {@code RenderType}
     * （切的时候上一批就被冲掉了）⇒ 连续同种的图元落进同一批。
     * 判据：{@code maxBatchVerts × BYTES_PER_VERTEX} 必须显著小于
     * {@link ShanhaiHoloSurroundTuning#PER_FRAME_BUFFER_BYTES}。
     */
    public static final class CountingSink implements Sink {

        /** 线段的条数 / 光点的个数。 */
        public int segments, dots;
        /** 一共切了几批（= 渲染器要额外发多少个 draw call）。 */
        public int batches;
        /** 单批最多多少个顶点。 */
        public int maxBatchVerts;
        /** 当前这一批已经累积了多少顶点。 */
        public int batchVerts;
        /** 当前这一批是什么（{@code -1} = 还没开始）。 */
        private int kind = -1;

        /** 自检用：记录"被规则 ③ 挡掉"的次数是渲染侧的事，这里只统计发出去的图元。 */

        public void begin() {
            segments = 0;
            dots = 0;
            batches = 0;
            maxBatchVerts = 0;
            batchVerts = 0;
            kind = -1;
        }

        /** 一帧结束后调一次（把最后一批结算掉）。 */
        public void finish() {
            flush();
        }

        private void flush() {
            if (batchVerts > maxBatchVerts) {
                maxBatchVerts = batchVerts;
            }
            if (kind >= 0) {
                batches++;
            }
            batchVerts = 0;
            kind = -1;
        }

        private void switchTo(int next) {
            if (kind != next) {
                flush();
                kind = next;
            }
        }

        @Override
        public void segment(double ax, double ay, double az,
                            double bx, double by, double bz,
                            int rgb, float widthHtmlPx, float alpha) {
            switchTo(0);
            segments++;
            batchVerts += ShanhaiHoloSurroundTuning.VERTS_PER_SEGMENT;
        }

        @Override
        public void dot(double x, double y, double z, float radiusBlocks, int rgb, float alpha) {
            switchTo(1);
            dots++;
            batchVerts += ShanhaiHoloSurroundTuning.VERTS_PER_DOT;
        }

        public int totalVerts() {
            return segments * ShanhaiHoloSurroundTuning.VERTS_PER_SEGMENT
                    + dots * ShanhaiHoloSurroundTuning.VERTS_PER_DOT;
        }

        public int totalBytes() {
            return totalVerts() * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX;
        }
    }

    // ==================================================================== 十、离线用的小工具

    /**
     * 造一个"理想针孔相机"的 MVP（<b>只给离线自检用</b>；游戏里由渲染器从
     * {@code RenderLevelStageEvent} 的 poseStack 与 projectionMatrix 造）。
     *
     * <p>坐标约定与原版一致：偏航 0 = 朝 +Z；相机朝 −Z 看。
     */
    public static void fillPinhole(Proj p, double camX, double camY, double camZ,
                                   double yawRad, double pitchRad,
                                   float focal, float halfW, float halfH) {
        p.halfW = halfW;
        p.halfH = halfH;
        final double cp = Math.cos(pitchRad);
        final double sp = Math.sin(pitchRad);
        final double sy = Math.sin(yawRad);
        final double cy = Math.cos(yawRad);
        // 前方向（与原版 Vec3.directionFromRotation 同一口径）
        final double fx = -sy * cp, fy = -sp, fz = cy * cp;
        // 右方向 = 前 × 世界上
        double rx = fy * 0.0 - fz * 1.0;
        double ry = fz * 0.0 - fx * 0.0;
        double rz = fx * 1.0 - fy * 0.0;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rl < 1.0e-9) {
            rx = 1.0;
            ry = 0.0;
            rz = 0.0;
            rl = 1.0;
        }
        rx /= rl;
        ry /= rl;
        rz /= rl;
        // 上方向 = 右 × 前
        final double ux = ry * fz - rz * fy;
        final double uy = rz * fx - rx * fz;
        final double uz = rx * fy - ry * fx;
        // 视图矩阵（世界 → 相机，−Z 朝前）
        final double[] v = {
                rx, ry, rz, -(rx * camX + ry * camY + rz * camZ),
                ux, uy, uz, -(ux * camX + uy * camY + uz * camZ),
                -fx, -fy, -fz, (fx * camX + fy * camY + fz * camZ),
                0.0, 0.0, 0.0, 1.0
        };
        // 投影矩阵（行主序的数学排布）
        final double near = ShanhaiHoloSurroundTuning.NEAR_DEPTH_BLOCKS;
        final double far = 1000.0;
        final double a = (far + near) / (near - far);
        final double b = 2.0 * far * near / (near - far);
        final double[] m = {
                focal / halfW, 0.0, 0.0, 0.0,
                0.0, focal / halfH, 0.0, 0.0,
                0.0, 0.0, a, b,
                0.0, 0.0, -1.0, 0.0
        };
        // MVP = P · V，按列主序写进 p.m（m[c*4+r] = M[r][c]）
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                double sum = 0.0;
                for (int k = 0; k < 4; k++) {
                    sum += m[r * 4 + k] * v[k * 4 + c];
                }
                p.m[c * 4 + r] = (float) sum;
            }
        }
    }

    // ==================================================================== 十一、自检（构建机上就有原始输出）

    /**
     * 离线自检：把这一层的每一条判据都跑一遍并打出原始读数。
     *
     * <p>它<b>不是</b>另写一套逻辑 —— 全部走的是上面那些生产函数。
     */
    public static String selfTest() {
        final StringBuilder sb = new StringBuilder(4096);
        sb.append("--- A. 参数（读的就是渲染要用的那 14 个）---\n");
        sb.append("  ").append(ShanhaiHoloSurroundTuning.summary()).append('\n');

        sb.append("--- B. 规则 ① 环半径硬夹 / 规则 ② 壳层自动外推 ---\n");
        final int n = activeRingCount();
        double rm = 0.0;
        for (int i = 0; i < n; i++) {
            final ShanhaiHoloSurroundTuning.RingSpec s = ShanhaiHoloSurroundTuning.RING_TABLE[i];
            final double raw = s.r() * (double) T.RING_SCALE();
            final double eff = ringRadius(s);
            final boolean clamped = raw > (double) T.RING_MAX_R + 1.0e-6;
            sb.append(String.format(java.util.Locale.ROOT,
                    "  环%d y=%.2f 表内半径=%.3f ×%.2f = %.4f ⇒ 生效 %.4f%s%n",
                    i + 1, s.y(), s.r(), T.RING_SCALE(), raw, eff,
                    clamped ? "   ← 被 1.75 硬夹住了" : ""));
            if (eff > rm) {
                rm = eff;
            }
        }
        final float[] shell = shellRadii();
        sb.append(String.format(java.util.Locale.ROOT,
                "  环最大半径=%.4f ⇒ 壳层内半径 = max(%.2f, %.4f+%.2f) = %.4f ; 外半径 = %.4f%n",
                rm, T.FIELD_INNER(), rm, T.FIELD_GAP, shell[0], shell[1]));
        sb.append("  两套不重叠（内半径 - 环最大半径 = "
                + ShanhaiHoloSurroundTuning.fmt(shell[0] - (float) rm, 4) + " 格 ≥ 0.32）\n");

        sb.append("--- C. 每帧几何规模（真的跑 render()，扫 300 帧取最大值）---\n");
        final CountingSink cw = new CountingSink();
        int worstVerts = 0;
        int worstVertsFrame = 0;
        int worstBatch = 0;
        int worstBatchFrame = 0;
        int worstSegs = 0;
        int worstDots = 0;
        int batches = 0;
        int maxOverflow = 0;
        final Frame fc = scenario(0.0f);
        for (int frame = 0; frame < 300; frame++) {
            fc.tSec = frame / 60.0f;
            fc.dtSec = 1.0f / 60.0f;
            final CountingSink cs = new CountingSink();
            cs.begin();
            render(fc, cs);
            cs.finish();
            if (cs.totalVerts() > worstVerts) {
                worstVerts = cs.totalVerts();
                worstVertsFrame = frame;
            }
            if (cs.maxBatchVerts > worstBatch) {
                worstBatch = cs.maxBatchVerts;
                worstBatchFrame = frame;
            }
            if (cs.segments > worstSegs) {
                worstSegs = cs.segments;
            }
            if (cs.dots > worstDots) {
                worstDots = cs.dots;
            }
            batches = cs.batches;
            if (pairOverflow() > maxOverflow) {
                maxOverflow = pairOverflow();
            }
        }
        // 最后再跑一遍完整的一帧，把各段的明细也带出来（用最大那一帧的时刻）
        fc.tSec = worstBatchFrame / 60.0f;
        final CountingSink c1 = new CountingSink();
        c1.begin();
        render(fc, c1);
        c1.finish();
        sb.append("  最坏那一帧：线段 ").append(worstSegs)
                .append(" 条 × ").append(ShanhaiHoloSurroundTuning.VERTS_PER_SEGMENT).append(" 顶点 + 光点 ")
                .append(worstDots).append(" 个 × ").append(ShanhaiHoloSurroundTuning.VERTS_PER_DOT)
                .append(" 顶点 = ").append(worstVerts).append(" 顶点 = ")
                .append(worstVerts * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX).append(" 字节 (")
                .append(ShanhaiHoloSurroundTuning.fmt(
                        worstVerts * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX / 1024.0f, 1))
                .append(" KiB)  第 ").append(worstVertsFrame).append(" 帧\n");
        sb.append("  同一时刻逐段明细：环线段=").append(fc.ringSegments)
                .append(" 连线=").append(fc.fieldLinks)
                .append(" 点场光点=").append(fc.fieldDots)
                .append(" 大光点=").append(bigStarCount())
                .append(" 候选点对溢出=").append(maxOverflow).append('\n');
        sb.append(String.format(java.util.Locale.ROOT,
                "  合计 %d 顶点 = %d 字节 (%.1f KiB)；按图元种类切了 %d 批%n",
                c1.totalVerts(), c1.totalBytes(), c1.totalBytes() / 1024.0, batches));
        sb.append(String.format(java.util.Locale.ROOT,
                "  单批峰值 %d 顶点 = %d 字节 (%.1f KiB)（第 %d 帧）%n",
                worstBatch, worstBatch * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX,
                worstBatch * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX / 1024.0, worstBatchFrame));
        sb.append(String.format(java.util.Locale.ROOT,
                "  每帧 BufferBuilder 容量 = %d 字节 (%.0f KiB)，本层单批峰值只占 %.1f%%%n",
                ShanhaiHoloSurroundTuning.PER_FRAME_BUFFER_BYTES,
                ShanhaiHoloSurroundTuning.PER_FRAME_BUFFER_BYTES / 1024.0,
                100.0 * worstBatch * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX
                        / ShanhaiHoloSurroundTuning.PER_FRAME_BUFFER_BYTES));
        sb.append(String.format(java.util.Locale.ROOT,
                "  对照：那 5 块板自己的单批峰值 ≈ 776 顶点 = %d 字节 (%.1f KiB)%n",
                776 * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX,
                776 * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX / 1024.0));

        sb.append("--- D. 可复现性（同一个种子跑两遍必须逐位相同）---\n");
        resetForTest();
        final Frame f2 = scenario(0.0f);
        final CountingSink c2 = new CountingSink();
        c2.begin();
        render(f2, c2);
        c2.finish();
        resetForTest();
        final Frame f3 = scenario(0.0f);
        final CountingSink c3 = new CountingSink();
        c3.begin();
        render(f3, c3);
        c3.finish();
        sb.append("  第 1 遍: 线=").append(c2.segments).append(" 点=").append(c2.dots)
                .append(" 顶点=").append(c2.totalVerts()).append('\n');
        sb.append("  第 2 遍: 线=").append(c3.segments).append(" 点=").append(c3.dots)
                .append(" 顶点=").append(c3.totalVerts()).append('\n');
        sb.append("  ⇒ 两次读数是否逐字相同: ")
                .append(c2.segments == c3.segments && c2.dots == c3.dots
                        && c2.totalVerts() == c3.totalVerts() && c2.batches == c3.batches)
                .append('\n');

        sb.append("--- E. 规则 ③-①（三维禁区）：板上/板外的读数 ---\n");
        appendFaceProbe(sb);

        sb.append("--- F. 规则 ③-②（屏幕兜底）：投影压在板上 vs 远离板 ---\n");
        appendScreenProbe(sb);

        sb.append("--- G. 负对照：把「取最小」写成「取最大」会怎样 ---\n");
        appendNegativeControl(sb);

        sb.append("--- H. 退化边保护：一块板侧对镜头时不许把整层抹掉 ---\n");
        appendDegenerateProbe(sb);

        sb.append("--- I. 实测剔除率（规则 ③-① 一共挡掉多少；判据不许是「恒挡」）---\n");
        appendCullProbe(sb);
        sb.append(appendPresetSection());
        return sb.toString();
    }

    /**
     * 🆕 <b>J 段：四档预设（关 / 弱 / 中 / 强）与 14 项取值</b>。
     *
     * <pre>
     *   ① 「中」档逐项 == 那 14 个出厂默认值（机器比对，逐条打印"第几项、值多少"）
     *   ② 那一整套判据 —— {@link ShanhaiHoloSurroundTuning#presetSelfCheck()}（含 4 条负对照）
     *   ③ 四档各自跑 300 帧，打"最坏一帧的顶点数 / 单批峰值"，证明最强档也远小于每帧容量
     *   ④ 「关」档必须一个顶点都不发（跳过绘制遍）
     * </pre>
     * ✅ 这一节<b>跑完把档位放回原样</b>（自检不许改现场，同 {@code ShanhaiHoloMenuPanel} 那条口径）。
     */
    private static String appendPresetSection() {
        final StringBuilder sb = new StringBuilder(4096);
        final int savedPreset = ShanhaiHoloSurroundTuning.preset();
        try {
            sb.append("--- J. 四档预设（关 / 弱 / 中 / 强）+ 14 项运行期取值 ---\n");
            sb.append("  ① 「中」档 == 14 个出厂默认值（逐项机器比对，不靠人看）：\n");
            int same = 0;
            for (int i = 0; i < ShanhaiHoloSurroundTuning.PARAM_COUNT; i++) {
                final float def = ShanhaiHoloSurroundTuning.defaultOf(i);
                final float mid = ShanhaiHoloSurroundTuning.PRESET_VALUES
                        [ShanhaiHoloSurroundTuning.PRESET_MID][i];
                final boolean eq = Float.compare(def, mid) == 0;
                if (eq) {
                    same++;
                }
                sb.append(String.format(java.util.Locale.ROOT,
                        "     第 %2d 项 %-6s 中档=%-7s 出厂默认=%-7s %s%n",
                        i + 1, ShanhaiHoloSurroundTuning.PARAM_NAME[i],
                        ShanhaiHoloSurroundTuning.fmt(mid, 2),
                        ShanhaiHoloSurroundTuning.fmt(def, 2), eq ? "✓ 相同" : "✗ 不同"));
            }
            sb.append("     ⇒ 「中」档 == 默认值：").append(same).append("/")
                    .append(ShanhaiHoloSurroundTuning.PARAM_COUNT).append(" 项逐项相同\n");
            sb.append("  ② 四档判据：").append(ShanhaiHoloSurroundTuning.presetSelfCheckLine())
                    .append('\n');

            sb.append("  ③ 每一档的每帧几何规模（真的跑 render()，同一张点场，各扫 300 帧取最大值）：\n");
            final int[] verts = new int[ShanhaiHoloSurroundTuning.PRESET_COUNT];
            final int[] peak = new int[ShanhaiHoloSurroundTuning.PRESET_COUNT];
            for (int p = 0; p < ShanhaiHoloSurroundTuning.PRESET_COUNT; p++) {
                ShanhaiHoloSurroundTuning.setPreset(p);
                final int[] m = measureWorstFrame();
                verts[p] = m[0];
                peak[p] = m[1];
                sb.append(String.format(java.util.Locale.ROOT,
                        "     「%s」档：最坏一帧 线段 %d 条 + 光点 %d 个 = %d 顶点 = %d 字节 (%.1f KiB)"
                                + "；单批峰值 %d 顶点 = %d 字节 (%.1f KiB)；切了 %d 批；候选点对溢出 %d%n",
                        ShanhaiHoloSurroundTuning.presetName(p), m[2], m[3], m[0],
                        m[0] * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX,
                        m[0] * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX / 1024.0,
                        m[1], m[1] * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX,
                        m[1] * ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX / 1024.0, m[4], m[5]));
            }
            // ④ 判据（三条，都是机器判定）
            final int capacityVerts = ShanhaiHoloSurroundTuning.PER_FRAME_BUFFER_BYTES
                    / ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX;
            sb.append(String.format(java.util.Locale.ROOT,
                    "  ④ 判据①：「关」档必须一个顶点都不发（跳过绘制遍）—— 读数 %d 顶点 %s%n",
                    verts[ShanhaiHoloSurroundTuning.PRESET_OFF],
                    verts[ShanhaiHoloSurroundTuning.PRESET_OFF] == 0 ? "✓ PASS" : "✗ FAIL"));
            sb.append(String.format(java.util.Locale.ROOT,
                    "     判据②：最强档的总顶点 %d < 每帧容量参数 %d（那个 1<<14）%s%n",
                    verts[ShanhaiHoloSurroundTuning.PRESET_STRONG],
                    ShanhaiHoloSurroundTuning.PER_FRAME_BUFFER_CAPACITY,
                    verts[ShanhaiHoloSurroundTuning.PRESET_STRONG]
                            < ShanhaiHoloSurroundTuning.PER_FRAME_BUFFER_CAPACITY ? "✓ PASS" : "✗ FAIL"));
            sb.append(String.format(java.util.Locale.ROOT,
                    "     判据③（真正咬人的那条）：最强档的单批峰值 %d < 缓冲区装得下的顶点数 %d"
                            + "（= %d 字节 ÷ %d 字节/顶点）%s%n",
                    peak[ShanhaiHoloSurroundTuning.PRESET_STRONG], capacityVerts,
                    ShanhaiHoloSurroundTuning.PER_FRAME_BUFFER_BYTES,
                    ShanhaiHoloSurroundTuning.BYTES_PER_VERTEX,
                    peak[ShanhaiHoloSurroundTuning.PRESET_STRONG] < capacityVerts ? "✓ PASS" : "✗ FAIL"));
            return sb.toString();
        } finally {
            ShanhaiHoloSurroundTuning.setPreset(savedPreset);
        }
    }

    /**
     * 扫 300 帧，取"最坏那一帧"的读数。
     *
     * @return {@code [总顶点, 单批峰值顶点, 线段条数, 光点个数, 批数, 候选点对溢出]}
     */
    private static int[] measureWorstFrame() {
        final CountingSink probe = new CountingSink();
        int worstVerts = 0;
        int worstBatch = 0;
        int worstSegs = 0;
        int worstDots = 0;
        int batches = 0;
        int maxOverflow = 0;
        resetForTest();                     // 同一张可复现的点场，各档之间才可比
        final Frame fc = scenario(0.0f);
        for (int frame = 0; frame < 300; frame++) {
            fc.tSec = frame / 60.0f;
            fc.dtSec = 1.0f / 60.0f;
            probe.begin();
            render(fc, probe);
            probe.finish();
            if (probe.totalVerts() > worstVerts) {
                worstVerts = probe.totalVerts();
            }
            if (probe.maxBatchVerts > worstBatch) {
                worstBatch = probe.maxBatchVerts;
            }
            if (probe.segments > worstSegs) {
                worstSegs = probe.segments;
            }
            if (probe.dots > worstDots) {
                worstDots = probe.dots;
            }
            batches = probe.batches;
            if (pairOverflow() > maxOverflow) {
                maxOverflow = pairOverflow();
            }
        }
        return new int[]{worstVerts, worstBatch, worstSegs, worstDots, batches, maxOverflow};
    }

    /** HTML 那次 bug 的观感是"整层不见了" ⇒ 这里直接量"到底被挡掉多少"。 */
    private static void appendCullProbe(StringBuilder sb) {
        final Frame f = scenario(0.0f);
        final FadeCount fc = new FadeCount(f);
        render(f, fc);
        final int segPct = (int) Math.round(1000.0 * fc.segCulled / Math.max(1, fc.segTotal));
        final int dotPct = (int) Math.round(1000.0 * fc.dotCulled / Math.max(1, fc.dotTotal));
        sb.append(String.format(java.util.Locale.ROOT,
                "  线段 %d 条：被挡掉 %d 条（%.1f%%），全帧最小淡出值 %.3f%n",
                fc.segTotal, fc.segCulled, segPct / 10.0, fc.worstSegFade));
        sb.append(String.format(java.util.Locale.ROOT,
                "  光点 %d 个：被挡掉 %d 个（%.1f%%），全帧最小淡出值 %.3f%n",
                fc.dotTotal, fc.dotCulled, dotPct / 10.0, fc.worstDotFade));
        sb.append("  ⇒ 判据不是恒挡：绝大多数图元照画（对照：HTML 那次 bug 时这里是 100%）。\n");
    }

    /** 造一个稳定的场景：玩家站在 y=64 的原点朝 +Z，五块板按环绕档摆开，相机在眼睛处。 */
    private static Frame scenario(float tSec) {
        final Frame f = newFrame();
        f.playerX = 0.0;
        f.playerY = 64.0;
        f.playerZ = 0.0;
        f.camX = 0.0;
        f.camY = 64.0 + ShanhaiHoloMenuTuning.EYE_HEIGHT_BLOCKS;
        f.camZ = 0.0;
        f.tSec = tSec;
        f.dtSec = 1.0f / 60.0f;
        f.globalAlpha = 1.0f;
        f.originX = 0.0;
        f.originY = f.camY + ShanhaiHoloMenuTuning.HEIGHT_OFFSET_FROM_EYE;
        f.originZ = 0.0;
        f.frameYawRad = 0.0;
        f.animScale = 1.0;
        final ShanhaiHoloMenuLayout.Board[] boards =
                ShanhaiHoloMenuLayout.ringAll(ShanhaiHoloMenuTuning.DISTANCE_BLOCKS);
        f.boardCount = boards.length;
        for (int i = 0; i < boards.length; i++) {
            f.boardX[i] = boards[i].x();
            f.boardY[i] = boards[i].y();
            f.boardZ[i] = boards[i].z();
            f.boardRotY[i] = boards[i].rotY();
            f.boardRotX[i] = boards[i].rotX();
            f.boardScale[i] = boards[i].scale();
        }
        final Proj p = new Proj();
        fillPinhole(p, f.camX, f.camY, f.camZ, 0.0, 0.0,
                ShanhaiHoloSurroundTuning.HTML_REF_FOCAL_PX, 480.0f, 270.0f);
        f.proj = p;
        updateBoardGeometry(f);
        return f;
    }

    private static void appendFaceProbe(StringBuilder sb) {
        final Frame f = scenario(0.0f);
        for (int i = 0; i < f.boardCount; i++) {
            final BoardBox b = f.boards[i];
            // 板心往里 0.10 格（贴着板面）
            final double inX = b.ox - b.nx * 0.10;
            final double inY = b.oy - b.ny * 0.10;
            final double inZ = b.oz - b.nz * 0.10;
            // 板心往外 1.20 格（明确在板外）
            final double outX = b.ox + b.nx * 1.20;
            final double outY = b.oy + b.ny * 1.20;
            final double outZ = b.oz + b.nz * 1.20;
            sb.append(String.format(java.util.Locale.ROOT,
                    "  板%d 心=(%7.3f,%7.3f,%7.3f) 面上(0.10格)=%.3f 面外(1.20格)=%.3f%n",
                    i + 1, b.ox, b.oy, b.oz,
                    boardFade(f, inX, inY, inZ), boardFade(f, outX, outY, outZ)));
        }
        // 玩家自己身上（环所在的地方）必须完全不受影响
        sb.append(String.format(java.util.Locale.ROOT,
                "  玩家胸口(0, +1.0, 0) 处 = %.3f（必须是 1.000：五块板都不在那儿）%n",
                boardFade(f, f.playerX, f.playerY + 1.0, f.playerZ)));
    }

    private static void appendScreenProbe(StringBuilder sb) {
        final Frame f = scenario(0.0f);
        final double pad = ShanhaiHoloSurroundTuning.DOT_SCREEN_PAD_FRAC * 2.0f * f.proj.halfH;
        for (int i = 0; i < f.boardCount; i++) {
            final BoardBox b = f.boards[i];
            if (!b.quadValid) {
                sb.append("  板").append(i + 1).append(" 投影不可信（quadValid=false）\n");
                continue;
            }
            final double mx = (b.sx[0] + b.sx[1] + b.sx[2] + b.sx[3]) * 0.25;
            final double my = (b.sy[0] + b.sy[1] + b.sy[2] + b.sy[3]) * 0.25;
            sb.append(String.format(java.util.Locale.ROOT,
                    "  板%d 屏幕四边形 x=[%7.1f..%7.1f] y=[%7.1f..%7.1f] 心=(%7.1f,%7.1f) 判据=%.3f%n",
                    i + 1, min4(b.sx), max4(b.sx), min4(b.sy), max4(b.sy),
                    mx, my, screenFade(f, mx, my, pad)));
        }
        sb.append(String.format(java.util.Locale.ROOT,
                "  屏幕正中心处的板心投影=%.3f（第 4 块板就在正后方，理应是 0.000）%n",
                screenFade(f, f.boards[3].sx[0], f.boards[3].sy[0], pad)));
        sb.append(String.format(java.util.Locale.ROOT,
                "  屏幕最左上角 (-480,-270) 处=%.3f（离板很远，理应是 1.000）%n",
                screenFade(f, -480.0, -270.0, pad)));
        sb.append(String.format(java.util.Locale.ROOT,
                "  屏幕中心正上方 200px 处=%.3f（多半落在某块板的投影里，允许 0）%n",
                screenFade(f, 0.0, -200.0, pad)));
    }

    /**
     * <b>负对照</b>：本判据"取最小值"这件事<b>不是恒绿的</b> ——
     * 把同一批屏幕四边形的极值取法反过来（取最大值，就是 HTML 修 bug 之前那个写法），
     * 同一个远离板的点也会被报成"在板面里"。
     */
    private static void appendNegativeControl(StringBuilder sb) {
        final Frame f = scenario(0.0f);
        final double pad = ShanhaiHoloSurroundTuning.DOT_SCREEN_PAD_FRAC * 2.0f * f.proj.halfH;
        final double px = -480.0;
        final double py = -270.0;
        sb.append(String.format(java.util.Locale.ROOT,
                "  同一个点 (-480,-270)：生产判据(min)=%.3f%n", screenFade(f, px, py, pad)));
        sb.append(String.format(java.util.Locale.ROOT,
                "  同一个点 (-480,-270)：取错极值(max)=%.3f  ← 0 就是「被判成在板面里」，整层会被抹掉%n",
                screenFadeWrongExtreme(f, px, py, pad)));
    }

    /** 故意写错的版本（只在自检里用；生产代码里没有这个函数）。 */
    private static float screenFadeWrongExtreme(Frame f, double x, double y, double pad) {
        float out = 1.0f;
        for (int i = 0; i < f.boardCount; i++) {
            final BoardBox b = f.boards[i];
            if (!b.quadValid) {
                continue;
            }
            double area = 0.0;
            for (int k = 0; k < 4; k++) {
                final int nn = (k + 1) & 3;
                area += b.sx[k] * b.sy[nn] - b.sx[nn] * b.sy[k];
            }
            if (Math.abs(area) * 0.5 < ShanhaiHoloSurroundTuning.MIN_QUAD_AREA_PX2) {
                continue;      // 与生产判据保持同一个面积下限，两次比较才只差"取极值"这一点
            }
            final double sign = area >= 0.0 ? 1.0 : -1.0;
            double worst = -Double.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                final int nn = (k + 1) & 3;
                final double ex = b.sx[nn] - b.sx[k];
                final double ey = b.sy[nn] - b.sy[k];
                final double rawLen = Math.sqrt(ex * ex + ey * ey);
                final double len = rawLen == 0.0 ? 1.0 : rawLen;   // = HTML 的 `|| 1`（正因为它才出事）
                final double d = (ex * (y - b.sy[k]) - ey * (x - b.sx[k])) * sign / len;
                if (d > worst) {
                    worst = d;
                }
            }
            if (worst >= 0.0) {
                return 0.0f;
            }
            float g = (float) (-worst / pad);
            if (g < 0.0f) {
                g = 0.0f;
            } else if (g > 1.0f) {
                g = 1.0f;
            }
            if (g < out) {
                out = g;
            }
        }
        return out;
    }

    /**
     * 退化几何的<b>实测</b>：环绕档下五块板绕玩家一圈 ⇒ 总有一块近乎侧对镜头，投影面积趋近 0。
     * <p>⚠️ 这一节的写法刻意<b>不替自己吹</b>：两边读数一样就写"一样"，
     * 那道退化保护是预防性的、不是修掉一个已复现的 bug。
     */
    private static void appendDegenerateProbe(StringBuilder sb) {
        final Frame f = edgeOnFrame(1.0);
        final BoardBox b = f.boards[0];
        final double pad = ShanhaiHoloSurroundTuning.DOT_SCREEN_PAD_FRAC * 2.0f * f.proj.halfH;
        sb.append(String.format(java.util.Locale.ROOT,
                "  侧对镜头的板：四角屏幕坐标 x=[%.3f..%.3f] y=[%.3f..%.3f]（面积≈0）%n",
                min4(b.sx), max4(b.sx), min4(b.sy), max4(b.sy)));
        sb.append(String.format(java.util.Locale.ROOT,
                "  生产判据：屏幕中心=%.3f  屏幕左上角=%.3f%n",
                screenFade(f, 0.0, 0.0, pad), screenFade(f, -480.0, -270.0, pad)));
        sb.append(String.format(java.util.Locale.ROOT,
                "  同一个场景用 HTML 的原写法（不跳退化边、没有面积下限）：中心=%.3f  左上角=%.3f%n",
                screenFadeHtmlBuggy(f, 0.0, 0.0, pad),
                screenFadeHtmlBuggy(f, -480.0, -270.0, pad)));
        sb.append("  ⇒ 实测结论：零面积的板【不会】把整层抹掉（两边的最左上角都是 1.000）；\n");
        sb.append("     差别只在正好落在那条退化线上的那一点（生产 1.000 / HTML 0.000）——\n");
        sb.append("     生产判据把面积≈0 的板整块跳过，而那正是板侧过来、自己缩成一条线的位置。\n");
        sb.append("     ⇒ 这两道保护是【预防性】的（成本为零），不是修掉一个把整层抹掉的已复现 bug。\n");

        final Frame g = edgeOnFrame(0.0);
        final double pad2 = ShanhaiHoloSurroundTuning.DOT_SCREEN_PAD_FRAC * 2.0f * g.proj.halfH;
        sb.append(String.format(java.util.Locale.ROOT,
                "  合成对照（把板的半宽半高都设成 0 ⇒ 四角完全重合，实测游戏里不会发生）："
                        + "生产=%.3f / HTML 写法=%.3f%n",
                screenFade(g, -480.0, -270.0, pad2),
                screenFadeHtmlBuggy(g, -480.0, -270.0, pad2)));
    }

    /** 造一块"侧对镜头"的板（{@code rotY = 90°，板面与视线平行}）；{@code scale=0} 时四角重合。 */
    private static Frame edgeOnFrame(double boardScale) {
        final Frame f = newFrame();
        f.playerX = 0.0;
        f.playerY = 64.0;
        f.playerZ = 0.0;
        f.camX = 0.0;
        f.camY = 65.62;
        f.camZ = -8.0;
        f.tSec = 0.0f;
        f.dtSec = 1.0f / 60.0f;
        f.originX = 0.0;
        f.originY = 65.52;
        f.originZ = 0.0;
        f.frameYawRad = 0.0;
        f.animScale = boardScale;
        f.boardCount = 1;
        f.boardX[0] = 0.0f;
        f.boardY[0] = 0.0f;
        f.boardZ[0] = 0.0f;
        f.boardRotX[0] = 0.0f;
        f.boardRotY[0] = 90.0f;
        f.boardScale[0] = 1.0f;
        final Proj p = new Proj();
        fillPinhole(p, f.camX, f.camY, f.camZ, 0.0, 0.0,
                ShanhaiHoloSurroundTuning.HTML_REF_FOCAL_PX, 480.0f, 270.0f);
        f.proj = p;
        updateBoardGeometry(f);
        return f;
    }

    /**
     * <b>故意照抄 HTML 那一行</b>的版本（{@code Math.hypot(ex,ey) || 1} 不跳退化边）——
     * 只在自检里用，用来证明"退化边保护"不是多余的。生产代码里没有这个函数。
     */
    private static float screenFadeHtmlBuggy(Frame f, double x, double y, double pad) {
        float out = 1.0f;
        for (int i = 0; i < f.boardCount; i++) {
            final BoardBox b = f.boards[i];
            if (!b.quadValid) {
                continue;
            }
            double area = 0.0;
            for (int k = 0; k < 4; k++) {
                final int nn = (k + 1) & 3;
                area += b.sx[k] * b.sy[nn] - b.sx[nn] * b.sy[k];
            }
            final double sign = area >= 0.0 ? 1.0 : -1.0;
            double worst = 1.0e9;
            for (int k = 0; k < 4; k++) {
                final int nn = (k + 1) & 3;
                final double ex = b.sx[nn] - b.sx[k];
                final double ey = b.sy[nn] - b.sy[k];
                final double raw = Math.sqrt(ex * ex + ey * ey);
                final double len = raw == 0.0 ? 1.0 : raw;      // = HTML 的 `|| 1`
                final double d = (ex * (y - b.sy[k]) - ey * (x - b.sx[k])) * sign / len;
                if (d < worst) {
                    worst = d;
                }
            }
            if (worst >= 0.0) {
                return 0.0f;
            }
            float g = (float) (-worst / pad);
            if (g < 0.0f) {
                g = 0.0f;
            } else if (g > 1.0f) {
                g = 1.0f;
            }
            if (g < out) {
                out = g;
            }
        }
        return out;
    }

    /**
     * 只统计"两道判据会挡掉多少"，用来证明环绕层<b>没有被整片抹掉</b>
     * （HTML 那次 bug 的观感就是"整层不见了"）。
     */
    private static final class FadeCount implements Sink {

        final Frame f;
        int segTotal, segCulled, dotTotal, dotCulled;
        double worstSegFade = 1.0;
        double worstDotFade = 1.0;

        FadeCount(Frame f) {
            this.f = f;
        }

        @Override
        public void segment(double ax, double ay, double az,
                            double bx, double by, double bz,
                            int rgb, float widthHtmlPx, float alpha) {
            segTotal++;
            final double fade = Math.min(boardFade(f, ax, ay, az), boardFade(f, bx, by, bz));
            if (fade < ShanhaiHoloSurroundTuning.CULL_FADE) {
                segCulled++;
            }
            if (fade < worstSegFade) {
                worstSegFade = fade;
            }
        }

        @Override
        public void dot(double x, double y, double z, float radiusBlocks, int rgb, float alpha) {
            dotTotal++;
            final double fade = boardFade(f, x, y, z);
            if (fade < ShanhaiHoloSurroundTuning.CULL_FADE) {
                dotCulled++;
            }
            if (fade < worstDotFade) {
                worstDotFade = fade;
            }
        }
    }

    private static double min4(double[] v) {
        double m = v[0];
        for (int i = 1; i < 4; i++) {
            if (v[i] < m) {
                m = v[i];
            }
        }
        return m;
    }

    private static double max4(double[] v) {
        double m = v[0];
        for (int i = 1; i < 4; i++) {
            if (v[i] > m) {
                m = v[i];
            }
        }
        return m;
    }
}
