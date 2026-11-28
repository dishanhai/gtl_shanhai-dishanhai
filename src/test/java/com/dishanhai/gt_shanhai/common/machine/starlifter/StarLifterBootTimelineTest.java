package com.dishanhai.gt_shanhai.common.machine.starlifter;

import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterBootTimeline.*;
import static org.junit.jupiter.api.Assertions.*;

class StarLifterBootTimelineTest {
    @Test
    void physicalViewportAndLogReachEachRingOnTheSameSchedule() {
        for (int ring = 0; ring < StarLifterGeometry.RINGS.size(); ring++) {
            assertEquals(0, ringSweep(ringStart(ring), ring));
            assertEquals(.5f, ringSweep((ringStart(ring) + ringEnd(ring)) * .5f, ring));
            assertEquals(1, ringSweep(ringEnd(ring), ring));
            assertEquals(0, ringMaterial(IMAGING - 1, ring));
            assertEquals(1, ringMaterial(HANDOFF, ring));
            assertEquals(Stage.ORBIT, stage(ringEnd(ring)));
        }
        assertEquals(0, starLight(STELLAR));
        assertEquals(1, starLight(RESONANCE));
        assertEquals(Stage.READY, stage(cueMs(DURATION - 1)));
    }

    @Test
    void completeBootStretchesTheLogAndPreviewTogether() {
        assertEquals(5600, DURATION);
        assertEquals(1200, playbackMs(ORBIT));
        assertEquals(ORBIT, cueMs(1200));
        assertEquals(ORBIT, cueTime(1200f / DURATION), .01f);
        assertEquals(Stage.READY, stage(cueMs(playbackMs(HANDOFF))));
        assertEquals(cueMs(DURATION), cueTime(1));
    }

    @Test
    void openingTheTerminalDoesNotImplyAWorkingMachineOrAvailableNetwork() {
        var waiting = new MachineState(false, false, false, false, false, false, false, "");
        assertEquals(Result.PENDING, waiting.structure());
        assertEquals(Result.PENDING, waiting.resonance());
        assertEquals("waiting", waiting.online());

        var unformed = new MachineState(true, false, false, true, false, false, false, "offline");
        assertEquals(Result.FAILED, unformed.structure());
        assertEquals(Result.SKIPPED, unformed.recipe());
        assertEquals(Result.OFFLINE, unformed.resonance());
        assertEquals("incomplete", unformed.online());

        var deferred = new MachineState(true, false, true, true, false, false, false, "unloaded");
        assertEquals(Result.DEFERRED, deferred.structure());
        assertEquals(Result.DEFERRED, deferred.resonance());
        assertEquals("structure_wait", deferred.online());

        var disabled = new MachineState(true, true, false, false, false, false, false, "unbound");
        assertEquals(Result.OK, disabled.structure());
        assertEquals(Result.SKIPPED, disabled.recipe());
        assertEquals(Result.UNBOUND, disabled.resonance());
        assertEquals("disabled", disabled.online());

        var idle = new MachineState(true, true, false, true, false, false, false, "online");
        assertEquals(Result.STANDBY, idle.recipe());
        assertEquals(Result.READY, idle.resonance());
        assertEquals("awaiting", idle.online());

        var active = new MachineState(true, true, false, true, true, false, false, "working");
        assertEquals(Result.ACTIVE, active.recipe());
        assertEquals(Result.READY, active.resonance());
        assertEquals("active", active.online());

        var halted = new MachineState(true, true, false, true, false, false, true, "unavailable");
        assertEquals(Result.WARNING, halted.recipe());
        assertEquals(Result.OFFLINE, halted.resonance());
        assertEquals("suspended", halted.online());
    }

    @Test
    void typedTextUsesExtendedGraphemeBoundaries() {
        assertArrayEquals(new int[]{0, 1, 2, 4, 9, 10}, StarLifterBootLog.graphemeEnds("恒星e\u0301👩‍🔬_"));
        assertEquals(0, StarLifterBootLog.visibleGraphemes(24, 30));
        assertEquals(1, StarLifterBootLog.visibleGraphemes(25, 30));
        assertEquals(12, StarLifterBootLog.visibleGraphemes(300, 30));
        assertEquals(30, StarLifterBootLog.visibleGraphemes(1000, 30));
    }
}
