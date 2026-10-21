package com.dishanhai.gt_shanhai.common.machine.output;

import com.dishanhai.gt_shanhai.api.machine.output.IOutputMultiplierSource;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.machine.wave.GravitationalWaveBroadcastManager;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.ForgeOfTheAntichristModuleBase;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.HelioFusionExoticizer;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.HelioflarePowerForge;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.HeliofluixMeltingCore;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.HeliothermalPlasmaFabricator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.utils.NumberUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 星律/AE 下单用的宿主产出倍率聚合器。
 * <p>
 * 确定性倍率来源按 source key 去重后叠乘；线程通道沿用现有机器逻辑，
 * 在 hubOutputMultiplier 开启时把同一宿主内的线程数先相加，再作为一个倍率通道参与叠乘。
 */
public final class OutputMultiplierResolver {

    private static final String FOA_NATIVE_DIMENSIONAL_PLASMA = "gtceu:dimensionally_transcendent_plasma_forge";
    private static final String FOA_NATIVE_STELLAR_FORGE = "gtceu:stellar_forge";
    private static final String FOA_NATIVE_ULTIMATE_MATERIAL_FORGE = "gtceu:ultimate_material_forge";
    private static final String HELIOFUSION_MATTER_EXOTIC = "gtceu:matter_exotic";
    private static final String HELIOFLARE_ALLOY_BLAST = "gtceu:alloy_blast_smelter";
    private static final String HELIOFLUIX_CHAOTIC_ALCHEMY = "gtceu:chaotic_alchemy";
    private static final String HELIOFLUIX_CHAOS_ALCHEMY_ALIAS = "gtceu:chaos_alchemy";
    private static final String HELIOTHERMAL_FUSION = "gtceu:fusion_reactor";
    private static final String HELIOTHERMAL_SUPER_PARTICLE_COLLIDER = "gtceu:super_particle_collider";

    private OutputMultiplierResolver() {}

    public static long resolveHostOutputMultiplier(Iterable<?> controllers,
            @Nullable Level level, @Nullable BlockPos pos) {
        return resolveHostOutputMultiplier(controllers, level, pos, null);
    }

    public static long resolveHostOutputMultiplier(Iterable<?> controllers,
            @Nullable Level level, @Nullable BlockPos pos, @Nullable String recipeTypeId) {
        return resolveHostOutputMultiplier(controllers, level, pos, recipeTypeId, false);
    }

    public static long resolveMaxHostOutputMultiplier(Iterable<?> controllers,
            @Nullable Level level, @Nullable BlockPos pos) {
        return resolveHostOutputMultiplier(controllers, level, pos, null, true);
    }

    public static long resolveUniversalHostOutputMultiplier(Iterable<?> controllers,
            @Nullable Level level, @Nullable BlockPos pos) {
        return resolveHostOutputMultiplier(controllers, level, pos, null, false);
    }

    /**
     * 只含伪神自身（本体/子模块）对该配方类型的倍率，不叠枢纽/线程/引力波。
     * 原版 FOTC 只按这个倍率放大带 {@code recipe_cycle_container} 标记的输入并追加空容器，
     * 叠加层只放大产出；没有伪神来源时返回 0（区别于「伪神倍率为 1」）。
     */
    public static long resolveForgeOutputMultiplier(Iterable<?> controllers, @Nullable String recipeTypeId) {
        String canonicalRecipeTypeId = PatternRecipeTypeHelper.canonicalRecipeTypeId(recipeTypeId);
        if (controllers == null || canonicalRecipeTypeId.isEmpty()) return 0L;
        Accumulator forgeOnly = new Accumulator();
        for (Object controller : controllers) {
            collectForgeOfTheAntichristMultiplier(controller, forgeOnly, canonicalRecipeTypeId, false);
            if (!(controller instanceof IMultiController multiController)) continue;
            Iterable<? extends IMultiPart> parts = multiController.getParts();
            if (parts == null) continue;
            for (IMultiPart part : parts) {
                collectForgeOfTheAntichristMultiplier(part, forgeOnly, canonicalRecipeTypeId, false);
            }
        }
        return forgeOnly.hasSources() ? forgeOnly.result() : 0L;
    }

    public static int resolveForgeRecipeTypeFingerprint(Iterable<?> controllers) {
        int fingerprint = 1;
        if (controllers == null) return fingerprint;
        for (Object controller : controllers) {
            fingerprint = 31 * fingerprint + forgeRecipeTypeFingerprint(controller);
            if (!(controller instanceof IMultiController multiController)) continue;
            Iterable<? extends IMultiPart> parts = multiController.getParts();
            if (parts == null) continue;
            for (IMultiPart part : parts) {
                fingerprint = 31 * fingerprint + forgeRecipeTypeFingerprint(part);
            }
        }
        return fingerprint;
    }

    private static long resolveHostOutputMultiplier(Iterable<?> controllers,
            @Nullable Level level, @Nullable BlockPos pos, @Nullable String recipeTypeId,
            boolean includeAnyForgeRecipeType) {
        String canonicalRecipeTypeId = PatternRecipeTypeHelper.canonicalRecipeTypeId(recipeTypeId);
        Accumulator accumulator = new Accumulator();
        if (controllers != null) {
            for (Object controller : controllers) {
                collectController(controller, accumulator, canonicalRecipeTypeId, includeAnyForgeRecipeType);
            }
        }
        accumulator.multiply(resolveFixedBroadcastMultiplier(level, pos));
        return accumulator.result();
    }

    private static void collectController(Object controller, Accumulator accumulator,
            String recipeTypeId, boolean includeAnyForgeRecipeType) {
        collectOutputSource(controller, accumulator);
        collectForgeOfTheAntichristMultiplier(controller, accumulator, recipeTypeId, includeAnyForgeRecipeType);
        if (!(controller instanceof IMultiController multiController)) return;

        Iterable<? extends IMultiPart> parts = multiController.getParts();
        if (parts == null) return;
        for (IMultiPart part : parts) {
            collectOutputSource(part, accumulator);
            collectForgeOfTheAntichristMultiplier(part, accumulator, recipeTypeId, includeAnyForgeRecipeType);
            if (DShanhaiConfig.COMMON.hubOutputMultiplier.get()
                    && part instanceof IThreadModifierPart threadPart) {
                accumulator.addThread(threadPart.getThreadCount());
            }
        }
    }

    private static void collectOutputSource(Object candidate, Accumulator accumulator) {
        if (!(candidate instanceof IOutputMultiplierSource source)) return;
        accumulator.addSource(source.getOutputMultiplierSourceKey(),
                source.getOutputMultiplierContribution());
    }

    private static void collectForgeOfTheAntichristMultiplier(Object candidate, Accumulator accumulator,
            String recipeTypeId, boolean includeAnyForgeRecipeType) {
        if (!includeAnyForgeRecipeType && recipeTypeId.isEmpty()) return;
        if (candidate instanceof ForgeOfTheAntichrist host) {
            collectForgeOfTheAntichristHostMultiplier(host, accumulator, recipeTypeId, includeAnyForgeRecipeType);
        } else if (candidate instanceof ForgeOfTheAntichristModuleBase module) {
            collectForgeOfTheAntichristModuleMultiplier(module, accumulator, recipeTypeId, includeAnyForgeRecipeType);
        }
    }

    private static void collectForgeOfTheAntichristHostMultiplier(ForgeOfTheAntichrist host,
            Accumulator accumulator, String recipeTypeId, boolean includeAnyForgeRecipeType) {
        if (includeAnyForgeRecipeType || forgeHostSupportsRecipeType(recipeTypeId)) {
            accumulator.addSource(host, sanitizeForgeMultiplier(host.getRecipeOutputMultiply()));
            return;
        }
        for (Object module : host.getModules()) {
            if (module instanceof ForgeOfTheAntichristModuleBase forgeModule
                    && forgeModule.isFormed()
                    && forgeModule.getHost() == host
                    && moduleSupportsForgeMultiplier(forgeModule, recipeTypeId)) {
                accumulator.addSource(host, sanitizeForgeMultiplier(host.getRecipeOutputMultiply()));
                return;
            }
        }
    }

    private static void collectForgeOfTheAntichristModuleMultiplier(ForgeOfTheAntichristModuleBase module,
            Accumulator accumulator, String recipeTypeId, boolean includeAnyForgeRecipeType) {
        if (!module.isFormed()) return;
        if (!includeAnyForgeRecipeType && !moduleSupportsForgeMultiplier(module, recipeTypeId)) return;
        ForgeOfTheAntichrist host = module.getHost();
        if (host == null) {
            // FOTCMixins.ModuleLogic：模块脱离主机独立运行时 getRecipeOutputMultiply 被写死为 15.0D，是静态 15x。
            if (standaloneModuleRunsWithoutHost(module)) {
                accumulator.addSource(module, ForgeOutputMultiplier.STANDALONE_MODULE_MULTIPLIER);
            }
            return;
        }
        accumulator.addSource(host, sanitizeForgeMultiplier(host.getRecipeOutputMultiply()));
    }

    /**
     * 只有走基类 calculateParallels 的模块吃得到 15.0D 兜底；HelioFusionExoticizer 自己重写了 calculateParallels
     * 并对 host 做 checkNotNull，没有主机就不会产出，不能声明倍率。
     */
    private static boolean standaloneModuleRunsWithoutHost(ForgeOfTheAntichristModuleBase module) {
        return DShanhaiConfig.COMMON.modulesWorkWithoutHost.get() && !(module instanceof HelioFusionExoticizer);
    }

    private static boolean forgeHostSupportsRecipeType(String recipeTypeId) {
        return FOA_NATIVE_DIMENSIONAL_PLASMA.equals(recipeTypeId)
                || FOA_NATIVE_STELLAR_FORGE.equals(recipeTypeId)
                || FOA_NATIVE_ULTIMATE_MATERIAL_FORGE.equals(recipeTypeId);
    }

    private static boolean moduleSupportsForgeMultiplier(ForgeOfTheAntichristModuleBase module, String recipeTypeId) {
        if (module instanceof HelioFusionExoticizer) {
            return HELIOFUSION_MATTER_EXOTIC.equals(recipeTypeId);
        }
        if (module instanceof HelioflarePowerForge) {
            return HELIOFLARE_ALLOY_BLAST.equals(recipeTypeId);
        }
        if (module instanceof HeliofluixMeltingCore) {
            return HELIOFLUIX_CHAOTIC_ALCHEMY.equals(recipeTypeId)
                    || HELIOFLUIX_CHAOS_ALCHEMY_ALIAS.equals(recipeTypeId);
        }
        if (module instanceof HeliothermalPlasmaFabricator) {
            return HELIOTHERMAL_FUSION.equals(recipeTypeId)
                    || HELIOTHERMAL_SUPER_PARTICLE_COLLIDER.equals(recipeTypeId);
        }
        // HeliophaseLeylineCrystallizer 的 Logic 没有 enableModify 覆写，继承基类的 false，
        // 宿主倍率根本不会作用在它的配方上（萊林配方的 1.5x 由 RecipesModify 在配方构建期写死）。
        return false;
    }

    private static int forgeRecipeTypeFingerprint(Object candidate) {
        int fingerprint = 1;
        if (candidate instanceof ForgeOfTheAntichrist host) {
            fingerprint = 31 * fingerprint + FOA_NATIVE_DIMENSIONAL_PLASMA.hashCode();
            fingerprint = 31 * fingerprint + FOA_NATIVE_STELLAR_FORGE.hashCode();
            fingerprint = 31 * fingerprint + FOA_NATIVE_ULTIMATE_MATERIAL_FORGE.hashCode();
            for (Object module : host.getModules()) {
                if (module instanceof ForgeOfTheAntichristModuleBase forgeModule
                        && forgeModule.isFormed()
                        && forgeModule.getHost() == host) {
                    fingerprint = 31 * fingerprint + moduleRecipeTypeFingerprint(forgeModule);
                }
            }
        } else if (candidate instanceof ForgeOfTheAntichristModuleBase module
                && (module.getHost() != null || standaloneModuleRunsWithoutHost(module))) {
            fingerprint = 31 * fingerprint + moduleRecipeTypeFingerprint(module);
        }
        return fingerprint;
    }

    private static int moduleRecipeTypeFingerprint(ForgeOfTheAntichristModuleBase module) {
        if (module instanceof HelioFusionExoticizer) {
            return HELIOFUSION_MATTER_EXOTIC.hashCode();
        }
        if (module instanceof HelioflarePowerForge) {
            return HELIOFLARE_ALLOY_BLAST.hashCode();
        }
        if (module instanceof HeliofluixMeltingCore) {
            int result = HELIOFLUIX_CHAOTIC_ALCHEMY.hashCode();
            return 31 * result + HELIOFLUIX_CHAOS_ALCHEMY_ALIAS.hashCode();
        }
        if (module instanceof HeliothermalPlasmaFabricator) {
            int result = HELIOTHERMAL_FUSION.hashCode();
            return 31 * result + HELIOTHERMAL_SUPER_PARTICLE_COLLIDER.hashCode();
        }
        return 0;
    }

    private static long resolveFixedBroadcastMultiplier(@Nullable Level level, @Nullable BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || pos == null) return 1L;
        int fixed = GravitationalWaveBroadcastManager.INSTANCE.getFixedOutputMultiplier(serverLevel, pos);
        return fixed > 1 ? fixed : 1L;
    }

    static long sanitizeMultiplier(long value) {
        return value <= 1L ? 1L : value;
    }

    /** 伪神倍率是连续 double，原版产出按 floor(数量 × 倍率) 截断，样板声明必须向下取整，见 {@link ForgeOutputMultiplier}。 */
    private static long sanitizeForgeMultiplier(double value) {
        return sanitizeMultiplier(ForgeOutputMultiplier.quantize(value));
    }

    private static final class Accumulator {

        private final Map<Object, Long> sourceMultipliers = new HashMap<>();
        private long threadMultiplier;
        private long fixedMultiplier = 1L;

        void addSource(Object sourceKey, long multiplier) {
            long sanitized = sanitizeMultiplier(multiplier);
            Object key = sourceKey == null ? this : sourceKey;
            sourceMultipliers.merge(key, sanitized, Math::max);
        }

        boolean hasSources() {
            return !sourceMultipliers.isEmpty();
        }

        void addThread(int threads) {
            if (threads <= 0) return;
            long next = threadMultiplier + (long) threads;
            threadMultiplier = next < threadMultiplier ? Long.MAX_VALUE : next;
        }

        void multiply(long multiplier) {
            fixedMultiplier = NumberUtils.saturatedMultiply(fixedMultiplier, sanitizeMultiplier(multiplier));
        }

        long result() {
            long result = fixedMultiplier;
            if (threadMultiplier > 0L) {
                result = NumberUtils.saturatedMultiply(result, threadMultiplier);
            }
            for (long sourceMultiplier : sourceMultipliers.values()) {
                result = NumberUtils.saturatedMultiply(result, sourceMultiplier);
            }
            return sanitizeMultiplier(result);
        }
    }
}
