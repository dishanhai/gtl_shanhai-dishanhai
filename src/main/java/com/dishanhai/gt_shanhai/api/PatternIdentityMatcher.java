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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * 强匹配失败时的一行原因。匹配成功返回 null。
     * 只在拒绝路径调用，用来区分主产物、输入物品和输入流体。
     */
    public static String explainMismatch(GTRecipe recipe, IPatternDetails pattern) {
        if (recipe == null || pattern == null) {
            return "配方或样板为空";
        }
        if (matches(recipe, pattern)) {
            return null;
        }
        String output = explainOutputMismatch(recipe, pattern);
        if (output != null) {
            return output;
        }
        String input = explainInputMismatch(recipe, pattern);
        return input == null ? "身份不符但未定位到具体输入或输出" : input;
    }

    // ---------------------------------------------------------------------
    // 输出指纹：配方「主产物」∈ 样板输出
    //
    // GTLCore 默认（keepByProduct=false）只保留跨 capability 的第一个主产物，副产物被剥离。
    // 样板保留物品时只校验物品主产物，保留流体时只校验流体主产物。
    // ---------------------------------------------------------------------

    private static boolean matchesOutputs(GTRecipe recipe, IPatternDetails pattern) {
        Set<Item> patternItems = new HashSet<>();
        Set<Fluid> patternFluids = new HashSet<>();
        collectPatternStacks(pattern.getOutputs(), patternItems, patternFluids);

        boolean itemMatches = !patternItems.isEmpty() && primaryItemOutputMatches(
                recipe.getOutputContents(ItemRecipeCapability.CAP), patternItems);
        boolean fluidMatches = !patternFluids.isEmpty() && primaryFluidOutputMatches(
                recipe.getOutputContents(FluidRecipeCapability.CAP), patternFluids);
        return itemMatches || fluidMatches;
    }

    /**
     * 校验配方首个物品输出是否被样板输出覆盖。配方无物品输出时不匹配物品样板。
     */
    private static boolean primaryItemOutputMatches(java.util.List<Content> contents, Set<Item> patternItems) {
        if (contents == null || contents.isEmpty()) {
            return false;
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
        return false;
    }

    /**
     * 校验配方首个流体输出是否被样板输出覆盖。配方无流体输出时不匹配流体样板。
     */
    private static boolean primaryFluidOutputMatches(java.util.List<Content> contents, Set<Fluid> patternFluids) {
        if (contents == null || contents.isEmpty()) {
            return false;
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
        return false;
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
    // 语义：配方的每一路输入，样板里至少有一个物品能被该路接受。全部路都被覆盖才算子集。
    // 同标签多物品不能只看 getItems()：标签成分常常只缓存一个展示物品（例如寰宇处理器），
    // 样板写的是同标签下另一种物品。Ingredient.test 走实时标签成员判断。
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
        if (patternItemAccepted(ingredient, patternItems)) {
            return true;
        }
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

    /**
     * 样板物品是否被该输入接受。标签成分用 {@link Ingredient#test} 认全体成员，
     * 不要求样板物品等于 {@code getItems()} 里的展示项。
     */
    private static boolean patternItemAccepted(Ingredient ingredient, Set<Item> patternItems) {
        if (ingredient == null || patternItems.isEmpty()) {
            return false;
        }
        for (Item item : patternItems) {
            if (item == null) {
                continue;
            }
            if (ingredient.test(new ItemStack(item))) {
                return true;
            }
        }
        return false;
    }

    private static String explainOutputMismatch(GTRecipe recipe, IPatternDetails pattern) {
        Set<Item> patternItems = new HashSet<>();
        Set<Fluid> patternFluids = new HashSet<>();
        collectPatternStacks(pattern.getOutputs(), patternItems, patternFluids);
        boolean itemMatches = !patternItems.isEmpty() && primaryItemOutputMatches(
                recipe.getOutputContents(ItemRecipeCapability.CAP), patternItems);
        boolean fluidMatches = !patternFluids.isEmpty() && primaryFluidOutputMatches(
                recipe.getOutputContents(FluidRecipeCapability.CAP), patternFluids);
        if (itemMatches || fluidMatches) {
            return null;
        }
        if (patternItems.isEmpty() && patternFluids.isEmpty()) {
            return "样板没有输出";
        }
        if (!patternItems.isEmpty()) {
            return "主产物不在样板输出中 " + describePrimaryItem(
                    recipe.getOutputContents(ItemRecipeCapability.CAP), patternItems);
        }
        return "主流体不在样板输出中 " + describePrimaryFluid(
                recipe.getOutputContents(FluidRecipeCapability.CAP), patternFluids);
    }

    private static String explainInputMismatch(GTRecipe recipe, IPatternDetails pattern) {
        Set<Item> patternItems = new HashSet<>();
        Set<Fluid> patternFluids = new HashSet<>();
        collectPatternInputs(pattern, patternItems, patternFluids);
        String item = firstUncoveredItem(recipe.getInputContents(ItemRecipeCapability.CAP), patternItems);
        if (item != null) {
            return "输入物品未覆盖 " + item;
        }
        String fluid = firstUncoveredFluid(recipe.getInputContents(FluidRecipeCapability.CAP), patternFluids);
        if (fluid != null) {
            return "输入流体未覆盖 " + fluid;
        }
        return null;
    }

    private static String describePrimaryItem(java.util.List<Content> contents, Set<Item> patternItems) {
        if (contents == null || contents.isEmpty()) {
            return "配方没有物品输出 样板=" + formatItems(patternItems, 6);
        }
        for (Content content : contents) {
            if (content == null) {
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof Ingredient ingredient)) {
                return "主产物内容类型=" + contentClass(raw) + " 样板=" + formatItems(patternItems, 6);
            }
            return describeIngredient(ingredient, patternItems);
        }
        return "配方没有可解析的物品输出 样板=" + formatItems(patternItems, 6);
    }

    private static String describePrimaryFluid(java.util.List<Content> contents, Set<Fluid> patternFluids) {
        if (contents == null || contents.isEmpty()) {
            return "配方没有流体输出 样板=" + formatFluids(patternFluids, 6);
        }
        for (Content content : contents) {
            if (content == null) {
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof FluidIngredient fluidIngredient)) {
                return "主流体内容类型=" + contentClass(raw) + " 样板=" + formatFluids(patternFluids, 6);
            }
            return describeFluidIngredient(fluidIngredient, patternFluids);
        }
        return "配方没有可解析的流体输出 样板=" + formatFluids(patternFluids, 6);
    }

    private static String firstUncoveredItem(java.util.List<Content> contents, Set<Item> patternItems) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        int index = 0;
        for (Content content : contents) {
            if (content == null || content.chance <= 0) {
                index++;
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof Ingredient ingredient)) {
                index++;
                continue;
            }
            if (isIntegratedCircuitIngredient(ingredient)) {
                index++;
                continue;
            }
            if (!ingredientCoveredByItems(ingredient, patternItems)) {
                return "#" + index + " " + describeIngredient(ingredient, patternItems);
            }
            index++;
        }
        return null;
    }

    private static String firstUncoveredFluid(java.util.List<Content> contents, Set<Fluid> patternFluids) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        int index = 0;
        for (Content content : contents) {
            if (content == null || content.chance <= 0) {
                index++;
                continue;
            }
            Object raw = content.getContent();
            if (!(raw instanceof FluidIngredient fluidIngredient)) {
                index++;
                continue;
            }
            if (!fluidIngredientCoveredByFluids(fluidIngredient, patternFluids)) {
                return "#" + index + " " + describeFluidIngredient(fluidIngredient, patternFluids);
            }
            index++;
        }
        return null;
    }

    private static String describeIngredient(Ingredient ingredient, Set<Item> patternItems) {
        StringBuilder text = new StringBuilder();
        text.append("class=").append(ingredient.getClass().getSimpleName());
        text.append(" 样板=").append(formatItems(patternItems, 6));
        text.append(" test=").append(formatItemTests(ingredient, patternItems, 6));
        try {
            ItemStack[] stacks = ingredient.getItems();
            int length = stacks == null ? 0 : stacks.length;
            text.append(" getItems=").append(length);
            if (length > 0) {
                text.append(" first=").append(formatStacks(stacks, 4));
            }
        } catch (RuntimeException exception) {
            text.append(" getItems=EX:").append(exception.getClass().getSimpleName());
        }
        return text.toString();
    }

    private static String describeFluidIngredient(FluidIngredient fluidIngredient, Set<Fluid> patternFluids) {
        StringBuilder text = new StringBuilder();
        text.append("class=").append(fluidIngredient.getClass().getSimpleName());
        text.append(" 样板=").append(formatFluids(patternFluids, 6));
        try {
            FluidStack[] stacks = fluidIngredient.getStacks();
            int length = stacks == null ? 0 : stacks.length;
            text.append(" getStacks=").append(length);
            if (length > 0) {
                text.append(" first=").append(formatFluidStacks(stacks, 4));
            }
        } catch (RuntimeException exception) {
            text.append(" getStacks=EX:").append(exception.getClass().getSimpleName());
        }
        return text.toString();
    }

    private static String formatItemTests(Ingredient ingredient, Set<Item> patternItems, int limit) {
        if (patternItems == null || patternItems.isEmpty()) {
            return "[]";
        }
        StringBuilder text = new StringBuilder("[");
        int shown = 0;
        for (Item item : patternItems) {
            if (item == null) {
                continue;
            }
            if (shown >= limit) {
                text.append(", ...");
                break;
            }
            if (shown++ > 0) {
                text.append(", ");
            }
            text.append(itemId(item)).append('=');
            try {
                text.append(ingredient.test(new ItemStack(item)));
            } catch (RuntimeException exception) {
                text.append("EX:").append(exception.getClass().getSimpleName());
            }
        }
        text.append(']');
        return text.toString();
    }

    private static String formatItems(Set<Item> items, int limit) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder text = new StringBuilder("[");
        int shown = 0;
        int extra = 0;
        for (Item item : items) {
            if (item == null) {
                continue;
            }
            if (shown >= limit) {
                extra++;
                continue;
            }
            if (shown++ > 0) {
                text.append(", ");
            }
            text.append(itemId(item));
        }
        if (extra > 0) {
            text.append(", +").append(extra);
        }
        text.append(']');
        return text.toString();
    }

    private static String formatStacks(ItemStack[] stacks, int limit) {
        StringBuilder text = new StringBuilder("[");
        int shown = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (shown >= limit) {
                text.append(", ...");
                break;
            }
            if (shown++ > 0) {
                text.append(", ");
            }
            text.append(itemId(stack.getItem()));
        }
        text.append(']');
        return text.toString();
    }

    private static String formatFluids(Set<Fluid> fluids, int limit) {
        if (fluids == null || fluids.isEmpty()) {
            return "[]";
        }
        StringBuilder text = new StringBuilder("[");
        int shown = 0;
        int extra = 0;
        for (Fluid fluid : fluids) {
            if (fluid == null || fluid == Fluids.EMPTY) {
                continue;
            }
            if (shown >= limit) {
                extra++;
                continue;
            }
            if (shown++ > 0) {
                text.append(", ");
            }
            text.append(fluidId(fluid));
        }
        if (extra > 0) {
            text.append(", +").append(extra);
        }
        text.append(']');
        return text.toString();
    }

    private static String formatFluidStacks(FluidStack[] stacks, int limit) {
        StringBuilder text = new StringBuilder("[");
        int shown = 0;
        for (FluidStack stack : stacks) {
            if (stack == null || stack.isEmpty() || stack.getFluid() == null) {
                continue;
            }
            if (shown >= limit) {
                text.append(", ...");
                break;
            }
            if (shown++ > 0) {
                text.append(", ");
            }
            text.append(fluidId(stack.getFluid()));
        }
        text.append(']');
        return text.toString();
    }

    private static String contentClass(Object raw) {
        return raw == null ? "null" : raw.getClass().getName();
    }

    private static String itemId(Item item) {
        if (item == null) {
            return "null";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? String.valueOf(item) : id.toString();
    }

    private static String fluidId(Fluid fluid) {
        if (fluid == null) {
            return "null";
        }
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
        return id == null ? String.valueOf(fluid) : id.toString();
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
