package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 兑换刷新包（S→C，山海署名）。
 *
 * <p>兑换条目在服务端<b>增 / 删 / 改</b>后回推给该玩家，客户端若正开着兑换界面就重排。</p>
 */
public class ShopRefreshPacket {

    public ShopRefreshPacket() {}

    public ShopRefreshPacket(FriendlyByteBuf buf) {}

    public void encode(FriendlyByteBuf buf) {}

    public static void handle(ShopRefreshPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        if (c.getDirection().getReceptionSide().isClient()) {
            c.enqueueWork(ShopRefreshPacket::applyClient);
        }
        c.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.gui.screens.Screen s = mc.screen;
        if (s instanceof com.dishanhai.gt_shanhai.client.gui.shop.ExchangeScreen) {
            s.resize(mc, s.width, s.height); // 触发 init() 重排，重读已更新清单
        }
    }

    /** 服务端便捷回推：告知该玩家刷新兑换界面。 */
    public static void sendTo(ServerPlayer player) {
        if (player != null) {
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ShopRefreshPacket());
        }
    }
}
