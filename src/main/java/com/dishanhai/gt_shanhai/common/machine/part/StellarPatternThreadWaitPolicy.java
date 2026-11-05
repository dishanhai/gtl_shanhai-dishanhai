package com.dishanhai.gt_shanhai.common.machine.part;

// Author: dishanhai

import com.dishanhai.gt_shanhai.api.machine.RecipeThreadSnapshot;

/** Per-slot/host observation for independently progressing recipe batches. */
final class StellarPatternThreadWaitPolicy {
    private boolean initialized;
    private long previousSuccessfulTicks;
    private long previousCompletedBatches;
    private long lastAdvance;
    private long opportunityBudget;
    private long missedBatches;

    boolean observe(long now, long timeout, RecipeThreadSnapshot state, long queuedSlots, boolean failed) {
        if (!initialized || state.successfulTicks() > previousSuccessfulTicks) lastAdvance = now;
        if (initialized && opportunityBudget > 0L && state.completedBatches() > previousCompletedBatches) {
            missedBatches = StellarPatternWaitPolicy.add(missedBatches,
                    state.completedBatches() - previousCompletedBatches);
        }
        previousSuccessfulTicks = state.successfulTicks();
        previousCompletedBatches = state.completedBatches();
        initialized = true;

        boolean competing = queuedSlots > 0L && state.active() > 0 && state.admissionBlocked();
        if (competing && opportunityBudget == 0L) {
            // 固定本槽前方批次與等待槽位的機會額度；後續補料不能永久掩蓋飢餓。
            opportunityBudget = StellarPatternWaitPolicy.add(state.active(), queuedSlots);
        }
        return competing && state.enabled() && !state.draining() && state.running() > 0 && !failed
                && missedBatches < opportunityBudget && now - lastAdvance >= 0L && now - lastAdvance < timeout;
    }

    long progressDeadline(long timeout) { return StellarPatternWaitPolicy.add(lastAdvance, timeout); }
}
