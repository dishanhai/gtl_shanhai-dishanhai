package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopFavoriteSyncPacket;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 收藏服务端读写 API（山海署名）。全部按<b>玩家本体 UUID</b> 操作 {@link ShopFavoriteSavedData}。
 *
 * <p>收藏是纯粹的个人标记（右键菜单一键收藏/取消），不影响商店目录本身、不参与购买结算——
 * 跟 {@link ShopCartAPI}（购物车）同源设计，见 {@link ShopEntry} 的 stableId 字段注释。</p>
 *
 * <p><b>仅服务端调用</b>：客户端界面读 {@code ClientShopFavorites} 快照缓存。</p>
 */
public final class ShopFavoriteAPI {

    private ShopFavoriteAPI() {}

    private static ShopFavoriteSavedData data(MinecraftServer server) {
        return ShopFavoriteSavedData.get(server);
    }

    /** 加入收藏（已存在则忽略）。 */
    public static void add(MinecraftServer server, UUID uuid, String stableId) {
        if (stableId == null || stableId.isBlank()) return;
        ShopFavoriteSavedData d = data(server);
        if (d.getOrCreate(uuid).add(stableId)) d.setDirty();
    }

    /** 从收藏移除。 */
    public static void remove(MinecraftServer server, UUID uuid, String stableId) {
        if (stableId == null) return;
        ShopFavoriteSavedData d = data(server);
        ShopFavorites favorites = d.get(uuid);
        if (favorites == null) return;
        if (favorites.remove(stableId)) d.setDirty();
    }

    /** 读该玩家全部收藏（副本，保序）。 */
    public static Set<String> getAll(MinecraftServer server, UUID uuid) {
        ShopFavorites favorites = data(server).get(uuid);
        return favorites == null ? new LinkedHashSet<>() : favorites.getAll();
    }

    /** 向该玩家推送收藏全量快照（打开商店 / 每次收藏变动后调用）。 */
    public static void sync(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ShanhaiNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new ShopFavoriteSyncPacket(getAll(server, player.getUUID())));
    }
}
