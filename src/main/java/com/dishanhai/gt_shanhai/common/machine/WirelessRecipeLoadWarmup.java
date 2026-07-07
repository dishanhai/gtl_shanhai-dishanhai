package com.dishanhai.gt_shanhai.common.machine;

public final class WirelessRecipeLoadWarmup {

    private final long warmupTicks;
    private long expiresAtTick = Long.MIN_VALUE;

    public WirelessRecipeLoadWarmup(long warmupTicks) {
        this.warmupTicks = Math.max(1L, warmupTicks);
    }

    public void onMachineLoad(long currentTick) {
        this.expiresAtTick = currentTick + warmupTicks;
    }

    public boolean shouldKeepSubscribed(long currentTick, boolean hasRecipeRunning) {
        return !hasRecipeRunning && currentTick < expiresAtTick;
    }
}
