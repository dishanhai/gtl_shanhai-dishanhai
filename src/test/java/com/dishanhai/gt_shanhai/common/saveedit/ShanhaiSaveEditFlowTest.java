package com.dishanhai.gt_shanhai.common.saveedit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShanhaiSaveEditFlowTest {
    @AfterEach
    void reset() {
        ShanhaiSaveEditFlow.resetGlobalForProbe();
    }

    @Test
    void diskWriteIsAllowedOnlyOnceAfterServerHasStopped() {
        ShanhaiSaveEditFlow flow = ShanhaiSaveEditFlow.begin("test");
        assertNotNull(flow);
        assertFalse(flow.tryApply("before-save").permitted());
        assertTrue(flow.markSaved());
        assertTrue(flow.markStopping());
        assertFalse(flow.tryApply("before-stop").permitted());
        assertTrue(flow.markStopped());
        assertTrue(flow.tryApply("after-stop").permitted());
        assertFalse(flow.tryApply("second-write").permitted());
    }

    @Test
    void failureReleasesTheGlobalRunLock() {
        ShanhaiSaveEditFlow flow = ShanhaiSaveEditFlow.begin("first");
        assertNotNull(flow);
        assertNull(ShanhaiSaveEditFlow.begin("concurrent"));
        flow.fail("failure");
        assertNotNull(ShanhaiSaveEditFlow.begin("next"));
    }
}
