package com.dishanhai.gt_shanhai.common.ae2.quantum;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

public final class QuantumDiagnostics {

    /**
     * 诊断总开关。为 false 时，配合调用点的 {@code if (QuantumDiagnostics.ENABLED)} 包裹，
     * Java 编译器会将死分支（含 detail 字符串拼接）整体消除，热路径零开销。
     * 排查"合成CPU显示100%完成但产出未回填样板总成"问题临时开启，输出到 logs/latest.log，
     * 搜索 "[QuantumDiag]" 前缀即可；排查完须改回 false，调用点非常多，长期开启会刷屏日志。
     */
    public static final boolean ENABLED = true;

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void hit(String key, String detail) {
        if (!ENABLED) return;
        GTDishanhaiMod.LOGGER.warn("[QuantumDiag] {} {}", key, detail);
    }

    public static void slow(String key, long startNanos, String detail) {
        if (!ENABLED) return;
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        GTDishanhaiMod.LOGGER.warn("[QuantumDiag] SLOW {} {}ms {}", key, elapsedMs, detail);
    }

    private QuantumDiagnostics() {}
}
