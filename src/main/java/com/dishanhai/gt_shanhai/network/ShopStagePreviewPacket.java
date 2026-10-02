package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.client.shop.ClientCostPreview;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 阶段解锁页库存预览回包。 */
public final class ShopStagePreviewPacket {
    private static final int MAX_ENTRIES = 64;
    private final String stagePath;
    private final boolean aeMode;
    private final List<Long> items;

    public ShopStagePreviewPacket(String stagePath, boolean aeMode, List<Long> items) {
        this.stagePath = stagePath == null ? "" : stagePath;
        this.aeMode = aeMode;
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    public ShopStagePreviewPacket(FriendlyByteBuf buf) {
        this.stagePath = buf.readUtf(256);
        this.aeMode = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new DecoderException("Invalid stage preview item count: " + count);
        }
        List<Long> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(buf.readVarLong());
        this.items = List.copyOf(values);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(stagePath, 256);
        buf.writeBoolean(aeMode);
        buf.writeVarInt(Math.min(MAX_ENTRIES, items.size()));
        for (int i = 0; i < items.size() && i < MAX_ENTRIES; i++) {
            buf.writeVarLong(items.get(i) == null ? 0L : items.get(i));
        }
    }

    public static void handle(ShopStagePreviewPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // 封包已在 ShanhaiNetwork 注册为 PLAY_TO_CLIENT，这里直接进入客户端工作队列。
        context.enqueueWork(() -> applyClient(packet));
        context.setPacketHandled(true);
    }

    private static void applyClient(ShopStagePreviewPacket packet) {
        ClientCostPreview.applyStage(packet.stagePath, packet.aeMode, packet.items);
    }
}
