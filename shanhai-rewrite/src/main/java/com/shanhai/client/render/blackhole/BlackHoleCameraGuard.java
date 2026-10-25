package com.shanhai.client.render.blackhole;

import java.util.Locale;

/**
 * 山海重构 · 黑洞渲染件的 <b>「相机瞬移 / 换维度」检测器</b>（纯逻辑：零 MC / 零 GL）。
 *
 * <h2>0. 它存在的理由（用户 2026-10-25 报的 bug）</h2>
 * 用户原话：「突然发现个bug，我<b>传送走再传送回来</b>黑洞渲染位置错了（右下角那一团光就是黑洞）」。
 *
 * <p>一帧里我们存进 {@link BlackHoleLensRenderer.Frame} 的东西，本质上是<b>"那一瞬间的相机世界坐标 +
 * 投影矩阵 + 屏幕尺寸"</b>；而它被<b>跨帧</b>用到两处（{@code pending} 读数、背景拷贝自检）。
 * 传送 / 换维度之后这些量<b>一次性全部失效</b>，所以必须有一个"这一帧相机是不是被打包换掉了"的判据，
 * 命中就整份作废并打一行 {@code camera_rebased}。
 *
 * <h2>1. 判据（两条，任一命中即算瞬移）</h2>
 * <ol>
 *   <li><b>单帧位移 &gt; {@value #TELEPORT_BLOCKS} 格</b>：
 *       正常移动最快 ≈ 20 格/秒（鞘翅俯冲 + 速度药水也在 60 格/秒量级），
 *       单帧（60 fps）位移 &gt; 64 格在游戏里只有传送能做到
 *       （末影珍珠 / {@code /tp} / 星门 / 传送门 / 载具切换）。</li>
 *   <li><b>维度变了</b>：{@code mc.level.dimension().location()} 的字符串不同。
 *       主世界 ↔ 下界 ↔ 末地 ↔ 自定义维度，坐标语义完全不同 ⇒ 一定要作废。</li>
 * </ol>
 *
 * <h2>2. 为什么是"检测"而不是"每帧重算就完了"</h2>
 * 每帧重算是<b>必须</b>的（{@code capture()} 本来就是每帧现取），但有两样东西是<b>结构上</b>
 * 跨帧的：① {@code pending}（AFTER_WEATHER 采、合成之后取走，中间可能隔帧）；
 * ② 一次性自检缓存（{@code copyVerified} 之类"验过就不再验"的旗标）。
 * 前者由帧号 + 本类的 50 ms 新鲜度把关，后者只能靠"打包换过相机"这个事件来作废。
 *
 * <h2>3. 纯逻辑</h2>
 * 不引用任何 MC 类型 ⇒ 离线复现器（{@code temp/bh-teleport/TeleportRepro.java}）直接 new 它来跑
 * "正常 → 传送 → 传送回来"三段序列，断言只在正确的帧上打 {@code camera_rebased}。
 */
public final class BlackHoleCameraGuard {

    /** 单帧位移超过这么多格 ⇒ 判定为瞬移（正常移动不可能：鞘翅 60 格/秒 @60fps = 1 格/帧）。 */
    public static final double TELEPORT_BLOCKS = 64.0D;

    /** {@code camera_rebased} 的原因词表（进日志，无空白）。 */
    public static final String WHY_TELEPORT = "teleport";
    /** 维度变化。 */
    public static final String WHY_DIMENSION = "dimension";

    private boolean primed;
    private double x;
    private double y;
    private double z;
    private String dimension = "";
    /** 本次会话检测到几次（{@code /shanhai bh status} 与离线复现器都用它）。 */
    private long rebases;

    /**
     * 喂一帧相机状态。
     *
     * @return {@code null} = 没瞬移；否则为原因词（{@link #WHY_TELEPORT} / {@link #WHY_DIMENSION}）
     */
    public String update(double camX, double camY, double camZ, String dim) {
        final String d = dim == null ? "" : dim;
        if (!primed) {
            primed = true;
            x = camX;
            y = camY;
            z = camZ;
            dimension = d;
            return null;                                // 第一帧只记录，不报
        }
        final double dx = camX - x;
        final double dy = camY - y;
        final double dz = camZ - z;
        final double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final boolean dimChanged = !dimension.equals(d);
        final boolean jumped = moved > TELEPORT_BLOCKS;
        if (!dimChanged && !jumped) {
            return null;
        }
        // 记录"上一次"给日志用（{@link #line} 读它）
        lastFromX = x;
        lastFromY = y;
        lastFromZ = z;
        lastFromDim = dimension;
        lastToX = camX;
        lastToY = camY;
        lastToZ = camZ;
        lastToDim = d;
        lastMoved = moved;
        lastWhy = dimChanged ? WHY_DIMENSION : WHY_TELEPORT;
        x = camX;
        y = camY;
        z = camZ;
        dimension = d;
        rebases++;
        return lastWhy;
    }

    private double lastFromX;
    private double lastFromY;
    private double lastFromZ;
    private String lastFromDim = "";
    private double lastToX;
    private double lastToY;
    private double lastToZ;
    private String lastToDim = "";
    private double lastMoved;
    private String lastWhy = "";

    /** 换世界 / 退出存档：下一次 {@link #update} 重新做"第一帧只记录"。 */
    public void forget() {
        primed = false;
    }

    /** 本会话检测到几次瞬移。 */
    public long rebases() {
        return rebases;
    }

    /** 最近一次瞬移的原因词（没瞬移过 = 空串）。 */
    public String lastWhy() {
        return lastWhy;
    }

    /**
     * 那一行日志（格式固定、值无空白，{@code split(" ")} 可切）：
     * <pre>
     *   [SHANHAI-BH] camera_rebased from=12.3,70.0,-44.5@minecraft:overworld
     *                to=1044.5,85.5,0.5@minecraft:overworld moved=1032.2 why=teleport
     * </pre>
     * ⚠️ 字段值里<b>不许出现空格</b>，所以坐标用逗号连、维度用 {@code @} 连 —— 与
     * {@link BlackHoleFallbackGuard#sanitize} 的口径一致（一行一条、可被断言）。
     */
    public String line() {
        return BlackHoleFallbackGuard.PREFIX + " camera_rebased"
                + " from=" + fmt(lastFromX) + "," + fmt(lastFromY) + "," + fmt(lastFromZ)
                + "@" + lastFromDim
                + " to=" + fmt(lastToX) + "," + fmt(lastToY) + "," + fmt(lastToZ)
                + "@" + lastToDim
                + " moved=" + fmt(lastMoved)
                + " why=" + lastWhy
                + " n=" + rebases;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * 只做判据、不落账（离线复现器/单测用：喂一串相机状态，数命中几次）。
     *
     * @return 与 {@link #update} 同语义
     */
    public static String classify(double fromX, double fromY, double fromZ, String fromDim,
                                  double toX, double toY, double toZ, String toDim) {
        final boolean dimChanged = toDim != null && fromDim != null && !fromDim.equals(toDim);
        final double dx = toX - fromX;
        final double dy = toY - fromY;
        final double dz = toZ - fromZ;
        final boolean jumped = Math.sqrt(dx * dx + dy * dy + dz * dz) > TELEPORT_BLOCKS;
        if (dimChanged) {
            return WHY_DIMENSION;
        }
        return jumped ? WHY_TELEPORT : null;
    }
}
