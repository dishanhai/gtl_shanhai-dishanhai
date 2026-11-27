package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterInstallation;
import com.dishanhai.gt_shanhai.client.renderer.preview.StarLifterBlockModels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public record StarLifterSyncPacket(ResourceLocation dimension, List<StarLifterInstallation> installations) {
    public StarLifterSyncPacket(FriendlyByteBuf buffer) {
        this(buffer.readResourceLocation(), buffer.readList(buf -> new StarLifterInstallation(buf.readUtf(64),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readFloat(), buf.readFloat(), buf.readLong())));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(dimension);
        buffer.writeCollection(installations, (buf, installation) -> {
            buf.writeUtf(installation.name(), 64);
            Vec3 center = installation.center();
            buf.writeDouble(center.x);
            buf.writeDouble(center.y);
            buf.writeDouble(center.z);
            buf.writeFloat(installation.radius());
            buf.writeFloat(installation.heading());
            buf.writeLong(installation.startTick());
        });
    }

    public static void handle(StarLifterSyncPacket packet, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        ctx.enqueueWork(() -> StarLifterBlockModels.acceptSnapshot(packet.dimension(), packet.installations()));
        ctx.setPacketHandled(true);
    }
}
