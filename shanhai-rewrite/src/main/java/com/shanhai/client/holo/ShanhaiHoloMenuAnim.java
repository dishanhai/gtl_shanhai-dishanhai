package com.shanhai.client.holo;

import java.util.Locale;

/**
 * 山海重构 · 全息菜单的「<b>从玩家脚下舒展开 / 关闭时收回</b>」动画状态机
 * （<b>纯算术，不碰 Minecraft</b>）。
 *
 * <h2>1. 用户的话</h2>
 * <blockquote>
 *   还有，模块打开的时候这个全息投影突然出现有点不太好看，
 *   我希望打开时有从玩家脚下，舒展开成最终的样子，然后这个过程差不多 0.几秒吧，
 *   然后关闭时再收回
 * </blockquote>
 *
 * <h2>2. 动画是怎么构成的（一条总变换，不动任何一块板的几何）</h2>
 * 全部效果都作用在<b>全息本体那一层</b>（{@code 锚点 → 框偏航} 之后、{@code pushBoard} 之前），
 * 所以五块板的相对几何<b>一个数都没改</b> —— 那套观感是你已经验收过的。
 * <pre>
 *   p   = {@link #open01()}     0 = 完全收起（在玩家脚下、缩成一个点、不亮）；1 = 最终形态
 *   e   = {@link #ease01()}     缓出曲线 1−(1−p)³ ⇒ "先舒展开、再稳稳停住"
 *   原点 = 玩家脚底 → 插值 → 锚点      （{@link #originLerp} 给插值系数）
 *   缩放 = 0.10 → 1.0                 （{@link #scale01}）
 *   自转 = −{@link #SPIN_MAX_DEG}° → 0°（{@link #spinDeg}，整块像"旋开"）
 *   透明度 = 0 → 1                    （{@link #alpha01}）
 * </pre>
 * ⇒ 视觉上就是「<b>从你脚底起、边长边转边亮地摊开</b>」；关闭时同一套式子倒着走。
 *
 * <h2>3. 🔴 三条必须做对的事（都有自检盯着）</h2>
 * <ol>
 *   <li><b>中途反转不许跳</b>：进度是"每秒走 1/时长"的连续量，反转只是把方向取反
 *       ⇒ 从 0.42 开始收就是 0.42→0，<b>不会先弹回 1</b>（自检里有负对照）。</li>
 *   <li><b>不许卡住</b>：反转到头一定停在 0 / 1（自检里走到底）。</li>
 *   <li><b>时长要能读出来</b>：每段走完返回一个事件，带上这一段实际花了多少毫秒，
 *       调用方打一行 {@code holo_anim open ms=…}（用户点名的判据）。</li>
 * </ol>
 *
 * <h2>4. 为什么所有时间函数都吃一个显式的 {@code nowNanos}</h2>
 * 同 {@link ShanhaiHoloMenuPending}：只有把"现在几点"当参数传进来，
 * 自检才能构造"开了 0.1 秒 / 关了 0.2 秒 / 走到一半反转"这种确定性现场。
 */
public final class ShanhaiHoloMenuAnim {

    private ShanhaiHoloMenuAnim() {}

    // ==================================================================== 数值

    /** 打开动画时长（秒）——用户要的"0.几秒"。 */
    public static final float DURATION_OPEN_SEC = 0.35f;

    /** 关闭动画时长（秒）。比打开略短：收起来不该让人等。 */
    public static final float DURATION_CLOSE_SEC = 0.28f;

    /** 单帧最大步进（秒）。窗口失焦/卡顿后回来时不许一跳到底（同 ShanhaiHoloMenuState 的口径）。 */
    public static final float MAX_FRAME_DT_SEC = 0.25f;

    /** 收起状态下的整块缩放（不是 0：留一点点，能看到它"是一团东西"而不是凭空出现）。 */
    public static final float MIN_SCALE = 0.10f;

    /** 展开时整块多转多少度（从 −这个值 转到 0）。 */
    public static final float SPIN_MAX_DEG = 68.0f;

    // ==================================================================== 事件码

    public static final int EV_NONE = 0;
    /** 展开走完（到最终形态）。 */
    public static final int EV_OPENED = 1;
    /** 收回走完（彻底收起）。 */
    public static final int EV_CLOSED = 2;
    /** 走到一半被反转成"往回收"。 */
    public static final int EV_REVERSED_TO_CLOSE = 3;
    /** 收到一半被反转成"重新展开"。 */
    public static final int EV_REVERSED_TO_OPEN = 4;

    /** 一次推进的结果。 */
    public record Step(int event, float progress, long elapsedMs) {
        public boolean changed() {
            return event != EV_NONE;
        }
    }

    // ==================================================================== 状态

    /** 0..1：0 = 完全收起，1 = 最终形态。 */
    private static float progress;
    /** 正在朝哪边去（0 / 1）。 */
    private static int target;
    /** 上一帧的时刻。 */
    private static long lastNanos;
    /** 当前这一段（本次目标定下之后）的起点时刻与起点进度——用来算"这段花了多久"。 */
    private static long segStartNanos;
    private static float segStartProgress;
    private static boolean primed;
    /** 已经【停在】目标上、并且那一次到达已经报过了（防止闲置的每一帧都报一次"收回完毕"）。 */
    private static boolean settled = true;

    // ==================================================================== 推帧

    /**
     * 每渲染帧推进一次。
     *
     * @param enabled   逻辑状态（{@code ShanhaiHoloMenuState.enabled()}）—— 目标是 1 还是 0 由它定
     * @param nowNanos  {@code System.nanoTime()}
     * @return 本帧发生了什么（{@link Step#changed()} 为真时调用方打一行日志）
     */
    public static Step advanceFrame(boolean enabled, long nowNanos) {
        final int want = enabled ? 1 : 0;

        if (!primed) {
            // 第一次：直接落到目标，不做动画（否则进世界的那一瞬间会"凭空展开"一次）
            primed = true;
            lastNanos = nowNanos;
            segStartNanos = nowNanos;
            target = want;
            progress = want;
            segStartProgress = progress;
            settled = true;
            return new Step(EV_NONE, progress, 0L);
        }

        float dt = (nowNanos - lastNanos) / 1.0e9f;
        lastNanos = nowNanos;
        if (dt < 0.0f) {
            dt = 0.0f;
        }
        if (dt > MAX_FRAME_DT_SEC) {
            dt = MAX_FRAME_DT_SEC;      // 卡顿/失焦后回来不许一跳到底
        }

        boolean reversed = false;
        if (want != target) {
            // 🔴 反转：只改方向，【不动 progress】⇒ 从当前位置接着往回走，不会跳
            target = want;
            segStartNanos = nowNanos;
            segStartProgress = progress;
            settled = false;
            reversed = true;
        }

        final float duration = target == 1 ? DURATION_OPEN_SEC : DURATION_CLOSE_SEC;
        if (duration > 0.0f && dt > 0.0f) {
            progress += (target == 1 ? 1.0f : -1.0f) * (dt / duration);
        }
        // 两端夹紧（负对照：给它一个远大于时长的 dt 也不许越过 0 / 1）
        if (progress >= 1.0f) {
            progress = 1.0f;
        } else if (progress <= 0.0f) {
            progress = 0.0f;
        }

        final boolean atTarget = target == 1 ? progress >= 1.0f : progress <= 0.0f;
        final int event;
        long elapsed = 0L;
        if (atTarget && !settled) {
            // 这一次"走到头"刚刚发生 ⇒ 报事件 + 这一段实际花了多久
            settled = true;
            event = target == 1 ? EV_OPENED : EV_CLOSED;
            // ⚠️ 顺序要紧：先把时长算出来，再把这一段的起点推到"现在"。
            //    第一版把起点提前推了（写在一个单独的"已完全收起"分支里），
            //    于是收回的时长读数永远是 0 ms —— 是自检里那条"时长≈280ms"当场抓出来的。
            elapsed = (nowNanos - segStartNanos) / 1_000_000L;
            segStartNanos = nowNanos;
            segStartProgress = progress;
        } else if (reversed) {
            event = target == 1 ? EV_REVERSED_TO_OPEN : EV_REVERSED_TO_CLOSE;
            elapsed = (nowNanos - segStartNanos) / 1_000_000L;
        } else {
            event = EV_NONE;
        }
        if (elapsed < 0L) {
            elapsed = 0L;
        }
        return new Step(event, progress, elapsed);
    }

    // ==================================================================== 读数

    /** 原始进度 0..1。 */
    public static float open01() {
        return progress;
    }

    /** 缓出曲线 {@code 1−(1−p)³}：开头快、收尾稳（"舒展开"的手感）。 */
    public static float ease01() {
        return ease(progress);
    }

    /** 缓出本身（纯函数，自检直接调它）。 */
    public static float ease(float p) {
        final float x = p <= 0.0f ? 0.0f : (p >= 1.0f ? 1.0f : p);
        final float inv = 1.0f - x;
        return 1.0f - inv * inv * inv;
    }

    /** 整块缩放：{@link #MIN_SCALE} → 1。 */
    public static float scale01() {
        return MIN_SCALE + (1.0f - MIN_SCALE) * ease01();
    }

    /** 整块附加自转（度）：{@code −SPIN_MAX_DEG} → 0。 */
    public static float spinDeg() {
        return -SPIN_MAX_DEG * (1.0f - ease01());
    }

    /** 整体透明度：0 → 1。 */
    public static float alpha01() {
        return ease01();
    }

    /** 收起 / 展开的插值系数（渲染器拿它把原点从玩家脚底插到锚点）。 */
    public static float originLerp() {
        return ease01();
    }

    /** 现在是不是"完全不显示"（渲染器用它做最便宜的提前返回）。 */
    public static boolean hidden() {
        return progress <= 0.0f;
    }

    /** 是不是正在动（诊断用）。 */
    public static boolean animating() {
        return progress > 0.0f && progress < 1.0f;
    }

    /** 事件码 ⇒ 中文（日志用）。 */
    public static String eventName(int event) {
        return switch (event) {
            case EV_OPENED -> "展开完毕";
            case EV_CLOSED -> "收回完毕";
            case EV_REVERSED_TO_CLOSE -> "中途反转为收回";
            case EV_REVERSED_TO_OPEN -> "中途反转为展开";
            default -> "无";
        };
    }

    /** 一行读数。 */
    public static String describe() {
        return "anim_progress=" + String.format(Locale.ROOT, "%.3f", progress)
                + " target=" + target
                + " scale=" + String.format(Locale.ROOT, "%.3f", scale01())
                + " spin_deg=" + String.format(Locale.ROOT, "%.1f", spinDeg())
                + " alpha=" + String.format(Locale.ROOT, "%.3f", alpha01());
    }

    /** 供离线自检使用：重置全部状态。 */
    public static void resetForTest() {
        progress = 0.0f;
        target = 0;
        lastNanos = 0L;
        segStartNanos = 0L;
        segStartProgress = 0.0f;
        primed = false;
        settled = true;
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
     *   N1 走到一半反转 ⇒ 进度必须【接着降】，不许先弹回 1
     *   N2 给一个 10 秒的 dt ⇒ 进度必须被夹在 0..1（不许越界）
     *   N3 dt=0 ⇒ 进度必须【一点都不动】（免得掉帧时动画瞬间走完）
     *   N4 首次推进必须【直接就位】（不许进世界时凭空播一次展开动画）
     * </pre>
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};
        final long t0 = 5_000_000_000L;
        final long openMs = (long) (DURATION_OPEN_SEC * 1000.0f);
        final long closeMs = (long) (DURATION_CLOSE_SEC * 1000.0f);

        // N4：首次推进直接就位
        resetForTest();
        Step s = advanceFrame(false, t0);
        c(bad, n, "N4 负对照：首次推进不动画（关着 ⇒ 直接 0）", s.event(), EV_NONE);
        c(bad, n, "N4 负对照：首次推进后进度=0", Math.abs(open01()) < 1.0e-6, true);
        c(bad, n, "收起状态下 hidden()=true", hidden(), true);

        // 打开：一路上升，走完报 EV_OPENED，时长≈DURATION_OPEN
        long t = t0;
        s = advanceFrame(true, t);                 // 反转事件
        c(bad, n, "刚开时是'中途反转为展开'", s.event(), EV_REVERSED_TO_OPEN);
        for (int i = 0; i < 4; i++) {
            t += (long) (DURATION_OPEN_SEC * 250_000_000.0f);   // 每步 1/4 时长
            s = advanceFrame(true, t);
        }
        c(bad, n, "走完打开动画报 EV_OPENED", s.event(), EV_OPENED);
        c(bad, n, "打开后进度=1", Math.abs(open01() - 1.0f) < 1.0e-6, true);
        c(bad, n, "打开时长读数≈" + openMs + "ms（容差 40ms）",
                Math.abs(s.elapsedMs() - openMs) <= 40L, true);
        c(bad, n, "打开后 hidden()=false", hidden(), false);
        c(bad, n, "打开后 animating()=false", animating(), false);

        // 关闭：一路下降到 0，报 EV_CLOSED
        t += 10_000_000L;
        s = advanceFrame(false, t);
        c(bad, n, "刚关时是'中途反转为收回'", s.event(), EV_REVERSED_TO_CLOSE);
        for (int i = 0; i < 4; i++) {
            t += (long) (DURATION_CLOSE_SEC * 250_000_000.0f);
            s = advanceFrame(false, t);
        }
        c(bad, n, "走完关闭动画报 EV_CLOSED", s.event(), EV_CLOSED);
        c(bad, n, "关闭后进度=0", Math.abs(open01()) < 1.0e-6, true);
        c(bad, n, "关闭时长读数≈" + closeMs + "ms（容差 40ms）",
                Math.abs(s.elapsedMs() - closeMs) <= 40L, true);
        c(bad, n, "关闭后 hidden()=true", hidden(), true);

        // ---- N1：走到一半反转，进度必须接着降 ----
        t += 10_000_000L;
        advanceFrame(true, t);                                  // 开始展开
        t += (long) (DURATION_OPEN_SEC * 0.5e9f);               // 走到约一半
        s = advanceFrame(true, t);
        final float half = open01();
        final boolean halfOk = half > 0.30f && half < 0.70f;
        c(bad, n, "走到一半时进度落在 0.30..0.70", halfOk, true);
        final float before = open01();
        t += 16_000_000L;
        s = advanceFrame(false, t);                             // 反转
        c(bad, n, "反转当帧报 EV_REVERSED_TO_CLOSE", s.event(), EV_REVERSED_TO_CLOSE);
        c(bad, n, "N1 负对照：反转后进度【接着降】（不许弹回 1）", open01() < before, true);
        c(bad, n, "N1 负对照：反转后进度仍 > 0（不是跳回 0）", open01() > 0.0f, true);

        // ---- N3：dt=0 ⇒ 一点都不动 ----
        final float frozen = open01();
        s = advanceFrame(false, t);
        c(bad, n, "N3 负对照：dt=0 时进度不变", Math.abs(open01() - frozen) < 1.0e-6, true);
        c(bad, n, "N3 负对照：dt=0 时不报事件", s.event(), EV_NONE);

        // ---- N2：给一个远大于时长的 dt，进度必须仍落在 0..1，而且【最多只走一帧的份额】----
        t += 10_000_000_000L;
        s = advanceFrame(false, t);
        c(bad, n, "N2 负对照：10s 的 dt 只走一帧（进度=0，因为本来就在 0）", Math.abs(open01()) < 1.0e-6, true);
        c(bad, n, "N2 负对照：那一下报 EV_CLOSED", s.event(), EV_CLOSED);
        t += 10_000_000_000L;
        s = advanceFrame(true, t);          // 开始展开，同样是 10s 的 dt
        final float oneFrame = MAX_FRAME_DT_SEC / DURATION_OPEN_SEC;
        c(bad, n, "N2 负对照：10s 的 dt 不许一跳到底（进度=" + String.format(Locale.ROOT, "%.3f", open01())
                        + "，应当≈" + String.format(Locale.ROOT, "%.3f", oneFrame) + "）",
                Math.abs(open01() - oneFrame) < 0.02f, true);
        c(bad, n, "N2 负对照：那一拍的进度 < 1", open01() < 1.0f, true);
        // 连着几拍 10 秒的 dt：最终必须【恰好】停在 1，绝不许越界
        boolean sawOpen = false;
        for (int i = 0; i < 8; i++) {
            t += 10_000_000_000L;
            s = advanceFrame(true, t);
            if (s.event() == EV_OPENED) {
                sawOpen = true;
            }
        }
        c(bad, n, "N2 负对照：多拍之后进度恰好=1（夹紧，不越界）", Math.abs(open01() - 1.0f) < 1.0e-6, true);
        c(bad, n, "N2 负对照：多拍之后报过 EV_OPENED", sawOpen, true);
        // 已停在目标上之后 ⇒ 不许每帧都报事件
        s = advanceFrame(true, t + 16_000_000L);
        c(bad, n, "N2 负对照：停在 1 上以后不再重复报事件", s.event(), EV_NONE);
        t += 10_000_000_000L;
        s = advanceFrame(false, t);
        for (int i = 0; i < 8; i++) {
            t += 10_000_000_000L;
            s = advanceFrame(false, t);
        }
        c(bad, n, "N2 负对照：收回后进度恰好=0", Math.abs(open01()) < 1.0e-6, true);
        s = advanceFrame(false, t + 16_000_000L);
        c(bad, n, "N2 负对照：停在 0 上以后不再重复报事件", s.event(), EV_NONE);

        // ---- 曲线：缓出、缩放、自转、透明 ----
        c(bad, n, "ease(0)=0", Math.abs(ease(0.0f)) < 1.0e-6, true);
        c(bad, n, "ease(1)=1", Math.abs(ease(1.0f) - 1.0f) < 1.0e-6, true);
        c(bad, n, "缓出在前半段已走过一半以上（ease(0.5)>0.5）", ease(0.5f) > 0.5f, true);
        c(bad, n, "缓出不会越过 1（ease(1.5)=1）", Math.abs(ease(1.5f) - 1.0f) < 1.0e-6, true);
        resetForTest();
        advanceFrame(false, t0);
        c(bad, n, "收起时缩放=" + MIN_SCALE, Math.abs(scale01() - MIN_SCALE) < 1.0e-6, true);
        c(bad, n, "收起时自转=" + (-SPIN_MAX_DEG), Math.abs(spinDeg() + SPIN_MAX_DEG) < 1.0e-4f, true);
        c(bad, n, "收起时透明度=0", Math.abs(alpha01()) < 1.0e-6, true);
        resetForTest();
        advanceFrame(true, t0);
        advanceFrame(true, t0 + (long) (DURATION_OPEN_SEC * 1.5e9f));
        c(bad, n, "展开完毕后缩放=1", Math.abs(scale01() - 1.0f) < 1.0e-6, true);
        c(bad, n, "展开完毕后自转=0", Math.abs(spinDeg()) < 1.0e-4f, true);
        c(bad, n, "展开完毕后透明度=1", Math.abs(alpha01() - 1.0f) < 1.0e-6, true);
        c(bad, n, "展开完毕后插值系数=1（原点=锚点，与动画前逐位一致）",
                Math.abs(originLerp() - 1.0f) < 1.0e-6, true);

        resetForTest();
        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b);
    }

    /** 一行自检读数。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_anim_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok()
                + (r.ok() ? "（展开 " + DURATION_OPEN_SEC + "s / 收回 " + DURATION_CLOSE_SEC
                + "s；含 4 条负对照：中途反转不跳 / 超大 dt 夹紧 / dt=0 不动 / 首次推进不播）"
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
                    .append("（").append(eventName(actual)).append("）")
                    .append("，应当 ").append(expected).append(';');
        }
    }
}
