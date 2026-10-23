package com.dishanhai.gt_shanhai.common.item.generator;

import com.dishanhai.gt_shanhai.network.PatternGeneratorActionPacket;
import com.dishanhai.gt_shanhai.network.PatternGeneratorPresetPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海生成器動作封包的邊界與 token 往返。 */
class PatternGeneratorPacketTest {
    private static final int CHUNK = PatternGeneratorActionPacket.MAX_PAYLOAD;
    @Test void actionRoundTripAndPayloadIsCopied() {
        UUID token = UUID.randomUUID();
        byte[] bytes = {4, 5};
        var packet = new PatternGeneratorActionPacket(token, 118, bytes);
        bytes[0] = 9;
        packet.payload()[1] = 9;
        assertArrayEquals(new byte[]{4, 5}, packet.payload());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            var decoded = new PatternGeneratorActionPacket(buffer);
            assertEquals(token, decoded.sessionId());
            assertEquals(118, decoded.action());
            assertEquals(0, decoded.requestId());
            assertArrayEquals(new byte[]{4, 5}, decoded.payload());
        } finally { buffer.release(); }
    }

    @Test void actionRejectsUnknownAndOversizeBeforeUse() {
        UUID token = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(token, 100, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(token, 120, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(
                token, 101, new byte[PatternGeneratorActionPacket.MAX_PAYLOAD + 1]));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(token); buffer.writeVarInt(101);
            buffer.writeVarInt(PatternGeneratorActionPacket.MAX_PAYLOAD + 1);
            assertThrows(RuntimeException.class, () -> new PatternGeneratorActionPacket(buffer));
        } finally { buffer.release(); }
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorPresetPacket(token, 119, new byte[0]));
    }

    @Test void presetSplitsRoundTripAndReassembleAtOriginalCapacity() {
        UUID token = UUID.randomUUID();
        byte[] original = new byte[524288];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (i % 127);
        var packets = PatternGeneratorActionPacket.split(token, 114, original, Integer.MAX_VALUE);
        assertEquals((original.length + CHUNK - 1) / CHUNK, packets.size());
        var chunks = new PatternGeneratorActionChunks();
        byte[] complete = null;
        for (int i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            assertEquals(i, packet.index());
            assertEquals(Integer.MAX_VALUE, packet.requestId());
            assertEquals(original.length, packet.totalBytes());
            assertEquals(Math.min(CHUNK, original.length - i * CHUNK), packet.payload().length);
            assertEquals(packets.get(0).transferId(), packet.transferId());
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                packet.encode(buffer);
                // SimpleChannel 的整數 discriminator 最多 5 bytes，也計入原版32767上限。
                assertTrue(buffer.readableBytes() + 5 <= 32767);
                var decoded = new PatternGeneratorActionPacket(buffer);
                assertEquals(token, decoded.sessionId());
                assertEquals(packet.transferId(), decoded.transferId());
                assertEquals(Integer.MAX_VALUE, decoded.requestId());
                complete = chunks.accept(decoded.action(), decoded.transferId(), decoded.index(),
                        decoded.count(), decoded.totalBytes(), decoded.payload(), i);
            } finally { buffer.release(); }
            if (i < packets.size() - 1) assertNull(complete);
        }
        assertArrayEquals(original, complete);
        original[0] = 100;
        assertEquals(0, packets.get(0).payload()[0]);
        assertEquals(1, PatternGeneratorActionPacket.split(token, 119, new byte[0]).size());
        assertThrows(IllegalArgumentException.class,
                () -> PatternGeneratorActionPacket.split(token, 108, new byte[CHUNK + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> PatternGeneratorActionPacket.split(token, 115,
                        new byte[PatternGeneratorActionPacket.MAX_TOTAL_BYTES + 1]));
    }

    @Test void metadataBoundsAreCheckedOnWriteConstructionAndDecode() {
        UUID token = UUID.randomUUID(), transfer = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(
                token, 119, new byte[0], PatternGeneratorActionChunks.SINGLE_TRANSFER, 0, 1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(
                token, 114, new byte[CHUNK], transfer, 0, 3, CHUNK + 1));
        assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(
                token, 114, new byte[CHUNK - 1], transfer, 0, 2, CHUNK + 1));
        var packets = PatternGeneratorActionPacket.split(token, 115, new byte[CHUNK + 1]);
        assertEquals(2, packets.size());
        assertEquals(1, packets.get(1).payload().length);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(token); buffer.writeVarInt(114); buffer.writeByteArray(new byte[CHUNK]);
            buffer.writeUUID(transfer); buffer.writeVarInt(0); buffer.writeVarInt(3); buffer.writeVarInt(CHUNK + 1);
            buffer.writeVarInt(0);
            assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(buffer));
        } finally { buffer.release(); }
    }

    @Test void negativeRequestAcknowledgementIsRejectedOnDecode() {
        var packet = new PatternGeneratorActionPacket(UUID.randomUUID(), 119, new byte[0],
                PatternGeneratorActionChunks.SINGLE_TRANSFER, 0, 1, 0);
        assertEquals(0, packet.requestId());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            buffer.writerIndex(buffer.writerIndex() - 1);
            buffer.writeVarInt(-1);
            assertThrows(IllegalArgumentException.class, () -> new PatternGeneratorActionPacket(buffer));
        } finally { buffer.release(); }
    }
}

