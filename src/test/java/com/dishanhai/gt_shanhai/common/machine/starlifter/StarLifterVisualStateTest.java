package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.network.StarLifterSyncPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterVisualState.Mode.*;
import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterVisualState.FULL_RESERVE;
import static org.junit.jupiter.api.Assertions.*;

class StarLifterVisualStateTest {
    @Test
    void startsOnlyWhenWorkBeginsAndKeepsTheIgnitionClockWhenLoadOrLinkChanges() {
        var startup = StarLifterVisualState.IDLE.update(RUNNING, 200, false, FULL_RESERVE);
        assertEquals(0f, startup.ignition(199.5));
        assertEquals(.5f, startup.ignition(232));
        assertEquals(1f, startup.ignition(300));
        var high = startup.update(HIGH_LOAD, 226, true, FULL_RESERVE);
        assertEquals(200, high.since());
        assertTrue(high.resonance());
        assertSame(high, high.update(HIGH_LOAD, 230, true, FULL_RESERVE));
        var fault = high.update(FAULT, 240, true, FULL_RESERVE);
        assertEquals(0f, fault.ignition(250));
        assertEquals(255, fault.update(RUNNING, 255, false, FULL_RESERVE).since());
        assertEquals(0f, high.update(IDLE, 240, false, FULL_RESERVE).ignition(250));
    }

    @Test
    void reserveChangesAndReactivationMorphFromTheCurrentlyVisibleSize() {
        assertEquals(FULL_RESERVE / 2, StarLifterVisualState.reserveLevel(72_000, 144_000));
        var depleted = StarLifterVisualState.IDLE.update(FAULT, 80, false, 0);
        assertEquals(1f, depleted.reserveFraction(79.5));
        assertEquals(.5f, depleted.reserveFraction(95));
        assertEquals(0f, depleted.reserveFraction(110));
        assertEquals(.80f, StarLifterGeometry.photosphereScale(depleted.reserveFraction(110)));
        var restored = depleted.update(IDLE, 95, true, FULL_RESERVE);
        assertEquals(depleted.reserveFraction(95), restored.reserveFraction(95));
        assertEquals(.75f, restored.reserveFraction(110));
        assertEquals(1f, StarLifterGeometry.photosphereScale(restored.reserveFraction(125)));
        assertEquals(restored.reserveSince(), restored.update(IDLE, 98, false, FULL_RESERVE).reserveSince());
    }

    @Test
    void installationSnapshotCarriesVisualStateWhileOldSavesStillLoad() {
        var installation = new StarLifterInstallation("controller:42", new Vec3(20.5, 140.5, -30.5), 68, 90, 100);
        installation.setVisual(new StarLifterVisualState(HIGH_LOAD, 137, true, 320, .5f, 196));
        var packet = new StarLifterSyncPacket(new ResourceLocation("minecraft", "overworld"), List.of(installation));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            var decoded = new StarLifterSyncPacket(buffer).installations().getFirst();
            assertEquals(installation.visual(), decoded.visual());
            assertEquals(installation.startTick(), decoded.startTick());
            assertEquals(installation.center(), decoded.center());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
        var legacy = StarLifterInstallation.load(installation.save());
        assertEquals(StarLifterVisualState.IDLE, legacy.visual());
        assertEquals(100L, legacy.startTick());
    }
}
