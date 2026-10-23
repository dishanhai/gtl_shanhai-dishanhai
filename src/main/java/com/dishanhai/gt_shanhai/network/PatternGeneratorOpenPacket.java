package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorCodec;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** 山海生成器開啟快照（伺服器至客戶端）。 */
public record PatternGeneratorOpenPacket(InteractionHand hand, PatternGeneratorState state) {
    public PatternGeneratorOpenPacket(FriendlyByteBuf buffer) {
        this(buffer.readEnum(InteractionHand.class), PatternGeneratorCodec.readState(buffer));
    }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        PatternGeneratorCodec.writeState(buffer, state);
    }
    public static void handle(PatternGeneratorOpenPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient())
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreenOpener.open(packet.hand(), packet.state())));
        context.setPacketHandled(true);
    }
}

