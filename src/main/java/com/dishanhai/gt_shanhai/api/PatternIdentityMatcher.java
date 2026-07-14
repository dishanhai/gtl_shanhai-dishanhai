package com.dishanhai.gt_shanhai.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import java.util.HashSet;
import java.util.Set;

/**
 * 样板身份匹配器（星律样板总成防串配方核心）。
 *
 * <p>GTLCore 样板总成的宿主匹配是「库存驱动」：只要某个样板槽的库存物品能凑出某配方的输入，
 * 就把该配方绑到该槽执行，完全不校验「这个槽的样板本身是不是这个配方」。当同一配方类型下存在
 * 重复输入时（如 A: 橡木+水→白桦木，B: 橡木+白桦木→钻石），单发 A 也可能被拿去执行 B。
 *
 * <p>本类提供样板身份校验：给定一个候选 {@link GTRecipe} 与某槽的样板 {@link IPatternDetails}，
 * 判断该配方的非电路输入是否都被样板输入覆盖，且配方主产物落在样板输出中。两者都满足才允许该配方
 * 在该槽执行，从而把匹配从「库存驱动」纠正为「样板身份驱动」。
 *
 * <p>指纹使用 {@link Item}/{@link Fluid} 作为 key（忽略数量与 NBT）。
 *
 * <p><b>与 GTLCore 样板裁剪规则对齐（关键）</b>：{@code slot2PatternMap} 里的样板身份是
 * {@code MEBufferPatternHelper.createPatternWithoutCircuit} 加工后的结果，与原始 GT 配方存在两处系统性差异：
 * <ul>
 *   <li><b>编程电路被剥离</b>：样板输入不含编程电路，而配方输入含电路那一路（{@code IntCircuitIngredient}）。
 *       故输入比对时<b>跳过编程电路</b>，只要求非电路输入被样板输入覆盖。</li>
 *   <li><b>副产物被剥离</b>：默认 {@code keepByProduct=false} 时样板只保留主产物，而配方含全部产物。
 *       故输出比对<b>只校验主产物</b>（配方首个物品 / 流体输出）是否在样板输出中，不要求副产物被覆盖。</li>
 * </ul>
 * 若不对齐这两点，几乎所有带电路 / 多输出的 GT 配方都会被误判为身份不符而无法执行。
 *
 * <p>Rhino 无关：这是纯 Java 侧代码，由 Mixin 调用。
 */
public final class PatternIdentityMatcher {

    private PatternIdentityMatcher() {
    }

    /**
     * 判断配方是否与样板身份一致（输入指纹与输出指纹均为样板对应指纹的子集）。
     *
     * @param recipe  候选配方
     * @param pattern 目标槽当前样板
     * @return true 表示该配方允许在该样板槽执行
     */
    public static boolean matches(GTRecipe recipe, IPatternDetails pattern) {
        if (recipe == null || pattern == null) {
            return false;
        }
        return matchesOutputs(recipe, pattern) && matchesInputs(recipe, pattern);
    }

    // ---------------------------------------------------------------------
    // 输出指纹：配方「主产物」∈ 样板输出
    //
    // GTLCore 默认（keepByProduct=false）样板只保留主产物，副产物被剥离，
    // 故这里只要求配方的主产物（首个物品输出 / 首个流体输出）落在样板输出集合中。
    // ---------------------------------------------------------------------

    private static boolean matchesOutputs(GTRecipe recipe, IPatternDetails pattern) {
        Set<Item> patternItems = new HashSet<>();
        Set<Fluid> patternFluids = new HashSet<>();
        collectPatternStacks(pattern.getOutputs(), patternItems, patternFluids);

        return primaryItemOutputMatches(recipe.getOutputContents(ItemRecipeCapability.CAP), patternItems)
                && primaryFluidOutputMatches(recipe.getOutputContents(FluidRecipeCapability.CAP), patternFluids);
    }

    /**
     * 校验配方首个物品输出是否被样板输出覆盖。配方无物品输出时视为通过（可能是纯流体产出配方）。
     */
    private static boolean primaryItemOutputMatches(java.util.List<Content> contents, Set<Item> patternItems) {
        if (contents == null || contents.isEmpty()) {
            return true;
        }
        for (Content content : contents) {
            if (content == null) {
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof Ingredient ingredient)) {
                continue;
            }
            // 首个可解析的物品输出即主产物：要求它至少有一个候选落在样板输出中
            return ingredientCoveredByItems(ingredient, patternItems);
        }
        return true;
    }

    /**
     * 校验配方首个流体输出是否被样板输出覆盖。配方无流体输出时视为通过。
     */
    private static boolean primaryFluidOutputMatches(java.util.List<Content> contents, Set<Fluid> patternFluids) {
        if (contents == null || contents.isEmpty()) {
            return true;
        }
        for (Content content : contents) {
            if (content == null) {
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof FluidIngredient fluidIngredient)) {
                continue;
            }
            return fluidIngredientCoveredByFluids(fluidIngredient, patternFluids);
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // 输入指纹：配方输入 ⊆ 样板输入
    // ---------------------------------------------------------------------

    private static boolean matchesInputs(GTRecipe recipe, IPatternDetails pattern) {
        Set<Item> patternItems = new HashSet<>();
        Set<Fluid> patternFluids = new HashSet<>();
        collectPatternInputs(pattern, patternItems, patternFluids);

        return recipeInputItemsCoveredBy(recipe.getInputContents(ItemRecipeCapability.CAP), patternItems)
                && recipeInputFluidsCoveredBy(recipe.getInputContents(FluidRecipeCapability.CAP), patternFluids);
    }

    // ---------------------------------------------------------------------
    // 样板指纹收集
    // ---------------------------------------------------------------------

    private static void collectPatternInputs(IPatternDetails pattern, Set<Item> items, Set<Fluid> fluids) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs == null) {
            return;
        }
        for (IPatternDetails.IInput input : inputs) {
            if (input == null) {
                continue;
            }
            collectPatternStacks(input.getPossibleInputs(), items, fluids);
        }
    }

    private static void collectPatternStacks(GenericStack[] stacks, Set<Item> items, Set<Fluid> fluids) {
        if (stacks == null) {
            return;
        }
        for (GenericStack stack : stacks) {
            if (stack == null) {
                continue;
            }
            AEKey what = stack.what();
            if (what instanceof AEItemKey itemKey) {
                items.add(itemKey.getItem());
            } else if (what instanceof AEFluidKey fluidKey) {
                fluids.add(fluidKey.getFluid());
            }
        }
    }

    // ---------------------------------------------------------------------
    // 配方输入 ⊆ 样板输入 判定
    //
    // 语义：配方的每一路输入，其「候选物品集合」必须与样板输入指纹「有交集」，
    // 即样板至少覆盖该路的一个可选项。全部路都被覆盖才算子集。
    // 例外：编程电路那一路被跳过（GTLCore 样板身份已剥离电路，见类头说明）。
    // ---------------------------------------------------------------------

    private static boolean recipeInputItemsCoveredBy(java.util.List<Content> contents, Set<Item> patternItems) {
        if (contents == null || contents.isEmpty()) {
            return true;
        }
        for (Content content : contents) {
            if (content == null || content.chance <= 0) {
                // chance <= 0 视为非硬性输入，跳过（与 GTLCore 催化剂判定一致）
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof Ingredient ingredient)) {
                continue;
            }
            if (isIntegratedCircuitIngredient(ingredient)) {
                // 编程电路输入：样板身份里已被剥离，不参与比对
                continue;
            }
            if (!ingredientCoveredByItems(ingredient, patternItems)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断某物品输入是否是编程电路（GT 的 IntCircuitIngredient / programmed_circuit）。
     * 只要该 Ingredient 的任一候选物品被识别为集成电路即认定为电路输入。
     */
    private static boolean isIntegratedCircuitIngredient(Ingredient ingredient) {
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0) {
            return false;
        }
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty() && IntCircuitBehaviour.isIntegratedCircuit(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean recipeInputFluidsCoveredBy(java.util.List<Content> contents, Set<Fluid> patternFluids) {
        if (contents == null || contents.isEmpty()) {
            return true;
        }
        for (Content content : contents) {
            if (content == null || content.chance <= 0) {
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof FluidIngredient fluidIngredient)) {
                continue;
            }
            if (!fluidIngredientCoveredByFluids(fluidIngredient, patternFluids)) {
                return false;
            }
        }
        return true;
    }

    private static boolean ingredientCoveredByItems(Ingredient ingredient, Set<Item> patternItems) {
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0) {
            // 空 Ingredient 不构成约束
            return true;
        }
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty() && patternItems.contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private static boolean fluidIngredientCoveredByFluids(FluidIngredient fluidIngredient, Set<Fluid> patternFluids) {
        FluidStack[] stacks = fluidIngredient.getStacks();
        if (stacks == null || stacks.length == 0) {
            return true;
        }
        for (FluidStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                Fluid fluid = stack.getFluid();
                if (fluid != null && fluid != Fluids.EMPTY && patternFluids.contains(fluid)) {
                    return true;
                }
            }
        }
        return false;
    }
}
