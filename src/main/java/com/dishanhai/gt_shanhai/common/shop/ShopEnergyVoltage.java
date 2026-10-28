package com.dishanhai.gt_shanhai.common.shop;

import java.math.BigInteger;

/** 山海：按展示用 EU 数量映射电压阶段，不参与实际电网结算。 */
public final class ShopEnergyVoltage {
    private static final long[] THRESHOLDS = {8L, 32L, 128L, 512L, 2048L, 8192L, 32768L,
            131072L, 524288L, 2097152L, 8388608L, 33554432L, 134217728L, 536870912L, 2147483647L};
    private static final String[] NAMES = {"ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM",
            "UV", "UHV", "UEV", "UIV", "UXV", "OpV", "MAX"};

    private ShopEnergyVoltage() {}

    public static String label(BigInteger amount) {
        if (amount == null || amount.compareTo(BigInteger.valueOf(THRESHOLDS[0])) < 0) return "低于 ULV";
        if (amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0) return "MAX+16";
        BigInteger max = BigInteger.valueOf(THRESHOLDS[THRESHOLDS.length - 1]);
        for (int i = 15; i >= 1; i--) {
            if (amount.compareTo(max.shiftLeft(i * 2)) >= 0) return "MAX+" + i;
        }
        for (int i = THRESHOLDS.length - 1; i >= 0; i--) {
            if (amount.compareTo(BigInteger.valueOf(THRESHOLDS[i])) >= 0) return NAMES[i];
        }
        return "低于 ULV";
    }
}
