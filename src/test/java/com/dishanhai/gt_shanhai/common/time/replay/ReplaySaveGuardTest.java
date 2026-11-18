// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplaySaveGuardTest {
    @AfterEach void reset() { ReplaySaveGuard.reset(); }
    @Test void blocksOnlyAfterFailureAndResetsForNextSession() {
        ReplaySaveGuard.reset();
        assertFalse(ReplaySaveGuard.block());
        ReplaySaveGuard.fail();
        assertTrue(ReplaySaveGuard.block());
        ReplaySaveGuard.reset();
        assertFalse(ReplaySaveGuard.block());
    }
}
