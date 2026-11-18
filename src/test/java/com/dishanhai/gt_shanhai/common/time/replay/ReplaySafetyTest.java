// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplaySafetyTest {
    @Test void evictedJournalMakesHistoryIncomplete() {
        var blocks = new ShanhaiReplayJournal(2);
        blocks.record(1, 0, 1, 1, 2, -1);
        blocks.record(2, 0, 2, 1, 2, -1);
        blocks.record(3, 0, 3, 1, 2, -1);
        var journals = new ShanhaiReplayPacer.Journals(blocks, new ShanhaiReplayEntityJournal(2),
                new ShanhaiReplayProgressJournal(2), new ShanhaiReplayBlockEntityJournal(2),
                new ShanhaiReplayScalarJournal(2));
        assertTrue(ReplaySafety.hasLoss(journals, 0, 0));
    }
    @Test void requiresAllDimensionsSavedAndCompleteHistory() {
        assertTrue(ReplaySafety.mayStart(2, 2, false, false));
        assertFalse(ReplaySafety.mayStart(2, 1, false, false));
        assertFalse(ReplaySafety.mayStart(2, 2, true, false));
        assertFalse(ReplaySafety.mayStart(2, 2, false, true));
        assertFalse(ReplaySafety.mayStart(0, 0, false, false));
    }
}
