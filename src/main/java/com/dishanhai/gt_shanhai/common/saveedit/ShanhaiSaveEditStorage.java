package com.dishanhai.gt_shanhai.common.saveedit;

import com.dishanhai.sh_common_lib.api.ulk.storage.CheckedFileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;

/** Save-editor adapter for ULK's checked single-file backup and replacement. */
public final class ShanhaiSaveEditStorage {
    private ShanhaiSaveEditStorage() {}
    private static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();

    private record Undo(Path target, Path backup) {}

    public static final class Transaction {
        private final List<Undo> undo = new ArrayList<>();
        private boolean finished;

        public void commit() {
            if (finished || CURRENT.get() != this) throw new IllegalStateException("invalid transaction");
            finished = true;
            CURRENT.remove();
        }

        /** Returns backup paths for files that could not be restored. */
        public List<Path> rollback() {
            if (finished || CURRENT.get() != this) throw new IllegalStateException("invalid transaction");
            List<Path> failed = new ArrayList<>();
            try {
                for (int i = undo.size() - 1; i >= 0; i--) {
                    Undo entry = undo.get(i);
                    try {
                        if (!CheckedFileStore.restore(entry.backup(), entry.target())) failed.add(entry.backup());
                    } catch (RuntimeException failure) {
                        failed.add(entry.backup());
                    }
                }
                return List.copyOf(failed);
            } finally {
                finished = true;
                CURRENT.remove();
            }
        }
    }

    public static Transaction beginTransaction() {
        if (CURRENT.get() != null) throw new IllegalStateException("transaction already active");
        Transaction tx = new Transaction();
        CURRENT.set(tx);
        return tx;
    }

    public static boolean hasActiveTransaction() {
        return CURRENT.get() != null;
    }

    public record WriteResult(boolean ok, boolean tmpCreated, boolean tmpVanished, boolean atomicMoveUsed,
                              Path backupPath, String originalSha256, String backupSha256,
                              String contentSha256, String targetSha256After, String error) {
        public String line() {
            return "ok=" + ok + " tmp_created=" + tmpCreated + " tmp_vanished=" + tmpVanished
                    + " atomic_move=" + atomicMoveUsed + " backup=" + backupPath
                    + " orig_sha=" + originalSha256 + " backup_sha=" + backupSha256
                    + " content_sha=" + contentSha256 + " target_sha_after=" + targetSha256After
                    + " error=" + error;
        }
    }

    public record Criterion3(boolean pass, boolean backupExists, boolean backupHashEqualsOriginal,
                             boolean atomicWitnessed, boolean contentLanded, String detail) {
        public String line() {
            return "criterion3 pass=" + pass + " backup_exists=" + backupExists
                    + " backup_hash_eq_original=" + backupHashEqualsOriginal
                    + " atomic_witnessed=" + atomicWitnessed + " content_landed=" + contentLanded
                    + " detail=" + detail;
        }
    }

    public static String sha256(Path path) {
        if (path == null) return null;
        try {
            return CheckedFileStore.sha256(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String sha256(byte[] content) {
        return content == null ? null : CheckedFileStore.sha256(content);
    }

    public static String stamp(long epochMillis) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date(epochMillis));
    }

    public static WriteResult writeAtomic(Path target, byte[] content, Path backupRoot, String stamp) {
        CheckedFileStore.WriteResult r = CheckedFileStore.write(target, content, backupRoot, stamp);
        Transaction tx = CURRENT.get();
        if (tx != null && r.backupPath() != null) tx.undo.add(new Undo(target, r.backupPath()));
        return new WriteResult(r.ok(), r.tmpCreated(), r.tmpVanished(), r.atomicMoveUsed(),
                r.backupPath(), r.originalSha256(), r.backupSha256(), r.contentSha256(),
                r.targetSha256After(), r.error());
    }

    public static boolean restore(Path backupFile, Path target) {
        return CheckedFileStore.restore(backupFile, target);
    }

    public static Criterion3 criterion3(WriteResult r) {
        if (r == null) return new Criterion3(false, false, false, false, false, "no_result");
        boolean exists = r.backupPath() != null && Files.isRegularFile(r.backupPath());
        boolean hashEquals = r.originalSha256() != null && r.originalSha256().equals(r.backupSha256());
        boolean landed = r.contentSha256() != null && r.contentSha256().equals(r.targetSha256After());
        return new Criterion3(r.ok() && exists && hashEquals && r.atomicMoveUsed() && landed,
                exists, hashEquals, r.atomicMoveUsed(), landed, r.error());
    }
}
