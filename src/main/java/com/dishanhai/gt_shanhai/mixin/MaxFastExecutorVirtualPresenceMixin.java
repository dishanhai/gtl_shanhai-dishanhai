package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingTreeNode;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastExecutor;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastMetrics;

import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaxFastExecutor.class, priority = 1500, remap = false)
public abstract class MaxFastExecutorVirtualPresenceMixin {

    @Shadow
    public abstract void executeChild(ICraftingTreeNode root, CraftingSimulationState inventory,
            long requestedAmount, KeyCounter containerItems, MaxFastMetrics metrics)
            throws InterruptedException, appeng.crafting.CraftBranchFailure;

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$runPresenceRootWithoutAggregation(ICraftingTreeNode root,
            CraftingSimulationState inventory, long requestedAmount, KeyCounter containerItems,
            MaxFastMetrics metrics, CallbackInfo ci) throws InterruptedException, appeng.crafting.CraftBranchFailure {
        if (!(root instanceof CraftingTreeNodeVirtualPresenceAccess access)
                || !access.gtShanhai$containsPresenceInputInSubtree()) {
            return;
        }
        executeChild(root, inventory, requestedAmount, containerItems, metrics);
        ci.cancel();
    }
}
