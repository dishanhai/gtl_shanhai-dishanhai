package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

/** 山海：雙端地址的排他性判斷；無世界狀態，呼叫端負責確認端點已載入及成形。 */
public final class GuixuNyarlathotepBindPolicy {
    public record Address(String dimension, long position) {}
    public enum Result { LINKED, ALREADY_LINKED, HOST_OCCUPIED, TARGET_OCCUPIED, UNAVAILABLE }

    private GuixuNyarlathotepBindPolicy() {}

    public static Result decide(boolean available, Address hostBinding, Address targetAddress,
                                Address targetBinding, Address hostAddress) {
        if (!available) return Result.UNAVAILABLE;
        if (hostBinding != null && !hostBinding.equals(targetAddress)) return Result.HOST_OCCUPIED;
        if (targetBinding != null && !targetBinding.equals(hostAddress)) return Result.TARGET_OCCUPIED;
        if (hostBinding != null && targetBinding != null) return Result.ALREADY_LINKED;
        // 單邊曾在另一端卸載時解除：只允許原地址配對修復，不覆蓋其他人的地址。
        return Result.LINKED;
    }
}
