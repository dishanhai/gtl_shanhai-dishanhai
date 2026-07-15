package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 玩家收藏数据对象（山海署名）：稳定身份 ID（见 {@link ShopEntry#getStableId}）集合（保序=收藏顺序）。
 * 纯粹的个人标记，不影响商店目录本身、不参与购买结算——跟 {@link ShopCart} 同源设计（见 {@link ShopEntry}
 * 的 stableId 字段注释：购物车、收藏共用这个跨快照/跨重登稳定身份）。
 *
 * <p>该对象仅存于服务端 {@link ShopFavoriteSavedData}，按玩家本体 UUID 索引，跨重登保留。</p>
 */
public class ShopFavorites {

    private static final String TAG_IDS = "ids";

    /** stableId 集合（保序）。 */
    private final Set<String> stableIds = new LinkedHashSet<>();

    public boolean contains(String stableId) {
        return stableId != null && stableIds.contains(stableId);
    }

    public boolean add(String stableId) {
        return stableId != null && !stableId.isBlank() && stableIds.add(stableId);
    }

    public boolean remove(String stableId) {
        return stableId != null && stableIds.remove(stableId);
    }

    /** 只读视图（副本，保序）。 */
    public Set<String> getAll() {
        return new LinkedHashSet<>(stableIds);
    }

    // ===== NBT =====

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (String id : stableIds) {
            list.add(StringTag.valueOf(id));
        }
        tag.put(TAG_IDS, list);
        return tag;
    }

    public static ShopFavorites load(CompoundTag tag) {
        ShopFavorites favorites = new ShopFavorites();
        ListTag list = tag.getList(TAG_IDS, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String id = list.getString(i);
            if (!id.isBlank()) favorites.stableIds.add(id);
        }
        return favorites;
    }
}
