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

    /** 工作区按 autocrlf 检出成 CRLF，多行断言统一按 LF 比对。 */
    private static String read(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n");
    }

    @Test
    void resolverCollectsGenericMultiplierSources() throws Exception {
        String source = read(RESOLVER);

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
        String source = read(RESOLVER);

        assertTrue(source.contains("resolveUniversalHostOutputMultiplier(Iterable<?> controllers,"),
                "读宿主必须能单独取得全局倍率，不能因伪神按配方类型解析而让按钮退化为只刷新");
        assertTrue(source.contains("return resolveHostOutputMultiplier(controllers, level, pos, null, false);"),
                "全局倍率入口必须排除伪神的配方类型专属倍率");
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
        // bytecode：HeliophaseLeylineCrystallizerLogic 没有 enableModify 覆写，继承基类 false，宿主倍率不作用在它的配方上
        assertFalse(source.contains("gtceu:leyline_crystallize"));
        assertFalse(source.contains("HELIOPHASE_LEYLINE"));
        assertTrue(source.contains("moduleSupportsForgeMultiplier"));
        assertTrue(source.contains("if (!module.isFormed()) return;"),
                "直接遍历到伪神子模块时，未成型模块不得给星律提供宿主输出倍率");
        assertTrue(source.contains("resolveForgeRecipeTypeFingerprint"),
                "伪神子模块增减但最大倍率数值不变时，也必须能触发星律重写对应配方类型样板");
    }

    @Test
    void forgeMultiplierIsFlooredAndStaticOnlyAtTheCap() throws Exception {
        String source = read(RESOLVER);

        assertTrue(source.contains("sanitizeForgeMultiplier(host.getRecipeOutputMultiply())"));
        assertTrue(source.contains("ForgeOutputMultiplier.quantize(value)"),
                "伪神倍率是 1~15 的连续 double，原版产出 floor(数量 x 倍率)，高报一格 AE 就永远等不到");
        assertFalse(source.contains("Math.round"), "样板声明不得四舍五入宿主倍率");
    }

    @Test
    void forgeOnlyMultiplierIsSeparatedFromStackedOutputLayers() throws Exception {
        String source = read(RESOLVER);

        assertTrue(source.contains("public static long resolveForgeOutputMultiplier(Iterable<?> controllers, @Nullable String recipeTypeId)"));
        assertTrue(source.contains("return forgeOnly.hasSources() ? forgeOnly.result() : 0L;"),
                "没有伪神来源必须返回 0，不能和「伪神倍率为 1」混淆");
        assertTrue(source.contains("standaloneModuleRunsWithoutHost"));
        assertTrue(source.contains("ForgeOutputMultiplier.STANDALONE_MODULE_MULTIPLIER"),
                "FOTCMixins 让脱离主机的模块固定按 15.0D 产出，样板也要声明 15");
        assertTrue(source.contains("!(module instanceof HelioFusionExoticizer)"),
                "物质异化器自己重写 calculateParallels 并对 host 做 checkNotNull，没有主机不会产出");
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
