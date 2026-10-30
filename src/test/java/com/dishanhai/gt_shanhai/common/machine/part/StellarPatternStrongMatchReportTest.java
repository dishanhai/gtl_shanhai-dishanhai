package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：報告正文必須保留摘要上限之後的診斷尾端。 */
class StellarPatternStrongMatchReportTest {
    @Test void fullReasonRetainsTheEndOfLongMismatchWhileTheSummaryStaysShort() {
        String detail = "expected-".repeat(100) + "TAIL_OF_MISMATCH";
        var report = new StellarPatternStrongMatchMessages.Report(
                StellarPatternStrongMatchMessages.Kind.IDENTITY, detail);
        assertTrue(report.line(0).endsWith(detail));
        assertFalse(report.line(180).contains("TAIL_OF_MISMATCH"));
    }
}
