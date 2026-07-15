package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopFavoriteAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 收藏编辑包（C→S）：收藏 / 取消收藏。任何玩家都能管理自己的收藏（不需要商店编辑权限，收藏是玩家
 * 个人标记，不影响商店目录本身）。每次变更后服务端立刻 {@link ShopFavoriteAPI#sync} 把该玩家收藏全量
 * 推回去，客户端 {@code ClientShopFavorites} 以服务端为准覆盖本地乐观更新（同 {@link ShopCartEditPacket}
 * 同款节奏）。
 */
public class ShopFavoriteEditPacket {

    public enum Action { ADD, REMOVE }

    private final Action action;
    private final String stableId;

    public ShopFavoriteEditPacket(Action action, String stableId) {
        this.action = action;
        this.stableId = stableId == null ? "" : stableId;
    }

    public ShopFavoriteEditPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.stableId = buf.readUtf();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(stableId);
    }

    public static void handle(ShopFavoriteEditPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopFavoriteEditPacket pkt, ServerPlayer player) {
        if (pkt.stableId.isBlank()) return;
        var server = player.getServer();
        if (server == null) return;
        switch (pkt.action) {
            case ADD -> ShopFavoriteAPI.add(server, player.getUUID(), pkt.stableId);
            case REMOVE -> ShopFavoriteAPI.remove(server, player.getUUID(), pkt.stableId);
        }
        ShopFavoriteAPI.sync(player);
    }
}
