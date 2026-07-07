package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.client.FtbqSubmitterSelectionClient;
import com.dishanhai.gt_shanhai.common.machine.misc.FtbqAeSubmitterMachine;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class FtbqSubmitterListResponsePacket {
    private static final int MAX_ENTRIES = 256;

    private final long taskId;
    private final List<FtbqAeSubmitterMachine.SubmitterEntry> entries;

    public FtbqSubmitterListResponsePacket(long taskId, List<FtbqAeSubmitterMachine.SubmitterEntry> entries) {
        this.taskId = taskId;
        this.entries = entries;
    }

    public FtbqSubmitterListResponsePacket(FriendlyByteBuf buf) {
        this.taskId = buf.readLong();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("Invalid submitter list size: " + size);
        }
        this.entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String pos = buf.readUtf(128);
            int queueSize = buf.readVarInt();
            boolean online = buf.readBoolean();
            this.entries.add(new FtbqAeSubmitterMachine.SubmitterEntry(id, pos, queueSize, online));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(taskId);
        int size = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            FtbqAeSubmitterMachine.SubmitterEntry entry = entries.get(i);
            buf.writeUUID(entry.id());
            buf.writeUtf(entry.pos(), 128);
            buf.writeVarInt(entry.queueSize());
            buf.writeBoolean(entry.online());
        }
    }

    public static void handle(FtbqSubmitterListResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(FtbqSubmitterListResponsePacket packet) {
        FtbqSubmitterSelectionClient.open(packet.taskId, packet.entries);
    }
}
