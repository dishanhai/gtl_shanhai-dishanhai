package com.dishanhai.gt_shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>「批量写入期间只置脏、不重建」的纯核</b>（不 import 任何 Minecraft / GTCEu / AE2 / LDLib 类
 * ⇒ 裸 JVM 可跑断言；{@code HostPatternMoverSelfCheck} 的 C25 组跑的就是这份代码本身）。
 *
 * <h2>一、为什么需要它（实机证据，2026-11 第四刀）</h2>
 * 主机样板搬运工具「放置」是<b>逐格</b>写的：{@code setItemDirect(slot, stack)} ⇒ 上游自己
 * {@code onPatternChange(slot)} ⇒ 星律样板总成当场 {@code shanhaiRebuildPatternTables()}。
 * 于是 1168 格 = <b>1168 次整表重建</b>。实机日志（{@code GTL山海9.10test/logs/latest.log}）：
 * <pre>
 *   19:44:13 PATREBUILD no=3  reason=wildcardSlot:1080 mothers=1 expanded=29
 *   19:45:33 PATREBUILD no=730 reason=patternSlot:696 mothers=31 expanded=1733 forged=1733
 *   19:44:53 A single server tick has taken 40001, more than 40000 milliseconds   ← 单 tick 卡死 40 秒
 * </pre>
 * 每一次重建要遍历 GT 材料表把通配符母样板展开（{@code expanded=1733}）再过一遍神锻
 * ⇒ 1168 × 1733 ≈ 两百万次展开，全压在同一个 tick 里。
 *
 * <h2>二、这个类的语义（三条，缺一不可）</h2>
 * <ol>
 *   <li><b>可重入</b>：{@link #begin()} 可以嵌套，只有<b>最外层</b> {@link #end()} 才真的收尾；</li>
 *   <li><b>异常安全</b>：调用方必须把 {@code begin} / {@code end} 放进 {@code try} / {@code finally}；
 *       本类另外做到「多调一次 {@code end} 不致病」（不产生负深度、不抛）——
 *       ⇒ <b>不可能永久卡在批量态</b>（卡住的话机器会一直用旧展开表，比卡死更难查）；</li>
 *   <li><b>线程隔离</b>：深度/记账都在 {@link ThreadLocal} 里。服务端 tick 是单线程，
 *       但同一个进程里还有网络线程与客户端线程；用全局静态量会让"客户端开面板"把"服务端 tick"带进批量态
 *       （那种 bug 只在联机时出现，最难查）。</li>
 * </ol>
 *
 * <h2>三、谁调它</h2>
 * <pre>
 *   开始/结束（唯一一处 try/finally）：{@code HostPatternMoverTargets#inBatchWrite}
 *     ↑ 调用方：{@code HostPatternMoverActions#confirmPlace} / {@code #confirmClear}
 *   批量期间"只置脏"的判定点：{@code StellarSampleAssemblerPartMachine#shanhaiBuildPatternTablesIfDirty}
 *     ↑ 它本来就是<b>唯一</b>的重建入口（{@code shanhaiRebuildPatternTables} 的其余调用点只有负对照开关）
 * </pre>
 * 🔴 <b>不在批量里时逐位不变</b>：{@link #suppress(Rebuilder)} 直接返回 {@code false}，
 * 调用方照原样往下走（一次重建、一行 {@code PATREBUILD}），所以编辑器 / AE / 玩家手动放那几条路
 * 的行为与改前完全相同。
 *
 * <h2>四、读数</h2>
 * 最外层 {@link #end()} 返回一份 {@link Report}：{@code writes} = 批量里被抑制掉的重建请求数
 * （= 批量内写了几格），{@code rebuilds} = 收尾时真重建了几台。判据就是
 * <b>{@code writes=1168 rebuilds=1}</b>（去掉批量 ⇒ {@code rebuilds} 会变成 1168）。
 */
public final class HostPatternMoverBatch {

    /**
     * 批量里的「重建者」（生产实现 = 星律样板总成；自检 = 记账桩）。
     *
     * <p>🔴 收尾时<b>逐个</b>调一次；返回 {@code true} 表示<b>真的重建了</b>
     * （客户端 / 没脏 / 没有 level 这些情况下实现应返回 {@code false}，
     * 否则 {@link Report#rebuilds()} 就成了自欺欺人的读数）。
     */
    public interface Rebuilder {

        /** @return 真的重建了一次 ⇒ true */
        boolean rebuildAfterBatch();
    }

    /**
     * 一次批量收尾的读数。
     *
     * @param writes   批量期间被抑制的重建请求次数（= 写了多少格；这就是"如果不批量会有多少次重建"）
     * @param rebuilds 收尾时真重建的次数（<b>判据：= 1</b>）
     * @param failed   收尾重建时抛异常的重建者个数（不许静默：>0 就是有问题）
     */
    public record Report(int writes, int rebuilds, int failed) {

        /** 一行式读数（日志 {@code PATREBUILD_BATCH writes=… rebuilds=…} 用的就是它）。 */
        public String describe() {
            return "writes=" + this.writes + " rebuilds=" + this.rebuilds + " failed=" + this.failed;
        }

        /** 批量真的起作用了吗（写了很多格，却只重建 1 次）。 */
        public boolean suppressed() {
            return this.writes > 0 && this.rebuilds <= 1 && this.failed == 0;
        }
    }

    /** 一个线程的批量状态（只在 {@link #begin()} 时创建，收尾时整份丢掉）。 */
    private static final class State {

        int depth;
        int writes;
        final Map<Rebuilder, Boolean> touched = new IdentityHashMap<>();
        final List<Rebuilder> order = new ArrayList<>(2);
    }

    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();

    private HostPatternMoverBatch() {}

    /** Execute a possibly nested batch and always finish it, even if a write fails. */
    public static <T> T run(java.util.function.Supplier<T> body) {
        if (body == null) return null;
        begin();
        try {
            return body.get();
        } finally {
            end();
        }
    }

    // ================================================================ 开 / 关

    /** 进入一层批量（<b>必须</b>与 {@link #end()} 配对，放 {@code try}/{@code finally} 里）。 */
    public static void begin() {
        State st = ACTIVE.get();
        if (st == null) {
            st = new State();
            ACTIVE.set(st);
        }
        st.depth++;
    }

    /** 当前线程在不在批量里。 */
    public static boolean active() {
        final State st = ACTIVE.get();
        return st != null && st.depth > 0;
    }

    /** 当前线程的嵌套深度（0 = 不在批量里）。 */
    public static int depth() {
        final State st = ACTIVE.get();
        return st == null ? 0 : Math.max(0, st.depth);
    }

    /**
     * 收尾一层批量。
     *
     * @return <b>最外层</b>收尾 ⇒ 本次批量的 {@link Report}；还有外层没结束 / 本来就不在批量里 ⇒ {@code null}
     *         （后者是"多调了一次 end"的兜底：什么都不做，绝不制造负深度）
     */
    public static Report end() {
        final State st = ACTIVE.get();
        if (st == null || st.depth <= 0) {
            // 不平衡的 end：清掉脏状态，但绝不重建（没有 begin 就没有"批量"这回事）
            if (st != null) {
                ACTIVE.remove();
            }
            return null;
        }
        st.depth--;
        if (st.depth > 0) {
            return null; // 内层结束：什么都不做
        }
        // 🔴 先把状态摘下来：重建里若再进 begin/end（理论上不会），也不会污染这一份读数。
        ACTIVE.remove();
        int rebuilds = 0;
        int failed = 0;
        for (Rebuilder rb : st.order) {
            try {
                if (rb != null && rb.rebuildAfterBatch()) {
                    rebuilds++;
                }
            } catch (Throwable t) {
                failed++;
                HostPatternMoverLog.warn("{} 批量收尾重建失败（这一台保持脏，下一 tick 会再试）：{}",
                        HostPatternMoverLog.PREFIX, String.valueOf(t));
            }
        }
        return new Report(st.writes, rebuilds, failed);
    }

    // ================================================================ 抑制点

    /**
     * 批量期间拦住一次「本来要重建」的请求（<b>唯一的抑制判定点</b>）。
     *
     * <p>调用方拿到 {@code true} ⇒ <b>只置脏、不重建</b>（脏标记本来就没人清过，收尾时会建一次）。
     *
     * @param rebuilder 这一格所属的重建者（生产 = {@code this} 那个星律样板总成）；{@code null} 只记账
     * @return {@code true} = 已抑制（不在批量里 ⇒ {@code false}，调用方照原样重建）
     */
    public static boolean suppress(Rebuilder rebuilder) {
        final State st = ACTIVE.get();
        if (st == null || st.depth <= 0) {
            return false;
        }
        st.writes++;
        if (rebuilder != null && st.touched.put(rebuilder, Boolean.TRUE) == null) {
            st.order.add(rebuilder); // 同一台机器在批量里被置脏 N 次 ⇒ 收尾只建 1 次
        }
        return true;
    }

    /** 只清本线程状态（<b>自检专用</b>；生产路径不存在"半途放弃批量"这件事）。 */
    public static void resetForProbe() {
        ACTIVE.remove();
    }

    /** 一行式读数（日志/断言用；不在批量里也能调）。 */
    public static String describe() {
        final State st = ACTIVE.get();
        if (st == null || st.depth <= 0) {
            return "batch=off";
        }
        return "batch=on depth=" + st.depth + " writes=" + st.writes + " touched=" + st.order.size();
    }
}
