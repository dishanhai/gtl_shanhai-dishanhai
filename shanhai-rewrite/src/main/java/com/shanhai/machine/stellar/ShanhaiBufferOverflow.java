package com.shanhai.machine.stellar;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.gtlcore.gtlcore.utils.datastructure.Int128;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>星律产物 buffer 的「二级缓存（L2）」—— 纯账内核</b>（只依赖 fastutil ＋ gtlcore 的 {@code Int128}；
 * <b>不碰 MC / AE / 日志</b>，可离线断言）。
 *
 * <h2>一、为什么要有 L2（一句话）</h2>
 * 上游 {@code MEPatternBufferPartMachineBase.buffer} 是 {@code Object2LongOpenHashMap<AEKey>}，
 * 值域只有 {@code long}。产物量被神锻倍率顶到 {@code Long.MAX_VALUE} 之后，
 * <b>再写就是回绕成负数</b>（上游 {@code addTo} 裸加）⇒ 我们加了夹（{@link ShanhaiBufferClamp}），
 * 夹住之后那部分<b>今天就是直接丢</b>。L2 就是"顶满之后再多出来的那批"的落脚处：
 * <pre>
 *   L1 = gtlcore 的 buffer（long，行为【逐位不变】）
 *   L2 = 本类的 Int128 表（{@code Object2ObjectOpenHashMap<AEKey, Int128>}）—— 只有 {@code want > write} 时才写
 *   某键的真实待退总量 = L1.getLong(k)（饱和在 Long.MAX） ＋ L2.get(k)（Int128，能装下 1e38）
 * </pre>
 * ⇒ <b>今天会丢的量，落进 L2 等下一轮免电搬运</b>（{@link ShanhaiBufferFlush} 那条路径，
 * 生产侧接在 {@code ShanhaiBufClampTrait#sweepFreeFor}）。
 *
 * <h2>二、🔴 {@code Int128} 是【可变】的（javap 实证，见 {@code temp\buf-int128\取证.md} §A.4）</h2>
 * {@code add/subtract/set/negate} 全部<b>原地改自己并 {@code return this}</b>。三条纪律，每条都在下面有对应的入口：
 * <ol>
 *   <li><b>新建的实例才允许放进表里</b>（{@link #addValue}）；放进表之后这个实例<b>只属于这张表</b>；
 *       gtladditions 的 {@code Int128Accumulator.getOrCopy/commit} 就是为这件事存在的。
 *       ⚠️ 反向的坑：拿一个<b>共享常量</b>（含 {@code Int128.MAX_VALUE/ZERO()} 这类静态实例）当"新建"塞进表
 *       ⇒ 两个键指向同一个对象 ⇒ 改一个另一个跟着变，而且<b>没有任何异常</b>。</li>
 *   <li><b>往外交的一律是副本</b>（{@link #entries} / {@link #copyOf}）——
 *       显示层（Jade / 日志）拿到的是快照，它改坏了也伤不到表。</li>
 *   <li><b>{@code longValue()} 饱和、{@code toLong()} 只返 low（禁用）</b>：所有"取值去推网络"的地方只许用
 *       {@code longValue()}（高位丢失会让量变成随机数，{@code 取证.md} §R4-3）。</li>
 * </ol>
 *
 * <h2>三、🔴 早退闸（最容易把整件事搞坏的一行）</h2>
 * 上游 {@code MEPatternBufferPartMachine$Ticker} 每 tick 也在搬<b>同一张 L1</b>
 * （{@code AEUtils#reFunds}）。它把 L1 搬空之后，若两处"buffer 空就早退"的闸门仍只看 L1：
 * <pre>
 *   L1 空 ＋ L2 压着 1e18 件 ⇒ 每 tick 一进门就 return
 *   ⇒ L2 【永久】卡在内存里：没有日志、面板看着是空的、量再也不出来
 * </pre>
 * ⇒ 判据只有一个实现 {@link #nothingToFlush}，<b>两个闸门都必须调它</b>
 * （{@code ShanhaiBufClampTrait#sweepFreeFor} 与 {@code StellarSampleAssemblerPartMachine#shanhaiTickFreeFlush}），
 * 谁都不许自己写 {@code buffer.isEmpty()}。
 *
 * <h2>四、L2 的刷写语义</h2>
 * <ul>
 *   <li><b>每轮优先级高于普通条目</b>：L2 代表"已经顶满、再攒就会丢"的那批 ⇒ 常规那一轮（{@code priority == null}）
 *       先推 L2 再搬 L1（顺序在生产侧 {@code sweepFreeFor} 里，顺序本身就是一条断言）；</li>
 *   <li>推的量 = {@code longValue()}（<b>饱和</b>）⇒ 走免电直插 ⇒ <b>成功多少减多少</b>
 *       （{@link #sweep}：{@code value.add(-moved)}），减到 {@code <= 0} 才 {@code remove}；</li>
 *   <li>搬不动（{@code moved <= 0}）⇒ 条目原样留着，下轮再试（<b>不丢</b>）；</li>
 *   <li>{@code <= 0} 的条目一次都不交给 mover（负值只记账、不搬不删，与 L1 那条同源）。</li>
 * </ul>
 *
 * <h2>五、存档（新键，绝不复用 gtlcore 的 {@code "buffer"}）</h2>
 * <pre>
 *   &lt;BE tag&gt;/shanhaiBufferOverflow = [
 *     { &lt;AEKey 的 generic tag，含 "#c"&gt;, "quantity": [high, low] },   ← 现行格式（Int128 两个 long 原样存）
 *     { … }                                                          ← 旧格式（只有 "real": long）也能读
 *   ]
 * </pre>
 * <ul>
 *   <li>🔴 <b>{@code "buffer"} 是 gtlcore 的 L1</b>（{@code MEPatternBufferPartMachineBase#saveCustomPersistedData}
 *       写的就是它，条目键 {@code "real"}）⇒ 我们的 L2 <b>必须另起一个键</b>，
 *       复用就会把上游那份 L1 覆盖成 Int128 表（新档回旧 build 时直接清零）。</li>
 *   <li>格式与 gtladditions 的 {@code WideBufferTools.writeAmounts/readAmounts} <b>同形</b>
 *       （{@code "quantity" = long[]{high,low}}；旧 {@code "real"} 自动迁移）⇒ 两边互相可读，
 *       但我们<b>自己</b>走 {@link #entries} / {@link #restore} 这条纯核（可离线断言，见
 *       {@code temp\buf-int128\实现.md} §自证 ⑥）。</li>
 *   <li>读档只收<b>正值</b>（0 / 负 = 坏档残骸，剔掉并在调用方留一行日志；<b>不许静默吞掉</b>）。</li>
 * </ul>
 *
 * <h2>六、合并读数（面板不许说谎）</h2>
 * 任何"扫一遍看还剩多少"的地方都走这里（不要各写一份）：
 * {@link #merged(long, Int128)} / {@link #total} / {@link #positiveTotal(Object2LongOpenHashMap)} /
 * {@link #entries}，以及一轮两层的合并读数 {@link Round#merged()}（走
 * {@link ShanhaiBufferFlush#merge}，饱和、读数永不为负）。
 */
public final class ShanhaiBufferOverflow {

    /** 我们的 L2 在存档里的<b>新键</b>（🔴 绝不复用 gtlcore 的 {@code "buffer"}，理由见类注释 §五）。 */
    public static final String TAG_OVERFLOW = "shanhaiBufferOverflow";

    /** 条目里 Int128 的写法：{@code long[]{high, low}}（与 gtladditions {@code WideBufferTools} 同形）。 */
    public static final String TAG_QUANTITY = "quantity";

    /** 旧格式的键：gtlcore 的 L1 用它存 long（我们只在<b>读</b>的时候兼容它）。 */
    public static final String TAG_LEGACY_REAL = "real";

    private ShanhaiBufferOverflow() {}

    // ================================================================ 0) 早退闸（两个闸门共用这一份判据）

    /**
     * <b>🔴 「这一轮是不是什么都不用做」</b>—— 早退闸的唯一判据：
     * <pre>
     *   L1 空 <b>且</b> L2 空 ⇒ 才允许 return
     * </pre>
     *
     * <p>为什么必须是"且"而不是"L1 空就 return"：上游 {@code Ticker#reFunds} 会把 L1 搬空，
     * 那一刻 L2 里可能压着"顶满之后再多出来的"全部量 —— 只看 L1 就等于<b>把 L2 永久冻死</b>
     * （无日志、面板看着空、量再也不出来）。这正是本方案最大的一处风险
     * （{@code 取证.md} §R4-1），所以判据只写一遍、两个闸门都调它。
     *
     * <p>{@code null} 一律当"空"（机器构造期/读档前的那一瞬）。
     */
    public static boolean nothingToFlush(@Nullable Map<?, ?> l1, @Nullable Map<?, ?> l2) {
        final boolean l1Empty = l1 == null || l1.isEmpty();
        final boolean l2Empty = l2 == null || l2.isEmpty();
        return l1Empty && l2Empty;
    }

    // ================================================================ 1) 转存（L1 装不下的那部分进 L2）

    /**
     * <b>把"L1 夹不进去的量"记进 L2</b>（唯一写 L2 的入口）。
     *
     * @return 真正记进去的量（{@code map == null} / {@code key == null} / {@code amount <= 0} ⇒ {@code 0}）
     */
    public static <K> long addTo(@Nullable Map<K, Int128> map, @Nullable K key, long amount) {
        if (amount <= 0L) {
            // 🔴 非正数不进 L2：L2 只装"今天会被丢掉的正量"，negative 塞进来会让 remove 判据失效
            return 0L;
        }
        return addValue(map, key, new Int128(0L, amount)) ? amount : 0L;
    }

    /**
     * <b>把一份 Int128 累加进 L2</b>（读档 / 转存共用；{@code addTo(long)} 就是它的正数特例）。
     *
     * <p>🔴 <b>所有权约定</b>：{@code value} 必须是一次性的新实例（本方法会把它直接放进表里），
     * 调用方交出去之后<b>不许再改它</b>。已有条目走 {@code add}（原地改<b>表里</b>的实例，它只属于这张表）。
     * 违反这条约定的症状是"改 A 键影响了 B 键"，而且没有任何异常（类注释 §二-1）。
     *
     * @return {@code false} = 什么都没写（null / 负值）
     */
    public static <K> boolean addValue(@Nullable Map<K, Int128> map, @Nullable K key,
                                       @Nullable Int128 value) {
        if (map == null || key == null || value == null || !value.isPositive()) {
            return false;
        }
        final Int128 current = map.get(key);
        if (current == null) {
            // 🔴 新实例（value 由调用方新建、自己不再持有）——共享实例是这一层的头号事故形态
            map.put(key, value);
        } else {
            current.add(value);
        }
        return true;
    }

    /**
     * <b>「夹不进去的那部分转存进 L2」—— 生产侧 {@code writeClamped} 的那一步，原样搬到这里</b>。
     *
     * <pre>
     *   进 L1 的 = write            （ShanhaiBufferClamp#allow 的结果，与老版逐位相同）
     *   进 L2 的 = want - write     （今天会丢的那部分）
     *   ⇒ 两层相加【恒等于 want】：同一份物料不许两边各记一次（离线自证第 1 组就是这条）
     * </pre>
     *
     * <p>为什么把它做成纯核里的一个方法、而不是写在 {@code ShanhaiBufClampTrait#writeClamped} 里：
     * 那样"转存"这一步就落在<b>带 MC 依赖的类</b>里，离线证不了 —— 而"不重复计数"正是本刀最该被钉住的一条。
     *
     * @param wide   L2 表（可为 {@code null} ⇒ 不转存，返回 0，行为与加 L2 之前逐位相同）
     * @param want   上游本来要写的量
     * @param write  真正写进 L1 的量
     * @return 真正记进 L2 的量
     */
    public static <K> long parkRemainder(@Nullable Map<K, Int128> wide, @Nullable K key, long want, long write) {
        if (want <= 0L || write >= want) {
            // 没被夹（write == want）/ 非正数（上游原样 addTo，永远不可能溢出）⇒ 一个字节都不进 L2
            return 0L;
        }
        return addTo(wide, key, want - write);
    }

    // ================================================================ 2) 合并读数（L1 + L2）

    /**
     * <b>合并读数</b>：{@code L1(long) + L2(Int128)}，<b>饱和</b>到 {@link Long#MAX_VALUE}、永不为负。
     *
     * <p>为什么不用 {@code Int128.toLong()}：它只返回 low（高位一丢，量直接变成随机数，{@code 取证.md} §A.4）。
     */
    public static long merged(long l1, @Nullable Int128 l2) {
        if (l2 == null) {
            return l1;
        }
        return ShanhaiBufferFlush.satAdd(l1, l2.longValue());
    }

    /** 同上，按 key 取（L1 的现值由调用方给，L2 自己查）。 */
    public static <K> long merged(@Nullable Map<K, Int128> l2, @Nullable K key, long l1) {
        return merged(l1, l2 == null || key == null ? null : l2.get(key));
    }

    /**
     * <b>交出 L2 里某个键的读数【副本】</b>（外部改它伤不到表；表里没有就是 {@code null}）。
     *
     * <p>只给"必须拿一个 Int128 对象"的调用方用；能直接用 {@link #entries} 的快照就别用它。
     */
    @Nullable
    public static <K> Int128 copyOf(@Nullable Map<K, Int128> map, @Nullable K key) {
        if (map == null || key == null) {
            return null;
        }
        final Int128 value = map.get(key);
        return value == null ? null : value.copy();
    }

    /**
     * <b>L2 的快照</b>（值全是<b>副本</b>）—— 存档 / 显示 / 日志的唯一取数口。
     *
     * <p>为什么一定要复制：{@code Int128} 可变（类注释 §二），把表里那个实例交出去，等于把"本机还没退的量"
     * 的写权交给了显示层 / NBT 层 —— 一处 {@code add} 就能让账目永久漂移，且没有异常。
     *
     * <p>顺序 = 表自身的遍历顺序（fastutil 稳定），与日志口径一致。
     */
    @NotNull
    public static <K> List<Entry<K>> entries(@Nullable Map<K, Int128> map) {
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        final List<Entry<K>> out = new ArrayList<>(map.size());
        for (Map.Entry<K, Int128> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            // 🔴 copyOf：交出去的是副本（负对照 M2 把它改回"直接给表里的实例"，第 5b 组必红）
            out.add(new Entry<>(e.getKey(), copyOf(map, e.getKey())));
        }
        return out;
    }

    /** L2 的条目数（{@code null} ⇒ 0）。 */
    public static <K> int size(@Nullable Map<K, Int128> map) {
        return map == null ? 0 : map.size();
    }

    /**
     * <b>L2 的正值合计</b>（饱和；{@code null} ⇒ 0）。
     *
     * <p>只累加正值：负值是"旧回绕残骸"（{@code BUFNEG} 那条的处置对象），把它算进合计会把读数变小甚至变负。
     */
    public static <K> long total(@Nullable Map<K, Int128> map) {
        if (map == null || map.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Map.Entry<K, Int128> e : map.entrySet()) {
            final Int128 value = e.getValue();
            if (value == null || !value.isPositive()) {
                continue;
            }
            sum = ShanhaiBufferFlush.satAdd(sum, value.longValue());
        }
        return sum;
    }

    /** <b>L1 的正值合计</b>（饱和；与 {@code ShanhaiBufferFlush#scanRemaining} 同一个口径）。 */
    public static <K> long positiveTotal(@Nullable Object2LongOpenHashMap<K> l1) {
        if (l1 == null || l1.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        final ObjectIterator<Object2LongMap.Entry<K>> it = l1.object2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            final long value = it.next().getLongValue();
            if (value > 0L) {
                sum = ShanhaiBufferFlush.satAdd(sum, value);
            }
        }
        return sum;
    }

    /** <b>两层的合计待退量</b>（{@code L1 正值和 + L2 正值和}，饱和）—— 面板/日志的"真实还剩多少"。 */
    public static <K> long mergedTotal(@Nullable Object2LongOpenHashMap<K> l1, @Nullable Map<K, Int128> l2) {
        return ShanhaiBufferFlush.satAdd(positiveTotal(l1), total(l2));
    }

    // ================================================================ 3) L2 → 网络（搬一轮）

    /**
     * <b>搬一轮 L2</b>：逐条 {@code longValue()}（饱和）交给 mover，搬动多少减多少，减到 {@code <= 0} 才移除。
     *
     * <pre>
     *   for entry (最多 budget 条；连续 5 条搬不动就收手):
     *     amount = value.longValue()              // 🔴 饱和；绝不用 toLong()
     *     amount &lt;= 0  ⇒ 跳过（负值只记账，不搬、不删、不归零）
     *     moved  = mover.move(key, amount)        // 生产侧 = 免电 poweredInsert（外面还包着 guarded 闸门）
     *     moved  &gt; 0  ⇒ value.add(-moved)；value 已经 &lt;= 0 ⇒ it.remove()；否则条目留着
     *     moved  &lt;= 0 ⇒ 条目原样留着，下轮再试（🔴 不丢）
     * </pre>
     *
     * <p>⚠️ <b>不用 gtladditions 的 {@code WideBufferTools#flushToNetwork}</b>：它走网格能量（<b>要付电</b>），
     * 而星律这条路径由用户拍板<b>免电</b>（{@code poweredInsert} 挂我们自己的无限电 {@code IEnergySource}）。
     *
     * @param map     L2 表（{@code null} ⇒ 空读数）
     * @param mover   搬一条（已由生产侧包好"负值不进屋"的闸门）
     * @param budget  本轮条目预算（{@code <= 0} ⇒ 用 {@link ShanhaiBufferFlush#MAX_ENTRIES}）
     */
    @NotNull
    public static <K> ShanhaiBufferFlush.Sweep sweep(@Nullable Object2ObjectOpenHashMap<K, Int128> map,
                                                     @Nullable ShanhaiBufferFlush.Mover<? super K> mover,
                                                     int budget) {
        if (map == null || map.isEmpty()) {
            return new ShanhaiBufferFlush.Acc().toSweep();
        }
        final int max = budget <= 0 ? ShanhaiBufferFlush.MAX_ENTRIES : budget;
        final ShanhaiBufferFlush.Acc acc = new ShanhaiBufferFlush.Acc();
        final ObjectIterator<Object2ObjectMap.Entry<K, Int128>> it = map.object2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            if (acc.entries >= max || acc.aborted) {
                break;
            }
            final Object2ObjectMap.Entry<K, Int128> entry = it.next();
            final Int128 value = entry.getValue();
            if (value == null) {
                continue;
            }
            final long amount = value.longValue();
            if (amount <= 0L) {
                // 🔴 负值/零值：不搬、不删、不归零（与 L1 那条同源的处置）
                if (value.isNegative()) {
                    acc.noteNegative(entry.getKey(), amount);
                }
                continue;
            }
            long moved;
            try {
                moved = mover == null ? 0L : mover.move(entry.getKey(), amount);
            } catch (Throwable ignored) {
                // 搬一条失败绝不许把 tick 带崩：当"没搬动"，条目留着下轮再试
                moved = 0L;
            }
            acc.entries++;
            if (moved > 0L) {
                acc.ok++;
                acc.noteMoved(moved);
                acc.consecutiveFails = 0;
                // 🔴 改的是【表里】那个实例（它只属于这张表）；-moved 安全：moved > 0
                value.add(-moved);
                if (value.isZero() || value.isNegative()) {
                    it.remove();
                }
            } else {
                acc.fail++;
                acc.consecutiveFails++;
                if (acc.consecutiveFails >= ShanhaiBufferFlush.MAX_CONSECUTIVE_FAILS) {
                    acc.aborted = true;
                }
            }
        }
        scanRemaining(map, acc);
        return acc.toSweep();
    }

    /** 扫一遍 L2 剩下的正量（饱和；负值单独由 {@code negatives} 报）。 */
    private static <K> void scanRemaining(@NotNull Map<K, Int128> map, @NotNull ShanhaiBufferFlush.Acc acc) {
        for (Map.Entry<K, Int128> e : map.entrySet()) {
            final Int128 value = e.getValue();
            if (value != null && value.isPositive()) {
                acc.noteRemaining(value.longValue());
            }
        }
    }

    /**
     * <b>搬一轮（两层，L2 先、L1 后）</b>—— 生产侧 {@code ShanhaiBufClampTrait#sweepFreeFor} 的那一步，
     * 原样搬到这里，就为了把"<b>顺序</b>"变成一条可离线断言的判据。
     *
     * <pre>
     *   ① L2：{@code wideBudget = (priority == null) ? maxEntries : 0}   ← 见下面"追写轮为什么给 0"
     *   ② L1：{@code ShanhaiBufferFlush#sweep(l1, mover, maxEntries, priority)}  ← 与加 L2 之前【逐位相同】
     * </pre>
     *
     * <h2>为什么 L2 必须先推</h2>
     * L2 里的量代表"<b>L1 已经顶满 {@code Long.MAX}、再攒就会丢</b>"的那批（{@link #nothingToFlush} 那节）。
     * 若一轮的预算只够动一条，先把 L1 搬走只会让"又开始丢"的那批继续排队。
     *
     * <h2>为什么追写轮（{@code priority != null}）不给 L2 预算</h2>
     * 追写只有一个任务：把<b>卡住的那个 L1 键</b>挪走（预算 {@link ShanhaiBufferFlush#CHASE_ENTRIES} = 8），
     * 好让这一笔写能落进 L1。把预算花在 L2 上救不了这一单，而 L2 有每 tick 的常规轮兜着，
     * 晚一条 tick 也不会丢。⇒ 这个 0 是<b>故意的</b>，不是漏写。
     */
    @NotNull
    public static <K> Round sweepBoth(@Nullable Object2ObjectOpenHashMap<K, Int128> wide,
                                      @Nullable Object2LongOpenHashMap<K> l1,
                                      @Nullable ShanhaiBufferFlush.Mover<? super K> mover,
                                      int maxEntries,
                                      @Nullable K priority) {
        final int wideBudget = priority == null ? maxEntries : 0;
        // 🔴 wideBudget <= 0 的意思是"这一轮【一次都不碰 L2】"，**不是**"用默认预算"：
        //    ShanhaiBufferFlush#sweep 里 budget<=0 ⇒ 回落成 MAX_ENTRIES（那是给"没传预算"设计的）
        //    ⇒ 这里必须显式短路，否则追写轮会把 64 条预算全花在 L2 上（自证第 3 组一开始就是这么红的）。
        final ShanhaiBufferFlush.Sweep wideSweep = wideBudget <= 0
                ? new ShanhaiBufferFlush.Acc().toSweep()
                : sweep(wide, mover, wideBudget);
        final ShanhaiBufferFlush.Sweep l1Sweep = ShanhaiBufferFlush.sweep(l1, mover, maxEntries, priority);
        return new Round(l1Sweep, wideSweep);
    }

    /**
     * <b>{@code bufflush} 那两行末尾的「二级缓存」后缀</b>（纯字符串；L2 空 ⇒ <b>空串</b>）。
     *
     * <pre>
     *   宽条数=&lt;L2 条目数&gt; 宽入网量=&lt;这一轮 L2 送进网络多少&gt; 宽剩余=&lt;L2 还剩多少&gt; L1剩余=&lt;…&gt;
     * </pre>
     *
     * <p>为什么要有它（"别让面板说谎"）：{@link ShanhaiBufferFlush#merge} 让主字段变成<b>两层合计</b>，
     * 那"合计里 L2 占多少"就必须单独能读 —— 否则看日志的人分不清"网络卡了"和"量太多"。
     *
     * <p>🔴 L2 空 ⇒ <b>返回空串</b>：99.99% 的机器一辈子不会出现 L2，它们的日志必须与加 L2 之前<b>逐字相同</b>
     * （历史 grep 口径不变，{@code log3} 那套离线断言也照旧）。
     *
     * @param wide        L2 那一轮的读数（{@code null} ⇒ 空串）
     * @param wideEntries 这一轮结束时 L2 的条目数
     * @param l1Remaining L1 那一轮自己的剩余（合计里减去它就是 L1 的部分）
     */
    @NotNull
    public static String wideSuffix(@Nullable ShanhaiBufferFlush.Sweep wide, int wideEntries, long l1Remaining) {
        if (wide == null || (wideEntries <= 0 && wide.moved <= 0L && wide.negatives <= 0)) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(" 宽条数=").append(wideEntries)
                .append(" 宽入网量=").append(wide.moved)
                .append(" 宽剩余=").append(wide.remaining)
                .append(" L1剩余=").append(l1Remaining);
        if (wide.negatives > 0) {
            sb.append(" 宽负=").append(wide.negatives);
        }
        return sb.toString();
    }

    // ================================================================ 4) 存档（新键 + 旧格式兼容）

    /**
     * 一条 L2 快照（{@code value} 是<b>副本</b>，改它伤不到表）。
     *
     * <p>存档侧只读它的 {@code getHigh()/getLow()}（{@code "quantity" = [high, low]}）。
     */
    public static final class Entry<K> {

        public final K key;

        /** 🔴 <b>表里那一份的副本</b>（不是表里的实例）—— 谁改它都不会污染账目。 */
        public final Int128 value;

        Entry(K key, Int128 value) {
            this.key = key;
            this.value = value;
        }

        /** 读数（<b>饱和</b>；用于显示/比较，<b>不</b>用于回写）。 */
        public long longValue() {
            return this.value.longValue();
        }

        /**
         * 是否为正（0/负 = 坏档，读档时剔掉）。
         *
         * <p>⚠️ 判据<b>不在这里手写</b>：{@code Int128} 的 low 是无符号位（{@code high == 0 && low < 0}
         * 其实是 2^63..2^64 的<b>正数</b>）⇒ 自己拿 high/low 比大小必错，一律委托给
         * {@code Int128#isPositive()}。
         */
        public boolean isPositive() {
            return this.value.isPositive();
        }
    }

    /**
     * <b>读档的入口（格式无关的窄接口）</b>：生产侧用 NBT 实现
     * （{@code StellarSampleAssemblerPartMachine#shanhaiOverflowSource}），离线自证用内存假货实现
     * ⇒ 判据（哪种格式、收不收、怎么累加）<b>只有一份</b>、且可离线断言。
     */
    public interface Source<K> {

        /** 条目数。 */
        int count();

        /** 第 {@code i} 条的键（读不出 = {@code null}，调用方跳过它）。 */
        @Nullable
        K key(int i);

        /** 第 {@code i} 条有没有 {@code "quantity"}（{@code long[]{high,low}}）。 */
        boolean hasQuantity(int i);

        /** {@code "quantity"[0]}（没有就 0）。 */
        long high(int i);

        /** {@code "quantity"[1]}（没有就 0）。 */
        long low(int i);

        /** 旧格式的 {@code "real"}（long；没有就 0）。 */
        long legacyReal(int i);
    }

    /**
     * <b>读档 → L2 表</b>（<b>先清空</b>再灌，避免"读到一半的旧值残留"；只收正值）。
     *
     * <pre>
     *   有 "quantity"  ⇒ new Int128(high, low)          // 现行格式
     *   否则有 "real"  ⇒ new Int128(real)               // 旧格式自动迁移（并按需触发 legacyHook）
     *   都不是 / 非正   ⇒ 丢（0 与负值都是坏档残骸，调用方负责留一行日志）
     *   键读不出        ⇒ 丢（不许把 null 塞进表）
     *   同键多条        ⇒ 累加（addValue；不许后一条覆盖前一条）
     * </pre>
     *
     * @param legacyHook 读到<b>旧格式</b>时调一次（生产侧用来留一行 INFO；可 {@code null}）
     * @return 真正收下的条目数
     */
    public static <K> int restore(@Nullable Source<K> src, @Nullable Object2ObjectOpenHashMap<K, Int128> map,
                                  @Nullable Runnable legacyHook) {
        if (map == null) {
            return 0;
        }
        map.clear();
        if (src == null) {
            return 0;
        }
        int accepted = 0;
        final int n = src.count();
        for (int i = 0; i < n; i++) {
            final K key = src.key(i);
            if (key == null) {
                continue;
            }
            final Int128 value;
            if (src.hasQuantity(i)) {
                value = new Int128(src.high(i), src.low(i));
            } else {
                if (legacyHook != null) {
                    legacyHook.run();
                }
                value = new Int128(src.legacyReal(i));
            }
            if (!value.isPositive()) {
                continue;
            }
            if (addValue(map, key, value)) {
                accepted++;
            }
        }
        return accepted;
    }

    // ================================================================ 5) 一轮两层的合并读数

    /**
     * <b>一轮搬运的两层读数</b>（{@link ShanhaiBufferFlush#sweep} 搬 L1、{@link #sweep} 搬 L2，
     * 这里的 {@link #merged()} 给日志与断言用）。
     *
     * <p>{@link #l1} / {@link #wide} 都<b>不会</b>是 {@code null}（构造器兜底成空读数）——
     * 调用方不必再判空，也就不会漏掉某一层。
     */
    public static final class Round {

        /** L1 那一轮的读数。 */
        public final ShanhaiBufferFlush.Sweep l1;

        /** L2 那一轮的读数。 */
        public final ShanhaiBufferFlush.Sweep wide;

        private final ShanhaiBufferFlush.Sweep mergedSweep;

        public Round(@Nullable ShanhaiBufferFlush.Sweep l1, @Nullable ShanhaiBufferFlush.Sweep wide) {
            this.l1 = l1 == null ? new ShanhaiBufferFlush.Acc().toSweep() : l1;
            this.wide = wide == null ? new ShanhaiBufferFlush.Acc().toSweep() : wide;
            this.mergedSweep = ShanhaiBufferFlush.merge(this.l1, this.wide);
        }

        /** <b>合并读数</b>（{@code 条数/成功/失败/本次入网量/剩余} 全是两层之和，饱和）—— 日志只许用它。 */
        @NotNull
        public ShanhaiBufferFlush.Sweep merged() {
            return this.mergedSweep;
        }

        /** 本轮两层真正入网的总量（饱和）。 */
        public long moved() {
            return this.mergedSweep.moved;
        }

        /** 本轮结束后两层的合计剩余（饱和）。 */
        public long remaining() {
            return this.mergedSweep.remaining;
        }
    }
}
