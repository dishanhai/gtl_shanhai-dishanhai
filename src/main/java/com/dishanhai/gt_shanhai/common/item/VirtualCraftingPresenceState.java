package com.dishanhai.gt_shanhai.common.item;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ICraftingInventory;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Tracks non-owning network presence for one CPU task. */
public final class VirtualCraftingPresenceState {

    private static final String TAG_EXTERNAL = "gtShanhaiExternalPresence";
    private static final List<Entry> ENTRIES = new ArrayList<>();

    public static synchronized void begin(ICraftingInventory inventory, Object2LongMap<AEKey> externalPresence) {
        remove(inventory);
        Object2LongOpenHashMap<AEKey> external = copy(externalPresence);
        if (!external.isEmpty()) ENTRIES.add(new Entry(inventory, external));
    }

    public static synchronized boolean hasPresence(ICraftingInventory inventory, AEKey key, long amount) {
        Entry entry = find(inventory);
        long external = entry == null ? 0L : entry.external.getLong(key);
        if (external >= amount) return true;
        long remaining = amount - external;
        return inventory.extract(key, remaining, Actionable.SIMULATE) >= remaining;
    }

    public static synchronized void writeToNBT(ICraftingInventory inventory, CompoundTag data) {
        Entry entry = find(inventory);
        if (entry == null) return;
        if (!entry.external.isEmpty()) {
            data.put(TAG_EXTERNAL, AEUtils.createListTag(AEKey::toTagGeneric, entry.external));
        }
    }

    public static synchronized void readFromNBT(ICraftingInventory inventory, CompoundTag data) {
        remove(inventory);
        Object2LongOpenHashMap<AEKey> external = new Object2LongOpenHashMap<>();
        AEUtils.loadInventory(data.getList(TAG_EXTERNAL, Tag.TAG_COMPOUND), AEKey::fromTagGeneric, external);
        begin(inventory, external);
    }

    public static synchronized void clear(ICraftingInventory inventory) {
        remove(inventory);
    }

    private static Object2LongOpenHashMap<AEKey> copy(Object2LongMap<AEKey> source) {
        Object2LongOpenHashMap<AEKey> copy = new Object2LongOpenHashMap<>();
        copy.defaultReturnValue(0L);
        if (source != null) copy.putAll(source);
        return copy;
    }

    private static Entry find(ICraftingInventory inventory) {
        if (inventory == null) return null;
        Iterator<Entry> iterator = ENTRIES.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            ICraftingInventory candidate = entry.inventory.get();
            if (candidate == null) {
                iterator.remove();
            } else if (candidate == inventory) {
                return entry;
            }
        }
        return null;
    }

    private static void remove(ICraftingInventory inventory) {
        Iterator<Entry> iterator = ENTRIES.iterator();
        while (iterator.hasNext()) {
            ICraftingInventory candidate = iterator.next().inventory.get();
            if (candidate == null || candidate == inventory) iterator.remove();
        }
    }

    private VirtualCraftingPresenceState() {}

    private static final class Entry {
        private final WeakReference<ICraftingInventory> inventory;
        private final Object2LongOpenHashMap<AEKey> external;

        private Entry(ICraftingInventory inventory, Object2LongOpenHashMap<AEKey> external) {
            this.inventory = new WeakReference<>(inventory);
            this.external = external;
        }
    }
}
