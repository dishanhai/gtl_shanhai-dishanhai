package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmonyMachineBypassMixinSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "HarmonyMachineBypassMixin.java");

    @Test
    void appliesAfterGtlAdditionsAddsHarmonyAccessorMethods() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("@Mixin(value = org.gtlcore.gtlcore.common.machine.multiblock.electric.HarmonyMachine.class, remap = false, priority = 1500)"),
                "枢纽绕过必须在 gtladditions HarmonyMachineMixin 之后应用");
        assertTrue(source.contains("gtladditions$consumeCosmosStartup"));
        assertTrue(source.contains("gtladditions$consumeAstralStartup"));
    }
}
