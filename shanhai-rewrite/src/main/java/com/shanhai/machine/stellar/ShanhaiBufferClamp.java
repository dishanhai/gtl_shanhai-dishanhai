package com.shanhai.machine.stellar;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.function.Function;

/**
 * <b>产物 buffer 的动态夹 —— 纯算术核 + 唯一写入口 + 负值护栏的判据</b>（只依赖 fastutil 与 JDK，不碰 MC / AE / 日志）。
 *
 * <h2>一、它治的是什么（一句话）</h2>
 * {@code MEPatternBufferPartMachineBase.buffer} 是 {@code Object2LongOpenHashMap<AEKey>}，
 * 上游写它的唯一动作是 fastutil 的 {@code addTo(key, amount)} —— <b>裸加、不饱和</b>
 * （{@code Object2LongOpenHashMap#addToValue}：{@code value[pos] = oldValue + incr}）。
 * 而我们的神锻倍率走 {@code NumberUtils.saturatedMultiply}，产量可以被顶到 {@code Long.MAX_VALUE}；
 * 同一个 key 再写一次就<b>回绕成负数</b>，而 {@code AEUtils#reFunds} 对 {@code amount <= 0} 的条目
 * <b>直接 {@code it.remove()}（不插入、不打日志）</b> ⇒ <b>整条静默丢掉</b>。
 * 证据：{@code temp\infinity2\退货是否丢量.md} §F6 / §附录 B（偏移 99 {@code ObjectIterator.remove}）。
 *
 * <h2>二、夹的算法（用户拍板：选项 B「动态夹 —— 按 buffer 现量补到不溢出」）</h2>
 * <pre>
 *   对每个产出条目 (key, want)：
 *     existing = buffer 里该 key 的现值（没有就是 0，fastutil 默认返回 0）
 *     room     = Long.MAX_VALUE - existing
 *     write    = min(want, room)          ← room &lt;= 0 时就是 0
 *   写 write；{@code write &lt; want} 就是"被夹了"。
 *   ⚠️ 被夹 ≠ 丢了：打不打日志、什么级别，由调用方按【追写之后】的最终读数决定
 *   （{@code ShanhaiBufferFlush#writeEvents}：救回来的一条 WARN 都不打，只有真丢才 WARN）。
 * </pre>
 *
 * <h2>三、🔴 回归底线（这是本类唯一不能做错的事）</h2>
 * <b>{@code existing + want <= Long.MAX_VALUE} 时，返回值必须逐位等于 {@code want}</b>。
 * 99.99% 的产出走的就是这条路径，任何"顺手钳一下"、"钳到 MAX/2"、"钳到某个安全档"都会
 * <b>改掉正常产出量</b> ⇒ 直接越权改玩法数值。所以：
 * <ul>
 *   <li>{@code want <= 0} ⇒ 原样返回（上游会写 0 / 负数，永远不可能溢出）；</li>
 *   <li>{@code existing < 0} ⇒ 原样返回（buffer 里已经是负数 = 上一轮回绕的残骸；
 *       这时 {@code Long.MAX_VALUE - existing} 自己就会溢出，绝不能拿它做减法）；</li>
 *   <li>{@code want <= room} ⇒ 原样返回（<b>逐位不变</b>）。</li>
 * </ul>
 *
 * <h2>四、为什么调用方拿到 0 要「不写」而不是「写 0」</h2>
 * 夹到 0 说明这个 key 的 buffer 已经 == {@code Long.MAX_VALUE}；此时 {@code addTo(key, 0)}
 * 对<b>已存在</b>的条目无害，但对<b>不存在</b>的 key 会插入一条 0 —— 而 0 值条目在
 * {@code reFunds} 里会被 {@code amount <= 0} 判掉（偏移 99）。两者都不丢量，但"不写"更干净，
 * 也不会让 {@code buffer.size()}（TICKER 那行日志的 {@code buffer=} 口径）凭空多一条。
 * 这条判据落在 {@link #skipWrite(long, long)}，由 {@link #commit} 强制执行（可离线断言）。
 */
public final class ShanhaiBufferClamp {

    /** 上游 buffer 的 value 是 long，上限就是它。 */
    public static final long MAX = Long.MAX_VALUE;

    private ShanhaiBufferClamp() {}

    /**
     * 本次<b>应当写进 buffer 的量</b>（纯函数，无副作用、无线程状态）。
     *
     * @param existing buffer 里该 key 的现值（缺失传 0，与 fastutil {@code getLong} 默认值一致）
     * @param want     上游本来要写的量
     * @return 本次写多少；{@code existing + want <= Long.MAX_VALUE} 时<b>逐位等于 {@code want}</b>
     */
    public static long allow(long existing, long want) {
        // ① 非正数：上游原样 addTo，永远不可能溢出 ⇒ 逐位不变（含 0 与负数）
        if (want <= 0L) {
            return want;
        }
        // ② 现值已经是负数（旧回绕残骸）：MAX - existing 会自己溢出 ⇒ 不做减法，退回原行为
        if (existing < 0L) {
            return want;
        }
        // ③ room >= 0（existing ∈ [0, MAX]）
        final long room = MAX - existing;
        // ④ 装得下 ⇒ 原样（回归底线：99.99% 的情况走这里）
        if (want <= room) {
            return want;
        }
        // ⑤ 装不下 ⇒ 只补到不溢出（room 可能是 0 ⇒ 调用方据此"本条不写"）
        return room;
    }

    /**
     * 被夹掉多少（{@code want - allow(existing, want)}；没夹时恒为 0，永不返回负数）。
     *
     * <p>给日志用：{@code bufclamp key=… existing=… want=… write=… dropped=…}。
     */
    public static long dropped(long existing, long want) {
        final long write = allow(existing, want);
        return want > write ? want - write : 0L;
    }

    /** 本条会不会被夹（{@code write < want}）。给"要不要打日志"用，语义与 {@link #allow} 同一份判据。 */
    public static boolean isClamped(long existing, long want) {
        return allow(existing, want) < want;
    }

    /**
     * <b>夹到 0 就必须"不写"</b>（用户要求 2）。
     *
     * <p>判据只看 {@code want > 0 && write == 0}：
     * <ul>
     *   <li>{@code want > 0, write == 0} ⇒ 这个 key 的 buffer 已经 == {@code Long.MAX_VALUE}，
     *       写 0 对缺键会插入一条 0 值条目（{@code reFunds} 偏移 99 再删掉），对
     *       {@code buffer.size()}（TICKER 的 {@code buffer=} 口径）还会凭空 +1 ⇒ <b>省略这次写</b>；</li>
     *   <li>{@code want == 0} ⇒ 与上游逐位一致，照写（{@code addTo(key, 0)}）；</li>
     *   <li>{@code want < 0} ⇒ 不可能溢出，与上游逐位一致，照写。</li>
     * </ul>
     */
    public static boolean skipWrite(long want, long write) {
        return want > 0L && write == 0L;
    }

    /**
     * <b>唯一写 buffer 的动作</b>：装得下就逐位照写，装不下写到刚好不溢出，夹到 0 就<b>不写</b>。
     *
     * <p>被测物 = 本方法（离线自证里用<b>真</b> {@code Object2LongOpenHashMap} 跑，
     * 所以验的就是 fastutil {@code addTo} 的真实溢出语义）。
     *
     * @return 实际写进去的量（= {@code allow(existing, want)}；被 {@link #skipWrite} 拦下时是 0）
     */
    public static <K> long commit(Object2LongOpenHashMap<K> buffer, K key, long want, long write) {
        if (!skipWrite(want, write)) {
            buffer.addTo(key, write);
        }
        return write;
    }

    /** 被夹时的回调（给日志用；纯核不碰日志，把"要不要报"交出去）。 */
    @FunctionalInterface
    public interface Reporter<K> {

        void clamped(K key, long existing, long want, long write);
    }

    /**
     * <b>把一张「待搬进 buffer」的表整批搬进去，逐条夹</b> —— 上游 {@code refundSlot} 那条裸加的替身。
     *
     * <p>逐行镜像 {@code MEPatternBufferPartMachineBase#refundSlot}（{@code :233-255}）：
     * {@code fastIterator} 遍历、{@code amount > 0} 才搬、搬完从源表移走。与之唯一的区别是**搬多少要夹**，
     * 而且<b>被夹掉的那部分不许凭空消失</b>：
     * <pre>
     *   write = allow(existing, amount)
     *   commit(buffer, key, amount, write)          // 夹到 0 ⇒ 不插入（不造 0 值条目）
     *   left  = amount - write
     *   left <= 0  ⇒ it.remove()                    // 与上游逐位一致（整条搬走）
     *   否则       ⇒ entry.setValue(left)           // 🔴 余量写回源表：料还在槽里，下次还能退
     * </pre>
     * <p>🔴 <b>为什么不"夹到 0 也照样 remove"</b>：那等于把 {@code amount} 直接扔掉 —— 正是我们要治的
     * "静默丢量"，只是换了个地方发生。余量写回与 {@code AEUtils#reFunds} 的做法同源
     * （{@code 退货是否丢量.md} §F4.1，bytecode 偏移 172 {@code Entry.setValue}）。
     *
     * @param keyOf    源表键 → buffer 键（{@code AEItemKey/AEFluidKey} 都是 {@code AEKey}，生产侧就是恒等）
     * @param reporter 被夹时的回调（可为 null）
     * @return 本次真正搬进 buffer 的总量
     */
    public static <S, K> long commitAll(Object2LongOpenHashMap<K> buffer,
                                        Object2LongOpenHashMap<S> source,
                                        Function<? super S, ? extends K> keyOf,
                                        Reporter<? super K> reporter) {
        if (buffer == null || source == null || keyOf == null || source.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        final ObjectIterator<Object2LongMap.Entry<S>> it = source.object2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            final Object2LongMap.Entry<S> entry = it.next();
            final long amount = entry.getLongValue();
            // 上游的过滤条件（Base:239 / Base:250）：非正数不搬、也不删
            if (amount <= 0L) {
                continue;
            }
            final K key = keyOf.apply(entry.getKey());
            if (key == null) {
                continue;
            }
            final long existing = buffer.getLong(key);
            final long write = allow(existing, amount);
            if (write < amount && reporter != null) {
                reporter.clamped(key, existing, amount, write);
            }
            commit(buffer, key, amount, write);
            total += write;
            final long left = amount - write;
            if (left <= 0L) {
                it.remove();
            } else {
                // 🔴 被夹掉的余量写回源表 ⇒ 不丢量；下次（buffer 腾出来之后）还能再退
                entry.setValue(left);
            }
        }
        return total;
    }

    /**
     * <b>BUFNEG 护栏的判据</b>：扫一遍 buffer，有负值就拼出告警行，没有就返回 null。
     *
     * <p>为什么要它：夹取只盖住<b>我们控制的两个写入点</b>（产物写入 + 退款槽搬料）。万一还有第三个写入点
     * 把值翻负（例如上游别处的 {@code addTo}、或已存在的残骸），{@code AEUtils#reFunds} 会
     * <b>静默 {@code it.remove()}</b> ⇒ 什么都看不见。有了这一行，翻负就变成**看得见的事件**。
     *
     * <p>格式（前缀与限流由调用方加）：
     * <pre>
     *   bufclamp BUFNEG pos=&lt;维度@x,y,z&gt; dim=&lt;维度&gt; key=&lt;键 id&gt; value=&lt;值&gt; neg=&lt;负值条目数&gt;
     * </pre>
     * <p>返回<b>第一条</b>负值条目的读数（附带负值条目总数）；没有负值 ⇒ {@code null}（一次都不许误报）。
     *
     * @param pos  机器位置（本工程口径：{@code <维度id>@<x>,<y>,<z>}）
     * @param dim  维度 id（单独给一份，便于 {@code grep dim=}）
     * @param idOf 键 → 可读 id（生产侧是 {@code AEKey#getId()}）
     */
    public static <K> String negativeReport(Object2LongOpenHashMap<K> buffer, String pos, String dim,
                                           Function<? super K, String> idOf) {
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }
        K firstKey = null;
        long firstValue = 0L;
        int negCount = 0;
        final ObjectIterator<Object2LongMap.Entry<K>> it = buffer.object2LongEntrySet().fastIterator();
        while (it.hasNext()) {
            final Object2LongMap.Entry<K> entry = it.next();
            final long value = entry.getLongValue();
            if (value < 0L) {
                negCount++;
                if (firstKey == null) {
                    firstKey = entry.getKey();
                    firstValue = value;
                }
            }
        }
        if (negCount == 0) {
            return null;
        }
        String keyId = "?";
        try {
            final String s = idOf == null ? null : idOf.apply(firstKey);
            if (s != null) {
                keyId = s;
            }
        } catch (Throwable ignored) {
            // 取不到 id 也要把这行打出来（值才是关键信息）
        }
        return "bufclamp BUFNEG pos=" + (pos == null ? "?" : pos)
                + " dim=" + (dim == null ? "?" : dim)
                + " key=" + keyId
                + " value=" + firstValue
                + " neg=" + negCount;
    }
}
