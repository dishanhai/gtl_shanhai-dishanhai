package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import com.dishanhai.gt_shanhai.common.item.terminal.StellarTerminalDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public final class ShanhaiPatternStellarDetailPacket {

    private static final int MAX_DETAILS = 256;

    private final int menuId;
    private final List<StellarTerminalDetail> details;

    public ShanhaiPatternStellarDetailPacket(int menuId, List<StellarTerminalDetail> details) {
        this.menuId = menuId;
        this.details = List.copyOf(details);
    }

    public static void encode(ShanhaiPatternStellarDetailPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.menuId);
        int size = Math.min(packet.details.size(), MAX_DETAILS);
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            StellarTerminalDetail detail = packet.details.get(index);
            buffer.writeLong(detail.serverId());
            buffer.writeComponent(detail.name());
            buffer.writeComponent(detail.host());
            buffer.writeInt(detail.x());
            buffer.writeInt(detail.y());
            buffer.writeInt(detail.z());
            buffer.writeVarInt(detail.filledPatterns());
            buffer.writeVarInt(detail.patternCapacity());
            buffer.writeVarInt(detail.wildcardMothers());
            buffer.writeVarInt(detail.wildcardExpanded());
            buffer.writeBoolean(detail.multiplierEnabled());
            buffer.writeVarLong(detail.multiplier());
            buffer.writeVarInt(detail.circuit());
            buffer.writeVarInt(detail.warnings());
            buffer.writeBoolean(detail.online());
            buffer.writeVarInt(detail.hostCount());
            buffer.writeBoolean(detail.hostFormed());
            buffer.writeInt(detail.hostX());
            buffer.writeInt(detail.hostY());
            buffer.writeInt(detail.hostZ());
            buffer.writeUtf(detail.hostStatus(), 180);
            buffer.writeUtf(detail.hostRecipeTypes(), 120);
            buffer.writeUtf(detail.stuckBroadcast(), 960);
            buffer.writeVarInt(detail.proxyCount());
            buffer.writeUtf(detail.proxyLines(), 480);
        }
    }

    public static ShanhaiPatternStellarDetailPacket decode(FriendlyByteBuf buffer) {
        int menuId = buffer.readVarInt();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_DETAILS) {
            throw new io.netty.handler.codec.DecoderException(
                    "ShanhaiPatternStellarDetailPacket size out of range: " + size);
        }
        List<StellarTerminalDetail> details = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            details.add(new StellarTerminalDetail(
                    buffer.readLong(),
                    buffer.readComponent(),
                    buffer.readComponent(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readVarLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readUtf(180),
                    buffer.readUtf(120),
                    buffer.readUtf(960),
                    buffer.readVarInt(),
                    buffer.readUtf(480)));
        }
        return new ShanhaiPatternStellarDetailPacket(menuId, details);
    }

    public static void handle(ShanhaiPatternStellarDetailPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> applyClient(packet));
        context.setPacketHandled(true);
    }

    private static void applyClient(ShanhaiPatternStellarDetailPacket packet) {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof ShanhaiPatternManagementMenu menu
                && menu.containerId == packet.menuId) {
            menu.replaceStellarDetails(packet.details);
        }
    }
}
