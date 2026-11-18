package com.dishanhai.gt_shanhai.common.saveedit.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShanhaiSaveEditAuditSidecarTransactionTest {
    @TempDir Path directory;

    @Test void unreadableMatchedSidecarPreventsAllWrites() throws Exception {
        String uuid = "bda13701-f01b-4bc2-ae2c-ec290bbfa5dd";
        Path first = directory.resolve("first.txt");
        Path missing = directory.resolve("missing.txt");
        Files.writeString(first, "keep\n" + uuid + "\n");
        byte[] before = Files.readAllBytes(first);
        var hits = List.of(
                new ShanhaiSaveEditAuditScan.SidecarHit(first, "first.txt", false, List.of(2), List.of(), "uuid"),
                new ShanhaiSaveEditAuditScan.SidecarHit(missing, "missing.txt", false, List.of(1), List.of(), "uuid"));

        var result = ShanhaiSaveEditAuditFix.applySidecarHits(hits, uuid, null, directory.resolve("backups"), "run");

        assertFalse(result.ok());
        assertEquals("sidecar_read_failed", result.reason());
        assertArrayEquals(before, Files.readAllBytes(first));
    }

    @Test void laterChangedSidecarRollsBackAnEarlierWrite() throws Exception {
        String uuid = "bda13701-f01b-4bc2-ae2c-ec290bbfa5dd";
        Path file = directory.resolve("sidecar.txt");
        Files.writeString(file, "keep\n" + uuid + "\n");
        byte[] before = Files.readAllBytes(file);
        var hit = new ShanhaiSaveEditAuditScan.SidecarHit(file, "sidecar.txt", false,
                List.of(2), List.of(), "uuid");

        var result = ShanhaiSaveEditAuditFix.applySidecarHits(
                List.of(hit, hit), uuid, null, directory.resolve("backups"), "run");

        assertFalse(result.ok());
        assertEquals("sidecar_changed", result.reason());
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}
