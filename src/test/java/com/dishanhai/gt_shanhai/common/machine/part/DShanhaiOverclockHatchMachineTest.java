package com.dishanhai.gt_shanhai.common.machine.part;

import com.gregtechceu.gtceu.api.GTValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DShanhaiOverclockHatchMachineTest {

    @Test
    void maxDivisorKeepsLvValidAndMatchesGtoScaleAtUv() {
        assertEquals(2L, DShanhaiOverclockHatchMachine.getMaxDivisorForTier(GTValues.LV));
        assertEquals(2L, DShanhaiOverclockHatchMachine.getMaxDivisorForTier(GTValues.UV));
        assertEquals(Math.max(2L, (long) GTValues.MAX - 6L),
                DShanhaiOverclockHatchMachine.getMaxDivisorForTier(GTValues.MAX));
    }

    @Test
    void durationDivisorRoundsUpAndNeverDropsBelowOneTick() {
        assertEquals(50, DShanhaiOverclockHatchMachine.applyDurationDivisor(100, 2L));
        assertEquals(34, DShanhaiOverclockHatchMachine.applyDurationDivisor(101, 3L));
        assertEquals(1, DShanhaiOverclockHatchMachine.applyDurationDivisor(1, 64L));
        assertEquals(20, DShanhaiOverclockHatchMachine.applyDurationDivisor(20, 1L));
    }
}
