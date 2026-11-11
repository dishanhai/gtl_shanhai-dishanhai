package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.dishanhai.gt_shanhai.common.item.VirtualCraftingInitialItemExtractor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CraftConfirmMenu.class, priority = 1500, remap = false)
public abstract class CraftConfirmMenuVirtualPresenceMixin {

    @Dynamic("Added by GTLCore CraftConfirmMenuMixin")
    @WrapOperation(method = "gtlcore$tryReserveInventory(Lappeng/api/networking/crafting/ICraftingPlan;)Z",
            at = @At(value = "INVOKE", target = "Lappeng/api/networking/crafting/ICraftingPlan;usedItems()Lappeng/api/stacks/KeyCounter;"),
            remap = false, require = 0)
    private KeyCounter gtShanhai$reserveRealInitialItems(ICraftingPlan plan, Operation<KeyCounter> original) {
        CraftConfirmMenu menu = (CraftConfirmMenu) (Object) this;
        return VirtualCraftingInitialItemExtractor.realInitialItems(plan, original.call(plan), menu.getPlayer().level());
    }
}
