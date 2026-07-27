package com.dishanhai.gt_shanhai.api.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.mixin.KeyCounterListsAccessor;
import it.unimi.dsi.fastutil.objects.Object2LongAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.function.ObjLongConsumer;

/** AE2 long 数量边界工具：存储源可以无限，但 AE2 接口只能表达 long。 */
public final class AeStorageAmountMath {

    private AeStorageAmountMath() {
    }

    // ===== KeyCounter 底层 records 直达通道 =====
    // 饱和累加经公开 API 必须先 get 探测再 add/set，每个 key 两次完整哈希查找，
    // 是网络扫描期快照回放的最大热点（spark：KeyCounter.get 占 0.94% 服务端线程）。
    // fastutil 的 addTo 单次读改写并返回旧值，恰好满足"先探测再累加"的饱和语义；
    // 但 AE2 的 VariantCounter / AEKey2LongMap 均为包私有，无法具名，走类级缓存
    // MethodHandle 直达 records 字段（LRN-20260726-004 模式）。任一环节不可用即回退公开 API。
    private static final MethodHandle KEY_COUNTER_LISTS_GETTER;
    private static final MethodHandle UNORDERED_RECORDS_GETTER;
    private static final MethodHandle FUZZY_RECORDS_GETTER;
    private static final Class<?> UNORDERED_VARIANT_CLASS;
    private static final Class<?> FUZZY_VARIANT_CLASS;

    static {
        MethodHandle listsGetter = null;
        MethodHandle unorderedGetter = null;
        MethodHandle fuzzyGetter = null;
        Class<?> unorderedClass = null;
        Class<?> fuzzyClass = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType getterType = MethodType.methodType(Object.class, Object.class);
            Field listsField = KeyCounter.class.getDeclaredField("lists");
            listsField.setAccessible(true);
            listsGetter = lookup.unreflectGetter(listsField).asType(getterType);
            unorderedClass = Class.forName("appeng.api.stacks.VariantCounter$UnorderedVariantMap");
            fuzzyClass = Class.forName("appeng.api.stacks.VariantCounter$FuzzyVariantMap");
            Field unorderedField = unorderedClass.getDeclaredField("records");
            unorderedField.setAccessible(true);
            unorderedGetter = lookup.unreflectGetter(unorderedField).asType(getterType);
            Field fuzzyField = fuzzyClass.getDeclaredField("records");
            fuzzyField.setAccessible(true);
            fuzzyGetter = lookup.unreflectGetter(fuzzyField).asType(getterType);
        } catch (Throwable t) {
            listsGetter = null;
            unorderedGetter = null;
            fuzzyGetter = null;
            unorderedClass = null;
            fuzzyClass = null;
        }
        KEY_COUNTER_LISTS_GETTER = listsGetter;
        UNORDERED_RECORDS_GETTER = unorderedGetter;
        FUZZY_RECORDS_GETTER = fuzzyGetter;
        UNORDERED_VARIANT_CLASS = unorderedClass;
        FUZZY_VARIANT_CLASS = fuzzyClass;
    }

    public static long saturatedAdd(long current, long amount) {
        if (amount <= 0L) {
            return current < 0L ? 0L : current;
        }
        if (current < 0L) {
            current = 0L;
        }
        if (current >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (amount > Long.MAX_VALUE - current) {
            return Long.MAX_VALUE;
        }
        return current + amount;
    }

    public static BigInteger afterBigIntegerInsert(BigInteger total, long inserted) {
        BigInteger current = total == null || total.signum() < 0 ? BigInteger.ZERO : total;
        return inserted == 0L ? current : current.add(BigInteger.valueOf(inserted));
    }

    public static BigInteger afterBigIntegerExtract(BigInteger total, BigInteger storedBefore, long requested) {
        BigInteger current = total == null || total.signum() < 0 ? BigInteger.ZERO : total;
        if (storedBefore == null || storedBefore.signum() <= 0 || requested <= 0L) return current;
        BigInteger request = BigInteger.valueOf(requested);
        BigInteger removed = request.compareTo(storedBefore) >= 0 ? storedBefore : request;
        BigInteger remaining = current.subtract(removed);
        return remaining.signum() < 0 ? BigInteger.ZERO : remaining;
    }

    public static void mergeSaturated(KeyCounter target, KeyCounter source) {
        if (target == null || source == null) {
            return;
        }
        if (target.isEmpty()) {
            target.addAll(source);
            return;
        }
        Object sourceObject = source;
        if (sourceObject instanceof KeyCounterListsAccessor accessor && mergeSaturatedFast(target, accessor)) {
            return;
        }
        mergeSaturatedFallback(target, source);
    }

    public static void getAvailableStacksSaturated(MEStorage provider, KeyCounter output, KeyCounter scratch) {
        if (provider == null || output == null) {
            return;
        }
        if (provider instanceof ISaturatedAvailableStacksProvider saturatedProvider) {
            saturatedProvider.gtShanhai$getAvailableStacksSaturated(output);
            return;
        }
        if (scratch == null) {
            scratch = new KeyCounter();
        } else {
            scratch.clear();
        }
        provider.getAvailableStacks(scratch);
        mergeSaturated(output, scratch);
    }

    public static void forEachEntry(KeyCounter source, ObjLongConsumer<AEKey> consumer) {
        if (source == null || consumer == null) {
            return;
        }
        Object sourceObject = source;
        if (sourceObject instanceof KeyCounterListsAccessor accessor) {
            Reference2ObjectMap<Object, Object> lists = accessor.gtShanhai$getListsRaw();
            boolean direct = true;
            for (Object subIndex : lists.values()) {
                if (!(subIndex instanceof Iterable<?>)) {
                    direct = false;
                    break;
                }
            }
            if (direct) {
                for (Object subIndex : lists.values()) {
                    for (Object rawEntry : (Iterable<?>) subIndex) {
                        Object2LongMap.Entry<AEKey> entry = asEntry(rawEntry);
                        consumer.accept(entry.getKey(), entry.getLongValue());
                    }
                }
                return;
            }
        }
        for (Object2LongMap.Entry<AEKey> entry : source) {
            consumer.accept(entry.getKey(), entry.getLongValue());
        }
    }

    private static boolean mergeSaturatedFast(KeyCounter target, KeyCounterListsAccessor source) {
        Reference2ObjectMap<Object, Object> lists = source.gtShanhai$getListsRaw();
        for (Object subIndex : lists.values()) {
            if (!(subIndex instanceof Iterable<?>)) {
                return false;
            }
        }
        for (Object subIndex : lists.values()) {
            Iterable<?> entries = (Iterable<?>) subIndex;
            for (Object rawEntry : entries) {
                Object2LongMap.Entry<AEKey> entry = asEntry(rawEntry);
                addSaturated(target, entry.getKey(), entry.getLongValue());
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Object2LongMap.Entry<AEKey> asEntry(Object rawEntry) {
        return (Object2LongMap.Entry<AEKey>) rawEntry;
    }

    private static void mergeSaturatedFallback(KeyCounter target, KeyCounter source) {
        for (Object2LongMap.Entry<AEKey> entry : source) {
            addSaturated(target, entry.getKey(), entry.getLongValue());
        }
    }

    public static void addSaturated(KeyCounter target, AEKey key, long amount) {
        if (target == null || key == null || amount <= 0L) {
            return;
        }
        if (addSaturatedDirect(target, key, amount)) {
            return;
        }
        // 回退路径：直达通道不可用（反射被拒/未知 VariantCounter 子类），语义与通道一致
        if (amount >= Long.MAX_VALUE) {
            target.set(key, Long.MAX_VALUE);
            return;
        }
        long current = target.get(key);
        long merged = saturatedAdd(current, amount);
        if (merged != current) {
            target.set(key, merged);
        }
    }

    /**
     * 单查找饱和累加：直达 KeyCounter 底层 records 映射，用 addTo 一次完成读改写；
     * 旧值触发溢出/已饱和时才追加一次 put 回写钳制（罕见）。
     * 子表缺失时不能在此建表（Fuzzy/Unordered 选型属于 AE2 内部逻辑），退回公开 set。
     */
    private static boolean addSaturatedDirect(KeyCounter target, AEKey key, long amount) {
        MethodHandle listsGetter = KEY_COUNTER_LISTS_GETTER;
        if (listsGetter == null) {
            return false;
        }
        try {
            Object lists = listsGetter.invokeExact((Object) target);
            Object variant = ((Reference2ObjectMap<?, ?>) lists).get(key.getPrimaryKey());
            if (variant == null) {
                // 主键子表缺失 → 无既有数量，公开 set 会经 computeIfAbsent 正确选型建表
                target.set(key, amount >= Long.MAX_VALUE ? Long.MAX_VALUE : amount);
                return true;
            }
            Object records;
            Class<?> variantClass = variant.getClass();
            if (variantClass == UNORDERED_VARIANT_CLASS) {
                records = UNORDERED_RECORDS_GETTER.invokeExact(variant);
            } else if (variantClass == FUZZY_VARIANT_CLASS) {
                records = FUZZY_RECORDS_GETTER.invokeExact(variant);
            } else {
                return false;
            }
            return addSaturatedToRecords(records, key, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean addSaturatedToRecords(Object records, AEKey key, long amount) {
        if (!(records instanceof Object2LongMap<?> rawMap)) {
            return false;
        }
        Object2LongMap<AEKey> map = (Object2LongMap<AEKey>) rawMap;
        if (amount >= Long.MAX_VALUE) {
            map.put(key, Long.MAX_VALUE);
            return true;
        }
        long previous;
        if (map instanceof Object2LongOpenHashMap<?> hashRecords) {
            previous = ((Object2LongOpenHashMap<AEKey>) hashRecords).addTo(key, amount);
        } else if (map instanceof Object2LongAVLTreeMap<?> treeRecords) {
            previous = ((Object2LongAVLTreeMap<AEKey>) treeRecords).addTo(key, amount);
        } else {
            return false;
        }
        long merged = saturatedAdd(previous, amount);
        if (merged != previous + amount) {
            map.put(key, merged);
        }
        return true;
    }
}
