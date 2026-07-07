package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.api.ae.DShanhaiAEKeyCodec;
import com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VirtualCellStorage implements MEStorage {

    public static final String TAG_ITEMS = "items";
    public static final String TAG_KEYS = "keys";
    public static final String TAG_AMTS = "amts";
    public static final String TAG_HASHES = "hashes";
    public static final String TAG_DATA_UUID = "data_uuid";
    public static final String TAG_TYPE = "type";
    public static final String TAG_BYTES = "bytes";
    public static final String TYPE_ITEM = "item";
    public static final String TYPE_FLUID = "fluid";

    private final Map<AEKey, BigInteger> storage = new HashMap<>();
    private long bytesUsed;
    private final long bytesCapacity;
    private final String cellType;
    private final ItemStack carrier;
    private final int cellIndex;
    private final DShanhaiVirtualCellSavedData savedData;
    private final UUID storageId;
    private boolean dirty;

    public VirtualCellStorage(String cellType, long bytesCapacity, ItemStack carrier, int cellIndex) {
        this(cellType, bytesCapacity, carrier, cellIndex, null, null);
    }

    public VirtualCellStorage(String cellType, long bytesCapacity, ItemStack carrier, int cellIndex,
                              DShanhaiVirtualCellSavedData savedData, UUID storageId) {
        this.cellType = cellType;
        this.bytesCapacity = bytesCapacity;
        this.carrier = carrier;
        this.cellIndex = cellIndex;
        this.savedData = savedData;
        this.storageId = storageId;
    }

    // ============ NBT I/O ============

    public void loadFromNBT(CompoundTag items) {
        storage.clear();
        bytesUsed = 0;
        if (items == null) return;
        if (items.contains(TAG_KEYS, Tag.TAG_LIST)) {
            loadKeyedFormat(items);
            return;
        }
        boolean isFluid = TYPE_FLUID.equals(cellType);
        for (String key : items.getAllKeys()) {
            long amount = items.getLong(key);
            if (amount <= 0) continue;
            AEKey aeKey = isFluid ? DShanhaiAEKeyCodec.fluidKey(key) : DShanhaiAEKeyCodec.itemKey(key);
            if (aeKey != null) {
                storage.put(aeKey, BigInteger.valueOf(amount));
                bytesUsed += amount;
            }
        }
    }

    public void saveToNBT(CompoundTag target) {
        if (savedData != null && storageId != null) {
            savedData.updateCell(storageId, cellType, bytesCapacity, toLongMap());
            target.putUUID(TAG_DATA_UUID, storageId);
            target.remove(TAG_ITEMS);
            target.remove(TAG_KEYS);
            target.remove(TAG_AMTS);
            target.remove(TAG_HASHES);
            return;
        }
        ListTag keys = new ListTag();
        ListTag hashes = new ListTag();
        java.util.ArrayList<Long> amountList = new java.util.ArrayList<>();
        for (var entry : storage.entrySet()) {
            BigInteger amount = entry.getValue();
            if (amount.signum() <= 0) continue;
            AEKey key = entry.getKey();
            keys.add(DShanhaiAEKeyCodec.toNormalizedTag(key));
            hashes.add(net.minecraft.nbt.StringTag.valueOf(DShanhaiAEKeyCodec.stableHash(key)));
            amountList.add(amount.longValue());
        }
        long[] amounts = new long[amountList.size()];
        for (int i = 0; i < amountList.size(); i++) {
            amounts[i] = amountList.get(i);
        }
        target.remove(TAG_ITEMS);
        target.put(TAG_KEYS, keys);
        target.put(TAG_HASHES, hashes);
        target.putLongArray(TAG_AMTS, amounts);
    }

    private void loadKeyedFormat(CompoundTag tag) {
        ListTag keys = tag.getList(TAG_KEYS, Tag.TAG_COMPOUND);
        long[] amounts = tag.getLongArray(TAG_AMTS);
        for (int i = 0; i < keys.size(); i++) {
            long amount = i < amounts.length ? amounts[i] : 0L;
            if (amount <= 0) continue;
            AEKey key = DShanhaiAEKeyCodec.fromNormalizedTag(keys.getCompound(i));
            if (key == null || !typeMatches(key)) continue;
            storage.put(key, BigInteger.valueOf(amount));
            bytesUsed += amount;
        }
    }

    public void loadFromExternal() {
        if (savedData == null || storageId == null) return;
        storage.clear();
        bytesUsed = 0;
        Map<AEKey, Long> amounts = savedData.readCellAmounts(storageId);
        for (Map.Entry<AEKey, Long> entry : amounts.entrySet()) {
            AEKey key = entry.getKey();
            long amount = entry.getValue() != null ? entry.getValue() : 0L;
            if (key == null || amount <= 0 || !typeMatches(key)) continue;
            storage.put(key, BigInteger.valueOf(amount));
            bytesUsed += amount;
        }
    }

    private Map<AEKey, Long> toLongMap() {
        Map<AEKey, Long> result = new HashMap<>();
        for (Map.Entry<AEKey, BigInteger> entry : storage.entrySet()) {
            BigInteger amount = entry.getValue();
            if (amount == null || amount.signum() <= 0) continue;
            result.put(entry.getKey(), amount.longValue());
        }
        return result;
    }

    public void markDirty() { this.dirty = true; }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { this.dirty = false; }
    public String getCellType() { return cellType; }
    public long getBytesUsed() { return bytesUsed; }
    public long getBytesCapacity() { return bytesCapacity; }
    public int getCellIndex() { return cellIndex; }

    // ============ MEStorage ============

    @Override
    public Component getDescription() {
        return Component.literal("§d虚拟存储单元 §7#" + cellIndex + " §8[" + cellType + "]");
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(what, amount, mode, source);
        if (amount <= 0) return 0;
        if (!typeMatches(what)) return 0;
        if (bytesUsed >= bytesCapacity) return 0;
        BigInteger current = storage.getOrDefault(what, BigInteger.ZERO);
        long canAccept = bytesCapacity - bytesUsed;
        long toInsert = Math.min(amount, canAccept);
        if (toInsert <= 0) return 0;
        if (mode == Actionable.MODULATE) {
            storage.put(what, current.add(BigInteger.valueOf(toInsert)));
            bytesUsed += toInsert;
            markDirty();
        }
        return toInsert;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        MEStorage.checkPreconditions(what, amount, mode, source);
        if (amount <= 0) return 0;
        BigInteger current = storage.getOrDefault(what, BigInteger.ZERO);
        long extracted = Math.min(amount, current.longValue());
        if (extracted <= 0) return 0;
        if (mode == Actionable.MODULATE) {
            long remaining = current.longValue() - extracted;
            if (remaining <= 0) {
                storage.remove(what);
            } else {
                storage.put(what, BigInteger.valueOf(remaining));
            }
            bytesUsed -= extracted;
            markDirty();
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        storage.forEach((key, amount) -> out.add(key, amount.longValue()));
    }

    private boolean typeMatches(AEKey key) {
        boolean isFluid = TYPE_FLUID.equals(cellType);
        if (isFluid) return key instanceof AEFluidKey;
        return key instanceof AEItemKey;
    }
}
