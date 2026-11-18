package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GuixuGenerationMeterTest {
    @Test void countsSuccessfulTransactionsOncePerCompletedTick() {
        GuixuGenerationMeter meter = new GuixuGenerationMeter();
        BigInteger large = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        meter.record(10, large);
        meter.record(10, BigInteger.TEN);
        assertEquals(BigInteger.ZERO, meter.completedTick(10));
        assertEquals(large.add(BigInteger.TEN), meter.completedTick(11));
        assertEquals(BigInteger.ZERO, meter.completedTick(12));
    }
}
