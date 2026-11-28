package com.dishanhai.gt_shanhai.common.machine.starlifter;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterTerminalSync.Command.*;
import static org.junit.jupiter.api.Assertions.*;

class StarLifterTerminalFeedbackTest {
    @Test
    void onlyMatchingServerAcknowledgementsMakeAResultSound() {
        var sync = new StarLifterTerminalSync(null);
        var messages = new ArrayList<String>();
        sync.onFeedback(response -> messages.add(response.message()));
        assertFalse(sync.submit(WORK, true));

        var state = new CompoundTag();
        state.put("status", new CompoundTag());
        var input = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sync.readInitialData(input.writeNbt(state));
        } finally {
            input.release();
        }

        assertTrue(sync.submit(WORK, true));
        assertFalse(sync.submit(WORK, false));
        int work = sync.response(WORK).request();
        acknowledge(sync, WORK, work - 1, "action.rejected");
        assertTrue(messages.isEmpty());
        acknowledge(sync, WORK, work, "action.applied");
        acknowledge(sync, WORK, work, "action.applied");
        assertTrue(sync.response(WORK).successful());

        assertTrue(sync.submit(REACTIVATE, true));
        int refill = sync.response(REACTIVATE).request();
        acknowledge(sync, REACTIVATE, refill, "capture.changed");
        assertFalse(sync.response(REACTIVATE).successful());
        assertEquals(List.of("action.applied", "capture.changed"), messages);
    }

    private static void acknowledge(StarLifterTerminalSync sync, StarLifterTerminalSync.Command command,
                                    int request, String message) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            b.writeEnum(command);
            b.writeVarInt(request);
            b.writeUtf(message);
            sync.readUpdateInfo(1, b);
        } finally {
            b.release();
        }
    }
}
