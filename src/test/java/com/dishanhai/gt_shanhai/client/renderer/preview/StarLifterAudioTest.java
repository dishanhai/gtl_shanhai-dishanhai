package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterVisualState;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterVisualState.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

class StarLifterAudioTest {
    @Test
    void playsOneCuePerOperatingTransition() {
        assertEquals("star_lifter.ignition", cue(IDLE, RUNNING));
        assertEquals("star_lifter.ignition", cue(FAULT, HIGH_LOAD));
        assertEquals("star_lifter.shutdown", cue(HIGH_LOAD, IDLE));
        assertEquals("star_lifter.fault", cue(RUNNING, FAULT));
        assertEquals("star_lifter.fault", cue(IDLE, FAULT));
        assertNull(cue(RUNNING, HIGH_LOAD));
        assertNull(cue(HIGH_LOAD, RUNNING));
        assertNull(cue(FAULT, FAULT));
        assertNull(cue(FAULT, IDLE));
    }

    @Test
    void aDepletedStarStillHasAnAmbientBedAndGrowsLouderWithReserve() {
        float depleted = StarLifterAudio.stellarVolume(0);
        float half = StarLifterAudio.stellarVolume(.5f);
        float full = StarLifterAudio.stellarVolume(1);
        assertTrue(depleted > 0 && depleted < half && half < full);
        assertTrue(full < .5f);
    }

    private static String cue(StarLifterVisualState.Mode before, StarLifterVisualState.Mode after) {
        var sound = StarLifterAudio.transition(new StarLifterVisualState(before, 5, false),
                new StarLifterVisualState(after, 10, true));
        return sound == null ? null : sound.getLocation().getPath();
    }
}
