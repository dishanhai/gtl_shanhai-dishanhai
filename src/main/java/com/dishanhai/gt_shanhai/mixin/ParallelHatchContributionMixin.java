package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.common.machine.misc.ProxyExecutorMachine;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiDivergenceEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.gregtechceu.gtceu.api.capability.IParallelHatch;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 并行/线程/电压覆写 Mixin。
 * <p>
 * 并行覆写提供两种模式（由配置文件 {@code parallel_override.forceMode} 控制）：
 * <ul>
 *   <li><b>精准覆写（默认 false）</b>：仅在 @Mixin 列表的已知类上生效，
 *       以 {@code @At("HEAD")} 强制覆写 {@code getMaxParallel()} 返回值，
 *       直接返回维护仓并行值，而非累加。</li>
 *   <li><b>全面覆写（true）</b>：扩大覆写范围，对所有实现了
 *       {@link IMultiController} 并定义了 {@code getMaxParallel()} 的机器
 *       强制生效，突破内部并行锁定（如纳米核心的 8192 限制）。</li>
 * </ul>
 */
@Mixin(value = {
        com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine.class,
        org.gtlcore.gtlcore.common.machine.multiblock.electric.WorkableElectricMultipleRecipesMachine.class,
        org.gtlcore.gtlcore.common.machine.multiblock.electric.CoilWorkableElectricMultipleRecipesMultiblockMachine.class,
        com.gtladd.gtladditions.api.machine.multiblock.GTLAddWorkableElectricMultipleRecipesMachine.class,
        com.gtladd.gtladditions.api.machine.multiblock.GTLAddCoilWorkableElectricMultipleRecipesMultiblockMachine.class,
        com.dishanhai.gt_shanhai.common.machine.spacetime.SpacetimeWaveMatrixMachine.class,
        com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine.class,
        com.dishanhai.gt_shanhai.common.machine.primordial.module.core.PrimordialBiologicalCore.class,
        com.dishanhai.gt_shanhai.common.machine.primordial.module.furnace.PrimordialChaoticEphemeralDeconstructionCrystallizationFurnace.class,
        com.dishanhai.gt_shanhai.common.machine.stripping.WorldLineStrippingOscillationGenerator.class,
        com.dishanhai.gt_shanhai.common.machine.primordial.module.core.PrimordialMatterRecombinatorCore.class,
        com.dishanhai.gt_shanhai.common.machine.primordial.module.matrix.PrimordialCausalWeavingMatrix.class,
        com.dishanhai.gt_shanhai.common.machine.primordial.module.core.PrimordialSingularityInversionCore.class,
        com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.PrimordialOmegaVoidInductionArmature.class,
        com.dishanhai.gt_shanhai.common.machine.misc.ZeroPhotonCondenserMachine.class,
        com.dishanhai.gt_shanhai.common.machine.misc.ProxyExecutorMachine.class,
        // gtlcore molecular assembler (AE 合成并行)
        org.gtlcore.gtlcore.api.machine.multiblock.MolecularAssemblerMultiblockMachineBase.class,
        // gtladditions machines with getMaxParallel override
        com.gtladd.gtladditions.common.machine.multiblock.controller.AdvancedSpaceElevatorModuleMachine.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.ApocalypticTorsionQuantumMatrix.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.BiologicalSimulationLaboratory.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.DimensionFocusInfinityCraftingArray.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.HeartOfTheUniverse.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.MacroAtomicResonantFragmentStripper.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.SkeletonShiftRiftEngine.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.TaixuTurbidArray.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.CreateAggregation.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.DoorOfCreate.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.MutableFusionReactorMachine.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.MutablePCBFactoryMachine.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.MutableSuprachronalAssemblyLineMachine.class,
        com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.MutableTierCasingMachine.class,
}, remap = false)
public class ParallelHatchContributionMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:parallel");

    // ========== 并行覆写（RETURN 替换模式） ==========

    /**
     * 在 {@code getMaxParallel()} 的 RETURN 处：
     * 如果并行仓的值 > 机器原生值，用并行仓值替换。
     * 精准覆写模式——只作用于 @Mixin 列表中的已知类。
     * 全面覆写由 AccurateParallelOverrideMixin + forceMode=true 处理。
     */
    @Inject(method = "getMaxParallel", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$boostMaxParallel(CallbackInfoReturnable<Integer> cir) {
        try {
            if (!((Object) this instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;

            // ADD 多配方机器会把 getMaxParallel() 作为 ContentModifier.multiplier(parallel)
            // 构造并行配方。对这些机器注入外部并行仓会变成直接输出倍率，跳过该覆写。
            if (gtShanhai$isGTLAddExternalMachine(controller)) return;

            int original = cir.getReturnValue();
            long hatchParallel = 0;
            for (IMultiPart part : controller.getParts()) {
                if (controller instanceof ProxyExecutorMachine && !gtShanhai$isProxyParallelOverridePart(part)) {
                    continue;
                }
                if (part instanceof DShanhaiMaintenanceHatchMachine
                        && !com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.hubOutputMultiplier.get()) {
                    continue;
                }
                if (part instanceof IParallelHatch hatch) {
                    int p = hatch.getCurrentParallel();
                    if (p > 0) hatchParallel += p;
                }
            }

            // 只有并行仓值大于原生值时才替换（原生值已包括机器自身并行）
            if (hatchParallel > original) {
                int result = hatchParallel > (long) Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) hatchParallel;
                cir.setReturnValue(result);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[boostMaxParallel] {} original={} hatch={} → {}", getClass().getSimpleName(), original, hatchParallel, result);
                }
                return;
            }

            // 分子操纵者：检测到 SUPER_PARALLEL_CORE 部件时直接设 MAX_VALUE
            if (controller.getClass().getName().contains("MolecularAssembler")) {
                for (IMultiPart part : controller.getParts()) {
                    if (part instanceof com.dishanhai.gt_shanhai.common.machine.part.DShanhaiSuperParallelCoreMachine) {
                        cir.setReturnValue(Integer.MAX_VALUE);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("[boostMaxParallel] 异常", e);
        }
    }

    private static boolean gtShanhai$isProxyParallelOverridePart(IMultiPart part) {
        return part instanceof DShanhaiMaintenanceHatchMachine
                || part instanceof DShanhaiDivergenceEngineMachine;
    }

    /** 检测是否为由主机供应并行的模块机器 */
    private static boolean gtShanhai$isModuleMachine(IMultiController controller) {
        try {
            var m = controller.getClass().getMethod("getHost");
            return m.invoke(controller) != null;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean gtShanhai$isGTLAddExternalMachine(IMultiController controller) {
        String name = controller.getClass().getName();
        return name.startsWith("com.gtladd.gtladditions.common.machine.multiblock.controller.")
                || name.startsWith("com.gtladd.gtladditions.common.machine.multiblock.controller.mutable.");
    }

    // ========== 线程注入（RETURN 累加模式） ==========

    @Inject(method = "getAdditionalThread", at = @At("RETURN"), cancellable = true, require = 0)
    private void gtShanhai$addHatchThreads(CallbackInfoReturnable<Integer> cir) {
        try {
            if (!((Object) this instanceof IMultiController controller)) return;
            // 模块机器跳过线程注入
            if (gtShanhai$isModuleMachine(controller)) return;
            if (!controller.isFormed()) return;

            int extra = 0;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart tp) {
                    int t = tp.getThreadCount();
                    if (t > 0) extra += t;
                }
            }

            if (extra > 0) {
                int original = cir.getReturnValue();
                long total = (long) original + extra;
                int result = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
                cir.setReturnValue(result);
                /* LOG.info("[getAdditionalThread] {} original={} extra={} → {}",
                        getClass().getSimpleName(), original, extra, result); */
            }
        } catch (Exception e) {
            LOG.error("[getAdditionalThread] 异常", e);
        }
    }

    // ========== 电压注入——让维护仓提供过场电压 ==========

    @Inject(method = "getMaxVoltage", at = @At("RETURN"), cancellable = true, require = 0)
    private void gtShanhai$overrideMaxVoltage(CallbackInfoReturnable<Long> cir) {
        try {
            if (!((Object) this instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;
            long maxVoltage = 0;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IMaintenanceBypassPart bp && bp.isVoltageBypassEnabled()) {
                    long v = bp.getBypassVoltage();
                    if (v > maxVoltage) maxVoltage = v;
                }
            }
            if (maxVoltage > 0) {
                long original = cir.getReturnValue();
                cir.setReturnValue(maxVoltage);
                /* LOG.info("[getMaxVoltage] {} original={} bypass={}",
                        getClass().getSimpleName(), original, maxVoltage); */
            }
        } catch (Exception ignored) {}
    }

    @Inject(method = "getOverclockVoltage", at = @At("RETURN"), cancellable = true, require = 0)
    private void gtShanhai$overrideOverclockVoltage(CallbackInfoReturnable<Long> cir) {
        try {
            if (!((Object) this instanceof IMultiController controller)) return;
            if (!controller.isFormed()) return;
            long maxVoltage = 0;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof IMaintenanceBypassPart bp && bp.isVoltageBypassEnabled()) {
                    long v = bp.getBypassVoltage();
                    if (v > maxVoltage) maxVoltage = v;
                }
            }
            if (maxVoltage > 0) {
                long original = cir.getReturnValue();
                cir.setReturnValue(maxVoltage);
                /* LOG.info("[getOverclockVoltage] {} original={} bypass={}",
                        getClass().getSimpleName(), original, maxVoltage); */
            }
        } catch (Exception ignored) {}
    }
}
