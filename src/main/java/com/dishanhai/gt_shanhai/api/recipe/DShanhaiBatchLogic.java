package com.dishanhai.gt_shanhai.api.recipe;

import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import org.jetbrains.annotations.Nullable;

/**
 * 山海批处理核心：把短配方（duration &lt; 目标 tick 数）合并为一条等效长配方一次执行。
 * <p>
 * 语义与 GTMAdvancedHatch 的 BatchLogic 一致：输入/输出/时长整体乘 N 后把 EUt 恢复为原值——
 * 功率不变、单位时间产出不变，纯粹减少 RecipeLogic 每周期查找/结算开销（TPS 优化）。
 * 挂载点必须在全部配方修饰器（超频/并行）之后，例如 {@code getRealRecipe} 覆写的 super 调用之后，
 * 与 GTMA 注入 RecipeModifierList#apply RETURN 的时机等价；二者条件同为 duration&lt;20 天然互斥，
 * 不会对同一配方叠加两次。
 */
public final class DShanhaiBatchLogic {

    /** 批处理目标时长：短于 1 秒（20t）的配方合并到 ≥1 秒。 */
    public static final int DEFAULT_TARGET_DURATION_TICKS = 20;

    private DShanhaiBatchLogic() {
    }

    /**
     * 配方短于 targetDuration 时尝试批量合并，否则原样返回。
     * 传入的 recipe 必须已经是修饰链产物副本（RecipeLogic 传入 fullModifyRecipe 前已 copy），
     * 本方法不修改传入对象，合并结果是新副本。
     */
    @Nullable
    public static GTRecipe batchIfShort(IRecipeLogicMachine machine, @Nullable GTRecipe recipe, int targetDuration) {
        if (recipe == null || recipe.duration <= 0 || recipe.duration >= targetDuration) {
            return recipe;
        }
        int batchAmount = (int) Math.ceil((double) targetDuration / (double) recipe.duration);
        return batch(machine, recipe, batchAmount);
    }

    /** 按不超过 batchAmount 的倍数合并配方；受输入库存与输出空间双重收窄，收窄到 ≤1 时原样返回。 */
    public static GTRecipe batch(IRecipeLogicMachine machine, GTRecipe recipe, int batchAmount) {
        if (batchAmount <= 1) {
            return recipe;
        }
        int byInputs = maxMultiplierByInputs(machine, recipe, batchAmount);
        if (byInputs <= 1) {
            return recipe;
        }
        int limit = Math.min(limitByOutputSpace(machine, recipe, byInputs), batchAmount);
        if (limit <= 1) {
            return recipe;
        }
        GTRecipe batched = recipe.copy(ContentModifier.multiplier(limit), true);
        // GTMA 直接覆盖 parallels 会吞掉前置并行修饰器的倍数，这里用乘法保留（供 Jade/JEI 显示）
        batched.parallels = (int) Math.min((long) Math.max(1, recipe.parallels) * limit, Integer.MAX_VALUE);
        // ContentModifier 会把 tickIO(EUt) 也乘 N，恢复原值才是「合并周期」而非「放大功率」
        RecipeHelper.setInputEUt(batched, RecipeHelper.getInputEUt(recipe));
        RecipeHelper.setOutputEUt(batched, RecipeHelper.getOutputEUt(recipe));
        return batched;
    }

    /** 输入侧上限：各输入能力按库存可支撑倍数取最小；无可计算输入（全 MAX_VALUE 或空）返回 0 不批。 */
    private static int maxMultiplierByInputs(IRecipeLogicMachine machine, GTRecipe recipe, int batchAmount) {
        int min = Integer.MAX_VALUE;
        for (RecipeCapability<?> cap : recipe.inputs.keySet()) {
            if (cap.doMatchInRecipe()) {
                min = Math.min(min, cap.getMaxParallelRatio(machine, recipe, batchAmount));
            }
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** 输出侧上限：可 void 的输出不收窄，其余按输出空间 limitParallel 取最小；某项为 0 直接不批。 */
    private static int limitByOutputSpace(IRecipeLogicMachine machine, GTRecipe recipe, int batchAmount) {
        int min = batchAmount;
        for (RecipeCapability<?> cap : recipe.outputs.keySet()) {
            if (!cap.doMatchInRecipe() || recipe.getOutputContents(cap).isEmpty()
                    || machine.canVoidRecipeOutputs(cap)) {
                continue;
            }
            int limited = cap.limitParallel(recipe, machine, batchAmount);
            if (limited <= 0) {
                return 0;
            }
            min = Math.min(min, limited);
        }
        return min;
    }
}
