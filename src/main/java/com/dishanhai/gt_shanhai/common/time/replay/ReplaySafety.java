// Author: dishanhai
package com.dishanhai.gt_shanhai.common.time.replay;

/** Fail-closed preflight for a destructive world rewind. */
public final class ReplaySafety {
    private ReplaySafety() {}
    public static boolean hasLoss(ShanhaiReplayPacer.Journals j, long captureFailures, long blockFailures) {
        return captureFailures > 0 || blockFailures > 0
                || j.blocks().overflow() > 0 || j.blocks().droppedOldest() > 0
                || j.entities().overflow() > 0 || j.entities().droppedOldest() > 0
                || j.progress().overflow() > 0 || j.progress().droppedOldest() > 0
                || j.blockEntities().overflow() > 0 || j.blockEntities().droppedOldest() > 0
                || j.scalars().overflow() > 0 || j.scalars().droppedOldest() > 0
                || j.entityMotion() != null && (j.entityMotion().overflow() > 0 || j.entityMotion().droppedOldest() > 0)
                || j.health() != null && (j.health().overflow() > 0 || j.health().droppedOldest() > 0);
    }
    public static boolean mayStart(int dimensions, int saved, boolean saveDisabled, boolean incompleteHistory) {
        return dimensions > 0 && saved == dimensions && !saveDisabled && !incompleteHistory;
    }
}
