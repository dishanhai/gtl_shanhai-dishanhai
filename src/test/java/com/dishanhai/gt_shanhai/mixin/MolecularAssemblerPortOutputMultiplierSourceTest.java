package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolecularAssemblerPortOutputMultiplierSourceTest {

    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "MolecularAssemblerPortOutputMultiplierMixin.java");
    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void portPublishesCachedEffectivePatternsButPushesSourcePatterns() throws Exception {
        String source = Files.readString(MIXIN);

        assertTrue(source.contains("@Mixin(value = MEMolecularAssemblerIOPartMachine.class"));
        assertTrue(source.contains("method = \"getAvailablePatterns\""));
        assertTrue(source.contains("VirtualPatternEncodingHelper.rewritePatternOutputMultiplier"));
        assertTrue(source.contains("gtShanhai$effectiveToSource"));
        assertTrue(source.contains("@ModifyVariable("));
        assertTrue(source.contains("method = \"pushPattern\""));
        assertTrue(source.contains("gtShanhai$sourcePattern(patternDetails)"),
                "端口接单前必须把 AE 可见倍增视图还原成源样板，避免实际倍率再次叠乘");
        assertFalse(source.contains("patternSlotMap.put("),
                "端口不能改写 GTLCore 源样板映射");
    }

    @Test
    void portUsesReadOnlyHostMultiplierAndRefreshesOnlyOnChange() throws Exception {
        String source = Files.readString(MIXIN);

        assertTrue(source.contains("getOffsetTimer() % 40L"));
        assertTrue(source.contains("if (detected == gtShanhai$cachedHostOutputMultiplier) return;"));
        assertTrue(source.contains("module.getHostOutputMultiplier()"));
        assertTrue(source.contains("ICraftingProvider.requestUpdate"));
        assertTrue(source.contains("gtShanhai$outputMultiplierModeEnabled"));
        assertTrue(source.contains("gtShanhai$toggleOutputMultiplierMode"));
        assertTrue(source.contains("gtShanhai$refreshOutputMultiplierNow"));
        assertFalse(source.contains("IntInputWidget"),
                "分子端口倍率只能读取万象核心，不允许手动输入");
    }

    @Test
    void portMixinIsRegistered() throws Exception {
        assertTrue(Files.readString(CONFIG).contains("MolecularAssemblerPortOutputMultiplierMixin"));
    }
}
