package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingTreeNode;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastExecutor;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastMetrics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaxFastExecutor.class, priority = 1500, remap = false)
public abstract class MaxFastExecutorVirtualPresenceMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$runPresenceRootWithoutAggregation(ICraftingTreeNode root,
            CraftingSimulationState inventory, long requestedAmount, KeyCounter containerItems,
            MaxFastMetrics metrics, CallbackInfo ci) throws InterruptedException, appeng.crafting.CraftBranchFailure {
        if (!(root instanceof CraftingTreeNodeVirtualPresenceAccess access)
                || !access.gtShanhai$containsPresenceInputInSubtree()) {
            return;
        }
        // executeChild 仍會進入 gtlcore$runMaxFastPrefix，對 PresenceInput 執行 MODULATE。
        // legacyRequest 才會沿用 AE2 原生的 Presence 專用輸入處理，不轉移虛擬存在物品。
        root.legacyRequest(inventory, requestedAmount, containerItems);
        ci.cancel();
    }
}
