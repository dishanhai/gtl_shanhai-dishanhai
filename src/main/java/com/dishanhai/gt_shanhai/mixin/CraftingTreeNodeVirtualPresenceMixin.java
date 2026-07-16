package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftingTreeNode;

import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = CraftingTreeNode.class, priority = 900, remap = false)
public abstract class CraftingTreeNodeVirtualPresenceMixin {

    @Shadow @Final IPatternDetails.IInput parentInput;

    @ModifyVariable(
            method = { "request", "adaptiveRequest", "fastRequest", "ultraFastRequest" },
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false)
    private long gtShanhai$requestPresenceOncePerBatch(long requestedAmount) {
        return VirtualPatternEncodingHelper.isPresenceInput(this.parentInput)
                ? this.parentInput.getMultiplier()
                : requestedAmount;
    }
}
