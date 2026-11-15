package com.shanhai.add.client.hud;

/**
 * 锚点进度条的<b>纯逻辑核</b>：喂一组 tick 数，算出「这条画不画 / 画成什么形态 / 填多满 / 写什么字」。
 *
 * <h2>0. 为什么单独拆一个零 MC 依赖的逻辑核</h2>
 * HUD 这种东西在游戏里只能靠肉眼看，出事（条不出现 / 条不反转 / 边界多显示一帧）在日志里
 * 一个字都留不下。所以把「算」和「画」切开：本类<b>一个 {@code net.minecraft.*} 都不 import</b>
 * ⇒ 可以脱离游戏、在 javac/JVM 里把真值表逐条跑（判据与负对照见 {@code temp/anchor-bar-selfcheck}）；
 * 画的那一侧（{@link ShanhaiAnchorBarHud}）就只剩「取状态 + 上屏」。
 *
 * <h2>1. 同一个 fraction，两个相反方向（这就是「反转」的全部含义）</h2>
 * <pre>
 *   {@link Mode#WINDOW}  锚点还能用的<b>剩余</b>比例：1 → 0（条从左往右退掉）
 *   {@link Mode#REWIND}  已经<b>回退</b>的比例　　  ：0 → 1（条反过来涨满）
 * </pre>
 * 两种形态共用 {@link #fraction()} 一个字段，方向由 {@link #mode()} 决定 ——
 * 「倒流时同一条进度条反转」不是一个独立控件，只是同一个字段反着走。
 *
 * <h2>2. 判定顺序（顺序就是判据顺序）</h2>
 * <pre>
 *   ① enabled == false                                     ⇒ NONE     整条不画
 *   ② anchorTick &lt; 0（没有锚点）                          ⇒ NONE
 *   ③ replaying == true                                    ⇒ REWIND   倒流中
 *   ④ age = nowTick - anchorTick &gt;= windowTicks           ⇒ EXPIRED  可见但 fraction = 0
 *   ⑤ 其余                                                 ⇒ WINDOW
 * </pre>
 *
 * <h2>3. 三个边界为什么这么定（都是刻意的，负对照就打在它们身上）</h2>
 * <ul>
 *   <li><b>age ≥ window ⇒ EXPIRED（而不是 {@code >}）</b>：窗口算的是「锚点之后那 1200 个 tick」，
 *       第 1200 个 tick 到来时窗口已经用完。写成 {@code >} 会让 {@code age=1200} 落在
 *       {@code WINDOW} + {@code fraction=0}（条空着却还算「能用」，标签还写着 1 秒）——自相矛盾的态。
 *       负对照：把这一处改回 {@code >}，边界那一行必须红。</li>
 *   <li><b>倒流优先于过期</b>：倒流本身可以跑过 60 秒。若让 EXPIRED 先命中，倒流到一半条就卡死在 0，
 *       而玩家明明正在往回走。</li>
 *   <li><b>age &lt; 0 夹成 0</b>：世界 tick 可能因为倒流/换维度出现「比锚点还早」的组合；
 *       这里只保证 fraction 永远落在 [0,1]，绝不把负宽度交给 HUD。</li>
 * </ul>
 *
 * <h2>4. 不带颜色码</h2>
 * {@link #label()} 是纯文本（日志、判据、以及没装语言文件时的兜底）；上屏那一侧优先走
 * {@code Component.translatable} 的 3 个语言键（{@code shanhai_add.anchor_bar.*}），
 * 用 {@link #value()} 当格式参数（WINDOW ≙ 剩余秒数，REWIND ≙ 已回退百分比）。
 */
public final class ShanhaiAnchorBarState {

    /** 20 tick = 1 秒。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 锚点窗口默认长度：1200 tick = 60 秒（用户要的就是「60s 进度条」）。 */
    public static final int DEFAULT_WINDOW_TICKS = 60 * TICKS_PER_SECOND;

    /** 「没有锚点」的写法：任何负数都算没有（世界 tick 从 0 起算，负数本来也不会是合法锚点）。 */
    public static final long NO_ANCHOR = -1L;

    /** 一条进度条只可能是这四种形态之一。 */
    public enum Mode {
        /** 不画（没有锚点 / 被关掉）。 */
        NONE,
        /** 锚点窗口内：fraction = 剩余比例，1 → 0。 */
        WINDOW,
        /** 倒流中：fraction = 已回退比例，0 → 1（「反转」）。 */
        REWIND,
        /** 窗口已过：可见但 fraction = 0。 */
        EXPIRED
    }

    /** 单例的「不画」：HUD 每帧都取一次状态，没必要每次都 new 一个。 */
    private static final ShanhaiAnchorBarState HIDDEN =
            new ShanhaiAnchorBarState(false, Mode.NONE, 0.0F, 0, "");

    private final boolean visible;
    private final Mode mode;
    private final float fraction;
    private final int value;
    private final String label;

    private ShanhaiAnchorBarState(boolean visible, Mode mode, float fraction, int value, String label) {
        this.visible = visible;
        this.mode = mode;
        this.fraction = fraction;
        this.value = value;
        this.label = label;
    }

    // ------------------------------------------------------------------ 工厂

    /** 不画（默认值；也是接缝没接线时的返回值）。 */
    public static ShanhaiAnchorBarState none() {
        return HIDDEN;
    }

    /**
     * 主入口：把一份输入算成一条状态。全参数版本，字段含义见 {@link Input}。
     *
     * <p>参数顺序刻意与需求里的输入清单一致（anchorTick / nowTick / windowTicks /
     * replayFromTick / replayToTick / replayCursorTick / enabled），接线方一行就能调。
     */
    public static ShanhaiAnchorBarState compute(boolean enabled,
                                               long anchorTick,
                                               long nowTick,
                                               long windowTicks,
                                               boolean replaying,
                                               long replayFromTick,
                                               long replayToTick,
                                               long replayCursorTick) {
        return evaluate(new Input(enabled, anchorTick, nowTick, windowTicks,
                replaying, replayFromTick, replayToTick, replayCursorTick));
    }

    /** 只要窗口态时的快捷入口（无倒流、60 秒窗口）。{@code anchorTick < 0} ⇒ {@link #none()}。 */
    public static ShanhaiAnchorBarState window(long anchorTick, long nowTick) {
        return evaluate(Input.window(anchorTick, nowTick));
    }

    /** 只要倒流态时的快捷入口（默认认为锚点存在、窗口 60 秒）。 */
    public static ShanhaiAnchorBarState rewind(long replayFromTick, long replayToTick, long replayCursorTick) {
        return evaluate(Input.rewind(replayFromTick, replayToTick, replayCursorTick));
    }

    /** 主入口（按 {@link Input} 算）。{@code in == null} 等同于 {@link #none()}。 */
    public static ShanhaiAnchorBarState evaluate(Input in) {
        if (in == null || !in.enabled()) {
            return HIDDEN;                                  // ① 关掉：整条不画
        }
        if (in.anchorTick() < 0L) {
            return HIDDEN;                                  // ② 没有锚点
        }
        final long windowTicks = in.windowTicks() > 0L ? in.windowTicks() : DEFAULT_WINDOW_TICKS;

        if (in.replaying()) {                               // ③ 倒流中：fraction 从 0 涨到 1
            return rewindState(in.replayFromTick(), in.replayToTick(), in.replayCursorTick());
        }

        long age = in.nowTick() - in.anchorTick();
        if (age < 0L) {
            age = 0L;                                       // §3 第三条：夹到 0，别把负值漏出去
        }
        if (age >= windowTicks) {                           // ④ 窗口用完（含边界那一 tick）
            return new ShanhaiAnchorBarState(true, Mode.EXPIRED, 0.0F, 0, "锚点 已过期");
        }

        final long remaining = windowTicks - age;           // ⑤ 窗口内：1 → 0
        final float fraction = (float) ((double) remaining / (double) windowTicks);
        // 秒数向上取整：还剩 1 tick 也显示 1 秒，绝不出现「0s 却还能用」这种读不通的标签。
        final int seconds = (int) ((remaining + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
        return new ShanhaiAnchorBarState(true, Mode.WINDOW, fraction, seconds, "锚点 " + seconds + "s");
    }

    /** 倒流态：fraction = (from - cursor) / (from - to)，即「已经往回退了多少」，0 → 1。 */
    private static ShanhaiAnchorBarState rewindState(long fromTick, long toTick, long cursorTick) {
        final long span = fromTick - toTick;
        if (span <= 0L) {
            // 退化输入（from <= to）：没有可回退的距离，按「已走完」处理，绝不除零。
            return new ShanhaiAnchorBarState(true, Mode.REWIND, 1.0F, 100, "倒流 100%");
        }
        long travel = fromTick - cursorTick;                // cursor 从 from 走向 to ⇒ travel 0 → span
        if (travel < 0L) {
            travel = 0L;                                    // cursor 跑到 from 之外 ⇒ 夹住
        } else if (travel > span) {
            travel = span;
        }
        final float fraction = (float) ((double) travel / (double) span);
        final int percent = Math.round(fraction * 100.0F);
        return new ShanhaiAnchorBarState(true, Mode.REWIND, fraction, percent, "倒流 " + percent + "%");
    }

    // ------------------------------------------------------------------ 输出

    /** 是否要画。 */
    public boolean visible() {
        return this.visible;
    }

    public Mode mode() {
        return this.mode;
    }

    /** 0..1。WINDOW ⇒ 剩余比例；REWIND ⇒ 已回退比例；EXPIRED / NONE ⇒ 0。 */
    public float fraction() {
        return this.fraction;
    }

    /** 语言文件格式用的整数：WINDOW ⇒ 剩余秒数（向上取整）；REWIND ⇒ 已回退百分比。 */
    public int value() {
        return this.value;
    }

    /** 纯文本标签（无颜色码）：「锚点 42s」/「倒流 37%」/「锚点 已过期」/ 空串。 */
    public String label() {
        return this.label;
    }

    /** 是否处于倒流态（HUD 据此把填充方向反过来：从右往左涨）。 */
    public boolean rewinding() {
        return this.mode == Mode.REWIND;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShanhaiAnchorBarState)) {
            return false;
        }
        final ShanhaiAnchorBarState other = (ShanhaiAnchorBarState) o;
        return this.visible == other.visible
                && this.mode == other.mode
                && Float.compare(this.fraction, other.fraction) == 0
                && this.value == other.value
                && this.label.equals(other.label);
    }

    @Override
    public int hashCode() {
        int h = Boolean.hashCode(this.visible);
        h = 31 * h + this.mode.hashCode();
        h = 31 * h + Float.floatToIntBits(this.fraction);
        h = 31 * h + this.value;
        h = 31 * h + this.label.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "AnchorBar[" + this.mode
                + " visible=" + this.visible
                + " fraction=" + this.fraction
                + " value=" + this.value
                + " label=\"" + this.label + "\"]";
    }

    // ------------------------------------------------------------------ 输入

    /**
     * 一次计算的全部输入。<b>不可变</b>（record），谁都能安全地按帧构造。
     *
     * @param enabled         总开关（配置/条件不符时整条不画）
     * @param anchorTick      设锚点那一刻的世界 tick；&lt; 0 表示<b>没有锚点</b>（见 {@link #NO_ANCHOR}）
     * @param nowTick         当前世界 tick
     * @param windowTicks     窗口长度（tick）；&lt;= 0 时退回 {@link #DEFAULT_WINDOW_TICKS}
     * @param replaying       是否正在倒流
     * @param replayFromTick  倒流的起点 tick（回退前的世界时刻，数值较大）
     * @param replayToTick    倒流的目标 tick（要回到的世界时刻，数值较小）；{@code from - to} 就是总回退距离
     * @param replayCursorTick 倒流游标当前所在的 tick（从 from 递减走向 to）
     */
    public record Input(boolean enabled,
                        long anchorTick,
                        long nowTick,
                        long windowTicks,
                        boolean replaying,
                        long replayFromTick,
                        long replayToTick,
                        long replayCursorTick) {

        /** 窗口态：默认开着、默认 60 秒、无倒流。{@code anchorTick < 0} ⇒ 算出来是 {@link #none()}。 */
        public static Input window(long anchorTick, long nowTick) {
            return new Input(true, anchorTick, nowTick, DEFAULT_WINDOW_TICKS, false, 0L, 0L, 0L);
        }

        /** 倒流态：锚点当作存在（{@code anchorTick = 0}）、窗口默认 60 秒。 */
        public static Input rewind(long replayFromTick, long replayToTick, long replayCursorTick) {
            return new Input(true, 0L, replayFromTick, DEFAULT_WINDOW_TICKS,
                    true, replayFromTick, replayToTick, replayCursorTick);
        }

        /** 同一份输入换个总开关（关心 {@code enabled=false} 那条判据时用）。 */
        public Input withEnabled(boolean value) {
            return new Input(value, this.anchorTick, this.nowTick, this.windowTicks,
                    this.replaying, this.replayFromTick, this.replayToTick, this.replayCursorTick);
        }

        /** 同一份输入换个窗口长度（非法值 &lt;= 0 会被 {@link #evaluate} 退回默认值）。 */
        public Input withWindowTicks(long value) {
            return new Input(this.enabled, this.anchorTick, this.nowTick, value,
                    this.replaying, this.replayFromTick, this.replayToTick, this.replayCursorTick);
        }

        /** 同一份输入换成「没有锚点」。 */
        public Input withoutAnchor() {
            return new Input(this.enabled, NO_ANCHOR, this.nowTick, this.windowTicks,
                    this.replaying, this.replayFromTick, this.replayToTick, this.replayCursorTick);
        }
    }
}
