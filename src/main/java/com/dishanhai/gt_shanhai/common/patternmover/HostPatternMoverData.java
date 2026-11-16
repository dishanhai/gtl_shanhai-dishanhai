package com.dishanhai.gt_shanhai.common.patternmover;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「主机样板搬运工具」的<b>纯核</b>：一份样板条目清单 ＋ <b>1.5 MiB 字节闸</b> ＋ <b>同 id 覆盖</b>。
 *
 * <h2>一、为什么做成泛型 ＋ 只吃 {@code java.util}</h2>
 * 本类<b>不 import 任何 Minecraft / GTCEu / AE2 / LDLib 类</b>，所以它可以被 {@code javac} 单独编译、
 * 在裸 JVM 上跑断言（本工程一贯纪律，见 {@code ShanhaiSlotCircuit} / {@code ShanhaiPatternRecipeCache}）。
 * <ul>
 *   <li><b>载荷</b>：任意 {@code E}。生产代码里它是 {@link HostPatternMoverEntry}
 *       （= 一份样板 NBT ＋ 类型），离线断言里可以是任何东西。</li>
 *   <li><b>字节量</b>：由 {@link Codec#measureBytes(List)} 提供。生产实现是
 *       {@link HostPatternMoverCodec}（用真的 {@code NbtIo.write} 算，见那个类的推导），
 *       离线实现可以给假的字节模型 —— 但<b>闸的判断逻辑是同一份</b>。</li>
 * </ul>
 *
 * <h2>二、🔴 「不许先写后报错」是怎么保证的（这是本类唯一的设计要点）</h2>
 * <pre>
 *   add(e):
 *     ① 先把【候选表】建出来（原表一个元素都不动）
 *     ② 用 codec 实算候选表的字节数
 *     ③ 超限 ⇒ 直接 return REJECTED_FULL（entries / bytes 一个字段都没变）
 *     ④ 没超 ⇒ 才把字段换成候选表
 * </pre>
 * 负对照（{@code HostPatternMoverSelfCheck} 里真的跑了）：把 ①③④ 换成「先 {@code entries.add(e)}
 * 再量字节、量完发现超了就 return REJECTED」⇒ 数据已经被污染，自检必须<b>报红</b>。
 * 没有这条负对照，「拒绝时数据不变」这一条可以被一个什么都不做的空实现骗过去。
 *
 * <h2>三、容量口径</h2>
 * <ul>
 *   <li><b>条数：无限</b>（用户拍板「块数无限」）—— 本类没有任何条数上限。</li>
 *   <li><b>字节：{@link #LIMIT_BYTES} = 1_572_864 B（= 1.5 MiB = 1.5 × 1024 × 1024）</b>。
 *       超了就是<b>明确拒绝</b>（{@link AddStatus#REJECTED_FULL} ＋ 报「还剩多少字节」），
 *       <b>不截断、不静默丢</b>。</li>
 * </ul>
 *
 * @param <E> 条目载荷类型
 */
public final class HostPatternMoverData<E> {

    /** 字节上限：1.5 MiB。🔴 1 MiB = 1024 × 1024，所以 1.5 MiB = 1_572_864（<b>不是</b> 1_500_000）。 */
    public static final int LIMIT_BYTES = 1_572_864;

    /** 一次 {@link #add} 的结论。 */
    public enum AddStatus {
        /** 新增了一条（键此前不存在）。 */
        ADDED,
        /** 同键覆盖（键此前存在，值换成了新的）。 */
        MERGED,
        /** 字节超限，<b>什么都没写</b>。 */
        REJECTED_FULL
    }

    /**
     * 一次 {@link #add} 的完整读数（给日志/聊天栏用，字段全是「算出来的」不是「估的」）。
     *
     * @param status         结论
     * @param key            这一条的键
     * @param bytesBefore    调用前的字节数
     * @param bytesAfter     <b>提交后</b>的字节数；被拒绝时 == {@code bytesBefore}（因为没提交）
     * @param candidateBytes 候选表的字节数（被拒绝时就是这个数超过了 {@code limitBytes}）
     * @param limitBytes     上限
     */
    public record AddOutcome(AddStatus status, String key,
                             int bytesBefore, int bytesAfter, int candidateBytes, int limitBytes) {

        /** 有没有写进去。 */
        public boolean ok() {
            return this.status != AddStatus.REJECTED_FULL;
        }

        /** 现在还剩多少字节（被拒绝时 = 仍然可用的余量，用来告诉玩家「还差多少」）。 */
        public int remaining() {
            return Math.max(0, this.limitBytes - this.bytesAfter);
        }

        /** 这一次还差多少字节才装得下（没被拒绝 ⇒ 0）。 */
        public int overBy() {
            return this.status == AddStatus.REJECTED_FULL
                    ? Math.max(0, this.candidateBytes - this.limitBytes) : 0;
        }
    }

    /**
     * 一批 {@link #addAll} 的汇总读数。
     *
     * @param added       新增条数
     * @param merged      同键覆盖条数
     * @param rejected    因字节超限被拒的条数
     * @param bytesBefore 批量开始前的字节数
     * @param bytesAfter  批量结束后的字节数
     * @param limitBytes  上限
     */
    public record BatchReport(int added, int merged, int rejected,
                              int bytesBefore, int bytesAfter, int limitBytes) {

        /** 实际写进去了几条。 */
        public int accepted() {
            return this.added + this.merged;
        }

        /** 现在还剩多少字节。 */
        public int remaining() {
            return Math.max(0, this.limitBytes - this.bytesAfter);
        }
    }

    /** 载荷 ↔ 字节/键/类型的适配器（生产实现见 {@link HostPatternMoverCodec}）。 */
    public interface Codec<E> {

        /**
         * 这一整份清单序列化后的<b>字节数</b>（生产实现用真的 {@code NbtIo.write} 算，见实现类）。
         */
        int measureBytes(List<E> entries);

        /** 去重键：键相同 = 「同一个配方」，新的覆盖旧的。 */
        String dedupKey(E entry);

        /** 配方类型 id（{@code ""} = 类型未识别）。 */
        String typeOf(E entry);

        /**
         * 🔴 同一个键被第二次加进来时，<b>这条合并结果是什么</b>（默认＝新的覆盖旧的）。
         *
         * <p>默认实现<b>刻意</b>就是"整条换成 {@code incoming}" —— 与本类改造前<b>逐位相同</b>，
         * 所以断言里的 {@code Payload} / 假 codec 一个字都不用改。
         * <p>生产实现 {@link HostPatternMoverCodec} 覆写它，只为一件事：
         * 让 {@code circuit}（通配符母槽的编程电路）合并时<b>取非 0 的那一个</b> ——
         * 否则「先从母槽读到 5、再从别处读到同一配方（那里没设电路，0）」会把 5 丢掉
         * （见 {@link HostPatternMoverCircuit#mergeForDedup}）。其余字段仍然新的覆盖旧的。
         *
         * @param existing 表里已有的那一条
         * @param incoming 这次要加进来的那一条
         * @return 合并后放进表里的那一条（<b>不许</b>返回 {@code null}）
         */
        default E merge(E existing, E incoming) {
            return incoming;
        }
    }

    private final Codec<E> codec;
    private final int limitBytes;

    /** 当前清单。<b>只在提交时才整体替换</b>（见类注释 §二）。 */
    private List<E> entries = List.of();

    /** 当前字节数（与 {@link #entries} 同生共死，提交时一起换）。 */
    private int bytes;

    /** 覆盖上一个入口的「键 → 下标」缓存：每次提交时重建（条数 ≤ 几千，重建可忽略）。 */
    private Map<String, Integer> indexByKey = Map.of();

    /** 用生产上限建一份空数据。 */
    public HostPatternMoverData(Codec<E> codec) {
        this(codec, LIMIT_BYTES);
    }

    /**
     * 指定上限（<b>只给断言用</b>：自检要把上限临时压到 200 B 来造「超限」现场；
     * 生产代码永远走 {@link #HostPatternMoverData(Codec)}）。
     */
    public HostPatternMoverData(Codec<E> codec, int limitBytes) {
        if (codec == null) {
            throw new IllegalArgumentException("codec 不能为 null");
        }
        if (limitBytes < 0) {
            throw new IllegalArgumentException("limitBytes 不能为负: " + limitBytes);
        }
        this.codec = codec;
        this.limitBytes = limitBytes;
    }

    // ------------------------------------------------------------------ 读

    public int size() {
        return this.entries.size();
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /** 当前字节数（只在提交时更新，所以「被拒绝 ⇒ 这个数不变」是结构性的）。 */
    public int bytes() {
        return this.bytes;
    }

    public int limitBytes() {
        return this.limitBytes;
    }

    public int remainingBytes() {
        return Math.max(0, this.limitBytes - this.bytes);
    }

    /** 清单的只读快照。 */
    public List<E> entries() {
        return this.entries;
    }

    public Codec<E> codec() {
        return this.codec;
    }

    /** 这一条现在在不在（按去重键）。 */
    public boolean containsKey(String key) {
        return key != null && this.indexByKey.containsKey(key);
    }

    /**
     * 按<b>类型</b>分组计数（面板用）。
     *
     * <p>顺序 = <b>首次出现</b>的顺序（{@code LinkedHashMap}）⇒ 同一个存档反复打开面板，
     * 行的顺序是稳定的（可复现）。类型为 {@code null}/{@code ""} 的归到空串那一组
     * （面板把它显示成「类型未识别」）。
     */
    public Map<String, Integer> groupsByType() {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (E e : this.entries) {
            out.merge(normalizeType(this.codec.typeOf(e)), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(out);
    }

    /** 类型未识别（空串组）的条数。 */
    public int unknownTypeCount() {
        int n = 0;
        for (E e : this.entries) {
            if (normalizeType(this.codec.typeOf(e)).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 写

    /**
     * 加入一条（同键覆盖）。
     *
     * <p>🔴 顺序是「<b>先算后写</b>」：先建候选表 → 实算字节 → 超了就<b>原样返回、一个字段都不改</b>
     * → 没超才提交。见类注释 §二。
     *
     * <p>🔴 同键时新元素<b>不是</b>直接顶替旧元素，而是过一道 {@link Codec#merge}：
     * 默认（= 改造前的行为）就是"整条换成新的"，生产实现只把 {@code circuit} 这一项
     * 改成"取非 0 的那一个"（见 {@link HostPatternMoverCircuit#mergeForDedup}）。
     */
    public AddOutcome add(E entry) {
        final int before = this.bytes;
        if (entry == null) {
            return new AddOutcome(AddStatus.REJECTED_FULL, "", before, before, before, this.limitBytes);
        }
        final String key = safeKey(this.codec.dedupKey(entry));
        final Integer existing = this.indexByKey.get(key);

        final List<E> candidate = new ArrayList<>(this.entries);
        if (existing != null) {
            final E merged = this.codec.merge(this.entries.get(existing.intValue()), entry);
            candidate.set(existing.intValue(), merged == null ? entry : merged);
        } else {
            candidate.add(entry);
        }
        final int candidateBytes = this.codec.measureBytes(Collections.unmodifiableList(candidate));

        if (candidateBytes > this.limitBytes) {
            // 🔴 拒绝路径：不碰 entries / bytes / indexByKey 任何一个字段。
            return new AddOutcome(AddStatus.REJECTED_FULL, key, before, before, candidateBytes, this.limitBytes);
        }
        commit(candidate, candidateBytes);
        return new AddOutcome(existing != null ? AddStatus.MERGED : AddStatus.ADDED, key,
                before, candidateBytes, candidateBytes, this.limitBytes);
    }

    /**
     * 批量加入（读取时用）。
     *
     * <p>逐条走 {@link #add}：<b>装得下的接着装，装不下的那一条被拒并继续试下一条</b>
     * —— 这不是「静默截断」，因为每一条被拒都会在 {@link BatchReport#rejected()} 里记账，
     * 调用方必须把它报给玩家。
     */
    public BatchReport addAll(List<? extends E> batch) {
        final int before = this.bytes;
        int added = 0;
        int merged = 0;
        int rejected = 0;
        if (batch != null) {
            for (E e : batch) {
                final AddStatus st = add(e).status();
                switch (st) {
                    case ADDED -> added++;
                    case MERGED -> merged++;
                    default -> rejected++;
                }
            }
        }
        return new BatchReport(added, merged, rejected, before, this.bytes, this.limitBytes);
    }

    /**
     * 用一份<b>已经排好序</b>的清单整体重设（读档时用）。
     *
     * <p>⚠️ 它<b>也</b>过字节闸：整份超过上限时<b>一条都不收</b>（返回 {@code false}，状态原样）。
     * 读档时超限只可能来自「手改过的 NBT」或「上限被调小过」，两种都该被挡下而不是带着走。
     */
    public boolean resetTo(List<E> incoming) {
        final List<E> candidate = incoming == null ? List.of() : List.copyOf(incoming);
        final int candidateBytes = this.codec.measureBytes(candidate);
        if (candidateBytes > this.limitBytes) {
            return false;
        }
        commit(candidate, candidateBytes);
        return true;
    }

    /** 提交：三样东西一起换（调用方已经算过字节、已经判过闸）。 */
    private void commit(List<E> next, int measuredBytes) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(next));
        this.bytes = measuredBytes;
        // 同键只能有一个下标；后出现的覆盖先出现的（与 add 的语义一致）
        final Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < this.entries.size(); i++) {
            index.put(safeKey(this.codec.dedupKey(this.entries.get(i))), Integer.valueOf(i));
        }
        this.indexByKey = Collections.unmodifiableMap(index);
    }

    private static String safeKey(String key) {
        return key == null ? "" : key;
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type;
    }

    /** 一行式读数（日志/断言用，格式稳定）。 */
    public String describe() {
        return "patterns=" + size() + " bytes=" + this.bytes + "/" + this.limitBytes
                + " remaining=" + remainingBytes() + " groups=" + groupsByType().size()
                + " unknownType=" + unknownTypeCount();
    }
}
