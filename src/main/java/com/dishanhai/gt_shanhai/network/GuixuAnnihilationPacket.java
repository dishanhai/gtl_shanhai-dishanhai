package com.dishanhai.gt_shanhai.network;

// Author: dishanhai
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record GuixuAnnihilationPacket(BlockPos controller, BlockPos center, float radius, int spin, long phaseTick,
                                     boolean preview) {
    public GuixuAnnihilationPacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), buf.readBlockPos(), buf.readFloat(), buf.readVarInt(), buf.readVarLong(), buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(controller);
        buf.writeBlockPos(center);
        buf.writeFloat(radius);
        buf.writeVarInt(spin);
        buf.writeVarLong(phaseTick);
        buf.writeBoolean(preview);
    }

    public static void handle(GuixuAnnihilationPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> com.dishanhai.gt_shanhai.client.renderer.hostfx.GuixuAnnihilationFx.start(packet));
        }
        context.setPacketHandled(true);
    }
}
