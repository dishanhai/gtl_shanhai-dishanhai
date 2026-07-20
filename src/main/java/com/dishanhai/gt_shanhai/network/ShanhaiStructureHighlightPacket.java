package com.dishanhai.gt_shanhai.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import com.dishanhai.gt_shanhai.client.ShanhaiStructureHighlightClient;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

public final class ShanhaiStructureHighlightPacket {

    private static final int MAX_MARKERS = 256;

    public record Marker(BlockPos pos, int color) {

        public Marker {
            pos = pos.immutable();
            color &= 0xFFFFFF;
        }
    }

    private final ResourceKey<Level> dimension;
    private final long expiresAt;
    private final List<Marker> markers;

    public ShanhaiStructureHighlightPacket(ResourceKey<Level> dimension, long expiresAt,
                                            List<Marker> markers) {
        this.dimension = dimension;
        this.expiresAt = expiresAt;
        this.markers = Collections.unmodifiableList(new ArrayList<>(
                markers.subList(0, Math.min(markers.size(), MAX_MARKERS))));
    }

    public ShanhaiStructureHighlightPacket(FriendlyByteBuf buf) {
        this.dimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
        this.expiresAt = buf.readLong();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_MARKERS) {
            throw new IllegalArgumentException("山海结构高亮数量越界: " + count);
        }
        List<Marker> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(new Marker(buf.readBlockPos(), buf.readInt()));
        }
        this.markers = Collections.unmodifiableList(decoded);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension.location());
        buf.writeLong(expiresAt);
        buf.writeVarInt(markers.size());
        for (Marker marker : markers) {
            buf.writeBlockPos(marker.pos());
            buf.writeInt(marker.color());
        }
    }

    public static void handle(ShanhaiStructureHighlightPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShanhaiStructureHighlightPacket packet) {
        ShanhaiStructureHighlightClient.highlight(packet);
    }

    public static void sendTo(net.minecraft.server.level.ServerPlayer player,
                              ResourceKey<Level> dimension, long expiresAt, List<Marker> markers) {
        if (markers.isEmpty()) return;
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ShanhaiStructureHighlightPacket(dimension, expiresAt, markers));
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public long expiresAt() {
        return expiresAt;
    }

    public List<Marker> markers() {
        return markers;
    }
}
