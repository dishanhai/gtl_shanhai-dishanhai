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

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

/**
 * 把 GTCEu 配方里"不消耗"（NotConsumable，{@code Content.chance==0}）的输入从"样板总成扣料层
 * 要扣减的 left"里剔除，使 {@code InternalSlot.handleItemInternal}/{@code handleFluidInternal}
 * 不再吞掉催化剂。
 *
 * <p>GTLCore 的样板扣料（{@code MEPatternBufferPartMachineBase$InternalSlot}）只按 needAmount
 * 做差值扣减，完全不识别 GTCEu 的 NotConsumable 语义；真实 GTCEu 机器的
 * {@code ItemRecipeCapability.getMaxParallelRatio} 会把 chance==0 的输入只做"存在性"校验、不随
 * 并行扣减，但那套逻辑在 AE 样板缓冲这一层用不上。此工具补上"扣料层识别 NotConsumable"这一环：
 * 只在真扣料（非 simulate）阶段调用，把 chance==0 的输入从 left 剔除，催化剂就永远保持在场、
 * 不被消耗。匹配（simulate）阶段绝不能调用本类——那会让"催化剂不在场也能匹配"，破坏在场校验。
 *
 * <p>匹配判定：先按引用相等快判（left 的 Ingredient 往往就是配方 Content 里同一个对象），再退回
 * 物品/流体匹配（NotConsumable 输入能 {@code test} 到 left 输入的代表物）。同一配方内一个物品几乎
 * 不会既是消耗输入又是不消耗输入，故此判定足够安全。
 */
public final class PatternNotConsumableFilter {

    private PatternNotConsumableFilter() {
    }

    /**
     * 当前正在扣料/结算的配方（每次样板扣料 {@code meHandleRecipeInner} 进入时由 filter mixin 写入）。
     * 供虚拟目标 strip（在 {@code handleItemInternal}/{@code meHandleRecipeInner} 的 RETURN 里触发、
     * 那些点拿不到 recipe）判断"哪个虚拟目标是不消耗催化剂、执行后不该被剥离"。同一服务器线程串行
     * 执行配方，下次扣料进入时覆盖，无需主动清理；strip 只在配方扣料链路内触发，读到的必是当前配方。
     */
    private static final ThreadLocal<GTRecipe> ACTIVE_RECIPE = new ThreadLocal<>();

    public static void setActiveRecipe(GTRecipe recipe) {
        ACTIVE_RECIPE.set(recipe);
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
            return isFluidNotConsumable(recipe, FluidStack.create(fluidKey.getFluid(), 1L));
        }
        return false;
    }

    /** 从 left 中移除配方里 chance==0 的物品输入（仅在非 simulate 扣料阶段调用）。 */
    public static void stripNotConsumableItems(GTRecipe recipe, Object2LongMap<Ingredient> left) {
        if (recipe == null || left == null || left.isEmpty()) return;
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        ObjectIterator<Object2LongMap.Entry<Ingredient>> it = Object2LongMaps.fastIterator(left);
        while (it.hasNext()) {
            Object2LongMap.Entry<Ingredient> entry = it.next();
            if (isNotConsumableItemIngredient(contents, entry.getKey())) {
                it.remove();
            }
        }
    }

    /** 从 left 中移除配方里 chance==0 的流体输入（仅在非 simulate 扣料阶段调用）。 */
    public static void stripNotConsumableFluids(GTRecipe recipe, Object2LongMap<FluidIngredient> left) {
        if (recipe == null || left == null || left.isEmpty()) return;
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) return;
        ObjectIterator<Object2LongMap.Entry<FluidIngredient>> it = Object2LongMaps.fastIterator(left);
        while (it.hasNext()) {
            Object2LongMap.Entry<FluidIngredient> entry = it.next();
            if (isNotConsumableFluidIngredient(contents, entry.getKey())) {
                it.remove();
            }
        }
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

    private static boolean isNotConsumableItemIngredient(List<Content> contents, Ingredient key) {
        if (key == null) return false;
        ItemStack[] items = key.getItems();
        for (Content content : contents) {
            if (content.chance != 0) continue;
            if (!(content.content instanceof Ingredient ncIngredient)) continue;
            if (ncIngredient == key) return true;
            if (items.length > 0 && ncIngredient.test(items[0])) return true;
        }
        return false;
    }

    private static boolean isNotConsumableFluidIngredient(List<Content> contents, FluidIngredient key) {
        if (key == null) return false;
        FluidStack[] stacks = key.getStacks();
        for (Content content : contents) {
            if (content.chance != 0) continue;
            if (!(content.content instanceof FluidIngredient ncIngredient)) continue;
            if (ncIngredient == key) return true;
            if (stacks.length > 0 && ncIngredient.test(stacks[0])) return true;
        }
        return false;
    }
}
