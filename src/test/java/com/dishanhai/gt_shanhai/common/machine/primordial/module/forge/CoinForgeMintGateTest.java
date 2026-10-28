package com.dishanhai.gt_shanhai.common.machine.primordial.module.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoinForgeMintGateTest {

    @Test
    void entryModuleUnlocksOnlyCopper() {
        assertTrue(CoinForgeMintGate.allows(1, "ulv"));
        assertFalse(CoinForgeMintGate.allows(1, "lv"));
        assertFalse(CoinForgeMintGate.allows(0, "ulv"));
    }

    @Test
    void higherModuleKeepsLowerCurrencies() {
        assertTrue(CoinForgeMintGate.allows(4, "ulv"));
        assertTrue(CoinForgeMintGate.allows(4, "hv"));
        assertFalse(CoinForgeMintGate.allows(4, "ev"));
        assertEquals("金币及以下", CoinForgeMintGate.permissionText(4));
    }

    @Test
    void topModuleUnlocksEveryShopStage() {
        assertTrue(CoinForgeMintGate.allows(14, "max"));
        assertTrue(CoinForgeMintGate.allows(17, "max"));
        assertEquals("全部货币", CoinForgeMintGate.permissionText(17));
    }

    @Test
    void unstagedOutputStaysOpenAndUnknownStageNeedsFounderModule() {
        assertTrue(CoinForgeMintGate.allows(0, ""));
        assertTrue(CoinForgeMintGate.allows(0, null));
        assertFalse(CoinForgeMintGate.allows(16, "custom"));
        assertTrue(CoinForgeMintGate.allows(17, "custom"));
    }

    @Test
    void parallelIsOneQuarterExceptZero() {
        assertEquals(16L, CoinForgeMintGate.quarterParallel(64L));
        assertEquals(32L, CoinForgeMintGate.quarterParallel(128L));
        assertEquals(1L, CoinForgeMintGate.quarterParallel(3L));
        assertEquals(0L, CoinForgeMintGate.quarterParallel(0L));
        assertEquals(Long.MAX_VALUE / 4L, CoinForgeMintGate.quarterParallel(Long.MAX_VALUE));
    }
}
