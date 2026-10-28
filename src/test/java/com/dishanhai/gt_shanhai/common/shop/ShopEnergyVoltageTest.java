package com.dishanhai.gt_shanhai.common.shop;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopEnergyVoltageTest {
    @Test
    void namedVoltageThresholdsFollowTheRequestedTable() {
        long[] thresholds = {8, 32, 128, 512, 2048, 8192, 32768, 131072, 524288,
                2097152, 8388608, 33554432, 134217728, 536870912, 2147483647L};
        String[] names = {"ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UXV", "OpV", "MAX"};
        for (int i = 0; i < thresholds.length; i++) {
            assertEquals(names[i], ShopEnergyVoltage.label(BigInteger.valueOf(thresholds[i])));
            if (i > 0) assertEquals(names[i - 1], ShopEnergyVoltage.label(BigInteger.valueOf(thresholds[i] - 1)));
        }
    }

    @Test
    void maxStagesUseFourfoldStepsAndSaturateAtLongMax() {
        BigInteger max = BigInteger.valueOf(2147483647L);
        BigInteger next = max.multiply(BigInteger.valueOf(4));
        assertEquals("MAX", ShopEnergyVoltage.label(next.subtract(BigInteger.ONE)));
        assertEquals("MAX+1", ShopEnergyVoltage.label(next));
        assertEquals("MAX+15", ShopEnergyVoltage.label(max.shiftLeft(30)));
        assertEquals("MAX+15", ShopEnergyVoltage.label(BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE)));
        assertEquals("MAX+16", ShopEnergyVoltage.label(BigInteger.valueOf(Long.MAX_VALUE)));
        assertEquals("MAX+16", ShopEnergyVoltage.label(BigInteger.TEN.pow(100)));
        assertEquals("低于 ULV", ShopEnergyVoltage.label(BigInteger.ZERO));
    }
}
