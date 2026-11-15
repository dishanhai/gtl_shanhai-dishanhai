package com.shanhai.add.time.replay;

/**
 * 山海重构 · <b>收尾"关账"的顺序护栏</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 🔴 根因（2026-11-14 真机实测，用户原话）</h2>
 * <blockquote>「倒放结束之后时间没有正常流动，下面的锚点进度条也没消失」</blockquote>
 * 日志实证（12:30，倒流结束 6 分钟后）：{@code clock_player_tick kept=1 folded=943}、
 * {@code clock_entity_probe … dy_now=0.0000}（实体仍冻结）、
 * {@code ANCHORBAR send reason=window_tick anchor_tick=7073 window=1200 now=8085}（条还在推）。
 * 排查结论（不是异常，是<b>提前 return</b>）：
 * <pre>
 *   ShanhaiReplayPacer#step()  :490-499  算出 ANCHOR/BUDGET 时【自己】把 active 置 false
 *   ShanhaiReplayService#tick  :435       finishAtAnchor(server, "anchor_reached")
 *   ShanhaiReplayService#stop  :517       if (!PACER.isActive()) return false;   ← 一进门就返回
 *                                         ⇒ 旧第 543 行 setMode(LIVE) 永远跑不到
 * </pre>
 * ⇒ 世界永久冻结（{@code ServerLevel#tick} 整段取消）、{@code noSave} 永久 true、
 *   锚点永不清（{@code journal.clearAnchor()} 也在那行之后）。
 *
 * <h2>2. 本类的职责只有一条</h2>
 * 把"<b>必须跑完、且必须在任何可能抛异常的代码之前</b>"的三件事依次做掉：
 * <pre>
 *   ① PACER.reset()                       —— 节拍器停
 *   ② ShanhaiReplayGate.resetReplayWrite() —— 关掉"替回放写世界"的后门
 *   ③ SERVER.setMode(mode_before)（bump epoch） —— 回到进来之前的模式（PAUSED→PAUSED、LIVE→LIVE）
 * </pre>
 * 任一件抛异常 ⇒ <b>记一行并继续做剩下的</b>（关账不许半途而废：宁可少 reset 一次节拍器，
 * 也不能让世界停在冻结态）。
 *
 * <h2>3. 为什么这三件事要经过一个纯类</h2>
 * 判据要求"<b>负对照：把 setMode(LIVE) 挪回原位置（在可能抛异常的代码之后）⇒ 构造一次异常
 * （注入 throw）应使判据红</b>"。顺序本身没有 Minecraft 依赖，所以可以离线驱动：
 * 自检给三个 {@code Runnable}，其中一个抛 ⇒ 断言后两个仍然被执行、且返回 {@code false}；
 * 突变体（旧顺序：先干活再 setLive，干活抛）⇒ {@code setLive} 从未被调 ⇒ 判据红。
 * 见 {@code temp\replay-clientsync-selfcheck\StopOrderSelfCheck.java}。
 */
public final class ShanhaiReplayStopOrder {

    /** 关账三件套的落点（运行期 = {@code ShanhaiReplayService#stop} 里的三个真调用）。 */
    public interface Clock {

        /** ① 停节拍器。 */
        void resetPacer();

        /** ② 关掉"替回放写世界"的可重入后门。 */
        void resetWriteGate();

        /**
         * ③ 切回<b>进来之前的模式</b>（{@code PAUSED → PAUSED} / {@code LIVE → LIVE}；bump epoch
         * ⇒ 客户端跟着回到那个模式）。
         *
         * <p>⚠️ 旧名字是 {@code setLive()}、旧行为是一律回 LIVE —— 那是"暂停被倒流弄丢"的根因
         * （用户 2026-11-14：「暂停时间，再 clock replay … 倒流结束之后时间被强行恢复流动」）。
         */
        void restoreMode();

        /** 某一件抛了：记一行（绝不能再抛出去，否则后面的关账就断了）。 */
        void warn(String what, Throwable t);
    }

    /**
     * 依次执行关账三件套；<b>任何一件失败都不影响后两件</b>。
     *
     * @param clock 落点（{@code null} ⇒ 直接算失败）
     * @return {@code true} = 三件全部成功（{@code stop_exit} 行里的 {@code disarmed=1}）
     */
    public static boolean disarm(Clock clock) {
        if (clock == null) {
            return false;
        }
        boolean ok = true;
        try {
            clock.resetPacer();
        } catch (Throwable t) {
            ok = false;
            warn(clock, "resetPacer", t);
        }
        try {
            clock.resetWriteGate();
        } catch (Throwable t) {
            ok = false;
            warn(clock, "resetWriteGate", t);
        }
        try {
            clock.restoreMode();
        } catch (Throwable t) {
            ok = false;
            warn(clock, "restoreMode", t);
        }
        return ok;
    }

    private static void warn(Clock clock, String what, Throwable t) {
        try {
            clock.warn(what, t);
        } catch (Throwable ignored) {
            // 记日志这件事本身不许把关账带崩
        }
    }

    private ShanhaiReplayStopOrder() {
    }
}
