package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCartAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 购物车编辑包（C→S）：加入 / 删除 / 改购买次数。任何玩家都能管理自己的购物车（不需要商店编辑权限，
 * 购物车是玩家个人候选清单，不影响商店目录本身）。每次变更后服务端立刻 {@link ShopCartAPI#sync}
 * 把该玩家购物车全量推回去，客户端 {@code ClientShopCart} 以服务端为准覆盖本地乐观更新。
 */
public class ShopCartEditPacket {

    public enum Action { ADD, REMOVE, SET_AMOUNT }

    private final Action action;
    private final String stableId;
    private final long amount; // ADD/SET_AMOUNT 有意义；REMOVE 忽略

    public ShopCartEditPacket(Action action, String stableId, long amount) {
        this.action = action;
        this.stableId = stableId == null ? "" : stableId;
        this.amount = amount;
    }

    public ShopCartEditPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
        this.stableId = buf.readUtf();
        this.amount = buf.readVarLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(stableId);
        buf.writeVarLong(amount);
    }

    public static void handle(ShopCartEditPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ShopCartEditPacket pkt, ServerPlayer player) {
        if (pkt.stableId.isBlank()) return;
        var server = player.getServer();
        if (server == null) return;
        switch (pkt.action) {
            case ADD -> ShopCartAPI.add(server, player.getUUID(), pkt.stableId, Math.max(1L, pkt.amount));
            case SET_AMOUNT -> ShopCartAPI.setAmount(server, player.getUUID(), pkt.stableId, pkt.amount);
            case REMOVE -> ShopCartAPI.remove(server, player.getUUID(), pkt.stableId);
        }
        ShopCartAPI.sync(player);
    }
}
