package com.shanhai.add.time.replay;

import java.util.HashMap;
import java.util.Map;

/**
 * 山海重构 · <b>天气写回的节流阀</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么写回侧也要节流（真机实测的"下雨鬼畜"）</h2>
 * 用户原话：「我没有看到倒流中一格一格退，我看到天上下雨在鬼畜」。
 * 客户端下雨／停雨的抖动只可能来自一件事：{@code ServerLevel#setWeatherParameters} 被<b>反复调用</b>。
 * 一拍的标量日志里有 45 条记录（真机实测），如果每条都调一次，客户端就会在一帧里收到几十次
 * "开始下雨/停止下雨" ⇒ 看上去就是鬼畜。
 *
 * <h2>2. 两条判据（离线断言见 {@code tools\replay-selfcheck} P 组）</h2>
 * <ol>
 *   <li><b>值没变就不写</b>：{@code (raining, thundering)} 与"世界里现在那一对"相同 ⇒ {@link Decision#SKIP_SAME}；</li>
 *   <li><b>同一 tick 同一维度最多写一次</b>：多出来的记录一律 {@link Decision#SKIP_SAME_TICK}
 *       （真机那种"一拍 45 条"的记录形状下，写调用次数从 45 降到 1）。</li>
 * </ol>
 * ⇒ 「连续 20 个回写步里 raining 只变 1 次 ⇒ 调用次数 = 1」；负对照（每步都写）⇒ 20。
 *
 * <h2>3. 基线从哪来</h2>
 * {@link #seed(int, boolean, boolean)}：记录侧每 tick 在 LIVE 状态下把"世界里现在那一对"喂进来，
 * 于是倒流一开始，这个阀就知道"现在世界是什么天气"，只有真的要把天气改掉的那一条才会写。
 * 没 seed 过的维度第一次请求一定会写（{@link Decision#WRITE}）—— 宁可写一次，不许"以为不用写"。
 */
public final class ShanhaiReplayWeatherGate {

    /** 判定结果。 */
    public enum Decision {

        /** 真的调 {@code setWeatherParameters}。 */
        WRITE("write"),
        /** 与世界里现在那一对相同。 */
        SKIP_SAME("skip_same"),
        /** 这个维度这一 tick 已经写过了。 */
        SKIP_SAME_TICK("skip_same_tick");

        private final String code;

        Decision(String code) {
            this.code = code;
        }

        public String code() {
            return this.code;
        }
    }

    /** dim → 世界里现在的 (raining, thundering) 打包。 */
    private final Map<Integer, Integer> last = new HashMap<>();
    /** dim → 最后一次写的世界 tick。 */
    private final Map<Integer, Integer> lastTick = new HashMap<>();

    private long writes;
    private long skippedSame;
    private long skippedTick;

    private static int pack(boolean raining, boolean thundering) {
        return (raining ? 1 : 0) | (thundering ? 2 : 0);
    }

    /** 记录侧喂基线（LIVE 每 tick 调；倒流期间不调，所以基线就停在"倒流开始那一刻"）。 */
    public void seed(int dim, boolean raining, boolean thundering) {
        this.last.put(Integer.valueOf(dim), Integer.valueOf(pack(raining, thundering)));
    }

    /**
     * 问一句"这条标量要不要真去调 {@code setWeatherParameters}"。
     *
     * @param tick 当前世界 tick（同一 tick 只写一次）
     */
    public Decision request(int dim, boolean raining, boolean thundering, int tick) {
        final Integer want = Integer.valueOf(pack(raining, thundering));
        final Integer have = this.last.get(Integer.valueOf(dim));
        if (have != null && have.equals(want)) {
            ++this.skippedSame;
            return Decision.SKIP_SAME;
        }
        final Integer wroteAt = this.lastTick.get(Integer.valueOf(dim));
        if (wroteAt != null && wroteAt.intValue() == tick) {
            ++this.skippedTick;
            return Decision.SKIP_SAME_TICK;
        }
        this.last.put(Integer.valueOf(dim), want);
        this.lastTick.put(Integer.valueOf(dim), Integer.valueOf(tick));
        ++this.writes;
        return Decision.WRITE;
    }

    /** 换世界/倒流收尾：基线作废（下次倒流会重新 seed）。 */
    public void reset() {
        this.last.clear();
        this.lastTick.clear();
        this.writes = 0L;
        this.skippedSame = 0L;
        this.skippedTick = 0L;
    }

    /** 真的调用了几次 {@code setWeatherParameters}（判据 3 的读数就是它）。 */
    public long writes() {
        return this.writes;
    }

    public long skippedSame() {
        return this.skippedSame;
    }

    public long skippedSameTick() {
        return this.skippedTick;
    }

    /** 一行摘要（status / done 行）。 */
    public String summary() {
        return "weather_writes=" + this.writes + " weather_skip_same=" + this.skippedSame
                + " weather_skip_tick=" + this.skippedTick;
    }
}
