package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.IDShanhaiBatchToggle;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiBatchLogic;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.logic.OCParams;
import com.gregtechceu.gtceu.api.recipe.logic.OCResult;
import com.gregtechceu.gtceu.api.recipe.modifier.RecipeModifierList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * part 载体批处理：多方块任一 part 实现 {@link IDShanhaiBatchToggle} 且开关开启时，
 * 在全部配方修饰器（含超频）跑完后对短配方（&lt;20t）做批量合并——终焉聚合枢纽、
 * 寰宇洁净重力维护仓装进任意 GT 多方块即生效，宿主机器无需改动。
 * <p>
 * 与 GTMAdvancedHatch 的 RecipeModifierListMixin 注入同一位置：二者触发条件同为
 * duration&lt;20，先跑的一方合并后另一方条件不再满足，天然互斥不双重叠加。
 * 多配方聚合机器（MultipleRecipesLogic 体系）不走 RecipeModifierList#apply，天然不受影响。
 */
@Mixin(value = RecipeModifierList.class, remap = false)
public abstract class DShanhaiBatchPartMixin {

    @Inject(method = "apply", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$applyPartBatch(MetaMachine machine, GTRecipe recipe, OCParams params, OCResult result,
                                          CallbackInfoReturnable<GTRecipe> cir) {
        GTRecipe modified = cir.getReturnValue();
        if (modified == null || modified.duration <= 0
                || modified.duration >= DShanhaiBatchLogic.DEFAULT_TARGET_DURATION_TICKS) {
            return;
        }
        if (!(machine instanceof MultiblockControllerMachine controller)
                || !(machine instanceof IRecipeLogicMachine rlm)) {
            return;
        }
        for (IMultiPart part : controller.getParts()) {
            if (part instanceof IDShanhaiBatchToggle toggle && toggle.isBatchModeEnabled()) {
                cir.setReturnValue(DShanhaiBatchLogic.batchIfShort(
                        rlm, modified, DShanhaiBatchLogic.DEFAULT_TARGET_DURATION_TICKS));
                return;
            }
        }
    }
}
