package com.dishanhai.gt_shanhai.common.ae2.quantum;

public final class QuantumDiagnostics {

    /**
     * 诊断总开关。为 false 时，配合调用点的 {@code if (QuantumDiagnostics.ENABLED)} 包裹，
     * Java 编译器会将死分支（含 detail 字符串拼接）整体消除，热路径零开销。
     * 需要排查量子合成问题时改为 true 并在 hit/slow 里接日志即可。
     */
    public static final boolean ENABLED = false;

    public static long start() {
        return 0L;
    }

    public static void hit(String key, String detail) {
    }

    public static void slow(String key, long startNanos, String detail) {
    }

    private QuantumDiagnostics() {}
}
