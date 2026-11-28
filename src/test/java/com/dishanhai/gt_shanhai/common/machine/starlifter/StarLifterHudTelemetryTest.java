package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.Snapshot;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.State;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StarLifterHudTelemetryTest {
    @Test
    void wirePreservesAbsoluteValuesAndRecipeContext() {
        var active = new Snapshot(new BlockPos(121, -32, 3017), 987,
                State.HIGH_LOAD, false, true, 0, 7_200_000, Long.MAX_VALUE,
                Long.MAX_VALUE, 2_147_483_648L, 0, 380, 9180, 503_316_480L,
                4, 120, 13, new ResourceLocation("gt_shanhai", "stellar_deconstruction/stellar_harvest"),
                null, null);
        assertEquals(active, roundTrip(active));
        assertTrue(active.formed());
        assertTrue(active.running());
    }

    @Test
    void emptyAndExpiredMeasurementsStayDifferentFromRealZero() {
        var offline = new Snapshot(BlockPos.ZERO, 1, State.OFFLINE, false, false,
                0, 1000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
        var expired = StarLifterHudTelemetry.unavailable(State.EXPIRED);
        assertEquals(offline, roundTrip(offline));
        assertTrue(roundTrip(offline).formed());
        assertEquals(0, roundTrip(offline).reserve());
        assertEquals(expired, roundTrip(expired));
        assertFalse(roundTrip(expired).formed());
        assertEquals(-1, roundTrip(expired).reserve());
    }

    @Test
    void failureKeepsIdentityAndDiagnosticButClearsLiveMetrics() {
        var failed = new Snapshot(new BlockPos(-9, 90, -42), 2,
                State.UNFORMED, false, false, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new ResourceLocation("gt_shanhai", "previous"), new BlockPos(1, 2, 3),
                Component.translatable("gt_shanhai.star_lifter.jade.invalid_at"));
        var received = roundTrip(failed);
        assertEquals(failed.controller(), received.controller());
        assertEquals(failed.incarnation(), received.incarnation());
        assertEquals(failed.invalidAt(), received.invalidAt());
        assertEquals(failed.reason(), received.reason());
        assertNull(received.recipe());
    }

    private static Snapshot roundTrip(Snapshot snapshot) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            snapshot.write(buffer);
            assertTrue(buffer.readableBytes() < 256);
            var decoded = Snapshot.read(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
