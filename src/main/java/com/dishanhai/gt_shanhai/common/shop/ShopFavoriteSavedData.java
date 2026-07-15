package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 收藏存档（山海署名）。
 *
 * <p>收藏按<b>玩家本体 UUID</b>（{@code player.getUUID()}）索引，存于 overworld 的 {@code DataStorage}，
 * 名 {@value #DATA_NAME}。结构与生命周期照抄 {@link ShopCartSavedData}：
 * {@link #get(MinecraftServer)} 惰性 computeIfAbsent，写操作后 {@link #setDirty()}。</p>
 */
public class ShopFavoriteSavedData extends SavedData {

    private static final String DATA_NAME = "gt_shanhai_shop_favorites";
    private static final String TAG_LIST = "favorites";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_DATA = "data";

    /** 玩家UUID → 收藏（保序）。 */
    private final Map<UUID, ShopFavorites> favorites = new LinkedHashMap<>();

    public static ShopFavoriteSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ShopFavoriteSavedData::load,
                ShopFavoriteSavedData::new,
                DATA_NAME);
    }

    public static ShopFavoriteSavedData load(CompoundTag tag) {
        ShopFavoriteSavedData data = new ShopFavoriteSavedData();
        ListTag list = tag.getList(TAG_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(TAG_UUID)) continue;
            data.favorites.put(entry.getUUID(TAG_UUID), ShopFavorites.load(entry.getCompound(TAG_DATA)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ShopFavorites> e : favorites.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.put(TAG_DATA, e.getValue().save());
            list.add(entry);
        }
        tag.put(TAG_LIST, list);
        return tag;
    }

    /** 读收藏，无则 null（只读场景用，避免凭空建一个空收藏占内存/占存档）。 */
    public ShopFavorites get(UUID uuid) {
        return uuid == null ? null : favorites.get(uuid);
    }

    /** 取收藏，无则新建（写场景用）。 */
    public ShopFavorites getOrCreate(UUID uuid) {
        return favorites.computeIfAbsent(uuid, k -> new ShopFavorites());
    }
}
