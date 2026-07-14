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
 * 购物车存档（山海署名）。
 *
 * <p>购物车按<b>玩家本体 UUID</b>（{@code player.getUUID()}）索引，存于 overworld 的 {@code DataStorage}，
 * 名 {@value #DATA_NAME}。结构与生命周期照抄 {@link WalletAccountSavedData}：
 * {@link #get(MinecraftServer)} 惰性 computeIfAbsent，写操作后 {@link #setDirty()}。</p>
 */
public class ShopCartSavedData extends SavedData {

    private static final String DATA_NAME = "gt_shanhai_shop_carts";
    private static final String TAG_CARTS = "carts";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_DATA = "data";

    /** 玩家UUID → 购物车（保序）。 */
    private final Map<UUID, ShopCart> carts = new LinkedHashMap<>();

    public static ShopCartSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ShopCartSavedData::load,
                ShopCartSavedData::new,
                DATA_NAME);
    }

    public static ShopCartSavedData load(CompoundTag tag) {
        ShopCartSavedData data = new ShopCartSavedData();
        ListTag list = tag.getList(TAG_CARTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID(TAG_UUID)) continue;
            data.carts.put(entry.getUUID(TAG_UUID), ShopCart.load(entry.getCompound(TAG_DATA)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ShopCart> e : carts.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            entry.put(TAG_DATA, e.getValue().save());
            list.add(entry);
        }
        tag.put(TAG_CARTS, list);
        return tag;
    }

    /** 读购物车，无则 null（只读场景用，避免凭空建一个空购物车占内存/占存档）。 */
    public ShopCart get(UUID uuid) {
        return uuid == null ? null : carts.get(uuid);
    }

    /** 取购物车，无则新建（写场景用）。 */
    public ShopCart getOrCreate(UUID uuid) {
        return carts.computeIfAbsent(uuid, k -> new ShopCart());
    }
}
