package com.dishanhai.gt_shanhai.common.time.replay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class ReplayRegionSaveGuardTest {
    @AfterEach void reset() { ReplaySaveGuard.reset(); }

    @Test void conflictingWritePreventsSuccessfulReleaseAndKeepsProtection() {
        ReplaySaveGuard.reset();
        assertFalse(ReplaySaveGuard.rejectRegionWrite());
        ReplaySaveGuard.beginReplay();
        assertFalse(ReplaySaveGuard.hasSaveConflict());
        assertTrue(ReplaySaveGuard.rejectRegionWrite());
        assertTrue(ReplaySaveGuard.hasSaveConflict());
        assertFalse(ReplaySaveGuard.releaseAfterSuccess());
        assertTrue(ReplaySaveGuard.rejectRegionWrite());
    }

    @Test void cleanReplayCanReleaseAndFailureRemainsProtected() {
        ReplaySaveGuard.beginReplay();
        assertTrue(ReplaySaveGuard.releaseAfterSuccess());
        assertFalse(ReplaySaveGuard.rejectRegionWrite());
        ReplaySaveGuard.fail();
        assertTrue(ReplaySaveGuard.rejectRegionWrite());
        assertFalse(ReplaySaveGuard.releaseAfterSuccess());
    }

    @Test void highLevelSaveAlsoSignalsConflict() {
        ReplaySaveGuard.beginReplay();
        assertTrue(ReplaySaveGuard.rejectSave());
        assertTrue(ReplaySaveGuard.hasSaveConflict());
        assertFalse(ReplaySaveGuard.releaseAfterSuccess());
    }

    @Test void ioThreadConflictIsVisibleToServerThread() throws Exception {
        ReplaySaveGuard.beginReplay();
        CountDownLatch done = new CountDownLatch(1);
        Thread io = new Thread(() -> { ReplaySaveGuard.rejectRegionWrite(); done.countDown(); });
        io.start();
        done.await();
        assertTrue(ReplaySaveGuard.hasSaveConflict());
        assertFalse(ReplaySaveGuard.releaseAfterSuccess());
        io.join();
    }
}
