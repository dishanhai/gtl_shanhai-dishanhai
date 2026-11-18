package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GuixuTelemetryFormatTest {
    @Test void bigValuesUseCompactScientificNotation() {
        assertEquals("0", GuixuTelemetryFormat.number(BigInteger.ZERO));
        assertEquals("1.5M", GuixuTelemetryFormat.number(BigInteger.valueOf(1_500_000)));
        assertEquals("1e20", GuixuTelemetryFormat.number(BigInteger.TEN.pow(20)));
        assertEquals("--", GuixuTelemetryFormat.number(null));
    }
}
