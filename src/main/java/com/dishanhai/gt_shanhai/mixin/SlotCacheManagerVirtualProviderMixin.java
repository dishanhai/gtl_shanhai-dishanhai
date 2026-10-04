package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotState;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.world.item.crafting.Ingredient;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Author: dishanhai. Only virtual presence participates in this simulation fallback.
@Mixin(value = SlotCacheManager.class, remap = false)
public abstract class SlotCacheManagerVirtualProviderMixin {

    @Inject(method = "getBestItemMatchSimulate", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$matchVirtualItem(Ingredient ingredient, Object2LongMap<AEItemKey> inventory,
            Object2LongMap<AEItemKey> catalystInventory, long needAmount, CallbackInfoReturnable<AEItemKey> cir) {
        if (!(inventory instanceof Object2LongOpenHashMap<AEItemKey> internal)) return;
        Object2LongMap<AEItemKey> targets = VirtualPatternBufferSlotState.getVirtualTargets(internal);
        if (targets.isEmpty()) return;
        AEItemKey matched = cir.getReturnValue();
        if (matched != null && matched.matches(ingredient)) return;
        // Do not seed the consumable-match cache with a virtual catalyst.
        cir.setReturnValue(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalystInventory, needAmount, key -> key.matches(ingredient)));
    }

    @Inject(method = "getBestFluidMatchSimulate", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$matchVirtualFluid(FluidIngredient ingredient, Object2LongMap<AEFluidKey> inventory,
            Object2LongMap<AEFluidKey> catalystInventory, long needAmount, CallbackInfoReturnable<AEFluidKey> cir) {
        if (!(inventory instanceof Object2LongOpenHashMap<AEFluidKey> internal)) return;
        Object2LongMap<AEFluidKey> targets = VirtualPatternBufferSlotState.getVirtualTargets(internal);
        if (targets.isEmpty()) return;
        AEFluidKey matched = cir.getReturnValue();
        if (matched != null && AEUtils.testFluidIngredient(ingredient, matched)) return;
        cir.setReturnValue(VirtualPatternBufferSlotState.findMatchingVirtualTarget(
                targets, inventory, catalystInventory, needAmount,
                key -> AEUtils.testFluidIngredient(ingredient, key)));
    }
}
