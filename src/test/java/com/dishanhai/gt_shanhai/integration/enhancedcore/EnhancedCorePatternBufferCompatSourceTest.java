package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class EnhancedCorePatternBufferCompatSourceTest {

    private static final Path COMPAT = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "integration", "enhancedcore", "EnhancedCorePatternBufferCompat.java");
    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "EnhancedCoreIntegratedFactoryStructureMixin.java");
    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.enhancedcore.mixin.json");
    private static final Path MODS_TOML = Path.of("src", "main", "resources", "META-INF", "mods.toml");

    @Test
    void optionalEnhancedCoreLayerAcceptsBothStellarPatternBlocks() throws IOException {
        String compat = Files.readString(COMPAT);
        String mixin = Files.readString(MIXIN);
        String config = Files.readString(CONFIG);
        String modsToml = Files.readString(MODS_TOML);

        assertTrue(compat.contains("gtladditions:me_super_pattern_buffer"));
        assertTrue(compat.contains("gt_shanhai:recipe_type_pattern_buffer"));
        assertTrue(compat.contains("gt_shanhai:recipe_type_pattern_buffer_proxy"));
        assertTrue(compat.contains("ForgeRegistries.BLOCKS.getValue"));
        assertTrue(compat.contains("Predicates.blocks(Arrays.copyOf(blocks, blockCount))"));
        assertTrue(!compat.contains("result.or(candidate)"));
        assertTrue(compat.indexOf("gt_shanhai:recipe_type_pattern_buffer\"")
                < compat.indexOf("gt_shanhai:recipe_type_pattern_buffer_proxy\""));
        assertTrue(mixin.contains("@Pseudo"));
        assertTrue(mixin.contains("IntegratedFactoryStructure"));
        assertTrue(mixin.contains("ordinal = 0"));
        assertTrue(config.contains("\"required\": false"));
        assertTrue(config.contains("EnhancedCoreIntegratedFactoryStructureMixin"));
        assertTrue(modsToml.contains("config=\"gt_shanhai.enhancedcore.mixin.json\""));
    }
}
