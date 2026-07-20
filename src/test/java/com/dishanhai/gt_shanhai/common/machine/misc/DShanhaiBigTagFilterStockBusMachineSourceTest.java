package com.dishanhai.gt_shanhai.common.machine.misc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DShanhaiBigTagFilterStockBusMachineSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "misc", "DShanhaiBigTagFilterStockBusMachine.java");

    @Test
    void tagFilterStockBusMustReadFullCachedInventoryInsteadOfHardCappingAt1024() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("getCachedInventory()"),
                "标签过滤库存总线必须从 AE cached inventory 枚举全量候选");
        assertFalse(source.contains("MAX_SCAN_ITEMS"),
                "标签过滤库存总线不得保留 1024 项硬截断");
        assertFalse(source.contains("storage.getAvailableStacks()"),
                "标签过滤库存总线不得继续扫 raw available stacks");
    }
}
