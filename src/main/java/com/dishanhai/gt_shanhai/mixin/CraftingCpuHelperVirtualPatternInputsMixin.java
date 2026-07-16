package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.inv.ListCraftingInventory;

import com.dishanhai.gt_shanhai.common.item.VirtualCraftingPatternInputExtractor;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingCpuHelper.class, remap = false)
    public class CraftingCpuHelperVirtualPatternInputsMixin {

    @Inject(method = "tryExtractInitialItems", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$extractOnlyRealInitialItems(ICraftingPlan plan, IGrid grid,
            ListCraftingInventory inventory, IActionSource source, CallbackInfoReturnable<appeng.api.stacks.GenericStack> cir) {
        if (!VirtualPatternEncodingHelper.containsPresenceInputs(plan)) return;
        cir.setReturnValue(com.dishanhai.gt_shanhai.common.item.VirtualCraftingInitialItemExtractor.extract(
                plan, grid, inventory, source));
    }

    @Inject(method = "extractPatternInputs", at = @At("HEAD"), cancellable = true, remap = false)
    private static void gtShanhai$extractReusablePresenceInputs(IPatternDetails details,
            ICraftingInventory sourceInv, Level level, KeyCounter expectedOutputs,
            KeyCounter expectedContainerItems, CallbackInfoReturnable<KeyCounter[]> cir) {
        if (!VirtualPatternEncodingHelper.containsVirtualProviderPattern(details)) return;
        cir.setReturnValue(VirtualCraftingPatternInputExtractor.extract(
                details, sourceInv, level, expectedOutputs, expectedContainerItems));
    }
}
