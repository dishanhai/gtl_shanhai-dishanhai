package com.dishanhai.gt_shanhai.common.machine.primordial.module.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.contents.TranslatableContents;

import org.junit.jupiter.api.Test;

class CoinForgeMintGateTest {
    @Test
    void lockedMintDiagnosticsReportTheSameRequiredLevelAsTheGate() {
        assertEquals("", CoinForgeMintGate.restrictionText(1, 1));
        assertEquals("", CoinForgeMintGate.restrictionText(0, 0));
        var first = (TranslatableContents) CoinForgeMintGate.restrictionComponent(0, CoinForgeMintGate.requiredModuleLevel("ulv")).getContents();
        assertEquals("gui.gt_shanhai.stellar_stuck.mint_locked_detail", first.getKey());
        assertEquals(0, first.getArgs()[0]);
        assertEquals(1, first.getArgs()[2]);
        var higher = (TranslatableContents) CoinForgeMintGate.restrictionComponent(1, CoinForgeMintGate.requiredModuleLevel("hv")).getContents();
        assertEquals("gui.gt_shanhai.stellar_stuck.mint_locked_detail", higher.getKey());
        assertEquals(1, higher.getArgs()[0]);
        assertEquals(4, higher.getArgs()[2]);
        assertEquals(CoinForgeMintGate.permissionComponent(1), higher.getArgs()[1]);
        assertFalse(CoinForgeMintGate.allowsRequiredLevel(1, 4));
        assertTrue(CoinForgeMintGate.allowsRequiredLevel(17, 17));
    }


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
        var gold = (TranslatableContents) CoinForgeMintGate.permissionComponent(4).getContents();
        assertEquals("gui.gt_shanhai.stellar_stuck.mint_upto", gold.getKey());
        assertEquals("gui.gt_shanhai.stellar_stuck.mint_tier_3",
                ((TranslatableContents) ((net.minecraft.network.chat.Component) gold.getArgs()[0]).getContents()).getKey());
    }

    @Test
    void topModuleUnlocksEveryShopStage() {
        assertTrue(CoinForgeMintGate.allows(14, "max"));
        assertTrue(CoinForgeMintGate.allows(17, "max"));
        assertEquals("gui.gt_shanhai.stellar_stuck.mint_all",
                ((TranslatableContents) CoinForgeMintGate.permissionComponent(17).getContents()).getKey());
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
