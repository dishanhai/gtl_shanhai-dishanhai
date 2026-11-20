package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GTLCoreBlockMapSelectorCompatibilitySourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalBehavior.java");

    @Test
    void terminalUsesGtlBlockFamiliesAndSendsTheSelectedTier() throws Exception {
        String behavior = Files.readString(SOURCE);
        String screen = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/terminal/UltimateTerminalScreen.java"));
        assertTrue(behavior.contains("UltimateTerminalScreenOpener.open(hand)"));
        assertTrue(screen.contains("BlockMap.tierBlockMap.get(key)"));
        assertTrue(screen.contains("getReplacementFamily(copy)"));
        assertTrue(screen.contains("getReplacementTier(copy, family)"));
        assertTrue(screen.contains("cell.click = () -> selectTier(index)"));
        assertTrue(screen.contains("send(Field.REPLACEMENT, index, family)"));
    }
}
