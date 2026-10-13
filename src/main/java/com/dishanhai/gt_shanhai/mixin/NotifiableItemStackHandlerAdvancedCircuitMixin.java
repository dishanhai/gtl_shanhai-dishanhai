package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.circuit.ShanhaiAdvancedCircuitSelection;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 单格电路槽写了多选标签后，查找和匹配都按全部已选电路放行，载体本身不消耗。
 */
@Mixin(value = NotifiableItemStackHandler.class, remap = false)
public class NotifiableItemStackHandlerAdvancedCircuitMixin {

    @Inject(method = "getContents", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$expandAdvancedCircuits(CallbackInfoReturnable<List<Object>> cir) {
        NotifiableItemStackHandler self = (NotifiableItemStackHandler) (Object) this;
        List<Object> expanded = ShanhaiAdvancedCircuitSelection.expandSingleSlot(self.storage);
        if (expanded != null) cir.setReturnValue(expanded);
    }

    @Inject(method = "handleRecipeInner", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$satisfyAdvancedCircuits(IO io, GTRecipe recipe, List<Ingredient> left, String slotName,
                                                   boolean simulate, CallbackInfoReturnable<List<Ingredient>> cir) {
        NotifiableItemStackHandler self = (NotifiableItemStackHandler) (Object) this;
        if (self.storage.getSlots() != 1 || left == null || io != self.handlerIO) return;
        ItemStack carrier = self.storage.getStackInSlot(0);
        if (!ShanhaiAdvancedCircuitSelection.hasAdvancedMask(carrier)) return;
        ShanhaiAdvancedCircuitSelection.removeSatisfied(left, ShanhaiAdvancedCircuitSelection.maskOf(carrier));
        self.storage.setStackInSlot(0, ItemStack.EMPTY);
        try {
            cir.setReturnValue(NotifiableItemStackHandler.handleIngredient(
                    io, recipe, left, simulate, self.handlerIO, self.storage));
        } finally {
            self.storage.setStackInSlot(0, carrier);
        }
    }
}
