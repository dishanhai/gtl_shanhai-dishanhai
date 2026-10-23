package com.dishanhai.gt_shanhai.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** 山海生成器本地預設匯出與預覽（120 / 121）。 */
public record PatternGeneratorPresetPacket(UUID sessionId, int kind, byte[] payload) {
    public static final int MAX_PAYLOAD = 8 * 1024 * 1024;
    public PatternGeneratorPresetPacket {
        if ((kind != 120 && kind != 121) || payload.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("Invalid pattern generator preset packet");
        payload = payload.clone();
    }
    @Override public byte[] payload() { return payload.clone(); }
    public PatternGeneratorPresetPacket(FriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readVarInt(), buffer.readByteArray(MAX_PAYLOAD));
    }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId); buffer.writeVarInt(kind); buffer.writeByteArray(payload);
    }
    public static void handle(PatternGeneratorPresetPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient())
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreenOpener.preset(
                            packet.sessionId(), packet.kind(), packet.payload())));
        context.setPacketHandled(true);
    }
}

