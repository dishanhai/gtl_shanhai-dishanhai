package com.shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「把若干台样板总成的<b>每一个槽</b>扫一遍」这件事的<b>纯核</b>（不 import 任何 MC 类）。
 *
 * <h2>一、🔴 为什么必须逐槽扫，而不能问 {@code getAvailablePatterns()}</h2>
 * 取证（{@code temp/pattern-mover/取证.md} §2.2，逐条有反编译行号）已经证死这条路走不通：
 * <table border="1">
 *   <tr><th>机器</th><th>{@code getAvailablePatterns()} 给的是什么</th><th>拿来搬会怎样</th></tr>
 *   <tr><td>gtlcore 各总成</td><td>{@code patternSlotMap.keySet()}：按 {@code IPatternDetails}
 *       <b>{@code putIfAbsent} 去重</b>、<b>丢掉解析失败项</b>、<b>不带槽号</b></td>
 *       <td>同一块样板放 3 个槽 ⇒ 只搬 1 块（少搬）</td></tr>
 *   <tr><td>通配符类（超级通配符 / 宿主通配符）</td><td>{@code expandedPatterns}（<b>展开结果</b>）</td>
 *       <td>1 块通配符样板展开成 N 条 ⇒ 一条都搬不对</td></tr>
 *   <tr><td>星律样板总成</td><td>{@code effectivePatternView}（认领表 ＋ 通配符展开 ＋ <b>神锻改写后的实例</b>）</td>
 *       <td>元素<b>不是</b>槽里那块 ItemStack ⇒ 搬回去会丢神锻倍率</td></tr>
 * </table>
 * ⇒ 唯一的读法是 <b>{@code for (i in 0..size()-1) getStackInSlot(i)}</b>，见 {@link SlotView}。
 *
 * <h2>二、🔴 「只读」是<b>结构性</b>的，不是靠纪律</h2>
 * {@link SlotView} 上<b>没有任何写方法</b>：没有 {@code setItemDirect}、没有 {@code clear}、
 * 没有 {@code extract}。所以本类<b>不可能</b>清源槽 —— 它连能调的东西都没有。
 * <p>用户口径（本轮）：<b>只读</b>，不许清源、不许改任何槽；「剪切」语义要另行确认。
 * <p>负对照（{@link HostPatternMoverSelfCheck} 里真跑）：给一个「顺手清源」的坏实现，
 * 扫完之后源槽指纹变了 ⇒ 自检必须<b>报红</b>。没有这条，「只读」可以被一个不解引用的空实现骗过去。
 *
 * <h2>三、计数口径</h2>
 * <ul>
 *   <li>{@code assemblies} = 扫了几台总成（{@code PatternContainer} 的个数）；</li>
 *   <li>{@code slots} = 这些总成的槽数<b>总和</b>（含空槽）；</li>
 *   <li>{@code nonEmpty} = 真正读到样板的槽数（{@code = payloads.size()}）。</li>
 * </ul>
 *
 * @param <E> 一格非空槽变成的载荷类型
 */
public final class HostPatternMoverScan {

    /** 一台样板总成的<b>只读</b>视图（生产实现：包 {@code InternalInventory}；断言实现：假容器）。 */
    public interface SlotView<E> {

        /** 日志用的标号（坐标 + 槽数即可，宁可短）。 */
        String label();

        /** 槽数（{@code PatternContainer#getTerminalPatternInventory().size()}）。 */
        int size();

        /**
         * 第 {@code slot} 格的载荷；<b>空槽 ⇒ {@code null}</b>。
         *
         * <p>🔴 实现<b>必须只读</b>：不许 {@code setItemDirect}、不许清源、不许 {@code extractItem}。
         */
        E view(int slot);
    }

    /**
     * 一次扫描的读数。
     *
     * @param assemblies 扫了几台总成
     * @param slots      槽数总和（含空槽）
     * @param nonEmpty   读到样板的槽数
     * @param payloads   读到的全部载荷（顺序 = 总成顺序 × 槽号升序）
     */
    public record Result<E>(int assemblies, int slots, int nonEmpty, List<E> payloads) {

        /** 一行式读数（日志用，格式稳定）。 */
        public String describe() {
            return "assemblies=" + this.assemblies + " slots=" + this.slots
                    + " nonEmpty=" + this.nonEmpty;
        }
    }

    private HostPatternMoverScan() {}

    /**
     * 扫全部总成的全部槽。
     *
     * <p>单台总成读崩（机器被挖、容器处于半初始化）⇒ <b>跳过那一台</b>继续扫剩下的，
     * 并把件数如实算进 {@code assemblies}（"扫了几台"是"尝试了几台"，避免把异常伪装成"没有样板"）。
     */
    public static <E> Result<E> scan(List<? extends SlotView<E>> views) {
        int assemblies = 0;
        int slots = 0;
        int nonEmpty = 0;
        final List<E> payloads = new ArrayList<>();
        if (views != null) {
            for (SlotView<E> view : views) {
                if (view == null) {
                    continue;
                }
                assemblies++;
                final int size;
                try {
                    size = view.size();
                } catch (Throwable t) {
                    HostPatternMoverLog.warn("{} 读 size 失败，跳过这台 label={} err={}",
                            HostPatternMoverLog.PREFIX, safeLabel(view), t.toString());
                    continue;
                }
                for (int i = 0; i < size; i++) {
                    slots++;
                    final E payload;
                    try {
                        payload = view.view(i);
                    } catch (Throwable t) {
                        HostPatternMoverLog.warn("{} 读槽失败，跳过这一格 label={} slot={} err={}",
                                HostPatternMoverLog.PREFIX, safeLabel(view), i, t.toString());
                        continue;
                    }
                    if (payload != null) {
                        payloads.add(payload);
                        nonEmpty++;
                    }
                }
            }
        }
        return new Result<>(assemblies, slots, nonEmpty, Collections.unmodifiableList(payloads));
    }

    private static String safeLabel(SlotView<?> view) {
        try {
            return String.valueOf(view.label());
        } catch (Throwable t) {
            return "?";
        }
    }
}
