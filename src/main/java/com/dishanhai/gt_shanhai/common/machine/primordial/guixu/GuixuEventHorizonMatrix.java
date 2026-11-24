package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
final class GuixuEventHorizonMatrix {
    static final int OFFLINE = -1;
    static final int WARNING = 15;
    static final int FAILURE = 30;
    static final int SPACETIME_MULTIPLIER = 4;
    static final String LANG = "gt_shanhai.machine.guixu_event_horizon_matrix.";

    private GuixuEventHorizonMatrix() {}

    static long spacetimeCost(long normalCost) {
        return normalCost > Long.MAX_VALUE / SPACETIME_MULTIPLIER
                ? Long.MAX_VALUE : normalCost * SPACETIME_MULTIPLIER;
    }

    static int nextPressure(int pressure, boolean supplied) {
        if (pressure == OFFLINE || pressure == FAILURE) return pressure;
        return supplied ? Math.max(0, pressure - 2) : Math.min(FAILURE, pressure + 5);
    }

    static GuixuContainmentBonus boost(GuixuContainmentBonus normal, int pressure) {
        if (pressure == FAILURE) return GuixuContainmentBonus.LOCKED;
        if (pressure != 0) return normal;
        return new GuixuContainmentBonus(normal.parallelMultiplier() * 4L, normal.durationMultiplier() * 0.5D,
                normal.extraOutput() + 20, normal.energyMultiplier(), normal.matterCountMultiplier(),
                normal.swallowChance(), false);
    }
}
