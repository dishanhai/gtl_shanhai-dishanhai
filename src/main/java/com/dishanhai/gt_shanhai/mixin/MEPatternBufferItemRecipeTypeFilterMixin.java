package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.RecipeTypePatternSlotAccess;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.capability.IMERecipeHandler;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferRecipeHandlerTraitBase$MEItemInputHandlerBase", remap = false)
public abstract class MEPatternBufferItemRecipeTypeFilterMixin implements IMERecipeHandler<Ingredient, ItemStack> {

    @Shadow
    public abstract MEPatternBufferPartMachineBase getMachine();

    @Inject(method = "meHandleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$rejectWrongRecipeTypeSlot(GTRecipe recipe, Object2LongMap<Ingredient> left,
            boolean simulate, int trySlot, CallbackInfoReturnable<Boolean> cir) {
        MEPatternBufferPartMachineBase machine = getMachine();
        if (machine instanceof RecipeTypePatternSlotAccess access) {
            if (!access.gtShanhai$slotAllowsRecipe(trySlot, recipe)) {
                cir.setReturnValue(false);
            }
        }
    }
}
