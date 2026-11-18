package com.dishanhai.gt_shanhai.common.time.anchor;

/**
 * 山海重构 · 锚点进度条的<b>一条完整状态</b>（服务端算好 → 客户端照着画）。
 *
 * <h2>1. 为什么是"一个不可变帧"而不是零散字段</h2>
 * 这条 HUD 的输入全是 tick 数，服务端和客户端<b>必须逐帧一致</b>：
 * 锚点是哪个 tick、窗口多长、现在几 tick、是不是在倒流、倒流的起止/游标。
 * 把它们装进一个 {@code record} 之后，"发一次"和"收一次"就是同一个对象，
 * 编解码往返可以逐字段比对（判据 ①），顺序错/漏字段在离线就能红。
 *
 * <h2>2. 字段（🔴 与 {@code AnchorBarStateMsg} 的线格式一一对应，别加多余的）</h2>
 * <table border="1">
 *   <caption>字段口径</caption>
 *   <tr><th>字段</th><th>谁给</th><th>含义</th></tr>
 *   <tr><td>{@code anchorTick}</td><td>服务端</td><td>设锚点那一刻的世界 tick；{@link #NO_ANCHOR} = 没有锚点</td></tr>
 *   <tr><td>{@code windowTicks}</td><td>服务端</td><td>锚点窗口长度（生产恒 {@code ShanhaiReplayConst#WINDOW_TICKS} = 1200 = 60s）</td></tr>
 *   <tr><td>{@code nowTick}</td><td>服务端</td><td>发这一帧时的世界 tick（算"锚点还剩多久"用）</td></tr>
 *   <tr><td>{@code replayActive}</td><td>服务端</td><td>是不是正在倒流（{@code ShanhaiReplayService#isActive}）</td></tr>
 *   <tr><td>{@code replayFromTick}</td><td>服务端</td><td>倒流起点 tick（数值较大）</td></tr>
 *   <tr><td>{@code replayToTick}</td><td>服务端</td><td>倒流目标 tick（数值较小）；{@code from-to} = 总回退距离</td></tr>
 *   <tr><td>{@code replayCursorTick}</td><td>服务端</td><td>回退游标当前所在 tick（从 from 递减走向 to）</td></tr>
 *   <tr><td>{@code flags}</td><td>服务端</td><td>三个状态位：bit0 有过锚点 / bit1 已过期 / bit2 stale</td></tr>
 * </table>
 *
 * <h2>3. 三个 bit 为什么单独发（客户端自己也能算出来，但算出来的不是"服务端的判定"）</h2>
 * "有没有锚点"决定条画不画；"已过期"是服务端的口径（{@code age >= window}）；
 * "stale"是锚点被标失效（死亡/换维度/进倒流）。客户端要能<b>不打折地复述服务端的判定</b>，
 * 而不是拿本地世界 tick 猜 —— 猜出来的东西在丢包/暂停时会和别处对不上。
 *
 * <h2>4. 失败方向</h2>
 * {@link #hasAnchor()} 为 {@code false} 时，"无锚点帧"就是 {@code anchorTick = -1} ＋ 无位，
 * 客户端据此算出 {@code NONE}（整条不画）。<b>绝不会</b>因为一个坏帧而画出"有锚点"的条。
 */
public record ShanhaiAnchorBarFrame(long anchorTick,
                                    int windowTicks,
                                    long nowTick,
                                    boolean replayActive,
                                    long replayFromTick,
                                    long replayToTick,
                                    long replayCursorTick,
                                    byte flags) {

    /** "没有锚点"（世界 tick 从 0 起算，负数结构上不可能是合法锚点）。 */
    public static final long NO_ANCHOR = -1L;

    /** bit0：有过锚点（{@code anchorTick >= 0}）；决定这条画不画。 */
    public static final byte FLAG_HAS_ANCHOR = 1;

    /** bit1：锚点窗口已用完（{@code now - anchorTick >= windowTicks}）⇒ 条还在但空着。 */
    public static final byte FLAG_EXPIRED = 1 << 1;

    /** bit2：锚点被标失效（玩家死亡 / 换维度 / 进倒流），位置快照不再可信。 */
    public static final byte FLAG_STALE = 1 << 2;

    public ShanhaiAnchorBarFrame {
        // 注意：record 的紧凑构造器里只能读/写**参数名**（字段还没赋值，别写 this.xxx）
        if (anchorTick < NO_ANCHOR) {
            // 只认 -1 与 >=0；更小的负数一律当"没有锚点"（坏输入不许变出锚点）
            anchorTick = NO_ANCHOR;
        }
        if (anchorTick == NO_ANCHOR) {
            // 无锚点帧三个位一律清掉：客户端据此算 NONE（宁可少画，绝不画出"有锚点"的条）
            flags = (byte) 0;
        }
    }

    /** 有没有锚点（bit0）。 */
    public boolean hasAnchor() {
        return (this.flags & FLAG_HAS_ANCHOR) != 0;
    }

    /** 锚点窗口是否已用完（bit1）。 */
    public boolean expired() {
        return (this.flags & FLAG_EXPIRED) != 0;
    }

    /** 锚点是否被标失效（bit2）。 */
    public boolean stale() {
        return (this.flags & FLAG_STALE) != 0;
    }

    /**
     * "没有锚点"的一帧（{@code anchor clear} 之后客户端要拿到的就是它 ⇒ 条消失）。
     *
     * @param nowTick     发这一帧时的世界 tick
     * @param windowTicks 窗口长度（客户端仍要知道口径，即使此刻没锚点）
     */
    public static ShanhaiAnchorBarFrame noAnchor(long nowTick, int windowTicks) {
        return new ShanhaiAnchorBarFrame(NO_ANCHOR, windowTicks, nowTick, false, 0L, 0L, 0L, (byte) 0);
    }

    /** 是否是"无锚点帧"（{@code anchor clear} / 倒流收尾之后）。 */
    public boolean isNoAnchor() {
        return !hasAnchor();
    }

    /** 一行文本（日志/判据用；字段顺序稳定，便于 grep）。 */
    public String toLine() {
        return "anchor_tick=" + this.anchorTick
                + " window=" + this.windowTicks
                + " now=" + this.nowTick
                + " replay=" + (this.replayActive ? 1 : 0)
                + " from=" + this.replayFromTick
                + " to=" + this.replayToTick
                + " cursor=" + this.replayCursorTick
                + " flags=" + (this.flags & 0xFF);
    }

    @Override
    public String toString() {
        return "AnchorBarFrame[" + toLine() + "]";
    }
}
