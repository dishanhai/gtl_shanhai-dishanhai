package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 山海模组网络通道。
 * 用于向客户端广播机器成形/解体的环隐藏操作。
 */
public class ShanhaiNetwork {

    private static final String PROTOCOL_VERSION = "1";
    private static final ResourceLocation CHANNEL_NAME = new ResourceLocation(GTDishanhaiMod.MOD_ID, "main");
    private static int packetId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_NAME,
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void init() {
        CHANNEL.registerMessage(
                packetId++,
                SHideRingPacket.class,
                SHideRingPacket::encode,
                SHideRingPacket::new,
                ShanhaiNetwork::handleHideRing
        );
        CHANNEL.registerMessage(
                packetId++,
                SelectableRecipeTypeSetPacket.class,
                SelectableRecipeTypeSetPacket::encode,
                SelectableRecipeTypeSetPacket::new,
                SelectableRecipeTypeSetPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                FtbqQueueTaskPacket.class,
                FtbqQueueTaskPacket::encode,
                FtbqQueueTaskPacket::new,
                FtbqQueueTaskPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                FtbqSubmitterListRequestPacket.class,
                FtbqSubmitterListRequestPacket::encode,
                FtbqSubmitterListRequestPacket::new,
                FtbqSubmitterListRequestPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                FtbqSubmitterListResponsePacket.class,
                FtbqSubmitterListResponsePacket::encode,
                FtbqSubmitterListResponsePacket::new,
                FtbqSubmitterListResponsePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ShopActionPacket.class,
                ShopActionPacket::encode,
                ShopActionPacket::new,
                ShopActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ShopOpenPacket.class,
                ShopOpenPacket::encode,
                ShopOpenPacket::new,
                ShopOpenPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                ShopEditPacket.class,
                ShopEditPacket::encode,
                ShopEditPacket::new,
                ShopEditPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                CurrencyActionPacket.class,
                CurrencyActionPacket::encode,
                CurrencyActionPacket::new,
                CurrencyActionPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                WalletAccountSyncPacket.class,
                WalletAccountSyncPacket::encode,
                WalletAccountSyncPacket::new,
                WalletAccountSyncPacket::handle
        );
        RecipeSyncPacket.init();
    }

    private static void handleHideRing(SHideRingPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleHideRingClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleHideRingClient(SHideRingPacket packet) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return;
        if (packet.add) {
            com.dishanhai.gt_shanhai.client.ShanhaiRingHelper.hideRings(level, packet.pos, packet.facing);
        } else {
            com.dishanhai.gt_shanhai.client.ShanhaiRingHelper.restoreRings(level, packet.pos, packet.facing);
        }
    }

    // ========== 快捷发送 ==========

    public static void sendHideRingToClients(ServerLevel level, SHideRingPacket packet) {
        CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), packet);
    }
}
