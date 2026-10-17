package com.dishanhai.gt_shanhai.common.machine.part;

/**
 * ME 请求器的单行数量：网络持有量低于维持量时，按批量补一次，且不超过缺口。
 */
public final class PatternBufferRequestMath {

    private PatternBufferRequestMath() {}

    public static long toRequest(long networkAmount, long threshold, long batch,
                                 boolean rowEnabled, boolean submitted) {
        if (!rowEnabled || !submitted || threshold <= 0L || batch <= 0L) {
            return 0L;
        }
        long network = networkAmount < 0L ? 0L : networkAmount;
        if (network >= threshold) {
            return 0L;
        }
        long deficit = threshold - network;
        return batch < deficit ? batch : deficit;
    }
}
