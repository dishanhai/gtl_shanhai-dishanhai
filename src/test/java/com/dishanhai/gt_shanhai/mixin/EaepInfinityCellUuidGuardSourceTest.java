package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void routineInsertAndExtractUpdateTotalsWithoutFullMapScan() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("@Redirect(method = \"insert\""));
        assertTrue(source.contains("@Redirect(method = \"extract\""));
        assertTrue(source.contains("AeStorageAmountMath.afterBigIntegerInsert"));
        assertTrue(source.contains("AeStorageAmountMath.afterBigIntegerExtract"));
        assertTrue(source.contains("gtShanhai$markChanged"));
        assertFalse(source.contains("@Overwrite"),
                "只能绕过常规存取后的全表重算，persist 与数据修复仍须保留 EAEP 原始实现");
    }
}
