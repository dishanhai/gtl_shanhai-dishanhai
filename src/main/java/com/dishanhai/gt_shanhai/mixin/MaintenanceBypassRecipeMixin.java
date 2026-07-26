package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.capability.recipe.CWURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineFeature;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

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
// priority 必须高于 gtlcore RecipeLogicMixin(默认 1000)：其对 setupRecipe / handleSearchingRecipes /
// checkMatchedRecipeAvailable / findAndHandleRecipe 全部是 @Overwrite，同优先级时应用顺序不定——
// 山海先应用则注入落在 GTCEu 原方法体上、随后被整体替换掉，表现为「枢纽插上去完全不生效」且无任何日志。
// 显式 1500 保证落在改写后的方法体上。与 NativeVirtualFindHandleRecipeMixin 保持一致。
@Mixin(value = RecipeLogic.class, priority = 1500, remap = false)
public class MaintenanceBypassRecipeMixin {

    @Shadow
    @Final
    public com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine machine;

    // ========== 关于「匹配前清除 EU」：绝不能在 checkMatchedRecipeAvailable 的 HEAD 做 ==========
    //
    // 这里曾有一个 gtShanhai$stripEUBeforeMatch，注入 checkMatchedRecipeAvailable 的 @At("HEAD")
    // 并直接 recipe.inputs.remove(EURecipeCapability.CAP)。那是错的，会永久污染全局配方注册表：
    //
    //   gtlcore RecipeLogicMixin#checkMatchedRecipeAvailable(GTRecipe match) {
    //       GTRecipe modified = this.machine.fullModifyRecipe(match.copy(), ...);   // copy 在方法体内
    //   }
    //
    // copy() 发生在方法体内，@At("HEAD") 跑在它之前，拿到的 match 就是 RecipeIterator 从查找树里
    // 直接吐出来的注册表实例（gtlcore$searchRecipe 与 RecipeIterator#next 全程无 copy）。
    // 于是机器每搜索一轮，所有候选配方的 EU/算力需求就被永久删掉一批，波及全服所有同类机器与 JEI，
    // 只有 /reload 或重启才能恢复。
    //
    // 正确做法已经存在：VoltageBypassMixin 注入 WorkableMultiblockMachine#doModifyRecipe 的 HEAD，
    // 对 fullModifyRecipe 传进去的**副本**做完全相同的三行剥除。而 checkMatchedRecipeAvailable 的
    // HEAD 与 fullModifyRecipe 之间没有任何其它语句，两处时机对结果等价——所以这里不需要任何注入。

    // ========== setupRecipe：免去 EU 消耗和应用可调耗时倍率 ==========

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

    // 这里曾有一个 gtShanhai$forceHubDuration，注入 setupRecipe 的 @At(value="RETURN", ordinal=0)，
    // 试图在 gtlcore 改完 duration 后再覆写回枢纽的目标值。三个问题，已整体移除：
    //   1. gtlcore 覆写后的 setupRecipe 有两个 RETURN，ordinal=0 指的是字节码顺序第一个——
    //      即 beforeWorking() 失败的提早返回分支，正常成功路径永远不会执行到这个 handler。
    //   2. 因此 HUB_TARGET_DURATION.remove() 在成功路径上从不调用，ThreadLocal 常驻主线程；
    //      等到某台机器 beforeWorking 失败触发本 handler 时，读到的是另一台无关机器的残留值。
    //   3. 即便改成 ordinal=1，gtlcore 的 this.duration = recipe.duration 也已在 if 块内赋值完毕，
    //      事后再改 recipe.duration 影响不到 this.duration。
    // 倍率已在上面 HEAD 注入里写进 recipe.duration，gtlcore 的 this.duration = recipe.duration
    // 自然会读到，无需事后覆写。

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

}
