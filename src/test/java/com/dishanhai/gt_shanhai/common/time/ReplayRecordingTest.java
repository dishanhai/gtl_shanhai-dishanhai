// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayRecordingTest {
    @AfterEach void reset() { ReplayRecording.reset(); }
    @Test void startsOnlyWhenExplicitlyEnabledAndStops() {
        ReplayRecording.reset();
        assertFalse(ReplayRecording.isEnabled());
        ReplayRecording.start();
        assertTrue(ReplayRecording.isEnabled());
        ReplayRecording.stop();
        assertFalse(ReplayRecording.isEnabled());
    }
}
