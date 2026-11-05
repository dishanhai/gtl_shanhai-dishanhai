package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ArcanicAstrographHubSlotSourceTest {

    @Test
    void sharedIoAdmissionReplacesAstrographBlanketSkipAndKeepsParallelFallback() throws Exception {
        String slot = Files.readString(Path.of(
                "src", "main", "java", "com", "dishanhai", "gt_shanhai", "mixin",
                "ShanhaiHubIoPredicateMixin.java"));
        String parallel = Files.readString(Path.of(
                "src", "main", "java", "com", "dishanhai", "gt_shanhai", "mixin",
                "ArcanicAstrographHubParallelMixin.java"));
        String mixinConfig = MixinConfigs.readAll();

        assertTrue(slot.contains("method = \"testLimited\""));
        assertFalse(slot.contains("instanceof ArcanicAstrograph"));
        assertTrue(slot.contains("gtShanhai$ioAdmissionOnly"));
        assertTrue(slot.contains("putIfAbsent(self, 0)"));
        assertTrue(slot.contains("setReturnValue(false)"));
        assertTrue(parallel.contains("findCosmosSimulationRecipe"));
        assertTrue(parallel.contains("hatchParallel > limit"));
        assertTrue(parallel.contains("applied < maxParallel"));
        assertTrue(parallel.contains("ParallelLogic.applyParallel"));
        assertFalse(mixinConfig.contains("\"ArcanicAstrographHubSlotMixin\""));
        assertTrue(mixinConfig.contains("\"ShanhaiHubIoPredicateMixin\""));
        assertTrue(mixinConfig.contains("\"ShanhaiHubAbilityPredicateMixin\""));
        assertTrue(mixinConfig.contains("\"ShanhaiHubAdmissionMixin\""));
        assertTrue(mixinConfig.contains("\"ArcanicAstrographHubParallelMixin\""));
    }
}
