package com.dishanhai.gt_shanhai.api.ae;

import appeng.api.stacks.AEKey;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class DShanhaiVirtualCellSavedData extends SavedData {
    private static final String DATA_NAME = "gt_shanhai_virtual_cells";
    private static final String TAG_KEY_TABLE = "key_table";
    private static final String TAG_CELLS = "cells";
    private static final String TAG_HASH = "hash";
    private static final String TAG_KEY = "key";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_TYPE = "type";
    private static final String TAG_BYTES = "bytes";
    private static final String TAG_HASHES = "hashes";
    private static final String TAG_AMTS = "amts";

    private final Map<String, CompoundTag> keyTable = new LinkedHashMap<>();
    private final Map<UUID, CellData> cells = new LinkedHashMap<>();
    private final Map<String, AEKey> decodedKeyCache = new LinkedHashMap<>();
    private final Map<UUID, Map<AEKey, Long>> decodedLongCellCache = new LinkedHashMap<>();
    private final Map<UUID, Map<AEKey, java.math.BigInteger>> decodedBigCellCache = new LinkedHashMap<>();

    public static DShanhaiVirtualCellSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                DShanhaiVirtualCellSavedData::load,
                DShanhaiVirtualCellSavedData::new,
                DATA_NAME);
    }

    public static DShanhaiVirtualCellSavedData load(CompoundTag tag) {
        DShanhaiVirtualCellSavedData data = new DShanhaiVirtualCellSavedData();
        ListTag keyTableTag = tag.getList(TAG_KEY_TABLE, Tag.TAG_COMPOUND);
        for (int i = 0; i < keyTableTag.size(); i++) {
            CompoundTag entry = keyTableTag.getCompound(i);
            String hash = entry.getString(TAG_HASH);
            if (!hash.isEmpty() && entry.contains(TAG_KEY, Tag.TAG_COMPOUND)) {
                data.keyTable.put(hash, entry.getCompound(TAG_KEY).copy());
            }
        }
        ListTag cellsTag = tag.getList(TAG_CELLS, Tag.TAG_COMPOUND);
        for (int i = 0; i < cellsTag.size(); i++) {
            CompoundTag cellTag = cellsTag.getCompound(i);
            if (!cellTag.hasUUID(TAG_UUID)) continue;
            UUID uuid = cellTag.getUUID(TAG_UUID);
            data.cells.put(uuid, CellData.load(cellTag));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag keyTableTag = new ListTag();
        for (Map.Entry<String, CompoundTag> entry : keyTable.entrySet()) {
            CompoundTag keyEntry = new CompoundTag();
            keyEntry.putString(TAG_HASH, entry.getKey());
            keyEntry.put(TAG_KEY, entry.getValue().copy());
            keyTableTag.add(keyEntry);
        }
        tag.put(TAG_KEY_TABLE, keyTableTag);

        ListTag cellsTag = new ListTag();
        for (Map.Entry<UUID, CellData> entry : cells.entrySet()) {
            CompoundTag cellTag = entry.getValue().save();
            cellTag.putUUID(TAG_UUID, entry.getKey());
            cellsTag.add(cellTag);
        }
        tag.put(TAG_CELLS, cellsTag);
        return tag;
    }

    public CellData getCell(UUID uuid) {
        return cells.get(uuid);
    }

    public void updateCell(UUID uuid, String type, long bytes, Map<AEKey, Long> amounts) {
        if (uuid == null) return;
        CellData cell = new CellData(type, bytes);
        for (Map.Entry<AEKey, Long> entry : amounts.entrySet()) {
            long amount = entry.getValue() != null ? entry.getValue() : 0L;
            if (amount <= 0) continue;
            AEKey key = entry.getKey();
            String hash = DShanhaiAEKeyCodec.stableHash(key);
            keyTable.put(hash, DShanhaiAEKeyCodec.toNormalizedTag(key));
            decodedKeyCache.put(hash, key);
            cell.amounts.put(hash, amount);
        }
        cells.put(uuid, cell);
        decodedLongCellCache.remove(uuid);
        decodedBigCellCache.remove(uuid);
        setDirty();
    }

    public void updateCellBig(UUID uuid, String type, long bytes, Map<AEKey, java.math.BigInteger> amounts) {
        if (uuid == null) return;
        CellData cell = new CellData(type, bytes);
        java.math.BigInteger total = java.math.BigInteger.ZERO;
        for (Map.Entry<AEKey, java.math.BigInteger> entry : amounts.entrySet()) {
            java.math.BigInteger amount = entry.getValue();
            if (amount == null || amount.signum() <= 0) continue;
            AEKey key = entry.getKey();
            String hash = DShanhaiAEKeyCodec.stableHash(key);
            keyTable.put(hash, DShanhaiAEKeyCodec.toNormalizedTag(key));
            decodedKeyCache.put(hash, key);
            cell.bigAmounts.put(hash, amount);
            total = total.add(amount);
        }
        cell.itemCount = total;
        cells.put(uuid, cell);
        decodedLongCellCache.remove(uuid);
        decodedBigCellCache.remove(uuid);
        setDirty();
    }

    public Map<AEKey, Long> readCellAmounts(UUID uuid) {
        Map<AEKey, Long> cached = decodedLongCellCache.get(uuid);
        if (cached != null) return cached;
        Map<AEKey, Long> result = new LinkedHashMap<>();
        CellData cell = cells.get(uuid);
        if (cell == null) return result;
        for (Map.Entry<String, Long> entry : cell.amounts.entrySet()) {
            AEKey key = getDecodedKey(entry.getKey());
            long amount = entry.getValue() != null ? entry.getValue() : 0L;
            if (key != null && amount > 0) result.put(key, amount);
        }
        decodedLongCellCache.put(uuid, result);
        return result;
    }

    public Map<AEKey, java.math.BigInteger> readCellBigAmounts(UUID uuid) {
        Map<AEKey, java.math.BigInteger> cached = decodedBigCellCache.get(uuid);
        if (cached != null) return cached;
        Map<AEKey, java.math.BigInteger> result = new LinkedHashMap<>();
        CellData cell = cells.get(uuid);
        if (cell == null) return result;
        for (Map.Entry<String, java.math.BigInteger> entry : cell.bigAmounts.entrySet()) {
            AEKey key = getDecodedKey(entry.getKey());
            java.math.BigInteger amount = entry.getValue();
            if (key != null && amount != null && amount.signum() > 0) result.put(key, amount);
        }
        decodedBigCellCache.put(uuid, result);
        return result;
    }

    private AEKey getDecodedKey(String hash) {
        AEKey cached = decodedKeyCache.get(hash);
        if (cached != null) return cached;
        CompoundTag keyTag = keyTable.get(hash);
        if (keyTag == null) return null;
        AEKey key = DShanhaiAEKeyCodec.fromNormalizedTag(keyTag);
        if (key != null) decodedKeyCache.put(hash, key);
        return key;
    }

    public static final class CellData {
        public final String type;
        public final long bytes;
        public final Map<String, Long> amounts = new LinkedHashMap<>();
        public final Map<String, java.math.BigInteger> bigAmounts = new LinkedHashMap<>();
        public java.math.BigInteger itemCount = java.math.BigInteger.ZERO;

        public CellData(String type, long bytes) {
            this.type = type != null ? type : "item";
            this.bytes = bytes;
        }

        private static CellData load(CompoundTag tag) {
            CellData cell = new CellData(tag.getString(TAG_TYPE), tag.getLong(TAG_BYTES));
            ListTag hashes = tag.getList(TAG_HASHES, Tag.TAG_STRING);
            boolean bigAmountFormat = tag.getList(TAG_AMTS, Tag.TAG_COMPOUND).size() > 0;
            if (bigAmountFormat) {
                ListTag amountsTag = tag.getList(TAG_AMTS, Tag.TAG_COMPOUND);
                for (int i = 0; i < hashes.size(); i++) {
                    if (i >= amountsTag.size()) continue;
                    java.math.BigInteger amount = new java.math.BigInteger(amountsTag.getCompound(i).getByteArray("value"));
                    if (amount.signum() > 0) {
                        cell.bigAmounts.put(hashes.getString(i), amount);
                        cell.itemCount = cell.itemCount.add(amount);
                        if (amount.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                            cell.amounts.put(hashes.getString(i), amount.longValue());
                        }
                    }
                }
                return cell;
            }
            long[] amounts = tag.getLongArray(TAG_AMTS);
            for (int i = 0; i < hashes.size(); i++) {
                long amount = i < amounts.length ? amounts[i] : 0L;
                if (amount > 0) {
                    cell.amounts.put(hashes.getString(i), amount);
                    java.math.BigInteger bigAmount = java.math.BigInteger.valueOf(amount);
                    cell.bigAmounts.put(hashes.getString(i), bigAmount);
                    cell.itemCount = cell.itemCount.add(bigAmount);
                }
            }
            return cell;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString(TAG_TYPE, type);
            tag.putLong(TAG_BYTES, bytes);
            ListTag hashes = new ListTag();
            ListTag amountsArray = new ListTag();
            int index = 0;
            Map<String, java.math.BigInteger> source = !bigAmounts.isEmpty() ? bigAmounts : convertLongAmounts(amounts);
            for (Map.Entry<String, java.math.BigInteger> entry : source.entrySet()) {
                hashes.add(StringTag.valueOf(entry.getKey()));
                CompoundTag amountTag = new CompoundTag();
                java.math.BigInteger amount = entry.getValue() != null ? entry.getValue() : java.math.BigInteger.ZERO;
                amountTag.putByteArray("value", amount.toByteArray());
                amountsArray.add(amountTag);
                index++;
            }
            tag.put(TAG_HASHES, hashes);
            tag.put(TAG_AMTS, amountsArray);
            return tag;
        }

        private static Map<String, java.math.BigInteger> convertLongAmounts(Map<String, Long> amounts) {
            Map<String, java.math.BigInteger> result = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : amounts.entrySet()) {
                long amount = entry.getValue() != null ? entry.getValue() : 0L;
                if (amount > 0) result.put(entry.getKey(), java.math.BigInteger.valueOf(amount));
            }
            return result;
        }
    }
}
