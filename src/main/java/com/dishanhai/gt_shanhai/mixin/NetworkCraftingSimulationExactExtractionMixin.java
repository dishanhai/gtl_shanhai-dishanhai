package com.dishanhai.gt_shanhai.mixin;

import appeng.core.AEConfig;
import appeng.crafting.inv.NetworkCraftingSimulationState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * AE 默认可用缓存可能包含递归链中已经不可真实抽取的 seed。
 * 合成计划阶段强制模拟抽取，避免确认页通过、提交时 missing ingredient。
 */
@Mixin(value = NetworkCraftingSimulationState.class, remap = false)
public class NetworkCraftingSimulationExactExtractionMixin {

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/core/AEConfig;isCraftingSimulatedExtraction()Z"),
            remap = false)
    private boolean gtShanhai$forceExactCraftingInventory(AEConfig config) {
        return true;
    }
}
