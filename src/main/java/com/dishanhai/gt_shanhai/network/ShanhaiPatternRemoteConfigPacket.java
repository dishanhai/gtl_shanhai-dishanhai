package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStellarRemoteUIFactory;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiStellarRemoteUIHolder;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

public final class ShanhaiPatternRemoteConfigPacket {

    public enum Operation {
        OPEN_SLOT_CATALYST,
        OPEN_STOCK_INPUT,
        OPEN_FULL_PATTERN,
        OPEN_OUTPUT_MULTIPLIER,
        OPEN_WILDCARD,
        OPEN_SHARED_INPUTS,
        OPEN_PATTERN_BEHAVIOR,
        OPEN_REFUND
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
            return;
        }
        ShanhaiStellarRemoteUIHolder.Mode mode = remoteMode(packet.operation);
        if (mode == null) return;
        ShanhaiStellarRemoteUIFactory.INSTANCE.openMode(
                player, stellar, menu.getWirelessHost(), mode, -1);
    }

    private static ShanhaiStellarRemoteUIHolder.Mode remoteMode(Operation operation) {
        if (operation == Operation.OPEN_STOCK_INPUT) return ShanhaiStellarRemoteUIHolder.Mode.STOCK_INPUT;
        if (operation == Operation.OPEN_FULL_PATTERN) return ShanhaiStellarRemoteUIHolder.Mode.FULL_PATTERN;
        if (operation == Operation.OPEN_OUTPUT_MULTIPLIER) {
            return ShanhaiStellarRemoteUIHolder.Mode.OUTPUT_MULTIPLIER;
        }
        if (operation == Operation.OPEN_WILDCARD) return ShanhaiStellarRemoteUIHolder.Mode.WILDCARD;
        if (operation == Operation.OPEN_SHARED_INPUTS) return ShanhaiStellarRemoteUIHolder.Mode.SHARED_INPUTS;
        if (operation == Operation.OPEN_PATTERN_BEHAVIOR) {
            return ShanhaiStellarRemoteUIHolder.Mode.PATTERN_BEHAVIOR;
        }
        if (operation == Operation.OPEN_REFUND) return ShanhaiStellarRemoteUIHolder.Mode.REFUND;
        return null;
    }
}
