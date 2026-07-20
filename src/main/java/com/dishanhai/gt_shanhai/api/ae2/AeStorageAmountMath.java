package com.dishanhai.gt_shanhai.api.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.mixin.KeyCounterListsAccessor;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;

/** AE2 long 数量边界工具：存储源可以无限，但 AE2 接口只能表达 long。 */
public final class AeStorageAmountMath {

    private AeStorageAmountMath() {
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

    public static void mergeSaturated(KeyCounter target, KeyCounter source) {
        if (target == null || source == null) {
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
}
