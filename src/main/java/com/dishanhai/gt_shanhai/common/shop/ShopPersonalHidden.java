package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 山海：个人隐藏只写当前世界中的玩家持久数据，从不改动商品目录。 */
public final class ShopPersonalHidden {
    public static final int MAX_ENTRIES = 2048;
    private static final String KEY = "gt_shanhai:shop_personal_hidden";

    private ShopPersonalHidden() {}

    public static Set<String> get(ServerPlayer player) {
        return read(player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG));
    }

    public static Set<String> set(ServerPlayer player, Collection<String> ids, boolean hidden) {
        CompoundTag data = player.getPersistentData();
        CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        Set<String> current = update(persisted, ids, hidden);
        data.put(Player.PERSISTED_NBT_TAG, persisted);
        return current;
    }

    static Set<String> read(CompoundTag persisted) {
        Set<String> ids = new LinkedHashSet<>();
        for (Tag tag : persisted.getList(KEY, Tag.TAG_STRING)) {
            String id = tag.getAsString();
            if (valid(id)) ids.add(id);
            if (ids.size() >= MAX_ENTRIES) break;
        }
        return Collections.unmodifiableSet(ids);
    }

    static Set<String> update(CompoundTag persisted, Collection<String> ids, boolean hidden) {
        Set<String> current = new LinkedHashSet<>(read(persisted));
        if (hidden) {
            for (String id : ids) {
                if (current.size() >= MAX_ENTRIES) break;
                if (valid(id)) current.add(id);
            }
        } else {
            current.removeAll(ids);
        }
        if (current.isEmpty()) {
            persisted.remove(KEY);
            return Set.of();
        }
        ListTag tags = new ListTag();
        current.forEach(id -> tags.add(StringTag.valueOf(id)));
        persisted.put(KEY, tags);
        return Collections.unmodifiableSet(current);
    }

    private static boolean valid(String id) {
        return id != null && !id.isBlank() && id.length() <= 128;
    }
}
