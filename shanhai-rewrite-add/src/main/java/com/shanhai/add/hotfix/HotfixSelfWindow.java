package com.shanhai.add.hotfix;

/**
 * 🆕 2026-11-21（根因③）：<b>我们自己的 retransform 窗口内的抑制位</b>。
 *
 * <h2>它防的是什么</h2>
 * 一次 {@code retransformClasses} 会回调<b>所有</b>可重定义的 transformer，还会让类被重定义／
 * 反优化；而场上还有"检测到类变化"的逻辑（违规栈观测、读回取样、10 s 存活哨兵）。
 * 那段逻辑如果在<b>我们自己正在写</b>的时候跑，看到的就是"类正在变" —— 于是它判定"补丁丢了"，
 * 判定的动作又是<b>再打一次</b> ⇒ 自己触发自己（自反馈），这正是
 * {@code retransform_count=7} 一路涨、字节一路变短的另一半机制。
 *
 * <h2>口径（用户任务书）</h2>
 * <blockquote>
 *   「我们自己的 retransform 窗口内置一个 ThreadLocal 抑制标记，期间任何"检测到类变化"的逻辑
 *    一律跳过（防自反馈）。日志 {@code patch_suppressed_self count=<N>}（正常应为 0 或极少）。」
 * </blockquote>
 * ⇒ 用 {@link ThreadLocal} 而不是全局标志位：别的线程（服务端 tick、渲染线程）该照常观测，
 * 只有"正在写字节的那条线程"需要闭嘴。全局标志会把真实证据一起吞掉。
 *
 * <h2>为什么 count 是"闸门"，不是"指标"</h2>
 * 正常应为 0 或极少。一旦它开始涨，说明<b>我们的写入窗口里确实有检测逻辑在跑</b> ——
 * 那是一个真问题（窗口开得太宽，或某个检测点挂在别的线程上），必须能看见。
 */
public final class HotfixSelfWindow {

    /** 每个线程自己的嵌套深度（retransform 窗口可以嵌套：apply 里套 undo 之类）。 */
    private static final class Depth {

        int n;

        String what = "";
    }

    private static final ThreadLocal<Depth> DEPTH = new ThreadLocal<Depth>();

    /** 被抑制掉几次（累计）。 */
    private static long skipped;

    /** 最后一次被抑制的是哪个检测点（诊断；空串 = 没有）。 */
    private static String lastWhat = "";

    private static long lastAtMillis;

    private HotfixSelfWindow() {
        // 纯静态
    }

    /** 进窗口（必须成对：{@code finally { exit(); }}）。 */
    public static void enter(String what) {
        Depth d = DEPTH.get();
        if (d == null) {
            d = new Depth();
            DEPTH.set(d);
        }
        d.n++;
        d.what = what == null ? "" : what;
    }

    /** 出窗口；深度回 0 时把 ThreadLocal 摘掉（线程池里的线程不许留下垃圾）。 */
    public static void exit() {
        final Depth d = DEPTH.get();
        if (d == null) {
            return;
        }
        if (d.n > 0) {
            d.n--;
        }
        if (d.n <= 0) {
            d.what = "";
            DEPTH.remove();
        }
    }

    /** 当前线程在不在"我们自己的写入窗口"里。 */
    public static boolean active() {
        final Depth d = DEPTH.get();
        return d != null && d.n > 0;
    }

    /**
     * 检测点统一入口：<b>在窗口里就跳过</b>并记账。
     *
     * @param what 检测点名（{@code violation_stack}／{@code readback}／{@code keepalive}…）
     * @return {@code true} = 请不要观测／不要下结论（这一次是我们自己在动）
     */
    public static boolean skip(String what) {
        if (!active()) {
            return false;
        }
        synchronized (HotfixSelfWindow.class) {
            skipped++;
            lastWhat = what == null ? "" : what;
            lastAtMillis = System.currentTimeMillis();
        }
        return true;
    }

    /** 累计被抑制几次。 */
    public static synchronized long count() {
        return skipped;
    }

    /** 最后一次被抑制的检测点。 */
    public static synchronized String lastWhat() {
        return lastWhat;
    }

    public static synchronized long lastAtMillis() {
        return lastAtMillis;
    }

    /** {@code patch_suppressed_self count=<N>}。 */
    public static synchronized String line() {
        return "patch_suppressed_self count=" + skipped;
    }

    /** 停服／退出世界：计数归零（窗口本身按线程开合，不需要清）。 */
    public static synchronized void reset() {
        skipped = 0L;
        lastWhat = "";
        lastAtMillis = 0L;
    }
}
