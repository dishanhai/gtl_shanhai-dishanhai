package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.circuit.ShanhaiAdvancedCircuitSelection;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.gtlcore.gtlcore.api.machine.trait.NotifiableCircuitItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 样板总成共享电路槽覆写了匹配，多选时在模拟阶段放行全部已选电路。
 */
@Mixin(value = NotifiableCircuitItemStackHandler.class, remap = false)
public class NotifiableCircuitItemStackHandlerAdvancedCircuitMixin {

    @Inject(method = "handleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$satisfyAdvancedCircuits(IO io, GTRecipe recipe, List<Ingredient> left, String slotName,
                                                   boolean simulate, CallbackInfoReturnable<List<Ingredient>> cir) {
        if (!simulate || left == null) return;
        NotifiableItemStackHandler self = (NotifiableItemStackHandler) (Object) this;
        if (self.storage.getSlots() <= 0) return;
        ItemStack carrier = self.storage.getStackInSlot(0);
        if (!ShanhaiAdvancedCircuitSelection.hasAdvancedMask(carrier)) return;
        ShanhaiAdvancedCircuitSelection.removeSatisfied(left, ShanhaiAdvancedCircuitSelection.maskOf(carrier));
        cir.setReturnValue(left.isEmpty() ? null : left);
    }
}
