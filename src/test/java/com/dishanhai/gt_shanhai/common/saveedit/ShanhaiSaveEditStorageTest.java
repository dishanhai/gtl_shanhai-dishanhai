package com.dishanhai.gt_shanhai.common.saveedit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShanhaiSaveEditStorageTest {
    @TempDir Path directory;

    @Test
    void retainsIndependentVerifiedBackupsForFilesWithTheSameName() throws Exception {
        Path one = Files.createDirectories(directory.resolve("one")).resolve("same.dat");
        Path two = Files.createDirectories(directory.resolve("two")).resolve("same.dat");
        Files.writeString(one, "first");
        Files.writeString(two, "second");
        Path backups = directory.resolve("backups");
        var a = ShanhaiSaveEditStorage.writeAtomic(one, "new-first".getBytes(), backups, "same-run");
        var b = ShanhaiSaveEditStorage.writeAtomic(two, "new-second".getBytes(), backups, "same-run");
        assertTrue(a.ok());
        assertTrue(b.ok());
        assertNotEquals(a.backupPath(), b.backupPath());
        assertTrue(ShanhaiSaveEditStorage.restore(a.backupPath(), one));
        assertTrue(ShanhaiSaveEditStorage.restore(b.backupPath(), two));
        assertEquals("first", Files.readString(one));
        assertEquals("second", Files.readString(two));
    }

    @Test
    void reportsAtomicMoveRatherThanInferringItFromTemporaryFileCleanup() {
        var nonAtomic = new ShanhaiSaveEditStorage.WriteResult(true, true, true, false,
                null, "same", "same", "new", "new", "");
        assertFalse(ShanhaiSaveEditStorage.criterion3(nonAtomic).atomicWitnessed());
    }

    @Test
    void abortedMultiPageWriteRestoresEveryEarlierFile() throws Exception {
        Path a = directory.resolve("a.dat");
        Path b = directory.resolve("b.dat");
        Files.writeString(a, "before-a");
        Files.writeString(b, "before-b");
        var tx = ShanhaiSaveEditStorage.beginTransaction();
        assertTrue(ShanhaiSaveEditStorage.writeAtomic(a, "after-a".getBytes(), directory.resolve("backup"), "run").ok());
        assertTrue(ShanhaiSaveEditStorage.writeAtomic(b, "after-b".getBytes(), directory.resolve("backup"), "run").ok());
        assertTrue(tx.rollback().isEmpty());
        assertEquals("before-a", Files.readString(a));
        assertEquals("before-b", Files.readString(b));
    }

    @Test
    void failedRollbackReportsTheBackupAndReleasesTheTransaction() throws Exception {
        Path target = directory.resolve("a.dat");
        Files.writeString(target, "before");
        var tx = ShanhaiSaveEditStorage.beginTransaction();
        var write = ShanhaiSaveEditStorage.writeAtomic(target, "after".getBytes(), directory.resolve("backup"), "run");
        assertTrue(write.ok());
        Files.delete(write.backupPath());
        assertEquals(java.util.List.of(write.backupPath()), tx.rollback());
        var next = ShanhaiSaveEditStorage.beginTransaction();
        next.commit();
    }
}
