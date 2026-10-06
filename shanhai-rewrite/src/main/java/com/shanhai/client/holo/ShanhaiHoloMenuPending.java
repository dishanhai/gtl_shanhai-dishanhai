package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 全息菜单的「<b>点了之后正在加载</b>」状态机（<b>纯算术，不碰 Minecraft</b>）。
 *
 * <h2>1. 它存在的理由（用户的话）</h2>
 * <blockquote>
 *   全部通过，但是我发现点击之后会卡个 0.几秒再弹出面版，这并不是什么问题，毕竟需要加载时间，
 *   但是我希望可以有一个按钮按下的效果，然后可以告诉玩家正在加载，直到弹出面版
 * </blockquote>
 * 🔴 这一段<b>不是锦上添花</b>：上一轮那个真 bug 的体感就是「点了没有任何反应」——
 * <b>「真的在加载」和「真的没反应」在玩家眼里长得一模一样</b>。
 * 有了这个状态，"同类问题一眼就能分清"。
 *
 * <h2>2. 三个状态与它们的时间线</h2>
 * <pre>
 *   空闲  ── 空手点「配方修改」那一格 ──▶  加载中 ──┬─ 面板（Screen）出现 ──▶ 空闲 【正常路径】
 *                                              ├─ 超时（{@link #TIMEOUT_NANOS}）──▶ 空闲 【警告】
 *                                              └─ 投影被关掉 ──────────▶ 空闲
 * </pre>
 * 「面板出现了」的判据由调用方给（{@code Minecraft.screen != null}）——
 * 本类不碰 Minecraft，因此整条时间线<b>可以在没有游戏的机器上逐格验</b>（见 {@link #selfTest()}）。
 *
 * <h2>3. 🔴 本类【不】做的事</h2>
 * <ul>
 *   <li><b>不阻塞任何东西</b>：它只是一组读时间的纯函数，渲染每帧来读一次
 *       （用户点名的"别同步等待"）。</li>
 *   <li><b>不发包、不重试</b>：发包仍然只在点击那一拍做一次；
 *       本类只负责"这一拍是不是已经在加载了"这一个布尔，用来<b>挡住重复发包</b>。</li>
 * </ul>
 *
 * <h2>4. 所有时间函数都吃一个显式的 {@code nowNanos}</h2>
 * 不读 {@code System.nanoTime()} 是刻意的：只有把"现在几点"当参数传进来，
 * 自检才能构造"按下 0.1 秒后 / 超时 1 毫秒后"这种确定性的现场。
 */
public final class ShanhaiHoloMenuPending {

    private ShanhaiHoloMenuPending() {}

    // ==================================================================== 时间与观感常量

    /** 点了之后最多等这么久；到点还没看到面板就判定"没等到"，收掉提示并打一条警告。 */
    public static final long TIMEOUT_NANOS = 3_000_000_000L;

    /** "被按下去"这个动作冲到底所需要的秒数（之后一直保持按到底，直到状态收掉）。 */
    public static final float PRESS_RAMP_SEC = 0.10f;

    /** 按下时板在横向/纵向缩小的比例（0.10 = 缩 10% ⇒ 一块 2.3 格宽的板缩掉约 0.23 格，看得见）。 */
    public static final float PRESS_SHRINK = 0.10f;

    /** 按下时沿板面法线【往里按】多少（bu；26 bu ≈ 0.107 格）。正=朝观众，负=按进去。 */
    public static final float PRESS_DEPTH_BU = -26.0f;

    /** 按下时那块板的亮度加成（在原有亮度倍率上再乘 {@code 1 + 这个值 × 按压量 × 呼吸}）。 */
    public static final float PRESS_BRIGHT_BOOST = 0.55f;

    /** 加载进度条的走马灯周期（秒）——<b>不确定进度</b>，所以是循环扫而不是真百分比。 */
    public static final float SWEEP_PERIOD_SEC = 1.1f;

    /** 进度条高度（bu）。 */
    public static final float SWEEP_BAR_H_BU = 5.0f;

    /** 进度条左端（bu；与中文标签同一个左边距 {@code LABEL_LEFT_BU}）。 */
    public static final float SWEEP_LEFT_BU = 58.0f;

    /** 进度条总宽（bu）。 */
    public static final float SWEEP_WIDTH_BU = 300.0f;

    /** 进度条顶端距板中心的 y（bu）。 */
    public static final float SWEEP_TOP_Y_BU = -26.0f;

    /** 「正在加载」四个字的顶端距板中心的 y（bu）—— 放在原来英文副标题那一行。 */
    public static final float LOADING_TEXT_TOP_Y_BU = -7.0f;

    /** 提示文字（中文，与板子上其它中文同一套字体通道）。 */
    public static final String LOADING_TEXT = "正在加载";

    // ==================================================================== 收掉的原因码

    /** 还没收掉。 */
    public static final int REASON_NONE = 0;
    /** 面板出现了（正常路径）。 */
    public static final int REASON_SCREEN_OPENED = 1;
    /** 等超时了（异常路径，调用方应当打警告）。 */
    public static final int REASON_TIMEOUT = 2;
    /** 投影被关掉了。 */
    public static final int REASON_MENU_CLOSED = 3;

    // ==================================================================== 状态

    private static boolean pending;
    private static int boardIndex = -1;
    private static long startedNanos;

    // ==================================================================== 读写

    /** 现在是不是"点了、正在等面板"。 */
    public static boolean isPending() {
        return pending;
    }

    /** 正在等的那一格（0 基）；空闲时 {@code -1}。 */
    public static int boardIndex() {
        return pending ? boardIndex : -1;
    }

    /** 是不是"这一格正在被按下"。 */
    public static boolean isPressed(int index) {
        return pending && index == boardIndex;
    }

    /**
     * 开始加载。<b>已经在加载时不会被覆盖</b>（返回 {@code false}）——
     * 这一条就是"加载期间再点不许重复发包"的落点：调用方看返回值决定要不要发包。
     */
    public static boolean begin(int index, long nowNanos) {
        if (index < 0) {
            return false;
        }
        if (pending) {
            return false;
        }
        pending = true;
        boardIndex = index;
        startedNanos = nowNanos;
        return true;
    }

    /** 强制收掉（不打印、不判原因）。 */
    public static void clear() {
        pending = false;
        boardIndex = -1;
        startedNanos = 0L;
    }

    /**
     * 每客户端 tick 推一次。
     *
     * @param screenOpen  {@code Minecraft.screen != null}（面板已经出现）
     * @param menuEnabled 投影还开着吗（关掉了就没必要继续等）
     * @return {@link #REASON_NONE} = 还要继续显示；否则是"因为什么收掉的"
     */
    public static int onTick(boolean screenOpen, boolean menuEnabled, long nowNanos) {
        if (!pending) {
            return REASON_NONE;
        }
        if (!menuEnabled) {
            return finish(REASON_MENU_CLOSED, nowNanos);
        }
        if (screenOpen) {
            return finish(REASON_SCREEN_OPENED, nowNanos);
        }
        if (nowNanos - startedNanos >= TIMEOUT_NANOS) {
            return finish(REASON_TIMEOUT, nowNanos);
        }
        return REASON_NONE;
    }

    /** 收掉并返回原因。 */
    private static int finish(int reason, long nowNanos) {
        clear();
        return reason;
    }

    /** 原因码 ⇒ 中文（日志与报告都用它）。 */
    public static String reasonName(int reason) {
        return switch (reason) {
            case REASON_SCREEN_OPENED -> "面板已弹出";
            case REASON_TIMEOUT -> "等待超时";
            case REASON_MENU_CLOSED -> "投影已关闭";
            default -> "仍在加载";
        };
    }

    /** 已经等了多久（毫秒）；空闲返回 {@code -1}。 */
    public static long elapsedMs(long nowNanos) {
        return pending ? (nowNanos - startedNanos) / 1_000_000L : -1L;
    }

    // ==================================================================== 观感（纯函数）

    /**
     * <b>按压量</b>：{@code 0 → 1} 在 {@link #PRESS_RAMP_SEC} 秒内线性冲到底，之后<b>一直保持 1</b>。
     * <p>⚠️ 永不越过 1（自检里有负对照盯着这一条）—— 否则板会一直缩下去、越点越小。
     */
    public static float pressAmount(long nowNanos) {
        if (!pending) {
            return 0.0f;
        }
        final float sec = (nowNanos - startedNanos) / 1.0e9f;
        if (sec <= 0.0f) {
            return 0.0f;
        }
        if (PRESS_RAMP_SEC <= 0.0f || sec >= PRESS_RAMP_SEC) {
            return 1.0f;
        }
        return sec / PRESS_RAMP_SEC;
    }

    /**
     * <b>走马灯</b>：{@code 0 → 1 → 0} 循环（周期 {@link #SWEEP_PERIOD_SEC} 秒）。
     * <p>用它而不是"真百分比"是刻意的：客户端<b>不知道</b>服务端还要多久
     * （用户也说了"毕竟需要加载时间"）—— 画一个假的百分比条就等于在界面上放假数据
     * （本工程血规矩：<b>宁可缺，不可假</b>）。
     */
    public static float sweep01(long nowNanos) {
        if (!pending) {
            return 0.0f;
        }
        final float period = SWEEP_PERIOD_SEC;
        if (period <= 0.0f) {
            return 0.0f;
        }
        float p = ((nowNanos - startedNanos) / 1.0e9f % period) / period;
        if (p < 0.0f) {
            p += 1.0f;
        }
        return p;
    }

    /** 亮度呼吸（{@code 0.55 → 1.0}），给"正在加载"那块板用；不点的时候就返回 0。 */
    public static float breath(long nowNanos) {
        if (!pending) {
            return 0.0f;
        }
        final float wave = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * sweep01(nowNanos));
        return 0.55f + 0.45f * wave;
    }

    /** 一行读数（日志用）。 */
    public static String describe(long nowNanos) {
        if (!pending) {
            return "loading=false";
        }
        return "loading=true board_idx=" + boardIndex
                + " elapsed_ms=" + elapsedMs(nowNanos)
                + " press=" + String.format(Locale.ROOT, "%.2f", pressAmount(nowNanos))
                + " sweep=" + String.format(Locale.ROOT, "%.2f", sweep01(nowNanos));
    }

    /** 供离线自检使用：重置全部状态（正常玩法路径不会被调用）。 */
    public static void resetForTest() {
        clear();
    }

    // ==================================================================== 自检

    /** 自检结果。 */
    public record Report(int pass, int total, String bad) {
        public boolean ok() {
            return bad == null;
        }
    }

    /**
     * 时间线自检（<b>离线可跑，时间全部由参数注入</b>）。
     *
     * <p>判据里 <b>4 条是负对照</b>：
     * <pre>
     *   N1 "刚点下去 0.2 秒"不许被当成"面板出来了"（免得提示一闪就没）
     *   N2 按压量冲到 1 之后【不许继续涨】（否则板会越点越小）
     *   N3 已经在加载时再 begin ⇒ 必须被拒（这条就是"不许重复发包"的机器判据）
     *   N4 收掉之后按压量与走马灯必须【归零】（提示必须收干净，不能留一格残影）
     * </pre>
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};
        final long t0 = 1_000_000_000L;          // 任意起点，全部相对它算

        resetForTest();
        c(bad, n, "空闲时 isPending=false", isPending(), false);
        c(bad, n, "空闲时 boardIndex=-1", boardIndex(), -1);
        c(bad, n, "N3 负对照：空闲时 begin(-1) 必须被拒", begin(-1, t0), false);
        c(bad, n, "begin(1) 应当成功", begin(1, t0), true);
        c(bad, n, "加载中 isPending=true", isPending(), true);
        c(bad, n, "加载中 boardIndex=1", boardIndex(), 1);
        c(bad, n, "isPressed(1)=true", isPressed(1), true);
        c(bad, n, "isPressed(0)=false", isPressed(0), false);

        // N3：已经在加载时再 begin ⇒ 必须被拒
        c(bad, n, "N3 负对照：加载中再 begin(1) 必须被拒", begin(1, t0 + 50_000_000L), false);
        // N1：0.2 秒时"屏幕还没开"不许被当成收掉
        c(bad, n, "N1 负对照：0.2s 时无屏幕 ⇒ 继续等",
                onTick(false, true, t0 + 200_000_000L), REASON_NONE);
        c(bad, n, "0.5s 时仍继续等", onTick(false, true, t0 + 500_000_000L), REASON_NONE);

        // 按压量：0 → 1 之后保持 1（N2）
        c(bad, n, "刚按下时按压量=0", Math.abs(pressAmount(t0)) < 1.0e-6, true);
        c(bad, n, "冲到底时按压量=1",
                Math.abs(pressAmount(t0 + (long) (PRESS_RAMP_SEC * 1.0e9f) + 1L) - 1.0f) < 1.0e-6, true);
        c(bad, n, "N2 负对照：再等 10 倍时间按压量仍=1",
                Math.abs(pressAmount(t0 + (long) (PRESS_RAMP_SEC * 10.0e9f)) - 1.0f) < 1.0e-6, true);

        // 走马灯：周期口径（起点=0、半个周期≈0.5、一个周期回到 0）
        c(bad, n, "走马灯起点=0", Math.abs(sweep01(t0)) < 1.0e-6, true);
        c(bad, n, "走马灯半周期≈0.5",
                Math.abs(sweep01(t0 + (long) (SWEEP_PERIOD_SEC * 0.5e9f)) - 0.5f) < 1.0e-3, true);
        c(bad, n, "走马灯一周期回到 0",
                Math.abs(sweep01(t0 + (long) (SWEEP_PERIOD_SEC * 1.0e9f))) < 1.0e-3, true);
        // 口径对照：它必须真的在动（两个不同时刻不许一样）
        c(bad, n, "口径对照：走马灯在两个时刻不同",
                Math.abs(sweep01(t0 + 100_000_000L) - sweep01(t0 + 300_000_000L)) > 1.0e-3, true);

        // 正常路径：面板出现 ⇒ 收掉
        c(bad, n, "面板出现 ⇒ 收掉（原因=面板已弹出）",
                onTick(true, true, t0 + 600_000_000L), REASON_SCREEN_OPENED);
        c(bad, n, "收掉后 isPending=false", isPending(), false);
        // N4：收掉后不许留残影
        c(bad, n, "N4 负对照：收掉后按压量=0", Math.abs(pressAmount(t0 + 700_000_000L)) < 1.0e-6, true);
        c(bad, n, "N4 负对照：收掉后走马灯=0", Math.abs(sweep01(t0 + 700_000_000L)) < 1.0e-6, true);
        c(bad, n, "收掉后再 tick 不再报原因",
                onTick(true, true, t0 + 800_000_000L), REASON_NONE);

        // 异常路径 1：超时
        begin(2, t0);
        c(bad, n, "超时前 1ms 仍继续等",
                onTick(false, true, t0 + TIMEOUT_NANOS - 1_000_000L), REASON_NONE);
        c(bad, n, "超时 ⇒ 收掉（原因=等待超时）",
                onTick(false, true, t0 + TIMEOUT_NANOS + 1_000_000L), REASON_TIMEOUT);
        c(bad, n, "超时后 isPending=false", isPending(), false);

        // 异常路径 2：投影被关掉
        begin(2, t0);
        c(bad, n, "投影关掉 ⇒ 收掉（原因=投影已关闭）",
                onTick(false, false, t0 + 10_000_000L), REASON_MENU_CLOSED);
        c(bad, n, "投影关掉后 isPending=false", isPending(), false);

        resetForTest();
        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b);
    }

    /** 一行自检读数。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_loading_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok()
                + (r.ok() ? "（时间线：点了→加载中→面板出现/超时/投影关掉；含 4 条负对照）"
                : (" FAILED:" + r.bad()));
    }

    private static void c(StringBuilder bad, int[] n, String what, boolean actual, boolean expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("，应当 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, int actual, int expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：实际 ").append(actual)
                    .append("（").append(reasonName(actual)).append("）")
                    .append("，应当 ").append(expected).append(';');
        }
    }
}
