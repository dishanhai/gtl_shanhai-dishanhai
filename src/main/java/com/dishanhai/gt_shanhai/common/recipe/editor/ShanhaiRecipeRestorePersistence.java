package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.sh_common_lib.api.ulk.storage.CheckedFileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Two-file replacement with verified backups and reverse-order recovery. */
public final class ShanhaiRecipeRestorePersistence {
    private ShanhaiRecipeRestorePersistence() {}
    public record Result(boolean ok, String error, List<Path> rollbackFailures) {}
    private record Change(Path target, Path backup, boolean created) {}

    public static Result write(Path rules, byte[] ruleData, Path toggles, byte[] toggleData, Path backupRoot) {
        if (rules == null || toggles == null || ruleData == null || toggleData == null || backupRoot == null
                || rules.toAbsolutePath().normalize().equals(toggles.toAbsolutePath().normalize())) {
            return new Result(false, "invalid-paths", List.of());
        }
        List<Change> changes = new ArrayList<>();
        try {
            writeOne(rules, ruleData, backupRoot, changes);
            writeOne(toggles, toggleData, backupRoot, changes);
            return new Result(true, "", List.of());
        } catch (IOException | RuntimeException failure) {
            List<Path> failed = new ArrayList<>();
            for (int i = changes.size() - 1; i >= 0; i--) {
                Change change = changes.get(i);
                try {
                    if (change.created()) {
                        Files.deleteIfExists(change.target());
                    } else if (change.backup() == null || !CheckedFileStore.restore(change.backup(), change.target())) {
                        failed.add(change.backup() == null ? change.target() : change.backup());
                    }
                } catch (IOException | RuntimeException rollbackFailure) {
                    failed.add(change.backup() == null ? change.target() : change.backup());
                }
            }
            return new Result(false, failure.toString(), List.copyOf(failed));
        }
    }

    private static void writeOne(Path target, byte[] contents, Path backupRoot, List<Change> changes)
            throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        boolean created = false;
        if (!Files.exists(target)) {
            Files.createFile(target);
            created = true;
        }
        if (created) changes.add(new Change(target, null, true));
        CheckedFileStore.WriteResult result = CheckedFileStore.write(target, contents, backupRoot, "recipe-restore");
        if (!created && result.backupPath() != null) changes.add(new Change(target, result.backupPath(), false));
        if (!result.ok()) throw new IOException(result.error());
    }
}
