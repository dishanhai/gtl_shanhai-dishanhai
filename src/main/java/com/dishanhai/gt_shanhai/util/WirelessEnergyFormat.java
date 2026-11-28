package com.dishanhai.gt_shanhai.util;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Compact display for wireless balances that may be far larger than a double. */
public final class WirelessEnergyFormat {
    private static final BigInteger SCIENTIFIC_THRESHOLD = BigInteger.TEN.pow(18);
    private static final double LOG10_OF_2 = Math.log10(2);

    private WirelessEnergyFormat() {}

    public static String balance(BigInteger value) {
        var magnitude = value.abs();
        if (magnitude.compareTo(SCIENTIFIC_THRESHOLD) < 0) return value.toString();

        int shift = magnitude.bitLength() - 53;
        double logarithm = Math.log10(magnitude.shiftRight(shift).longValue()) + shift * LOG10_OF_2;
        int exponent = (int) Math.floor(logarithm);
        int coefficient = (int) Math.round(Math.pow(10, logarithm - exponent) * 100);
        if (coefficient == 1000) {
            coefficient = 100;
            exponent++;
        }
        if (value.signum() < 0) coefficient = -coefficient;
        return BigDecimal.valueOf(coefficient, 2).stripTrailingZeros().toPlainString() + "E" + exponent;
    }
}
