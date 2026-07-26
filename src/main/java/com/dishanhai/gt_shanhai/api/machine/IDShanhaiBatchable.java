package com.dishanhai.gt_shanhai.api.machine;

import com.dishanhai.gt_shanhai.api.recipe.DShanhaiBatchLogic;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import org.jetbrains.annotations.Nullable;

/**
 * 山海批处理能力接口（控制器版）：不限父类，任何配方机器 implements 后即可接入批处理。
 * <p>
 * 实现方约定：
 * <ul>
 * <li>自持一个 {@code @Persisted} 布尔字段实现开关存取（接口无法带字段）；</li>
 * <li>在自己的修饰链末端（多方块推荐覆写 {@code getRealRecipe}，对 super 结果）调 {@link #applyBatchMode}；</li>
 * <li>在 {@code attachConfigurators} 里调 {@link IDShanhaiBatchToggle#attachBatchConfigurator} 挂 GUI 开关。</li>
 * </ul>
 * 现成基类见 {@link DShanhaiBatchableMultiblockMachine}；纯开关载体（维护仓等 part）只需实现
 * {@link IDShanhaiBatchToggle}，由 {@code DShanhaiBatchPartMixin} 对宿主生效。
 * <p>
 * 注意：多配方聚合机器（gtlcore MultipleRecipesLogic 体系，如原初模块）不适用本接口——
 * 其聚合配方 duration 保底 {@code getLimitedDuration()}（默认 20t）且输入在聚合时已消费，
 * 架构上天然内建批处理语义，事后再乘会凭空放大产出。
 */
public interface IDShanhaiBatchable extends IRecipeLogicMachine, IDShanhaiBatchToggle {

    /** 合并目标时长（tick），子类可覆写调整门槛。 */
    default int getBatchTargetDuration() {
        return DShanhaiBatchLogic.DEFAULT_TARGET_DURATION_TICKS;
    }

    /** 在修饰链末端调用：开关开启且配方过短时返回合并后的新配方，否则原样返回。 */
    @Nullable
    default GTRecipe applyBatchMode(@Nullable GTRecipe recipe) {
        if (recipe == null || !isBatchModeEnabled()) {
            return recipe;
        }
        return DShanhaiBatchLogic.batchIfShort(this, recipe, getBatchTargetDuration());
    }
}
