package com.dishanhai.gt_shanhai.common.machine.structure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class STRStructureRegistryResolutionSourceTest {

    private static final Path STR_STRUCTURE = Path.of("src", "main", "java", "com",
            "dishanhai", "gt_shanhai", "common", "machine", "structure", "STRStructure.java");

    @Test
    void gravitationalWaveStructureDoesNotCacheRegistryBlocksDuringClassLoad() throws IOException {
        String source = Files.readString(STR_STRUCTURE);

        assertFalse(source.contains("public static final Block"),
                "STRStructure must not cache registry blocks as static final fields");
        assertFalse(source.contains("static {"),
                "STRStructure must not resolve registry blocks during class initialization");
        assertFalse(source.contains("ForgeRegistries.BLOCKS.getValue(new ResourceLocation("),
                "STRStructure must resolve block ids through the guarded helper");
        assertTrue(source.contains("private static Block block(String id)"),
                "STRStructure should resolve block ids through a guarded helper at pattern build time");
        assertTrue(source.contains("block(\"gtlcore:rhenium_reinforced_energy_glass\")"),
                "rhenium reinforced energy glass must be resolved when the pattern is built");
        assertTrue(source.contains("block(\"gt_shanhai:gravitational_wave_antenna_transmitter\", definition.getBlock())"),
                "the controller preview block must fall back to the registered definition block");
    }
}
