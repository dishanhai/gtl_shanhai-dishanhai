package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

/** Tracks which virtual catalysts to keep after the active recipe consumes its physical inputs. */
public final class PatternNotConsumableFilter {

    private PatternNotConsumableFilter() {
    }

    /**
     * 由整筆 {@code MEPatternRecipeHandlePart.handleRecipe} 的入口設定，
     * 正常出口清除，供成功結算時判斷哪些虛擬催化劑必須保留。
     */
    private static final ThreadLocal<GTRecipe> ACTIVE_RECIPE = new ThreadLocal<>();

    public static void setActiveRecipe(GTRecipe recipe) {
        ACTIVE_RECIPE.set(recipe);
    }

    public static void clearActiveRecipe() {
        ACTIVE_RECIPE.remove();
    }

    public static boolean isActiveRecipeAuxiliaryIO() {
        return PatternRecipeExecutionGuard.isAuxiliaryIORecipe(ACTIVE_RECIPE.get());
    }

    /**
     * 某个 AEKey 是否是"当前扣料配方"里的不消耗（chance==0）输入。虚拟目标 strip 的 keep 谓词——
     * 返回 true 表示这是催化剂虚拟目标，配方执行后要保留在场（不剥离），支撑同一单的后续执行。
     * 退料（refundSlot）走的是 {@code VirtualPatternBufferSlotState.stripVirtualTargets} 无谓词版，
     * 不经过这里，故催化剂仍会在退料/下单结束时被正常清掉，不会残留。
     */
    public static boolean isKeyNotConsumableForActiveRecipe(AEKey key) {
        GTRecipe recipe = ACTIVE_RECIPE.get();
        if (recipe == null || key == null) return false;
        if (key instanceof AEItemKey itemKey) {
            return isItemNotConsumable(recipe, itemKey.toStack());
        }
        if (key instanceof AEFluidKey fluidKey) {
            return isFluidNotConsumable(recipe, FluidStack.create(fluidKey.getFluid(), 1L, fluidKey.getTag()));
        }
        return false;
    }

    /** 某个物品栈是否对应配方里的 chance==0 输入（供料层"补料判断排除 NotConsumable"用）。 */
    public static boolean isItemNotConsumable(GTRecipe recipe, ItemStack stack) {
        if (recipe == null || stack == null || stack.isEmpty()) return false;
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null) return false;
        for (Content content : contents) {
            if (content.chance != 0) continue;
            if (content.content instanceof Ingredient ncIngredient && ncIngredient.test(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 某个流体栈是否对应配方里的 chance==0 输入（供料层"补料判断排除 NotConsumable"用）。 */
    public static boolean isFluidNotConsumable(GTRecipe recipe, FluidStack stack) {
        if (recipe == null || stack == null || stack.isEmpty()) return false;
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null) return false;
        for (Content content : contents) {
            if (content.chance != 0) continue;
            if (content.content instanceof FluidIngredient ncIngredient && ncIngredient.test(stack)) {
                return true;
            }
        }
        return false;
    }

}
