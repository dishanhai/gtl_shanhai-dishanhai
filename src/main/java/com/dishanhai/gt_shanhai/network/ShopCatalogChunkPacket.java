package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogEntryPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/** 服务端返回的山海商店纯 JSON 分块；网络回调只入队，不创建 ItemStack。 */
public final class ShopCatalogChunkPacket {

    private final long revision;
    private final long requestId;
    private final int chunkId;
    private final List<ShopCatalogEntryPayload> entries;

    public ShopCatalogChunkPacket(long revision, long requestId, int chunkId,
                                  List<ShopCatalogEntryPayload> entries) {
        this.revision = revision;
        this.requestId = requestId;
        this.chunkId = chunkId;
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public ShopCatalogChunkPacket(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readVarLong(), buf.readVarInt(), ShopCatalogCodecs.readPayloads(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(revision);
        buf.writeVarLong(requestId);
        buf.writeVarInt(chunkId);
        ShopCatalogCodecs.writePayloads(buf, entries);
    }

    public long revision() { return revision; }
    public long requestId() { return requestId; }
    public int chunkId() { return chunkId; }
    public List<ShopCatalogEntryPayload> entries() { return entries; }

    public static void handle(ShopCatalogChunkPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopCatalogChunkPacket packet) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog.acceptChunk(
                packet.revision, packet.requestId, packet.chunkId, packet.entries);
    }
}
