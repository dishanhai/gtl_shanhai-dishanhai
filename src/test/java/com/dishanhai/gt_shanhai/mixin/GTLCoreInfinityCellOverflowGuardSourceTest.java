package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GTLCoreInfinityCellOverflowGuardSourceTest {

    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path ORDINARY = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "GTLCoreInfinityCellOverflowGuardMixin.java");
    private static final Path FAST = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "GTLCoreFastInfinityCellOverflowGuardMixin.java");

    @Test
    void ordinaryInfinityCellResyncsAfterInsertAndExtract() throws IOException {
        String source = Files.readString(ORDINARY);
        assertTrue(source.contains("org.gtlcore.gtlcore.integration.ae2.storage.InfinityCellInventory"));
        assertTrue(source.contains("method = \"insert\""));
        assertTrue(source.contains("method = \"extract\""));
        assertTrue(source.contains("lists.remove(what)"));
        assertTrue(source.contains("storedMap.get(what)"));
        assertTrue(source.contains("KeyCounter;add(Lappeng/api/stacks/AEKey;J)V"));
        assertTrue(source.contains("KeyCounter;remove(Lappeng/api/stacks/AEKey;J)V"));
        assertTrue(source.contains("lists.set(what, clamped)"));
    }

    @Test
    void fastInfinityCellUsesInt128SaturatedCounter() throws IOException {
        String source = Files.readString(FAST);
        assertTrue(Files.readString(CONFIG).contains("GTLCoreFastInfinityCellOverflowGuardMixin"));
        assertTrue(source.contains("FastInfinityCellInventory"));
        assertTrue(source.contains("Int128"));
        assertTrue(source.contains("toBigInteger()"));
        assertTrue(source.contains("Long.MAX_VALUE"));
        assertTrue(source.contains("method = \"insert\""));
        assertTrue(source.contains("method = \"extract\""));
        assertTrue(source.contains("KeyCounter;set(Lappeng/api/stacks/AEKey;J)V"));
        assertTrue(source.contains("method = \"loadCellItems\""));
        assertTrue(source.contains("replaceTruncatedLoadedAmount"));
    }
}
