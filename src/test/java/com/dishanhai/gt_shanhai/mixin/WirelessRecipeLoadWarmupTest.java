package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.WirelessRecipeLoadWarmup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WirelessRecipeLoadWarmupTest {

    @Test
    void keepsIdleWirelessLogicAliveDuringWarmupWindow() {
        WirelessRecipeLoadWarmup warmup = new WirelessRecipeLoadWarmup(40);

        warmup.onMachineLoad(100L);

        assertTrue(warmup.shouldKeepSubscribed(100L, false));
        assertTrue(warmup.shouldKeepSubscribed(139L, false));
        assertFalse(warmup.shouldKeepSubscribed(140L, false));
    }

    @Test
    void doesNotForceKeepAliveWhenRecipeAlreadyRunning() {
        WirelessRecipeLoadWarmup warmup = new WirelessRecipeLoadWarmup(40);

        warmup.onMachineLoad(200L);

        assertFalse(warmup.shouldKeepSubscribed(200L, true));
    }

    @Test
    void reloadResetsWarmupWindowFromLatestLoadTick() {
        WirelessRecipeLoadWarmup warmup = new WirelessRecipeLoadWarmup(20);

        warmup.onMachineLoad(10L);
        assertFalse(warmup.shouldKeepSubscribed(30L, false));

        warmup.onMachineLoad(50L);
        assertTrue(warmup.shouldKeepSubscribed(69L, false));
        assertFalse(warmup.shouldKeepSubscribed(70L, false));
    }
}
