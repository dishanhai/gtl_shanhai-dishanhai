package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogSnapshot;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** 客户端按服务端 manifest 给出的 chunkId 请求山海商店完整商品负载。 */
public final class ShopCatalogChunkRequestPacket {

    private final long revision;
    private final long requestId;
    private final int chunkId;

    public ShopCatalogChunkRequestPacket(long revision, long requestId, int chunkId) {
        this.revision = revision;
        this.requestId = requestId;
        this.chunkId = chunkId;
    }

    public ShopCatalogChunkRequestPacket(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readVarLong(), buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(revision);
        buf.writeVarLong(requestId);
        buf.writeVarInt(chunkId);
    }

    public long revision() { return revision; }
    public long requestId() { return requestId; }
    public int chunkId() { return chunkId; }

    public static void handle(ShopCatalogChunkRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || packet.requestId <= 0L || packet.chunkId < 0) return;
            ShopCatalogSnapshot snapshot = ShopConfig.snapshot();
            if (snapshot.revision() != packet.revision) return;
            ShanhaiNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ShopCatalogChunkPacket(snapshot.revision(), packet.requestId,
                            packet.chunkId, snapshot.chunk(packet.chunkId)));
        });
        context.setPacketHandled(true);
    }
}
