package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorCodec;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** 山海生成器狀態更新；空快照表示伺服器關閉此 token 的畫面。 */
public record PatternGeneratorStatePacket(UUID sessionId, PatternGeneratorState state) {
    public PatternGeneratorStatePacket(PatternGeneratorState state) { this(state.sessionId(), state); }
    public PatternGeneratorStatePacket(UUID sessionId) { this(sessionId, null); }
    public PatternGeneratorStatePacket(FriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readBoolean() ? PatternGeneratorCodec.readState(buffer) : null);
    }
    public PatternGeneratorStatePacket {
        if (state != null && !sessionId.equals(state.sessionId()))
            throw new IllegalArgumentException("Mismatched pattern generator session");
    }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId);
        buffer.writeBoolean(state != null);
        if (state != null) PatternGeneratorCodec.writeState(buffer, state);
    }
    public static void handle(PatternGeneratorStatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient())
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (packet.state() == null)
                    com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreenOpener.close(packet.sessionId());
                else com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreenOpener.update(packet.state());
            }));
        context.setPacketHandled(true);
    }
}

