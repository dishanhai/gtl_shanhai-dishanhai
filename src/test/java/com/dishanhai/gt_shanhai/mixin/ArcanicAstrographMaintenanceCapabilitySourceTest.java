package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcanicAstrographMaintenanceCapabilitySourceTest {

    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "dishanhai", "gt_shanhai", "mixin",
            "ArcanicAstrographMaintenanceCapabilityMixin.java");
    @Test
    void restoresOnlyAstrographHubMaintenanceAndDataCapabilitiesAfterGtlCoreUpdate() throws Exception {
        String source = Files.readString(SOURCE);
        String mixinConfig = MixinConfigs.readAll();

        assertTrue(source.contains("@Mixin(value = WorkableMultiblockMachine.class, remap = false, priority = 1500)"));
        assertTrue(source.contains("instanceof ArcanicAstrograph"));
        assertTrue(source.contains("DShanhaiMaintenanceHatchMachine hatch"));
        assertTrue(source.contains("\"maintenanceMachine\""));
        assertTrue(source.contains("\"dataAccessHatch\""));
        assertTrue(source.contains("parallelPreserved=true"));
        assertTrue(source.contains("method = \"upDate\""));
        assertTrue(mixinConfig.contains("\"ArcanicAstrographMaintenanceCapabilityMixin\""));
    }
}
