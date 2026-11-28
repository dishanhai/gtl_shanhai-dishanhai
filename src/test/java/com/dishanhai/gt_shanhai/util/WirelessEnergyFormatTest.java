package com.dishanhai.gt_shanhai.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WirelessEnergyFormatTest {
    @Test
    void formatsLargeBalancesWithAnExplicitExponent() {
        assertEquals("0", WirelessEnergyFormat.balance(BigInteger.ZERO));
        assertEquals("999999999999999999", WirelessEnergyFormat.balance(new BigInteger("999999999999999999")));
        assertEquals("1E18", WirelessEnergyFormat.balance(BigInteger.TEN.pow(18)));
        assertEquals("-1.23E18", WirelessEnergyFormat.balance(new BigInteger("-1234000000000000000")));
        assertEquals("1E19", WirelessEnergyFormat.balance(new BigInteger("9999000000000000000")));
    }

    @Test
    void keepsCompactOutputBeyondDoubleRange() {
        assertEquals("4.8E315", WirelessEnergyFormat.balance(BigInteger.valueOf(48).multiply(BigInteger.TEN.pow(314))));
        assertEquals("1E100000", WirelessEnergyFormat.balance(BigInteger.TEN.pow(100_000)));
    }
}
