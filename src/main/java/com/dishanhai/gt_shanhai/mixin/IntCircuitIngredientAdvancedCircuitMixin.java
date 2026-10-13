package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.circuit.ShanhaiAdvancedCircuitSelection;
import com.gregtechceu.gtceu.api.recipe.ingredient.IntCircuitIngredient;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 自写匹配只认载体上的一个 Configuration。多选标签里的其余电路也要算通过。
 */
@Mixin(value = IntCircuitIngredient.class, remap = false)
public class IntCircuitIngredientAdvancedCircuitMixin {

    @Shadow
    @Final
    private int configuration;

    @Inject(method = "test", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$matchAdvancedMask(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!ShanhaiAdvancedCircuitSelection.hasAdvancedMask(stack)) return;
        if (ShanhaiAdvancedCircuitSelection.isBitSet(ShanhaiAdvancedCircuitSelection.maskOf(stack), this.configuration)) {
            cir.setReturnValue(true);
        }
    }
}
