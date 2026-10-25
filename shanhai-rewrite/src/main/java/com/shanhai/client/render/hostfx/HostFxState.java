package com.shanhai.client.render.hostfx;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 归墟视界枢核 · <b>客户端特效状态</b>（"机器 → 渲染" 的唯一喂料口）。
 *
 * <h2>它解决的是什么问题</h2>
 * 改造前 {@code client/render/hostfx/**} 里，角速度 / 黑洞半径 / 透镜开关<b>全部</b>读客户端静态常量或
 * 客户端单例（{@code HostFxGeometry.RING_DEG_PER_SEC}、{@code BlackHoleTuning.scale()}、
 * {@code BlackHoleTuning.lensMode()}），<b>一处都不来自机器</b> ⇒ 玩家在主机面板上调什么都改不了画面。
 * 本类就是那条缺口：渲染侧每帧从机器读一次、经 {@link #feed} 写进这里，渲染代码只读本类。
 *
 * <h2>🔴 零 Minecraft 依赖（这是硬约束，不是风格）</h2>
 * 本文件<b>只 import {@code java.util.*}</b>，没有一个 MC / LDLib / GTCEu 类型。
 * 判据（可脱机自检，见 {@code temp/host-ui/报告-状态与面板.md}）：
 * <pre>
 *   javac -d out HostFxState.java        # 不给 -classpath 也能过 ⇒ 证明零 MC 依赖
 * </pre>
 * 因此这里也<b>不能</b>直接用 {@code ShanhaiMod.LOGGER}（那会把整个 MC 拖进来）。
 * 打 WARN 的出口是 {@link #setWarner}：渲染侧在静态块里把 {@code ShanhaiMod.LOGGER::warn} 装进来，
 * 默认实现退回 {@code System.err}（MC 会把 stderr 收进 {@code logs/latest.log}，仍然可 grep）。
 *
 * <h2>为什么要有一份"客户端副本"而不是让渲染侧直接读机器</h2>
 * <ol>
 *   <li>渲染侧只拿得到 {@code GuixuVisionCoreMachine} 这一个类型（见 {@code GuixuHostFxRenderer#render}
 *       的 {@code instanceof}），而画环/画黑洞的那几个类<b>不该</b>反向依赖机器包；</li>
 *   <li>"冻结时钟"必须<b>跨帧</b>记住一个值 —— 那不是机器字段，是客户端自己的状态
 *       （见 {@link #animSeconds()}）。</li>
 * </ol>
 *
 * <h2>值域与默认值（与 {@code GuixuVisionCoreMachine} 的 setter 同一口径）</h2>
 * <pre>
 *   内环   ringDegPerSec   1 .. 60 °/s ，默认 12
 *   上 4 舰 shipUpDegPerSec  0 .. 60 °/s ，默认  9
 *   下 4 舰 shipDownDegPerSec 0 .. 60 °/s ，默认  9（幅值；方向由渲染侧的 −1 负责）
 *   黑洞半径 holeRadiusBlocks 1.0 .. 12.0 格，默认 5.196（= 现行 B_CRIT 2.598076 × RS_BLOCKS 2.0）
 * </pre>
 * <b>为什么下舰这里是正数</b>：机器字段 {@code hostShipDownDegPerSec} 存的是<b>玩家看到的正数</b>
 * （面板也显示正数），负号是渲染侧 {@code HostFxGeometry.shipOrbitDeg(t, false)} 里那个
 * {@code SHIP_DOWN_SIGN = −1} 的事。两处口径<b>必须</b>一致，否则面板写 9 而画面转 −9 会互相打架。
 */
public final class HostFxState {

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  值域（每一条都与机器侧 setter 的钳位逐位相同；改这里必须同步改机器侧）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 内环角速度下限（°/s）。 */
    public static final float RING_MIN_DEG_PER_SEC = 1.0F;
    /** 内环角速度上限（°/s）。 */
    public static final float RING_MAX_DEG_PER_SEC = 60.0F;
    /** 飞船舱组角速度下限（°/s）—— 允许 0（= 那一组不转）。 */
    public static final float SHIP_MIN_DEG_PER_SEC = 0.0F;
    /** 飞船舱组角速度上限（°/s）。 */
    public static final float SHIP_MAX_DEG_PER_SEC = 60.0F;
    /** 黑洞半径下限（格）= 10 × 0.1 格。 */
    public static final float HOLE_MIN_BLOCKS = 1.0F;
    /**
     * 黑洞半径上限（格）= <b>12.0</b> = 120 × 0.1 格。
     *
     * <p>🔴 这个上限不是设计值，是<b>被渲染侧的现有 clamp 钉住的</b>：
     * {@code HostFxBlackHole.surfaceRadius()}（{@code HostFxBlackHole.java:181}）里是
     * {@code Math.max(0.8F, Math.min(r, 12.0F))} ⇒ 再往大写在画面上<b>没有任何变化</b>
     * （"能填但看不见效果" = 假控件，本工程红线）。
     * 将来那处放宽之后，改这里 + {@code GuixuVisionCoreMachine.HOST_HOLE_RADIUS_TENTHS_MAX}（两处必须同步）。
     */
    public static final float HOLE_MAX_BLOCKS = 12.0F;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  默认值（= 改造前那三个常量与现行黑洞半径；"没有主机 / 值非法"时就退到这里）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 内环默认角速度（°/s）= 改造前的 {@code HostFxGeometry.RING_DEG_PER_SEC}。 */
    public static final float DEFAULT_RING_DEG_PER_SEC = 12.0F;
    /** 上 4 舰默认角速度（°/s）= 改造前的 {@code HostFxGeometry.SHIP_UP_DEG_PER_SEC}。 */
    public static final float DEFAULT_SHIP_UP_DEG_PER_SEC = 9.0F;
    /** 下 4 舰默认角速度幅值（°/s）= 改造前的 {@code HostFxGeometry.SHIP_DOWN_DEG_PER_SEC}。 */
    public static final float DEFAULT_SHIP_DOWN_DEG_PER_SEC = 9.0F;
    /**
     * 黑洞默认半径（格）= <b>5.196</b>。
     *
     * <p>推导（三个数都是仓库里的实证常量，不是我拍的）：
     * {@code B_CRIT = 2.598076211353316}（{@code BlackHoleLensMath.B_CRIT}，= 3√3/2）
     * × {@code RS_BLOCKS_DEFAULT = 2.0}（{@code BlackHoleLensSpec.RS_BLOCKS_DEFAULT}）
     * = <b>5.196076</b> 格 ⇒ 玩家口径（×0.1 格、整数）就是 <b>52</b>。
     * 与 {@code HostFxGeometry.HOLE_SURFACE_R = 5.196F} 同值。
     *
     * <p>⚠️ 这里写的是字面量而不是引用它们：那三个常量分别在 {@code blackhole/**} 与
     * {@code HostFxGeometry} 里，引用任何一个都会把本类拖进 MC 依赖（见类注释）。
     */
    public static final float DEFAULT_HOLE_RADIUS_BLOCKS = 5.196F;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  状态
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 特效总开关（机器字段 {@code hostFxEnabled} 的客户端副本）。 */
    private static boolean fxEnabled = true;
    /** 内环角速度（°/s）。 */
    private static float ringDegPerSec = DEFAULT_RING_DEG_PER_SEC;
    /** 上 4 舰角速度幅值（°/s，正数）。 */
    private static float shipUpDegPerSec = DEFAULT_SHIP_UP_DEG_PER_SEC;
    /** 下 4 舰角速度幅值（°/s，正数；方向由渲染侧的 −1 负责）。 */
    private static float shipDownDegPerSec = DEFAULT_SHIP_DOWN_DEG_PER_SEC;
    /** 黑洞半径（格）。 */
    private static float holeRadiusBlocks = DEFAULT_HOLE_RADIUS_BLOCKS;
    /** 引力透镜开关（机器字段 {@code hostLensEnabled} 的客户端副本）。 */
    private static boolean lensEnabled = true;

    /**
     * <b>玻璃 AR 屏背后的灰色半透明遮罩</b>（机器字段 {@code hostArMaskEnabled} 的客户端副本）。
     *
     * <p>用户 2026-10-25 原话：「是否在 AR 屏幕背后添加灰色半透明遮罩（这样字可以看得更清楚）」。
     * 默认 {@code true} —— 与机器侧字段的默认值<b>逐位相同</b>（两处不一致会让"刚读档的那一帧"
     * 闪一下：面板显示开、画面没有遮罩）。
     *
     * <p>本类仍然<b>零 Minecraft 依赖</b>：这里只存一个 boolean，画不画由
     * {@code HostFxGlassArModel} 决定（它同样零 MC 依赖 ⇒ 可以脱机出图自审）。
     */
    private static boolean arMaskEnabled = true;

    /**
     * 动画时钟（秒）：<b>只在 {@link #fxEnabled} 为真时累加</b>，关掉时冻结在最后一次的值。
     *
     * <p>为什么用 {@code double} 而对外给 {@code float}：{@code float} 在 10 万秒量级只剩
     * 约 0.008 秒精度，长期运行的存档里会让"12°/s"看起来在漂；内部用 {@code double} 累加、
     * 出口再转 {@code float}，几小时的误差远小于一帧。
     */
    private static double animSeconds;

    /**
     * 上一次{@link #animSeconds()}（或 {@link #feed} 冻结时）取的 {@link System#nanoTime()}；0 = 还没锚定。
     */
    private static long lastNanos;

    /** WARN 出口（默认 {@code System.err}；渲染侧可换成 {@code ShanhaiMod.LOGGER::warn}）。 */
    private static java.util.function.Consumer<String> warner = HostFxState::warnToStderr;

    /** 已经报过的 WARN 键（"打一次"的判据；{@code LinkedHashSet} 便于复现顺序）。 */
    private static final Set<String> WARNED = new LinkedHashSet<>();

    private HostFxState() {
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  喂料
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 喂进一台主机的 6 个视界参数（渲染侧<b>每帧一次</b>，在成型闸门之后、任何绘制之前调）。
     *
     * <p>所有数值都在这里<b>再钳一次</b>：机器侧的 setter 已经钳过，但存档/网络包里的值不归 setter 管
     * （{@code @Persisted} 是从 NBT 直接写字段的）⇒ 这里是"画面上永远看不到越界值"的最后一道。
     *
     * @param fxEnabled        特效总开关（关 = 环/舰静止、光束换色…，具体语义在渲染侧）
     * @param ringDegPerSec    内环角速度（°/s，钳到 1..60）
     * @param shipUpDegPerSec  上 4 舰角速度（°/s，钳到 0..60）
     * @param shipDownDegPerSec 下 4 舰角速度<b>幅值</b>（°/s，钳到 0..60；负号由渲染侧负责）
     * @param holeRadiusBlocks 黑洞半径（格，钳到 1.0..12.0）
     * @param lensEnabled      引力透镜开关（开 = 走真透镜；关 = 只画黑盘/光子环/吸积盘）
     */
    public static void feed(boolean fxEnabled, float ringDegPerSec, float shipUpDegPerSec,
                            float shipDownDegPerSec, float holeRadiusBlocks, boolean lensEnabled) {
        HostFxState.fxEnabled = fxEnabled;
        HostFxState.ringDegPerSec = clampRingDegPerSec(ringDegPerSec);
        HostFxState.shipUpDegPerSec = clampShipDegPerSec("shipUpDegPerSec", shipUpDegPerSec);
        HostFxState.shipDownDegPerSec = clampShipDegPerSec("shipDownDegPerSec", shipDownDegPerSec);
        HostFxState.holeRadiusBlocks = clampHoleRadiusBlocks(holeRadiusBlocks);
        HostFxState.lensEnabled = lensEnabled;
        if (!fxEnabled) {
            // 🔴 冻结期间每帧重新锚定一次：不然"关掉 3 分钟后打开"会一次性累加 180 秒 ⇒ 环瞬移大半圈。
            //    （既不是"传 0"，也不清零 —— 时钟值原样保留，见 animSeconds() 的契约）
            lastNanos = System.nanoTime();
        }
    }

    /**
     * 清空"当前有一台主机在喂料"这份状态（主机不在视野 / 结构不成型 / 机器为 {@code null} 时调）。
     *
     * <p>清的是<b>参数</b>（回到 {@link #DEFAULT_RING_DEG_PER_SEC} 那一组默认值），
     * <b>不清时钟</b>：闸门抖一帧（成型位/视野来回一次）不应该让环"瞬移回初始姿态"。
     * 于是下一个 {@link #feed} 会从冻结的那个相位继续。
     */
    public static void clear() {
        fxEnabled = true;
        ringDegPerSec = DEFAULT_RING_DEG_PER_SEC;
        shipUpDegPerSec = DEFAULT_SHIP_UP_DEG_PER_SEC;
        shipDownDegPerSec = DEFAULT_SHIP_DOWN_DEG_PER_SEC;
        holeRadiusBlocks = DEFAULT_HOLE_RADIUS_BLOCKS;
        lensEnabled = true;
        // AR 遮罩回到默认（true）——与机器字段默认值一致；否则"主机拆了"之后遮罩会停在关闭态
        arMaskEnabled = true;
        // 重新锚定：下一次 animSeconds() 的 dt 从 0 起，不会把"不在视野这段墙钟"算进去。
        lastNanos = 0L;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  读
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 特效总开关（默认 {@code true}）。 */
    public static boolean fxEnabled() {
        return fxEnabled;
    }

    /** 内环角速度（°/s；钳过，1..60）。 */
    public static float ringDegPerSec() {
        return ringDegPerSec;
    }

    /** 上 4 舰角速度（°/s；钳过，0..60）。 */
    public static float shipUpDegPerSec() {
        return shipUpDegPerSec;
    }

    /** 下 4 舰角速度<b>幅值</b>（°/s；钳过，0..60，<b>正数</b> —— 负号由渲染侧 {@code SHIP_DOWN_SIGN} 负责）。 */
    public static float shipDownDegPerSec() {
        return shipDownDegPerSec;
    }

    /** 黑洞半径（格；钳过，1.0..12.0）。 */
    public static float holeRadiusBlocks() {
        return holeRadiusBlocks;
    }

    /** 引力透镜开关（默认 {@code true}）。 */
    public static boolean lensEnabled() {
        return lensEnabled;
    }

    /** 玻璃 AR 屏背后的灰色半透明遮罩（默认 {@code true}）。 */
    public static boolean arMaskEnabled() {
        return arMaskEnabled;
    }

    /**
     * 喂进"AR 遮罩开关"（渲染侧每帧一次，与 {@link #feed} 同一个节奏）。
     *
     * <p>为什么不并进 {@link #feed} 那 6 个参数：那个方法的形参表被
     * {@code temp/host-ui/selfcheck} 里那份离线探针逐位引用着（6 个实参），
     * 加第 7 个会让"探针与产品代码同一个入口"这条判据断掉 ⇒ 单开一个单参方法最省事且不动既有契约。
     */
    public static void feedArMask(boolean enabled) {
        arMaskEnabled = enabled;
    }

    /**
     * <b>动画时钟（秒）</b>：{@code fxEnabled} 时按墙钟累加，关闭时<b>冻结</b>在最后一次的值。
     *
     * <h2>🔴 两条不许改的语义</h2>
     * <ol>
     *   <li><b>冻结 ≠ 归零</b>。关掉特效时环/舰"静止但仍画"，它们的姿态是
     *       {@code ringDeg(animSeconds)} ⇒ 时钟若归零，环会<b>瞬移回初始姿态</b>（用户看到的会是
     *       "一关特效环就跳回起点"）。所以关掉时这里返回<b>上一次的值</b>。</li>
     *   <li><b>重新打开不许跳</b>。关闭期间 {@link #feed} 每帧重新锚定 {@link #lastNanos}，
     *       ⇒ 打开的第一帧 {@code dt ≈ 0}，从冻结点继续转。</li>
     * </ol>
     *
     * <h2>为什么时钟放在这里、而不是用世界时间</h2>
     * 世界时间（{@code level.getGameTime()}）在"暂停/单人开菜单"时会停，而本类的开关语义是
     * <b>玩家自己按的</b>；用墙钟才能保证"开着 = 一直在转，关着 = 一直不动"，两者互不干扰。
     * 代价是单人暂停时特效仍会转（这与改造前 {@code seconds} 的行为不同，已写进交付报告的「待你确认」）。
     *
     * <h2>刻意<b>不</b>夹"单帧最多加 N 秒"</h2>
     * 最初写过 {@code Math.min(dt, 1.0)}，后来删掉：① 它让本函数<b>不再是墙钟</b>（一条 5 秒采样一次的
     * 调用方会看到时钟每 5 秒只涨 1 秒，日志读数会骗人）；② 渲染侧每帧调用时 dt≈0.016s，夹子根本不起作用，
     * 唯一生效的场合是"卡顿/离开视野很久之后的第一次调用"——那种场合画面本身也停着，
     * 相位前进是<b>正确</b>语义（"它一直转着"），玩家看不到任何瞬移。
     * 只挡 {@code dt <= 0}（时钟回绕这种理论情况）。
     *
     * @return 动画时钟（秒）；同一帧内多次调用只会累加微秒级增量
     */
    public static float animSeconds() {
        final long now = System.nanoTime();
        if (fxEnabled && lastNanos != 0L) {
            final double dt = (now - lastNanos) / 1.0e9D;
            if (dt > 0.0D) {
                animSeconds += dt;
            }
            // dt <= 0（时钟回绕/理论上不会发生）⇒ 不累加，只在下面重新锚定
        }
        lastNanos = now;
        return (float) animSeconds;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  钳位（public：机器面板与 HostFxGeometry 都要用同一份，绝不各写一套）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /** 内环角速度钳位：NaN/∞ ⇒ 默认 12 + WARN；其余夹到 1..60。 */
    public static float clampRingDegPerSec(float value) {
        if (!isFinite(value)) {
            return fallback("ringDegPerSec", value, DEFAULT_RING_DEG_PER_SEC);
        }
        return clamp(value, RING_MIN_DEG_PER_SEC, RING_MAX_DEG_PER_SEC);
    }

    /** 船舱角速度幅值钳位：NaN/∞ ⇒ 默认 9 + WARN；其余夹到 0..60。 */
    public static float clampShipDegPerSec(String field, float value) {
        if (!isFinite(value)) {
            return fallback(field, value, DEFAULT_SHIP_UP_DEG_PER_SEC);
        }
        return clamp(value, SHIP_MIN_DEG_PER_SEC, SHIP_MAX_DEG_PER_SEC);
    }

    /** 黑洞半径钳位（格）：NaN/∞ ⇒ 默认 5.196 + WARN；其余夹到 1.0..12.0（上限见 {@link #HOLE_MAX_BLOCKS}）。 */
    public static float clampHoleRadiusBlocks(float value) {
        if (!isFinite(value)) {
            return fallback("holeRadiusBlocks", value, DEFAULT_HOLE_RADIUS_BLOCKS);
        }
        return clamp(value, HOLE_MIN_BLOCKS, HOLE_MAX_BLOCKS);
    }

    /** 整数版（单位 °/s）：给面板与机器 setter 用。 */
    public static int clampRingDegPerSecInt(int value) {
        return Math.round(clampRingDegPerSec(value));
    }

    /** 整数版（单位 °/s）。 */
    public static int clampShipDegPerSecInt(String field, int value) {
        return Math.round(clampShipDegPerSec(field, value));
    }

    /** 整数版（单位 ×0.1 格 ⇒ 10..120）。 */
    public static int clampHoleRadiusTenths(int tenths) {
        return Math.round(clampHoleRadiusBlocks(tenths / 10.0F) * 10.0F);
    }

    /**
     * 装 WARN 出口（渲染侧静态块里把 {@code ShanhaiMod.LOGGER::warn} 装上）。
     *
     * @param sink 收到一整行（已带 {@code [SHANHAI-HOSTFX] HostFxState} 前缀）；{@code null} = 恢复默认
     */
    public static void setWarner(java.util.function.Consumer<String> sink) {
        warner = sink != null ? sink : HostFxState::warnToStderr;
    }

    /** 测试/复位用：忘掉"已经报过"的记录（只影响 WARN 去重，不碰任何数值）。 */
    public static void resetWarned() {
        WARNED.clear();
    }

    /** 已经报过的 WARN 键（自检用；正常游戏里没人读它）。 */
    public static Set<String> warnedKeys() {
        return java.util.Collections.unmodifiableSet(WARNED);
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════════════════════════

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float fallback(String field, float bad, float def) {
        warnOnce("default:" + field, "[SHANHAI-HOSTFX] HostFxState WARN 字段 " + field
                + " 收到非法值 " + bad + " ⇒ 回落到默认 " + def + "（同类只报一次）");
        return def;
    }

    private static void warnOnce(String key, String line) {
        if (WARNED.add(key)) {
            final java.util.function.Consumer<String> sink = warner;
            if (sink != null) {
                try {
                    sink.accept(line);
                } catch (Throwable ignored) {
                    // 日志出口自己炸了也不许影响渲染（本类被每帧调用）
                }
            }
        }
    }

    private static void warnToStderr(String line) {
        System.err.println(line);
    }
}
