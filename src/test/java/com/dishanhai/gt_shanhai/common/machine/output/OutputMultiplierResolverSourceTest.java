package com.dishanhai.gt_shanhai.common.machine.output;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputMultiplierResolverSourceTest {

    private static final Path RESOLVER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "output", "OutputMultiplierResolver.java");
    private static final Path TRANSMITTER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "wave", "GravitationalWaveAntennaTransmitter.java");

    @Test
    void resolverCollectsGenericMultiplierSources() throws Exception {
        String source = Files.readString(RESOLVER);

        assertTrue(source.contains("IOutputMultiplierSource"));
        assertTrue(source.contains("IThreadModifierPart"));
        assertTrue(source.contains("DShanhaiConfig.COMMON.hubOutputMultiplier.get()"));
        assertTrue(source.contains("GravitationalWaveBroadcastManager.INSTANCE.getFixedOutputMultiplier"));
        assertTrue(source.contains("sourceMultipliers.merge(key, sanitized, Math::max);"));
        assertTrue(source.contains("NumberUtils.saturatedMultiply"));
        assertFalse(source.contains("PrimordialOmegaEngineMachine"));
        assertFalse(source.contains("PrimordialOmegaEngineModuleBase"));
    }

    @Test
    void gravitationalWaveTransmitterPublishesStableFixedMultiplier() throws Exception {
        String source = Files.readString(TRANSMITTER);

        assertTrue(source.contains("private int getBroadcastOutputMultiplier()"));
        assertTrue(source.contains("return lensCount > 0 ? 3 : 2;"));
        assertTrue(source.contains("addSource("));
        assertTrue(source.contains("getBroadcastOutputMultiplier()"));
        assertTrue(source.contains("multiplier="));
    }
}
