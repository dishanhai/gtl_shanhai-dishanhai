package com.dishanhai.gt_shanhai.util;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gtladd.gtladditions.common.machine.multiblock.controller.rrf.RRFModuleMachine;
import com.gtladd.gtladditions.common.machine.multiblock.controller.rrf.RRFWorkableModuleMachine;

public final class RRFModuleRestrictionBypass {

    private RRFModuleRestrictionBypass() {}

    private static boolean enabled() {
        return DShanhaiConfig.COMMON.recursiveReverseArrayBypassModuleRestrictions.get();
    }

    public static boolean ready(RRFModuleMachine module) {
        return enabled() && module.isFormed() && module.isConnectedToHost();
    }

    public static boolean ready(RRFWorkableModuleMachine module) {
        return enabled() && module.isFormed() && module.isConnectedToHost();
    }
}
