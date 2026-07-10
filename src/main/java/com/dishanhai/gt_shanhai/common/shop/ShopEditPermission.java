package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 商店编辑权限白名单（山海署名）。
 *
 * <p>持久化一份被授权玩家的 UUID 集合（{@link SavedData}，随存档保存）。
 * {@link #canEdit(ServerPlayer)} = OP（权限等级 ≥ 2）<b>或</b>在白名单内。
 * OP 默认拥有编辑权，无需授权；非 OP 玩家由 OP 用指令
 * {@code /山海 商店 授权 <玩家>} 授予/撤销。</p>
 */
public class ShopEditPermission extends SavedData {

    private static final String DATA_NAME = "gt_shanhai_shop_editors";
    private static final String TAG_EDITORS = "editors";

    private final Set<UUID> editors = new LinkedHashSet<>();

    public static ShopEditPermission get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ShopEditPermission::load,
                ShopEditPermission::new,
                DATA_NAME);
    }

    public static ShopEditPermission load(CompoundTag tag) {
        ShopEditPermission data = new ShopEditPermission();
        ListTag list = tag.getList(TAG_EDITORS, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.editors.add(net.minecraft.nbt.NbtUtils.loadUUID(list.get(i)));
            } catch (Exception ignored) {
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID id : editors) {
            list.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        }
        tag.put(TAG_EDITORS, list);
        return tag;
    }

    /** 是否已在白名单（不含 OP 判定，纯名单查询）。 */
    public boolean isGranted(UUID id) {
        return editors.contains(id);
    }

    /** 授予编辑权；返回是否新增（已在名单返回 false）。 */
    public boolean grant(UUID id) {
        boolean added = editors.add(id);
        if (added) setDirty();
        return added;
    }

    /** 撤销编辑权；返回是否移除。 */
    public boolean revoke(UUID id) {
        boolean removed = editors.remove(id);
        if (removed) setDirty();
        return removed;
    }

    /** 当前白名单 UUID 快照。 */
    public Set<UUID> all() {
        return new LinkedHashSet<>(editors);
    }

    // ===== 静态便捷判定 =====

    /** 玩家是否可编辑商店：OP（≥2）或在白名单内。 */
    public static boolean canEdit(ServerPlayer player) {
        if (player == null) return false;
        if (player.hasPermissions(2)) return true;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        return get(server).isGranted(player.getUUID());
    }
}
