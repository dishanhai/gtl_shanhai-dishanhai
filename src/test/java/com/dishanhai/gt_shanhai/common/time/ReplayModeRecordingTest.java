// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time;

import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayModeCompose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayModeRecordingTest {
    @AfterEach void reset() { ReplayRecording.reset(); }
    @Test void modeAndExplicitOptInBothControlRecording() {
        ReplayRecording.reset();
        assertFalse(ShanhaiReplayModeCompose.recordsUnder(ShanhaiClockMode.LIVE));
        assertFalse(ShanhaiReplayModeCompose.recordsUnder(ShanhaiClockMode.PAUSED));
        ReplayRecording.start();
        assertTrue(ShanhaiReplayModeCompose.recordsUnder(ShanhaiClockMode.LIVE));
        assertTrue(ShanhaiReplayModeCompose.recordsUnder(ShanhaiClockMode.PAUSED));
        assertFalse(ShanhaiReplayModeCompose.recordsUnder(ShanhaiClockMode.REPLAY));
        ReplayRecording.stop();
        assertFalse(ShanhaiReplayModeCompose.recordsUnder(ShanhaiClockMode.LIVE));
    }
}
