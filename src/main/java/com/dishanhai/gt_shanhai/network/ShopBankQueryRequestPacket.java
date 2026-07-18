package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 会员中心「银行」查询请求（C→S，空载荷）：打开/刷新银行面板时向服务端要一份最新的
 * 存款/欠款惰性结息快照（见 {@link ShopBankQueryPacket}）。
 */
public class ShopBankQueryRequestPacket {

    public ShopBankQueryRequestPacket() {}

    public ShopBankQueryRequestPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public static void handle(ShopBankQueryRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) ShopBankQueryPacket.sendTo(player);
        });
        context.setPacketHandled(true);
    }
}
