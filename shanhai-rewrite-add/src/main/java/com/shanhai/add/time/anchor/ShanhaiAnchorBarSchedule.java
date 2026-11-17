package com.shanhai.add.time.anchor;

import java.util.Optional;

/**
 * 山海重构 · 锚点条的<b>发送节拍</b>（🔴 纯逻辑核：零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 谁该在什么时候收到一条</h2>
 * <table border="1">
 *   <caption>五个触发点（1–4 是一次性事件，5 是周期）</caption>
 *   <tr><th>#</th><th>入口</th><th>发送</th><th>为什么必须发</th></tr>
 *   <tr><td>1</td><td>{@link Event#ANCHOR_SET}</td><td>是</td><td>条要出现</td></tr>
 *   <tr><td>2</td><td>{@link Event#ANCHOR_CLEAR}</td><td>是</td><td><b>条要消失</b>（不发 ⇒ 客户端挂着旧锚点的条，判据 ③ 的负对照点）</td></tr>
 *   <tr><td>3</td><td>{@link Event#REPLAY_START}</td><td>是</td><td>条要变青、从右往左涨</td></tr>
 *   <tr><td>4</td><td>{@link Event#REPLAY_STOP}</td><td>是</td><td>倒流结束/中止 ⇒ 回到窗口态或消失</td></tr>
 *   <tr><td>5</td><td>{@link #onReplayTick}／{@link #onWindowTick}</td><td>周期</td><td>倒流中每 {@value #REPLAY_PERIOD_TICKS} tick；不在倒流时每 {@value #WINDOW_PERIOD_TICKS} tick 滴一次</td></tr>
 * </table>
 *
 * <h2>2. 为什么"窗口态"也要一条慢滴（每 20 tick = 1 秒）</h2>
 * 一次性事件只覆盖"状态跳变"；而窗口态是<b>连续变化</b>的：条要从满退到空、60 秒后变 EXPIRED。
 * 只发事件的话，客户端拿到 {@code nowTick} 之后就再也不会更新 ⇒ 条永远停在满格、也永远不会过期
 * （玩家看到的是一条"不动的 60 秒条"，比不画更糟）。
 * 周期取 20 tick（1 秒）：60 秒的条按秒走 60 步，肉眼连续；同时它只在<b>锚点所在维度</b>发，不是全服刷。
 *
 * <h2>3. 为什么周期计数要在一次性事件后清零</h2>
 * 否则会出现"事件那条刚发完，下一个 tick 又补一条"的情形 —— 判据 ② 数的是
 * "倒流 100 tick ⇒ 20 条"，多出来的那条会让读数变成 21，把真正的节拍问题掩盖掉。
 *
 * <h2>4. 这个类<b>不含</b>任何"发给谁"的判断</h2>
 * 维度过滤在 {@code ShanhaiAnchorBarBroadcast}（它要碰 {@code ServerLevel}），
 * 这里只回答"这一拍该不该发"。这样判据 ② 可以在没有游戏的情况下用替身驱动。
 */
public final class ShanhaiAnchorBarSchedule {

    /** 倒流中每多少 tick 发一条（100 tick ⇒ 20 条）。 */
    public static final int REPLAY_PERIOD_TICKS = 5;

    /** 窗口态（锚点还在计时）每多少 tick 滴一条（20 tick = 1 秒）。 */
    public static final int WINDOW_PERIOD_TICKS = 20;

    /** 四个<b>必须</b>各发一次的一次性事件。 */
    public enum Event {

        /** {@code /shanhai_add time anchor set} 成功之后。 */
        ANCHOR_SET,
        /** {@code /shanhai_add time anchor clear} 之后（帧本身已是"无锚点"）。 */
        ANCHOR_CLEAR,
        /** 倒流开始（{@code time rewind} 成功）。 */
        REPLAY_START,
        /** 倒流结束或中止（含 {@code rewind stop}、换模式、停机）。 */
        REPLAY_STOP
    }

    private int replayPeriod;
    private int windowPeriod;
    private int sent;

    /**
     * 一次性事件：命中就返回<b>要发的那一帧</b>，否则 {@link Optional#empty()}。
     *
     * <p>🔴 四个分支一个都不能少（判据 ② 数"4 个事件各发一次"）：
     * 少 {@code ANCHOR_CLEAR} 那一行，客户端就永远停在"有锚点"，
     * 这正是判据 ③ 负对照要打的地方（删掉它 ⇒ {@code clear} 之后算出的状态不是 {@code NONE}）。
     *
     * @param event 事件种类（{@code null} ⇒ 不发）
     * @param frame 该事件之后服务端眼里的状态（{@code null} ⇒ 不发）
     */
    public Optional<ShanhaiAnchorBarFrame> onEvent(Event event, ShanhaiAnchorBarFrame frame) {
        if (event == null || frame == null) {
            return Optional.empty();
        }
        switch (event) {
            case ANCHOR_SET:        // ① 条要出现
            case ANCHOR_CLEAR:      // ② 条要消失（判据 ③ 的负对照点：删掉这一行必须红）
            case REPLAY_START:      // ③ 条要变青
            case REPLAY_STOP:       // ④ 条要回窗口态 / 消失
                resetPeriods();
                ++this.sent;
                return Optional.of(frame);
            default:
                return Optional.empty();
        }
    }

    /**
     * 倒流中的每一拍（调用方只在 {@code isActive()} 时调）。
     *
     * @return 每 {@value #REPLAY_PERIOD_TICKS} 拍给一帧，其余为空
     */
    public Optional<ShanhaiAnchorBarFrame> onReplayTick(ShanhaiAnchorBarFrame frame) {
        this.windowPeriod = 0;
        if (frame == null) {
            return Optional.empty();
        }
        if (++this.replayPeriod >= REPLAY_PERIOD_TICKS) {
            this.replayPeriod = 0;
            ++this.sent;
            return Optional.of(frame);
        }
        return Optional.empty();
    }

    /**
     * 没在倒流时的每一拍（调用方每外层 tick 调一次）。
     *
     * @return 有锚点且每 {@value #WINDOW_PERIOD_TICKS} 拍给一帧，其余为空
     */
    public Optional<ShanhaiAnchorBarFrame> onWindowTick(ShanhaiAnchorBarFrame frame) {
        this.replayPeriod = 0;
        if (frame == null || !frame.hasAnchor()) {
            // 没锚点就没有"一直在变的量"，不必滴（否则每个玩家每秒收一条空帧）
            this.windowPeriod = 0;
            return Optional.empty();
        }
        if (++this.windowPeriod >= WINDOW_PERIOD_TICKS) {
            this.windowPeriod = 0;
            ++this.sent;
            return Optional.of(frame);
        }
        return Optional.empty();
    }

    /** 换世界/停机：把周期计数清零（下一次从"完整一拍"开始）。 */
    public void reset() {
        resetPeriods();
        this.sent = 0;
    }

    /** 累计放行过多少帧（status/判据用；只增不减）。 */
    public int sent() {
        return this.sent;
    }

    private void resetPeriods() {
        this.replayPeriod = 0;
        this.windowPeriod = 0;
    }
}
