package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public final class GuixuTelemetryFormat {
    private static final String[] SUFFIXES = {"", "k", "M", "G", "T", "P", "E"};
    private GuixuTelemetryFormat() {}

    public static String number(BigInteger value) {
        if (value == null || value.signum() < 0) return "--";
        if (value.compareTo(BigInteger.TEN.pow(20)) >= 0) {
            int exponent = value.toString().length() - 1;
            BigDecimal scaled = new BigDecimal(value).movePointLeft(exponent).setScale(2, RoundingMode.HALF_UP);
            if (scaled.compareTo(BigDecimal.TEN) >= 0) { scaled = scaled.movePointLeft(1); exponent++; }
            return scaled.stripTrailingZeros().toPlainString() + "e" + exponent;
        }
        int magnitude = 0;
        BigDecimal scaled = new BigDecimal(value);
        while (scaled.compareTo(BigDecimal.valueOf(1000)) >= 0 && magnitude < SUFFIXES.length - 1) {
            scaled = scaled.movePointLeft(3);
            magnitude++;
        }
        scaled = scaled.setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(BigDecimal.valueOf(1000)) >= 0 && magnitude < SUFFIXES.length - 1) {
            scaled = scaled.movePointLeft(3);
            magnitude++;
        }
        return scaled.stripTrailingZeros().toPlainString() + SUFFIXES[magnitude];
    }

    public static String energy(BigInteger stored, BigInteger capacity, int flags, String noHatch, String unavailable) {
        if ((flags & 4) != 0) return noHatch;
        if ((flags & 1) == 0 || stored == null || capacity == null) return unavailable;
        return number(stored) + " / " + ((flags & 2) != 0 ? "∞" : number(capacity)) + " EU";
    }
}
