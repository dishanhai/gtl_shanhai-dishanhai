package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.function.Supplier;

/** 山海商店永久限购剩余量的绝对值增量同步。 */
public final class ShopCatalogStatePacket {

    private final long revision;
    private final long entryKey;
    private final long remainingUses;

    public ShopCatalogStatePacket(long revision, long entryKey, long remainingUses) {
        this.revision = revision;
        this.entryKey = entryKey;
        this.remainingUses = remainingUses;
    }

    public ShopCatalogStatePacket(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readLong(), buf.readLong());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(revision);
        buf.writeLong(entryKey);
        buf.writeLong(remainingUses);
    }

    public long revision() { return revision; }
    public long entryKey() { return entryKey; }
    public long remainingUses() { return remainingUses; }

    public static void handle(ShopCatalogStatePacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopCatalogStatePacket packet) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog.applyRemainingUses(
                packet.revision, packet.entryKey, packet.remainingUses);
    }

    /** 只广播有效的永久限购绝对值；启动期服务器尚未存在时安全跳过。 */
    public static void broadcast(long revision, long entryKey, long remainingUses) {
        if (entryKey < 0L || remainingUses < 0L) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ShopCatalogStatePacket packet = new ShopCatalogStatePacket(revision, entryKey, remainingUses);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}
