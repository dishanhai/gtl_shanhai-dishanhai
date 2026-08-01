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
    void resolverSupportsForgeOfTheAntichristMultiplierByRecipeType() throws Exception {
        String source = Files.readString(RESOLVER);

        assertTrue(source.contains("resolveHostOutputMultiplier(Iterable<?> controllers,\n"
                        + "            @Nullable Level level, @Nullable BlockPos pos, @Nullable String recipeTypeId)"),
                "星律读取宿主倍率必须有样板配方类型上下文，不能把伪神 15x 当成全局倍率");
        assertTrue(source.contains("ForgeOfTheAntichrist"));
        assertTrue(source.contains("ForgeOfTheAntichristModuleBase"));
        assertTrue(source.contains("collectForgeOfTheAntichristMultiplier"));
        assertTrue(source.contains("host.getRecipeOutputMultiply()"));
        assertTrue(source.contains("host.getModules()"));
        assertTrue(source.contains("module.getHost()"));
        assertTrue(source.contains("gtceu:matter_exotic"));
        assertTrue(source.contains("gtceu:alloy_blast_smelter"),
                "恒星烈焰能量煅炉 bytecode 判定为 GCyMRecipeTypes.ALLOY_BLAST_RECIPES，不是普通 alloy_smelter");
        assertTrue(source.contains("gtceu:chaotic_alchemy"));
        assertTrue(source.contains("gtceu:fusion_reactor"));
        assertTrue(source.contains("gtceu:super_particle_collider"));
        assertTrue(source.contains("gtceu:leyline_crystallize"));
        assertTrue(source.contains("moduleSupportsForgeMultiplier"));
        assertTrue(source.contains("if (!module.isFormed()) return;"),
                "直接遍历到伪神子模块时，未成型模块不得给星律提供宿主输出倍率");
        assertTrue(source.contains("resolveForgeRecipeTypeFingerprint"),
                "伪神子模块增减但最大倍率数值不变时，也必须能触发星律重写对应配方类型样板");
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
