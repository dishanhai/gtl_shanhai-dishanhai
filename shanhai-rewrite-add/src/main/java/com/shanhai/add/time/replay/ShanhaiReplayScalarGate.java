package com.shanhai.add.time.replay;

/**
 * 山海重构 · <b>世界标量的"记不记"判定核</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么必须把这条判据抽出来（2026-11 真机实测）</h2>
 * 真机日志原文：
 * <pre>
 *   scalars=records=8000/8000 ticks=[43226,43403] dropped=26020 overflow=0
 *   step=20 … scalars=45 written=45 failed=0
 * </pre>
 * ⇒ 标量时间线<b>满得最早</b>（8000 条只装下 178 tick），而每拍还在回写几十条；
 * 用户看到的「天上下雨在鬼畜」就是<b>每 tick 调一次 {@code setWeatherParameters}</b> 的客户端表现。
 * 根因是原来的判据只有"值变了才记"：
 * <ul>
 *   <li>{@code dayTime} 每 tick <b>+1</b> ⇒ 每 tick 都算"变了" ⇒ 每 tick 都记；</li>
 *   <li>{@code rainTime / thunderTime / clearWeatherTime} 是<b>自然倒计时</b>，每 tick −1
 *       ⇒ 也每 tick 都"变了" ⇒ 同样每 tick 都记（这三条已经<b>不记</b>了，见 {@link #WRITE_ONLY_KEYS}）。</li>
 * </ul>
 * 所以这一层要区分<b>离散事件</b>（下雨开/关、打雷开/关）和<b>自然流逝</b>（+1 / −1 的计数器）。
 *
 * <h2>2. 三条判据（离线断言见 {@code tools\replay-selfcheck} O 组）</h2>
 * <table border="1">
 *   <caption>判定表</caption>
 *   <tr><th>key</th><th>判据</th><th>稳态 200 tick 记几条</th></tr>
 *   <tr><td>{@code raining} / {@code thundering}</td>
 *       <td>布尔<b>翻转</b>才记（{@link #flag}）</td><td>0（翻转几次就记几条）</td></tr>
 *   <tr><td>{@code dayTime}</td>
 *       <td>相邻两 tick 的差<b>不是 +1</b> 才记（{@link #dayTime}）。
 *           +1 是原版自然流逝，记它等于每 tick 一条；而 {@code /time set} / 床 / 改时钟倍率
 *           都会让差 ≠ +1 ⇒ 那些一定会被记下来</td>
 *       <td>0</td></tr>
 *   <tr><td>{@code rainTime} / {@code thunderTime} / {@code clearWeatherTime}</td>
 *       <td><b>不记</b>（见 {@link #WRITE_ONLY_KEYS}）：它们是自然倒计时，回退时天气由
 *           {@code raining}/{@code thundering} 两个开关决定，原版自己会重新起算</td>
 *       <td>0</td></tr>
 * </table>
 *
 * <h2>3. 🔴 关于设计里那句「与上次记录的差 ≥ 20 tick」</h2>
 * 判据 1 明确要求「喂 200 tick 的 {@code dayTime} +1 ⇒ 记录数必须 ≤2」，而"每 20 tick 补记一条"
 * 在 200 tick 里会补出 10 条 ⇒ <b>两条口径互相冲突</b>。
 * 本次按<b>判据 1</b>（更严的那条）执行：稳态 {@code dayTime} 一条都不记。
 * 代价与补法：稳态流逝没进日志，所以回退到位的最后一刻由
 * {@code ShanhaiReplayScalarRecorder#catchUpDayTime} 按"实际退掉的 tick 数"把
 * {@code dayTime} 一次性减回去（那一段不经过日志，也就不会每 tick 调 {@code setDayTime}）。
 */
public final class ShanhaiReplayScalarGate {

    /**
     * 只写回、<b>不记录</b>的 key（自然倒计时）。
     *
     * <p>写回去没有意义：回退时 {@code setWeatherParameters} 会把它们重新起算；
     * 而记录它们就是"每 tick 一条"的水龙头（真机实测里被灌满 8000 条的就是这一类）。
     */
    public static final String[] WRITE_ONLY_KEYS = {
            "rain_time", "thunder_time", "clear_weather_time"
    };

    /** 判定结果（带码，日志/自检都按码比对）。 */
    public enum Decision {

        /** 记一条。 */
        RECORD("record"),
        /** 值没变。 */
        SKIP_SAME("skip_same"),
        /** 正常流逝（{@code dayTime} 恰好 +1）⇒ 不记。 */
        SKIP_STEADY("skip_steady"),
        /** 只写不记的自然倒计时。 */
        SKIP_WRITE_ONLY("skip_write_only");

        private final String code;

        Decision(String code) {
            this.code = code;
        }

        public String code() {
            return this.code;
        }

        public boolean recorded() {
            return this == RECORD;
        }
    }

    /**
     * {@code dayTime} 的判据。
     *
     * @param previous 上一 tick 读到的值（{@code last} 基线，不是"上次记录的"）
     * @param now      这一 tick 读到的值
     */
    public static Decision dayTime(long previous, long now) {
        if (now == previous) {
            return Decision.SKIP_SAME;
        }
        if (now - previous == 1L) {
            return Decision.SKIP_STEADY;   // 原版自然流逝：每 tick +1，记它等于每 tick 一条
        }
        return Decision.RECORD;            // /time set、床、改倍率、回退写回……差不是 +1
    }

    /** {@code raining} / {@code thundering} 的判据：<b>翻转</b>才记。 */
    public static Decision flag(boolean previous, boolean now) {
        return previous == now ? Decision.SKIP_SAME : Decision.RECORD;
    }

    /**
     * 这条 key 名是不是"只写不记"的自然倒计时。
     *
     * @param keyName {@link ShanhaiReplayScalarJournal.Keys#nameOf(int)}
     */
    public static boolean writeOnly(String keyName) {
        if (keyName == null) {
            return false;
        }
        for (String k : WRITE_ONLY_KEYS) {
            if (k.equals(keyName)) {
                return true;
            }
        }
        return false;
    }

    private ShanhaiReplayScalarGate() {
    }
}
