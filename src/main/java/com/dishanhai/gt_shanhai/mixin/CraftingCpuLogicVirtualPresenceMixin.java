package com.dishanhai.gt_shanhai.mixin;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.inv.ListCraftingInventory;

import com.dishanhai.gt_shanhai.common.item.VirtualCraftingPresenceState;

import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCpuLogic.class, remap = false)
public class CraftingCpuLogicVirtualPresenceMixin {

    @Shadow
    @Final
    private ListCraftingInventory inventory;

    @Inject(method = "storeItems", at = @At("HEAD"), remap = false)
    private void gtShanhai$clearExternalPresence(CallbackInfo ci) {
        VirtualCraftingPresenceState.clear(inventory);
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"), remap = false)
    private void gtShanhai$savePresenceState(CompoundTag data, CallbackInfo ci) {
        VirtualCraftingPresenceState.writeToNBT(inventory, data);
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"), remap = false)
    private void gtShanhai$loadPresenceState(CompoundTag data, CallbackInfo ci) {
        VirtualCraftingPresenceState.readFromNBT(inventory, data);
    }
}
