package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.NativeVirtualRecipeRunner;
import com.dishanhai.gt_shanhai.common.item.NativeVirtualSetupState;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EnhancedCore 的 Plus 工厂、蒸汽平台等使用 RetryableRecipeLogic。
 * 它重写 findAndHandleRecipe，不走 RecipeLogic 上的星律虚拟执行链，
 * 并且用配方类型引用相等拒绝当前没选中的类型。
 * 通用加工厂-plus 停在卷板机时，流体固化样板因此报「未找到配方」。
 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.recipe.RetryableRecipeLogic", remap = false)
public abstract class EnhancedCoreRetryableVirtualMixin {

    @Inject(method = "findAndHandleRecipe", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void gtShanhai$ownedInactiveVirtualChain(CallbackInfo ci) {
        if (NativeVirtualRecipeRunner.tryStartOwnedInactive((RecipeLogic) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * 准入第一行是 {@code recipe.recipeType != machine.getRecipeType()}。
     * 虚拟执行这一轮让它看到样板自己的类型实例，引用相等才能通过。
     * 持久化的机器模式下标不动。
     */
    @Redirect(method = "checkMatchedRecipeAvailable",
            at = @At(value = "INVOKE",
                    target = "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;getRecipeType()Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;"),
            require = 0, remap = false)
    private GTRecipeType gtShanhai$alignOwnedInactiveType(IRecipeLogicMachine machine) {
        GTRecipeType override = NativeVirtualSetupState.ownedInactiveType();
        GTRecipe candidate = NativeVirtualSetupState.ownedInactiveCandidate();
        if (override != null && candidate != null
                && NativeVirtualRecipeRunner.sameRecipeType(candidate.recipeType, override)) {
            return candidate.recipeType;
        }
        return machine.getRecipeType();
    }
}
