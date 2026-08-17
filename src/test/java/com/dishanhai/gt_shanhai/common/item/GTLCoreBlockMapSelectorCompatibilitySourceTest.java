package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GTLCoreBlockMapSelectorCompatibilitySourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "ShanhaiUltimateTerminalBehavior.java");

    @Test
    void usesTheGtlCore1231PositionAndSelectionCallbacks() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("0, root.getSizeHeight() + 4, settings.getSizeWidth(),"));
        assertTrue(source.contains("(family, tier) -> family != null && tier != null"));
        assertTrue(source.contains("family.equals(ShanhaiUltimateTerminalConfig.getReplacementFamily(terminal))"));
        assertTrue(source.contains("tier == ShanhaiUltimateTerminalConfig.getReplacementTier(terminal)"));
    }
}
