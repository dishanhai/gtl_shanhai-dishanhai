package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.GenericStack;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "appeng.api.crafting.PatternDetailsHelper", remap = false)
public class PatternDetailsHelperMixin {

    @ModifyVariable(method = "encodeProcessingPattern", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static GenericStack[] gtShanhai$rewriteNonConsumables(GenericStack[] inputs, GenericStack[] originalInputs, GenericStack[] outputs) {
        return VirtualPatternEncodingHelper.rewriteInputsForVirtualProviders(inputs, outputs);
    }

    @Inject(method = "encodeProcessingPattern", at = @At("RETURN"), remap = false)
    private static void gtShanhai$writeRecipeType(GenericStack[] inputs, GenericStack[] outputs,
            CallbackInfoReturnable<ItemStack> cir) {
        PatternRecipeTypeHelper.writeRecipeTypeFromPattern(cir.getReturnValue(), inputs, outputs);
    }
}
