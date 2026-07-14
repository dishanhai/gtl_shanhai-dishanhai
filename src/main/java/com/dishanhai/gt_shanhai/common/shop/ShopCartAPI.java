package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopCartSyncPacket;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 购物车服务端读写 API（山海署名）。全部按<b>玩家本体 UUID</b> 操作 {@link ShopCartSavedData}。
 *
 * <p>购物车只存"候选"（稳定 ID + 次数），不代表已购买、不参与实际扣款——真正购买仍走既有的
 * {@code ShopActionPacket#BUY}；结算 = 按候选清单逐项各自单独发起购买请求（异步、互不阻塞，
 * 每项各自走原有的成本/限购/前置任务校验，见反馈：结算要异步逐项校验，不做整体原子结算）。</p>
 *
 * <p><b>仅服务端调用</b>：客户端界面读 {@code ClientShopCart} 快照缓存。</p>
 */
public final class ShopCartAPI {

    private ShopCartAPI() {}

    private static ShopCartSavedData data(MinecraftServer server) {
        return ShopCartSavedData.get(server);
    }

    /** 加入购物车（已存在则忽略，不覆盖已有数量；数量调整走 {@link #setAmount}）。 */
    public static void add(MinecraftServer server, UUID uuid, String stableId, long amount) {
        if (stableId == null || stableId.isBlank() || amount <= 0L) return;
        ShopCartSavedData d = data(server);
        d.getOrCreate(uuid).addIfAbsent(stableId, amount);
        d.setDirty();
    }

    /** 覆盖写候选购买次数（购物车面板的数量调整用）；≤0 视为移除。 */
    public static void setAmount(MinecraftServer server, UUID uuid, String stableId, long amount) {
        if (stableId == null || stableId.isBlank()) return;
        ShopCartSavedData d = data(server);
        d.getOrCreate(uuid).setAmount(stableId, amount);
        d.setDirty();
    }

    /** 从购物车移除某条目（结算成功/玩家手动删除都走这个）。 */
    public static void remove(MinecraftServer server, UUID uuid, String stableId) {
        if (stableId == null) return;
        ShopCartSavedData d = data(server);
        ShopCart cart = d.get(uuid);
        if (cart == null) return;
        cart.remove(stableId);
        d.setDirty();
    }

    /** 读该玩家购物车全部候选（副本，保序）。 */
    public static Map<String, Long> getAll(MinecraftServer server, UUID uuid) {
        ShopCart cart = data(server).get(uuid);
        return cart == null ? new LinkedHashMap<>() : cart.getItems();
    }

    /** 向该玩家推送购物车全量快照（打开商店 / 每次购物车变动后调用）。 */
    public static void sync(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ShanhaiNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new ShopCartSyncPacket(getAll(server, player.getUUID())));
    }
}
