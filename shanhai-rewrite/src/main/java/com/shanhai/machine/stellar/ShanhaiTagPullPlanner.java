package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * ⑧ 标签过滤 ⇒ 库存配置（160 格）的 <b>纯决策层</b>：<b>不引用任何 Minecraft / AE2 类</b>。
 *
 * <h2>一、为什么单独一个纯类（这是本轮的唯一目的）</h2>
 * 「标签命中哪些键、写进第几格、多出来的尾巴要不要清」这件事<b>必须能脱离游戏断言</b>：
 * 探针 {@code temp\tagpull-impl\probe\TagPullProbe.java} 用一组假候选跑真值表，
 * 断言「命中进、未命中清、空白名单=全收、黑名单优先、超过上限按量截断」。
 * 如果这套判定写死在机器类里（那一坨要 MC/AE2/LDLib 才能加载），探针就只能<b>另写一份</b>
 * —— 那正是「检查器自己和自己自洽」的经典假绿形态（本工程 2026-09-20 血账）。
 * ⇒ 判定全部收在本类，机器只负责「把 AE 键喂进来 / 把结果写进 slot」，<b>两边共用同一段判断</b>。
 *
 * <h2>二、语义（与上游 {@code TagFilterMEStockBusPartMachine} 对齐，逐条可查）</h2>
 * <ol>
 *   <li><b>候选</b> = AE 缓存库存（{@code IStorageService#getCachedInventory(): KeyCounter}）里的每一项；
 *       {@code amount <= 0} 直接跳（上游 {@code refreshList} 同款）。</li>
 *   <li><b>命中</b> = {@link ShanhaiStockConfigLayout#accepts(String, String, Collection)}：
 *       黑名单优先 ⇒ 空白名单全收 ⇒ 白名单命中。表达式语法错会<b>抛</b>
 *       （{@link IllegalArgumentException}），由调用方决定「不动任何格子」。</li>
 *   <li><b>排序</b> = 现量降序、同量按 id 升序（稳定可复现）；
 *       <b>超过上限</b>（160）按这个顺序截断 —— 先把「量多的」装进去，装不下的记 {@code truncated}。</li>
 *   <li><b>清尾</b> = 第 {@code written..limit-1} 格写 {@code null}。上游是
 *       {@code aeItemHandler.clearInventory(index)}（{@code javap -c} 偏移 0–50：从 index 到
 *       {@code getConfigurableSlots()} 逐格 {@code IMESlot.setConfigWithoutNotify(null)} +
 *       {@code setStock(null)}，收尾只调<b>一次</b> {@code onConfigChanged()}）。</li>
 * </ol>
 *
 * <h2>三、这个类<b>不</b>做的事（写清边界，免得被当万能被调）</h2>
 * <ul>
 *   <li>不碰 slot、不碰 ME、不打日志、不排序以外的任何 IO；</li>
 *   <li>不决定「要不要接管」（开关 / 空白表达式 / 没连网这些闸在机器侧，因为要读字段）；</li>
 *   <li>不缓存任何东西 —— 每次调用都算一遍，状态只活在调用方。</li>
 * </ul>
 */
public final class ShanhaiTagPullPlanner {

    private ShanhaiTagPullPlanner() {}

    /**
     * 一个候选键（ME 缓存库存里的一项）。
     *
     * @param payload 调用方的<b>真键对象</b>（机器侧是 {@code AEItemKey} / {@code AEFluidKey}，
     *                探针里是任意假对象）—— 本类只负责带着它走，不解读它
     * @param id      注册名（日志 / 差分用；⚠️ 同 id 不同 NBT 的两个键 id 相同，
     *                所以「变没变」一律比 {@code payload} 而不是比 {@code id}）
     * @param amount  ME 里现在的量（{@code <= 0} 的会被丢弃）
     * @param tags    这个键身上的标签串（形如 {@code forge:ores}），{@code null} 当空集
     */
    public static final class Candidate<T> {

        public final @Nullable T payload;
        public final @NotNull String id;
        public final long amount;
        public final @NotNull List<String> tags;

        public Candidate(@Nullable T payload, @NotNull String id, long amount,
                         @Nullable Collection<String> tags) {
            this.payload = payload;
            this.id = id;
            this.amount = amount;
            this.tags = tags == null ? List.of() : new ArrayList<>(tags);
        }

        @Override
        public String toString() {
            return this.id + "x" + this.amount + this.tags;
        }
    }

    /** 一次规划的结果（不可变；{@link #hits} 已排序、已按上限截断）。 */
    public static final class Plan<T> {

        /** 要写进前 {@code written} 格的键（顺序 = 槽序）。 */
        public final @NotNull List<Candidate<T>> hits;
        /** 见到的候选总数（= 缓存库存里这一类的项数）。 */
        public final int candidates;
        /** 命中总数（<b>截断前</b>；{@code matched >= written}）。 */
        public final int matched;
        /** 真正会被写进格子的条数 = {@code min(matched, limit)}。 */
        public final int written;
        /** 因为装不下被丢掉的条数 = {@code matched - written}。 */
        public final int truncated;

        Plan(@NotNull List<Candidate<T>> hits, int candidates, int matched, int written, int truncated) {
            this.hits = hits;
            this.candidates = candidates;
            this.matched = matched;
            this.written = written;
            this.truncated = truncated;
        }

        /** 一行读数（日志 / 探针断言用；不含任何 Minecraft 文本）。 */
        public @NotNull String describe() {
            return "candidates=" + this.candidates + " matched=" + this.matched
                    + " written=" + this.written + " truncated=" + this.truncated;
        }
    }

    // ---------------------------------------------------------------- 前置闸（2026-10-25 独立供料源版）

    /**
     * 一轮「标签供料」的<b>前置闸</b>状态。
     *
     * <p>🔴 为什么把这一段也抽成纯函数：闸的<b>顺序</b>本身是语义（先看开关、再看名单、再看网络），
     * 而上一轮的教训正是「空名单被当成全收」这类闸序错误。抽出来之后，
     * 探针 {@code temp\tagpull2\probe\TagSupplyProbe.java} 能对<b>生产代码同一段判断</b>跑真值表，
     * 而不是另写一份等价实现（那种「检查器自己和自己自洽」的假绿，本工程 2026-09-20 已付过学费）。
     */
    public enum Gate {
        /** 开关关 ⇒ 这一路整体不供料（<b>不碰</b>库存那 160 格，也不清空）。 */
        DISABLED("disabled"),
        /** 白/黑名单都空 ⇒ 还没写过滤条件（<b>刻意不把「空」当「全收」</b>）。 */
        EMPTY_EXPRESSION("emptyExpression"),
        /** 没连上 ME 网络。 */
        NO_GRID("noGrid"),
        /** 取不到 AE 缓存库存。 */
        NO_INVENTORY("noInventory"),
        /** 闸全过 ⇒ 可以算命中集合。 */
        OK("ok");

        private final String code;

        Gate(String code) {
            this.code = code;
        }

        /** 机器侧 {@code shanhaiTagPullSkip} 里用的状态码（与上一轮那套码逐字相同，日志判读表不用改）。 */
        public String code() {
            return this.code;
        }

        /** 这一路现在该不该供料。 */
        public boolean supplies() {
            return this == OK;
        }
    }

    /**
     * 闸序（纯函数）：<b>开关 → 名单 → 网络 → 库存</b>。
     *
     * @param enabled      标签过滤总开关
     * @param white        白名单表达式（{@code null}/空白 = 不过滤）
     * @param black        黑名单表达式（{@code null}/空白 = 不排除）
     * @param hasGrid      主节点在不在网上
     * @param hasInventory 取不取得到 AE 缓存库存
     */
    public static @NotNull Gate gate(boolean enabled, @Nullable String white, @Nullable String black,
                                     boolean hasGrid, boolean hasInventory) {
        if (!enabled) {
            return Gate.DISABLED;
        }
        final boolean whiteBlank = white == null || white.isBlank();
        final boolean blackBlank = black == null || black.isBlank();
        if (whiteBlank && blackBlank) {
            return Gate.EMPTY_EXPRESSION;
        }
        if (!hasGrid) {
            return Gate.NO_GRID;
        }
        if (!hasInventory) {
            return Gate.NO_INVENTORY;
        }
        return Gate.OK;
    }

    /** 排序：量大的先；同量按 id 升序（保证两轮同样的库存得到同样的槽序）。 */
    private static final Comparator<Candidate<?>> ORDER = Comparator
            .comparingLong((Candidate<?> c) -> -c.amount)
            .thenComparing(c -> c.id);

    /**
     * 规划一轮：<b>过滤 + 排序 + 截断</b>。
     *
     * @param white 白名单表达式（{@code null}/空白 = 全收）
     * @param black 黑名单表达式（{@code null}/空白 = 不排除；命中即排除，<b>优先于白名单</b>）
     * @param limit 目标格数（机器侧 = 160）
     * @throws IllegalArgumentException 白/黑名单里有语法错（<b>不是</b>「全收/全不收」——由调用方响亮处理）
     */
    public static <T> @NotNull Plan<T> plan(@Nullable String white, @Nullable String black,
                                            @NotNull List<Candidate<T>> candidates, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("标签拉取的格数上限必须 > 0：" + limit);
        }
        final List<Candidate<T>> hits = new ArrayList<>();
        for (Candidate<T> candidate : candidates) {
            if (candidate == null || candidate.amount <= 0L) {
                continue;
            }
            // 语法错在这里抛（响亮失败）⇒ 调用方**一格都不动**
            if (!ShanhaiStockConfigLayout.accepts(white, black, candidate.tags)) {
                continue;
            }
            hits.add(candidate);
        }
        hits.sort(ORDER);
        final int matched = hits.size();
        if (matched > limit) {
            hits.subList(limit, matched).clear();
        }
        return new Plan<>(List.copyOf(hits), candidates.size(), matched, hits.size(), matched - hits.size());
    }

    /**
     * 期望布局与现状的<b>差异格数</b>（0 = 本轮什么都不用写）。
     *
     * <p>槽 {@code i} 的期望值是 {@code i < hits.size() ? hits[i].payload : null}；
     * 现状由 {@code currentPayloadAt} 给出。比较的是 <b>payload 本身</b>（{@code AEKey.equals} 含 NBT），
     * <b>不是</b> id 串 —— 否则「同 id 不同 NBT 的两种键换了位置」会被误判成没变。
     */
    public static <T> int diffCount(int limit, @NotNull List<Candidate<T>> hits,
                                    @NotNull IntFunction<@Nullable T> currentPayloadAt) {
        if (limit <= 0) {
            return 0;
        }
        final int n = Math.min(limit, hits.size());
        int diff = 0;
        for (int i = 0; i < limit; i++) {
            final T want = i < n ? hits.get(i).payload : null;
            final T current = currentPayloadAt.apply(i);
            if (!Objects.equals(want, current)) {
                diff++;
            }
        }
        return diff;
    }

    /**
     * 「清尾」会清掉几格：{@code [written, limit)} 里<b>现在有东西</b>的格数。
     *
     * <p>玩法上这就是"上一轮写了 N 个、这一轮只写 M 个"里多出来的 {@code N-M'}：
     * 它必须被清掉，否则机器会继续供「已经不该供」的东西（上游用
     * {@code clearInventory(written)} 一次清到尾，本函数只负责把这个数字<b>报准</b>给日志）。
     */
    public static int tailOccupied(int limit, int written, @NotNull IntPredicate occupiedAt) {
        int occupied = 0;
        for (int i = Math.max(0, written); i < limit; i++) {
            if (occupiedAt.test(i)) {
                occupied++;
            }
        }
        return occupied;
    }

    /**
     * 结果指纹（<b>只用于「变化了才打一行日志」</b>，不参与任何判定）。
     *
     * <p>用 payload 的 {@code hashCode} 而不是 id：同 id 不同 NBT 的键换位也会变指纹。
     */
    public static <T> long signature(@NotNull List<Candidate<T>> hits) {
        long sig = 0x9E3779B97F4A7C15L;
        for (Candidate<T> hit : hits) {
            final int h = hit.payload == null ? hit.id.hashCode() : hit.payload.hashCode();
            sig = sig * 31L + h;
        }
        return sig;
    }
}
