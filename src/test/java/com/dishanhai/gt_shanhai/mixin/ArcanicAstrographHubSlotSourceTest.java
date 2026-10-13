package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcanicAstrographHubSlotSourceTest {

    @Test
    void hubDoesNotConsumeAstrographLimitedHatchAndKeepsItsOwnParallelFallback() throws Exception {
        String slot = Files.readString(Path.of(
                "src", "main", "java", "com", "dishanhai", "gt_shanhai", "mixin",
                "ArcanicAstrographHubSlotMixin.java"));
        String parallel = Files.readString(Path.of(
                "src", "main", "java", "com", "dishanhai", "gt_shanhai", "mixin",
                "ArcanicAstrographHubParallelMixin.java"));
        String mixinConfig = Files.readString(Path.of(
                "src", "main", "resources", "gt_shanhai.mixin.json"));

        assertTrue(slot.contains("method = \"testLimited\""));
        assertTrue(slot.contains("instanceof ArcanicAstrograph"));
        assertTrue(slot.contains("\"maintenance_hatch\""));
        assertTrue(slot.contains("setReturnValue(false)"));
        assertTrue(parallel.contains("findCosmosSimulationRecipe"));
        assertTrue(parallel.contains("hatchParallel > limit"));
        assertTrue(parallel.contains("applied < maxParallel"));
        assertTrue(parallel.contains("ParallelLogic.applyParallel"));
        assertTrue(mixinConfig.contains("\"ArcanicAstrographHubSlotMixin\""));
        assertTrue(mixinConfig.contains("\"ArcanicAstrographHubParallelMixin\""));
    }
}
