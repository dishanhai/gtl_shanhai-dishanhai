package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternTerminalOpenHelper;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

public final class ShanhaiPatternTerminalOpenRequestPacket {

    public static void encode(ShanhaiPatternTerminalOpenRequestPacket packet, FriendlyByteBuf buffer) {
    }

    public static ShanhaiPatternTerminalOpenRequestPacket decode(FriendlyByteBuf buffer) {
        return new ShanhaiPatternTerminalOpenRequestPacket();
    }

    public static void handle(ShanhaiPatternTerminalOpenRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                ShanhaiPatternTerminalOpenHelper.openFirst(player);
            }
        });
        context.setPacketHandled(true);
    }
}
