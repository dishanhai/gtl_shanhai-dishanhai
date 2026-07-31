package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.world.item.ItemStack;

public interface RecipeTypePatternSlotAccess {

    String gtShanhai$getPatternRecipeTypeId(int slot);

    GTRecipe gtShanhai$getPatternRecipe(int slot);

    default GTRecipe gtShanhai$getPatternRecipe(int slot, GenericStack[] availableCatalystInputs) {
        return gtShanhai$getPatternRecipe(slot);
    }

    default GenericStack[] gtShanhai$getPatternInferenceInputs() {
        return new GenericStack[0];
    }

    default long gtShanhai$getPatternInferenceFingerprint(int slot, long sharedFingerprint) {
        return sharedFingerprint;
    }

    boolean gtShanhai$slotAllowsRecipe(int slot, GTRecipe recipe);

    int gtShanhai$getPatternSlotCount();

    ItemStack gtShanhai$getPatternStack(int slot);

    /**
     * 虚拟供料桥接：InternalSlot 是 GTLCore 的 protected 内部类型，非子类只能反射访问；
     * 但宿主子类（星律机器）类体内部访问完全合法。下面三个方法只用公开类型做参数/返回值，
     * 由星律机器直接实现，让 {@code topUpVirtualSupply} 热路径绕开反射；
     * 非本模组的通用/超级样板总成不实现（default 返回不可用），调用方保留反射兜底。
     * 返回 null / false 表示槽位越界或桥接不可用。
     */
    default Object2LongMap<AEItemKey> gtShanhai$getSlotItemInventory(int slot) {
        return null;
    }

    default Object2LongMap<AEFluidKey> gtShanhai$getSlotFluidInventory(int slot) {
        return null;
    }

    default boolean gtShanhai$addToSlot(int slot, AEKey key, long amount) {
        return false;
    }
}
