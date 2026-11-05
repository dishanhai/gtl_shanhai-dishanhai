package com.dishanhai.gt_shanhai.api.machine;

// Author: dishanhai

/** Lightweight server-side observation; counters cover all paid batches, not the displayed lane. */
public record RecipeThreadSnapshot(long capacity, int active, int running, boolean enabled, boolean draining,
                                   long parallelCapacity, long reservedParallel,
                                   long successfulTicks, long completedBatches, boolean admissionBlocked) {
    public boolean threadsFull() { return capacity > 0L && active >= capacity; }
    public boolean parallelFull() { return parallelCapacity > 0L && reservedParallel >= parallelCapacity; }
}
