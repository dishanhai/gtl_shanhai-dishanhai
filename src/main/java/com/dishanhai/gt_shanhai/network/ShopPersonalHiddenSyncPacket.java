package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopPersonalHidden;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/** 山海：仅向该玩家同步自己的隐藏清单。 */
public record ShopPersonalHiddenSyncPacket(Set<String> stableIds) {
    public ShopPersonalHiddenSyncPacket {
        stableIds = Set.copyOf(stableIds);
    }

    public ShopPersonalHiddenSyncPacket(FriendlyByteBuf buf) {
        this(readIds(buf));
    }

    private static Set<String> readIds(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > ShopPersonalHidden.MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid shop personal hidden count: " + count);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) ids.add(buf.readUtf(128));
        return ids;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(stableIds.size());
        for (String id : stableIds) buf.writeUtf(id, 128);
    }

    public static void sendTo(ServerPlayer player) {
        sendTo(player, ShopPersonalHidden.get(player));
    }

    static void sendTo(ServerPlayer player, Set<String> stableIds) {
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShopPersonalHiddenSyncPacket(stableIds));
    }

    public static void handle(ShopPersonalHiddenSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(packet.stableIds));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(Set<String> stableIds) {
        com.dishanhai.gt_shanhai.client.shop.ClientShopPersonalHidden.apply(stableIds);
    }
}
