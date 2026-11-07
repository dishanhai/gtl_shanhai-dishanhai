package com.dishanhai.gt_shanhai.network;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShanhaiPatternTerminalUpdateChunkPacketTest {

    @Test
    void reassemblesTheReportedOversizePayloadWithoutChangingItsBytes() {
        byte[] original = new byte[2_277_607];
        new Random(37).nextBytes(original);
        var chunks = chunks(original);
        var assembler = new ShanhaiPatternTerminalUpdateChunkPacket.Assembler();

        assertEquals(3, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var wire = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ShanhaiPatternTerminalUpdateChunkPacket.encode(chunks.get(i), wire);
                assertTrue(wire.readableBytes() < 1024 * 1024);
                var decoded = ShanhaiPatternTerminalUpdateChunkPacket.decode(wire);
                assertEquals(8, decoded.menuId());
                byte[] reassembled = assembler.accept(decoded);
                if (i == chunks.size() - 1) {
                    assertArrayEquals(original, reassembled);
                } else {
                    assertNull(reassembled);
                }
            } finally {
                wire.release();
            }
        }
    }

    @Test
    void discardsOutOfOrderFragmentsAndAcceptsTheNextCompleteUpdate() {
        byte[] original = new byte[2_277_607];
        var chunks = chunks(original);
        var assembler = new ShanhaiPatternTerminalUpdateChunkPacket.Assembler();

        assertNull(assembler.accept(chunks.get(0)));
        assertNull(assembler.accept(chunks.get(2)));
        assertNull(assembler.accept(chunks.get(1)));
        assertNull(assembler.accept(chunks.get(2)));
        assertNull(assembler.accept(chunks.get(0)));
        assertNull(assembler.accept(chunks.get(1)));
        assertArrayEquals(original, assembler.accept(chunks.get(2)));
    }

    @Test
    void rejectsAnOversizeTotalBeforeAssembly() {
        assertThrows(IllegalArgumentException.class, () -> new ShanhaiPatternTerminalUpdateChunkPacket(
                8, 0, Integer.MAX_VALUE, new byte[0]));
    }

    private static List<ShanhaiPatternTerminalUpdateChunkPacket> chunks(byte[] bytes) {
        var chunks = new ArrayList<ShanhaiPatternTerminalUpdateChunkPacket>();
        var source = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            ShanhaiPatternTerminalUpdateChunkPacket.split(8, source, chunks::add);
            assertEquals(0, source.readerIndex());
        } finally {
            source.release();
        }
        return chunks;
    }
}
