package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineFeature;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import com.dishanhai.gt_shanhai.api.machine.part.IMaintenanceBypassPart;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.gtlcore.gtlcore.common.machine.multiblock.part.maintenance.IAutoConfigurationMaintenanceHatch;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 维护仓绕过：注入 RecipeLogic.setupRecipe（免EU/耗时）和 handleTickRecipe（免CWU匹配+消费）。
 * 注意：不使用 @Shadow 访问 GTRecipe 字段（可能有 final 泛型字段 Shadow 绑定问题），
 * 而是通过方法参数 recipe 直接访问其 public 字段。
 */
@Mixin(value = RecipeLogic.class, remap = false)
public class MaintenanceBypassRecipeMixin {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:mixin");

    @Shadow
    @Final
    public com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine machine;

    // ========== checkMatchedRecipeAvailable：匹配前清除 EU，解除电压等级限制 ==========

    @Inject(method = "checkMatchedRecipeAvailable", at = @At("HEAD"))
    private void gtShanhai$stripEUBeforeMatch(GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;

        try {
            DShanhaiMaintenanceHatchMachine hatch = gtShanhai$getHatch();
            if (hatch == null || !hatch.isVoltageBypassEnabled()) return;

            boolean hasEU = recipe.inputs.containsKey(EURecipeCapability.CAP)
                    || recipe.tickInputs.containsKey(EURecipeCapability.CAP);
            recipe.inputs.remove(EURecipeCapability.CAP);
            recipe.tickInputs.remove(EURecipeCapability.CAP);
            recipe.tickInputs.remove(CWURecipeCapability.CAP);
            if (hasEU) {
                LOG.info("[山海] stripEU: 已清除EU, recipe=" + recipe.hashCode());
            }
        } catch (Exception e) {
            LOG.info("[山海] stripEU异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ========== setupRecipe：免去 EU 消耗和应用可调耗时倍率 ==========

    /** ThreadLocal 传递枢纽计算的目标耗时，供 TAIL 注入覆写 gtlcore 的倍率 */
    private static final ThreadLocal<Integer> HUB_TARGET_DURATION = new ThreadLocal<>();

    @Inject(method = "setupRecipe", at = @At("HEAD"))
    private void gtShanhai$bypassRecipe(GTRecipe recipe, CallbackInfo ci) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;

        try {
            DShanhaiMaintenanceHatchMachine hatch = gtShanhai$getHatch();
            if (hatch == null) return;

            // 硬限被并行系统膨胀的时长（>1k ticks = 被并行计算无限拉长）
            if (recipe.duration > 1000) {
                recipe.duration = 20;
            }
            // 应用可调耗时倍率，记录目标值供 RETURN 覆写 gtlcore
            float multiplier = hatch.getDurationMultiplier();
            if (multiplier != 1.0f) {
                recipe.duration = Math.max(1, (int) (recipe.duration * multiplier));
            }
            HUB_TARGET_DURATION.set(recipe.duration);

            // 清除 EU/CWU 消耗（受电压开关控制）
            if (hatch.isVoltageBypassEnabled()) {
                gtShanhai$stripEUKeepGenerated(recipe);
            }

            // 应用产出倍率（最大5x，叠加于机器自带倍率之上）
            float outputMul = hatch.getOutputMultiplier();
            float totalMul = outputMul;
            // 额外线程倍率：从机器部件中找天球分歧引擎的线程数
            int threadCount = gtShanhai$getDivergenceThreads();
            if (threadCount > 1) {
                totalMul *= threadCount;
            }
            // 创造现实修改模块：线程附加 ×2（配合 int 上限补全 4294967294）
            if (gtShanhai$hasCreateModule()) {
                totalMul *= 2.0f;
            }
            if (totalMul > 1.01f) {
                GTRecipe multiplied = recipe.copy(ContentModifier.multiplier(totalMul), false);
                recipe.outputs.putAll(multiplied.outputs);
                recipe.tickOutputs.putAll(multiplied.tickOutputs);
                // 同样倍率 EU 产出（负数 EU/t）
                gtShanhai$multiplyGeneratedEU(recipe, totalMul);
            }
        } catch (Exception ignored) {}
    }

    /** TAIL 注入：在所有 setupRecipe 逻辑完成后覆写 duration */
    @Inject(method = "setupRecipe", at = @At(value = "RETURN", ordinal = 0))
    private void gtShanhai$forceHubDuration(GTRecipe recipe, CallbackInfo ci) {
        Integer target = HUB_TARGET_DURATION.get();
        HUB_TARGET_DURATION.remove();
        if (target != null && target > 0) {
            recipe.duration = target;
        }
    }

    /**
     * 从机器部件中查找天球分歧引擎（IThreadModifierPart）的线程数。
     * 用于单配方机器的线程注入。
     */
    private int gtShanhai$getDivergenceThreads() {
        try {
            var metaMachine = (MetaMachine) ((IMachineFeature) machine).self();
            if (!(metaMachine instanceof IMultiController controller)) return 1;
            if (!controller.isFormed()) return 1;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof com.gtladd.gtladditions.api.machine.feature.IThreadModifierPart tp) {
                    // 配置禁用时跳过枢纽，不影响分歧引擎等
                    if (!DShanhaiConfig.COMMON.hubOutputMultiplier.get()
                            && part instanceof DShanhaiMaintenanceHatchMachine) continue;
                    int t = tp.getThreadCount();
                    if (t > 1) return t;
                }
            }
        } catch (Exception ignored) {}
        return 1;
    }

    /** 检测是否插入了创造现实修改模块（触发跨线程 ×2） */
    private boolean gtShanhai$hasCreateModule() {
        try {
            var metaMachine = (MetaMachine) ((IMachineFeature) machine).self();
            if (!(metaMachine instanceof IMultiController controller)) return false;
            if (!controller.isFormed()) return false;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine hatch) {
                    var slot = hatch.getModuleSlot();
                    var stack = slot.getStackInSlot(0);
                    if (!stack.isEmpty() && "dishanhai:create_mk".equals(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 发电机配方通过负数 EU/t 表示发电量，不可被清除。
     */
    private void gtShanhai$stripEUKeepGenerated(GTRecipe recipe) {
        // 处理 tickInputs 中的 EU
        var euTick = recipe.tickInputs.get(EURecipeCapability.CAP);
        if (euTick != null && !euTick.isEmpty()) {
            // 检查第一个 EU 内容元素
            var first = euTick.get(0);
            if (first != null && first.getContent() instanceof Long euLong && euLong < 0) {
                // 负 EU = 发电，先保留，后续由 multiplyGeneratedEU 处理
                return;
            }
        }
        // 正 EU 或空则直接清除
        recipe.tickInputs.remove(EURecipeCapability.CAP);
        recipe.tickInputs.remove(CWURecipeCapability.CAP);
        recipe.inputs.remove(EURecipeCapability.CAP);
    }

    private void gtShanhai$multiplyGeneratedEU(GTRecipe recipe, float factor) {
        try {
            var euTick = (java.util.List<com.gregtechceu.gtceu.api.recipe.content.Content>) recipe.tickInputs.get(EURecipeCapability.CAP);
            if (euTick != null && !euTick.isEmpty()) {
                var first = euTick.get(0);
                if (first != null && first.getContent() instanceof Long euLong && euLong < 0) {
                    first.content = (long) (euLong * factor);
                }
            }
        } catch (Exception ignored) {}
    }

    // ========== handleTickRecipe：清除 CWU/EU，让后续 matchTickRecipe + handleTickRecipeIO 正常走完 ==========
    // 不能 cancellable 返回 SUCCESS，那样会跳过 handleTickRecipeIO 导致无产出，配方卡 100%。

    @Inject(method = "handleTickRecipe", at = @At("HEAD"))
    private void gtShanhai$stripCWUFromTick(GTRecipe recipe, CallbackInfoReturnable<GTRecipe.ActionResult> cir) {
        if (!DShanhaiConfig.COMMON.maintenanceHatchEnabled.get()) return;

        try {
            DShanhaiMaintenanceHatchMachine hatch = gtShanhai$getHatch();
            if (hatch == null || !hatch.isVoltageBypassEnabled()) return;

            recipe.tickInputs.remove(EURecipeCapability.CAP);
            recipe.tickInputs.remove(CWURecipeCapability.CAP);
        } catch (Exception ignored) {}
    }

    // ========== 辅助：检测多方块主机是否安装了维护仓 ==========

    private int bypassLogSkip = 0;

    private boolean gtShanhai$hasBypassPart() {
        try {
            var metaMachine = (MetaMachine) ((IMachineFeature) machine).self();
            if (!(metaMachine instanceof IMultiController controller)) return false;
            if (!controller.isFormed()) return false;

            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine) {
                    if (++bypassLogSkip % 10 == 0) {
                        LOG.info("[山海] 找到维护仓! controller="
                                + controller.getClass().getSimpleName()
                                + " parts=" + controller.getParts().size());
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.info("[山海] hasBypassPart异常: " + e.getClass().getSimpleName());
        }
        return false;
    }

    private DShanhaiMaintenanceHatchMachine gtShanhai$getHatch() {
        try {
            var metaMachine = (MetaMachine) ((IMachineFeature) machine).self();
            if (!(metaMachine instanceof IMultiController controller)) return null;
            if (!controller.isFormed()) return null;
            for (IMultiPart part : controller.getParts()) {
                if (part instanceof DShanhaiMaintenanceHatchMachine h) return h;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 从维护仓读取可调耗时倍率，默认 1.0（不影响原耗时）
     */
    private float gtShanhai$getDurationMultiplier() {
        DShanhaiMaintenanceHatchMachine hatch = gtShanhai$getHatch();
        if (hatch != null) return hatch.getDurationMultiplier();
        return 1.0f;
    }

    /**
     * 从维护仓读取产出倍率（创造模块/大反冲解锁），默认 1.0。
     * 矩阵上固定将自带的 15x 覆写为 20x（乘 20/15，不叠加）。
     */
    private float gtShanhai$getOutputMultiplier() {
        DShanhaiMaintenanceHatchMachine hatch = gtShanhai$getHatch();
        if (hatch == null) return 1.0f;
        float baseMul = hatch.getOutputMultiplier();
        if (baseMul <= 1.0f) return 1.0f;
        // 矩阵上：15x 自带倍率 → 覆写为 20x（不叠加）
        if (gtShanhai$getHatch() != null) {
            try {
                var metaMachine = (MetaMachine) ((IMachineFeature) machine).self();
                if (metaMachine instanceof com.dishanhai.gt_shanhai.common.machine.spacetime.SpacetimeWaveMatrixMachine) {
                    return 20.0f / 15.0f;
                }
            } catch (Exception ignored) {}
        }
        return baseMul;
    }
}
