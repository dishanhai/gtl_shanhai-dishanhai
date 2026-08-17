package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GTLCoreAeUtilsCompatibilitySourceTest {

    private static final Path BUILD = Path.of("build.gradle");
    private static final Path QUANTUM_LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "ae2", "quantum", "QuantumCraftingCPULogic.java");
    private static final Path AE_UTILS_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "AeUtilsVirtualPatternInputsMixin.java");

    @Test
    void usesTheGtlCore1231PatternDetailsDescriptor() throws Exception {
        String build = Files.readString(BUILD);
        String quantumLogic = Files.readString(QUANTUM_LOGIC);
        String mixin = Files.readString(AE_UTILS_MIXIN);

        assertTrue(build.contains("[GTLCore]gtlcore-1.2.3.1-fix9.jar"));
        assertTrue(quantumLogic.contains("AEUtils.extractForProcessingPattern(details, inventory,"));
        assertFalse(quantumLogic.contains(
                "AEUtils.extractForProcessingPattern((AEProcessingPattern) details, inventory,"));
        assertTrue(mixin.contains("import appeng.api.crafting.IPatternDetails;"));
        assertTrue(mixin.contains(
                "extractForProcessingPattern(Lappeng/api/crafting/IPatternDetails;"));
        assertTrue(mixin.contains(
                "gtShanhai$extractReusablePresenceInputs(IPatternDetails details,"));
        assertFalse(mixin.contains("appeng.crafting.pattern.AEProcessingPattern"));
    }
}
