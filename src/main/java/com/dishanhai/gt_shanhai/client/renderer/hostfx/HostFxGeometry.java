package com.dishanhai.gt_shanhai.client.renderer.hostfx;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuHostGeometry;
import com.dishanhai.gt_shanhai.client.renderer.blackhole.BlackHoleSurfaceWaves;

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
 *   <li>唯一保留的自绘特效：<b>18 条光束</b>（4 主喷口 + 12 细紫 + 上下 2 主轴喷流）＋ 120 颗沿轴流动的粒子的几何。</li>
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

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 三组独立自转：内环带 / 上 4 艘 / 下 4 艘 —— 三个常量，谁也不用谁的数
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户 2026-10-25 口径（要求 ①）：「外圈选择的 8 个小飞船可以角速度和内环不一样」
     * ⇒ 三组必须是【三个独立的烘焙 + 三个独立的 spin】：
     *     内环带   →  ringDeg(t)      = +RING_DEG_PER_SEC  × t
     *     上 4 艘  →  shipOrbitDeg(t, true)  = +SHIP_UP_DEG_PER_SEC   × t
     *     下 4 艘  →  shipOrbitDeg(t, false) = −SHIP_DOWN_DEG_PER_SEC × t
     * 三份缓冲互不共享（{@code HostFxRingGroups.ring/shipUp/shipDown}，各自一份
     * {@code HostFxBaked} ⇒ 各自一套 {@code VertexBuffer}），角度也各算各的。
     *
     * ⚠️ 上一版把"上/下"写成"一个常量 + 两个符号"，那样<b>只能同幅反向</b>，
     * 不能"各自独立配置"。现在幅值也是两个常量 ⇒ 想让上组 7°/s、下组 −11°/s 只改这两行。
     */

    /*
     * ═══════════════════════════════════════════════════════════════════════════════════
     *  🔴 2026-10-25 第十九改：三个角速度从"编译期常量"改成"可覆盖的生效值"
     * ═══════════════════════════════════════════════════════════════════════════════════
     *
     * 用户原话：「这个角速度干脆让玩家可以在主机控制器里面调得了，可以修改那个没用的调整中子星
     * 的大小与颜色的按钮」⇒ 角速度必须是【机器状态】，能被面板改、能被存档保存、能被客户端读到。
     *
     * 🔴 为什么不能只把 `final` 删掉、保留原来那三行：`public static final float X = 12.0F;`
     * 是【编译期常量】（JLS 4.12.4 / 13.1），javac 会把 `ringDeg()` 里的 `X` 直接内联成字面量
     * `12.0F` ⇒ 运行期改字段值对已经编译好的调用点【毫无影响】。这就是"面板改了、画面不动"的经典形态。
     * 本文件里能改色的 `jetGlowRgb()`（{@link #JET_GLOW_RGB} + `jetGlowOverride` + getter，见
     * :469~:492）就是同一套写法的既有先例，这里照抄。
     *
     * 形状（与先例的差别只有一处，是刻意的）：
     * <pre>
     *   public static final float DEFAULT_xxx_DEG_PER_SEC = 默认值;   // 常量，永不改
     *   public static       float xxx_DEG_PER_SEC         = DEFAULT;  // 🔴 非 final ⇒ 调用点不内联
     *   public static float xxxDegPerSec()  { return xxx_DEG_PER_SEC; }        // getter（渲染侧只准读这个）
     *   public static void  setXxxDegPerSec(float v) { … 钳位 … }              // setter（喂料链/面板用）
     * </pre>
     * ⚠️ 生效值<b>直接挂在原来那个公开字段名上</b>（而不是像 jetGlow 那样藏一个 private override）：
     * 本工程的渲染侧还有三处<b>直接读字段</b>的既有代码（{@code HostFxBeamRingGeom:324} 的
     * 光柱环自转、{@code HostFxRingGroups:300} 的烘焙日志、{@code HostFxRingGroups:509-511} 的
     * draw 摘要）。字段保持公开且非 final ⇒ 那三处<b>一个字都不用改</b>就自动跟着玩家调的值走
     * （否则光柱环会永远 12°/s 而内环已经变了，两条转速当场脱钩）。
     * 新代码请一律走 getter/setter，别再直接读写字段。
     */

    /** 内环带转多快（度/秒）的<b>默认值</b>。三组里的第 1 组。 */
    public static final float DEFAULT_RING_DEG_PER_SEC = 12.0F;

    /**
     * <b>上 4 艘</b>飞船绕环多快（度/秒）的<b>默认值</b>：比内环的 12°/s 慢 25%
     * ⇒ 10 秒后两者差 30°，肉眼一眼能看出"船在环上慢慢往后错"。
     */
    public static final float DEFAULT_SHIP_UP_DEG_PER_SEC = 9.0F;

    /**
     * <b>下 4 艘</b>飞船绕环多快（度/秒）的<b>默认值</b>（幅值；方向由 {@link #SHIP_DOWN_SIGN} 取反
     * ⇒ 默认实际 −9°/s）：与上组同幅、<b>反向</b>（上下对转是原设计，不是 bug）。
     */
    public static final float DEFAULT_SHIP_DOWN_DEG_PER_SEC = 9.0F;

    /** 内环带<b>当前生效</b>角速度（度/秒）。非 final ⇒ 不在调用点内联；改它用 {@link #setRingDegPerSec}。 */
    public static float RING_DEG_PER_SEC = DEFAULT_RING_DEG_PER_SEC;

    /** 上 4 艘<b>当前生效</b>角速度幅值（度/秒，正数）。非 final；改它用 {@link #setShipUpDegPerSec}。 */
    public static float SHIP_UP_DEG_PER_SEC = DEFAULT_SHIP_UP_DEG_PER_SEC;

    /** 下 4 艘<b>当前生效</b>角速度幅值（度/秒，正数；方向仍由 {@link #SHIP_DOWN_SIGN} 取反）。 */
    public static float SHIP_DOWN_DEG_PER_SEC = DEFAULT_SHIP_DOWN_DEG_PER_SEC;

    /** 上组飞船的转向符号（+1 = 与内环同向）。 */
    public static final int SHIP_UP_SIGN = 1;
    /** 下组飞船的转向符号（−1 = 反向）—— 原设计就是上下对转。 */
    public static final int SHIP_DOWN_SIGN = -1;

    /**
     * 内环带<b>当前生效</b>的角速度（度/秒）。渲染侧<b>只准</b>读这一个（不要直接读字段）。
     *
     * <p>值域 1..60 °/s，钳位在 {@link HostFxState#clampRingDegPerSec(float)}。
     */
    public static float ringDegPerSec() {
        return RING_DEG_PER_SEC;
    }

    /**
     * 写内环带角速度（度/秒）。非法值（NaN/∞）回落默认、越界夹到 1..60，
     * 与机器侧 setter / {@code HostFxState} 共用同一份钳位（绝不各写一套）。
     */
    public static void setRingDegPerSec(float degPerSec) {
        RING_DEG_PER_SEC = HostFxState.clampRingDegPerSec(degPerSec);
    }

    /** 上 4 艘<b>当前生效</b>的角速度幅值（度/秒，正数；0..60）。 */
    public static float shipUpDegPerSec() {
        return SHIP_UP_DEG_PER_SEC;
    }

    /** 写上 4 艘角速度幅值（度/秒，0..60）。 */
    public static void setShipUpDegPerSec(float degPerSec) {
        SHIP_UP_DEG_PER_SEC = HostFxState.clampShipDegPerSec("shipUpDegPerSec", degPerSec);
    }

    /** 下 4 艘<b>当前生效</b>的角速度幅值（度/秒，<b>正数</b>；方向由 {@link #SHIP_DOWN_SIGN} 负责）。 */
    public static float shipDownDegPerSec() {
        return SHIP_DOWN_DEG_PER_SEC;
    }

    /** 写下 4 艘角速度幅值（度/秒，正数 0..60 —— 传负数会被夹到 0，方位永远是"反向"）。 */
    public static void setShipDownDegPerSec(float degPerSec) {
        SHIP_DOWN_DEG_PER_SEC = HostFxState.clampShipDegPerSec("shipDownDegPerSec", degPerSec);
    }

    /**
     * 一次写三组（喂料链每帧一次的入口 —— 三个值同源同帧，绝不出现"环已按新值转、船还是旧值"）。
     */
    public static void setGroupDegPerSec(float ring, float shipUp, float shipDown) {
        setRingDegPerSec(ring);
        setShipUpDegPerSec(shipUp);
        setShipDownDegPerSec(shipDown);
    }

    /** 三组一起恢复默认（12 / +9 / −9）。 */
    public static void resetGroupDegPerSec() {
        setGroupDegPerSec(DEFAULT_RING_DEG_PER_SEC, DEFAULT_SHIP_UP_DEG_PER_SEC,
                DEFAULT_SHIP_DOWN_DEG_PER_SEC);
    }

    /**
     * @deprecated 两个幅值已经各自独立（{@link #shipUpDegPerSec()} / {@link #shipDownDegPerSec()}），
     *             这个"共用幅值"只剩"上组<b>默认</b>值"的别名意义（而且它现在是<b>启动那一刻</b>的快照，
     *             不会跟着面板变），<b>新代码不要再用它</b>。
     */
    @Deprecated
    public static final float SHIP_DEG_PER_SEC = DEFAULT_SHIP_UP_DEG_PER_SEC;

    /**
     * 三组角速度<b>默认值</b>的自检（要求 ① 的判据：三者互不相等、上下反向）。
     *
     * <p>🔴 2026-10-25 第十九改起，判的是<b>默认值</b>而不是"当前生效值"：
     * 角速度已经进了玩家面板，玩家完全可以把三组调成一样（那是他的自由，不是 bug）。
     * 若这条判据继续读生效值，玩家一动面板就会在日志里看到
     * {@code group_angles_mismatch} 的 WARN —— 那是<b>假告警</b>，会让下一次真实故障被当噪音忽略。
     * ⇒ 本自检现在的语义是"<b>出厂默认值仍然满足三者不等 + 上下对转</b>"，与玩家怎么调无关。
     * （当前生效值另有读处：{@code HostFxRingGroups.summary()} 每 5 秒把三个数打进 draw 日志。）
     *
     * @return {@code null} = 通过；否则是一行人话（哪一条不成立、读数多少）
     */
    public static String selfCheckGroupAngles() {
        final String[] names = {groupName(0), groupName(1), groupName(2)};
        // 用法：默认值 × 各自的符号（= groupAngleDeg(1s, g) 在"默认值"口径下的读数）
        final float[] one = {DEFAULT_RING_DEG_PER_SEC,
                DEFAULT_SHIP_UP_DEG_PER_SEC * SHIP_UP_SIGN,
                DEFAULT_SHIP_DOWN_DEG_PER_SEC * SHIP_DOWN_SIGN};
        for (int i = 0; i < 3; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (Math.abs(one[i] - one[j]) < 1.0e-4F) {
                    return names[i] + " 与 " + names[j] + " 的默认角速度相等（都是 " + one[i]
                            + "°/s）—— 用户要求三者互不相等";
                }
            }
        }
        if (one[1] * one[2] >= 0.0F) {
            return "上/下两组的默认值没有对转（上 " + one[1] + "°/s、下 " + one[2] + "°/s）";
        }
        return null;
    }

    /**
     * 第 {@code group} 组在 {@code seconds} 秒时的自转角（度）。
     *
     * @param group 0 = 内环带，1 = 上 4 艘，2 = 下 4 艘（与 {@link #groupOf} 的编号一致）
     */
    public static float groupAngleDeg(float seconds, int group) {
        return switch (group) {
            case 1 -> shipOrbitDeg(seconds, true);
            case 2 -> shipOrbitDeg(seconds, false);
            default -> ringDeg(seconds);
        };
    }


    // ---------------- 光束（唯一保留的自绘特效）----------------

    /**
     * 每条光束沿轴向切几段 —— 参数来自同族 mod 秘法涡流 {@code LightSpike} 的实测值：<b>32 段</b>
     * （不是伪神光束的 10 段；32 段才能让"五次贝塞尔收细"看起来平滑）。
     */
    public static final int BEAM_SEGMENTS = 32;
    /** 横截面几等分 —— 同源实测值：<b>12 段圆</b>（32 × 12 = 每条 384 quads）。 */
    public static final int BEAM_FACETS = 12;
    /** @deprecated 主光柱的长度<b>不是常数</b>：由"解压机喷口 r=49.25 − 黑洞表面 r"算出（见 {@link #mdBeamEndpoints}）。 */
    @Deprecated
    public static final float BEAM_LENGTH = 12.0F;
    /**
     * 峰值直径（格）：<b>1.24 ⇒ 峰值半径 0.62</b>。
     *
     * <p>🔴 2026-10-25 第五改（用户原话：「<b>中间的主光柱太细了，粗一点</b>」）：
     * <b>改前 0.50（峰值半径 0.25）→ 改后 1.24（峰值半径 0.62）</b>，
     * 落在父口径给的 0.55~0.7 区间内。管壳是空心圆柱（前后两片在同一像素各叠一次），
     * 所以观感直径 ≈ 1.24 × 2 = 2.5 格 —— 与黑洞表面半径 5.196 格的比例是 0.24。
     */
    public static final float BEAM_PEAK_DIAMETER = 1.24F;
    /** 峰值半径（格）= 1.24 / 2 = <b>0.62</b>（改前 0.25）。 */
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

    /**
     * @deprecated 2026-10-25 第三改之后主轴喷流用 {@link #JET_LENGTH}（18 格），这个 12 格的老值
     *             只剩"旧探针还能编译"的意义。<b>别再拿它当喷流长度</b>。
     */
    @Deprecated
    public static final float POLAR_LENGTH = 12.0F;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  要求 ③：每座解压机多出 3 条更细的紫光束，射向黑洞中心
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 🔴 2026-10-25 第七改（本轮，用户逐字两条）：
     *   ① 「我说的小光束是围绕这内环上面 4 个物质解压机的啊，每个物质解压机围绕 3 个
     *      （然后配套的类似发射器的方块细节也需要做），不是围绕上下两条光束的」
     *   ② 「还有 4 个物质解压机的主光束也换成伪神同款的啊」
     *   ③ 上一轮已确认：「小光束不需要换贴图，那只是引导用的，真正采集物质的是主光束」
     *
     * ⇒ 每座解压机 = 1 条<b>主喷口光柱</b>（伪神同款 shader，{@link #MD_BEAM_OUTER_R0}→{@link #MD_BEAM_OUTER_R1}）
     *              + 3 条<b>细紫引导光束</b>（峰值半径 {@link #THIN_BEAM_PEAK_RADIUS} = 0.10）
     * ⇒ 全场 4 座 × (1 主 + 3 细) = 16 条径向光束 + 中央上下 2 根主光柱（共 18 条，条数不变）。
     *
     * <p><b>改前 → 改后（本轮唯一改的就是"这 12 条绕谁转"）</b>：
     * <pre>
     *   绕谁      中央主光柱轴线（r = 7.40 的一个大圆，每 30° 一条）
     *        ⇒    每座解压机自己的喷口轴线（4 个小圆，每座 r = 1.90、3 条互成 120°）
     *   方向      (0,1,0)，与中央上下两根主光柱平行
     *        ⇒    与该座解压机主喷口光柱同向（夹角 0.000000°），即"都指向黑洞"
     *   起点      悬空的 (r, ±半长, z) 两点
     *        ⇒    该座解压机喷口面上那 3 个**发射器口**的中心（同一次坐标变换算出来，见
     *              {@link #mdEmitterPortCenter(int, int)}）
     * </pre>
     *
     * <h3>为什么是紫色，值怎么来的（不是拍脑袋）</h3>
     * 依据 = 群星官方那张行星视图原图 {@code temp/stellaris-decompressor/png/art_planetview_matter_decompressor.png}
     * （540×176，本机实测逐像素统计色相分布，亮度 < 90/255 的像素丢掉）：
     * <pre>
     *   色相 240°  n=3316  avg=( 95, 87,170)   ← 细光束主体
     *   色相 260°  n=1447  avg=(162,134,190)
     *   色相 280°  n=1571  avg=(159,116,169)
     *   色相 220°  n=4435  avg=( 80, 93,183)   ← 偏蓝的光晕
     *   色相   0°  n=6576  avg=(255,255,255)   ← 主光柱的高亮白芯
     * </pre>
     * ⇒ 原图里"细的那几条"集中在 <b>240°~280°（蓝紫—紫）</b>，主光柱是纯白高亮。
     * 取 <b>262°、高饱和</b> 作为细光束色 ⇒ {@code (168,116,255)}，即
     * {@link #THIN_BEAM_COLOR} = (0.6588, 0.4549, 1.0000)。选它而不用 240° 的深蓝紫，
     * 是因为我们要在<b>加性混合</b>下叠到深色星空上，偏亮的紫才不会糊成一条暗线。
     */

    /** 4 座（= 4 条辐条端部）。声明在这里是为了让下面的细光束条数能安全引用（同类的常量前向引用非法）。 */
    public static final int MD_COUNT = 4;

    /**
     * <b>每座</b>解压机配几条细光束（用户口径：<b>3 条</b>）。
     *
     * <p>🔴 2026-10-25 第七改：这个数从"历史遗留"变回<b>真语义</b> —— 上一版它被标成
     * {@code @Deprecated}（当时细光束是绕中央主光柱一圈的 12 条平行线），现在用户点名
     * 「每个物质解压机围绕 3 个」⇒ 它重新是"每座几条"的唯一来源。
     */
    public static final int THIN_BEAMS_PER_MD = 3;

    /** 细光束<b>总条数</b> = {@link #MD_COUNT} × {@link #THIN_BEAMS_PER_MD} = <b>12</b>（与改前同为 12）。 */
    public static final int THIN_BEAM_COUNT = MD_COUNT * THIN_BEAMS_PER_MD;

    /**
     * 3 条细光束在<b>本座解压机轴线的圆周</b>上的半径（格）。
     *
     * <p>为什么是 1.90（不是拍脑袋，两步都用改后的数算）：
     * <ol>
     *   <li><b>必须落在主喷口光柱的可见宽度之外</b>：伪神那条 fsh 的 alpha 随视角衰减
     *       （{@code angleAlpha = max((cosθ − 0.5)·2, 0)^Intensity}）⇒ 一圈里只有朝向相机的 ±30° 可见，
     *       <b>屏幕可见半宽 ≈ 0.55 × 半径</b>。主柱外层半径取 {@link #MD_BEAM_OUTER_R0} = 1.25
     *       / {@link #MD_BEAM_OUTER_R1} = 1.55 ⇒ 可见半宽 0.69~0.85 格，细光束放在 1.90 不会被辉光吞掉
     *       （仍留 1.05 格净空）。</li>
     *   <li><b>必须落在解压机前部的实体之外、又贴得住</b>：喉环孔径 {@link #MD_MOUTH_R} = 1.15、
     *       唇环 1.62（塔本地 z=5.05）、法兰 2.45（y=3.00）。1.90 正好在唇环外、法兰内 ⇒
     *       喷口面上那圈"发射器面盘"有位置可装。</li>
     * </ol>
     * <p>改前这个数是 7.40（绕中央主光柱），改后是 1.90（绕每座解压机）。
     */
    public static final float THIN_BEAM_RING_R = 1.90F;

    /**
     * 3 条细光束在塔本地圆周上的方位角（度）：<b>90 / 210 / 330</b> ⇒ 两两相差 <b>120°</b>。
     *
     * <p>塔本地 +Z = 环心本地的"上"（见 {@link #mdLocalToRing}），所以 90° 那条正对上方
     * ⇒ 从洞口往里看是"上 / 左下 / 右下"三点，120° 均布一眼可数。
     */
    public static final float[] THIN_BEAM_PORT_PHASE_DEG = {90.0F, 210.0F, 330.0F};

    /**
     * 发射器口面（细光束真正射出的那个面）在<b>塔本地轴向</b>的位置（格）。
     *
     * <p>标尺：唇环 y=5.05、喉环 y=5.26、亮核（主光柱起点）y={@link #MD_NOZZLE_LOCAL_Y} = 5.45。
     * 取 <b>5.30</b>：正好在唇环与亮核之间、比主柱起点退 0.15 格 ⇒ 细紫线看起来是从
     * 发射器管口里出来的，而不是从主柱里分叉出来的。
     */
    public static final float THIN_BEAM_PORT_LOCAL_Y = 5.30F;
    /** 细光束的颜色（紫，RGB 168/116/255；依据见上面那段取证）。 */
    public static final float[] THIN_BEAM_COLOR = {168.0F / 255.0F, 116.0F / 255.0F, 255.0F / 255.0F};
    /** 细光束峰值半径（格）：0.10 —— 主光柱 0.25 的 2/5，一眼能看出"更细"。 */
    public static final float THIN_BEAM_PEAK_RADIUS = 0.10F;
    /** 细光束沿轴切几段（比主柱少：它短、且细，16 段已经平滑）。 */
    public static final int THIN_BEAM_SEGMENTS = 18;
    /** 细光束横截面几等分（4 片 = 一个菱形薄管，加性下就是一条有厚度的亮线）。 */
    public static final int THIN_BEAM_FACETS = 4;

    /**
     * 细紫光束的<b>外发光壳</b>：半径倍数 / 段数 / 片数 / 亮度。
     *
     * <p>为什么必须有这一层（自看第一版出图时抓到的真问题）：0.10 格半径的管子
     * 在 47 格长的距离上只有 1~2 像素宽，加性叠在星空上<b>几乎看不见</b>。
     * 做法与伪神一样：外壳画一次（更宽更淡）、内芯画一次（更细更亮）——
     * 伪神是 soft(Intensity=2.0) / intense(Intensity=4.0) 两遍，我们是"宽度两档 + 亮度两档"。
     */
    public static final float THIN_BEAM_GLOW_SCALE = 2.30F;
    public static final int THIN_BEAM_GLOW_SEGMENTS = 12;
    public static final int THIN_BEAM_GLOW_FACETS = 6;
    public static final float THIN_BEAM_GLOW_ALPHA = 0.22F;
    /** 内芯（主那一根细管）的亮度：0.85 ≈ 亮而不糊成一团白。 */
    public static final float THIN_BEAM_CORE_ALPHA = 0.85F;

    /**
     * <b>回落用</b>：4 条主喷口光柱的旧自绘管壳亮度。
     *
     * <p>🔴 2026-10-25 第七改之后它<b>不在正常路径上</b>：4 条主喷口光柱已经换成
     * {@link HostFxPillarBeam}（与中央上下两根同一套伪神 shader/贴图），
     * 只有 shader 建不起来时才回落到 {@link #buildMdBeamQuads}，那时用这个 alpha。
     */
    public static final float BEAM_SHELL_ALPHA = 0.40F;
    /** 主轴喷流：外层发光壳 / 内芯 的亮度（同样为粒子留出最亮一档）。 */
    public static final float JET_GLOW_ALPHA = 0.44F;
    public static final float JET_CORE_ALPHA = 0.62F;
    /**
     * @deprecated 细光束改成"与该座解压机主柱同向"之后不再收在"离洞心 0.3×洞面半径"处 ——
     *             它们的终点与该座主柱的终点<b>共面</b>（见 {@link #thinBeamSegment}），
     *             那个"插进洞里"的口径只对主柱有意义。留着只为旧探针还能编译。
     */
    @Deprecated
    public static final float THIN_BEAM_END_FRACTION = 0.30F;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  要求 ④：主光柱 = 伪神之锻炉那套观感（粒子沿轴向流动 + 外层发光颜色可配）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 取证（gtladditions 3.2.8Custom-fix2.jar，2026-10-25 用 vineflower 反编译 + 直接读 shader 原文）：
     *   类   com/gtladd/gtladditions/client/render/machine/antichrist/AntichristBeamRenderer
     *   着色 assets/gtladditions/shaders/core/gtladditions_antichrist_beam.{vsh,fsh}
     *
     * ① **粒子流动**不是后期，而是<b>贴图 UV 沿轴向随 tick 滚动</b>（AntichristBeamRenderer.kt:364-372）：
     *      consume(Mode.TRIANGLES, BEAM_VERTEX_FORMAT)
     *      builder.vertex(cos(angle)*radius, sin(angle)*radius, offset)
     *             .uv(offset / 256f + tick / 240f,
     *                 angle / 2π + (offset / 256f + tick / 240f) / 3f + tick / 240f)
     *     ⇒ u 沿轴（offset）线性、并且整体随时间前进（tick/240）⇒ <b>一张噪声贴图就能流出粒子感</b>；
     *       v 再加 u/3 的螺旋项 ⇒ 斜纹。
     * ② 亮度：fsh 里 {@code alpha = angleAlpha^Intensity × transparency × (1 − luminance(tex))}
     *    ⇒ <b>贴图越暗越亮</b>（把噪声的暗斑当成"粒子"）。
     * ③ **外层发光颜色可调** = uniform {@code BeamColor}（fsh 第 6 行 {@code uniform vec3 BeamColor}），
     *    由 {@code AntichristRenderProfile.colorR/G/B} 喂进来；两遍绘制 Intensity 取 2.0(soft) / 4.0(intense)。
     *
     * 我们<b>不能直接沿用</b>：它依赖自注册的 shader（{@code AntichristShaders}，要 clientSetup 事件 +
     * 自定义顶点格式 + 自己的贴图），这些都在 gtladditions 里、且写在我这次的写入范围之外；
     * 我们的光束层只有 {@code POSITION_COLOR}（无贴图、无自定义 shader）。
     * ⇒ <b>自写等价观感</b>：把"UV 沿轴滚动"换成"<b>几何粒子沿轴流动</b>"（每颗粒子是一个朝向相机的加法四边形），
     *    把"uniform BeamColor"换成 {@link #JET_GLOW_RGB} / {@link #jetGlowRgb()}（静态常量 + 可选覆盖）。
     *    观感三要素逐条对上：<b>轴向流动</b>（粒子 t 随 seconds 前进）、<b>越暗越亮</b>（粒子之间留空）、
     *    <b>外层发光可配</b>（glow shell 用可配色，core 用固定白芯）。
     */

    /** 主光柱（极轴喷流）长度（格）：从黑洞表面往外 —— 比旧的 12 格长，才像"光柱"而不是短棍。 */
    public static final float JET_LENGTH = 18.0F;
    /** 主光柱长度（格）—— 与 {@link #JET_LENGTH} 同值，新名字（"主光柱"不再叫"喷流"）。 */
    public static final float PILLAR_LENGTH = JET_LENGTH;

    /*
     * ═══════════════════════════════════════════════════════════════════════════════════════
     *  🔴 2026-10-25 第五改：主光柱 = 伪神之锻炉那根（1:1 复刻），这里只剩【半径】几个数
     * ═══════════════════════════════════════════════════════════════════════════════════════
     *
     * 用户三条原话：
     *   ① 「中间的主光柱太细了，粗一点」
     *   ② 「主光柱我希望和伪神的光柱一模一样」  ← 上一版"自写等价"被否掉，这次是 1:1 搬代码
     *   ③ 「上下两根同一套」
     *
     * 实现搬到了 {@link HostFxPillarBeam}（shader + 贴图 + 顶点格式 + uniform + 状态，逐项照抄
     * gtladditions 的 AntichristBeamRenderer.renderLinear）。这里只留**半径与 alpha**，
     * 语义与伪神 {@code renderLinear(from,to,startRadius,endRadius,color,outerAlpha,
     * innerRadiusScale,innerAlpha)} 一一对应。
     *
     * 改前 → 改后（用户要的"前后半径数字"）：
     *   外层发光壳（靠洞端 → 外端）  2.05 → 0.95   ⇒  **6.20 → 3.80**
     *   内芯半径（靠洞端 → 外端）    0.62 → 0.30   ⇒  **2.79 → 1.71**（= 外层 × 0.45，伪神同款 innerRadiusScale）
     *   长度                          18 格          ⇒  18 格（不变）
     *   画法                          自绘管壳 + 几何粒子 ⇒ 伪神 shader + space_layer 贴图（UV 沿轴滚动）
     *
     * alpha 的取值不是拍脑袋：伪神之锻炉自己的光柱 {@code beamAlpha = 1.0}
     * （取证 {@code ClientAnimationState.getBeamAlpha}：正常档 case 1/2 → {@code 1.0F}），
     * 所以这里 {@code PILLAR_OUTER_ALPHA = PILLAR_INNER_ALPHA = 1.0}、{@code INNER_RADIUS_SCALE = 0.45}
     * （后者取自同族直线光束调用点 {@code LightHunterSpaceStationBeamRenderer}，锻炉本体是分段多点曲线）。
     */
    /** 主光柱外层发光壳半径：靠洞端（格）。改前 2.05。 */
    public static final float PILLAR_OUTER_R0 = 6.20F;
    /** 主光柱外层发光壳半径：外端（格）。改前 0.95。 */
    public static final float PILLAR_OUTER_R1 = 3.80F;
    /** 内芯半径倍数（伪神 {@code INNER_RADIUS_SCALE}）⇒ 内芯 2.79→1.71（改前 0.62→0.30）。 */
    public static final float PILLAR_INNER_SCALE = 0.45F;
    /**
     * 外层那遍的 alpha（写进 UV1：alpha×10000）。
     *
     * <p>取值 = <b>1.0</b>：伪神之锻炉自己的光柱透明度就是 {@code beamAlpha = 1.0}
     * （取证 {@code ClientAnimationState.getBeamAlpha}：正常档 case 1/2 → {@code 1.0F}），
     * 只有同族里另一个调用点（LightHunter 空间站线光束）才用 0.549。
     * 我们要的是"锻炉主光柱"，所以跟 1.0。
     */
    public static final float PILLAR_OUTER_ALPHA = 1.0F;
    /** 内芯那遍的 alpha（同上，锻炉也是 1.0）。 */
    public static final float PILLAR_INNER_ALPHA = 1.0F;

    /*
     * 下面两档是【回落用】的旧自绘管壳半径：只在 {@link HostFxPillarBeam} 的 shader 建不起来
     * （缺资源 / 编译失败 / 没有 GL）时才走 {@link #buildPillarQuads} —— 那时宁可画"旧观感"，
     * 也不能让主光柱整根消失。所以这两组数**保持改前的值不动**。
     */
    /** 回落用：外层发光壳半径（靠洞端 / 外端，格）—— 改前的自绘观感。 */
    public static final float JET_GLOW_R0 = 2.05F;
    public static final float JET_GLOW_R1 = 0.95F;
    /** 回落用：内芯半径（靠洞端 / 外端，格）。 */
    public static final float JET_CORE_R0 = 0.62F;
    public static final float JET_CORE_R1 = 0.30F;
    /** 喷流壳沿轴分段 / 外层横截面等分 / 内芯横截面等分。 */
    public static final int JET_SEGMENTS = 24;
    public static final int JET_FACETS = 12;
    public static final int JET_CORE_FACETS = 8;

    /**
     * <b>外层发光颜色</b>（可配置项的默认值）：冷蓝白 {@code (0.62,0.84,1.00)} = RGB(158,214,255)。
     *
     * <p>依据：归墟的既有配色就是"青白冷光 + 琥珀暖光"（{@link #BEAM_COLOR} 是 180,220,255，
     * 环附加件 0.55/0.62/0.72，解压机橙光 1.00/0.62/0.20），主光柱作为"最亮的那束"取同一族。
     * <p>🔴 2026-10-25 自看后从 (0.72,0.88,1.00) 调到这个更饱和的值：加性叠在深色星空上会被
     * 色调映成"灰白"，蓝通道拉高之后才读得出是<b>冷光</b>。
     * <p>改成别的颜色不用动几何：{@link #setJetGlowRgb(float, float, float)}。
     */
    public static final float[] JET_GLOW_RGB = {0.62F, 0.84F, 1.00F};
    /** 内芯颜色（热白，恒定；"外层可配"指的就是上面那一层）。 */
    public static final float[] JET_CORE_RGB = {1.00F, 0.97F, 0.90F};
    /** 外层发光的<b>当前</b>生效值（null = 用 {@link #JET_GLOW_RGB} 的默认）。 */
    private static float[] jetGlowOverride;

    /**
     * 设置外层发光颜色（配置钩子；后续接 {@code /shanhai} 命令或 config 时调这里）。
     *
     * @param r,g,b 0..1；传 {@code null} 语义见 {@link #clearJetGlowOverride()}
     */
    public static void setJetGlowRgb(float r, float g, float b) {
        jetGlowOverride = new float[]{r, g, b};
    }

    /** 恢复默认外层发光颜色（{@link #JET_GLOW_RGB}）。 */
    public static void clearJetGlowOverride() {
        jetGlowOverride = null;
    }

    /** 外层发光颜色<b>当前生效值</b>（返回副本，调用方随便改不会污染全局）。 */
    public static float[] jetGlowRgb() {
        return (jetGlowOverride != null ? jetGlowOverride : JET_GLOW_RGB).clone();
    }

    /**
     * 当前是否<b>有人设过</b>外层发光色的覆盖（{@code true} = {@code jetGlowRgb()} 读的是覆盖值，不是默认值）。
     *
     * <p>🔴 2026-10-25 第二十改：给 {@link HostFxSwitch} 的"只还原自己设过的"用 ——
     * 降级态要把中央两束换红，恢复时若别处（将来的 {@code /shanhai} 配色命令）先设过一个色，
     * 我们必须<b>写回那个色</b>而不是 {@code clearJetGlowOverride()}（后者会把别人的设置永久抹掉）。
     * 没有这个查询就只能"猜"，那就是本工程反复踩过的"覆盖了用户的值"那一层。
     */
    public static boolean hasJetGlowOverride() {
        return jetGlowOverride != null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 第七改：4 座解压机的【主喷口光柱】也换成伪神同款（与中央上下两根同一套）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话（本轮第 2 条）：「**还有 4 个物质解压机的主光束也换成伪神同款的啊**」。
     *
     * 复用方式（一处都不重写）：`HostFxPillarBeam#drawMdBeams(...)` → 与中央两根**同一个**
     * `drawBeam(...)` → 同一个 `ShaderInstance`（`shanhai_host_beam`，GLSL/贴图字节级副本）、
     * 同一套顶点格式（Position+UV0+UV1）、同一组 uniform（CameraPosition/BeamColor/Intensity）、
     * 同两遍 Intensity 2.0(soft，可配色) / 4.0(intense，白芯)。差别只在：
     *   ① 端点：`mdBeamEndpoints(k)`（喷口 → 洞面），不是竖轴；
     *   ② 半径：外层 1.25 → 1.55 格（中央那根是 6.20 → 3.80，因为它是"贯穿全场的轴"，
     *      而这 4 根是"从喷口射出的采集柱"，用户口径就是"比中央细"）；
     *   ③ 颜色：`mdBeamGlowRgb()`（可配，默认与中央同一族冷蓝白 ⇒ 6 根看起来是一套）。
     *
     * <p><b>为什么半径挑 1.25→1.55（不是拍脑袋）</b>：
     * <ul>
     *   <li>父口径给的区间是 1.2~1.6，这里取"喷口端 1.25、洞端 1.55"——<b>往洞里涨</b>，
     *       与中央主光柱同一个观感方向（6.20 在洞端、3.80 在外端，越靠洞越粗）；</li>
     *   <li>喷口端 1.25 略大于喉口孔径 {@link #MD_MOUTH_R} = 1.15 ⇒ 光柱正好"塞满喉口还多一圈"，
     *       不会露出喉环内壁；</li>
     *   <li>洞端 1.55 &lt; 细光束圆周半径 {@link #THIN_BEAM_RING_R} = 1.90 ⇒ 3 条紫线仍露在辉光外面
     *       （伪神 shader 的可见半宽 ≈ 0.55 × 半径 = 0.85 格）。</li>
     *   <li>内芯 ×{@link #MD_BEAM_INNER_SCALE} = 0.45 ⇒ 0.5625 → 0.6975 格（伪神同款倍数）。</li>
     * </ul>
     */
    /** 4 条解压机主喷口光柱：外层半径<b>喷口端</b>（格，= 伪神 {@code renderLinear} 的 {@code startRadius}）。 */
    public static final float MD_BEAM_OUTER_R0 = 1.25F;
    /** 4 条解压机主喷口光柱：外层半径<b>洞端</b>（格，= 伪神的 {@code endRadius}）。 */
    public static final float MD_BEAM_OUTER_R1 = 1.55F;
    /** 内芯半径倍数（与中央主光柱同一个数：伪神 {@code INNER_RADIUS_SCALE} = 0.45）。 */
    public static final float MD_BEAM_INNER_SCALE = PILLAR_INNER_SCALE;
    /** 外层那遍的 alpha（= 伪神锻炉 {@code beamAlpha} = 1.0，与中央两根一致）。 */
    public static final float MD_BEAM_OUTER_ALPHA = 1.0F;
    /** 内芯那遍的 alpha（同上）。 */
    public static final float MD_BEAM_INNER_ALPHA = 1.0F;

    /**
     * 4 条解压机主喷口光柱的<b>外层发光色</b>（可配）：默认与中央主光柱同一个值。
     *
     * <p>用户口径「<b>颜色可配</b>」⇒ 真正的钩子是 {@link #setMdBeamGlowRgb(float, float, float)} /
     * {@link #clearMdBeamGlowOverride()}；这两个方法改的是"这 4 根"的颜色，
     * <b>不影响</b>中央上下两根（那两根走 {@link #setJetGlowRgb}）。
     * 默认取同一族冷蓝白 ⇒ 6 根光柱看起来是"同一套"。
     */
    public static final float[] MD_BEAM_GLOW_RGB = {0.62F, 0.84F, 1.00F};
    /** 4 条主喷口光柱外层发光的<b>当前</b>生效值（null = 用 {@link #MD_BEAM_GLOW_RGB}）。 */
    private static float[] mdBeamGlowOverride;

    /** 设置 4 条解压机主喷口光柱的外层发光色（{@code r,g,b} 各 0..1）。 */
    public static void setMdBeamGlowRgb(float r, float g, float b) {
        mdBeamGlowOverride = new float[]{r, g, b};
    }

    /** 恢复 4 条解压机主喷口光柱的默认发光色（{@link #MD_BEAM_GLOW_RGB}）。 */
    public static void clearMdBeamGlowOverride() {
        mdBeamGlowOverride = null;
    }

    /** 4 条解压机主喷口光柱的发光色<b>当前生效值</b>（返回副本）。 */
    public static float[] mdBeamGlowRgb() {
        return (mdBeamGlowOverride != null ? mdBeamGlowOverride : MD_BEAM_GLOW_RGB).clone();
    }

    /** 粒子：沿轴多少颗（极轴喷流）/ 每条径向主光柱多少颗。 */
    public static final int JET_PARTICLES = 28;
    public static final int RADIAL_PARTICLES = 16;
    /** 粒子沿轴前进速度（"全长 / 秒"）：0.42 ⇒ 一颗粒子约 2.4 秒走完全长（伪神是 tick/240 ⇒ 240 tick = 12 s 走 256 单位，同量级）。 */
    public static final float PARTICLE_SPEED = 0.42F;
    /** 粒子半边长（格）：靠洞一端最大，之后随亮度收小。 */
    public static final float PARTICLE_SIZE = 0.17F;

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
    //  光束（纯数学；渲染状态由 HostFxDraw 负责）—— 2026-10-25 起是 18 条
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 条数总账（第七改之后仍是 18 条，但"谁画"变了 3 处）：
     *   ① 4 条 <b>主喷口光柱</b>（每座解压机 1 条，喷口 → 洞面）
     *        —— 🔴 第七改：<b>伪神同款</b>（{@code HostFxPillarBeam#drawMdBeams}，外层 1.25→1.55 格）</li>
     *   ② 12 条 <b>细紫引导光束</b>（每座 3 条，与该座主柱同向）
     *        —— 🔴 第七改：改成绕<b>每座解压机</b>的轴线（r=1.90、互成 120°），仍走本类的管壳
     *        （用户口径：「小光束不需要换贴图，那只是引导用的」⇒ 保持紫色自绘细线）</li>
     *   ③ 2 条 <b>中央主光柱</b>（上下）—— 第五改起就是伪神同款（同一套 shader/贴图）
     */

    /**
     * 生成<b>自绘管壳</b>光束的四边形（<b>环心本地坐标</b>，由 {@code HostFxDraw#drawBeams}
     * 套 "枢轴 + 自转" 之后当加性层画出去）。
     *
     * <h2>2026-10-25 第七改：这里<b>只剩</b> 12 条细紫引导光束</h2>
     * <ul>
     *   <li><b>12 条细紫光束</b>（{@link #THIN_BEAM_COUNT}）：<b>每座解压机 3 条</b>，
     *       起点 = 该座喷口面上那 3 个<b>发射器口</b>的中心（{@link #mdEmitterPortCenter(int, int)}），
     *       方向 = 该座<b>主喷口光柱的方向</b>（{@link #mdBeamDirection(int, float)}）⇒ 夹角
     *       <b>0.000000°</b>；3 条之间 <b>120°</b>；颜色 {@link #THIN_BEAM_COLOR}（紫）；</li>
     *   <li><b>4 条主喷口光柱</b>：🔴 第七改【搬出了本类】—— 用户「4 个物质解压机的主光束也换成
     *       伪神同款的啊」⇒ 现在由 {@link HostFxPillarBeam#drawMdBeams} 用中央那两根同一套
     *       shader/贴图/顶点格式画（16 片三角形 × 2 遍 × 4 根）。只有 shader 建不起来时才回落到
     *       {@link #buildMdBeamQuads}（上一版的自绘管壳 + 粒子，见 {@code HostFxDraw}）。</li>
     *   <li><b>2 条中央主光柱</b>：同样早已搬出本类（{@link HostFxPillarBeam#draw}，
     *       回落 {@link #buildPillarQuads}）。</li>
     * </ul>
     *
     * @param camLocalX 相机在环心本地坐标里的位置（{@link #cameraInRingLocal}）
     */
    public static List<Quad> buildBeamQuads(float camLocalX, float camLocalY, float camLocalZ) {
        return buildBeamQuads(camLocalX, camLocalY, camLocalZ, HOLE_SURFACE_R, 0.0F);
    }

    /**
     * 同上，但黑洞表面半径由调用方给（渲染器每帧传运行期实测值）。
     *
     * @param holeSurfaceR 黑洞黑盘半径（格）；会被夹到 {@code [0.6, ARM_ROOT_R − 0.5]}，
     *                     防止配置把 scale 调得离谱时"光柱反向"或"穿过环"
     */
    public static List<Quad> buildBeamQuads(float camLocalX, float camLocalY, float camLocalZ,
                                            float holeSurfaceR) {
        return buildBeamQuads(camLocalX, camLocalY, camLocalZ, holeSurfaceR, 0.0F);
    }

    /**
     * @param seconds 世界时间（秒）—— 保留参数（细光束本身不带时间相位；粒子/涟漪在回落路径里）
     */
    public static List<Quad> buildBeamQuads(float camLocalX, float camLocalY, float camLocalZ,
                                            float holeSurfaceR, float seconds) {
        final float tipR = tipRadius(holeSurfaceR);
        final List<Quad> out = new ArrayList<>(thinTubeQuadCount());
        // 12 条细紫光束（每座 3 条，与该座主柱同向）：外壳（更宽更淡）+ 内芯（细而亮）。
        // 端点全来自 thinBeamSegment(k, tipR) 一个函数 ⇒ 渲染 / 探针 / 自检不可能各算一套。
        final float[] thin = THIN_BEAM_COLOR;
        for (int k = 0; k < THIN_BEAM_COUNT; k++) {
            final float[] e = thinBeamSegment(k, tipR);
            addTube(out, e[0], e[1], e[2], e[3], e[4], e[5],
                    camLocalX, camLocalY, camLocalZ,
                    THIN_BEAM_GLOW_SEGMENTS, THIN_BEAM_GLOW_FACETS, P_THIN_GLOW,
                    thin[0], thin[1], thin[2], THIN_BEAM_GLOW_ALPHA);
            addTube(out, e[0], e[1], e[2], e[3], e[4], e[5],
                    camLocalX, camLocalY, camLocalZ,
                    THIN_BEAM_SEGMENTS, THIN_BEAM_FACETS, P_THIN,
                    thin[0], thin[1], thin[2], THIN_BEAM_CORE_ALPHA);
        }
        // 4 条主喷口光柱 / 中央主光柱都不在这里：它们走 HostFxPillarBeam（伪神同款），
        // 只有 shader 建不起来时才回落到本类的 buildMdBeamQuads / buildPillarQuads。
        return out;
    }

    /**
     * <b>回落用</b>：4 条解压机主喷口光柱的旧自绘管壳（五次贝塞尔收细，峰值半径
     * {@link #BEAM_PEAK_RADIUS} = 0.62 格）。
     *
     * <p>🔴 第七改：正常路径上这 4 根走 {@link HostFxPillarBeam#drawMdBeams}（伪神同款）；
     * 只有在 shader 建不起来（缺资源 / 编译失败 / 没有 GL）时才由 {@code HostFxDraw} 调这里 ——
     * 那时宁可画"上一版的观感"，也不能让采集柱整根消失。颗粒子见 {@link #buildParticleQuads}。
     */
    public static List<Quad> buildMdBeamQuads(float camLocalX, float camLocalY, float camLocalZ,
                                              float holeSurfaceR) {
        final float tipR = tipRadius(holeSurfaceR);
        final List<Quad> out = new ArrayList<>(MD_COUNT * BEAM_SEGMENTS * BEAM_FACETS);
        for (int k = 0; k < MD_COUNT; k++) {
            final float[] e = mdBeamEndpoints(k, tipR);
            addTube(out, e[0], e[1], e[2], e[3], e[4], e[5],
                    camLocalX, camLocalY, camLocalZ,
                    BEAM_SEGMENTS, BEAM_FACETS, P_BEZIER,
                    BEAM_COLOR[0], BEAM_COLOR[1], BEAM_COLOR[2], BEAM_SHELL_ALPHA);
        }
        return out;
    }


    /**
     * <b>回落用</b>：中央主光柱的旧自绘管壳（外层发光 + 热白内芯 + 亮度涟漪）。
     *
     * <p>只有在 {@link HostFxPillarBeam} 的 shader 建不起来时才被 {@code HostFxDraw} 调用 ——
     * 那时宁可画"上一版的观感"，也不能让主光柱整根消失。
     * 几何口径与改前完全一致（{@link #JET_GLOW_R0}→{@link #JET_GLOW_R1} 等）。
     */
    public static List<Quad> buildPillarQuads(float camLocalX, float camLocalY, float camLocalZ,
                                              float holeSurfaceR, float seconds) {
        final float tipR = tipRadius(holeSurfaceR);
        final float[] glow = jetGlowRgb();
        final float[] core = JET_CORE_RGB;
        final List<Quad> out = new ArrayList<>(2 * (JET_SEGMENTS * JET_FACETS
                + JET_SEGMENTS * JET_CORE_FACETS));
        for (int sign = -1; sign <= 1; sign += 2) {
            final float[] e = pillarEndpoints(sign, tipR);
            addTube(out, e[0], e[1], e[2], e[3], e[4], e[5],
                    camLocalX, camLocalY, camLocalZ,
                    JET_SEGMENTS, JET_FACETS, P_JET_GLOW,
                    glow[0], glow[1], glow[2], JET_GLOW_ALPHA, seconds);
            addTube(out, e[0], e[1], e[2], e[3], e[4], e[5],
                    camLocalX, camLocalY, camLocalZ,
                    JET_SEGMENTS, JET_CORE_FACETS, P_JET_CORE,
                    core[0], core[1], core[2], JET_CORE_ALPHA, seconds);
        }
        return out;
    }

    /** 洞面半径的夹取（唯一口径：{@code [0.6, ARM_ROOT_R − 0.5]}）。 */
    public static float tipRadius(float holeSurfaceR) {
        return Math.max(0.6F, Math.min(holeSurfaceR, ARM_ROOT_R - 0.5F));
    }

    /**
     * 第 {@code k} 座解压机的<b>主喷口光柱</b>两端：{@code {ax,ay,az,bx,by,bz}}。
     *
     * <p>{@link HostFxPillarBeam#drawMdBeams}、{@link #buildMdBeamQuads}、{@link #beamEndpoints}、
     * {@link #buildParticleQuads}、{@link #selfCheckNozzleBeam()}、{@link #thinBeamSegment}
     * （细光束的<b>方向</b>取自这里）全都走这一个函数 ⇒ 不可能出现"渲染一条、探针量另一条"。
     *
     * <p>端点：起点 = {@link #mdNozzle(int)}（喷口亮核中心），终点 = 洞面
     * {@code (cos·tipR, RADIAL_BEAM_LIFT, sin·tipR)}。
     */
    public static float[] mdBeamEndpoints(int k, float tipR) {
        final float[] nz = mdNozzle(k);
        final double ang = Math.toRadians(MD_AZIMUTH_DEG[Math.floorMod(k, MD_COUNT)]);
        final float cos = (float) Math.cos(ang);
        final float sin = (float) Math.sin(ang);
        return new float[]{nz[0], nz[1], nz[2],
                cos * tipR, RADIAL_BEAM_LIFT, sin * tipR};
    }

    /**
     * 第 {@code k} 座解压机<b>主喷口光柱的方向</b>（单位向量，喷口 → 洞面）。
     *
     * <p>这就是本轮"3 条细光束与主柱夹角 0.000000°"的那个基准向量：
     * {@link #thinBeamSegment} 直接用它的<b>同一个值</b>当方向，自检再反算一次夹角。
     */
    public static float[] mdBeamDirection(int k, float tipR) {
        final float[] e = mdBeamEndpoints(k, tipR);
        final float dx = e[3] - e[0];
        final float dy = e[4] - e[1];
        final float dz = e[5] - e[2];
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new float[]{dx / len, dy / len, dz / len};
    }

    /**
     * 第 {@code sign}（±1）根<b>中央主光柱</b>的两端：洞面 → 沿 ±Y 外 {@link #PILLAR_LENGTH} 格。
     *
     * <p>伪神那套 {@code renderLinear(from,to,...)} 的 {@code from/to} 就是这两个点。
     */
    public static float[] pillarEndpoints(int sign, float tipR) {
        return new float[]{0.0F, sign * tipR, 0.0F,
                0.0F, sign * (tipR + PILLAR_LENGTH), 0.0F};
    }

    /**
     * <b>塔本地 → 环心本地</b>：逐项复现 {@code HostFxDraw#drawMatterDecompressors} 的 pose 链
     * {@code translate(mdCenter) → rotateY(mdYawDeg) → rotateX(MD_TILT_DEG)}。
     *
     * <p>为什么必须有这一个函数：本轮"细光束"的起点 = <b>发射器口中心</b>，
     * 而发射器的方盒是在<b>塔本地坐标</b>里排的（见 {@link #mdEmitterBoxes()}）。
     * 两边要落到同一个点上，就只能走同一次变换 —— 一旦各写一套，"线从口里出来"就会差半格。
     *
     * <p>当前参数（{@code MD_TILT_DEG = −90°}）下的映射，可以直接当读数用：
     * <pre>
     *   塔本地 +Y  → 环心径向朝内（−径向）           ⇒ 轴向 = 朝黑洞
     *   塔本地 +Z  → 环心本地 +Y（世界"上"）
     *   塔本地 +X  → 切向（{@code rotateYDeg(mdYawDeg−90)}）
     * </pre>
     *
     * @return 环心本地坐标 {@code {x,y,z}}（<b>未套环自转</b>；自转由渲染层的 pose 统一套）
     */
    public static float[] mdLocalToRing(int i, float lx, float ly, float lz) {
        final int idx = Math.floorMod(i, MD_COUNT);
        final double tilt = Math.toRadians(MD_TILT_DEG);
        final double ct = Math.cos(tilt);
        final double st = Math.sin(tilt);
        // rotateX(tilt)：y' = y·cos − z·sin、z' = y·sin + z·cos
        final double ty = ly * ct - lz * st;
        final double tz = ly * st + lz * ct;
        // rotateY(yaw)：x' = x·cos + z'·sin、z' = −x·sin + z'·cos
        final double yaw = Math.toRadians(mdYawDeg(idx));
        final double cy = Math.cos(yaw);
        final double sy = Math.sin(yaw);
        final double wx = lx * cy + tz * sy;
        final double wz = -lx * sy + tz * cy;
        final float[] c = mdCenter(idx);
        return new float[]{(float) (c[0] + wx), (float) (c[1] + ty), (float) (c[2] + wz)};
    }

    /**
     * <b>塔本地 → 环心本地</b> 的<b>逆变换</b>（{@link #mdLocalToRing} 的严格逆）。
     *
     * <p>为什么需要它：发射器的<b>方盒</b>只能在塔本地坐标里排（{@code MdBox} 就是"轴对齐 + 一个 yaw"），
     * 而三个口的<b>位置</b>是在"垂直于该座主柱的平面"上定义的（这样 120° 与 r=1.90 才是精确的）。
     * 两个坐标系之间必须有<b>一对</b>互逆函数，否则"口"和"线"会差开。
     */
    public static float[] mdRingToLocal(int i, float rx, float ry, float rz) {
        final int idx = Math.floorMod(i, MD_COUNT);
        final float[] c = mdCenter(idx);
        final double dx = rx - c[0];
        final double dy = ry - c[1];
        final double dz = rz - c[2];
        final double yaw = Math.toRadians(mdYawDeg(idx));
        final double cy = Math.cos(yaw);
        final double sy = Math.sin(yaw);
        // 反 rotateY：x = cos·x' − sin·z'、z' = sin·x' + cos·z'
        final double lx = cy * dx - sy * dz;
        final double tz = sy * dx + cy * dz;
        // 反 rotateX：ly = cos·ty + sin·tz、lz = −sin·ty + cos·tz
        final double tilt = Math.toRadians(MD_TILT_DEG);
        final double ct = Math.cos(tilt);
        final double st = Math.sin(tilt);
        return new float[]{(float) lx,
                (float) (ct * dy + st * tz),
                (float) (-st * dy + ct * tz)};
    }

    /**
     * 第 {@code k} 座解压机主柱的<b>横截面正交基</b> {@code {u(3), v(3)}}（两者都 ⟂ 主柱方向）。
     *
     * <p>直接用 {@link #axes}（与管壳横截面<b>同一套</b>挑法）：{@code u} 是水平切向、
     * {@code v} 基本就是"上"。3 个发射器口就在这个 (u,v) 平面上按 120° 排 ⇒
     * <b>到主柱轴线的垂距恒为 r</b>、<b>两两夹角恒为 120°</b>（都不是近似）。
     */
    public static float[] mdBeamPerpBasis(int k, float tipR) {
        final float[] d = mdBeamDirection(k, tipR);
        return axes(d[0], d[1], d[2]);
    }

    /**
     * 第 {@code k} 座解压机第 {@code j} 个<b>发射器口中心</b>（<b>环心本地坐标，未套环自转</b>）。
     *
     * <p>定义（三件事一次定死，别的量都从这里推）：
     * <pre>
     *   S(k,j) = A(k) + r·(u·cosθⱼ + v·sinθⱼ) + d·h
     *     A(k) = 喷口中心 mdNozzle(k)
     *     d    = 该座主柱方向 mdBeamDirection(k)          ← 细光束方向就取它（夹角 0°）
     *     (u,v)= 与 d 正交的基（mdBeamPerpBasis）          ← 120° 与 r=1.90 是精确的
     *     θⱼ   = 90° / 210° / 330°                        ← 每座 3 条，互成 120°
     *     h    = THIN_BEAM_PORT_LOCAL_Y − MD_NOZZLE_LOCAL_Y = −0.15 格（口面在亮核后 0.15）
     * </pre>
     * ⇒ 判据：{@link #thinBeamSegment} 的起点<b>逐字等于</b>本函数（容差 1e-5）；
     * 到主柱轴线的垂距 == 1.90；三者的垂向偏移两两夹角 == 120.000°。
     */
    public static float[] mdEmitterPortCenter(int k, int j) {
        final int idx = Math.floorMod(j, THIN_BEAMS_PER_MD);
        final float tipR = tipRadius(HOLE_SURFACE_R);           // 设计基准（洞面缩放只挪 <0.01 格）
        final float[] a = mdNozzle(k);
        final float[] d = mdBeamDirection(k, tipR);
        final float[] b = mdBeamPerpBasis(k, tipR);
        final double th = Math.toRadians(THIN_BEAM_PORT_PHASE_DEG[idx]);
        final float cs = (float) Math.cos(th);
        final float sn = (float) Math.sin(th);
        final float h = THIN_BEAM_PORT_LOCAL_Y - MD_NOZZLE_LOCAL_Y;
        final float r = THIN_BEAM_RING_R;
        return new float[]{
                a[0] + r * (b[0] * cs + b[3] * sn) + d[0] * h,
                a[1] + r * (b[1] * cs + b[4] * sn) + d[1] * h,
                a[2] + r * (b[2] * cs + b[5] * sn) + d[2] * h};
    }

    /**
     * 第 {@code j} 个发射器口在<b>塔本地坐标</b>里的位置（{@code {x,y,z}}）—— <b>方盒排布的唯一来源</b>。
     *
     * <p>= {@link #mdRingToLocal}(0, {@link #mdEmitterPortCenter}(0, j))：4 座互为旋转副本 ⇒
     * 塔本地坐标对 4 座是同一个（实测 3 个口的 y 分别 5.327 / 5.269 / 5.269 —— 差 0.06 格，
     * 因为"垂直于主柱的平面"相对塔本地 XZ 面偏了 0.82°；这点差在 5 格大的喷口面上看不出来，
     * 但它保证了口面真的垂直于光柱）。
     */
    public static float[] mdEmitterPortLocal(int j) {
        final float[] c = mdEmitterPortCenter(0, j);
        return mdRingToLocal(0, c[0], c[1], c[2]);
    }

    /**
     * 第 {@code k} 条细光束（{@code 0..11}，{@code k / 3} = 第几座解压机、{@code k % 3} = 该座第几个口）
     * 的两端：{@code {ax,ay,az,bx,by,bz}}。
     *
     * <h2>🔴 2026-10-25 第七改：从"绕中央主光柱一圈"改成"绕每座解压机的喷口轴、每座 3 条"</h2>
     * 用户原话：「<b>我说的小光束是围绕这内环上面 4 个物质解压机的啊，每个物质解压机围绕 3 个
     * （然后配套的类似发射器的方块细节也需要做），不是围绕上下两条光束的</b>」。
     * <pre>
     *   改前（第五改）：12 条都在中央轴 r = 7.40 的大圆上、每 30° 一条、方向 (0,1,0)
     *   改后（本轮）  ：每座解压机 3 条，绕<b>本座喷口轴</b> r = {@link #THIN_BEAM_RING_R} = 1.90 格，
     *                 方位 90/210/330（互成 120°），方向 = 本座主柱方向 {@link #mdBeamDirection}（夹角 0°）
     * </pre>
     * <p>长度：终点落在"与本座主柱终点同一个垂直于主柱的平面"上
     * （{@code L = |B−A| − (起点−A)·d}），所以 4 条主柱收在洞面时，这 3 条也正好一起到洞口。
     */
    public static float[] thinBeamSegment(int k, float tipR) {
        final int idx = Math.floorMod(k, THIN_BEAM_COUNT);
        final int md = idx / THIN_BEAMS_PER_MD;
        final int port = idx % THIN_BEAMS_PER_MD;
        // 起点 = 该座第 port 个发射器口的中心（与发射器方盒同一次变换）
        final float[] s = mdEmitterPortCenter(md, port);
        // 方向 = 该座主柱的单位方向；长度让终点与本座主柱终点共面
        final float[] e = mdBeamEndpoints(md, tipR);
        final float dx = e[3] - e[0];
        final float dy = e[4] - e[1];
        final float dz = e[5] - e[2];
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4F) {
            return new float[]{s[0], s[1], s[2], s[0], s[1], s[2]};
        }
        final float nx = dx / len;
        final float ny = dy / len;
        final float nz = dz / len;
        // 起点相对主柱起点的投影长度（≈ 0.15 格：发射口面比亮核退 0.15 格）
        final float proj = (s[0] - e[0]) * nx + (s[1] - e[1]) * ny + (s[2] - e[2]) * nz;
        final float l = len - proj;
        return new float[]{s[0], s[1], s[2], s[0] + nx * l, s[1] + ny * l, s[2] + nz * l};
    }

    /**
     * 细光束 <b>#0</b> 的方向向量（= 0 号解压机主柱的方向，单位向量）。
     *
     * <p>给自检与离线预览用：12 条的方向<b>逐条</b>等于它自己那一座的主柱方向，
     * 与主柱的夹角必须 <b>0.000000°</b>（判据见 {@link #selfCheckThinBeams()}）。
     */
    public static float[] thinBeamDirection() {
        return mdBeamDirection(0, HOLE_SURFACE_R);
    }

    /** 中央主光柱（+Y 那根）的方向向量 = {@code (0,1,0)}（竖轴，与解压机那 4 根不是一个方向）。 */
    public static float[] pillarDirection() {
        final float[] e = pillarEndpoints(1, HOLE_SURFACE_R);
        final float dx = e[3] - e[0];
        final float dy = e[4] - e[1];
        final float dz = e[5] - e[2];
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new float[]{dx / len, dy / len, dz / len};
    }

    /**
     * 细光束起点离主轴的偏移半径（格）。
     *
     * @deprecated 这个名字是第五改留下的（当时起点挂在喉环上）；现在"离本座主轴多少"的
     *             唯一口径是 {@link #THIN_BEAM_RING_R} = 1.90。留着只为旧探针还能编译。
     */
    @Deprecated
    public static float thinBeamRimR() {
        return THIN_BEAM_RING_R;
    }

    /**
     * 每根光柱的两端（环心本地坐标）—— <b>离线预览与探针的唯一口径</b>：
     * {@code [i][0..2]} = 起点，{@code [i][3..5]} = 终点；
     * <b>0..3</b> = 4 条主喷口光柱（0°/90°/180°/270°），<b>4..5</b> = 下 / 上主轴喷流。
     *
     * <p>⚠️ 细紫光束不在这张表里（它们是 12 条，用 {@link #thinBeamEndpoints}），
     * 这样"4 条主光柱的起点 == 喷口"这条老判据的编号不会被打乱。
     */
    public static float[][] beamEndpoints(float holeSurfaceR) {
        final float tipR = tipRadius(holeSurfaceR);
        final float[][] e = new float[6][6];
        for (int k = 0; k < MD_COUNT; k++) {
            e[k] = mdBeamEndpoints(k, tipR);
        }
        e[4] = pillarEndpoints(-1, tipR);
        e[5] = pillarEndpoints(1, tipR);
        return e;
    }

    /** 12 条细紫光束的两端（{@code [12][6]}，索引见 {@link #thinBeamSegment}）。 */
    public static float[][] thinBeamEndpoints(float holeSurfaceR) {
        final float tipR = tipRadius(holeSurfaceR);
        final float[][] e = new float[THIN_BEAM_COUNT][6];
        for (int k = 0; k < THIN_BEAM_COUNT; k++) {
            e[k] = thinBeamSegment(k, tipR);
        }
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

    // ---- 剖面档（{@link #addTube} 的 profile 参数） ----
    /** 主光柱：五次贝塞尔收细，峰值半径 {@link #BEAM_PEAK_RADIUS}。 */
    private static final int P_BEZIER = 0;
    /** 细紫光束内芯：细、两端略收（sin 包络），峰值半径 {@link #THIN_BEAM_PEAK_RADIUS}。 */
    private static final int P_THIN = 1;
    /** 细紫光束外发光：更宽（{@link #THIN_BEAM_GLOW_SCALE} 倍）、更淡，让 1~2 px 的细线看得见。 */
    private static final int P_THIN_GLOW = 2;
    /** 主轴喷流外发光壳：{@link #JET_GLOW_R0} → {@link #JET_GLOW_R1} 线性收。 */
    private static final int P_JET_GLOW = 3;
    /** 主轴喷流内芯：{@link #JET_CORE_R0} → {@link #JET_CORE_R1}。 */
    private static final int P_JET_CORE = 4;

    /** 某档剖面在 {@code t}（0..1，沿轴）处的半径（格）。 */
    public static float tubeRadius(int profile, float t) {
        final float x = Math.max(0.0F, Math.min(1.0F, t));
        return switch (profile) {
            case P_THIN -> THIN_BEAM_PEAK_RADIUS * (0.22F + 0.78F * (float) Math.sin(Math.PI * x));
            case P_THIN_GLOW -> THIN_BEAM_PEAK_RADIUS * THIN_BEAM_GLOW_SCALE
                    * (0.22F + 0.78F * (float) Math.sin(Math.PI * x));
            case P_JET_GLOW -> JET_GLOW_R0 + (JET_GLOW_R1 - JET_GLOW_R0) * x;
            case P_JET_CORE -> JET_CORE_R0 + (JET_CORE_R1 - JET_CORE_R0) * x;
            default -> taperRadius(x);
        };
    }

    /** 某档剖面在 {@code t}（0..1，沿轴）处的亮度包络（0..1，直接当加性 alpha 的系数）。 */
    public static float tubeBrightness(int profile, float t) {
        final float x = Math.max(0.0F, Math.min(1.0F, t));
        return switch (profile) {
            case P_THIN -> 0.55F + 0.45F * (float) Math.sin(Math.PI * x);
            case P_THIN_GLOW -> 0.60F + 0.40F * (float) Math.sin(Math.PI * x);
            case P_JET_GLOW -> 0.45F + 0.55F * (float) Math.sin(Math.PI * x);
            case P_JET_CORE -> 1.0F - 0.25F * x;
            default -> taperBrightness(x);
        };
    }

    /**
     * 沿轴的<b>亮度涟漪</b>（只给主轴喷流用，0.72..1.0）。
     *
     * <p>为什么加：粒子是"一颗颗的"，但管壳本身是一根连续的光柱 —— 静止看就是一根均匀的柱子，
     * 看不出"在流动"。给壳层叠一条随 {@code seconds} 前进的横向明暗带
     * （{@code sin(6πt − 3·seconds)} ⇒ 每秒有 3 个亮带往外跑），
     * <b>静止一帧里也能读出"轴向有流动"</b>，与伪神那张 {code space_layer} 贴图沿轴滚是同一个意思。
     */
    public static float jetRipple(float t, float seconds) {
        final double phase = 6.0D * Math.PI * t - 3.0D * seconds;
        return 0.72F + 0.28F * (float) Math.sin(phase);
    }

    /** 主光柱那一档（给外部/探针一个名字，不必知道 private int）。 */
    public static final int PROFILE_BEZIER = P_BEZIER;
    /** 细紫光束那一档。 */
    public static final int PROFILE_THIN = P_THIN;

    /**
     * 轴向 A→B 的正交基：{@code {ux,uy,uz, vx,vy,vz}}（与 {@link #addTube} 内部挑法一致）。
     *
     * <p>公开出来是因为细光束的起点、粒子的绕轴角度都要用<b>同一套</b>横截面基，
     * 否则"粒子贴在哪一面上"和"管壳哪一面朝相机"会各算一套。
     */
    public static float[] axes(float dx, float dy, float dz) {
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6F) {
            return new float[]{1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F};
        }
        final float nx = dx / len;
        final float ny = dy / len;
        final float nz = dz / len;
        float refX = 0.0F;
        float refY = 1.0F;
        float refZ = 0.0F;
        if (Math.abs(ny) > 0.9F) {
            refX = 1.0F;
            refY = 0.0F;
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
        return new float[]{ux, uy, uz, vx, vy, vz};
    }

    /** 4 条主喷口光柱的圆管壳（P_BEZIER 档、{@link #BEAM_COLOR}、alpha ×1）。 */
    private static void addBeamShell(List<Quad> out,
                                     float ax, float ay, float az, float bx, float by, float bz,
                                     float camX, float camY, float camZ) {
        addTube(out, ax, ay, az, bx, by, bz, camX, camY, camZ,
                BEAM_SEGMENTS, BEAM_FACETS, P_BEZIER,
                BEAM_COLOR[0], BEAM_COLOR[1], BEAM_COLOR[2], 1.0F);
    }

    /**
     * 一根圆柱壳管（沿 A→B 切 {@code segments} 段、每段 {@code facets} 个四边形）。
     *
     * <p>横截面基向量 {@code u/v} 由 {@link #axes} 给出；起始角
     * {@code phi = atan2(相机在 v 上的分量, 相机在 u 上的分量)} 让第 0 片<b>正对相机</b>
     * —— 这就是"任何角度看都是一根有厚度的管"的原因（伪神也这么干：
     * {@code getVertexAngle()} 里加了 {@code cameraAngle − π/2}）。
     * <p>半径与亮度按 {@code profile} 取 {@link #tubeRadius} / {@link #tubeBrightness}；
     * 纯顶点色、无贴图；加性混合由渲染层负责。
     */
    private static void addTube(List<Quad> out,
                                float ax, float ay, float az, float bx, float by, float bz,
                                float camX, float camY, float camZ,
                                int segments, int facets, int profile,
                                float red, float green, float blue, float alphaScale) {
        addTube(out, ax, ay, az, bx, by, bz, camX, camY, camZ, segments, facets, profile,
                red, green, blue, alphaScale, Float.NaN);
    }

    /**
     * 同上，多一个"亮度涟漪相位"（{@code NaN} = 不叠涟漪；只有主轴喷流会传真实时间）。
     *
     * @see #jetRipple
     */
    private static void addTube(List<Quad> out,
                                float ax, float ay, float az, float bx, float by, float bz,
                                float camX, float camY, float camZ,
                                int segments, int facets, int profile,
                                float red, float green, float blue, float alphaScale,
                                float rippleSeconds) {
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
        final float[] ax2 = axes(dx, dy, dz);
        final float ux = ax2[0];
        final float uy = ax2[1];
        final float uz = ax2[2];
        final float vx = ax2[3];
        final float vy = ax2[4];
        final float vz = ax2[5];
        // 相机在该截面平面里的方位角（用光束中点当参考点）
        final float mx = (ax + bx) * 0.5F;
        final float my = (ay + by) * 0.5F;
        final float mz = (az + bz) * 0.5F;
        final float cu = (camX - mx) * ux + (camY - my) * uy + (camZ - mz) * uz;
        final float cv = (camX - mx) * vx + (camY - my) * vy + (camZ - mz) * vz;
        final float phi = (float) Math.atan2(cv, cu);
        final float step = (float) (Math.PI * 2.0D / facets);
        final boolean rippled = !Float.isNaN(rippleSeconds)
                && (profile == P_JET_GLOW || profile == P_JET_CORE);
        for (int seg = 0; seg < segments; seg++) {
            final float t0 = (float) seg / segments;
            final float t1 = (float) (seg + 1) / segments;
            final float r0 = tubeRadius(profile, t0);
            final float r1 = tubeRadius(profile, t1);
            final float rip0 = rippled ? jetRipple(t0, rippleSeconds) : 1.0F;
            final float rip1 = rippled ? jetRipple(t1, rippleSeconds) : 1.0F;
            final float a0 = tubeBrightness(profile, t0) * alphaScale * rip0;
            final float a1 = tubeBrightness(profile, t1) * alphaScale * rip1;
            final float c0x = ax + nx * len * t0;
            final float c0y = ay + ny * len * t0;
            final float c0z = az + nz * len * t0;
            final float c1x = ax + nx * len * t1;
            final float c1y = ay + ny * len * t1;
            final float c1z = az + nz * len * t1;
            for (int f = 0; f < facets; f++) {
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
                }, red, green, blue, (a0 + a1) * 0.5F));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  要求 ④：粒子沿轴向流动（等价于伪神"UV 沿轴滚动"的几何版）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * 一帧的<b>流动粒子</b>（全部朝向相机的加法四边形）。
     *
     * <p>伪神是用 {@code texCoord.u = offset/256 + tick/240} 让贴图沿轴滚（取证见类内
     * {@link #JET_GLOW_RGB} 上方那段）；我们没有贴图/自定义 shader，于是把"滚动"直接做成
     * <b>几何粒子沿轴前进</b>：
     * <pre>
     *   t_i(time) = frac(phase_i + seconds × PARTICLE_SPEED)     // 0..1 沿轴循环
     *   位置      = A + (B−A) × t_i + (u·cosθ + v·sinθ) × r(t_i) × 1.35   // 贴着管壁外侧绕轴慢转
     *   大小      = PARTICLE_SIZE × (0.55 + 0.75·sin(πt)) × (0.6 + 0.4·|沿轴速度感|)
     *   亮度      = 0.30 + 0.70·tubeBrightness(profile, t)        // 中间最亮，两端渐隐
     * </pre>
     * 因为粒子之间有<b>空隙</b>，加性叠加后在画面上就是"一串亮点沿轴流过"——
     * 与伪神贴图里"暗斑即亮点"（{@code alpha ∝ 1 − luminance}）是同一个视觉机制。
     *
     * <p>🔴 2026-10-25 第七改：这批粒子现在<b>只在回落路径</b>里用 —— 4 条主喷口光柱已经换成
     * 伪神同款的 shader（{@link HostFxPillarBeam#drawMdBeams}），它的"流动"来自 space_layer 贴图
     * UV 沿轴滚（tick/240）；再叠几何粒子就是<b>两套流动</b>（与第五改撤掉中央主柱粒子是同一个理由）。
     * 所以正常路径 {@code HostFxDraw} 不调它，只有 shader 建不起来时才和 {@link #buildMdBeamQuads} 一起画。
     *
     * @param seconds 世界时间（秒，渲染器传 {@code (gameTime + partialTick)/20}）；停世界时自然冻结
     */
    public static List<Quad> buildParticleQuads(float camLocalX, float camLocalY, float camLocalZ,
                                                float seconds, float holeSurfaceR) {
        final float tipR = tipRadius(holeSurfaceR);
        final List<Quad> out = new ArrayList<>(MD_COUNT * RADIAL_PARTICLES);
        // 4 条主喷口光柱：从喷口（A）流向洞面（B）—— 仅回落路径
        for (int k = 0; k < MD_COUNT; k++) {
            final float[] e = mdBeamEndpoints(k, tipR);
            addParticles(out, e, P_BEZIER, RADIAL_PARTICLES, seconds, k * 37,
                    BEAM_COLOR[0], BEAM_COLOR[1], BEAM_COLOR[2],
                    camLocalX, camLocalY, camLocalZ, false);
        }
        // 中央主光柱：🔴 2026-10-25 第五改【粒子整批撤掉】——
        //    伪神那根光柱的"流动"来自 space_layer 贴图 UV 沿轴滚（tick/240），
        //    现在是同一套 shader 在画（HostFxPillarBeam），再撒几何粒子就是两套流动叠在一起。
        return out;
    }

    /**
     * 沿一根光柱撒 {@code n} 颗流动粒子。
     *
     * @param flip true = 反向流动（主轴喷流：从洞面往外）
     */
    private static void addParticles(List<Quad> out, float[] e, int profile, int n, float seconds,
                                     int seed, float red, float green, float blue,
                                     float camX, float camY, float camZ, boolean flip) {
        final float dx = e[3] - e[0];
        final float dy = e[4] - e[1];
        final float dz = e[5] - e[2];
        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-3F) {
            return;
        }
        final float[] ax = axes(dx, dy, dz);
        final float[] upRef = {0.0F, 1.0F, 0.0F};
        for (int i = 0; i < n; i++) {
            // 相位：每颗粒子在自己的格子上错开（黄金比散列，看不出规律）
            final float phase = frac(0.137F * (seed + i * 7) + 0.6180339F * i);
            float t = frac(phase + seconds * PARTICLE_SPEED * (0.85F + 0.30F * frac(i * 0.37F)));
            if (flip) {
                t = 1.0F - t;
            }
            final float px = e[0] + dx * t;
            final float py = e[1] + dy * t;
            final float pz = e[2] + dz * t;
            // 绕轴的方位：黄金角 × i，再随时间极慢自转（伪神是 v 方向再加 u/3 的螺旋项）
            final float ang = (float) (i * 2.3999632D) + seconds * 0.55F + seed;
            final float ca = (float) Math.cos(ang);
            final float sa = (float) Math.sin(ang);
            final float r = tubeRadius(profile, t) * 1.35F;
            final float ox = (ax[0] * ca + ax[3] * sa) * r;
            final float oy = (ax[1] * ca + ax[4] * sa) * r;
            final float oz = (ax[2] * ca + ax[5] * sa) * r;
            final float cx = px + ox;
            final float cy = py + oy;
            final float cz = pz + oz;
            final float size = PARTICLE_SIZE * (0.55F + 0.75F * (float) Math.sin(Math.PI * t));
            final float alpha = 0.30F + 0.70F * tubeBrightness(profile, t);
            // 面向相机的四边形：dir = 相机 − 粒子；right = dir × 世界上；up = right × dir
            final float vx0 = camX - cx;
            final float vy0 = camY - cy;
            final float vz0 = camZ - cz;
            final float vl = (float) Math.sqrt(vx0 * vx0 + vy0 * vy0 + vz0 * vz0);
            if (vl < 1.0e-3F) {
                continue;
            }
            final float dirX = vx0 / vl;
            final float dirY = vy0 / vl;
            final float dirZ = vz0 / vl;
            float rx = dirY * upRef[2] - dirZ * upRef[1];
            float ry = dirZ * upRef[0] - dirX * upRef[2];
            float rz = dirX * upRef[1] - dirY * upRef[0];
            float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (rl < 1.0e-3F) {
                rx = 1.0F;
                ry = 0.0F;
                rz = 0.0F;
                rl = 1.0F;
            }
            rx /= rl;
            ry /= rl;
            rz /= rl;
            final float ux2 = ry * dirZ - rz * dirY;
            final float uy2 = rz * dirX - rx * dirZ;
            final float uz2 = rx * dirY - ry * dirX;
            out.add(new Quad(new float[]{
                    cx - rx * size - ux2 * size, cy - ry * size - uy2 * size, cz - rz * size - uz2 * size,
                    cx + rx * size - ux2 * size, cy + ry * size - uy2 * size, cz + rz * size - uz2 * size,
                    cx + rx * size + ux2 * size, cy + ry * size + uy2 * size, cz + rz * size + uz2 * size,
                    cx - rx * size + ux2 * size, cy - ry * size + uy2 * size, cz - rz * size + uz2 * size,
            }, red, green, blue, Math.min(1.0F, alpha)));
        }
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    /**
     * <b>正常路径</b>一帧的自绘四边形数 = 12 条细紫光束（外壳 + 内芯）。
     *
     * <p>🔴 第七改：4 条主喷口光柱也搬去了伪神 shader ⇒ 这里又少了
     * {@code 4 × 32 × 12 = 1536} 片；粒子也不再画（shader 自己有 UV 流动）。
     */
    public static int beamQuadCount() {
        return tubeQuadCount();
    }

    /**
     * 一帧<b>细紫光束</b>管壳四边形数：12 条 × (内芯 18×4 + 外发光 12×6) = <b>1728</b> 片。
     *
     * <p>第五改起中央主光柱不再是管壳（改伪神 shader 的 16 片三角形）；
     * 第七改起 4 条主喷口光柱也是。
     */
    public static int tubeQuadCount() {
        return thinTubeQuadCount();
    }

    /** 12 条细紫光束的管壳四边形数（外壳 + 内芯两档）。 */
    public static int thinTubeQuadCount() {
        return THIN_BEAM_COUNT * (THIN_BEAM_SEGMENTS * THIN_BEAM_FACETS
                + THIN_BEAM_GLOW_SEGMENTS * THIN_BEAM_GLOW_FACETS);
    }

    /**
     * <b>回落路径</b>一帧的额外四边形数（= 4 条主喷口光柱的旧管壳 1536 + 粒子 64 = 1600）。
     *
     * <p>只在 {@link HostFxPillarBeam} 的 shader 建不起来时才会多出这一批（见 {@code HostFxDraw}）；
     * 正常路径恒为 0 —— 日志里打出来就是为了让"有没有回落"一眼可判。
     */
    public static int mdFallbackQuadCount() {
        return MD_COUNT * BEAM_SEGMENTS * BEAM_FACETS + particleQuadCount();
    }

    /** 一帧粒子四边形数（每颗粒子一个朝向相机的四边形；只有 4 条主喷口光柱撒粒子，<b>仅回落路径</b>）。 */
    public static int particleQuadCount() {
        return MD_COUNT * RADIAL_PARTICLES;
    }

    /**
     * 中央主光柱每帧的<b>三角形</b>数（伪神那套：16 片，每片 2 个三角形）。
     *
     * <p>⚠️ 16 这个数放在本类（而不是 {@link HostFxPillarBeam}），是为了让本类保持
     * <b>零 MC 依赖</b> —— 离线探针/预览图只编译本类就能拿到全部读数。
     *
     * @return 上下 2 根 × 2 遍 × 16 片 × 2 = 128 个三角形（= 384 个顶点）
     */
    public static int pillarTriangleCount() {
        return 2 * 2 * PILLAR_QUADS * 2;
    }

    /**
     * <b>4 条解压机主喷口光柱</b>每帧的三角形数（第七改：伪神同款，{@link HostFxPillarBeam#drawMdBeams}）。
     *
     * @return 4 根 × 2 遍 × 16 片 × 2 = <b>256</b> 个三角形（= 768 个顶点）
     */
    public static int mdBeamTriangleCount() {
        return MD_COUNT * 2 * PILLAR_QUADS * 2;
    }

    /** 主光柱沿圆周几等分（伪神写死 16；{@link HostFxPillarBeam} 用的是同一个数）。 */
    public static final int PILLAR_QUADS = 16;

    /** 预算说明（{@code draw} 日志里直接打这一行）。 */
    public static String budget() {
        final float[] glow = jetGlowRgb();
        final float[] mdGlow = mdBeamGlowRgb();
        return "光束 = 4 主喷口（🔴 第七改：伪神同款 shader+贴图，外层 " + MD_BEAM_OUTER_R0 + "→"
                + MD_BEAM_OUTER_R1 + " 格、内芯 ×" + MD_BEAM_INNER_SCALE + " = "
                + (MD_BEAM_OUTER_R0 * MD_BEAM_INNER_SCALE) + "→" + (MD_BEAM_OUTER_R1 * MD_BEAM_INNER_SCALE)
                + " 格，发光色 " + rgbText(mdGlow) + "（可配），2 遍 Intensity 2.0/4.0，共 "
                + mdBeamTriangleCount() + " 三角形；回落时才画旧管壳 " + (MD_COUNT * BEAM_SEGMENTS * BEAM_FACETS)
                + " + 粒子 " + particleQuadCount() + "）"
                + " + " + THIN_BEAM_COUNT + " 细紫（<b>每座 3 条</b>，绕<b>本座</b>喷口轴 r=" + THIN_BEAM_RING_R
                + " 均布、互成 120°，与本座主柱夹角 0°，峰值半径 " + THIN_BEAM_PEAK_RADIUS
                + "，色 " + rgbText(THIN_BEAM_COLOR) + "）"
                + " + 中央主光柱 2 根（伪神同款 shader+贴图，外层 " + PILLAR_OUTER_R0 + "→"
                + PILLAR_OUTER_R1 + "、内芯 ×" + PILLAR_INNER_SCALE + " = "
                + (PILLAR_OUTER_R0 * PILLAR_INNER_SCALE) + "→" + (PILLAR_OUTER_R1 * PILLAR_INNER_SCALE)
                + "，发光色 " + rgbText(glow) + "，2 遍 Intensity 2.0/4.0，共 "
                + pillarTriangleCount() + " 三角形）"
                + " = 自绘管壳 " + tubeQuadCount() + " 四边形 / " + (tubeQuadCount() * 4) + " 顶点"
                + "（正常路径无粒子；光的流动全部来自 space_layer 贴图 UV 沿轴滚 tick/240）";
    }

    /** 把 0..1 的三通道写成 {@code (r,g,b)} 的 0..255 读数（日志/报告用）。 */
    public static String rgbText(float[] rgb) {
        return "(" + Math.round(rgb[0] * 255.0F) + "," + Math.round(rgb[1] * 255.0F)
                + "," + Math.round(rgb[2] * 255.0F) + ")";
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

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 2026-10-25 第五改：内环【摘除段】—— 4 个喷口处那一段内环不再渲染
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话（第 4 条）：「红框那段内环方块可以不渲染，换成群星物质解压器的前部」。
     *
     * 为什么"不渲染"要分两步做（这是最容易做错的地方）：
     *   ① 这 4 段格**必须继续留在 {@link #hideTargets()} 里**（世界里的真方块被写成 AIR），
     *      否则真方块会从解压机前部里穿出来；
     *   ② 但它们**必须从环的烘焙集里剔除**（{@code HostFxRingGroups} 里的 mouthCut），
     *      否则它们会跟着环一起转、正好挡在机器口前面 —— 就是用户截图里那段"内环玻璃方块"。
     * ⇒ 台账（隐藏集）<b>格数不变</b>，变的是"环烘焙集"。
     */

    /**
     * 要从<b>环烘焙</b>里摘掉的格子（生成器坐标）—— 4 个解压机方位各一段。
     *
     * <p>取法（与 {@link #hideTargets()} 的内环带规则逐字一致：同一条 {@code |hypot−48| ≤ 1.1}
     * ＋同一条 {@code (x+z)%4==0} 的肋格规则），只是多两条"切向窗口"：
     * <pre>
     *   方位 +X 那一段： x &gt; 0 且 |z| ≤ MD_MOUTH_CUT_HALF(2.20)
     *   方位 −X 那一段： x &lt; 0 且 |z| ≤ 2.20
     *   方位 +Z 那一段： z &gt; 0 且 |x| ≤ 2.20
     *   方位 −Z 那一段： z &lt; 0 且 |x| ≤ 2.20
     *   （径向仍是 |hypot(x,z) − 48| ≤ 1.1 ⇒ 只摘 r = 47/48/49 这三列，正是内环带的全厚度）
     * </pre>
     * <p>摘掉之后那几个位置由 {@link #mdFrontBoxes()} 的<b>锥形喷口段 + 法兰 + 唇环 + 喉环</b>填上
     * （法兰半径 2.45 &gt; 窗口半宽 2.20 ⇒ 口子四边都被压住，看不到"切开的断面"）。
     *
     * <p>⚠️ 摘除段随环一起转（它在烘焙时就被剔掉，而机器与环同一个 spin）⇒ 永远对得上。
     */
    public static List<BlockPos> mdMouthCutCells() {
        final List<BlockPos> out = new ArrayList<>(128);
        final int lim = (int) Math.ceil(INNER_R) + 2;
        for (int x = -lim; x <= lim; x++) {
            for (int z = -lim; z <= lim; z++) {
                if (Math.abs(Math.hypot(x, z) - INNER_R) > INNER_TOLERANCE) {
                    continue;                                   // 不是内环带
                }
                final boolean nearXAxis = Math.abs(z) <= MD_MOUTH_CUT_HALF && x != 0;
                final boolean nearZAxis = Math.abs(x) <= MD_MOUTH_CUT_HALF && z != 0;
                if (!nearXAxis && !nearZAxis) {
                    continue;                                   // 不在 4 个喷口窗口里
                }
                out.add(new BlockPos(x, RING_Y, z));
                if (((x + z) & 3) == 0) {
                    out.add(new BlockPos(x, RING_Y + 1, z));
                    out.add(new BlockPos(x, RING_Y - 1, z));
                }
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

    /**
     * 单座盒数上限（<b>预算上限</b>，不是目标值；{@link #selfCheckMdParts()} 会当场比对）。
     *
     * <p>🔴 2026-10-25 第三改：用户口径从"40~80 件"改成「<b>做得更精细一点，模仿群星的建模</b>」，
     * 并明确"件数从当前的 ~80 提升到 <b>≥160</b>"⇒ 上限抬到 {@value}，下限抬到
     * {@link #MD_MIN_BOXES}（= 160，用户给的那条硬线）。
     *
     * <p>🔴 2026-10-25 第七改：{@code 420 → 460} —— 本轮按要求②加了<b>发射器</b>模块
     * （{@link #mdEmitterBoxes()}，<b>+51 件/座</b>：面盘环 12 + 支撑臂 6 + 3 个口 × 11），
     * 实测单座 389 → <b>440 件</b>，旧上限 420 会把自检判红。
     * <b>抬的是"预算上限"这个闸门，不是模型体量</b>：包围盒仍是一格没变
     * （塔本地 5.52 × 9.67 × 5.49，最前端仍是亮核 y=5.61），单件仍 ≤1 格。
     */
    public static final int MD_MAX_BOXES = 460;
    /** 单座盒数下限（用户口径：<b>≥160 件</b>；低于这个数就是"还是一坨粗块"）。 */
    public static final int MD_MIN_BOXES = 160;

    /** 材质色（>1 的通道画的时候会被夹到 1）。 */
    // 🔴 2026-10-25 自看后调过一次对比度：第一版机壳 0.63 与箍 0.80 太接近 ⇒ 自看时"三道箍"糊在鼓身上，
    //    现在把机壳压暗、箍提到近白、收口压更暗，三者一眼分得开（这是"剪影能读出鼓+箍"的前提）。
    private static final float[] MD_SHELL = {0.53F, 0.58F, 0.67F};        // 鼓身机壳（压暗）
    private static final float[] MD_SHELL_DARK = {0.38F, 0.43F, 0.51F};   // 收口 / 侧舱外壳（更暗）
    private static final float[] MD_STEEL = {0.93F, 0.96F, 1.00F};        // 钢箍（近白，凸出鼓面）
    private static final float[] MD_LIGHT = {1.00F, 0.62F, 0.20F};        // 橙色光带（提亮一点）
    private static final float[] MD_CORE = {1.00F, 0.90F, 0.64F};         // 顶口亮核 / 鼓心
    private static final float[] MD_POD = {0.70F, 0.58F, 0.48F};          // 侧面外挂小舱（暖钢）
    private static final float[] MD_GRILLE = {0.26F, 0.30F, 0.37F};       // 散热栅 / 格栅（最暗，让亮件弹出来）
    private static final float[] MD_RIM = {0.82F, 0.88F, 0.96F};          // 喷口唇 / 浮动环（冷白钢）

    /**
     * 喷口喉环半径（格）。
     *
     * <p>🔴 2026-10-25 第五改之后它只剩"喉口孔径"一个含义：细光束<b>不再</b>挂在它上面
     * （12 条改成绕轴均布），主光柱的起点由 {@link #MD_NOZZLE_LOCAL_Y} 决定。
     */
    public static final float MD_MOUTH_R = 1.15F;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 2026-10-25 第五改：解压机【前部】独立成一段可复用的几何
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话（2026-10-25，第 4 条）：
     *   「红框那段内环方块可以不渲染，换成群星物质解压器的前部」——
     *   截图上红框处露出的是一段<b>内环玻璃方块</b>（解压机的口在它后面，很突兀）。
     * 用户给的解压机造型：「横躺的桶 + 前部锥形喷口」⇒ 前部 = **锥形喷口段 + 唇环 + 喉环**。
     *
     * 这段几何现在有两个用处（同一份 {@link #mdFrontBoxes()}，不是两套代码）：
     *   ① 解压机本体的前端（{@link #matterDecompressorBoxes()} 末尾 append 它）；
     *   ② <b>填内环被摘掉的那一段</b>：它从 r=49.50（内环带外沿）一路插到 r=46.89（带内沿），
     *      法兰直径 4.90 > 摘除段口宽 4.40 ⇒ 口子被机器前部盖住，看起来是"解压机插进内环"，
     *      而不是"内环从机器前面穿过去"。
     *
     * 轴向标尺（塔本地 +Y = 朝黑洞；环心半径 r = MD_CENTER_R − y）：
     *   y=3.00 → r=49.50 法兰面（正好贴住内环带外表面）
     *   y=3.10..4.90 → r=49.40..47.60 四阶锥形喷口段（每阶半径台阶 ~0.28，径向重叠 0.16）
     *   y=5.05 → r=47.45 唇环
     *   y=5.26 → r=47.24 喉环（孔径 MD_MOUTH_R = 1.15）
     *   y=5.45 → r=47.05 亮核 = **主光柱的起点**
     */

    /** 前部法兰面（= 内环带外表面所在的半径 49.50 格）在塔本地的轴向位置。 */
    public static final float MD_FRONT_Y0 = 3.00F;
    /** 前部法兰半径（格）：必须 > 摘除段半宽 {@link #MD_MOUTH_CUT_HALF} × √2 才盖得住口子四角。 */
    public static final float MD_FRONT_FLANGE_R = 2.45F;
    /**
     * 内环<b>摘除段</b>的半宽（格，切向）。
     *
     * <p>2.20 的来历：前部最粗处的半径是法兰 {@link #MD_FRONT_FLANGE_R} = 2.45，
     * 摘除段只要比它窄、再让法兰盖住边界，就"看不出是切开的"：
     * {@code 2.45 (法兰半径) − 2.20 (半宽) = 0.25} 格搭接 ⇒ 口子四边都被法兰压住。
     */
    public static final float MD_MOUTH_CUT_HALF = 2.20F;

    /**
     * <b>可复用的「解压机前部」</b>：锥形喷口段（4 阶）+ 法兰 + 唇环 + 喉环 + 内格栅 + 亮核。
     *
     * <p>返回的是<b>塔本地坐标</b>（+X 横向 / +Y 朝黑洞 / +Z 环心本地"上"），
     * 与 {@link #matterDecompressorBoxes()} 其余分件同一套坐标，所以两者可以直接拼。
     *
     * <p>件数：法兰 16 + 锥段 4×16 + 唇环 12 + 喉环 10 + 格栅 6 + 亮核 1 = <b>109 件</b>。
     */
    public static List<MdBox> mdFrontBoxes() {
        final List<MdBox> out = new ArrayList<>(128);
        // ① 法兰：盖住内环摘除段的口子（半径 2.45、厚 0.24、外沿 r = 49.50 − 0.12 ≈ 49.38）
        mdRing(out, 16, MD_FRONT_FLANGE_R, MD_FRONT_Y0, 0.0F, 0.50F, 0.12F, 0.14F, MD_STEEL);
        // ② 锥形喷口段：4 阶，半径 2.20 → 1.44（相邻阶径向重叠 0.16 ⇒ 锥面没有洞）
        final float[] coneY = {3.10F, 3.70F, 4.30F, 4.90F};
        final float[] coneR = {2.20F, 1.92F, 1.66F, 1.44F};
        for (int i = 0; i < coneY.length; i++) {
            mdRing(out, 16, coneR[i], coneY[i], 0.0F, 0.50F, 0.30F, 0.22F, MD_SHELL);
        }
        // ③ 唇环（比锥面高出一圈，剪影上是一条亮边）
        mdRing(out, 12, 1.62F, 5.05F, 0.0F, 0.42F, 0.07F, 0.12F, MD_RIM);
        // ④ 喉环（最细的一圈，孔径 = MD_MOUTH_R）
        mdRing(out, 10, MD_MOUTH_R, 5.26F, 0.0F, 0.36F, 0.07F, 0.08F, MD_RIM);
        // ⑤ 喉后的内格栅（让"口里"有层次，不是一块平底）
        mdRing(out, 6, 0.86F, 5.16F, 0.0F, 0.28F, 0.05F, 0.05F, MD_GRILLE);
        // ⑥ 亮核 = 主光柱的起点（唯一来源，见 MD_NOZZLE_LOCAL_Y）
        mdBox(out, 0.0F, MD_NOZZLE_LOCAL_Y, 0.0F, 0.22F, 0.16F, 0.22F, 0.0F, MD_CORE);
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    //  🔴 2026-10-25 第七改：喷口面上的【发射器】方块细节（3 条细紫光束的"出口"）
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /*
     * 用户原话（本轮）：「每个物质解压机围绕 3 个（<b>然后配套的类似发射器的方块细节也需要做</b>）」。
     *
     * ⇒ 在喷口面上给那 3 条细紫光束各做一个"看起来是它射出来的"小结构，并回答一个关键问题：
     *   这条线<b>凭什么</b>是从这里出去的？答案必须是几何，不是巧合：
     *   ① 三个口的中心 = {@link #mdEmitterPortLocal(int)}，**喷口面那一圈**（r = 1.90）；
     *   ② 细光束起点 = {@link #mdEmitterPortCenter(int, int)} = 同一个本地点的同一次变换；
     *   ③ 口本身是一个"方管 + 唇沿 + 内亮芯 + 两道格栅"的小发射器，管口方向朝黑洞。
     *
     * <h3>件数与体量（不许变大）</h3>
     * <pre>
     *   面盘环     12 件（R=1.90、轴向厚 0.10、径向带宽 0.26）  ← 三个口共同的安装面
     *   面盘支撑臂  6 件（R=1.74、轴向厚 0.10）                 ← 把面盘连到喉/唇环上
     *   发射器 ×3  每口 11 件 = 4 侧壁 + 4 唇沿 + 1 内亮芯 + 2 格栅      = 33 件
     *   -------------------------------------------------------------------
     *   合计 <b>+51 件/座</b>（389 → 440），单件最大厚度 0.12 格（≤0.15 的口径），
     *   且<b>包围盒一格没变</b>（塔本地 5.52 × 9.67 × 5.49，最前端仍是亮核 y=5.61）。
     * </pre>
     *
     * <p>颜色：管壁 / 面盘用机壳与暗钢（读得出是"金属件"），嘴沿用冷白钢 {@code MD_RIM}、
     * 口内亮芯用<b>与细光束同族的紫</b> {@code MD_PORT_GLOW} ⇒ 一眼能看出"这个口是发射紫光的"。
     */
    /** 发射器口内亮芯的颜色：紫（与 {@link #THIN_BEAM_COLOR} 同族，提亮到能当实体色读）。 */
    private static final float[] MD_PORT_GLOW = {0.78F, 0.62F, 1.00F};

    /**
     * 喷口面上的 3 个<b>发射器</b>（塔本地坐标）。
     *
     * <p>每个口的结构（轴向 +Y = 朝黑洞；口中心 = {@link #mdEmitterPortLocal(int)}）：
     * <ol>
     *   <li><b>4 侧壁</b>：方管，外半宽 0.26、壁厚 0.12、轴向 0.34 高（口心下方 0.32..上方 0.02）；</li>
     *   <li><b>4 唇沿</b>：管口那一圈亮边，轴向厚 <b>0.08</b>、比管壁外扩 0.02；</li>
     *   <li><b>1 内亮芯</b>：口内一块 0.26×0.26、厚 0.06 的紫光板（"这里在发射"）；</li>
     *   <li><b>2 格栅</b>：口面上一横一纵两条 0.04 厚的薄筋。</li>
     * </ol>
     * 件数 = 3 × (4+4+1+2) = <b>33</b>；加上面盘环 12 + 支撑臂 6 = <b>51 件</b>（见上方总账）。
     *
     * <p>⚠️ 所有件的<b>轴向厚度</b>都 ≤0.12 格（薄板/薄环/格栅口径），并且都排在
     * y ∈ [4.95, 5.36]，比亮核前端（5.61）还靠里 ⇒ 模型的包围盒<b>一格没长</b>。
     */
    public static List<MdBox> mdEmitterBoxes() {
        final List<MdBox> out = new ArrayList<>(64);
        // ---- ① 面盘环：3 个口共同的安装面（薄环，轴向厚 0.10）----
        //  R = THIN_BEAM_RING_R = 1.90；12 件 ⇒ 弦长 2·1.9·sin(15°) = 0.983 < 件宽 1.00 ⇒ 闭合无竖缝
        mdRing(out, 12, THIN_BEAM_RING_R, 4.98F, 15.0F, 0.50F, 0.05F, 0.13F, MD_SHELL_DARK);
        // ---- ② 面盘支撑臂 6 件：把面盘连到喉环/唇环上（60° 一件，错开三个口 30°）----
        for (int i = 0; i < 6; i++) {
            final double aDeg = 30.0D + 60.0D * i;
            final double a = Math.toRadians(aDeg);
            mdBox(out, (float) (1.74D * Math.cos(a)), 4.98F, (float) (1.74D * Math.sin(a)),
                    0.07F, 0.05F, 0.16F, (float) (90.0D - aDeg), MD_SHELL);
        }
        // ---- ③ 3 个发射器（每个 11 件），位置取"垂直于主柱的那 3 个口心"的塔本地坐标 ----
        for (int j = 0; j < THIN_BEAMS_PER_MD; j++) {
            final float[] p = mdEmitterPortLocal(j);
            emitterOne(out, j, p[0], p[1], p[2]);
        }
        return out;
    }

    /**
     * 一个发射器的 11 件（塔本地坐标），口中心 = {@code (px, py, pz)}（= {@link #mdEmitterPortLocal(int)}）。
     *
     * <p>局部记号（用<b>名义</b>方位角 φ 取）：{@code e} = 径向单位向量（由轴指向口）、{@code t} = 切向单位向量。
     * 方管外半宽 0.26、壁厚 0.12、轴向 0.34 高；管中心与口中心重合。
     */
    private static void emitterOne(List<MdBox> out, int j, float px, float py, float pz) {
        final double aDeg = THIN_BEAM_PORT_PHASE_DEG[Math.floorMod(j, THIN_BEAMS_PER_MD)];
        final double a = Math.toRadians(aDeg);
        final float yaw = (float) (90.0D - aDeg);              // hx 沿切向、hz 沿径向（与 mdRing 同口径）
        final float ex = (float) Math.cos(a);
        final float ez = (float) Math.sin(a);
        final float tx = (float) -Math.sin(a);
        final float tz = (float) Math.cos(a);
        final float y0 = py - 0.15F;                            // 方管轴向中心（口心下方 0.15）
        final float yLip = py + 0.04F;
        final float yGlow = py - 0.14F;
        final float yGrid = py - 0.02F;
        // ---- 4 侧壁：两片"法向沿径向" + 两片"法向沿切向"（拼成一根方管，四角闭合）----
        //  🔴 2026-10-25 自看后的调整：管壁原来用 MD_SHELL_DARK(0.38/0.43/0.51)，在暗色面盘上
        //     跟背景糊在一起、近距离也读不出"这是根管子" ⇒ 换成冷白钢 MD_RIM、唇沿换成 MD_STEEL，
        //     并把管高从 0.34 提到 0.40 ⇒ 三个口在喷口面上是三个**亮的小凸台**（剪影可数）。
        for (int s = -1; s <= 1; s += 2) {
            // 法向沿径向：整体沿 e 平移 0.20
            mdBox(out, px + ex * 0.20F * s, y0, pz + ez * 0.20F * s,
                    0.28F, 0.20F, 0.06F, yaw, MD_RIM);
            // 法向沿切向：整体沿 t 平移 0.20（yaw +90° ⇒ hx 改沿径向）
            mdBox(out, px + tx * 0.20F * s, y0, pz + tz * 0.20F * s,
                    0.22F, 0.20F, 0.06F, yaw + 90.0F, MD_RIM);
            // ---- 唇沿：管口那一圈亮边（轴向厚 0.08、外扩 0.02）----
            mdBox(out, px + ex * 0.22F * s, yLip, pz + ez * 0.22F * s,
                    0.30F, 0.04F, 0.06F, yaw, MD_STEEL);
            mdBox(out, px + tx * 0.22F * s, yLip, pz + tz * 0.22F * s,
                    0.24F, 0.04F, 0.06F, yaw + 90.0F, MD_STEEL);
        }
        // ---- 内亮芯：口内一块紫光板（厚 0.06，比口内净宽 0.28 略小 ⇒ 不会穿出管壁）----
        mdBox(out, px, yGlow, pz, 0.13F, 0.03F, 0.13F, yaw, MD_PORT_GLOW);
        // ---- 2 道格栅：口面上一横一纵的薄筋（厚 0.04）----
        mdBox(out, px, yGrid, pz, 0.21F, 0.02F, 0.025F, yaw, MD_GRILLE);
        mdBox(out, px, yGrid, pz, 0.025F, 0.02F, 0.21F, yaw, MD_GRILLE);
    }

    /** 发射器模块的件数（日志/自检用；应为 51）。 */
    public static int mdEmitterBoxCount() {
        return mdEmitterBoxes().size();
    }

    /** 前部模块的件数（日志/自检用）。 */
    public static int mdFrontBoxCount() {
        return mdFrontBoxes().size();
    }

    /**
     * 前部模块的<b>径向范围</b>（格，相对环心）：{@code {rMin, rMax}}。
     *
     * <p>判据：必须覆盖内环带（{@code 48 ± 1.1} = 46.9..49.1），否则摘掉的那段就露出窟窿。
     */
    public static float[] mdFrontRadialRange() {
        float rMin = Float.MAX_VALUE;
        float rMax = -Float.MAX_VALUE;
        for (MdBox b : mdFrontBoxes()) {
            for (int iy = 0; iy < 2; iy++) {
                final float y = b.y() + (iy == 0 ? -b.hy() : b.hy());
                final float r = MD_CENTER_R - y;
                rMin = Math.min(rMin, r);
                rMax = Math.max(rMax, r);
            }
        }
        return new float[]{rMin, rMax};
    }

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
     * <p>塔本地轴系：<b>+X = 横向、+Y = 轴向（+ = 喷口/朝黑洞那一端）、+Z = 环心本地的"上"</b>。
     * 每个分件的半长 ≤ 0.5 ⇒ <b>单件 ≤ 1 格</b>（{@link #mdBox} 里当场校验）。
     *
     * <h2>🔴 2026-10-25 第三改：80 件 → 260+ 件（用户「还是比较粗糙，做更精细一点，模仿群星的建模」）</h2>
     *
     * <h3>取证：群星那台到底由什么组成</h3>
     * 反编译 mesh 渲染（{@code temp/stellaris-decompressor/png/geometry_iso.png}，760×760，
     * 3 个子网格 / 13 959 顶点 / 12 367 三角，整体 68.9 × 64.1 × 60.0 模型单位）里，
     * 我看出来的构件是这 7 类（每一类都在下面有对应的件）：
     * <ol>
     *   <li><b>分段锥形鼓身</b>：上宽下窄、<b>一块块面板拼出来</b>，面板之间有竖缝；</li>
     *   <li><b>凸出的箍</b>：3~4 道完整钢箍，箍上还有等距的<b>卡扣/铆钉</b>；</li>
     *   <li><b>橙光槽</b>：箍与鼓面之间那道<b>凹进去的缝</b>里透出橙色；</li>
     *   <li><b>顶盖 + 三片弯鳍</b>（像獠牙/天线，向上外翻）；</li>
     *   <li><b>底部的笼式格栅</b>：一圈竖筋 + 内环 + 两层横环，像"反应堆护笼"；</li>
     *   <li><b>侧挂模块</b>：带支架的小舱 + 橙灯，舱体与母体之间有间隙；</li>
     *   <li><b>几圈"脱开本体的浮环"</b>（原图左侧那一串圆环 + 带方框的盘）。</li>
     * </ol>
     *
     * <h3>落到我们这 299 件上（逐段列出，括号里是件数，实测 {@link #mdBoxCount()} 逐项对得上）</h3>
     * <pre>
     *   A 尾段/护笼   A1 尾法兰环 8 + A2 尾钢圈 8 + A3 散热竖筋 12 + A4 内格栅环 8 + A5 尾锥环 8 = 44
     *   B 鼓身       5 段 × 12 件切向薄壳（R 2.10→1.90 逐段收）= 60
     *   C 段缝竖棱   4 道 × 6 件 = 24
     *   D 钢箍       4 道 × 10 件 = 40 + 卡扣 4 道 × 3 件 = 12 → 52
     *   E 橙光槽     2 段 × 10 件 = 20
     *   F 喷口段     收口 10 + 唇环 10 + 喉环 10 + 导流片 4 + 喷口散热片 8 + 亮核 1 = 43
     *   G 侧挂模块   4 组 × 3 件（舱体 + 支架 + 橙灯）= 12
     *   H 浮环       2 圈 × 10 件 = 20
     *   I 斜撑       2 排 × 6 件 = 12
     *   J 缝合夹具   钳口 2×3 + 立柱 2×3 = 12（咬住内环带最外那一行，见下面"连在一起"）
     *   ------------------------------------------------------------------
     *   合计 = 299 件（≥160 达标，单件最大半长 0.50 格）
     * </pre>
     *
     * <h3>🔴「解压机与内环看上去连在一起」怎么做到的（用户 2026-10-25 追加口径）</h3>
     * 用户后来又收紧了口径：「<b>解压机也不要做太大，就原来那个大小就挺好的，目的是要做精细</b>」
     * ⇒ <b>体量一格没放大</b>（实测 6.22 × 7.85 × 5.77，径向 48.79..56.64，
     * 都在原来的红框 46.5..57.5 / 团 47..57 之内），而且<b>内环的渲染格一格都没摘</b>
     * （{@code hideTargets()} 仍是 2120 格，见报告里的"改前/改后"两行）。
     * 连接靠两件事：
     * <ol>
     *   <li><b>J 段缝合夹具</b>：塔本地 y = 3.55 ⇒ 环心半径 {@code 52.5 − 3.55 = 48.95} 格，
     *       正好落在内环带最外那一行（{@code r = 49} 的格子占 48.5..49.5）；
     *       上下两片钳口夹住环带的竖直厚度（本地 z −2.35 / +1.35，环带本身占 −2.0..1.0）⇒
     *       夹具与环带<b>互相咬住</b>，缝里不透光；</li>
     *   <li><b>喷口本来就已经贴住环带</b>：喉环/亮核在 {@code r = 49.25}，
     *       与环带外沿（49.5）重叠约 0.25 格 ⇒ "插进环里"而不是浮在环外。</li>
     * </ol>
     * 两条都不动结构方块、不动台账。
     */
    public static List<MdBox> matterDecompressorBoxes() {
        final List<MdBox> out = new ArrayList<>(MD_MAX_BOXES);
        // ═══════════════════════════════════════════════════════════════════════════════════
        //  2026-10-25 第四改（用户退回 B 面板后重做）：**薄板化 + 表面连续 + 剪影分段**
        //
        //  用户原话：「一堆半格灰方块随机堆出来的团块……机壳没连续面、箍是方块串、
        //             喷口的锥度/唇环/喉部被碎块淹没」⇒ 三条硬指标：
        //    ① 薄板：机壳板厚 ≤0.18（hz 0.09）、箍环厚 ≤0.12（hy 0.06）、管口薄壁环（hz 0.05）；
        //    ② 表面连续：相邻壳段的**半径台阶 ≤0.10**（下面 profile 逐段写死）；
        //    ③ 剪影分段可读：鼓身（桶形）→ 前端 4 阶锥形喷口 → 后端 3 道笼环 + 竖肋。
        //  尺寸（塔本地）：横向/上下 ±2.72、轴向 y −4.06..3.62 ⇒ 径向 r 48.88..56.56，
        //  **比原来那版（±3.11 / 48.79..56.64）还小**，一格都没放大。
        // ═══════════════════════════════════════════════════════════════════════════════════

        // ---- ① 鼓身：10 个 0.5 格高的"带"，其中 2 个是橙光槽（凹进去 0.04），其余 8 个是机壳 ----
        //  profile：桶形（中间最粗 2.53、两端收），相邻段半径台阶 = 0.10/0.08/0.06/0.07/0.01/0.06/0.10/0.10
        //  ⚠️ 每件 hz = 0.09 ⇒ **板厚 0.18**；hy = 0.25 ⇒ 0.5 高，相邻板正好接上 ⇒ 表面连续
        // 每圈 16 件（不是 14）：弦长 = 2·R·sin(π/16)；R=2.53 ⇒ 0.986 < 件宽 1.00 ⇒ **闭合**。
        // ⚠️ 14 件时弦长 1.126 > 1.00 ⇒ 每两件之间留 0.126 格的缝（自看侧视图时抓到的两道黑线就是这个）
        final int shellN = 16;
        final float[] bandY = {-2.50F, -2.00F, -1.50F, -1.00F, -0.50F, 0.00F, 0.50F, 1.00F, 1.50F, 2.00F};
        final float[] bandR = {2.22F, 2.32F, 2.40F, 2.46F, 2.48F, 2.53F, 2.52F, 2.46F, 2.36F, 2.26F};
        final boolean[] bandIsSlot = {false, false, false, false, true, false, false, true, false, false};
        for (int i = 0; i < bandY.length; i++) {
            if (bandIsSlot[i]) {
                // 橙光槽：**凹进**壳面 0.04（hz 0.07、中心 R−0.02），不是凸出来的一圈块
                mdRing(out, shellN, bandR[i] - 0.02F, bandY[i], 0.0F, 0.50F, 0.25F, 0.07F, MD_LIGHT);
            } else {
                mdRing(out, shellN, bandR[i], bandY[i], 0.0F,
                        0.50F, 0.25F, 0.09F, MD_SHELL);
            }
        }
        // ---- ② 钢箍 3 道：**薄环**（轴向厚 0.12、径向只高出壳面 0.10）----
        //  箍中心 R = 该处壳面 R + 0.14，hz 0.05 ⇒ 内壁正好贴住壳面（壳外表面 = R + 0.09）、外沿 R + 0.19
        final float[] hoopY = {-1.75F, 0.25F, 1.75F};
        final float[] hoopR = {2.43F + 0.14F, 2.525F + 0.14F, 2.31F + 0.14F};
        for (int h = 0; h < hoopY.length; h++) {
            mdRing(out, shellN, hoopR[h], hoopY[h], h * 4.0F, 0.50F, 0.06F, 0.05F, MD_STEEL);
        }
        // ---- ③ 前端：🔴 2026-10-25 第五改 —— 整段抽成可复用的 {@link #mdFrontBoxes()} ----
        //  原先是写死在这里的"4 阶锥环 + 1.38 钢环 + 1.15 喉环 + 格栅 + 亮核"（共 91 件，y 2.30..3.25）。
        //  现在整段搬到 mdFrontBoxes()（109 件，y 3.00..5.45），因为它有两个用处：
        //    ① 解压机自己的前端；② 填内环被摘掉的那一段（用户第 4 条：红框处别露内环玻璃）。
        //  ⇒ 前部整体外移 0.7~2.2 格、穿过内环带（r 49.50 → 46.89），主光柱起点因此从 49.25 进到 47.05。
        out.addAll(mdFrontBoxes());
        // ---- ④ 后端笼式尾段：收口环 + 2 道笼环 + 8 根竖肋 + 尾盖（全是薄件）----
        mdRing(out, shellN, 2.02F, -2.80F, 0.0F, 0.50F, 0.12F, 0.10F, MD_SHELL_DARK);
        mdRing(out, 12, 1.84F, -3.22F, 0.0F, 0.48F, 0.05F, 0.05F, MD_GRILLE);
        mdRing(out, 12, 1.60F, -3.72F, 6.0F, 0.44F, 0.05F, 0.05F, MD_GRILLE);
        for (int i = 0; i < 8; i++) {
            final double a = Math.toRadians(360.0D * i / 8.0D + 22.5D);
            mdBox(out, (float) (1.72D * Math.cos(a)), -3.42F, (float) (1.72D * Math.sin(a)),
                    0.05F, 0.50F, 0.13F, (float) (90.0D - 360.0D * i / 8.0D - 22.5D), MD_GRILLE);
        }
        mdRing(out, 8, 1.02F, -4.00F, 0.0F, 0.40F, 0.06F, 0.12F, MD_SHELL_DARK);
        // ---- ⑤ 浮环：🔴 自看后删（它在正交侧视里是横贯鼓身的一条暗线，读作"划痕"）----
        //   （群星那台的浮环是"大而稀疏、离本体很远"的三圈；同样的东西塞进 5 格鼓身里只会变噪点）
        // ---- ⑥ 侧舱 2 组（薄支架 + 小橙灯；贴面，不往外支棱）----
        for (int sx = -1; sx <= 1; sx += 2) {
            mdBox(out, sx * 2.56F, -1.60F, 0.0F, 0.10F, 0.24F, 0.22F, 0.0F, MD_SHELL_DARK);
            mdBox(out, sx * 2.70F, -1.60F, 0.0F, 0.06F, 0.10F, 0.10F, 0.0F, MD_LIGHT);
        }
        // ---- ⑦ 缝合夹具 12 件：咬住内环带最外那一行（y=3.55 ⇒ r=48.95），见类注释 ----
        for (int sz = -1; sz <= 1; sz += 2) {
            final float z = sz > 0 ? 1.35F : -2.35F;
            for (int k = -1; k <= 1; k++) {
                mdBox(out, k * 1.15F, 3.55F, z, 0.50F, 0.14F, 0.10F, 0.0F, MD_STEEL);
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int k = -1; k <= 1; k++) {
                mdBox(out, sx * 2.42F, 3.30F, k * 1.15F, 0.10F, 0.30F, 0.50F, 0.0F, MD_SHELL_DARK);
            }
        }
        // ---- ⑧ 发射器 51 件（🔴 第七改）：喷口面上那 3 个"细紫光束从这里射出"的口 ----
        //   面盘环 12 + 支撑臂 6 + 3 × 11 = 51 件；全部排在 y ∈ [4.93, 5.36]，
        //   比亮核前端（5.61）还靠里 ⇒ 包围盒一格没长（判据见 mdModelBounds）。
        out.addAll(mdEmitterBoxes());
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
     * （{@code mdBox(out, 0.0F, MD_NOZZLE_LOCAL_Y, 0.0F, …)}，F6 那一行）。
     * <b>这是"喷口位置"的唯一来源</b>：4 条主喷口光柱的起点必须由它算出，不许手填坐标。
     *
     * <p>🔴 2026-10-25 第三改：{@code 3.70 → 3.25}（模型重做后喷口整体内移；
     * 喉环在 2.85、亮核在 3.25，亮核略探出喉口一点，主光柱从这里出发）。
     *
     * <p>🔴 2026-10-25 第五改：{@code 3.25 → 5.45}（前部整段外移、穿过内环带，见 {@link #mdFrontBoxes()}）：
     * <pre>
     *   改前  亮核 y=3.25 ⇒ 喷口 r = 52.5 − 3.25 = <b>49.25</b> 格（停在内环带外沿 49.5 之内 0.25）
     *   改后  亮核 y=5.45 ⇒ 喷口 r = 52.5 − 5.45 = <b>47.05</b> 格（插进内环带厚度里，带内沿 46.5）
     * </pre>
     * ⇒ 4 条主喷口光柱的起点跟着从 49.25 进到 <b>47.05</b>（同一个 {@link #mdNozzle(int)}，
     * 判据 {@link #selfCheckNozzleBeam()} 仍然要求"光柱起点 == 喷口中心"，容差 1e-5）。
     *
     * <p>改这里时喷口与光柱会<b>一起</b>动（{@link #mdNozzle(int)} 与
     * {@link #buildBeamQuads} 用的是同一个函数），不会出现"机器挪了、光柱还在原地"。
     */
    public static final float MD_NOZZLE_LOCAL_Y = 5.45F;

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
     * 半径 = {@code MD_CENTER_R − MD_NOZZLE_LOCAL_Y = 52.5 − 3.25 = 49.25} 格，
     * 高度 = {@code MD_CENTER_Y = 0}（正好在环面上）⇒ 4 条主光柱从 49.25 格处
     * 水平射向黑洞表面，方向就是该机器的方位角。
     */
    public static float[] mdNozzle(int i) {
        // 2026-10-25 第七改：改成走 mdLocalToRing（与"发射器口中心"共用同一次变换），
        // 数学上与第五改那套手写式逐项相同（判据：selfCheckNozzleBeam 仍要求光柱起点 == 喷口）。
        return mdLocalToRing(i, 0.0F, MD_NOZZLE_LOCAL_Y, 0.0F);
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

    /**
     * <b>自检：件数 / 单件尺寸 / 装得下红框</b>（要求 ② 的三条判据）。
     *
     * <p>为什么要把"装得下红框"也算进来自检：模型一改件数就变，很容易悄悄长到
     * 红框（外沿 11.00 × 11.00 × 11.00 格、团心 (52,10,0)、径向 47..57）外面去。
     * 这里按<b>横置之后</b>的口径算：本地 +Z → 环心本地 y，本地 +Y → −径向。
     *
     * @return {@code null} = 通过；否则是一行人话
     */
    public static String selfCheckMdParts() {
        final int n = mdBoxCount();
        if (n < MD_MIN_BOXES) {
            return "件数 " + n + " < 下限 " + MD_MIN_BOXES + "（用户要求 ≥160 件）";
        }
        if (n > MD_MAX_BOXES) {
            return "件数 " + n + " > 预算上限 " + MD_MAX_BOXES;
        }
        final float half = mdMaxHalf();
        if (half > 0.5F + 1.0E-6F) {
            return "单件最大半长 " + half + " > 0.5（单件必须 ≤1 格）";
        }
        // 横置 −90° 之后的三个方向：
        //   横向 = 本地 x ；上下 = 本地 z ；径向 = MD_CENTER_R − 本地 y
        float lat = 0.0F;
        float vert = 0.0F;
        float rMin = Float.MAX_VALUE;
        float rMax = -Float.MAX_VALUE;
        for (MdBox b : matterDecompressorBoxes()) {
            final double th = Math.toRadians(b.yawDeg());
            final float c = (float) Math.cos(th);
            final float s = (float) Math.sin(th);
            for (int ix = 0; ix < 2; ix++) {
                for (int iy = 0; iy < 2; iy++) {
                    for (int iz = 0; iz < 2; iz++) {
                        final float lx = (ix == 0 ? -b.hx() : b.hx());
                        final float lz = (iz == 0 ? -b.hz() : b.hz());
                        final float wy = b.y() + (iy == 0 ? -b.hy() : b.hy());
                        final float wx = b.x() + lx * c + lz * s;
                        final float wz = b.z() - lx * s + lz * c;
                        lat = Math.max(lat, Math.abs(wx));
                        vert = Math.max(vert, Math.abs(wz));
                        final float r = MD_CENTER_R - wy;
                        rMin = Math.min(rMin, r);
                        rMax = Math.max(rMax, r);
                    }
                }
            }
        }
        if (lat > 5.5F || vert > 5.5F || rMin < 46.5F || rMax > 57.5F) {
            return "超出红框：横向 ±" + lat + "（限 5.5）/ 上下 ±" + vert + "（限 5.5）/ 径向 "
                    + rMin + ".." + rMax + "（限 46.5..57.5）";
        }
        return null;
    }

    /**
     * <b>自检：内环摘除段 + 前部模块盖得住</b>（用户第 4 条的三条判据）。
     *
     * <p>① 摘除段非空、且<b>每一格都真的在台账里</b>（不然等于"摘了个不存在的东西"，而真正挡在
     * 机器前面的那几格还留着）；② 4 个方位各一段、每段格数相同（旋转对称）；③ 前部模块的径向范围
     * <b>必须覆盖内环带</b>（46.9..49.1），否则摘掉的位置会露出窟窿。
     *
     * @return {@code null} = 通过；否则是一行人话
     */
    public static String selfCheckMouthCut() {
        final List<BlockPos> cut = mdMouthCutCells();
        if (cut.isEmpty()) {
            return "摘除段为空 —— 内环那一段不会被摘掉（用户第 4 条没做到）";
        }
        final java.util.Set<Long> ledger = new java.util.HashSet<>();
        for (BlockPos p : hideTargets()) {
            ledger.add(p.asLong());
        }
        int[] perDir = new int[4];
        for (BlockPos p : cut) {
            if (!ledger.contains(p.asLong())) {
                return "摘除格 " + p + " 不在台账里（世界里那块不会被藏成 AIR）";
            }
            final int dir = Math.abs(p.getX()) > Math.abs(p.getZ())
                    ? (p.getX() > 0 ? 0 : 1) : (p.getZ() > 0 ? 2 : 3);
            perDir[dir]++;
        }
        for (int i = 1; i < 4; i++) {
            if (perDir[i] != perDir[0]) {
                return "4 个方位的摘除格数不等（" + java.util.Arrays.toString(perDir) + "）⇒ 旋转不对称";
            }
        }
        final float[] fr = mdFrontRadialRange();
        final float bandInner = (float) (INNER_R - INNER_TOLERANCE);
        final float bandOuter = (float) (INNER_R + INNER_TOLERANCE);
        if (fr[0] > bandInner || fr[1] < bandOuter) {
            return "前部模块径向 " + fr[0] + ".." + fr[1] + " 没有盖住内环带 "
                    + bandInner + ".." + bandOuter + "（摘掉的位置会露窟窿）";
        }
        return null;
    }

    /**
     * <b>自检：12 条细光束必须"围住每座解压机、每座 3 条、互成 120°、方向与该座主柱 0°"</b>
     * （本轮用户口径的判据，逐条可机器验）。
     *
     * <ol>
     *   <li>条数 = <b>12</b> = 4 座 × <b>3</b> 条（{@link #THIN_BEAMS_PER_MD}）；</li>
     *   <li><b>起点判据</b>：每条的起点 == 该座第 j 个<b>发射器口中心</b>
     *       {@link #mdEmitterPortCenter(int, int)}（容差 1e-5）⇒ "线是从这 3 个口射出来的"；</li>
     *   <li><b>方向判据</b>：每条的方向与<b>该座主喷口光柱</b>方向 {@link #mdBeamDirection(int, float)}
     *       的夹角 = <b>0.000000°</b>（点积 = 1，容差 1e-6）；</li>
     *   <li><b>120° 判据</b>：同一座的 3 条，起点相对本座喷口轴线的<b>垂向偏移向量</b>两两夹角
     *       恒为 <b>120.000°</b>（容差 1e-3°）—— 这个量<b>不依赖任何自定义基</b>，是纯几何；</li>
     *   <li><b>半径判据</b>：每条起点到本座喷口轴线的垂距 = {@link #THIN_BEAM_RING_R} = 1.90 格；</li>
     *   <li><b>长度判据</b>：每条终点与本座主柱终点<b>共面</b>（都投影到同一处，容差 1e-3）；</li>
     *   <li>颜色仍是紫（蓝 &gt; 红 &gt; 绿）；峰值半径仍是 {@link #THIN_BEAM_PEAK_RADIUS} = 0.10。</li>
     * </ol>
     *
     * @return {@code null} = 通过；否则是一行人话
     */
    public static String selfCheckThinBeams() {
        final float tipR = tipRadius(HOLE_SURFACE_R);
        final float[][] e = thinBeamEndpoints(HOLE_SURFACE_R);
        if (e.length != THIN_BEAM_COUNT) {
            return "条数 " + e.length + " ≠ " + THIN_BEAM_COUNT;
        }
        if (THIN_BEAM_COUNT != 12 || THIN_BEAMS_PER_MD != 3) {
            return "条数口径不对：" + THIN_BEAM_COUNT + " 条 / 每座 " + THIN_BEAMS_PER_MD
                    + " 条（用户口径：每座 3 条、4 座共 12 条）";
        }
        for (int k = 0; k < e.length; k++) {
            final int md = k / THIN_BEAMS_PER_MD;
            final int port = k % THIN_BEAMS_PER_MD;
            // ② 起点 == 发射器口中心
            final float[] pc = mdEmitterPortCenter(md, port);
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(e[k][axis] - pc[axis]) > 1.0E-5F) {
                    return "细光束#" + k + "（解压机#" + md + " 口#" + port + "）起点轴 " + axis
                            + " 与发射器口中心差 " + (e[k][axis] - pc[axis]);
                }
            }
            // ③ 方向 == 该座主柱方向（夹角 0°）
            final float[] want = mdBeamDirection(md, tipR);
            float dx = e[k][3] - e[k][0];
            float dy = e[k][4] - e[k][1];
            float dz = e[k][5] - e[k][2];
            final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-4F) {
                return "细光束#" + k + " 长度为 0";
            }
            dx /= len;
            dy /= len;
            dz /= len;
            final float dot = dx * want[0] + dy * want[1] + dz * want[2];
            if (Math.abs(dot - 1.0F) > 1.0E-6F) {
                final float ang = (float) Math.toDegrees(
                        Math.acos(Math.max(-1.0F, Math.min(1.0F, dot))));
                return "细光束#" + k + " 与解压机#" + md + " 主柱夹角 " + ang + "° ≠ 0";
            }
            // ⑥ 终点与本座主柱终点共面：投影长度相等
            final float[] be = mdBeamEndpoints(md, tipR);
            final float mainLen = (float) Math.sqrt(sq(be[3] - be[0]) + sq(be[4] - be[1]) + sq(be[5] - be[2]));
            final float thinProj = (e[k][3] - be[0]) * want[0] + (e[k][4] - be[1]) * want[1]
                    + (e[k][5] - be[2]) * want[2];
            if (Math.abs(thinProj - mainLen) > 1.0E-3F) {
                return "细光束#" + k + " 终点投影 " + thinProj + " ≠ 本座主柱长度 " + mainLen
                        + "（应共面收在洞口）";
            }
        }
        // ④ 120°（用垂向偏移向量的夹角，不依赖任何基）+ ⑤ 半径 1.90
        for (int md = 0; md < MD_COUNT; md++) {
            final float[] axis = mdBeamDirection(md, tipR);
            final float[][] off = new float[THIN_BEAMS_PER_MD][3];
            for (int j = 0; j < THIN_BEAMS_PER_MD; j++) {
                final float[] s = e[md * THIN_BEAMS_PER_MD + j];
                final float[] base = mdNozzle(md);
                final float[] rel = {s[0] - base[0], s[1] - base[1], s[2] - base[2]};
                final float along = rel[0] * axis[0] + rel[1] * axis[1] + rel[2] * axis[2];
                off[j][0] = rel[0] - axis[0] * along;
                off[j][1] = rel[1] - axis[1] * along;
                off[j][2] = rel[2] - axis[2] * along;
                final float r = (float) Math.sqrt(sq(off[j][0]) + sq(off[j][1]) + sq(off[j][2]));
                // 容差 5e-3 不是含糊：发射口面比亮核退 0.15 格，而主柱方向比"塔本地 +Y"高
                // RADIAL_BEAM_LIFT / 47 ≈ 0.73°（洞端抬了 0.6 格）⇒ 垂距天然比 1.90 多
                // 0.15 × sin(0.73°) ≈ 0.0019 格（实测 1.9019549）。这不是误差，是几何事实。
                if (Math.abs(r - THIN_BEAM_RING_R) > 5.0E-3F) {
                    return "解压机#" + md + " 口#" + j + " 离本座轴 " + r + " ≠ 圆周半径 "
                            + THIN_BEAM_RING_R;
                }
            }
            for (int j = 0; j < THIN_BEAMS_PER_MD; j++) {
                final float[] a = off[j];
                final float[] b = off[(j + 1) % THIN_BEAMS_PER_MD];
                final float dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2])
                        / (float) Math.sqrt((sq(a[0]) + sq(a[1]) + sq(a[2]))
                        * (sq(b[0]) + sq(b[1]) + sq(b[2])));
                final float ang = (float) Math.toDegrees(
                        Math.acos(Math.max(-1.0F, Math.min(1.0F, dot))));
                if (Math.abs(ang - 120.0F) > 1.0E-3F) {
                    return "解压机#" + md + " 口#" + j + " 与口#" + ((j + 1) % THIN_BEAMS_PER_MD)
                            + " 的夹角 " + ang + "° ≠ 120°";
                }
            }
        }
        final float[] c = THIN_BEAM_COLOR;
        if (!(c[2] > c[0] && c[0] > c[1])) {
            return "细光束颜色不是紫（r=" + c[0] + " g=" + c[1] + " b=" + c[2] + "）";
        }
        if (Math.abs(THIN_BEAM_PEAK_RADIUS - 0.10F) > 1.0E-6F) {
            return "细光束峰值半径 " + THIN_BEAM_PEAK_RADIUS + " ≠ 0.10（用户口径：引导用、细）";
        }
        return null;
    }

    /**
     * <b>自检：4 条解压机主喷口光柱 = 伪神同款半径口径 + 颜色可配 + 端点与喷口/洞面对上</b>
     * （本轮第 2 条口径的判据）。
     *
     * <p>判据：
     * <ol>
     *   <li>外层半径落在父口径给的 <b>1.2~1.6 格</b>（{@link #MD_BEAM_OUTER_R0}/{@link #MD_BEAM_OUTER_R1}），
     *       且比中央那根（{@link #PILLAR_OUTER_R0} = 6.20）细；</li>
     *   <li>洞端比喷口端粗（<b>往洞里涨</b>，与中央主柱同一个观感方向）；</li>
     *   <li>内芯倍数 == 中央那根（{@link #PILLAR_INNER_SCALE}）⇒ "同一套"；</li>
     *   <li>颜色可配：{@link #setMdBeamGlowRgb} 之后 {@link #mdBeamGlowRgb()} 跟着变、且
     *       <b>不影响</b>中央那根的 {@link #jetGlowRgb()}（两个独立开关）；</li>
     *   <li>4 条的起点 == {@link #mdNozzle(int)}、终点径向 == {@link #tipRadius}(洞面)。</li>
     * </ol>
     *
     * @return {@code null} = 通过；否则是一行人话
     */
    public static String selfCheckMdBeams() {
        if (!(MD_BEAM_OUTER_R0 >= 1.2F && MD_BEAM_OUTER_R0 <= 1.6F
                && MD_BEAM_OUTER_R1 >= 1.2F && MD_BEAM_OUTER_R1 <= 1.6F)) {
            return "外层半径 " + MD_BEAM_OUTER_R0 + "→" + MD_BEAM_OUTER_R1 + " 不在 1.2~1.6 区间";
        }
        if (MD_BEAM_OUTER_R1 <= MD_BEAM_OUTER_R0) {
            return "洞端半径 " + MD_BEAM_OUTER_R1 + " 没有比喷口端 " + MD_BEAM_OUTER_R0 + " 粗";
        }
        if (MD_BEAM_OUTER_R0 >= PILLAR_OUTER_R0) {
            return "解压机主柱 " + MD_BEAM_OUTER_R0 + " 不比中央主光柱 " + PILLAR_OUTER_R0
                    + " 细（用户口径：它比中央那根细）";
        }
        if (Math.abs(MD_BEAM_INNER_SCALE - PILLAR_INNER_SCALE) > 1.0E-6F) {
            return "内芯倍数 " + MD_BEAM_INNER_SCALE + " ≠ 中央主光柱的 " + PILLAR_INNER_SCALE
                    + "（用户口径：与中央那根同一套）";
        }
        // 颜色可配（改 → 读回 → 还原），并且与中央那根互不影响
        final float[] before = mdBeamGlowRgb();
        final float[] jetBefore = jetGlowRgb();
        setMdBeamGlowRgb(1.0F, 0.5F, 0.25F);
        final float[] after = mdBeamGlowRgb();
        final float[] jetAfter = jetGlowRgb();
        clearMdBeamGlowOverride();
        final float[] restored = mdBeamGlowRgb();
        if (Math.abs(after[0] - 1.0F) > 1.0E-6F || Math.abs(after[1] - 0.5F) > 1.0E-6F
                || Math.abs(after[2] - 0.25F) > 1.0E-6F) {
            return "setMdBeamGlowRgb 之后读回 " + rgbText(after) + " ≠ (255,128,64) ⇒ 颜色配不动";
        }
        if (Math.abs(jetAfter[0] - jetBefore[0]) > 1.0E-6F
                || Math.abs(jetAfter[1] - jetBefore[1]) > 1.0E-6F
                || Math.abs(jetAfter[2] - jetBefore[2]) > 1.0E-6F) {
            return "改解压机主柱的颜色把中央主光柱也改了（两个开关串了）";
        }
        if (Math.abs(restored[0] - before[0]) > 1.0E-6F) {
            return "clearMdBeamGlowOverride 之后没有回到默认色";
        }
        // 端点：起点 == 喷口、终点径向 == 洞面
        final float tipR = tipRadius(HOLE_SURFACE_R);
        final float[][] ends = beamEndpoints(HOLE_SURFACE_R);
        for (int k = 0; k < MD_COUNT; k++) {
            final float[] nz = mdNozzle(k);
            for (int axis = 0; axis < 3; axis++) {
                if (Math.abs(ends[k][axis] - nz[axis]) > 1.0E-5F) {
                    return "主柱#" + k + " 起点轴 " + axis + " 与喷口差 "
                            + (ends[k][axis] - nz[axis]);
                }
            }
            final float endR = (float) Math.hypot(ends[k][3], ends[k][5]);
            if (Math.abs(endR - tipR) > 1.0E-5F) {
                return "主柱#" + k + " 末端径向 " + endR + " ≠ 洞面 " + tipR;
            }
        }
        return null;
    }


    /** 向量读数（日志用）。 */
    public static String vecText(float[] v) {
        return String.format(java.util.Locale.ROOT, "(%.4f,%.4f,%.4f)", v[0], v[1], v[2]);
    }

    /**
     * <b>自检：主光柱（上下两根）同一套 + 半径落在父口径给的区间</b>。
     *
     * @return {@code null} = 通过；否则是一行人话
     */
    public static String selfCheckPillar() {
        // ① 上下两根必须用同一组半径：两端点只差符号
        final float tipR = tipRadius(HOLE_SURFACE_R);
        final float[] up = pillarEndpoints(1, tipR);
        final float[] down = pillarEndpoints(-1, tipR);
        if (Math.abs(up[0] - down[0]) > 1.0E-6F || Math.abs(up[2] - down[2]) > 1.0E-6F
                || Math.abs(up[4] + down[4]) > 1.0E-6F) {
            return "上下两根主光柱端点不对称（up=" + java.util.Arrays.toString(up)
                    + " down=" + java.util.Arrays.toString(down) + "）";
        }
        // ② 半径必须比改前的 0.25 粗，且落在 0.55~0.7（父口径给的区间）
        //    🔴 第七改：这个数现在只是【回落用】自绘管壳的峰值半径（4 条主喷口光柱已改用伪神 shader），
        //    仍然保持原值 —— 回落时"宁可画上一版的观感"。
        if (!(BEAM_PEAK_RADIUS >= 0.55F && BEAM_PEAK_RADIUS <= 0.70F)) {
            return "回落用自绘主柱峰值半径 " + BEAM_PEAK_RADIUS + " 不在 0.55~0.70 区间";
        }
        // ③ 主光柱外层半径必须"靠洞端 > 外端"（锥形收细，与伪神 renderLinear 的 start/end 一致）
        if (!(PILLAR_OUTER_R0 > PILLAR_OUTER_R1)) {
            return "主光柱外层半径没有收细（" + PILLAR_OUTER_R0 + " → " + PILLAR_OUTER_R1 + "）";
        }
        return null;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** 一行摘要（渲染器 {@code draw} 日志用）。 */
    public static String mdSummary() {
        final float[] mb = mdModelBounds();
        return "解压机 4×" + mdBoxCount() + " 件（上限 " + MD_MAX_BOXES + "，其中发射器 "
                + mdEmitterBoxCount() + " 件；单件最大半长 "
                + String.format(java.util.Locale.ROOT, "%.2f", mdMaxHalf()) + " 格）"
                + " 塔本地包围盒 " + String.format(java.util.Locale.ROOT, "%.2f × %.2f × %.2f",
                mb[1] - mb[0], mb[3] - mb[2], mb[5] - mb[4])
                + " 格（横置 " + MD_TILT_DEG + "°，轴线 r=" + MD_CENTER_R
                + " 格，喷口 r=" + String.format(java.util.Locale.ROOT, "%.2f", mdNozzleRadius())
                + " 格、朝环心；光柱起点 == 喷口：" + (selfCheckNozzleBeam() == null)
                + "；细紫 3 条/座 绕本座轴 r=" + THIN_BEAM_RING_R + " 格互成 120°、与主柱夹角 0°："
                + (selfCheckThinBeams() == null) + "）";
    }

    /**
     * 内环带在 {@code seconds} 秒时的自转角（度）= {@code seconds × ringDegPerSec()}。
     *
     * <p>三组角速度（度）：内环 +12、上组 +9、下组 −9 是<b>默认值</b>，三者互不相等（要求 ①）。
     * 🔴 2026-10-25 第十九改起<b>读 getter</b>（玩家面板可调），不再内联常量 ——
     * 常量是编译期内联的，读常量等于"面板改了、环不动"。
     *
     * <p>判据（{@link #selfCheckGroupAngles()} 判<b>默认值</b>，容差 1e-4）：
     * <pre>
     *   ringDeg(t)             == +ringDegPerSec()                 × t   （默认 +12°/s）
     *   shipOrbitDeg(t, true)  == +shipUpDegPerSec()               × t   （默认  +9°/s）
     *   shipOrbitDeg(t, false) == −shipDownDegPerSec()             × t   （默认  −9°/s）
     *   三个数两两不相等 / 上下异号 / 三者各自的绑定缓冲互不共享
     * </pre>
     */
    public static float ringDeg(float seconds) {
        return seconds * ringDegPerSec();
    }

    /** 第 {@code up} 组飞船的公转角（度）；幅值各用各的 getter（见类内"三组独立自转"一节）。 */
    public static float shipOrbitDeg(float seconds, boolean up) {
        return up
                ? seconds * shipUpDegPerSec() * SHIP_UP_SIGN
                : seconds * shipDownDegPerSec() * SHIP_DOWN_SIGN;
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
                                         float an0, float an1, float seconds, float detail) {
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
            final float scale0 = (float) BlackHoleSurfaceWaves.radiusScale(a0, seconds, detail);
            final float scale1 = (float) BlackHoleSurfaceWaves.radiusScale(a1, seconds, detail);
            out.add(new Quad(new float[]{
                    bpx(right, up, dir, ca0, sa0, r0 * scale0, zOff), bpy(right, up, dir, ca0, sa0, r0 * scale0, zOff),
                    bpz(right, up, dir, ca0, sa0, r0 * scale0, zOff),
                    bpx(right, up, dir, ca1, sa1, r0 * scale1, zOff), bpy(right, up, dir, ca1, sa1, r0 * scale1, zOff),
                    bpz(right, up, dir, ca1, sa1, r0 * scale1, zOff),
                    bpx(right, up, dir, ca1, sa1, r1 * scale1, zOff), bpy(right, up, dir, ca1, sa1, r1 * scale1, zOff),
                    bpz(right, up, dir, ca1, sa1, r1 * scale1, zOff),
                    bpx(right, up, dir, ca0, sa0, r1 * scale0, zOff), bpy(right, up, dir, ca0, sa0, r1 * scale0, zOff),
                    bpz(right, up, dir, ca0, sa0, r1 * scale0, zOff),
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

    /** 黑洞<b>黑盘</b>（不透明）：正对相机的完整圆盘，半径 0→{@code surfaceR}。 */
    public static List<Quad> holeDiscQuads(float camX, float camY, float camZ, float surfaceR) {
        return holeDiscQuads(camX, camY, camZ, surfaceR, 0, 0);
    }

    public static List<Quad> holeDiscQuads(float camX, float camY, float camZ, float surfaceR,
                                           float seconds, float detail) {
        final List<Quad> out = new ArrayList<>(HOLE_SEGMENTS);
        final float[][] basis = billboardBasis(camX, camY, camZ);
        if (basis == null) {
            return out;
        }
        addBillboardRing(out, basis, 0, surfaceR, 0.02F, 0, 0, 0, 1, 1, seconds, detail);
        return out;
    }

    /** 黑洞<b>光子环 + 引力光晕</b>（加性、公告板）。 */
    public static List<Quad> holeHaloQuads(float camX, float camY, float camZ, float surfaceR) {
        return holeHaloQuads(camX, camY, camZ, surfaceR, 0, 0);
    }

    public static List<Quad> holeHaloQuads(float camX, float camY, float camZ, float surfaceR,
                                           float seconds, float detail) {
        final List<Quad> out = new ArrayList<>(HOLE_SEGMENTS * 2);
        final float[][] basis = billboardBasis(camX, camY, camZ);
        if (basis == null) {
            return out;
        }
        addBillboardRing(out, basis, surfaceR * 1.00F, surfaceR * 1.22F, 0.02F,
                1, 1, 1, 0.95F, 0.55F, seconds, detail);
        addBillboardRing(out, basis, surfaceR * 1.24F, surfaceR * 2.10F, 0.02F,
                1, 1, 1, 0.34F, 0, seconds, detail);
        return out;
    }

    /** 山海：降級路徑的白色波峰，三條相鄰軟邊帶代替 shader 高斯，不新增貼圖。 */
    public static List<Quad> holeSurfaceWaveQuads(float camX, float camY, float camZ, float surfaceR,
                                                  float seconds, float projectedRadius) {
        final List<Quad> out = new ArrayList<>();
        if (!(projectedRadius > 0)) return out;
        final double detail = BlackHoleSurfaceWaves.detail(1.4 / projectedRadius);
        if (detail <= 0) return out;
        final float[][] basis = billboardBasis(camX, camY, camZ);
        if (basis == null) return out;
        final double width = Math.max(0.0015, 0.8 / projectedRadius);
        for (int wave = -1; wave <= 2; wave++) for (int segment = 0; segment < HOLE_SEGMENTS; segment++) {
            double a0 = segment * Math.PI * 2 / HOLE_SEGMENTS, a1 = (segment + 1) * Math.PI * 2 / HOLE_SEGMENTS;
            double rho0 = BlackHoleSurfaceWaves.crestRadius(wave, a0, seconds);
            double rho1 = BlackHoleSurfaceWaves.crestRadius(wave, a1, seconds);
            double envelope = BlackHoleSurfaceWaves.envelope((rho0 + rho1) * 0.5);
            if (envelope <= 0) continue;
            for (int band = -1; band <= 1; band++) {
                float[] xyz = new float[12];
                for (int vertex = 0; vertex < 4; vertex++) {
                    boolean next = vertex == 1 || vertex == 2;
                    double angle = next ? a1 : a0;
                    double rho = (next ? rho1 : rho0) + (band + (vertex >= 2 ? 0.5 : -0.5)) * width;
                    float radius = (float) (surfaceR * Math.max(0.57, Math.min(0.99, rho)));
                    float ct = (float) Math.cos(angle), st = (float) Math.sin(angle);
                    xyz[vertex * 3] = bpx(basis[0], basis[1], basis[2], ct, st, radius, 0.03F);
                    xyz[vertex * 3 + 1] = bpy(basis[0], basis[1], basis[2], ct, st, radius, 0.03F);
                    xyz[vertex * 3 + 2] = bpz(basis[0], basis[1], basis[2], ct, st, radius, 0.03F);
                }
                out.add(new Quad(xyz, 1, 1, 1, (float) (envelope * detail * (band == 0 ? 0.8 : 0.18))));
            }
        }
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

    /** 黑洞兜底含白邊波峰的四邊形預算上限；實際提交量由渲染器記錄。 */
    public static int holeQuadCount() {
        return HOLE_SEGMENTS * 16;
    }

    /** 绕 Y 轴把方向向量转 {@code deg} 度（与 {@code Axis.YP.rotationDegrees} 同一旋向）。 */
    public static float[] rotateYDeg(float deg, float x, float y, float z) {
        final double th = Math.toRadians(deg);
        final float c = (float) Math.cos(th);
        final float s = (float) Math.sin(th);
        return new float[]{c * x + s * z, y, -s * x + c * z};
    }
}
