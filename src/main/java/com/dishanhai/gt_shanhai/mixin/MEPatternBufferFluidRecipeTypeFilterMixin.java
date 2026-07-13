package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.PatternNotConsumableFilter;
import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSlotAccess;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import org.gtlcore.gtlcore.api.capability.IMERecipeHandler;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEFluidHandlerBase", remap = false)
public abstract class MEPatternBufferFluidRecipeTypeFilterMixin implements IMERecipeHandler<FluidIngredient, FluidStack> {

    @Shadow
    public abstract MEPatternBufferPartMachineBase getMachine();

    @Inject(method = "meHandleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$rejectWrongRecipeTypeSlot(GTRecipe recipe, Object2LongMap<FluidIngredient> left,
            boolean simulate, int trySlot, CallbackInfoReturnable<Boolean> cir) {
        // 记录当前扣料配方，供配方执行后的虚拟目标 strip 判断"不消耗催化剂需保留在场"。
        // 对所有样板总成都记录（不只星律），保证 ThreadLocal 不残留上一次配方而串味。
        if (!simulate) {
            PatternNotConsumableFilter.setActiveRecipe(recipe);
        }
        MEPatternBufferPartMachineBase machine = getMachine();
        if (!(machine instanceof RecipeTypePatternSlotAccess access)) {
            return; // 非星律样板总成不做配方类型过滤/剔除，保持 GTLCore 原生行为
        }
        if (!access.gtShanhai$slotAllowsRecipe(trySlot, recipe)) {
            cir.setReturnValue(false);
            return;
        }
        // 真扣料阶段（非 simulate）把配方里"不消耗"（chance==0）的流体输入从待扣列表剔除，
        // 使 InternalSlot.handleFluidInternal 不再吞掉催化剂流体；simulate 阶段保留以维持在场校验。
        if (!simulate) {
            PatternNotConsumableFilter.stripNotConsumableFluids(recipe, left);
        }
    }
}
