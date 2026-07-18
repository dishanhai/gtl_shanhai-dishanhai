package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EaepInfinityCellUuidGuardSourceTest {

    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "EaepInfinityCellUuidGuardMixin.java");

    @Test
    void guardRunsBeforeEaepPersistAndOnlyRepairsNonEmptyCells() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("com.extendedae_plus.api.storage.InfinityBigIntegerCellInventory"));
        assertTrue(source.contains("method = \"persist\""));
        assertTrue(source.contains("getTotalAEKey2Amounts()"));
        assertTrue(source.contains("InfinityConstants.INFINITY_CELL_UUID"));
        assertTrue(source.contains("putUUID"));
        assertTrue(Files.readString(CONFIG).contains("EaepInfinityCellUuidGuardMixin"));
    }
}
