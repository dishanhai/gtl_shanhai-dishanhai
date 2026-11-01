package com.shanhai.machine.creation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「原初造物编译矩阵」<b>界面上那几行字</b>的纯格式化（<b>零</b> Minecraft / LDLib 依赖 ⇒ 可离线自证）。
 *
 * <h2>为什么单独拆出来</h2>
 * 这些行的内容必须能被<b>运行</b>着断言（"抬头里不能再出现 GT 的 64 并行 / 1 线程"），
 * 而 {@link PrimordialCreationCompilationMatrix} 的类初始化会牵动 GTCEu / MC 的静态状态，
 * 离线跑不动。⇒ 文案与数字的拼装放这里，机器类只负责<b>把真值喂进来</b>
 * （{@code PrimordialCreationCompilationMatrix#creationDisplayLines()} 是那一层薄封装）。
 *
 * <h2>🔴 只能放"事实"，不能放"承诺"</h2>
 * 每一行里的数字都必须由调用方从<b>本机的活值</b>传进来（等级 / 并行 / 队列 / 进度），
 * 本类<b>不读任何机器状态、也没有任何默认值</b> —— 本工程红线「活的界面上不许放假数据」。
 */
public final class CreationDisplayLines {

    /** 并行的底数 k（{@link CreationCraftMath#PARALLEL_BASE_K}；这里只用于文案，不参与算术）。 */
    private static final long K = CreationCraftMath.PARALLEL_BASE_K;

    /** 「无限」起点（{@link CreationCraftMath#INFINITE_PARALLEL_FROM_LEVEL}）。 */
    private static final int INFINITE_FROM = CreationCraftMath.INFINITE_PARALLEL_FROM_LEVEL;

    /**
     * 队列为空时「最快 k tick 后到期」那一段的占位。
     *
     * <p>🔴 <b>不许</b>在这种时候印 {@code 0}：那是在说谎（"0 tick 后到期"= 马上就有活干），
     * 而队列空时根本没有单在等 —— 用户实机看到的就是这个假读数。
     */
    public static final String IDLE_DUE_TEXT = "—";

    private CreationDisplayLines() {
    }

    /**
     * 「并行上限」一行：{@code 并行上限：8^Lv.<等级> = <数值>}；等级 ≥ 15 ⇒ 画「无限」两个字。
     *
     * <p>哨兵 {@link CreationCraftMath#INFINITE_PARALLEL_SENTINEL}（{@link Long#MIN_VALUE}）
     * <b>绝不许</b>直接印成数字/负数（那是本工程反复强调的"显示不能瞎印"）。
     */
    public static String parallelLine(int matterLevel, long parallelCap) {
        final String head = "§b并行上限：§f" + K + "^Lv." + matterLevel + " §7= §r";
        if (CreationCraftMath.isInfinite(parallelCap)) {
            return head + "§d无限§r§7（等级 ≥ " + INFINITE_FROM + " ⇒ 一次做完全部积压）";
        }
        return head + "§6" + String.format(Locale.ROOT, "%,d", parallelCap);
    }

    /**
     * 抬头（Jade）上的<b>三行真值</b>，<b>恒 3 行、顺序固定</b>：
     * <ol>
     *   <li>{@code 并行上限：8^Lv.N = …}（或「无限」）；</li>
     *   <li>{@code 每单固定 10 tick（= 500 ms…）}；</li>
     *   <li>{@code 队列：N 单（欠 M 份） ｜ 本周期进度 p/10 ｜ 最快 k tick 后到期}
     *       —— <b>队列 = 0 时改成</b> {@code 本周期进度 0/10 ｜ 最快到期 —（空闲）}，见 {@link #queueLine}。</li>
     * </ol>
     * <p>🔴 这三行<b>替换</b>掉 gtladditions 画的那两行（「同时处理至多 N 个配方」/「拥有 N 个跨配方线程」）
     * —— 那两个数来自"模块并行槽/线程数"，与本机（接 AE 合成单）的口径毫无关系。
     * 替换的落点：NBT 键 {@code parallel}/{@code threads} 被删掉（键不存在 ⇒ 上游一行都不画），
     * 三行由我们自己的键带过来（见 {@code ShanhaiInfiniteThreadDisplayMixin}）。
     *
     * @param matterLevel       有效物质模块等级
     * @param parallelCap       并行上限（可能是哨兵）
     * @param queuedOrders      队列里的单数（<b>= 0 ⇒ 第三行按「空闲」画</b>，见 {@link #queueLine}）
     * @param pendingCrafts     队列里还欠的份数
     * @param cycleProgress     本周期已过 tick 数（会被夹到 {@code 0..durationTicks}；<b>队列空时不显示它</b>）
     * @param durationTicks     一单固定多少 tick
     * @param ticksUntilNextDue 最快那一单还要几 tick 到期（负数按 0 画；<b>队列空时不显示它</b>）
     */
    public static List<String> threeLines(int matterLevel, long parallelCap, int queuedOrders, long pendingCrafts,
                                          int cycleProgress, int durationTicks, long ticksUntilNextDue) {
        final List<String> lines = new ArrayList<>(3);
        final int duration = Math.max(1, durationTicks);
        lines.add(parallelLine(matterLevel, parallelCap));
        lines.add("§7每单固定 §f" + duration + " tick§7（= " + (duration * 50L)
                + " ms；一单到达后§f满 " + duration + " tick§7 才会产出，不是「到达即完成」）");
        lines.add(queueLine(queuedOrders, pendingCrafts, cycleProgress, duration, ticksUntilNextDue));
        return lines;
    }

    /**
     * 第三行（队列 / 本周期进度 / 最快到期）的<b>唯一拼装点</b>。
     *
     * <h2>🔴 队列为空 ⇒ 显示必须也"空"（用户 2026 实机报的缺陷）</h2>
     * 用户原话：<blockquote>「我没下单的时候，这个本周期进度还会动」</blockquote>
     * 根因：周期时钟是<b>单调</b>的（{@code CreationPacing#advance()} 每 tick +1、永不清零，这是内部实现，
     * 不改），而旧文案把它<b>无条件</b>印出来 ⇒ 队列空时仍然看到「本周期进度 6/10」在一格格跳，
     * 同时 {@code getTicksUntilNextDue()} 在"没有积压"时返回 0 ⇒ 又印出「最快 0 tick 后到期」
     * 两个读数都在<b>说谎</b>（本工程红线：活的界面上不许放假数据）。
     *
     * <p>⇒ 现在的口径（时钟照走，显示照实）：
     * <ul>
     *   <li><b>队列 = 0</b>：{@code 本周期进度 0/10} ＋ {@code 最快到期 —（空闲）}；</li>
     *   <li><b>队列 &gt; 0</b>：才显示真实进度与真实的最快到期。</li>
     * </ul>
     * <p>⚠️ 判据只看 {@code queuedOrders <= 0}（{@code backlog.size()}）—— 它与"有没有活在干"同义：
     * 队列空 ⇒ 没有待做单，{@link CreationCraftExecutor#getPendingCrafts()} 必为 0，
     * 周期边界那一 tick 也只会空转（{@code runCycle} 遍历空表）。
     *
     * @param queuedOrders      队列里的单数
     * @param pendingCrafts     队列里还欠的份数
     * @param cycleProgress     本周期已过 tick 数
     * @param durationTicks     一单固定多少 tick（&gt; 0）
     * @param ticksUntilNextDue 最快那一单还要几 tick 到期
     */
    public static String queueLine(int queuedOrders, long pendingCrafts, int cycleProgress,
                                   int durationTicks, long ticksUntilNextDue) {
        final int duration = Math.max(1, durationTicks);
        final boolean idle = queuedOrders <= 0;
        // 空闲时进度<b>恒画 0</b>：时钟内部走到 7 还是 9，都不代表"有活做到第 7/9 tick"。
        final int shownProgress = idle ? 0 : Math.max(0, Math.min(duration, cycleProgress));
        final StringBuilder line = new StringBuilder();
        line.append("§7队列：§f").append(Math.max(0, queuedOrders))
                .append(" 单§7（欠 §f").append(Math.max(0L, pendingCrafts)).append(" 份§7）")
                .append(" ｜ 本周期进度 §f").append(shownProgress).append('/').append(duration).append("§7")
                .append(" ｜ ");
        if (idle) {
            line.append("最快到期 §f").append(IDLE_DUE_TEXT).append("§7（空闲）");
        } else {
            line.append("最快 §f").append(Math.max(0L, ticksUntilNextDue)).append(" tick§7 后到期");
        }
        return line.toString();
    }
}
