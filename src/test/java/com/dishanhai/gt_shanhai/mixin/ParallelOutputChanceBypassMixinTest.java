package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelOutputChanceBypassMixinTest {

    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "ParallelOutputChanceBypassMixin.java");

    @Test
    void mixinTargetingInterfaceMustAlsoBeDeclaredAsInterface() throws IOException {
        String source = Files.readString(MIXIN);

        assertTrue(source.contains("@Mixin(value = IParallelLogic.class, remap = false)"));
        assertTrue(source.contains("public interface ParallelOutputChanceBypassMixin"));
        assertTrue(source.contains("@Overwrite"));
        assertTrue(source.contains("public static GTRecipe getRecipeOutputChance("));
        assertFalse(source.contains("public class ParallelOutputChanceBypassMixin"));
        assertFalse(source.contains("@ModifyVariable"));
        assertFalse(source.contains("gtShanhai$forceFullOutputChance"));
        assertFalse(source.contains("->"));
    }
}
