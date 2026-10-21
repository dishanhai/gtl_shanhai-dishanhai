package com.dishanhai.gt_shanhai.client.gui.scaled;

/**
 * 按住连发的节奏（纯函数）：按下后等 {@code initialDelayMs} 发第 1 次，之后间隔从 {@code startIntervalMs}
 * 起每次乘 {@code factor}，降到 {@code minIntervalMs} 封底。
 *
 * <p>「按住这么久该发几次」是按住时长的函数，调用方每帧现算，不靠计时器累加；
 * 帧卡顿时差了几次一算便知，补不补由调用方决定（见 {@link ButtonFeedback#MAX_REPEAT_BURST}）。</p>
 */
public final class RepeatCurve {

    /** 数量步进：0.4 秒后开始，约 1 秒后加速到每 30ms 一次。 */
    public static final RepeatCurve STEPPER = new RepeatCurve(400L, 120L, 0.85, 30L);

    private final long initialDelayMs;
    private final long startIntervalMs;
    private final double factor;
    private final long minIntervalMs;

    public RepeatCurve(long initialDelayMs, long startIntervalMs, double factor, long minIntervalMs) {
        if (initialDelayMs < 0L || minIntervalMs <= 0L || startIntervalMs < minIntervalMs
                || !(factor > 0.0 && factor <= 1.0)) {
            throw new IllegalArgumentException("连发参数不合法：delay=" + initialDelayMs + " start=" + startIntervalMs
                    + " factor=" + factor + " min=" + minIntervalMs);
        }
        this.initialDelayMs = initialDelayMs;
        this.startIntervalMs = startIntervalMs;
        this.factor = factor;
        this.minIntervalMs = minIntervalMs;
    }

    /** 第 n 次连发与第 n-1 次之间的间隔（n≥2）；n≤1 返回首次延迟。 */
    public long intervalAt(int n) {
        if (n <= 1) return initialDelayMs;
        double v = startIntervalMs * Math.pow(factor, n - 2);
        return Math.max(minIntervalMs, Math.round(v));
    }

    /** 第 n 次连发相对按下时刻的毫秒数；n=0 是按下那一次本身。 */
    public long fireTimeMs(int n) {
        if (n <= 0) return 0L;
        long t = initialDelayMs;
        for (int k = 2; k <= n; k++) {
            long i = intervalAt(k);
            // 到了封底间隔，剩下的每次都一样长，直接乘
            if (i == minIntervalMs) return t + (long) (n - k + 1) * minIntervalMs;
            t += i;
        }
        return t;
    }

    /** 按住 {@code heldMs} 时应已发出的连发次数（不含按下那次）。 */
    public int firesBy(long heldMs) {
        if (heldMs < initialDelayMs) return 0;
        long t = initialDelayMs;
        int n = 1;
        while (true) {
            long i = intervalAt(n + 1);
            if (i == minIntervalMs) {
                long extra = (heldMs - t) / minIntervalMs;
                return (int) Math.min(Integer.MAX_VALUE, n + extra);
            }
            if (t + i > heldMs) return n;
            t += i;
            n++;
        }
    }
}
