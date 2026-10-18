package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.inv.CraftingSimulationState;

import com.dishanhai.gt_shanhai.common.item.VirtualCraftingPresenceState;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingCalculation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// priority 必须高于 GTLCore 的 CraftingTreeNodeMixin(默认 1000)。
// fastRequest / ultraFastRequest / maxFastRequest /
// gtlcore$runMaxFastPrefix 是 GTLCore 后加的。本 mixin 若先应用，
// @ModifyVariable 只会命中原版 request，催化剂量仍会被下单次数放大。
// 注意：1.2.3.2 起没有 adaptiveRequest，不要加回选择器。
// 在场物品只改自己这个节点：已在场则直接记为满足，不抽取、不关闭上层 MAX_FAST。
@Mixin(value = CraftingTreeNode.class, priority = 1500, remap = false)
public abstract class CraftingTreeNodeVirtualPresenceMixin {

    @Shadow @Final IPatternDetails.IInput parentInput;
    @Shadow @Final private CraftingCalculation job;
    @Shadow @Final private AEKey what;
    @Shadow @Final private long amount;

    @ModifyVariable(
            method = { "request", "fastRequest", "ultraFastRequest",
                    "maxFastRequest", "gtlcore$runMaxFastPrefix" },
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false)
    private long gtShanhai$requestPresenceOncePerBatch(long requestedAmount) {
        return VirtualPatternEncodingHelper.isPresenceInput(this.parentInput)
                ? Math.max(1L, this.parentInput.getMultiplier())
                : requestedAmount;
    }

    @Inject(method = { "request", "fastRequest", "ultraFastRequest", "maxFastRequest" },
            at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$keepPresenceInputInInventory(CraftingSimulationState inventory,
            long requestedAmount, KeyCounter containerItems, CallbackInfo ci)
            throws InterruptedException, CraftBranchFailure {
        if (gtShanhai$presenceAlreadyAvailable(inventory)) {
            ci.cancel();
        }
    }

    @Inject(method = "gtlcore$runMaxFastPrefix", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$satisfyPresenceWithoutExtract(CraftingSimulationState inventory, long requestedAmount,
            KeyCounter containerItems, CallbackInfoReturnable<Long> cir) throws InterruptedException {
        if (gtShanhai$presenceAlreadyAvailable(inventory)) {
            cir.setReturnValue(0L);
        }
    }

    private boolean gtShanhai$presenceAlreadyAvailable(CraftingSimulationState inventory) throws InterruptedException {
        if (!VirtualPatternEncodingHelper.isPresenceInput(this.parentInput)) {
            return false;
        }
        ((ICraftingCalculation) this.job).gtlcore$handlePausing();
        long needed = Math.max(1L, this.parentInput.getMultiplier());
        GenericStack[] possibleInputs = this.parentInput.getPossibleInputs();
        if (possibleInputs == null) {
            return false;
        }
        for (GenericStack possibleInput : possibleInputs) {
            if (possibleInput != null && VirtualCraftingPresenceState.hasPresence(
                    inventory, possibleInput.what(), needed)) {
                inventory.addStackBytes(this.what, this.amount, needed);
                return true;
            }
        }
        return false;
    }
}
