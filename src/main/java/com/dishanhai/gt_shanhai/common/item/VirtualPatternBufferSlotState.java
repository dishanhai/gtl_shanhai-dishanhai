package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public final class VirtualPatternBufferSlotState {

    private static final List<Entry> VIRTUAL_TARGETS = new ArrayList<>();
    private static final List<CircuitEntry> VIRTUAL_CIRCUITS = new ArrayList<>();

    public static synchronized <T extends AEKey> void addVirtualTarget(Object2LongOpenHashMap<T> inventory, T key,
            long amount) {
        if (inventory == null || key == null || amount <= 0) return;
        Object2LongOpenHashMap<T> targets = getOrCreateTargets(inventory);
        targets.addTo(key, amount);
    }

    public static synchronized <T extends AEKey> Object2LongMap<T> getVirtualTargets(Object2LongOpenHashMap<T> inventory) {
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        return targets == null ? it.unimi.dsi.fastutil.objects.Object2LongMaps.emptyMap() : targets;
    }

    public static synchronized <T extends AEKey> boolean hasVirtualTargets(Object2LongOpenHashMap<T> inventory) {
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        return targets != null && !targets.isEmpty();
    }

    public static synchronized <T extends AEKey> Object2LongOpenHashMap<T> snapshotVirtualTargets(Object2LongOpenHashMap<T> inventory) {
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        Object2LongOpenHashMap<T> snapshot = new Object2LongOpenHashMap<>();
        snapshot.defaultReturnValue(0L);
        if (targets != null && !targets.isEmpty()) {
            snapshot.putAll(targets);
        }
        return snapshot;
    }

    public static synchronized <T extends AEKey> void restoreConsumedVirtualTargets(Object2LongOpenHashMap<T> inventory,
            Object2LongOpenHashMap<T> snapshot) {
        if (inventory == null || snapshot == null || snapshot.isEmpty()) return;
        Object2LongOpenHashMap<T> targets = getOrCreateTargets(inventory);
        for (Object2LongMap.Entry<T> entry : snapshot.object2LongEntrySet()) {
            T key = entry.getKey();
            long expected = entry.getLongValue();
            long current = inventory.getLong(key);
            if (current < expected) {
                inventory.addTo(key, expected - current);
            }
            targets.put(key, expected);
        }
    }

    public static synchronized <T extends AEKey> void copyVirtualTargets(Object2LongOpenHashMap<T> inventory,
            Object2LongMap<T> targetInventory) {
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        if (targetInventory == null || targets == null || targets.isEmpty()) return;
        for (Object2LongMap.Entry<T> entry : targets.object2LongEntrySet()) {
            long amount = entry.getLongValue();
            if (amount > 0) {
                targetInventory.put(entry.getKey(), amount);
            }
        }
    }

    public static synchronized <T extends AEKey> void removeVirtualTargets(Object2LongOpenHashMap<T> inventory,
            Object2LongMap<T> targetInventory) {
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        if (targetInventory == null || targets == null || targets.isEmpty()) return;
        for (Object2LongMap.Entry<T> entry : targets.object2LongEntrySet()) {
            targetInventory.removeLong(entry.getKey());
        }
    }

    public static synchronized <T extends AEKey> void stripVirtualTargets(Object2LongOpenHashMap<T> inventory) {
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        if (inventory == null || targets == null || targets.isEmpty()) return;
        for (Object2LongMap.Entry<T> entry : targets.object2LongEntrySet()) {
            long current = inventory.getLong(entry.getKey());
            long remaining = current - entry.getLongValue();
            if (remaining > 0) {
                inventory.put(entry.getKey(), remaining);
            } else {
                inventory.removeLong(entry.getKey());
            }
        }
        targets.clear();
        removeTargets(inventory);
    }

    /**
     * 带"保留谓词"的剥离：{@code keep} 判定为 true 的虚拟目标（如不消耗催化剂）不剥离——既不从库存
     * 扣减、也不移除其 target 登记，使其在配方多次执行间常驻在场。其余虚拟目标照常剥离。仅在 targets
     * 被清空（无保留项）后才注销整条 target 登记，否则保留项下次执行仍能被识别并继续保留。
     * <p>用于"配方执行后"的 strip（{@code handleItemInternal}/{@code meHandleRecipeInner} 的 RETURN）：
     * 让虚拟催化剂支撑一整单的所有执行次数，不再执行一次即被清空。退料/下单结束走无谓词版全清，
     * 保证不残留、不把虚拟物品泄漏回网络。
     */
    public static synchronized <T extends AEKey> void stripVirtualTargets(Object2LongOpenHashMap<T> inventory,
            Predicate<AEKey> keep) {
        if (keep == null) {
            stripVirtualTargets(inventory);
            return;
        }
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        if (inventory == null || targets == null || targets.isEmpty()) return;
        ObjectIterator<Object2LongMap.Entry<T>> it = Object2LongMaps.fastIterator(targets);
        while (it.hasNext()) {
            Object2LongMap.Entry<T> entry = it.next();
            T key = entry.getKey();
            if (keep.test(key)) continue; // 催化剂虚拟目标：执行后保留在场，不剥离
            long current = inventory.getLong(key);
            long remaining = current - entry.getLongValue();
            if (remaining > 0) {
                inventory.put(key, remaining);
            } else {
                inventory.removeLong(key);
            }
            it.remove();
        }
        if (targets.isEmpty()) {
            removeTargets(inventory);
        }
    }

    public static synchronized void setVirtualCircuit(Object2LongOpenHashMap<AEItemKey> inventory, int config) {
        if (inventory == null || config < 0) return;
        CircuitEntry entry = findCircuitEntry(inventory);
        if (entry != null) {
            entry.config = config;
            return;
        }
        cleanupClearedCircuitEntries();
        VIRTUAL_CIRCUITS.add(new CircuitEntry(inventory, config));
    }

    public static synchronized int getVirtualCircuit(Object2LongOpenHashMap<AEItemKey> inventory) {
        CircuitEntry entry = findCircuitEntry(inventory);
        return entry == null ? -1 : entry.config;
    }

    public static synchronized void clearVirtualCircuit(Object2LongOpenHashMap<AEItemKey> inventory) {
        Iterator<CircuitEntry> iterator = VIRTUAL_CIRCUITS.iterator();
        while (iterator.hasNext()) {
            CircuitEntry entry = iterator.next();
            Object2LongOpenHashMap<AEItemKey> candidate = entry.inventory.get();
            if (candidate == null || candidate == inventory) {
                iterator.remove();
            }
        }
    }

    private VirtualPatternBufferSlotState() {}

    @SuppressWarnings("unchecked")
    private static <T extends AEKey> Object2LongOpenHashMap<T> getOrCreateTargets(Object2LongOpenHashMap<T> inventory) {
        Object2LongOpenHashMap<T> targets = findTargets(inventory);
        if (targets != null) return targets;
        cleanupClearedEntries();
        Object2LongOpenHashMap<T> created = new Object2LongOpenHashMap<>();
        created.defaultReturnValue(0L);
        VIRTUAL_TARGETS.add(new Entry(inventory, created));
        return created;
    }

    @SuppressWarnings("unchecked")
    private static <T extends AEKey> Object2LongOpenHashMap<T> findTargets(Object2LongOpenHashMap<T> inventory) {
        if (inventory == null) return null;
        Iterator<Entry> iterator = VIRTUAL_TARGETS.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            Object2LongOpenHashMap<? extends AEKey> candidate = entry.inventory.get();
            if (candidate == null) {
                iterator.remove();
                continue;
            }
            if (candidate == inventory) {
                return (Object2LongOpenHashMap<T>) entry.targets;
            }
        }
        return null;
    }

    private static <T extends AEKey> void removeTargets(Object2LongOpenHashMap<T> inventory) {
        Iterator<Entry> iterator = VIRTUAL_TARGETS.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            Object2LongOpenHashMap<? extends AEKey> candidate = entry.inventory.get();
            if (candidate == null || candidate == inventory) {
                iterator.remove();
            }
        }
    }

    private static void cleanupClearedEntries() {
        Iterator<Entry> iterator = VIRTUAL_TARGETS.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.inventory.get() == null) {
                iterator.remove();
            }
        }
    }

    private static CircuitEntry findCircuitEntry(Object2LongOpenHashMap<AEItemKey> inventory) {
        if (inventory == null) return null;
        Iterator<CircuitEntry> iterator = VIRTUAL_CIRCUITS.iterator();
        while (iterator.hasNext()) {
            CircuitEntry entry = iterator.next();
            Object2LongOpenHashMap<AEItemKey> candidate = entry.inventory.get();
            if (candidate == null) {
                iterator.remove();
                continue;
            }
            if (candidate == inventory) {
                return entry;
            }
        }
        return null;
    }

    private static void cleanupClearedCircuitEntries() {
        Iterator<CircuitEntry> iterator = VIRTUAL_CIRCUITS.iterator();
        while (iterator.hasNext()) {
            CircuitEntry entry = iterator.next();
            if (entry.inventory.get() == null) {
                iterator.remove();
            }
        }
    }

    private static final class Entry {
        private final WeakReference<Object2LongOpenHashMap<? extends AEKey>> inventory;
        private final Object2LongOpenHashMap<? extends AEKey> targets;

        private Entry(Object2LongOpenHashMap<? extends AEKey> inventory, Object2LongOpenHashMap<? extends AEKey> targets) {
            this.inventory = new WeakReference<>(inventory);
            this.targets = targets;
        }
    }

    private static final class CircuitEntry {
        private final WeakReference<Object2LongOpenHashMap<AEItemKey>> inventory;
        private int config;

        private CircuitEntry(Object2LongOpenHashMap<AEItemKey> inventory, int config) {
            this.inventory = new WeakReference<>(inventory);
            this.config = config;
        }
    }
}
