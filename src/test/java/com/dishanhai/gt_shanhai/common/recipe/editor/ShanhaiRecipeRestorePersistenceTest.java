package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ShanhaiRecipeRestorePersistenceTest {
    @TempDir Path directory;

    @Test void secondFileFailureRestoresTheFirstFile() throws Exception {
        Path rules = directory.resolve("rules.json");
        Path invalid = Files.createDirectory(directory.resolve("toggles.json"));
        Files.writeString(rules, "old-rules");
        var result = ShanhaiRecipeRestorePersistence.write(rules, bytes("new-rules"),
                invalid, bytes("new-toggles"), directory.resolve("backups"));
        assertFalse(result.ok());
        assertTrue(result.rollbackFailures().isEmpty());
        assertEquals("old-rules", Files.readString(rules));
    }

    @Test void writesBothFilesWhenBothSucceed() throws Exception {
        Path rules = directory.resolve("rules.json");
        Path toggles = directory.resolve("toggles.json");
        Files.writeString(rules, "old-rules");
        Files.writeString(toggles, "old-toggles");
        var result = ShanhaiRecipeRestorePersistence.write(rules, bytes("new-rules"),
                toggles, bytes("new-toggles"), directory.resolve("backups"));
        assertTrue(result.ok());
        assertEquals("new-rules", Files.readString(rules));
        assertEquals("new-toggles", Files.readString(toggles));
    }

    @Test void rollbackRemovesOnlyTheFileCreatedByThisAttempt() throws Exception {
        Path rules = directory.resolve("new-rules.json");
        Path invalid = Files.createDirectory(directory.resolve("not-a-file"));
        var result = ShanhaiRecipeRestorePersistence.write(rules, bytes("new-rules"),
                invalid, bytes("new-toggles"), directory.resolve("backups"));
        assertFalse(result.ok());
        assertTrue(result.rollbackFailures().isEmpty());
        assertFalse(Files.exists(rules));
        assertTrue(Files.isDirectory(invalid));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
