package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopFavoriteEditPacket;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端收藏快照缓存（山海署名，仅客户端）。
 *
 * <p>收藏按稳定身份 ID（见 {@code ShopEntry#getStableId}）索引，跨快照/跨重登有效——不能用 entryKey
 * （每次 {@code ShopConfig#publish} 都会重排）。服务端 {@code ShopFavoriteSyncPacket} 推送后写入这里，
 * 供 {@code ShopScreen} 右键菜单一键收藏、网格角标、以及「只看收藏」筛选读取。</p>
 *
 * <p>{@link #add}/{@link #remove}/{@link #toggle} 均先本地乐观改缓存（界面立刻有反馈），再发编辑包给
 * 服务端；服务端处理完总会回推一份权威快照整体覆盖校正（同 {@code ClientShopCart} 同款节奏），不需要
 * 调用方自己对齐。</p>
 */
public final class ClientShopFavorites {

    private static Set<String> stableIds = new LinkedHashSet<>();

    private ClientShopFavorites() {}

    /** 应用服务端全量快照（权威覆盖）。 */
    public static void apply(Set<String> newIds) {
        stableIds = newIds != null ? new LinkedHashSet<>(newIds) : new LinkedHashSet<>();
    }

    public static boolean contains(String stableId) {
        return stableId != null && stableIds.contains(stableId);
    }

    /** 全部收藏（副本，保序=收藏顺序）。 */
    public static Set<String> getAll() {
        return new LinkedHashSet<>(stableIds);
    }

    public static int size() {
        return stableIds.size();
    }

    /** 加入收藏；本地乐观更新 + 发编辑包。 */
    public static void add(String stableId) {
        if (stableId == null || stableId.isBlank() || stableIds.contains(stableId)) return;
        stableIds.add(stableId);
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopFavoriteEditPacket(ShopFavoriteEditPacket.Action.ADD, stableId));
    }

    /** 取消收藏；本地乐观更新 + 发编辑包。 */
    public static void remove(String stableId) {
        if (stableId == null || !stableIds.contains(stableId)) return;
        stableIds.remove(stableId);
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopFavoriteEditPacket(ShopFavoriteEditPacket.Action.REMOVE, stableId));
    }

    /** 右键菜单「收藏/取消收藏」一键切换。 */
    public static void toggle(String stableId) {
        if (contains(stableId)) remove(stableId); else add(stableId);
    }
}
