package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 山海：中央庫逐張提交，不把已提交原件交給星律回滾。 */
class EnhancedCoreCloudUploadBatchTest {

    private static final class Terminal implements EnhancedCoreCloudUploadBatch.Port<String> {
        final Set<String> stored = new HashSet<>();
        final List<String> events = new ArrayList<>();
        int blanks;
        String output;
        String failOn;
        boolean review;

        Terminal(int blanks) { this.blanks = blanks; }
        public boolean duplicate(String pattern) { return stored.contains(pattern); }
        public boolean outputEmpty() { return output == null; }
        public boolean consumeBlank() {
            if (blanks == 0) return false;
            blanks--;
            events.add("pay");
            return true;
        }
        public void stage(String pattern) { output = pattern; events.add("stage:" + pattern); }
        public EnhancedCoreCloudUploadBatch.Upload upload(String pattern) {
            events.add("upload:" + pattern);
            if (pattern.equals(failOn)) {
                if (review) output = null; // 原生 quarantine 已將原件收進 escrow。
                return review ? EnhancedCoreCloudUploadBatch.Upload.REVIEW
                        : EnhancedCoreCloudUploadBatch.Upload.RETAINED;
            }
            stored.add(pattern);
            output = null;
            return EnhancedCoreCloudUploadBatch.Upload.COMMITTED;
        }
    }

    @Test
    void commitsInPaymentOutputUploadOrder() {
        Terminal terminal = new Terminal(2);
        var result = EnhancedCoreCloudUploadBatch.run(List.of("a", "b"), terminal);
        assertEquals(2, result.uploaded());
        assertEquals(2, result.encoded());
        assertEquals(0, terminal.blanks);
        assertNull(terminal.output);
        assertEquals(List.of("pay", "stage:a", "upload:a", "pay", "stage:b", "upload:b"), terminal.events);
    }

    @Test
    void duplicatesNeedNoBlankAndDoNotTouchOccupiedOutput() {
        Terminal terminal = new Terminal(0);
        terminal.stored.add("a");
        terminal.output = "owned-existing";
        var result = EnhancedCoreCloudUploadBatch.run(List.of("a"), terminal);
        assertEquals(1, result.duplicates());
        assertEquals(0, result.encoded());
        assertTrue(terminal.events.isEmpty());
        assertEquals("owned-existing", terminal.output);
    }

    @Test
    void occupiedOutputIsNeverOverwrittenOrPaidFor() {
        Terminal terminal = new Terminal(2);
        terminal.output = "owned-existing";
        var result = EnhancedCoreCloudUploadBatch.run(List.of("a"), terminal);
        assertEquals(EnhancedCoreCloudUploadBatch.Status.OUTPUT_OCCUPIED, result.status());
        assertEquals(2, terminal.blanks);
        assertEquals("owned-existing", terminal.output);
        assertTrue(terminal.events.isEmpty());
    }

    @Test
    void shortageKeepsEarlierCommitsAndDoesNotCreateUnpaidPattern() {
        Terminal terminal = new Terminal(1);
        var result = EnhancedCoreCloudUploadBatch.run(List.of("a", "b"), terminal);
        assertEquals(1, result.uploaded());
        assertEquals(EnhancedCoreCloudUploadBatch.Status.MISSING_BLANK, result.status());
        assertEquals(Set.of("a"), terminal.stored);
        assertNull(terminal.output);
    }

    @Test
    void failedUploadKeepsPaidOutputAndStopsBeforeNextPattern() {
        Terminal terminal = new Terminal(3);
        terminal.failOn = "b";
        var result = EnhancedCoreCloudUploadBatch.run(List.of("a", "b", "c"), terminal);
        assertEquals(1, result.uploaded());
        assertEquals(2, result.encoded());
        assertEquals(1, terminal.blanks);
        assertEquals("b", terminal.output);
        assertEquals(Set.of("a"), terminal.stored);
        assertEquals(EnhancedCoreCloudUploadBatch.Status.UPLOAD_FAILED, result.status());
    }

    @Test
    void quarantinedSourceIsNotRecreatedOrRefunded() {
        Terminal terminal = new Terminal(2);
        terminal.failOn = "a";
        terminal.review = true;
        var result = EnhancedCoreCloudUploadBatch.run(List.of("a", "b"), terminal);
        assertEquals(1, result.encoded());
        assertEquals(0, result.uploaded());
        assertEquals(1, terminal.blanks);
        assertEquals(EnhancedCoreCloudUploadBatch.Status.REVIEW, result.status());
    }
}
