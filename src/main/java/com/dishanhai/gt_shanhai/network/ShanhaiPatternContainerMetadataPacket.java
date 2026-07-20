package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiPatternManagementMenu;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

public final class ShanhaiPatternContainerMetadataPacket {

    private final int menuId;
    private final Set<Long> stellarContainerIds;

    public ShanhaiPatternContainerMetadataPacket(int menuId, Set<Long> stellarContainerIds) {
        this.menuId = menuId;
        this.stellarContainerIds = Set.copyOf(stellarContainerIds);
    }

    public static void encode(ShanhaiPatternContainerMetadataPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.menuId);
        buffer.writeVarInt(packet.stellarContainerIds.size());
        for (long serverId : packet.stellarContainerIds) {
            buffer.writeLong(serverId);
        }
    }

    public static ShanhaiPatternContainerMetadataPacket decode(FriendlyByteBuf buffer) {
        int menuId = buffer.readVarInt();
        int size = buffer.readVarInt();
        Set<Long> ids = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(buffer.readLong());
        }
        return new ShanhaiPatternContainerMetadataPacket(menuId, ids);
    }

    public static void handle(ShanhaiPatternContainerMetadataPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> applyClient(packet));
        context.setPacketHandled(true);
    }

    private static void applyClient(ShanhaiPatternContainerMetadataPacket packet) {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof ShanhaiPatternManagementMenu menu
                && menu.containerId == packet.menuId) {
            menu.replaceStellarContainerIds(packet.stellarContainerIds);
        }
    }
}
