package com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialMolecularAssemblerModuleSourceTest {

    private static final Path LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "module", "crafting",
            "PrimordialMolecularAssemblerModuleLogic.java");

    @Test
    void extractionUsesPositiveNativeLimitInsteadOfMixinSentinel() throws Exception {
        String source = Files.readString(LOGIC);

        assertTrue(source.contains("handler.extractGTRecipe(Long.MAX_VALUE, 1)"),
                "原初分子模块必须使用所有 MECraftHandler 实现都支持的正数提取上限");
        assertFalse(source.contains("handler.extractGTRecipe(Long.MIN_VALUE, 1)"),
                "Long.MIN_VALUE 会被 extendedae_plus_gtladd 的 HEAD Mixin 当作不可提取数量");
    }
}
