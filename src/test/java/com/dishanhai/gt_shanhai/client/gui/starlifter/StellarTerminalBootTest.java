package com.dishanhai.gt_shanhai.client.gui.starlifter;

import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.client.gui.starlifter.StellarTerminalBoot.Policy.*;
import static com.dishanhai.gt_shanhai.client.gui.starlifter.StellarTerminalBoot.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

class StellarTerminalBootTest {
    private final StellarTerminalBoot boot = new StellarTerminalBoot();
    private final StellarTerminalBoot.Device core = new StellarTerminalBoot.Device(1, "core", null);
    private final StellarTerminalBoot.Device chamber = new StellarTerminalBoot.Device(2, "chamber", null);
    private final StellarTerminalBoot.Device other = new StellarTerminalBoot.Device(3, "core", null);

    @Test
    void coldBootFinishesWithoutAReplyAndNeverClaimsSyncWithoutAFormedSample() {
        assertEquals(DORMANT, boot.stage(0, false));
        see(core, 0, 0, 100);
        assertEquals(COLD, boot.policy());
        assertEquals(39, boot.space(100));
        assertEquals(TARGET_ACQUISITION, boot.stage(100, false));
        assertEquals(BRAND_REVEAL, boot.stage(250, false));
        assertEquals(TERMINAL_INIT, boot.stage(410, false));
        assertEquals(DEVICE_IDENTIFICATION, boot.stage(530, false));
        assertEquals(STRUCTURE_SCAN, boot.stage(605, false));
        assertEquals(TELEMETRY_CONNECT, boot.stage(651, false));
        assertEquals(TELEMETRY_SYNC, boot.stage(651, true));
        assertEquals(INTERFACE_MORPH, boot.stage(780, false));
        assertEquals(ONLINE, boot.stage(820, false));
        assertEquals(0, boot.space(820));
        see(core, 1, 7, 1100);
        assertEquals(ONLINE, boot.stage(1100, true));
    }

    @Test
    void usesModuleOnlyForTheSameVerifiedControllerInstance() {
        see(core, 1, 7, 0);
        see(chamber, 1, 7, 900);
        assertEquals(MODULE, boot.policy());
        assertEquals(ONLINE, boot.stage(1040, true));
        see(core, 1, 8, 1050);
        assertEquals(FAST, boot.policy());
        see(other, 3, 9, 1400);
        assertEquals(FAST, boot.policy());
        assertEquals(ONLINE, boot.stage(1720, true));
    }

    @Test
    void delayedModuleConfirmationInterruptsFastBootWithoutResettingGeometry() {
        see(core, 1, 7, 0);
        see(chamber, 0, 0, 300);
        assertEquals(FAST, boot.policy());
        float space = boot.space(350);
        float layout = boot.layout(350);
        see(chamber, 1, 7, 350);
        assertEquals(MODULE, boot.policy());
        assertEquals(space, boot.space(350));
        assertEquals(layout, boot.layout(350));
        assertEquals(ONLINE, boot.stage(490, true));
    }

    @Test
    void closingAndReversingColdBootKeepsCurrentGeometryAndExpiresTheSingleSlot() {
        see(core, 1, 7, 0);
        boot.lose(210);
        assertEquals(SHUTDOWN, boot.stage(210, true));
        float space = boot.space(250);
        float layout = boot.layout(250);
        see(core, 0, 0, 250);
        assertEquals(WARM, boot.policy());
        assertEquals(RESUMING, boot.stage(250, false));
        assertEquals(space, boot.space(250));
        assertEquals(layout, boot.layout(250));
        assertEquals(ONLINE, boot.stage(350, true));
        boot.lose(410);
        assertEquals(SUSPENDED, boot.stage(545, false));
        see(core, 1, 7, 2210);
        assertEquals(COLD, boot.policy());
    }

    @Test
    void detailWakeRunsAlongsideBootAndResetStartsFresh() {
        see(core, 1, 7, 0);
        boot.details(true, 200);
        assertEquals(0, boot.detail(200));
        assertEquals(.5f, boot.detail(310), .001f);
        assertEquals(TERMINAL_INIT, boot.stage(310, true));
        boot.details(false, 320);
        boot.details(true, 340);
        assertEquals(0, boot.detail(340));
        boot.reset();
        assertEquals(DORMANT, boot.stage(1000, false));
        assertEquals(1, boot.detail(1000));
    }

    @Test
    void absoluteClockHandlesHighAndLowFrameRatesAndRapidRetargets() {
        see(core, 1, 7, 0);
        assertEquals(ONLINE, boot.stage(1000, false));
        for (int i = 1000; i < 11000; i++) {
            see((i & 1) == 0 ? chamber : other, (i & 1) == 0 ? 1 : 3, 7, i);
            assertTrue(boot.space(i) <= 39);
            assertTrue(boot.layout(i) >= 0 && boot.layout(i) <= 1);
        }
        assertEquals(ONLINE, boot.stage(11500, true));
    }

    @Test
    void reducedMotionUsesOneBriefResumeAndNoBootRows() {
        boot.observe(core, 1, 7, 100, 1800, 720, 320, 100, 0, 0, true);
        assertEquals(RESUMING, boot.stage(100, false));
        assertEquals(0, boot.space(100));
        assertEquals(ONLINE, boot.stage(190, false));
    }

    private void see(StellarTerminalBoot.Device device, long owner, long incarnation, long now) {
        boot.observe(device, owner, incarnation, now, 1800, 720, 320, 100, 39, 19, false);
    }
}
