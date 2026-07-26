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

    /**
     * 以 long 视图整体覆盖写入单元。
     * ⚠️ 与 {@link #updateCellBig} 共用 UUID 空间且互为整体覆盖：现行约定是
     * "sda" 类型单元只走 updateCellBig/readCellBigAmounts（BigInteger 大数），
     * 虚拟磁盘 "item"/"fluid" 单元只走本方法与 readCellAmounts。
     * 对同一 UUID 混用两套接口会丢数据（long 视图读不到超 long 的条目）。
     */
    public void updateCell(UUID uuid, String type, long bytes, Map<AEKey, Long> amounts) {
        if (uuid == null) return;
        CellData previous = cells.get(uuid);
        if (previous != null && !previous.bigAmounts.isEmpty()
                && previous.amounts.size() != previous.bigAmounts.size()) {
            // 埋雷警戒线：long 写入方正在覆盖一个含超 long 大数的单元（见 2026-07 体检）
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.warn(
                    "[VirtualCell] updateCell 正以 long 视图覆盖含超 long 大数的单元 {}（type={}），超出部分将丢失",
                    uuid, previous.type);
        }
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

    /**
     * 以 BigInteger 大数视图整体覆盖写入单元（"sda" 内容单元专用）。
     * UUID 空间约定见 {@link #updateCell} 的注释，混用会丢数据。
     */
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
        if (!cell.bigAmounts.isEmpty() && cell.amounts.size() != cell.bigAmounts.size()) {
            // 埋雷警戒线：long 视图读不到该单元的全部条目，调用方应改用 readCellBigAmounts
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.warn(
                    "[VirtualCell] readCellAmounts({}) 读到含大数的单元（type={}），{} 个条目未包含在 long 视图中",
                    uuid, cell.type, cell.bigAmounts.size() - cell.amounts.size());
        }
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

    /**
     * 返回所有 SDA 单元的简要信息列表，供命令输出。
     * 每项：[uuid字符串, 总物品数字符串, 物品种类数字符串]
     */
    public java.util.List<String[]> listSdaCells() {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        for (Map.Entry<UUID, CellData> entry : cells.entrySet()) {
            CellData cell = entry.getValue();
            if (!"sda".equals(cell.type)) continue;
            result.add(new String[]{
                    entry.getKey().toString(),
                    cell.itemCount.toString(),
                    String.valueOf(cell.bigAmounts.isEmpty() ? cell.amounts.size() : cell.bigAmounts.size())
            });
        }
        return result;
    }

    /**
     * 启动时自动将所有 SDA 单元同步到 ContentStore（kubejs/data）。
     * 每次服务器启动时调用，无需手动操作。
     */
    /**
     * 启动时刷新：只更新 ContentStore 中已有文件的 SDA 单元。
     * 不会为新 UUID 创建文件，导出需通过命令显式操作。
     */
    public void syncAllSdaToContentStore() {
        int count = 0;
        for (Map.Entry<UUID, CellData> entry : cells.entrySet()) {
            CellData cell = entry.getValue();
            if (!"sda".equals(cell.type)) continue;
            if (cell.bigAmounts.isEmpty() && cell.amounts.isEmpty()) continue;
            // 只刷新已显式导出过的 UUID，不自动创建新文件
            if (!DShanhaiSdaContentStore.hasStored(entry.getKey())) continue;
            Map<AEKey, java.math.BigInteger> amounts = readCellBigAmounts(entry.getKey());
            if (!amounts.isEmpty()) {
                DShanhaiSdaContentStore.persist(entry.getKey(), amounts);
                count++;
            }
        }
        if (count > 0) {
            com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.info(
                    "[SDA] 启动刷新：已更新 {} 个已导出 SDA 单元", count);
        }
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
