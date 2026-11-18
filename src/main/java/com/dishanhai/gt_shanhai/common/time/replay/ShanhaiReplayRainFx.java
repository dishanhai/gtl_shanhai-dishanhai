package com.dishanhai.gt_shanhai.common.time.replay;

/**
 * 山海重构 · <b>倒流期间的"上雨"相位（游标驱动）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11-14 13:26 录像之后）</h2>
 * <blockquote>「还有那个下雨，那个雨在<b>鬼畜</b>，<b>没有反方向向上移动</b>」</blockquote>
 *
 * <h2>2. 鬼畜的根因（原文，1.20.1）</h2>
 * {@code LevelRenderer#renderSnowAndRain}（{@code LevelRenderer.java:269-380}）的雨相位是
 * <pre>
 *   :291  float f1 = (float)this.ticks + pPartialTicks;
 *   :334  int i3 = this.ticks + k1*k1*3121 + … &amp; 31;
 *   :357  float f5 = -((float)(this.ticks &amp; 511) + pPartialTicks) / 512.0F;   （雪）
 * </pre>
 * 而 {@code ticks}（{@code LevelRenderer.java:190}）是<b>整数</b>、每 tick 才 +1。
 * 我们冻结时钟时把 {@code LevelRenderer#tick()} 整段取消 ⇒ <b>{@code ticks} 不动、
 * {@code partialTick} 照常 0→1 循环</b> ⇒ {@code ticks + partialTick} 每 tick 从
 * {@code T+0.99} 掉回 {@code T+0} ⇒ <b>雨纹理相位每秒 20 次倒退</b>，这就是"鬼畜"。
 *
 * <h2>3. 本类给的两条口径</h2>
 * <ul>
 *   <li>{@link #phase(int)} —— 倒流期间的相位：<b>由回放游标驱动、单调递减</b>
 *       （游标每外层 tick 减 1 ⇒ 雨往上走，且绝不来回跳）；</li>
 *   <li>{@link #monotoneStep(double, double, double)} —— 判据：连续两次采样的相位必须
 *       <b>朝同一个方向</b>走（负对照：把驱动量改回墙钟正方向 ⇒ 方向相反 ⇒ 判红）。</li>
 * </ul>
 *
 * <p>⚠️ 正常时间（{@code LIVE}）下本类一次都不参与：那时原版 {@code ticks + partialTick}
 * 本来就是单调的，改了就是回归（自检 Z2 用"驱动量回墙钟"的负对照钉住这条）。
 */
public final class ShanhaiReplayRainFx {

    /** 倒流期间雨/雪的相位（游标驱动；游标递减 ⇒ 相位递减 ⇒ 雨向上）。 */
    public static double phase(int cursor) {
        // 相位与游标同向：游标 100→99 ⇒ 相位 100→99（递减）。
        // 原版相位递减 = 雨往上走（原版是随 ticks 递增而向下）。
        return (double) cursor;
    }

    /** 相位变化的方向（{@code +1} 增 / {@code -1} 减 / {@code 0} 没动）。 */
    public static int direction(double before, double after, double eps) {
        final double d = after - before;
        if (Math.abs(d) <= Math.max(0.0D, eps)) {
            return 0;
        }
        return d > 0 ? 1 : -1;
    }

    /**
     * 判据：连续两段的方向必须一致（且不为 0）。
     *
     * @param d1 第一段的方向（{@link #direction}）
     * @param d2 第二段的方向
     * @return {@code true} = 单调（不许出现双向跳变）
     */
    public static boolean monotoneStep(int d1, int d2) {
        return d1 != 0 && d1 == d2;
    }

    /** 一行判据文本（客户端每 250 ms 打一次）。 */
    public static String line(String dir, String src, double phase, int particlesLive) {
        return "rain_dir=" + dir + " src=" + src
                + " rain_phase=" + String.format(java.util.Locale.ROOT, "%.2f", phase)
                + " particles_live=" + particlesLive;
    }

    private ShanhaiReplayRainFx() {
    }
}
