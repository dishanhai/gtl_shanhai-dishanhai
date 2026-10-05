package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcanicAstrographMaintenanceCapabilitySourceTest {

    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "dishanhai", "gt_shanhai", "mixin",
            "ArcanicAstrographMaintenanceCapabilityMixin.java");
    private static final Path MIXIN_CONFIG = Path.of(
            "src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void restoresOnlyAstrographHubMaintenanceAndDataCapabilitiesAfterGtlCoreUpdate() throws Exception {
        String source = Files.readString(SOURCE);
        String mixinConfig = Files.readString(MIXIN_CONFIG);

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
