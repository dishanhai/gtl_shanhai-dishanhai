package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.client.gui.starlifter.StarLifterHudCache;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTarget;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.WeakHashMap;

/** Jade 11 requests data only for block entities. The optional channel also serves passive structure blocks. */
public final class StarLifterHudNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("gt_shanhai", "stellar_hud"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));
    private static final WeakHashMap<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();

    private StarLifterHudNetwork() {}

    public static void init() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write).decoder(Request::read)
                .consumerMainThread((request, context) -> observe(context.get().getSender(), request)).add();
        CHANNEL.messageBuilder(Reply.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Reply::write).decoder(Reply::read)
                .consumerMainThread((reply, context) -> StarLifterHudCache.accept(reply)).add();
    }

    public static boolean supported(Connection connection) {
        return CHANNEL.isRemotePresent(connection);
    }

    public static void request(int sequence, ResourceLocation dimension, BlockPos pos) {
        CHANNEL.sendToServer(new Request(sequence, dimension, pos));
    }

    private static void observe(ServerPlayer player, Request request) {
        if (player == null) return;
        var level = player.serverLevel();
        if (!level.dimension().location().equals(request.dimension)) return;
        long tick = level.getServer().getTickCount();
        Long previous = LAST_REQUEST.get(player);
        if (previous != null && tick - previous < 2) return;
        LAST_REQUEST.put(player, tick);

        var pos = request.pos;
        var eye = player.getEyePosition();
        var bound = new AABB(pos);
        double reach = player.getBlockReach() + 0.5;
        if (bound.distanceToSqr(eye) > reach * reach) return;
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return;
        var observation = StarLifterHudTelemetry.observe(level, chunk, pos);
        if (observation == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Reply(request.sequence,
                request.dimension, pos, observation.role(), observation.snapshot()));
    }

    private record Request(int sequence, ResourceLocation dimension, BlockPos pos) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(sequence);
            buf.writeResourceLocation(dimension);
            buf.writeBlockPos(pos);
        }

        static Request read(FriendlyByteBuf buf) {
            return new Request(buf.readVarInt(), buf.readResourceLocation(), buf.readBlockPos());
        }
    }

    public record Reply(int sequence, ResourceLocation dimension, BlockPos pos, StarLifterHudTarget role,
                        StarLifterHudTelemetry.Snapshot snapshot) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(sequence);
            buf.writeResourceLocation(dimension);
            buf.writeBlockPos(pos);
            buf.writeEnum(role);
            snapshot.write(buf);
        }

        static Reply read(FriendlyByteBuf buf) {
            return new Reply(buf.readVarInt(), buf.readResourceLocation(), buf.readBlockPos(),
                    buf.readEnum(StarLifterHudTarget.class), StarLifterHudTelemetry.Snapshot.read(buf));
        }
    }
}
