package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;

import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingTreeProcess.class, remap = false)
public abstract class CraftingTreeProcessVirtualPresenceMixin {

    @Shadow @Final IPatternDetails details;
    @Shadow private boolean limitQty;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtShanhai$allowBulkPlanningWithPresenceInputs(ICraftingService craftingService,
            CraftingCalculation calculation, IPatternDetails details, CraftingTreeNode parent, CallbackInfo ci) {
        boolean hasPresenceInput = false;
        boolean requiresPerPatternLimit = false;
        for (IPatternDetails.IInput input : this.details.getInputs()) {
            GenericStack primaryInput = input.getPossibleInputs()[0];
            if (VirtualPatternEncodingHelper.isPresenceInput(input)) {
                hasPresenceInput = true;
            } else if (input.getRemainingKey(primaryInput.what()) != null) {
                requiresPerPatternLimit = true;
            }
            for (GenericStack output : this.details.getOutputs()) {
                if (output.what().matches(primaryInput)) {
                    requiresPerPatternLimit = true;
                    break;
                }
            }
        }
        if (hasPresenceInput) {
            this.limitQty = requiresPerPatternLimit;
        }
    }

}
