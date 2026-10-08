package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

import com.dishanhai.gt_shanhai.common.item.VirtualPatternBufferSlotAccess;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GTLCore 1.2.3.2 把催化劑倉裡出現的消耗輸入直接判成配方失敗。
 * 山海虛擬在場會把目標鏡像進催化劑倉，普通樣板總成因此整張配方跑不起來。
 */
@Mixin(targets = "org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine$PatternBufferInternalSlot", remap = false)
public class GTLCorePatternBufferCatalystVirtualGuardMixin {

    @Inject(method = "testCatalystItemInternal", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$presenceIsLegalItemCatalyst(GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        if (!((Object) this instanceof VirtualPatternBufferSlotAccess access)) return;
        if (gtShanhai$itemOverlapIsPresence(recipe, access)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "testCatalystFluidInternal", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$presenceIsLegalFluidCatalyst(GTRecipe recipe, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        if (!((Object) this instanceof VirtualPatternBufferSlotAccess access)) return;
        if (gtShanhai$fluidOverlapIsPresence(recipe, access)) {
            cir.setReturnValue(true);
        }
    }

    private static boolean gtShanhai$itemOverlapIsPresence(GTRecipe recipe, VirtualPatternBufferSlotAccess access) {
        if (recipe == null) return false;
        Object2LongMap<AEItemKey> catalyst = access.gtShanhai$itemCatalystInventory();
        if (catalyst == null) return false;
        for (Content content : recipe.getInputContents(ItemRecipeCapability.CAP)) {
            if (content == null || content.chance <= 0 || !(content.getContent() instanceof Ingredient ingredient)) {
                continue;
            }
            for (ItemStack stack : ingredient.getItems()) {
                if (stack == null || stack.isEmpty()) continue;
                AEItemKey key = AEItemKey.of(stack);
                if (key != null && catalyst.containsKey(key) && !access.gtShanhai$hasVirtualTarget(key)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean gtShanhai$fluidOverlapIsPresence(GTRecipe recipe, VirtualPatternBufferSlotAccess access) {
        if (recipe == null) return false;
        Object2LongMap<AEFluidKey> catalyst = access.gtShanhai$fluidCatalystInventory();
        if (catalyst == null) return false;
        for (Content content : recipe.getInputContents(FluidRecipeCapability.CAP)) {
            if (content == null || content.chance <= 0
                    || !(content.getContent() instanceof FluidIngredient ingredient)) {
                continue;
            }
            for (FluidStack stack : ingredient.getStacks()) {
                if (stack == null || stack.isEmpty()) continue;
                AEFluidKey key = AEFluidKey.of(stack.getFluid());
                if (key != null && catalyst.containsKey(key) && !access.gtShanhai$hasVirtualTarget(key)) {
                    return false;
                }
            }
        }
        return true;
    }
}
