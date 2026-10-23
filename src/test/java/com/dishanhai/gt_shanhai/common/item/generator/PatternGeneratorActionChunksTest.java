package com.dishanhai.gt_shanhai.common.item.generator;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 山海生成器分片重組不依賴 Minecraft，可在獨立 JVM 驗證。 */
class PatternGeneratorActionChunksTest {
    private static final int CHUNK = PatternGeneratorActionChunks.MAX_PAYLOAD;
    @Test void chunksLeaveRoomForTheForgeEnvelope() {
        assertTrue(PatternGeneratorActionChunks.MAX_PAYLOAD <= 32767 - 64);
    }

    @Test void mismatchedAcknowledgementRequestClearsTheTransfer() {
        var chunks = new PatternGeneratorActionChunks();
        UUID transfer = UUID.randomUUID();
        assertNull(chunks.accept(114, transfer, 0, 2, CHUNK + 1, new byte[CHUNK], 0, 7));
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(114, transfer, 1, 2, CHUNK + 1, new byte[1], 1, 8));
        assertFalse(chunks.pending());
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(119, PatternGeneratorActionChunks.SINGLE_TRANSFER,
                        0, 1, 0, new byte[0], 2, -1));
    }
    @Test void assemblesOnlyAfterLastChunkAndCopiesBytes() {
        var chunks = new PatternGeneratorActionChunks();
        UUID transfer = UUID.randomUUID();
        byte[] first = new byte[CHUNK]; Arrays.fill(first, (byte) 7);
        assertNull(chunks.accept(114, transfer, 0, 2, CHUNK + 3, first, 1));
        first[0] = 9;
        byte[] complete = chunks.accept(114, transfer, 1, 2, CHUNK + 3, new byte[]{1, 2, 3}, 2);
        assertEquals(CHUNK + 3, complete.length);
        assertEquals(7, complete[0]);
        assertEquals(3, complete[CHUNK + 2]);
        assertFalse(chunks.pending());
    }

    @Test void rejectsOutOfOrderDuplicatesAndInconsistentActionThenClears() {
        var chunks = new PatternGeneratorActionChunks();
        UUID transfer = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(114, transfer, 1, 2, CHUNK + 1, new byte[1], 0));
        assertNull(chunks.accept(114, transfer, 0, 2, CHUNK + 1, new byte[CHUNK], 1));
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(114, transfer, 0, 2, CHUNK + 1, new byte[CHUNK], 2));
        assertFalse(chunks.pending());
        assertNull(chunks.accept(114, transfer, 0, 2, CHUNK + 1, new byte[CHUNK], 3));
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(115, transfer, 1, 2, CHUNK + 1, new byte[1], 4));
        assertFalse(chunks.pending());
    }

    @Test void newTransferReplacesOldAndTimeoutClearsBeforeNextChunk() {
        var chunks = new PatternGeneratorActionChunks();
        UUID old = UUID.randomUUID(), next = UUID.randomUUID();
        chunks.accept(114, old, 0, 2, CHUNK + 1, new byte[CHUNK], 0);
        chunks.accept(115, next, 0, 2, CHUNK + 1, new byte[CHUNK], 1);
        assertNotNull(chunks.accept(115, next, 1, 2, CHUNK + 1, new byte[1], 2));
        chunks.accept(114, old, 0, 2, CHUNK + 1, new byte[CHUNK], 3);
        chunks.expire(202); assertTrue(chunks.pending());
        chunks.expire(203); assertFalse(chunks.pending());
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(114, old, 1, 2, CHUNK + 1, new byte[1], 204));
    }

    @Test void exactBoundariesAndOnlyPresetActionsMayChunk() {
        var chunks = new PatternGeneratorActionChunks();
        UUID transfer = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(113, transfer, 0, 2, CHUNK + 1, new byte[CHUNK], 0));
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(114, transfer, 0, 3, CHUNK + 1, new byte[CHUNK], 0));
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(114, transfer, 0, 2, CHUNK + 1, new byte[CHUNK - 1], 0));
        assertThrows(IllegalArgumentException.class,
                () -> chunks.accept(114, transfer, 0, 65, 2097153, new byte[CHUNK], 0));
        chunks.accept(114, transfer, 0, 2, CHUNK + 1, new byte[CHUNK], 0);
        assertArrayEquals(new byte[]{4}, chunks.accept(119, PatternGeneratorActionChunks.SINGLE_TRANSFER,
                0, 1, 1, new byte[]{4}, 1));
        assertFalse(chunks.pending());
        assertThrows(IllegalArgumentException.class,
                () -> PatternGeneratorActionChunks.validate(114, transfer, 0, 1, 1, 1));
    }
}

