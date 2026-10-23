package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorSession;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorSessions;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorActionChunks;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** 山海生成器動作；每次開啟都以獨立 token 隔離晚到封包。 */
public record PatternGeneratorActionPacket(UUID sessionId, int action, byte[] payload,
                                          UUID transferId, int index, int count, int totalBytes, int requestId) {
    public static final int MAX_PAYLOAD = PatternGeneratorActionChunks.MAX_PAYLOAD;
    public static final int MAX_TOTAL_BYTES = PatternGeneratorActionChunks.MAX_TOTAL_BYTES;
    public PatternGeneratorActionPacket(UUID sessionId, int action, byte[] payload) {
        this(sessionId, action, payload, PatternGeneratorActionChunks.SINGLE_TRANSFER, 0, 1, payload.length);
    }
    public PatternGeneratorActionPacket(UUID sessionId, int action, byte[] payload,
                                        UUID transferId, int index, int count, int totalBytes) {
        this(sessionId, action, payload, transferId, index, count, totalBytes, 0);
    }
    public PatternGeneratorActionPacket {
        if (requestId < 0) throw new IllegalArgumentException("Invalid pattern generator request id");
        PatternGeneratorActionChunks.validate(action, transferId, index, count, totalBytes, payload.length);
        payload = payload.clone();
    }
    @Override public byte[] payload() { return payload.clone(); }
    public PatternGeneratorActionPacket(FriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readVarInt(), buffer.readByteArray(MAX_PAYLOAD),
                buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId); buffer.writeVarInt(action); buffer.writeByteArray(payload);
        buffer.writeUUID(transferId); buffer.writeVarInt(index); buffer.writeVarInt(count); buffer.writeVarInt(totalBytes);
        buffer.writeVarInt(requestId);
    }
    public static List<PatternGeneratorActionPacket> split(UUID sessionId, int action, byte[] payload) {
        return split(sessionId, action, payload, 0);
    }
    public static List<PatternGeneratorActionPacket> split(UUID sessionId, int action, byte[] payload, int requestId) {
        if (requestId < 0) throw new IllegalArgumentException("Invalid pattern generator request id");
        if (payload.length <= MAX_PAYLOAD)
            return List.of(new PatternGeneratorActionPacket(sessionId, action, payload,
                    PatternGeneratorActionChunks.SINGLE_TRANSFER, 0, 1, payload.length, requestId));
        if ((action != 114 && action != 115) || payload.length > MAX_TOTAL_BYTES)
            throw new IllegalArgumentException("Pattern generator action cannot be chunked");
        UUID transferId;
        do { transferId = UUID.randomUUID(); } while (transferId.equals(PatternGeneratorActionChunks.SINGLE_TRANSFER));
        int count = (payload.length + MAX_PAYLOAD - 1) / MAX_PAYLOAD;
        List<PatternGeneratorActionPacket> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * MAX_PAYLOAD;
            byte[] part = Arrays.copyOfRange(payload, start, Math.min(payload.length, start + MAX_PAYLOAD));
            chunks.add(new PatternGeneratorActionPacket(sessionId, action, part, transferId, index, count, payload.length, requestId));
        }
        return List.copyOf(chunks);
    }
    public static void handle(PatternGeneratorActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) context.enqueueWork(() -> {
            var sender = context.getSender();
            if (sender == null) return;
            PatternGeneratorSession session = PatternGeneratorSessions.get(sender);
            if (session == null || !session.sessionId().equals(packet.sessionId()) || !session.stillHeld()) return;
            FriendlyByteBuf buffer = null;
            try {
                byte[] payload = session.assembleAction(packet.action(), packet.transferId(), packet.index(),
                        packet.count(), packet.totalBytes(), packet.payload(), packet.requestId());
                if (payload == null) return;
                buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
                session.acknowledgeRequest(packet.requestId());
                session.handle(packet.action(), buffer);
            }
            catch (RuntimeException invalid) {
                GTDishanhaiMod.LOGGER.debug("Rejected pattern generator action {}", packet.action(), invalid);
            } finally { if (buffer != null) buffer.release(); }
        });
        context.setPacketHandled(true);
    }
}

