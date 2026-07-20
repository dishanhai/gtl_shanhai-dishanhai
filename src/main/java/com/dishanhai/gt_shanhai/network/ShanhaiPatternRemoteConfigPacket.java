package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStellarRemoteUIFactory;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

public final class ShanhaiPatternRemoteConfigPacket {

    public enum Operation {
        OPEN_SLOT_CATALYST,
        OPEN_STOCK_INPUT,
        OPEN_FULL_PATTERN
    }

    private final int menuId;
    private final long serverId;
    private final Operation operation;
    private final int slot;

    public ShanhaiPatternRemoteConfigPacket(int menuId, long serverId, Operation operation, int slot) {
        this.menuId = menuId;
        this.serverId = serverId;
        this.operation = operation;
        this.slot = slot;
    }

    public static void encode(ShanhaiPatternRemoteConfigPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.menuId);
        buffer.writeLong(packet.serverId);
        buffer.writeEnum(packet.operation);
        buffer.writeVarInt(packet.slot);
    }

    public static ShanhaiPatternRemoteConfigPacket decode(FriendlyByteBuf buffer) {
        return new ShanhaiPatternRemoteConfigPacket(
                buffer.readVarInt(),
                buffer.readLong(),
                buffer.readEnum(Operation.class),
                buffer.readVarInt());
    }

    public static void handle(ShanhaiPatternRemoteConfigPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> apply(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(ShanhaiPatternRemoteConfigPacket packet, ServerPlayer player) {
        if (player == null || !(player.containerMenu instanceof ShanhaiPatternManagementMenu menu)
                || menu.containerId != packet.menuId || !menu.stillValid(player)) {
            return;
        }
        RecipeTypePatternBufferPartMachine stellar = menu.resolveStellarContainer(packet.serverId);
        if (stellar == null) return;

        if (packet.operation == Operation.OPEN_SLOT_CATALYST) {
            if (!stellar.isValidRemotePatternSlot(packet.slot)) return;
            ShanhaiStellarRemoteUIFactory.INSTANCE.openSlot(
                    player, stellar, menu.getWirelessHost(), packet.slot);
        } else if (packet.operation == Operation.OPEN_STOCK_INPUT) {
            ShanhaiStellarRemoteUIFactory.INSTANCE.openStock(player, stellar, menu.getWirelessHost());
        } else {
            ShanhaiStellarRemoteUIFactory.INSTANCE.openPattern(player, stellar, menu.getWirelessHost());
        }
    }
}
