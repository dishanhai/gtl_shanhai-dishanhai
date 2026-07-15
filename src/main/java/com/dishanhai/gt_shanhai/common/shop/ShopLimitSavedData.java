package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商店「限购总量」剩余次数存档（山海署名）。按商品 {@link ShopEntry#getStableId} 索引，存于
 * overworld 的 {@code DataStorage}，名 {@value #DATA_NAME}。
 *
 * <p>取代原先直接把消耗后的剩余次数写回 {@code config/gt_shanhai/shop.json}——那是 Forge 全局
 * 配置目录，同一游戏实例下所有存档共享同一份文件，会导致玩家在存档 A 买掉的份额直接"继承"到
 * 存档 B（见反馈：跨存档不该共享消耗进度）。迁移后 shop.json 里的 {@code remainingUses} 只当
 * 「某存档第一次遇到这个 stableId 时的起始配额」：某存档一旦为这个 stableId 记过账，后续该存档
 * 永远认自己存档里的数，不再受 shop.json 数字变化影响——除非管理员在编辑器里显式改动限购次数，
 * 那条路径会连同当前存档一起覆盖（见 {@link com.dishanhai.gt_shanhai.network.ShopEditPacket}）。</p>
 *
 * <p>结构与生命周期照抄 {@link WalletAccountSavedData}：{@link #get(MinecraftServer)} 惰性
 * computeIfAbsent，写操作后 {@link #setDirty()}。</p>
 */
public class ShopLimitSavedData extends SavedData {

    private static final String DATA_NAME = "gt_shanhai_shop_limits";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_ID = "id";
    private static final String TAG_REMAINING = "remaining";

    /** 商品 stableId → 该存档里的当前剩余次数（保序）。 */
    private final Map<String, Long> remaining = new LinkedHashMap<>();

    public static ShopLimitSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ShopLimitSavedData::load,
                ShopLimitSavedData::new,
                DATA_NAME);
    }

    public static ShopLimitSavedData load(CompoundTag tag) {
        ShopLimitSavedData data = new ShopLimitSavedData();
        ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(TAG_ID)) continue;
            data.remaining.put(entry.getString(TAG_ID), entry.getLong(TAG_REMAINING));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Long> e : remaining.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_ID, e.getKey());
            entry.putLong(TAG_REMAINING, e.getValue());
            list.add(entry);
        }
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    /** 读该存档里某商品的剩余次数；从未记过账（新存档/新条目）返回 null，区分「0次」和「没记录」。 */
    public Long get(String stableId) {
        return stableId == null ? null : remaining.get(stableId);
    }

    /** 写入/覆盖该存档里某商品的剩余次数并标脏。 */
    public void set(String stableId, long value) {
        if (stableId == null) return;
        remaining.put(stableId, value);
        setDirty();
    }
}
